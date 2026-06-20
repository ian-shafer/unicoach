# SPEC.md — `public-web/src/main/kotlin/ed/unicoach/web`

## I. Overview

The process-bootstrap and routing layer of `public-web`, the internet-facing
Ktor/Netty server that serves Unicoach's public web presence: the brand/home
page, the legal pages (Terms of Service, Privacy Policy), and branded error
pages. It owns the `public-web` entrypoint (config load, Netty bind, module
assembly), the typed `PublicWebConfig`, the dependency-free `/healthz` liveness
route, and the single routing table that maps every page route through the
shared HTML renderers in the sibling [`render`](./render) package. No
authentication or client-key gate fronts any route; every route is public.

---

## II. Behavioral Contracts

### `startServer(wait: Boolean = true): EmbeddedServer<*, *>` — [`Application.kt`](./Application.kt)

- **Behavior**: Boots the server. Loads configuration, parses `PublicWebConfig`,
  binds Netty to the configured host/port, installs the routing module, starts
  the listener non-blocking, then synchronously resolves the bound connectors
  before returning the running server handle.
- **Side effects**: Reads configuration from the classpath plus the local
  overlay (`AppConfig.load("common.conf", "public-web.conf")`); binds and starts
  a Netty listener on `PublicWebConfig.host`/`port`. Constructs no `Database`
  and opens no connection pool — the module wires no persistence.
- **Error handling**: A config-load or parse failure propagates as an uncaught
  exception via `getOrThrow()`, crashing the process before the listener binds.
- **Blocking**: When `wait = true` (the `main` path) the call blocks the calling
  thread indefinitely after the listener binds; when `wait = false` (the test
  path) it returns the running handle once Netty has bound.
- **Idempotency**: No — each call binds a fresh server instance.

### `main()` — [`Application.kt`](./Application.kt)

- **Behavior**: The module entrypoint (`ed.unicoach.web.ApplicationKt`). Calls
  `startServer(wait = true)` and does not return under normal operation.
- **Side effects**: As `startServer`.

### `Application.publicWebModule()` — [`Application.kt`](./Application.kt)

- **Behavior**: The Ktor application module. Delegates to
  `installPublicWebRouting()` — it installs the status-pages plugin and
  registers the routing table.
- **Inputs**: None — the module takes no collaborators, parses no config, and
  constructs no IO-bound singleton.
- **Idempotency**: No — Ktor plugin and route installation duplicates on a
  second invocation against the same `Application`.

### `Application.installPublicWebRouting()` — [`Routing.kt`](./Routing.kt)

- **Behavior**: Installs the single routing table and the error-page handling.
  Installs `StatusPages` with a `status` handler for `NotFound` (renders the
  branded 404 through the shared layout) and an `exception<Throwable>` catch-all
  (renders the branded 503). In the routing block, registers `GET /healthz`,
  `GET /` (home), `GET /terms`, `GET /privacy`, and a
  `staticResources("/",
  "static")` mount. The explicit page routes are
  registered before the static mount, so the mount never handles them; it serves
  only the chrome-less assets packaged under `static/` (the stylesheet and any
  images), never HTML.
- **Side effects**: Mutates the `Application`'s plugin and routing trees. No DB
  or network access at install time.
- **Error handling**: An unmatched route resolves to the `NotFound` status
  handler (404); an uncaught `Throwable` from any handler resolves to the
  `exception` handler (503). Both render branded HTML rather than a default Ktor
  body or a stack trace. The 404 is caught structurally at the status layer
  because this module performs no lookups (no handler issues an explicit
  not-found).
- **Idempotency**: No — installation duplicates on a second call.

### `Route.healthRoute()` — [`Health.kt`](./Health.kt)

- **Behavior**: Registers `GET /healthz`, responding `200` with a constant JSON
  body (`{"status":"ok"}`) and `Content-Type: application/json`. No auth header
  is required.
- **Side effects**: None — touches no backing service (there is none), so the
  response reflects only whether the process is accepting connections.
- **Error handling**: None — the response is unconditional.
- **Idempotency**: Yes — every request yields the identical response;
  registration itself is not idempotent (a second call duplicates the route).

### `PublicWebConfig.from(config: Config): Result<PublicWebConfig>` — [`PublicWebConfig.kt`](./PublicWebConfig.kt)

- **Behavior**: Parses the bind `host` (String) and `port` (Int) from the
  `publicWeb.server` section of an already-loaded `Config`, exposing them as
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

Required keys in `public-web.conf`, parsed by `PublicWebConfig.from`:

| Key                     | Type   | Description                                         |
| ----------------------- | ------ | --------------------------------------------------- |
| `publicWeb.server.host` | String | Netty bind host; defaults to loopback (`127.0.0.1`) |
| `publicWeb.server.port` | Int    | Netty bind port; defaults to `8082`                 |

### Environment Variable Overrides

`public-web.conf` binds these HOCON substitutions; each overrides the
corresponding key when set in the process environment:

| Variable          | Overrides               |
| ----------------- | ----------------------- |
| `PUBLIC_WEB_HOST` | `publicWeb.server.host` |
| `PUBLIC_WEB_PORT` | `publicWeb.server.port` |

### Config Load List

`AppConfig.load("common.conf", "public-web.conf")` — the public-web process
loads only these two resources. It loads neither `db.conf` nor
`rest-server.conf` nor `admin-server.conf`.

### Module Dependencies

`public-web` depends on `common` only (for `AppConfig`). It does not depend on
`db`, `service`, `rest-server`, `queue`, `email`, or `chat` — this module wires
no database.

### Runtime Dependencies

- **Netty**: Embedded HTTP server engine.
- **`io.ktor:ktor-server-status-pages`**: Supplies the `StatusPages` plugin used
  for the 404/503 branded error pages.
- The HTML rendering of every page body (home, legal, error) lives in the
  sibling [`render`](./render) package; this layer references it only by the
  `respond*` extensions it calls.

### Logging

`logback.xml` configures a single console appender using the
`logstash-logback-encoder` JSON encoder at root level `INFO`.

---

## IV. History

- [x] [RFC-61: Public Web Module (Dynamic HTML via Shared Layout)](../../../../../../../rfc/61-static-marketing-site.md)
