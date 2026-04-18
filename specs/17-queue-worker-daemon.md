# 17 Queue Worker Daemon and CLI

## Executive Summary

This specification creates the `queue-worker` Gradle module as a standalone
daemon process, mirroring the `rest-server` pattern. It includes the daemon
entry point (`main()`), HOCON configuration, Docker Compose definition, daemon
lifecycle scripts (`queue-worker-start/stop/check/restart`), and six CLI tools
for queue inspection and management (`q-status`, `q-truncate`, `q-enqueue`,
`q-delete-job`, `q-inspect`, `q-retry`). Together these provide the operational
infrastructure to run and manage the queue system in development and production.

## Detailed Design

### Queue Worker Module

- **New Gradle Module**: `queue-worker`
- **Package**: `ed.unicoach.worker`
- **Dependencies**: `common`, `db`, `queue`, `service`
- **Main class**: `ed.unicoach.worker.ApplicationKt`

The `queue-worker` module is structurally identical to `rest-server` — a thin
driving adapter with a `main()` function that wires dependencies and starts the
worker.

### Application Entry Point

Located at `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`:

```kotlin
fun main() {
    val config = AppConfig
        .load("common.conf", "db.conf", "service.conf", "queue-worker.conf")
        .getOrThrow()

    val dbConfig = DatabaseConfig.from(config).getOrThrow()
    val database = Database(dbConfig)

    val handlers = listOf<JobHandler>(
        // Concrete handlers registered here as they are implemented
        // in future specs.
    )

    val worker = QueueWorker(database, handlers)

    Runtime.getRuntime().addShutdownHook(Thread {
        worker.stop(timeout = Duration.ofSeconds(30))
        database.close()
    })

    runBlocking {
        worker.start(this)
        // Block until cancelled by shutdown hook
        awaitCancellation()
    }
}
```

### HOCON Configuration

`queue-worker/src/main/resources/queue-worker.conf`:

```hocon
queue-worker {
    # Placeholder for future worker-level configuration.
    # Per-job-type config lives on the handler (JobTypeConfig).
}
```

Per the HOCON configuration skill, each module gets its own `.conf` file. The
`queue-worker.conf` is intentionally minimal since per-job-type configuration
lives on the `JobHandler.config` property (spec 16).

### Docker Compose

`docker/queue-worker-compose.yaml`:

```yaml
services:
  queue-worker:
    image: eclipse-temurin:21-jdk
    user: "${HOST_UID}:${HOST_GID}"
    working_dir: /workspace
    volumes:
      - ./:/workspace
    environment:
      - GRADLE_USER_HOME=/workspace/var/gradle
      - HOME=/workspace/var/gradle
      - DATABASE_JDBCURL=${DATABASE_JDBCURL:-jdbc:postgresql://postgres:5432/unicoach}
      - DATABASE_USER=${DATABASE_USER:-postgres}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD:-}
    command: ["./gradlew", ":queue-worker:run"]

networks:
  default:
    external: true
    name: unicoach-network
```

This mirrors `rest-server-compose.yaml` but omits `ports` (no HTTP listener).

### Daemon Scripts

Following the existing daemon script pattern (e.g., `rest-server-start`):

- **`bin/queue-worker-start`**: Ensures the Docker network exists, then
  delegates to `docker-daemon-start queue-worker`.
- **`bin/queue-worker-stop`**: Delegates to `docker-daemon-stop queue-worker`.
- **`bin/queue-worker-check`**: Delegates to `docker-daemon-check queue-worker`.
- **`bin/queue-worker-restart`**: Delegates to
  `docker-daemon-restart queue-worker`.

### CLI Scripts

All CLI scripts source `bin/common` and use `bin/db-query` or `bin/db-update`
for database access. They do NOT depend on the JVM or Gradle — they are pure
shell scripts querying the database directly.

#### `bin/q-status [job-types...]`

Displays job counts grouped by status. If job types are provided, filters to
those types. If none are provided, lists all types.

Output format:

```
Job Type                  SCHEDULED  RUNNING  COMPLETED  DEAD_LETTERED
session.extend_expiry            5        1         42              0
email.welcome                    0        0         15              2
```

Implementation: SQL query grouping by `job_type` and `status` with `COUNT(*)`,
formatted via `psql` column output.

#### `bin/q-truncate [job-types...]`

Deletes all jobs (and their attempts via CASCADE) for the specified job types.
If no types are given, deletes all jobs.

Safety mechanism: Requires `--yes-i-really-want-to-do-this` flag for
non-interactive execution. Without the flag, prompts the user to type
`yes i really want to do this`. On empty input, exits with status `0`. On
incorrect input, prints input to stderr and prompts again. User can exit via
`ctrl-c` or `ctrl-d`.

This follows the same pattern as `db-destroy`'s `DESTROY` confirmation but with
the longer confirmation string and a flag variant.

The `db-destroy` script MUST also be updated to accept a `--destroy-all-db-data`
flag matching this pattern (currently it only supports the
`--destroy-all-db-data` flag — verify and align if discrepancies exist).

#### `bin/q-enqueue <job-type> <payload-json> [options]`

Enqueues a job from the command line. Useful for testing and debugging.

Arguments:

- `<job-type>`: Must be a valid `JobType` enum value string.
- `<payload-json>`: JSON payload string.

Options:

- `--max-attempts <n>`: Override max attempts.
- `--delay <duration>`: Delay before the job becomes eligible (e.g., `5m`, `1h`,
  `30s`).

Implementation: Direct `INSERT` via `bin/db-update`.

#### `bin/q-delete-job <job-id>`

Deletes a single job and its attempt history by UUID. Exits `0` on success, `1`
if the job is not found.

#### `bin/q-inspect <job-id>`

Displays full detail for a single job: all columns from `jobs`, followed by all
attempt records from `job_attempts` ordered by `attempt_number`.

Output format:

```
=== Job ===
ID:            550e8400-e29b-41d4-a716-446655440000
Type:          session.extend_expiry
Status:        DEAD_LETTERED
Created:       2026-04-17 19:00:00
Scheduled:     2026-04-17 19:00:00
Max Attempts:  3
Payload:       {"sessionId": "abc-123", "currentVersion": 2}

=== Attempts ===
#  Status               Started              Finished             Error
1  RETRIABLE_FAILURE    2026-04-17 19:00:05  2026-04-17 19:00:06  Connection timeout
2  RETRIABLE_FAILURE    2026-04-17 19:00:10  2026-04-17 19:00:11  Connection timeout
3  RETRIABLE_FAILURE    2026-04-17 19:00:18  2026-04-17 19:00:19  Connection timeout
```

#### `bin/q-retry <job-id>`

Resets a `DEAD_LETTERED` job back to `SCHEDULED` with `scheduled_at = NOW()`.
Rejects jobs that are not in `DEAD_LETTERED` status. Preserves existing attempt
history. The job will be retried using its original `max_attempts` value.

### Error Contracts

All CLI scripts follow the standard error contract:

- Exit `0` on success.
- Exit `1` on general failure.
- Exit `2` on container/database unreachability.
- Dynamic variables formatted with brackets (`[$VAR]`) per coding skill.
- Output to stderr for status messages and errors.

## Tests

### Daemon Script Tests

Added to `bin/scripts-tests` (or a new `bin/queue-worker-scripts-tests`):

- `test_queue_worker_start_stop`: Verify daemon starts and stops cleanly.
- `test_queue_worker_check_running`: Verify check reports running status.
- `test_queue_worker_check_stopped`: Verify check reports stopped status.
- `test_queue_worker_restart`: Verify restart cycles the daemon.

### CLI Script Tests

`bin/q-scripts-tests`:

#### q-status

- `test_q_status_no_jobs`: Empty output when no jobs exist.
- `test_q_status_with_jobs`: Correct counts by status.
- `test_q_status_filtered_by_type`: Only shows requested types.

#### q-truncate

- `test_q_truncate_with_flag`: Deletes jobs when flag provided.
- `test_q_truncate_interactive_success`: Deletes on correct confirmation.
- `test_q_truncate_interactive_empty_exit`: Exits `0` on empty input.
- `test_q_truncate_interactive_retry`: Prompts again on wrong input.
- `test_q_truncate_specific_types`: Only deletes specified types.
- `test_q_truncate_cascades_attempts`: Attempt records are deleted.

#### q-enqueue

- `test_q_enqueue_immediate`: Job created with SCHEDULED status.
- `test_q_enqueue_with_delay`: Job created with future scheduled_at.
- `test_q_enqueue_with_max_attempts`: Custom max_attempts stored.

#### q-delete-job

- `test_q_delete_job_success`: Job and attempts deleted.
- `test_q_delete_job_not_found`: Exits `1` with message.

#### q-inspect

- `test_q_inspect_shows_job_details`: All job fields displayed.
- `test_q_inspect_shows_attempts`: Attempt history displayed in order.
- `test_q_inspect_not_found`: Exits `1` with message.

#### q-retry

- `test_q_retry_resets_dead_lettered`: Status changes to SCHEDULED.
- `test_q_retry_rejects_non_dead_lettered`: Exits `1` for COMPLETED/RUNNING.
- `test_q_retry_preserves_attempt_history`: Existing attempts retained.

## Implementation Plan

1. **Create `queue-worker` Gradle module**: Add `queue-worker/build.gradle.kts`
   with dependencies on `common`, `db`, `queue`, `service`. Configure
   `application` plugin with main class. Add `include("queue-worker")` to
   `settings.gradle.kts`. Create `queue-worker.conf`. Verify:
   `./gradlew :queue-worker:build` compiles.

2. **Implement Application entry point**: Create `Application.kt` with `main()`,
   config loading, worker instantiation, shutdown hook, and coroutine blocking.
   Verify: `./gradlew :queue-worker:run` starts (and exits cleanly with no
   handlers).

3. **Docker Compose**: Create `docker/queue-worker-compose.yaml`. Verify:
   `docker compose -f docker/queue-worker-compose.yaml config` validates.

4. **Daemon scripts**: Create `bin/queue-worker-start`, `bin/queue-worker-stop`,
   `bin/queue-worker-check`, `bin/queue-worker-restart`. Make executable.
   Verify: daemon starts and stops via scripts.

5. **Daemon script tests**: Add tests to verify start/stop/check/restart
   lifecycle. Verify: daemon tests pass.

6. **CLI scripts — `q-status` and `q-inspect`**: Create read-only tools first.
   Add tests to `bin/q-scripts-tests`. Verify: tests pass.

7. **CLI scripts — `q-enqueue` and `q-delete-job`**: Create mutating tools. Add
   tests. Verify: tests pass.

8. **CLI scripts — `q-truncate` and `q-retry`**: Create tools with confirmation
   UX and status validation. Add tests. Verify: tests pass.

## Files Modified

- `settings.gradle.kts` [MODIFY]
- `queue-worker/build.gradle.kts` [NEW]
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` [NEW]
- `queue-worker/src/main/resources/queue-worker.conf` [NEW]
- `docker/queue-worker-compose.yaml` [NEW]
- `bin/queue-worker-start` [NEW]
- `bin/queue-worker-stop` [NEW]
- `bin/queue-worker-check` [NEW]
- `bin/queue-worker-restart` [NEW]
- `bin/q-status` [NEW]
- `bin/q-truncate` [NEW]
- `bin/q-enqueue` [NEW]
- `bin/q-delete-job` [NEW]
- `bin/q-inspect` [NEW]
- `bin/q-retry` [NEW]
- `bin/q-scripts-tests` [NEW]
