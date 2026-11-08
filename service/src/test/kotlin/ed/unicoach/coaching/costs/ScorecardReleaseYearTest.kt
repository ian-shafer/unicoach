package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.college.CanonicalMoneyLoader
import ed.unicoach.college.EditedScorecardDictionary
import ed.unicoach.college.ScorecardDictionary
import ed.unicoach.college.ScorecardInstitutionFile
import ed.unicoach.college.SeedScorecardDictionary
import ed.unicoach.college.SeedScorecardYears
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * RFC 274's acceptance, read where a family reads it: fetching and ingesting a
 * newer Scorecard release changes the year [CollegeCostService] states, with no
 * code or seed edit.
 *
 * Runs the real fill against the real database. Every year is read from the
 * committed dictionary or derived from one read there; none is typed.
 */
class ScorecardReleaseYearTest {
  private val service = CollegeCostService(CostsTestDb.database)
  private val loader = CanonicalMoneyLoader(CostsTestDb.database)

  @BeforeEach
  fun resetDatabase() {
    CostsTestDb.reset()
  }

  /** A college whose only money is what the fill writes for it: no IC_AY filing, no fixture rows. */
  private fun seedScorecardOnlyCollege(): CollegeId {
    val collegeId = CostsTestDb.seedCollege("Scorecard Only U", state = "CA", control = CostsTestDb.CONTROL_PUBLIC)
    CoachingTestDb.truncate("price_figures", "cohort_money_stats")
    return collegeId
  }

  private fun ipedsUnitIdOf(collegeId: CollegeId): Int =
    CollegeIpedsDao
      .collegeIdsByIpedsUnitId(CostsTestDb.sqlSession)
      .getOrThrow()
      .entries
      .single { it.value == collegeId }
      .key

  private fun fill(
    institutionCsv: File,
    dictionary: ScorecardDictionary,
  ) {
    runBlocking { loader.fill(institutionCsv, sfa = null, dictionary = dictionary) }
  }

  private fun answerFor(student: StudentId): CollegeCost =
    runBlocking {
      service
        .getForStudent(student, null)
        .getOrThrow()
        .colleges
        .single()
    }

  @Test
  fun `a release re-dating the charges a year later changes the year a family is told`() {
    val collegeId = seedScorecardOnlyCollege()
    val student = CostsTestDb.createStudent()
    CostsTestDb.addToCollegeList(student, collegeId)
    // Out of state, because a Scorecard-only fill withholds the in-state cell:
    // it stages no IPEDS residency tiers to evidence it (RFC 183 D1).
    CostsTestDb.answerResidency(student, "NY")
    val institutionCsv = ScorecardInstitutionFile.forOneCollege(ipedsUnitIdOf(collegeId), CostsTestDb.CONTROL_PUBLIC)
    val committed = SeedScorecardYears.PUBLISHED_CHARGE
    val newer = AcademicYear(committed.firstCalendarYear + 1)

    fill(institutionCsv, SeedScorecardDictionary.dictionary)
    assertEquals(committed.label, answerFor(student).publishedPriceAcademicYear, "the committed dictionary's year")

    fill(institutionCsv, EditedScorecardDictionary.withChargesDated(newer))
    val told = answerFor(student)
    assertEquals(newer.label, told.publishedPriceAcademicYear, "the newer release's year")
    assertEquals(ScorecardInstitutionFile.AMOUNT_USD, told.publishedAmountOf(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD))
  }
}
