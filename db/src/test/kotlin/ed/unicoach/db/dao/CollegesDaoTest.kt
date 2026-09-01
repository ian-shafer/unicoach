package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.CollegeSearchPage
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.NewAdmissionTestPolicy
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieBasicClass
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.db.models.NewCollegeProgramsCensus
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewReligiousAffiliation
import ed.unicoach.db.models.NewSubject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegesDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        SearchIndexFixture.truncate(connection)
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    SearchIndexFixture.truncate(connection)
    SearchIndexFixture.seedCodebooks(session)
    preparedSql.clear()
  }

  /**
   * Every SQL statement this suite's session prepared, most recent last. It is
   * how the D60 assertions read the GENERATED text: "the filter and the count
   * statements name no table other than `college_search_index`" is a property
   * of the SQL, and a behavioural test would keep passing after a payload
   * column quietly rejoined the hot path.
   */
  private val preparedSql = mutableListOf<String>()

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement {
        preparedSql += sql
        return connection.prepareStatement(sql)
      }
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun newCollege(
    ipedsUnitId: Int,
    name: String = "Test U $ipedsUnitId",
    city: String = "Townsville",
    state: String = "CA",
    control: Int = 1,
    undergradEnrollmentHeadcount: Int? = 5000,
    admissionRateShare: Double? = 0.5,
    netPricePerYearUsd: Int? = 20000,
    netPricePerYearIncomeQ1Usd: Int? = 9000,
    netPricePerYearIncomeQ2Usd: Int? = 11000,
    netPricePerYearIncomeQ3Usd: Int? = 14000,
    netPricePerYearIncomeQ4Usd: Int? = 17000,
    netPricePerYearIncomeQ5Usd: Int? = 21000,
    completionRate150pct4yrShare: Double? = 0.7,
    medianEarnings10yAfterEntryUsd: Int? = 55000,
    medianDebtAtCompletionUsd: Int? = 23000,
    housingAndFoodOnCampusPerYearUsd: Int? = 9000,
    housingAndFoodOffCampusPerYearUsd: Int? = 11000,
    booksAndSuppliesPerYearUsd: Int? = 1200,
    otherExpensesOnCampusPerYearUsd: Int? = 3000,
    otherExpensesOffCampusPerYearUsd: Int? = 3500,
    otherExpensesWithFamilyPerYearUsd: Int? = 2500,
    pellShare: Double? = 0.4,
    locale: Int? = 13,
    region: Int? = 8,
  ) = NewCollege(
    ipedsUnitId = ipedsUnitId,
    opeid = "0012$ipedsUnitId",
    name = name,
    city = city,
    state = state,
    region = region,
    locale = locale,
    latitude = 34.0,
    longitude = -118.0,
    control = control,
    undergradEnrollmentHeadcount = undergradEnrollmentHeadcount,
    admissionRateShare = admissionRateShare,
    satAverageEquivalentScore = 1200,
    costOfAttendancePerYearUsd = 40000,
    netPricePerYearUsd = netPricePerYearUsd,
    netPricePerYearIncomeQ1Usd = netPricePerYearIncomeQ1Usd,
    netPricePerYearIncomeQ2Usd = netPricePerYearIncomeQ2Usd,
    netPricePerYearIncomeQ3Usd = netPricePerYearIncomeQ3Usd,
    netPricePerYearIncomeQ4Usd = netPricePerYearIncomeQ4Usd,
    netPricePerYearIncomeQ5Usd = netPricePerYearIncomeQ5Usd,
    tuitionAndFeesInStatePerYearUsd = 12000,
    tuitionAndFeesOutOfStatePerYearUsd = 30000,
    completionRate150pct4yrShare = completionRate150pct4yrShare,
    medianEarnings10yAfterEntryUsd = medianEarnings10yAfterEntryUsd,
    medianDebtAtCompletionUsd = medianDebtAtCompletionUsd,
    housingAndFoodOnCampusPerYearUsd = housingAndFoodOnCampusPerYearUsd,
    housingAndFoodOffCampusPerYearUsd = housingAndFoodOffCampusPerYearUsd,
    booksAndSuppliesPerYearUsd = booksAndSuppliesPerYearUsd,
    otherExpensesOnCampusPerYearUsd = otherExpensesOnCampusPerYearUsd,
    otherExpensesOffCampusPerYearUsd = otherExpensesOffCampusPerYearUsd,
    otherExpensesWithFamilyPerYearUsd = otherExpensesWithFamilyPerYearUsd,
    pellShare = pellShare,
    website = "https://test$ipedsUnitId.edu",
  )

  /**
   * Seeds one college AND rebuilds the derived `college_name_words` table.
   *
   * The rebuild belongs in the helper, not in the tests: `colleges` is written
   * only by the ingest in production, which rebuilds the words as its own phase
   * (RFC 146), so a test that seeds rows directly is the one caller that has to
   * do it by hand — and a future test that forgot would not fail, it would
   * silently lose the one-keystroke arm and pass on the substring arm alone.
   * That is exactly the way RFC 139's fuzzy test passed for the wrong reason.
   */
  private fun seed(input: NewCollege): CollegeId {
    val id = CollegesDao.upsert(session, input).getOrThrow().id
    rebuildNameWords()
    rebuildSearchIndex()
    return id
  }

  /** Applies curated aliases and re-derives the words, since aliases feed the search text. */
  private fun setAliases(
    ipedsUnitId: Int,
    aliases: List<String>,
  ) {
    CollegesDao.updateAliases(session, ipedsUnitId, aliases).getOrThrow()
    rebuildNameWords()
    // The index's `search_text` is derived from the name AND the aliases, and
    // the substring arm now reads it off the index (RFC 150 D53) — so an alias
    // change is invisible to search until the index is rebuilt too.
    rebuildSearchIndex()
  }

  private fun rebuildNameWords(): Int = CollegesDao.rebuildNameWords(session).getOrThrow()

  /**
   * Both search entry points now read `college_search_index` (RFC 150 D53), and
   * that table is derived state written only by the ingest's `search-index`
   * phase — so a suite that seeds rows directly has to rebuild it by hand, for
   * exactly the reason [rebuildNameWords] gives. A test that forgot would not
   * fail loudly; it would silently search an empty index and assert nothing.
   */
  private fun rebuildSearchIndex(): Int = CollegesDao.rebuildSearchIndex(session).getOrThrow()

  /**
   * One `college_ipeds` row for an already-seeded college, and a rebuild after
   * it: the attribute half of the index (sector, level, the slugs, the
   * tri-state booleans) is derived from this table, joined by `ipeds_unit_id`.
   */
  private fun seedIpeds(
    ipedsUnitId: Int,
    sector: Int? = 1,
    instLevel: Int? = 1,
    cyActive: Boolean = true,
    relAffil: Int? = 30,
    testPolicy: Int? = 5,
    carnegieBasic: Int? = 15,
    carnegieSize: Int? = 17,
    athleticAssoc: List<Int> = listOf(1),
    hasRotc: Boolean? = true,
    hasStudyAbroad: Boolean? = true,
    offersHousing: Boolean? = true,
    surveyYear: Int = 2023,
  ) {
    CollegeIpedsDao
      .upsert(
        session,
        NewCollegeIpeds(
          ipedsUnitId = ipedsUnitId,
          surveyYear = surveyYear,
          cyActive = cyActive,
          deathYear = null,
          closedAt = null,
          newIpedsUnitId = null,
          instLevel = instLevel,
          ugOffer = true,
          sector = sector,
          carnegieBasic = carnegieBasic,
          carnegieSize = carnegieSize,
          cbsa = null,
          relAffil = relAffil,
          hasRotc = hasRotc,
          hasStudyAbroad = hasStudyAbroad,
          disabilityBand = null,
          registeredDisabilityPercent = null,
          offersHousing = offersHousing,
          housingCapacityHeadcount = null,
          applicationFeeUsd = null,
          athleticAssoc = athleticAssoc,
          footballConf = null,
          testPolicy = testPolicy,
        ),
      ).getOrThrow()
    rebuildSearchIndex()
  }

  /** One `college_programs_census` row — what the index derives its programs from. */
  private fun seedCensusProgram(
    collegeId: CollegeId,
    cipCode: String,
    surveyYear: Int = 2023,
  ) {
    CollegeIpedsDao
      .upsertProgramsCensus(session, NewCollegeProgramsCensus(collegeId, cipCode, 5, 12, surveyYear))
      .getOrThrow()
    rebuildSearchIndex()
  }

  // ---------------------------------------------------------------------------
  // Upserts
  // ---------------------------------------------------------------------------

  @Test
  fun `upsert inserts a new college and returns it with a generated id`() {
    val college = CollegesDao.upsert(session, newCollege(100100)).getOrThrow()
    assertNotNull(college.id)
    assertEquals(100100, college.ipedsUnitId)
    assertEquals(1, college.control)
    assertEquals(20000, college.netPricePerYearUsd)
    assertEquals(9000, college.netPricePerYearIncomeQ1Usd)
    assertEquals(11000, college.netPricePerYearIncomeQ2Usd)
    assertEquals(14000, college.netPricePerYearIncomeQ3Usd)
    assertEquals(17000, college.netPricePerYearIncomeQ4Usd)
    assertEquals(21000, college.netPricePerYearIncomeQ5Usd)
    assertEquals(23000, college.medianDebtAtCompletionUsd)
  }

  @Test
  fun `upsert on existing ipeds_unit_id updates in place and advances updated_at`() {
    val first = CollegesDao.upsert(session, newCollege(100200, name = "Old Name")).getOrThrow()
    Thread.sleep(5)
    val second = CollegesDao.upsert(session, newCollege(100200, name = "New Name")).getOrThrow()

    assertEquals(first.id, second.id)
    assertEquals("New Name", second.name)
    assertTrue(!second.updatedAt.isBefore(first.updatedAt))

    val count =
      connection.prepareStatement("SELECT count(*) FROM colleges WHERE ipeds_unit_id = 100200").use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
    assertEquals(1, count)
  }

  @Test
  fun `upsertProgram enforces (college_id, cip_code, credential_level) uniqueness`() {
    val collegeId = seed(newCollege(100300))
    val first =
      CollegesDao
        .upsertProgram(session, NewCollegeProgram(collegeId, "260702", "Marine Biology", 3))
        .getOrThrow()
    val second =
      CollegesDao
        .upsertProgram(session, NewCollegeProgram(collegeId, "260702", "Marine Biology and Oceanography", 3))
        .getOrThrow()

    assertEquals(first.id, second.id)
    assertEquals("Marine Biology and Oceanography", second.cipTitle)

    val count =
      connection.prepareStatement("SELECT count(*) FROM college_programs").use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
    assertEquals(1, count)
  }

  // ---------------------------------------------------------------------------
  // Constraint enforcement
  // ---------------------------------------------------------------------------

  @Test
  fun `control outside the set is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100400, control = 9))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `admission_rate_share above 1 is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100500, admissionRateShare = 1.5))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `negative undergrad_enrollment_headcount is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100600, undergradEnrollmentHeadcount = -1))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `negative net_price_per_year_usd is accepted`() {
    // Net price is cost of attendance minus average aid, so a heavily-subsidized
    // institution (e.g. a community college) publishes a negative figure.
    val result = CollegesDao.upsert(session, newCollege(100650, netPricePerYearUsd = -982))
    assertTrue(result.isSuccess, "expected negative net_price_per_year_usd to be accepted")
    assertEquals(-982, result.getOrThrow().netPricePerYearUsd)
  }

  @Test
  fun `negative band net price is accepted, negative median_debt_at_completion_usd is rejected`() {
    // The five band columns follow the net_price_per_year_usd precedent (0022): no nonneg
    // CHECK, because aid exceeding cost publishes a negative figure -- and the
    // low-income bands go negative most often.
    val ok = CollegesDao.upsert(session, newCollege(100660, netPricePerYearIncomeQ1Usd = -1913))
    assertTrue(ok.isSuccess, "expected negative net_price_per_year_income_q1_usd to be accepted")
    assertEquals(-1913, ok.getOrThrow().netPricePerYearIncomeQ1Usd)

    // median_debt_at_completion_usd is a loan amount: genuinely nonneg, CHECK-backed.
    val bad = CollegesDao.upsert(session, newCollege(100661, medianDebtAtCompletionUsd = -1))
    assertTrue(bad.isFailure)
    assertTrue(bad.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `a negative cost component is rejected`() {
    // RFC 149: all six components are GROSS costs -- unlike the net-price band
    // columns, a negative is a loader bug, so every one carries a nonneg CHECK.
    // Each is asserted separately: one shared CHECK would pass this test while
    // five columns went unconstrained.
    val negatives: List<Pair<String, NewCollege>> =
      listOf(
        "housing_and_food_on_campus_per_year_usd" to newCollege(100670, housingAndFoodOnCampusPerYearUsd = -1),
        "housing_and_food_off_campus_per_year_usd" to newCollege(100671, housingAndFoodOffCampusPerYearUsd = -1),
        "books_and_supplies_per_year_usd" to newCollege(100672, booksAndSuppliesPerYearUsd = -1),
        "other_expenses_on_campus_per_year_usd" to newCollege(100673, otherExpensesOnCampusPerYearUsd = -1),
        "other_expenses_off_campus_per_year_usd" to newCollege(100674, otherExpensesOffCampusPerYearUsd = -1),
        "other_expenses_with_family_per_year_usd" to newCollege(100675, otherExpensesWithFamilyPerYearUsd = -1),
      )
    for ((column, input) in negatives) {
      val result = CollegesDao.upsert(session, input)
      assertTrue(result.isFailure, "expected a negative [$column] to be rejected")
      assertTrue(result.exceptionOrNull() is ConstraintViolationException, "[$column]: ${result.exceptionOrNull()}")
    }
  }

  @Test
  fun `a state the published codebook does not name is rejected`() {
    // Migration 0067 replaced `colleges_state_length_check` with a real
    // foreign key onto `us_states (usps_code)`, so this covers strictly more
    // than the old "length must be 2" test did: "CAL" is the wrong SHAPE and
    // "ZZ" is the right shape naming nothing, and the length check only ever
    // caught the first.
    for (state in listOf("CAL", "ZZ")) {
      val result = CollegesDao.upsert(session, newCollege(100700, state = state))
      assertTrue(result.isFailure, "expected state [$state] to be rejected")
      val error = result.exceptionOrNull()
      assertIs<NotFoundException>(error, "[$state]: $error")
      assertEquals("colleges_state_codebook_fkey", error.constraint, "[$state]")
      // And the message says CODEBOOK, not "referenced college not found":
      // the two absences share SQLSTATE 23503 and do not share a remedy.
      assertTrue(error.message!!.contains("codebook"), "[$state]: ${error.message}")
    }
  }

  @Test
  fun `a locale the published codebook does not name is rejected`() {
    // The same tightening on the numeric half: 42 is inside the 11..43 range
    // the dropped `colleges_locale_range_check` allowed and is only a real
    // locale because `nces_locales` says so. 14 was never a locale and the
    // range check let it through.
    for (locale in listOf(14, 44)) {
      val result = CollegesDao.upsert(session, newCollege(100701, locale = locale))
      assertTrue(result.isFailure, "expected locale [$locale] to be rejected")
      val error = result.exceptionOrNull()
      assertIs<NotFoundException>(error, "[$locale]: $error")
      assertEquals("colleges_locale_codebook_fkey", error.constraint, "[$locale]")
    }
  }

  @Test
  fun `the search index cannot hold a state the codebook does not name, and the map says which table`() {
    // 0067's third foreign key. It is provoked from the REAL schema rather than
    // asserted against a hand-made SQLException, because the thing that can
    // break is the NAME: `CODEBOOK_FOREIGN_KEYS` restates three schema-owned
    // constraint names, and a rename in a later migration would silently demote
    // this message back to "Referenced college not found".
    // Upserted WITHOUT the rebuild `seed` performs: the rebuild would write the
    // index row this insert needs to be the first one.
    val collegeId = CollegesDao.upsert(session, newCollege(100702, state = "CA")).getOrThrow().id
    val thrown =
      assertFailsWith<SQLException> {
        connection
          .prepareStatement(
            """
            INSERT INTO college_search_index (
                college_id, ipeds_unit_id, name, search_text, state, control, is_active
            ) VALUES (?, 100702, 'Test U', 'test u', 'ZZ', 'public', true)
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, collegeId.value)
            stmt.executeUpdate()
          }
      }

    val mapped = mapCollegeWriteError(thrown)
    assertIs<NotFoundException>(mapped, "$mapped")
    assertEquals("college_search_index_state_fkey", mapped.constraint)
    // The message names the table the value had to be IN, not a disjunction of
    // every codebook table it might have been.
    assertTrue(mapped.message!!.contains("[us_states]"), mapped.message!!)
  }

  @Test
  fun `cip_code at 2, 4 or 6 digits is accepted`() {
    val collegeId = seed(newCollege(100800))
    // The three real CIP widths: series, family, detail. The Scorecard
    // Field-of-Study file carries all three; the 4-digit '0901' is the case
    // the old six-only CHECK wrongly rejected.
    for (cip in listOf("09", "0901", "090101")) {
      val result = CollegesDao.upsertProgram(session, NewCollegeProgram(collegeId, cip, "Comm", 3))
      assertTrue(result.isSuccess, "expected cip_code [$cip] to be accepted")
    }
  }

  @Test
  fun `cip_code of an impossible width or non-digit is rejected`() {
    val collegeId = seed(newCollege(100850))
    // 1/3/5-digit widths cannot be a real CIP (a 3-digit value is most often a
    // leading zero stripped from a 4-digit code); 7 digits overshoots detail.
    for (cip in listOf("9", "260", "26070", "2607021", "26x7")) {
      val result = CollegesDao.upsertProgram(session, NewCollegeProgram(collegeId, cip, "Bad Cip", 3))
      assertTrue(result.isFailure, "expected cip_code [$cip] to be rejected")
      assertTrue(result.exceptionOrNull() is ConstraintViolationException)
    }
  }

  @Test
  fun `ConstraintViolationException carries the constraint name and detail`() {
    // A named CHECK violation (23514) surfaces the constraint that failed and
    // the server DETAIL line (the "Failing row contains (...)" tuple Postgres
    // attaches for a CHECK violation), captured verbatim for log drill-down.
    val checkResult = CollegesDao.upsert(session, newCollege(100870, control = 4))
    assertTrue(checkResult.isFailure)
    val checkError = checkResult.exceptionOrNull() as ConstraintViolationException
    assertEquals("colleges_control_valid_check", checkError.constraint)
    val detail = checkError.detail
    assertNotNull(detail)
    assertTrue(
      detail.contains("Failing row"),
      "expected CHECK detail to carry the failing row, got [$detail]",
    )
  }

  // ---------------------------------------------------------------------------
  // findByIpedsUnitId
  // ---------------------------------------------------------------------------

  @Test
  fun `findByIpedsUnitId returns the row or null`() {
    seed(newCollege(100900))
    assertNotNull(CollegesDao.findByIpedsUnitId(session, 100900).getOrThrow())
    assertNull(CollegesDao.findByIpedsUnitId(session, 999999).getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // search
  // ---------------------------------------------------------------------------

  @Test
  fun `search with no filters returns all rows ordered by enrollment desc, ipeds_unit_id asc`() {
    seed(newCollege(201, undergradEnrollmentHeadcount = 1000))
    seed(newCollege(202, undergradEnrollmentHeadcount = 9000))
    seed(newCollege(203, undergradEnrollmentHeadcount = 9000))

    val matches = CollegesDao.search(session, CollegeQuery(limit = 25)).page().matches
    assertEquals(listOf(202, 203, 201), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search by cipPrefix reads the index census codes and matches 2, 4 and 6 digit prefixes`() {
    val collegeId = seed(newCollege(301))
    seedCensusProgram(collegeId, "260702")
    seed(newCollege(302))

    for (prefix in listOf("26", "2607", "260702")) {
      val matches = CollegesDao.search(session, CollegeQuery(cipPrefix = prefix, limit = 25)).page().matches
      assertEquals(1, matches.size, "prefix $prefix should match")
      // The title comes from `cip_codes`, the one home for a CIP title, joined
      // over the returned page only — never stored on the index row.
      assertEquals(listOf("Marine Biology and Biological Oceanography"), matches.single().programTitles)
    }
  }

  @Test
  fun `search by a cipPrefix no real CIP code carries is a named error, not a silent empty result`() {
    val collegeId = seed(newCollege(303))
    seedCensusProgram(collegeId, "260702")

    // "5116" was the old nursing series; the loaded vocabulary carries no code
    // under it. Before RFC 150 this returned zero rows and said nothing.
    val outcome = CollegesDao.search(session, CollegeQuery(cipPrefix = "5116", limit = 25)).getOrThrow()
    // A DOMAIN outcome, not a failure: the database did not break, the word did.
    val refusal = assertIs<CollegeSearchOutcome.UnresolvableProgramFilter>(outcome)
    assertEquals(CollegeSearchOutcome.UnresolvableProgramFilter.Field.CIP_PREFIX, refusal.field)
    assertEquals("5116", refusal.value)
    // The CAUSE is data, not a sentence: the prefix is not a published code.
    assertEquals(
      CollegeSearchOutcome.UnresolvableProgramFilter.Cause.NOT_A_PUBLISHED_CIP_CODE,
      refusal.cause,
    )
  }

  @Test
  fun `search by maxNetPricePerYearUsd includes and excludes`() {
    seed(newCollege(401, netPricePerYearUsd = 10000))
    seed(newCollege(402, netPricePerYearUsd = 40000))
    val matches = CollegesDao.search(session, CollegeQuery(maxNetPricePerYearUsd = 20000, limit = 25)).page().matches
    assertEquals(listOf(401), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search by size band includes and excludes`() {
    seed(newCollege(411, undergradEnrollmentHeadcount = 800))
    seed(newCollege(412, undergradEnrollmentHeadcount = 5000))
    seed(newCollege(413, undergradEnrollmentHeadcount = 50000))
    val matches =
      CollegesDao
        .search(session, CollegeQuery(minUndergradEnrollmentHeadcount = 1000, maxUndergradEnrollmentHeadcount = 10000, limit = 25))
        .page()
        .matches
    assertEquals(listOf(412), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search by states includes and excludes`() {
    seed(newCollege(421, state = "CA"))
    seed(newCollege(422, state = "OR"))
    seed(newCollege(423, state = "TX"))
    val matches = CollegesDao.search(session, CollegeQuery(states = listOf("CA", "OR"), limit = 25)).page().matches
    assertEquals(setOf(421, 422), matches.map { it.ipedsUnitId }.toSet())
  }

  @Test
  fun `search by control includes and excludes`() {
    seed(newCollege(431, control = 1))
    seed(newCollege(432, control = 2))
    seed(newCollege(433, control = 3))
    val matches =
      CollegesDao
        .search(
          session,
          CollegeQuery(control = listOf(InstitutionControl.PRIVATE_NONPROFIT, InstitutionControl.PRIVATE_FOR_PROFIT), limit = 25),
        ).page()
        .matches
    assertEquals(setOf(432, 433), matches.map { it.ipedsUnitId }.toSet())
  }

  @Test
  fun `search by admission rate band includes and excludes`() {
    seed(newCollege(441, admissionRateShare = 0.1))
    seed(newCollege(442, admissionRateShare = 0.5))
    seed(newCollege(443, admissionRateShare = 0.9))
    val matches =
      CollegesDao
        .search(session, CollegeQuery(minAdmissionRateShare = 0.2, maxAdmissionRateShare = 0.6, limit = 25))
        .page()
        .matches
    assertEquals(listOf(442), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search by minCompletionRate150pct4yrShare includes and excludes`() {
    seed(newCollege(451, completionRate150pct4yrShare = 0.4))
    seed(newCollege(452, completionRate150pct4yrShare = 0.8))
    val matches = CollegesDao.search(session, CollegeQuery(minCompletionRate150pct4yrShare = 0.6, limit = 25)).page().matches
    assertEquals(listOf(452), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search returns the outcome columns`() {
    seed(newCollege(501, completionRate150pct4yrShare = 0.65, medianEarnings10yAfterEntryUsd = 62000, pellShare = 0.33))
    val match =
      CollegesDao
        .search(session, CollegeQuery(limit = 25))
        .page()
        .matches
        .single()
    assertEquals(0.65, match.completionRate150pct4yrShare)
    assertEquals(62000, match.medianEarnings10yAfterEntryUsd)
    assertEquals(0.33, match.pellShare)
    assertEquals(9000, match.netPricePerYearIncomeQ1Usd)
    assertEquals(11000, match.netPricePerYearIncomeQ2Usd)
    assertEquals(14000, match.netPricePerYearIncomeQ3Usd)
    assertEquals(17000, match.netPricePerYearIncomeQ4Usd)
    assertEquals(21000, match.netPricePerYearIncomeQ5Usd)
    assertEquals(23000, match.medianDebtAtCompletionUsd)
  }

  @Test
  fun `search combines filters conjunctively`() {
    // The motivating example: small + coastal-state set + marine-biology CIP + net-price ceiling.
    val target = seed(newCollege(601, state = "CA", undergradEnrollmentHeadcount = 2000, netPricePerYearUsd = 18000))
    seedCensusProgram(target, "260702")

    // Too big.
    val big = seed(newCollege(602, state = "OR", undergradEnrollmentHeadcount = 40000, netPricePerYearUsd = 18000))
    seedCensusProgram(big, "260702")

    // No marine biology program.
    seed(newCollege(603, state = "CA", undergradEnrollmentHeadcount = 2000, netPricePerYearUsd = 18000))

    // Too expensive.
    val pricey = seed(newCollege(604, state = "CA", undergradEnrollmentHeadcount = 2000, netPricePerYearUsd = 60000))
    seedCensusProgram(pricey, "260702")

    val matches =
      CollegesDao
        .search(
          session,
          CollegeQuery(
            cipPrefix = "2607",
            states = listOf("CA", "OR", "WA"),
            maxUndergradEnrollmentHeadcount = 5000,
            maxNetPricePerYearUsd = 25000,
            limit = 25,
          ),
        ).page()
        .matches
    assertEquals(listOf(601), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search applies limit and the limit is honored at the SQL level`() {
    for (u in 700..710) seed(newCollege(u, undergradEnrollmentHeadcount = u))
    val matches = CollegesDao.search(session, CollegeQuery(limit = 3)).page().matches
    assertEquals(3, matches.size)
  }

  // ---------------------------------------------------------------------------
  // Versioning (RFC 82)
  // ---------------------------------------------------------------------------

  @Test
  fun `upsert inserts at version 1 and logs one history row`() {
    val college = CollegesDao.upsert(session, newCollege(800100, name = "Original")).getOrThrow()
    assertEquals(1, college.version)

    val history = CollegesDao.listVersions(session, college.id).getOrThrow()
    assertEquals(1, history.size)
    assertEquals(1, history.single().version)
    assertEquals("Original", history.single().entity.name)
    assertEquals(college.ipedsUnitId, history.single().entity.ipedsUnitId)
  }

  @Test
  fun `upsert of changed content bumps version and logs a second history row`() {
    val first = CollegesDao.upsert(session, newCollege(800200, name = "Old Name")).getOrThrow()
    assertEquals(1, first.version)
    val second = CollegesDao.upsert(session, newCollege(800200, name = "New Name")).getOrThrow()
    assertEquals(2, second.version)
    assertEquals("New Name", second.name)

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    assertEquals("New Name", history.last().entity.name)
  }

  @Test
  fun `re-upsert of identical content is a no-op`() {
    val input = newCollege(800300, name = "Steady U")
    val first = CollegesDao.upsert(session, input).getOrThrow()
    assertEquals(1, first.version)
    val second = CollegesDao.upsert(session, input).getOrThrow()
    assertEquals(1, second.version, "identical re-upsert must not bump version")
    assertEquals(first.id, second.id)
    assertEquals(first.updatedAt, second.updatedAt, "no-op must not advance updated_at")

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(1, history.size)
  }

  @Test
  fun `a change in only net_price_per_year_income_q3_usd bumps version and logs history carrying all six new fields`() {
    // RFC 133: the six new columns are in the upsert's IS DISTINCT FROM tuple,
    // so a re-ingest differing only in one band price is a real content change.
    val first = CollegesDao.upsert(session, newCollege(800250)).getOrThrow()
    assertEquals(1, first.version)

    val second = CollegesDao.upsert(session, newCollege(800250, netPricePerYearIncomeQ3Usd = 14500)).getOrThrow()
    assertEquals(2, second.version)
    assertEquals(14500, second.netPricePerYearIncomeQ3Usd)

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    val latest = history.last().entity
    assertEquals(9000, latest.netPricePerYearIncomeQ1Usd)
    assertEquals(11000, latest.netPricePerYearIncomeQ2Usd)
    assertEquals(14500, latest.netPricePerYearIncomeQ3Usd)
    assertEquals(17000, latest.netPricePerYearIncomeQ4Usd)
    assertEquals(21000, latest.netPricePerYearIncomeQ5Usd)
    assertEquals(23000, latest.medianDebtAtCompletionUsd)
  }

  @Test
  fun `a change in only one cost component bumps version and logs history carrying all six`() {
    // RFC 149: the six components are in the upsert's IS DISTINCT FROM tuple, so
    // a re-ingest differing only in the books allowance is a real content change
    // -- and the redefined log_college_version() carries all six into history.
    val first = CollegesDao.upsert(session, newCollege(800260)).getOrThrow()
    assertEquals(1, first.version)
    assertEquals(9000, first.housingAndFoodOnCampusPerYearUsd)
    assertEquals(11000, first.housingAndFoodOffCampusPerYearUsd)
    assertEquals(1200, first.booksAndSuppliesPerYearUsd)
    assertEquals(3000, first.otherExpensesOnCampusPerYearUsd)
    assertEquals(3500, first.otherExpensesOffCampusPerYearUsd)
    assertEquals(2500, first.otherExpensesWithFamilyPerYearUsd)

    val unchanged = CollegesDao.upsert(session, newCollege(800260)).getOrThrow()
    assertEquals(1, unchanged.version, "an unchanged re-ingest must not bump the version")

    val second = CollegesDao.upsert(session, newCollege(800260, booksAndSuppliesPerYearUsd = 1350)).getOrThrow()
    assertEquals(2, second.version)
    assertEquals(1350, second.booksAndSuppliesPerYearUsd)

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    val latest = history.last().entity
    assertEquals(9000, latest.housingAndFoodOnCampusPerYearUsd)
    assertEquals(11000, latest.housingAndFoodOffCampusPerYearUsd)
    assertEquals(1350, latest.booksAndSuppliesPerYearUsd)
    assertEquals(3000, latest.otherExpensesOnCampusPerYearUsd)
    assertEquals(3500, latest.otherExpensesOffCampusPerYearUsd)
    assertEquals(2500, latest.otherExpensesWithFamilyPerYearUsd)
  }

  @Test
  fun `a null cost component round-trips as not reported, never as zero`() {
    val seeded =
      CollegesDao
        .upsert(
          session,
          newCollege(
            800270,
            housingAndFoodOnCampusPerYearUsd = null,
            otherExpensesOnCampusPerYearUsd = null,
          ),
        ).getOrThrow()
    val read = CollegesDao.findById(session, seeded.id).getOrThrow()
    assertNull(read.housingAndFoodOnCampusPerYearUsd, "an unreported component is null, never 0")
    assertNull(read.otherExpensesOnCampusPerYearUsd)
    assertEquals(11000, read.housingAndFoodOffCampusPerYearUsd, "the reported ones are untouched")
  }

  @Test
  fun `upsert preserves id and created_at across a change`() {
    val first = CollegesDao.upsert(session, newCollege(800400, name = "Before")).getOrThrow()
    val second = CollegesDao.upsert(session, newCollege(800400, name = "After")).getOrThrow()
    assertEquals(first.id, second.id)
    assertEquals(first.createdAt, second.createdAt)
  }

  @Test
  fun `findById returns the row, or NotFoundException when absent`() {
    val seeded = CollegesDao.upsert(session, newCollege(800500)).getOrThrow()
    assertEquals(seeded.id, CollegesDao.findById(session, seeded.id).getOrThrow().id)

    val missing = CollegesDao.findById(session, CollegeId(UUID.randomUUID()))
    assertTrue(missing.isFailure)
    assertTrue(missing.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `list pages name-stable with limit and offset`() {
    seed(newCollege(800601, name = "Charlie College"))
    seed(newCollege(800602, name = "Alpha College"))
    seed(newCollege(800603, name = "Bravo College"))
    seed(newCollege(800604, name = "Delta College"))

    val page1 = CollegesDao.list(session, limit = 2, offset = 0).getOrThrow()
    val page2 = CollegesDao.list(session, limit = 2, offset = 2).getOrThrow()
    assertEquals(listOf("Alpha College", "Bravo College"), page1.map { it.name })
    assertEquals(listOf("Charlie College", "Delta College"), page2.map { it.name })
    assertTrue((page1.map { it.id } + page2.map { it.id }).toSet().size == 4, "pages must not overlap")
  }

  @Test
  fun `listVersions orders ascending by version`() {
    val first = CollegesDao.upsert(session, newCollege(800700, name = "v1")).getOrThrow()
    CollegesDao.upsert(session, newCollege(800700, name = "v2")).getOrThrow()
    CollegesDao.upsert(session, newCollege(800700, name = "v3")).getOrThrow()
    CollegesDao.upsert(session, newCollege(800700, name = "v4")).getOrThrow()

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2, 3, 4), history.map { it.version })
  }

  @Test
  fun `listByIds returns the full rows for the given ids and skips absent ones`() {
    val a = seed(newCollege(800750, name = "Alpha U"))
    val b = seed(newCollege(800751, name = "Beta U"))
    seed(newCollege(800752, name = "Gamma U"))

    val rows = CollegesDao.listByIds(session, listOf(a, b, CollegeId(UUID.randomUUID()))).getOrThrow()
    assertEquals(setOf("Alpha U", "Beta U"), rows.map { it.name }.toSet())
    assertEquals(9000, rows.first { it.id == a }.netPricePerYearIncomeQ1Usd, "the full cost columns must ride the row")

    assertEquals(emptyList(), CollegesDao.listByIds(session, emptyList()).getOrThrow(), "empty ids short-circuit")
  }

  @Test
  fun `physical delete on colleges is rejected`() {
    val college = CollegesDao.upsert(session, newCollege(800800)).getOrThrow()
    assertFailsWith<SQLException> {
      connection.prepareStatement("DELETE FROM colleges WHERE id = ?").use { stmt ->
        stmt.setObject(1, college.id.value)
        stmt.executeUpdate()
      }
    }
  }

  @Test
  fun `updating id or created_at on colleges is rejected`() {
    val college = CollegesDao.upsert(session, newCollege(800900)).getOrThrow()
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE colleges SET id = ? WHERE id = ?").use { stmt ->
        stmt.setObject(1, UUID.randomUUID())
        stmt.setObject(2, college.id.value)
        stmt.executeUpdate()
      }
    }
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE colleges SET created_at = NOW() + INTERVAL '1 day' WHERE id = ?").use { stmt ->
        stmt.setObject(1, college.id.value)
        stmt.executeUpdate()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // searchByName (RFC 137)
  // ---------------------------------------------------------------------------

  @Test
  fun `searchByName matches case-insensitive substrings`() {
    seed(newCollege(810100, name = "Columbia University"))
    seed(newCollege(810101, name = "University of Michigan"))

    val matches = CollegesDao.searchByName(session, "columbia", 25).getOrThrow()
    assertEquals(listOf("Columbia University"), matches.map { it.name })
    assertEquals("Townsville", matches.single().city)
    assertEquals("CA", matches.single().state)
  }

  @Test
  fun `searchByName orders prefix matches first, then enrollment desc, then name`() {
    seed(newCollege(810200, name = "District of Columbia College", undergradEnrollmentHeadcount = 90000))
    seed(newCollege(810201, name = "Columbia College", undergradEnrollmentHeadcount = 900))
    seed(newCollege(810202, name = "Columbia University", undergradEnrollmentHeadcount = 30000))
    seed(newCollege(810203, name = "Columbia Bible College", undergradEnrollmentHeadcount = null))

    val matches = CollegesDao.searchByName(session, "Columbia", 25).getOrThrow()
    assertEquals(
      listOf("Columbia University", "Columbia College", "Columbia Bible College", "District of Columbia College"),
      matches.map { it.name },
    )
  }

  @Test
  fun `searchByName escapes LIKE wildcards in the query`() {
    seed(newCollege(810300, name = "A percent % College"))
    seed(newCollege(810301, name = "A plain College"))
    seed(newCollege(810302, name = "Under_score College"))
    seed(newCollege(810303, name = "Underscore College"))

    // The ILIKE arm is escaped: '%' must not act as a wildcard, so the literal
    // '%' name matches. The one-keystroke arm sees the query as the single word
    // "percent" (the '%' is not a word character), which "A plain College" has
    // no word within one keystroke of — so the literal row comes first.
    val percent = CollegesDao.searchByName(session, "percent %", 25).getOrThrow()
    assertEquals("A percent % College", percent.first().name)

    // An unescaped underscore would ALSO match "Underscore" on the ILIKE arm.
    // Under RFC 139 the trigram arms surfaced "Underscore College" regardless
    // (trigrams ignore '_'), so the literal-match guarantee could only be
    // ranking; with every arm now exact (RFC 146) it is EXCLUSIVITY again:
    // "Under_score" splits into the words "under" and "score", and
    // "underscore" is nowhere near one keystroke from either.
    val underscore = CollegesDao.searchByName(session, "Under_score", 25).getOrThrow()
    assertEquals(listOf("Under_score College"), underscore.map { it.name })

    val backslash = CollegesDao.searchByName(session, "\\", 25).getOrThrow()
    assertEquals(emptyList(), backslash.map { it.name })
  }

  @Test
  fun `searchByName applies the limit`() {
    for (u in 810400..810410) seed(newCollege(u, name = "Limit U $u"))
    val matches = CollegesDao.searchByName(session, "Limit U", 3).getOrThrow()
    assertEquals(3, matches.size)
  }

  // ---------------------------------------------------------------------------
  // searchByName — the one-keystroke rule (RFC 146)
  //
  // The RFC 139 threshold-pinning test is deleted with the thresholds: pg_trgm
  // is gone, so there is no GUC left for a hostile session to be hostile about.
  // ---------------------------------------------------------------------------

  /**
   * Every single-keystroke variant of [word]: all 25 other letters substituted
   * at every position, all 26 letters inserted at every position (both ends
   * included), every single deletion, and every adjacent transposition. Some
   * variants coincide (inserting 'l' either side of an existing 'l'); they are
   * kept rather than deduplicated so the count is an exact, checkable formula
   * and a broken generator cannot silently test nothing.
   */
  private fun keystrokeVariants(word: String): List<String> {
    val variants = mutableListOf<String>()
    for (i in word.indices) {
      for (ch in 'a'..'z') if (ch != word[i]) variants += word.substring(0, i) + ch + word.substring(i + 1)
    }
    for (i in 0..word.length) {
      for (ch in 'a'..'z') variants += word.substring(0, i) + ch + word.substring(i)
    }
    for (i in word.indices) variants += word.substring(0, i) + word.substring(i + 1)
    for (i in 0 until word.length - 1) {
      variants += word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
    }
    return variants
  }

  /** 25n substitutions + 26(n+1) insertions + n deletions + (n-1) transpositions. */
  private fun expectedVariantCount(word: String): Int = 25 * word.length + 26 * (word.length + 1) + word.length + (word.length - 1)

  /**
   * THE property test — the one that would have caught the RFC 139 defect.
   *
   * Recall is a theorem under RFC 146 ("a query one keystroke off some word of
   * a name matches that name"), so this is its proof obligation, not a sample:
   * every single-keystroke variant of a distinctive word of each fixture name
   * must find that college. RFC 139's only fuzzy test used the two-word
   * "Amhurst Colege" and passed for the wrong reason; no single-word typo was
   * ever tested, and single-word typos were exactly what was broken.
   */
  @Test
  fun `searchByName finds the college for EVERY single-keystroke variant of a distinctive name word`() {
    val fixtures =
      listOf(
        "Amherst College" to "amherst",
        "Stanford University" to "stanford",
        "Harvard University" to "harvard",
        "University of California-Berkeley" to "berkeley",
        "Cornell University" to "cornell",
        "University of Missouri-Columbia" to "missouri",
      )
    fixtures.forEachIndexed { i, (name, _) -> seed(newCollege(870100 + i, name = name)) }

    var checked = 0
    val failures = mutableListOf<String>()
    for ((name, word) in fixtures) {
      val variants = keystrokeVariants(word)
      assertEquals(expectedVariantCount(word), variants.size, "the generator must produce every variant of [$word]")
      for (variant in variants) {
        checked++
        val found = CollegesDao.searchByName(session, variant, 25).getOrThrow().map { it.name }
        if (name !in found) failures += "[$variant] found $found, expected [$name]"
      }
    }

    // 53n + 25 per word over lengths 7,8,7,8,7,8 — asserted so a generator that
    // silently produced nothing could not pass this test vacuously.
    assertEquals(2535, checked, "the property must be checked over every generated variant")
    assertTrue(failures.isEmpty(), "${failures.size} of $checked variants did not find their college: ${failures.take(20)}")
  }

  /**
   * Optimal string alignment distance — an independent reference for the
   * differential test below, written from the definition (the classic
   * Damerau-Levenshtein matrix with the adjacent-transposition arm) and sharing
   * no code, and no Postgres, with `one_keystroke_off`.
   */
  private fun osaDistance(
    a: String,
    b: String,
  ): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
      for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
          d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
      }
    }
    return d[a.length][b.length]
  }

  /**
   * Differential test: the SQL predicate against the reference implementation
   * over 2,490 pairs — 1,690 exhaustive single-keystroke mutations of real name
   * words plus 800 random pairs from a fixed seed (which are overwhelmingly far
   * apart, and are the half that would catch a predicate matching too much).
   * Batched into ONE round trip as a bound VALUES list; the pair text is bound,
   * never interpolated.
   */
  @Test
  fun `one_keystroke_off agrees with an independent OSA reference over thousands of pairs`() {
    val words = listOf("amherst", "stanford", "harvard", "berkeley")
    val pairs = mutableListOf<Pair<String, String>>()
    for (word in words) for (variant in keystrokeVariants(word)) pairs += word to variant

    val random = java.util.Random(146)
    val alphabet = ('a'..'z').toList()
    repeat(800) {
      fun randomString(): String = (0 until 1 + random.nextInt(10)).map { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
      pairs += randomString() to randomString()
    }
    assertEquals(2490, pairs.size, "the pair corpus must be the size this test claims")
    // Composition, not just size: without genuine negatives a predicate that
    // returned TRUE unconditionally would pass this test, and the negatives are
    // exactly the half that catches a predicate matching too much.
    // The seed is fixed and the generator deterministic, so this is a FACT
    // about this corpus, not a floor chosen to be comfortably below whatever a
    // run produced: 783 of the 800 random pairs are more than one keystroke
    // apart (the other 17 landed within one by chance, and the 1,690 exhaustive
    // variants are all positives by construction). A generator that drifted
    // fails here instead of quietly testing less.
    val negatives = pairs.count { (a, b) -> osaDistance(a, b) > 1 }
    assertEquals(783, negatives, "the random half must really be negatives, or over-matching goes unnoticed")

    val values = pairs.joinToString(", ") { "(?, ?)" }
    // Query word first, stored word second — the orientation searchByName calls
    // it in; the second column asserts the predicate is symmetric, which the
    // definition says it is and only a test can hold it to.
    val sql =
      "SELECT a, b, one_keystroke_off(b, a) AS off, one_keystroke_off(a, b) AS reversed " +
        "FROM (VALUES $values) AS t(a, b)"
    val mismatches = mutableListOf<String>()
    var compared = 0
    connection.prepareStatement(sql).use { stmt ->
      pairs.forEachIndexed { i, (a, b) ->
        stmt.setString(2 * i + 1, a)
        stmt.setString(2 * i + 2, b)
      }
      stmt.executeQuery().use { rs ->
        while (rs.next()) {
          compared++
          val a = rs.getString("a")
          val b = rs.getString("b")
          val sqlSays = rs.getBoolean("off")
          val reversedSays = rs.getBoolean("reversed")
          val referenceSays = osaDistance(a, b) <= 1
          if (sqlSays != referenceSays) mismatches += "[$a] vs [$b]: sql=$sqlSays reference=${osaDistance(a, b)}"
          if (reversedSays != sqlSays) mismatches += "[$a] vs [$b]: asymmetric — $sqlSays one way, $reversedSays the other"
        }
      }
    }

    assertEquals(pairs.size, compared, "every pair must come back")
    assertTrue(mismatches.isEmpty(), "${mismatches.size} pairs disagree with the reference: ${mismatches.take(20)}")
  }

  @Test
  fun `searchByName finds a one-keystroke typo and does not surface a two-keystroke neighbour`() {
    // The RFC 146 defect, exactly: word_similarity scored El(mhurst) 0.625 and
    // Amherst 0.455, so "Amhurst" returned Elmhurst University and missed
    // Amherst College entirely. "amhurst" is one keystroke from "amherst" and
    // two from "elmhurst", so the rule admits the first and excludes the second.
    seed(newCollege(871100, name = "Amherst College"))
    seed(newCollege(871101, name = "Elmhurst University", undergradEnrollmentHeadcount = 90000))
    seed(newCollege(871102, name = "Hampshire College"))

    val matches = CollegesDao.searchByName(session, "Amhurst", 25).getOrThrow().map { it.name }
    assertEquals(listOf("Amherst College"), matches)
  }

  @Test
  fun `searchByName matches a typo in every word of a multi-word query, and only then`() {
    // The quantifier is `for all` query words: "amhurst"→"amherst" and
    // "colege"→"college" are one keystroke each, while "amhurst"→"elmhurst" is
    // two — so the shared "colege" cannot drag Elmhurst College in. (An
    // any-word rule would return every college in the corpus for "colege".)
    seed(newCollege(871200, name = "Amherst College"))
    seed(newCollege(871201, name = "Elmhurst College"))

    val matches = CollegesDao.searchByName(session, "Amhurst Colege", 25).getOrThrow().map { it.name }
    assertEquals(listOf("Amherst College"), matches)

    // …and the single word on its own legitimately matches both.
    val bothColleges = CollegesDao.searchByName(session, "Colege", 25).getOrThrow().map { it.name }
    assertEquals(setOf("Amherst College", "Elmhurst College"), bothColleges.toSet())
  }

  @Test
  fun `searchByName finds a transposition and a deletion`() {
    seed(newCollege(871300, name = "Stanford University"))
    seed(newCollege(871301, name = "Harvard University"))

    // Stanfrod: an adjacent transposition, which plain Levenshtein scores 2 —
    // the second arm of one_keystroke_off is what covers it.
    assertEquals(
      "Stanford University",
      CollegesDao
        .searchByName(session, "Stanfrod", 25)
        .getOrThrow()
        .first()
        .name,
    )
    // Harvad: a deletion.
    assertEquals(
      "Harvard University",
      CollegesDao
        .searchByName(session, "Harvad", 25)
        .getOrThrow()
        .first()
        .name,
    )
  }

  @Test
  fun `searchByName finds a nickname through the curated aliases`() {
    seed(newCollege(820200, name = "University of Missouri-Columbia"))
    seed(newCollege(820201, name = "University of Central Missouri"))
    setAliases(820200, listOf("Mizzou", "University of Missouri"))

    // The alias is a word of the search text, so the exact-word arm finds it —
    // the nickname mechanism needs no scoring of its own (RFC 139 + 146).
    val matches = CollegesDao.searchByName(session, "Mizzou", 25).getOrThrow()
    assertEquals("University of Missouri-Columbia", matches.first().name)

    // And a nickname one keystroke off still finds it.
    assertEquals(
      "University of Missouri-Columbia",
      CollegesDao
        .searchByName(session, "Mizou", 25)
        .getOrThrow()
        .first()
        .name,
    )
  }

  @Test
  fun `searchByName substring arm ranges over alias text, not just the name`() {
    // "izzo" is an exact substring of the alias "Mizzou" but of no college
    // name, and it is two keystrokes from every word of the search text — so
    // only the ILIKE arm over college_search_text(name, aliases) can find it.
    seed(newCollege(820400, name = "University of Missouri-Columbia"))
    setAliases(820400, listOf("Mizzou"))

    val matches = CollegesDao.searchByName(session, "izzo", 25).getOrThrow()
    assertEquals(listOf("University of Missouri-Columbia"), matches.map { it.name })
  }

  @Test
  fun `searchByName finds a short fragment through the substring arm`() {
    seed(newCollege(871400, name = "Amherst College"))
    seed(newCollege(871401, name = "Stanford University"))

    val matches = CollegesDao.searchByName(session, "Amh", 25).getOrThrow()
    assertEquals(listOf("Amherst College"), matches.map { it.name })
  }

  @Test
  fun `searchByName finds a multi-word alias fragment`() {
    seed(newCollege(820300, name = "University of Massachusetts-Amherst"))
    setAliases(820300, listOf("UMass Amherst", "UMass"))

    val matches = CollegesDao.searchByName(session, "UMass Amherst", 25).getOrThrow()
    assertEquals("University of Massachusetts-Amherst", matches.first().name)
  }

  @Test
  fun `searchByName ranks a rule match above a substring-only match, whatever the enrollment`() {
    // Neither name is a prefix of the query, so the tie is broken by the summed
    // per-word distance alone: an exact word scores 0, a row reachable only
    // through the substring arm scores 2 — below the rule, and below it even
    // with 900x the enrollment.
    seed(newCollege(871500, name = "Northamherstville Academy", undergradEnrollmentHeadcount = 90000))
    seed(newCollege(871501, name = "The Amherst Institute", undergradEnrollmentHeadcount = 100))

    val matches = CollegesDao.searchByName(session, "amherst", 25).getOrThrow().map { it.name }
    assertEquals(listOf("The Amherst Institute", "Northamherstville Academy"), matches)
  }

  @Test
  fun `searchByName with no word characters at all matches nothing, not everything`() {
    // "%%%" splits into ZERO words, and "every query word matches" is vacuously
    // true over an empty set — the guard is what keeps that from returning the
    // whole corpus. The substring arm still applies, and matches nothing here.
    seed(newCollege(871600, name = "Amherst College"))
    seed(newCollege(871601, name = "Stanford University"))

    assertEquals(emptyList(), CollegesDao.searchByName(session, "%%%", 25).getOrThrow().map { it.name })
    assertEquals(emptyList(), CollegesDao.searchByName(session, "---", 25).getOrThrow().map { it.name })
  }

  @Test
  fun `rebuildNameWords derives the word set wholesale and follows the current names`() {
    seed(newCollege(871700, name = "Amherst College"))
    setAliases(871700, listOf("Lord Jeffs"))

    assertEquals(
      listOf("amherst", "college", "jeffs", "lord"),
      nameWordsFor(871700),
      "words come from lower(college_search_text(name, aliases)) split on [^a-z0-9]+",
    )

    // A renamed college keeps no stale words: the rebuild is wholesale.
    seed(newCollege(871700, name = "Hampshire College"))
    assertEquals(listOf("college", "hampshire", "jeffs", "lord"), nameWordsFor(871700))

    // Rebuilding again writes the same set: the derivation is a function of the
    // rows, not an accumulation.
    val rows = rebuildNameWords()
    assertEquals(4, rows)
    assertEquals(listOf("college", "hampshire", "jeffs", "lord"), nameWordsFor(871700))
  }

  /** The stored words of one college, alphabetical, read straight from the derived table. */
  private fun nameWordsFor(ipedsUnitId: Int): List<String> =
    connection
      .prepareStatement(
        "SELECT nw.word FROM college_name_words nw JOIN colleges c ON c.id = nw.college_id " +
          "WHERE c.ipeds_unit_id = ? ORDER BY nw.word",
      ).use { stmt ->
        stmt.setInt(1, ipedsUnitId)
        stmt.executeQuery().use { rs ->
          val words = mutableListOf<String>()
          while (rs.next()) words += rs.getString("word")
          words
        }
      }

  // ---------------------------------------------------------------------------
  // nonNullCounts (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `nonNullCounts counts only allowlisted columns and refuses anything else`() {
    seed(newCollege(825100, admissionRateShare = 0.4))
    seed(newCollege(825101, admissionRateShare = null))

    val counts = CollegesDao.nonNullCounts(session, listOf("admission_rate_share", "net_price_per_year_usd")).getOrThrow()
    assertEquals(1, counts["admission_rate_share"])
    assertEquals(2, counts["net_price_per_year_usd"])

    // An identifier outside the allowlist never reaches SQL — including one
    // that would otherwise be a valid injection point.
    val thrown =
      assertFailsWith<IllegalArgumentException> {
        CollegesDao.nonNullCounts(session, listOf("admission_rate_share", "1) AS x, (SELECT 1"))
      }
    assertTrue(thrown.message!!.contains("unknown colleges column"), "the refusal names the fault: ${thrown.message}")
    assertFailsWith<IllegalArgumentException> { CollegesDao.nonNullCounts(session, listOf("name")) }
  }

  // ---------------------------------------------------------------------------
  // updateAliases (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `updateAliases applies a change with a version bump and history row`() {
    val id = seed(newCollege(830100))
    val outcome = CollegesDao.updateAliases(session, 830100, listOf("Nickname U")).getOrThrow()
    assertEquals(CollegesDao.AliasUpdateOutcome.APPLIED, outcome)

    val college = CollegesDao.findById(session, id).getOrThrow()
    assertEquals(listOf("Nickname U"), college.aliases)
    assertEquals(2, college.version)

    val history = CollegesDao.listVersions(session, id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    assertEquals(listOf("Nickname U"), history.last().entity.aliases)
  }

  @Test
  fun `updateAliases with the same aliases is suppressed - no bump, no history`() {
    val id = seed(newCollege(830200))
    CollegesDao.updateAliases(session, 830200, listOf("Steady", "Nick")).getOrThrow()

    val again = CollegesDao.updateAliases(session, 830200, listOf("Steady", "Nick")).getOrThrow()
    assertEquals(CollegesDao.AliasUpdateOutcome.UNCHANGED, again)

    val college = CollegesDao.findById(session, id).getOrThrow()
    assertEquals(2, college.version, "the suppressed re-apply must not bump")
    assertEquals(2, CollegesDao.listVersions(session, id).getOrThrow().size)
  }

  @Test
  fun `updateAliases on an unknown ipeds_unit_id reports it, never throws`() {
    val outcome = CollegesDao.updateAliases(session, 999999, listOf("Ghost U")).getOrThrow()
    assertEquals(CollegesDao.AliasUpdateOutcome.UNKNOWN_IPEDS_UNIT_ID, outcome)
  }

  @Test
  fun `scorecard upsert of a changed row preserves curated aliases`() {
    seed(newCollege(830300, name = "Before"))
    CollegesDao.updateAliases(session, 830300, listOf("Kept")).getOrThrow()

    val after = CollegesDao.upsert(session, newCollege(830300, name = "After")).getOrThrow()
    assertEquals(listOf("Kept"), after.aliases, "the Scorecard upsert must not clobber curated aliases")
  }

  // ---------------------------------------------------------------------------
  // search — sortBy / credentialLevel / totalMatches (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `search sortBy admission rate ascends with NULLS LAST`() {
    seed(newCollege(840100, admissionRateShare = 0.9))
    seed(newCollege(840101, admissionRateShare = 0.1))
    seed(newCollege(840102, admissionRateShare = null))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.ADMISSION_RATE_SHARE_ASC, limit = 25)
    val matches = CollegesDao.search(session, query).page().matches
    assertEquals(listOf(840101, 840100, 840102), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search sortBy net price ascends with NULLS LAST`() {
    seed(newCollege(840200, netPricePerYearUsd = 30000))
    seed(newCollege(840201, netPricePerYearUsd = null))
    seed(newCollege(840202, netPricePerYearUsd = 5000))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.NET_PRICE_PER_YEAR_USD_ASC, limit = 25)
    val matches = CollegesDao.search(session, query).page().matches
    assertEquals(listOf(840202, 840200, 840201), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search sortBy graduation rate descends with NULLS LAST`() {
    seed(newCollege(840300, completionRate150pct4yrShare = 0.5))
    seed(newCollege(840301, completionRate150pct4yrShare = null))
    seed(newCollege(840302, completionRate150pct4yrShare = 0.9))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.COMPLETION_RATE_150PCT_4YR_SHARE_DESC, limit = 25)
    val matches = CollegesDao.search(session, query).page().matches
    assertEquals(listOf(840302, 840300, 840301), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search sortBy name ascends with ipeds_unit_id tiebreak`() {
    seed(newCollege(840401, name = "Bravo College"))
    seed(newCollege(840400, name = "Alpha College"))
    seed(newCollege(840403, name = "Same Name College"))
    seed(newCollege(840402, name = "Same Name College"))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.NAME_ASC, limit = 25)
    val matches = CollegesDao.search(session, query).page().matches
    assertEquals(listOf(840400, 840401, 840402, 840403), matches.map { it.ipedsUnitId })
  }

  @Test
  fun `search sortBy never filters - a NULL-keyed row sinks, it does not vanish`() {
    seed(newCollege(840500, netPricePerYearUsd = null))
    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.NET_PRICE_PER_YEAR_USD_ASC, limit = 25)
    val page = CollegesDao.search(session, query).page()
    assertEquals(listOf(840500), page.matches.map { it.ipedsUnitId })
    assertEquals(1, page.totalMatches)
  }

  @Test
  fun `a program filter matches every college the census records the code for`() {
    // `credential_level` is gone (RFC 150 D53): the census the index derives
    // programs from is bachelor's first majors only, so the filter would have
    // been a tautology for "bachelors" and a falsehood for anything else.
    val first = seed(newCollege(850100))
    seedCensusProgram(first, "230101")
    val second = seed(newCollege(850101))
    seedCensusProgram(second, "230101")

    val page = CollegesDao.search(session, CollegeQuery(cipPrefix = "23", limit = 25)).page()
    assertEquals(setOf(850100, 850101), page.matches.map { it.ipedsUnitId }.toSet())
    assertEquals(2, page.totalMatches)
  }

  @Test
  fun `search totalMatches exceeds the returned slice when more rows match`() {
    for (u in 860100..860129) seed(newCollege(u))
    val page = CollegesDao.search(session, CollegeQuery(limit = 5)).page()
    assertEquals(5, page.matches.size)
    assertEquals(30, page.totalMatches)
  }

  // ---------------------------------------------------------------------------
  // The index as the one search path (RFC 150)
  // ---------------------------------------------------------------------------

  /**
   * The acceptance query of brief 0004: "the University of Maine campuses".
   * Five campuses come back and the System Central Office does not — and that
   * exclusion comes from HD.SECTOR, carried onto the index as OUR word
   * `administrative_unit` (D61b), never from a name heuristic.
   */
  @Test
  fun `the Maine query returns the five campuses and not the System Central Office`() {
    val campuses =
      listOf(
        900001 to "University of Maine",
        900002 to "University of Maine at Augusta",
        900003 to "University of Maine at Farmington",
        900004 to "University of Maine at Machias",
        900005 to "University of Maine at Presque Isle",
      )
    for ((unit, name) in campuses) {
      seed(newCollege(unit, name = name, state = "ME"))
      seedIpeds(unit, sector = 1)
    }
    seed(newCollege(900006, name = "University of Maine System Central Office", state = "ME"))
    seedIpeds(900006, sector = 0)
    rebuildSearchIndex()

    val page = CollegesDao.search(session, CollegeQuery(states = listOf("ME"), limit = 25)).page()
    assertEquals(campuses.map { it.first }.toSet(), page.matches.map { it.ipedsUnitId }.toSet())
    assertEquals(5, page.totalMatches)

    // ...and the exclusion is a DEFAULT, not a wall (D56): ask for it and it is
    // there.
    val withOffice =
      CollegesDao
        .search(session, CollegeQuery(states = listOf("ME"), includeAdministrativeUnits = true, limit = 25))
        .page()
    assertEquals(6, withOffice.totalMatches)
  }

  @Test
  fun `the default universe includes an unknown level and excludes an inactive school`() {
    seed(newCollege(901001, name = "Four Year U"))
    seedIpeds(901001, instLevel = 1)
    seed(newCollege(901002, name = "Unknown Level U"))
    seedIpeds(901002, instLevel = null)
    seed(newCollege(901003, name = "Two Year U"))
    seedIpeds(901003, instLevel = 2)
    seed(newCollege(901004, name = "Closed U"))
    seedIpeds(901004, cyActive = false)
    rebuildSearchIndex()

    // `is_four_year IS NOT FALSE`: unknown is INCLUDED, only a KNOWN two-year
    // is dropped. Inactive is excluded either way.
    val default = CollegesDao.search(session, CollegeQuery(limit = 25)).page()
    assertEquals(setOf(901001, 901002), default.matches.map { it.ipedsUnitId }.toSet())

    val twoYear = CollegesDao.search(session, CollegeQuery(isFourYear = false, limit = 25)).page()
    assertEquals(listOf(901003), twoYear.matches.map { it.ipedsUnitId })

    val closed = CollegesDao.search(session, CollegeQuery(isActive = false, limit = 25)).page()
    assertEquals(listOf(901004), closed.matches.map { it.ipedsUnitId })
  }

  @Test
  fun `an explicit is_four_year excludes an unknown level AND reports how many`() {
    // The DEFAULT keeps unknown-level colleges (the test above). An EXPLICIT
    // level is an ordinary filter over a nullable column, so it drops them --
    // and must say how many it could not judge, never read unknown as "no".
    seed(newCollege(901101, name = "Four Year U"))
    seedIpeds(901101, instLevel = 1)
    seed(newCollege(901102, name = "Unknown Level U"))
    seedIpeds(901102, instLevel = null)
    seed(newCollege(901103, name = "Two Year U"))
    seedIpeds(901103, instLevel = 2)
    rebuildSearchIndex()

    val fourYear = CollegesDao.search(session, CollegeQuery(isFourYear = true, limit = 25)).page()
    assertEquals(listOf(901101), fourYear.matches.map { it.ipedsUnitId })
    assertEquals(mapOf("is_four_year" to 1), fourYear.excludedUnknown)

    val twoYear = CollegesDao.search(session, CollegeQuery(isFourYear = false, limit = 25)).page()
    assertEquals(listOf(901103), twoYear.matches.map { it.ipedsUnitId })
    assertEquals(mapOf("is_four_year" to 1), twoYear.excludedUnknown)
  }

  @Test
  fun `every new attribute filter matches on its slug, excludes NULL, and reports the excluded count`() {
    seed(newCollege(902001, name = "Attributed U"))
    seedIpeds(902001)
    seed(newCollege(902002, name = "Silent U"))
    seedIpeds(
      902002,
      relAffil = null,
      testPolicy = null,
      carnegieBasic = null,
      carnegieSize = null,
      athleticAssoc = emptyList(),
      hasRotc = null,
      hasStudyAbroad = null,
      offersHousing = null,
    )
    rebuildSearchIndex()

    val cases =
      listOf(
        Triple(CollegeQuery(testPolicy = "considered-but-not-required", limit = 25), "test_policy", 902001),
        Triple(CollegeQuery(religiousAffiliation = "jesuit", limit = 25), "religious_affiliation", 902001),
        Triple(CollegeQuery(carnegieClass = "doctoral-very-high", limit = 25), "carnegie_class", 902001),
        Triple(CollegeQuery(carnegieSize = "four-year-large-residential", limit = 25), "carnegie_size", 902001),
        // `athletic_associations` is NOT in this table: its unknown is a NULL
        // column (no IPEDS row), not the empty array this fixture writes, and
        // the test below is what states the difference.
        Triple(CollegeQuery(hasRotc = true, limit = 25), "has_rotc", 902001),
        Triple(CollegeQuery(hasStudyAbroad = true, limit = 25), "has_study_abroad", 902001),
        // `has_housing` is the model's word and `offers_housing` is the index
        // column it reads: the excluded-unknown key is the COLUMN (D55), and
        // the mismatch is exactly what an untested bind would get wrong.
        Triple(CollegeQuery(hasHousing = true, limit = 25), "offers_housing", 902001),
      )
    for ((query, axis, expected) in cases) {
      val page = CollegesDao.search(session, query).page()
      assertEquals(listOf(expected), page.matches.map { it.ipedsUnitId }, "[$axis] must match the attributed college")
      // Unknown is never silently "no": the unjudgeable row is excluded AND
      // counted, once, under the axis it could not be judged on.
      assertEquals(mapOf(axis to 1), page.excludedUnknown, "[$axis] must report its one unjudgeable college")
    }
  }

  @Test
  fun `an athletic_associations filter counts only an UNREPORTED college as unjudgeable`() {
    // Three states, and the middle one is the whole point. Most colleges belong
    // to no athletic association, and that is a KNOWN answer: they are excluded
    // from the matches, and counting them as "could not be judged" would put a
    // number in the thousands into a coach's mouth. Only a college with nothing
    // reported at all is unjudgeable.
    seed(newCollege(902201, name = "NCAA U"))
    seedIpeds(902201, athleticAssoc = listOf(1))
    seed(newCollege(902202, name = "No Association U"))
    seedIpeds(902202, athleticAssoc = emptyList())
    seed(newCollege(902203, name = "Unreported U")) // no college_ipeds row at all
    rebuildSearchIndex()

    val page = CollegesDao.search(session, CollegeQuery(athleticAssociation = "ncaa", limit = 25)).page()
    assertEquals(listOf(902201), page.matches.map { it.ipedsUnitId })
    assertEquals(mapOf("athletic_associations" to 1), page.excludedUnknown)
  }

  @Test
  fun `a cipPrefix filter counts only a college with no program census as unjudgeable`() {
    val known = seed(newCollege(902301, name = "Marine U"))
    seedCensusProgram(known, "260702")
    // Programs KNOWN, none of them under this prefix: a judged NO.
    val other = seed(newCollege(902302, name = "Letters U"))
    seedCensusProgram(other, "230101")
    // No census row at all: the only college that cannot be judged.
    seed(newCollege(902303, name = "Silent U"))
    rebuildSearchIndex()

    val page = CollegesDao.search(session, CollegeQuery(cipPrefix = "2607", limit = 25)).page()
    assertEquals(listOf(902301), page.matches.map { it.ipedsUnitId })
    assertEquals(mapOf("cip_codes" to 1), page.excludedUnknown)
  }

  @Test
  fun `each boolean attribute filter reads its own index column, so a swapped bind cannot pass`() {
    // The table-driven test above seeds every boolean the same way, so it would
    // still pass if `has_study_abroad` and `has_housing` were bound to each
    // other's column. Here the two colleges disagree on exactly one attribute
    // each, and `has_housing` reads a DIFFERENTLY NAMED column
    // (`offers_housing`) — the one bind in the set a rename could silently
    // re-point.
    seed(newCollege(902101, name = "Abroad Only"))
    seedIpeds(902101, hasStudyAbroad = true, offersHousing = false)
    seed(newCollege(902102, name = "Housing Only"))
    seedIpeds(902102, hasStudyAbroad = false, offersHousing = true)
    rebuildSearchIndex()

    val abroad = CollegesDao.search(session, CollegeQuery(hasStudyAbroad = true, limit = 25)).page()
    assertEquals(listOf(902101), abroad.matches.map { it.ipedsUnitId })
    val housing = CollegesDao.search(session, CollegeQuery(hasHousing = true, limit = 25)).page()
    assertEquals(listOf(902102), housing.matches.map { it.ipedsUnitId })

    // And the negative asks are answered too, not treated as "no filter".
    val noAbroad = CollegesDao.search(session, CollegeQuery(hasStudyAbroad = false, limit = 25)).page()
    assertEquals(listOf(902102), noAbroad.matches.map { it.ipedsUnitId })
    val noHousing = CollegesDao.search(session, CollegeQuery(hasHousing = false, limit = 25)).page()
    assertEquals(listOf(902101), noHousing.matches.map { it.ipedsUnitId })
  }

  @Test
  fun `a numeric filter reports one excluded-unknown count per axis, not one per bound`() {
    seed(newCollege(903001, admissionRateShare = 0.3))
    seed(newCollege(903002, admissionRateShare = null))
    seed(newCollege(903003, admissionRateShare = null))

    val page =
      CollegesDao
        .search(session, CollegeQuery(minAdmissionRateShare = 0.1, maxAdmissionRateShare = 0.5, limit = 25))
        .page()
    assertEquals(listOf(903001), page.matches.map { it.ipedsUnitId })
    assertEquals(mapOf("admission_rate_share" to 2), page.excludedUnknown)
  }

  @Test
  fun `a search with no filter that can exclude an unknown reports no counts at all`() {
    seed(newCollege(903100, state = "CA"))
    val page = CollegesDao.search(session, CollegeQuery(states = listOf("CA"), limit = 25)).page()
    // `state` is NOT NULL on the index, so there is nothing it could exclude.
    assertEquals(emptyMap(), page.excludedUnknown)
  }

  @Test
  fun `a subject filter matches through the materialised subject_slugs`() {
    CodebooksDao
      .upsertSubject(session, NewSubject("literature", "Literature", listOf("2301")))
      .getOrThrow()
    CodebooksDao.upsertSubject(session, NewSubject("biology", "Biology", listOf("26"))).getOrThrow()
    val lit = seed(newCollege(904001, name = "Letters College"))
    seedCensusProgram(lit, "230101")
    val bio = seed(newCollege(904002, name = "Science College"))
    seedCensusProgram(bio, "260101")

    val page = CollegesDao.search(session, CollegeQuery(subject = "literature", limit = 25)).page()
    assertEquals(listOf(904001), page.matches.map { it.ipedsUnitId })
    // `programs` still means "the titles that matched your program filter",
    // sourced from `cip_codes` over the returned page only.
    assertEquals(listOf("English Language and Literature, General"), page.matches.single().programTitles)

    // A word the taxonomy does not carry is a NAMED refusal, never an empty
    // result that looks like an answer -- and never a failure, which would tell
    // a family the search broke.
    val unknown =
      CollegesDao.search(session, CollegeQuery(subject = "underwater-basket-weaving", limit = 25)).getOrThrow()
    val refusal = assertIs<CollegeSearchOutcome.UnresolvableProgramFilter>(unknown)
    assertEquals(CollegeSearchOutcome.UnresolvableProgramFilter.Field.SUBJECT, refusal.field)
    assertEquals("underwater-basket-weaving", refusal.value)
    assertEquals(
      CollegeSearchOutcome.UnresolvableProgramFilter.Cause.SUBJECT_NOT_IN_TAXONOMY,
      refusal.cause,
    )
  }

  @Test
  fun `a subject and a cipPrefix that share no CIP code are refused, not answered with a page`() {
    CodebooksDao
      .upsertSubject(session, NewSubject("literature", "Literature", listOf("2301")))
      .getOrThrow()
    // One college offering a literature program AND, separately, an
    // entomology one: it satisfies each clause on its own, and no single
    // program of it satisfies both. The search used to return it.
    val both = seed(newCollege(904201, name = "Two Departments College"))
    seedCensusProgram(both, "230101")
    seedCensusProgram(both, "260702")

    val outcome =
      CollegesDao.search(session, CollegeQuery(subject = "literature", cipPrefix = "2607", limit = 25)).getOrThrow()
    val refusal = assertIs<CollegeSearchOutcome.UnresolvableProgramFilter>(outcome)
    assertEquals(CollegeSearchOutcome.UnresolvableProgramFilter.Field.SUBJECT, refusal.field)
    assertEquals("literature", refusal.value)
    assertEquals("2607", refusal.conflictsWith)
    assertEquals(
      CollegeSearchOutcome.UnresolvableProgramFilter.Cause.SUBJECT_AND_CIP_PREFIX_SHARE_NO_CIP_CODE,
      refusal.cause,
    )

    // An OVERLAPPING pair still searches, and `programs` reports the
    // intersection: the titles that satisfied the whole program filter.
    val page =
      CollegesDao.search(session, CollegeQuery(subject = "literature", cipPrefix = "2301", limit = 25)).page()
    assertEquals(listOf(904201), page.matches.map { it.ipedsUnitId })
    assertEquals(listOf("English Language and Literature, General"), page.matches.single().programTitles)
  }

  @Test
  fun `programs is NULL when no program filter was written, and a list when one was`() {
    val id = seed(newCollege(904301, name = "Quiet College", state = "VT"))
    seedCensusProgram(id, "230101")

    // Nothing was asked about programs, so nothing is reported -- which is not
    // the empty list's "your filter matched none of them".
    val unfiltered = CollegesDao.search(session, CollegeQuery(states = listOf("VT"), limit = 25)).page()
    assertNull(unfiltered.matches.single().programTitles)

    val filtered = CollegesDao.search(session, CollegeQuery(cipPrefix = "2301", limit = 25)).page()
    assertEquals(listOf("English Language and Literature, General"), filtered.matches.single().programTitles)
  }

  @Test
  fun `a subject filter counts as unjudgeable only a college whose programs are unknown`() {
    CodebooksDao
      .upsertSubject(session, NewSubject("literature", "Literature", listOf("2301")))
      .getOrThrow()
    val lit = seed(newCollege(904101, name = "Letters College"))
    seedCensusProgram(lit, "230101")
    // Programs KNOWN, none of them this subject: a judged NO, not an unknown.
    val bio = seed(newCollege(904102, name = "Science College"))
    seedCensusProgram(bio, "260101")
    // No census row at all: this is the only college we cannot judge.
    seed(newCollege(904103, name = "Silent College"))

    val page = CollegesDao.search(session, CollegeQuery(subject = "literature", limit = 25)).page()
    assertEquals(listOf(904101), page.matches.map { it.ipedsUnitId })
    assertEquals(mapOf("subject_slugs" to 1), page.excludedUnknown)
  }

  @Test
  fun `the filter and count statements name no table but college_search_index`() {
    seed(newCollege(905001, state = "CA"))
    preparedSql.clear()

    CollegesDao
      .search(
        session,
        CollegeQuery(states = listOf("CA"), region = "far-west", hasRotc = true, minAdmissionRateShare = 0.1, limit = 25),
      ).getOrThrow()

    // The COUNT statement, whole: the hot path that reads every row to find the
    // matching ones carries no join at all (D60).
    val countSql = preparedSql.single { it.startsWith("SELECT count(*)") }
    assertTrue(countSql.contains("FROM college_search_index"), countSql)
    for (table in listOf("colleges", "college_ipeds", "college_programs", "cip_codes")) {
      assertFalse(countSql.contains(" $table"), "the count must not reach [$table]: $countSql")
    }

    // ...and the FILTER half of the payload query — the subquery the LIMIT
    // applies to — names the index and nothing else. Only what comes back from
    // it joins the source of truth.
    val payloadSql = preparedSql.single { it.contains("FROM (") }
    val filterHalf = payloadSql.substringAfter("FROM (").substringBefore(") i")
    assertTrue(filterHalf.contains("FROM college_search_index"), filterHalf)
    for (table in listOf("colleges", "college_ipeds", "college_programs_census")) {
      assertFalse(filterHalf.contains(" $table"), "the filter must not reach [$table]: $filterHalf")
    }
    // The payload half does reach them, over the returned page only.
    assertTrue(payloadSql.contains("JOIN colleges c ON c.id = i.college_id"))
  }

  @Test
  fun `every coded filter binds the slug it was given, so no code lookup can creep back`() {
    seed(newCollege(905100))
    preparedSql.clear()

    CollegesDao
      .search(
        session,
        CollegeQuery(
          region = "far-west",
          locales = listOf("city-small"),
          control = listOf(InstitutionControl.PUBLIC),
          testPolicy = "considered-but-not-required",
          limit = 25,
        ),
      ).getOrThrow()

    // Every coded clause is an equality against a BOUND parameter — there is no
    // sub-select resolving a word to a number anywhere in the generated text,
    // because after D61 the index stores the word itself.
    for (sql in preparedSql) {
      assertFalse(sql.contains("ipeds_regions"), "a region code lookup came back: $sql")
      assertFalse(sql.contains("nces_locales"), "a locale code lookup came back: $sql")
      assertFalse(sql.contains("admission_test_policies"), "a test-policy code lookup came back: $sql")
    }
    val payloadSql = preparedSql.single { it.contains("FROM (") }
    for (clause in listOf("region = ?", "control = ANY", "test_policy = ?")) {
      assertTrue(payloadSql.contains(clause), "expected the bound clause [$clause] in: $payloadSql")
    }
  }

  @Test
  fun `source_years reports the vintage of the rows returned, and the SPAN when they disagree`() {
    val a = seed(newCollege(906001))
    seedIpeds(906001, surveyYear = 2023)
    seedCensusProgram(a, "230101", surveyYear = 2023)
    rebuildSearchIndex()

    val agreed = CollegesDao.search(session, CollegeQuery(cipPrefix = "2301", limit = 25)).page()
    assertEquals(mapOf("ipeds" to 2023..2023, "programs_census" to 2023..2023), agreed.sourceYears)

    // A second college on a different vintage: the answer describes the rows
    // returned, so a mixed page reports the SPAN. Reporting nothing (what a
    // single-year reading had to do) read exactly like a page with no vintage
    // at all, deleting the one fact worth knowing about the mixture.
    val b = seed(newCollege(906002))
    seedIpeds(906002, surveyYear = 2022)
    seedCensusProgram(b, "230101", surveyYear = 2022)
    rebuildSearchIndex()

    val mixed = CollegesDao.search(session, CollegeQuery(cipPrefix = "2301", limit = 25)).page()
    assertEquals(2, mixed.matches.size)
    assertEquals(mapOf("ipeds" to 2022..2023, "programs_census" to 2022..2023), mixed.sourceYears)

    // A page with no vintage at all still reports NOTHING: absent is "we have
    // none", which the span never has to stand in for. This college has neither
    // a `college_ipeds` row nor a census program, and its own state isolates it.
    seed(newCollege(906003, state = "VT"))
    val unvintaged = CollegesDao.search(session, CollegeQuery(states = listOf("VT"), limit = 25)).page()
    assertEquals(1, unvintaged.matches.size)
    assertEquals(emptyMap(), unvintaged.sourceYears)
  }

  @Test
  fun `an UNBUILT index is refused by both entry points, never answered with zero`() {
    // A migrated-but-never-ingested database has no index, and both entry
    // points READ that index (D53). This test used to assert an empty result
    // was the honest answer; it is not. A full `colleges` table answering "0
    // colleges match" is a false fact in a truthful shape, and nothing warned.
    seed(newCollege(907001, name = "Invisible University"))
    connection.createStatement().use { it.execute("DELETE FROM college_search_index") }
    connection.createStatement().use { it.execute("DELETE FROM college_index_build") }

    val structured = CollegesDao.search(session, CollegeQuery(limit = 25)).getOrThrow()
    assertEquals(CollegeSearchOutcome.IndexNotBuilt, structured)

    val byName = CollegesDao.searchByName(session, "Invisible", 25)
    assertTrue(byName.exceptionOrNull() is SearchIndexNotBuiltException, "got: ${byName.exceptionOrNull()}")
  }

  @Test
  fun `a BUILT index over no colleges answers zero, because that is the truth`() {
    // The other side of the same check, and the reason it reads a build row as
    // well as the rows: an empty `colleges` table has a built index with
    // nothing in it, and refusing THAT search would be its own lie.
    connection.createStatement().use { it.execute("DELETE FROM college_search_index") }
    connection.createStatement().use { it.execute("DELETE FROM college_index_build") }
    CollegesDao
      .insertIndexBuild(
        session,
        NewCollegeIndexBuild(
          startedAt = Instant.now(),
          finishedAt = Instant.now(),
          sources = JsonArray(emptyList()),
          rowsIngested = JsonObject(emptyMap()),
          nameWordsRows = 0,
          searchIndexRows = 0,
          changeSummary = JsonObject(emptyMap()),
          methodVersion = 1,
        ),
      ).getOrThrow()

    val page = CollegesDao.search(session, CollegeQuery(limit = 25)).page()
    assertEquals(emptyList(), page.matches)
    assertEquals(0, page.totalMatches)
    assertEquals(emptyList(), CollegesDao.searchByName(session, "Invisible", 25).getOrThrow())
  }

  @Test
  fun `a college whose region code has no codebook row is still searchable with a null region`() {
    // The LEFT-JOIN discipline of the rebuild, asserted from the SEARCH side:
    // an INNER JOIN there would silently delete colleges from search, which is
    // the worst failure this table can have and the hardest to notice.
    // Region 9 (`other-us-jurisdictions`) is the one codebook code the shared
    // fixture deliberately leaves unseeded, and `colleges.region` keeps a plain
    // 0..9 range check rather than the foreign key `state` and `locale` gained
    // in 0067 — so it is still storable and still unexplained. Deleting the
    // whole `ipeds_regions` table is no longer an option: `us_states` (which
    // every college now references) points at it.
    seed(newCollege(908001, name = "Regionless College", region = 9))

    val page = CollegesDao.search(session, CollegeQuery(limit = 25)).page()
    assertEquals(listOf(908001), page.matches.map { it.ipedsUnitId })
    assertNull(page.matches.single().region)
  }

  // ---------------------------------------------------------------------------
  // insertIndexBuild (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `insertIndexBuild writes one provenance row and returns its id`() {
    val started =
      java.time.Instant
        .now()
        .minusSeconds(60)
    val id =
      CollegesDao
        .insertIndexBuild(
          session,
          ed.unicoach.db.models.NewCollegeIndexBuild(
            startedAt = started,
            finishedAt = started.plusSeconds(41),
            sources =
              Json
                .parseToJsonElement(
                  """[{"file":"institution.csv","sha256":"ab12","bytes":143,"source_arg":"institution.csv"}]""",
                ).jsonArray,
            rowsIngested =
              Json
                .parseToJsonElement(
                  """{"colleges":{"seen":5,"inserted":5,"changed":0,"unchanged":0,"skipped":0}}""",
                ).jsonObject,
            nameWordsRows = null,
            searchIndexRows = null,
            changeSummary =
              Json
                .parseToJsonElement(
                  """{"non_null":{"admission_rate_share":{"before":0,"after":4}},"version_bumps":5}""",
                ).jsonObject,
            methodVersion = 1,
          ),
        ).getOrThrow()

    connection
      .prepareStatement(
        "SELECT method_version, name_words_rows, search_index_rows FROM college_index_build WHERE id = ?",
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next())
          assertEquals(1, rs.getInt("method_version"))
          // `index_rows` became `name_words_rows` in 0064 (RFC 150 D48), and the
          // search index got a column of its own. Both are NULL here: this row
          // is written by hand, not by a run that rebuilt either table.
          rs.getInt("name_words_rows")
          assertTrue(rs.wasNull())
          rs.getInt("search_index_rows")
          assertTrue(rs.wasNull())
        }
      }
  }

  @Test
  fun `insertIndexBuild rejects finished_at before started_at`() {
    val started = java.time.Instant.now()
    val result =
      CollegesDao.insertIndexBuild(
        session,
        ed.unicoach.db.models.NewCollegeIndexBuild(
          startedAt = started,
          finishedAt = started.minusSeconds(1),
          sources = JsonArray(emptyList()),
          rowsIngested = JsonObject(emptyMap()),
          nameWordsRows = null,
          searchIndexRows = null,
          changeSummary = JsonObject(emptyMap()),
          methodVersion = 1,
        ),
      )
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }
}

/**
 * The page a search must have produced. A search now returns a
 * [CollegeSearchOutcome], so a test that expects rows says so once here rather
 * than unwrapping the outcome at forty call sites; a refusal fails loudly
 * instead of being read as an empty page.
 */
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
