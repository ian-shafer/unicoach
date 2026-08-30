package ed.unicoach.coaching.admissions

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FactorRating
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeAdmissionFactors
import ed.unicoach.db.models.NewCollegeDeadline
import ed.unicoach.db.models.StudentId

/**
 * The admissions tests' own fixture, shared by [CollegeAdmissionsServiceTest]
 * and [CollegeAdmissionsChatToolTest]: the CDS seeders and the truncation list
 * this suite needs. The connection, session, statement counter, student and
 * college-list plumbing it shares with the costs suite lives in
 * [CoachingTestDb].
 *
 * Every CDS seeder takes an explicit `sourceYear` with a default, because the
 * cycle IS the subject of several of these tests: the corpus mixes 2024 and
 * 2025 per table per college, and the read must resolve each independently.
 */
object AdmissionsTestDb {
  val database: Database get() = CoachingTestDb.database

  val sqlSession: SqlSession get() = CoachingTestDb.sqlSession

  private var nextIpedsUnitId = 700000

  const val SOURCE_URL: String = "https://example.edu/common-data-set-2024-25.pdf"
  const val ARCHIVE_URL: String = "https://www.collegedata.fyi/schools/example/2024-25"

  /** Truncates every table the admissions read touches; each test class calls this from `@BeforeEach`. */
  fun reset() {
    CoachingTestDb.truncate(
      "college_merit_aid",
      "college_admission_factors",
      "college_deadlines",
      "college_list_entries",
      "colleges",
      "students",
      "users",
    )
  }

  fun createStudent(): StudentId = CoachingTestDb.createStudent("admissions")

  fun seedCollege(
    name: String,
    state: String = "MA",
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
          control = 2,
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = 40000,
          netPrice = 20000,
          netPriceQ1 = 9000,
          netPriceQ2 = 11000,
          netPriceQ3 = 14000,
          netPriceQ4 = 17000,
          netPriceQ5 = 21000,
          tuitionInState = 12000,
          tuitionOutState = 30000,
          graduationRate = 0.7,
          medianEarnings = 55000,
          medianDebt = 23000,
          pctPell = 0.4,
          website = "https://test$ipedsUnitId.edu",
        ),
      ).getOrThrow()
      .id
  }

  fun addToCollegeList(
    student: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
  ) = CoachingTestDb.addToCollegeList(student, collegeId, status)

  /** A listed college in one call — the shape most of these tests want. */
  fun seedListedCollege(
    student: StudentId,
    name: String,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
  ): CollegeId = seedCollege(name).also { addToCollegeList(student, it, status) }

  /** One CDS merit-aid row, through the shared seeder both tools' suites use. */
  fun seedMeritAid(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    freshmenFtTotal: Int? = 2000,
    noNeedMeritCount: Int? = 500,
    noNeedMeritAvg: Int? = 12500,
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) = CoachingTestDb.seedMeritAid(collegeId, sourceYear, freshmenFtTotal, noNeedMeritCount, noNeedMeritAvg, sourceUrl, archiveUrl)

  /**
   * A C7 grid. [interview] defaults to NULL and [alumniRelation] to the
   * school's explicit "not considered": the two facts the render layer must
   * keep apart live in the DEFAULT fixture, so no test can forget them.
   */
  fun seedFactors(
    collegeId: CollegeId,
    sourceYear: Int = 2024,
    interview: FactorRating? = null,
    alumniRelation: FactorRating? = FactorRating.NOT_CONSIDERED,
    rigor: FactorRating? = FactorRating.VERY_IMPORTANT,
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) {
    CdsAdmissionsDao
      .upsertAdmissionFactors(
        sqlSession,
        NewCollegeAdmissionFactors(
          collegeId = collegeId,
          sourceYear = sourceYear,
          rigor = rigor,
          classRank = FactorRating.CONSIDERED,
          gpa = FactorRating.VERY_IMPORTANT,
          testScores = FactorRating.CONSIDERED,
          essay = FactorRating.IMPORTANT,
          recommendations = FactorRating.IMPORTANT,
          interview = interview,
          extracurriculars = FactorRating.IMPORTANT,
          talent = FactorRating.CONSIDERED,
          characterQualities = FactorRating.IMPORTANT,
          firstGeneration = FactorRating.CONSIDERED,
          alumniRelation = alumniRelation,
          geography = FactorRating.CONSIDERED,
          stateResidency = FactorRating.NOT_CONSIDERED,
          religiousAffiliation = FactorRating.NOT_CONSIDERED,
          volunteerWork = FactorRating.CONSIDERED,
          workExperience = FactorRating.CONSIDERED,
          applicantInterest = FactorRating.CONSIDERED,
          sourceUrl = sourceUrl,
          archiveUrl = archiveUrl,
        ),
      ).getOrThrow()
  }

  fun seedDeadline(
    collegeId: CollegeId,
    round: ApplicationRound = ApplicationRound.EARLY_DECISION_1,
    sourceYear: Int = 2024,
    offered: Boolean = true,
    closing: CdsMonthDay? = CdsMonthDay(1, 15),
    notification: CdsMonthDay? = CdsMonthDay(12, 15),
    sourceUrl: String = SOURCE_URL,
    archiveUrl: String? = ARCHIVE_URL,
  ) {
    CdsAdmissionsDao
      .upsertDeadline(
        sqlSession,
        NewCollegeDeadline(
          collegeId = collegeId,
          sourceYear = sourceYear,
          round = round,
          offered = offered,
          closing = closing,
          notification = notification,
          sourceUrl = sourceUrl,
          archiveUrl = archiveUrl,
        ),
      ).getOrThrow()
  }
}
