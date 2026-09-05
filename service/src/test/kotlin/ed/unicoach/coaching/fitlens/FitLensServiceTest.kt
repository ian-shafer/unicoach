package ed.unicoach.coaching.fitlens

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.exhaustedBudgetService
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.coaching.costs.canonical.CanonicalCostReader
import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.DbCanonicalCostReader
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.FitLensRunsDao
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.NewCollegeProgramsCensus
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.ValueBearingStatus
import ed.unicoach.queue.JobResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitLensServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    /** `colleges.control` for a public institution — the IPEDS code, as the loader reads it. */
    private const val CONTROL_PUBLIC = 1

    /** `colleges.control` for a private not-for-profit institution. */
    private const val CONTROL_PRIVATE_NONPROFIT = 2

    /** The vintage every fixture net-price row carries, stated so a test can assert the digest speaks it. */
    private const val NET_PRICE_VINTAGE = "2022-23"

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
    // The published state/locale rows `colleges` foreign-keys into (0067).
    // Truncating `colleges` does not empty them, but another suite on this
    // shared database does, so each suite puts them back.
    CodebookReferenceFixture.seed(session)
    // The five money-vocabulary tables every `cohort_money_stats` row
    // foreign-keys into (RFC 158 P2). A WRITE PRECONDITION: without them the
    // seeder below fails on a foreign key that has nothing to do with what these
    // tests assert. Idempotent, so it is safe after every TRUNCATE.
    MoneyVocabularyFixture.seed(session)
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

  /**
   * The REAL committed codebook (RFC 147 D45), loaded into the test database:
   * `record_college_query` now speaks the same published words as
   * `search_colleges`, so the pass is tested against the same vocabulary the
   * search tool is rather than a second copy typed here — which is the exact
   * duplication this slice deleted.
   */
  private val codebook =
    runBlocking {
      ed.unicoach.college.CodebookFixture
        .load(database)
    }

  private fun service(
    provider: ChatProvider,
    cfg: FitLensConfig = config,
    budget: BudgetService = generousBudget,
    reader: CanonicalCostReader = DbCanonicalCostReader(database),
  ): FitLensService =
    FitLensService(
      database,
      ed.unicoach.coaching.LlmCallLog(provider, database),
      CollegeSearchService(database),
      cfg,
      budget,
      codebook,
      reader,
    )

  /**
   * A canonical reader whose every door THROWS [failure] -- the substitution
   * seam the injected [CanonicalCostReader] exists for.
   *
   * The alternative is what this test used to do: build a second whole service
   * over a [Database] opened from config and then closed, which proves the read
   * throws on a dead pool but cannot carry the rest of a passing run through it.
   * A stub fails the ONE read and leaves everything else alive, which is the
   * behaviour under test.
   */
  private class ThrowingCostReader(
    private val failure: () -> Throwable,
  ) : CanonicalCostReader {
    override fun read(
      session: SqlSession,
      collegeIds: List<CollegeId>,
    ): Map<CollegeId, CollegeFigures> = throw failure()

    override fun readCohortStats(
      session: SqlSession,
      collegeIds: List<CollegeId>,
    ): Map<CollegeId, CollegeFigures> = throw failure()

    override suspend fun readCohortStats(collegeIds: List<CollegeId>): Map<CollegeId, CollegeFigures> = throw failure()
  }

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

  private var ipedsUnitIdCounter = 600000

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

  /**
   * One retrievable college, in BOTH stores: the `colleges` row the search index
   * is rebuilt from, and the canonical `cohort_money_stats` row the digest's net
   * price is now read from (RFC 166 §9).
   *
   * The two net prices are separate parameters on purpose. [indexNetPricePerYearUsd]
   * is the FILTER and RANKING column and belongs to `shape/05`; [netPriceReading]
   * is what a family is told. A test may set them apart to prove which surface
   * reads which.
   */
  private fun createCollege(
    name: String = "Test College",
    control: Int = CONTROL_PUBLIC,
    indexNetPricePerYearUsd: Int? = 20_000,
    netPriceReading: FigureReading<Double> = FigureReading.Present(20_000.0, ValueBearingStatus.REPORTED),
    netPriceVintage: String = NET_PRICE_VINTAGE,
    // No canonical row at all -- the college the fill has never reached, and the
    // shape a DEGRADED read leaves every college in (RFC 166 §9).
    seedsNetPriceRow: Boolean = true,
  ): CollegeId {
    val collegeId =
      CollegesDao
        .upsert(
          session,
          NewCollege(
            housingAndFoodOnCampusPerYearUsd = null,
            housingAndFoodOffCampusPerYearUsd = null,
            booksAndSuppliesPerYearUsd = null,
            otherExpensesOnCampusPerYearUsd = null,
            otherExpensesOffCampusPerYearUsd = null,
            otherExpensesWithFamilyPerYearUsd = null,
            ipedsUnitId = ipedsUnitIdCounter++,
            opeid = null,
            name = name,
            city = "Townsville",
            state = "CA",
            region = 8,
            locale = 13,
            latitude = 34.0,
            longitude = -118.0,
            control = control,
            undergradEnrollmentHeadcount = 5000,
            admissionRateShare = 0.5,
            satAverageEquivalentScore = 1200,
            costOfAttendancePerYearUsd = 40000,
            netPricePerYearUsd = indexNetPricePerYearUsd,
            netPricePerYearIncomeQ1Usd = null,
            netPricePerYearIncomeQ2Usd = null,
            netPricePerYearIncomeQ3Usd = null,
            netPricePerYearIncomeQ4Usd = null,
            netPricePerYearIncomeQ5Usd = null,
            tuitionAndFeesInStatePerYearUsd = 12000,
            tuitionAndFeesOutOfStatePerYearUsd = 30000,
            completionRate150pct4yrShare = 0.7,
            medianEarnings10yAfterEntryUsd = 55000,
            medianDebtAtCompletionUsd = null,
            pellShare = 0.4,
            website = null,
          ),
        ).getOrThrow()
        .id
    if (seedsNetPriceRow) seedNetPriceStat(collegeId, control, netPriceReading, netPriceVintage)
    // Both search entry points read `college_search_index` (RFC 150 D53),
    // which is derived state the ingest rebuilds in its own phase — so a
    // test that seeds `colleges` directly must rebuild it or the college is
    // invisible to retrieval.
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    return collegeId
  }

  /**
   * The college's canonical `avg_net_price` row — the figure the digest now
   * speaks.
   *
   * The residency scope is keyed off CONTROL exactly as `CanonicalMoneyLoader`
   * keys it, never hand-asserted: the Scorecard builds this average for
   * in-state-rate-paying undergraduates at a PUBLIC school and for everybody at
   * a private one (RFC 157), which is the whole reason the digest's key is read
   * off the row.
   */
  private fun seedNetPriceStat(
    collegeId: CollegeId,
    control: Int,
    reading: FigureReading<Double>,
    vintage: String,
  ) {
    CanonicalMoneyDao
      .insertCohortMoneyStats(
        session,
        listOf(
          NewCohortMoneyStat(
            collegeId = collegeId.value,
            measure = MoneyMeasure.AVG_NET_PRICE,
            population = CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
            residencyScope =
              if (control == CONTROL_PUBLIC) CohortResidencyScope.IN_STATE_RATE_PAYING else CohortResidencyScope.ALL,
            aidScope = CohortAidScope.FEDERAL_AID_RECEIVING,
            incomeBand = null,
            vintage = vintage,
            reading = reading,
            source = MoneySource.SCORECARD,
            sourceVariable = "NPT4",
          ),
        ),
      ).getOrThrow()
  }

  private fun createCollegeWithProgram(
    cipCode: String,
    title: String,
  ): CollegeId {
    val collegeId = createCollege()
    // The index derives its programs from the IPEDS census, not from
    // `college_programs` (RFC 150 D51); the title comes back from `cip_codes`,
    // which the real codebook fixture has loaded.
    CollegeIpedsDao
      .upsertProgramsCensus(session, NewCollegeProgramsCensus(collegeId, cipCode, 5, 12, 2023))
      .getOrThrow()
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
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

  /** A scripted two-call pass: [queryDoc] for call #1, then a choice of [collegeId] for call #2. */
  private fun providerFor(
    collegeId: CollegeId,
    queryDoc: String = """{"states":["CA"]}""",
  ): ScriptedProvider =
    ScriptedProvider(
      terminals = listOf(completed(queryDoc), completed(reasonDoc(collegeId), toolName = "record_fit_reason")),
    )

  /** Runs one full pass and returns the USER message call #2 was handed — the digest under test. */
  private suspend fun reasonContextOf(
    studentId: StudentId,
    collegeId: CollegeId,
  ): String {
    val provider = providerFor(collegeId)
    service(provider).discover(studentId)
    return ed.unicoach.chat.ContentBlocks
      .renderText(
        provider.requests[1]
          .messages
          .single()
          .content,
      )
  }

  /**
   * The ONE digest line describing [collegeId], taken out of the reason-call
   * message.
   *
   * The paragraph above the college list explains the same key names in prose,
   * so a negative `contains` over the whole message would fire on the
   * explanation rather than on the claim the line makes about this college.
   */
  private fun collegeLine(
    context: String,
    collegeId: CollegeId,
  ): String = context.lines().single { it.startsWith("- collegeId=[${collegeId.asString}]") }

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
        .create(session, NewCollegeListEntry(student, college, CollegeListEntryStatus.CONSIDERING, null, null))
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
      // A seeded college, so the index is BUILT, and a real state it is not in:
      // a genuine zero-match. ("ZZ" stood here and is now refused at parse —
      // it is not a US jurisdiction, RFC 150.)
      createCollege()
      val provider = scripted("""{"states":["WY"]}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      assertEquals(1, runRows(student), "an applied run records the spent tokens")
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 0), latestRun(student).outcome)
      assertEquals(0, latestRun(student).matchesConsidered)
    }

  @Test
  fun `an unexpandable program filter skips with its CAUSE, not as a zero match`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()

      // "5116" is the retired nursing series: a well-formed prefix the loaded
      // vocabulary carries no code for. The search cannot run at all, which is
      // a different fact from "the search ran and found nothing" -- and the
      // persisted run could not tell them apart while both collapsed into
      // ZeroSearchMatches.
      val provider = scripted("""{"cipPrefix":"5116"}""")
      val result = service(provider).discover(student)

      assertTrue(result is FitLensResult.Skipped, "Expected Skipped, got: $result")
      val reason = result.reason
      assertTrue(reason is SkipReason.UnresolvableProgramFilter, "Expected the named skip, got: $reason")
      assertEquals(student, reason.studentId)
      assertEquals(CollegeSearchOutcome.UnresolvableProgramFilter.Field.CIP_PREFIX, reason.field)
      assertEquals("5116", reason.value)
      assertEquals(
        CollegeSearchOutcome.UnresolvableProgramFilter.Cause.NOT_A_PUBLISHED_CIP_CODE,
        reason.cause,
      )
      assertTrue(reason.toDisplay().contains("cipPrefix"), "the log line names the field, got: ${reason.toDisplay()}")
      // The tokens were spent, so the run is still recorded as applied with no
      // suggestion -- the skip's CAUSE changed, not its billing.
      assertEquals(1, runRows(student))
      assertEquals(FitLensOutcome.Applied(suggestionsWritten = 0), latestRun(student).outcome)
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
  fun `the query tool advertises the shared filter vocabulary, in words`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Vocabulary U")
      val provider =
        ScriptedProvider(
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college), toolName = "record_fit_reason")),
        )
      service(provider).discover(student)

      val properties =
        provider.requests[0]
          .tools
          .single()["input_schema"]!!
          .jsonObject["properties"]!!
          .jsonObject

      // The SAME fields the search tool offers, from the one shared home -- so
      // the drift this slice deleted cannot come back by editing one of them.
      // Minus any whose vocabulary this database has not loaded: a filter with
      // no values is not advertised on either surface (RFC 150).
      assertEquals(
        ed.unicoach.college.CollegeQueryVocabulary.FIELD_NAMES - codebook.emptyVocabularies.toSet(),
        properties.keys,
      )
      assertEquals(
        codebook.regionSlugs,
        (properties["region"]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content },
      )
      assertTrue(properties["region"]!!.jsonObject.containsKey("description"), "each field describes itself; the prompt no longer does")
      // The prompt used to carry this codebook as prose. Nothing here is a code.
      // Positive control FIRST: this assertion is only worth anything if the
      // pattern can fire at all. The copy that used to live here was written
      // with doubled backslashes in a raw string and could never match, which
      // is why the pattern is now shared from BareSourceCodeGuard.
      assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
      assertNull(
        BareSourceCodeGuard.CODE_EQUALS_WORD.find(properties.toString()),
        "the schema must name no code-to-word pair",
      )
    }

  @Test
  fun `a region word is resolved to its code, and an unknown word fails the pass`() =
    runBlocking {
      // createCollege() seeds region 8 / locale 13 -- far-west, city: small.
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Far West Fit U")
      val provider =
        ScriptedProvider(
          terminals =
            listOf(
              completed("""{"region":"far-west","locale_type":"city"}"""),
              completed(reasonDoc(college), toolName = "record_fit_reason"),
            ),
        )
      assertTrue(service(provider).discover(student) is FitLensResult.Applied, "a worded query must retrieve the seeded college")

      // ...and a word the codebook does not carry fails the pass rather than
      // being dropped, which would have searched with no region filter at all.
      val unknownStudent = createStudent()
      createClaims(unknownStudent, 3)
      createCollege(name = "Unreachable U")
      val unknown = service(scripted("""{"region":"new-englund"}""")).discover(unknownStudent)
      assertTrue(unknown is FitLensResult.Failed, "Expected Failed, got: $unknown")
      assertEquals(
        FitLensFailureCategory.MALFORMED_OUTPUT,
        (latestRun(unknownStudent).outcome as FitLensOutcome.Failed).category,
      )
      assertTrue(
        unknown.reason.toDisplay().contains("new-englund"),
        "the failure carries the offending word, got: ${unknown.reason.toDisplay()}",
      )
    }

  @Test
  fun `an unknown field fails the pass rather than being ignored`() =
    runBlocking {
      // The trap the v2 prompt would have set: it teaches `locales: [11,12,13]`,
      // a field name the shared vocabulary no longer has. Ignoring it would run
      // the search with NO locale filter and hand back the whole corpus — a
      // narrower question answered with a wider answer, silently.
      val student = createStudent()
      createClaims(student, 3)
      createCollege(name = "Retired Vocabulary U")

      val result = service(scripted("""{"states":["CA"],"locales":[11,12,13]}""")).discover(student)

      assertTrue(result is FitLensResult.Failed, "Expected Failed, got: $result")
      assertEquals(
        FitLensFailureCategory.MALFORMED_OUTPUT,
        (latestRun(student).outcome as FitLensOutcome.Failed).category,
      )
      assertTrue(
        result.reason.toDisplay().contains("locales"),
        "the failure names the field it refused, got: ${result.reason.toDisplay()}",
      )
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
      // A college in CA and a query for WY -> zero matches -> writeAppliedRun
      // runs. A provider id outside fit_lens_runs_provider_check
      // ('anthropic','log') makes the run-row append fail with a real DB
      // constraint violation.
      createCollege()
      val provider = ScriptedProvider(id = "bogus-provider", terminals = listOf(completed("""{"states":["WY"]}""")))

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
        .create(session, NewCollegeListEntry(student, listed, CollegeListEntryStatus.CONSIDERING, null, null))
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
  fun `call 2 names the retrieved net price as the school's IN-STATE figure, never a bare number`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      // A PUBLIC school (control = 1), the only case where the Scorecard's
      // in-state basis can fail to be the reader's -- RFC 157 D-A.
      val college = createCollege(name = "Public Candidate U")

      val provider =
        ScriptedProvider(
          terminals = listOf(completed("""{"states":["CA"]}"""), completed(reasonDoc(college), toolName = "record_fit_reason")),
        )
      service(provider).discover(student)

      val call2Text =
        ed.unicoach.chat.ContentBlocks
          .renderText(
            provider.requests[1]
              .messages
              .single()
              .content,
          )
      assertTrue(
        call2Text.contains("netPricePerYearUsd=[20000] netPriceBasis=[in_state_rate_paying]"),
        "the retrieved net price must reach the model with its in-state BASIS beside it, message=[$call2Text]",
      )
      assertTrue(
        call2Text.contains("is the school's own published in-state net price, not this family's"),
        "the label must say whose figure it is, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("inStateNetPricePerYearUsd"),
        "the basis is a field, never a key spelling a reader must decode, message=[$call2Text]",
      )
    }

  @Test
  fun `call 2 speaks a suppressed net price as its status, never a number and never the bare local wording`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      // The canonical row exists and says the publisher withheld the figure --
      // a fact a NULL column could not carry (RFC 166 §6).
      val college =
        createCollege(
          name = "Suppressed Net Price U",
          netPriceReading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        )

      val call2Text = reasonContextOf(student, college)

      assertTrue(
        call2Text.contains(
          "netPriceStatus=[suppressed_by_publisher] " +
            "netPriceNote=[This figure is withheld by the publisher for privacy.]",
        ),
        "a suppressed figure must ride as a status CODE with its sentence beside it, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("netPricePerYearUsd="),
        "and the dollar key must be OMITTED, never made to hold a sentence, message=[$call2Text]",
      )
      assertTrue(
        call2Text.contains("netPriceBasis=[in_state_rate_paying]"),
        "the row we DO hold still says which students it was built on, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("not reported"),
        "the suppressed figure must not collapse into the bare local wording, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("=[20000]"),
        "the index copy of the number must not be printed for a suppressed figure, message=[$call2Text]",
      )
    }

  @Test
  fun `call 2 does not label a private college's all-students net price as in-state`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      // A PRIVATE school: the Scorecard builds its net price for EVERY student,
      // so the row's residency_scope is `all` and no in-state claim is true.
      val college = createCollege(name = "Private Candidate College", control = CONTROL_PRIVATE_NONPROFIT)

      val call2Text = reasonContextOf(student, college)

      val line = collegeLine(call2Text, college)

      assertTrue(
        line.contains("netPricePerYearUsd=[20000] netPriceBasis=[all]"),
        "an all-students figure must reach the model under the SAME key, with basis `all`, line=[$line]",
      )
      assertTrue(
        !line.contains("in_state_rate_paying") && !call2Text.contains("inStateNetPricePerYearUsd"),
        "a scope=all row must never be labelled in-state, message=[$call2Text]",
      )
    }

  @Test
  fun `call 2 states the net price's vintage`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Dated Net Price U")

      val call2Text = reasonContextOf(student, college)

      assertTrue(
        call2Text.contains("netPriceVintage=[$NET_PRICE_VINTAGE]"),
        "the digest must state the year its figure describes, message=[$call2Text]",
      )
    }

  @Test
  fun `a canonical net-price read that THROWS still finishes the pass, and the digest carries no net-price key at all`() =
    runBlocking {
      // The digest number is ADVISORY. Aborting the pass on it threw away a
      // completed retrieval and a paid LLM call, and left the student with NO
      // suggestions rather than suggestions carrying one fact less.
      //
      // But the degrade may not SPEAK. "We have not collected this figure yet."
      // is a claim about unicoach's data coverage, made to a model that
      // narrates it to a student; a broken read knows nothing about this
      // school's net price, so the run says nothing about it.
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Broken Read U")
      val provider = providerFor(college)

      val result =
        service(provider, reader = ThrowingCostReader { IllegalStateException("the canonical store is unreachable") })
          .discover(student)

      assertTrue(result is FitLensResult.Applied, "a failed net-price read must not fail the pass, got: $result")
      assertEquals(1, suggestionRows(student), "the student still gets their suggestion")

      val call2Text =
        ed.unicoach.chat.ContentBlocks
          .renderText(
            provider.requests[1]
              .messages
              .single()
              .content,
          )
      assertTrue(call2Text.contains(college.asString), "the college itself must still reach the model, message=[$call2Text]")
      assertTrue(
        !call2Text.contains("netPricePerYearUsd") && !call2Text.contains("netPriceStatus"),
        "an unavailable read omits every net-price key entirely, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("netPriceVintage") && !call2Text.contains("netPriceBasis"),
        "and omits its year and its basis with them, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("We have not collected this figure yet."),
        "a broken read must never be spoken as a claim about our data coverage, message=[$call2Text]",
      )
      assertTrue(
        !call2Text.contains("=[20000]"),
        "and never falls back to the index copy of the number, message=[$call2Text]",
      )
    }

  @Test
  fun `a college we hold no canonical row for still says so, which is the sentence the broken read may not borrow`() =
    runBlocking {
      // The twin of the test above, and the reason the two states may not
      // share one sentence: HERE the read worked and the answer is "we have no
      // row for this school", which is true and belongs in the digest.
      val student = createStudent()
      createClaims(student, 3)
      val uncollected = createCollege(name = "No Canonical Row U", seedsNetPriceRow = false)

      val call2Text = reasonContextOf(student, uncollected)

      val line = collegeLine(call2Text, uncollected)

      assertTrue(
        line.contains(
          "netPriceStatus=[not_collected_by_us] netPriceNote=[We have not collected this figure yet.]",
        ),
        "a college with no row is one we have not collected, said as a code plus its sentence, line=[$line]",
      )
      assertTrue(
        !line.contains("netPricePerYearUsd="),
        "and no dollar key is printed for a figure we do not hold, line=[$line]",
      )
      assertTrue(
        !line.contains("netPriceVintage") && !line.contains("netPriceBasis"),
        "with no row there is no source to date and no population to name, line=[$line]",
      )
      assertTrue(
        !line.contains("not dated by the source"),
        "and the digest never dates a figure that does not exist, line=[$line]",
      )
      assertTrue(
        !line.contains("=[20000]"),
        "and never falls back to the index copy of the number, line=[$line]",
      )
    }

  @Test
  fun `a cancelled pass unwinds at the net-price read instead of degrading into a second billed call`() =
    runBlocking {
      // CancellationException IS an Exception, so a bare `catch (e: Exception)`
      // absorbs the cancellation of a turn the caller already abandoned and
      // walks on into LLM call #2 -- billed, for nobody. Every sibling in this
      // package rethrows it first; this one now does too.
      val student = createStudent()
      createClaims(student, 3)
      val college = createCollege(name = "Cancelled Pass U")
      val provider = providerFor(college)

      assertFailsWith<CancellationException> {
        service(provider, reader = ThrowingCostReader { CancellationException("the job was cancelled") })
          .discover(student)
      }

      assertEquals(1, provider.requests.size, "the cancelled pass must never reach the second, BILLED call")
      assertEquals(0, suggestionRows(student), "and writes nothing")
    }

  @Test
  fun `the search filter still reads the index column, not the canonical figure`() =
    runBlocking {
      // The filter and ranking column `net_price_per_year_usd` is shape/05's and
      // this slice moves only the DIGEST (RFC 166 §9). The two numbers are seeded
      // APART so the assertion cannot pass by coincidence: the index says 20000,
      // the canonical store says 9000.
      val student = createStudent()
      createClaims(student, 3)
      val college =
        createCollege(
          name = "Split Net Price U",
          indexNetPricePerYearUsd = 20_000,
          netPriceReading = FigureReading.Present(9_000.0, ValueBearingStatus.REPORTED),
        )

      val filtered =
        providerFor(college, queryDoc = """{"maxNetPricePerYearUsd":15000}""").also {
          service(it).discover(student)
        }
      assertEquals(
        1,
        filtered.requests.size,
        "a max of 15000 must exclude the college on its INDEX price of 20000, not admit it on the canonical 9000",
      )

      // A SECOND student: the first pass wrote an applied run row, and the
      // freshness gate would skip a re-run of the same unchanged model.
      val other = createStudent()
      createClaims(other, 3)
      val admitted = providerFor(college, queryDoc = """{"maxNetPricePerYearUsd":25000}""")
      service(admitted).discover(other)
      val call2Text =
        ed.unicoach.chat.ContentBlocks
          .renderText(
            admitted.requests[1]
              .messages
              .single()
              .content,
          )
      assertTrue(
        call2Text.contains("netPricePerYearUsd=[9000] netPriceBasis=[in_state_rate_paying]"),
        "the digest must speak the CANONICAL figure even though the filter used the index one, message=[$call2Text]",
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
