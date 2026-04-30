# SPEC: `rest-server/.../rest/plugins`

## I. Overview

This directory contains Ktor application-level plugins that are installed once
at server startup and apply cross-cutting concerns to every HTTP
request/response in the `rest-server`. The three plugins cover:

1. **Serialization** — JSON codec configuration (Jackson).
2. **StatusPages** — Global exception-to-HTTP-status mapping.
3. **SessionExpiryPlugin** — Post-response hook that asynchronously enqueues
   session expiry extension jobs.

---

## II. Invariants

### Serialization (`Serialization.kt`)

- The server MUST install `ContentNegotiation` with the Jackson codec via
  `configureSerialization()` before any route handles a request.
- Jackson MUST be configured with
  `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = true` — any JSON payload
  containing a field not present in the target data class MUST be rejected.
- Jackson MUST be configured with
  `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES = true` — any JSON
  payload omitting a required constructor field MUST be rejected.
- `SerializationFeature.INDENT_OUTPUT` MUST be enabled (formatted JSON output).

### StatusPages (`StatusPages.kt`)

- The server MUST install `StatusPages` via `configureStatusPages()` before any
  route handles a request.
- An unhandled `UnsupportedMediaTypeException` MUST produce a `415
  Unsupported Media Type` response with an `ErrorResponse` body (`code =
  "unsupported_media_type"`).
- An unhandled `BadRequestException` MUST produce a `400 Bad Request` response
  with an `ErrorResponse` body (`code = "bad_request"`, `message = "Invalid
  JSON payload structure"`).
- The `ErrorResponse` type used here MUST be the shared
  `ed.unicoach.rest.models.ErrorResponse` — no ad-hoc response types are
  permitted.

### SessionExpiryPlugin (`SessionExpiryPlugin.kt`)

- The plugin MUST fire on the `ResponseSent` hook — **after** the response has
  been fully delivered to the client.
- The plugin MUST NOT enqueue when the request cookie named
  `sessionConfig.cookieName` is absent.
- The plugin MUST NOT enqueue when the request path matches any prefix in
  `ignorePathPrefixes`.
- The plugin MUST NOT enqueue when the response HTTP status is outside the
  `200–299` range.
- Enqueue logic MUST run on `Dispatchers.IO` inside a fire-and-forget coroutine
  launched on the application scope (`call.application.launch`).
- The plugin MUST NEVER propagate exceptions to the caller — all errors MUST be
  caught, logged at `error` level, and swallowed.
- On server shutdown, in-flight fire-and-forget coroutines are silently
  cancelled by Ktor. This is an accepted trade-off; the next request
  re-enqueues.
- `SessionExpiryPluginConfig` MUST declare `sessionConfig` and `queueService`
  as `lateinit var`. Accessing either field without prior assignment MUST throw
  `UninitializedPropertyAccessException` at plugin startup.
- The token hash transmitted in the queue payload MUST be the SHA-256 hash of
  the raw cookie value, Base64-encoded (standard, not URL-safe), computed via
  `TokenHash.fromRawToken(token)`.
- The plugin MUST NOT perform any database reads or writes. Its only I/O side
  effect is a single call to `queueService.enqueue()`.

---

## III. Behavioral Contracts

### `configureSerialization()` ([Serialization.kt](./Serialization.kt))

- **Signature**: `fun Application.configureSerialization()`
- **Side Effects**: Installs the Ktor `ContentNegotiation` plugin with Jackson.
  No database writes, no network calls.
- **Error Handling**: If installation fails (e.g., duplicate plugin install),
  Ktor throws internally at startup — not a runtime HTTP error.
- **Idempotency**: MUST be called exactly once at startup. Calling it twice on
  the same `Application` instance results in a Ktor `DuplicatePluginException`.

---

### `configureStatusPages()` ([StatusPages.kt](./StatusPages.kt))

- **Signature**: `fun Application.configureStatusPages()`
- **Side Effects**: Installs the Ktor `StatusPages` plugin. No database writes,
  no network calls.
- **Handled Exceptions**:

  | Exception                       | HTTP Status | `ErrorResponse.code`         | `ErrorResponse.message`                       |
  |---------------------------------|-------------|------------------------------|------------------------------------------------|
  | `UnsupportedMediaTypeException`  | 415         | `"unsupported_media_type"`   | `cause.message ?: "Unsupported media type"`   |
  | `BadRequestException`            | 400         | `"bad_request"`              | `"Invalid JSON payload structure"` (hardcoded) |

- **Unhandled Exceptions**: Any exception type not listed above falls through to
  Ktor's default 500 handler.
- **Idempotency**: MUST be called exactly once at startup.

---

### `SessionExpiryPlugin` / `SessionExpiryPluginConfig` ([SessionExpiryPlugin.kt](./SessionExpiryPlugin.kt))

- **Type**: Ktor `ApplicationPlugin` created via `createApplicationPlugin`.
- **Plugin Name**: `"SessionExpiryPlugin"` (used by Ktor's internal registry).
- **Configuration** (`SessionExpiryPluginConfig`):

  | Field                | Type              | Required | Default        |
  |----------------------|-------------------|----------|----------------|
  | `sessionConfig`      | `SessionConfig`   | Yes      | `lateinit`     |
  | `queueService`       | `QueueService`    | Yes      | `lateinit`     |
  | `ignorePathPrefixes` | `Set<String>`     | No       | `emptySet()`   |

- **Hook**: `on(ResponseSent)`

- **Execution Flow** (all guards evaluated in order; first failure short-circuits):

  1. Read cookie `cookieName` from request. If absent → return.
  2. Read `call.request.uri`. If any `ignorePathPrefixes` element is a prefix →
     return.
  3. Read `call.response.status()?.value`. If null or outside `200..299` →
     return.
  4. Launch `call.application.launch(Dispatchers.IO)`:
     a. Hash cookie: `TokenHash.fromRawToken(token)`.
     b. Base64-encode hash bytes (standard encoder).
     c. Build `SessionExpiryPayload(tokenHash = encodedHash).asJson()`.
     d. Call `queueService.enqueue(JobType.SESSION_EXTEND_EXPIRY, payload)`.
     e. On `EnqueueResult.DatabaseFailure` → log error. On
        `EnqueueResult.Success` → no-op.
     f. On any uncaught exception → log error and swallow.

- **Side Effects**: One write to the job queue (PostgreSQL `jobs` table) per
  qualifying request, executed asynchronously on `Dispatchers.IO`.
- **Error Handling**:
  - `EnqueueResult.DatabaseFailure`: logged at `error` level via
    `LoggerFactory.getLogger("SessionExpiryPlugin")`, not re-thrown.
  - Any `Exception` in the coroutine: logged at `error` level, swallowed.
- **Idempotency**: Not idempotent per-request — multiple requests with the same
  session cookie will enqueue multiple jobs. Deduplication is handled downstream
  by the `SessionExpiryHandler` via OCC versioning and the sliding window check.

---

## IV. Infrastructure & Environment

- **HOCON Key** (`rest-server.conf`): `sessionExpiry.ignorePathPrefixes` — a
  list of path prefix strings. Example: `["/health"]`. Read by `Application.kt`
  and passed to `SessionExpiryPluginConfig.ignorePathPrefixes`.
- **Gradle Plugin** (`rest-server/build.gradle.kts`): `SessionExpiryPlugin`
  calls `SessionExpiryPayload(...).asJson()`, which requires the
  `alias(libs.plugins.kotlin.serialization)` plugin to be applied at the
  `rest-server` call site so the kotlinx-serialization compiler can generate the
  serializer for `SessionExpiryPayload`.
- **Queue Module Dependency**: `SessionExpiryPayload` lives in the `queue`
  module (not `net`) so both `rest-server` (enqueuer) and `net` (handler) can
  import it without a cross-dependency.
- **Logger Name**: `"SessionExpiryPlugin"` — used for all error-level logging
  emitted by the plugin.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md) — introduced `Serialization.kt` and `StatusPages.kt`.
- [x] [RFC-09: Global Config](../../../../../../../../rfc/09-global-config.md) — introduced `JwtConfig.kt` (subsequently deleted).
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — deleted `JwtConfig.kt` (replaced by `SessionConfig`-based auth).
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md) — introduced `SessionExpiryPlugin.kt`.
