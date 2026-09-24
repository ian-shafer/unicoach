package ed.unicoach.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CalendarYearTest {
  @Test
  fun `a year a publisher states as a dollar base is carried as it is`() {
    assertEquals(2024, CalendarYear(2024).year)
    assertEquals(CalendarYear.FIRST_YEAR, CalendarYear(CalendarYear.FIRST_YEAR).year)
    assertEquals(CalendarYear.LAST_YEAR, CalendarYear(CalendarYear.LAST_YEAR).year)
  }

  // The values a transcription slip actually produces -- an unset cell, a sign
  // slip, a two-digit year, a year past the range this type admits. Without the
  // guard each of these was a legal "dollar base" a figure could be spoken in.
  @Test
  fun `a number that names no calendar year is refused`() {
    listOf(0, -5, 24, CalendarYear.FIRST_YEAR - 1, CalendarYear.LAST_YEAR + 1).forEach { year ->
      // `assertFailsWith`'s String parameter is the message printed when the
      // assertion FAILS -- not a claim about the thrown text -- so a quoted
      // fragment there reads as pinning the refusal's wording while pinning
      // nothing. The wording is asserted, on the exception the call returns; the
      // parameter says what went wrong when no exception arrives.
      val refusal = assertFailsWith<IllegalArgumentException>("[$year] must be refused") { CalendarYear(year) }
      assertEquals(
        "[$year] is not a calendar year a publisher states: " +
          "the range is [${CalendarYear.FIRST_YEAR}..${CalendarYear.LAST_YEAR}]",
        refusal.message,
        "the refusal names the value and the range an operator needs to read it",
      )
    }
  }
}
