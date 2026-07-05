package ed.unicoach.cron

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CronScheduleTest {
  private val utc = ZoneId.of("UTC")
  private val cronSchedule = CronSchedule()

  @Test
  fun `daily 3am returns the next 3am UTC strictly after the instant`() {
    // 2026-07-04T01:00:00Z -> next fire is 2026-07-04T03:00:00Z.
    val after = Instant.parse("2026-07-04T01:00:00Z")
    val next = cronSchedule.nextRunAt("0 3 * * *", utc, after).getOrThrow()
    assertEquals(Instant.parse("2026-07-04T03:00:00Z"), next)
  }

  @Test
  fun `an instant exactly at 3am returns the following day`() {
    // Strictly-after semantics: an instant exactly on the boundary skips to the next occurrence.
    val after = Instant.parse("2026-07-04T03:00:00Z")
    val next = cronSchedule.nextRunAt("0 3 * * *", utc, after).getOrThrow()
    assertEquals(Instant.parse("2026-07-05T03:00:00Z"), next)
  }

  @Test
  fun `quarter-hour schedule returns the next quarter hour`() {
    val after = Instant.parse("2026-07-04T10:07:00Z")
    val next = cronSchedule.nextRunAt("*/15 * * * *", utc, after).getOrThrow()
    assertEquals(Instant.parse("2026-07-04T10:15:00Z"), next)
  }

  @Test
  fun `a non-UTC zone offsets the fire correctly`() {
    // '0 3 * * *' in America/New_York (UTC-4 in July, DST) fires at 07:00 UTC.
    val ny = ZoneId.of("America/New_York")
    val after = Instant.parse("2026-07-04T05:00:00Z")
    val next = cronSchedule.nextRunAt("0 3 * * *", ny, after).getOrThrow()
    assertEquals(Instant.parse("2026-07-04T07:00:00Z"), next)
  }

  @Test
  fun `an unparseable schedule returns a failure`() {
    val result = cronSchedule.nextRunAt("not a cron", utc, Instant.now())
    assertTrue(result.isFailure, "An unparseable schedule must be a Result.failure, not a crash")
  }
}
