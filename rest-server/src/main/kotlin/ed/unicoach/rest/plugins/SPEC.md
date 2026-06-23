# SPEC: `rest-server/.../rest/plugins`

## I. Overview

Ktor application-level plugins and pipeline interceptors installed once at
server startup, applying cross-cutting concerns to every HTTP request/response
in the `rest-server`. Coverage:

1. **Serialization** — JSON codec configuration (Jackson): unknown-field and
   missing-field rejection, scalar type-punning rejection, and ISO-8601 temporal
   output.
2. **StatusPages** — Global exception/response-status to HTTP-status mapping
   onto the shared `ErrorResponse` JSON shape, with codes drawn from the
   `ed.unicoach.rest.models.ErrorCode` enum.
3. **SessionExpiryPlugin** — Post-response hook that asynchronously enqueues
   session-expiry-extension jobs.
4. **RequestSizeLimit** — Application-scope request body size enforcement,
   rejecting oversized bodies with `413`.
5. **ClientKeyGate** — A pre-routing interceptor rejecting any request lacking a
   valid client key with `403`, exempting only an exact-match path allowlist.
6. **EmailVerificationGate** — A pre-routing interceptor rejecting
   authenticated-but-unverified callers with `403` on non-exempt paths.

---

## II. Behavioral Contracts

All `ErrorResponse.code` values are members of the
`ed.unicoach.rest.models.ErrorCode` enum; the lowercase snake_case strings shown
below are that enum's `@JsonValue` wire forms (the single source of truth for
the wire code).

### `configureSerialization()` ([Serialization.kt](./Serialization.kt))

- **Signature**: `fun Application.configureSerialization()`
- **Behavior**: Installs Ktor `ContentNegotiation` with the Jackson codec. The
  mapper enables `FAIL_ON_UNKNOWN_PROPERTIES` (a JSON field absent from the
  target data class is rejected) and `FAIL_ON_MISSING_CREATOR_PROPERTIES` (an
  omitted required constructor field is rejected).
- **Scalar type-punning**: Two configurations together reject scalar coercion in
  both directions, so a mismatched scalar surfaces as a `400` rather than a
  silently coerced `2xx`:
  - `MapperFeature.ALLOW_COERCION_OF_SCALARS` is disabled — a JSON string for a
    numeric/boolean target (e.g. `UpdateStudentRequest.version: Int`) fails.
  - A `Textual` `CoercionConfig` fails the `Boolean`/`Integer`/`Float` input
    shapes — a JSON boolean/number for a `String` target (e.g.
    `RegisterRequest.name`) fails. Disabling `ALLOW_COERCION_OF_SCALARS` alone
    does not cover this direction.
- **Temporal output**: `SerializationFeature.INDENT_OUTPUT` is enabled; the
  `JavaTimeModule` is registered and `WRITE_DATES_AS_TIMESTAMPS` disabled, so
  `java.time.Instant` (and other `java.time` values) serialize as ISO-8601
  strings, not numeric epoch arrays.
- **Side Effects**: None — no DB writes, no network calls.
- **Error Handling**: A duplicate install throws a Ktor
  `DuplicatePluginException` at startup, not a runtime HTTP error.
- **Idempotency**: Installed once at startup. A second call on the same
  `Application` throws `DuplicatePluginException`.

---

### `configureStatusPages()` ([StatusPages.kt](./StatusPages.kt))

- **Signature**: `fun Application.configureStatusPages()`
- **Behavior**: Installs the Ktor `StatusPages` plugin. Every handled outcome
  responds with the shared `ed.unicoach.rest.models.ErrorResponse` JSON shape.
  Dispatch routes to the handler for the most specific superclass; the
  `PayloadTooLargeException` and `BadRequestException` handlers take precedence
  over the `exception<Throwable>` catch-all.
- **Side Effects**: None — no DB writes, no network calls.
- **Handled exceptions and statuses**:

  | Trigger                                                                  | HTTP Status | `ErrorResponse.code`  | `ErrorResponse.message`                                                                                                |
  | ------------------------------------------------------------------------ | ----------- | --------------------- | ---------------------------------------------------------------------------------------------------------------------- |
  | `PayloadTooLargeException`                                               | 413         | `"payload_too_large"` | `"Request body exceeds the maximum allowed size"`                                                                      |
  | `BadRequestException`                                                    | 400         | `"bad_request"`       | `"Invalid JSON payload structure"` (fixed; never derived from the cause, so Jackson internals do not reach the client) |
  | `PermanentError` — `NotFoundException`                                   | 404         | `"permanent_error"`   | `cause.message ?: "Bad request"`                                                                                       |
  | `PermanentError` — `DuplicateEmailException`                             | 409         | `"permanent_error"`   | `cause.message ?: "Bad request"`                                                                                       |
  | `PermanentError` — `DatabaseException`, `CorruptPersistedValueException` | 500         | `"permanent_error"`   | `cause.message ?: "Bad request"`                                                                                       |
  | `PermanentError` — any other subtype                                     | 400         | `"permanent_error"`   | `cause.message ?: "Bad request"`                                                                                       |
  | `TransientError`                                                         | 503         | `"internal_error"`    | `cause.message ?: "Internal server error"`                                                                             |
  | Any other `Throwable`                                                    | 500         | `"internal_error"`    | `"An internal error occurred"`                                                                                         |

  Server-fault `PermanentError` subtypes (database faults and persisted-state
  corruption — `DatabaseException`, `CorruptPersistedValueException`) map to
  `500`; persisted-state corruption is presented as a server fault, not a client
  error. The remaining `PermanentError` subtypes map to `400` as client faults.
- **Unreadable body (status handler)**: A
  `status(HttpStatusCode.UnsupportedMediaType)` handler intercepts the `415`
  **response status** Ktor raises for an unreadable body (a JSON `null`, an
  unparseable payload, or a non-`application/json` content type, surfaced as
  `CannotTransformContentToTypeException`, which no `exception<>` handler
  intercepts). It responds `400` with
  `ErrorResponse(code = "bad_request", message = "Request body could not be read as the expected application/json payload")`,
  replacing the opaque `415` `text/plain` body. Responding `400` here does not
  recurse — there is no `status(400)` handler.
- **Idempotency**: Installed once at startup; a second call throws
  `DuplicatePluginException`.

---

### `SessionExpiryPlugin` / `SessionExpiryPluginConfig` ([SessionExpiryPlugin.kt](./SessionExpiryPlugin.kt))

- **Type**: Ktor `ApplicationPlugin` via `createApplicationPlugin`, registered
  under the name `"SessionExpiryPlugin"`.
- **Configuration** (`SessionExpiryPluginConfig`): `sessionConfig` and
  `queueService` are `lateinit` (required, no default; an unassigned value fails
  at startup). `ignorePathPrefixes` defaults to `emptySet()`.
- **Hook**: `on(ResponseSent)` — fires after the response is fully delivered to
  the client.
- **Per-call flow** (guards in order; first failure returns without enqueue):
  1. Read the cookie named `sessionConfig.cookieName`. Absent → return.
  2. Read `call.request.uri`. If any `ignorePathPrefixes` element is a prefix →
     return.
  3. Read `call.response.status()?.value`. Null or outside `200..299` → return.
  4. Launch a fire-and-forget coroutine on `call.application` /
     `Dispatchers.IO`: SHA-256-hash the raw cookie value via
     `TokenHash.fromRawToken(token)`, Base64-encode the hash with the standard
     (non-URL-safe) encoder, build
     `SessionExpiryPayload(tokenHash = ...).asJson()`, and call
     `queueService.enqueue(JobType.SESSION_EXTEND_EXPIRY, payload)`.
- **Side Effects**: One write to the job queue per qualifying request, performed
  asynchronously off the request pipeline. No DB reads.
- **Error Handling**: An `EnqueueResult.DatabaseFailure` and any uncaught
  exception in the coroutine are logged at `error` level (logger
  `"SessionExpiryPlugin"`) and swallowed; nothing propagates to the caller. On
  server shutdown Ktor cancels in-flight coroutines, silently dropping them; the
  next request after restart re-enqueues.
- **Idempotency**: Not idempotent per request — multiple requests with the same
  session cookie enqueue multiple jobs. Deduplication is handled downstream by
  the session-expiry handler.

---

### `configureRequestSizeLimit()` ([RequestSizeLimit.kt](./RequestSizeLimit.kt))

- **Signature**:
  `fun Application.configureRequestSizeLimit(config: RequestSizeConfig)`
- **Behavior**: Installs the Ktor `RequestBodyLimit` plugin once at application
  scope with a path-aware `bodyLimit` callback, so every routed call is covered
  with no per-route opt-in. The limit is selected by
  `resolveLimit(config, path)` in fixed order: exact-path override
  (`routeOverrides`) → longest matching path prefix (`routePrefixOverrides`) →
  `defaultMax`. An exact match wins over any prefix; among prefixes the longest
  matching key wins; matching is slash- and case-sensitive. A dynamic path such
  as `/api/v1/conversations/{id}/messages` resolves to its prefix's limit while
  an exact entry still takes precedence.
- **Side Effects**: None — no DB writes, no network calls.
- **Error Handling**: A body exceeding the applicable limit (declared
  `Content-Length` or streamed bytes) raises
  `io.ktor.server.plugins.PayloadTooLargeException`, mapped to `413` by
  `configureStatusPages()`. The over-limit body is rejected before
  `ContentNegotiation` runs, so it yields a `413` rather than a Jackson `400`.
- **Idempotency**: Installed once at startup; a second call throws
  `DuplicatePluginException`.

---

### `configureClientKeyGate()` ([ClientKeyGate.kt](./ClientKeyGate.kt))

- **Signature**:
  `fun Application.configureClientKeyGate(config: ClientKeyGateConfig)`
- **Behavior**: Installs one interceptor on `ApplicationCallPipeline.Plugins` —
  before routing — fronting every route, including the public `auth/register`
  and `auth/login` routes. Per-call flow:
  - `validKeys` empty → proceed (gate disabled / fail-open; the local/CI
    default).
  - `call.request.path()` an exact member of `allowlistPaths` → proceed.
    Matching is exact, not prefix (`/healthzextra` is not exempt).
  - Otherwise the `X-Unicoach-Client-Key` header (a compile-time constant
    `CLIENT_KEY_HEADER`, not config) is compared against the configured set. A
    missing header and an invalid key yield the identical response, so the gate
    does not reveal which condition failed. Comparison is constant-time and
    folds over the entire key set with boolean-OR accumulation (no first-match
    short-circuit), keeping which key matched and how many were checked
    unobservable through timing.
- **Side Effects**: On rejection, responds `403` with
  `ErrorResponse(code = "forbidden", message = "Valid client key required.")`
  and `finish()`es the pipeline before any route handler runs. Otherwise
  read-only — it does not read or mutate the session cookie and contributes no
  session side effect.
- **Idempotency**: Installed once at startup; the per-request check is
  side-effect-free except for the rejection response.

---

### `configureEmailVerificationGate()` ([EmailVerificationGate.kt](./EmailVerificationGate.kt))

- **Signature**:
  `fun Application.configureEmailVerificationGate(authService: AuthService, sessionConfig: SessionConfig)`
- **Behavior**: Installs one interceptor on `ApplicationCallPipeline.Plugins` —
  before routing. Registered after `configureClientKeyGate`, so the coarse
  client-key check runs ahead of this finer verification check. Per-call flow:
  1. **Exempt paths** pass unconditionally: `call.request.path()` equal to
     `/healthz`, or starting with `/api/v1/auth/`. The `/healthz` match is
     exact; the auth match is a `/api/v1/auth/` prefix. This keeps the health
     probe and the entire verification lifecycle reachable while unverified.
     Every other path is gated.
  2. **No resolved caller** → proceed.
     `call.resolveCaller(authService, sessionConfig)` returns `null` for a
     missing session cookie or a null session resolution; in that case the gate
     passes the request through so the downstream handler applies its own auth
     check and emits its own `401`. The gate never converts an unauthenticated
     request into a `403`.
  3. **Authenticated but unverified** (resolved caller whose
     `user.emailVerifiedAt` is null) → respond `403 Forbidden` with
     `ErrorResponse(code = "email_not_verified", message = "Email verification required.")`
     and `finish()` the pipeline. A verified caller falls through; its resolved
     caller is already cached on `call.attributes` for the handler.
- **Side Effects**: Reads identity via `resolveCaller`, which performs one
  `sessions`+`users` lookup through `authService.resolveSession` and caches the
  `ResolvedCaller` on `call.attributes` (shared with the downstream handler), so
  the handler does not repeat the lookup. On rejection, writes the `403`
  response. No writes of its own.
- **Error Handling**: A DB fault inside `resolveCaller` propagates (via
  `getOrThrow`) to `configureStatusPages()`, which maps it to `500`. Email
  verification state is asserted only after identity resolves.
- **Idempotency**: Installed once at startup; the per-request check is
  side-effect-free except for the rejection response.

---

## III. Infrastructure & Environment

- **HOCON key** (`rest-server.conf`): `sessionExpiry.ignorePathPrefixes` — a
  list of path-prefix strings (e.g. `["/health"]`), read by `Application.kt` and
  passed to `SessionExpiryPluginConfig.ignorePathPrefixes`.
- **HOCON keys** (`rest-server.conf`): `clientKeyGate.keys` — comma-separated
  valid client keys, overridden by the `UNICOACH_CLIENT_KEYS` env var (empty =
  disabled gate); `clientKeyGate.allowlistPaths` — string list of gate-exempt
  paths. Parsed by `ClientKeyGateConfig` and passed to `configureClientKeyGate`.
- **Gradle plugin** (`rest-server/build.gradle.kts`): `SessionExpiryPlugin`
  calls `SessionExpiryPayload(...).asJson()`, requiring
  `alias(libs.plugins.kotlin.serialization)` at the `rest-server` call site so
  the kotlinx-serialization compiler generates the `SessionExpiryPayload`
  serializer.
- **Queue module dependency**: `SessionExpiryPayload` lives in the `queue`
  module (not `net`), so both `rest-server` (enqueuer) and `net` (handler)
  import it without a cross-dependency.
- **Logger name**: `"SessionExpiryPlugin"` — used for all error-level logging
  from the plugin.

---

## IV. History

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
      `CorruptPersistedValueException`) to `500` in `StatusPages.kt`;
      client-fault mappings unchanged.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../../rfc/45-coaching-service.md)
      — extended `RequestSizeLimit.kt` body-limit resolution to exact override →
      longest matching prefix (`RequestSizeConfig.routePrefixOverrides`) →
      `defaultMax`, so dynamic conversation paths receive the correct limit.
- [x] [RFC-52: Make the REST Surface Fuzz-Clean](../../../../../../../../rfc/52-make-rest-surface-fuzz-clean.md)
      — `Serialization.kt` rejects scalar type-punning (disabled
      `ALLOW_COERCION_OF_SCALARS` + `Textual` `CoercionConfig`);
      `StatusPages.kt` replaced the dead
      `exception<UnsupportedMediaTypeException>` handler with a
      `status(UnsupportedMediaType)` handler mapping an unreadable body to a
      `400` JSON `bad_request` instead of a `415` `text/plain`.
- [x] [RFC-54: Client-Key Gate](../../../../../../../../rfc/54-client-key-gate.md)
      — introduced `ClientKeyGate.kt`: a pre-routing interceptor rejecting
      requests without a valid client key (`403`), exempting an exact-match path
      allowlist.
- [x] [RFC-64: Google SSO Login](../../../../../../../../rfc/64-google-sso-login.md)
      — removed the deleted `CorruptPersistedAuthMethodException` from
      `StatusPages.kt`; server-fault `PermanentError`s mapping to `500` is now
      `DatabaseException` and `CorruptPersistedValueException`.
- [x] [RFC-69: Email-Verification Gate + Error-Code Unification](../../../../../../../../rfc/69-email-verification-gate.md)
      — introduced `EmailVerificationGate.kt`: a pre-routing interceptor
      rejecting authenticated-but-unverified callers with
      `403 email_not_verified` on non-exempt paths (exempt: `/healthz`,
      `/api/v1/auth/*`). Switched `StatusPages.kt` and `ClientKeyGate.kt` from
      string literals to the typed `ed.unicoach.rest.models.ErrorCode` enum for
      error-code unification.
