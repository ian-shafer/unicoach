package ed.unicoach.queue

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class JobTypeConfig(
  val concurrency: Int = 1,
  val maxAttempts: Int = 3,
  val backoffStrategy: BackoffStrategy =
    BackoffStrategy.Exponential(
      base = 2.seconds,
    ),
  val lockDuration: Duration = 1.minutes,
  val delayedJobPollInterval: Duration = 10.seconds,
  val executionTimeout: Duration = 2.minutes,
)
