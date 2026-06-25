# SPEC.md — `public-web/src/main/kotlin/ed/unicoach/web`

## I. Overview

The process-bootstrap and routing layer of `public-web`, the internet-facing
Ktor/Netty server that serves Unicoach's public web presence: the brand/home
page, the legal pages (Terms of Service, Privacy Policy), the server-side
email-verification flow, and branded error pages. It owns the `public-web`
entrypoint (config load, `Database` construction, Netty bind, module assembly),
the typed `PublicWebConfig`, the dependency-free `/healthz` liveness route, the
`VerifyEmailOutcome` render view-model, and the single routing table that maps
every page route through the shared HTML renderers in the sibling
[`render`](./render) package. No authentication or client-key gate fronts any
route; every route is public.

---

## II. Behavioral Contracts

### `startServer(wait: Boolean = true): EmbeddedServer<*, *>` — [`Application.kt`](./Application.kt)

- **Behavior**: Boots the server. Loads configuration, parses `PublicWebConfig`,
  builds a
  [`Database`](../../../../../../../db/src/main/kotlin/ed/unicoach/db/Database.kt)
  from the shared `database` config block, wraps it in a `DbEmailVerifier` for
  the in-process verify flow, binds Netty to the configured host/port, installs
  the routing module (passing the verifier and the open-in-app URL), starts the
  listener non-blocking, then synchronously resolves the bound connectors before
  returning the running server handle.
- **Side effects**: Reads configuration from the classpath plus the local
  overlay (`AppConfig.load("common.conf", "db.conf", "public-web.conf")`);
  constructs a `Database` (opening a connection pool); registers an
  `ApplicationStopped` hook that closes the `Database` on server stop; binds and
  starts a Netty listener on `PublicWebConfig.host`/`port`.
- **Error handling**: A config-load or parse failure (including the `database`
  block via `DatabaseConfig.from`) propagates as an uncaught exception via
  `getOrThrow()`, crashing the process before the listener binds.
- **Blocking**: When `wait = true` (the `main` path) the call blocks the calling
  thread indefinitely after the listener binds; when `wait = false` (the test
  path) it returns the running handle once Netty has bound.
- **Idempotency**: No — each call builds a fresh `Database` and binds a fresh
  server instance.

### `main()` — [`Application.kt`](./Application.kt)

- **Behavior**: The module entrypoint (`ed.unicoach.web.ApplicationKt`). Calls
  `startServer(wait = true)` and does not return under normal operation.
- **Side effects**: As `startServer`.

### `Application.publicWebModule(emailVerifier: EmailVerifier, openInAppUrl: String)` — [`Application.kt`](./Application.kt)

- **Behavior**: The Ktor application module. Delegates to
  `installPublicWebRouting(emailVerifier, openInAppUrl)`, which installs the
  status-pages plugin and registers the routing table. (The `Database`-closing
  `ApplicationStopped` hook is subscribed by `startServer` in the
  `embeddedServer` lambda, alongside this call.)
- **Inputs**: The `EmailVerifier` (the verify-flow collaborator) and the
  iPhone-only open-in-app URL — both built by `startServer`. It parses no config
  and constructs no IO-bound singleton itself.
- **Idempotency**: No — Ktor plugin and route installation duplicates on a
  second invocation against the same `Application`.

### `Application.installPublicWebRouting(emailVerifier: EmailVerifier, openInAppUrl: String)` — [`Routing.kt`](./Routing.kt)

- **Behavior**: Installs the single routing table and the error-page handling.
  Installs `StatusPages` with a `status` handler for `NotFound` (renders the
  branded 404 through the shared layout) and an `exception<Throwable>` catch-all
  (renders the branded 503). In the routing block, registers `GET /healthz`,
  `GET /` (home), `GET /terms`, `GET /privacy`, the two-step
  `GET`/`POST
  /verify-email` flow, and a `staticResources("/", "static")`
  mount. The explicit page routes are registered before the static mount, so the
  mount never handles them; it serves only the chrome-less assets packaged under
  `static/` (the stylesheet and any images), never HTML.
- **Side effects**: Mutates the `Application`'s plugin and routing trees. No DB
  or network access at install time; the only request-time DB access is the
  verify call inside `POST /verify-email`.
- **Error handling**: An unmatched route resolves to the `NotFound` status
  handler (404); an uncaught `Throwable` from any handler resolves to the
  `exception` handler (503). Both render branded HTML rather than a default Ktor
  body or a stack trace. The 404 is caught structurally at the status layer
  because the page handlers perform no lookups (no handler issues an explicit
  not-found).
- **Idempotency**: No — installation duplicates on a second call.

### `GET /verify-email` — [`Routing.kt`](./Routing.kt)

- **Behavior**: The side-effect-free confirm step. Reads the `token` query
  parameter; a non-blank token renders the confirm form
  (`respondVerifyEmailConfirm`); an absent or blank token renders the
  `InvalidToken` result page directly.
- **Side effects**: None — no verifier call, no token consumption, no state
  change. Rendering the confirm form rather than consuming the token on `GET`
  keeps a link-scanner prefetch from burning the single-use token.
- **Error handling**: Blank/absent token → `VerifyEmailOutcome.InvalidToken`
  result page; otherwise the confirm form.
- **Idempotency**: Yes — re-requesting renders the same page and changes no
  state.

### `POST /verify-email` — [`Routing.kt`](./Routing.kt)

- **Behavior**: The one state-mutating route. Reads the `token` form parameter
  and the `User-Agent` header (used only to set the iPhone open-in-app
  affordance). A blank/absent token short-circuits to `InvalidToken` with no
  verifier call; otherwise it calls `emailVerifier.verify(token)`, maps the
  `Result<VerifyEmailResult>` to a `VerifyEmailOutcome`, and renders the result
  page (`respondVerifyEmailResult`).
- **Side effects**: Consumes a single-use verification token in-process through
  the `EmailVerifier`. The open-in-app button is shown only when the
  `User-Agent` contains `iPhone`.
- **Error handling**: Domain success folds case-by-case (`Success → Verified`,
  `InvalidToken → InvalidToken`, `Expired → Expired`,
  `AlreadyConsumed → AlreadyUsed`); a domain `Result.failure` (a DB fault) folds
  to `VerifyEmailOutcome.Unavailable`. Mapping is performed by the private
  `Result<VerifyEmailResult>.toOutcome()` in [`Routing.kt`](./Routing.kt).
- **Idempotency**: No — a successful first call consumes the token; a second
  call with the same token yields `AlreadyUsed`.

### `VerifyEmailOutcome` — [`VerifyEmailOutcome.kt`](./VerifyEmailOutcome.kt)

- **Behavior**: The closed set of outcomes the verify flow renders, one branded
  page each: `Verified`, `InvalidToken`, `Expired`, `AlreadyUsed`,
  `Unavailable`. This is public-web's render view-model, distinct from the
  domain `VerifyEmailResult` (defined in the `auth` module): it keeps the domain
  `User` out of the render layer and adds the `Unavailable` case (a DB fault)
  that the domain type does not carry.
- **Side effects**: None — a pure value type.

### `Route.healthRoute()` — [`Health.kt`](./Health.kt)

- **Behavior**: Registers `GET /healthz`, responding `200` with a constant JSON
  body (`{"status":"ok"}`) and `Content-Type: application/json`. No auth header
  is required.
- **Side effects**: None — touches no backing service. Although the process now
  holds a `Database`, the liveness probe issues no query, so the response
  reflects only whether the process is accepting connections.
- **Error handling**: None — the response is unconditional.
- **Idempotency**: Yes — every request yields the identical response;
  registration itself is not idempotent (a second call duplicates the route).

### `PublicWebConfig.from(config: Config): Result<PublicWebConfig>` — [`PublicWebConfig.kt`](./PublicWebConfig.kt)

- **Behavior**: Parses the bind `host` (String) and `port` (Int) from
  `publicWeb.server`, and the open-in-app `url` (String) from
  `publicWeb.openInApp`, of an already-loaded `Config`, exposing them as
  `PublicWebConfig`. The public-web process shares no configuration object with
  `rest-server` or `admin-server`.
- **Side effects**: None — a pure parse of its input.
- **Error handling**: A missing `publicWeb` section or any missing required key
  yields `Result.failure` carrying the underlying exception; the function never
  throws. `startServer` unwraps it with `getOrThrow()`, so a malformed config
  crashes the process at startup rather than on the first request.
- **Idempotency**: Yes — pure function of its input.

---

## III. Infrastructure & Environment

### HOCON Configuration (module: `public-web`)

Required keys parsed by `PublicWebConfig.from`:

| Key                       | Type   | Description                                                  |
| ------------------------- | ------ | ------------------------------------------------------------ |
| `publicWeb.server.host`   | String | Netty bind host; defaults to loopback (`127.0.0.1`)          |
| `publicWeb.server.port`   | Int    | Netty bind port; defaults to `8082`                          |
| `publicWeb.openInApp.url` | String | iPhone open-in-app deep link shown on the verify-result page |

The `database` config block (loaded from `db.conf`) is parsed separately by
`DatabaseConfig.from` to build the `Database`.

### Environment Variable Overrides

`public-web.conf` binds these HOCON substitutions; each overrides the
corresponding key when set in the process environment:

| Variable                     | Overrides                 |
| ---------------------------- | ------------------------- |
| `PUBLIC_WEB_HOST`            | `publicWeb.server.host`   |
| `PUBLIC_WEB_PORT`            | `publicWeb.server.port`   |
| `PUBLIC_WEB_OPEN_IN_APP_URL` | `publicWeb.openInApp.url` |

### Config Load List

`AppConfig.load("common.conf", "db.conf", "public-web.conf")` — the public-web
process loads these three resources. It reuses the same shared `db.conf`
`database` block that `rest-server` and `admin-server` load, and loads neither
`rest-server.conf` nor `admin-server.conf`.

### Module Dependencies

`public-web` depends on `common` (for `AppConfig`), `db` (for `Database`/
`DatabaseConfig`), and `auth` (for `EmailVerifier`, `DbEmailVerifier`, and the
domain `VerifyEmailResult`). It does not depend on `service`, `rest-server`,
`queue`, `email`, or `chat`.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- **`io.ktor:ktor-server-status-pages`**: Supplies the `StatusPages` plugin used
  for the 404/503 branded error pages.
- **`Database`** (the `db` module): Opened at startup, wrapped in a
  `DbEmailVerifier`, and closed on `ApplicationStopped`. The only request-time
  database access is the `POST /verify-email` token consume.
- The HTML rendering of every page body (home, legal, verify, error) lives in
  the sibling [`render`](./render) package; this layer references it only by the
  `respond*` extensions it calls.

### Logging

`logback.xml` configures a single console appender using the
`logstash-logback-encoder` JSON encoder at root level `INFO`.

---

## IV. History

- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../../../../../../../rfc/61-static-marketing-site.md)
- [x] [RFC-71: Public Web Email-Verification Page](../../../../../../../rfc/71-public-web-email-verification-page.md)
