# SPEC.md — `rest-server/src/main/kotlin/ed/unicoach/rest`

## I. Overview

The HTTP presentation layer of the unicoach platform. It hosts the Ktor/Netty
embedded server, wires all application plugins, and exposes the REST API surface
under `/api/v1`. It constructs the application's services (`AuthService`,
`StudentService`, `CoachingService`, `EmailService`, `EmailVerificationService`)
from boot-time config and injects them into the route handlers, then translates
between domain results (from `service` and `db`) and HTTP responses, managing
session cookie lifecycle and asynchronous session expiry enqueueing. It contains
no domain logic.

---

## II. Behavioral Contracts

> The per-route HTTP contracts for `/api/v1/auth/*`, `/api/v1/students/*`, and
> `/api/v1/conversations/*` (request/response DTOs, status codes, cookie
> lifecycle, OCC behavior, cascade-delete cookie clearing, the
> email-verification routes) are owned by
> [`routing/SPEC.md`](./routing/SPEC.md). The DTO shapes are owned by
> [`models/SPEC.md`](./models/SPEC.md). Request-time plugin behavior is owned by
> [`plugins/SPEC.md`](./plugins/SPEC.md). This directory documents only the
> wiring, server lifecycle, and config it constructs.

### `startServer(wait: Boolean, port: Int?): EmbeddedServer<*, *>` — [`Application.kt`](./Application.kt)

- **Behavior**: Loads config, resolves every typed config block fail-fast,
  constructs the database, email provider, email service, and queue service,
  then boots an `embeddedServer(Netty)`. `port` overrides `server.port`; tests
  pass `0` for an ephemeral port. Starts non-blocking
  (`server.start(wait = false)`) and blocks on
  `server.engine.resolvedConnectors()` so it returns only after Netty has bound.
  When `wait = true`, joins the current thread to keep the process alive.
- **Boot-time construction (all fail-fast)**: Each of `DatabaseConfig`,
  `SessionConfig`, `RequestSizeConfig`, `QueueConfig`, `ChatConfig` +
  `ChatProviderFactory.fromConfig`, `CoachingConfig`, `ClientKeyGateConfig`,
  `EmailConfig`, and `EmailVerificationConfig` is resolved with `getOrThrow()`
  before the server binds. The email provider is built via
  `EmailProviderFactory.fromConfig(emailConfig)` and folded into
  `EmailService(database, emailProvider, emailConfig)`. A missing or invalid key
  in any of these blocks crashes the process at startup rather than on the first
  request.
- **Email provider selection**: `email.provider` defaults to `"log"`
  (`LogOnlyEmailProvider`), which records each outbound email to the log but
  does not transmit it; `"ses"` selects the SES-backed provider. Any other value
  fails boot.
- **Side effects**: Opens the HikariCP pool (`Database`), starts the Netty
  listener, installs `SessionExpiryPlugin` (inside the `embeddedServer` lambda,
  not in `appModule`), and subscribes an `ApplicationStopped` hook that calls
  `database.close()`.
- **Error handling**: Any `getOrThrow()` failure propagates as an uncaught
  exception, crashing the process with the underlying config message before the
  server binds.
- **Idempotency**: Not idempotent — calling twice binds two server instances.

### `Application.appModule(database, sessionConfig, requestSizeConfig, chatProvider, coachingConfig, clientKeyGateConfig, emailService, emailVerificationConfig)` — [`Application.kt`](./Application.kt)

- **Behavior**: Installs the request-pipeline plugins, then constructs the
  services and registers all routes. Plugins install in order:
  `configureSerialization()` (Jackson `ContentNegotiation`), the client-key gate
  via `configureClientKeyGate(clientKeyGateConfig)` (after serialization, before
  routing), `configureStatusPages()`, and the application-scope body-size limit
  via `configureRequestSizeLimit(requestSizeConfig)`.
- **Service construction**: Builds `Argon2Hasher` and `TokenGenerator`, then
  `EmailVerificationService(database, emailService, tokenGenerator, emailVerificationConfig)`,
  `AuthService(database, argon2Hasher, tokenGenerator, emailVerificationService)`,
  `StudentService(database)`, and
  `CoachingService(database, chatProvider, coachingConfig)`. Routes are
  registered via
  `configureRouting(authService, studentService, coachingService, sessionConfig, emailVerificationService)`.
- **Inputs**: The pre-built `chatProvider`, `coachingConfig`, `emailService`,
  and `emailVerificationConfig` are passed in (resolved fail-fast by
  `startServer`), so `appModule` itself parses no config.
- **Scope**: Excludes `SessionExpiryPlugin` installation. Tests calling
  `appModule()` directly bypass the queue-write side effect.
- **Idempotency**: Not idempotent — Ktor plugin installation throws if repeated
  on the same `Application`.

### `Application.configureRouting(authService, studentService, coachingService, sessionConfig, emailVerificationService)` — [`Routing.kt`](./Routing.kt)

- **Behavior**: Constructs
  `AuthRouteHandler(authService, sessionConfig, emailVerificationService)`,
  `StudentRouteHandler(authService, studentService, sessionConfig)`, and
  `ConvoRouteHandler(authService, studentService, coachingService, sessionConfig)`,
  then registers their routes inside a single top-level `routing { }` block.
- **Routes registered**:
  - `GET /healthz` → `200 OK`, `Content-Type: application/json`, constant body
    `{"status":"ok"}`; unauthenticated, unversioned (not under `/api/v1`), and
    dependency-free. Non-GET → `405` with `Allow: GET` via
    `rejectUnsupportedMethods(HttpMethod.Get)`.
  - All `/api/v1/auth/*` routes via `authRouteHandler.registerRoutes(...)` —
    including the email-verification routes, which depend on the threaded
    `emailVerificationService`.
  - All `/api/v1/students/*` routes via
    `studentRouteHandler.registerRoutes(...)`.
  - All `/api/v1/conversations/*` routes via
    `convoRouteHandler.registerRoutes(...)`.
- **Side effects**: Route-table registration — installs handlers into the Ktor
  routing tree.
- **Idempotency**: Not idempotent — calling twice installs duplicate routes.

### `Route.rejectUnsupportedMethods(vararg methods)` — [`Routing.kt`](./Routing.kt)

- **Behavior**: Installs a catch-all `handle {}` into the routing DSL that
  responds `405 Method Not Allowed`, appends an `Allow` header listing the
  permitted method values, and writes the expected-methods string as the body.
- **Side effects**: Registration-time only — adds a handler to the route.
- **Idempotency**: Not idempotent — calling twice on the same route installs a
  duplicate handler.

### `SessionConfig.from(config): Result<SessionConfig>` — [`auth/SessionConfig.kt`](./auth/SessionConfig.kt)

- **Behavior**: Pure parsing of the `session` HOCON block into the four fields
  `expiration` (Duration), `cookieName`, `cookieDomain`, `cookieSecure`.
- **Side effects**: None.
- **Error handling**: A missing `session` block or any missing key returns
  `Result.failure(exception)` carrying the underlying config exception.
- **Idempotency**: Yes — pure function.

---

## III. Infrastructure & Environment

### Config Load Order

`AppConfig.load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")`

- `chat.conf` is supplied by the `:chat` dependency and surfaces `chat.provider`
  (default `"log"`; production config pins `"anthropic"`).
- `email.conf` is supplied by the `:email` dependency and surfaces the `email`
  block consumed by `EmailConfig.from`.
- The `emailVerification` block consumed by `EmailVerificationConfig.from` lives
  in `service.conf` (supplied by the `:service` dependency).
- `net.conf` is not loaded by `rest-server` — only `queue-worker` loads it.

### HOCON Configuration

Keys read by this directory's wiring:

| Key                                 | Source       | Type                         | Description                                                                      |
| ----------------------------------- | ------------ | ---------------------------- | -------------------------------------------------------------------------------- |
| `server.host`                       | rest-server  | String                       | Netty bind host                                                                  |
| `server.port`                       | rest-server  | Int                          | Netty bind port (overridable by `startServer`'s `port` argument)                 |
| `server.requestSize.maxSize`        | rest-server  | Size string                  | Default max request body size (e.g. `"8 KiB"`); parsed via `Config.getBytes`     |
| `server.requestSize.routeOverrides` | rest-server  | Object\<path → size string\> | Per-exact-path body-size overrides (e.g. `"/api/v1/auth/register" = "1 KiB"`)    |
| `session.expiration`                | rest-server  | Duration                     | Session TTL                                                                      |
| `session.cookieName`                | rest-server  | String                       | Cookie name                                                                      |
| `session.cookieDomain`              | rest-server  | String                       | Cookie domain attribute                                                          |
| `session.cookieSecure`              | rest-server  | Boolean                      | `Secure` cookie flag                                                             |
| `sessionExpiry.ignorePathPrefixes`  | rest-server  | List\<String\>               | Paths excluded from expiry enqueue                                               |
| `clientKeyGate.keys`                | rest-server  | String (comma-separated)     | Valid client keys; empty disables the gate (`${?UNICOACH_CLIENT_KEYS}` override) |
| `clientKeyGate.allowlistPaths`      | rest-server  | List\<String\>               | Paths exempt from the gate (e.g. `["/healthz"]`)                                 |
| `email.defaultFrom`                 | email.conf   | String                       | Default `From` address (`${?EMAIL_DEFAULT_FROM}` override)                       |
| `email.provider`                    | email.conf   | String                       | `"log"` or `"ses"` (`${?EMAIL_PROVIDER}` override); selects the email provider   |
| `email.ses.region`                  | email.conf   | String                       | SES region (`${?EMAIL_SES_REGION}` override); read only for the `"ses"` provider |
| `email.ses.accessKeyId`             | email.conf   | String (optional)            | Static SES access key (`${?EMAIL_SES_ACCESS_KEY_ID}`); default chain when absent |
| `email.ses.secretAccessKey`         | email.conf   | String (optional)            | Static SES secret (`${?EMAIL_SES_SECRET_ACCESS_KEY}`); default chain when absent |
| `emailVerification.tokenTtl`        | service.conf | Duration                     | Verification-token lifetime (`${?EMAIL_VERIFICATION_TOKEN_TTL}` override)        |
| `emailVerification.verifyUrlBase`   | service.conf | String                       | Verification-link prefix (`${?EMAIL_VERIFICATION_VERIFY_URL_BASE}` override)     |

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- **HikariCP** (via `Database`): JDBC connection pool.
- **Kotlinx Serialization** (compiler plugin): Required at the `rest-server`
  call site for `SessionExpiryPayload(...).asJson()`.
- **Jackson** (via `ContentNegotiation`): JSON serialization.
- **SLF4J**: Logging backend (e.g. `SessionExpiryPlugin`,
  `LogOnlyEmailProvider`).

### Module Dependencies

`rest-server` depends on `common`, `db`, `service`, `chat`, `queue`, and
`email`. It does not depend on `net`. `CoachingService`/`CoachingConfig` live in
the `service` module's `coaching` package; `EmailVerificationService`/
`EmailVerificationConfig` live in the `service` module's `auth` package;
`ChatProvider`/`ChatConfig`/`ChatProviderFactory` come from `chat`;
`EmailConfig`/`EmailProviderFactory`/`EmailService` come from `email`.

---

## IV. History

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
      `chatProvider`/`coachingConfig` into `appModule` to build
      `CoachingService`; registered the `/api/v1/conversations/*` group via
      `ConvoRouteHandler` in `configureRouting`. First production callsite of
      the `:chat` module.
- [x] [RFC-53: `/healthz` Liveness Endpoint](../../../../../../../rfc/53-healthz-liveness-endpoint.md)
      — Replaced the placeholder `GET /hello` route with the unauthenticated,
      unversioned `GET /healthz` liveness endpoint that depends on no backing
      service.
- [x] [RFC-54: Client-Key Gate](../../../../../../../rfc/54-client-key-gate.md)
      — Loaded `ClientKeyGateConfig` fail-fast in `startServer`, threaded it
      through `appModule`, and installed the gate after serialization and before
      routing so it fronts every route.
- [x] [RFC-65: Email Verification (Backend)](../../../../../../../rfc/65-email-verification.md)
      — Added `email.conf` to the config load list; resolved `EmailConfig`,
      `EmailProviderFactory.fromConfig`, `EmailService`, and
      `EmailVerificationConfig` fail-fast in `startServer`; threaded
      `emailService`/`emailVerificationConfig` through `appModule` to construct
      `EmailVerificationService` (and pass it into `AuthService`); threaded
      `emailVerificationService` into `configureRouting` → `AuthRouteHandler`.
      First production callsite of the `:email` module (default provider
      `"log"`).
