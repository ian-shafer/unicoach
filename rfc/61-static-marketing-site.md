# RFC 61: Public Web Module (Dynamic HTML via Shared Layout)

## Executive Summary

This RFC introduces `public-web`, a new internet-facing Gradle module that
serves the project's public web presence: brand/marketing pages, Terms of
Service, and Privacy Policy. It is a Ktor + Netty embedded server structured
like the `admin-server` module but public (no client-key gate) and
dependency-light (no database, no `:service`).

The brand/home page copy reflects Unicoach's end-to-end college-coaching
positioning — a college coach guiding high-school students through the whole
path to college, no single stage singled out — captured in a new top-level
`PRODUCT.md` this RFC also introduces.

Every HTML page renders dynamically through a single shared `Layout`: the
brand/home page, the legal pages (Terms of Service, Privacy Policy), and the
branded `404`/`503` error pages. Each body is authored with the `kotlinx.html`
DSL in a `render/` package and injected into `Layout`'s content slot, so every
page inherits the same header/footer chrome. The legal copy is static, but the
serving mechanism is dynamic. A static mount serves only chrome-less assets (the
shared stylesheet and images) and never HTML.

The `Layout` content slot is the seam a future email-verification flow will plug
its result pages into. That feature — token issuance, the verification endpoint,
the token table, and its database wiring — is explicitly **out of scope**.

AWS exposure is also deferred, matching `admin-server`: the server binds a
configurable host/port (default `127.0.0.1:8082`) and ships `bin/` daemon
scripts, but no OpenTofu, ALB rule, ACM certificate, or Route53 record is added,
and the process is registered in neither `bin/daemon-status` nor `bin/deploy`. A
follow-up infra RFC routes the apex `unicoachapp.com` here.

## Detailed Design

### Module and process

`public-web` is a new Gradle module mirroring `admin-server`'s structure: a
Ktor + Netty embedded server whose `application` plugin `mainClass` is
`ed.unicoach.web.ApplicationKt`. It depends on **`:common` only** (for
`AppConfig`); it has no `:db` or `:service` dependency because this RFC wires no
database. It is launched and supervised by the same `bin/daemon-up` machinery as
the other daemons.

The server binds to a configurable host/port defaulting to `127.0.0.1:8082`.
Unlike `rest-server`, it does **not** install the RFC-54 client-key gate: the
public web presence is reachable by any browser and by email clients following a
link, so no client key is expected or checked. Unlike `admin-server`, it
installs no authentication gate at all — every route is public.

`startServer()` mirrors `admin-server`'s, minus the database and auth
construction:

```
AppConfig.load("common.conf", "public-web.conf")   // classpath + local overlay
PublicWebConfig.from(config)
embeddedServer(Netty, port = config.port, host = config.host) { publicWebModule(config) }
```

It loads neither `db.conf` nor any DAO; it constructs no `Database`.

### Configuration

New `public-web/src/main/resources/public-web.conf`:

```
publicWeb {
  server {
    host = "127.0.0.1"
    host = ${?PUBLIC_WEB_HOST}
    port = 8082
    port = ${?PUBLIC_WEB_PORT}
  }
}
```

`PublicWebConfig.from(config): Result<PublicWebConfig>` parses these fail-fast
at startup, mirroring `AdminConfig.from` / `DatabaseConfig.from`. It exposes
`host: String` and `port: Int`.

### Routing and render paths

The module registers a single routing table. Every HTML page is an explicit
dynamic `GET` route; the static mount serves only assets.

| Route          | Path    | Render path  | Renders / does                                          |
| -------------- | ------- | ------------ | ------------------------------------------------------- |
| `GET /`        | dynamic | kotlinx.html | Brand/home page via the shared `Layout`                 |
| `GET /terms`   | dynamic | kotlinx.html | Terms of Service via the shared `Layout`                |
| `GET /privacy` | dynamic | kotlinx.html | Privacy Policy via the shared `Layout`                  |
| `GET /healthz` | dynamic | text         | Liveness `200` (`{"status":"ok"}`), no DB               |
| static mount   | assets  | static file  | Serves chrome-less assets (`site.css`, images); no HTML |

**Dynamic generation.** A `render/` package provides the `kotlinx.html` skeleton
shared by all pages and one body renderer per page:

- `Layout` — the shared HTML skeleton: `<head>` linking `/site.css`, a top-level
  site header and footer, and a content slot into which a page body is injected.
  Declarative signature only; no markup body is specified here.
- `HomePage` — the brand/awareness landing body. Its copy, and any tagline or
  brand chrome it carries, reflect the broad end-to-end positioning recorded in
  `PRODUCT.md` (see Brand copy below), without singling out any one stage of the
  journey.
- `TermsPage` — the Terms of Service body (static copy).
- `PrivacyPage` — the Privacy Policy body (static copy).
- `ErrorPages` — the `404` (not found) and `503` (service unavailable) bodies.

Each renderer produces only a page body; the body is injected into `Layout`'s
content slot, so every page — home, legal, and error — carries the identical
header/footer chrome and links the same `/site.css`. No page is authored as an
HTML-fragment file, and there is no raw-HTML injection seam; all markup is the
`kotlinx.html` DSL.

**Static serving.** A `staticResources("/", "static")` mount serves the packaged
`static/` resource directory, which holds chrome-less assets only — the shared
`site.css` and any images — and no HTML. `GET /site.css` → `static/site.css`.
The guarantee that `/`, `/terms`, `/privacy`, and `/healthz` render dynamically
is **route precedence**: each is a registered routing node matched before the
catch-all mount is consulted for that exact path, so the mount never handles
them. Ktor's `index` parameter defaults to `"index.html"`, but that default is
inert here precisely because the explicit `get("/")` wins; as defense-in-depth,
`static/` ships no `index.html` and no HTML at all, so even a fallthrough to the
mount can neither serve a directory index nor emit an HTML body. The
`extensions("html")` fallback and an `index = null` guard are therefore
unnecessary and omitted.

### Brand copy and `PRODUCT.md`

`PRODUCT.md` is a new top-level Markdown file that is the single canonical
source of truth for what Unicoach is and who it serves. It carries the
end-to-end positioning: Unicoach is a college coach for high-school students
that guides them through the entire path to college — exploring colleges,
deciding which colleges are the best fit, standardized testing (SAT/ACT) and
test prep, application strategy, the applications themselves, and college
budgeting and costs. The framing is the breadth of that journey, with no single
stage singled out. It is prose documentation; it defines no code, schema, or
interface, and the `public-web` module does not read it at runtime.

The top-level `CLAUDE.md` gains a short `## Product` section that points to
`PRODUCT.md` and states that all public-facing and brand copy must reflect it.
This binds the source of truth to the brand surface: the `HomePage` body and any
tagline or brand chrome (header/footer wordmark text) must reflect the broad,
end-to-end `PRODUCT.md` positioning rather than presenting Unicoach as
specializing in any one stage. The legal pages remain placeholder legal text —
`TermsPage` and `PrivacyPage` carry generic Terms/Privacy boilerplate and are
unaffected by this positioning, since the home page is the brand-awareness
surface that carries the framing.

### Data models

None. This RFC adds no schema, no migration, and no DAO; it constructs no
`Database`. A future email-verification RFC will introduce the database wiring
(a `Database` constructed at startup and read through DAOs) that the dynamic
render path will consume; the render seam established here is the integration
point, but nothing in this RFC touches the database.

### API contracts

The module serves `text/html` for all page routes (home, legal, and error
pages), `text/css` for the stylesheet, and `application/json` for `/healthz`.
There is no JSON API, no request body parsing, and no authentication. All routes
are `GET`. Unknown paths and uncaught exceptions resolve to the dynamic error
pages below.

### Error handling and edge cases

- **Unknown path** — any unmatched route renders the dynamic `404` page via a
  `StatusPages` `status` handler for `HttpStatusCode.NotFound`. This is a new
  pattern: `admin-server` has no such handler because its 404s originate from
  explicit `respondNotFound()` calls on DAO misses, whereas this module performs
  no lookups, so unmatched routes must be caught structurally at the status
  layer.
- **Uncaught exception** — a `StatusPages` `exception<Throwable>` handler
  renders the dynamic `503` page, mirroring `admin-server`'s
  `exception<Throwable>` catch-all so an unexpected failure returns branded HTML
  rather than a stack trace.
- **Health is dependency-free** — `/healthz` never touches any backing service
  (there is none), so an operator probe reflects only whether the process is
  accepting connections.

### Dependencies

- No new third-party dependency. All required artifacts already exist in
  `gradle/libs.versions.toml`: `ktor-server-core`, `ktor-server-netty`,
  `ktor-server-html-builder`, `ktor-server-status-pages`, `logback-classic`,
  `logstash-logback-encoder` (runtime); `kotlin-test-junit5`,
  `ktor-server-test-host`, `ktor-client-cio` (test). The `kotlinx.html` DSL
  arrives transitively via `ktor-server-html-builder`; there is no separate
  `kotlinx-html` catalog entry. Static serving needs no extra artifact — it is
  part of `ktor-server-core`.
- Internal module dependency: `:common` only.
- No dependency on `:db`, `:service`, `:rest-server`, `:queue`, `:email`, or
  `:chat`.
- `logback.xml` mirrors `admin-server`'s: the `logstash-logback-encoder` JSON
  encoder on the console appender. No new logging dependency.

## Tests

`public-web` module, run with `nix develop -c bin/test public-web --force`,
using Ktor `testApplication` and a CIO client. No database is started or
required for these tests.

- **Health.** `GET /healthz` → `200` with body `{"status":"ok"}` and
  `application/json`; no auth header required.
- **Dynamic home.** `GET /` → `200`, `text/html`, body contains a
  positioning-accurate brand marker proving the `HomePage` body rendered — a
  college-coach / end-to-end-journey phrase drawn from `PRODUCT.md` — the shared
  header/footer chrome marker proving it rendered through `Layout`, and links
  `/site.css`.
- **Dynamic Terms.** `GET /terms` → `200`, `text/html`, body contains a marker
  from `TermsPage` and the shared header/footer chrome marker (proves the legal
  copy renders dynamically through `Layout`, not from a static file).
- **Dynamic Privacy.** `GET /privacy` → `200`, `text/html`, body contains a
  marker from `PrivacyPage` and the shared header/footer chrome marker.
- **Stylesheet.** `GET /site.css` → `200` with `text/css` content type (proves
  the static asset mount serves the chrome-less stylesheet).
- **Dynamic 404.** `GET /does-not-exist` → `404`, `text/html`, body contains the
  branded not-found marker and the shared chrome marker (proves the
  `StatusPages` not-found handler renders through `Layout`, not a default Ktor
  body).
- **Dynamic 503.** The test registers a test-only route that throws inside its
  `testApplication` block (after `publicWebModule` installs `StatusPages`), then
  asserts `GET` of that route yields `503`, `text/html`, with the branded
  service-unavailable marker and the shared chrome marker, proving the
  `exception<Throwable>` handler renders through `Layout`.
- **`PublicWebConfig.from`.** Parses `publicWeb.server.host`/`port` from a test
  `Config`; a missing required key yields `Result.failure` (fail-fast),
  mirroring the existing config-parser tests.

`bin` daemon scripts are verified manually per the Implementation Plan
(`build-public-web` → `public-web-up` → `public-web-check` → `public-web-bounce`
→ `public-web-down`; `public-web-wait-for-health` is exercised transitively as
an `-up` helper); they reuse the shared `daemon-*` machinery already covered by
`scripts-tests`.

## Implementation Plan

Each step is independently compilable/testable. Verification commands assume the
nix dev shell (`nix develop -c ...`).

1. **Product positioning docs.**
   - Create top-level `PRODUCT.md` carrying the end-to-end positioning per the
     Brand copy design (college coach for high-school students; the full path to
     college, no single stage singled out).
   - Add a `## Product` section to top-level `CLAUDE.md` pointing to
     `PRODUCT.md` and stating that all public-facing / brand copy must reflect
     it.
   - Verify: `test -f PRODUCT.md`; `grep -q "path to college" PRODUCT.md`;
     `grep -q "PRODUCT.md" CLAUDE.md`.
2. **Module scaffold + config + health.**
   - `include("public-web")` in `settings.gradle.kts`.
   - Create `public-web/build.gradle.kts` (plugins: `kotlin.jvm`, `ktor`,
     `application`; `mainClass = ed.unicoach.web.ApplicationKt`; deps:
     `:common`, `ktor-server-core`, `ktor-server-netty`,
     `ktor-server-status-pages`, `ktor-server-html-builder`, `logback-classic`,
     `logstash-logback-encoder`; test deps: `kotlin-test-junit5`,
     `ktor-server-test-host`, `ktor-client-cio`; a
     `tasks.withType<Test> { useJUnitPlatform() }` block mirroring
     `admin-server`'s, without which the JUnit5 tests do not run).
   - Create `public-web/src/main/resources/public-web.conf`, `logback.xml`.
   - Create `PublicWebConfig.kt`, `Application.kt` (config load, Netty bind,
     `publicWebModule`), and `Health.kt` (`GET /healthz`).
   - Verify: `nix develop -c ./gradlew :public-web:compileKotlin`;
     `nix develop -c bin/build-public-web` (after step 6 adds the script, or run
     `./gradlew :public-web:installDist`).
3. **Render layer (dynamic HTML).**
   - Create `render/Layout.kt` (shared skeleton with content slot),
     `render/HomePage.kt`, `render/TermsPage.kt`, `render/PrivacyPage.kt`, and
     `render/ErrorPages.kt` (`404`/`503`) — each a `kotlinx.html` body injected
     into `Layout`'s content slot. `HomePage` copy and any tagline/brand chrome
     reflect the broad `PRODUCT.md` positioning (step 1) without singling out
     any one stage; `TermsPage`/`PrivacyPage` stay placeholder legal text.
   - Create `Routing.kt` registering the dynamic `GET /`, `GET /terms`, and
     `GET /privacy` routes, the `StatusPages` install (not-found + exception
     handlers), and the static asset mount.
   - Verify: `nix develop -c ./gradlew :public-web:compileKotlin`.
4. **Static assets.**
   - Create `static/site.css` (and any images) — chrome-less assets only, no
     HTML. The shared chrome is supplied by `Layout`, not by static files.
   - Verify: `nix develop -c ./gradlew :public-web:compileKotlin`.
5. **Tests.**
   - Add `HealthTest`, `HomePageTest`, `LegalPagesTest` (dynamic `/terms` and
     `/privacy` through `Layout`), `StaticAssetsTest` (`/site.css`),
     `ErrorPagesTest`, and `PublicWebConfigTest` per the Tests section.
   - Verify: `nix develop -c bin/test public-web --force`.
6. **`bin` scripts + module-list updates.**
   - Create `bin/build-public-web`, `bin/public-web-up`, `bin/public-web-down`,
     `bin/public-web-check`, `bin/public-web-wait-for-health`,
     `bin/public-web-bounce` (mirror the `admin-server-*` scripts; default port
     `8082`). The `-bounce` script invokes `public-web-down` then
     `public-web-up`, matching `rest-server-bounce`.
   - Backfill `bin/admin-server-bounce` (`admin-server-down` then
     `admin-server-up`), absent from the implemented `admin-server` daemon
     scripts though every other daemon has one. Co-located here because it edits
     the same `bin/` daemon family this RFC already touches and is a one-line
     mirror of the new `public-web-bounce`.
   - Add `public-web` to `bin/test` `MODULES` and the help text; append
     `build-public-web` last in `bin/build` `MODULES` and extend its
     dependency-order comment with `-> public-web`. `public-web` depends only on
     `:common`, so its position in the sequential build is unconstrained; last
     is simplest.
   - Verify: `nix develop -c bin/build`; `nix develop -c bin/build-public-web`
     then `bin/public-web-up`, `bin/public-web-check` (expect healthy),
     `bin/public-web-bounce` (expect healthy again), `bin/public-web-down`;
     `nix develop -c bin/test public-web --force`.

## Files Modified

### Created — top-level docs

- `PRODUCT.md` (canonical product positioning, source of truth for brand copy)

### Created — `public-web` module

- `public-web/build.gradle.kts`
- `public-web/src/main/resources/public-web.conf`
- `public-web/src/main/resources/logback.xml`
- `public-web/src/main/resources/static/site.css`
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/PublicWebConfig.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/Health.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/HomePage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/TermsPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/PrivacyPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/ErrorPages.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/HealthTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/HomePageTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/LegalPagesTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/StaticAssetsTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/ErrorPagesTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/PublicWebConfigTest.kt`

### Created — `bin` scripts

- `bin/build-public-web`
- `bin/public-web-up`
- `bin/public-web-down`
- `bin/public-web-check`
- `bin/public-web-wait-for-health`
- `bin/public-web-bounce`
- `bin/admin-server-bounce` (backfill: missing from the implemented
  `admin-server` scripts)

### Modified — build, tooling, and docs

- `CLAUDE.md` (add `## Product` section pointing to `PRODUCT.md`)
- `settings.gradle.kts` (`include("public-web")`)
- `bin/test` (`MODULES` + help text)
- `bin/build` (`MODULES` + dependency-order comment)
