package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SearchIndexNotBuiltException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.CollegeSearchPage
import ed.unicoach.db.models.NewCollege
import ed.unicoach.error.TransientError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollegeSearchServiceTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs CASCADE").use { it.execute() }
        // The state/locale reference rows `colleges` foreign-keys into (0067).
        CodebookReferenceFixture.seed(session)
      }
      Unit
    }

  private val service = CollegeSearchService(database)

  private fun newCollege(
    ipedsUnitId: Int,
    state: String = "CA",
    netPricePerYearUsd: Int? = 20000,
  ) = NewCollege(
    housingAndFoodOnCampusPerYearUsd = null,
    housingAndFoodOffCampusPerYearUsd = null,
    booksAndSuppliesPerYearUsd = null,
    otherExpensesOnCampusPerYearUsd = null,
    otherExpensesOffCampusPerYearUsd = null,
    otherExpensesWithFamilyPerYearUsd = null,
    ipedsUnitId = ipedsUnitId,
    opeid = null,
    name = "Test U $ipedsUnitId",
    city = "Townsville",
    state = state,
    region = 8,
    locale = 13,
    latitude = null,
    longitude = null,
    control = 1,
    undergradEnrollmentHeadcount = ipedsUnitId,
    admissionRateShare = 0.5,
    satAverageEquivalentScore = null,
    costOfAttendancePerYearUsd = null,
    netPricePerYearUsd = netPricePerYearUsd,
    netPricePerYearIncomeQ1Usd = null,
    netPricePerYearIncomeQ2Usd = null,
    netPricePerYearIncomeQ3Usd = null,
    netPricePerYearIncomeQ4Usd = null,
    netPricePerYearIncomeQ5Usd = null,
    tuitionAndFeesInStatePerYearUsd = null,
    tuitionAndFeesOutOfStatePerYearUsd = null,
    completionRate150pct4yrShare = 0.7,
    medianEarnings10yAfterEntryUsd = 50000,
    medianDebtAtCompletionUsd = null,
    pellShare = 0.4,
    website = null,
  )

  /**
   * Seeds one college AND rebuilds the derived `college_name_words` table
   * (RFC 146). In production only the ingest writes `colleges`, and it rebuilds
   * the words as its own phase; a test seeding rows directly is the one caller
   * that must do it by hand, so it lives in the helper where a later test
   * cannot forget it.
   */
  private fun seed(input: NewCollege) =
    runBlocking {
      database.withConnection { session: SqlSession ->
        val college = CollegesDao.upsert(session, input).getOrThrow()
        CollegesDao.rebuildNameWords(session).getOrThrow()
        // ...and `college_search_index` (RFC 150 D53), which both entry points
        // now read. Same rule, same reason: the ingest rebuilds it in a phase.
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        college
      }
    }

  @Test
  fun `clamps limit to the supported range`() =
    runBlocking {
      for (u in 1..30) seed(newCollege(u))

      val tooMany = service.search(CollegeQuery(limit = 100)).page().matches
      assertEquals(CollegeSearchService.MAX_LIMIT, tooMany.size)

      val tooFew = service.search(CollegeQuery(limit = 0)).page().matches
      assertTrue(tooFew.size >= CollegeSearchService.MIN_LIMIT)
    }

  @Test
  fun `delegates filtering to the DAO and returns matches`() =
    runBlocking {
      seed(newCollege(11, state = "CA"))
      seed(newCollege(12, state = "TX"))

      val matches = service.search(CollegeQuery(states = listOf("CA"), limit = 25)).page().matches
      assertEquals(listOf(11), matches.map { it.ipedsUnitId })
    }

  @Test
  fun `zero matches returns an empty list, not a failure`() =
    runBlocking {
      seed(newCollege(21, state = "CA"))
      val result = service.search(CollegeQuery(states = listOf("ZZ"), limit = 25))
      assertTrue(result.isSuccess)
      assertTrue(result.page().matches.isEmpty())
      assertEquals(0, result.page().totalMatches)
    }

  // ---------------------------------------------------------------------------
  // searchByName (RFC 137)
  // ---------------------------------------------------------------------------

  @Test
  fun `searchByName clamps limit to the supported range`() =
    runBlocking {
      for (u in 101..130) seed(newCollege(u))

      val tooMany = service.searchByName("Test U", 100).getOrThrow()
      assertEquals(CollegeSearchService.MAX_LIMIT, tooMany.size)

      val tooFew = service.searchByName("Test U", 0).getOrThrow()
      assertTrue(tooFew.size >= CollegeSearchService.MIN_LIMIT)
    }

  @Test
  fun `searchByName delegates matching to the DAO`() =
    runBlocking {
      seed(newCollege(141))
      seed(newCollege(142))

      val matches = service.searchByName("Test U 141", 25).getOrThrow()
      // "Test U 142" is one keystroke away ("141"→"142") and so legitimately
      // matches too (RFC 146); the exact match must rank first.
      assertEquals("Test U 141", matches.first().name)
    }

  @Test
  fun `searchByName zero matches returns an empty list, not a failure`() =
    runBlocking {
      seed(newCollege(151))
      val result = service.searchByName("No Such College", 25)
      assertTrue(result.isSuccess)
      assertTrue(result.getOrThrow().isEmpty())
    }

  @Test
  fun `searchByName blank query is an empty success, never an unbounded scan`() =
    runBlocking {
      seed(newCollege(161))
      val blank = service.searchByName("   ", 25)
      assertTrue(blank.isSuccess)
      assertTrue(blank.getOrThrow().isEmpty())
    }

  @Test
  fun `searchByName trims the query before matching`() =
    runBlocking {
      seed(newCollege(171))
      val matches = service.searchByName("  Test U 171  ", 25).getOrThrow()
      assertEquals(listOf("Test U 171"), matches.map { it.name })
    }

  @Test
  fun `searchByName against an UNBUILT index is a named failure, never an empty list`() =
    runBlocking {
      // The migration lands the table empty; only the ingest fills it. Name
      // search is the entry point this hurts most — it used to degrade to a
      // `colleges` substring scan, so an unbuilt index makes it strictly worse
      // than it was, and an empty list is indistinguishable from "no such
      // college".
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges CASCADE").use { it.execute() }
        session.prepareStatement("DELETE FROM college_index_build").use { it.execute() }
      }

      val result = service.searchByName("Test U", 25)

      val error = result.exceptionOrNull()
      assertTrue(error is SearchIndexNotBuiltException, "expected a named refusal, got: $error")
      // Retryable: the next ingest fixes it with no code change, and the REST
      // layer answers a transient DAO failure with 503 rather than a 200 of
      // nothing.
      assertTrue(error is TransientError, "an unbuilt index must be retryable")
      assertTrue(error.message!!.contains("search-index"), "the failure must name the phase that fixes it")
    }

  @Test
  fun `a raw JDBC fault from the connection boundary comes back as a failure, not a throw`() =
    runBlocking {
      // `withConnection` throws AROUND the DAO's enveloped Result -- a pool
      // timeout, a failed commit, a failed rollback -- so `Result.failure` did
      // not in fact mean "the database failed": the exception escaped the
      // Result the tool boundary builds its retryable `search_failed` shape
      // from, and a transient blip reached the model as a hard failure. A
      // CLOSED pool is that fault in its simplest form.
      val closed = Database(DatabaseConfig.from(AppConfig.load("common.conf", "db.conf").getOrThrow()).getOrThrow())
      closed.close()
      val closedService = CollegeSearchService(closed)

      val structured = closedService.search(CollegeQuery(limit = 25))
      assertTrue(structured.isFailure, "a connection fault must be a failure, not a thrown exception")

      val byName = closedService.searchByName("Test U", 25)
      assertTrue(byName.isFailure, "a connection fault must be a failure, not a thrown exception")
    }

  @Test
  fun `searchByName oversized query is a failure naming the bound`() =
    runBlocking {
      val result = service.searchByName("x".repeat(CollegeSearchService.MAX_QUERY_LENGTH + 1), 25)
      assertTrue(result.isFailure)
      val exception = result.exceptionOrNull()
      assertTrue(exception is IllegalArgumentException)
      assertTrue(exception.message!!.contains("${CollegeSearchService.MAX_QUERY_LENGTH}"))
    }
}

/** The page a search must have produced; a program-filter refusal fails loudly. */
private fun Result<CollegeSearchOutcome>.page(): CollegeSearchPage =
  when (val outcome = getOrThrow()) {
    is CollegeSearchOutcome.Page -> {
      outcome.page
    }

    is CollegeSearchOutcome.UnresolvableProgramFilter -> {
      throw AssertionError("expected a page, got a refusal: [${outcome.field}] [${outcome.value}] ${outcome.cause}")
    }

    is CollegeSearchOutcome.IndexNotBuilt -> {
      throw AssertionError("expected a page, but college_search_index has never been built")
    }
  }
