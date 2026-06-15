# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest`

## I. Overview

The HTTP presentation layer of the unicoach platform. It hosts the Ktor/Netty
embedded server, wires all application plugins, and exposes the REST API surface
under `/api/v1`. It owns construction of the application's services
(`AuthService`, `StudentService`, `CoachingService`) and injects them into the
route handlers, including fail-fast boot-time construction of the chat provider
the coaching service depends on. It translates between domain results (from
`service` and `db`) and HTTP responses,
managing session cookie lifecycle and asynchronous session expiry enqueueing. It
does not contain domain logic.

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
  `"common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf"`.
  `chat.conf` (resource supplied by the `:chat` dependency) MUST be in the load
  list so that `chat.provider` is surfaced to `ChatConfig.from(config)`.
- `SessionConfig.from(config)` MUST fail-fast (`getOrThrow()`) at startup if the
  `session` HOCON block is absent or misconfigured.
- The chat provider and coaching config MUST be constructed at boot, fail-fast:
  `ChatProviderFactory.fromConfig(ChatConfig.from(config))` and
  `CoachingConfig.from(config)` are each resolved with `getOrThrow()` before the
  server binds, so a missing/invalid `chat.provider` or coaching config crashes
  the process rather than failing on the first conversation request.
- `SessionConfig` MUST extract exactly four fields from the `session` block:
  `expiration` (Duration), `cookieName` (String), `cookieDomain` (String),
  `cookieSecure` (Boolean).

### Serialization

- All JSON serialization MUST use Jackson with `INDENT_OUTPUT` enabled and
  `FAIL_ON_UNKNOWN_PROPERTIES = true` and
  `FAIL_ON_MISSING_CREATOR_PROPERTIES = true`. Unknown or missing fields in
  request bodies MUST trigger a `400 Bad Request`.
- `java.time` values MUST serialize as ISO-8601 strings (`JavaTimeModule`
  registered, `WRITE_DATES_AS_TIMESTAMPS` disabled), NEVER as numeric epoch
  arrays.

### Routing

- All API routes MUST be nested under `/api/v1`.
- The `/api/v1/auth/*`, `/api/v1/students/*`, and `/api/v1/conversations/*`
  route groups MUST each be registered by their own route handler
  (`AuthRouteHandler.registerRoutes`, `StudentRouteHandler.registerRoutes`,
  `ConvoRouteHandler.registerRoutes`). This directory owns only the wiring and
  service construction; the per-route HTTP contracts (methods, status codes,
  request/response DTOs, `rejectUnsupportedMethods` placement) live in
  [`routing/SPEC.md`](./routing/SPEC.md).
- `rejectUnsupportedMethods(...)`, defined in this directory, MUST return
  `405 Method Not Allowed` with an `Allow` header listing the permitted methods.

### Liveness

- The server MUST expose an **unauthenticated, unversioned** liveness endpoint
  at `/healthz` (NOT under `/api/v1`), registered in the top-level `routing { }`
  block so no per-route-group auth wraps it. It MUST return `200` whenever the
  process is up and serving HTTP.
- The `/healthz` handler MUST NOT depend on the database, queue, chat provider,
  or any backing service — it returns its response unconditionally. A transient
  dependency failure MUST NEVER turn the liveness probe non-200, so a
  health-check consumer can never kill an otherwise-serving process over a
  dependency blip.
- `/healthz` MUST remain the sole client-key-gate allowlist entry — an AWS ALB /
  load-balancer health probe carries no client key, so the gate MUST NEVER
  reject it with `403`.

### Client-Key Gate

- The client-key gate MUST be installed in `appModule` after
  `configureSerialization` and before `configureRouting`, so it fronts every
  registered route (a future route inherits it with no extra wiring) and its
  `403` body serializes through the installed `ContentNegotiation`. The gate's
  request-time behavior is owned by [`plugins/SPEC.md`](./plugins/SPEC.md).
- `ClientKeyGateConfig.from(config)` MUST be resolved fail-fast (`getOrThrow()`)
  at boot, so a malformed `clientKeyGate` block crashes startup rather than
  failing on the first request.

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
  `sessionExpiry.ignorePathPrefixes`). The `"/health"` prefix transitively
  covers `/healthz` (`"/healthz".startsWith("/health")`), so the liveness route
  needs no dedicated entry.

### Payload Limits

- A single application-scope request-body-size limit MUST govern every route.
  There MUST NOT be any per-route opt-in a handler could omit; a route added in
  future MUST inherit the limit with no additional wiring.
- The configured default (`server.requestSize.maxSize`) MUST apply to any path
  with no `routeOverrides` entry. A `routeOverrides` entry, matched by exact
  request path, MUST be the only way a path deviates from the default.
- A request whose body exceeds the applicable limit MUST be rejected with
  `413 Payload Too Large` before content negotiation runs, so an oversized body
  never reaches the JSON converter.

### Error Mapping

- `UnsupportedMediaTypeException` MUST map to `415 Unsupported Media Type` with
  `ErrorResponse(code = "unsupported_media_type", ...)`.
- `PayloadTooLargeException` MUST map to `413 Payload Too Large` with
  `ErrorResponse(code = "payload_too_large", ...)`. This handler MUST take
  precedence over the `Throwable` catch-all (which would otherwise return
  `500`).
- `BadRequestException` MUST map to `400 Bad Request` with
  `ErrorResponse(code = "bad_request", message = "Invalid JSON payload structure")`.
- Uncaught `PermanentError` MUST map to `400 Bad Request` (code
  `permanent_error`), except for `NotFoundException` (`404`) and
  `DuplicateEmailException` (`409`).
- Uncaught `TransientError` MUST map to `503 Service Unavailable` (code
  `internal_error`) and MUST NOT expose internal exception details.

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

### `Application.appModule(database, sessionConfig, requestSizeConfig, chatProvider, coachingConfig, clientKeyGateConfig)` — [`Application.kt`](./Application.kt)

- **Side effects**: Installs `ContentNegotiation` (Jackson), the client-key gate
  via `configureClientKeyGate(clientKeyGateConfig)` (after serialization, before
  routing), `StatusPages`, and the application-scope request-body-size limit via
  `configureRequestSizeLimit(requestSizeConfig)`; builds `AuthService`,
  `StudentService`, and `CoachingService(database, chatProvider, coachingConfig)`,
  and registers all routes via `configureRouting`.
- **Inputs**: The pre-constructed `chatProvider` and `coachingConfig` are passed
  in (built fail-fast by `startServer`), so `appModule` itself performs no chat
  config parsing.
- **Scope**: Intentionally excludes `SessionExpiryPlugin` installation. Tests
  calling `appModule()` directly bypass the queue-write side effect.
- **Idempotency**: Not idempotent — Ktor plugin installation is not idempotent
  if called twice on the same Application.

### `Application.configureRouting(authService, studentService, coachingService, sessionConfig)` — [`Routing.kt`](./Routing.kt)

- **Routes registered**:
  - `GET /healthz` → `200 OK`, `Content-Type: application/json`, constant body;
    unauthenticated and dependency-free. Non-GET → `405` with `Allow: GET` via
    `rejectUnsupportedMethods(HttpMethod.Get)`.
  - All `/api/v1/auth/*` routes via `AuthRouteHandler.registerRoutes(...)`.
  - All `/api/v1/students/*` routes via
    `StudentRouteHandler.registerRoutes(...)`.
  - All `/api/v1/conversations/*` routes via
    `ConvoRouteHandler.registerRoutes(...)`.
- **Side effects**: Route table registration — installs handlers into the Ktor
  routing tree.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

### `Route.rejectUnsupportedMethods(vararg methods)` — [`Routing.kt`](./Routing.kt)

- **Behavior**: Installs a catch-all `handle {}` block into the routing DSL that
  responds `405 Method Not Allowed` and appends an `Allow` header listing the
  permitted method values.
- **Side effects**: Registration-time side effect — adds a handler to the route.
  Must be called exactly once per route block.
- **Idempotency**: Not idempotent — calling twice on the same route installs a
  duplicate handler.

> The per-route HTTP contracts for `/api/v1/auth/*` and `/api/v1/students/*`
> (request/response DTOs, status codes, cookie lifecycle, OCC behavior,
> cascade-delete cookie clearing) are owned by
> [`routing/SPEC.md`](./routing/SPEC.md). The DTO shapes are owned by
> [`models/SPEC.md`](./models/SPEC.md). This directory documents only the
> wiring, plugins, and config it constructs.

### `SessionExpiryPlugin` — [`plugins/SessionExpiryPlugin.kt`](./plugins/SessionExpiryPlugin.kt)

- **Trigger**: `ResponseSent` hook (post-response, non-blocking).
- **Conditions to enqueue** (all must hold):
  1. Session cookie present in request.
  2. Request path does NOT start with any prefix in `ignorePathPrefixes`.
  3. Response status in `200–299`.
- **Side effects**: Enqueues `SESSION_EXTEND_EXPIRY` job via `QueueService` — DB
  write to `jobs` table. Enqueue is fire-and-forget on `Dispatchers.IO`.
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
- Registers `JavaTimeModule` with `WRITE_DATES_AS_TIMESTAMPS` disabled, so
  `java.time.Instant` fields serialize as ISO-8601 strings, not numeric epoch
  arrays.

### `Application.configureStatusPages()` — [`plugins/StatusPages.kt`](./plugins/StatusPages.kt)

- **Handles**:
  - `UnsupportedMediaTypeException` → `415`.
  - `PayloadTooLargeException` → `413`, code `payload_too_large`.
  - `BadRequestException` → `400`, message fixed as
    `"Invalid JSON payload structure"`.
- **Side effects**: None beyond response write.

### `Application.configureRequestSizeLimit(config: RequestSizeConfig)` — [`plugins/RequestSizeLimit.kt`](./plugins/RequestSizeLimit.kt)

- **Side effects**: A single application-scope `install(RequestBodyLimit)`. No
  DB or network access.
- **Behavior**: The `bodyLimit` callback selects the limit per call by exact
  request-path match —
  `config.routeOverrides[path]?.bytes ?: config.defaultMax.bytes`. Enforced on
  both the `Content-Length` header and the streamed body, ahead of content
  negotiation. Over-limit requests raise `PayloadTooLargeException` → `413`.
- **Idempotency**: Not idempotent — `RequestBodyLimit` is route-scoped; a second
  install throws `DuplicatePluginException`.

### `SessionConfig.from(config): Result<SessionConfig>` — [`auth/SessionConfig.kt`](./auth/SessionConfig.kt)

- **Side effects**: None — pure config parsing.
- **Error handling**: Missing `session` block →
  `Result.failure(IllegalArgumentException(...))`. Any missing key →
  `Result.failure(exception)`.
- **Idempotency**: Yes — pure function.

---

## IV. Infrastructure & Environment

### HOCON Configuration (module: `rest-server`)

Required keys in `rest-server.conf`:

| Key                                 | Type                         | Description                                                                   |
| ----------------------------------- | ---------------------------- | ----------------------------------------------------------------------------- |
| `server.host`                       | String                       | Netty bind host                                                               |
| `server.port`                       | Int                          | Netty bind port                                                               |
| `server.requestSize.maxSize`        | Size string                  | Default max request body size (e.g. `"8 KiB"`); parsed via `Config.getBytes`  |
| `server.requestSize.routeOverrides` | Object\<path → size string\> | Per-exact-path body-size overrides (e.g. `"/api/v1/auth/register" = "1 KiB"`) |
| `session.expiration`                | Duration                     | Session TTL                                                                   |
| `session.cookieName`                | String                       | Cookie name                                                                   |
| `session.cookieDomain`              | String                       | Cookie domain attribute                                                       |
| `session.cookieSecure`              | Boolean                      | `Secure` cookie flag                                                          |
| `sessionExpiry.ignorePathPrefixes`  | List\<String\>               | Paths excluded from expiry enqueue                                            |
| `clientKeyGate.keys`                | String (comma-separated)     | Valid client keys; empty disables the gate (`${?UNICOACH_CLIENT_KEYS}` override) |
| `clientKeyGate.allowlistPaths`      | List\<String\>               | Paths exempt from the gate (e.g. `["/healthz"]`)                              |

### Config Load Order

`AppConfig.load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf")`

- `chat.conf` is supplied by the `:chat` dependency and surfaces `chat.provider`
  (default `"log"`; production config pins `"anthropic"`).
- `net.conf` is NOT loaded by `rest-server` — only `queue-worker` loads it.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- **HikariCP** (via `Database`): JDBC connection pool.
- **Kotlinx Serialization** (compiler plugin): Required at the `rest-server`
  call site for `SessionExpiryPayload(...).asJson()`.
- **Jackson** (via `ContentNegotiation`): JSON serialization.
- **SLF4J**: Logging backend for `SessionExpiryPlugin`.

### Module Dependencies

`rest-server` depends on: `common`, `db`, `service`, `chat`, `queue`. It does
NOT depend on `net`. (`CoachingService` and `CoachingConfig` live in the
`service` module's `coaching` package; `ChatProvider`/`ChatConfig`/
`ChatProviderFactory` come from `chat`.)

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
- [x] [RFC-24: Result Types](../../../../../../../rfc/24-result-types.md) —
      Introduced the `StatusPages` `PermanentError`/`TransientError` mapping and
      the `getOrThrow()` propagation contract in `appModule`.
- [x] [RFC-25: Auth Routes Refactor](../../../../../../../rfc/25-auth-routes-refactor.md)
      — Moved route registration into `configureRouting` + `AuthRouteHandler`,
      establishing the wiring boundary this directory owns.
- [x] [RFC-29: Request Payload Size Limits](../../../../../../../rfc/29-request-payload-limits.md)
- [x] [RFC-31: Student Profile](../../../../../../../rfc/31-student-profile.md)
      — Wired `StudentService` construction in `appModule` and registered the
      `/api/v1/students/*` route group via `configureRouting`.
- [x] [RFC-45: Coaching Service and Conversation REST Surface](../../../../../../../rfc/45-coaching-service.md)
      — Added `chat.conf` to the config load list; wired fail-fast boot
      construction of the chat provider and `CoachingConfig`; threaded
      `chatProvider`/`coachingConfig` into `appModule` to build `CoachingService`;
      registered the `/api/v1/conversations/*` group via `ConvoRouteHandler` in
      `configureRouting`. First production callsite of the `:chat` module.
- [x] [RFC-53: `/healthz` Liveness Endpoint](../../../../../../../rfc/53-healthz-liveness-endpoint.md)
      — Replaced the placeholder `GET /hello` route with the unauthenticated,
      unversioned `GET /healthz` liveness endpoint that depends on no backing
      service.
- [x] [RFC-54: Client-Key Gate](../../../../../../../rfc/54-client-key-gate.md)
      — Loaded `ClientKeyGateConfig` fail-fast in `startServer`, threaded it
      through `appModule`, and installed the gate after serialization and before
      routing so it fronts every route.
