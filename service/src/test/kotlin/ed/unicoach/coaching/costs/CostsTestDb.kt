package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.NewCollege
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

  /** The shared bracket dollar figures (`net_price_q1..q5`) [seedCollege] seeds by default — the one home both test classes read. */
  const val NET_PRICE_Q1 = 9000
  const val NET_PRICE_Q2 = 11000
  const val NET_PRICE_Q3 = 14000
  const val NET_PRICE_Q4 = 17000
  const val NET_PRICE_Q5 = 21000

  const val SOURCE_URL: String = "https://example.edu/cds-2024-25.pdf"
  const val ARCHIVE_URL: String = "https://www.collegedata.fyi/schools/example/2024-25"

  /** Truncates every table the cost read touches; each test class calls this from `@BeforeEach`. */
  fun reset() {
    CoachingTestDb.truncate("money_profiles", "college_list_entries", "college_merit_aid", "colleges", "students", "users")
  }

  /** One CDS merit-aid row for [collegeId] (RFC 148 D7), through the shared seeder. */
  fun seedMeritAid(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    freshmenFtTotal: Int? = 2000,
    noNeedMeritCount: Int? = 500,
    noNeedMeritAvg: Int? = 12500,
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) = CoachingTestDb.seedMeritAid(collegeId, sourceYear, freshmenFtTotal, noNeedMeritCount, noNeedMeritAvg, sourceUrl, archiveUrl)

  fun createStudent(): StudentId = CoachingTestDb.createStudent("costs")

  fun seedCollege(
    name: String,
    state: String = "CA",
    control: Int = 1,
    costAttendance: Int? = 40000,
    netPrice: Int? = 20000,
    netPriceQ1: Int? = NET_PRICE_Q1,
    netPriceQ2: Int? = NET_PRICE_Q2,
    netPriceQ3: Int? = NET_PRICE_Q3,
    netPriceQ4: Int? = NET_PRICE_Q4,
    netPriceQ5: Int? = NET_PRICE_Q5,
    tuitionInState: Int? = 12000,
    tuitionOutState: Int? = 30000,
    medianDebt: Int? = 23000,
    medianEarnings: Int? = 55000,
  ): CollegeId {
    val ipedsUnitId = nextIpedsUnitId++
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
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = costAttendance,
          netPrice = netPrice,
          netPriceQ1 = netPriceQ1,
          netPriceQ2 = netPriceQ2,
          netPriceQ3 = netPriceQ3,
          netPriceQ4 = netPriceQ4,
          netPriceQ5 = netPriceQ5,
          tuitionInState = tuitionInState,
          tuitionOutState = tuitionOutState,
          graduationRate = 0.7,
          medianEarnings = medianEarnings,
          medianDebt = medianDebt,
          pctPell = 0.4,
          website = "https://test$ipedsUnitId.edu",
        ),
      ).getOrThrow()
      .id
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
