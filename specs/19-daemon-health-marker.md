# 18: Daemon Health Marker

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
  ├─ HEALTH_NONCE="$NONCE" bin/docker-compose -f ... up -d --wait
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

Pass it inline to `bin/docker-compose`:

```bash
HEALTH_NONCE="$NONCE" "$PROJECT_ROOT/bin/docker-compose" -f "$COMPOSE_FILE" up -d --wait
```

This scopes `HEALTH_NONCE` exclusively to the subprocess. No `export`.

#### `bin/docker-compose`

Add `HEALTH_NONCE` to the existing inline environment variable block:

```bash
HOST_UID="$(id -u)" \
HOST_GID="$(id -g)" \
PORT="${PORT:-8080}" \
HEALTH_NONCE="${HEALTH_NONCE:-}" \
POSTGRES_DATA_DIR="${POSTGRES_DATA_DIR:-./var/postgres}" \
...
docker compose --env-file /dev/null --project-directory "$PROJECT_ROOT" "$@"
```

The `:-` default to empty ensures non-health-checked invocations (e.g.,
`docker-compose logs`) pass through without error.

#### `bin/queue-worker-start`

Remove the nonce generation added during the spec-17 implementation. Retain the
`postgres-start` dependency. The script delegates entirely to
`docker-daemon-start`:

```bash
#!/usr/bin/env bash
source "$(dirname "$0")/common"

"$PROJECT_ROOT/bin/postgres-start" || exit $?

exec "$PROJECT_ROOT/bin/docker-daemon-start" "$@" queue-worker
```

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
      - GRADLE_USER_HOME=/workspace/var/gradle
      - HOME=/workspace/var/gradle
      - DATABASE_JDBCURL=${DATABASE_JDBCURL:-jdbc:postgresql://postgres:5432/unicoach}
      - DATABASE_USER=${DATABASE_USER:-postgres}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD:-}
      - HEALTH_NONCE=${HEALTH_NONCE}
      - RUN_DIR=/workspace/var/run
      - SERVICE_NAME=queue-worker
    command: ["./gradlew", ":queue-worker:run"]
    healthcheck:
      test:
        [
          "CMD-SHELL",
          '[ "$(cat /workspace/var/run/queue-worker.check 2>/dev/null)" =
          "$HEALTH_NONCE" ]',
        ]
      interval: 5s
      timeout: 3s
      retries: 12
      start_period: 30s

networks:
  default:
    name: unicoach-network
```

`start_period: 30s` accommodates Gradle compilation and JVM boot time. During
the start period, failed healthchecks do not count toward retries.

#### `docker/rest-server-compose.yaml`

Same pattern:

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
      - GRADLE_USER_HOME=/workspace/var/gradle
      - HOME=/workspace/var/gradle
      - DATABASE_JDBCURL=${DATABASE_JDBCURL:-jdbc:postgresql://postgres:5432/unicoach}
      - DATABASE_USER=${DATABASE_USER:-postgres}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD:-}
      - HEALTH_NONCE=${HEALTH_NONCE}
      - RUN_DIR=/workspace/var/run
      - SERVICE_NAME=rest-server
    command: ["./gradlew", ":rest-server:run"]
    healthcheck:
      test:
        [
          "CMD-SHELL",
          '[ "$(cat /workspace/var/run/rest-server.check 2>/dev/null)" =
          "$HEALTH_NONCE" ]',
        ]
      interval: 5s
      timeout: 3s
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
}
```

No logging, no static state, no singletons. The caller owns the lifecycle.

#### `HealthMarker.fromSystemProperties()` factory

A convenience factory that reads the three required system properties and fails
fast if any are missing:

```kotlin
companion object {
    fun fromSystemProperties(): HealthMarker {
        val runDir = System.getProperty("run.dir")
            ?: error("System property [run.dir] is not set")
        val serviceName = System.getProperty("service.name")
            ?: error("System property [service.name] is not set")
        val nonce = System.getProperty("health.nonce")
            ?: error("System property [health.nonce] is not set")
        return HealthMarker(runDir, serviceName, nonce)
    }
}
```

#### Gradle System Property Forwarding

Both `queue-worker/build.gradle.kts` and `rest-server/build.gradle.kts` add:

```kotlin
tasks.named<JavaExec>("run") {
    systemProperty("run.dir", providers.environmentVariable("RUN_DIR").getOrElse(""))
    systemProperty("service.name", providers.environmentVariable("SERVICE_NAME").getOrElse(""))
    systemProperty("health.nonce", providers.environmentVariable("HEALTH_NONCE").getOrElse(""))
}
```

This explicitly maps container environment variables to JVM system properties.
No convention-based magic.

#### `queue-worker/Application.kt` Integration

```kotlin
val healthMarker = HealthMarker.fromSystemProperties()

Runtime.getRuntime().addShutdownHook(Thread {
    worker.stop(timeout = 30.seconds)
    healthMarker.delete()
})

try {
    runBlocking {
        worker.start(this)
        healthMarker.write()
        awaitCancellation()
    }
} finally {
    healthMarker.delete()
    database.close()
}
```

Remove `HEALTH_NONCE` and `HEALTH_FILE` environment variable reads. Remove
`java.io.File` import if no longer needed elsewhere.

#### `rest-server/Application.kt` Integration

```kotlin
fun startServer(wait: Boolean = true): EmbeddedServer<*, *> {
    // ... existing config loading ...
    val healthMarker = HealthMarker.fromSystemProperties()

    val server = embeddedServer(Netty, port = portInt, host = hostStr) {
        environment.monitor.subscribe(ApplicationStopped) {
            healthMarker.delete()
            database.close()
        }
        appModule(database, sessionConfig)
    }

    server.start(wait = false)
    healthMarker.write()

    if (wait) {
        Runtime.getRuntime().addShutdownHook(Thread {
            healthMarker.delete()
        })
        Thread.currentThread().join()
    }

    return server
}
```

The marker is written after `server.start(wait = false)` returns, confirming
Netty is bound and accepting connections. The `ApplicationStopped` monitor event
handles cleanup on graceful shutdown.

### Error Handling

- `HealthMarker` constructor fails fast via `require` on blank inputs.
- `fromSystemProperties()` fails fast via `error()` on missing properties.
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

### Shell Integration Tests: Stale Marker Resilience

File: `bin/scripts-tests` (added to the existing daemon wrapper test function)

1. **`stale marker does not fool start/check`**: For the `rest-server` wrapper:
   - Stop the service.
   - Write a fake nonce (`echo "stale-nonce" > var/run/rest-server.check`).
   - Start the service.
   - Assert `check` returns success (the new container overwrites the stale
     marker with its own nonce).
   - Stop the service.

### Implicit Integration Coverage

The existing daemon lifecycle tests in `scripts-tests` (start, check, stop,
restart, concurrent start/stop) inherently validate the full nonce flow once
healthchecks are added. No modifications needed — `docker compose up --wait`
will block until the healthcheck passes, confirming the nonce was written
correctly.

## Implementation Plan

### Step 1: Create `HealthMarker` in `common`

Create `common/src/main/kotlin/ed/unicoach/common/HealthMarker.kt` with the
class, `require` guards, `write()`, `delete()`, and `fromSystemProperties()`
factory.

Create `common/src/test/kotlin/ed/unicoach/common/HealthMarkerTest.kt` with all
7 unit tests.

Verify: `./gradlew :common:test`

### Step 2: Update shell infrastructure

Modify `bin/docker-daemon-start` to generate a nonce and pass it inline to
`bin/docker-compose`.

Modify `bin/docker-compose` to add `HEALTH_NONCE="${HEALTH_NONCE:-}"` to the
inline env var block.

Modify `bin/queue-worker-start` to remove nonce generation (retain
`postgres-start` dependency).

Verify: `bin/docker-daemon-start -h` still works, no syntax errors.

### Step 3: Update `queue-worker` Docker and Gradle configuration

Overwrite `docker/queue-worker-compose.yaml` with healthcheck, `HEALTH_NONCE`,
`RUN_DIR`, and `SERVICE_NAME` environment variables.

Modify `queue-worker/build.gradle.kts` to add system property forwarding in the
`run` task.

Modify `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` to use
`HealthMarker.fromSystemProperties()`. Remove direct
`HEALTH_NONCE`/`HEALTH_FILE` env var reads and `java.io.File` usage for the
marker.

Verify: `./gradlew :queue-worker:compileKotlin`

### Step 4: Update `rest-server` Docker and Gradle configuration

Overwrite `docker/rest-server-compose.yaml` with healthcheck, `HEALTH_NONCE`,
`RUN_DIR`, and `SERVICE_NAME` environment variables.

Modify `rest-server/build.gradle.kts` to add system property forwarding in the
`run` task.

Modify `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` to
integrate `HealthMarker`. Write marker after `server.start()`. Delete in
`ApplicationStopped` monitor and shutdown hook.

Verify: `./gradlew :rest-server:compileKotlin`

### Step 5: Add stale marker test

Modify `bin/scripts-tests` to add a stale marker resilience test within the
daemon wrapper test function.

### Step 6: Full verification

Run `bin/test` to verify Kotlin compilation and all JVM tests pass.

Run `bin/scripts-tests` to verify all daemon lifecycle tests pass with the new
healthchecks.

## Files Modified

- `common/src/main/kotlin/ed/unicoach/common/HealthMarker.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/common/HealthMarkerTest.kt` [NEW]
- `bin/docker-compose` [MODIFY]
- `bin/docker-daemon-start` [MODIFY]
- `bin/queue-worker-start` [MODIFY]
- `bin/scripts-tests` [MODIFY]
- `docker/queue-worker-compose.yaml` [MODIFY]
- `docker/rest-server-compose.yaml` [MODIFY]
- `queue-worker/build.gradle.kts` [MODIFY]
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` [MODIFY]
- `rest-server/build.gradle.kts` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
