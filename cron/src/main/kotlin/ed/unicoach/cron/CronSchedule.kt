package ed.unicoach.cron

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The sole seam over `cron-utils`; nothing else in the codebase imports the
 * library. Wraps UNIX 5-field cron parsing and next-fire computation, mapping
 * every failure mode — an unparseable [schedule] or an absent next occurrence —
 * to a [Result.failure] the scheduler surfaces as a logged skip, never a crash.
 *
 * A plain class constructed once at the composition root and constructor-injected
 * into [PeriodicScheduler] — the codebase's pattern for a CPU-bound,
 * library-wrapping utility (mirrors `Argon2Hasher` injected into `AuthService`).
 * A single implementation, so no interface; the seam is the class itself.
 */
class CronSchedule {
  private val parser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

  /** Next fire strictly after [after], per a UNIX 5-field [schedule] in [timezone]. */
  fun nextRunAt(
    schedule: String,
    timezone: ZoneId,
    after: Instant,
  ): Result<Instant> =
    runCatching {
      val cron = parser.parse(schedule)
      val from = ZonedDateTime.ofInstant(after, timezone)
      val next: ZonedDateTime =
        ExecutionTime
          .forCron(cron)
          .nextExecution(from)
          .orElseThrow { IllegalStateException("No next execution for schedule [$schedule] in [$timezone] after [$after]") }
      next.toInstant()
    }
}
