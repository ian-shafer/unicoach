# RFC 275 — Google and Apple sign-in on the web

**Slice:** `web/11/sso` (brief 0013, _Unicoach on the web_). **Status:**
approved (Ian, 2026-11-05: "Approve all"). **Base:** `main@6a551b90`. **Lane:**
rfc.

## Executive Summary

A family that signed up on the iPhone with "Sign in with Apple" (or Google) has
no password, so today the website cannot let them in at all. This RFC puts
**Continue with Apple** and **Continue with Google** on the web's sign-in page
and its Create-account page (D-B).

The mechanism is the one brief 0013 D7 named: each provider's own JavaScript
button. The buttons run in popup mode (D-A). The provider hands the page an ID
token, and our bundle posts it to our own origin, to `POST /sign-in/apple` or
`POST /sign-in/google`. Those routes call the same `AuthService.loginWithSso`
the iPhone's `POST /auth/apple` calls, in-process (D13). So the returning login,
the email link, the name fallback and the session are the iPhone's code, not a
copy. The post rides the existing same-origin guard unchanged, because it IS
same-origin. No guard exemption, no state cookie, no pre-login session (D5).

The web's client IDs are config (D-C). Each one is ADDED to that provider's
accepted audiences rather than listed twice. A page renders a provider's button
only when that ID is configured. Only the pages that show a button get a widened
content-security policy, naming that provider's documented origins and nothing
else (D-D).

Apple's first-authorization name is posted beside the token. If it never
arrives, the existing fallback names the account after its email address, so an
account is never created with an empty name.

No table, no migration, no `SessionMetadata` change.

## Detailed Design

### 1. What the code says, where it differs from the slice

- **The backend half exists and is wired fail-closed on the web.**
  `AuthService.loginWithSso(provider, idToken, clientProvidedName, …)` verifies
  the token, resolves or links or creates the user, and mints the session
  (`service/.../auth/AuthService.kt`). `rest-server`'s two routes are thin: they
  deserialize, call it and map the outcome, with no side effect of their own to
  copy (contrast preamble 10). `public-web` builds its `AuthService` with
  `DisabledIdTokenVerifier` in both slots (`public-web/.../Application.kt`,
  `WebAuthServices.build`). This RFC replaces those with the real verifiers from
  the same `IdTokenVerifierFactory` `rest-server` uses.
- **"A misconfigured audience" cannot happen to the web by construction.** The
  audience list lives in `auth.<provider>.clientIds` (`GOOGLE_CLIENT_IDS`,
  `APPLE_CLIENT_IDS`), and today it holds only the iOS IDs. Making operators put
  the web ID in two places and hoping they agree is the failure the slice names.
  So the web ID gets one home, `auth.<provider>.webClientId`, and the accepted
  set is derived as `clientIds ∪ {webClientId}` (D-C). The remaining ways to
  misconfigure are outside our process: a Google client whose authorized
  JavaScript origins omit the site, or an Apple Services ID whose domain or
  return URL is wrong. Those fail inside the provider's popup with the
  provider's own message, never as a blank page of ours. A token that still
  arrives with an audience we do not accept is refused with a page sentence, and
  the reason is logged with the audience it carried (§4).
- **The same data, for an Apple user from iOS.** Apple's `sub` is per developer
  team, so a Services ID grouped under the iOS app's primary App ID yields the
  same `sub`, and `UserAuthIdentitiesDao.findByProviderAndSubject` resolves the
  iPhone's user. If the grouping were wrong, the email-match link in
  `resolveOrProvisionUser` still lands on the same user, because Apple's relay
  address is per team too, and a passwordless SSO user always links. The
  grouping is an Apple-portal step (§5), not code.
- **The first-authorization name.** Apple's JS resolves `user.name.firstName` /
  `lastName` only on a user's first authorization of the team. The iPhone joins
  the two, keeps the result per Apple user, and resends it. The web joins the
  two the same way (given name, a space, family name, trimmed; a blank result is
  no name) and posts it as `name`. `loginWithSso` uses a client name only when
  it creates a user, and falls back to the email local-part (`deriveName`). So a
  user whose name never arrives gets a non-empty name, not an empty one. Where
  the iPhone has a keychain, the web has the re-rendered form. A refused Apple
  post re-renders the sign-in page with the posted name in the hidden field, so
  the retry, whose token carries no name, sends it again. That is ordinary form
  handling on a signed-out page, not session storage (D12 does not reach it).
- **Google never sends a client name.** Its token carries a `name` claim, which
  `deriveName` reads first, exactly as for the iPhone.

### 2. The flow

```
sign-in page (GET /sign-in, or GET /register)
  └─ provider script + our bundle → button rendered (popup UX)
       user completes the provider popup
  └─ bundle fills a hidden form and submits it, same-origin:
       POST /sign-in/google   id_token=<credential>        [token] [report]
       POST /sign-in/apple    id_token=<id_token> [name]   [token] [report]
  └─ same-origin guard (unchanged) → route → AuthService.loginWithSso (in-process)
       Success → web session cookie (30 days, RFC 224) → SignInCompletion → 303
       refused → sign-in page re-rendered with the refusal sentence, at its status
```

**API contract.** Two routes, both `POST`, both
`application/x-www-form-urlencoded`, both signed-out doors exactly like
`POST /sign-in` (no CSRF synchronizer token, because there is no session to hold
one; the same-origin guard covers them as it covers every non-GET).

| Field      | `/sign-in/google` | `/sign-in/apple` | Meaning                                                |
| ---------- | ----------------- | ---------------- | ------------------------------------------------------ |
| `id_token` | required          | required         | the provider's ID token, as the provider issued it     |
| `name`     | —                 | optional         | the Apple first-authorization name, joined (§1)        |
| `token`    | optional          | optional         | the invite token the sign-in door carried (RFC 232)    |
| `report`   | optional          | optional         | the cost-report claim token the door carried (RFC 254) |

`token` and `report` are read through the existing `presentedToken(...)`
readers. On success they are handled by the code that handles them for a
password sign-in today, so an invite accepted or a report claimed by Apple lands
where it lands for a password. That code moves from the `POST /sign-in` handler
into one private
`SignInCompletion.complete(call, user, sessionToken, presentedInvite, presentedReport, door)`
in `Routing.kt`, called by all three routes; the class holds the session config
and the two services it needs, and `door` names the route in the invite and
claim logs. It sets the web session cookie, accepts the invite, claims the
report and answers `303` to `signInLandingUrl(...)`. A user created by SSO lands
on `/chat`, and the onboarding wall (RFC 268) takes them from there.

`GET /sign-in/google` and `GET /sign-in/apple` are not routes. They answer the
site's 404.

### 3. The pages

`SignInPage.kt` and `RegisterPage.kt` gain one shared block, from a new
`render/SsoButtons.kt`, placed above the email form:

- the Google host `<div data-sso-google data-client-id="…">`, which the provider
  script fills with its own button, an iframe at `accounts.google.com`
  (`renderButton`, `text: "continue_with"`);
- our own `<button type="button" data-sso-apple …>Continue with Apple</button>`,
  styled to Apple's guidelines in `site.css`. It is ours, not the SDK-rendered
  `#appleid-signin`, because the SDK's markup is not something our enforcing
  `style-src 'self'` has been shown to admit. If the implementer verifies in a
  browser that the SDK's own button renders with ZERO CSP violations, using it
  is acceptable and the RFC is amended to say so;
- the two hidden forms the bundle submits, each carrying the page's `token` /
  `report` hidden fields through the existing `tokenField` / `reportField`;
- a status line (`role="status"`, `aria-live="polite"`) for the sentences the
  bundle writes (§4);
- a divider, `or use your email`, between the buttons and the email form.

A provider whose web ID is not configured renders nothing at all: no button, no
script tag, no widened policy. A page with neither configured is today's page
byte for byte.

The provider scripts are external `<script src defer>` tags in the page head,
placed BEFORE the bundle and added through a parameter of `siteLayout` that
takes `SsoDoor`s, each door naming its own script URL (`SsoDoor.sdkUrl`), so a
page can load only a script a door names. Each has run or failed by the time the
bundle mounts (an `async` script a content blocker cancels fires `error` before
a later listener could hear it):

- `https://accounts.google.com/gsi/client`
- `https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js`

Our bundle gets a new `public-web/src/main/ts/sso.ts`, mounted from `main.ts`
like every enhancement, a no-op when its hosts are absent:

- **Google.** With `window.google?.accounts?.id` present at mount, it calls
  `google.accounts.id.initialize({ client_id, callback, ux_mode: "popup" })` and
  `renderButton(host, …)`. The `callback` puts `response.credential` into the
  Google form's `id_token` and submits it.
- **Apple.** On click it calls
  `AppleID.auth.init({ clientId, scope: "name
  email", redirectURI, usePopup: true })`
  once, then `await
  AppleID.auth.signIn()`. On success it fills `id_token`
  from `authorization.id_token` and `name` from `user?.name` (joined per §1,
  keeping the field's re-rendered value when Apple sent none), then submits. On
  a rejection it writes the cancelled sentence when
  `error ===
  "user_cancelled_authorize"` or `"popup_closed_by_user"`, and the
  could-not-start sentence otherwise.
- **The SDK did not load** (a content blocker, a network failure): a click on
  the Apple button, or a Google global absent at mount, writes the
  could-not-load sentence. Nothing is submitted.

The joining of a name and the choice of sentence are pure functions, exported
and Deno-tested (§Tests).

`redirectURI` is required by Apple even in popup mode and must be registered on
the Services ID. It is DERIVED, `PUBLIC_WEB_URL_BASE` + `/sign-in`, from the
parsed `SiteOrigin` `PublicWebConfig` already holds, and rendered into
`data-redirect-uri`. It is never configured separately. Apple refuses
`localhost`, so the Apple button can only complete on a deployed domain (§5).

### 4. Errors and the words (D-F)

Every refusal re-renders the sign-in page, through a new `SignInFormState` arm
`SsoRefused`, which owns its status as the existing two arms do. It is a sealed
pair, `SsoRefused.Google(refusal)` and `SsoRefused.Apple(refusal, name)`, each
naming its `SsoDoor`: only Apple carries the posted name, so a Google refusal
holding one does not compile. Each `SsoLoginResult` maps to a sentence, in an
exhaustive `when` with no `else`:

| Outcome                                 | Status | Sentence (`{P}` = Google or Apple)                                                                                                                                   |
| --------------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `InvalidToken` (incl. a wrong audience) | 401    | We couldn't sign you in with {P}. Please try again, or use your email and password.                                                                                  |
| missing or blank `id_token` field       | 400    | (same sentence as `InvalidToken`)                                                                                                                                    |
| `EmailNotVerified`                      | 403    | Your {P} account's email address isn't verified, so we can't use it to sign you in. Verify it with {P}, or use your email and password.                              |
| `LinkBlockedUnverifiedEmail`            | 403    | There's already an account for this email address, and its address hasn't been verified yet. Sign in with your email and password and verify it, then try {P} again. |
| `AccountDisabled`                       | 403    | This account has been closed, so it can't be signed in to.                                                                                                           |
| `VerificationUnavailable`               | 503    | {P} sign-in isn't available right now. Please try again in a few minutes, or use your email and password.                                                            |

Client-side sentences, written into the status line by `sso.ts`:

| When                                   | Sentence                                                                                             |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| the user cancelled or closed the popup | Sign in with {P} was cancelled.                                                                      |
| the SDK did not load or start          | {P} sign-in couldn't load. Check your connection or content blocker, or use your email and password. |

A misconfigured provider fails in the browser and never reaches the server, so
every could-not-load case that the provider's own JS reports (a thrown `init` or
`renderButton`, a non-cancel Apple rejection, a response with no token) also
writes that error to the browser console. A cancellation is the user's choice
and is not logged. The console line never carries the token or the name.

Every server-side refusal is logged at the route, mirroring `rest-server`'s
`respondSsoLoginOutcome`: INFO with the provider and the outcome, WARN for
`VerificationUnavailable`, with its transport failure passed as the throwable so
the cause chain is printed. `InvalidToken` carries the verifier's exception, and
`JwksIdTokenVerifier`'s message names the audience it saw ("ID token audience is
not exactly one accepted client id [audience=…]"). That logged line is the
"diagnosable error". The token itself is never logged. The posted `name` is
never logged either, because it is user data.

Boot logs one line per provider, beside the same-origin guard's:
`web sign-in
with Google: on [<client id>]` or
`off (GOOGLE_WEB_CLIENT_ID unset)`, and the same for Apple. An operator can then
tell "buttons missing" from "buttons broken".

### 5. Configuration (D-C) — documented where the values are set

`service.conf`, inside the existing blocks:

```hocon
auth.google.webClientId = ${?GOOGLE_WEB_CLIENT_ID}   # the Web OAuth client; optional
auth.apple.webClientId  = ${?APPLE_WEB_SERVICES_ID}  # the Services ID; optional
```

`SsoProviderConfig` gains `webClientId: String?`, where blank reads as absent,
and `acceptedAudiences: List<String>` = `clientIds` plus `webClientId` when
present, de-duplicated. `IdTokenVerifierFactory` checks emptiness against
`acceptedAudiences` and hands `acceptedAudiences` to `JwksIdTokenVerifier`.
`rest-server` therefore also accepts a web-audience token. That is harmless: the
token proves the same person, and the REST routes already accept every audience
this team issues. `public-web` reads the same two blocks to decide whether to
render each button. The ID is one fact with one home: the button reads it, and
the verifier accepts it.

The documentation lives in `.env.template`, `.env.prod` and `service.conf`,
beside the keys. It is a short block per provider stating:

- **Google:** create an OAuth client of type _Web application_ in the same
  Google Cloud project as the iOS client. Authorized JavaScript origins:
  `https://app.<APP_DOMAIN>`, plus `http://localhost` and
  `http://localhost:<PUBLIC_WEB_PORT>` for local runs. Put its ID in
  `GOOGLE_WEB_CLIENT_ID`. Do not add it to `GOOGLE_CLIENT_IDS`, because it is
  accepted by derivation.
- **Apple:** create a _Services ID_, enable Sign in with Apple and group it with
  the iOS app's primary App ID (`coach.uni.UnicoachiOS`), so the user ID matches
  the iPhone's. Domain: `app.<APP_DOMAIN>`. Return URL:
  `https://app.<APP_DOMAIN>/sign-in`. Put its identifier in
  `APPLE_WEB_SERVICES_ID`. Apple refuses `localhost`, so the Apple button is
  exercised on a deployed environment only.

**Prod (D-C, decision for Ian).** `bin/gen-deployed-env` ships only the keys in
its required `DEPLOY_VAR_NAMES` set. Either Ian supplies the two IDs now and
they join that set and `.env.prod` in this run, or the buttons stay dark in prod
until a small follow-up adds them.

### 6. Content-security policy and referrer policy (D-D)

`ContentSecurityPolicy.kt` (`:web-common`) today holds one string. It gains a
small directive builder:
`contentSecurityPolicy(extraSources: Map<String, List<String>> = emptyMap())`
returns the default policy with the extra sources appended to the named
directives. `CONTENT_SECURITY_POLICY` becomes `contentSecurityPolicy()`, so the
default stays one home and is byte-identical. `public-web` builds the widened
policy from the configured providers only:

- **Google:** `script-src` `https://accounts.google.com/gsi/client`; `frame-src`
  `https://accounts.google.com/gsi/`; `connect-src`
  `https://accounts.google.com/gsi/`; `style-src`
  `https://accounts.google.com/gsi/style` and `'unsafe-inline'`. The first four
  are Google's documented set; `'unsafe-inline'` is not, and is there because
  the browser check proved the set insufficient: GIS `renderButton` writes a
  `<style>` element and `style=` attributes into our page, outside its iframe,
  and under a strict `style-src` the button renders broken. Ian chose this over
  hashes (which break silently when Google changes its CSS) and over shipping
  Apple alone. `script-src` stays strict, and `img-src`/`font-src 'self'` leave
  injected style no off-site URL to load. `frame-src` is new to the policy: it
  is added explicitly, rather than through `default-src`.
- **Apple:** `script-src` `https://appleid.cdn-apple.com`. The popup is a
  separate window, not a frame, and the result comes back by `postMessage`.

The widened policy is set on the `/sign-in` and `/register` routes only, which
override the root install. The implementation MUST prove exactly ONE
`Content-Security-Policy` header on those responses. Ktor's route-scoped plugin
lets a nested install replace the parent's configuration, and two headers would
be enforced as their intersection, silently re-blocking the providers. Every
other page keeps the default.

Those two routes also carry `setLinkHolderPrivacyHeaders()` (RFC 232/254), whose
`Referrer-Policy: same-origin` sends NO `Referer` to a cross-origin frame.
Google recommends `strict-origin-when-cross-origin`. That value sends only the
ORIGIN cross-origin, never the path or query, so the invite or report token in
the URL still never leaves the site. When Google is configured, the two pages
send `strict-origin-when-cross-origin`. `PrivateResponse.kt` gains the constant
beside `REFERRER_POLICY_SAME_ORIGIN`. The other link-holder headers
(`Cache-Control`, `X-Robots-Tag`) are unchanged. A same-origin POST from such a
page still sends a real `Origin`, so the guard is unaffected.

No `Cross-Origin-Opener-Policy` is set by the site today. Both popups need the
opener relationship, so the RFC adds none.

### 7. Decisions

- **D-A — the providers' own JS, popup mode, token posted same-origin (D7 as
  written).** The alternative is the server-side authorization-code redirect. It
  needs a Google client secret and an Apple client-secret JWT signed with a new
  private key. Apple's name scope forces `response_mode=form_post`, a cross-site
  POST, which needs a same-origin-guard exemption and a `SameSite=None` state
  cookie. And the `SameSite=Strict` session cookie is not sent on the redirect
  chain after a cross-site callback. Popup mode needs none of this. Cost: two
  third-party scripts run on the sign-in and Create-account pages, which can
  carry an invite or report token in their URL, and those scripts can read the
  page. They come from the two providers the user is authenticating with, and
  they appear only when that provider is configured. **Default: as written.**
- **D-B — buttons on Create account too.** The slice's door is the sign-in page.
  An Apple user who clicks "Create an account" would otherwise be offered only a
  password form. SSO is sign-up-or-in by construction (`loginWithSso` creates on
  first sight). **Default: both pages.**
- **D-C — config shape and prod.** One new optional key per provider, added to
  the accepted audiences by derivation. Buttons render iff configured. **Prod
  default: buttons dark until Ian supplies the Google Web client ID and the
  Apple Services ID**, then a small follow-up adds both to `.env.prod` and
  `DEPLOY_VAR_NAMES`. If Ian has them now, they land in this run.
- **D-D — CSP and referrer widening on those two pages only**, as §6. **Approved
  as written; amended mid-run (Ian, "Option A") to add
  `style-src 'unsafe-inline'` when Google is configured.**
- **D-E — no nonce.** GIS and Apple JS accept an optional nonce, and the
  verifier would have to check it. But the REST routes accept nonce-less tokens
  for the same audiences, so a web-only nonce closes no replay: a captured web
  token would be replayed at `/auth/apple`. Login CSRF, the attack that matters
  for a cookie site, is closed by the same-origin guard. A nonce would also need
  pre-login state, which D5 rules out as a session. **Default: none. A replay
  defence belongs to both doors at once, in a later RFC if wanted.**
- **D-F — the words**, §4's two tables, the button labels "Continue with Apple"
  / "Continue with Google", and the divider "or use your email". **Default: as
  written.**

## Files Modified

Expected scope, non-exhaustive.

- `service/src/main/kotlin/ed/unicoach/auth/SsoProviderConfig.kt` —
  `webClientId`, `acceptedAudiences`.
- `service/src/main/kotlin/ed/unicoach/auth/IdTokenVerifierFactory.kt` — uses
  `acceptedAudiences`.
- `service/src/main/resources/service.conf` — the two `webClientId` keys and
  their documentation.
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — real verifiers
  in `WebAuthServices.build`; boot log lines; the button config handed to
  routing.
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt` —
  `POST /sign-in/google`, `POST /sign-in/apple`, `SignInCompletion`, the
  per-route CSP and referrer.
- `public-web/src/main/kotlin/ed/unicoach/web/render/SignInPage.kt`,
  `RegisterPage.kt`, `Layout.kt`, new `render/SsoButtons.kt`.
- `public-web/src/main/resources/static/site.css` — the Apple button, the
  divider, the status line.
- `public-web/src/main/ts/sso.ts` (new), `public-web/src/main/ts/main.ts`, and a
  Deno test beside them.
- `web-common/src/main/kotlin/ed/unicoach/web/common/http/ContentSecurityPolicy.kt`,
  `PrivateResponse.kt`.
- `.env.template`, `.env.prod` — documentation. If D-C is "now",
  `bin/gen-deployed-env` and its harness in `bin/scripts-tests` also change.
- Tests under `public-web/src/test/kotlin/ed/unicoach/web/`,
  `service/src/test/kotlin/ed/unicoach/auth/`, and
  `web-common/src/test/kotlin/`.

## Implementation Plan

1. **Audiences by derivation.** Add `webClientId` and `acceptedAudiences` to
   `SsoProviderConfig`, switch `IdTokenVerifierFactory` to them, and add the two
   keys and their comments to `service.conf`. Verify:
   `nix develop -c bin/test service -t "ed.unicoach.auth.*"`.
2. **CSP builder.** Add `contentSecurityPolicy(extraSources)` in
   `ContentSecurityPolicy.kt` with `CONTENT_SECURITY_POLICY` defined through it
   (byte-identical), and add the `strict-origin-when-cross-origin` constant.
   Verify: `nix develop -c bin/test web-common`.
3. **Real verifiers in public-web.** Build both verifiers in `public-web`'s
   startup from `SsoProviderConfig` via `IdTokenVerifierFactory`, replacing
   `DisabledIdTokenVerifier`. Derive the button config (Google client ID, Apple
   Services ID, the Apple redirect URI from `PublicWebConfig.origin`) and log
   the two boot lines. Make sure the test config gives public-web the stub
   provider. Verify: `nix develop -c bin/test public-web`.
4. **Routes.** Extract `SignInCompletion` from `POST /sign-in` (behaviour
   unchanged, existing `SignInRoutingTest` green). Add the two POST routes, the
   `SsoRefused` state and the §4 mapping and logging. Verify:
   `nix develop -c bin/test public-web`.
5. **Pages and policy.** Add `SsoButtons.kt`, render it on both pages, wire the
   head scripts through `siteLayout`, and set the widened CSP and referrer
   policy on `/sign-in` and `/register` only when a provider is configured. Add
   the CSS. Verify: `nix develop -c bin/test public-web`.
6. **Bundle.** Write `sso.ts` and its Deno tests, and mount it in `main.ts`.
   Verify: `nix develop -c bin/build-web-assets`.
7. **Docs.** Write the `.env.template` / `.env.prod` blocks (§5). If D-C is
   "now", also add the keys to `DEPLOY_VAR_NAMES` and `.env.prod`. Verify:
   `nix develop -c bin/format -c`.
8. **Browser check.** Run public-web locally (`bin/public-web-up` or the RFC 270
   D19 recipe) with placeholder `GOOGLE_WEB_CLIENT_ID` / `APPLE_WEB_SERVICES_ID`
   values. In the in-app browser, confirm that both buttons render, the console
   shows ZERO CSP violations on `/sign-in` and `/register`, exactly one CSP
   header arrives, and clicking Apple produces its popup or the could-not-start
   sentence, never an uncaught error. Check the D19 matrix sizes for the button
   block. A full round trip needs real IDs on a deployed domain, which is a
   human check (open item).
9. **Full suite.** `nix develop -c bin/test`.

## Tests

`public-web` route tests run against the real database and `AuthService`, with
the `stub` verifier selected by test config. The stub token format is
`stub:sub=…;email=…;email_verified=true[;name=…]`.

**`SsoSignInRoutingTest`** (new, `public-web`):

1. _An iPhone Apple user gets in and sees their data._ Seed a user with an
   `APPLE` identity row (`sub=apple-1`, no password) and a college-list entry.
   `POST /sign-in/apple` with
   `id_token=stub:sub=apple-1;email=…;email_verified=true`. Expect a `303` to
   `/chat`, a session cookie, a `sessions` row for that same `user_id` with
   `login_method = APPLE`, and that cookie's `GET /colleges` lists the seeded
   entry.
2. _First Apple sign-in takes the posted name._ Unknown `sub`, plus
   `name=Ada Lovelace`: the created user's name is `Ada Lovelace`.
3. _No name, never an empty name._ Unknown `sub`, no `name` field: the created
   user's name is the email local-part, not blank.
4. _A blank posted name falls through._ `name=`: the local-part again.
5. _A returning Apple user is not renamed by a posted name._
6. _Google sign-in, new and returning._ `POST /sign-in/google`: a `303`, a
   session with `login_method = GOOGLE`, and the token's `name` claim used.
7. _Each refusal, one test each, asserting its status and sentence:_
   `stub:invalid` → 401; a missing `id_token` → 400; `email_verified=false` →
   403; a password user with an unverified email at that address → 403 (link
   blocked) with no identity row created; a soft-deleted user's identity → 403
   closed; `stub:unavailable` → 503. In every case, assert that no session row
   was created.
8. _A refused Apple post keeps its name._ `stub:unavailable` with
   `name=Ada Lovelace`: the re-rendered page's Apple form carries
   `Ada
   Lovelace` in its hidden `name` field.
9. _An invite carried through SSO is accepted._ The RFC 232 fixture: post with
   `token=<invite>`. The landing is `/account` with the invite notice, and the
   link exists.
10. _A report claim carried through SSO lands where a password sign-in's does_
    (the RFC 254 fixture).
11. _The same-origin guard covers the new doors._ `POST /sign-in/apple` with a
    foreign `Origin` → the branded 403, and no session.
12. _An existing session is replaced._ Post with a live old cookie: the old row
    is revoked (fixation, as `mintSession` does).
13. _`GET /sign-in/apple` is a 404._

**`SignInRoutingTest` / `RegisterRoutingTest`** (extended):

14. _With both providers configured_, `/sign-in` and `/register` render the
    Google host with the client ID, the Apple button with the Services ID and
    the derived redirect URI (`<urlBase>/sign-in`), and the names `sso.ts`
    restates pinned as string literals (each form's `id_token`, Apple's `name`,
    the `google`/`apple` form segments, `data-sso-status`), both script tags,
    exactly ONE `Content-Security-Policy` header containing the §6 sources, and
    `Referrer-Policy: strict-origin-when-cross-origin`.
15. _With neither configured_, both pages carry no SSO markup, no provider
    script, the default CSP string exactly, and `Referrer-Policy: same-origin`.
16. _With only Apple configured_, the page has no Google host, and the CSP names
    `appleid.cdn-apple.com` and no `accounts.google.com`.
17. _Other pages keep the default policy_ (`/chat`, `/account`) when providers
    are configured.
18. _The existing password sign-in tests stay green unchanged_ after the
    `SignInCompletion` extraction.

**`SsoProviderConfigTest` / `IdTokenVerifierFactoryTest`** (`service`,
extended):

19. `acceptedAudiences` = `clientIds` + `webClientId`, de-duplicated; a blank
    `webClientId` is absent; the comma-string env shape still parses.
20. A real provider with an empty `clientIds` but a `webClientId` builds a
    verifier (the union is non-empty). With both empty, it fails with the
    existing message.
21. `JwksIdTokenVerifier`, built through the factory, accepts a token whose
    `aud` is the web ID, using the existing locally signed JWKS fixture.

**`ContentSecurityPolicyTest`** (`web-common`, extended):

22. `contentSecurityPolicy()` equals the previous literal byte for byte.
23. Extra sources append to the named directive, and an unknown directive
    (`frame-src`) is added.

**Deno tests** (`public-web/src/main/ts/sso_test.ts`):

24. `joinAppleName`: given+family → `"Ada Lovelace"`; only one part → that part;
    both blank or absent → `null`; whitespace is trimmed.
25. The name chosen for the form: Apple's name wins over the re-rendered one,
    and the re-rendered one is kept when Apple sent none.
26. The rejection-to-sentence mapping: `user_cancelled_authorize` and
    `popup_closed_by_user` → cancelled; anything else → could-not-start.
27. `mount` is a no-op on a document with no SSO hosts.
