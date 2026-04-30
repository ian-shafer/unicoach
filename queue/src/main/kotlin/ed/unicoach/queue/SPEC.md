# Queue Module Specification

## I. Overview

The `queue` module (`ed.unicoach.queue`) is the **persistent job queue infrastructure** for the
unicoach platform. It owns the data layer (`JobsDao`, schema), the public enqueue API
(`QueueService`), and the worker orchestrator (`QueueWorker`) that polls, dispatches, retries,
and reaps jobs. It is a shared library module; consuming modules wire handlers at startup and
call `QueueService.enqueue()` to produce work.

---

## II. Invariants

### Schema & Status Machine

- The system MUST enforce the following job status transitions via SQL and never permit arbitrary
  cross-transitions:
  - `SCHEDULED → RUNNING` (claim)
  - `RUNNING → COMPLETED` (success)
  - `RUNNING → DEAD_LETTERED` (permanent failure or exhausted retries)
  - `RUNNING → SCHEDULED` (retry reschedule or stuck-job reset)
- All `UPDATE` operations targeting terminal transitions (`COMPLETED`, `DEAD_LETTERED`) MUST
  clear `locked_until` to `NULL`.
- The `jobs` table MUST enforce `status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'DEAD_LETTERED')`
  via a `CHECK` constraint.
- The `job_attempts` table MUST enforce `status IN ('SUCCESS', 'RETRIABLE_FAILURE', 'PERMANENT_FAILURE')`
  via a `CHECK` constraint.
- `payload` size MUST be constrained at the database layer to `<= 65536` bytes
  (`octet_length(payload::text)`).
- `job_type` MUST be constrained to `<= 128` characters via `CHECK` constraint.
- `job_attempts.job_id` MUST cascade-delete: deleting a job MUST physically remove all its
  attempt records.
- The `(job_id, attempt_number)` pair on `job_attempts` MUST be unique.
- `updated_at` on `jobs` MUST be maintained by a database trigger (`update_timestamp()`), never
  by application code.

### Worker Lifecycle

- `QueueWorker` MUST NOT be started twice; a second call to `start()` MUST throw
  `IllegalStateException`.
- `QueueWorker` MUST NOT register two handlers for the same `JobType`; constructor MUST throw
  `IllegalArgumentException` on duplicate.
- Every `JobHandler` registered with `QueueWorker` MUST be idempotent. The framework guarantees
  **at-least-once** delivery; handlers MUST tolerate duplicate executions.
- `QueueWorker.stop()` MUST wait up to the specified timeout for in-flight handlers to complete
  before forcefully cancelling the coroutine scope.

### Concurrency & Locking

- Job claim MUST use `SELECT ... FOR UPDATE SKIP LOCKED` to prevent two workers from claiming
  the same job.
- A JDBC connection used to claim a job MUST be returned to the Hikari pool **before** handler
  execution begins. Handler execution MUST NOT hold an open database connection.
- `executionTimeout` (per `JobTypeConfig`) MUST be less than `lockDuration`. Exceeding
  `executionTimeout` returns a `RetriableFailure`; the stuck-job reaper will reset the lock.

### Timestamps

- `startedAt` recorded in `job_attempts` MUST originate from `Job.updatedAt` of the
  `claimJob` result — a DB-sourced timestamp set by the `update_timestamp()` trigger.
  Application-side `Instant.now()` MUST NOT be used for `startedAt`.
- `finished_at` in `job_attempts` MUST be set by the database (`DEFAULT NOW()`) at insert time.
- All interval arithmetic in SQL MUST use `NOW() + ?::interval` (database clock), never
  application-side clock offsets.

### Attempt Recording

- An attempt record MUST be written after job execution resolves — never at claim time.
- The attempt number is derived as `countAttempts() + 1` within the outcome transaction.

### Dead Lettering

- A `RetriableFailure` job MUST be dead-lettered when `attemptNumber >= maxAttempts`.
  `maxAttempts` resolves as `job.maxAttempts ?: handler.config.maxAttempts`.
- A `PermanentFailure` result MUST immediately dead-letter the job regardless of attempt count.

### Reaper Contracts

- The **stuck-job reaper** MUST reset any job with `status = 'RUNNING' AND locked_until < NOW()`
  back to `SCHEDULED`. It MUST use `NOW()` from the database, not the application clock.
- The **completed-job reaper** MUST physically delete `COMPLETED` jobs older than
  `completedJobRetention` (default: 30 days) using `updated_at < NOW() - interval`.

### LISTEN/NOTIFY

- On establishing (or re-establishing) the raw `LISTEN` connection, the worker MUST immediately
  broadcast a `Unit` to **all** registered job channels to recover any jobs enqueued during the
  connection gap.
- If the LISTEN connection drops with a transient SQL error (SQLState `08*`, `53*`, `57P*`),
  the worker MUST back off (≥ 2 seconds) before reconnecting to prevent spin-lock.
- A received notification for an unregistered `JobType` MUST be logged at `WARN` level and
  otherwise ignored.

### Configuration

- `QueueConfig.from(config)` MUST fail fast if the `queue {}` HOCON block is absent. This
  ensures a misconfigured classpath is caught at startup, not at first job dispatch.
- `QueueConfig` carries **no fields**. It is a pure startup-time existence guard. Callers
  MUST NOT expect configuration values to be readable from the returned object.

---

## II-B. Data Types

This section documents the shape of every value type in the module. An LLM modifying this
module MUST treat these as the authoritative field inventories.

### `Job`

The read-side projection of a `jobs` row, returned by all DAO query and update operations.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `id` | `UUID` | no | Primary key |
| `createdAt` | `Instant` | no | Set by DB default |
| `updatedAt` | `Instant` | no | Maintained by `update_timestamp()` trigger |
| `jobType` | `JobType` | no | Enum value |
| `payload` | `JsonObject` | no | Raw JSON payload |
| `status` | `JobStatus` | no | Current status |
| `scheduledAt` | `Instant` | no | When the job becomes eligible |
| `lockedUntil` | `Instant` | yes | Non-null only while `RUNNING` |
| `maxAttempts` | `Int` | yes | Null means inherit from `JobTypeConfig` |

### `NewJob`

The write-side input passed to `JobsDao.insert()`. Constructed by `QueueService.enqueue()`
before being forwarded to the DAO.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `jobType` | `JobType` | no | Target handler type |
| `payload` | `JsonObject` | no | Handler-specific JSON payload |
| `maxAttempts` | `Int` | yes | Null → inherit from handler config at execution time |
| `delay` | `Duration` | yes | Null → `scheduled_at = NOW()`; non-null → `scheduled_at = NOW() + delay` |

### `JobAttempt`

A single execution record written to `job_attempts` after each handler invocation.

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `id` | `UUID` | no | Primary key |
| `jobId` | `UUID` | no | FK → `jobs(id)` with CASCADE DELETE |
| `attemptNumber` | `Int` | no | 1-indexed, derived as `countAttempts() + 1` |
| `startedAt` | `Instant` | no | DB-sourced from `Job.updatedAt` of the claim result |
| `finishedAt` | `Instant` | no | Set to `NOW()` by DB at insert time |
| `status` | `AttemptStatus` | no | Outcome of the execution |
| `errorMessage` | `String` | yes | Non-null for `RETRIABLE_FAILURE` and `PERMANENT_FAILURE` |

### `JobStatus`

Enum for the `jobs.status` column. Values: `SCHEDULED`, `RUNNING`, `COMPLETED`, `DEAD_LETTERED`.
Each variant carries a `value: String` property that matches its DB representation.

### `AttemptStatus`

Enum for the `job_attempts.status` column. Values: `SUCCESS`, `RETRIABLE_FAILURE`,
`PERMANENT_FAILURE`. Each variant carries a `value: String` property.

`JobResult` variants expose a `status: AttemptStatus` property — this is the bridge between
handler output and the attempt row written to the database:
- `JobResult.Success.status == AttemptStatus.SUCCESS`
- `JobResult.RetriableFailure.status == AttemptStatus.RETRIABLE_FAILURE`
- `JobResult.PermanentFailure.status == AttemptStatus.PERMANENT_FAILURE`

---

## III. Behavioral Contracts

### `QueueService.enqueue()`

- **Side effects**: Inserts a row into `jobs` with `status = 'SCHEDULED'`. Emits a PostgreSQL
  `NOTIFY jobs_channel, '<job_type>'` via database trigger (not application code).
- **Delay**: If `delay` is non-null, `scheduled_at = NOW() + delay` (SQL interval). If null,
  `scheduled_at = NOW()`.
- **`maxAttempts`**: If null, the job inherits the handler's `JobTypeConfig.maxAttempts` at
  execution time.
- **Result**: `EnqueueResult.Success(job)` or `EnqueueResult.DatabaseFailure(error)`. Never
  throws.
- **Idempotency**: Not idempotent. Each call inserts a new row.

### `JobsDao.findNextScheduledJob()`

- Selects a single `SCHEDULED` job with `scheduled_at <= NOW()` for the given `jobType`,
  ordered by `scheduled_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED`.
- Returns `JobFindResult.NotFound` when the queue is empty for that type.
- **Side effects**: Acquires a row-level lock (released on transaction end).

### `JobsDao.claimJob()`

- Transitions `SCHEDULED → RUNNING`, sets `locked_until = NOW() + lockDuration`.
- Returns `JobUpdateResult.InvalidState` if the job is not in `SCHEDULED` status.
- Returns `JobUpdateResult.NotFound` if the job ID does not exist.
- **Idempotency**: Not idempotent. Calling twice on the same job returns `InvalidState` on the
  second call.

### `JobsDao.completeJob()` / `JobsDao.deadLetterJob()`

- Require source status `RUNNING`. Return `InvalidState` if the job is in any other status.
- Always clear `locked_until = NULL`.

### `JobsDao.reschedule()`

- Requires source status `RUNNING`. Returns `InvalidState` otherwise.
- Sets `scheduled_at = NOW() + delay` (SQL interval), `locked_until = NULL`,
  `status = 'SCHEDULED'`.

### `JobsDao.resetStuckRunning()`

- Bulk resets all `RUNNING` jobs where `locked_until < NOW()` back to `SCHEDULED`.
- Returns `JobResetResult.Success(count)`. Count of `0` is normal (no stuck jobs).
- **Side effects**: Modifies the `jobs` table. Uses database `NOW()`.

### `JobsDao.deleteBefore()`

- Physically deletes jobs in the given `statuses` set with `updated_at < NOW() - olderThan`.
- Empty `statuses` set returns `Success(0)` without executing SQL.

### `JobsDao.insert()`

- Called exclusively by `QueueService.enqueue()` via a `NewJob` value.
- Inserts a row with `status = 'SCHEDULED'`. If `newJob.delay` is non-null, `scheduled_at` is
  set to `NOW() + delay` via SQL interval; otherwise `scheduled_at = NOW()`.
- `maxAttempts` is stored as-is (may be `NULL`).
- Returns `JobInsertResult.Success(job)` or `JobInsertResult.DatabaseFailure(error)`. Never
  throws.

### `JobsDao.insertAttempt()`

- Inserts a row into `job_attempts`. Called by the worker after every handler invocation.
- `startedAt` MUST be `Job.updatedAt` from the `claimJob` result (DB-sourced). The application
  MUST NOT pass `Instant.now()` here.
- `finishedAt` is set to `NOW()` by the database at insert time; it is not an application
  parameter.
- Returns `AttemptInsertResult.Success(attempt)` or `AttemptInsertResult.DatabaseFailure(error)`.

### `JobsDao.countAttempts()`

- Returns the count of rows in `job_attempts` for a given `jobId`.
- Used by the worker to derive the next `attemptNumber` (`count + 1`) within the outcome
  transaction.
- Returns `AttemptCountResult.Success(count)` or `AttemptCountResult.DatabaseFailure(error)`.

### `JobsDao.findAttemptsByJobId()`

- Returns all `job_attempts` rows for a given `jobId`, ordered by `attempt_number ASC`.
- Returns `AttemptFindResult.Success(attempts)` where `attempts` is an empty list if none exist,
  or `AttemptFindResult.DatabaseFailure(error)` on SQL error.
- Primarily used by integration tests and diagnostic tooling.

### `JobsDao.findById()`

- Fetches a single `jobs` row by primary key `UUID`.
- Returns `JobFindResult.Success(job)` or `JobFindResult.NotFound(message)` or
  `JobFindResult.DatabaseFailure(error)`.
- Primarily used by integration tests to assert job state after execution.

### `JobsDao.deleteByIds()`

- Physically deletes jobs by a list (or vararg) of `UUID` primary keys. Cascades to
  `job_attempts` via `ON DELETE CASCADE`.
- Empty list returns `JobDeleteResult.Success(0)` without executing SQL.
- Returns `JobDeleteResult.Success(count)` or `JobDeleteResult.DatabaseFailure(error)`.
- Primarily used by integration tests for teardown.

### `JobsDao.countByStatus()`

- Returns a `Map<JobStatus, Int>` of job counts grouped by status.
- Accepts an optional `jobType: JobType?` filter; when non-null, restricts the count to
  that job type only.
- Returns `JobCountResult.Success(counts)` or `JobCountResult.DatabaseFailure(error)`.
- Use this method before implementing any custom status-counting query — it is the canonical
  read path for queue depth metrics.

### `JobHandler.execute()`

- Receives the raw `JsonObject` payload from the job row.
- MUST return `JobResult.Success`, `JobResult.RetriableFailure`, or `JobResult.PermanentFailure`.
- MUST NOT throw unhandled exceptions; the framework catches all non-`CancellationException`
  throwables and converts them to `RetriableFailure`.
- `CancellationException` MUST be re-thrown to respect graceful shutdown.
- Execution is dispatched on `Dispatchers.IO`. Blocking JDBC calls inside `execute()` are safe
  without an additional `withContext(Dispatchers.IO)`.

### `BackoffStrategy.delayFor()`

- `Exponential(base)`: delay = `base * (1 shl min(attemptNumber - 1, 20))`. The `coerceAtMost(20)`
  cap prevents integer overflow at high attempt counts.
- `Fixed(delay)`: returns `delay` regardless of attempt number.

### `SessionExpiryPayload`

- A `@Serializable` data class co-located in the `queue` module so both `rest-server` (enqueuer)
  and `net` (handler) can depend on it without creating a cross-module cycle.
- `tokenHash`: Base64-encoded SHA-256 hash of the raw session token. `ByteArray` is not used
  directly because it does not serialize cleanly to JSON.

### `JobType` Enum

- `TEST_JOB` and `TEST_JOB_B` are **reserved** for integration test suites. MUST NOT be used
  in production handler registrations.
- `SESSION_EXTEND_EXPIRY`: first production variant, handled by `net.SessionExpiryHandler`.
- `fromValue(value: String): JobType?` returns `null` for unknown strings; the worker logs a
  `WARN` and discards the notification.

---

## IV. Infrastructure & Environment

- **Gradle module**: `queue`. Dependencies: `common`, `db`.
- **HOCON**: `queue/src/main/resources/queue.conf` MUST declare a `queue {}` root block.
  `QueueConfig.from(config).getOrThrow()` is called at startup by both `rest-server` and
  `queue-worker`.
- **`dao/` subdirectory**: Contains two files.
  - `JobsDao.kt` — all SQL operations against `jobs` and `job_attempts`.
  - `Results.kt` — **all** sealed result interfaces for the DAO layer. New DAO methods MUST
    add their result types here, not inline in `JobsDao.kt`. Current inventory:
    - `JobInsertResult` (`Success`, `DatabaseFailure`)
    - `JobFindResult` (`Success`, `NotFound`, `DatabaseFailure`)
    - `JobUpdateResult` (`Success`, `NotFound`, `InvalidState`, `DatabaseFailure`)
    - `JobResetResult` (`Success`, `DatabaseFailure`)
    - `JobDeleteResult` (`Success`, `DatabaseFailure`)
    - `JobCountResult` (`Success(counts: Map<JobStatus, Int>)`, `DatabaseFailure`)
    - `AttemptInsertResult` (`Success`, `DatabaseFailure`)
    - `AttemptCountResult` (`Success(count: Int)`, `DatabaseFailure`)
    - `AttemptFindResult` (`Success(attempts: List<JobAttempt>)`, `DatabaseFailure`)
- **Database schema**: Two tables managed by `db/schema/0003.create-queue.sql`:
  - `jobs` — with partial indexes on `(job_type, scheduled_at) WHERE status = 'SCHEDULED'`
    and `(status, locked_until) WHERE status = 'RUNNING'`.
  - `job_attempts` — with `CASCADE` delete on `job_id → jobs(id)`.
- **NOTIFY trigger**: `db/schema/0004.add-jobs-notify-trigger.sql` installs a trigger that
  fires `pg_notify('jobs_channel', NEW.job_type)` whenever a job row enters `status = 'SCHEDULED'`.
  The `LISTEN` connection is raw JDBC (`database.createRawConnection()`), bypassing the Hikari
  pool to maintain a persistent connection.
- **Concurrency**: Each `JobHandler` declares `JobTypeConfig.concurrency` (default: `1`).
  `QueueWorker` launches that many coroutines per handler, all sharing a single `CONFLATED`
  channel per job type.
- **Default `JobTypeConfig` values**:
  - `concurrency = 1`
  - `maxAttempts = 3`
  - `backoffStrategy = Exponential(base = 2.seconds)`
  - `lockDuration = 1.minutes`
  - `delayedJobPollInterval = 10.seconds`
  - `executionTimeout = 2.minutes`
- **Default `QueueWorker` values**:
  - `stuckJobCheckInterval = 1.minutes`
  - `completedJobRetention = 30.days`
  - `completedJobReapInterval = 1.hours`
- **Transient SQL errors**: SQLState prefixes `08` (Connection Exception), `53` (Insufficient
  Resources), and `57P` (Admin/Crash Shutdown) are classified as transient. Worker coroutines
  MUST catch and back off on these; all others are re-thrown.

---

## V. History

- [x] [RFC-15: Queue Data Layer](../../../../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../../../../rfc/16-queue-worker-framework.md)
- [x] [RFC-17: Queue Worker Daemon and CLI](../../../../../rfc/17-queue-worker-daemon.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../rfc/21-session-expiry-queue.md)
