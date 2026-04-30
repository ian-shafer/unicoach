# SPEC: `net` Module

## I. Overview

The `net` Gradle module is the **integration layer** between the queue
infrastructure (`queue`) and domain data access (`db`). It owns all production
`JobHandler` implementations and the configuration class (`NetConfig`) that
governs their runtime parameters. It has no web-facing endpoints and is never
loaded by `rest-server`.

---

## II. Invariants

- The `net` module MUST depend on `common`, `db`, and `queue`; it MUST NOT
  introduce direct dependencies on `rest-server` or any Ktor server artifact.
- `NetConfig` MUST be constructed via `NetConfig.from(config).getOrThrow()` at
  `queue-worker` startup; a missing or malformed `net.conf` MUST cause a hard
  startup failure.
- `net.conf` MUST be loaded by `queue-worker` and MUST NOT be loaded by
  `rest-server`.
- `SessionExpiryHandler` MUST register `jobType = JobType.SESSION_EXTEND_EXPIRY`.
- `SessionExpiryHandler` MUST return `JobResult.PermanentFailure` on any
  payload deserialization or Base64 decoding error; it MUST NOT retry
  undecodable payloads.
- `SessionExpiryHandler` MUST return `JobResult.Success` (no retry) when
  `findByTokenHash()` returns `NotFound` (session expired, revoked, or deleted).
- `SessionExpiryHandler` MUST return `JobResult.Success` (no retry) when
  `extendExpiry()` returns `NotFound` (OCC version mismatch — session already
  extended by a concurrent handler).
- `SessionExpiryHandler` MUST return `JobResult.RetriableFailure` on any
  `DatabaseFailure` from `findByTokenHash()` or `extendExpiry()`.
- `SessionExpiryHandler` MUST NOT extend the session if `expiresAt` is strictly
  after `Instant.now() + slidingWindowThreshold`.
- `SessionExpiryHandler` MUST NOT write to the session's `metadata` JSONB
  column; deduplication relies entirely on OCC versioning and the sliding window
  check.
- `SessionExpiryHandler` MUST declare `JobTypeConfig` with exactly: `concurrency
  = 1`, `maxAttempts = 3`, `lockDuration = 1 minute`, `executionTimeout = 30
  seconds`. Deviation from any of these values changes queue throughput and retry
  SLAs.
- All `JobHandler` implementations in `net` MUST be dispatched on an IO-bound
  coroutine context by `QueueWorker`; they MUST NOT wrap JDBC calls in an
  additional `withContext(Dispatchers.IO)`.

---

## III. Behavioral Contracts

### `NetConfig`

See [`NetConfig.kt`](./NetConfig.kt).

- **Parse**: `NetConfig.from(config): Result<NetConfig>` reads the `net` HOCON
  block and extracts `net.session.slidingWindowThreshold` as a `java.time.Duration`.
  - **Side effects**: None. Pure config parsing.
  - **Errors**: Any missing key or type mismatch causes `runCatching` to capture
    the exception; the caller receives `Result.failure(...)`.
  - **Idempotent**: Yes — same config input always produces the same result.

### `SessionExpiryHandler`

See [`handlers/SessionExpiryHandler.kt`](./handlers/SessionExpiryHandler.kt).

Implements `JobHandler` for `JobType.SESSION_EXTEND_EXPIRY`. Extends the expiry
of a session when it is approaching the end of its TTL.

- **Execution parameters**:
  - `concurrency = 1` (single concurrent worker per job type)
  - `maxAttempts = 3`
  - `lockDuration = 1 minute`
  - `executionTimeout = 30 seconds`

#### `execute(payload: JsonObject): JobResult`

- **Side effects**:
  1. Reads one row from the `sessions` table via `SessionsDao.findByTokenHash()`.
  2. Conditionally writes one row to the `sessions` table via
     `SessionsDao.extendExpiry()` (only when `expiresAt ≤ now + threshold`).
- **Payload contract**: `payload` MUST deserialize to `SessionExpiryPayload`
  with a `tokenHash` field containing a valid Base64-encoded SHA-256 hash of
  the raw session token. Failure to deserialize or decode → `PermanentFailure`.
- **Error handling**:
  | Condition | Return |
  |---|---|
  | Malformed payload / invalid Base64 | `PermanentFailure("Malformed payload: ...")` |
  | `findByTokenHash` → `NotFound` | `Success` (session gone — no-op) |
  | `findByTokenHash` → `DatabaseFailure` | `RetriableFailure(error.message)` |
  | `expiresAt > now + threshold` | `Success` (not approaching — no-op) |
  | `extendExpiry` → `Success` | `Success` |
  | `extendExpiry` → `NotFound` (OCC mismatch) | `Success` (already extended — no-op) |
  | `extendExpiry` → `DatabaseFailure` | `RetriableFailure(error.message)` |
- **Idempotent**: Yes — duplicate jobs for the same session both return
  `Success`. The first handler extends the session (bumping the OCC version);
  subsequent handlers see either `expiresAt` outside the sliding window or an
  OCC version mismatch, both of which are treated as no-ops.

---

## IV. Infrastructure & Environment

### HOCON Configuration

`net.conf` (loaded from the classpath by `queue-worker`):

```hocon
net {
    session {
        slidingWindowThreshold = 2 days
    }
}
```

Resource path: `net/src/main/resources/net.conf`.

- `net.session.slidingWindowThreshold` — `java.time.Duration`. Controls *when*
  the handler triggers an extension; does NOT control the extension duration
  (that is hard-coded to 7 days inside `SessionsDao.extendExpiry()`).

### Gradle Module

`net/build.gradle.kts` — dependencies: `common`, `db`, `queue`, `slf4j.api`.
Test dependencies: `kotlin-test-junit5`, `postgresql`, `hikaricp`.

### Consumer Wiring (queue-worker)

- `queue-worker/build.gradle.kts` MUST declare `implementation(project(":net"))`.
- `queue-worker`'s `AppConfig.load(...)` MUST include `"net.conf"`.
- `SessionExpiryHandler` MUST be instantiated with
  `netConfig.sessionSlidingWindowThreshold` and registered in the handler list.

### No rest-server Dependency

`rest-server` MUST NOT load `net.conf` or depend on the `net` module. The
`SessionExpiryPayload` used by the Ktor plugin lives in the `queue` module to
avoid a cross-dependency.

---

## V. History

- [x] [RFC-21: Session Expiry Queue](../../../../../../../rfc/21-session-expiry-queue.md)
