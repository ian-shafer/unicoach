# RFC 71: Public-Web Email-Verification Page

## Executive Summary

The verification email links to `https://unicoach.app/verify-email?token=…`, but
`public-web` has no route there: the link 404s today. This RFC adds the
`/verify-email` landing flow and verifies the token **in-process against the
database**, not by calling `rest-server` over HTTP.

The flow is server-side and two-step: `GET /verify-email?token=…` renders a
confirm page (a `POST /verify-email` form with the token in a hidden field) and
performs no verification; submitting it posts back to `public-web`, which
consumes the token through a shared `EmailVerifier` and renders the result page
(`Verified`, `InvalidToken`, `Expired`, `AlreadyUsed`, or a catch-all
`Unavailable`). The side-effect-free `GET` preserves the single-use-token
guarantee against email-scanner prefetch. `Verified` and `AlreadyUsed` carry an
iPhone-only "open in app" link.

The verify logic is **consumed, not duplicated**.
`EmailVerificationService.verify` is extracted from the `service` module into a
new lightweight `auth` module (`common` + `db` only) as `EmailVerifier` /
`DbEmailVerifier`, so both `rest-server` and `public-web` call the same
single-use consume. `public-web` gains a `Database` (reusing the shared
`db.conf` block) and a dependency on `:db` + `:auth`, and **drops** the outbound
HTTP client, client key, and JSON-parsing dependencies the network approach
required.

This removes a needless `public-web` → `rest-server` network hop: first-party
services compose in-process through shared modules, reserving HTTP clients for
third-party APIs.

Out of scope: iOS handling of the open-in-app link; migrating `service`'s other
auth classes into the `auth` module.

## Detailed Design

### New `auth` module

A new Gradle module `auth` (package `ed.unicoach.auth`), depending only on
`common` and `db`, holds the email-verification consume so any service can call
it in-process. It is the intended eventual home for the auth logic currently in
`service`; this RFC moves only the verify path, leaving `service`'s other
`ed.unicoach.auth` classes in place (an interim split package, accepted).

```kotlin
package ed.unicoach.auth

interface EmailVerifier {
  suspend fun verify(rawToken: String): Result<VerifyEmailResult>
}

class DbEmailVerifier(
  private val database: Database,
) : EmailVerifier

sealed interface VerifyEmailResult {
  data class Success(val user: User) : VerifyEmailResult
  data object InvalidToken : VerifyEmailResult
  data object Expired : VerifyEmailResult
  data object AlreadyConsumed : VerifyEmailResult
}
```

`DbEmailVerifier.verify` is the current `EmailVerificationService.verify` body
unchanged: one transaction performing a compare-and-swap consume
(`VerificationTokensDao.consume`), then `UsersDao.markEmailVerified`, then
`VerificationTokensDao.consumeAllForUser`; a zero-row consume is classified via
`VerificationTokensDao.findByTokenHash` into `InvalidToken` / `Expired` /
`AlreadyConsumed`. It returns `Result.failure` on a DB fault and does not throw.
`verify` reads `database` only — none of `EmailService`, `TokenGenerator`, or
`EmailVerificationConfig` — which is why the move sheds the `email` / `queue` /
`chat` transitive graph. `VerifyEmailResult` moves from `service` to this module
with its declaration unchanged (same package name, new module).

### `service` and `rest-server` changes

`EmailVerificationService` (in `service`) loses `verify` and
`classifyFailedConsume`, retaining `issueToken`, `sendVerificationEmail`, and
`resend`; it no longer references `VerifyEmailResult`. Its constructor and
module dependencies are unchanged, so its other construction sites
(admin-server, tests) are untouched.

`rest-server` constructs a `DbEmailVerifier(database)` in `Application` and
threads it through `configureRouting` into `AuthRouteHandler` alongside the
existing `emailVerificationService`. The verify route (`AuthRoutes.kt:213`)
calls `emailVerifier.verify`; `resend` (`AuthRoutes.kt:287`) still calls
`emailVerificationService.resend`. `rest-server` adds an `:auth` module
dependency; the `VerifyEmailResult` import repoints to the new module (same
fully-qualified name). The `POST /api/v1/auth/verify-email` endpoint, its
request/response shape, and the RFC-54 gate are unchanged. Because rest-server's
tests boot the real server (`startServer`) and seed the DB directly rather than
constructing handlers, the wiring change is internal to production code and no
rest-server test changes.

### Routes (`public-web`)

Two routes registered in `installPublicWebRouting`, before the catch-all static
mount, alongside the existing page routes:

- `GET /verify-email` — reads the `token` query parameter. Non-blank renders the
  confirm page (a `POST /verify-email` form with the token in a hidden input);
  absent or blank renders the `InvalidToken` result page directly. **No
  verification, no state change.** This preserves the `POST`-only single-use
  guarantee against scanner prefetch: a scanner that prefetches the mailed `GET`
  link gets only the confirm form and cannot burn the token, because consumption
  requires the `POST`. A non-blank-but-invalid token (garbage, malformed, or
  already-consumed) intentionally reaches the confirm page and is rejected only
  on the `POST` — distinguishing valid from invalid on the `GET` would require
  the very consume this step forbids. Accepted UX trade-off.
- `POST /verify-email` — reads the `token` form field. Blank renders
  `InvalidToken` with no DB call. Otherwise calls `EmailVerifier.verify(token)`,
  maps the result to a `VerifyEmailOutcome`, and renders the result page.

Both render through `siteLayout`. The token rides in the form body, never in a
URL. `POST /verify-email` is the one state-mutating route and carries no CSRF
token and no `Origin`/`Referer` check: the single-use token is itself the
unguessable secret, so a blind cross-site `POST` cannot target a victim's token.

### Verification call and outcome mapping (`public-web`)

`public-web` calls `EmailVerifier.verify` in-process and maps the domain
`Result<VerifyEmailResult>` to its own closed render view-model
`VerifyEmailOutcome`, keeping the domain `User` out of the render layer and
giving the `Unavailable` case (which the domain type does not carry) a home:

```kotlin
sealed interface VerifyEmailOutcome {
  data object Verified : VerifyEmailOutcome
  data object InvalidToken : VerifyEmailOutcome
  data object Expired : VerifyEmailOutcome
  data object AlreadyUsed : VerifyEmailOutcome
  data object Unavailable : VerifyEmailOutcome
}
```

| `Result<VerifyEmailResult>` | `VerifyEmailOutcome` |
| --------------------------- | -------------------- |
| `success(Success(user))`    | `Verified`           |
| `success(InvalidToken)`     | `InvalidToken`       |
| `success(Expired)`          | `Expired`            |
| `success(AlreadyConsumed)`  | `AlreadyUsed`        |
| `failure` (DB fault)        | `Unavailable`        |

The mapping is an exhaustive `when` over the sealed `VerifyEmailResult` plus the
`Result` fold — compiler-checked, no string parsing. `VerifyEmailOutcome` is
retained from the prior design; the `VerifyEmailClient` interface and
`KtorVerifyEmailClient` are removed. `Unavailable` now originates from a
`Result.failure`, not an HTTP status fold.

### Result pages (`web.render` package)

One file, `VerifyEmailPage.kt`, exposing renderers that each write one HTML
response through `siteLayout`, reusing existing chrome classes with no new
styling (so `static/site.css` is unchanged):

- `suspend fun ApplicationCall.respondVerifyEmailConfirm(token: String)` — the
  confirm page: a section with a `method="post" action="/verify-email"` form
  containing `<input type="hidden" name="token">` and a submit button.
- `suspend fun ApplicationCall.respondVerifyEmailResult(outcome: VerifyEmailOutcome, openInAppUrl: String, isIPhone: Boolean)`
  — renders the page for the outcome, each with a distinct heading marker and
  copy (`Verified` success; `InvalidToken` request-a-new-link; `Expired`
  link-expired; `AlreadyUsed` already-verified; `Unavailable` try-again). On
  `Verified` and `AlreadyUsed`, when `isIPhone` is true, the body includes an
  `<a href="{openInAppUrl}">Open in app</a>` link; `openInAppUrl` is rendered
  verbatim as the opaque `href` (escaped as an attribute value, otherwise
  unvalidated). The link is inert markup here; iOS handling is out of scope.

iPhone detection is a server-side check of whether the request `User-Agent`
contains `iPhone` — no client JS. The boolean and the configured URL are passed
into the renderer from the route handler. iPad and desktop user-agents see the
plain page with no link.

### Configuration

`PublicWebConfig` keeps `host`/`port`, keeps `openInAppUrl`, and **drops** the
`apiBaseUrl` / `apiClientKey` fields:

```kotlin
data class PublicWebConfig(
  val host: String,
  val port: Int,
  val openInAppUrl: String,
)
```

DB settings are **not** added to `PublicWebConfig`. `public-web` reuses the
shared `database` block defined once in `db/src/main/resources/db.conf` — the
same block `rest-server` and `admin-server` load — by adding `"db.conf"` to its
`AppConfig.load(...)` list and parsing it with `DatabaseConfig.from(config)`,
exactly as the other servers do. This adds no new DB config keys or env vars to
`public-web` (the `DATABASE_*` overrides come from `db.conf`). `public-web.conf`
removes the `publicWeb.api` block and keeps `publicWeb.server` and
`publicWeb.openInApp.url` (env `PUBLIC_WEB_OPEN_IN_APP_URL`). A missing required
key surfaces as `Result.failure`, crashing at startup as today.

### Dependency injection / lifecycle

`startServer` loads `common.conf` + `db.conf` + `public-web.conf`, builds
`Database(DatabaseConfig.from(config).getOrThrow())`, wraps it in
`DbEmailVerifier`, and threads the `EmailVerifier` plus `openInAppUrl` into
`publicWebModule`. The `Database` is closed on server stop via
`monitor.subscribe(ApplicationStopped) { database.close() }` in the
`embeddedServer(Netty, …) { … }` module lambda — replacing the prior
`HttpClient` close, and matching the `rest-server` / `admin-server` precedent
where that hook closes the `Database`.
`publicWebModule(emailVerifier, openInAppUrl)` forwards both to
`installPublicWebRouting`. Both parameters are required (no null-object
verifier), so a wiring omission fails at compile time. The prior design's
`HttpClient(CIO)`, `HttpTimeout`, and the `CONNECT_TIMEOUT_MS` /
`SOCKET_TIMEOUT_MS` constants are removed.

### Security

`public-web` gains DB credentials carrying the shared application role's full
table access — the project defines no per-service DB roles. This is accepted:
`public-web` is intended to grow into a feature peer of `rest-server`, so a
verify-scoped least-privilege role would be churned wider with each new feature;
a scoped role is deliberately deferred to a future infra RFC. The verify routes
stay public and unauthenticated — a user arriving from an email link has no
session, and the single-use token is the credential — and CSRF stays omitted for
that reason. The internet-facing entry point is identical to the prior design
(the same public `POST /verify-email`); only the internal hop, and the RFC-54
client-key that fronted it, are removed.

### Error Handling / Edge Cases

- **Scanner prefetch** — covered by the side-effect-free `GET`: consumption
  requires the `POST`, which a prefetch does not issue.
- **Missing/blank token** — `GET` without a usable `token` renders
  `InvalidToken` directly; `POST` with a blank `token` renders `InvalidToken`
  with no DB call.
- **DB fault / unexpected failure** — `DbEmailVerifier.verify` returns
  `Result.failure`, which the handler maps to `Unavailable`; the page always
  renders and no stack trace surfaces. (An uncaught throwable would otherwise
  hit the module's existing `503` status page; `Unavailable` is the intended
  branded path.)
- **All outcomes render `200`** — every confirm and result page (including
  `InvalidToken`, `Expired`, `AlreadyUsed`, `Unavailable`) returns HTTP `200`;
  the branded body carries the outcome.
- **Double submit** — submitting the confirm form twice yields `Verified` then
  `AlreadyUsed` (the consume burns the token on first submit); both render.
- **Token exposure** — the token is already in the mailed `GET` URL; the
  two-step flow neither worsens nor improves its exposure, and it never reaches
  a URL on the `POST` (form body only).

### Dependencies

- New module `auth`: depends on `:common`, `:db`.
- `public-web/build.gradle.kts` adds `implementation(project(":db"))` and
  `implementation(project(":auth"))`; **removes** `ktor-client-core`,
  `ktor-client-cio`, `kotlinx-serialization-json`, and the `ktor-client-mock`
  test dependency.
- `rest-server/build.gradle.kts` adds `implementation(project(":auth"))`.
- `settings.gradle.kts` adds `include("auth")`.
- No new third-party libraries or catalog entries. `service`'s module
  dependencies are unchanged.

## Tests

### `auth` — `DbEmailVerifierTest` (new, real DB)

The verify-outcome matrix, relocated from `EmailVerificationServiceTest` and run
against a real Postgres via the `bin/test` harness. The `verification_tokens`
table stores only the SHA-256 hash, so each case inserts a known raw token (and
its hash) for a registered user, then calls `verify`:

- a freshly-issued, unexpired token → `Success` (and `users.email_verified_at`
  is set; sibling tokens are burned).
- a never-issued raw token → `InvalidToken`.
- an expired token → `Expired`.
- an already-consumed token (verify twice) → `Success` then `AlreadyConsumed`.

### `public-web` — `VerifyEmailRoutingTest` (new, `testApplication`)

A real-DB wiring anchor plus a hand-written fake `EmailVerifier` (a real class
with scripted results, not a mock) for the outcome matrix:

- **Real anchor:** wire the route with a real `DbEmailVerifier` over a real
  `Database`, seed a valid token for a registered user, `POST` it → `200`,
  success heading marker. Proves the route consumes through a real verifier and
  DB.
- `GET /verify-email?token=abc` → `200`; confirm-page heading marker, a
  `method="post"` / `action="/verify-email"` form, a hidden `token` input with
  value `abc`. The fake records **zero** calls.
- `GET /verify-email` with no `token` → `InvalidToken` page; fake not called.
- `POST /verify-email` (fake → `Success`) → success marker; `iPhone` UA shows
  the configured open-in-app URL, non-iPhone UA does not.
- `POST /verify-email` (fake → `AlreadyConsumed`) → already-verified marker;
  iPhone UA shows the open-in-app link.
- `POST /verify-email` (fake → `InvalidToken` / `Expired`) → respective markers;
  no open-in-app link even for an iPhone UA.
- `POST /verify-email` (fake → `failure`) → `Unavailable` marker; no link.
- `POST /verify-email` with a blank `token` → `InvalidToken`; fake records zero
  calls.
- Every confirm and result page renders through `siteLayout` (chrome +
  stylesheet markers present).

### `public-web` — `PublicWebConfigTest` (extend)

- `from` parses `publicWeb.openInApp.url` into `openInAppUrl`.
- a missing `publicWeb.openInApp.url` yields `Result.failure`.
- the removed `publicWeb.api` block is no longer parsed (no `apiBaseUrl` /
  `apiClientKey` fields).

### `service` — `EmailVerificationServiceTest` (trim)

The four `verify` cases are removed (relocated to `DbEmailVerifierTest`); the
`issueToken` / `sendVerificationEmail` / `resend` cases remain unchanged.

### `public-web` — existing route tests (compile-only update)

`HomePageTest`, `LegalPagesTest`, `StaticAssetsTest`, `HealthTest`,
`ErrorPagesTest` are updated to call `publicWebModule(fakeVerifier, testUrl)` (a
fake `EmailVerifier` in place of the prior fake client); their assertions are
unchanged. Seven `publicWebModule()` call sites across these five files
(`LegalPagesTest` and `ErrorPagesTest` call it twice) must each be updated.

## Implementation Plan

Run all commands inside the Nix dev shell. Verify DB-backed tests with
`nix develop -c bin/test --force <module>` (plain runs may be all-cache no-ops).

1. **Create the `auth` module and move the verify path into it.** Add
   `include("auth")` to `settings.gradle.kts` and create `auth/build.gradle.kts`
   (`implementation(project(":common"))`, `implementation(project(":db"))`).
   Move `VerifyEmailResult` into `auth` (package unchanged). Add `EmailVerifier`
   and `DbEmailVerifier`, lifting `verify` + `classifyFailedConsume` verbatim
   from `EmailVerificationService`. Remove `verify` + `classifyFailedConsume`
   (and the now-unused `VerifyEmailResult` / `NotFoundException` references)
   from `EmailVerificationService`.
   - Verify:
     `nix develop -c ./gradlew :auth:compileKotlin :service:compileKotlin`.

2. **`DbEmailVerifierTest`.** Add the real-DB suite (the four relocated verify
   cases); remove the four `verify` cases from `EmailVerificationServiceTest`.
   - Verify:
     `nix develop -c bin/test --force auth --tests "ed.unicoach.auth.DbEmailVerifierTest"`
     and `nix develop -c bin/test --force service`.

3. **rest-server extraction wiring.** Add `implementation(project(":auth"))` to
   `rest-server/build.gradle.kts`. Construct `DbEmailVerifier(database)` in
   `Application.kt`, add an `emailVerifier: EmailVerifier` parameter to
   `configureRouting` and `AuthRouteHandler`, repoint the verify call
   (`AuthRoutes.kt:213`) to `emailVerifier.verify`, and update the
   `VerifyEmailResult` import.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin` and
     `nix develop -c bin/test --force rest-server --tests "ed.unicoach.rest.EmailVerificationRoutingTest"`.

4. **public-web swap to in-process verify.** In `public-web/build.gradle.kts`
   add `:db` + `:auth` and remove `ktor-client-core` / `ktor-client-cio` /
   `kotlinx-serialization-json` / `ktor-client-mock`. Drop `apiBaseUrl` /
   `apiClientKey` from `PublicWebConfig`; remove the `publicWeb.api` block from
   `public-web.conf`. In `Application.kt` load `"db.conf"`, build `Database` +
   `DbEmailVerifier`, thread the `EmailVerifier` + `openInAppUrl` into
   `publicWebModule`, and close the `Database` on `ApplicationStopped` (removing
   the `HttpClient`, `HttpTimeout`, and timeout constants). Change
   `installPublicWebRouting` / `publicWebModule` to take `EmailVerifier`; in the
   `POST` handler call `verify` and map `Result<VerifyEmailResult>` to
   `VerifyEmailOutcome`. Extract `VerifyEmailOutcome` into its own file; delete
   `VerifyEmailClient.kt`, `KtorVerifyEmailClient.kt`, and
   `VerifyEmailClientTest.kt`. Keep `render/VerifyEmailPage.kt` (it already
   renders from `VerifyEmailOutcome`).
   - Verify: `nix develop -c ./gradlew :public-web:compileKotlin`.

5. **`PublicWebConfigTest`.** Update to the `openInApp.url` parse + required-key
   failure cases; drop the removed `api` cases.
   - Verify:
     `nix develop -c bin/test --force public-web --tests "ed.unicoach.web.PublicWebConfigTest"`.

6. **Update existing public-web route tests.** Change all seven
   `publicWebModule()` call sites across `HomePageTest`, `LegalPagesTest` (two),
   `StaticAssetsTest`, `HealthTest`, `ErrorPagesTest` (two) to pass a fake
   `EmailVerifier` and test URL.
   - Verify: `nix develop -c ./gradlew :public-web:compileTestKotlin`.

7. **`VerifyEmailRoutingTest`.** Add the `testApplication` suite: the real-DB
   wiring anchor plus the fake-`EmailVerifier` outcome/iPhone/blank-token/chrome
   matrix.
   - Verify: `nix develop -c bin/test --force public-web`.

8. **Full gate.** `nix develop -c bin/test check` (tests + ktlint).

## Files Modified

Created:

- `auth/build.gradle.kts`
- `auth/src/main/kotlin/ed/unicoach/auth/EmailVerifier.kt`
- `auth/src/main/kotlin/ed/unicoach/auth/DbEmailVerifier.kt`
- `auth/src/main/kotlin/ed/unicoach/auth/VerifyEmailResult.kt` (moved from
  `service`)
- `auth/src/test/kotlin/ed/unicoach/auth/DbEmailVerifierTest.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/VerifyEmailOutcome.kt` (extracted
  from the deleted `VerifyEmailClient.kt`)
- `public-web/src/main/kotlin/ed/unicoach/web/render/VerifyEmailPage.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/VerifyEmailRoutingTest.kt`

Modified:

- `settings.gradle.kts`
- `service/src/main/kotlin/ed/unicoach/auth/EmailVerificationService.kt`
- `service/src/test/kotlin/ed/unicoach/auth/EmailVerificationServiceTest.kt`
- `rest-server/build.gradle.kts`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`
- `public-web/build.gradle.kts`
- `public-web/src/main/resources/public-web.conf`
- `public-web/src/main/kotlin/ed/unicoach/web/PublicWebConfig.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/PublicWebConfigTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/HomePageTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/LegalPagesTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/StaticAssetsTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/HealthTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/ErrorPagesTest.kt`

Deleted:

- `service/src/main/kotlin/ed/unicoach/auth/VerifyEmailResult.kt` (moved to
  `auth`)
- `public-web/src/main/kotlin/ed/unicoach/web/VerifyEmailClient.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/KtorVerifyEmailClient.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/VerifyEmailClientTest.kt`
