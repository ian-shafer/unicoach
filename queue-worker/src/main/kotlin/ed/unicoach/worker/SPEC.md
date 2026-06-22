# SPEC: `queue-worker/src/main/kotlin/ed/unicoach/worker`

## I. Overview

This directory holds the sole entry point for the `queue-worker` JVM daemon. Its
singular domain is **process bootstrapping**: it loads configuration, constructs
the composition root (database, job handlers), starts the `QueueWorker`
orchestrator, registers a graceful-shutdown hook, and blocks until the process
is terminated. All job-processing logic lives in the `queue` module; all handler
logic lives in the modules that own each `JobHandler`.

---

## II. Behavioral Contracts

### `main()`

**Signature:** `fun main()` **File:** [`Application.kt`](./Application.kt)

A pure wiring and lifecycle function carrying no job-handling, session, or HTTP
logic. It executes the following startup sequence in order:

1. **Config load** — `AppConfig.load(...)` merges seven HOCON files from the
   classpath in this order: `common.conf`, `db.conf`, `service.conf`,
   `chat.conf`, `queue.conf`, `queue-worker.conf`, `net.conf`. **Side effect:**
   reads classpath resources. `.getOrThrow()` propagates a failure on a missing
   or malformed file.
2. **Queue config validation** — `QueueConfig.from(config).getOrThrow()`
   validates the `queue` block. A misconfigured block throws before any queue
   infrastructure exists. The returned value is not retained; the call is a
   fail-fast gate.
3. **Database config + pool** — `DatabaseConfig.from(config).getOrThrow()`
   validates the `db` block, then `Database(dbConfig)` initializes the HikariCP
   connection pool. **Side effect:** opens JDBC connections to PostgreSQL.
4. **Jobs DAO** — constructs `ed.unicoach.queue.dao.JobsDao()`, a stateless DAO
   for job-table access, injected into the worker rather than constructed inside
   it. **Side effect:** none.
5. **Net config** — `NetConfig.from(config).getOrThrow()` validates the `net`
   block and exposes `sessionSlidingWindowThreshold` (from
   `net.session.slidingWindowThreshold`). Throws on a misconfigured block.
6. **Extraction config** — `ExtractionConfig.from(config).getOrThrow()`
   validates the `extraction` block of `service.conf` and exposes `enabled` plus
   the distillation parameters. Throws on a missing or unreadable key. See
   [`ExtractionConfig`](../../../../../../../service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionConfig.kt).
7. **Handler list construction** — builds an ordered `List<JobHandler>`:
   - `SessionExpiryHandler(database, netConfig.sessionSlidingWindowThreshold)`
     is always registered.
   - The extraction handler is registered **only when**
     `extractionConfig.enabled` is `true`. In that branch `main()` builds a
     `ChatProvider` via
     `ChatProviderFactory.fromConfig(ChatConfig.from(config).getOrThrow()).getOrThrow()`,
     constructs `ExtractionService(database, chatProvider, extractionConfig)`,
     and adds `ExtractionHandler(extractionService)`. The worker is the only
     production site that builds a `ChatProvider` for the extraction path. When
     `enabled` is `false`, no `ChatProvider` is built and the
     `EXTRACT_CONVERSATION` handler is absent. **Side effect (enabled branch):**
     `ChatProviderFactory` may construct a CIO `HttpClient` whose lifetime is
     owned by the provider. **Failure modes:** an unknown `chat.provider`
     selector or a missing Anthropic API key surfaces as a failed `Result` from
     the factory and propagates via `.getOrThrow()`.
8. **Worker construction** — `QueueWorker(database, jobsDao, handlers)`. **Side
   effect:** validates the handler set for duplicate `jobType` entries; throws
   `IllegalArgumentException` on a conflict.
9. **Shutdown hook** —
   `Runtime.getRuntime().addShutdownHook(Thread { worker.stop(timeout = 30.seconds) })`,
   registered before `worker.start()` so an OS signal arriving during startup
   still has a stop path. **Side effect:** JVM shutdown-hook registration.
10. **Run** — `runBlocking { worker.start(this); awaitCancellation() }` launches
    all worker coroutines and blocks the main thread until cancellation. **Side
    effect:** starts the PostgreSQL `LISTEN/NOTIFY` connection, per-type polling
    coroutines, and the stuck-job and completed-job reapers.
11. **Teardown** — `finally { database.close() }` tears down the HikariCP pool
    unconditionally, regardless of normal completion or interruption. **Side
    effect:** closes all JDBC connections.

**Error handling:** Any exception thrown during steps 1–8 propagates out of
`main()` and terminates the JVM with a non-zero exit code. The `runBlocking`
block is not wrapped in a catch; an unhandled coroutine failure propagates as a
JVM crash.

**Idempotency:** Not applicable — `main()` runs once per process lifetime.

**Shutdown behavior:** On `SIGTERM`/`SIGINT` the JVM shutdown hook invokes
`worker.stop(timeout = 30.seconds)`, which signals coroutines to stop accepting
new work and waits up to 30 seconds for in-flight handlers; on timeout the scope
is force-cancelled. After `runBlocking` returns, `database.close()` executes.

### Extraction handler wiring (cross-module references)

The worker constructs but does not define these collaborators; their contracts
live in their owning modules:

- [`ExtractionHandler`](../../../../../../../service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionHandler.kt)
  is the thin `JobHandler` for `JobType.EXTRACT_CONVERSATION`. It deserializes
  the job payload and delegates to `ExtractionService.extract`, mapping a
  malformed payload to `JobResult.PermanentFailure` and a transient service
  error to `JobResult.RetriableFailure`. The worker treats it as an opaque
  `JobHandler`.
- [`ExtractionService`](../../../../../../../service/src/main/kotlin/ed/unicoach/coaching/extraction/ExtractionService.kt)
  runs the per-conversation distillation pass. The worker supplies its three
  constructor dependencies: the shared `Database`, the `ChatProvider`, and the
  `ExtractionConfig`.

---

## III. Infrastructure & Environment

### HOCON Config Files (classpath)

| File                | Owner module   | Key config consumed by `main()`                                                             |
| ------------------- | -------------- | ------------------------------------------------------------------------------------------- |
| `common.conf`       | `common`       | Shared defaults                                                                             |
| `db.conf`           | `db`           | JDBC URL, credentials, pool config (`DatabaseConfig`)                                       |
| `service.conf`      | `service`      | `extraction` block (`ExtractionConfig`)                                                     |
| `chat.conf`         | `chat`         | `chat.provider` and `chat.anthropic.*` (`ChatConfig`), read only when extraction is enabled |
| `queue.conf`        | `queue`        | Queue framework defaults (`QueueConfig`)                                                    |
| `queue-worker.conf` | `queue-worker` | Daemon-level placeholder (currently empty)                                                  |
| `net.conf`          | `net`          | `net.session.slidingWindowThreshold` (`NetConfig`)                                          |

### Environment Variables (consumed transitively)

| Variable                 | Consumer                     | Purpose                                                   |
| ------------------------ | ---------------------------- | --------------------------------------------------------- |
| `DATABASE_JDBCURL`       | `db.conf` → `DatabaseConfig` | JDBC connection string                                    |
| `DATABASE_USER`          | `db.conf` → `DatabaseConfig` | DB username                                               |
| `DATABASE_PASSWORD`      | `db.conf` → `DatabaseConfig` | DB password                                               |
| `CHAT_PROVIDER`          | `chat.conf` → `ChatConfig`   | Selects the `ChatProvider` (`log` \| `anthropic`)         |
| `CHAT_ANTHROPIC_API_KEY` | `chat.conf` → `ChatConfig`   | Anthropic API key (required for the `anthropic` provider) |

`chat.conf` exposes further `CHAT_ANTHROPIC_*` overrides (base URL, timeouts)
consumed by the `chat` module. No JVM system properties (`run.dir`,
`service.name`, `health.nonce`) are read by this module.

### Runtime Artifacts

- **PID file**: `var/run/queue-worker.pid` — written by `bin/daemon-up`, not by
  this JVM process.
- **Log file**: `var/log/queue-worker.log` — stdout/stderr captured by
  `bin/daemon-up` via `nohup`.
- **No health marker file**: readiness is determined by PID liveness
  (`bin/queue-worker-check` delegates to `bin/daemon-check`); no
  `var/run/queue-worker.check` nonce file is created.

### Build & Deployment

- Built via `bin/build-queue-worker` → `./gradlew :queue-worker:installDist`.
- Launch binary: `queue-worker/build/install/queue-worker/bin/queue-worker`.
- `bin/queue-worker-up` checks that the `installDist` binary exists before
  invoking `bin/daemon-up`, failing with an explicit error if it is absent.
- `bin/queue-worker-check` delegates to `bin/daemon-check queue-worker` (PID
  liveness — no HTTP port to probe).
- `bin/queue-worker-wait-for-health` wraps
  `bin/wait-for 4s bin/queue-worker-check`.

### Module Dependencies

```text
queue-worker → common, db, queue, net, service, chat
```

`chat` is a direct dependency (added with RFC 66) so the worker can build a
`ChatProvider` for extraction and bring `chat.conf` onto the classpath; it was
previously only a transitive dependency via `service`.

---

<!-- RFC paths below are relative to this file and resolve to the repo root `rfc/` directory (7 levels up). -->

## IV. History

- [x] [RFC-17: Queue Worker Daemon and CLI](../../../../../../../rfc/17-queue-worker-daemon.md)
- [x] [RFC-19: Daemon Health Marker](../../../../../../../rfc/19-daemon-health-marker.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../../../rfc/21-session-expiry-queue.md)
- [x] [RFC-23: Native Daemon Scripts](../../../../../../../rfc/23-native-daemon-scripts.md)
- [x] [RFC-66: Conversation Extraction](../../../../../../../rfc/66-extraction.md)
