# SPEC: `queue/src/main/kotlin/ed/unicoach/queue/dao`

## I. Overview

This package is the **queue data access layer**. It owns all SQL interactions with
the `jobs` and `job_attempts` PostgreSQL tables. `JobsDao` is the sole public class;
`Results.kt` defines the sealed result types returned by every `JobsDao` method.
No business logic lives here — only query execution, row mapping, and error
encapsulation.

---

## II. Invariants

- **I-1**: Every public `JobsDao` method MUST accept a `SqlSession` as its first
  parameter. No method manages its own connection or transaction.
- **I-2**: Every public `JobsDao` method MUST return a sealed result type. Raw
  exceptions MUST NOT propagate to callers; all `Exception` instances are caught
  by `executeSafely` and wrapped in a `DatabaseFailure` variant.
- **I-3**: All timestamp arithmetic used for scheduling, locking, and eviction
  MUST delegate to the PostgreSQL `NOW()` function via `?::interval` SQL
  parameters. Application-side `Instant.now()` MUST NOT be used for
  clock-sensitive comparisons in DAO queries.
- **I-4**: `startedAt` passed to `insertAttempt` MUST originate from
  `Job.updatedAt` of the `claimJob` success result — a DB-sourced timestamp
  written by the `update_timestamp()` trigger. Application-side clocks MUST NOT
  be used for `startedAt`.
- **I-5**: `insertAttempt` MUST NOT be called at claim time. It MUST only be
  called after job execution resolves (success or failure).
- **I-6**: `finished_at` on `job_attempts` is NEVER set by the application. It is
  set to `NOW()` by the database at insert time.
- **I-7**: `claimJob` MUST only transition a job from `SCHEDULED` → `RUNNING`.
  Any attempt to claim a job not in `SCHEDULED` status MUST return
  `JobUpdateResult.InvalidState`.
- **I-8**: `completeJob` and `deadLetterJob` MUST only transition a job from
  `RUNNING` → terminal status. Any attempt to update a job not in `RUNNING`
  status MUST return `JobUpdateResult.InvalidState`. Both MUST clear
  `locked_until` to `NULL`.
- **I-9**: `reschedule` MUST only transition a job from `RUNNING` → `SCHEDULED`.
  Any attempt to reschedule a job not in `RUNNING` status MUST return
  `JobUpdateResult.InvalidState`.
- **I-10**: `findNextScheduledJob` MUST use `SELECT ... FOR UPDATE SKIP LOCKED
  LIMIT 1` to prevent concurrent workers from double-claiming the same job.
- **I-11**: `deleteBefore` with an empty `statuses` set MUST return
  `JobDeleteResult.Success(0)` immediately without executing any SQL.
- **I-12**: `deleteByIds` with an empty `ids` list MUST return
  `JobDeleteResult.Success(0)` immediately without executing any SQL.
- **I-13**: `deleteByIds` MUST cascade to `job_attempts` via the `ON DELETE
  CASCADE` foreign key constraint on the `job_attempts.job_id` column — no
  explicit deletion of attempts is required in application code.
- **I-14**: `mapJob` MUST use `JobType.fromValue()` for type-safe deserialization.
  An unrecognized `job_type` string in the database MUST cause an
  `IllegalStateException` (propagated via `error()`), wrapped by `executeSafely`
  as `DatabaseFailure`.

---

## III. Behavioral Contracts

### `JobsDao`

See [`JobsDao.kt`](./JobsDao.kt).

#### `insert(session: SqlSession, newJob: NewJob): JobInsertResult`

- **Side Effects**: Inserts one row into `jobs` with `status = 'SCHEDULED'`.
- **Scheduling**: If `newJob.delay` is non-null, `scheduled_at = NOW() + delay`
  via SQL interval. If null, delay is treated as `0` seconds (`NOW()`).
- **maxAttempts**: If `newJob.maxAttempts` is null, the column is stored as SQL
  `NULL`.
- **Returns**: `JobInsertResult.Success(job)` with the inserted row. Returns
  `JobInsertResult.DatabaseFailure(error)` on any exception.
- **Idempotent**: No.

#### `findNextScheduledJob(session: SqlSession, jobType: JobType): JobFindResult`

- **Side Effects**: Acquires a row-level lock (`FOR UPDATE SKIP LOCKED`) on the
  selected row within the caller's transaction. No data is mutated.
- **Selection criteria**: `status = 'SCHEDULED' AND job_type = ? AND
  scheduled_at <= NOW()`, ordered by `scheduled_at ASC`, limit 1.
- **Returns**: `JobFindResult.Success(job)` if a qualifying job exists.
  `JobFindResult.NotFound(message)` if the queue is empty or no job is due.
  `JobFindResult.DatabaseFailure(error)` on any exception.
- **Idempotent**: Yes (read-only within transaction).

#### `claimJob(session: SqlSession, id: UUID, lockDuration: Duration): JobUpdateResult`

- **Side Effects**: Updates `jobs` row: `status = 'RUNNING'`, `locked_until =
  NOW() + lockDuration`. `locked_until` guards against the stuck-job reaper
  prematurely reclaiming the job.
- **Precondition**: Job MUST be in `SCHEDULED` status.
- **Returns**: `JobUpdateResult.Success(job)` on successful transition.
  `JobUpdateResult.InvalidState(message)` if the job exists but is not
  `SCHEDULED`. `JobUpdateResult.NotFound(message)` if no row with the given `id`
  exists. `JobUpdateResult.DatabaseFailure(error)` on any exception.
- **Idempotent**: No (mutates status and lock).

#### `completeJob(session: SqlSession, id: UUID): JobUpdateResult`

- **Side Effects**: Updates `jobs` row: `status = 'COMPLETED'`, `locked_until =
  NULL`.
- **Precondition**: Job MUST be in `RUNNING` status.
- **Returns**: Same variants as `claimJob`. `InvalidState` if the row exists but
  is not in `RUNNING` status (e.g., already `COMPLETED` or `DEAD_LETTERED`).
  `NotFound` only if no row with the given `id` exists.
- **Idempotent**: No.

#### `deadLetterJob(session: SqlSession, id: UUID): JobUpdateResult`

- **Side Effects**: Updates `jobs` row: `status = 'DEAD_LETTERED'`, `locked_until
  = NULL`.
- **Precondition**: Job MUST be in `RUNNING` status.
- **Returns**: Same variants as `claimJob`. `InvalidState` if the row exists but
  is not in `RUNNING` status (e.g., already `COMPLETED` or `DEAD_LETTERED`).
  `NotFound` only if no row with the given `id` exists.
- **Idempotent**: No.

#### `reschedule(session: SqlSession, id: UUID, delay: Duration): JobUpdateResult`

- **Side Effects**: Updates `jobs` row: `status = 'SCHEDULED'`, `scheduled_at =
  NOW() + delay` (SQL interval), `locked_until = NULL`.
- **Precondition**: Job MUST be in `RUNNING` status.
- **Returns**: Same variants as `claimJob`. `InvalidState` if not `RUNNING`.
- **Idempotent**: No.

#### `insertAttempt(session: SqlSession, jobId: UUID, attemptNumber: Int, startedAt: Instant, status: AttemptStatus, errorMessage: String?): AttemptInsertResult`

- **Side Effects**: Inserts one row into `job_attempts`. `finished_at` is set by
  the database to `NOW()`.
- **startedAt**: MUST be taken from `Job.updatedAt` of the preceding `claimJob`
  success result.
- **errorMessage**: May be `null` for successful attempts; stored as SQL `NULL`.
- **Returns**: `AttemptInsertResult.Success(attempt)` with the inserted row.
  `AttemptInsertResult.DatabaseFailure(error)` on any exception (including
  constraint violations: duplicate `(job_id, attempt_number)`, invalid `status`
  string, oversized `error_message`).
- **Idempotent**: No (unique constraint on `(job_id, attempt_number)` enforces
  exactly-once insertion per attempt slot).

#### `countAttempts(session: SqlSession, jobId: UUID): AttemptCountResult`

- **Side Effects**: None (read-only).
- **Returns**: `AttemptCountResult.Success(count)`. `AttemptCountResult.DatabaseFailure(error)`
  on any exception.
- **Idempotent**: Yes.

#### `findAttemptsByJobId(session: SqlSession, jobId: UUID): AttemptFindResult`

- **Side Effects**: None (read-only).
- **Ordering**: Results are ordered by `attempt_number ASC`.
- **Returns**: `AttemptFindResult.Success(attempts)` — empty list if no attempts
  exist. `AttemptFindResult.DatabaseFailure(error)` on any exception.
- **Idempotent**: Yes.

#### `resetStuckRunning(session: SqlSession): JobResetResult`

- **Side Effects**: Bulk-updates `jobs` rows where `status = 'RUNNING' AND
  locked_until < NOW()`: sets `status = 'SCHEDULED'`, `locked_until = NULL`.
  Clock comparison uses database `NOW()`.
- **Returns**: `JobResetResult.Success(count)` — count of rows reset (may be 0).
  `JobResetResult.DatabaseFailure(error)` on any exception.
- **Idempotent**: Yes (repeated calls are safe; expired locks are idempotently
  reset).

#### `deleteBefore(session: SqlSession, statuses: Set<JobStatus>, olderThan: Duration): JobDeleteResult`

- **Side Effects**: Physically deletes `jobs` rows where `status IN (statuses)
  AND updated_at < NOW() - olderThan` (SQL interval). Cascades to
  `job_attempts`.
- **Empty set guard**: Returns `JobDeleteResult.Success(0)` immediately if
  `statuses` is empty.
- **Returns**: `JobDeleteResult.Success(count)` — count of deleted rows (may be
  0). `JobDeleteResult.DatabaseFailure(error)` on any exception.
- **Idempotent**: Yes (repeated deletions on already-deleted rows affect 0 rows).

#### `deleteByIds(session: SqlSession, ids: List<UUID>): JobDeleteResult`

- **Side Effects**: Physically deletes `jobs` rows by primary key. Cascades to
  `job_attempts` via `ON DELETE CASCADE`.
- **Empty list guard**: Returns `JobDeleteResult.Success(0)` immediately if
  `ids` is empty.
- **Returns**: `JobDeleteResult.Success(count)`. `JobDeleteResult.DatabaseFailure(error)`
  on any exception.
- **Idempotent**: Yes (deleting already-deleted IDs affects 0 rows).
- **Note**: Also exposed as a vararg overload `deleteByIds(session, vararg ids:
  UUID)` for call-site convenience.

#### `findById(session: SqlSession, id: UUID): JobFindResult`

- **Side Effects**: None (read-only).
- **Returns**: `JobFindResult.Success(job)` if found. `JobFindResult.NotFound(message)`
  if no row with the given `id` exists. `JobFindResult.DatabaseFailure(error)`
  on any exception.
- **Idempotent**: Yes.

#### `countByStatus(session: SqlSession, jobType: JobType?): JobCountResult`

- **Side Effects**: None (read-only).
- **Filtering**: If `jobType` is non-null, results are filtered to that type. If
  null, counts are returned across all job types.
- **Returns**: `JobCountResult.Success(counts)` — a `Map<JobStatus, Int>`
  containing only statuses with at least one row (statuses with zero rows are
  absent from the map). `JobCountResult.DatabaseFailure(error)` on any
  exception.
- **Idempotent**: Yes.

---

### Sealed Result Types

See [`Results.kt`](./Results.kt).

| Result Type          | Variants                                              |
|----------------------|-------------------------------------------------------|
| `JobInsertResult`    | `Success(job)`, `DatabaseFailure(error)`              |
| `JobFindResult`      | `Success(job)`, `NotFound(message)`, `DatabaseFailure(error)` |
| `JobUpdateResult`    | `Success(job)`, `NotFound(message)`, `InvalidState(message)`, `DatabaseFailure(error)` |
| `JobResetResult`     | `Success(count: Int)`, `DatabaseFailure(error)`       |
| `JobDeleteResult`    | `Success(count: Int)`, `DatabaseFailure(error)`       |
| `JobCountResult`     | `Success(counts: Map<JobStatus, Int>)`, `DatabaseFailure(error)` |
| `AttemptInsertResult`| `Success(attempt)`, `DatabaseFailure(error)`          |
| `AttemptCountResult` | `Success(count: Int)`, `DatabaseFailure(error)`       |
| `AttemptFindResult`  | `Success(attempts: List<JobAttempt>)`, `DatabaseFailure(error)` |

**`JobUpdateResult.InvalidState`**: Returned when the row exists but its current
`status` does not satisfy the precondition of the requested transition. Includes
the current status in the message for observability.

---

## IV. Infrastructure & Environment

- **Database Tables**: `jobs`, `job_attempts` (created by
  `db/schema/0003.create-queue.sql`).
- **Database Trigger**: `trigger_03_enforce_jobs_updated_at` on `jobs` maintains
  `updated_at` via `update_timestamp()` procedure. This trigger is the source of
  truth for `Job.updatedAt` and therefore for `startedAt` in `insertAttempt`.
- **NOTIFY Trigger**: A trigger on `jobs` emits `pg_notify('jobs_channel',
  NEW.job_type)` when a row enters `SCHEDULED` status (created by
  `db/schema/0004.add-jobs-notify-trigger.sql`). `JobsDao` does not invoke
  `NOTIFY` directly — the trigger fires automatically on INSERT/UPDATE.
- **Locking Index**: `idx_jobs_scheduled_job_type` (partial index on
  `job_type, scheduled_at WHERE status = 'SCHEDULED'`) is required for efficient
  `findNextScheduledJob` execution under concurrent workers.
- **Lock Expiry Index**: `idx_jobs_status_locked_until` (partial index on
  `status, locked_until WHERE status = 'RUNNING'`) is required for efficient
  `resetStuckRunning` execution.
- **Dependencies**: `ed.unicoach.db.dao.SqlSession` (from the `db` module);
  `ed.unicoach.error.ExceptionWrapper` (from the `common` module);
  `kotlinx.serialization.json.Json` / `JsonObject` (from `common`'s
  `kotlinx-serialization-json` api dependency).

---

## V. History

- [x] [RFC-15: Queue Data Layer](../../../../../../../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../../../../../../../rfc/16-queue-worker-framework.md)
