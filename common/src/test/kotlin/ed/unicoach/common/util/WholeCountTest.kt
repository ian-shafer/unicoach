package ed.unicoach.common.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WholeCountTest {
  @Test
  fun `zero through nine are spelled out`() {
    assertEquals(
      listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"),
      (0..9).map { WholeCount.inWords(it) },
    )
  }

  @Test
  fun `ten and above are said as the grouped number`() {
    assertEquals("10", WholeCount.inWords(10))
    assertEquals("1,000", WholeCount.inWords(1000))
  }

  @Test
  fun `a negative count is said as the grouped number, never indexed`() {
    assertEquals("-1", WholeCount.inWords(-1))
  }
}
