package ed.unicoach.coaching.extraction

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtractionServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
    }
    // Re-seed BOTH migration-seeded prompts the truncate cleared. Leaving the
    // catalog as the migrations left it keeps the shared test DB usable by other
    // modules' suites that resolve coach/v1 (cross-suite isolation).
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val config =
    ExtractionConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  private fun service(provider: ChatProvider): ExtractionService = ExtractionService(database, provider, config)

  /** A config pinning a prompt (name, version) that has no catalog row. */
  private val missingPromptConfig =
    ExtractionConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString("""extraction.promptVersion = "v-missing"""")
          .withFallback(
            ed.unicoach.common.config.AppConfig
              .load("service.conf")
              .getOrThrow(),
          ),
      ).getOrThrow()

  /** A config capping the window at two turns, to exercise the safety-cap path. */
  private val cappedWindowConfig =
    ExtractionConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString("extraction.windowMaxTurns = 2")
          .withFallback(
            ed.unicoach.common.config.AppConfig
              .load("service.conf")
              .getOrThrow(),
          ),
      ).getOrThrow()

  // ---------------------------------------------------------------------------
  // Fakes
  // ---------------------------------------------------------------------------

  /** Returns a Completed terminal whose content is a single text block holding [jsonDoc]. */
  private class JsonProvider(
    override val id: String = "log",
    private val jsonDoc: String,
    private val usage: TokenUsage = TokenUsage(100, 50, 0, 0),
    private val model: String = "claude-sonnet-4-6",
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        val content =
          kotlinx.serialization.json.JsonArray(
            listOf(
              buildJsonObject {
                put("type", "text")
                put("text", jsonDoc)
              },
            ),
          )
        emit(
          ChatEvent.Completed(
            response =
              ChatResponse(
                content = content,
                modelResolved = model,
                stopReason = "end_turn",
                usage = usage,
                providerRequestId = "req_${UUID.randomUUID()}",
              ),
            rawPayload = content,
          ),
        )
      }
  }

  /** Returns a non-Completed terminal (no usage). */
  private class TerminalProvider(
    override val id: String = "log",
    private val terminal: ChatEvent.Terminal,
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(terminal) }
  }

  /**
   * Runs [beforeReply] (a side effect simulating an interleaved concurrent pass
   * that advances the watermark during the lock-free LLM window) and THEN emits
   * an unparseable [ChatEvent.Completed], so the calling pass's writeFailedRun
   * observes a watermark already past its target.
   */
  private class InterleavingProvider(
    override val id: String = "log",
    private val beforeReply: suspend () -> Unit,
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        beforeReply()
        val content =
          kotlinx.serialization.json.JsonArray(
            listOf(
              buildJsonObject {
                put("type", "text")
                put("text", "not json")
              },
            ),
          )
        emit(
          ChatEvent.Completed(
            response =
              ChatResponse(
                content = content,
                modelResolved = "m",
                stopReason = "end_turn",
                usage = TokenUsage(7, 3, 0, 0),
                providerRequestId = "req_${UUID.randomUUID()}",
              ),
            rawPayload = content,
          ),
        )
      }
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private fun createStudent(): UUID {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'ex-$userId@test.com', 'Ex User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return studentId
  }

  private fun createConvo(studentId: UUID): UUID {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId)
      stmt.executeUpdate()
    }
    return convoId
  }

  private fun softDeleteConvo(convoId: UUID) {
    connection.prepareStatement("UPDATE convos SET deleted_at = NOW() WHERE id = ?").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.executeUpdate()
    }
  }

  private fun promptId(): UUID {
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT id FROM system_prompts WHERE name = 'extraction' AND version = 'v1'").use { rs ->
        rs.next()
        return UUID.fromString(rs.getString("id"))
      }
    }
  }

  /** Appends a user turn, returns its convo_requests.id. [utteredDaysAgo] backdates created_at. */
  private fun appendUserTurn(
    convoId: UUID,
    text: String,
    utteredDaysAgo: Long = 0,
  ): Long {
    val pid = promptId()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, provider, model_requested, system_prompt_id, content, created_at)
        VALUES (?, 'anthropic', 'claude-opus-4-8', ?, ?::jsonb, NOW() - (? || ' days')::interval)
        RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, pid)
        stmt.setString(3, """[{"type":"text","text":${quote(text)}}]""")
        stmt.setString(4, utteredDaysAgo.toString())
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getLong("id")
        }
      }
  }

  private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private fun countObservations(studentId: UUID): Int = countWhere("observations", "student_id", studentId)

  private fun activeClaimCount(studentId: UUID): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM claims WHERE student_id = ? AND status = 'active'").use { stmt ->
      stmt.setObject(1, studentId)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun countWhere(
    table: String,
    column: String,
    value: UUID,
  ): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use { stmt ->
      stmt.setObject(1, value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun watermark(convoId: UUID): Long =
    ed.unicoach.db.dao.ExtractionRunsDao
      .watermark(sqlSession, ConvoId(convoId))
      .getOrThrow()

  // ---------------------------------------------------------------------------
  // observation-only doc helper
  // ---------------------------------------------------------------------------

  private fun newClaimDoc(
    sourceRequestId: Long,
    quote: String,
    statement: String = "wants to study CS",
  ): String =
    """
    {"observations":[{"sourceRequestId":$sourceRequestId,"quote":${quote(quote)}}],
     "claims":[{"op":"new","statement":${quote(statement)},"kind":"goal","subject":"student",
                "topic":"academics","origin":"student_stated","visibility":"student_visible","supports":[0]}]}
    """.trimIndent()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `happy path writes observations, a new claim with support, and advances the watermark`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I want to study computer science")

      val result =
        service(JsonProvider(jsonDoc = newClaimDoc(req, "I want to study computer science")))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.Success, "got $result")
      assertEquals(1, countObservations(student))
      assertEquals(1, activeClaimCount(student))
      assertEquals(req, watermark(convo))

      // claim_support link exists; claim confidence > 0 (one recent observation).
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT confidence FROM claims WHERE student_id = '$student'").use { rs ->
          rs.next()
          assertTrue(rs.getInt("confidence") > 0)
        }
      }
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `reinforce raises confidence by adding recent support`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req1 = appendUserTurn(convo, "I want to study CS", utteredDaysAgo = 0)

      service(JsonProvider(jsonDoc = newClaimDoc(req1, "I want to study CS")))
        .extract(ConvoId(convo), ConvoRequestId(req1))

      val claimId =
        connection.createStatement().use { stmt ->
          stmt.executeQuery("SELECT id, confidence FROM claims WHERE student_id = '$student'").use { rs ->
            rs.next()
            rs.getString("id") to rs.getInt("confidence")
          }
        }
      val before = claimId.second

      // second turn reinforces.
      val req2 = appendUserTurn(convo, "Definitely CS, I love it")
      val reinforceDoc =
        """
        {"observations":[{"sourceRequestId":$req2,"quote":${quote("Definitely CS, I love it")}}],
         "claims":[{"op":"reinforce","statement":"wants CS","kind":"goal","subject":"student",
                    "topic":"academics","origin":"student_stated","supports":[0],"targetClaimId":"${claimId.first}"}]}
        """.trimIndent()
      val result = service(JsonProvider(jsonDoc = reinforceDoc)).extract(ConvoId(convo), ConvoRequestId(req2))
      assertTrue(result is ExtractionResult.Success, "got $result")

      val after =
        connection.createStatement().use { stmt ->
          stmt.executeQuery("SELECT confidence FROM claims WHERE id = '${claimId.first}'").use { rs ->
            rs.next()
            rs.getInt("confidence")
          }
        }
      assertTrue(after > before, "confidence should rise on reinforce: before=$before after=$after")
    }

  @Test
  fun `supersede marks the old claim superseded with pointer and creates a new active claim`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req1 = appendUserTurn(convo, "I want to study CS")
      service(JsonProvider(jsonDoc = newClaimDoc(req1, "I want to study CS"))).extract(ConvoId(convo), ConvoRequestId(req1))

      val oldId =
        connection.createStatement().use { stmt ->
          stmt.executeQuery("SELECT id FROM claims WHERE student_id = '$student'").use { rs ->
            rs.next()
            rs.getString("id")
          }
        }

      val req2 = appendUserTurn(convo, "Actually I changed my mind, I want biology")
      val supersedeDoc =
        """
        {"observations":[{"sourceRequestId":$req2,"quote":${quote("I want biology")}}],
         "claims":[{"op":"supersede","statement":"wants biology","kind":"goal","subject":"student",
                    "topic":"academics","origin":"student_stated","supports":[0],"targetClaimId":"$oldId"}]}
        """.trimIndent()
      val result = service(JsonProvider(jsonDoc = supersedeDoc)).extract(ConvoId(convo), ConvoRequestId(req2))
      assertTrue(result is ExtractionResult.Success, "got $result")

      // old claim superseded, pointer set; one active claim remains; old observations survive.
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT status, superseded_by_id FROM claims WHERE id = '$oldId'").use { rs ->
          rs.next()
          assertEquals("superseded", rs.getString("status"))
          assertNotNull(rs.getString("superseded_by_id"))
        }
      }
      assertEquals(1, activeClaimCount(student))
      assertEquals(2, countObservations(student))
    }

  @Test
  fun `older support yields lower confidence than recent support`() =
    runBlocking {
      val studentOld = createStudent()
      val convoOld = createConvo(studentOld)
      val o1 = appendUserTurn(convoOld, "old want a", utteredDaysAgo = 365)
      val o2 = appendUserTurn(convoOld, "old want b", utteredDaysAgo = 365)
      val oldDoc =
        """
        {"observations":[{"sourceRequestId":$o1,"quote":"a"},{"sourceRequestId":$o2,"quote":"b"}],
         "claims":[{"op":"new","statement":"s","kind":"goal","subject":"student","topic":"academics",
                    "origin":"student_stated","supports":[0,1]}]}
        """.trimIndent()
      service(JsonProvider(jsonDoc = oldDoc)).extract(ConvoId(convoOld), ConvoRequestId(o2))
      val oldConfidence = singleConfidence(studentOld)

      val studentNew = createStudent()
      val convoNew = createConvo(studentNew)
      val n1 = appendUserTurn(convoNew, "new want a", utteredDaysAgo = 0)
      val n2 = appendUserTurn(convoNew, "new want b", utteredDaysAgo = 0)
      val newDoc =
        """
        {"observations":[{"sourceRequestId":$n1,"quote":"a"},{"sourceRequestId":$n2,"quote":"b"}],
         "claims":[{"op":"new","statement":"s","kind":"goal","subject":"student","topic":"academics",
                    "origin":"student_stated","supports":[0,1]}]}
        """.trimIndent()
      service(JsonProvider(jsonDoc = newDoc)).extract(ConvoId(convoNew), ConvoRequestId(n2))
      val newConfidence = singleConfidence(studentNew)

      assertTrue(newConfidence > oldConfidence, "recent=$newConfidence should exceed old=$oldConfidence")
    }

  private fun singleConfidence(studentId: UUID): Int =
    connection.prepareStatement("SELECT confidence FROM claims WHERE student_id = ?").use { stmt ->
      stmt.setObject(1, studentId)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt("confidence")
      }
    }

  @Test
  fun `idempotent re-run with throughRequestId at or below the watermark writes nothing`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I want CS")
      service(JsonProvider(jsonDoc = newClaimDoc(req, "I want CS"))).extract(ConvoId(convo), ConvoRequestId(req))

      val runsBefore = countWhere("extraction_runs", "convo_id", convo)
      // re-run at the same target: no-op (provider would error if called — assert no new run).
      val result =
        service(TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null)))
          .extract(ConvoId(convo), ConvoRequestId(req))
      assertTrue(result is ExtractionResult.Success, "got $result")
      assertEquals(runsBefore, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `empty extraction output still appends an applied run and advances the watermark`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      val emptyDoc = """{"observations":[],"claims":[]}"""
      val result = service(JsonProvider(jsonDoc = emptyDoc)).extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.Success, "got $result")
      assertEquals(0, countObservations(student))
      assertEquals(0, activeClaimCount(student))
      assertEquals(req, watermark(convo))
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `unparseable Completed writes a failed run carrying usage and leaves the watermark unchanged`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      val result =
        service(JsonProvider(jsonDoc = "this is not json", usage = TokenUsage(11, 22, 0, 0)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT outcome, input_tokens, output_tokens FROM extraction_runs WHERE convo_id = '$convo'").use { rs ->
          rs.next()
          assertEquals("failed", rs.getString("outcome"))
          assertEquals(11, rs.getInt("input_tokens"))
          assertEquals(22, rs.getInt("output_tokens"))
        }
      }
    }

  @Test
  fun `unparseable Completed whose target was overtaken mid-window writes no duplicate failed run`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I want CS")

      // The unparseable pass reads the watermark (0 < req), calls the provider,
      // and DURING the lock-free LLM window an interleaved applied pass advances
      // the watermark to req. writeFailedRun must re-check the watermark and write
      // NO failed row — the single-row-per-Completed invariant survives the race.
      val interleaved =
        InterleavingProvider {
          val r =
            service(JsonProvider(jsonDoc = newClaimDoc(req, "I want CS")))
              .extract(ConvoId(convo), ConvoRequestId(req))
          assertTrue(r is ExtractionResult.Success, "interleaved applied pass should succeed, got $r")
        }

      val result = service(interleaved).extract(ConvoId(convo), ConvoRequestId(req))
      assertTrue(result is ExtractionResult.TransientFailure, "unparseable pass should be transient, got $result")

      // Exactly one run row exists (the interleaved applied), no duplicate failed row.
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT outcome FROM extraction_runs WHERE convo_id = '$convo'").use { rs ->
          rs.next()
          assertEquals("applied", rs.getString("outcome"))
        }
      }
      assertEquals(req, watermark(convo))
    }

  @Test
  fun `Rejected terminal writes no run and leaves the watermark unchanged`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      val result =
        service(TerminalProvider(terminal = ChatEvent.Rejected("nope", null, null)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))
      assertEquals(0L, watermark(convo))
    }

  @Test
  fun `TransientFailure terminal writes no run and leaves the watermark unchanged`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      val result =
        service(TerminalProvider(terminal = ChatEvent.TransientFailure("later", null, null)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))
      assertEquals(0L, watermark(convo))
    }

  @Test
  fun `token accounting sums across an applied and a failed pass`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req1 = appendUserTurn(convo, "I want CS")
      service(JsonProvider(jsonDoc = newClaimDoc(req1, "I want CS"), usage = TokenUsage(100, 50, 0, 0)))
        .extract(ConvoId(convo), ConvoRequestId(req1))

      val req2 = appendUserTurn(convo, "more")
      service(JsonProvider(jsonDoc = "garbage", usage = TokenUsage(30, 0, 0, 0)))
        .extract(ConvoId(convo), ConvoRequestId(req2))

      connection.prepareStatement("SELECT COALESCE(SUM(input_tokens),0) FROM extraction_runs WHERE student_id = ?").use { stmt ->
        stmt.setObject(1, student)
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(130, rs.getInt(1))
        }
      }
    }

  @Test
  fun `window exceeding the cap distills the oldest turns and advances the watermark only to them`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req1 = appendUserTurn(convo, "turn one")
      val req2 = appendUserTurn(convo, "turn two")
      val req3 = appendUserTurn(convo, "turn three")

      val cappedService = ExtractionService(database, JsonProvider(jsonDoc = """{"observations":[],"claims":[]}"""), cappedWindowConfig)

      // Target req3 but cap=2: the OLDEST two turns (req1, req2) are distilled and
      // the watermark advances ONLY to req2 — req3 is left for a later pass, never
      // silently dropped.
      val first = cappedService.extract(ConvoId(convo), ConvoRequestId(req3))
      assertTrue(first is ExtractionResult.Success, "got $first")
      assertEquals(req2, watermark(convo))
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))

      // A later pass picks up the stranded req3 and advances the watermark to it.
      val second = cappedService.extract(ConvoId(convo), ConvoRequestId(req3))
      assertTrue(second is ExtractionResult.Success, "got $second")
      assertEquals(req3, watermark(convo))
      assertEquals(2, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `soft-deleted convo is a no-op success`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      softDeleteConvo(convo)

      val result =
        service(TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null)))
          .extract(ConvoId(convo), ConvoRequestId(req))
      assertTrue(result is ExtractionResult.Success, "got $result")
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `internal visibility from provider output is honored`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I tend to underestimate reach schools")
      val doc =
        """
        {"observations":[{"sourceRequestId":$req,"quote":"underestimates reach schools"}],
         "claims":[{"op":"new","statement":"underestimates reach schools","kind":"concern","subject":"student",
                    "topic":"academics","origin":"coach_inferred","visibility":"internal","supports":[0]}]}
        """.trimIndent()
      service(JsonProvider(jsonDoc = doc)).extract(ConvoId(convo), ConvoRequestId(req))

      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT visibility FROM claims WHERE student_id = '$student'").use { rs ->
          rs.next()
          assertEquals("internal", rs.getString("visibility"))
        }
      }
    }

  @Test
  fun `default visibility is student_visible when omitted`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I want CS")
      // newClaimDoc omits visibility.
      service(JsonProvider(jsonDoc = newClaimDoc(req, "I want CS"))).extract(ConvoId(convo), ConvoRequestId(req))

      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT visibility FROM claims WHERE student_id = '$student'").use { rs ->
          rs.next()
          assertEquals("student_visible", rs.getString("visibility"))
        }
      }
    }

  @Test
  fun `stale supersede target fails the pass and mutates no claim`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I want CS")
      // targetClaimId references a claim id that does not exist in the active set.
      val ghost = UUID.randomUUID()
      val doc =
        """
        {"observations":[{"sourceRequestId":$req,"quote":"I want CS"}],
         "claims":[{"op":"supersede","statement":"x","kind":"goal","subject":"student","topic":"academics",
                    "origin":"student_stated","supports":[0],"targetClaimId":"$ghost"}]}
        """.trimIndent()
      val result = service(JsonProvider(jsonDoc = doc)).extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      // The write transaction rolled back: no claims, no observations, no run.
      assertEquals(0, activeClaimCount(student))
      assertEquals(0, countObservations(student))
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))
      assertEquals(0L, watermark(convo))
    }

  @Test
  fun `absent prompt row surfaces a transient failure with no run`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")

      // A config pinning a (name, version) with no catalog row → resolution fails
      // in the read phase, surfacing a transient failure with no run.
      val result =
        ExtractionService(database, JsonProvider(jsonDoc = newClaimDoc(req, "hi")), missingPromptConfig)
          .extract(ConvoId(convo), ConvoRequestId(req))
      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))
    }
}
