package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationResult
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Month
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The codec between a [PartialDate] and its two storage columns (RFC 209).
 *
 * Pure: no database. The schema's own refusals are proved in
 * `StudentsDaoTest`; this file proves that the Kotlin side agrees with them,
 * which is what makes the pair unable to escape the DAO with a date and a
 * precision that disagree.
 */
class PartialDateColumnsTest {
  @Test
  fun `each precision round-trips through the columns`() {
    val cases =
      listOf(
        PartialDate.YearOnly(Year.of(2028)) to (LocalDate.of(2028, 1, 1) to DatePrecision.YEAR),
        PartialDate.YearAndMonth(Year.of(2028), Month.JUNE) to (LocalDate.of(2028, 6, 1) to DatePrecision.MONTH),
        PartialDate.FullDate(LocalDate.of(2028, 6, 15)) to (LocalDate.of(2028, 6, 15) to DatePrecision.DAY),
      )

    for ((date, columns) in cases) {
      val (expectedOn, expectedPrecision) = columns
      assertEquals(expectedOn, PartialDateColumns.periodStart(date), "period start for $date")
      assertEquals(expectedPrecision, PartialDateColumns.precision(date), "precision for $date")
      assertEquals(
        ValidationResult.Valid(date),
        PartialDateColumns.decode(expectedOn, expectedPrecision.value),
        "decode must return exactly what encode was given, for $date",
      )
    }
  }

  /**
   * THE PERIOD'S OTHER END, and it is a published bound rather than a column.
   *
   * "Through December 2026" admits every December sitting, so an end computed
   * one caller at a time silently shortens somebody's window -- which is exactly
   * what a caller that reached for `periodStart` would do. February is the case
   * a hand-written `withDayOfMonth(30)` gets wrong, and 2028 is a leap year, so
   * both are here.
   */
  @Test
  fun `the period end is the last day the date admits, and never the first`() {
    val cases =
      listOf(
        PartialDate.YearOnly(Year.of(2026)) to LocalDate.of(2026, 12, 31),
        PartialDate.YearAndMonth(Year.of(2026), Month.DECEMBER) to LocalDate.of(2026, 12, 31),
        PartialDate.YearAndMonth(Year.of(2026), Month.FEBRUARY) to LocalDate.of(2026, 2, 28),
        PartialDate.YearAndMonth(Year.of(2028), Month.FEBRUARY) to LocalDate.of(2028, 2, 29),
        PartialDate.FullDate(LocalDate.of(2026, 6, 15)) to LocalDate.of(2026, 6, 15),
      )

    for ((date, expected) in cases) {
      assertEquals(expected, PartialDateColumns.periodEnd(date), "period end for $date")
      assertTrue(
        !PartialDateColumns.periodEnd(date).isBefore(PartialDateColumns.periodStart(date)),
        "a period cannot end before it starts: $date",
      )
    }
  }

  @Test
  fun `a year-precision row can never be read as a month or a day`() {
    val decoded = PartialDateColumns.decode(LocalDate.of(2026, 1, 1), "year")
    val date = (decoded as ValidationResult.Valid).value

    assertEquals(PartialDate.YearOnly(Year.of(2026)), date)
    assertEquals(null, date.month, "a year-only date has no month to leak")
    assertEquals(null, date.day, "a year-only date has no day to leak")
    assertEquals("2026", date.toIso(), "and it says so on the wire")
  }

  @Test
  fun `a date that disagrees with its precision is refused rather than shortened`() {
    // The one danger of storing a real DATE: 2026-03-05 at 'year' precision
    // carries a month nobody recorded. Shortening it would be silent; refusing
    // it is loud, and the schema's is_period_start CHECK means it can only
    // arrive here through corruption anyway.
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 3, 5), "year") is ValidationResult.Invalid)
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 3, 5), "month") is ValidationResult.Invalid)
    // The boundary cases that are NOT corruption: 1 January at year precision,
    // and the 1st at month precision.
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 1, 1), "year") is ValidationResult.Valid)
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 3, 1), "month") is ValidationResult.Valid)
    // A day-precision date is never a period start question.
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 3, 5), "day") is ValidationResult.Valid)
  }

  @Test
  fun `a precision outside the vocabulary is refused`() {
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 1, 1), "decade") is ValidationResult.Invalid)
    assertTrue(PartialDateColumns.decode(LocalDate.of(2026, 1, 1), "") is ValidationResult.Invalid)
    assertEquals(null, DatePrecision.fromValue("YEAR"), "the vocabulary is lower-case, as the domain spells it")
  }
}
