package ed.unicoach.common.util

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth
import kotlin.test.assertEquals

/**
 * The seam's own unit tests (RFC 227). The claim under test is narrow and
 * total: one shape, at every precision, and no entry point that can say more
 * than its argument holds.
 */
class UsDateTest {
  @Test
  fun `a full date is month, day, year`() {
    assertEquals("Sep 12, 2026", UsDate.of(LocalDate.of(2026, 9, 12)))
    // Single-digit day: `d` and not `dd`, so it is "Jun 5", never "Jun 05".
    assertEquals("Jun 5, 2020", UsDate.of(LocalDate.of(2020, 6, 5)))
    // A month whose abbreviation IS its full name keeps its one spelling.
    assertEquals("May 1, 2026", UsDate.of(LocalDate.of(2026, 5, 1)))
  }

  @Test
  fun `a month-precision date is the month and the year, and no day is invented`() {
    assertEquals("Sep 2026", UsDate.of(YearMonth.of(2026, 9)))
    assertEquals("Jun 2028", UsDate.of(YearMonth.of(2028, 6)))
  }

  @Test
  fun `a year-precision date is the year, and there is no month in it to leak`() {
    assertEquals("2028", UsDate.of(Year.of(2028)))
    // RFC 209 stores a year-precision date as the period start, 2026-01-01.
    // Rendered through the year entry point it can never become "Jan".
    assertEquals("2026", UsDate.of(Year.of(2026)))
  }

  @Test
  fun `a month and day with no year renders no year`() {
    assertEquals("Nov 1", UsDate.monthDay(Month.NOVEMBER, 1))
    assertEquals("Feb 29", UsDate.monthDay(Month.FEBRUARY, 29))
    // The month-only case is real Common Data Set reporting: a school that says
    // "applications close in January" and gives no day.
    assertEquals("Jan", UsDate.monthDay(Month.JANUARY, null))
  }

  @Test
  fun `every month abbreviates to exactly one spelling`() {
    // The whole enum, so a locale or pattern change that silently renamed one
    // month fails here rather than in one feature's copy.
    assertEquals(
      listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
      Month.entries.map { UsDate.monthDay(it, null) },
    )
  }

  @Test
  fun `the shapes agree with one another`() {
    // The three precisions of the same moment are prefixes of one another in
    // the way a reader expects: the year is in all three, the month in two.
    val full = UsDate.of(LocalDate.of(2026, 9, 12))
    val month = UsDate.of(YearMonth.of(2026, 9))
    val year = UsDate.of(Year.of(2026))
    assertEquals("Sep 12, 2026", full)
    assertEquals("Sep 2026", month)
    assertEquals("2026", year)
    assertEquals(true, full.startsWith("Sep ") && month.startsWith("Sep "))
    assertEquals(true, full.endsWith(year) && month.endsWith(year))
  }
}
