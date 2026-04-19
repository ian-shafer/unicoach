package ed.unicoach.queue

import kotlin.time.Duration

sealed interface BackoffStrategy {
  fun delayFor(attemptNumber: Int): Duration

  data class Exponential(
    val base: Duration,
  ) : BackoffStrategy {
    override fun delayFor(attemptNumber: Int): Duration = base * (1 shl (attemptNumber - 1).coerceAtMost(20))
  }

  data class Fixed(
    val delay: Duration,
  ) : BackoffStrategy {
    override fun delayFor(attemptNumber: Int): Duration = delay
  }
}
