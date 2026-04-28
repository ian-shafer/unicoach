# 19: Daemon Health Marker

## Executive Summary

Docker Compose `--wait` returns immediately for services without a healthcheck,
creating a race condition where `start` reports success before the JVM crashes.
A stale marker file from a previous run compounds the problem — `check` could
falsely report a dead container as healthy.

This spec introduces a generalized, nonce-based health marker system. On each
`start` invocation, `docker-daemon-start` generates a unique nonce. The nonce
flows explicitly through `bin/docker-compose` into the container environment, is
forwarded as a JVM system property via Gradle, and is written to a marker file
(`var/run/${SERVICE_NAME}.check`) by a shared `HealthMarker` utility after
successful initialization. The Docker healthcheck compares the marker file
contents against the nonce, guaranteeing that only the current container
instance can pass.

This pattern applies uniformly to all JVM-based daemons (`queue-worker`,
`rest-server`). Non-JVM services (postgres) are unaffected.

## Detailed Design

### Data Flow

The nonce traverses the following explicit chain. No `export` is used at any
point.

```
docker-daemon-start (generates nonce via uuidgen)
  │
  ├─ HEALTH_NONCE="$NONCE" bin/docker-compose -f ... up -d
  │
  └─ bin/docker-compose (explicit inline: HEALTH_NONCE="${HEALTH_NONCE:-}")
       │
       └─ docker compose (resolves ${HEALTH_NONCE} in YAML)
            │
            ├─ Container env: HEALTH_NONCE=<nonce>
            ├─ Container env: RUN_DIR=/workspace/var/run
            └─ Container env: SERVICE_NAME=<name>
                 │
                 └─ Gradle build.gradle.kts (forwards env → system properties)
                      │
                      ├─ -Dhealth.nonce=<nonce>
                      ├─ -Drun.dir=/workspace/var/run
                      └─ -Dservice.name=<name>
                           │
                           └─ HealthMarker reads System.getProperty(...)
```

### Shell Layer

#### `bin/docker-daemon-start`

After acquiring the file lock and before calling `bin/docker-compose`, generate
a nonce:

```bash
NONCE=$(uuidgen)
```

The startup strategy is chosen explicitly by the caller via the `--nonce` flag
(not derived from file contents):

**Nonce-based services (JVM daemons, `--nonce`):** Start detached and poll the
marker file on the shared volume from the host. No fixed timeout — compilation
can take as long as it needs. Only a container exit triggers failure:

```bash
MARKER_FILE="$PROJECT_ROOT/var/run/${SERVICE_NAME}.check"
HEALTH_NONCE="$NONCE" docker-compose up -d

while true; do
  if [ -f "$MARKER_FILE" ] && [ "$(cat "$MARKER_FILE")" = "$NONCE" ]; then
    # Wait for Docker's healthcheck to converge before returning.
    while ! docker-daemon-check "$SERVICE_NAME"; do sleep 1; done
    break
  fi

  # If the container exited, it's a boot failure.
  if container_state == "exited"; then
    docker-compose logs --no-log-prefix "$SERVICE_NAME" >&2
    docker-compose rm -f "$SERVICE_NAME"
    exit 1
  fi

  sleep 1
done
```

**Non-nonce services (e.g., postgres):** Fall back to Docker's native
healthcheck via `--wait`:

```bash
docker-compose up -d --wait
```

On boot failure for either mode, only the failing service container is removed.
`down` is never used — it tears down the shared network and kills unrelated
services.

#### `bin/docker-compose`

Add `HEALTH_NONCE` and `COMPOSE_IGNORE_ORPHANS` to the existing inline
environment variable block:

```bash
HOST_UID="$(id -u)" \
HOST_GID="$(id -g)" \
PORT="${PORT:-8080}" \
HEALTH_NONCE="${HEALTH_NONCE:-}" \
COMPOSE_IGNORE_ORPHANS=true \
POSTGRES_DATA_DIR="${POSTGRES_DATA_DIR:-./var/postgres}" \
...
docker compose --env-file /dev/null --project-directory "$PROJECT_ROOT" "$@"
```

The `:-` default to empty ensures non-health-checked invocations (e.g.,
`docker-compose logs`) pass through without error.

`COMPOSE_IGNORE_ORPHANS=true` suppresses the "Found orphan containers" warning
that Docker Compose emits when containers from other compose files share the
same project name and network. This is by design — each daemon has its own
compose file but they share `unicoach-network`.

### Docker Layer

#### `docker/queue-worker-compose.yaml`

Add environment variables and healthcheck:

```yaml
services:
  queue-worker:
    image: eclipse-temurin:21-jdk
    user: "${HOST_UID}:${HOST_GID}"
    working_dir: /workspace
    volumes:
      - ./:/workspace
    environment:
      - GRADLE_USER_HOME=/workspace/var/gradle-queue-worker
      - HOME=/workspace/var/gradle-queue-worker
      - DATABASE_JDBCURL=${DATABASE_JDBCURL:-jdbc:postgresql://postgres:5432/unicoach}
      - DATABASE_USER=${DATABASE_USER:-postgres}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD:-}
      - HEALTH_NONCE=${HEALTH_NONCE}
      - RUN_DIR=/workspace/var/run
      - SERVICE_NAME=queue-worker
    command:
      [
        "./gradlew",
        ":queue-worker:run",
        "--project-cache-dir",
        "/workspace/var/gradle-queue-worker/.project-cache",
      ]
    healthcheck:
      test:
        [
          "CMD-SHELL",
          '[ "$(cat /workspace/var/run/$$SERVICE_NAME.check 2>/dev/null)" =
          "$$HEALTH_NONCE" ]',
        ]
      interval: 200ms
      timeout: 5s
      retries: 12
      start_period: 30s

networks:
  default:
    name: unicoach-network
```

The `$$` escaping prevents Docker Compose from resolving `SERVICE_NAME` and
`HEALTH_NONCE` as compose-level variables. The literal `$` is passed through to
the container shell, which resolves them from the container's environment at
healthcheck execution time.

`start_period: 30s` defines how long Docker waits before counting failed
healthchecks toward the retry limit. This only affects Docker's internal health
label (used by `docker-daemon-check`), not the startup script — which polls the
marker file directly with no timeout.

Each JVM daemon uses its own `GRADLE_USER_HOME` and `--project-cache-dir` to
avoid Gradle cache lock contention when multiple daemons run concurrently.

#### `docker/rest-server-compose.yaml`

Same pattern, same `$$`-escaped healthcheck:

```yaml
services:
  rest-server:
    image: eclipse-temurin:21-jdk
    user: "${HOST_UID}:${HOST_GID}"
    working_dir: /workspace
    volumes:
      - ./:/workspace
    ports:
      - "${PORT}:${PORT}"
    environment:
      - PORT=${PORT}
      - GRADLE_USER_HOME=/workspace/var/gradle-rest-server
      - HOME=/workspace/var/gradle-rest-server
      - DATABASE_JDBCURL=${DATABASE_JDBCURL:-jdbc:postgresql://postgres:5432/unicoach}
      - DATABASE_USER=${DATABASE_USER:-postgres}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD:-}
      - HEALTH_NONCE=${HEALTH_NONCE}
      - RUN_DIR=/workspace/var/run
      - SERVICE_NAME=rest-server
    command:
      [
        "./gradlew",
        ":rest-server:run",
        "--project-cache-dir",
        "/workspace/var/gradle-rest-server/.project-cache",
      ]
    healthcheck:
      test:
        [
          "CMD-SHELL",
          '[ "$(cat /workspace/var/run/$$SERVICE_NAME.check 2>/dev/null)" =
          "$$HEALTH_NONCE" ]',
        ]
      interval: 200ms
      timeout: 5s
      retries: 12
      start_period: 30s

networks:
  default:
    name: unicoach-network
```

### JVM Layer

#### `HealthMarker` (new class in `common`)

Path: `common/src/main/kotlin/ed/unicoach/common/HealthMarker.kt`

```kotlin
package ed.unicoach.common

import java.io.File

class HealthMarker(
    runDir: String,
    serviceName: String,
    private val nonce: String,
) {
    init {
        require(runDir.isNotBlank()) { "runDir must not be blank" }
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(nonce.isNotBlank()) { "nonce must not be blank" }
    }

    private val file = File(runDir, "$serviceName.check")

    fun write() {
        file.parentFile.mkdirs()
        file.writeText(nonce)
    }

    fun delete() {
        file.delete()
    }

    companion object {
        /**
         * Creates a health marker from system properties, writes it, and
         * registers a shutdown hook for cleanup. No-ops gracefully when
         * system properties are absent (e.g. in test harnesses).
         *
         * Call once, after the service is fully initialized and ready to
         * accept traffic.
         */
        fun markHealthy() {
            val runDir = System.getProperty("run.dir")?.takeIf { it.isNotBlank() } ?: return
            val serviceName = System.getProperty("service.name")?.takeIf { it.isNotBlank() } ?: return
            val nonce = System.getProperty("health.nonce")?.takeIf { it.isNotBlank() } ?: return
            val marker = HealthMarker(runDir, serviceName, nonce)
            marker.write()
            Runtime.getRuntime().addShutdownHook(Thread { marker.delete() })
        }
    }
}
```

No logging, no singletons. The `markHealthy()` static method encapsulates the
entire health marker lifecycle: create from system properties, write the marker,
and register a shutdown hook for cleanup. Callers never hold a `HealthMarker`
instance. In test harnesses where system properties are absent, `markHealthy()`
silently no-ops.

#### Gradle System Property Forwarding

The root `build.gradle.kts` centralizes system property forwarding for all
subprojects with the `application` plugin:

```kotlin
subprojects {
    plugins.withId("application") {
        tasks.named<JavaExec>("run") {
            systemProperty("run.dir", providers.environmentVariable("RUN_DIR").getOrElse(""))
            systemProperty("service.name", providers.environmentVariable("SERVICE_NAME").getOrElse(""))
            systemProperty("health.nonce", providers.environmentVariable("HEALTH_NONCE").getOrElse(""))
        }
    }
}
```

This explicitly maps container environment variables to JVM system properties.
Applied once in the root build file to avoid duplicating the block in each
module. No convention-based magic.

#### `queue-worker/Application.kt` Integration

```kotlin
Runtime.getRuntime().addShutdownHook(Thread {
    worker.stop(timeout = 30.seconds)
})

try {
    runBlocking {
        worker.start(this)
        HealthMarker.markHealthy()
        awaitCancellation()
    }
} finally {
    database.close()
}
```

The shutdown hook handles `worker.stop()`. The `markHealthy()` shutdown hook
(registered internally) handles marker cleanup. The `finally` block handles
`database.close()`. No `HealthMarker` instance is held by the caller.

#### `rest-server/Application.kt` Integration

```kotlin
fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
    // ... existing config loading ...

    val server = embeddedServer(Netty, port = portInt, host = hostStr) {
        environment.monitor.subscribe(ApplicationStopped) {
            database.close()
        }
        appModule(database, sessionConfig)
    }

    // Start non-blocking, wait for Netty to bind, then signal readiness.
    server.start(wait = false)
    @Suppress("DEPRECATION")
    kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors() }
    HealthMarker.markHealthy()

    if (wait) {
        Thread.currentThread().join()
    }

    return server
}
```

The existing `server.start(wait = wait)` call MUST be replaced with
`server.start(wait = false)`. The original call blocks forever when
`wait = true`, which would prevent the marker from being written. After
starting, `resolvedConnectors()` blocks until Netty has actually bound the port,
preventing a premature readiness signal. Blocking is reimplemented manually via
`Thread.currentThread().join()`. The `ApplicationStopped` monitor handles
`database.close()`. Marker cleanup is handled internally by `markHealthy()`'s
shutdown hook.

The `startServer()` function is shared between production (`main()`) and test
harnesses (`AuthRoutingTest`, `RoutingTest`). In test contexts, system
properties are absent and `markHealthy()` silently no-ops.

### Error Handling

- `HealthMarker` constructor fails fast via `require` on blank inputs.
- `markHealthy()` silently no-ops when any system property is missing or blank
  (graceful degradation for test harnesses).
- `write()` calls `mkdirs()` to ensure the directory exists; `writeText` throws
  `IOException` on permission failures, which crashes the application (correct
  behavior — if we can't write the marker, the healthcheck will never pass, and
  Docker will restart the container).
- `delete()` returns `false` silently if the file doesn't exist (idempotent).

### Dependencies

No new dependencies. `HealthMarker` uses only `java.io.File`.

## Tests

### Unit Tests: `HealthMarker`

File: `common/src/test/kotlin/ed/unicoach/common/HealthMarkerTest.kt`

1. **`write creates file with nonce contents`**: Create a `HealthMarker` with a
   temp directory. Call `write()`. Assert the file exists and contains the exact
   nonce string.

2. **`write creates parent directories`**: Create a `HealthMarker` pointing to a
   non-existent subdirectory. Call `write()`. Assert the file and directories
   were created.

3. **`delete removes file`**: Call `write()` then `delete()`. Assert the file no
   longer exists.

4. **`delete is idempotent`**: Call `delete()` without calling `write()` first.
   Assert no exception is thrown.

5. **`constructor rejects blank runDir`**: Pass blank `runDir`. Assert
   `IllegalArgumentException`.

6. **`constructor rejects blank serviceName`**: Pass blank `serviceName`. Assert
   `IllegalArgumentException`.

7. **`constructor rejects blank nonce`**: Pass blank `nonce`. Assert
   `IllegalArgumentException`.

8. **`markHealthy noops when system properties are absent`**: Clear all three
   system properties. Call `markHealthy()`. Assert no exception is thrown.

### Shell Integration Tests: Stale Marker Resilience

File: `bin/scripts-tests` (added to the existing daemon wrapper test function)

For each JVM daemon (`rest-server`, `queue-worker`):

1. Stop the service.
2. Write a fake nonce (`echo "stale-nonce" > var/run/${WRAPPER}.check`).
3. Start the service.
4. Assert `check` returns success (the new container overwrites the stale marker
   with its own nonce).
5. Stop the service.

### Shell Integration Tests: Test Ordering

JVM daemons require a live postgres connection to boot. With healthchecks now
enforced, `docker compose up --wait` properly blocks until the JVM is healthy,
which requires a database connection. The test suite orders accordingly:

1. `test_daemon_wrapper "postgres"` runs first.
2. `ensure_postgres_for_jvm` restarts postgres and creates the default
   `unicoach` database before each JVM daemon start (since `stop -d` tears down
   the shared network).
3. `test_daemon_wrapper "rest-server"` and `test_daemon_wrapper "queue-worker"`
   run after postgres infrastructure is established.

### Implicit Integration Coverage

The existing daemon lifecycle tests in `scripts-tests` (start, check, stop,
restart, concurrent start/stop) inherently validate the full nonce flow once
healthchecks are added. The startup script polls the marker file directly,
confirming the nonce was written correctly before returning success.

## Implementation Plan

### Step 1: Create `HealthMarker` in `common`

Create `common/src/main/kotlin/ed/unicoach/common/HealthMarker.kt` with the
class, `require` guards, `write()`, `delete()`, and `markHealthy()` companion
method.

Create `common/src/test/kotlin/ed/unicoach/common/HealthMarkerTest.kt` with all
8 unit tests.

Verify: `./gradlew :common:test`

### Step 2: Update shell infrastructure

Modify `bin/docker-daemon-start` to accept a `--nonce` flag, generate a nonce,
and pass it inline to `bin/docker-compose`. Use nonce-based marker polling when
`--nonce` is set, Docker's native `--wait` otherwise.

Modify `bin/docker-daemon-restart` to accept and forward `--nonce` to
`bin/docker-daemon-start`.

Modify `bin/docker-compose` to add `HEALTH_NONCE="${HEALTH_NONCE:-}"` and
`COMPOSE_IGNORE_ORPHANS=true` to the inline env var block.

Modify `bin/rest-server-start` and `bin/queue-worker-start` to pass `--nonce`.
Modify `bin/rest-server-restart` and `bin/queue-worker-restart` to pass
`--nonce`.

Verify: `bin/docker-daemon-start -h` still works, no syntax errors.

### Step 3: Centralize Gradle system property forwarding

Modify `build.gradle.kts` (root) to add a `subprojects` block that forwards
`RUN_DIR`, `SERVICE_NAME`, and `HEALTH_NONCE` environment variables to JVM
system properties for all subprojects with the `application` plugin.

Verify: `./gradlew :queue-worker:compileKotlin`

### Step 4: Update `queue-worker` Docker configuration

Overwrite `docker/queue-worker-compose.yaml` with healthcheck, `HEALTH_NONCE`,
`RUN_DIR`, and `SERVICE_NAME` environment variables.

Modify `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` to call
`HealthMarker.markHealthy()` after `worker.start()`. Remove all manual marker
lifecycle code.

Verify: `./gradlew :queue-worker:compileKotlin`

### Step 5: Update `rest-server` Docker configuration

Overwrite `docker/rest-server-compose.yaml` with healthcheck, `HEALTH_NONCE`,
`RUN_DIR`, and `SERVICE_NAME` environment variables.

Modify `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` to call
`HealthMarker.markHealthy()` after `resolvedConnectors()` confirms Netty has
bound the port. Remove all manual marker lifecycle code.

Verify: `./gradlew :rest-server:compileKotlin`

### Step 6: Add stale marker test and update test ordering

Modify `bin/scripts-tests` to add a stale marker resilience test within the
daemon wrapper test function. Reorder tests: postgres first, then JVM daemons.
Add `ensure_postgres_for_jvm` helper to restart postgres whenever the shared
network is torn down during JVM daemon testing.

### Step 7: Full verification

Run `bin/test` to verify Kotlin compilation and all JVM tests pass.

Run `bin/scripts-tests` to verify all daemon lifecycle tests pass with the new
healthchecks.

## Files Modified

- `common/src/main/kotlin/ed/unicoach/common/HealthMarker.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/common/HealthMarkerTest.kt` [NEW]
- `build.gradle.kts` [MODIFY]
- `bin/docker-compose` [MODIFY]
- `bin/docker-daemon-start` [MODIFY]
- `bin/docker-daemon-restart` [MODIFY]
- `bin/rest-server-start` [MODIFY]
- `bin/rest-server-restart` [MODIFY]
- `bin/queue-worker-start` [MODIFY]
- `bin/queue-worker-restart` [MODIFY]
- `bin/scripts-tests` [MODIFY]
- `docker/queue-worker-compose.yaml` [MODIFY]
- `docker/rest-server-compose.yaml` [MODIFY]
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
