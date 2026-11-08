package ed.unicoach.db.models

import ed.unicoach.common.util.CalendarYear
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProgramMoneyDollarsTest {
  private val year = CalendarYear(2030)

  @Test
  fun `the door builds the measure with a year exactly where the measure states one`() {
    for (measure in ProgramMoneyMeasure.entries) {
      val (stated, unstated) = ProgramMoneyDollars.ofOrNull(measure, year) to ProgramMoneyDollars.ofOrNull(measure, null)
      if (measure.statesAmountUsdYear) {
        assertEquals(year, assertNotNull(stated, "[$measure] carries its year").amountUsdYear)
        assertEquals(measure, stated.measure)
        assertNull(unstated, "[$measure] without its year is no value")
      } else {
        assertNull(assertNotNull(unstated, "[$measure] needs no year").amountUsdYear)
        assertEquals(measure, unstated.measure)
        assertNull(stated, "[$measure] with a year is no value")
      }
    }
  }

  @Test
  fun `earnings is the one measure that states a dollar year`() {
    assertEquals(
      listOf(ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY),
      ProgramMoneyMeasure.entries.filter { it.statesAmountUsdYear },
    )
  }
}
