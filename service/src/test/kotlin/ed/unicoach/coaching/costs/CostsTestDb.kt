package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking

/**
 * The costs tests' own fixture, shared by [CollegeCostServiceTest] and
 * [CollegeCostChatToolTest]: the cost-domain seeders (a college with its
 * published figures, the money-profile answers) and the truncation list this
 * suite needs. The connection, session, statement counter, student and
 * college-list plumbing it shares with the admissions suite live in
 * [CoachingTestDb].
 */
object CostsTestDb {
  val database: Database get() = CoachingTestDb.database

  val sqlSession: SqlSession get() = CoachingTestDb.sqlSession

  /**
   * The one writer both costs test classes seed money-profile answers through
   * -- the real service, so a fixture can never record a state or a band the
   * production write path would have normalised or rejected.
   */
  val moneyProfiles: MoneyProfileService by lazy { MoneyProfileService(database) }

  private var nextIpedsUnitId = 500000

  /** The shared bracket dollar figures (`net_price_per_year_income_q1_usd..q5`) [seedCollege] seeds by default — the one home both test classes read. */
  const val NET_PRICE_PER_YEAR_INCOME_Q1_USD = 9000
  const val NET_PRICE_PER_YEAR_INCOME_Q2_USD = 11000
  const val NET_PRICE_PER_YEAR_INCOME_Q3_USD = 14000
  const val NET_PRICE_PER_YEAR_INCOME_Q4_USD = 17000
  const val NET_PRICE_PER_YEAR_INCOME_Q5_USD = 21000

  /**
   * The shared component figures (RFC 149) [seedCollege] seeds by default -- the
   * one home both test classes read, so an expected arrangement total is
   * written as a sum of these constants rather than as a magic number that
   * silently stops matching the fixture.
   */
  const val HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD = 9000
  const val HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD = 11000
  const val BOOKS_AND_SUPPLIES_PER_YEAR_USD = 1200
  const val OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD = 3000
  const val OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD = 3500
  const val OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD = 2500

  const val SOURCE_URL: String = "https://example.edu/cds-2024-25.pdf"
  const val ARCHIVE_URL: String = "https://www.collegedata.fyi/schools/example/2024-25"

  /** Truncates every table the cost read touches; each test class calls this from `@BeforeEach`. */
  fun reset() {
    CoachingTestDb.truncate(
      "money_profiles",
      "college_list_entries",
      "college_merit_aid",
      // The IPEDS attribute row carries the no-dorms flag the cost read joins
      // (RFC 149), so it is part of this suite's fixture and must be reset with it.
      "college_ipeds",
      "colleges",
      "students",
      "users",
    )
  }

  /**
   * One `college_ipeds` row carrying the on-campus housing flag (RFC 149 D-B).
   *
   * [offersHousing] is nullable on purpose: a row that does not report `IC.ROOM`
   * must read exactly like NO row at all, and a fixture that could not express
   * it would leave that equivalence untested. Every other column is an
   * uninteresting minimum -- this seeder exists for one flag.
   */
  fun seedIpedsHousing(
    ipedsUnitId: Int,
    offersHousing: Boolean?,
  ) = CollegeIpedsDao
    .upsert(
      sqlSession,
      NewCollegeIpeds(
        ipedsUnitId = ipedsUnitId,
        surveyYear = 2023,
        cyActive = true,
        deathYear = null,
        closedAt = null,
        newIpedsUnitId = null,
        instLevel = null,
        ugOffer = null,
        sector = null,
        carnegieBasic = null,
        carnegieSize = null,
        cbsa = null,
        relAffil = null,
        hasRotc = null,
        hasStudyAbroad = null,
        disabilityBand = null,
        registeredDisabilityPercent = null,
        offersHousing = offersHousing,
        housingCapacityHeadcount = null,
        applicationFeeUsd = null,
        athleticAssoc = emptyList(),
        footballConf = null,
        testPolicy = null,
      ),
    ).getOrThrow()

  /** One CDS merit-aid row for [collegeId] (RFC 148 D7), through the shared seeder. */
  fun seedMeritAid(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    firstTimeFullTimeFreshmenHeadcount: Int? = 2000,
    noNeedMeritRecipientsHeadcount: Int? = 500,
    noNeedMeritAverageUsd: Int? = 12500,
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) = CoachingTestDb.seedMeritAid(
    collegeId,
    sourceYear,
    firstTimeFullTimeFreshmenHeadcount,
    noNeedMeritRecipientsHeadcount,
    noNeedMeritAverageUsd,
    sourceUrl,
    archiveUrl,
  )

  fun createStudent(): StudentId = CoachingTestDb.createStudent("costs")

  /**
   * What IPEDS says about one fixture college's on-campus housing (RFC 149 D-B).
   *
   * FOUR cases, not three, because the read must fold two of them together: a
   * college with NO `college_ipeds` row and one whose row leaves `IC.ROOM`
   * unreported are both "not reported", and a fixture that could only express
   * one of them would leave that equivalence unasserted.
   */
  enum class IpedsHousing(
    /** Whether a `college_ipeds` row is written at all. */
    val seedsRow: Boolean,
    /** The `offers_housing` value that row carries; null is a row that does not report `IC.ROOM`. */
    val offersHousing: Boolean?,
  ) {
    /** No `college_ipeds` row at all -- the ordinary case for most colleges. */
    NO_ROW(seedsRow = false, offersHousing = null),

    /** A row that does not report `IC.ROOM`; must read exactly like [NO_ROW]. */
    UNREPORTED(seedsRow = true, offersHousing = null),

    /** `IC.ROOM = 1`: this school offers on-campus housing. */
    OFFERS(seedsRow = true, offersHousing = true),

    /** `IC.ROOM = 2`: this school has no residence halls. */
    DOES_NOT_OFFER(seedsRow = true, offersHousing = false),
  }

  fun seedCollege(
    name: String,
    state: String = "CA",
    control: Int = 1,
    costOfAttendancePerYearUsd: Int? = 40000,
    netPricePerYearUsd: Int? = 20000,
    netPricePerYearIncomeQ1Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q1_USD,
    netPricePerYearIncomeQ2Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q2_USD,
    netPricePerYearIncomeQ3Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q3_USD,
    netPricePerYearIncomeQ4Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q4_USD,
    netPricePerYearIncomeQ5Usd: Int? = NET_PRICE_PER_YEAR_INCOME_Q5_USD,
    tuitionAndFeesInStatePerYearUsd: Int? = 12000,
    tuitionAndFeesOutOfStatePerYearUsd: Int? = 30000,
    medianDebtAtCompletionUsd: Int? = 23000,
    medianEarnings10yAfterEntryUsd: Int? = 55000,
    // The six published cost components (RFC 149). Defaulted to a complete,
    // internally consistent set so an ordinary fixture college renders all
    // three living arrangements; a test that wants a gap nulls the one part it
    // is about.
    housingAndFoodOnCampusPerYearUsd: Int? = HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
    housingAndFoodOffCampusPerYearUsd: Int? = HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
    booksAndSuppliesPerYearUsd: Int? = BOOKS_AND_SUPPLIES_PER_YEAR_USD,
    otherExpensesOnCampusPerYearUsd: Int? = OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
    otherExpensesOffCampusPerYearUsd: Int? = OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
    otherExpensesWithFamilyPerYearUsd: Int? = OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
    ipedsHousing: IpedsHousing = IpedsHousing.NO_ROW,
  ): CollegeId {
    val ipedsUnitId = nextIpedsUnitId++
    // One seeder call, and the case's own data decides what it writes: three
    // arms saying the same thing differently is how a fourth case comes to be
    // added with the wrong value in it.
    if (ipedsHousing.seedsRow) seedIpedsHousing(ipedsUnitId, ipedsHousing.offersHousing)
    return CollegesDao
      .upsert(
        sqlSession,
        NewCollege(
          ipedsUnitId = ipedsUnitId,
          opeid = "00$ipedsUnitId",
          name = name,
          city = "Townsville",
          state = state,
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = control,
          undergradEnrollmentHeadcount = 5000,
          admissionRateShare = 0.5,
          satAverageEquivalentScore = 1200,
          costOfAttendancePerYearUsd = costOfAttendancePerYearUsd,
          netPricePerYearUsd = netPricePerYearUsd,
          netPricePerYearIncomeQ1Usd = netPricePerYearIncomeQ1Usd,
          netPricePerYearIncomeQ2Usd = netPricePerYearIncomeQ2Usd,
          netPricePerYearIncomeQ3Usd = netPricePerYearIncomeQ3Usd,
          netPricePerYearIncomeQ4Usd = netPricePerYearIncomeQ4Usd,
          netPricePerYearIncomeQ5Usd = netPricePerYearIncomeQ5Usd,
          tuitionAndFeesInStatePerYearUsd = tuitionAndFeesInStatePerYearUsd,
          tuitionAndFeesOutOfStatePerYearUsd = tuitionAndFeesOutOfStatePerYearUsd,
          completionRate150pct4yrShare = 0.7,
          medianEarnings10yAfterEntryUsd = medianEarnings10yAfterEntryUsd,
          medianDebtAtCompletionUsd = medianDebtAtCompletionUsd,
          housingAndFoodOnCampusPerYearUsd = housingAndFoodOnCampusPerYearUsd,
          housingAndFoodOffCampusPerYearUsd = housingAndFoodOffCampusPerYearUsd,
          booksAndSuppliesPerYearUsd = booksAndSuppliesPerYearUsd,
          otherExpensesOnCampusPerYearUsd = otherExpensesOnCampusPerYearUsd,
          otherExpensesOffCampusPerYearUsd = otherExpensesOffCampusPerYearUsd,
          otherExpensesWithFamilyPerYearUsd = otherExpensesWithFamilyPerYearUsd,
          pellShare = 0.4,
          website = "https://test$ipedsUnitId.edu",
        ),
      ).getOrThrow()
      .id
      .also {
        // `search_colleges` reads `college_search_index` (RFC 150 D53), derived
        // state the ingest rebuilds in its own phase; a fixture that writes
        // `colleges` directly has to rebuild it or the college is unsearchable.
        CollegesDao.rebuildSearchIndex(sqlSession).getOrThrow()
      }
  }

  /**
   * The money-profile seeders, here rather than per test class: the cost read
   * is a fold of a college row and a money profile, so both halves of the
   * fixture belong in the same home ([CollegeCostServiceTest] and
   * [CollegeCostChatToolTest] otherwise keep byte-identical copies that drift).
   */
  fun answerBand(
    student: StudentId,
    band: IncomeBand,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(band))).getOrThrow()
  }

  fun declineBand(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Decline)).getOrThrow()
    }

  fun answerResidency(
    student: StudentId,
    state: String,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Set(state))).getOrThrow()
  }

  fun declineResidency(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Decline)).getOrThrow()
    }

  fun addToCollegeList(
    student: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
  ) = CoachingTestDb.addToCollegeList(student, collegeId, status)
}
