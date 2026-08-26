package ed.unicoach.coaching.fitlens

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.exhaustedBudgetService
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.JobResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitLensServiceTest {
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
        "TRUNCATE TABLE fit_suggestions, fit_lens_runs, commitment_support, commitments, synthesis_runs, observations, " +
          "claim_support, claims, college_list_entries, colleges, convos, convo_requests, " +
          "llm_requests, llm_responses, llm_responses_raw, students, users CASCADE",
      )
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val baseConfig =
    ed.unicoach.common.config.AppConfig
      .load("service.conf")
      .getOrThrow()

  private val config = FitLensConfig.from(baseConfig).getOrThrow()

  private fun configWith(overrides: String): FitLensConfig =
    FitLensConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString(overrides)
          .withFallback(baseConfig),
      ).getOrThrow()

  private val generousBudget = generousBudgetService(database)

  private val exhaustedBudget = exhaustedBudgetService(database)

  private fun service(
    provider: ChatProvider,
    cfg: FitLensConfig = config,
    budget: BudgetService = generousBudget,
  ): FitLensService =
    FitLensService(database, ed.unicoach.coaching.LlmCallLog(provider, database), CollegeSearchService(database), cfg, budget)

  // ---------------------------------------------------------------------------
  // Fakes
  // ---------------------------------------------------------------------------

  /** Returns the scripted terminals in call order; captures each request. */
  private class ScriptedProvider(
    override val id: String = "log",
    private val terminals: List<ChatEvent.Terminal>,
  ) : ChatProvider {
    val requests = mutableListOf<ChatRequest>()
    private var call = 0

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        requests += request
        val terminal = terminals[call.coerceAtMost(terminals.size - 1)]
        call++
        emit(terminal)
      }
  }

  /** A provider whose Nth call throws a transient error. */
  private class ThrowingOnCallProvider(
    override val id: String = "log",
    private val throwOnCall: Int,
  ) : ChatProvider {
    private var call = 0

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        call++
        if (call == throwOnCall) throw RuntimeException("transient provider blip")
        emit(completedFrom("""{}""", 0, 0))
      }
  }

  // The pass calls query (#1) then reason (#2), so the first doc is the
  // record_college_query payload and any subsequent doc is a record_fit_reason
  // payload — the block name is now load-bearing (toolUseInput matches on it).
  private fun scripted(vararg docs: String): ScriptedProvider =
    ScriptedProvider(
      terminals = docs.mapIndexed { i, doc -> completed(doc, toolName = if (i == 0) "record_college_query" else "record_fit_reason") },
    )

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private var unitIdCounter = 600000

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'fl-$userId@test.com', 'FL User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createClaim(
    studentId: StudentId,
    statement: String,
  ) = ClaimsDao
    .create(
      session,
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

  private fun createClaims(
    studentId: StudentId,
    n: Int,
  ) {
    repeat(n) { createClaim(studentId, "claim number $it") }
  }

  private fun createCollege(name: String = "Test College"): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          unitId = unitIdCounter++,
          opeid = null,
          name = name,
          city = "Townsville",
          state = "CA",
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = 1,
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = 40000,
          netPrice = 20000,
          netPriceQ1 = null,
          netPriceQ2 = null,
          netPriceQ3 = null,
          netPriceQ4 = null,
          netPriceQ5 = null,
          tuitionInState = 12000,
          tuitionOutState = 30000,
          graduationRate = 0.7,
          medianEarnings = 55000,
          medianDebt = null,
          pctPell = 0.4,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun createCollegeWithProgram(
    cipCode: String,
    title: String,
  ): CollegeId {
    val collegeId = createCollege()
    CollegesDao
      .upsertProgram(session, NewCollegeProgram(collegeId, cipCode, title, 3))
      .getOrThrow()
    return collegeId
  }

  /**
   * Inserts one minimal `llm_requests` row and returns its id. Used to satisfy
   * the non-null `NewFitLensRun.queryLlmRequestId` (and its NOT NULL FK) when a
   * test seeds a prior run row directly rather than driving a full pass.
   */
  private fun seedLlmRequestId(): LlmRequestId =
    connection
      .prepareStatement(
        "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) " +
          "VALUES ('log', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          LlmRequestId(rs.getLong("id"))
        }
      }

  private fun suggestionRows(studentId: StudentId): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM fit_suggestions WHERE student_id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun runRows(studentId: StudentId): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM fit_lens_runs WHERE student_id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun latestRun(studentId: StudentId) = FitLensRunsDao.listByStudent(session, studentId, limit = 10, offset = 0).getOrThrow().last()

  /**
   * Summed (input, output) tokens across a fit-lens run's two linked calls. Since
   * RFC 106 the per-call spend lives on the generic llm_responses rows the run
   * references via query_llm_request_id / reason_llm_request_id, not on the run
   * row itself.
   */
  private fun runTokens(run: ed.unicoach.db.models.FitLensRun): Pair<Int, Int> {
    val ids = listOfNotNull(run.queryLlmRequestId, run.reasonLlmRequestId).map { it.value }
    if (ids.isEmpty()) return 0 to 0
    val inList = ids.joinToString(",")
    connection
      .createStatement()
      .use { stmt ->
        stmt
          .executeQuery(
            "SELECT COALESCE(SUM(input_tokens),0) AS i, COALESCE(SUM(output_tokens),0) AS o " +
              "FROM llm_responses WHERE request_id IN ($inList)",
          ).use { rs ->
            rs.next()
            return rs.getInt("i") to rs.getInt("o")
          }
      }
  }

  private fun reasonDoc(collegeId: CollegeId): String = """{"collegeId":"${collegeId.asString}","rationale":"you would love it here"}"""

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `happy path writes one open suggestion, an applied run with summed tokens`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Great Fit U")

      // Call #1 returns a CollegeQuery matching the seeded college; call #2 names it.
      val provider =
        ScriptedProvider(
          terminals =
            listOf(
              completed("""{"states":["CA"]}""", input = 100, output = 40),
              completed(reasonDoc(college), input = 200, output = 60, toolName = "record_fit_reason"),
            ),
        )

      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Applied, "Expected Applied, got: $result")
      assertEquals(1, suggestionRows(student), "one open suggestion written")
      val run = latestRun(student)
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 1), run.outcome)
      val (inputTokens, outputTokens) = runTokens(run)
      assertEquals(300, inputTokens, "tokens summed across both calls")
      assertEquals(100, outputTokens, "tokens summed across both calls")
      assertEquals(
        "open",
        FitSuggestionsDao
          .list(session, 10, 0)
          .getOrThrow()
          .single()
          .status.value,
        "the suggestion is written open",
      )
    }

  @Test
  fun `novelty write-time gate - a college already on the college_list is not re-suggested`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Already Known U")
      // The reasoned college is already on the student's college list.
      CollegeListEntriesDao
        .create(session, NewCollegeListEntry(student, college, CollegeListEntryStatus.CONSIDERING, null))
        .getOrThrow()

      val provider =
        ScriptedProvider(
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college), toolName = "record_fit_reason")),
        )
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Applied, "Expected Applied, got: $result")
      assertEquals(0, suggestionRows(student), "no suggestion written for a college already on the list")
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 0), latestRun(student).outcome)
    }

  @Test
  fun `novelty write-time gate - a college already in fit_suggestions is not re-suggested`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Prior Suggestion U")
      FitSuggestionsDao.create(session, NewFitSuggestion(student, college, "suggested before")).getOrThrow()

      val provider =
        ScriptedProvider(
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college), toolName = "record_fit_reason")),
        )
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Applied, "Expected Applied, got: $result")
      assertEquals(1, suggestionRows(student), "only the pre-existing suggestion remains; no duplicate")
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 0), latestRun(student).outcome)
    }

  @Test
  fun `minClaims floor - too few active claims skips with no LLM call and no run`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 2) // below default minClaims = 3
      createCollege()

      val provider = scripted("""{"states":["CA"]}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      assertEquals(0, provider.requests.size, "no LLM call is made below the minClaims floor")
      assertEquals(0, runRows(student), "no run row written for a pre-LLM skip")
    }

  @Test
  fun `budget gate - an exhausted student skips by name with no LLM call and no run`() =
    runBlocking {
      val student = createStudent()
      // Enough signal to clear every other pre-LLM gate, so only the budget can
      // be what stopped the pass.
      createClaims(student, 5)
      createCollege()

      val provider = scripted("""{"states":["CA"]}""")
      val result = service(provider, budget = exhaustedBudget).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      val reason = result.reason
      assertTrue(reason is SkipReason.BudgetExhausted, "Expected a named budget skip, got: $reason")
      assertEquals(student, reason.studentId)
      assertTrue(reason.entitlement.exhausted)
      assertEquals(0, provider.requests.size, "an exhausted student's pass makes neither LLM call")
      assertEquals(0, runRows(student), "no run row written for a pre-LLM skip")
      assertTrue(
        reason.toDisplay().contains("coaching budget exhausted") && reason.toDisplay().contains("0.000000"),
        "the display line states spent against allowance, got: ${reason.toDisplay()}",
      )
    }

  @Test
  fun `freshness gate - an unchanged model since the last applied run skips before any LLM call`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      // A prior applied run whose created_at is after every claim's updated_at.
      Thread.sleep(5)
      FitLensRunsDao
        .append(
          session,
          NewFitLensRun(
            studentId = student,
            outcome = FitLensOutcome.Applied(suggestionsWritten = 0),
            querySystemPromptId = queryPromptId(),
            reasonSystemPromptId = reasonPromptId(),
            queryLlmRequestId = seedLlmRequestId(),
            reasonLlmRequestId = null,
            matchesConsidered = 0,
          ),
        ).getOrThrow()

      val provider = scripted("""{"states":["CA"]}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      assertEquals(0, provider.requests.size, "no LLM call when the model is unchanged")
    }

  @Test
  fun `failure circuit breaker - maxConsecutiveFailures failed runs skip before any LLM call`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      // Three failed runs already logged (default maxConsecutiveFailures = 3), never applied.
      repeat(3) {
        FitLensRunsDao
          .append(
            session,
            NewFitLensRun(
              studentId = student,
              outcome = FitLensOutcome.Failed(FitLensFailureCategory.MALFORMED_OUTPUT, "test failure"),
              querySystemPromptId = queryPromptId(),
              reasonSystemPromptId = reasonPromptId(),
              queryLlmRequestId = seedLlmRequestId(),
              reasonLlmRequestId = null,
              matchesConsidered = null,
            ),
          ).getOrThrow()
      }

      val provider = scripted("""{"states":["CA"]}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      assertEquals(0, provider.requests.size, "the breaker stops the pass before any token is spent")
    }

  @Test
  fun `maxClaims cap truncates the claim payload the provider observes on call 1`() =
    runBlocking {
      val student = createStudent()
      createClaim(student, "KEEP_claim_a")
      createClaim(student, "KEEP_claim_b")
      createClaim(student, "DROP_claim_c")
      createCollege()

      val provider = scripted("""{"states":["CA"]}""", """{}""")
      // minClaims 2 so 3 claims pass the floor; maxClaims 2 so only the first two feed the prompt.
      val cfg = configWith("fitLens.minClaims = 2\nfitLens.maxClaims = 2")
      service(provider, cfg).discover(student)

      val call1 = provider.requests.first()
      val text =
        ed.unicoach.chat.ContentBlocks
          .renderText(call1.messages.single().content)
      assertTrue(text.contains("KEEP_claim_a") && text.contains("KEEP_claim_b"), "the first maxClaims claims must be present")
      assertTrue(!text.contains("DROP_claim_c"), "the excess claim must be truncated out of the prompt")
    }

  @Test
  fun `zero search matches skips with an applied run and no suggestion`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      // No college seeded -> the search matches nothing.
      val provider = scripted("""{"states":["ZZ"]}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      assertEquals(1, runRows(student), "an applied run records the spent tokens")
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 0), latestRun(student).outcome)
      assertEquals(0, latestRun(student).matchesConsidered)
    }

  @Test
  fun `an unquoted dotted CIP prefix is read from the number literal the model wrote`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollegeWithProgram("260702", "Marine Biology")

      // A model writing a CIP code often omits the quotes; the literal text is
      // still a readable prefix, and dropping it would silently widen the search
      // to every college.
      val provider = scripted("""{"cipPrefix":26.07}""", """{}""")
      service(provider).discover(student)

      assertEquals(1, latestRun(student).matchesConsidered)
    }

  @Test
  fun `an unreadable CIP prefix fails the pass instead of silently retrieving nothing`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollegeWithProgram("260702", "Marine Biology")

      // "5.138" cannot be read one way (05.138? 51.38?). Forwarded verbatim it
      // would match no program and look like an honest "no fit".
      val provider = scripted("""{"cipPrefix":"5.138"}""", """{}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Failed, "Expected Failed, got: $result")
      assertEquals(
        FitLensFailureCategory.MALFORMED_OUTPUT,
        (latestRun(student).outcome as FitLensOutcome.Failed).category,
      )
      assertEquals(0, suggestionRows(student))
    }

  @Test
  fun `a dotted CIP prefix from the model retrieves the same programs as the canonical form`() =
    runBlocking {
      // The model writes CIP codes dotted; cip_code is stored digits-only, so an
      // un-canonicalized prefix silently matches nothing and the pass reports
      // "no fit" instead of an error.
      // One seeded college for the whole loop: the table is not truncated between
      // iterations, so re-seeding would inflate matches_considered per pass.
      createCollegeWithProgram("260702", "Marine Biology")
      for (prefix in listOf("260702", "26.0702", "26.07")) {
        val student = createStudent()
        createClaims(student, 3)

        val provider = scripted("""{"cipPrefix":"$prefix"}""", """{}""")
        service(provider).discover(student)

        assertEquals(1, latestRun(student).matchesConsidered, "cipPrefix [$prefix] should retrieve the program")
      }
    }

  @Test
  fun `reason returns empty object skips with an applied run and no suggestion`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      val provider = scripted("""{"states":["CA"]}""", """{}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      assertEquals(0, suggestionRows(student))
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 0), latestRun(student).outcome)
    }

  @Test
  fun `a query call with no tool_use block fails, writes a failed run with matches_considered null`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      // Call #1 returned text instead of calling the forced tool: QueryNoToolUse.
      val provider = ScriptedProvider(terminals = listOf(noToolUseCompleted()))
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Failed, "Expected Failed, got: $result")
      assertTrue(latestRun(student).outcome is FitLensOutcome.Failed, "Expected a Failed run outcome")
      assertEquals(
        FitLensFailureCategory.MALFORMED_OUTPUT,
        (latestRun(student).outcome as FitLensOutcome.Failed).category,
      )
      assertNull(latestRun(student).matchesConsidered, "the retrieve never ran, so matches_considered is null")
      assertEquals(0, suggestionRows(student))
    }

  @Test
  fun `a reason call with no tool_use block fails, writes a failed run recording spent tokens`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege(name = "Retrieved U")
      // Call #1 returns a valid query; call #2 returns text (no tool_use):
      // ReasonNoToolUse → MALFORMED_OUTPUT, both calls' tokens summed.
      val provider = ScriptedProvider(terminals = listOf(completed("""{"states":["CA"]}"""), noToolUseCompleted()))
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Failed, "Expected Failed, got: $result")
      assertEquals(
        FitLensFailureCategory.MALFORMED_OUTPUT,
        (latestRun(student).outcome as FitLensOutcome.Failed).category,
      )
      assertEquals(0, suggestionRows(student))
    }

  @Test
  fun `both requests carry their forcing tool and tool_choice`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Great Fit U")
      val provider =
        ScriptedProvider(
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college), toolName = "record_fit_reason")),
        )
      service(provider).discover(student)

      val queryReq = provider.requests[0]
      assertEquals(
        "record_college_query",
        queryReq.tools
          .single()["name"]
          ?.jsonPrimitive
          ?.content,
      )
      assertEquals("record_college_query", queryReq.toolChoice!!["name"]?.jsonPrimitive?.content)
      assertEquals("tool", queryReq.toolChoice!!["type"]?.jsonPrimitive?.content)

      val reasonReq = provider.requests[1]
      assertEquals(
        "record_fit_reason",
        reasonReq.tools
          .single()["name"]
          ?.jsonPrimitive
          ?.content,
      )
      assertEquals("record_fit_reason", reasonReq.toolChoice!!["name"]?.jsonPrimitive?.content)
      assertEquals("tool", reasonReq.toolChoice!!["type"]?.jsonPrimitive?.content)
    }

  @Test
  fun `a collegeId outside the match set fails, writes a failed run recording spent tokens`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege(name = "Retrieved U")
      val phantom = CollegeId(UUID.randomUUID())
      val provider = scripted("""{"states":["CA"]}""", reasonDoc(phantom))
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Failed, "Expected Failed, got: $result")
      assertTrue(latestRun(student).outcome is FitLensOutcome.Failed, "Expected a Failed run outcome")
      assertEquals(0, suggestionRows(student))
    }

  @Test
  fun `Failed category distinguishes the shape-defect query from the content-defect off-match-set case`() =
    runBlocking {
      // A type-invalid query field (states is not an array): a shape defect →
      // QueryTypeInvalidField → MALFORMED_OUTPUT.
      val shapeStudent = createStudent()
      createClaims(shapeStudent, 3)
      createCollege()
      val shapeResult = service(scripted("""{"states":"CA"}""")).discover(shapeStudent)
      assertTrue(shapeResult is FitLensResult.Failed, "Expected Failed, got: $shapeResult")

      // A collegeId outside the retrieved set: a content defect → INVALID_CONTENT.
      val offSetStudent = createStudent()
      createClaims(offSetStudent, 3)
      createCollege(name = "Retrieved U")
      val phantom = CollegeId(UUID.randomUUID())
      val offSetResult =
        service(scripted("""{"states":["CA"]}""", reasonDoc(phantom))).discover(offSetStudent)
      assertTrue(offSetResult is FitLensResult.Failed, "Expected Failed, got: $offSetResult")

      assertEquals(
        FitLensFailureCategory.MALFORMED_OUTPUT,
        (latestRun(shapeStudent).outcome as FitLensOutcome.Failed).category,
        "a type-invalid query field is a shape defect (malformed_output)",
      )
      assertEquals(
        FitLensFailureCategory.INVALID_CONTENT,
        (latestRun(offSetStudent).outcome as FitLensOutcome.Failed).category,
        "an off-match-set collegeId is a content defect (invalid_content)",
      )
      assertTrue(
        offSetResult.reason.toDisplay().contains("outside the retrieved match set"),
        "the off-match-set reason names the match-set failure, got: ${offSetResult.reason.toDisplay()}",
      )
    }

  @Test
  fun `an over-length rationale fails and is not a silent Applied no-op`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Retrieved U")
      // A rationale longer than the fit_suggestions_rationale_length_check (2048).
      val hugeRationale = "x".repeat(3000)
      val reasonDoc = """{"collegeId":"${college.asString}","rationale":"$hugeRationale"}"""
      val provider = scripted("""{"states":["CA"]}""", reasonDoc)

      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Failed, "an over-length rationale must be Failed, not Applied, got: $result")
      assertEquals(0, suggestionRows(student), "no suggestion is written for an over-length rationale")
      assertTrue(
        latestRun(student).outcome is FitLensOutcome.Failed,
        "the run is recorded failed, not silently applied",
      )
    }

  @Test
  fun `a DB failure during the run-row write yields a retriable TransientFailure, not a silent success`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      // No college seeded -> zero matches -> writeAppliedRun runs. A provider id
      // outside fit_lens_runs_provider_check ('anthropic','log') makes the run-row
      // append fail with a real DB constraint violation.
      val provider = ScriptedProvider(id = "bogus-provider", terminals = listOf(completed("""{"states":["ZZ"]}""")))

      val result = service(provider).discover(student)

      assertTrue(
        result is FitLensResult.TransientFailure,
        "a DB failure writing the run row must be a retriable TransientFailure, got: $result",
      )
      assertEquals(0, runRows(student), "the failed write left no run row")
    }

  @Test
  fun `call 1 message carries the excluded and previously-suggested college names`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      // One college on the list, one already suggested; both must steer call #1.
      val listed = createCollege(name = "Already Listed College")
      CollegeListEntriesDao
        .create(session, NewCollegeListEntry(student, listed, CollegeListEntryStatus.CONSIDERING, null))
        .getOrThrow()
      val priorSuggested = createCollege(name = "Previously Suggested College")
      FitSuggestionsDao.create(session, NewFitSuggestion(student, priorSuggested, "suggested before")).getOrThrow()
      // A third college to actually reason over.
      val fresh = createCollege(name = "Fresh Candidate College")

      val provider =
        ScriptedProvider(
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(fresh), toolName = "record_fit_reason")),
        )
      service(provider).discover(student)

      val call1Text =
        ed.unicoach.chat.ContentBlocks
          .renderText(
            provider.requests
              .first()
              .messages
              .single()
              .content,
          )
      assertTrue(
        call1Text.contains("Already Listed College"),
        "the college-list exclusion must appear by name in call #1, message was:\n$call1Text",
      )
      assertTrue(
        call1Text.contains("Previously Suggested College"),
        "the prior fit-suggestion exclusion must appear by name in call #1, message was:\n$call1Text",
      )
    }

  @Test
  fun `dead-letter - FitLensHandler over a malformed-output pass returns Success and does not retry`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      val provider = ScriptedProvider(terminals = listOf(noToolUseCompleted()))
      val handler = FitLensHandler(service(provider))
      val result =
        handler.execute(
          buildJsonObject { put("studentId", student.asString) },
        )
      assertTrue(result is JobResult.Success, "A dead-lettered Failed pass maps to Success (no retry), got: $result")
    }

  @Test
  fun `transient service error on call 1 - no run row, freshness and breaker unmoved`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      val provider = ThrowingOnCallProvider(throwOnCall = 1)
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.TransientFailure, "Expected TransientFailure, got: $result")
      assertEquals(0, runRows(student), "a transient failure writes no fit_lens_runs row")
      assertNull(FitLensRunsDao.lastAppliedAt(session, student).getOrThrow(), "freshness marker unmoved")
      assertEquals(
        0,
        FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(),
        "the breaker count is unmoved by an infra blip",
      )
    }

  // --- prompt-id helpers ---

  private fun queryPromptId() =
    ed.unicoach.db.dao.SystemPromptsDao
      .findByNameAndVersion(session, "fit_lens_query", "v1")
      .getOrThrow()
      .id

  private fun reasonPromptId() =
    ed.unicoach.db.dao.SystemPromptsDao
      .findByNameAndVersion(session, "fit_lens_reason", "v1")
      .getOrThrow()
      .id

  /**
   * A Completed terminal whose content is a forced tool_use block carrying the
   * object parsed from [doc] (the shape a forced `tool_choice` produces, RFC 104,
   * read by `ContentBlocks.toolUseInput`). The block's [toolName] IS load-bearing
   * — `toolUseInput` now matches on it — so it defaults to the query tool
   * (`record_college_query`, call #1) and reason-call slots pass
   * `record_fit_reason`.
   */
  private fun completed(
    doc: String,
    input: Int = 100,
    output: Int = 50,
    toolName: String = "record_college_query",
  ): ChatEvent.Completed = completedFrom(doc, input, output, toolName)
}

private fun completedFrom(
  doc: String,
  input: Int,
  output: Int,
  toolName: String = "record_college_query",
): ChatEvent.Completed {
  val toolInput =
    kotlinx.serialization.json.Json
      .parseToJsonElement(doc) as JsonObject
  val content =
    JsonArray(
      listOf(
        buildJsonObject {
          put("type", "tool_use")
          put("id", "toolu_${UUID.randomUUID()}")
          put("name", toolName)
          put("input", toolInput)
        },
      ),
    )
  return ChatEvent.Completed(
    response =
      ChatResponse(
        content = content,
        modelResolved = "claude-sonnet-4-6",
        stopReason = "tool_use",
        usage = TokenUsage(input, output, 0, 0),
        providerRequestId = "req_${UUID.randomUUID()}",
      ),
    rawPayload = content,
  )
}

/**
 * A Completed terminal whose content is a text-only block (no tool_use block) —
 * the model declined to call the forced tool, mapped to `Query|ReasonNoToolUse`.
 */
private fun noToolUseCompleted(
  input: Int = 100,
  output: Int = 50,
): ChatEvent.Completed {
  val content =
    JsonArray(
      listOf(
        buildJsonObject {
          put("type", "text")
          put("text", "I could not do that.")
        },
      ),
    )
  return ChatEvent.Completed(
    response =
      ChatResponse(
        content = content,
        modelResolved = "claude-sonnet-4-6",
        stopReason = "tool_use",
        usage = TokenUsage(input, output, 0, 0),
        providerRequestId = "req_${UUID.randomUUID()}",
      ),
    rawPayload = content,
  )
}
