package ed.unicoach.coaching.aid

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.FafsaFilingCycle
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * The two award-year clocks, ASKED TOGETHER (RFC 215).
 *
 * `FederalAidPolicyService.currentAwardYear()` answers "which year's Pell table
 * is in force" and turns over on 1 July. [FafsaFilingCycle.currentFilingYear]
 * answers "which cycle is a family filing" and turns over on 1 October. The
 * FAFSA deadline store reads the second, and the reason it exists at all is
 * that reusing the first would serve last cycle's deadlines for most of the
 * year.
 *
 * THIS TEST LIVES IN `:service` BECAUSE IT IS THE ONLY MODULE THAT CAN SEE
 * BOTH. The first version of it sat in `:db` beside the clock and restated the
 * July rule by hand, because `:db` cannot see `:service` -- and a restated rule
 * keeps asserting a contrast that has stopped existing the day the real rule
 * moves. That is the quiet direction of the failure: not a false alarm, a false
 * reassurance. Here both functions are the real ones.
 */
class FilingCycleVersusAwardYearTest {
  @Test
  fun `the two clocks disagree for nine months of every year, and the FAFSA store reads the filing one`() {
    val disagreements =
      Month.entries.filter { month ->
        val day = LocalDate.of(2026, month, 15)
        FafsaFilingCycle.currentFilingYear(day) != awardYearOn(day)
      }

    // July, August and September are the only months the two rules agree in:
    // both name 2026 there. Everywhere else they differ by one year.
    assertEquals(
      Month.entries - listOf(Month.JULY, Month.AUGUST, Month.SEPTEMBER),
      disagreements,
      "the filing clock and the July-1 award year must not be the same rule",
    )
  }

  @Test
  fun `in November the money being paid and the form being filled in are different years`() {
    // The case a family would feel, and the page defect it prevents: in
    // November 2026 the aid being disbursed is 2026-27, and the form a senior is
    // filling in is the 2027-28 one. A deadline page keyed on the first would
    // print a date that closed months ago.
    val november = LocalDate.of(2026, 11, 15)

    assertEquals(AcademicYear(2026), awardYearOn(november), "the money in force")
    assertEquals(AcademicYear(2027), FafsaFilingCycle.currentFilingYear(november), "the form being filed")
  }

  /** The REAL July-1 rule, read off the service at [day] rather than restated. */
  private fun awardYearOn(day: LocalDate): AcademicYear =
    FederalAidPolicyService(
      CoachingTestDb.database,
      Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
    ).currentAwardYear()
}
