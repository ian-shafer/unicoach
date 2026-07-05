package ed.unicoach.cron

import ed.unicoach.cron.dao.ClaimDueResult
import ed.unicoach.cron.dao.ParkResult
import ed.unicoach.cron.dao.PeriodicJobsDao
import ed.unicoach.cron.dao.PeriodicUpdateResult
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The `:cron` scheduler (RFC 97): a one-minute tick loop that atomically claims
 * due `periodic_jobs` rows and enqueues each onto the queue. The flow is
 * one-directional — cron feeds the queue, never the reverse — so this class is
 * domain-agnostic: it reads a row's `job_type`/`payload` and enqueues, knowing
 * nothing about the work.
 *
 * Lifecycle mirrors [ed.unicoach.queue.QueueWorker]. Each tick drains due rows in
 * a loop until none is due; each row is claimed, advanced, and enqueued in a
 * single [Database.withConnection] transaction, so a due row is enqueued exactly
 * once per window and the schedule never advances without a matching enqueue (and
 * vice versa). The scheduler uses [JobsDao] directly rather than `QueueService`
 * because the enqueue must compose into the claim transaction, and
 * `QueueService.enqueue` opens its own.
 *
 * A row whose `schedule` is unparseable is **inert, not fatal** (RFC 97): it is
 * parked ([brokenRowCooldown] into the future) rather than enqueued, so it drops
 * out of the due window instead of remaining the cheapest-due row every tick and
 * starving every other due job. The drain continues past a parked row to the
 * next due one.
 */
class PeriodicScheduler(
  private val database: Database,
  private val jobsDao: JobsDao,
  private val periodicJobsDao: PeriodicJobsDao,
  private val cronSchedule: CronSchedule = CronSchedule(),
  private val tickInterval: Duration = 1.minutes,
  private val brokenRowCooldown: Duration = 1.hours,
) {
  private val logger = LoggerFactory.getLogger(PeriodicScheduler::class.java)

  private val scopeRef = AtomicReference<CoroutineScope?>(null)

  @Volatile private var isRunning = false
  private var tickJob: Job? = null

  /** One row's disposition within a drain: dispatched, skipped-but-keep-draining, or nothing due. */
  private enum class DispatchOutcome { DISPATCHED, SKIPPED, NONE_DUE }

  fun start(scope: CoroutineScope) {
    if (!scopeRef.compareAndSet(null, scope)) {
      throw IllegalStateException("Scheduler is already started")
    }
    isRunning = true
    tickJob = scope.launch(Dispatchers.IO) { tickLoop() }
  }

  fun stop(timeout: Duration) {
    if (scopeRef.getAndSet(null) == null) return
    isRunning = false
    runBlocking {
      try {
        withTimeout(timeout) {
          tickJob?.cancelAndJoin()
        }
      } catch (e: TimeoutCancellationException) {
        // Cancel only the job this scheduler launched, never the borrowed caller
        // scope — cancelling the scope would tear down everything else on it.
        tickJob?.cancel()
      }
    }
  }

  /**
   * Runs a single tick's dispatch work: drains every currently-due row once. The
   * timed [tickLoop] calls this on each interval; tests drive it directly to
   * exercise one deterministic tick without the timing loop. `isRunning` is set
   * so the drain proceeds when invoked outside [start].
   */
  internal suspend fun tickOnce() {
    isRunning = true
    drainDueRows()
  }

  private suspend fun tickLoop() {
    while (isRunning) {
      try {
        drainDueRows()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // A tick's own failure must not kill the loop; the next tick retries.
        logger.error("Unexpected failure in scheduler tick", e)
      }
      if (!isRunning) break
      delay(tickInterval)
    }
  }

  /**
   * Claims and dispatches due rows one at a time until [claimDue] reports none
   * due. A dispatched **or** skipped (parked) row keeps the drain going; only
   * [DispatchOutcome.NONE_DUE] stops it — so one misconfigured row can never
   * starve the healthy rows behind it (RFC 97 "claims every due row").
   */
  private suspend fun drainDueRows() {
    while (isRunning) {
      if (dispatchOneDueRow() == DispatchOutcome.NONE_DUE) break
    }
  }

  /**
   * Runs one claim→(advance→enqueue | park) transaction and reports its outcome.
   * A [ClaimDueResult.DatabaseFailure] is logged and folded to [DispatchOutcome.NONE_DUE]
   * so the drain backs off this tick (the next tick retries).
   */
  private suspend fun dispatchOneDueRow(): DispatchOutcome =
    database.withConnection { session ->
      when (val claim = periodicJobsDao.claimDue(session)) {
        is ClaimDueResult.NoneDue -> {
          DispatchOutcome.NONE_DUE
        }

        is ClaimDueResult.DatabaseFailure -> {
          logger.error("Failed to claim due periodic jobs", claim.error)
          DispatchOutcome.NONE_DUE
        }

        is ClaimDueResult.Corrupt -> {
          dispatchCorruptRow(session, claim)
        }

        is ClaimDueResult.Claimed -> {
          dispatchClaimed(session, claim)
        }
      }
    }

  /**
   * Parks a locked-but-unreconstructable row (unknown `job_type` / malformed
   * `payload`) and keeps draining — the same "inert, not fatal" contract as an
   * unparseable schedule ([dispatchClaimed]'s park branch), just reached from the
   * claim itself rather than from schedule resolution. Returns
   * [DispatchOutcome.SKIPPED] so the drain proceeds to the next due row instead of
   * letting the corrupt row (never advanced by a plain back-off) stay cheapest-due
   * and starve every healthy row behind it every tick.
   */
  private fun dispatchCorruptRow(
    session: SqlSession,
    claim: ClaimDueResult.Corrupt,
  ): DispatchOutcome {
    logger.error(
      "Skipping periodic job [{}]: corrupt row cannot be reconstructed",
      claim.name.value,
      claim.cause,
    )
    parkBrokenRow(session, claim.name, claim.dbNow)
    return DispatchOutcome.SKIPPED
  }

  /**
   * Dispatches one claimed row in its open claim transaction, reading as three
   * named steps: resolve the next fire, then either park an unresolvable row or
   * advance-and-enqueue a healthy one. A parked row returns [DispatchOutcome.SKIPPED]
   * (drain continues); a dispatched row returns [DispatchOutcome.DISPATCHED].
   */
  private fun dispatchClaimed(
    session: SqlSession,
    claim: ClaimDueResult.Claimed,
  ): DispatchOutcome {
    val job = claim.job
    val nextRunAt = resolveNextRunAt(job, claim.dbNow)
    if (nextRunAt == null) {
      parkBrokenRow(session, job.name, claim.dbNow)
      return DispatchOutcome.SKIPPED
    }
    advanceClaimedRow(session, job, nextRunAt)
    enqueueClaimedJob(session, job, nextRunAt)
    return DispatchOutcome.DISPATCHED
  }

  /**
   * The next fire for [job] after [dbNow], or null if the schedule is unparseable
   * or has no next occurrence — logged as a skip, never thrown (RFC 97 inert row).
   */
  private fun resolveNextRunAt(
    job: PeriodicJob,
    dbNow: Instant,
  ): Instant? =
    cronSchedule.nextRunAt(job.schedule, job.timezone, dbNow).getOrElse { error ->
      logger.error(
        "Skipping periodic job [{}]: unresolvable schedule [{}] in [{}]",
        job.name.value,
        job.schedule,
        job.timezone.id,
        error,
      )
      null
    }

  /**
   * Parks a misconfigured row (identified by [name]) [brokenRowCooldown] past
   * [dbNow] so it leaves the due window instead of being reclaimed as cheapest-due
   * every tick. Shared by both corruption sources — an unparseable schedule and an
   * unreconstructable row — so nothing is enqueued and `last_run_at` is untouched:
   * the row is inert, not run.
   */
  private fun parkBrokenRow(
    session: SqlSession,
    name: PeriodicJobName,
    dbNow: Instant,
  ) {
    val retryAt = dbNow.plus(brokenRowCooldown.toJavaDuration())
    when (val parked = periodicJobsDao.park(session, name, retryAt)) {
      is ParkResult.Success -> {
        logger.warn(
          "Parked misconfigured periodic job [{}] until [{}]; healthy rows continue draining",
          name.value,
          retryAt,
        )
      }

      // The row is locked by this transaction, so it cannot vanish mid-claim.
      is ParkResult.NotFound -> {
        error("Claimed periodic job [${name.value}] not found on park")
      }

      is ParkResult.DatabaseFailure -> {
        throw parked.error
      }
    }
  }

  /** Advances the claimed row's `next_run_at`/`last_run_at` in the claim transaction. */
  private fun advanceClaimedRow(
    session: SqlSession,
    job: PeriodicJob,
    nextRunAt: Instant,
  ) {
    when (val advanced = periodicJobsDao.advance(session, job.name, nextRunAt)) {
      is PeriodicUpdateResult.Success -> {
        Unit
      }

      // The row is locked by this transaction, so it cannot vanish mid-claim.
      is PeriodicUpdateResult.NotFound -> {
        error("Claimed periodic job [${job.name.value}] not found on advance")
      }

      is PeriodicUpdateResult.DatabaseFailure -> {
        throw advanced.error
      }
    }
  }

  /**
   * Enqueues the claimed row's job at the queue's own defaults (the consumer sets
   * its own `maxAttempts` via its `JobTypeConfig`; no producer-side delay). A
   * failed insert throws so the whole claim→advance→enqueue transaction rolls
   * back and the row is reclaimed next tick (all-or-nothing).
   */
  private fun enqueueClaimedJob(
    session: SqlSession,
    job: PeriodicJob,
    nextRunAt: Instant,
  ) {
    val newJob = NewJob(job.jobType, job.payload, maxAttempts = null, delay = null)
    when (val inserted = jobsDao.insert(session, newJob)) {
      is JobInsertResult.Success -> {
        logger.info(
          "Dispatched periodic job [{}] as [{}]; next fire at [{}]",
          job.name.value,
          job.jobType.value,
          nextRunAt,
        )
      }

      is JobInsertResult.DatabaseFailure -> {
        throw inserted.error
      }
    }
  }
}
