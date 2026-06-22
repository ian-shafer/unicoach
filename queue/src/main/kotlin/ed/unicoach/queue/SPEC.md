# Queue Module Specification

## I. Overview

The `queue` module (`ed.unicoach.queue`) is the persistent job-queue
infrastructure for the unicoach platform. It owns the data layer (`JobsDao` and
its result types under [dao/](./dao/)), the public enqueue API
([QueueService](./QueueService.kt)), and the worker orchestrator
([QueueWorker](./QueueWorker.kt)) that polls, dispatches, retries, and reaps
jobs. It is a shared library module: consuming modules wire `JobHandler`
implementations at startup and call `QueueService.enqueue()` to produce work.

---

## II. Behavioral Contracts

### `QueueService.enqueue()`

- **Behavior**: Inserts a `SCHEDULED` job for the given `jobType` and
  `JsonObject` payload. `maxAttempts` (nullable) and `delay` (nullable
  `Duration`) are optional.
- **Side Effects**: Inserts one row into `jobs`. A database trigger fires a
  PostgreSQL `NOTIFY jobs_channel, '<job_type>'`; the application emits no
  notify itself.
- **Delay**: Non-null `delay` sets `scheduled_at = NOW() + delay` (SQL
  interval); null sets `scheduled_at = NOW()`.
- **`maxAttempts`**: Null causes the job to inherit the handler's
  `JobTypeConfig.maxAttempts` at execution time.
- **Error Handling**: Returns `EnqueueResult.Success(job)` or
  `EnqueueResult.DatabaseFailure(error)`. Does not throw.
- **Idempotency**: Not idempotent; each call inserts a new row.

### `JobsDao.findNextScheduledJob()`

- **Behavior**: Selects a single `SCHEDULED` job with `scheduled_at <= NOW()`
  for the given `jobType`, ordered by
  `scheduled_at ASC LIMIT 1 FOR UPDATE SKIP
  LOCKED`.
- **Side Effects**: Acquires a row-level lock released when the transaction
  ends.
- **Error Handling**: Returns `JobFindResult.NotFound` when no eligible job
  exists for that type.

### `JobsDao.claimJob()`

- **Behavior**: Transitions `SCHEDULED → RUNNING` and sets `locked_until = NOW()
  - lockDuration`.
- **Error Handling**: Returns `JobUpdateResult.InvalidState` if the job is not
  in `SCHEDULED`; `JobUpdateResult.NotFound` if the job ID does not exist.
- **Idempotency**: Not idempotent; a second claim on the same job returns
  `InvalidState`.

### `JobsDao.completeJob()` / `JobsDao.deadLetterJob()`

- **Behavior**: Transition `RUNNING → COMPLETED` / `RUNNING → DEAD_LETTERED`,
  clearing `locked_until` to `NULL`.
- **Error Handling**: Return `JobUpdateResult.InvalidState` when the source
  status is not `RUNNING`.

### `JobsDao.reschedule()`

- **Behavior**: Transitions `RUNNING → SCHEDULED`, sets
  `scheduled_at = NOW() +
  delay` (SQL interval) and `locked_until = NULL`.
- **Error Handling**: Returns `JobUpdateResult.InvalidState` when the source
  status is not `RUNNING`.

### `JobsDao.resetStuckRunning()`

- **Behavior**: Bulk-resets every `RUNNING` job with `locked_until < NOW()` back
  to `SCHEDULED`, using the database clock.
- **Side Effects**: Modifies the `jobs` table.
- **Error Handling**: Returns `JobResetResult.Success(count)`; a count of `0`
  (no stuck jobs) is normal. `JobResetResult.DatabaseFailure(error)` on SQL
  error.

### `JobsDao.deleteBefore()`

- **Behavior**: Physically deletes jobs whose `status` is in the given set and
  `updated_at < NOW() - olderThan`. An empty `statuses` set returns `Success(0)`
  without executing SQL.
- **Side Effects**: Deletes `jobs` rows (cascading to `job_attempts`).
- **Error Handling**: Returns `JobDeleteResult.Success(count)` or
  `JobDeleteResult.DatabaseFailure(error)`.

### `JobsDao.insert()`

- **Behavior**: Called exclusively by `QueueService.enqueue()` via a `NewJob`.
  Inserts a `SCHEDULED` row; `scheduled_at` follows the same delay rule as
  `enqueue`. `maxAttempts` is stored as-is (may be `NULL`).
- **Error Handling**: Returns `JobInsertResult.Success(job)` or
  `JobInsertResult.DatabaseFailure(error)`. Does not throw.

### `JobsDao.insertAttempt()`

- **Behavior**: Inserts a `job_attempts` row after a handler invocation
  resolves. `startedAt` is the `Job.updatedAt` of the claim result (a DB-sourced
  timestamp set by the `update_timestamp()` trigger), not an application clock.
  `finishedAt` is set by the database (`DEFAULT NOW()`) at insert time and is
  not an application parameter.
- **Side Effects**: Inserts one `job_attempts` row.
- **Error Handling**: Returns `AttemptInsertResult.Success(attempt)` or
  `AttemptInsertResult.DatabaseFailure(error)`.

### `JobsDao.countAttempts()`

- **Behavior**: Returns the count of `job_attempts` rows for a `jobId`. The
  worker derives the next attempt number as `count + 1` inside the outcome
  transaction.
- **Error Handling**: Returns `AttemptCountResult.Success(count)` or
  `AttemptCountResult.DatabaseFailure(error)`.

### `JobsDao.findAttemptsByJobId()`

- **Behavior**: Returns all `job_attempts` rows for a `jobId`, ordered by
  `attempt_number ASC`; an empty list when none exist. Used by integration tests
  and diagnostic tooling.
- **Error Handling**: Returns `AttemptFindResult.Success(attempts)` or
  `AttemptFindResult.DatabaseFailure(error)`.

### `JobsDao.findById()`

- **Behavior**: Fetches a single `jobs` row by `UUID` primary key. Used by
  integration tests to assert job state.
- **Error Handling**: Returns `JobFindResult.Success(job)`,
  `JobFindResult.NotFound(message)`, or `JobFindResult.DatabaseFailure(error)`.

### `JobsDao.deleteByIds()`

- **Behavior**: Physically deletes jobs by a list (or vararg) of `UUID` keys,
  cascading to `job_attempts`. An empty list returns `Success(0)` without
  executing SQL. Used by integration tests for teardown.
- **Error Handling**: Returns `JobDeleteResult.Success(count)` or
  `JobDeleteResult.DatabaseFailure(error)`.

### `JobsDao.countByStatus()`

- **Behavior**: Returns a `Map<JobStatus, Int>` of job counts grouped by status.
  An optional `jobType: JobType?` filter restricts the count to that type. This
  is the canonical read path for queue-depth metrics.
- **Error Handling**: Returns `JobCountResult.Success(counts)` or
  `JobCountResult.DatabaseFailure(error)`.

### `QueueWorker`

- **Construction**: Takes the `Database`, `JobsDao`, a `List<JobHandler>`, and
  reaper-interval/retention durations. Builds a handler map keyed by `JobType`;
  a duplicate handler for one `JobType` throws `IllegalArgumentException`.
- **`start(scope)`**: Creates one `CONFLATED` `Channel<Unit>` per registered
  `JobType`, launches `JobTypeConfig.concurrency` worker coroutines per handler
  on `Dispatchers.IO`, and launches three background loops (listen, stuck-job
  reaper, completed-job reaper). A second `start()` call throws
  `IllegalStateException`.
- **`stop(timeout)`**: Closes all channels (waking idle receivers), then waits
  up to `timeout` for in-flight handlers and background loops to finish before
  cancelling the scope.
- **Job lifecycle (worker loop)**: A loop wakes on a channel signal (or after
  `delayedJobPollInterval`), claims the next eligible job, releases the pooled
  connection, then runs the handler. After a claim it sends a baton-pass signal
  to its own channel so a sibling coroutine can pick up the next job. The
  handler runs under `withTimeoutOrNull(executionTimeout)`; a timeout yields
  `RetriableFailure("Execution timed out")`. Any non-`CancellationException`
  thrown by the handler is caught, logged, and converted to a
  `RetriableFailure`; `CancellationException` is re-thrown for graceful
  shutdown.
- **Outcome handling**: In a fresh connection the worker computes
  `attemptNumber = countAttempts() + 1`, writes a `job_attempts` row, then:
  `Success` completes the job; `PermanentFailure` dead-letters it immediately
  regardless of attempt count; `RetriableFailure` dead-letters when
  `attemptNumber >= (job.maxAttempts ?: handler.config.maxAttempts)`, otherwise
  reschedules with the handler's backoff delay. When a `RetriableFailure`
  carries a non-null `cause`, the worker logs it at `WARN` with the job id and
  attempt number.
- **Delivery semantics**: At-least-once. The claim connection is returned to the
  pool before the handler runs, so handler execution holds no open database
  connection; handlers tolerate duplicate executions.

### `QueueWorker` background loops

- **`listenLoop`**: Opens a raw (non-pooled) connection via
  `database.createRawConnection()`, issues `LISTEN jobs_channel`, and on
  connect/reconnect broadcasts a `Unit` to every registered channel to recover
  jobs enqueued during any gap. Incoming notifications for a registered
  `JobType` wake that channel; a notification for an unregistered type is logged
  at `WARN` and ignored. On a transient SQL error (SQLState prefix `08`, `53`,
  or `57P`) it backs off 2 seconds before reconnecting; other SQL errors
  propagate.
- **`stuckJobReaperLoop`**: Every `stuckJobCheckInterval` calls
  `resetStuckRunning()`; logs the count when non-zero. Transient SQL errors are
  logged and swallowed; others propagate.
- **`completedJobReaperLoop`**: Every `completedJobReapInterval` deletes
  `COMPLETED` jobs older than `completedJobRetention`; logs the count when
  non-zero. Transient SQL errors are logged and swallowed; others propagate.

### `JobHandler.execute()`

- **Behavior**: Receives the raw `JsonObject` payload and returns a `JobResult`
  (`Success`, `RetriableFailure`, or `PermanentFailure`). Invoked on an IO-bound
  dispatcher, so handlers need not manage their own context for blocking IO.
- **Error Handling**: The worker treats an uncaught throwable as a
  `RetriableFailure`; a `CancellationException` propagates to honor shutdown.
- **Idempotency**: Handlers are written to tolerate duplicate executions, since
  delivery is at-least-once.

### `BackoffStrategy.delayFor()`

- **`Exponential(base)`**: `base * (1 shl min(attemptNumber - 1, 20))`; the cap
  prevents integer overflow at high attempt counts.
- **`Fixed(delay)`**: returns `delay` regardless of attempt number.

### Payload types

- **`SessionExpiryPayload`**
  ([SessionExpiryPayload.kt](./SessionExpiryPayload.kt)): `@Serializable`
  payload for `SESSION_EXTEND_EXPIRY`. Carries `tokenHash`, a Base64-encoded
  SHA-256 hash of the raw session token (a `String` rather than a `ByteArray` so
  it serializes cleanly to JSON). Co-located here so both the enqueuer
  (`rest-server`) and handler (`net`) depend on it without a cross-module cycle.
- **`ExtractionPayload`** ([ExtractionPayload.kt](./ExtractionPayload.kt)):
  `@Serializable` payload for `EXTRACT_CONVERSATION` (RFC 66). Carries `convoId`
  (the `convos.id` UUID as a `String`) and `throughRequestId` (the
  `convo_requests.id` BIGINT, a `Long`, of the user turn the extraction window
  runs through). Co-located here so the enqueuer (`rest-server`) and the handler
  (`service`) depend on it without a cross-module cycle.

### `JobType` enum

- **Variants**: `TEST_JOB` and `TEST_JOB_B` are reserved for integration test
  suites. `SESSION_EXTEND_EXPIRY` is handled by `net.SessionExpiryHandler`.
  `EXTRACT_CONVERSATION` (RFC 66) is handled by the `service`-module extraction
  handler and carries an `ExtractionPayload`. Each variant carries a
  `value:
  String` matching its DB representation.
- **`fromValue(value)`**: returns the matching `JobType` or `null` for an
  unknown string; the worker logs a `WARN` and discards the notification on
  `null`.

### Status & result enums

- **`JobStatus`** ([JobStatus.kt](./JobStatus.kt)): `SCHEDULED`, `RUNNING`,
  `COMPLETED`, `DEAD_LETTERED`. Each carries a `value: String` matching the
  `jobs.status` DB representation.
- **`AttemptStatus`** ([AttemptStatus.kt](./AttemptStatus.kt)): `SUCCESS`,
  `RETRIABLE_FAILURE`, `PERMANENT_FAILURE`. Each carries a `value: String`
  matching the `job_attempts.status` DB representation.
- **`JobResult`** ([JobResult.kt](./JobResult.kt)): sealed result a handler
  returns. Each variant exposes a `status: AttemptStatus` that maps to the
  attempt row written by the worker (`Success → SUCCESS`,
  `RetriableFailure →
  RETRIABLE_FAILURE`,
  `PermanentFailure → PERMANENT_FAILURE`). `RetriableFailure` carries a
  `message` and an optional `cause: Throwable?` (the worker logs the cause at
  `WARN` when present); `PermanentFailure` carries a `message`.

### Job & attempt value types

- **`Job`** ([Job.kt](./Job.kt)): read-side projection of a `jobs` row returned
  by DAO query/update operations. `lockedUntil` is non-null only while
  `RUNNING`; `maxAttempts` null means inherit from `JobTypeConfig`;
  `createdAt`/`updatedAt` are DB-maintained.
- **`NewJob`** ([NewJob.kt](./NewJob.kt)): write-side input to
  `JobsDao.insert()`, constructed by `enqueue()`. Null `maxAttempts` inherits
  the handler config at execution time; null `delay` schedules immediately.
- **`JobAttempt`** ([JobAttempt.kt](./JobAttempt.kt)): one execution record in
  `job_attempts`. `attemptNumber` is 1-indexed (`countAttempts() + 1`);
  `errorMessage` is non-null for `RETRIABLE_FAILURE` and `PERMANENT_FAILURE`.

### `QueueConfig`

- **`QueueConfig.from(config)`**: a startup-time existence guard that fails with
  an error when the `queue {}` HOCON block is absent, so a misconfigured
  classpath surfaces at startup rather than at first dispatch. `QueueConfig`
  carries no readable fields.

---

## III. Infrastructure & Environment

- **Gradle module**: `queue`. Depends on `common` and `db`.
- **HOCON**: `queue/src/main/resources/queue.conf` declares a `queue {}` root
  block. `QueueConfig.from(config).getOrThrow()` is called at startup by both
  `rest-server` and `queue-worker`.
- **`dao/` subdirectory**: `JobsDao.kt` holds all SQL against `jobs` and
  `job_attempts`; `Results.kt` holds every sealed DAO result interface
  (`JobInsertResult`, `JobFindResult`, `JobUpdateResult`, `JobResetResult`,
  `JobDeleteResult`, `JobCountResult`, `AttemptInsertResult`,
  `AttemptCountResult`, `AttemptFindResult`). See [dao/SPEC.md](./dao/SPEC.md)
  for the DAO-layer detail.
- **Database schema**: Two tables from `db/schema/0003.create-queue.sql`:
  - `jobs` — `status` constrained to
    `('SCHEDULED','RUNNING','COMPLETED','DEAD_LETTERED')`; `payload` constrained
    to `<= 65536` bytes; `job_type` constrained to `<= 128` chars; partial
    indexes on `(job_type, scheduled_at) WHERE status = 'SCHEDULED'` and
    `(status, locked_until) WHERE status = 'RUNNING'`; `updated_at` maintained
    by the `update_timestamp()` trigger.
  - `job_attempts` — `status` constrained to
    `('SUCCESS','RETRIABLE_FAILURE','PERMANENT_FAILURE')`;
    `(job_id,
    attempt_number)` unique; `job_id` cascade-deletes with its
    parent job.
- **NOTIFY trigger**: `db/schema/0004.add-jobs-notify-trigger.sql` fires
  `pg_notify('jobs_channel', NEW.job_type)` when a job row enters `SCHEDULED`.
  The worker's `LISTEN` connection is a raw JDBC connection
  (`database.createRawConnection()`) outside the Hikari pool.
- **Concurrency**: Each handler declares `JobTypeConfig.concurrency` (default
  `1`); the worker runs that many coroutines per handler over a single
  `CONFLATED` channel per job type.
- **Default `JobTypeConfig`**: `concurrency = 1`, `maxAttempts = 3`,
  `backoffStrategy = Exponential(base = 2.seconds)`, `lockDuration = 1.minutes`,
  `delayedJobPollInterval = 10.seconds`, `executionTimeout = 2.minutes`. By
  configuration `executionTimeout` is set below `lockDuration` so an
  over-running handler returns a `RetriableFailure` and the stuck-job reaper
  reclaims the lock.
- **Default `QueueWorker`**: `stuckJobCheckInterval = 1.minutes`,
  `completedJobRetention = 30.days`, `completedJobReapInterval = 1.hours`.
- **Transient SQL errors**: SQLState prefixes `08` (Connection Exception), `53`
  (Insufficient Resources), and `57P` (Admin/Crash Shutdown) are treated as
  transient; worker loops back off or swallow-and-log on these and re-throw all
  others.

---

## IV. History

- [x] [RFC-15: Queue Data Layer](../../../../../../../rfc/15-queue-data-layer.md)
- [x] [RFC-16: Queue Worker Framework](../../../../../../../rfc/16-queue-worker-framework.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../../../rfc/21-session-expiry-queue.md)
- [x] [RFC-24: Result Types Refactoring](../../../../../../../rfc/24-result-types.md)
- [x] [RFC-28: Coroutine Context Refactor](../../../../../../../rfc/28-coroutine-context.md)
- [x] [RFC-66: Extraction](../../../../../../../rfc/66-extraction.md)
