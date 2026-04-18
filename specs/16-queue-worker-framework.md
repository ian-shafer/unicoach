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
    val pollInterval: Duration = Duration.ofSeconds(5),
)
```

- `concurrency`: Number of coroutines polling this job type simultaneously.
- `maxAttempts`: Default max attempts if not specified at enqueue time.
- `backoffStrategy`: Delay calculation between retries.
- `lockDuration`: How long a `RUNNING` job is locked before the reaper reclaims
  it.
- `pollInterval`: How frequently this job type's lane polls for new work.

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
constructor injection.

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

- For each registered handler, launches `handler.config.concurrency` coroutines
  that each run the poll loop for that handler's `jobType`.
- Launches one coroutine for the stuck job reaper.
- Launches one coroutine for the completed job reaper.

**`stop(timeout: Duration)`**: Signals all coroutines to stop accepting new
work. Waits up to `timeout` for in-flight handlers to complete.

#### Poll Loop

Each poll coroutine executes the following cycle:

1. `database.withConnection { session -> JobsDao.findScheduledJobs(session, jobType, limit = 1) }`
2. If no jobs found, sleep for `handler.config.pollInterval`, repeat.
3. For each job found: a. Transition status to `RUNNING` with
   `locked_until = NOW() + lockDuration`. b. Commit the transaction (release the
   `FOR UPDATE` lock). c. Execute the handler in a separate `try/catch`:
   - Record attempt start time.
   - Call `handler.execute(job.payload)`.
   - If handler throws an uncaught exception, treat as
     `RetriableFailure(exception.message)`. d. Record the attempt in
     `job_attempts`. e. Based on result:
   - `Success` → update status to `COMPLETED`.
   - `RetriableFailure` → check attempt count against resolved max attempts
     (`job.maxAttempts ?: handler.config.maxAttempts`). If exhausted, update to
     `DEAD_LETTERED`. Otherwise, reschedule with
     `backoffStrategy.delayFor(attemptNumber)`.
   - `PermanentFailure` → update status to `DEAD_LETTERED` immediately.

#### Max Attempts Resolution

The worker resolves max attempts per job as:
`job.maxAttempts ?: handler.config.maxAttempts`. This allows per-job overrides
at enqueue time while falling back to the handler's configured default.

#### Unknown Job Type Handling

If a job's `job_type` value does not map to any registered handler (e.g., the
enum has a variant but no handler is registered, or the value is unknown), the
worker dead-letters the job immediately and records an attempt with
`PERMANENT_FAILURE` and error message indicating no handler found.

#### Stuck Job Reaper

A single coroutine that runs every `stuckJobCheckInterval`:

1. Calls `JobsDao.resetStuckRunning(session, cutoff = Instant.now())`.
2. Logs the count of reset jobs to stderr.

#### Completed Job Reaper

A single coroutine that runs every `completedJobReapInterval`:

1. Calls
   `JobsDao.deleteCompletedBefore(session, cutoff = Instant.now() - completedJobRetention)`.
2. Logs the count of deleted jobs to stderr.

### Error Handling

- Handler execution is wrapped in `try/catch`. Any uncaught exception is
  converted to `RetriableFailure(e.message ?: "Unknown error")`.
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
  - `worker dead-letters job with unknown job type`
  - `worker resolves max_attempts from job when set`
  - `worker resolves max_attempts from handler config when job is NULL`
  - `stuck job reaper resets stale RUNNING jobs`
  - `completed job reaper deletes old completed jobs`
  - `completed job reaper excludes dead-lettered jobs`
  - `worker respects per-job-type concurrency`
  - `worker does not pick up jobs with future scheduled_at`

All tests verified via: `./bin/test ed.unicoach.queue`

## Implementation Plan

1. **Define result types and contracts**: Create `JobResult.kt`,
   `BackoffStrategy.kt`, `JobTypeConfig.kt`, `JobHandler.kt` in
   `ed.unicoach.queue`. Create `BackoffStrategyTest.kt`. Verify:
   `./bin/test ed.unicoach.queue.BackoffStrategyTest`.

2. **Implement `QueueWorker`**: Create `QueueWorker.kt` with the poll loop,
   handler dispatch, retry/dead-letter logic, stuck job reaper, and completed
   job reaper. Verify: `./gradlew :queue:build` compiles.

3. **Implement `QueueWorkerTest`**: Create integration tests for all worker
   behaviors. Use a simple test `JobHandler` implementation that returns
   configurable results. Verify: `./bin/test ed.unicoach.queue.QueueWorkerTest`.

## Files Modified

- `queue/src/main/kotlin/ed/unicoach/queue/JobResult.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/BackoffStrategy.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobTypeConfig.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobHandler.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/QueueWorker.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/BackoffStrategyTest.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/QueueWorkerTest.kt` [NEW]
- `queue/build.gradle.kts` [MODIFY]
