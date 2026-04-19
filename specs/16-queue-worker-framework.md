# 16 Queue Worker Framework

## Executive Summary

This specification implements the queue worker framework within the existing
`queue` module (created in spec 15). It defines the `JobHandler` interface,
`JobResult` sealed type, `JobTypeConfig` configuration model, and the
`QueueWorker` orchestrator that polls for jobs, dispatches to handlers, manages
retries with exponential backoff, dead-letters exhausted jobs, and runs reapers
for stuck and completed jobs. The worker uses per-job-type concurrency lanes
implemented via Kotlin coroutines.

## Detailed Design

### JobResult

Sealed interface returned by handlers to signal execution outcome:

```kotlin
sealed interface JobResult {
    data object Success : JobResult
    data class RetriableFailure(val message: String) : JobResult
    data class PermanentFailure(val message: String) : JobResult
}
```

### BackoffStrategy

Sealed interface modeling retry delay calculation:

```kotlin
sealed interface BackoffStrategy {
    fun delayFor(attemptNumber: Int): Duration

    data class Exponential(val base: Duration) : BackoffStrategy {
        override fun delayFor(attemptNumber: Int): Duration =
            base.multipliedBy(1L shl (attemptNumber - 1).coerceAtMost(20))
    }

    data class Fixed(val delay: Duration) : BackoffStrategy {
        override fun delayFor(attemptNumber: Int): Duration = delay
    }
}
```

The `coerceAtMost(20)` caps the shift to prevent overflow at high attempt
counts.

### JobTypeConfig

Configuration declared on each handler, controlling worker-side behavior:

```kotlin
data class JobTypeConfig(
    val concurrency: Int = 1,
    val maxAttempts: Int = 3,
    val backoffStrategy: BackoffStrategy = BackoffStrategy.Exponential(
        base = Duration.ofSeconds(2)
    ),
    val lockDuration: Duration = Duration.ofMinutes(5),
    val delayedJobPollInterval: Duration = Duration.ofSeconds(10),
    val executionTimeout: Duration = Duration.ofMinutes(4),
)
```

- `concurrency`: Number of coroutines polling this job type simultaneously.
- `maxAttempts`: Default max attempts if not specified at enqueue time.
- `backoffStrategy`: Delay calculation between retries.
- `lockDuration`: How long a `RUNNING` job is locked before the reaper reclaims
  it.
- `delayedJobPollInterval`: The timeout applied to the worker's channel
  suspension. It acts as a heartbeat to automatically wake the worker to pick up
  delayed jobs whose `scheduled_at` bounds have expired, or to recover from any
  anomalous dropped notifications. Note: Jobs scheduled in the future do not
  emit a `NOTIFY` when their time arrives. They rely on this polling interval to
  be discovered, meaning all backoff retry logic inherently has up to this
  duration of execution jitter. The default is kept intentionally tight (10s) to
  keep retries responsive.
- `executionTimeout`: Maximum time allowed for handler execution. Must be less
  than `lockDuration`. If exceeded, the coroutine is cancelled and the attempt
  is recorded as a retriable failure.

### JobHandler Interface

The contract every job handler must implement:

```kotlin
interface JobHandler {
    val jobType: JobType
    val config: JobTypeConfig
    fun execute(payload: JsonObject): JobResult
}
```

Handlers are domain-specific implementations living in consuming modules (e.g.,
`service`). They are registered with the `QueueWorker` at startup via
constructor injection. Handlers MUST be completely idempotent. Because the
worker framework guarantees at-least-once delivery (e.g. if the worker crashes
after execution but before recording success, the reaper will reschedule it), it
is the handler's responsibility to handle duplicate executions safely.

### QueueWorker

Located at `queue/src/main/kotlin/ed/unicoach/queue/QueueWorker.kt`. The central
orchestrator managing the polling lifecycle.

**Constructor**:

```kotlin
class QueueWorker(
    private val database: Database,
    private val handlers: List<JobHandler>,
    private val stuckJobCheckInterval: Duration = Duration.ofMinutes(1),
    private val completedJobRetention: Duration = Duration.ofDays(30),
    private val completedJobReapInterval: Duration = Duration.ofHours(1),
)
```

**Initialization**: On construction, the worker builds an internal
`Map<JobType, JobHandler>` from the handlers list. If duplicate job types are
detected, construction fails with `IllegalArgumentException`.

**`start(scope: CoroutineScope)`**: Launches all coroutines within the provided
scope:

- Initializes a per-job-type `Channel<Unit>(capacity = Channel.CONFLATED)`
  registry for wakeup signals.
- For each registered handler, launches `handler.config.concurrency` coroutines
  that each run the worker execution loop for that handler's `jobType`.
- Launches one dedicated coroutine bypassing the Hikari pool to maintain a
  persistent `LISTEN jobs_channel` connection.
- Launches one coroutine for the stuck job reaper.
- Launches one coroutine for the completed job reaper.

**`stop(timeout: Duration)`**: Signals all coroutines to stop accepting new
work. Waits up to `timeout` for in-flight handlers to complete. If the timeout
expires, it explicitly calls `cancel()` on the underlying `CoroutineScope` to
forcefully interrupt hung handlers.

#### Event Notification (LISTEN/NOTIFY)

Instead of sleep-polling, the worker uses PostgreSQL `LISTEN/NOTIFY` for instant
wakeup. A database trigger emits `NOTIFY jobs_channel, '<job_type>'` on inserts
or updates to `SCHEDULED` status. Because `NOTIFY` events are not durable, if
the `LISTEN` database connection drops, the coroutine MUST explicitly broadcast
a `Unit` to all registered job channels immediately upon successfully
establishing (or re-establishing) the raw connection. This guarantees no jobs
inserted during the downtime are orphaned. The dedicated `LISTEN` coroutine uses
raw JDBC `getNotifications(timeout)` to block. Upon receiving a payload matching
a registered `jobType`, it sends a `Unit` to that type's conflated `Channel`.

#### Worker Execution Loop

Each handler coroutine executes a continuous outer loop:

1. **Wait for Signal**: Suspend on
   `withTimeoutOrNull(handler.config.delayedJobPollInterval) { channel.receive() }`
   to wait for a Postgres notification (or fallback timeout for delayed jobs).
   When awakened, enter the inner drain loop.
2. **Inner Drain Loop**: Loop continuously to process jobs until the queue is
   empty: a. Open a `database.withConnection` block (ensuring commit semantics):
   - Call `JobsDao.findNextScheduledJob(session, jobType)`.
   - If `JobFindResult.NotFound`, break out of the inner loop and return to
     Step 1.
   - If found, immediately call
     `JobsDao.claimJob(session, job.id, handler.config.lockDuration)` to
     transition to `RUNNING` and set the lock.
   - **CRITICAL**: The `withConnection` block MUST return here. The transaction
     commits and the JDBC connection MUST be returned to the Hikari pool before
     proceeding to the handler execution. b. **Baton Pass**: Since this worker
     successfully found a job, there might be more backlog. Call
     `channel.trySend(Unit)` to wake up one idle sibling worker in the
     concurrency pool before continuing. c. Outside the database connection
     lock, execute the handler in a `try/catch`:
   - Note attempt start time (`Instant.now()`).
   - Call
     `withTimeout(handler.config.executionTimeout.toMillis()) { handler.execute(job.payload) }`.
   - If handler throws an uncaught exception: verify it is not a
     `CancellationException`. If it is `TimeoutCancellationException`, treat it
     as `RetriableFailure("Execution timed out")`. If it is a generic
     `CancellationException`, immediately rethrow it to respect graceful
     shutdown procedures. Otherwise, treat it as
     `RetriableFailure(exception.message)`. d. Open a new database session to
     record the outcome:
   - Call
     `JobsDao.insertAttempt(session, job.id, attemptNumber, startedAt, attemptStatus, errorMessage)`.
   - Based on the `JobResult` (`Success`, `RetriableFailure`,
     `PermanentFailure`), call the corresponding `updateStatus` or `reschedule`
     method on the DAO. e. The inner loop repeats instantly to process the next
     job, ensuring the queue is fully drained before returning to the wait
     signal.

#### Max Attempts Resolution

The worker resolves max attempts per job as:
`job.maxAttempts ?: handler.config.maxAttempts`. This allows per-job overrides
at enqueue time while falling back to the handler's configured default.

#### Unknown Job Type Handling

Because poll coroutines are launched uniformly per registered handler, querying
specifically for their assigned `job_type`, unknown or unregistered job types
will not be picked up by the worker. They will remain in the database in
`SCHEDULED` status indefinitely. This is intentional; handling them is deferred
to external monitoring or CLI operational tools (e.g., `q-status`), preventing
the worker from churning on anomalies.

#### Stuck Job Reaper

A single coroutine that runs every `stuckJobCheckInterval`:

1. Calls `JobsDao.resetStuckRunning(session)` (the DAO natively uses `NOW()` to
   avoid application clock drift).
2. Logs the count of reset jobs to stderr.

#### Completed Job Reaper

A single coroutine that runs every `completedJobReapInterval`:

1. Calls
   `JobsDao.deleteBefore(session, statuses = setOf(JobStatus.COMPLETED), olderThan = completedJobRetention)`.
2. Logs the count of deleted jobs to stderr.

### Error Handling

- Handler execution is wrapped in `try/catch`. Any uncaught exception (excluding
  `CancellationException`) is converted to
  `RetriableFailure(e.message ?: "Unknown error")`.
- DAO failures during status transitions are logged to stderr. The job remains
  in its current state and will be reclaimed by the stuck job reaper if
  necessary.
- Worker-level coroutine failures (e.g., database connection loss) are caught
  and retried after a backoff delay, preventing the coroutine from dying
  permanently.

## Tests

### Unit

- `BackoffStrategyTest`:
  - `exponential backoff calculates correct delays`
  - `exponential backoff caps shift to prevent overflow`
  - `fixed backoff returns constant delay`

### Integration (against real Postgres)

- `QueueWorkerTest`:
  - `worker picks up scheduled job and completes it`
  - `worker records SUCCESS attempt on completion`
  - `worker retries on RetriableFailure with backoff`
  - `worker records RETRIABLE_FAILURE attempt with error message`
  - `worker dead-letters after max attempts exhausted`
  - `worker dead-letters immediately on PermanentFailure`
  - `worker records PERMANENT_FAILURE attempt with error message`
  - `worker treats uncaught exception as retriable failure`
  - `worker times out handler execution and records retriable failure`
  - `worker resolves max_attempts from job when set`
  - `worker resolves max_attempts from handler config when job is NULL`
  - `stuck job reaper resets stale RUNNING jobs`
  - `completed job reaper deletes old completed jobs`
  - `completed job reaper excludes dead-lettered jobs`
  - `worker respects per-job-type concurrency`
  - `worker does not pick up jobs with future scheduled_at`
  - `worker does not pick up locked RUNNING jobs`
  - `worker picks up previously locked RUNNING jobs once lock expires`
  - `worker handles immediate enqueue with empty delay`

All tests verified via: `./bin/test ed.unicoach.queue`

## Implementation Plan

1. **Database Notification Trigger**: Create a new DB migration in `db/schema/`
   (e.g., `0004.add-jobs-notify-trigger.sql`) that creates a trigger on `jobs`
   emitting `pg_notify('jobs_channel', NEW.job_type)` when
   `status = 'SCHEDULED'`. Verify trigger via simple `bin/db-update` inserts.

2. **Define result types and contracts**: Add
   `implementation(libs.kotlinx.coroutines.core)` and
   `testImplementation(libs.kotlinx.coroutines.test)` to
   `queue/build.gradle.kts` since coroutines aren't exported by `:common`. Apply
   alias `kotlinx-coroutines-test` in `gradle/libs.versions.toml`. Create
   `JobResult.kt`, `BackoffStrategy.kt`, `JobTypeConfig.kt`, `JobHandler.kt` in
   `ed.unicoach.queue`. Create `BackoffStrategyTest.kt`. Verify:
   `./bin/test ed.unicoach.queue.BackoffStrategyTest`.

3. **Implement `QueueWorker`**: Create `QueueWorker.kt` with the notification
   listener, execution loop, handler dispatch, retry/dead-letter logic, stuck
   job reaper, and completed job reaper. Verify: `./gradlew :queue:build`
   compiles.

4. **Implement `QueueWorkerTest`**: Create integration tests for all worker
   behaviors. Use a simple test `JobHandler` implementation that returns
   configurable results. Validate instant wakeup without sleeps. Verify:
   `./bin/test ed.unicoach.queue.QueueWorkerTest`.

## Files Modified

- `queue/src/main/kotlin/ed/unicoach/queue/JobResult.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/BackoffStrategy.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobTypeConfig.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobHandler.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/QueueWorker.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/BackoffStrategyTest.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/QueueWorkerTest.kt` [NEW]
- `queue/build.gradle.kts` [MODIFY]
- `gradle/libs.versions.toml` [MODIFY]
- `db/schema/0004.add-jobs-notify-trigger.sql` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/dao/JobsDao.kt` [MODIFY]
- `queue/src/test/kotlin/ed/unicoach/queue/dao/JobsDaoTest.kt` [MODIFY]
- `db/src/main/kotlin/ed/unicoach/db/Database.kt` [MODIFY]
