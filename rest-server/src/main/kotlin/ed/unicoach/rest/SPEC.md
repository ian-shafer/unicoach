# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest`

## I. Overview

The HTTP presentation layer of the unicoach platform. It hosts the Ktor/Netty
embedded server, wires all application plugins, and exposes the REST API surface
under `/api/v1`. It translates between domain results (from `service` and `db`)
and HTTP responses, managing session cookie lifecycle and asynchronous session
expiry enqueueing. It does not contain domain logic.

---

## II. Invariants

### Server Lifecycle

- The server MUST start non-blocking (`server.start(wait = false)`) and
  synchronously wait for Netty to bind before returning from `startServer()`.
- `database.close()` MUST be called via the `ApplicationStopped` lifecycle event
  to release the HikariCP connection pool on shutdown.
- `SessionExpiryPlugin` MUST be installed in the `embeddedServer` lambda, NOT
  inside `appModule()`, so that integration tests calling `appModule()` directly
  do not activate the plugin.

### Configuration

- `startServer()` MUST load configs in this exact order:
  `"common.conf", "db.conf", "service.conf", "rest-server.conf", "queue.conf"`.
- `SessionConfig.from(config)` MUST fail-fast (`getOrThrow()`) at startup if the
  `session` HOCON block is absent or misconfigured.
- `SessionConfig` MUST extract exactly four fields from the `session` block:
  `expiration` (Duration), `cookieName` (String), `cookieDomain` (String),
  `cookieSecure` (Boolean).

### Serialization

- All JSON serialization MUST use Jackson with `INDENT_OUTPUT` enabled and
  `FAIL_ON_UNKNOWN_PROPERTIES = true` and `FAIL_ON_MISSING_CREATOR_PROPERTIES =
  true`. Unknown or missing fields in request bodies MUST trigger a
  `400 Bad Request`.

### Routing

- All API routes MUST be nested under `/api/v1`.
- Route blocks declared with `route("...")` that permit only specific methods MUST
  call `rejectUnsupportedMethods(...)` to return `405 Method Not Allowed` with an
  `Allow` header. Leaf handlers declared with `post("...")` or `get("...")` do
  NOT apply `rejectUnsupportedMethods` — Ktor returns a method-not-allowed
  response natively for those.
- `/api/v1/auth/me` and `/api/v1/auth/logout` use `route()` blocks and MUST
  call `rejectUnsupportedMethods`. `/api/v1/auth/register` uses `post()` and
  does NOT.
- The `/hello` endpoint MUST NOT be removed — it is the health probe target for
  `rest-server-check`.

### Session Cookie

- The session cookie MUST be set with: `HttpOnly = true`, `SameSite = Strict`,
  `path = "/"`, and the `secure` flag driven by `sessionConfig.cookieSecure`.
- The cookie MUST NOT contain the raw token hash — it carries the opaque raw
  token string only.
- Cookie-clearing on logout MUST use `maxAge = 0L` with domain, path, secure,
  HttpOnly, and SameSite attributes **identical** to those used when setting the
  cookie. A mismatch leaves the original cookie intact in the browser.

### Session Expiry Plugin

- `SessionExpiryPlugin` MUST fire on `ResponseSent` only when all three
  conditions hold: a session cookie is present, the request path does NOT match
  any prefix in `ignorePathPrefixes`, and the response status is in `200–299`.
- The plugin MUST enqueue `JobType.SESSION_EXTEND_EXPIRY` fire-and-forget on
  `Dispatchers.IO`. Enqueue failures MUST be logged at `error` level and MUST
  NOT affect the response already sent.
- Coroutine cancellation on server shutdown is silent and acceptable — the next
  request re-enqueues.
- The `ignorePathPrefixes` set MUST include at least `"/health"` and
  `"/api/v1/auth/logout"` (configured via `rest-server.conf`
  `sessionExpiry.ignorePathPrefixes`).

### Payload Limits

- `POST /api/v1/auth/register` MUST reject requests whose `Content-Length`
  header exceeds 4096 bytes with `413 Payload Too Large` before body
  deserialization.

### Error Mapping

- `UnsupportedMediaTypeException` MUST map to `415 Unsupported Media Type` with
  `ErrorResponse(code = "unsupported_media_type", ...)`.
- `BadRequestException` MUST map to `400 Bad Request` with
  `ErrorResponse(code = "bad_request", message = "Invalid JSON payload structure")`.
- All `AuthResult.DatabaseFailure` conditions MUST map to `500 Internal Server
  Error` and MUST NOT expose internal exception details in the response body.

### Token Hashing

- Raw session tokens MUST be hashed via `TokenHash.fromRawToken(token)` (SHA-256
  via `java.security.MessageDigest`) before any DAO or service call. The plain
  token is NEVER stored or forwarded to the domain layer.

---

## III. Behavioral Contracts

### `startServer(wait: Boolean): EmbeddedServer<*, *>` — [`Application.kt`](./Application.kt)

- **Side effects**: Opens HikariCP pool (`Database`), starts Netty listener,
  subscribes `ApplicationStopped` hook to close the database.
- **Error handling**: Any `getOrThrow()` on config parsing propagates as an
  uncaught exception, crashing the process with a meaningful message before the
  server binds.
- **Idempotency**: Not idempotent — calling twice binds two server instances.

### `Application.appModule(database, sessionConfig)` — [`Application.kt`](./Application.kt)

- **Side effects**: Installs `ContentNegotiation` (Jackson), `StatusPages`,
  builds `AuthService`, and registers all routes.
- **Scope**: Intentionally excludes `SessionExpiryPlugin` installation. Tests
  calling `appModule()` directly bypass the queue-write side effect.
- **Idempotency**: Not idempotent — Ktor plugin installation is not
  idempotent if called twice on the same Application.

### `Application.configureRouting(...)` — [`Routing.kt`](./Routing.kt)

- **Routes registered**:
  - `GET /hello` → `200 OK` with plain UTF-8 text body.
  - All `/api/v1/auth/*` routes via `authRoutes(...)`.
- **Side effects**: Route table registration — installs handlers into the
  Ktor routing tree.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

### `Route.rejectUnsupportedMethods(vararg methods)` — [`Routing.kt`](./Routing.kt)

- **Behavior**: Installs a catch-all `handle {}` block into the routing DSL
  that responds `405 Method Not Allowed` and appends an `Allow` header listing
  the permitted method values.
- **Side effects**: Registration-time side effect — adds a handler to the
  route. Must be called exactly once per route block.
- **Idempotency**: Not idempotent — calling twice on the same route installs
  a duplicate handler.

### `POST /api/v1/auth/register` — [`routing/AuthRoutes.kt`](./routing/AuthRoutes.kt)

- **Request**: JSON body `{"email": string, "password": string, "name": string}`.
  Deserialized as `RegisterRequest`.
- **Side effects**:
  - Calls `AuthService.register()` — writes `users` row in DB.
  - Calls `SessionsDao.remintToken()` (if anonymous session cookie present) OR
    `SessionsDao.create()` — writes `sessions` row in DB.
  - Sets `Set-Cookie` response header with opaque session token.
- **Response mapping**:
  - `AuthResult.Success` → `201 Created`, `RegisterResponse { user: PublicUser }`.
  - `AuthResult.ValidationFailure` → `400 Bad Request`, `ErrorResponse(code="validation_failed", fieldErrors=[...])`.
  - `AuthResult.DuplicateEmail` → `409 Conflict`, `ErrorResponse(code="conflict", fieldErrors=[{field="email"}])`.
  - `AuthResult.DatabaseFailure` → `500 Internal Server Error`.
  - `Content-Length > 4096` → `413 Payload Too Large` before body read.
- **Session reminting**: If a session cookie is present, the handler calls
  `SessionsDao.findByTokenHash()` to look up the existing session. If the
  result is `SessionFindResult.Success`, the session is reminted via
  `SessionsDao.remintToken()` (token rotated, `user_id` assigned). Only if no
  valid session is found is a new session created via `SessionsDao.create()`.
  DB exceptions during session writes are silently swallowed — the registration
  response is sent regardless.
- **Idempotency**: Not idempotent — duplicate email returns `409`.

### `GET /api/v1/auth/me` — [`routing/AuthRoutes.kt`](./routing/AuthRoutes.kt)

- **Request**: No body. Session identity derived from cookie.
- **Side effects**: Calls `AuthService.getCurrentUser()` — DB read only.
- **Response mapping**:
  - Cookie absent → `401 Unauthorized`, `ErrorResponse(code="unauthorized")`.
  - `MeResult.Authenticated` → `200 OK`, `MeResponse { user: PublicUser }`.
  - `MeResult.Unauthenticated` → `401 Unauthorized`.
  - `MeResult.DatabaseFailure` → `500 Internal Server Error`.
- **Method restriction**: Non-GET methods → `405 Method Not Allowed`.
- **Idempotency**: Yes — read-only.

### `POST /api/v1/auth/logout` — [`routing/AuthRoutes.kt`](./routing/AuthRoutes.kt)

- **Request**: No body. Session identity derived from cookie.
- **Side effects**: Calls `AuthService.logout()` — writes `is_revoked = true` on
  the `sessions` row. Always clears the session cookie on success.
- **Response mapping**:
  - Cookie absent → `204 No Content`, cookie cleared. No DB call.
  - `LogoutResult.Success` → `204 No Content`, cookie cleared.
  - `LogoutResult.DatabaseFailure` → `500 Internal Server Error`. Cookie NOT
    cleared.
- **Method restriction**: Non-POST methods → `405 Method Not Allowed`.
- **Idempotency**: Yes — logout of an already-revoked or missing session returns
  `204`.

### `SessionExpiryPlugin` — [`plugins/SessionExpiryPlugin.kt`](./plugins/SessionExpiryPlugin.kt)

- **Trigger**: `ResponseSent` hook (post-response, non-blocking).
- **Conditions to enqueue** (all must hold):
  1. Session cookie present in request.
  2. Request path does NOT start with any prefix in `ignorePathPrefixes`.
  3. Response status in `200–299`.
- **Side effects**: Enqueues `SESSION_EXTEND_EXPIRY` job via `QueueService` —
  DB write to `jobs` table. Enqueue is fire-and-forget on `Dispatchers.IO`.
- **Error handling**: `EnqueueResult.DatabaseFailure` → `error`-level log, no
  exception propagation. All exceptions in the coroutine body → `error`-level
  log, swallowed.
- **Idempotency**: Not idempotent per request — enqueues once per eligible
  response. The handler is idempotent via OCC versioning.

### `Application.configureSerialization()` — [`plugins/Serialization.kt`](./plugins/Serialization.kt)

- **Side effects**: Installs Jackson `ContentNegotiation` plugin.
- `FAIL_ON_UNKNOWN_PROPERTIES = true` causes `BadRequestException` on unknown
  JSON fields, handled by `StatusPages`.
- `FAIL_ON_MISSING_CREATOR_PROPERTIES = true` rejects partial request bodies.

### `Application.configureStatusPages()` — [`plugins/StatusPages.kt`](./plugins/StatusPages.kt)

- **Handles**:
  - `UnsupportedMediaTypeException` → `415`.
  - `BadRequestException` → `400`, message fixed as `"Invalid JSON payload structure"`.
- **Side effects**: None beyond response write.

### `SessionConfig.from(config): Result<SessionConfig>` — [`auth/SessionConfig.kt`](./auth/SessionConfig.kt)

- **Side effects**: None — pure config parsing.
- **Error handling**: Missing `session` block → `Result.failure(IllegalArgumentException(...))`. Any
  missing key → `Result.failure(exception)`.
- **Idempotency**: Yes — pure function.

### `ApplicationCall.respondAppError(error, status)` — [`routing/AuthRoutes.kt`](./routing/AuthRoutes.kt)

- **Behavior**: Maps `AuthResult` subtypes to structured `ErrorResponse` JSON.
- **Side effects**: Writes HTTP response.
- Unrecognized `AuthResult` variants fall through to `500 Internal Server Error`
  with `code = "unknown_error"`.

---

## III-B. Data Model Contracts

All types are plain Kotlin data classes serialized via Jackson. Because
`FAIL_ON_MISSING_CREATOR_PROPERTIES = true`, every non-optional field MUST be
present in the JSON request body or deserialization throws `BadRequestException`
(→ `400`).

### `PublicUser` — [`models/PublicUser.kt`](./models/PublicUser.kt)

| Field | JSON key | Type | Notes |
|-------|----------|------|-------|
| `id` | `"id"` | UUID | Serializes as lowercase hyphenated string |
| `email` | `"email"` | String | Validated email address |
| `name` | `"name"` | String | Display name |

Included in: `RegisterResponse.user`, `MeResponse.user`.

### `RegisterRequest` — [`models/RegisterRequest.kt`](./models/RegisterRequest.kt)

| Field | Type | Required |
|-------|------|----------|
| `email` | String | Yes |
| `password` | String | Yes |
| `name` | String | Yes |

All fields are required. Missing any field → `400 Bad Request`.

### `RegisterResponse` — [`models/RegisterResponse.kt`](./models/RegisterResponse.kt)

- `user: PublicUser` — the newly registered user's public fields.

### `MeResponse` — [`models/MeResponse.kt`](./models/MeResponse.kt)

- `user: PublicUser` — the authenticated user's public fields.

### `ErrorResponse` — [`models/ErrorResponse.kt`](./models/ErrorResponse.kt)

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `code` | String | Yes | Machine-readable error code |
| `message` | String | Yes | Human-readable description |
| `fieldErrors` | List\<FieldError\>? | No | Present only for validation failures |

`FieldError` is sourced from `ed.unicoach.error.FieldError` (common module), not
defined in this package.

---

## IV. Infrastructure & Environment

### HOCON Configuration (module: `rest-server`)

Required keys in `rest-server.conf`:

| Key | Type | Description |
|-----|------|-------------|
| `server.host` | String | Netty bind host |
| `server.port` | Int | Netty bind port |
| `session.expiration` | Duration | Session TTL |
| `session.cookieName` | String | Cookie name |
| `session.cookieDomain` | String | Cookie domain attribute |
| `session.cookieSecure` | Boolean | `Secure` cookie flag |
| `sessionExpiry.ignorePathPrefixes` | List\<String\> | Paths excluded from expiry enqueue |

### Config Load Order

`AppConfig.load("common.conf", "db.conf", "service.conf", "rest-server.conf", "queue.conf")`

- `net.conf` is NOT loaded by `rest-server` — only `queue-worker` loads it.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- **HikariCP** (via `Database`): JDBC connection pool.
- **Kotlinx Serialization** (compiler plugin): Required at the `rest-server`
  call site for `SessionExpiryPayload(...).asJson()`.
- **Jackson** (via `ContentNegotiation`): JSON serialization.
- **SLF4J**: Logging backend for `SessionExpiryPlugin`.

### Module Dependencies

`rest-server` depends on: `common`, `db`, `service`, `queue`.
It does NOT depend on `net`.

---

## V. History

- [x] [RFC-02: Hello World / OpenAPI Spec](../../../../../../../rfc/02-hello-world-open-api-spec.md)
- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md)
- [x] [RFC-09: Global Config](../../../../../../../rfc/09-global-config.md)
- [x] [RFC-11: Sessions](../../../../../../../rfc/11-sessions.md)
- [x] [RFC-13: Auth Me](../../../../../../../rfc/13-auth-me.md)
- [x] [RFC-14: DB Module](../../../../../../../rfc/14-db-module.md)
- [x] [RFC-15: Queue Data Layer](../../../../../../../rfc/15-queue-data-layer.md)
- [x] [RFC-19: Daemon Health Marker](../../../../../../../rfc/19-daemon-health-marker.md)
- [x] [RFC-21: Session Expiry Queue](../../../../../../../rfc/21-session-expiry-queue.md)
- [x] [RFC-22: Auth Logout](../../../../../../../rfc/22-auth-logout.md)
- [x] [RFC-23: Native Daemon Scripts](../../../../../../../rfc/23-native-daemon-scripts.md)
