package ed.unicoach.queue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class BackoffStrategyTest {
  @Test
  fun `exponential backoff calculates correct delays`() {
    val strategy = BackoffStrategy.Exponential(2.seconds)
    assertEquals(2.seconds, strategy.delayFor(1))
    assertEquals(4.seconds, strategy.delayFor(2))
    assertEquals(8.seconds, strategy.delayFor(3))
    assertEquals(16.seconds, strategy.delayFor(4))
  }

  @Test
  fun `exponential backoff caps shift to prevent overflow`() {
    val strategy = BackoffStrategy.Exponential(1.seconds)
    val maxShift = 1 shl 20
    assertEquals(maxShift.seconds, strategy.delayFor(22))
    assertEquals(maxShift.seconds, strategy.delayFor(50))
  }

  @Test
  fun `fixed backoff returns constant delay`() {
    val strategy = BackoffStrategy.Fixed(5.seconds)
    assertEquals(5.seconds, strategy.delayFor(1))
    assertEquals(5.seconds, strategy.delayFor(10))
  }
}
