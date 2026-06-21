# SPEC.md — `admin-server/src/main/kotlin/ed/unicoach/admin`

## I. Overview

The process-bootstrap layer of the internal admin website. It owns the
`admin-server` entrypoint: it loads the admin's own configuration, constructs
the application's shared dependencies (database, auth service, password hasher),
binds an embedded Ktor/Netty server on an internal-only host, and assembles the
application module (status pages, the authorization gate, login/logout routes,
the descriptor-driven resource engine, and the liveness route). It also owns
`AdminConfig` — the typed parse of the admin's bind and session-cookie settings
— and the unauthenticated `/healthz` liveness route. The authorization gate, the
resource engine, the HTML renderers, and the resource descriptors live in
sibling subpackages and are referenced here only by responsibility.

---

## II. Invariants

### Network Isolation

- The server MUST bind the host from `AdminConfig.host`, which defaults to
  `127.0.0.1`. Loopback binding is the tool's primary security boundary: the
  admin website is **never** internet-exposed, and remote reach is an operator
  concern (SSH tunnel / VPN) outside this layer. A change that defaults the bind
  to a non-loopback address violates this boundary.

### Configuration Isolation

- Startup MUST load exactly `common.conf`, `db.conf`, and `admin-server.conf`.
  It MUST NOT load `rest-server.conf`, and the admin process MUST NOT share a
  configuration object with `rest-server`. The admin's bind and cookie settings
  are sourced solely from its own `admin-server.conf` via `AdminConfig`.
- `AdminConfig.from(config)` MUST parse fail-fast: every config parse
  (`DatabaseConfig.from`, `AdminConfig.from`) is unwrapped with `getOrThrow()`
  before the server binds, so a missing or malformed `admin` HOCON block crashes
  the process at startup rather than on the first request.
- `AdminConfig.from` MUST return `Result<AdminConfig>` and MUST NOT throw
  directly — all parse failures are captured as `Result.failure`.

### Liveness

- The server MUST expose an **unauthenticated** liveness route at `/healthz`,
  registered in the top-level `routing { }` block. The authorization gate MUST
  exempt `/healthz` so an operator's probe carrying no admin session is never
  redirected or rejected.
- The `/healthz` handler MUST be dependency-free: it MUST NOT touch the
  database, the auth service, or any backing service, and MUST return its
  response unconditionally. A transient dependency failure MUST NEVER turn the
  liveness probe non-200, so a probe consumer can never kill an
  otherwise-serving process over a dependency blip.

### Server Lifecycle

- The server MUST start non-blocking (`start(wait = false)`) and synchronously
  confirm Netty has bound (resolving connectors) before `startServer` returns,
  so a caller that received the server handle knows the listener is live.
- `database.close()` MUST be invoked from the `ApplicationStopped` lifecycle
  event to release the connection pool on shutdown.

### Dependency Construction

- The database, password hasher, and auth service MUST be constructed once in
  `startServer` and injected into the application module; the module MUST NOT
  construct its own `Database` or auth service. The same hasher instance
  injected for login MUST be the one threaded to the resource engine's
  user-create path, so the admin process holds a single hashing configuration.

### Authorization Coverage

- The authorization gate MUST be installed before any descriptor-driven resource
  routes are registered, so every resource route is fronted by the gate with no
  per-route opt-in. `/healthz`, `/login`, and `/logout` are the only routes
  reachable without an authorized admin session; the exemption of those three is
  owned by the gate (see [`auth/SPEC.md`](./auth/SPEC.md)).

### Error Containment

- An uncaught `Throwable` from any handler MUST be caught by a single
  application-scope `StatusPages` handler and rendered as an HTML service-
  unavailable page; an exception MUST NEVER escape as a default framework error
  page. The mapping of specific domain failures (not-found, OCC conflict,
  validation, duplicate) to their pages is owned by the engine and render
  subpackages, not this layer.

---

## III. Behavioral Contracts

### `startServer(wait: Boolean = true): EmbeddedServer<*, *>` — [`Application.kt`](./Application.kt)

- **Side effects**: Loads configuration from the classpath (plus the sanctioned
  local overlay, via `AppConfig.load`); opens the database connection pool
  (`Database`); constructs `AuthService`; binds and starts a Netty listener on
  `AdminConfig.host`/`port`; subscribes an `ApplicationStopped` hook that closes
  the database.
- **Error handling**: Any config parse failure propagates as an uncaught
  exception via `getOrThrow()`, crashing the process before the server binds.
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

- **Side effects**: Installs the application-scope `StatusPages` catch-all;
  installs the authorization gate; builds the resource registry over the user,
  student, session, and system-prompt descriptors; and registers the liveness,
  login/logout, and descriptor-driven resource routes into the routing tree.
- **Inputs**: All collaborators are pre-constructed by `startServer` and passed
  in; the module performs no config parsing and constructs no IO-bound singleton
  of its own.
- **Idempotency**: No — Ktor plugin and route installation is not idempotent;
  invoking it twice on one `Application` installs duplicate plugins/routes.

### `Route.healthRoute()` — [`Health.kt`](./Health.kt)

- **Behavior**: Registers `GET /healthz`, responding `200` with a constant JSON
  body (`{"status":"ok"}`) and `Content-Type: application/json`.
- **Side effects**: None — no database, auth, or backing-service access.
- **Error handling**: None — the response is unconditional.
- **Idempotency**: Yes — every request yields the identical response;
  registration-time installation is not idempotent (a second call duplicates the
  route).

### `AdminConfig.from(config: Config): Result<AdminConfig>` — [`AdminConfig.kt`](./AdminConfig.kt)

- **Side effects**: None — pure parse of an already-loaded `Config`.
- **Error handling**: A missing `admin` section or any missing key under
  `admin.server` / `admin.session` yields `Result.failure` carrying the
  underlying exception; the function never throws.
- **Idempotency**: Yes — pure function of its input.

---

## IV. Infrastructure & Environment

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

`AppConfig.load("common.conf", "db.conf", "admin-server.conf")` — the admin
process loads only these three resources and never `rest-server.conf`.

### Module Dependencies

`admin-server` depends on `common`, `db`, and `service` (the auth service and
password hasher). It does NOT depend on `rest-server`, `queue`, `email`, or
`chat`.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- Connection pooling and transaction handling are owned by `Database` (in `db`)
  and are not the concern of this layer.

---

## V. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../rfc/60-admin-website.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../rfc/63-admin-system-prompts.md)
      — `adminModule` now registers `SystemPromptsResource` alongside the user,
      student, and session descriptors, so the admin gains a `system-prompt`
      create + list/detail surface over the immutable catalog. No change to the
      gate, config-load list, or module dependencies.
