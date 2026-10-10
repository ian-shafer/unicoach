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
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.CommitmentSupportDao
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.NationalMeritCyclesDao
import ed.unicoach.db.dao.NationalMeritFixture
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.newCollegeFixture
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimRevision
import ed.unicoach.db.models.ClaimStatus
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CommitmentStatus
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.NewNationalMeritCycle
import ed.unicoach.db.models.ShareEventKind
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
      // system_prompts is deliberately NOT truncated: it is the migration-seeded,
      // immutable catalog (RFC 33/0007) that every other module's tests on this
      // shared database read. bin/test re-migrates before every run, so it is
      // already complete; wiping it and hand-restoring a stale list left the seeds
      // partial for whoever ran next (RFC 129).
      stmt.execute(
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "college_list_entries, colleges, share_events, cost_report_shares, convos, convo_requests, " +
          "supporter_invites, supporter_student_links, supporters, " +
          "llm_requests, llm_responses, llm_responses_raw, students, users CASCADE",
      )
    }
    // colleges.state/locale are FKs into the codebook reference tables since
    // migration 0067; seed them here idempotently rather than depending on
    // whichever other suite happens to run first (CostsTestDb precedent).
    CodebookReferenceFixture.seed(sqlSession)
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
      StudentRowFixture.insert(connection, studentId, userId)
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
      assertTrue(result is SynthesisResult.Success, "got [$result]")

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
      assertTrue(result is SynthesisResult.Success, "got [$result]")

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
      assertTrue(result is SynthesisResult.Success, "got [$result]")

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

      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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

      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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

      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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

      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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
      assertTrue(result is SynthesisResult.Success, "got [$result]")
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
      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
      assertEquals(0, runRows(student))
    }

  @Test
  fun `TransientFailure terminal writes no run and returns TransientFailure`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val result = service(TerminalProvider(terminal = ChatEvent.TransientFailure("later", null, null))).synthesize(student)
      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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
      assertTrue(result is SynthesisResult.Success, "got [$result]")
      assertEquals(0, provider.calls)
      assertEquals(0, runRows(student))
    }

  @Test
  fun `empty active-claim set is a no-op success with no run`() =
    runBlocking {
      val student = createStudent()
      val provider = TerminalProvider(terminal = ChatEvent.TransientFailure("should not be called", null, null))
      val result = service(provider).synthesize(student)
      assertTrue(result is SynthesisResult.Success, "got [$result]")
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
      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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

      assertTrue(result is SynthesisResult.SkippedBudgetExhausted, "got [$result]")
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

  // ---------------------------------------------------------------------------
  // The share-nudge step (RFC 160)
  // ---------------------------------------------------------------------------

  // Each fixture college needs a distinct ipeds_unit_id: CollegesDao.upsert
  // keys on it, so a repeat would silently merge two "different" schools into
  // one row. 900000 keeps clear of real IPEDS ids in other fixtures; the
  // nanoTime salt keeps ids unique across test classes sharing one database
  // within a run (this suite truncates colleges, others may not).
  private var ipedsUnitIdCounter = 900000 + (System.nanoTime() % 10000).toInt()

  private fun createCollege(): CollegeId =
    CollegesDao
      .upsert(sqlSession, newCollegeFixture(ipedsUnitId = ipedsUnitIdCounter++, name = "Nudge Test College"))
      .getOrThrow()
      .id

  /** One active list entry; [touchedAt] backdates both stamps so freshness and the list-changed condition are controllable. */
  private fun addListEntry(
    studentId: StudentId,
    touchedAt: Instant? = null,
  ) {
    val collegeId = createCollege()
    if (touchedAt == null) {
      connection
        .prepareStatement("INSERT INTO college_list_entries (student_id, college_id) VALUES (?, ?)")
        .use { stmt ->
          stmt.setObject(1, studentId.value)
          stmt.setObject(2, collegeId.value)
          stmt.executeUpdate()
        }
    } else {
      connection
        .prepareStatement(
          "INSERT INTO college_list_entries (student_id, college_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
        ).use { stmt ->
          stmt.setObject(1, studentId.value)
          stmt.setObject(2, collegeId.value)
          stmt.setTimestamp(3, java.sql.Timestamp.from(touchedAt))
          stmt.setTimestamp(4, java.sql.Timestamp.from(touchedAt))
          stmt.executeUpdate()
        }
    }
  }

  /** A resolved (dropped) share_report commitment whose created_at is [createdAt] — the re-nudge anchor. */
  private fun insertResolvedShareNudge(
    studentId: StudentId,
    createdAt: Instant,
  ) {
    connection
      .prepareStatement(
        "INSERT INTO commitments (student_id, lens, disclosure, statement, status, dropped_at, drop_reason, created_at) " +
          "VALUES (?, 'share_report', 'explicit', 'earlier nudge', 'dropped', NOW(), 'test', ?)",
      ).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setTimestamp(2, java.sql.Timestamp.from(createdAt))
        stmt.executeUpdate()
      }
  }

  private fun insertLiveShare(studentId: StudentId) {
    val id = CostReportSharesDao.nextId(sqlSession).getOrThrow()
    CostReportSharesDao
      .create(sqlSession, NewCostReportShare(id, studentId, TokenHash.fromRawToken("raw-$id")))
      .getOrThrow()
  }

  private fun shareNudges(studentId: StudentId): List<ed.unicoach.db.models.Commitment> =
    CommitmentsDao
      .listByStudent(sqlSession, studentId, limit = 100, offset = 0)
      .getOrThrow()
      .filter { it.lens == CommitmentLens.SHARE_REPORT }

  /** A provider that must never be reached: every nudge-matrix student below has no active claims, so the LLM phases no-op. */
  private fun executeNudgeOnlyPass(
    student: StudentId,
    cfg: SynthesisConfig = config,
    clock: Clock = fixedClock,
  ): SynthesisResult =
    runBlocking {
      val provider = TerminalProvider(terminal = ChatEvent.TransientFailure("LLM must not be called", null, null))
      val result = service(provider, cfg, clock).synthesize(student)
      assertEquals(0, provider.calls, "the nudge step must not need the LLM")
      assertIs<SynthesisResult.Success>(result, "a suppression must be a completed pass declining to insert, not a fault")
      result
    }

  @Test
  fun `an eligible student gets exactly one open explicit share_report commitment with the fixed statement and no supports`() {
    val student = createStudent()
    addListEntry(student)
    addListEntry(student)

    val result = executeNudgeOnlyPass(student)
    assertTrue(result is SynthesisResult.Success, "got [$result]")

    val nudge = shareNudges(student).single()
    assertEquals(CommitmentStatus.OPEN, nudge.status)
    assertEquals(CommitmentDisclosure.EXPLICIT, nudge.disclosure)
    assertEquals(SynthesisService.SHARE_NUDGE_STATEMENT, nudge.statement, "template text, never LLM text")
    assertEquals(
      emptyList(),
      CommitmentSupportDao.listClaimsForCommitment(sqlSession, nudge.id).getOrThrow(),
      "no claims were reasoned over, so no support rows",
    )
    assertEquals(0, runRows(student), "a nudge-only pass writes no synthesis_runs row: runs log LLM work")

    // A second pass the same day is a no-op: the open nudge suppresses.
    assertTrue(executeNudgeOnlyPass(student) is SynthesisResult.Success)
    assertEquals(1, shareNudges(student).size, "a second pass the same day must not double-nudge")
  }

  @Test
  fun `fewer than two active list entries suppresses the nudge`() {
    val zero = createStudent()
    val one = createStudent()
    addListEntry(one)

    executeNudgeOnlyPass(zero)
    executeNudgeOnlyPass(one)

    assertEquals(0, shareNudges(zero).size, "no list, nothing to compare, no nudge")
    assertEquals(0, shareNudges(one).size, "the report is a comparison; two schools make one")
  }

  @Test
  fun `a live share suppresses the nudge`() {
    val student = createStudent()
    addListEntry(student)
    addListEntry(student)
    insertLiveShare(student)

    executeNudgeOnlyPass(student)

    assertEquals(0, shareNudges(student).size, "a student with a live link needs no invitation")
  }

  @Test
  fun `an opted_out share event suppresses the nudge forever`() {
    val student = createStudent()
    addListEntry(student)
    addListEntry(student)
    ShareEventsDao.recordOptOut(sqlSession, student).getOrThrow()

    executeNudgeOnlyPass(student)

    assertEquals(0, shareNudges(student).size, "never ask me again is forever")
  }

  @Test
  fun `the open-set cap suppresses the nudge`() {
    val student = createStudent()
    addListEntry(student)
    addListEntry(student)
    createCommitment(student, "one")
    createCommitment(student, "two")

    executeNudgeOnlyPass(student, configWith("synthesis.maxOpenCommitments = 2"))

    assertEquals(0, shareNudges(student).size, "a saturated open set takes no nudge either")
  }

  @Test
  fun `shareNudgeEnabled = false suppresses the nudge`() {
    val student = createStudent()
    addListEntry(student)
    addListEntry(student)

    executeNudgeOnlyPass(student, configWith("synthesis.shareNudgeEnabled = false"))

    assertEquals(0, shareNudges(student).size, "the rollback knob stops new nudges")
  }

  // All re-nudge anchors hang off the SERVICE's pinned clock, not the wall
  // clock: the cooldown compares commitment created_at against Instant.now(clock).

  @Test
  fun `inside the cooldown no repeat fires even when the list changed since`() {
    // Cooldown not elapsed: the prior nudge is recent, even though the list changed since.
    val now = fixedClock.instant()
    val recent = createStudent()
    insertResolvedShareNudge(recent, now.minus(config.shareNudgeCooldown.minusDays(1)))
    addListEntry(recent)
    addListEntry(recent)

    executeNudgeOnlyPass(recent)

    // The seeded resolved nudge itself is a share_report row; what must NOT appear is a new OPEN one.
    assertEquals(0, shareNudges(recent).count { it.status == CommitmentStatus.OPEN }, "inside the cooldown no repeat fires")
  }

  @Test
  fun `an unchanged list earns no repeat even after the cooldown`() {
    // Cooldown elapsed but the list unchanged since: nothing new to point at.
    val anchor = fixedClock.instant().minus(config.shareNudgeCooldown.plusDays(6))
    val staleList = createStudent()
    addListEntry(staleList, touchedAt = anchor.minus(java.time.Duration.ofDays(10)))
    addListEntry(staleList, touchedAt = anchor.minus(java.time.Duration.ofDays(9)))
    insertResolvedShareNudge(staleList, anchor)

    executeNudgeOnlyPass(staleList)

    assertEquals(0, shareNudges(staleList).count { it.status == CommitmentStatus.OPEN }, "an unchanged list earns no repeat")
  }

  @Test
  fun `a re-nudge fires when the cooldown elapsed AND the list changed since`() {
    val now = fixedClock.instant()
    val anchor = now.minus(config.shareNudgeCooldown.plusDays(6))
    val eligible = createStudent()
    addListEntry(eligible, touchedAt = anchor.minus(java.time.Duration.ofDays(10)))
    insertResolvedShareNudge(eligible, anchor)
    addListEntry(eligible, touchedAt = now)

    executeNudgeOnlyPass(eligible)

    val renudge = shareNudges(eligible)
    assertEquals(1, renudge.count { it.status == CommitmentStatus.OPEN }, "the repeat has something new to point at, so it fires")
  }

  @Test
  fun `a pass whose LLM phases no-op on freshness still writes the nudge`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      addListEntry(student)
      addListEntry(student)

      // First pass with the nudge dark: the LLM applies, advancing the freshness marker.
      val first =
        service(JsonProvider(jsonDoc = gapDoc()), configWith("synthesis.shareNudgeEnabled = false")).synthesize(student)
      assertTrue(first is SynthesisResult.Success, "got [$first]")
      assertEquals(0, shareNudges(student).size)
      assertEquals(1, runRows(student))

      // Second pass, nudge on, model unchanged: the freshness gate no-ops the
      // LLM phases (no provider call, no new run) — the nudge fires anyway.
      val provider = JsonProvider(jsonDoc = gapDoc())
      val second = service(provider).synthesize(student)
      assertTrue(second is SynthesisResult.Success, "got [$second]")
      assertEquals(0, provider.calls, "share eligibility is not a freshness input, by design")
      assertEquals(1, runRows(student), "no new run row: the LLM never ran")
      assertEquals(1, shareNudges(student).size, "the nudge is independent of the freshness gate")
    }

  @Test
  fun `the LLM cannot propose the share_report lens - the schema omits it and the validator rejects it`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val doc = """{"commitments":[{"lens":"share_report","disclosure":"explicit","statement":"x","supports":[]}]}"""
      val provider = JsonProvider(jsonDoc = doc, usage = TokenUsage(9, 4, 0, 0))

      val result = service(provider, configWith("synthesis.shareNudgeEnabled = false")).synthesize(student)

      // The advertised schema never offers the nudge lens: walk the structured
      // schema to the lens enum and assert the exact advertised set.
      val advertisedLenses =
        provider.lastRequest!!
          .tools
          .single()
          .getValue("input_schema")
          .jsonObject
          .getValue("properties")
          .jsonObject
          .getValue("commitments")
          .jsonObject
          .getValue("items")
          .jsonObject
          .getValue("properties")
          .jsonObject
          .getValue("lens")
          .jsonObject
          .getValue("enum")
          .jsonArray
          .map { it.jsonPrimitive.content }
          .toSet()
      assertEquals(
        setOf("gap", "timing", "contradiction"),
        advertisedLenses,
        "the schema offers exactly the LLM-proposable lenses, never the code-only share_report",
      )

      // ...and a model that invents it anyway is rejected like an unknown lens.
      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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
      assertEquals(0, shareNudges(student).size, "the invented lens must never become a commitment")
    }

  // ---------------------------------------------------------------------------
  // RFC 240: the invite-nudge step, beside the share nudge
  // ---------------------------------------------------------------------------

  private fun inviteNudges(studentId: StudentId): List<ed.unicoach.db.models.Commitment> =
    CommitmentsDao
      .listByStudent(sqlSession, studentId, limit = 100, offset = 0)
      .getOrThrow()
      .filter { it.lens == CommitmentLens.INVITE_SUPPORTER }

  /** A user with no student profile — the person a supporter link or an invite is for. */
  private fun createSupporterUser(): ed.unicoach.db.models.UserId {
    val userId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES " +
          "('$userId', 'sup-$userId@test.com', 'Sup User', 'ahash')",
      )
    }
    return ed.unicoach.db.models
      .UserId(userId)
  }

  /** One ACTIVE supporter_student_links row for this student — condition 4. */
  private fun linkSupporter(studentId: StudentId) {
    val supporter =
      SupportersDao
        .create(
          sqlSession,
          ed.unicoach.db.models
            .NewSupporter(createSupporterUser()),
        ).getOrThrow()
    SupporterStudentLinksDao
      .create(
        sqlSession,
        ed.unicoach.db.models
          .NewSupporterStudentLink(supporter.id, studentId, SupporterRelationship.SUPPORTER),
      ).getOrThrow()
  }

  /** One LIVE student-minted supporter_invites row — condition 5. */
  private fun insertLiveInvite(studentId: StudentId) {
    val inviterUserId = studentUserId(studentId)
    SupporterInvitesDao
      .create(
        sqlSession,
        ed.unicoach.db.models.NewSupporterInvite(
          tokenHash = TokenHash.fromRawToken("live-invite-${UUID.randomUUID()}"),
          inviterUserId = inviterUserId,
          inviterRole = ed.unicoach.db.models.InviterRole.STUDENT,
          studentId = studentId,
          supporterId = null,
          relationship = SupporterRelationship.SUPPORTER,
          expiresAt = Instant.now().plus(java.time.Duration.ofDays(14)),
        ),
      ).getOrThrow()
  }

  private fun studentUserId(studentId: StudentId): ed.unicoach.db.models.UserId =
    ed.unicoach.db.dao.StudentsDao
      .findById(sqlSession, studentId)
      .getOrThrow()
      .userId

  /** A resolved (dropped) invite_supporter commitment whose created_at is [createdAt] — the re-offer anchor. */
  private fun insertResolvedInviteNudge(
    studentId: StudentId,
    createdAt: Instant,
  ) {
    connection
      .prepareStatement(
        "INSERT INTO commitments (student_id, lens, disclosure, statement, status, dropped_at, drop_reason, created_at) " +
          "VALUES (?, 'invite_supporter', 'explicit', 'earlier invite nudge', 'dropped', NOW(), 'test', ?)",
      ).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setTimestamp(2, java.sql.Timestamp.from(createdAt))
        stmt.executeUpdate()
      }
  }

  /**
   * THE criterion this slice exists for (brief 0014 D20): one opt-out silences
   * BOTH offers. The student here is otherwise perfectly eligible for the invite
   * nudge — they have a live share, no link, no live invite — so only the
   * opt-out can be what stopped it, and the second door the opt-out was never
   * told about is exactly the defect this asserts against.
   */
  @Test
  fun `an opted_out student with a live share gets no invite nudge`() {
    val student = createStudent()
    insertLiveShare(student)
    ShareEventsDao.recordOptOut(sqlSession, student).getOrThrow()

    executeNudgeOnlyPass(student)

    assertEquals(0, inviteNudges(student).size, "one stop covers both offers")
    assertEquals(0, shareNudges(student).size, "and the first offer stays stopped too")
  }

  @Test
  fun `an eligible student gets exactly one open explicit invite_supporter commitment with the fixed statement and no supports`() {
    val student = createStudent()
    insertLiveShare(student)

    val result = executeNudgeOnlyPass(student)
    assertTrue(result is SynthesisResult.Success, "got [$result]")

    val nudge = inviteNudges(student).single()
    assertEquals(CommitmentStatus.OPEN, nudge.status)
    assertEquals(CommitmentDisclosure.EXPLICIT, nudge.disclosure)
    assertEquals(SynthesisService.INVITE_NUDGE_STATEMENT, nudge.statement, "template text, never LLM text")
    assertEquals(
      emptyList(),
      CommitmentSupportDao.listClaimsForCommitment(sqlSession, nudge.id).getOrThrow(),
      "no claims were reasoned over, so no support rows",
    )
    assertEquals(0, runRows(student), "a nudge-only pass writes no synthesis_runs row: runs log LLM work")

    // A second pass the same day is a no-op: the open nudge suppresses (OPEN_INVITE_NUDGE).
    assertTrue(executeNudgeOnlyPass(student) is SynthesisResult.Success)
    assertEquals(1, inviteNudges(student).size, "a second pass the same day must not double-nudge")
  }

  @Test
  fun `no live share suppresses the invite nudge`() {
    val student = createStudent()

    executeNudgeOnlyPass(student)

    assertEquals(0, inviteNudges(student).size, "the account invite is the SECOND step; the report link is the first")
  }

  @Test
  fun `an active supporter link suppresses the invite nudge`() {
    val student = createStudent()
    insertLiveShare(student)
    linkSupporter(student)

    executeNudgeOnlyPass(student)

    assertEquals(0, inviteNudges(student).size, "a student whose parent is already connected is not asked to connect them")
  }

  @Test
  fun `a live invite suppresses the invite nudge`() {
    val student = createStudent()
    insertLiveShare(student)
    insertLiveInvite(student)

    executeNudgeOnlyPass(student)

    assertEquals(0, inviteNudges(student).size, "a link is already out; the coach does not volunteer a second one")
  }

  @Test
  fun `the open-set cap suppresses the invite nudge`() {
    val student = createStudent()
    insertLiveShare(student)
    createCommitment(student, "one")
    createCommitment(student, "two")

    executeNudgeOnlyPass(student, configWith("synthesis.maxOpenCommitments = 2"))

    assertEquals(0, inviteNudges(student).size, "a saturated open set takes no invite nudge either")
  }

  @Test
  fun `inviteNudgeEnabled = false suppresses the invite nudge`() {
    val student = createStudent()
    insertLiveShare(student)

    executeNudgeOnlyPass(student, configWith("synthesis.inviteNudgeEnabled = false"))

    assertEquals(0, inviteNudges(student).size, "the rollback knob stops new invite nudges")
  }

  @Test
  fun `inside the cooldown no invite re-offer fires`() {
    val now = fixedClock.instant()
    val student = createStudent()
    insertLiveShare(student)
    insertResolvedInviteNudge(student, now.minus(config.inviteNudgeCooldown.minusDays(1)))

    executeNudgeOnlyPass(student)

    // The seeded resolved nudge is itself an invite_supporter row; what must NOT appear is a new OPEN one.
    assertEquals(0, inviteNudges(student).count { it.status == CommitmentStatus.OPEN }, "inside the cooldown no repeat fires")
  }

  /**
   * The re-offer, and the deliberate DIFFERENCE from the share nudge: there is
   * no list-change half. The student whose first invite lapsed untouched is the
   * one who most needs asking again, and a list-change test would suppress
   * exactly them.
   */
  @Test
  fun `after the cooldown an invite re-offer fires with no list change at all`() {
    val now = fixedClock.instant()
    val student = createStudent()
    insertLiveShare(student)
    insertResolvedInviteNudge(student, now.minus(config.inviteNudgeCooldown.plusDays(6)))

    executeNudgeOnlyPass(student)

    assertEquals(1, inviteNudges(student).count { it.status == CommitmentStatus.OPEN }, "a lapsed invite IS the something new")
  }

  /**
   * "Alongside, not instead of", proved as MUTUAL EXCLUSION rather than trusted
   * to the model. The share nudge is suppressed BY a live share and this one
   * REQUIRES one, so one pass can never write both — asserted in both
   * directions, since a predicate that had dropped condition 2 would write both
   * rows for the second student and read as a richer opening.
   */
  @Test
  fun `one pass writes the share nudge or the invite nudge, never both`() {
    val notShared = createStudent()
    addListEntry(notShared)
    addListEntry(notShared)
    val shared = createStudent()
    insertLiveShare(shared)

    executeNudgeOnlyPass(notShared)
    executeNudgeOnlyPass(shared)

    assertEquals(1, shareNudges(notShared).size, "no live share: the report offer is the one that fires")
    assertEquals(0, inviteNudges(notShared).size, "and the account invite does not")
    assertEquals(1, inviteNudges(shared).size, "a live share: the account invite is the one that fires")
    assertEquals(0, shareNudges(shared).size, "and the report offer does not")
  }

  @Test
  fun `the LLM cannot propose the invite_supporter lens`() =
    runBlocking {
      val student = createStudent()
      createClaim(student)
      val doc = """{"commitments":[{"lens":"invite_supporter","disclosure":"explicit","statement":"x","supports":[]}]}"""
      val provider = JsonProvider(jsonDoc = doc, usage = TokenUsage(9, 4, 0, 0))

      val result = service(provider, configWith("synthesis.inviteNudgeEnabled = false")).synthesize(student)

      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
      assertEquals(0, inviteNudges(student).size, "the invented lens must never become a commitment")
    }

  // ---------------------------------------------------------------------------
  // The PSAT/NMSQT window nudge (RFC 243)
  // ---------------------------------------------------------------------------

  /**
   * THE CYCLES ARE THE COMMITTED CORPUS, AND THE CLOCKS ARE DERIVED FROM THEM.
   *
   * `national_merit_cycles` is Route A -- seeded by migration, so `bin/test`'s
   * `db-reset` puts it in every test database -- and `:db`'s own
   * `NationalMeritCycleSeedTest` asserts those rows FROM THE DATABASE. So this
   * suite writes no cycle of its own (an upsert here would overwrite a committed
   * row and fail another module's test) and instead reads the corpus and moves
   * the CLOCK, which is the input the nudge actually turns on.
   *
   * The dated cycle and the described one are both required to exist, because the
   * PAIR is the design: "the competition in progress" and "the competition
   * described and not dated" (brief 0010 D22).
   */
  private val cycles = NationalMeritCyclesDao.list(sqlSession).getOrThrow()

  private val datedCycle =
    cycles.firstOrNull { it.window is ed.unicoach.db.models.CycleWindow.Dated }
      ?: error("the committed national_merit_cycles corpus holds no DATED cycle; db/seed/national-merit/cycles.csv")

  private val describedCycle =
    cycles.firstOrNull { it.window is ed.unicoach.db.models.CycleWindow.Described }
      ?: error("the committed national_merit_cycles corpus holds no DESCRIBED cycle; db/seed/national-merit/cycles.csv")

  private val datedWindow = datedCycle.window as ed.unicoach.db.models.CycleWindow.Dated

  /** A competition year no cycle row exists for, read off the corpus rather than typed. */
  private val uncuratedClass = cycles.maxOf { it.competitionYear } + 7

  /**
   * Two more years no cycle row exists for, for the two cases below that must
   * SEED THEIR OWN -- see [withSeededCycle] for why they cannot borrow a
   * committed one. Read off the corpus for [uncuratedClass]'s reason, and
   * distinct from it and from each other so the three never collide.
   */
  private val undatedCycleYear = cycles.maxOf { it.competitionYear } + 5
  private val expiringCycleYear = cycles.maxOf { it.competitionYear } + 6

  /**
   * Seeds one cycle of this suite's own for the duration of [body] and deletes
   * it after, BY YEAR.
   *
   * TWO GUARDS CANNOT BE REACHED WITH A COMMITTED ROW, and that is a property of
   * the corpus rather than a defect in it: the described 2029 cycle is believed
   * through 2027-07-31, one month into the junior year of the class it belongs
   * to (RFC 186's 1 July turnover), so by the time that class could be nudged at
   * all the row must already have been re-read -- which is exactly what the
   * 90-day refresh guard exists to make true. A case about an undated window, or
   * about expiry and its boundary day, therefore brings its own competition year
   * with dates it chooses, and each guard is isolated from the others.
   *
   * THE YEAR MUST BE ONE NO CORPUS HOLDS, and that is checked here rather than
   * trusted, because the committed corpus can grow: the DAO's write is an
   * UPSERT, so a seed on a committed year would overwrite a committed row (and
   * the deletion afterwards would take it away outright), failing `:db`'s own
   * seed test rather than this one.
   */
  private fun <T> withSeededCycle(
    row: NewNationalMeritCycle,
    body: () -> T,
  ): T {
    require(cycles.none { it.competitionYear == row.competitionYear }) {
      "competition year ${row.competitionYear} is in the committed corpus (db/seed/national-merit/cycles.csv), " +
        "and a suite that seeds it would overwrite the row another module asserts"
    }
    return try {
      NationalMeritFixture.seedCycle(sqlSession, row)
      body()
    } finally {
      connection.prepareStatement("DELETE FROM national_merit_cycles WHERE competition_year = ?").use { stmt ->
        stmt.setInt(1, row.competitionYear)
        stmt.executeUpdate()
      }
    }
  }

  /** Noon UTC on [day] -- an INSTANT, because the step applies `COACH_ZONE` itself. */
  private fun clockOn(day: LocalDate): Clock = Clock.fixed(day.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC), ZoneOffset.UTC)

  /** Inside the lead window and before the sitting: the one moment this nudge exists for. */
  private val windowNearClock: Clock = clockOn(datedWindow.opensOn.minusDays(5))

  private fun psatNudges(studentId: StudentId): List<ed.unicoach.db.models.Commitment> =
    CommitmentsDao
      .listByStudent(sqlSession, studentId, limit = 100, offset = 0)
      .getOrThrow()
      .filter { it.lens == CommitmentLens.PSAT_WINDOW }

  /** A student whose stored class is [graduationYear] -- the year of school IS the subject of every test below. */
  private fun createStudentInClass(graduationYear: Int): StudentId =
    createStudent().also { student ->
      StudentRowFixture.updateGraduation(connection, student.value, StudentRowFixture.graduationYear(graduationYear))
    }

  /** A dropped `psat_window` commitment -- the ALREADY_RAISED predicate's own input, in a status that is not open. */
  private fun insertDroppedPsatNudge(studentId: StudentId) {
    connection
      .prepareStatement(
        "INSERT INTO commitments (student_id, lens, disclosure, statement, status, dropped_at, drop_reason) " +
          "VALUES (?, 'psat_window', 'explicit', 'raised once already', 'dropped', NOW(), 'test')",
      ).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.executeUpdate()
      }
  }

  /**
   * THE VERDICT ITSELF, which is what "one test per suppression reason, BY NAME"
   * asks for.
   *
   * Every suppression writes nothing, so a row count cannot tell one reason from
   * another -- and three of these cases were SHADOWED: the guard could be
   * deleted and the suite stayed green. No clock or fixture can un-shadow
   * NOT_QUALIFYING_YEAR, because the year of school is DERIVED from her class
   * and today, so for the committed corpus the student who isolates it does not
   * exist. The verdict is `internal` for exactly this: Kotlin associates `test`
   * with `main`, so the suite reads the decision the step actually made.
   */
  private fun verdictFor(
    studentId: StudentId,
    clock: Clock,
    cfg: SynthesisConfig = config,
  ): SynthesisService.PsatWindowNudgeVerdict {
    val student =
      ed.unicoach.db.dao.StudentsDao
        .findById(sqlSession, studentId, ed.unicoach.db.models.SoftDeleteScope.ACTIVE)
        .getOrThrow()
    return service(TerminalProvider(terminal = ChatEvent.TransientFailure("LLM must not be called", null, null)), cfg, clock)
      .computePsatWindowNudgeVerdict(sqlSession, student)
  }

  /** The suppression this student's pass decided on, by name. */
  private fun suppressionFor(
    studentId: StudentId,
    clock: Clock,
    cfg: SynthesisConfig = config,
  ): SynthesisService.PsatWindowNudgeSuppression =
    assertIs<SynthesisService.PsatWindowNudgeVerdict.Suppressed>(verdictFor(studentId, clock, cfg)).reason

  /**
   * THE MOMENT THE SLICE'S ONE PROACTIVE STEP EXISTS FOR: a junior whose one
   * qualifying sitting is days away gets one open explicit commitment carrying the
   * FIXED statement, and the next day's sweep adds nothing.
   *
   * It runs through [executeNudgeOnlyPass], which asserts the provider is never
   * called -- so this also proves the step runs BEFORE every LLM-phase gate: these
   * students have no active claims at all, and the read phase no-ops on that.
   */
  @Test
  fun `a junior inside the lead window gets exactly one open explicit psat_window commitment, and the next day adds nothing`() {
    val student = createStudentInClass(datedCycle.competitionYear)

    // ASKED BEFORE THE PASS, because the pass WRITES the row this verdict is
    // about: put to the step a second time afterwards, the honest answer is
    // ALREADY_RAISED, which is the repeat rule below and not this assertion.
    assertEquals(
      SynthesisService.PsatWindowNudgeVerdict.Eligible,
      verdictFor(student, windowNearClock),
      "the step says so by name: this is the one moment it fires for",
    )

    executeNudgeOnlyPass(student, clock = windowNearClock)

    val nudge = psatNudges(student).single()
    assertEquals(CommitmentStatus.OPEN, nudge.status)
    assertEquals(CommitmentDisclosure.EXPLICIT, nudge.disclosure)
    assertEquals(SynthesisService.PSAT_WINDOW_NUDGE_STATEMENT, nudge.statement, "template text, never LLM text")
    assertEquals(
      emptyList(),
      CommitmentSupportDao.listClaimsForCommitment(sqlSession, nudge.id).getOrThrow(),
      "no claims were reasoned over, so no support rows",
    )
    assertEquals(0, runRows(student), "a nudge-only pass writes no synthesis_runs row: runs log LLM work")

    executeNudgeOnlyPass(student, clock = clockOn(datedWindow.opensOn.minusDays(4)))
    assertEquals(1, psatNudges(student).size, "the next day's sweep must not raise the one sitting of her life twice")
  }

  /** DISABLED: the rollback knob stops new nudges, and every reactive answer about the PSAT still comes from the tool. */
  @Test
  fun `psatWindowNudgeEnabled = false suppresses the nudge`() {
    val student = createStudentInClass(datedCycle.competitionYear)

    val cfg = configWith("synthesis.psatWindowNudgeEnabled = false")
    executeNudgeOnlyPass(student, cfg, windowNearClock)

    assertEquals(0, psatNudges(student).size, "the rollback knob stops new nudges")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.DISABLED,
      suppressionFor(student, windowNearClock, cfg),
      "and the knob is the reason, reached before any read",
    )
  }

  /**
   * NOT_QUALIFYING_YEAR: a sophomore on the same clock, whose own cycle row
   * EXISTS -- so the only reason left is the year of school. Only the one
   * qualifying sitting counts, and hers is a year away.
   *
   * THE REASON IS ASSERTED, not the absence of a row. Her own cycle is the
   * DESCRIBED one, which carries no dates, so with this guard deleted the pass
   * falls through to CYCLE_UNDATED and writes nothing either -- the row count is
   * identical and the guard is invisible to it.
   */
  @Test
  fun `a student who is not in the qualifying year of school gets no nudge, and the year of school is the reason`() {
    val sophomore = createStudentInClass(describedCycle.competitionYear)

    executeNudgeOnlyPass(sophomore, clock = windowNearClock)

    assertEquals(0, psatNudges(sophomore).size, "a sitting in any other year does not enter the competition at all")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.NOT_QUALIFYING_YEAR,
      suppressionFor(sophomore, windowNearClock),
      "the year of school is the reason, and not the undated window behind it",
    )
  }

  /**
   * NOT_QUALIFYING_YEAR FROM THE OTHER SIDE, and the half a sophomore cannot
   * prove: a SENIOR of the dated cycle's own class, a year after the window she
   * was a junior for. Her cycle row exists and IS dated, and the window is behind
   * her -- so both NOT_QUALIFYING_YEAR and WINDOW_PASSED are true of her, and the
   * step must reach the year of school FIRST. Her one qualifying sitting was last
   * autumn; a "the window is open" nudge now would be about a competition she can
   * no longer enter by sitting the test.
   */
  @Test
  fun `a senior past her own cycle gets no nudge, and the year of school is the reason reached first`() {
    val senior = createStudentInClass(datedCycle.competitionYear)

    // A year on from the sitting: the class of [competitionYear] is grade 12 in the
    // academic year that ENDS in that year (RFC 186's 1 July turnover).
    val aYearOn = clockOn(datedWindow.closesOn.plusYears(1))
    executeNudgeOnlyPass(senior, clock = aYearOn)

    assertEquals(0, psatNudges(senior).size, "the one sitting that counted is behind her, so this window is not hers")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.NOT_QUALIFYING_YEAR,
      suppressionFor(senior, aYearOn),
      "the year of school is the reason REACHED FIRST, which is what this case's name claims and nothing " +
        "asserted until now: WINDOW_PASSED is equally true of her",
    )
  }

  /**
   * NO_CYCLE_ROW: we hold no cycle for her class, so there is no window to call
   * near and no date to name.
   *
   * AND IT IS THE REASON REACHED FIRST, which is the order claim RFC 243 amended
   * and nothing asserted: she is also not in the qualifying year of school on
   * this clock, so the step must name the missing ROW rather than her year.
   */
  @Test
  fun `a student whose class has no cycle row gets no nudge, and the missing row is the reason reached first`() {
    val student = createStudentInClass(uncuratedClass)

    executeNudgeOnlyPass(student, clock = windowNearClock)

    assertEquals(0, psatNudges(student).size, "no row, no dates, nothing to raise")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.NO_CYCLE_ROW,
      suppressionFor(student, windowNearClock),
      "the row we do not hold is the reason, and the qualifying grade is the ROW's -- so there is no grade to " +
        "judge her against until it is read",
    )
  }

  /**
   * CYCLE_EXPIRED: she IS in the qualifying year and we have stopped believing
   * our reading of her cycle (`expires_on`), so no window from it may drive an
   * unprompted message about a date.
   *
   * REACHED AFTER THE YEAR OF SCHOOL AND BEFORE THE WINDOW, which the two clocks
   * here are chosen to show: one day apart, with her year of school identical on
   * both, so the only thing that moves is whether the row is still believed. A
   * reading we no longer believe is no better than a row we never had, and the
   * reactive side refuses the same row the same way
   * ([ed.unicoach.coaching.testing.CycleTiming.CycleExpired]).
   *
   * ITS OWN CYCLE, for [withSeededCycle]'s reason: this needs a row whose expiry
   * falls INSIDE the junior year of the class it belongs to, and no committed row
   * is shaped that way -- a corpus whose rows outlived their students' sittings
   * would be a corpus nobody re-read in time.
   */
  @Test
  fun `a cycle past its own expires_on raises nothing, and the stale reading is the reason`() {
    val cycle =
      NationalMeritFixture.cycle(
        competitionYear = expiringCycleYear,
        window =
          ed.unicoach.db.models.CycleWindow
            .Dated(
              opensOn = LocalDate.of(expiringCycleYear - 2, 10, 1),
              closesOn = LocalDate.of(expiringCycleYear - 2, 10, 30),
              asPublished = "The test is given in school in October of the junior year.",
            ),
        alternateEntry = null,
        checkedOn = LocalDate.of(expiringCycleYear - 2, 9, 1),
        // The December of her junior year: her sitting has been and gone and she
        // is still in grade 11, so the clock crosses the expiry and nothing else.
        expiresOn = LocalDate.of(expiringCycleYear - 2, 12, 1),
      )
    val student = createStudentInClass(cycle.competitionYear)
    val onExpiry = clockOn(cycle.expiresOn)
    val pastExpiry = clockOn(cycle.expiresOn.plusDays(1))

    withSeededCycle(cycle) {
      executeNudgeOnlyPass(student, clock = pastExpiry)

      assertEquals(0, psatNudges(student).size, "a dated window out of a dead reading is not a date to raise")
      assertEquals(
        SynthesisService.PsatWindowNudgeSuppression.CYCLE_EXPIRED,
        suppressionFor(student, pastExpiry),
        "and the stale reading is the reason, reached before every date on the row",
      )
      assertEquals(
        SynthesisService.PsatWindowNudgeSuppression.WINDOW_PASSED,
        suppressionFor(student, onExpiry),
        "ON expires_on the reading still stands, so the ordinary window reason is the one reached",
      )
    }
  }

  /**
   * CYCLE_UNDATED: she IS in the qualifying year of her own cycle, we still
   * believe our reading of it, and it has no dates at all -- so "near" has no
   * meaning and a nudge would be raising a deadline nobody has published.
   *
   * ITS OWN CYCLE, for [withSeededCycle]'s reason: the committed described row
   * stops being believed a month into its class's junior year, so no clock exists
   * on which a student of that class reaches this guard.
   */
  @Test
  fun `a junior whose own cycle is described and not dated gets no nudge`() {
    val cycle =
      NationalMeritFixture.cycle(
        competitionYear = undatedCycleYear,
        window =
          ed.unicoach.db.models.CycleWindow
            .Described(asPublished = "The test is given in school in October of the junior year."),
        alternateEntry = null,
        checkedOn = LocalDate.of(undatedCycleYear - 2, 9, 1),
        // Believed through the June her junior year ends in, so the undated
        // window is the only thing left that can stop the step.
        expiresOn = LocalDate.of(undatedCycleYear - 1, 6, 30),
      )
    val junior = createStudentInClass(cycle.competitionYear)
    val herJuniorAutumn = clockOn(LocalDate.of(cycle.competitionYear - 2, 10, 15))

    withSeededCycle(cycle) {
      executeNudgeOnlyPass(junior, clock = herJuniorAutumn)

      assertEquals(0, psatNudges(junior).size, "an undated window cannot be near, and no date may be invented for it")
      assertEquals(
        SynthesisService.PsatWindowNudgeSuppression.CYCLE_UNDATED,
        suppressionFor(junior, herJuniorAutumn),
        "the undated window is the reason: she IS in the qualifying year of it and the row is still believed",
      )
    }
  }

  /** WINDOW_NOT_NEAR: the same junior, a month and a half earlier. The window is hers and it is not yet the moment. */
  @Test
  fun `a junior further out than the lead days gets no nudge yet`() {
    val student = createStudentInClass(datedCycle.competitionYear)

    val tooEarly = clockOn(datedWindow.opensOn.minus(config.psatWindowNudgeLead).minusDays(5))
    executeNudgeOnlyPass(student, clock = tooEarly)

    assertEquals(0, psatNudges(student).size, "before the lead window it is a reminder she would forget")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.WINDOW_NOT_NEAR,
      suppressionFor(student, tooEarly),
      "and it is the lead that has not been reached, not her year of school",
    )
  }

  /**
   * WINDOW_PASSED IS A DELIBERATE REFUSAL, NOT AN OMISSION. Nothing in the product
   * stores whether she sat the test, so an unprompted "you may have missed it" is a
   * guess about her life -- and the alternate-entry answer is fully available the
   * moment she asks, through the tool's own arm.
   */
  @Test
  fun `a junior past the window gets no nudge, because nothing here knows whether she sat it`() {
    val student = createStudentInClass(datedCycle.competitionYear)

    val dayAfter = clockOn(datedWindow.closesOn.plusDays(1))
    executeNudgeOnlyPass(student, clock = dayAfter)

    assertEquals(0, psatNudges(student).size, "the letter route is an answer to a question, never an unprompted guess")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.WINDOW_PASSED,
      suppressionFor(student, dayAfter),
      "the closed window is the reason, and she is still in the qualifying year of school",
    )
  }

  /**
   * ALREADY_RAISED, IN ANY STATUS -- which is the whole repeat rule, because the
   * qualifying sitting happens once in a student's life. A DROPPED row is a student
   * who did not want it, and that answer stands: there is no cooldown here and no
   * day count to configure.
   */
  @Test
  fun `a student who already has a dropped psat_window row is never nudged again`() {
    val student = createStudentInClass(datedCycle.competitionYear)
    insertDroppedPsatNudge(student)

    executeNudgeOnlyPass(student, clock = windowNearClock)

    assertEquals(
      0,
      psatNudges(student).count { it.status == CommitmentStatus.OPEN },
      "one row in any status is the whole repeat rule",
    )
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.ALREADY_RAISED,
      suppressionFor(student, windowNearClock),
      "and the row she already has is the reason, on the clock this nudge otherwise fires on",
    )
  }

  /** OPEN_SET_AT_CAP: a saturated open set takes no nudge either. */
  @Test
  fun `the open-set cap suppresses the psat nudge`() {
    val student = createStudentInClass(datedCycle.competitionYear)
    createCommitment(student, "one")
    createCommitment(student, "two")

    val cfg = configWith("synthesis.maxOpenCommitments = 2")
    executeNudgeOnlyPass(student, cfg, windowNearClock)

    assertEquals(0, psatNudges(student).size, "a student at the cap no-ops until some resolve")
    assertEquals(
      SynthesisService.PsatWindowNudgeSuppression.OPEN_SET_AT_CAP,
      suppressionFor(student, windowNearClock, cfg),
      "and the saturated open set is the reason, which is the last gate the step reaches",
    )
  }

  /**
   * THE MODEL CANNOT PROPOSE THIS LENS. The exhaustive `when` behind
   * `LLM_PROPOSABLE_LENSES` keeps it out of the advertised schema, and a model
   * that invents it anyway is rejected as an unknown field -- exactly as the
   * share-report nudge already proves for itself.
   */
  @Test
  fun `the LLM cannot propose the psat_window lens - the schema omits it and the validator rejects it`() =
    runBlocking {
      val student = createStudentInClass(datedCycle.competitionYear)
      createClaim(student)
      val doc = """{"commitments":[{"lens":"psat_window","disclosure":"explicit","statement":"x","supports":[]}]}"""
      val provider = JsonProvider(jsonDoc = doc, usage = TokenUsage(9, 4, 0, 0))

      val result =
        service(provider, configWith("synthesis.psatWindowNudgeEnabled = false"), windowNearClock).synthesize(student)

      val advertisedLenses =
        provider.lastRequest!!
          .tools
          .single()
          .getValue("input_schema")
          .jsonObject
          .getValue("properties")
          .jsonObject
          .getValue("commitments")
          .jsonObject
          .getValue("items")
          .jsonObject
          .getValue("properties")
          .jsonObject
          .getValue("lens")
          .jsonObject
          .getValue("enum")
          .jsonArray
          .map { it.jsonPrimitive.content }
          .toSet()
      assertTrue(
        CommitmentLens.PSAT_WINDOW.value !in advertisedLenses,
        "the schema offers exactly the LLM-proposable lenses, never the code-only psat_window: [$advertisedLenses]",
      )

      assertTrue(result is SynthesisResult.TransientFailure, "got [$result]")
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
      assertEquals(0, psatNudges(student).size, "the invented lens must never become a commitment")
    }

  private fun countAllLlmRequests(): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM llm_requests").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
