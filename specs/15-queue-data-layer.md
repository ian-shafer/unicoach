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

Add `kotlinx-serialization-json` (version `1.11.0`) as an `api` dependency of the `common` module.
Two separate entries are required in `gradle/libs.versions.toml`:

- **Plugin** (`org.jetbrains.kotlin.plugin.serialization`): added under `[plugins]` as
  `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`.
  The plugin is part of the Kotlin compiler toolchain and **must** use `version.ref = "kotlin"` — a
  mismatch causes a compile error.
- **Library** (`kotlinx-serialization-json`): added under `[versions]` as
  `kotlinx-serialization = "1.11.0"` and under `[libraries]` as
  `kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }`.
  The runtime library has its own independent versioning cadence.

> **Versioning note — read before changing this version**: The plugin and the runtime library have
> **separate version numbers that must not be confused**. The plugin version is pinned to
> `version.ref = "kotlin"` (currently `2.3.20`) and must never have an explicit version entry.
> The runtime library (`kotlinx-serialization-json`) follows its own cadence and must be pinned
> independently. Each library release is built against a specific Kotlin version; pick the release
> that matches the project's Kotlin version. As of Kotlin `2.3.20`, the correct library version is
> `1.11.0` (confirmed on the [GitHub releases page](https://github.com/Kotlin/kotlinx.serialization/releases)).
> To update: check the release notes on that page and verify the target Kotlin version matches.

The `kotlin-serialization` plugin must be applied to `common` (and any module that uses `@Serializable` annotations).

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

### Configuration

The queue module uses a dedicated `queue.conf` configuration file loaded via a `QueueConfig` object. This establishes the pattern for adding future properties (e.g., global timeouts or fallback policies). The `queue {}` block is required now — even while empty — so that a misconfigured classpath (e.g., missing resource directory, excluded module) causes a hard startup failure via `QueueConfig.from(config).getOrThrow()`, rather than a silent failure discovered only when the worker daemon first attempts to dispatch a job.

**`queue/src/main/resources/queue.conf`**:

```hocon
queue {
    # Establish the root block. Ready for future properties.
}
```

**`QueueConfig.kt`** (in `ed.unicoach.queue`):

```kotlin
class QueueConfig private constructor() {
    companion object {
        fun from(config: Config): Result<QueueConfig> = runCatching {
            // Verify the block exists as a basic health check
            config.getConfig("queue")
            QueueConfig()
        }
    }
}
```

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

### Attempt Status

Modeled as a Kotlin enum in `ed.unicoach.queue.AttemptStatus`:

```kotlin
enum class AttemptStatus(val value: String) {
    SUCCESS("SUCCESS"),
    RETRIABLE_FAILURE("RETRIABLE_FAILURE"),
    PERMANENT_FAILURE("PERMANENT_FAILURE"),
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
- `finishedAt: Instant`
- `status: AttemptStatus`
- `errorMessage: String?`

Attempt rows are written after job execution completes or fails — never at claim time. `finishedAt`
is always set by the database at insert time and is therefore non-nullable.

**`NewJob`** — DAO input for insertion (per DAO input data class pattern):
- `jobType: JobType`
- `payload: JsonObject`
- `maxAttempts: Int?`
- `delay: kotlin.time.Duration?`

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

CREATE INDEX idx_jobs_scheduled_job_type ON jobs (job_type, scheduled_at)
    WHERE status = 'SCHEDULED';
CREATE INDEX idx_jobs_status_locked_until ON jobs (status, locked_until)
    WHERE status = 'RUNNING';
```

**`job_attempts` table**:

```sql
CREATE TABLE job_attempts (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    job_id          UUID            NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number  INTEGER         NOT NULL,
    started_at      TIMESTAMPTZ     NOT NULL,
    finished_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    status          TEXT            NOT NULL,
    error_message   TEXT            NULL,

    CONSTRAINT job_attempts_status_length_check CHECK (length(status) <= 32),
    CONSTRAINT job_attempts_status_valid_check CHECK (
        status IN ('SUCCESS', 'RETRIABLE_FAILURE', 'PERMANENT_FAILURE')
    ),
    CONSTRAINT job_attempts_error_message_length_check CHECK (
        error_message IS NULL OR length(error_message) <= 4096
    ),

    UNIQUE(job_id, attempt_number)
);

CREATE INDEX idx_job_attempts_job_id ON job_attempts (job_id);
```

The `jobs` table gets a single trigger for `updated_at` maintenance. Unlike `sessions`, it has no
`version` column so `enforce_versioning` does not apply:

```sql
CREATE TRIGGER trigger_03_enforce_jobs_updated_at
BEFORE UPDATE ON jobs
FOR EACH ROW
EXECUTE PROCEDURE update_timestamp();
```

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
- `findNextScheduledJob(session, jobType): JobFindResult` — Selects a single job
  with `status = 'SCHEDULED' AND scheduled_at <= NOW()` using
  `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1`. Returns either a `Job` or `NotFound`.
- `claimJob(session, id, lockDuration: kotlin.time.Duration): JobUpdateResult` — Transitions a
  job from `SCHEDULED` to `RUNNING` and sets `locked_until = NOW() + ?::interval`. `lockDuration`
  is required and non-nullable; a caller cannot invoke this method without specifying a lock window,
  making misuse impossible by construction.
- `updateStatus(session, id, status: JobStatus): JobUpdateResult` — Transitions a job from
  `RUNNING` to a terminal status (`COMPLETED` or `DEAD_LETTERED`). Always clears `locked_until`
  to `NULL`. Does not accept a `lockDuration` — the signature makes setting a lock on a terminal
  transition structurally impossible.
- `reschedule(session, id, delay: kotlin.time.Duration): JobUpdateResult` — Resets status to
  `SCHEDULED` with a future `scheduled_at = NOW() + ?::interval` for retry backoff.
- `insertAttempt(session, jobId, attemptNumber, startedAt, status: AttemptStatus, errorMessage?): AttemptInsertResult` —
  Records a completed or failed attempt in `job_attempts`. Always called after job execution
  resolves — never at claim time. `startedAt` MUST be taken from `Job.updatedAt` of the
  `JobUpdateResult.Success` returned by the preceding `claimJob(...)` call;
  that value is written by the DB `update_timestamp()` trigger and is therefore a DB-sourced
  timestamp. Application-side clocks (`Instant.now()`) MUST NOT be used for `startedAt`.
  `finished_at` is set to `NOW()` by the database at insert time.
- `countAttempts(session, jobId): AttemptCountResult` — Returns the count of
  attempts for a given job.
- `findAttemptsByJobId(session, jobId): AttemptFindResult` — Returns all attempts
  for a job, ordered by `attempt_number`.
- `resetStuckRunning(session): JobResetResult` — Resets jobs
  with `status = 'RUNNING' AND locked_until < NOW()` back to `SCHEDULED`.
- `deleteBefore(session, statuses: Set<JobStatus>, olderThan: kotlin.time.Duration): JobDeleteResult` —
  Physically deletes jobs where `status IN (?)` and `updated_at < NOW() - ?::interval`.
  Relying on the database `NOW()` ensures we do not trust application server clocks for eviction boundaries.
- `deleteByIds(session, ids: List<UUID>): JobDeleteResult` — Deletes specific
  jobs by ID.
- `findById(session, id): JobFindResult` — Finds a single job by ID.
- `countByStatus(session, jobType?): JobCountResult` — Returns counts grouped by
  status, optionally filtered by job type. Used by `q-status` CLI.

**Sealed Result Types**:

- `JobInsertResult`: `Success(job)`, `DatabaseFailure(error)`
- `JobFindResult`: `Success(job)`, `NotFound(message)`, `DatabaseFailure(error)`
- `JobUpdateResult`: `Success(job)`, `NotFound(message)`, `DatabaseFailure(error)`
- `JobResetResult`: `Success(count: Int)`, `DatabaseFailure(error)`
- `JobDeleteResult`: `Success(count: Int)`, `DatabaseFailure(error)`
- `JobCountResult`: `Success(counts: Map<JobStatus, Int>)`, `DatabaseFailure(error)`
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
        delay: kotlin.time.Duration? = null,
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

- `JobTypeTest` (in `queue`):
  - `fromValue returns enum for valid string`
  - `fromValue returns null for unknown string`

- `QueueConfigTest` (in `queue`):
  - `from parses valid minimal configuration`
  - `from fails if queue block is completely missing`

`AttemptStatus` and `JobStatus` have no dedicated unit tests. They contain no companion logic and
no `fromValue` lookup — they are pure value enums. Their valid values are enforced at the database
layer by `CHECK` constraints, exercised by the `insertAttempt constraint rejects invalid status`
and `status constraint rejects invalid status values` integration tests in `JobsDaoTest`.

### Integration (against real Postgres)

- `JobsDaoTest` (in `queue`):
  - `insert creates job with SCHEDULED status and default scheduled_at`
  - `insert with delay sets future scheduled_at`
  - `insert with null max_attempts stores NULL`
  - `insert with explicit max_attempts stores value`
  - `findNextScheduledJob returns only SCHEDULED job with scheduled_at in the past`
  - `findNextScheduledJob excludes future scheduled_at`
  - `findNextScheduledJob uses SKIP LOCKED to prevent double pickup`
  - `findNextScheduledJob returns NotFound when no jobs of the requested type exist`
  - `findNextScheduledJob ignores SCHEDULED jobs of a different type`
  - `claimJob transitions SCHEDULED to RUNNING setting locked_until via SQL`
  - `updateStatus transitions RUNNING to COMPLETED clearing locked_until`
  - `updateStatus transitions RUNNING to DEAD_LETTERED clearing locked_until`
  - `reschedule resets to SCHEDULED with future scheduled_at`
  - `insertAttempt records attempt with all fields`
  - `insertAttempt enforces unique job_id and attempt_number`
  - `insertAttempt constraint rejects invalid status`
  - `insertAttempt constraint rejects oversized error_message`
  - `countAttempts returns correct count`
  - `findAttemptsByJobId returns attempts ordered by attempt_number`
  - `resetStuckRunning resets stale RUNNING jobs`
  - `resetStuckRunning ignores RUNNING jobs with future locked_until`
  - `resetStuckRunning returns count equal to number of stale jobs reset`
  - `deleteBefore removes old jobs with matching statuses via SQL interval`
  - `deleteBefore ignores jobs with statuses not in the provided set`
  - `deleteBefore ignores jobs newer than the duration`
  - `deleteByIds cascades to job_attempts`
  - `findById returns job`
  - `findById returns NotFound for nonexistent ID`
  - `countByStatus returns counts grouped by status`
  - `countByStatus filters by job type when specified`
  - `payload size constraint rejects oversized payloads`
  - `status constraint rejects invalid status values`

- `QueueServiceTest` (in `queue`, integration — delegates to `JobsDao.insert()` via real DB):
  - `enqueue creates SCHEDULED job with immediate scheduled_at`
  - `enqueue with delay sets future scheduled_at`
  - `enqueue with custom max_attempts stores value on job`
  - `enqueue with null max_attempts stores NULL`

All tests verified via: `./bin/test ed.unicoach.queue`

## Implementation Plan

1. **Add kotlinx-serialization to `common`**: In `gradle/libs.versions.toml`, add
   `kotlinx-serialization = "1.11.0"` under `[versions]`, add
   `kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }`
   under `[libraries]`, and add
   `kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }`
   under `[plugins]`. Apply `alias(libs.plugins.kotlin.serialization)` and add
   `api(libs.kotlinx.serialization.json)` in `common/build.gradle.kts`. Create
   `JsonExtensions.kt` in `common`. Create `JsonExtensionsTest.kt`. Verify:
   `./bin/test ed.unicoach.common`.

2. **Create `queue` Gradle module skeleton**: Add `queue/build.gradle.kts` with
   `common` and `db` dependencies, plus `kotlinx-serialization-json`. Apply
   the `kotlin-serialization` plugin. Add `include("queue")` to
   `settings.gradle.kts`. Verify: `./gradlew :queue:build`.

3. **Define configuration and domain models**: Create `queue/src/main/resources/queue.conf` with a basic `queue {}` block. Create `QueueConfig.kt` and `QueueConfigTest.kt`. Create `JobType.kt`, `JobTypeTest.kt`, `JobStatus.kt`,
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

7. **Load Queue configuration globally**: Add `implementation(project(":queue"))` to
   `rest-server/build.gradle.kts`. In `startServer()` in
   `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`, add `"queue.conf"` to the
   `AppConfig.load(...)` call and add `QueueConfig.from(config).getOrThrow()` immediately after
   the existing config blocks (`DatabaseConfig`, `SessionConfig`), following the same fail-fast
   startup validation pattern. Verify: `./gradlew :rest-server:build` compiles and the server
   starts without error.

## Files Modified

- `gradle/libs.versions.toml` [MODIFY]
- `common/build.gradle.kts` [MODIFY]
- `common/src/main/kotlin/ed/unicoach/common/json/JsonExtensions.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/common/json/JsonExtensionsTest.kt` [NEW]
- `settings.gradle.kts` [MODIFY]
- `queue/build.gradle.kts` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/QueueConfig.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/QueueConfigTest.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` [NEW]
- `queue/src/test/kotlin/ed/unicoach/queue/JobTypeTest.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/JobStatus.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/AttemptStatus.kt` [NEW]
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
- `rest-server/build.gradle.kts` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
