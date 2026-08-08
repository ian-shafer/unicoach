package ed.unicoach.coaching.synthesis

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentBlocks
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.exhaustedBudgetService
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimRevision
import ed.unicoach.db.models.ClaimStatus
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.StudentId
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynthesisServiceTest {
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
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "college_list_entries, colleges, convos, convo_requests, llm_requests, llm_responses, llm_responses_raw, " +
          "system_prompts, students, users CASCADE",
      )
      // Restore all migration-seeded prompts for cross-module suites on the shared DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v2', 'call the record_synthesis tool')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val baseConfig =
    ed.unicoach.common.config.AppConfig
      .load("service.conf")
      .getOrThrow()

  private val config = SynthesisConfig.from(baseConfig).getOrThrow()

  private val fixedClock: Clock = Clock.fixed(Instant.parse("2027-01-15T00:00:00Z"), ZoneOffset.UTC)

  private val generousBudget = generousBudgetService(database)

  private val exhaustedBudget = exhaustedBudgetService(database)

  private fun service(
    provider: ChatProvider,
    cfg: SynthesisConfig = config,
    clock: Clock = fixedClock,
    budget: BudgetService = generousBudget,
  ): SynthesisService = SynthesisService(database, ed.unicoach.coaching.LlmCallLog(provider, database), cfg, budget, clock)

  private fun configWith(overrides: String): SynthesisConfig =
    SynthesisConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString(overrides)
          .withFallback(baseConfig),
      ).getOrThrow()

  // ---------------------------------------------------------------------------
  // Fakes
  // ---------------------------------------------------------------------------

  /**
   * The forced-tool content array: a single `record_synthesis` tool_use block
   * whose `input` is [input], the shape a forced `tool_choice` produces (RFC 104).
   */
  private fun toolUseContent(input: JsonObject): kotlinx.serialization.json.JsonElement =
    kotlinx.serialization.json.JsonArray(
      listOf(
        buildJsonObject {
          put("type", "tool_use")
          put("id", "toolu_${UUID.randomUUID()}")
          put("name", "record_synthesis")
          put("input", input)
        },
      ),
    )

  /** Parses [jsonDoc] into the JsonObject the model would have returned as the tool input. */
  private fun toolInput(jsonDoc: String): JsonObject =
    kotlinx.serialization.json.Json
      .parseToJsonElement(jsonDoc) as JsonObject

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
   * Returns a Completed terminal whose content is a forced `record_synthesis`
   * tool_use block carrying the object parsed from [jsonDoc]; captures the request.
   */
  private inner class JsonProvider(
    override val id: String = "log",
    private val jsonDoc: String,
    private val usage: TokenUsage = TokenUsage(100, 50, 0, 0),
    private val model: String = "claude-sonnet-4-6",
  ) : ChatProvider {
    var lastRequest: ChatRequest? = null
    var calls = 0

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        lastRequest = request
        calls++
        emit(completed(toolUseContent(toolInput(jsonDoc)), usage, model))
      }
  }

  /**
   * Returns a Completed terminal whose content is a text-only block (no tool_use
   * block) — the model declined to call the forced tool, mapped to `NoToolUse`.
   */
  private inner class NoToolUseProvider(
    override val id: String = "log",
    private val usage: TokenUsage = TokenUsage(11, 22, 0, 0),
  ) : ChatProvider {
    var calls = 0

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        calls++
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

  /** Returns a non-Completed terminal (no usage); counts calls. */
  private class TerminalProvider(
    override val id: String = "log",
    private val terminal: ChatEvent.Terminal,
  ) : ChatProvider {
    var calls = 0

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        calls++
        emit(terminal)
      }
  }

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sy-$userId@test.com', 'Sy User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun softDeleteStudent(studentId: StudentId) {
    // students is OCC-versioned; go through the DAO so enforce_versioning is satisfied.
    ed.unicoach.db.dao.StudentsDao
      .delete(sqlSession, studentId, currentVersion = 1)
      .getOrThrow()
  }

  private fun createClaim(
    studentId: StudentId,
    statement: String = "wants CS",
  ): ClaimId =
    ClaimsDao
      .create(
        sqlSession,
        NewClaim(
          studentId,
          ClaimOrigin.STUDENT_STATED,
          ClaimKind.GOAL,
          ClaimSubject.STUDENT,
          ClaimTopic.ACADEMICS,
          ClaimVisibility.STUDENT_VISIBLE,
          statement,
        ),
      ).getOrThrow()
      .id

  private fun retractClaim(claimId: ClaimId) {
    ClaimsDao.revise(sqlSession, claimId, ClaimRevision(ClaimStatus.RETRACTED, 0)).getOrThrow()
  }

  private fun createCommitment(
    studentId: StudentId,
    statement: String = "existing intention",
    disclosure: CommitmentDisclosure = CommitmentDisclosure.EXPLICIT,
    lens: CommitmentLens = CommitmentLens.GAP,
  ) = CommitmentsDao.create(sqlSession, NewCommitment(studentId, lens, disclosure, statement)).getOrThrow()

  private fun commitmentRows(studentId: StudentId): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM commitments WHERE student_id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun runRows(studentId: StudentId): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM synthesis_runs WHERE student_id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun gapDoc(supports: List<ClaimId> = emptyList()): String {
    val supportsJson = supports.joinToString(",") { "\"${it.asString}\"" }
    return """
      {"commitments":[
        {"lens":"gap","disclosure":"explicit","statement":"help narrow the college list","supports":[$supportsJson]}
      ]}
      """.trimIndent()
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `happy path creates gap and contradiction commitments with support links and an applied run`() =
    runBlocking {
      val student = createStudent()
      val claim = createClaim(student, "wants a small campus")

      val doc =
        """
        {"commitments":[
          {"lens":"gap","disclosure":"explicit","statement":"they have not discussed finances","supports":[]},
          {"lens":"contradiction","disclosure":"explicit","statement":"small campus vs big-sports preference","supports":["${claim.asString}"]}
        ]}
        """.trimIndent()

      val result = service(JsonProvider(jsonDoc = doc)).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "got $result")

      val commitments = CommitmentsDao.listOpenByStudent(sqlSession, student).getOrThrow()
      assertEquals(2, commitments.size)
      assertEquals(setOf(CommitmentLens.GAP, CommitmentLens.CONTRADICTION), commitments.map { it.lens }.toSet())

      // The contradiction commitment cites the claim; the gap cites nothing.
      val contradiction = commitments.first { it.lens == CommitmentLens.CONTRADICTION }
      assertEquals(
        listOf(claim),
        CommitmentSupportDao.listClaimsForCommitment(sqlSession, contradiction.id).getOrThrow().map { it.id },
      )

      connection
        .prepareStatement(
          "SELECT sr.outcome, sr.commitments_written, resp.input_tokens, resp.output_tokens FROM synthesis_runs sr " +
            "JOIN llm_responses resp ON resp.request_id = sr.llm_request_id WHERE sr.student_id = ?",
        ).use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals("applied", rs.getString("outcome"))
            assertEquals(2, rs.getInt("commitments_written"))
            assertEquals(100, rs.getInt("input_tokens"))
            assertEquals(50, rs.getInt("output_tokens"))
          }
        }
    }

  @Test
  fun `internal commitment is created but excluded from the opener read`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val doc =
        """
        {"commitments":[
          {"lens":"gap","disclosure":"internal","statement":"note: student is anxious about deadlines","supports":[]},
          {"lens":"gap","disclosure":"explicit","statement":"raise the missing activities list","supports":[]}
        ]}
        """.trimIndent()

      service(JsonProvider(jsonDoc = doc)).synthesize(student)

      assertEquals(2, CommitmentsDao.listOpenByStudent(sqlSession, student).getOrThrow().size)
      val explicit = CommitmentsDao.listOpenExplicitByStudent(sqlSession, student).getOrThrow()
      assertEquals(1, explicit.size)
      assertEquals(CommitmentDisclosure.EXPLICIT, explicit.single().disclosure)
    }

  @Test
  fun `timing lens carries the pinned today and persists triggerAt deterministically`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val doc =
        """
        {"commitments":[
          {"lens":"timing","disclosure":"explicit","statement":"ED deadline approaches","triggerAt":"2027-11-01","supports":[]}
        ]}
        """.trimIndent()

      val provider = JsonProvider(jsonDoc = doc)
      service(provider).synthesize(student)

      // The assembled prompt carries the pinned "today".
      val requestText =
        ContentBlocks.renderText(
          provider.lastRequest!!
            .messages
            .single()
            .content,
        )
      assertTrue(requestText.contains("2027-01-15T00:00:00Z"), "prompt must carry pinned today; got:\n$requestText")

      val timing = CommitmentsDao.listOpenByStudent(sqlSession, student).getOrThrow().single()
      assertEquals(CommitmentLens.TIMING, timing.lens)
      assertEquals(Instant.parse("2027-11-01T00:00:00Z"), timing.triggerAt)
    }

  @Test
  fun `freshness gate no-ops when the model is unchanged, then runs after a new claim`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)

      val first = service(JsonProvider(jsonDoc = gapDoc())).synthesize(student)
      assertTrue(first is SynthesisResult.Success)
      assertEquals(1, runRows(student))
      val commitmentsAfterFirst = commitmentRows(student)

      // Second pass with no model change: no LLM call, no new run.
      val provider = JsonProvider(jsonDoc = gapDoc())
      val second = service(provider).synthesize(student)
      assertTrue(second is SynthesisResult.Success)
      assertEquals(0, provider.calls, "provider must not be called when the model is not fresh")
      assertEquals(1, runRows(student))
      assertEquals(commitmentsAfterFirst, commitmentRows(student))

      // A new claim makes the model fresh again: the pass runs and applies.
      createClaim(student, "a new belief")
      val third = service(JsonProvider(jsonDoc = gapDoc())).synthesize(student)
      assertTrue(third is SynthesisResult.Success)
      assertEquals(2, runRows(student))
    }

  @Test
  fun `open-set cap no-ops a student already at maxOpenCommitments`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val cappedConfig = configWith("synthesis.maxOpenCommitments = 2")
      createCommitment(student, "one")
      createCommitment(student, "two")

      val provider = JsonProvider(jsonDoc = gapDoc())
      val result = service(provider, cappedConfig).synthesize(student)
      assertTrue(result is SynthesisResult.Success)
      assertEquals(0, provider.calls, "provider must not be called at the open-set cap")
      assertEquals(0, runRows(student))
      assertEquals(2, commitmentRows(student))
    }

  @Test
  fun `maxNewCommitmentsPerRun persists only the first N proposed`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val cappedConfig = configWith("synthesis.maxNewCommitmentsPerRun = 2")
      val doc =
        """
        {"commitments":[
          {"lens":"gap","disclosure":"explicit","statement":"one","supports":[]},
          {"lens":"gap","disclosure":"explicit","statement":"two","supports":[]},
          {"lens":"gap","disclosure":"explicit","statement":"three","supports":[]}
        ]}
        """.trimIndent()

      service(JsonProvider(jsonDoc = doc), cappedConfig).synthesize(student)
      assertEquals(2, commitmentRows(student))
    }

  @Test
  fun `open commitments are passed to the provider for dedup context`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      createCommitment(student, "MARKER_do_not_restate")

      val provider = JsonProvider(jsonDoc = gapDoc())
      service(provider).synthesize(student)

      val requestText =
        ContentBlocks.renderText(
          provider.lastRequest!!
            .messages
            .single()
            .content,
        )
      assertTrue(requestText.contains("MARKER_do_not_restate"), "open commitments must be in the prompt; got:\n$requestText")
    }

  @Test
  fun `stale-drop drops an open commitment whose only support claim was retracted, leaves an unsupported one open`() =
    runBlocking {
      val student = createStudent()
      val supportedClaim = createClaim(student, "belief to retract")
      val stale = createCommitment(student, "built on a stale belief")
      CommitmentSupportDao.link(sqlSession, stale.id, supportedClaim).getOrThrow()
      val unsupported = createCommitment(student, "whole-model inference, no support")

      // Retract the supporting claim, then make the model fresh via a new claim so the pass runs.
      retractClaim(supportedClaim)
      createClaim(student, "some fresh belief")

      val result = service(JsonProvider(jsonDoc = gapDoc())).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "got $result")

      val staleAfter = CommitmentsDao.findById(sqlSession, stale.id).getOrThrow()
      assertEquals("dropped", staleAfter.status.value)
      assertEquals("stale_basis", staleAfter.dropReason)

      val unsupportedAfter = CommitmentsDao.findById(sqlSession, unsupported.id).getOrThrow()
      assertEquals("open", unsupportedAfter.status.value)

      connection
        .prepareStatement(
          "SELECT commitments_dropped FROM synthesis_runs WHERE student_id = ? AND outcome = 'applied'",
        ).use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals(1, rs.getInt("commitments_dropped"))
          }
        }
    }

  @Test
  fun `a support id inactive at write time is omitted while the commitment is still created`() =
    runBlocking {
      val student = createStudent()
      val activeClaim = createClaim(student, "active belief")
      val retractedClaim = createClaim(student, "retracted belief")
      retractClaim(retractedClaim)

      val doc =
        """
        {"commitments":[
          {"lens":"contradiction","disclosure":"explicit","statement":"cite both","supports":["${activeClaim.asString}","${retractedClaim.asString}"]}
        ]}
        """.trimIndent()

      val result = service(JsonProvider(jsonDoc = doc)).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "got $result")

      val commitment = CommitmentsDao.listOpenByStudent(sqlSession, student).getOrThrow().single()
      // Only the active claim is linked; the retracted one is omitted.
      assertEquals(
        listOf(activeClaim),
        CommitmentSupportDao.listClaimsForCommitment(sqlSession, commitment.id).getOrThrow().map { it.id },
      )
    }

  @Test
  fun `a Completed with no tool_use block writes a failed run carrying usage and does not advance the marker`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      // The model returned text instead of calling the forced tool: NoToolUse,
      // mapped to the not_a_json_object category (the payload never arrived).
      val result =
        service(NoToolUseProvider(usage = TokenUsage(11, 22, 0, 0))).synthesize(student)

      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      assertNull(
        ed.unicoach.db.dao.SynthesisRunsDao
          .lastAppliedAt(sqlSession, student)
          .getOrThrow(),
      )
      connection
        .prepareStatement(
          "SELECT sr.outcome, resp.input_tokens, resp.output_tokens, sr.failure_category, sr.failure_reason FROM synthesis_runs sr " +
            "JOIN llm_responses resp ON resp.request_id = sr.llm_request_id WHERE sr.student_id = ?",
        ).use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
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
      createClaim(student)
      // A commitment with an out-of-set lens: BadField → invalid_field. The tool
      // input is a valid object, so the failure is content, not shape.
      val doc = """{"commitments":[{"lens":"bogus","disclosure":"explicit","statement":"x","supports":[]}]}"""
      val result =
        service(JsonProvider(jsonDoc = doc, usage = TokenUsage(9, 4, 0, 0))).synthesize(student)

      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      connection
        .prepareStatement("SELECT outcome, failure_category, failure_reason FROM synthesis_runs WHERE student_id = ?")
        .use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals("failed", rs.getString("outcome"))
            assertEquals("invalid_field", rs.getString("failure_category"))
            assertTrue(rs.getString("failure_reason").contains("lens"), rs.getString("failure_reason"))
          }
        }
    }

  @Test
  fun `the request carries the record_synthesis tool and a forcing tool_choice`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val provider = JsonProvider(jsonDoc = gapDoc())
      service(provider).synthesize(student)

      val captured = provider.lastRequest!!
      assertEquals(1, captured.tools.size, "exactly one tool spec")
      assertEquals(
        "record_synthesis",
        captured.tools
          .single()["name"]
          ?.jsonPrimitive
          ?.content,
      )
      val toolChoice = captured.toolChoice!!
      assertEquals("tool", toolChoice["type"]?.jsonPrimitive?.content)
      assertEquals("record_synthesis", toolChoice["name"]?.jsonPrimitive?.content)
    }

  @Test
  fun `structurally malformed Completed (non-primitive where a scalar is expected) writes a failed run carrying usage, does not throw`() {
    runBlocking {
      val student = createStudent()
      createClaim(student)

      // `supports` holds an object where a string claim-id is expected. Before the
      // parseOutput totality fix this threw IllegalArgumentException past the
      // writeFailedRun path, dropping the billed token ledger. It must now surface
      // as a FAILED run with the tokens recorded and a TransientFailure result.
      val malformed =
        """
        {"commitments":[
          {"lens":"gap","disclosure":"explicit","statement":"has a nested object in supports","supports":[{"id":"x"}]}
        ]}
        """.trimIndent()

      val result =
        service(JsonProvider(jsonDoc = malformed, usage = TokenUsage(13, 27, 0, 0))).synthesize(student)

      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      assertNull(
        ed.unicoach.db.dao.SynthesisRunsDao
          .lastAppliedAt(sqlSession, student)
          .getOrThrow(),
        "a failed parse must not advance the freshness marker",
      )
      connection
        .prepareStatement(
          "SELECT sr.outcome, resp.input_tokens, resp.output_tokens, sr.failure_category, sr.failure_reason FROM synthesis_runs sr " +
            "JOIN llm_responses resp ON resp.request_id = sr.llm_request_id WHERE sr.student_id = ?",
        ).use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "a failed synthesis_runs row must exist")
            assertEquals("failed", rs.getString("outcome"))
            assertEquals(13, rs.getInt("input_tokens"))
            assertEquals(27, rs.getInt("output_tokens"))
            assertEquals("invalid_field", rs.getString("failure_category"))
            assertTrue(
              rs.getString("failure_reason").contains("supports"),
              "failure_reason must name the offending field: ${rs.getString("failure_reason")}",
            )
            assertTrue(!rs.next(), "exactly one run row")
          }
        }
    }
  }

  @Test
  fun `Completed whose top-level commitments is present but a non-array writes a failed run carrying usage, does not throw`() {
    runBlocking {
      val student = createStudent()
      createClaim(student)

      // Top-level `commitments` is present but a JSON string, not an array. A lenient
      // container cast would silently treat this as an empty array → a zero-commitments
      // APPLIED run (marker advanced). It must instead route to writeFailedRun as a
      // FAILED run with the billed tokens recorded and a TransientFailure result.
      val malformed = """{"commitments":"oops"}"""

      val result =
        service(JsonProvider(jsonDoc = malformed, usage = TokenUsage(17, 23, 0, 0))).synthesize(student)

      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      assertNull(
        ed.unicoach.db.dao.SynthesisRunsDao
          .lastAppliedAt(sqlSession, student)
          .getOrThrow(),
        "a failed parse must not advance the freshness marker",
      )
      connection
        .prepareStatement(
          "SELECT sr.outcome, resp.input_tokens, resp.output_tokens FROM synthesis_runs sr " +
            "JOIN llm_responses resp ON resp.request_id = sr.llm_request_id WHERE sr.student_id = ?",
        ).use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "a failed synthesis_runs row must exist")
            assertEquals("failed", rs.getString("outcome"))
            assertEquals(17, rs.getInt("input_tokens"))
            assertEquals(23, rs.getInt("output_tokens"))
            assertTrue(!rs.next(), "exactly one run row")
          }
        }
    }
  }

  @Test
  fun `nonexistent student is a no-op success with no run and no provider call`() {
    runBlocking {
      // A never-created student id: findById fails NotFoundException. Classified as
      // a fast no-op success, not a retriable failure that would burn all attempts.
      val ghost = StudentId(UUID.randomUUID())
      val provider = TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null))
      val result = service(provider).synthesize(ghost)
      assertTrue(result is SynthesisResult.Success, "got $result")
      assertEquals(0, provider.calls)
      assertEquals(0, runRows(ghost))
    }
  }

  @Test
  fun `Rejected terminal writes no run and returns TransientFailure`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val result = service(TerminalProvider(terminal = ChatEvent.Rejected("nope", null, null))).synthesize(student)
      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      assertEquals(0, runRows(student))
    }

  @Test
  fun `TransientFailure terminal writes no run and returns TransientFailure`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val result = service(TerminalProvider(terminal = ChatEvent.TransientFailure("later", null, null))).synthesize(student)
      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      assertEquals(0, runRows(student))
    }

  @Test
  fun `soft-deleted student is a no-op success with no run`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      softDeleteStudent(student)
      val provider = TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null))
      val result = service(provider).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "got $result")
      assertEquals(0, provider.calls)
      assertEquals(0, runRows(student))
    }

  @Test
  fun `empty active-claim set is a no-op success with no run`() =
    runBlocking {
      val student = createStudent()
      val provider = TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null))
      val result = service(provider).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "got $result")
      assertEquals(0, provider.calls)
      assertEquals(0, runRows(student))
    }

  @Test
  fun `token accounting sums across an applied and an unparseable failed pass`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      service(JsonProvider(jsonDoc = gapDoc(), usage = TokenUsage(100, 50, 0, 0))).synthesize(student)

      // Make the model fresh again and drive a no-tool-use pass.
      createClaim(student, "fresh belief")
      service(NoToolUseProvider(usage = TokenUsage(30, 0, 0, 0))).synthesize(student)

      connection
        .prepareStatement(
          "SELECT COALESCE(SUM(resp.input_tokens),0) FROM synthesis_runs sr " +
            "JOIN llm_responses resp ON resp.request_id = sr.llm_request_id WHERE sr.student_id = ?",
        ).use { stmt ->
          stmt.setObject(1, student.value)
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals(130, rs.getInt(1))
          }
        }

      connection
        .prepareStatement(
          "SELECT outcome, failure_category, failure_reason FROM synthesis_runs WHERE student_id = ? ORDER BY id",
        ).use { stmt ->
          stmt.setObject(1, student.value)
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
  fun `absent prompt row surfaces a transient failure with no run`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val missingPromptConfig = configWith("""synthesis.promptVersion = "v-missing"""")
      val result = service(JsonProvider(jsonDoc = gapDoc()), missingPromptConfig).synthesize(student)
      assertTrue(result is SynthesisResult.TransientFailure, "got $result")
      assertEquals(0, runRows(student))
    }

  @Test
  fun `lost-race no-op - an interleaved applied run overtakes the read-phase snapshot`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)

      // First pass applies, establishing a lastAppliedAt marker.
      service(JsonProvider(jsonDoc = gapDoc())).synthesize(student)
      val runsAfterFirst = runRows(student)

      // Simulate a lost race: a provider whose call, before returning, drives an
      // interleaved applied pass (after a fresh claim) so the write-phase re-check
      // sees a newer marker than this pass's snapshot and no-ops.
      createClaim(student, "fresh belief A")
      val interleaving =
        object : ChatProvider {
          override val id = "log"

          override fun stream(request: ChatRequest): Flow<ChatEvent> =
            flow {
              // An interleaved applied pass runs during this pass's lock-free window.
              createClaim(student, "fresh belief B")
              val r = service(JsonProvider(jsonDoc = gapDoc())).synthesize(student)
              assertTrue(r is SynthesisResult.Success, "interleaved pass should apply, got $r")
              emit(completed(toolUseContent(toolInput(gapDoc())), TokenUsage(1, 1, 0, 0), "m"))
            }
        }

      val result = service(interleaving).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "lost-race pass returns Success, got $result")
      // The interleaved pass added exactly one applied run; the lost-race pass wrote none.
      assertEquals(runsAfterFirst + 1, runRows(student))
    }

  // ---------------------------------------------------------------------------
  // Budget gate (RFC 109)
  // ---------------------------------------------------------------------------

  @Test
  fun `an exhausted student's pass skips by name, spending nothing and writing no run`() =
    runBlocking {
      val student = createStudent()
      createClaim(student, "wants to study CS")
      val llmRequestsBefore = countAllLlmRequests()

      val result =
        service(JsonProvider(jsonDoc = gapDoc()), budget = exhaustedBudget).synthesize(student)

      assertTrue(result is SynthesisResult.SkippedBudgetExhausted, "got $result")
      assertEquals(student, result.studentId, "the skip names the student it was decided for")
      assertTrue(result.entitlement.exhausted)
      assertEquals(llmRequestsBefore, countAllLlmRequests(), "a skipped pass makes no provider call")
      assertEquals(0, runRows(student), "a pre-LLM skip writes no run row")
      assertEquals(
        null,
        ed.unicoach.db.dao.SynthesisRunsDao
          .lastAppliedAt(sqlSession, student)
          .getOrThrow(),
        "the freshness marker is untouched",
      )
    }

  private fun countAllLlmRequests(): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM llm_requests").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
