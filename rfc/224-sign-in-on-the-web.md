# RFC 224 — Sign in on the web

**Slice:** `web/02/sign-in` (brief 0013, _Unicoach on the web_, handle `web`).
**Base:** `main@8bca6fc0`. **Builds on:** RFC 210 (`web/01/the-shell`).

## Summary

A student or parent signs in to unicoach in a browser and stays signed in. They
can register, verify their email, resend the verification email, sign in, sign
out, and change their email address. The browser session is the SAME session the
iOS app uses: one `UNICOACH_SESSION` cookie, one `sessions` row, one
`AuthService`. This is the gate every later web slice sits behind.

No new table. No change to the session metadata schema. No DDL of any kind.

## Context and binding decisions

From brief 0013, already committed and not re-litigated here:

- **D2 — one login, same origin.** `public-web` shares the `UNICOACH_SESSION`
  cookie contract with `rest-server`; the browser talks only to the web origin.
- **D5 — no anonymous sessions.** Nothing mints a session for a signed-out
  visitor, so there is no session to hold a pre-login form draft.
- **D7 — email+password only here.** Google and Apple are `web/11`; password
  reset is `web/12`.
- **D12 (standing) — wherever a session exists, every form stores its data in
  it.** Sign-in and registration are pre-session forms, so the rule does not
  reach them, and their re-render with typed values is ordinary form handling
  and is NOT session storage. This RFC says so in prose and in a test, and no
  code comment claims otherwise.
- **D13 — the web calls the service layer in-process.** A business rule written
  in the web tier is mis-placed; `:service` is its single home.
- **D21 — the web cookie gets a real lifetime rather than dying with the
  browser.** Its 7-day figure is OVERRIDDEN to 30 days by Ian at this run's
  gate; see D-D.
- **D22 — CSRF tokens are scheduled for `web/05`.** This RFC re-opens that
  timing deliberately; see decision D-C.

From the slice text, verbatim:

> Register, log in, log out, resend verification, change email all work; an
> unverified user is held at the verification screen exactly as the API's gate
> requires, and the page says what to do next.
>
> A validation error re-renders the form with the user's typed values. This does
> NOT satisfy D4 and the code comments must not claim it does (D12).
>
> Signing in on the web and then using the iOS app does not require signing in
> again, and the reverse holds.
>
> **Door:** a "Sign in" link in the shell's navigation.

## What DISCOVER established, re-verified against the tree at `8bca6fc0`

- `:public-web` already depends on `:common`, `:web-common`, `:db`, `:auth`,
  `:service` (`public-web/build.gradle.kts:11-19`). `AuthService` lives in
  `:service` (`service/src/main/kotlin/ed/unicoach/auth/AuthService.kt:39`), so
  it is importable with no new module edge. `QueueService` is in `:queue`
  (`queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt:29`) and IS a new
  edge — registration must enqueue a verification email.
- `:public-web` cannot import `:rest-server`. `SessionConfig`
  (`rest-server/.../auth/SessionConfig.kt`, 32 lines), `resolveCaller`
  (`rest-server/.../auth/CallerResolution.kt:41-54`), `ErrorCode` and
  `ErrorResponse` are all unreachable, and there is no reverse edge.
- `public-web` installs no auth gate today; every route is public
  (`public-web/src/main/kotlin/ed/unicoach/web/Application.kt:19-33`).
- The cookie is written in exactly two helpers in `rest-server`
  (`AuthRoutes.kt:34-47` set, `:49-60` clear) plus one duplicate clear
  (`StudentRoutes.kt:162-168`), and in two more in `admin-web`
  (`AdminAuth.kt:45-59`, `:61-72`). The SET path in `rest-server` sets **no
  `Max-Age`**, so it is a browser-session cookie while the row lives 7 days
  (`rest-server/src/main/resources/rest-server.conf:41`).
- `SameSite=Strict` is a hard-coded `extensions` entry at all five sites and is
  not configurable anywhere (`SessionConfig.kt:6-11`, `rest-server.conf:31-43`).
- There is **no CSRF machinery in the repo at all** — no token utility, no
  double-submit cookie, no Origin or Referer check, no CORS plugin. A repo-wide
  case-insensitive search for `csrf` over `*.kt`/`*.conf` returns nothing.
- There is **no rate limiting or lockout on login** anywhere. Argon2
  verification is deliberately expensive, so an unthrottled public login form is
  both a credential-stuffing surface and a CPU one.
- The content-security policy is ENFORCING with no `unsafe-inline`
  (`web-common/.../http/ContentSecurityPolicy.kt:30-33`), and
  `form-action
  'self'` already permits a same-origin form POST. No inline
  `style=`, no inline `<script>`, no inline event handler may be written.
- `siteLayout` takes a typed `ShellWidth` (`render/Layout.kt:36-53, 75-103`).
  `ShellWidth.WIDE` has no production caller yet and its own KDoc names
  `web/02`'s signed-in shell as its first user.
- `publicWeb.urlBase` — the ONE public-web origin (RFC 155 D-J) — already exists
  in `service.conf:431-432`, driven by `PUBLIC_WEB_URL_BASE`. `admin-web`
  already loads `service.conf` (`admin-web/.../Application.kt:52`), so
  `public-web` may too.

## Decisions taken here

### D-A — session resolution is NEW code in `public-web`, not a moved type

`public-web` gets its own small `ed.unicoach.web.auth` package. The
`rest-server` types are NOT moved into `:web-common`.

Measured, not asserted. Moving `SessionConfig` touches ~27 files: 16 main and 11
test files import it, with 98 reference lines in `rest-server/src/main` and 29
fully-qualified `ed.unicoach.rest.auth.SessionConfig` spellings that a rename
must catch as text. Moving `resolveCaller` is worse in kind than in size: it
depends on `AuthService`, `Session`, `TokenHash` and `User`, so `:web-common` —
a module that today depends on NO project module
(`web-common/build.gradle.kts:6-8`) — would gain edges to `:service` and `:db`,
and `admin-web` and `rest-server` would inherit them.

Against that, the thing actually needed is three lines: read the cookie, hash
it, call `authService.resolveSession`. `admin-web` already does exactly this
without `:rest-server` (`AdminAuth.kt:157-195`). The resolver is ~25 lines with
its type and KDoc.

What is genuinely shared between the two servers is the cookie's VALUE contract
— name, domain, secure flag, lifetime — and D-B gives that one home. A shared
Kotlin type would not have made the cookie match; identical configuration does.

### D-B — one home for the cookie contract

Two moves, both small, both so that `public-web` and `rest-server` cannot drift
into two different cookies while claiming to share one session:

1. **The `session { }` config block moves to
   `common/src/main/resources/session.conf`**, loaded by `rest-server` and by
   `public-web`. It is removed from `rest-server.conf`. The block is unchanged,
   including its required `${APP_DOMAIN}` and `${SESSION_COOKIE_SECURE}`
   substitutions, so every environment's dotenv keeps working untouched. D2's
   claim stops depending on two files agreeing.
2. **One cookie writer lands in `:web-common`** —
   `web-common/src/main/kotlin/ed/unicoach/web/common/http/SessionCookie.kt` —
   holding the attribute set in a single place: `path="/"`, `httpOnly=true`,
   `SameSite=Strict`, with name, domain, secure and `maxAge` as parameters.
   `public-web` uses it, and `rest-server`'s three sites adopt it.

The second move is the one that pays for itself at `web/05`: D22 changes the
`SameSite` value, and today that literal is copied at five sites with no test
naming it. After this, the copies in the two servers that share the session
cookie are one. `admin-web`'s own `admin_session` cookie is left alone — a
different cookie with a different lifetime, and dragging it in would widen this
slice for no shared-session benefit.

### D-C — CSRF protection comes forward into this slice, as a same-origin check

**This is the decision the slice was told to make consciously rather than ship
silently.** D22 puts CSRF tokens in `web/05`. The sign-in and registration POSTs
land here, in `web/02`.

The facts, stated plainly:

`SameSite` governs when a cookie is SENT. It never governs whether a response's
`Set-Cookie` is APPLIED. So for the three POSTs this slice adds:

| POST         | Protected by `SameSite=Strict` today?                                                                                                                                                                                                                               |
| ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| sign-in      | **No.** There is no inbound cookie to withhold. A cross-site form POST still gets its `Set-Cookie` stored, so login-CSRF works fully — an attacker can sign a victim's browser into the ATTACKER's account, and then read back everything the victim types into it. |
| register     | **No.** Same shape, plus forced account creation and a forced verification email from our domain.                                                                                                                                                                   |
| sign-out     | **Half.** The server-side revoke needs the cookie, so Strict blocks the revocation. But the cookie-clearing `Set-Cookie` is emitted unconditionally, so forced sign-out works — a nuisance on its own, and a reliable setup step for login-CSRF.                    |
| change email | **Yes** — it reads the session cookie, which Strict withholds. Subject to the caveats below.                                                                                                                                                                        |

Caveats even where Strict does work: the cookie is domain-wide
(`cookieDomain = ${APP_DOMAIN}`), so any same-site page defeats it; ~3-4% of
browsers in `caniuse`'s table ignore `SameSite`; and there is no second line.

So "SameSite is sufficient until `web/05`" is **not** a claim this RFC could
honestly make. It is precisely wrong for the two POSTs the slice introduces. The
alternative — writing it down as a known hole and shipping a public sign-in form
with it — is a real option, and it is rejected because the hole is on the login
page of a product for families, and `web/05` is three slices away.

**Chosen: an Origin/Referer check on every state-changing request in
`public-web`**, installed once as an interceptor (`SameOriginGuard`). It is ~50
lines, needs no session, no token store, no secret and no schema, protects all
four POSTs against a pure cross-site attacker, and **composes with** rather than
pre-empts `web/05`'s synchronizer tokens — D22 still lands as written, with this
as the outer layer.

Policy, stated exactly because the edges are where these go wrong:

- It checks every method EXCEPT the two safe ones, `GET` and `HEAD`. Enumerating
  the methods it checks would be an allowlist written backwards: any method
  added later would bypass CSRF until someone remembered to list it. Stating
  `SAFE_METHODS = {Get, Head}` and checking everything else is the arm that
  cannot silently forget.
- `Origin` has three states and the guard treats them as three. A present,
  parseable `Origin` is compared to `publicWeb.urlBase`'s scheme+host+port. A
  present-but-unparseable `Origin` (the opaque `Origin: null` of a sandboxed
  iframe, or a malformed value) is **refused** — it is never demoted to the
  weaker Referer check. Only an ABSENT `Origin` falls back to the origin of
  `Referer`. If both are absent, the request is **refused** — the strict arm,
  chosen deliberately.
- A refusal is a 403 HTML page in the site shell that says what happened and
  links back to the form; it is not a blank error.

The cost of the strict arm, named: a browser or privacy tool that strips BOTH
headers cannot sign in. Every current browser sends `Origin` on cross-origin and
same-origin form POSTs, so the affected population is the same old-browser tail
that already ignores `SameSite`. The alternative default — allow when both are
missing — is the arm that quietly re-opens the hole, because "no headers" is
exactly what an attacker would send if it were accepted.

`POST /verify-email` (the existing token-authorised route) is included, since
the guard is a blanket rule; the token remains its actual authorisation.

### D-D — a web session lives 30 days, and the cookie carries `Max-Age` to match

**This overrides gate-2 decision D21 on Ian's direct call at this run's approval
gate (2026-09-16).** D21 said "give the web cookie the same 7-day life the
server already grants the session". The RFC presented the consequence — there is
no sliding refresh in the web tier, so a web session would end 7 days after
sign-in rather than 7 days after last use — and Ian answered: _"Let's make this
30 days instead of 7."_

So a session minted by a web sign-in or registration is created with a
**30-day** expiration, and the cookie's `Max-Age` is the same 30 days. Row and
cookie expire together; a cookie that outlives its row would show a signed-in
shell to a signed-out user, and the reverse silently signs a family out early.

**This is scoped to the web, and does NOT change the iOS session life.**
`rest-server` keeps `expiration = "7d"` and keeps minting rows with it. Two
reasons: the instruction was about the web session, and lengthening the life of
every iOS session widens the window in which a stolen token is still good — a
security change nobody asked for, taken as a side effect of a UX fix. A family
signing in on both surfaces simply has two rows with two lifetimes, which is
already true of every other attribute of the two sessions.

Consequence for D-B: `session.conf` is the one home for the cookie's IDENTITY —
name, domain, secure flag — which is what "one login across web and app"
actually requires. The LIFETIME is per-client and is stated per server:
`session.expiration = "7d"` for `rest-server`,
`publicWeb.session.expiration =
"30d"` (overridable by
`PUBLIC_WEB_SESSION_EXPIRATION`) for `public-web`. The split is deliberate, and
both keys are asserted in config tests so neither can drift unnoticed.

`rest-server` keeps its no-`Max-Age` behaviour unchanged: its client is the iOS
app, which does not depend on browser cookie persistence.

**Known limit, stated rather than hidden:** the sliding-expiry refresh is
`rest-server`-only (`SessionExpiryPlugin.kt:24-77`, which enqueues a job) and is
NOT added here. A web session therefore ends 30 days after it was minted, not 30
days after it was last used. The 30-day figure is what makes that acceptable
rather than a defect; adding sliding refresh to the web tier stays out of this
slice.

### D-E — change-email is a signed-in form, and D12 is deferred for it, explicitly

D12 binds every form on a surface where a session exists. Change-email
qualifies: the user is signed in. The form-state foundation it would write into
is `web/05`.

This RFC therefore takes a NAMED deviation rather than a silent one: the
change-email form re-renders with typed values on error and stores no draft, and
`web/05` must sweep it in when the rail exists. The code comment says that, in
those words, and does not claim the re-render satisfies D4. The alternative was
dropping change-email from the slice, which its acceptance criteria forbid. See
the gate question.

### D-F — no login throttle: raised at the gate, and declined

There is no rate limiting or lockout on login anywhere in the repo, and this
slice puts the first public, unauthenticated, Argon2-backed POST on the
internet. That was put to Ian at this run's approval gate as a risk, with the
note that a throttle is a new subsystem with its own storage and its own product
question (what a locked-out family is told). He answered: _"I'm not concerned."_

So none is built and none is queued. It is recorded here as **considered and
declined**, not as an oversight and not as an open item — the distinction
matters to whoever reads this file next.

## Detailed Design

### Configuration

`common/src/main/resources/session.conf` (new, moved verbatim from
`rest-server.conf:31-43`):

```hocon
session {
    # The name is CONTRACT, like Path/HttpOnly/SameSite: an env override that
    # one server sets and the other misses would silently split the shared
    # session, which is the drift this file exists to prevent.
    cookieName = "UNICOACH_SESSION"
    cookieDomain = ${APP_DOMAIN}
    cookieSecure = ${SESSION_COOKIE_SECURE}
    expiration = "7d"
    expiration = ${?REST_SERVER_SESSION_EXPIRATION}
}
```

`expiration` carries the `REST_SERVER_SESSION_EXPIRATION` override now rather
than a shared-looking `SESSION_EXPIRATION`, because the shared-looking key moved
only one of the two servers — `public-web` reads its lifetime elsewhere — and a
lever that moves only one side should not read as if it moved both.

`public-web.conf` adds the one key whose value differs per client (D-D):

```hocon
publicWeb {
  session {
    expiration = "30d"
    expiration = ${?PUBLIC_WEB_SESSION_EXPIRATION}
  }
}
```

`public-web` loads
`"common.conf", "db.conf", "session.conf", "service.conf",
"public-web.conf"`.
`service.conf` arrives for two values it already owns: `publicWeb.urlBase` (the
origin the guard compares against, RFC 155 D-J) and the `emailVerification`
block `EmailVerificationService` needs. `admin-web` is the precedent for a web
module loading it.

`PublicWebConfig` gains `session: WebSessionConfig` (`cookieName`,
`cookieDomain`, `cookieSecure` read from the shared `session` block;
`expiration: Duration` read from `publicWeb.session.expiration`) and
`origin: SiteOrigin`, both parsed with the existing `Result`-returning
`from(Config)` shape — the origin is parsed at config time, so an unparseable
value fails at boot with its own name rather than at first request. The KDoc
states why the lifetime is read from a different block than the identity.

### `ed.unicoach.web.auth` (new package in `public-web`)

`WebSession.kt`:

```kotlin
data class WebCaller(val tokenHash: TokenHash, val session: Session, val user: User)

suspend fun ApplicationCall.resolveWebCaller(
  authService: AuthService,
  config: WebSessionConfig,
): WebCaller?          // cookie -> TokenHash.fromRawToken -> authService.resolveSession,
                       // cached on call.attributes for the request
fun ApplicationCall.setWebSessionCookie(config: WebSessionConfig, token: String)
fun ApplicationCall.clearWebSessionCookie(config: WebSessionConfig)
```

Both cookie functions delegate to `:web-common`'s single writer (D-B). The
resolver asserts identity only — verification is the gate's business, exactly as
`rest-server`'s KDoc says of `resolveCaller`.

`WebAuthGate.kt` — a `ApplicationCallPipeline.Plugins` interceptor, modelled on
`installAdminGate` but redirect-based, because this is a browser UI and not a
JSON API. It is an OPTIMISATION, not the authority, and its KDoc says so in
those words.

The authority is the route wrapper each gated route is registered with:
`getVerified` / `postVerified` (and their signed-in twins) declare the access
class where the route is declared, and enforce it after Ktor has resolved which
route this is. That split exists because of a finding the review turned up,
which this RFC records rather than hides: the gate classifies
`call.request.path()` — the raw bytes — while Ktor routes on empty segments
dropped and every segment percent-decoded. So `//account` and `/%61ccount`
reached the `/account` handler while the gate read them as OPEN, and an
unverified family rendered the account page; the POST twin actually changed
their address. No amount of path-normalising in the gate closes that gap,
because the only normaliser that agrees with the router is the router. The gate
still buys the cheap early redirect for the common spelling; a path it misses is
refused a moment later by its own route, and a path it over-matches is a path
that would have been refused anyway.

Access classes:

- **Open** — everything not named below. The closed set is what is enumerable:
  this is a public marketing site that has served open pages since before it had
  a session, so a path the list forgets is a page that keeps working, not a page
  that silently 404s to every visitor. The open set today is `/`, `/terms`,
  `/privacy`, `/guides/**`, `/report`, `/healthz`, `/site.css` and the static
  mount, `/sign-in`, `/register`, `/verify-email`.
- **Signed-in** — `/sign-out`, `/verify-email/resend`. No session -> redirect to
  `/sign-in`.
- **Verified** — `/account` and `/account/email`. A signed-in but unverified
  caller is redirected to `/verify-email`, which renders the hold screen. This
  mirrors what the API's gate refuses (`EmailVerificationGate.kt:32-51`) in the
  medium a browser understands: the API answers 403 `email_not_verified`; the
  web shows the screen that says what to do next and offers the resend button.
- Signed-in callers making a **GET** of `/`, `/sign-in` or `/register` are
  redirected to `/account`. GET only: bouncing a POST would swallow a form
  submission and answer it with a page the user did not ask for. — the root
  routing the iOS app already does, and the slice's first-session test ("lands
  in the app, not on a marketing page") as far as it can be satisfied before
  `web/04` exists.

`SameOriginGuard.kt` — the interceptor described in D-C.

### Wiring (`Application.kt`)

Following `admin-web/.../Application.kt:70-92` exactly: `Argon2Hasher`,
`TokenGenerator`, `QueueService(database)`,
`EmailVerificationConfig.from(config)`, `EmailVerificationService(...)`, then
`AuthService(...)` with both SSO slots wired fail-closed as
`GoogleIdTokenVerifier(DisabledIdTokenVerifier)` /
`AppleIdTokenVerifier(DisabledIdTokenVerifier)` — `public-web` serves no SSO
route until `web/11`, and a fail-closed verifier refuses every token rather than
silently accepting a stub. `:queue` is added to `public-web/build.gradle.kts`.

The test seam is the existing `Application.testPublicWebModule(...)`
(`FakeEmailVerifier.kt:38-45`), whose KDoc already says a new collaborator is
one edit there. Three defaulted parameters join it — `authService`,
`emailVerificationService` and `publicWebConfig` — and they default to the REAL
services over the test database, not to fakes. A fake `AuthService` would be a
second implementation of the login rules that D13 says must have exactly one
home.

**The cost, stated rather than discovered later:** `public-web`'s whole test
suite now needs the test database. `bin/test` provides one on every run, so
nothing breaks, but a `public-web` test is no longer a pure rendering test.

### Routes

| Method + path               | Class     | What it does                                                                                                                                |
| --------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /sign-in`              | open      | the form; signed-in -> `/account`                                                                                                           |
| `POST /sign-in`             | open      | `AuthService.login`; success -> set cookie, 303 to `/account`; any failure -> re-render at 401 with the typed email and ONE generic message |
| `GET /register`             | open      | the form; signed-in -> `/account`                                                                                                           |
| `POST /register`            | open      | `AuthService.register`; success -> set cookie, 303 to `/verify-email`                                                                       |
| `POST /sign-out`            | signed-in | `AuthService.logout`, clear cookie, 303 to `/`                                                                                              |
| `GET /verify-email`         | open      | with `?token` -> today's verify; without, signed-in -> hold screen; without, signed-out -> `/sign-in`                                       |
| `POST /verify-email`        | open      | unchanged (token)                                                                                                                           |
| `POST /verify-email/resend` | signed-in | `EmailVerificationService.resend`; always the same confirmation, verified or not (no state leak, mirroring the API's 204)                   |
| `GET /account`              | verified  | the signed-in home, `ShellWidth.WIDE`                                                                                                       |
| `GET /account/email`        | verified  | change-email form                                                                                                                           |
| `POST /account/email`       | verified  | `AuthService.changeEmail`; success -> 303 back with a confirmation                                                                          |

Post-redirect-get everywhere: every successful POST answers `303 See Other`.

A redirect cannot carry a rendered message, so the confirmation after a
successful POST rides as `?notice=<key>` and the page looks the key up in a
CLOSED set. The registry is the direct consequence of post-redirect-get, and the
closed set is the security half of it: a `?notice=` that carried the MESSAGE
would let `/account?notice=<attacker+text>` render an attacker's sentence inside
our shell, over our domain.

`AuthService.login` returns four distinct failure cases (`LoginResult.kt:6-21`).
All four collapse to one message, "Email or password is incorrect" — the same
rule `rest-server` (`AuthRoutes.kt:197-216`) and `admin-web` (`AdminAuth.kt:43`)
already follow. The web tier makes no authentication judgement of its own (D13):
it renders what `:service` returned.

Session fixation is already handled in `:service` — `login` passes
`oldCookieToken` to `mintSession`, which revokes the old row before inserting
(`AuthService.kt:611-645`). The web passes the existing cookie, as `rest-server`
does and `admin-web` does not.

### Shell and CSS

`siteLayout` gains a fourth parameter, `nav: ShellNav = ShellNav.SignedOut`, and
`siteHeader` renders from it. `ShellNav.SignedIn` adds "Account" and a sign-out
form; `SignedOut` adds the **door**: `<a href="/sign-in">Sign in</a>` in
`nav.site-nav`. Default-valued, so every page that existed before renders the
bytes it did — the same discipline `noindex` and `width` already follow, and the
reason there is still ONE layout function.

Sign-out is a `POST` (it changes state and must be guarded), so the header
carries a small form with a submit button styled as a link — not an anchor.

`site.css` gains form component rules in its section 5. They are SCOPED to the
auth forms (`.auth-form input`, `.field label`, and so on) rather than written
as bare element selectors: a bare `input` rule would restyle every future form
in the site from a slice that only designed four of them. No inline styles
anywhere — the CSP forbids them and `ShellMechanismTest`'s source sweep enforces
it. Controls respect the existing `--tap-target` floor.

## Files Modified

New:

- `rfc/224-sign-in-on-the-web.md`
- `common/src/main/resources/session.conf`
- `web-common/src/main/kotlin/ed/unicoach/web/common/http/SessionCookie.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/auth/WebSession.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/auth/WebAuthGate.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/auth/SameOriginGuard.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/SignInPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/RegisterPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/AccountPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/ChangeEmailPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/FormControls.kt` — one
  labelled-control helper, so no form can ship a control with no label, and so a
  password field has no `value` parameter that could re-render a typed password
  into the HTML
- `public-web/src/test/kotlin/ed/unicoach/web/SignInRoutingTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/RegistrationRoutingTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/SessionCookieTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/SameOriginGuardTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/WebAuthGateTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/ChangeEmailRoutingTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/ShellNavTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/WebAuthTestSupport.kt`
- `web-common/src/test/kotlin/ed/unicoach/web/common/http/SessionCookieTest.kt`

Modified:

- `public-web/build.gradle.kts` — `:queue`
- `public-web/src/main/resources/public-web.conf` — the session block's IDENTITY
  arrives via `session.conf`; this file adds only
  `publicWeb.session.expiration`, the one key whose value differs per client
  (D-D)
- `public-web/src/main/kotlin/ed/unicoach/web/PublicWebConfig.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/VerifyEmailPage.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/ErrorPages.kt` — the 403
- `public-web/src/main/resources/static/site.css`
- `public-web/src/test/kotlin/ed/unicoach/web/FakeEmailVerifier.kt` — test seam
- `rest-server/src/main/resources/rest-server.conf` — `session` block removed
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — load list
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` — adopt
  the shared writer
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt` —
  adopt the shared writer
- `rest-server/src/test/kotlin/ed/unicoach/rest/auth/SessionConfigTest.kt` — its
  packaged-conf assertions now resolve `rest-server.conf` with `session.conf`
  beneath it, through one `packagedConf()` helper, and the assertions themselves
  are UNCHANGED: that is the proof that step 1 preserved behaviour
- the ten sibling `rest-server` test files whose `AppConfig.load(...)` lists
  must gain `"session.conf"` before they can build a `SessionConfig`

## Implementation Plan

1. `session.conf` + the `rest-server` load-list change; confirm
   `SessionConfigTest` still resolves the same values, with its assertions
   unchanged. No behaviour change.
2. `:web-common` `SessionCookie.kt` with its own test; adopt at the three
   `rest-server` sites. Still no behaviour change — assert the emitted
   `Set-Cookie` header is byte-identical.
3. `public-web` config + wiring (`:queue`, `AuthService`, test seam).
4. `WebSession.kt`, then `SameOriginGuard.kt`, then `WebAuthGate.kt`, each with
   its tests, in that order — the guard is independent of the pages, so it lands
   before anything can be posted to.
5. Pages and routes: sign-in, register, sign-out, verification hold + resend,
   account home, change email.
6. `Layout.kt` `ShellNav` + the door; `site.css` form rules.
7. Device-matrix check per D19, on the six sizes, for the four new rendered
   surfaces (sign-in, register, account home, change email) plus the changed
   verification screen. This is a HUMAN check — brief 0013 D9 is explicit that
   no automated test may claim the site renders correctly — and it is recorded
   as a checklist in the run report, exactly as RFC 210 recorded its own. If it
   has not been run when the run reports, the report says so by name; it is
   never implied to have passed.

## Tests

DB-backed, in `public-web`'s existing JUnit5 + `testApplication` harness
(`VerifyEmailRoutingTest.kt:121-137` is the pattern).

**Session sharing — the slice's headline claim.** A session minted through
`AuthService` exactly as `rest-server` mints it resolves on a web page request
carrying that cookie; and a session minted by a web sign-in resolves through
`AuthService.resolveSession`. One row, both directions. This is what "signing in
on the web and then using the iOS app does not require signing in again" means
in a test.

**Cookie attributes.** `Set-Cookie` on sign-in carries `Max-Age=2592000` — 30
days, D-D's override of D21 — and the minted row's `expires_at` is ~30 days out,
not 7; a test asserts BOTH, because the whole point is that they agree. A
sibling test asserts `rest-server` still mints at 7 days, so the split stays
deliberate.

`Set-Cookie` also carries `HttpOnly`, `Path=/`, `SameSite=Strict`, the
configured name and domain, and `Secure` per config. Sign-out emits `Max-Age=0`.

**Sign-in.** Success sets the cookie and 303s to `/account`. Each of the four
`LoginResult` failure branches re-renders at 401 with the SAME message and with
the typed email present. A test asserts the four responses are identical, so a
future edit cannot leak which failed.

**Registration.** Success mints a session, sets the cookie, 303s to
`/verify-email`, and enqueues exactly one `SEND_EMAIL` job.

**The D12 honesty test.** After a failed sign-in and a failed registration, the
typed values are in the re-rendered HTML and **no session row exists**; after a
failed change-email, the session's `metadata` column is still `NULL`. The
re-render is not session storage, and this is the test that stops a later reader
believing it was.

**Verification gate.** A signed-in unverified caller requesting `/account` is
redirected to `/verify-email` and sees the hold screen naming the address the
mail went to and offering resend. Resend answers identically whether the user
was verified or not.

**Same-origin guard.** A POST with a matching `Origin` proceeds; a foreign
`Origin` gets 403; a missing `Origin` with a matching `Referer` proceeds; both
missing gets 403; a `GET` with a foreign `Origin` is untouched.

**Sign-out.** Revokes the row (`resolveSession` then returns null) and clears
the cookie.

**Shell.** The signed-out header contains exactly one `<a href="/sign-in">`; the
signed-in header contains the account link and the sign-out form and no sign-in
link; `/account` renders with the `wide-measure` body class. Existing pages'
markup is unchanged for a signed-out visitor.

**CSP and CSS mechanisms.** The existing `ShellMechanismTest` sweeps cover the
new render files; the new pages carry no inline style or script, and the new
form rules parse and use no absolute layout token.

## Open items

- **No sliding session refresh on the web** (D-D). A web session ends 30 days
  after mint, not 30 days after last use.
- **`web/05` must sweep change-email into the form rail** (D-E).
- **`admin-web` keeps its own cookie writer.** A later run may adopt the shared
  one; nothing in this slice depends on it.
