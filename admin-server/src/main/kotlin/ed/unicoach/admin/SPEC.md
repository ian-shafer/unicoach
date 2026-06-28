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
- **Email wiring (live collaborator of `UsersResource`)**: Builds one
  `EmailService` and one `EmailVerificationService` and passes the latter into
  both `AuthService` (which requires it) and `adminModule` (which threads it
  into `UsersResource`). The `EmailVerificationService` is exercised in the
  admin process: the `UsersResource` "send verification email" action calls
  `resend`, which — for an active, unverified user — burns outstanding
  verification tokens, issues a fresh one (a DB write), and best-effort sends
  the verification email through the configured (log) provider. The admin gate
  itself still only authenticates via `AuthService` and never registers users;
  the `StubGoogleTokenVerifier` (RFC 64) remains inert because the admin gate
  never exercises the Google login path.
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

### `Application.adminModule(database, authService, argon2Hasher, emailVerificationService, adminConfig)` — [`Application.kt`](./Application.kt)

- **Behavior**: Assembles the application module from pre-constructed
  collaborators. Installs the gate before any descriptor-driven resource routes,
  so every resource route is fronted by the gate with no per-route opt-in.
- **Side effects**: Installs an application-scope `StatusPages` catch-all that
  renders any uncaught `Throwable` as an HTML service-unavailable page; installs
  the authorization gate; builds the resource registry over the user, student,
  session, system-prompt, claims, observations, extraction-runs, conversation,
  and conversation-request descriptors (`UsersResource`, `StudentsResource`,
  `SessionsResource`, `SystemPromptsResource`, `ClaimsResource`,
  `ObservationsResource`, `ExtractionRunsResource`, `ConvosResource`,
  `ConvoRequestsResource`); constructs an `AdminDisplay` from
  `adminConfig.display` and the registry's entity-support predicate (RFC 79:
  `{ slug -> registry.bySlug(slug) != null }`), then passes it to
  `registerAdminRoutes` so every cell renders through the uniform `renderCell`
  helper; and registers the liveness, login/logout, and descriptor-driven
  resource routes into the routing tree. The claims, observations,
  extraction-runs, conversation, and conversation-request descriptors expose the
  coaching-memory surfaces as list+detail read-only views (no
  create/edit/delete). `UsersResource` is the only descriptor constructed with
  collaborators: it receives both the same hasher instance used for login (for
  its user-create path) and the `emailVerificationService` (for its "send
  verification email" action's resend path); the other descriptors are passed
  as-is.
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
  bind/session settings plus the display conventions (`DisplayConfig`), reading
  every key directly under `admin.server` / `admin.session` / `admin.display`
  with no in-code defaults (all defaults live in `admin-server.conf`). The
  `timezone` key is validated through `ZoneId.of`, so a malformed timezone zone
  ID fails this call (not at first render).
- **Side effects**: None.
- **Error handling**: A missing `admin` section, any missing key under
  `admin.server` / `admin.session` / `admin.display`, or a malformed timezone
  yields `Result.failure` carrying the underlying exception; the function does
  not throw.
- **Idempotency**: Yes — pure function of its input.

### `DisplayConfig` — [`AdminConfig.kt`](./AdminConfig.kt)

A nested value on `AdminConfig` (RFC 79). Carries the display conventions shared
by every admin view: `timezone` (`ZoneId`), `idLinkGlyph`, `boolTrueGlyph`,
`boolFalseGlyph`. Parsed from the `admin.display` HOCON section. The render
layer converts it to `AdminDisplay` (via `DisplayConfig.toAdminDisplay`) and
uses it directly; `AdminConfig` is never referenced by the render layer.

---

## III. Infrastructure & Environment

### HOCON Configuration (module: `admin-server`)

Required keys in `admin-server.conf`, parsed by `AdminConfig.from`:

| Key                               | Type    | Description                                                                  |
| --------------------------------- | ------- | ---------------------------------------------------------------------------- |
| `admin.server.host`               | String  | Netty bind host; defaults to loopback (`127.0.0.1`)                          |
| `admin.server.port`               | Int     | Netty bind port                                                              |
| `admin.session.cookieName`        | String  | Admin session cookie name                                                    |
| `admin.session.cookieDomain`      | String  | Cookie domain attribute (empty ⇒ host-only cookie)                           |
| `admin.session.cookieSecure`      | Boolean | `Secure` cookie flag                                                         |
| `admin.session.expirationSeconds` | Long    | Admin session TTL, in seconds                                                |
| `admin.display.timezone`          | String  | Timezone for all datetime cells (validated via `ZoneId.of`); default `"UTC"` |
| `admin.display.idLinkGlyph`       | String  | Entity-reference link glyph; default `"🔗"`                                  |
| `admin.display.boolTrueGlyph`     | String  | Boolean true glyph; default `"✓"`                                            |
| `admin.display.boolFalseGlyph`    | String  | Boolean false glyph; default `"✗"`                                           |

### Environment Variable Overrides

`admin-server.conf` binds these HOCON substitutions; each overrides the
corresponding key when set in the process environment:

| Variable                 | Overrides                      |
| ------------------------ | ------------------------------ |
| `ADMIN_SERVER_HOST`      | `admin.server.host`            |
| `ADMIN_SERVER_PORT`      | `admin.server.port`            |
| `ADMIN_COOKIE_DOMAIN`    | `admin.session.cookieDomain`   |
| `ADMIN_COOKIE_SECURE`    | `admin.session.cookieSecure`   |
| `ADMIN_DISPLAY_TIMEZONE` | `admin.display.timezone`       |
| `ADMIN_ID_LINK_GLYPH`    | `admin.display.idLinkGlyph`    |
| `ADMIN_BOOL_TRUE_GLYPH`  | `admin.display.boolTrueGlyph`  |
| `ADMIN_BOOL_FALSE_GLYPH` | `admin.display.boolFalseGlyph` |

### Config Load List

`AppConfig.load("common.conf", "db.conf", "admin-server.conf", "service.conf", "email.conf")`
— the admin process loads these five resources. `service.conf` and `email.conf`
back the `EmailService` / `EmailVerificationService` that `AuthService` requires
and that `UsersResource` exercises via its "send verification email" action; the
admin process does not load `rest-server.conf`. The `admin` bind and cookie
settings are sourced solely from `admin-server.conf` via `AdminConfig`.

### Module Dependencies

`admin-server` depends on `common`, `db`, `service` (the auth service, password
hasher, and email-verification service), and `email` (the email service and
provider factory). The email-verification service is a live collaborator here,
driving the `UsersResource` resend action. It does not depend on `rest-server`,
`queue`, or `chat`.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- Connection pooling and transaction handling are owned by `Database` (in `db`)
  and are not the concern of this layer.

---

## IV. History

- [x] [RFC-60: Admin Website (Framework + Users Spine)](../../../../../../../rfc/60-admin-website.md)
- [x] [RFC-63: Admin System Prompts](../../../../../../../rfc/63-admin-system-prompts.md)
- [x] [RFC-65: Email Verification (Backend)](../../../../../../../rfc/65-email-verification.md)
- [x] [RFC-76: Admin email-verification actions](../../../../../../../rfc/76-admin-email-verification-actions.md)
- [x] [RFC-77: Read-only admin views for coaching memory](../../../../../../../rfc/77-admin-coaching-memory-views.md)
- [x] [RFC-79: Admin display conventions](../../../../../../../rfc/79-admin-display-conventions.md)
      — added `DisplayConfig` to `AdminConfig.kt` (parsed from a new
      `admin.display` HOCON block: `timezone`, `idLinkGlyph`, `boolTrueGlyph`,
      `boolFalseGlyph`; timezone validated via `ZoneId.of` at startup); added
      four new environment-variable overrides (`ADMIN_DISPLAY_TIMEZONE`,
      `ADMIN_ID_LINK_GLYPH`, `ADMIN_BOOL_TRUE_GLYPH`, `ADMIN_BOOL_FALSE_GLYPH`);
      `adminModule` now builds an `AdminDisplay` from the parsed display config
      and the registry's entity-support predicate and passes it to
      `registerAdminRoutes`.
- [x] [RFC-81: Admin conversation views](../../../../../../../rfc/81-admin-conversation-views.md)
      — `adminModule` now registers `ConvosResource` and `ConvoRequestsResource`
      in the `AdminRegistry` constructor list, bringing the total to 9
      resources; the registry's entity-support predicate consequently returns
      `true` for the `"convo"` and `"convo-request"` slugs.
