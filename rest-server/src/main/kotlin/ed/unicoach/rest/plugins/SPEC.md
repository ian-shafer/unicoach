# SPEC: `rest-server/.../rest/plugins`

## I. Overview

This directory contains Ktor application-level plugins that are installed once
at server startup and apply cross-cutting concerns to every HTTP
request/response in the `rest-server`. The plugins cover:

1. **Serialization** — JSON codec configuration (Jackson).
2. **StatusPages** — Global exception-to-HTTP-status mapping.
3. **SessionExpiryPlugin** — Post-response hook that asynchronously enqueues
   session expiry extension jobs.
4. **RequestSizeLimit** — Application-scope request body size enforcement,
   rejecting oversized bodies with `413`.
5. **ClientKeyGate** — A pre-routing interceptor that rejects any request
   lacking a valid client key with `403`, exempting only a path allowlist.

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
- The Jackson `JavaTimeModule` MUST be registered, and
  `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` MUST be disabled, so that
  `java.time.Instant` (and other `java.time` values) serialize as ISO-8601
  strings rather than numeric epoch arrays. Any response body carrying an
  `Instant` field MUST emit it as an ISO-8601 string.

### StatusPages (`StatusPages.kt`)

- The server MUST install `StatusPages` via `configureStatusPages()` before any
  route handles a request.
- An unhandled `UnsupportedMediaTypeException` MUST produce a
  `415
  Unsupported Media Type` response with an `ErrorResponse` body
  (`code =
  "unsupported_media_type"`).
- An unhandled `PayloadTooLargeException` MUST produce a `413 Payload Too Large`
  response with `code = "payload_too_large"`.
- An unhandled `BadRequestException` MUST produce a `400 Bad Request` response
  with `code = "bad_request"` and a fixed message never derived from the cause —
  Jackson parsing internals MUST NOT reach the client.
- An unhandled `PermanentError` MUST produce `code = "permanent_error"` with a
  status partitioned by fault ownership:
  - Server-fault subtypes — database faults and persisted-state corruption
    (`DatabaseException`, `CorruptPersistedValueException`,
    `CorruptPersistedAuthMethodException`) — MUST produce `500`. Persisted-state
    corruption MUST NOT be presented as a client error; it is the server's
    fault.
  - Client-fault subtypes MUST keep their specific statuses: `NotFoundException`
    → `404`, `DuplicateEmailException` → `409`.
  - All remaining `PermanentError` subtypes MUST produce `400` — they are client
    faults. A refactor flipping this fallback to `500` would wrongly blame the
    server for client errors.
- An unhandled `TransientError` MUST produce a `503 Service Unavailable`
  response with `code = "internal_error"`.
- Any other `Throwable` MUST produce a `500 Internal Server Error` with
  `code = "internal_error"`.
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
- Enqueue work MUST run in a fire-and-forget coroutine off the request pipeline
  — application-scoped and IO-bound, never blocking the response.
- The plugin MUST NEVER propagate exceptions to the caller — all errors MUST be
  caught, logged at `error` level, and swallowed.
- On server shutdown, in-flight fire-and-forget coroutines are silently
  cancelled by Ktor. This is an accepted trade-off; the next request
  re-enqueues.
- Plugin installation MUST fail at startup if `sessionConfig` or `queueService`
  is unassigned — both are required configuration with no defaults.
- The token hash transmitted in the queue payload MUST be the SHA-256 hash of
  the raw cookie value, Base64-encoded (standard, not URL-safe), computed via
  `TokenHash.fromRawToken(token)`.
- The plugin MUST NOT perform any database reads or writes. Its only I/O side
  effect is a single call to `queueService.enqueue()`.

### RequestSizeLimit (`RequestSizeLimit.kt`)

- The server MUST enforce a request body size limit on every routed call via a
  single application-scope `install(RequestBodyLimit)` in
  `configureRequestSizeLimit()`. There MUST NOT be any per-route opt-in — a
  route added in the future is covered without per-route wiring.
- The applicable limit MUST be selected per request by a fixed resolution
  order: **exact-path override** (`routeOverrides`) → **longest matching path
  prefix** (`routePrefixOverrides`) → `defaultMax`. An exact match MUST win over
  any prefix match; among competing prefixes the longest matching key MUST win.
  Matching is slash- and case-sensitive. This guarantees a dynamic path such as
  `/api/v1/conversations/{id}/messages` resolves to its prefix's limit while an
  exact entry for a specific path still takes precedence.
- A request body exceeding the applicable limit (declared `Content-Length` or
  streamed bytes) MUST raise `PayloadTooLargeException`, mapped to `413` by
  StatusPages.
- An over-limit body MUST be rejected before `ContentNegotiation` runs, so it
  produces a `413` — never a Jackson `400`.

### ClientKeyGate (`ClientKeyGate.kt`)

- The gate MUST intercept `ApplicationCallPipeline.Plugins` — before routing —
  so it fronts every route. A request that does not carry a valid client key
  MUST be rejected with `403` and the pipeline MUST be `finish()`ed before any
  route handler runs.
- The gate MUST apply to ALL routes, including the public `auth/register` and
  `auth/login` routes. The ONLY exemption is an exact-match path allowlist (the
  `/healthz` health-check, so load-balancer probes pass). Matching MUST be exact,
  not prefix: `/healthzextra` MUST NOT be exempt.
- The gate MUST be independent of the session cookie: it MUST NOT read or depend
  on the session, and contributes no cookie or session side effect. The client
  key answers "is this one of my clients?"; the session answers "who is the
  user?". A protected route requires both.
- An empty `validKeys` set MUST disable the gate (fail open) — every request
  passes. This is the local/CI default.
- A rejected request MUST receive `403` with the shared
  `ErrorResponse(code = "forbidden", ...)`. A missing header and an invalid key
  MUST return the identical response — the gate MUST NOT reveal which condition
  failed.
- Key comparison MUST be constant-time and MUST NOT short-circuit on the first
  match, so neither which key matched nor how many keys were checked is
  observable through request timing.
- The header name (`X-Unicoach-Client-Key`) MUST be a compile-time constant, not
  a config value.

---

## III. Behavioral Contracts

### `configureSerialization()` ([Serialization.kt](./Serialization.kt))

- **Signature**: `fun Application.configureSerialization()`
- **Side Effects**: Installs the Ktor `ContentNegotiation` plugin with Jackson,
  with the `JavaTimeModule` registered and `WRITE_DATES_AS_TIMESTAMPS` disabled
  for ISO-8601 temporal output. No database writes, no network calls.
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

  | Exception                                                                                                                     | HTTP Status                                                                           | `ErrorResponse.code`       | `ErrorResponse.message`                           |
  | ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- | -------------------------- | ------------------------------------------------- |
  | `PayloadTooLargeException`                                                                                                    | 413                                                                                   | `"payload_too_large"`      | `"Request body exceeds the maximum allowed size"` |
  | `BadRequestException`                                                                                                         | 400                                                                                   | `"bad_request"`            | `"Invalid JSON payload structure"` (fixed)        |
  | `UnsupportedMediaTypeException`                                                                                               | 415                                                                                   | `"unsupported_media_type"` | `cause.message ?: "Unsupported media type"`       |
  | `PermanentError` (client fault)                                                                                               | 404 (`NotFoundException`) / 409 (`DuplicateEmailException`) / 400 (any other subtype) | `"permanent_error"`        | `cause.message ?: "Bad request"`                  |
  | `PermanentError` (server fault: `DatabaseException`, `CorruptPersistedValueException`, `CorruptPersistedAuthMethodException`) | 500                                                                                   | `"permanent_error"`        | `cause.message ?: "Bad request"`                  |
  | `TransientError`                                                                                                              | 503                                                                                   | `"internal_error"`         | `cause.message ?: "Internal server error"`        |

- **Dispatch**: `StatusPages` routes to the handler for the most specific
  superclass; the `PayloadTooLargeException`, `BadRequestException`, and
  `UnsupportedMediaTypeException` handlers take precedence over the
  `exception<Throwable>` catch-all.
- **Fallback**: any `Throwable` that is neither `PermanentError` nor
  `TransientError` produces a `500 Internal Server Error` with
  `code = "internal_error"`, message `"An internal error occurred"`.
- **Idempotency**: MUST be called exactly once at startup.

---

### `SessionExpiryPlugin` / `SessionExpiryPluginConfig` ([SessionExpiryPlugin.kt](./SessionExpiryPlugin.kt))

- **Type**: Ktor `ApplicationPlugin` created via `createApplicationPlugin`.
- **Plugin Name**: `"SessionExpiryPlugin"` (used by Ktor's internal registry).
- **Configuration** (`SessionExpiryPluginConfig`):

  | Field                | Type            | Required | Default      |
  | -------------------- | --------------- | -------- | ------------ |
  | `sessionConfig`      | `SessionConfig` | Yes      | `lateinit`   |
  | `queueService`       | `QueueService`  | Yes      | `lateinit`   |
  | `ignorePathPrefixes` | `Set<String>`   | No       | `emptySet()` |

- **Hook**: `on(ResponseSent)`

- **Execution Flow** (all guards evaluated in order; first failure
  short-circuits):

  1. Read cookie `cookieName` from request. If absent → return.
  2. Read `call.request.uri`. If any `ignorePathPrefixes` element is a prefix →
     return.
  3. Read `call.response.status()?.value`. If null or outside `200..299` →
     return.
  4. Launch `call.application.launch(Dispatchers.IO)`: a. Hash cookie:
     `TokenHash.fromRawToken(token)`. b. Base64-encode hash bytes (standard
     encoder). c. Build
     `SessionExpiryPayload(tokenHash = encodedHash).asJson()`. d. Call
     `queueService.enqueue(JobType.SESSION_EXTEND_EXPIRY, payload)`. e. On
     `EnqueueResult.DatabaseFailure` → log error. On `EnqueueResult.Success` →
     no-op. f. On any uncaught exception → log error and swallow.

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

### `configureRequestSizeLimit()` ([RequestSizeLimit.kt](./RequestSizeLimit.kt))

- **Signature**:
  `fun Application.configureRequestSizeLimit(config: RequestSizeConfig)`
- **Side Effects**: Installs the Ktor `RequestBodyLimit` plugin once at
  application scope with a path-aware `bodyLimit` callback. No database writes,
  no network calls.
- **Limit Selection**: delegated to `resolveLimit(config, path)`, which resolves
  in order exact override → longest matching prefix override → `defaultMax`.
- **Error Handling**: a body exceeding the applicable limit raises
  `io.ktor.server.plugins.PayloadTooLargeException`, mapped to `413` by
  `configureStatusPages()`.
- **Idempotency**: MUST be called exactly once at startup. A second call on the
  same `Application` results in a Ktor `DuplicatePluginException`.

---

### `configureClientKeyGate()` ([ClientKeyGate.kt](./ClientKeyGate.kt))

- **Signature**: `fun Application.configureClientKeyGate(config: ClientKeyGateConfig)`
- **Side Effects**: Installs one interceptor on `ApplicationCallPipeline.Plugins`.
  No database writes, no network calls. On rejection, writes a `403` response and
  `finish()`es the pipeline.
- **Per-call flow**: empty `validKeys` → proceed (disabled); exact-match path in
  `allowlistPaths` → proceed; otherwise the `X-Unicoach-Client-Key` header MUST
  match a configured key (constant-time, over the whole set) or the call is
  rejected `403` with `ErrorResponse(code = "forbidden")`.
- **Idempotency**: Installs once at startup; the per-request check is read-only
  and side-effect-free except for the rejection response.

---

## IV. Infrastructure & Environment

- **HOCON Key** (`rest-server.conf`): `sessionExpiry.ignorePathPrefixes` — a
  list of path prefix strings. Example: `["/health"]`. Read by `Application.kt`
  and passed to `SessionExpiryPluginConfig.ignorePathPrefixes`.
- **HOCON Keys** (`rest-server.conf`): `clientKeyGate.keys` — comma-separated
  valid client keys, overridden by the `UNICOACH_CLIENT_KEYS` env var (empty =
  disabled gate); `clientKeyGate.allowlistPaths` — string list of gate-exempt
  paths. Parsed by `ClientKeyGateConfig` and passed to `configureClientKeyGate`.
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

- [x] [RFC-08: Auth Registration](../../../../../../../../rfc/08-auth-registration.md)
      — introduced `Serialization.kt` and `StatusPages.kt`.
- [x] [RFC-09: Global Config](../../../../../../../../rfc/09-global-config.md) —
      introduced `JwtConfig.kt` (subsequently deleted).
- [x] [RFC-11: Sessions](../../../../../../../../rfc/11-sessions.md) — deleted
      `JwtConfig.kt` (replaced by `SessionConfig`-based auth).
- [x] [RFC-21: Session Expiry Queue](../../../../../../../../rfc/21-session-expiry-queue.md)
      — introduced `SessionExpiryPlugin.kt`.
- [x] [RFC-24: Result Types](../../../../../../../../rfc/24-result-types.md) —
      reworked `StatusPages.kt` exception mapping (`permanent_error` /
      `internal_error` codes, subtype-driven status).
- [x] [RFC-29: Request Payload Limits](../../../../../../../../rfc/29-request-payload-limits.md)
      — introduced `RequestSizeLimit.kt`; added the
      `413`/`PayloadTooLargeException` handler in `StatusPages.kt`.
- [x] [RFC-31: Student Profile](../../../../../../../../rfc/31-student-profile.md)
      — registered the Jackson `JavaTimeModule` and disabled
      `WRITE_DATES_AS_TIMESTAMPS` in `Serialization.kt` so `Instant` response
      fields serialize as ISO-8601 strings.
- [x] [RFC-40: Validation Error Reporting](../../../../../../../../rfc/40-validation-error-reporting.md)
      — mapped server-fault `PermanentError`s (`DatabaseException`,
      `CorruptPersistedValueException`, `CorruptPersistedAuthMethodException`)
      to `500` in `StatusPages.kt`; client-fault mappings unchanged.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
      — extended `RequestSizeLimit.kt` body-limit resolution to exact override →
      longest matching prefix (`RequestSizeConfig.routePrefixOverrides`) →
      `defaultMax`, so dynamic conversation paths receive the correct limit.
- [x] [RFC-54: Client-Key Gate](../../../../../../../../rfc/54-client-key-gate.md)
      — introduced `ClientKeyGate.kt`: a pre-routing interceptor rejecting
      requests without a valid client key (`403`), exempting an exact-match path
      allowlist.
