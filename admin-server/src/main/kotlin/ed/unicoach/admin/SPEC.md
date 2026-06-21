# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin`

## I. Overview

The process-bootstrap layer of the internal admin website. It owns the
`admin-server` entrypoint: it loads the admin's configuration, constructs the
application's shared dependencies (database, auth service, password hasher),
binds an embedded Ktor/Netty server on an internal-only host, and assembles the
application module (status pages, the authorization gate, login/logout routes,
the descriptor-driven resource engine, and the liveness route). It also owns
`AdminConfig` — the typed parse of the admin's bind and session-cookie settings
— and the unauthenticated `/healthz` liveness route. The authorization gate, the
resource engine, the HTML renderers, and the resource descriptors live in
sibling subpackages and are referenced here only by responsibility.

---

## II. Behavioral Contracts

### `startServer(wait: Boolean = true): EmbeddedServer<*, *>` — [`Application.kt`](./Application.kt)

- **Behavior**: Loads and parses configuration fail-fast (every parse is
  unwrapped with `getOrThrow()` before the server binds), constructs the shared
  dependencies, binds a Netty listener on `AdminConfig.host`/`port`, starts it
  non-blocking, and synchronously confirms Netty has bound (resolving
  connectors) before returning the running server handle.
- **Side effects**: Loads configuration from the classpath plus the sanctioned
  local overlay (via `AppConfig.load`); opens the database connection pool
  (`Database`); constructs the password hasher, token generator, email service,
  and email-verification service; constructs `AuthService`; binds and starts the
  Netty listener; subscribes an `ApplicationStopped` hook that closes the
  database.
- **Email wiring (inert here)**: Builds an `EmailService` and an
  `EmailVerificationService` solely to satisfy the `AuthService` constructor,
  which requires an `EmailVerificationService`. The admin gate only
  authenticates via `AuthService`; it never registers users or sends
  verification mail, so the configured (log) email provider is never exercised
  in the admin process.
- **Error handling**: Any config parse failure (`DatabaseConfig.from`,
  `AdminConfig.from`, `EmailConfig.from`, `EmailVerificationConfig.from`, or
  provider construction) propagates as an uncaught exception via `getOrThrow()`,
  crashing the process before the server binds rather than on first request.
- **Blocking**: When `wait = true` (the `main` path) the call blocks the current
  thread indefinitely after binding; when `wait = false` (the test path) it
  returns the running server handle once Netty has bound.
- **Idempotency**: No — each call binds a new server instance and opens a new
  pool.

### `main()` — [`Application.kt`](./Application.kt)

- **Behavior**: The module entrypoint (`ed.unicoach.admin.ApplicationKt`). Calls
  `startServer(wait = true)` and never returns under normal operation.
- **Side effects**: As `startServer`.

### `Application.adminModule(database, authService, argon2Hasher, adminConfig)` — [`Application.kt`](./Application.kt)

- **Behavior**: Assembles the application module from pre-constructed
  collaborators. Installs the gate before any descriptor-driven resource routes,
  so every resource route is fronted by the gate with no per-route opt-in.
- **Side effects**: Installs an application-scope `StatusPages` catch-all that
  renders any uncaught `Throwable` as an HTML service-unavailable page; installs
  the authorization gate; builds the resource registry over the user, student,
  session, and system-prompt descriptors (`UsersResource`, `StudentsResource`,
  `SessionsResource`, `SystemPromptsResource`); and registers the liveness,
  login/logout, and descriptor-driven resource routes into the routing tree. The
  same hasher instance used for login is threaded to the resource engine's
  user-create path (only `UsersResource` is constructed with the hasher; the
  other three descriptors are passed as-is).
- **Inputs**: All collaborators are pre-constructed by `startServer` and passed
  in; the module performs no config parsing and constructs no IO-bound singleton
  of its own.
- **Error handling**: The `StatusPages` catch-all contains uncaught exceptions
  as the service-unavailable page; mapping of specific domain failures
  (not-found, OCC conflict, validation, duplicate) to pages is owned by the
  engine and render subpackages.
- **Idempotency**: No — Ktor plugin and route installation is not idempotent;
  invoking it twice on one `Application` installs duplicate plugins/routes.

### `Route.healthRoute()` — [`Health.kt`](./Health.kt)

- **Behavior**: Registers an unauthenticated `GET /healthz`, responding `200`
  with a constant JSON body (`{"status":"ok"}`) and
  `Content-Type: application/json`. The gate exempts `/healthz`, so a probe
  carrying no admin session is never redirected or rejected.
- **Side effects**: None — no database, auth, or backing-service access.
- **Error handling**: None — the response is returned unconditionally, so a
  transient dependency failure does not turn the probe non-200.
- **Idempotency**: Yes — every request yields the identical response;
  registration-time installation is not idempotent (a second call duplicates the
  route).

### `AdminConfig.from(config: Config): Result<AdminConfig>` — [`AdminConfig.kt`](./AdminConfig.kt)

- **Behavior**: Pure parse of an already-loaded `Config` into the typed admin
  bind/session settings, reading every key directly under `admin.server` /
  `admin.session` with no in-code default (the loopback `host` default lives in
  `admin-server.conf`, not this function).
- **Side effects**: None.
- **Error handling**: A missing `admin` section or any missing key under
  `admin.server` / `admin.session` yields `Result.failure` carrying the
  underlying exception; the function does not throw.
- **Idempotency**: Yes — pure function of its input.

---

## III. Infrastructure & Environment

### HOCON Configuration (module: `admin-server`)

Required keys in `admin-server.conf`, parsed by `AdminConfig.from`:

| Key                               | Type    | Description                                         |
| --------------------------------- | ------- | --------------------------------------------------- |
| `admin.server.host`               | String  | Netty bind host; defaults to loopback (`127.0.0.1`) |
| `admin.server.port`               | Int     | Netty bind port                                     |
| `admin.session.cookieName`        | String  | Admin session cookie name                           |
| `admin.session.cookieDomain`      | String  | Cookie domain attribute (empty ⇒ host-only cookie)  |
| `admin.session.cookieSecure`      | Boolean | `Secure` cookie flag                                |
| `admin.session.expirationSeconds` | Long    | Admin session TTL, in seconds                       |

### Environment Variable Overrides

`admin-server.conf` binds these HOCON substitutions; each overrides the
corresponding key when set in the process environment:

| Variable              | Overrides                    |
| --------------------- | ---------------------------- |
| `ADMIN_SERVER_HOST`   | `admin.server.host`          |
| `ADMIN_SERVER_PORT`   | `admin.server.port`          |
| `ADMIN_COOKIE_DOMAIN` | `admin.session.cookieDomain` |
| `ADMIN_COOKIE_SECURE` | `admin.session.cookieSecure` |

### Config Load List

`AppConfig.load("common.conf", "db.conf", "admin-server.conf", "service.conf", "email.conf")`
— the admin process loads these five resources. `service.conf` and `email.conf`
back the `EmailService` / `EmailVerificationService` that `AuthService`
requires; the admin process does not load `rest-server.conf`. The `admin` bind
and cookie settings are sourced solely from `admin-server.conf` via
`AdminConfig`.

### Module Dependencies

`admin-server` depends on `common`, `db`, `service` (the auth service and
password hasher), and `email` (the email service and provider factory, wired
inert in the admin context). It does not depend on `rest-server`, `queue`, or
`chat`.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- Connection pooling and transaction handling are owned by `Database` (in `db`)
  and are not the concern of this layer.

---

## IV. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../rfc/60-admin-website.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-65: Email Verification (Backend)](../../../../../../../rfc/65-email-verification.md)
