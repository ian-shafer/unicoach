package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

  /**
   * THE WINDOW A CYCLE'S DATES MAY FALL IN, which is the bound
   * `fafsa_cycle_opens_on` states in SQL and the `fafsa/02` generator states in
   * python. This is the Kotlin home of it, and the cases sit on the two edges
   * for the same reason every case above does.
   *
   * The lower edge is the one that earns the rule: 30 June 2026 is the last day
   * of the 2025-26 cycle, and a 2026-27 row carrying it is last cycle's date
   * typed under this cycle's key -- the one mistake that makes the deadline
   * table worse than no table, because a family acts on it. Probing with a date
   * two cycles back instead would pass against a window a whole year too wide,
   * which is exactly the vacuous test RFC 215's review caught.
   */
  @Test
  fun `a cycle's window opens the July before it and closes the December after it`() {
    assertEquals(LocalDate.of(2025, 7, 1), FafsaFilingCycle.windowOpens(AcademicYear(2026)))
    assertEquals(LocalDate.of(2027, 12, 31), FafsaFilingCycle.windowCloses(AcademicYear(2026)))

    // The 2026-27 form opened on 24 September 2025 and its corrections close on
    // 11 September 2027: both real dates are inside their own window.
    assertTrue(LocalDate.of(2025, 9, 24) >= FafsaFilingCycle.windowOpens(AcademicYear(2026)))
    assertTrue(LocalDate.of(2027, 9, 11) <= FafsaFilingCycle.windowCloses(AcademicYear(2026)))

    // And the day before it opens is the previous cycle's, not this one's.
    assertTrue(LocalDate.of(2025, 6, 30) < FafsaFilingCycle.windowOpens(AcademicYear(2026)))
  }

  /**
   * THE WINDOWS OF TWO NEIGHBOURING CYCLES OVERLAP, and the schema says why at
   * length: the 2026-27 corrections deadline falls eleven months AFTER the
   * 2027-28 form opens, so no pair of dates separates the two. A window tight
   * enough to tell them apart would refuse true rows.
   */
  @Test
  fun `neighbouring cycles' windows overlap, and that is not a defect`() {
    assertTrue(FafsaFilingCycle.windowOpens(AcademicYear(2027)) < FafsaFilingCycle.windowCloses(AcademicYear(2026)))
  }

  @Test
  fun `the clock overload reads the same day the date overload does`() {
    val clock = java.time.Clock.fixed(LocalDate.of(2026, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)

    assertEquals(FafsaFilingCycle.currentFilingYear(LocalDate.of(2026, 10, 1)), FafsaFilingCycle.currentFilingYear(clock))
  }
}
