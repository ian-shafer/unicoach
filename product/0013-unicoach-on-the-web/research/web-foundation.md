# Research: web foundation — SSR + TypeScript approach for "Unicoach on the web"

Brief 0013. Author: research subagent `web-foundation`. Date: 2026-09-02.

## Method

Read-only inspection of the root checkout `/Users/ian/Work/unicoach` (no gradle
runs, no writes). I read every source file in `public-web/` and `web-common/`,
the render/auth/engine layers of `admin-web/`, the session machinery in
`service/` + `db/` + `rest-server/`, the deploy path (`bin/`, `infra/`), and the
TS/Deno surface (`flake.nix`, `deno.json`, `bin/format`). Every load-bearing
claim below cites a file path. Where I state something is ABSENT, that is a
grep/find over the main source trees (excluding `build/`, `.claude/worktrees/`),
which I believe is exhaustive but note as a search result, not a proof.

## 1. public-web today

**Framework: Ktor 3.4.2 on Netty, Kotlin 2.3.20, kotlinx.html DSL SSR.**

- `public-web/build.gradle.kts` — depends on `:common`, `:web-common`, `:db`,
  `:auth`, `:service`; uses `libs.ktor.server.netty`,
  `libs.ktor.server.html.builder`, `libs.ktor.server.status.pages`. Versions in
  `gradle/libs.versions.toml:2-3` (`kotlin = "2.3.20"`, `ktor = "3.4.2"`).
- Boot: `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — loads
  `common.conf` + `db.conf` + `public-web.conf` via `AppConfig`, builds a
  `Database`, wires an in-process `DbEmailVerifier` and
  `ServiceCostReportSource` over `CollegeCostService`. **No auth gate, every
  route public** (stated in the class doc).
- Routes (`public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`): `/healthz`,
  `GET /`, `GET /terms`, `GET /privacy`, `GET|POST /verify-email` (the one
  state-mutating POST — burns a single-use token), `GET /report?token=` (RFC 155
  Family Cost Report), then a catch-all `staticResources("/", "static")` mount.
  `StatusPages` renders branded 404/503; errors are logged with method+path only
  (token privacy).
- Rendering: every page is a `kotlinx.html` block through one shared
  `siteLayout` (`public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`) —
  proper
  `<meta name="viewport" content="width=device-width,
  initial-scale=1">`, one
  external stylesheet `/site.css`, shared header/footer chrome. No page is an
  HTML file on disk.
- CSS: `public-web/src/main/resources/static/site.css` — ~4.5 KB, hand-written,
  CSS custom properties for the palette, `max-width: 760px` centered `main`, and
  **exactly one media query** (`@media (max-width: 640px)`) that stacks the
  cost-report table into per-school cards with `data-label` re-labelling.
- Responsiveness today: honest but minimal. The viewport meta + fluid
  single-column layout means the existing pages degrade fine, but there is one
  breakpoint, no grid system, no landscape/height consideration, and only the
  report page was designed mobile-first.
- Client JS: **none in public-web**. `grep script public-web/src/main/kotlin`
  matches only a prose word in `CostReportPage.kt` (line 1327). No `.js`/`.ts`
  source anywhere in the repo outside `var/` npm caches and `ios-app`.
- Tests: `public-web/src/test/.../` — `testApplication` (ktor-server-test-host)
  route tests asserting status codes, content-type, and substring/structure of
  rendered HTML (e.g. `StaticAssetsTest.kt` pins `/site.css` content type;
  `CostReportPageTest.kt` is page-level). No HTML parser (no jsoup anywhere in
  `gradle/libs.versions.toml` or any `build.gradle.kts`), no browser tests.

## 2. admin-web patterns

admin-web is the proof this stack can do a full form-driven CRUD app:

- **Resource engine**: `admin-web/src/main/kotlin/ed/unicoach/admin/engine/`
  (`AdminResource`, `AdminField` with `FieldType`
  TEXT/INT/BOOL/ENUM/JSON/MULTILINE, `AdminRegistry`, `AdminRouting`) drives ~20
  resources under `admin-web/.../resources/`.
- **Forms**: `admin-web/src/main/kotlin/ed/unicoach/admin/render/FormView.kt` —
  `renderForm(action, editableFields, values, version)` renders typed inputs
  **pre-filled from a `values: Map<String, String>`**, with a hidden `version`
  field for OCC. `AdminRouting.kt` shows the flow: POST → `receiveParameters()`
  → on validation failure **re-render the form with `values = form`** (the
  submitted params, same request — lines 92-141, 188-191); on success
  **POST-redirect-GET** (`respondRedirect`, lines 106, 154, 163, 175). So
  "nothing lost on a form ERROR" already has a precedent — but it is
  request-scoped re-render, not session storage; the values do NOT survive
  navigating away and coming back.
- **Auth**: `admin-web/src/main/kotlin/ed/unicoach/admin/auth/AdminAuth.kt` —
  server-rendered login form, `AuthService.login(...)` (lives in `:service`:
  `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`), sets an
  HttpOnly/SameSite=Strict cookie holding the raw token, and a pipeline
  intercept (`installAdminGate`) that resolves the cookie via
  `TokenHash.fromRawToken` against the DB on every request and stashes the
  `User` in `call.attributes` (`CurrentAdminKey`). This whole shape is directly
  reusable for a student/parent login gate.
- **CSS/JS**: `admin-web/.../render/Layout.kt` inlines a `<style>` constant and
  one inline vanilla-JS click-delegation script (RFC 83 copy-to-clipboard) —
  explicitly noted as permitted only because admin-web has no CSP. Admin-web is
  desktop-only (fixed 200px sidebar, 320px inputs) — a pattern NOT to copy for
  public web.

## 3. web-common and deployment

- `web-common/` is small: structured request logging with secret-query-param
  redaction (`RequestLogging.kt`, `secretQueryParams(...)`) and privacy headers
  (`http/PrivateResponse.kt` — `no-store`, `X-Robots-Tag`, `no-referrer`). It is
  the natural home for anything shared between public-web and a future richer
  public surface (e.g. a session plugin, CSRF helper, form-state helper), since
  both web modules already depend on it.
- Run/deploy: `bin/build-public-web` builds `installDist`; `bin/public-web-up`
  boots the binary via `bin/daemon-up` on `PUBLIC_WEB_PORT` (default 8082) and
  waits for `/healthz`. In prod: one EC2 app instance, systemd unit
  `infra/files/unicoach-public-web.service`, host-routed behind one ALB —
  `infra/locals.tf:25` maps `public-web → app.<domain>` (priority 10),
  `admin-web → admin.<domain>`; rest-server is the listener default at
  `api.<domain>` (`infra/locals.tf:16`, `infra/alb.tf`). `bin/deploy` +
  `infra/files/deploy-on-instance.sh` restart the units. **Consequence: the
  public web app already has a production hostname (`app.<domain>`), TLS, and a
  deploy path — zero new infra needed to grow public-web.**

## 4. Sessions and forms

**There is a real, DB-backed session system, and it is the same one the iOS app
uses.**

- Table: `db/schema/0002.create-sessions.sql` —
  `sessions(id, user_id UUID
  NULL, token_hash BYTEA UNIQUE, user_agent, initial_ip, metadata JSONB NULL,
  expires_at, is_revoked, version...)`.
  Two properties matter for 0013:
  - `user_id` is **nullable — anonymous sessions are schema-legal** and
    `AuthService.resolveSession` explicitly handles them
    (`service/.../auth/AuthService.kt:205-213`), though I found **no code path
    that currently mints an anonymous session** (only `mintSession`/register,
    both with a user).
  - `metadata JSONB` is capped at **2048 bytes**
    (`sessions_metadata_size_check`) and is written only at session creation
    (`db/.../dao/SessionsDao.kt:159-171`) — usable in principle for small form
    state, but the cap and the immutable-ish write path make it a poor fit for
    per-page form data.
- Cookie auth: rest-server (the iOS backend) is **cookie-session** based, not
  bearer: `rest-server/.../auth/CallerResolution.kt` reads
  `request.cookies[sessionConfig.cookieName]` (`UNICOACH_SESSION`,
  `rest-server/src/main/resources/rest-server.conf:32`) and resolves it through
  `AuthService.resolveSession`. Crucially `cookieDomain = ${APP_DOMAIN}`
  (rest-server.conf:36) — the cookie is **domain-wide**, so a browser holding a
  `UNICOACH_SESSION` minted at `api.<domain>` presents it to `app.<domain>` too.
  Public-web can therefore authenticate users with the exact same table,
  `TokenHash`, and `AuthService`, no new auth system.
- **Ktor Sessions plugin: not used anywhere** (`ktor-server-sessions` absent
  from `gradle/libs.versions.toml`; no `Sessions` install in any module).
- Form-data-in-session (Ian's constraint) therefore has three candidate shapes
  in this stack, none built yet:
  1. **New `session_form_state` table** keyed by `sessions.id` (or token hash)
     - form/page key, JSONB value — fits the repo's DB-first grain, works for
       logged-in AND (if minted) anonymous sessions, survives server restarts,
       testable with the existing DAO test pattern. My read: this is the natural
       answer here; the repo has no cache tier (no Redis anywhere in
       `flake.nix`/`infra/`), so "session state" means Postgres.
  2. Ktor Sessions plugin with a cookie-stored encrypted payload — no DB write,
     but 4 KB cookie limits, key management, and it fights the existing
     hand-rolled cookie auth; poor fit.
  3. Reuse `sessions.metadata` — rejected above (2 KB cap, write-once path).
- What exists today for "nothing lost": admin-web's same-request re-render with
  submitted values (`AdminRouting.kt:113-116`) plus PRG. Ian's constraint is
  strictly stronger (survive navigation), which is what forces choice (1).

## 5. TypeScript tooling: Deno

- Deno is already first-class in the dev shell: `flake.nix:33` (`pkgs.deno`),
  and the flake comment says it replaced node/npx. Today it is used **only for
  `deno fmt` on Markdown** (`bin/format:46-52`; `deno.json` is fmt-config only,
  with excludes). There are **zero `.ts` files** in the repo and no bundling,
  linting, type-checking, or JS testing wired anywhere.
- Assessment: Deno is a genuinely sufficient TS toolchain for this brief without
  adding node/npm: `deno check` (types), `deno lint`, `deno fmt` (already the
  repo's formatter — extend `deno.json` fmt to `*.ts`), `deno test` (unit tests
  for client logic), and `deno bundle` / `deno_emit` or `deno compile`-adjacent
  bundling to emit a plain ES module into
  `public-web/src/main/resources/static/` served by the existing
  `staticResources` mount (`Routing.kt`). Missing pieces to build: a
  `bin/build-web-assets` script (bin conventions: getopts, stderr logging —
  `CLAUDE.md` "bin/ conventions"), a Gradle `processResources` dependency or a
  checked-in build artifact policy, wiring `deno lint`/`check` into `bin/format`
  or a sibling gate, and cache-busting (asset hash in the filename) since
  nothing does that today. One honest caveat: `deno bundle` was
  deprecated/reintroduced across Deno versions; I could not verify the pinned
  Deno version's bundler surface without running the dev shell (read-only
  constraint) — the flake pins nixpkgs, so this needs a 2-minute check
  (`nix develop -c deno --version`) before the spec hard-codes the bundling
  command.
- Progressive enhancement fits the existing grain: SSR pages fully functional
  without JS (admin-web's inline-script-with-fallback comment in its `Layout.kt`
  is the existing norm), TS only where interaction demands it — chat view
  (streaming/poll updates, textarea UX) certainly; possibly autosave-to-session
  for long forms.

## 6. Responsive: reaching ~90% of widths/heights

What the stack gives us: one stylesheet, one layout function, viewport meta
already correct (`render/Layout.kt`). Sketch:

- **Fluid-first, few breakpoints**: keep the single-column `max-width` +
  `margin: auto` shell (`site.css` `main` rule) as the base; use CSS grid / flex
  with `minmax()`/`clamp()` so 320–1440 px is covered continuously, and add at
  most 2-3 breakpoints (~640 px exists; add ~960 px for two-column/desktop
  chrome). Height matters mainly for the chat view (dynamic viewport units `dvh`
  for the composer-pinned layout) — nothing today uses vh at all.
- Android phones are the stated primary device; the existing 640 px card-stack
  pattern in `site.css` (report table → cards) is the template for every
  data-dense view.
- **Honest testing options in this repo**: today's tests assert HTML strings via
  `testApplication` — they can pin _mechanisms_ (viewport meta present,
  `data-label` attributes emitted, no fixed-width inline styles, stylesheet
  contains the breakpoint rules) but **cannot honestly assert rendered
  responsiveness**; that requires a real layout engine. Options: (a) accept
  mechanism-pinning tests + a manual device-matrix checklist in the spec —
  cheapest, honest if labelled as such; (b) add Playwright via Deno
  (`npm:playwright` or `astral`) screenshotting N viewport sizes as a non-gating
  check — real evidence, but a new browser dependency in the flake and
  flake-risk in CI. I recommend (a) as the gate plus (b) as an optional tool,
  and recommend the spec say explicitly which claim each test kind can make.

## Recommended architecture (in this repo)

**Grow `public-web`. No new deployable.** It already has the hostname
(`app.<domain>`), ALB rule, systemd unit, daemon scripts, `:service`+`:db`+
`:auth` dependencies, SSR layout, static mount, and test harness. New pieces:

1. **Auth gate for students/parents** in public-web, modelled on
   `AdminAuth.kt`/`CallerResolution.kt` but sharing the `UNICOACH_SESSION`
   cookie contract with rest-server (same `AuthService.resolveSession`).
   Login/register pages SSR'd like admin's.
2. **`session_form_state` table + DAO** (new migration in `db/schema/`), a small
   form-state service, and a `web-common` (or public-web) helper: read-through
   on GET render, write on POST (valid or not), clear on successful final
   submit. PRG everywhere, admin-style re-render with values on error,
   session-persisted so navigation loses nothing.
3. **A public-web form renderer** — take `FormView.kt`'s shape (typed fields,
   values map, hidden version) but restyled responsive; likely a
   `web-common`-level or public-web `render/forms` package, not a dependency on
   admin-web.
4. **Deno TS pipeline**: `web-ts/` (or `public-web/ts/`) source dir,
   `bin/build-web-assets` emitting hashed bundles into the static mount,
   `deno check|lint|test` wired into the existing gates.
5. **CSS growth in `site.css`** (or a split into layered files) — fluid grid
   utilities + the 2-3 breakpoints; add a CSP now, before any inline script
   habit forms (admin-web's own Layout comment flags this).

## Decisions for Ian at gate 1

1. **Session identity for the web**: reuse the rest-server `UNICOACH_SESSION`
   cookie/`AuthService` (one login shared with the API domain) vs. a separate
   public-web session cookie like admin-web's. Reuse is less code and gives
   domain-wide SSO; separate is cleaner blast-radius. (Evidence:
   `rest-server.conf:36` already sets the cookie domain-wide.)
2. **Anonymous sessions**: does form-state-before-login matter? The schema
   supports `user_id NULL` sessions but nothing mints them — deciding yes means
   building an anonymous-session mint on first form touch.
3. **Form state store**: bless the new `session_form_state` Postgres table (my
   recommendation) vs. encrypted cookie state — this fixes size limits, restart
   behaviour, and testability.
4. **TS build contract**: build-at-package-time via `bin/build-web-assets` in
   the Gradle/`bin/build-public-web` path, vs. committing built bundles. Also:
   confirm the pinned Deno's bundling story before the spec names a command.
5. **Responsiveness evidence bar**: accept mechanism-level automated tests +
   manual device matrix as the gate, or invest in browser-based screenshot
   checks (new flake dependency). This decides what "target >=90% of
   widths/heights" means as an acceptance criterion.

## Not verified / open

- Deno version + bundler surface in the pinned flake (needs `nix develop`, which
  I did not run — read-only constraint).
- Whether any RFC already reserves a web-session design (I did not read all 200+
  RFCs; grep for sessions found none web-form related).
- iOS app's exact login flow against rest-server (out of scope here; the cookie
  mechanism cited is from rest-server source, not the iOS client).
