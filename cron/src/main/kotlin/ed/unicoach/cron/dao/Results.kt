package ed.unicoach.cron.dao

import ed.unicoach.cron.PeriodicJob
import ed.unicoach.cron.PeriodicJobName
import java.time.Instant

/**
 * A `periodic_jobs` row could not be reconstructed from its persisted form: an
 * unknown `job_type` value or a malformed `payload`. Carries the row [name] plus
 * the offending [field]/[value] and the originating cause, so an operator
 * scanning a `list()` failure can find the corrupt row without re-deriving what
 * was queried (mirrors `CorruptPersistedValueException` in `:db`, which is keyed
 * on the value alone; here the row's natural key is what pins it down).
 */
class PeriodicJobRowCorruptException(
  val name: PeriodicJobName,
  val field: String,
  val value: String,
  cause: Throwable? = null,
) : RuntimeException(
    "Periodic job [${name.value}] has a corrupt [$field] value: [$value]",
    cause,
  )

/**
 * A single corrupt `periodic_jobs` row surfaced from a bulk read that otherwise
 * succeeded (see [PeriodicListResult.Success]). Carries the row [name] plus the
 * offending [field]/[value] plus the originating [cause] — the same data
 * [PeriodicJobRowCorruptException] carries — so [PeriodicJobsDao.list] can degrade
 * per-row (return every healthy row and name the corrupt ones, with the malformed
 * payload's detail preserved on [cause]) instead of folding one unreconstructable
 * row into a whole-table [PeriodicListResult.DatabaseFailure].
 */
data class CorruptRow(
  val name: PeriodicJobName,
  val field: String,
  val value: String,
  val cause: PeriodicJobRowCorruptException,
)

/**
 * Outcome of [PeriodicJobsDao.claimDue]. [Claimed] carries the locked row plus
 * the database clock ([dbNow]) read in the same statement, so the scheduler
 * computes the next fire against the DB clock, never a host wall clock
 * (Invariant 1). [NoneDue] means no due, enabled, unlocked row exists.
 *
 * [Corrupt] is a distinct outcome from [DatabaseFailure]: the claim query
 * succeeded and locked a real row, but its persisted form could not be
 * reconstructed (unknown `job_type` / malformed `payload`). It carries the
 * locked row's [name] plus the database clock [dbNow] so the scheduler can park
 * that row (advance `next_run_at` out of the due window) inside the same claim
 * transaction — the same "inert, not fatal" path as an unparseable schedule —
 * instead of backing off the whole tick. A genuine DB/connection failure is a
 * [DatabaseFailure]; only a row that will never reconstruct is [Corrupt].
 */
sealed interface ClaimDueResult {
  data class Claimed(
    val job: PeriodicJob,
    val dbNow: Instant,
  ) : ClaimDueResult

  data object NoneDue : ClaimDueResult

  data class Corrupt(
    val name: PeriodicJobName,
    val dbNow: Instant,
    val cause: PeriodicJobRowCorruptException,
  ) : ClaimDueResult

  class DatabaseFailure(
    val error: Exception,
  ) : ClaimDueResult
}

/**
 * Outcome of [PeriodicJobsDao.park]. Unlike [PeriodicUpdateResult], [Success]
 * carries no reconstructed [PeriodicJob]: park is the one write that runs against
 * a **corrupt** row (unknown `job_type` / malformed `payload`) — reconstructing
 * the returned row would re-throw the very corruption park exists to quarantine.
 * Park only needs to confirm the `next_run_at` write hit the (locked) row, so it
 * reports hit vs. miss from the `was_updated` flag alone.
 */
sealed interface ParkResult {
  data object Success : ParkResult

  data class NotFound(
    val name: PeriodicJobName,
  ) : ParkResult

  class DatabaseFailure(
    val error: Exception,
  ) : ParkResult
}

/**
 * Outcome of [PeriodicJobsDao.setEnabled] — the admin enable/disable toggle, and
 * the operator's remedy for a corrupt row. Like [ParkResult] and unlike
 * [PeriodicUpdateResult], [Success] carries no reconstructed [PeriodicJob]:
 * disable must succeed on a **corrupt** row (unknown `job_type` / malformed
 * `payload`), and reconstructing the returned row would re-throw the corruption
 * the operator is quarantining. Hit vs. miss is read from the `was_updated` flag
 * alone.
 */
sealed interface SetEnabledResult {
  data object Success : SetEnabledResult

  data class NotFound(
    val name: PeriodicJobName,
  ) : SetEnabledResult

  class DatabaseFailure(
    val error: Exception,
  ) : SetEnabledResult
}

/** Outcome of a by-name write ([PeriodicJobsDao.advance]). */
sealed interface PeriodicUpdateResult {
  data class Success(
    val job: PeriodicJob,
  ) : PeriodicUpdateResult

  data class NotFound(
    val name: PeriodicJobName,
  ) : PeriodicUpdateResult

  class DatabaseFailure(
    val error: Exception,
  ) : PeriodicUpdateResult
}

/**
 * Outcome of [PeriodicJobsDao.list]. [Success] degrades per-row: it carries every
 * reconstructable [PeriodicJob] in [jobs] plus a [CorruptRow] for each row that
 * could not be reconstructed (unknown `job_type` / malformed `payload`), so one
 * corrupt row never folds the whole admin table into [DatabaseFailure]. [DatabaseFailure]
 * remains reserved for a genuine DB/connection fault (the query itself failing),
 * never a single unreconstructable row.
 */
sealed interface PeriodicListResult {
  data class Success(
    val jobs: List<PeriodicJob>,
    val corruptRows: List<CorruptRow>,
  ) : PeriodicListResult

  class DatabaseFailure(
    val error: Exception,
  ) : PeriodicListResult
}

/**
 * Outcome of [PeriodicJobsDao.findByName]. [Corrupt] mirrors [ClaimDueResult.Corrupt]:
 * the row was found but its persisted form could not be reconstructed (unknown
 * `job_type` / malformed `payload`), so the admin detail path can report a corrupt
 * row (name + reason) instead of folding into a whole-page [DatabaseFailure]. A
 * genuine DB/connection fault is still [DatabaseFailure]; only an unreconstructable
 * (but present) row is [Corrupt].
 */
sealed interface PeriodicFindResult {
  data class Success(
    val job: PeriodicJob,
  ) : PeriodicFindResult

  data class NotFound(
    val name: PeriodicJobName,
  ) : PeriodicFindResult

  data class Corrupt(
    val name: PeriodicJobName,
    val cause: PeriodicJobRowCorruptException,
  ) : PeriodicFindResult

  class DatabaseFailure(
    val error: Exception,
  ) : PeriodicFindResult
}
