package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [FafsaFilingCycle], the clock the `fafsa_filing_deadlines` reads filter on
 * (RFC 215).
 *
 * The BOUNDARY MONTHS are the whole point, so every case here sits on one: the
 * rule is a single comparison and the only way to get it wrong is to get the
 * edge wrong.
 *
 * THE CONTRAST WITH THE JULY-1 AWARD YEAR IS NOT TESTED HERE, deliberately.
 * `:db` cannot see `:service`, so a test here could only restate
 * `FederalAidPolicyService.currentAwardYear()`'s rule from memory -- and a
 * restated rule keeps asserting a contrast that has stopped existing the day
 * the real rule moves, which is the quiet direction of that failure. The case
 * that calls BOTH real functions lives in
 * `service/src/test/.../aid/FilingCycleVersusAwardYearTest.kt`, where both are
 * visible.
 */
class FafsaFilingCycleTest {
  @Test
  fun `September and October of the same year are different cycles`() {
    // The last day before the form opens: a family is still filing THIS award
    // year's FAFSA.
    assertEquals(AcademicYear(2026), FafsaFilingCycle.currentFilingYear(LocalDate.of(2026, 9, 30)))
    // The day it opens, and every day after it until the new year.
    assertEquals(AcademicYear(2027), FafsaFilingCycle.currentFilingYear(LocalDate.of(2026, 10, 1)))
    assertEquals(AcademicYear(2027), FafsaFilingCycle.currentFilingYear(LocalDate.of(2026, 12, 31)))
  }

  @Test
  fun `January through September name the cycle that opened last October`() {
    // A senior who filed in November is still filing the SAME cycle in March;
    // the answer must not roll over at the calendar year.
    assertEquals(AcademicYear(2027), FafsaFilingCycle.currentFilingYear(LocalDate.of(2027, 1, 1)))
    assertEquals(AcademicYear(2027), FafsaFilingCycle.currentFilingYear(LocalDate.of(2027, 3, 2)))
    assertEquals(AcademicYear(2027), FafsaFilingCycle.currentFilingYear(LocalDate.of(2027, 9, 30)))
  }

  @Test
  fun `the clock overload reads the same day the date overload does`() {
    val clock = java.time.Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    assertEquals(FafsaFilingCycle.currentFilingYear(LocalDate.of(2026, 10, 1)), FafsaFilingCycle.currentFilingYear(clock))
  }
}
