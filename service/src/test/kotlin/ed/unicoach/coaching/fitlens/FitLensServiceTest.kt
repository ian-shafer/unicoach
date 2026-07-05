package ed.unicoach.coaching.fitlens

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
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
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.JobResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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
      stmt.execute(
        "TRUNCATE TABLE fit_suggestions, fit_lens_runs, commitment_support, commitments, synthesis_runs, observations, " +
          "claim_support, claims, college_list_entries, colleges, convos, convo_requests, convo_responses, convo_responses_raw, " +
          "system_prompts, students, users CASCADE",
      )
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('fit_lens_query', 'v1', 'formulate a query')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('fit_lens_reason', 'v1', 'reason over matches')")
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

  private fun service(
    provider: ChatProvider,
    cfg: FitLensConfig = config,
  ): FitLensService = FitLensService(database, provider, CollegeSearchService(database), cfg)

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

  private fun scripted(vararg docs: String): ScriptedProvider = ScriptedProvider(terminals = docs.map { completed(it) })

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
          tuitionInState = 12000,
          tuitionOutState = 30000,
          graduationRate = 0.7,
          medianEarnings = 55000,
          pctPell = 0.4,
          website = null,
        ),
      ).getOrThrow()
      .id

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
              completed(reasonDoc(college), input = 200, output = 60),
            ),
        )

      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Applied, "Expected Applied, got: $result")
      assertEquals(1, suggestionRows(student), "one open suggestion written")
      val run = latestRun(student)
      assertEquals(FitLensOutcome.APPLIED, run.outcome)
      assertEquals(1, run.suggestionsWritten)
      assertEquals(300, run.inputTokens, "tokens summed across both calls")
      assertEquals(100, run.outputTokens, "tokens summed across both calls")
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
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college))),
        )
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Applied, "Expected Applied, got: $result")
      assertEquals(0, suggestionRows(student), "no suggestion written for a college already on the list")
      assertEquals(0, latestRun(student).suggestionsWritten)
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
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college))),
        )
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Applied, "Expected Applied, got: $result")
      assertEquals(1, suggestionRows(student), "only the pre-existing suggestion remains; no duplicate")
      assertEquals(0, latestRun(student).suggestionsWritten)
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
            outcome = FitLensOutcome.APPLIED,
            querySystemPromptId = queryPromptId(),
            reasonSystemPromptId = reasonPromptId(),
            provider = "log",
            modelResolved = "m",
            suggestionsWritten = 0,
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
              outcome = FitLensOutcome.FAILED,
              querySystemPromptId = queryPromptId(),
              reasonSystemPromptId = reasonPromptId(),
              provider = "log",
              modelResolved = "m",
              suggestionsWritten = 0,
              matchesConsidered = null,
              failureCategory = FitLensFailureCategory.MALFORMED_OUTPUT,
              failureReason = "test failure",
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
      assertEquals(FitLensOutcome.APPLIED, latestRun(student).outcome)
      assertEquals(0, latestRun(student).suggestionsWritten)
      assertEquals(0, latestRun(student).matchesConsidered)
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
      assertEquals(FitLensOutcome.APPLIED, latestRun(student).outcome)
      assertEquals(0, latestRun(student).suggestionsWritten)
    }

  @Test
  fun `malformed CollegeQuery JSON fails, writes a failed run with matches_considered null`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      val provider = scripted("not json at all")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Failed, "Expected Failed, got: $result")
      assertEquals(FitLensOutcome.FAILED, latestRun(student).outcome)
      assertNull(latestRun(student).matchesConsidered, "the retrieve never ran, so matches_considered is null")
      assertEquals(0, suggestionRows(student))
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
      assertEquals(FitLensOutcome.FAILED, latestRun(student).outcome)
      assertEquals(0, suggestionRows(student))
    }

  @Test
  fun `Failed reason distinguishes the malformed-query from the off-match-set case`() =
    runBlocking {
      val malformedStudent = createStudent()
      createClaims(malformedStudent, 3)
      createCollege()
      val malformedResult = service(scripted("not json at all")).discover(malformedStudent)
      assertTrue(malformedResult is FitLensResult.Failed, "Expected Failed, got: $malformedResult")

      val offSetStudent = createStudent()
      createClaims(offSetStudent, 3)
      createCollege(name = "Retrieved U")
      val phantom = CollegeId(UUID.randomUUID())
      val offSetResult =
        service(scripted("""{"states":["CA"]}""", reasonDoc(phantom))).discover(offSetStudent)
      assertTrue(offSetResult is FitLensResult.Failed, "Expected Failed, got: $offSetResult")

      assertTrue(
        malformedResult.reason.toDisplay().contains("malformed JSON"),
        "the malformed-query reason names the JSON failure, got: ${malformedResult.reason.toDisplay()}",
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
      assertEquals(FitLensOutcome.FAILED, latestRun(student).outcome, "the run is recorded failed, not silently applied")
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

      val provider = ScriptedProvider(terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(fresh))))
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
      val provider = scripted("garbage")
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

  private fun completed(
    doc: String,
    input: Int = 100,
    output: Int = 50,
  ): ChatEvent.Completed = completedFrom(doc, input, output)
}

private fun completedFrom(
  doc: String,
  input: Int,
  output: Int,
): ChatEvent.Completed {
  val content =
    JsonArray(
      listOf(
        buildJsonObject {
          put("type", "text")
          put("text", doc)
        },
      ),
    )
  return ChatEvent.Completed(
    response =
      ChatResponse(
        content = content,
        modelResolved = "claude-sonnet-4-6",
        stopReason = "end_turn",
        usage = TokenUsage(input, output, 0, 0),
        providerRequestId = "req_${UUID.randomUUID()}",
      ),
    rawPayload = content,
  )
}
