# 15 Queue Data Layer

## Executive Summary

This specification creates the `queue` Gradle module and implements its data
layer: the PostgreSQL schema for `jobs` and `job_attempts` tables, the `JobsDao`
for all CRUD and lifecycle operations, and the `QueueService` enqueue API. It
also adds `kotlinx-serialization-json` to the `common` module with generic JSON
extension functions (`asJson()`, `deserialize()`) for typed payload handling.
This spec establishes the persistent foundation that the queue worker framework
(spec 16) builds upon.

## Detailed Design

### kotlinx-serialization in `common`

Add `kotlinx-serialization-json` as an `api` dependency of the `common` module.
The `kotlin-serialization` Gradle plugin must also be applied to `common` (and
any module that uses `@Serializable` annotations).

Create generic JSON extension functions in
`common/src/main/kotlin/ed/unicoach/common/json/JsonExtensions.kt`:

- `inline fun <reified T> JsonObject.deserialize(): T` — Decodes a `JsonObject`
  into a typed data class via `Json.decodeFromJsonElement`.
- `inline fun <reified T> T.asJson(): JsonObject` — Encodes a `@Serializable`
  object into a `JsonObject` via `Json.encodeToJsonElement`.

These are domain-agnostic utilities usable across all modules.

### Queue Module Structure

- **New Gradle Module**: `queue`
- **Package**: `ed.unicoach.queue`
- **Dependencies**: `common`, `db`

### JobType Enum

Defined in `ed.unicoach.queue.JobType`. Each variant maps to a database TEXT
value via a `value` property:

```kotlin
enum class JobType(val value: String) {
    // Intentionally empty in this spec. Concrete job types are added by
    // consuming specs as they implement specific handlers.
    ;

    companion object {
        fun fromValue(value: String): JobType? =
            entries.find { it.value == value }
    }
}
```

The `fromValue` lookup is used by the worker (spec 16) to dispatch incoming jobs.
The enum starts empty — consuming specs add variants as they implement handlers.

### Job Status

Modeled as a Kotlin enum in `ed.unicoach.queue.JobStatus`:

```kotlin
enum class JobStatus(val value: String) {
    SCHEDULED("SCHEDULED"),
    RUNNING("RUNNING"),
    COMPLETED("COMPLETED"),
    DEAD_LETTERED("DEAD_LETTERED"),
}
```

### Domain Models

**`Job`** — represents a persisted job row:
- `id: UUID`
- `createdAt: Instant`
- `updatedAt: Instant`
- `jobType: JobType`
- `payload: JsonObject`
- `status: JobStatus`
- `scheduledAt: Instant`
- `lockedUntil: Instant?`
- `maxAttempts: Int?`

**`JobAttempt`** — represents an attempt record:
- `id: UUID`
- `jobId: UUID`
- `attemptNumber: Int`
- `startedAt: Instant`
- `finishedAt: Instant?`
- `status: String` (SUCCESS, RETRIABLE_FAILURE, PERMANENT_FAILURE)
- `errorMessage: String?`
- `errorCode: String?`

**`NewJob`** — DAO input for insertion (per DAO input data class pattern):
- `jobType: JobType`
- `payload: JsonObject`
- `maxAttempts: Int?`
- `delay: Duration?`

### Schema Migration

A single migration file creates both tables.

**`jobs` table**:

```sql
CREATE TABLE jobs (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    created_at      TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
    updated_at      TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
    job_type        TEXT            NOT NULL,
    payload         JSONB           NOT NULL,
    status          TEXT            NOT NULL DEFAULT 'SCHEDULED',
    scheduled_at    TIMESTAMPTZ     DEFAULT NOW() NOT NULL,
    locked_until    TIMESTAMPTZ     NULL,
    max_attempts    INTEGER         NULL,

    CONSTRAINT jobs_job_type_length_check CHECK (length(job_type) <= 128),
    CONSTRAINT jobs_status_length_check CHECK (length(status) <= 32),
    CONSTRAINT jobs_status_valid_check CHECK (
        status IN ('SCHEDULED', 'RUNNING', 'COMPLETED', 'DEAD_LETTERED')
    ),
    CONSTRAINT jobs_payload_size_check CHECK (octet_length(payload::text) <= 65536)
);

CREATE INDEX idx_jobs_status_scheduled_at ON jobs (status, scheduled_at)
    WHERE status = 'SCHEDULED';
CREATE INDEX idx_jobs_status_locked_until ON jobs (status, locked_until)
    WHERE status = 'RUNNING';
CREATE INDEX idx_jobs_job_type ON jobs (job_type);
```

**`job_attempts` table**:

```sql
CREATE TABLE job_attempts (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    job_id          UUID            NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number  INTEGER         NOT NULL,
    started_at      TIMESTAMPTZ     NOT NULL,
    finished_at     TIMESTAMPTZ     NULL,
    status          TEXT            NOT NULL,
    error_message   TEXT            NULL,
    error_code      TEXT            NULL,

    CONSTRAINT job_attempts_status_length_check CHECK (length(status) <= 32),
    CONSTRAINT job_attempts_status_valid_check CHECK (
        status IN ('SUCCESS', 'RETRIABLE_FAILURE', 'PERMANENT_FAILURE')
    ),
    CONSTRAINT job_attempts_error_message_length_check CHECK (
        error_message IS NULL OR length(error_message) <= 4096
    ),
    CONSTRAINT job_attempts_error_code_length_check CHECK (
        error_code IS NULL OR length(error_code) <= 128
    ),

    UNIQUE(job_id, attempt_number)
);

CREATE INDEX idx_job_attempts_job_id ON job_attempts (job_id);
```

An `updated_at` trigger is applied to the `jobs` table (reusing the shared
trigger function pattern from existing migrations).

### JobsDao

Located at `queue/src/main/kotlin/ed/unicoach/queue/dao/JobsDao.kt`. Follows
the existing DAO patterns:

- Uses `executeSafely` for DRY error handling.
- Accepts `SqlSession` as the first parameter.
- Returns sealed result types.

**Methods**:

- `insert(session, newJob): JobInsertResult` — Inserts a job with status
  `SCHEDULED`. If `delay` is non-null, `scheduled_at = NOW() + delay`. Returns
  the created `Job`.
- `findScheduledJobs(session, jobType, limit): JobFindManyResult` — Selects jobs
  with `status = 'SCHEDULED' AND scheduled_at <= NOW()` using
  `SELECT ... FOR UPDATE SKIP LOCKED LIMIT ?`. Returns a list of `Job`.
- `updateStatus(session, id, status, lockedUntil?): JobUpdateResult` —
  Transitions job status. Sets `locked_until` when transitioning to `RUNNING`.
- `reschedule(session, id, scheduledAt): JobUpdateResult` — Resets status to
  `SCHEDULED` with a future `scheduled_at` for retry backoff.
- `insertAttempt(session, jobId, attemptNumber, startedAt, finishedAt?, status, errorMessage?, errorCode?): AttemptInsertResult` —
  Records an attempt in `job_attempts`.
- `countAttempts(session, jobId): AttemptCountResult` — Returns the count of
  attempts for a given job.
- `findAttemptsByJobId(session, jobId): AttemptFindResult` — Returns all attempts
  for a job, ordered by `attempt_number`.
- `resetStuckRunning(session, cutoff: Instant): JobResetResult` — Resets jobs
  with `status = 'RUNNING' AND locked_until < cutoff` back to `SCHEDULED`.
- `deleteCompletedBefore(session, cutoff: Instant): JobDeleteResult` — Physically
  deletes `COMPLETED` jobs with `updated_at < cutoff`. Excludes
  `DEAD_LETTERED`.
- `deleteByIds(session, ids: List<UUID>): JobDeleteResult` — Deletes specific
  jobs by ID.
- `findById(session, id): JobFindResult` — Finds a single job by ID.
- `countByStatus(session, jobType?): JobCountResult` — Returns counts grouped by
  status, optionally filtered by job type. Used by `q-status` CLI.

**Sealed Result Types**:

- `JobInsertResult`: `Success(job)`, `DatabaseFailure(error)`
- `JobFindResult`: `Success(job)`, `NotFound(message)`, `DatabaseFailure(error)`
- `JobFindManyResult`: `Success(jobs: List<Job>)`, `DatabaseFailure(error)`
- `JobUpdateResult`: `Success(job)`, `NotFound(message)`, `DatabaseFailure(error)`
- `JobResetResult`: `Success(count: Int)`, `DatabaseFailure(error)`
- `JobDeleteResult`: `Success(count: Int)`, `DatabaseFailure(error)`
- `AttemptInsertResult`: `Success(attempt)`, `DatabaseFailure(error)`
- `AttemptCountResult`: `Success(count: Int)`, `DatabaseFailure(error)`
- `AttemptFindResult`: `Success(attempts: List<JobAttempt>)`, `DatabaseFailure(error)`

### QueueService

Located at `queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt`. Thin
wrapper over `JobsDao` providing the public enqueue API:

```kotlin
class QueueService(private val database: Database) {
    fun enqueue(
        jobType: JobType,
        payload: JsonObject,
        maxAttempts: Int? = null,
        delay: Duration? = null,
    ): EnqueueResult
}
```

- `EnqueueResult`: `Success(job)`, `DatabaseFailure(error)`
- Internally creates a `NewJob` and delegates to `JobsDao.insert()`.

## Tests

### Unit

- `JsonExtensionsTest` (in `common`):
  - `asJson encodes Serializable data class to JsonObject`
  - `deserialize decodes JsonObject to typed data class`
  - `deserialize fails with SerializationException for invalid structure`

### Integration (against real Postgres)

- `JobsDaoTest` (in `queue`):
  - `insert creates job with SCHEDULED status and default scheduled_at`
  - `insert with delay sets future scheduled_at`
  - `insert with null max_attempts stores NULL`
  - `insert with explicit max_attempts stores value`
  - `findScheduledJobs returns only SCHEDULED jobs with scheduled_at in the past`
  - `findScheduledJobs excludes future scheduled_at`
  - `findScheduledJobs uses SKIP LOCKED to prevent double pickup`
  - `findScheduledJobs respects limit parameter`
  - `updateStatus transitions SCHEDULED to RUNNING with locked_until`
  - `updateStatus transitions RUNNING to COMPLETED`
  - `updateStatus transitions RUNNING to DEAD_LETTERED`
  - `reschedule resets to SCHEDULED with future scheduled_at`
  - `insertAttempt records attempt with all fields`
  - `insertAttempt enforces unique job_id and attempt_number`
  - `countAttempts returns correct count`
  - `findAttemptsByJobId returns attempts ordered by attempt_number`
  - `resetStuckRunning resets stale RUNNING jobs`
  - `resetStuckRunning ignores RUNNING jobs with future locked_until`
  - `deleteCompletedBefore removes old COMPLETED jobs`
  - `deleteCompletedBefore excludes DEAD_LETTERED jobs`
  - `deleteByIds cascades to job_attempts`
  - `findById returns job`
  - `findById returns NotFound for nonexistent ID`
  - `countByStatus returns counts grouped by status`
  - `countByStatus filters by job type when specified`
  - `payload size constraint rejects oversized payloads`
  - `status constraint rejects invalid status values`

- `QueueServiceTest` (in `queue`):
  - `enqueue creates SCHEDULED job with immediate scheduled_at`
  - `enqueue with delay sets future scheduled_at`
  - `enqueue with custom max_attempts stores value on job`
  - `enqueue with null max_attempts stores NULL`

All tests verified via: `./bin/test ed.unicoach.queue`

## Implementation Plan

1. **Add kotlinx-serialization to `common`**: Add `kotlinx-serialization-json`
   to `gradle/libs.versions.toml`. Add the `kotlin-serialization` plugin to
   `gradle/libs.versions.toml` plugins section. Apply the plugin and add the
   dependency as `api` in `common/build.gradle.kts`. Create
   `JsonExtensions.kt` in `common`. Create `JsonExtensionsTest.kt`. Verify:
   `./bin/test ed.unicoach.common`.

2. **Create `queue` Gradle module skeleton**: Add `queue/build.gradle.kts` with
   `common` and `db` dependencies, plus `kotlinx-serialization-json`. Apply
   the `kotlin-serialization` plugin. Add `include("queue")` to
   `settings.gradle.kts`. Verify: `./gradlew :queue:build`.

3. **Define domain models and enums**: Create `JobType.kt`, `JobStatus.kt`,
   `Job.kt`, `JobAttempt.kt`, `NewJob.kt` in `ed.unicoach.queue`. Create
   sealed result types in `ed.unicoach.queue.dao`. Verify:
   `./gradlew :queue:build` compiles.

4. **Schema migration**: Create `db/schema/0003.create-queue.sql` with both
   `jobs` and `job_attempts` tables, indexes, constraints, and `updated_at`
   trigger. Verify: `./bin/db-migrate` applies cleanly.

5. **Implement `JobsDao`**: Create `JobsDao.kt` with all methods defined in
   Detailed Design. Follow `executeSafely` pattern from `SessionsDao`. Create
   `JobsDaoTest.kt` covering all test cases. Verify:
   `./bin/test ed.unicoach.queue.dao.JobsDaoTest`.

6. **Implement `QueueService`**: Create `QueueService.kt` with the `enqueue`
   method. Create `QueueServiceTest.kt`. Verify:
   `./bin/test ed.unicoach.queue.QueueServiceTest`.

## Files Modified

- `gradle/libs.versions.toml` [MODIFY]
- `common/build.gradle.kts` [MODIFY]
- `common/src/main/kotlin/ed/unicoach/common/json/JsonExtensions.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/common/json/JsonExtensionsTest.kt` [NEW]
- `settings.gradle.kts` [MODIFY]
- `queue/build.gradle.kts` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobStatus.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/Job.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobAttempt.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/NewJob.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/dao/JobsDao.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/dao/Results.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/dao/JobsDaoTest.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/QueueServiceTest.kt` [NEW]
- `queue/src/main/resources/queue.conf` [NEW]
- `db/schema/0003.create-queue.sql` [NEW]
