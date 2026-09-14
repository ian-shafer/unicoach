# Backend and auth feasibility for a browser client

**Method.** Read-only source inspection of the root checkout
`/Users/ian/Work/unicoach` (no gradle runs, no writes). I read every file under
`rest-server/src/main/kotlin/ed/unicoach/rest/` (Application, Routing, all
`routing/*Routes.kt`, all `plugins/`, `auth/`, `config/`), the packaged configs
(`rest-server/src/main/resources/rest-server.conf`,
`service/src/main/resources/service.conf`, `.env.template`), the budget and
subscription services under
`service/src/main/kotlin/ed/unicoach/{coaching/budget,subscriptions,auth}/`,
`public-web/src/main/kotlin/ed/unicoach/web/{Application,Routing}.kt`, the iOS
client transport
(`ios-app/UnicoachiOS/{APIClient,ClientKey,ConversationClient,AppViewModel}.swift`),
`api-specs/openapi.yaml`, and `infra/{dns,alb}.tf`. Claims below cite those
files. I did not run the server and could not read production SSM secrets, so
two claims are flagged as unverified.

**Bottom line:** the REST backend is already browser-shaped. Auth is an HttpOnly
session **cookie**, not a bearer token; there is no device attestation; chat
streaming is SSE over plain HTTP POST; a signed-out-of-payment user still gets a
$5 lifetime free coaching allowance. The real gaps are: no CORS support (so the
web client must be same-origin or server-proxied), no web payment path
(StoreKit-only), Google/Apple SSO audiences configured for the iOS app only, and
no rate limiting.

---

## 1. Route inventory and auth model

All API routes live in one Ktor service (`rest-server`), registered in
`rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` and mirrored by a
maintained OpenAPI spec at `api-specs/openapi.yaml` (23 paths; the only route
missing from the spec is `POST /api/v1/auth/change-email` — spec lags code
there).

| Path                                         | Methods            | Auth                                           |
| -------------------------------------------- | ------------------ | ---------------------------------------------- |
| `/healthz`                                   | GET                | none (allowlisted from every gate)             |
| `/api/v1/auth/register`                      | POST               | none (sets cookie)                             |
| `/api/v1/auth/login`                         | POST               | none (sets cookie)                             |
| `/api/v1/auth/google`                        | POST               | Google ID token (sets cookie)                  |
| `/api/v1/auth/apple`                         | POST               | Apple ID token (sets cookie)                   |
| `/api/v1/auth/me`                            | GET                | cookie                                         |
| `/api/v1/auth/logout`                        | POST               | cookie (idempotent)                            |
| `/api/v1/auth/verify-email`                  | POST               | token in body, no session needed               |
| `/api/v1/auth/resend-verification`           | POST               | cookie                                         |
| `/api/v1/auth/change-email`                  | POST               | cookie                                         |
| `/api/v1/students`                           | POST               | cookie + verified email                        |
| `/api/v1/students/me`                        | GET, PATCH, DELETE | cookie + verified email                        |
| `/api/v1/students/me/college-list`           | GET, POST          | cookie + verified email                        |
| `/api/v1/students/me/college-list/{entryId}` | GET, PATCH, DELETE | cookie + verified email                        |
| `/api/v1/students/me/money-profile`          | GET, PUT           | cookie + verified email                        |
| `/api/v1/students/me/coaching-usage`         | GET                | cookie + verified email                        |
| `/api/v1/colleges`                           | GET (`q`, `limit`) | cookie + verified email                        |
| `/api/v1/vocabularies`                       | GET                | cookie + verified email                        |
| `/api/v1/conversations`                      | GET, POST          | cookie + verified email                        |
| `/api/v1/conversations/stream`               | POST (SSE)         | cookie + verified email                        |
| `/api/v1/conversations/{id}`                 | GET, PATCH, DELETE | cookie + verified email                        |
| `/api/v1/conversations/{id}/messages`        | GET, POST          | cookie + verified email                        |
| `/api/v1/conversations/{id}/messages/stream` | POST (SSE)         | cookie + verified email                        |
| `/api/v1/subscriptions/verify`               | POST               | cookie + verified email (StoreKit JWS in body) |
| `/api/v1/subscriptions/apple-notifications`  | POST               | Apple JWS signature (client-key exempt)        |

Sources: `Routing.kt`, `routing/AuthRoutes.kt`, `routing/StudentRoutes.kt`,
`routing/CollegeListRoutes.kt`, `routing/CollegeRoutes.kt`,
`routing/MoneyProfileRoutes.kt`, `routing/CoachingUsageRoutes.kt`,
`routing/ConvoRoutes.kt`, `routing/SubscriptionRoutes.kt`,
`routing/VocabularyRoutes.kt`, `routing/AppleNotificationRoutes.kt`.

### How auth works — session cookies, exactly like a website

- Login/register/SSO set an **HttpOnly, SameSite=Strict** cookie named
  `UNICOACH_SESSION` with `domain = ${APP_DOMAIN}` and `secure` from env
  (`AuthRoutes.kt` `setSessionCookie`, lines ~36-60; `rest-server.conf`
  `session` block). Expiration 7d default, refreshed by a queue job per
  successful request (`plugins/SessionExpiryPlugin.kt`).
- Every protected route resolves the caller from that cookie via
  `auth/CallerResolution.kt` (`TokenHash.fromRawToken` → DB session lookup,
  cached per call). **There are no bearer tokens anywhere.**
- The iOS app itself uses this same cookie via `HTTPCookieStorage`
  (`ios-app/UnicoachiOS/AppViewModel.swift:15,82,177`). So a browser is the
  _native_ client shape for this auth model. Nothing to build here.

### Two global gates a browser must pass

1. **Client-key gate** (`plugins/ClientKeyGate.kt`): if `UNICOACH_CLIENT_KEYS`
   is non-empty, every path except `/healthz` and
   `/api/v1/subscriptions/apple-notifications` requires header
   `X-Unicoach-Client-Key` matching a configured key (`rest-server.conf`
   `clientKeyGate`). This is a **shared static secret baked into the iOS
   bundle** (`ios-app/UnicoachiOS/ClientKey.swift` reads `UnicoachClientKey`
   from Info.plist) — it is **not** device attestation. There is no App Check /
   DeviceCheck / AppAttest anywhere in first-party code (grep over `ios-app`,
   `rest-server`, `service` matched only vendored GoogleSignIn sample code).
   Implications for web:
   - An SSR web tier can hold its own key server-side — fine.
   - A browser-side `fetch` cannot hold the key secretly. Options: mint a
     separate web key and accept it is public (the gate then degrades to a
     bot-noise filter for web), allowlist paths, or proxy API calls through the
     web server. **Decision needed.**
   - Unverified: whether `UNICOACH_CLIENT_KEYS` is actually set in prod. It is
     blank in `.env.template:120` (gate off locally) and the real value is
     SSM-owned; I could not read it. Design as if the gate is on.
2. **Email-verification gate** (`plugins/EmailVerificationGate.kt`): every path
   outside `/healthz` and `/api/v1/auth/*` returns `403 EMAIL_NOT_VERIFIED` for
   an authenticated-but-unverified user. The web client must handle this state
   (show "check your email").

Also relevant: per-route request-size caps (`rest-server.conf` `requestSize`, 8
KiB default, 512 KiB under `/api/v1/conversations`), and a uniform JSON error
envelope `{code, message, fieldErrors}` (`Routing.kt`,
`models/ErrorResponse.kt`) a web client can map generically.

## 2. Chat streaming

Transport is **SSE over HTTP POST** — no websockets, no polling
(`routing/ConvoRoutes.kt`):

- `POST /api/v1/conversations/stream` and
  `POST /api/v1/conversations/{id}/messages/stream` respond
  `Content-Type: text/event-stream`, `Cache-Control: no-store`, and write frames
  `event: <name>\ndata: <json>\n\n` (`streamReply` / `writeSseEvent` in
  `ConvoRoutes.kt`).
- Event vocabulary (`models/StreamEvent.kt`): opener `conversation` (create) or
  `user_message` (follow-up), then repeated `delta` (`{text}`), then exactly one
  terminal frame — `message` (completed coach message) or `error`
  (ErrorResponse). Budget refusal happens **before** the stream opens as a plain
  JSON `402 COACHING_BUDGET_EXHAUSTED`.
- Non-streaming twins exist (`POST /conversations`, `POST /{id}/messages`) that
  buffer the whole reply — a no-JS fallback path exists for free.
- iOS consumes this with a raw byte stream + its own SSE line parser and a 60s
  inter-chunk idle timeout (`ios-app/UnicoachiOS/APIClient.swift`
  `stream(...)`/`streamSession`, `ConversationClient.swift`, `SSEParser.swift`).

**What a browser chat client needs:** because these are POST-with-body SSE
endpoints, the native `EventSource` API is unusable (GET-only). The standard
pattern is `fetch()` + `ReadableStream` + a small SSE frame parser —
functionally identical to what `SSEParser.swift` already does. Cookies ride
along automatically same-origin. No backend change is required for a same-origin
web chat client. Server-side rendering can additionally use the buffered
endpoints for initial page loads (`GET /conversations`, `GET /{id}/messages`).

## 3. Subscription, entitlement, and the web payment gap

- **Recording:** the iOS app posts a StoreKit 2 signed transaction to
  `POST /api/v1/subscriptions/verify`; `SubscriptionService` verifies it with
  the App Store Server API and stores a local `subscriptions` row
  (`routing/SubscriptionRoutes.kt`,
  `service/.../subscriptions/SubscriptionService.kt`). Renewals/revocations
  arrive via Apple server notifications at
  `/api/v1/subscriptions/apple-notifications`
  (`routing/AppleNotificationRoutes.kt`, authenticated by the JWS itself).
- **Gating:** `BudgetService`
  (`service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetService.kt`) is
  the single gate. No current subscription row → the **free branch**: lifetime
  LLM cost vs a configured free allowance, **$5.00 lifetime** by default
  (`service/src/main/resources/service.conf:357`). A current subscription →
  period spend vs `budgetRatio (0.5) × plan price` (one plan configured:
  `coach.uni.UnicoachiOS.monthly10` at $9.99, `service.conf:362-374`). All four
  coaching surfaces (chat plus background passes) block on the same verdict;
  `GET /students/me/coaching-usage` reports the same entitlement
  (`routing/CoachingUsageRoutes.kt`).
- **So a web user with no subscription gets real value today:** full account,
  profile, college list, search, money profile, and coaching until the $5
  lifetime allowance is spent; after that, chat answers
  `402 COACHING_BUDGET_EXHAUSTED` (`ConvoRoutes.kt` `respondBudgetExhausted`).
  The `service.conf:356` comment is explicit that the hard block stands "until a
  purchase path exists (subscriptions-apple)".
- **Web payment gap:** there is **no Stripe or any non-Apple payment code
  anywhere** (repo-wide grep matches only prose in
  `features/paid-subscriptions.md:161`, which records the deliberate "StoreKit
  2, not Stripe" decision — made when iOS was the only client). The verify
  endpoint accepts only an Apple `signedTransaction`. For web, either (a) a web
  user who subscribed on iOS is already entitled (the subscription is bound to
  the student, not the device — web chat just works for them), or (b) a web-only
  user hits the $5 wall with **no way to pay** until a Stripe (or similar) path
  plus a second `subscriptions`-row source is built. That is a product-sized
  build: checkout, webhooks, a provider column/plan mapping beside
  `SubscriptionPlans`.

## 4. Sign-in on web

- **Email+password:** fully supported (`/auth/register`, `/auth/login`, Argon2
  hashing wired in `rest-server/.../Application.kt` `appModule`). Works from a
  browser as-is. Note: there is **no password-reset route** anywhere in
  `AuthRoutes.kt`/the OpenAPI spec — a gap for a web product where the app
  cannot fall back to "reinstall and use SSO".
- **Google:** `POST /auth/google` takes a Google **ID token** and verifies
  signature/issuer/audience server-side
  (`service/.../auth/IdTokenVerifierFactory.kt`, config `service.conf:432-445`).
  `clientIds` (accepted audiences) is a comma-separated env list
  (`GOOGLE_CLIENT_IDS`) — adding the web OAuth client ID is a config change, no
  code. The web front end must run Google Identity Services to obtain the ID
  token (client-side JS; SSR pages need this one island of JS or a redirect flow
  terminating in a page that posts the token).
- **Apple:** `POST /auth/apple` likewise verifies an Apple ID token; accepted
  audiences via `APPLE_CLIENT_IDS` (`service.conf:447-460`). Sign in with Apple
  **JS/web** uses a Services ID as the audience, not the app bundle ID — again
  config, plus registering the Services ID + return URL with Apple. The route
  shape (`{idToken, name}`) already matches what Apple's web flow returns.
  Unverified: I did not confirm the Apple verifier accepts multiple audiences in
  one token check beyond the config plumbing shown in `SsoProviderConfig.kt` (it
  clearly reads a list).
- **Email verification:** already web-native end to end. The email links to
  `public-web`'s `GET /verify-email` confirm page; the POST consumes the token
  **in-process** via `DbEmailVerifier`
  (`public-web/src/main/kotlin/ed/unicoach/web/{Application,Routing}.kt`), and
  rest-server exposes the same consumption at `POST /api/v1/auth/verify-email`
  for the app. Link base is `publicWeb.urlBase` (`service.conf` `publicWeb`
  block, e.g. `https://app.uni.coach`).

## 5. Everything else a web client hits

- **CORS: none.** No CORS plugin is installed in `rest-server` (`Application.kt`
  `appModule` installs request-logging, serialization, client-key gate, status
  pages, size limit, email gate only; repo grep for CORS matches only a test
  name). Deployment puts the API at `api.<domain>` and web at `app.<domain>`
  behind one ALB (`infra/dns.tf:47-65`, `infra/alb.tf:80-90`). So browser JS on
  `app.uni.coach` **cannot call `api.uni.coach` directly today.** Three ways
  out: (a) SSR + the web server proxying/calling the API (or services)
  server-side — zero backend change; (b) add an ALB/route rule serving `/api/*`
  on the web origin; (c) install Ktor CORS with credentials — the most work and
  the most footgun. The cookie itself is fine either way: `domain=${APP_DOMAIN}`
  covers both subdomains, and SameSite=Strict is satisfied because both hosts
  are the same registrable site.
- **CSRF:** no CSRF-token machinery exists. Protection rests on
  `SameSite=Strict` + HttpOnly on the session cookie (`AuthRoutes.kt`) plus JSON
  bodies. That is adequate for fetch-based same-site clients; if the web app
  uses classic HTML form posts to state-changing API routes, add CSRF tokens or
  keep forms posting to the web tier. `public-web`'s only existing form
  (`POST /verify-email`) is authorized by the single-use token itself
  (`public-web/.../Routing.kt` doc comment).
- **Rate limiting: none** in application code (no RateLimit plugin anywhere in
  `rest-server` or `web-common`). Login/register on the open web with no
  throttle is a launch-blocker-grade gap to at least note.
- **Request logging** treats `Cookie` and the client key as secret headers
  (`rest-server.conf` `requestLogging`), so browser traffic logs safely.
- **Existing web precedent:** `public-web` and `admin-web` are Ktor SSR services
  that talk to the DB/services **in-process**, not over the REST API
  (`public-web/.../Application.kt` builds `Database`, `DbEmailVerifier`,
  `CostReportSource` directly). A brief-0013 web app could follow either
  pattern: in-process (like public-web — fastest, shares the monolith) or
  REST-consuming (exercises the same contract as iOS). That is a spec decision,
  not a constraint.

## Exists vs needs building (summary)

**Exists, browser-ready:** cookie auth incl. logout/expiry; every iOS feature's
REST route; SSE chat (POST+fetch-stream, with buffered fallback); free $5
coaching tier; email verification web pages; multi-audience SSO config; uniform
error envelope; OpenAPI spec (minus change-email).

**Needs building / deciding:** CORS-or-proxy topology; client-key policy for
browsers; web payment path (nothing exists — Stripe-sized project) or an
explicit "subscribe on iOS" story; Google/Apple _web_ OAuth front-end flows +
audience config + Apple Services ID; password reset; rate limiting on auth
routes; CSRF tokens only if HTML-form posts hit the API.
