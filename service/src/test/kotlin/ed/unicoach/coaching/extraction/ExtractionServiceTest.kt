package ed.unicoach.coaching.extraction

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.budget.exhaustedBudgetService
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
          "convos, convo_requests, llm_requests, llm_responses, llm_responses_raw, system_prompts, students, users CASCADE",
      )
    }
    // Re-seed BOTH migration-seeded prompts the truncate cleared. Leaving the
    // catalog as the migrations left it keeps the shared test DB usable by other
    // modules' suites that resolve coach/v1 (cross-suite isolation).
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v2', 'call the record_extraction tool')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
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

  private val generousBudget = generousBudgetService(database)

  private val exhaustedBudget = exhaustedBudgetService(database)

  private fun service(provider: ChatProvider): ExtractionService =
    ExtractionService(database, ed.unicoach.coaching.LlmCallLog(provider, database), config, generousBudget)

  /** The same service as [service], but with every student's allowance already spent. */
  private fun exhaustedService(provider: ChatProvider): ExtractionService =
    ExtractionService(database, ed.unicoach.coaching.LlmCallLog(provider, database), config, exhaustedBudget)

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

  private fun completed(
    content: kotlinx.serialization.json.JsonElement,
    usage: TokenUsage,
    model: String = "claude-sonnet-4-6",
  ): ChatEvent.Completed =
    ChatEvent.Completed(
      response =
        ChatResponse(
          content = content,
          modelResolved = model,
          stopReason = "tool_use",
          usage = usage,
          providerRequestId = "req_${UUID.randomUUID()}",
        ),
      rawPayload = content,
    )

  /**
   * The forced-tool content array: a single `tool_use` block whose `input` is
   * [input]. This is the shape a forced `tool_choice` produces (RFC 104), read
   * by `ContentBlocks.toolUseInput`.
   */
  private fun toolUseContent(input: JsonObject): kotlinx.serialization.json.JsonElement =
    kotlinx.serialization.json.JsonArray(
      listOf(
        buildJsonObject {
          put("type", "tool_use")
          put("id", "toolu_${UUID.randomUUID()}")
          put("name", "record_extraction")
          put("input", input)
        },
      ),
    )

  /** Parses [jsonDoc] into the JsonObject the model would have returned as the tool input. */
  private fun toolInput(jsonDoc: String): JsonObject =
    kotlinx.serialization.json.Json
      .parseToJsonElement(jsonDoc) as JsonObject

  /**
   * Returns a Completed terminal whose content is a forced `record_extraction`
   * tool_use block carrying the object parsed from [jsonDoc].
   */
  private inner class JsonProvider(
    override val id: String = "log",
    private val jsonDoc: String,
    private val usage: TokenUsage = TokenUsage(100, 50, 0, 0),
    private val model: String = "claude-sonnet-4-6",
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(completed(toolUseContent(toolInput(jsonDoc)), usage, model)) }
  }

  /**
   * Returns a Completed terminal whose content is a text-only block (no tool_use
   * block) — the model declined to call the forced tool, mapped to `NoToolUse`.
   */
  private inner class NoToolUseProvider(
    override val id: String = "log",
    private val usage: TokenUsage = TokenUsage(11, 22, 0, 0),
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        val content =
          kotlinx.serialization.json.JsonArray(
            listOf(
              buildJsonObject {
                put("type", "text")
                put("text", "I could not do that.")
              },
            ),
          )
        emit(completed(content, usage))
      }
  }

  /** Captures the request (for transcript/tool assertions), then returns a valid empty document. */
  private inner class CapturingProvider(
    override val id: String = "log",
  ) : ChatProvider {
    var captured: ChatRequest? = null

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        captured = request
        emit(completed(toolUseContent(toolInput("""{"observations":[],"claims":[]}""")), TokenUsage(1, 1, 0, 0)))
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
   * a no-tool-use [ChatEvent.Completed], so the calling pass's writeFailedRun
   * observes a watermark already past its target.
   */
  private inner class InterleavingProvider(
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
                put("text", "no tool call")
              },
            ),
          )
        emit(completed(content, TokenUsage(7, 3, 0, 0)))
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

  /** Mints one turn_id from convo_turn_id_seq — the opener of a fresh logical turn. */
  private fun nextTurnId(): Long =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT nextval('convo_turn_id_seq')").use { rs ->
        rs.next()
        rs.getLong(1)
      }
    }

  /**
   * Appends a user turn, returns its convo_requests.id. [utteredDaysAgo]
   * backdates created_at. [turnId] defaults to a freshly minted value (a singleton
   * turn); pass an explicit value to open an excursion whose tool_result
   * continuations share it.
   *
   * By default the turn is CLOSED: an `end_turn` final-answer response is written
   * on the opener row, so the turn is window-eligible (a real distillable turn
   * always has a closing answer). Excursion openers, whose opener row instead
   * bears a `tool_use` response and whose closing answer lands on a later
   * tool_result row, pass [close] = false and append their own responses.
   */
  private fun appendUserTurn(
    convoId: UUID,
    text: String,
    utteredDaysAgo: Long = 0,
    turnId: Long = nextTurnId(),
    close: Boolean = true,
  ): Long {
    val pid = promptId()
    // Since RFC 106 the request I/O envelope lives in the generic llm_requests
    // call log; convo_requests carries only the FK. The projection lifts this
    // turn's user input from the last role='user' message of llm_requests.content,
    // so seed content as a single-user-message messages array.
    val llmRequestId = appendLlmRequest("""[{"role":"user","content":[{"type":"text","text":${quote(text)}}]}]""")
    val requestId =
      connection
        .prepareStatement(
          """
          INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id, created_at)
          VALUES (?, ?, ?, ?, NOW() - (? || ' days')::interval)
          RETURNING id
          """.trimIndent(),
        ).use { stmt ->
          stmt.setObject(1, convoId)
          stmt.setObject(2, pid)
          stmt.setLong(3, llmRequestId)
          stmt.setLong(4, turnId)
          stmt.setString(5, utteredDaysAgo.toString())
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    if (close) appendResponseForLlmRequest(llmRequestId, "end_turn", "(coach answer)")
    return requestId
  }

  /**
   * Appends a tool_result continuation request row (kind='tool_result') stamped
   * with its opener's [turnId] (the excursion shares one turn_id), returns its id.
   */
  private fun appendToolResultTurn(
    convoId: UUID,
    text: String,
    turnId: Long,
  ): Long {
    val pid = promptId()
    val llmRequestId =
      appendLlmRequest("""[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":${quote(text)}}]}]""")
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id, kind)
        VALUES (?, ?, ?, ?, 'tool_result')
        RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, pid)
        stmt.setLong(3, llmRequestId)
        stmt.setLong(4, turnId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getLong("id")
        }
      }
  }

  /** Appends a generic llm_requests row with [contentJson] as its messages array, returns its id. */
  private fun appendLlmRequest(contentJson: String): Long =
    connection
      .prepareStatement(
        """
        INSERT INTO llm_requests (provider, model_requested, system, content, max_tokens)
        VALUES ('anthropic', 'claude-opus-4-8', 'be a good coach', ?::jsonb, 1024)
        RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, contentJson)
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getLong("id")
        }
      }

  /** Reads back the turn_id of a persisted convo_requests row (for threading an excursion). */
  private fun turnIdOf(requestId: Long): Long =
    connection.prepareStatement("SELECT turn_id FROM convo_requests WHERE id = ?").use { stmt ->
      stmt.setLong(1, requestId)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getLong("turn_id")
      }
    }

  /**
   * Appends a completed `llm_responses` row for the convo_requests row [requestId],
   * resolving its `llm_request_id` first (RFC 106 moved the response into the
   * generic call log, reached via convo_requests.llm_request_id).
   */
  private fun appendResponse(
    convoId: UUID,
    requestId: Long,
    stopReason: String,
    text: String,
  ) {
    appendResponseForLlmRequest(llmRequestIdOf(requestId), stopReason, text)
  }

  /** Appends a completed `llm_responses` row directly for an llm_requests id. */
  private fun appendResponseForLlmRequest(
    llmRequestId: Long,
    stopReason: String,
    text: String,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO llm_responses (request_id, outcome, content, model_resolved, stop_reason, latency_ms)
        VALUES (?, 'completed', ?::jsonb, 'm', ?, 0)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setLong(1, llmRequestId)
        stmt.setString(2, """[{"type":"text","text":${quote(text)}}]""")
        stmt.setString(3, stopReason)
        stmt.executeUpdate()
      }
  }

  /** Reads back the llm_request_id a convo_requests row references. */
  private fun llmRequestIdOf(requestId: Long): Long =
    connection.prepareStatement("SELECT llm_request_id FROM convo_requests WHERE id = ?").use { stmt ->
      stmt.setLong(1, requestId)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getLong("llm_request_id")
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
  fun `a Completed with no tool_use block writes a failed run carrying usage and leaves the watermark unchanged`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      // The model returned text instead of calling the forced tool: NoToolUse,
      // mapped to the not_a_json_object category (the payload never arrived).
      val result =
        service(NoToolUseProvider(usage = TokenUsage(11, 22, 0, 0)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      connection.createStatement().use { stmt ->
        stmt
          .executeQuery(
            "SELECT er.outcome, resp.input_tokens, resp.output_tokens, er.failure_category, er.failure_reason " +
              "FROM extraction_runs er JOIN llm_responses resp ON resp.request_id = er.llm_request_id WHERE er.convo_id = '$convo'",
          ).use { rs ->
            rs.next()
            assertEquals("failed", rs.getString("outcome"))
            assertEquals(11, rs.getInt("input_tokens"))
            assertEquals(22, rs.getInt("output_tokens"))
            assertEquals("not_a_json_object", rs.getString("failure_category"))
            assertTrue(rs.getString("failure_reason").isNotBlank(), "failure_reason must be populated")
          }
      }
    }

  @Test
  fun `a bad enum in the tool input is a failed run with invalid_field category`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      // A claim with an out-of-set kind: BadField → invalid_field. The tool input
      // is a valid object, so the failure is content, not shape.
      val doc =
        """
        {"observations":[{"sourceRequestId":$req,"quote":"x"}],
         "claims":[{"op":"new","statement":"s","kind":"bogus","subject":"student",
                    "topic":"academics","origin":"student_stated","supports":[0]}]}
        """.trimIndent()
      val result =
        service(JsonProvider(jsonDoc = doc, usage = TokenUsage(9, 4, 0, 0)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      connection.createStatement().use { stmt ->
        stmt
          .executeQuery("SELECT outcome, failure_category, failure_reason FROM extraction_runs WHERE convo_id = '$convo'")
          .use { rs ->
            rs.next()
            assertEquals("failed", rs.getString("outcome"))
            assertEquals("invalid_field", rs.getString("failure_category"))
            assertTrue(rs.getString("failure_reason").contains("kind"), rs.getString("failure_reason"))
          }
      }
    }

  @Test
  fun `the request carries the record_extraction tool and a forcing tool_choice`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      val provider = CapturingProvider()
      service(provider).extract(ConvoId(convo), ConvoRequestId(req))

      val captured = provider.captured!!
      assertEquals(1, captured.tools.size, "exactly one tool spec")
      assertEquals(
        "record_extraction",
        captured.tools
          .single()["name"]
          ?.jsonPrimitive
          ?.content,
      )
      val toolChoice = captured.toolChoice!!
      assertEquals("tool", toolChoice["type"]?.jsonPrimitive?.content)
      assertEquals("record_extraction", toolChoice["name"]?.jsonPrimitive?.content)
    }

  @Test
  fun `a non-primitive scalar field is a failed run carrying usage, not a thrown exception`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      // sourceRequestId is an object, not a scalar: parseOutput must return a
      // structured Failure (not throw on `.jsonPrimitive`), so the billed call is
      // still recorded as a failed run rather than crashing past writeFailedRun.
      val doc = """{"observations":[{"sourceRequestId":{},"quote":"x"}],"claims":[]}"""
      val result =
        service(JsonProvider(jsonDoc = doc, usage = TokenUsage(13, 24, 0, 0)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      connection.createStatement().use { stmt ->
        stmt
          .executeQuery(
            "SELECT er.outcome, resp.input_tokens, resp.output_tokens, er.failure_category, er.failure_reason " +
              "FROM extraction_runs er JOIN llm_responses resp ON resp.request_id = er.llm_request_id WHERE er.convo_id = '$convo'",
          ).use { rs ->
            rs.next()
            assertEquals("failed", rs.getString("outcome"))
            assertEquals(13, rs.getInt("input_tokens"))
            assertEquals(24, rs.getInt("output_tokens"))
            assertEquals("invalid_field", rs.getString("failure_category"))
            assertTrue(
              rs.getString("failure_reason").contains("sourceRequestId"),
              "failure_reason must name the offending field: ${rs.getString("failure_reason")}",
            )
          }
      }
    }

  @Test
  fun `a present-but-non-array observations container is a failed run, not a zero-result applied run`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "hi")
      // observations is an object, not an array: a lenient cast would treat it as
      // an empty list and record a valid zero-result APPLIED run advancing the
      // watermark. Totality demands a failed run with the watermark left intact.
      val doc = """{"observations":{},"claims":[]}"""
      val result =
        service(JsonProvider(jsonDoc = doc, usage = TokenUsage(5, 6, 0, 0)))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      assertEquals(0, countObservations(student))
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT outcome FROM extraction_runs WHERE convo_id = '$convo'").use { rs ->
          rs.next()
          assertEquals("failed", rs.getString("outcome"))
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
      service(NoToolUseProvider(usage = TokenUsage(30, 0, 0, 0)))
        .extract(ConvoId(convo), ConvoRequestId(req2))

      connection
        .prepareStatement(
          "SELECT COALESCE(SUM(resp.input_tokens),0) FROM extraction_runs er " +
            "JOIN llm_responses resp ON resp.request_id = er.llm_request_id WHERE er.student_id = ?",
        ).use { stmt ->
          stmt.setObject(1, student)
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals(130, rs.getInt(1))
          }
        }

      connection
        .prepareStatement(
          "SELECT outcome, failure_category, failure_reason FROM extraction_runs WHERE student_id = ? ORDER BY id",
        ).use { stmt ->
          stmt.setObject(1, student)
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals("applied", rs.getString("outcome"))
            assertEquals(null, rs.getString("failure_category"))
            rs.next()
            assertEquals("failed", rs.getString("outcome"))
            // No tool_use block: the forced payload never arrived (NoToolUse),
            // mapped to the not_a_json_object category.
            assertEquals("not_a_json_object", rs.getString("failure_category"))
            assertTrue(rs.getString("failure_reason").isNotBlank(), "failure_reason must be populated")
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

      val cappedService =
        ExtractionService(
          database,
          ed.unicoach.coaching.LlmCallLog(JsonProvider(jsonDoc = """{"observations":[],"claims":[]}"""), database),
          cappedWindowConfig,
          generousBudget,
        )

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
        ExtractionService(
          database,
          ed.unicoach.coaching.LlmCallLog(JsonProvider(jsonDoc = newClaimDoc(req, "hi")), database),
          missingPromptConfig,
          generousBudget,
        ).extract(ConvoId(convo), ConvoRequestId(req))
      assertTrue(result is ExtractionResult.TransientFailure, "got $result")
      assertEquals(0L, watermark(convo))
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `transcript over an excursion emits only the user and final-coach lines and advances past it`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      // A full excursion: user -> tool_use response; tool_result request -> end_turn response.
      // The tool_result row shares the opener's turn_id (one logical turn).
      val userReq = appendUserTurn(convo, "find CS colleges", close = false)
      appendResponse(convo, userReq, "tool_use", "(calling search)")
      val toolReq = appendToolResultTurn(convo, "(2 colleges)", turnIdOf(userReq))
      appendResponse(convo, toolReq, "end_turn", "Here are two options.")

      val provider = CapturingProvider()
      // Extract through the continuation request id (the final answer's request).
      val result = service(provider).extract(ConvoId(convo), ConvoRequestId(toolReq))
      assertTrue(result is ExtractionResult.Success, "got $result")

      val transcript = renderTranscript(provider.captured!!.messages.single())
      assertTrue(transcript.contains("[userTurn id=$userReq] find CS colleges"), "user line: $transcript")
      assertTrue(transcript.contains("[coach] Here are two options."), "coach line: $transcript")
      // No tool plumbing: the tool_result request text and the tool_use preamble are absent.
      assertTrue(!transcript.contains("(2 colleges)"), "tool_result text leaked: $transcript")
      assertTrue(!transcript.contains("(calling search)"), "tool_use preamble leaked: $transcript")
      assertTrue(!transcript.contains("id=$toolReq"), "synthetic request id surfaced: $transcript")

      // The watermark advances over the whole excursion in one pass.
      assertEquals(toolReq, watermark(convo))
    }

  @Test
  fun `straddling excursion at the window boundary is never split - kept whole then defers the rest (F1)`() =
    runBlocking {
      // windowMaxTurns = 2 (cappedWindowConfig). A three-row excursion (user + two
      // tool_result) FIRST, then two plain turns. The rows in id order are:
      //   exUser(1) exTool1(2) exTool2(3) plain1(4) plain2(5).
      // The shipped per-ROW cap of 2 kept rows 1..2 = {exUser, exTool1}, advancing
      // the watermark onto exTool1 — INSIDE the excursion — permanently orphaning
      // exTool2 (its opener now below the watermark). That is F1. The whole-turn
      // cap instead keeps the excursion WHOLE plus plain1 (2 whole turns), never
      // bisecting it, and advances the watermark to plain1 (a whole-turn boundary),
      // deferring plain2 to a later pass.
      val student = createStudent()
      val convo = createConvo(student)

      val exTurnId = nextTurnId()
      val exUser = appendUserTurn(convo, "excursion opener", turnId = exTurnId, close = false)
      appendResponse(convo, exUser, "tool_use", "(calling search 1)")
      val exTool1 = appendToolResultTurn(convo, "(result 1)", exTurnId)
      appendResponse(convo, exTool1, "tool_use", "(calling search 2)")
      val exTool2 = appendToolResultTurn(convo, "(result 2)", exTurnId)
      appendResponse(convo, exTool2, "end_turn", "Final excursion answer.")

      val plain1 = appendUserTurn(convo, "plain turn one", close = false)
      appendResponse(convo, plain1, "end_turn", "answer one")
      val plain2 = appendUserTurn(convo, "plain turn two", close = false)
      appendResponse(convo, plain2, "end_turn", "answer two")

      val cappedService =
        ExtractionService(
          database,
          ed.unicoach.coaching.LlmCallLog(JsonProvider(jsonDoc = """{"observations":[],"claims":[]}"""), database),
          cappedWindowConfig,
          generousBudget,
        )

      // First pass: target plain2, cap = 2 whole turns. The excursion is kept whole
      // (not split at exTool1) and plain1 is kept; the watermark lands on plain1
      // (a whole-turn boundary), NEVER on exTool1/exTool2. plain2 defers.
      val first = cappedService.extract(ConvoId(convo), ConvoRequestId(plain2))
      assertTrue(first is ExtractionResult.Success, "got $first")
      assertEquals(plain1, watermark(convo))
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))

      // Second pass picks up the deferred plain2 and advances to it — no orphaned
      // excursion row, no duplicated observation.
      val second = cappedService.extract(ConvoId(convo), ConvoRequestId(plain2))
      assertTrue(second is ExtractionResult.Success, "got $second")
      assertEquals(plain2, watermark(convo))
      assertEquals(2, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `an in-range but still-open excursion at the boundary is deferred and the watermark does not advance`() =
    runBlocking {
      // Structural closure enforcement, independent of the enqueue contract: even
      // when EVERY row of an open excursion is id-contained in (watermark,
      // throughRequestId] — so id-containment alone would admit it — the group is
      // withheld because its tail row is still a mid-excursion tool_use call (no
      // final answer written yet). The watermark must NOT advance into it.
      //
      // Rows in id order: exUser(1, tool_use) exTool1(2, tool_use). Both <= the
      // target (exTool1) and > the watermark (0), so the group is fully
      // id-contained; only the missing closing answer defers it.
      val student = createStudent()
      val convo = createConvo(student)

      val exTurnId = nextTurnId()
      val exUser = appendUserTurn(convo, "open excursion opener", turnId = exTurnId, close = false)
      appendResponse(convo, exUser, "tool_use", "(calling search 1)")
      val exTool1 = appendToolResultTurn(convo, "(result 1)", exTurnId)
      appendResponse(convo, exTool1, "tool_use", "(calling search 2)")

      // Target the excursion's highest-id row: the whole group is id-contained but
      // open (tail stop_reason = tool_use). The pass finds no window-eligible turn
      // and no-ops — the provider must not be called, the watermark stays at 0.
      val provider = TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null))
      val result = service(provider).extract(ConvoId(convo), ConvoRequestId(exTool1))

      assertTrue(result is ExtractionResult.Success, "open excursion should be a no-op success, got $result")
      assertEquals(0L, watermark(convo), "watermark must not advance past the open excursion's opener")
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo))

      // Once the excursion closes (its final answer is written), the same target
      // becomes window-eligible and the watermark advances over the whole turn.
      val exTool2 = appendToolResultTurn(convo, "(result 2)", exTurnId)
      appendResponse(convo, exTool2, "end_turn", "Final excursion answer.")
      val closed =
        service(JsonProvider(jsonDoc = """{"observations":[],"claims":[]}"""))
          .extract(ConvoId(convo), ConvoRequestId(exTool2))
      assertTrue(closed is ExtractionResult.Success, "got $closed")
      assertEquals(exTool2, watermark(convo))
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))
    }

  @Test
  fun `an excursion that fits inside the cap is kept whole in one pass`() =
    runBlocking {
      // windowMaxTurns = 2: one plain turn then a two-row excursion = 2 whole
      // turns, both in range. The excursion is kept whole (never half-included)
      // and the watermark advances over its final-answer row in a single pass.
      val student = createStudent()
      val convo = createConvo(student)
      val plain1 = appendUserTurn(convo, "plain turn one", close = false)
      appendResponse(convo, plain1, "end_turn", "answer one")

      val exTurnId = nextTurnId()
      val exUser = appendUserTurn(convo, "excursion opener", turnId = exTurnId, close = false)
      appendResponse(convo, exUser, "tool_use", "(calling search)")
      val exTool = appendToolResultTurn(convo, "(result)", exTurnId)
      appendResponse(convo, exTool, "end_turn", "Final answer.")

      val cappedService =
        ExtractionService(
          database,
          ed.unicoach.coaching.LlmCallLog(JsonProvider(jsonDoc = """{"observations":[],"claims":[]}"""), database),
          cappedWindowConfig,
          generousBudget,
        )
      val result = cappedService.extract(ConvoId(convo), ConvoRequestId(exTool))
      assertTrue(result is ExtractionResult.Success, "got $result")
      // Both whole turns distilled in one pass; watermark on the excursion's last row.
      assertEquals(exTool, watermark(convo))
      assertEquals(1, countWhere("extraction_runs", "convo_id", convo))
    }

  // ---------------------------------------------------------------------------
  // Budget gate (RFC 109)
  // ---------------------------------------------------------------------------

  @Test
  fun `an exhausted student's pass skips by name, spending nothing and writing no run`() =
    runBlocking {
      val student = createStudent()
      val convo = createConvo(student)
      val req = appendUserTurn(convo, "I want to study computer science")
      val llmRequestsBefore = countAllLlmRequests()

      val result =
        exhaustedService(JsonProvider(jsonDoc = newClaimDoc(req, "I want to study computer science")))
          .extract(ConvoId(convo), ConvoRequestId(req))

      assertTrue(result is ExtractionResult.SkippedBudgetExhausted, "got $result")
      assertEquals(student, result.studentId.value, "the skip names the student it was decided for")
      assertTrue(result.entitlement.exhausted)
      assertEquals(llmRequestsBefore, countAllLlmRequests(), "a skipped pass makes no provider call")
      assertEquals(0, countWhere("extraction_runs", "convo_id", convo), "a pre-LLM skip writes no run row")
      assertEquals(0L, watermark(convo), "the watermark does not advance over turns that were never distilled")
      assertEquals(0, countObservations(student))
    }

  private fun countAllLlmRequests(): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM llm_requests").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun renderTranscript(message: ed.unicoach.chat.ChatMessage): String =
    ed.unicoach.coaching.ConvoContent
      .renderText(message.content)
}
