# SPEC: `queue-worker/src/main/kotlin/ed/unicoach/worker`

## I. Overview

This directory contains the sole entry point for the `queue-worker` JVM daemon.
Its singular domain is **process bootstrapping**: load configuration, wire
dependencies (database, job handlers), start the `QueueWorker` orchestrator,
register a graceful-shutdown hook, and block until the process is terminated.
All job-processing logic lives in the `queue` module; all handler logic lives in
the `net` module.

---

## II. Invariants

- The daemon MUST load exactly six HOCON config files at startup:
  `common.conf`, `db.conf`, `service.conf`, `queue.conf`, `queue-worker.conf`,
  `net.conf`. Any missing or malformed config MUST cause a hard startup failure
  via `.getOrThrow()`.
- `QueueConfig.from(config).getOrThrow()` MUST be called before any queue
  infrastructure is constructed. A misconfigured queue block MUST kill the
  process before a `QueueWorker` is created.
- `NetConfig.from(config).getOrThrow()` MUST be called and its result used to
  configure every handler that requires a net-level parameter. A misconfigured
  `net` block MUST kill the process at startup.
- The `QueueWorker` MUST receive a `JobsDao` instance — it MUST NOT construct
  its own DAO internally; the DAO is injected at the call site. External
  injection allows the DAO to be swapped for a test double without subclassing
  `QueueWorker`.
- The shutdown hook MUST call `worker.stop(timeout = 30.seconds)` and MUST be
  registered before `worker.start()` is called, so that an OS signal arriving
  during startup does not leave the worker running without a stop path.
- `database.close()` MUST be called in a `finally` block so connection pool
  teardown is guaranteed regardless of whether the `runBlocking` coroutine
  completes normally or is interrupted.
- The `main()` function MUST NOT contain any job-handling logic, session logic,
  or HTTP concerns. It is a pure wiring and lifecycle function.
- The `HealthMarker` nonce-file protocol MUST NOT be present. RFC 23 removed all
  `HealthMarker` call sites from this file; re-introducing them violates the
  current architectural contract.

---

## III. Behavioral Contracts

### `main()`

**Signature:** `fun main()`
**File:** [`Application.kt`](./Application.kt)

**Startup sequence (order is invariant):**

1. `AppConfig.load(...)` — loads and merges all six HOCON files from the
   classpath. **Side effect:** reads classpath resources. Throws on
   missing/invalid config.
2. `QueueConfig.from(config).getOrThrow()` — validates the `queue` block.
   **Side effect:** none beyond parsing. Throws on invalid config.
3. `DatabaseConfig.from(config).getOrThrow()` — validates the `db` block and
   constructs a `DatabaseConfig`. Throws on invalid config.
4. `Database(dbConfig)` — initializes the HikariCP connection pool.
   **Side effect:** opens JDBC connections to PostgreSQL.
5. `JobsDao()` — constructs a stateless DAO for job table access.
   **Side effect:** none.
6. `NetConfig.from(config).getOrThrow()` — validates the `net` block and
   extracts `sessionSlidingWindowThreshold`. Throws on invalid config.
7. Handler list construction — instantiates `SessionExpiryHandler(database,
   netConfig.sessionSlidingWindowThreshold)`. Currently the only registered
   handler.
8. `QueueWorker(database, jobsDao, handlers)` — constructs the worker
   orchestrator. **Side effect:** validates for duplicate `jobType` entries;
   throws `IllegalArgumentException` on conflict.
9. `Runtime.getRuntime().addShutdownHook(Thread { worker.stop(timeout =
   30.seconds) })` — registers OS-signal handler. **Side effect:** JVM shutdown
   hook registration.
10. `runBlocking { worker.start(this); awaitCancellation() }` — starts all
    worker coroutines and blocks the main thread until cancellation.
    **Side effect:** launches PostgreSQL `LISTEN/NOTIFY` connection, per-type
    polling coroutines, stuck-job reaper, completed-job reaper.
11. `finally { database.close() }` — tears down the HikariCP pool.
    **Side effect:** closes all JDBC connections.

**Error handling:**
- Any exception thrown during steps 1–8 propagates out of `main()` and
  terminates the JVM with a non-zero exit code.
- The `runBlocking` block is not wrapped in a catch; an unhandled coroutine
  failure propagates as a JVM crash, which is correct — the process should not
  silently swallow a broken worker.
- `database.close()` in `finally` is unconditional.

**Idempotency:** Not applicable — `main()` is invoked exactly once per process
lifetime.

**Shutdown behavior:** On `SIGTERM` or `SIGINT`, the JVM shutdown hook invokes
`worker.stop(timeout = 30.seconds)`, which signals all coroutines to stop
accepting new work and waits up to 30 seconds for in-flight handlers to
complete. If the timeout expires, the coroutine scope is force-cancelled. After
`runBlocking` returns, `database.close()` executes.

---

## IV. Infrastructure & Environment

### HOCON Config Files (classpath)

| File | Owner module | Key config consumed |
|---|---|---|
| `common.conf` | `common` | Shared defaults |
| `db.conf` | `db` | JDBC URL, credentials, pool config |
| `service.conf` | `service` | Empty at this revision; included because the `service` module is a transitive classpath dependency. No config keys from this file are read directly in `main()`. |  
| `queue.conf` | `queue` | Queue framework defaults |
| `queue-worker.conf` | `queue-worker` | Daemon-level placeholder (currently empty) |
| `net.conf` | `net` | `net.session.slidingWindowThreshold` |

### Environment Variables (consumed transitively)

| Variable | Consumer | Purpose |
|---|---|---|
| `DATABASE_JDBCURL` | `db.conf` → `DatabaseConfig` | JDBC connection string |
| `DATABASE_USER` | `db.conf` → `DatabaseConfig` | DB username |
| `DATABASE_PASSWORD` | `db.conf` → `DatabaseConfig` | DB password |

No JVM system properties (`run.dir`, `service.name`, `health.nonce`) are read
by this module. The `HealthMarker` nonce protocol was removed in RFC 23.

### Runtime Artifacts

- **PID file**: `var/run/queue-worker.pid` — written by `bin/daemon-up`, not
  by this JVM process.
- **Log file**: `var/log/queue-worker.log` — stdout/stderr captured by
  `bin/daemon-up` via `nohup`.
- **No health marker file**: `var/run/queue-worker.check` MUST NOT be created.
  Readiness is determined by PID liveness (`bin/queue-worker-check` delegates to
  `bin/daemon-check`).

### Build & Deployment

- Built via `bin/build-queue-worker` → `./gradlew :queue-worker:installDist`.
- Launch binary: `queue-worker/build/install/queue-worker/bin/queue-worker`.
- `bin/queue-worker-up` guards that the `installDist` binary exists before
  invoking `bin/daemon-up`; it MUST fail with an explicit error if the binary
  is absent.
- `bin/queue-worker-check` delegates to `bin/daemon-check queue-worker` (PID
  liveness — no HTTP port to probe).
- `bin/queue-worker-wait-for-health` wraps `bin/wait-for 4s
  bin/queue-worker-check`.

### Module Dependencies

```text
queue-worker → common, db, queue, net, service
```

---

<!-- RFC paths below are relative to this file and resolve to the repo root `rfc/` directory (7 levels up). -->
## V. History

- [x] [RFC-17: Queue Worker Daemon and CLI](../../../../../../../rfc/17-queue-worker-daemon.md)
- [x] [RFC-19: Daemon Health Marker](../../../../../../../rfc/19-daemon-health-marker.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../../../rfc/21-session-expiry-queue.md)
- [x] [RFC-23: Native Daemon Scripts](../../../../../../../rfc/23-native-daemon-scripts.md)
