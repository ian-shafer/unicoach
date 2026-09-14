# DISCOVER — AUTH (ground truth, cited)

All citations are `path:line` in the ROOT checkout `/Users/ian/Work/unicoach`,
read read-only at the time of writing. Every claim below was read from source.
Where a brief or common sense would be WRONG, the paragraph starts with
**WRONG-BY-DEFAULT**.

---

## 0. Headline findings a slice author must not get wrong

1. **There is NO CORS anywhere in the codebase.** `install(CORS)` / `anyHost` /
   `allowHost` / `Access-Control-*` appear in no Kotlin main source in
   `rest-server`, `public-web`, `admin-web`, or `web-common`. A browser at
   `https://app.uni.coach` calling `https://api.uni.coach` is a cross-ORIGIN
   request and will be blocked by the browser. The only file that mentions CORS
   is a test that asserts nothing:
   `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt:125-134`
   accepts `405 Method Not Allowed` as a pass, so it is green with zero CORS
   support. **A web slice MUST add a CORS plugin with an explicit origin
   allowlist and `allowCredentials = true`** (cookie auth cannot use `*`).
2. **The session cookie is `SameSite=Strict`, hard-coded**
   (`rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt:45,58`).
   `app.<domain>` and `api.<domain>` are the same _site_ (same registrable
   domain), so Strict does not break the SPA's own XHR. It DOES suppress the
   cookie on a **top-level navigation arriving from another site** — e.g. a
   click from a mail client, or an OAuth provider redirect back to
   `app.<domain>`. Any "land on a page already logged in from an email link"
   flow needs `SameSite=Lax`.
3. **Cookie `Domain` IS settable and already defaults to the apex.**
   `cookieDomain = ${APP_DOMAIN}`
   (`rest-server/src/main/resources/rest-server.conf:36`),
   `APP_DOMAIN=uni.coach` in prod (`.env.prod:34`). So the cookie is issued with
   `Domain=uni.coach` and is sent to `app.uni.coach` as well as `api.uni.coach`.
   **No change needed for `app.<domain>`.**
4. **The client-key gate would NOT block a browser today, but it is a loaded
   gun.** `clientKeyGate.keys` defaults to `""`
   (`rest-server/src/main/resources/rest-server.conf:50`), only overridden by
   `UNICOACH_CLIENT_KEYS`, which is empty in `.env.template:120` and is set
   nowhere in `infra/*.tf` (grep: no hits). With an empty key set the gate
   returns immediately
   (`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/ClientKeyGate.kt:18-20`).
   **If anyone ever seeds it, every path except `/healthz` and
   `/api/v1/subscriptions/apple-notifications` gets `403 forbidden`** —
   including all auth routes
   (`rest-server/src/main/resources/rest-server.conf:57`). A browser cannot hold
   that secret anyway, and the custom header would force a CORS preflight.
5. **PASSWORD RESET DOES NOT EXIST.** See §5. Nothing: no route, no table, no
   token, no template, no service method.
6. **Session sliding renewal ignores the configured expiry.**
   `SessionsDao.extendExpiry` hard-codes `NOW() + INTERVAL '7 days'`
   (`db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt:217`) even though
   `session.expiration` is configurable (`rest-server.conf:41-42`).
7. **There is no rate limit on `resend-verification`** (or on login/register).
   Grep for `RateLimit` in `rest-server`/`service` main sources returns only
   JWKS-provider hits.

---

## 1. Auth routes — exact shapes

Router:
`rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt:68-107`. All
under prefix `/api/v1/auth`. Every route also installs
`rejectUnsupportedMethods(...)`, which answers **405** with an `Allow` header
and a `text/plain` body `"Method Not Allowed. Expected: POST"` — NOT a JSON
`ErrorResponse`
(`rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt:35-44`).

Serialization is **Jackson** for these models (`ErrorCode` uses
`com.fasterxml.jackson.annotation.JsonValue`,
`rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt:3,14`). Some
request models additionally carry `@Serializable` (`LoginRequest`,
`GoogleLoginRequest`, `AppleLoginRequest`) but `RegisterRequest`,
`VerifyEmailRequest`, `ChangeEmailRequest` and every response model are plain
data classes.

### Error envelope

```kotlin
// rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorResponse.kt:5-9
data class ErrorResponse(
  val code: ErrorCode,          // lowercase snake_case wire string
  val message: String,
  val fieldErrors: List<FieldError>? = null,   // ed.unicoach.error.FieldError(field, message)
)
```

```json
{
  "code": "validation_failed",
  "message": "Invalid registration parameters",
  "fieldErrors": [
    { "field": "password", "message": "Password must contain at least 1 digit" }
  ]
}
```

`ErrorCode` closed set:
`rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt:17-62`.
Auth-relevant wire codes: `unauthorized`, `validation_failed`, `conflict`,
`invalid_token`, `token_expired`, `token_already_used`, `email_not_verified`,
`account_email_not_verified`, `account_disabled`, `service_unavailable`,
`bad_request`, `payload_too_large`, `forbidden`, `internal_error`,
`permanent_error`.

### The public user shape (returned by register/login/me/verify-email/change-email)

```kotlin
// rest-server/src/main/kotlin/ed/unicoach/rest/models/PublicUser.kt:6-21
data class PublicUser(
  val id: UUID,            // JSON: string uuid
  val email: String,
  val name: String,
  val emailVerified: Boolean,   // == (user.emailVerifiedAt != null)
)
```

Every success body wraps it as `{"user": {...}}` — `RegisterResponse`,
`LoginResponse`, `MeResponse`, `VerifyEmailResponse`, `ChangeEmailResponse` are
all one-field `user` wrappers (`.../models/RegisterResponse.kt:3-5`,
`LoginResponse.kt:3-5`, `MeResponse.kt:3-5`, `VerifyEmailResponse.kt:3-5`,
`ChangeEmailResponse.kt:3-5`).

---

### `POST /api/v1/auth/register`

Handler `AuthRoutes.kt:109-154`.

```kotlin
// rest-server/src/main/kotlin/ed/unicoach/rest/models/RegisterRequest.kt:3-7
data class RegisterRequest(val email: String, val password: String, val name: String)
```

| Outcome                    | Status                                 | Body                                                                                                                                                        |
| -------------------------- | -------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| success                    | **201 Created** + `Set-Cookie` session | `{"user": PublicUser}` (`AuthRoutes.kt:137-140`)                                                                                                            |
| validation failure         | 400                                    | `validation_failed`, `"Invalid registration parameters"`, `fieldErrors` per field; general errors come back as `field: "general"` (`AuthRoutes.kt:142-147`) |
| duplicate email            | 409                                    | `conflict`, `"Email already in use"`, `fieldErrors[0] = {email, "Email already in use"}` (`AuthRoutes.kt:149-154`)                                          |
| unreadable / non-JSON body | 400                                    | `bad_request`, `"Request body could not be read as the expected application/json payload"` (415→400 rewrite, `plugins/StatusPages.kt:30-38`)                |
| malformed JSON structure   | 400                                    | `bad_request`, `"Invalid JSON payload structure"` (`StatusPages.kt:45-50`)                                                                                  |
| body > **1 KiB**           | 413                                    | `payload_too_large` (`rest-server/src/main/resources/rest-server.conf:11`, `StatusPages.kt:39-44`)                                                          |
| DB/other fault             | 500                                    | `internal_error`, `"An internal error occurred"` (`StatusPages.kt:75-78`)                                                                                   |

**WRONG-BY-DEFAULT:** `/api/v1/auth/register` has a **1 KiB** request-size cap,
far below the 8 KiB default (`rest-server.conf:8-11`).

Password rules (server-side, must be mirrored in web form copy) —
`service/src/main/kotlin/ed/unicoach/auth/RegistrationValidator.kt:39-54`:

- ≥ 8 code points, ≤ 128 code points
- ≥ 1 uppercase `A-Z`, ≥ 1 lowercase `a-z`, ≥ 1 digit `0-9`
- email: non-blank, ≤ 254 chars, interior `@` (`RegistrationValidator.kt:26-28`)
- name: non-blank, within `users.name` bound (`RegistrationValidator.kt:34-37`)

Register also, **in one transaction**: creates the user, mints/remints the
session, issues a verification token and enqueues the verification email
(`service/src/main/kotlin/ed/unicoach/auth/AuthService.kt:86-147`, esp.
`143-144`). A failed enqueue aborts the whole thing → the caller sees 500 and no
user exists.

### `POST /api/v1/auth/login`

Handler `AuthRoutes.kt:179-216`.

```kotlin
// rest-server/src/main/kotlin/ed/unicoach/rest/models/LoginRequest.kt:6-9
data class LoginRequest(val email: String, val password: String)
```

- **200 OK** + `Set-Cookie` → `{"user": PublicUser}` (`AuthRoutes.kt:207-210`)
- **401** →
  `{"code":"unauthorized","message":"Invalid email or password","fieldErrors":null}`
  for ALL FOUR failure modes — invalid email format, user not found, no password
  set (SSO-only account), wrong password (`AuthRoutes.kt:197-216`). No account
  enumeration.
- Email is trimmed and lowercased server-side (`AuthService.kt:269`).
- **An SSO-only account returns the same 401** (`LoginResult.PasswordNotSet`,
  `AuthService.kt:292-294`). The web UI cannot tell the user "use Google" from
  this response.

### `POST /api/v1/auth/google` and `POST /api/v1/auth/apple`

Handlers `AuthRoutes.kt:240-252`, shared outcome mapper `AuthRoutes.kt:286-339`.

```kotlin
// GoogleLoginRequest.kt:6-8
data class GoogleLoginRequest(val idToken: String)
// AppleLoginRequest.kt:6-14
data class AppleLoginRequest(val idToken: String, val name: String? = null)
```

| Outcome                                            | Status       | code                                                                                  |
| -------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------- |
| success                                            | 200 + cookie | `{"user": PublicUser}` (`AuthRoutes.kt:353-356`)                                      |
| bad/unverifiable ID token                          | 401          | `unauthorized`, `"Invalid Google ID token"` / `"Invalid Apple ID token"` (`:300-304`) |
| provider says email unverified                     | 403          | `email_not_verified` (`:306-313`)                                                     |
| matched local account is unverified (link refused) | 403          | `account_email_not_verified` (`:315-322`)                                             |
| account disabled                                   | 403          | `account_disabled` (`:324-328`)                                                       |
| provider/JWKS unavailable                          | 503          | `service_unavailable` (`:330-337`)                                                    |

**These are native-client token flows, not browser OAuth redirect flows.** The
server verifies an ID token the client already obtained; there is no
`/auth/google/callback`, no state param, no redirect handling anywhere.

### `GET /api/v1/auth/me`

Handler `AuthRoutes.kt:156-163`.

- 200 → `{"user": PublicUser}`
- 401 → `{"code":"unauthorized","message":"Not authenticated"}` when the cookie
  is absent, unknown, revoked, expired, anonymous, or the user is soft-deleted
  (`AuthService.resolveSession`, `AuthService.kt:211-236`).
- No request body. `/me` is the correct "am I logged in?" probe for a web app,
  and it is exempt from the email-verification gate (see §3), so it answers 200
  with `emailVerified:false` for an unverified user.

### `POST /api/v1/auth/logout`

Handler `AuthRoutes.kt:165-177`. No request body.

- **204 No Content** always, cookie cleared (`maxAge = 0`), whether or not a
  cookie was present and whether or not the token matched a live session
  (`AuthRoutes.kt:167-176`, `AuthService.logout` swallows `NotFoundException`,
  `AuthService.kt:246-258`).
- This path is in `sessionExpiry.ignorePathPrefixes` so no renew job is enqueued
  (`rest-server.conf:46`).

### `POST /api/v1/auth/verify-email`

Handler `AuthRoutes.kt:218-238`.

```kotlin
// VerifyEmailRequest.kt:3-5
data class VerifyEmailRequest(val token: String)   // the RAW token from the email link
```

| Outcome           | Status | code / message                                                     |
| ----------------- | ------ | ------------------------------------------------------------------ |
| success           | 200    | `{"user": PublicUser}` with `emailVerified:true`                   |
| no matching token | 400    | `invalid_token`, `"Verification token is invalid"`                 |
| expired           | 400    | `token_expired`, `"Verification token has expired"`                |
| already consumed  | 400    | `token_already_used`, `"Verification token has already been used"` |

**No session/cookie required** — verify-email is authenticated by the token
alone.

### `POST /api/v1/auth/resend-verification`

Handler `AuthRoutes.kt:341-351`. **No request body.** Requires a session cookie.

- 401 `unauthorized` `"Not authenticated"` when no caller resolves.
- **204 No Content** otherwise — deliberately identical for "sent" and "already
  verified" (`AuthRoutes.kt:348-350`, `ResendResult`,
  `service/src/main/kotlin/ed/unicoach/auth/ResendResult.kt:4-10`).
- **NO RATE LIMIT.** Each call burns all outstanding tokens and issues a new one
  (`EmailVerificationService.resend`,
  `service/.../EmailVerificationService.kt:88-101`). A web client can loop this.
  A web slice should consider adding a limit; the server has no mechanism today.

### `POST /api/v1/auth/change-email`

Handler `AuthRoutes.kt:358-386`. Requires a session cookie.

```kotlin
// ChangeEmailRequest.kt:3-5
data class ChangeEmailRequest(val email: String)
```

- 401 `unauthorized` `"Not authenticated"` (`:359-363`)
- 200 → `{"user": PublicUser}` with `emailVerified:false` again (`:368-370`)
- 400 `validation_failed` `"Invalid email"`, `fieldErrors[0].field == "email"`
  (`:372-377`)
- 409 `conflict` `"Email already in use"` (`:379-384`)
- Side effects in one transaction: rewrite `users.email`, clear
  `email_verified_at`, burn outstanding tokens, issue a fresh token, enqueue the
  verification email (`AuthService.changeEmail`, `AuthService.kt:161-202`).

**Consequence for web:** after `change-email` the user is instantly unverified
and the email-verification gate starts 403-ing every non-auth endpoint (§3). The
web UI must handle that transition.

---

## 2. The session cookie

Set on register / login / google / apple (`AuthRoutes.kt:34-47`), cleared on
logout (`AuthRoutes.kt:49-60`):

```kotlin
// rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt:38-46
response.cookies.append(
  name = sessionConfig.cookieName,     // default "UNICOACH_SESSION"
  value = token,                        // raw 32-byte token, Base64URL no padding
  domain = sessionConfig.cookieDomain,  // = APP_DOMAIN
  path = "/",
  secure = sessionConfig.cookieSecure,  // = SESSION_COOKIE_SECURE
  httpOnly = true,                      // HARD-CODED
  extensions = mapOf("SameSite" to "Strict"),  // HARD-CODED
)
```

| Attribute          | Value                                                                                                | Where                                                               |
| ------------------ | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| name               | `UNICOACH_SESSION`, override `SESSION_COOKIE_NAME`                                                   | `rest-server/src/main/resources/rest-server.conf:32-33`             |
| value              | raw token: 32 secure-random bytes, Base64-URL, unpadded (43 chars)                                   | `service/src/main/kotlin/ed/unicoach/util/TokenGenerator.kt:9-13`   |
| HttpOnly           | **always true**, not configurable                                                                    | `AuthRoutes.kt:44`                                                  |
| SameSite           | **always `Strict`**, not configurable                                                                | `AuthRoutes.kt:45`                                                  |
| Secure             | `SESSION_COOKIE_SECURE`, required (no default)                                                       | `rest-server.conf:37-40`; `.env.dev:163` false, `.env.prod:67` true |
| Path               | `/` hard-coded                                                                                       | `AuthRoutes.kt:42`                                                  |
| Domain             | `APP_DOMAIN` — `localhost` dev (`.env.dev:147`), `uni.coach` prod (`.env.prod:34`)                   | `rest-server.conf:34-36`                                            |
| Max-Age / Expires  | **NOT SET on the set path** — a session cookie in the browser; only the clear path sets `maxAge = 0` | `AuthRoutes.kt:38-46` vs `:57`                                      |
| server-side expiry | `session.expiration = "7d"`, override `SESSION_EXPIRATION`                                           | `rest-server.conf:41-42`                                            |

```kotlin
// rest-server/src/main/kotlin/ed/unicoach/rest/auth/SessionConfig.kt:6-11
data class SessionConfig(
  val expiration: Duration, val cookieName: String,
  val cookieDomain: String, val cookieSecure: Boolean,
)
```

**WRONG-BY-DEFAULT #1 — "the cookie lasts 7 days in the browser".** It does not.
No `Max-Age`/`Expires` is emitted, so it is a **browser-session cookie**:
closing the browser logs the user out client-side even though the server row
lives 7 days. On iOS this is invisible (the app keeps its own cookie store). On
the web a "Remember me" experience requires ADDING `maxAge` to
`setSessionCookie`.

**WRONG-BY-DEFAULT #2 — Domain.** `Domain` is not just settable, it is already
the apex, so the cookie is shared across `api.`, `app.`, and `admin.`
subdomains. That is what makes an `app.<domain>` SPA + `api.<domain>` backend
work _for cookie scope_. It also means **`admin.<domain>` receives the same
cookie** — worth noting for the brief.

### Per-request validation

`ApplicationCall.resolveCaller`
(`rest-server/src/main/kotlin/ed/unicoach/rest/auth/CallerResolution.kt:41-54`):

1. Return a cached `ResolvedCaller` from `call.attributes` if present (`:45`).
2. Read `request.cookies[cookieName]`; absent → `null` (`:47`).
3. `TokenHash.fromRawToken(token)` — SHA-256 (`:48`).
4. `AuthService.resolveSession(hash)` (`:49`), which runs
   `SELECT * FROM sessions WHERE token_hash = ? AND is_revoked = false AND expires_at > NOW()`
   (`db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt:135`), then loads the
   user; null session / anonymous session (`userId == null`) / missing user all
   map to `null` (`AuthService.kt:211-236`).

There is **no Ktor `Authentication` plugin and no bearer-token path** for these
routes — each handler calls `resolveCaller` itself and emits its own 401.

### Expiry / sliding renewal

`SessionExpiryPlugin`
(`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/SessionExpiryPlugin.kt:24-77`):

- On `ResponseSent`, for any request that carried the cookie, with a **2xx**
  status, and whose path does not start with an ignored prefix (`/health`,
  `/api/v1/auth/logout` — `rest-server.conf:45-47`), it enqueues a
  `SESSION_EXTEND_EXPIRY` job with the Base64'd token hash (`:33-59`).
  Fire-and-forget; failures are logged only (`:63-74`).
- `SessionExpiryHandler`
  (`net/src/main/kotlin/ed/unicoach/net/handlers/SessionExpiryHandler.kt:19-83`)
  extends only if `expiresAt` is within `slidingWindowThreshold` (`:65-73`).
- `SessionsDao.extendExpiry` sets `expires_at = NOW() + INTERVAL '7 days'` —
  **literal, ignores `session.expiration`** (`db/.../SessionsDao.kt:209-231`,
  esp. `:217`).

There is **no refresh token and no cookie re-issue on renewal**: the browser's
cookie value never changes; only the DB row's `expires_at` moves.

---

## 3. The email-verification gate

`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/EmailVerificationGate.kt:32-52`,
installed at `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt:423`
(after the client-key gate, installed at `Application.kt:302`).

Rules, in order (`EmailVerificationGate.kt:36-51`):

1. **Exempt:** path `== "/healthz"` or `startsWith("/api/v1/auth/")` — the
   ENTIRE auth family, so login / me / resend-verification / verify-email /
   change-email stay reachable while unverified (`:37-40`).
2. No resolvable caller → pass through; the handler emits its own 401. The gate
   never turns an anonymous request into a 403 (`:42`).
3. `caller.user.emailVerifiedAt == null` → **403** and `finish()` (`:44-50`):

```json
{ "code": "email_not_verified", "message": "Email verification required." }
```

**So: every non-auth endpoint is gated.** `/api/v1/students`,
`/api/v1/conversations`, `/api/v1/colleges`, `/api/v1/college-list`,
`/api/v1/money-profile`, `/api/v1/coaching-usage`, `/api/v1/subscriptions`,
`/api/v1/vocabularies` — all 403 for an unverified caller (routes registered at
`rest-server/.../Routing.kt:91-100`). A web app MUST implement the "check your
email" wall.

State that flips it: `users.email_verified_at` set by
`UsersDao.markEmailVerified`
(`db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt:180`), called only from
`DbEmailVerifier.verify`
(`auth/src/main/kotlin/ed/unicoach/auth/DbEmailVerifier.kt:42`), and from the
SSO path `markVerifiedIfEmailMatches` (`service/.../AuthService.kt:594`). It is
cleared by `UsersDao.changeEmail` (`AuthService.kt:179`).

---

## 4. Email verification end to end

### Table

```sql
-- db/schema/0014.create-verification-tokens.sql:8-20
CREATE TABLE verification_tokens (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  user_id     UUID NOT NULL REFERENCES users(id),
  token_hash  BYTEA NOT NULL,     -- SHA-256 of the raw token; raw NEVER stored
  expires_at  TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ NULL    -- single-use marker, set once on verify
);
CREATE UNIQUE INDEX verification_tokens_token_hash_idx ON verification_tokens (token_hash);
CREATE INDEX verification_tokens_user_id_idx ON verification_tokens (user_id);
```

No OCC `version` column and no `_versions` history table — deliberate,
documented at `db/schema/0014.create-verification-tokens.sql:2-6`.

### Token generation and expiry

- Raw token: `TokenGenerator.generateToken()` — 32 bytes `SecureRandom`,
  Base64-URL unpadded
  (`service/src/main/kotlin/ed/unicoach/util/TokenGenerator.kt:9-13`). Same
  generator as session tokens.
- Stored as `TokenHash.fromRawToken(raw)` (SHA-256) —
  `EmailVerificationService.kt:46-47`.
- TTL: `emailVerification.tokenTtl = "24 hours"`, override
  `EMAIL_VERIFICATION_TOKEN_TTL`
  (`service/src/main/resources/service.conf:396-397`); read by
  `EmailVerificationConfig`
  (`service/src/main/kotlin/ed/unicoach/auth/EmailVerificationConfig.kt:29`) and
  applied as `Instant.now().plus(config.tokenTtl)`
  (`EmailVerificationService.kt:48`).

### Issue + enqueue (same transaction as the caller's work, RFC 96)

`EmailVerificationService.issueToken`
(`service/.../EmailVerificationService.kt:42-55`) and `.enqueue` (`:65-80`) both
take the caller's `SqlSession`. `enqueue` builds

```kotlin
EmailJobPayload(to = <email>, template = EmailTemplate.EMAIL_VERIFICATION,
                context = VerificationEmailContext(verifyToken = rawToken).asJson())
// -> queueService.enqueue(session, JobType.SEND_EMAIL, payload)   (:76)
```

`EmailTemplate` currently has exactly ONE constant: `EMAIL_VERIFICATION`
(`email/src/main/kotlin/ed/unicoach/email/EmailTemplate.kt:11-13`).

### Send path (queue-worker)

`JobType.SEND_EMAIL` → `EmailSendHandler`
(`email/src/main/kotlin/ed/unicoach/email/EmailSendHandler.kt:30-57`;
concurrency 4, maxAttempts 5, lock 2 min, timeout 30 s). It resolves the
renderer by template (`:36-44`) and sends via `EmailService`. Registration of
the one renderer:
`queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt:124` →
`EmailSendHandler(emailService, listOf(VerificationEmailRenderer(verifyUrlBase)))`.

### Template (copy lives in Kotlin, not a resource file)

`service/src/main/kotlin/ed/unicoach/auth/VerificationEmailRenderer.kt:24-66`:

- link = `"$verifyUrlBase?token=$verifyToken"` (`:34`)
- subject = `"Verify your email address"` (`:37`)
- body =
  `"Welcome to Unicoach. Confirm your email address by visiting:\n\n$link\n\nIf you did not create this account, you can ignore this message."`
  (`:51-52`)
- Plain text only. There is no HTML email and no template file anywhere.

`verifyUrlBase` default: `${publicWeb.urlBase}"/verify-email"` where
`publicWeb.urlBase = "http://"${APP_DOMAIN}":"${PUBLIC_WEB_PORT}` overridden by
`PUBLIC_WEB_URL_BASE` (`service/src/main/resources/service.conf:391-403`). Prod:
`PUBLIC_WEB_URL_BASE=https://app.$APP_DOMAIN` (`.env.prod:95`) →
`https://app.uni.coach/verify-email?token=...`. Escape hatch
`EMAIL_VERIFICATION_VERIFY_URL_BASE` still wins (`service.conf:402`).

**So the verification link already points at `app.<domain>` today**, served by
the server-rendered `public-web` module, NOT by the REST API:
`public-web/src/main/kotlin/ed/unicoach/web/Routing.kt:125-131`
(`GET /verify-email` renders a confirm form, side-effect-free, to survive
scanner prefetch) and `:137-146` (`POST /verify-email` consumes the token
in-process via `EmailVerifier`). A web slice that replaces `app.<domain>` with
an SPA **collides with this existing route** and must decide: keep the
server-rendered two-step page, or re-point the link and rebuild the two-step
(the GET must stay side-effect-free).

### Consume / classify

`DbEmailVerifier.verify`
(`auth/src/main/kotlin/ed/unicoach/auth/DbEmailVerifier.kt:28-66`):
compare-and-swap `VerificationTokensDao.consume`, then
`UsersDao.markEmailVerified`, then burn sibling tokens (`:41-44`). A zero-row
consume is classified by re-reading the row: `consumedAt != null` →
`AlreadyConsumed`; `expiresAt <= now` → `Expired`; else `InvalidToken`; no row →
`InvalidToken` (`:48-66`).

### Resend

`EmailVerificationService.resend` (`:88-101`): already verified →
`AlreadyVerified` no-op; otherwise `consumeAllForUser` + `issueToken` +
`enqueue`, atomic. **No rate limit, no cooldown, no per-user counter anywhere.**

---

## 5. PASSWORD RESET — DOES NOT EXIST

Verified by repo-wide grep for `reset`, `forgot`, `password_reset` across
`*.kt`, `*.sql`, `*.conf`, `*.md` (excluding vendored Terraform provider
changelogs and iOS SPM checkouts). **Zero hits relate to password recovery.**
Every `reset` hit is a test-DB reset, a coaching-budget `resetsAt`, or prose.
Specifically:

- No route: `AuthRoutes.kt:68-107` lists all nine auth routes; none is
  reset/forgot.
- No table: `db/schema/` has `0014.create-verification-tokens.sql` and nothing
  similar.
- No template: `EmailTemplate` has one constant (`EmailTemplate.kt:11-13`).
- No service method: `AuthService` public API is `register`, `changeEmail`,
  `resolveSession`, `getCurrentUser`, `logout`, `login`, `loginWithSso`
  (`service/.../AuthService.kt:50,161,211,244,246,260,335`). No `resetPassword`,
  no `setPassword`.
- No DAO method: `UsersDao` exposes `findById`, `findByIdForUpdate`,
  `findByEmail`, `findVersion`, `create`, `update`, `markEmailVerified`,
  `changeEmail`, `updatePhysicalRecord`, `delete`, `undelete`,
  `revertToVersion`, `list`, `listVersions`
  (`db/.../UsersDao.kt:58,73,110,120,134,152,180,222,252,273,286,300,382,406`).
  **There is no "set password" writer.** `password_hash` is written only at
  `create` (`UsersDao.kt:145`) and restored by the full-row OCC writer used by
  `revertToVersion` (`UsersDao.kt:342,349,361`).

**Consequence: a user who forgets their password today is permanently locked
out.** The web app makes this far more visible than iOS did.

### What a slice must ADD, mirroring email verification

Pattern to copy, file for file:

1. **Migration** — `db/schema/NNNN.create-password-reset-tokens.sql`, a
   near-verbatim copy of `db/schema/0014.create-verification-tokens.sql:8-20`:
   `id uuidv7()`, `created_at`, `row_created_at`,
   `user_id REFERENCES users(id)`, `token_hash BYTEA` (SHA-256, raw never
   stored), `expires_at TIMESTAMPTZ`, `consumed_at TIMESTAMPTZ NULL`, unique
   index on `token_hash`, index on `user_id`. No OCC version, no `_versions`.
   **Use a SHORTER TTL than verification's 24 h** — 1 h is the norm for reset.
2. **Models + DAO** —
   `db/src/main/kotlin/ed/unicoach/db/models/NewPasswordResetToken.kt` and
   `PasswordResetToken.kt` mirroring `NewVerificationToken`; a
   `PasswordResetTokensDao` with `create` / `consume` (compare-and-swap) /
   `findByTokenHash` / `consumeAllForUser`, mirroring `VerificationTokensDao` as
   used at `EmailVerificationService.kt:50-53,95` and
   `DbEmailVerifier.kt:32,43,52`.
3. **A `UsersDao` password writer** — this does not exist and must be added: an
   OCC `setPasswordHash(session, userId, currentVersion, PasswordHash)` in the
   isolated style already used for `markEmailVerified` (`UsersDao.kt:180`) /
   `changeEmail` (`UsersDao.kt:222`), which the doc at `UsersDao.kt:178` says is
   deliberately kept out of the generic `update` surface.
4. **Service** —
   `service/src/main/kotlin/ed/unicoach/auth/PasswordResetService.kt` shaped
   like `EmailVerificationService` (`:31-102`): `request(email)` burns
   outstanding reset tokens, issues a fresh one and enqueues the email **inside
   one transaction** (RFC 96 — the enqueue must be part of the request
   transaction, see `EmailVerificationService.kt:65-80` and
   `AuthService.kt:143-144`); `reset(rawToken,
   newPassword)` consumes
   single-use, validates the password against the SAME rules as
   `RegistrationValidator.kt:39-54`, hashes with `Argon2Hasher`
   (`AuthService.kt:41,71`), writes the hash, burns sibling reset tokens, **and
   revokes all live sessions for that user** (`SessionsDao` has `listByUser` at
   `db/.../SessionsDao.kt:68` and `revokeByTokenHash` at `:233` — a
   `revokeAllForUser` writer would need adding). Result types mirroring
   `VerifyEmailResult` (`auth/.../VerifyEmailResult.kt:6-19`): `Success` /
   `InvalidToken` / `Expired` / `AlreadyConsumed`, plus a validation case. A
   `PasswordResetConfig` mirroring `EmailVerificationConfig.kt:21-34` reading
   `passwordReset.tokenTtl` and `passwordReset.resetUrlBase` from `service.conf`
   (derive the default from `publicWeb.urlBase`, `service.conf:391-394`).
5. **Routes** — in `AuthRoutes.kt`, two new routes inside the existing
   `route("/api/v1/auth")` block (`:69-106`), each with
   `rejectUnsupportedMethods(HttpMethod.Post)`:
   - `POST /api/v1/auth/forgot-password` `{ "email": string }` → **204 No
     Content ALWAYS**, whether or not the address exists (no account enumeration
     — the same discipline as login's uniform 401 at `:212-216` and resend's
     uniform 204 at `:348-350`). Add a request-size override in
     `rest-server.conf` alongside `/api/v1/auth/register`'s 1 KiB
     (`rest-server.conf:11`).
   - `POST /api/v1/auth/reset-password`
     `{ "token": string, "password": string }` → 200 `{"user": PublicUser}` or
     204; errors reusing the EXISTING codes `invalid_token` / `token_expired` /
     `token_already_used` (`ErrorCode.kt:20-22`) at 400, and `validation_failed`
     with `fieldErrors[0].field == "password"` for a weak password. **No new
     `ErrorCode` constant is needed**; if one is added it must be lowercase
     snake_case or `ErrorCodeTest` fails (`ErrorCode.kt:5-12`). Both routes are
     automatically exempt from the email-verification gate because the exemption
     is the whole `/api/v1/auth/` prefix (`EmailVerificationGate.kt:38`). Both
     must be unauthenticated — do NOT call `resolveCaller`.
6. **Email template + renderer + worker wiring** — add `PASSWORD_RESET` to
   `EmailTemplate` (`email/.../EmailTemplate.kt:11-13`); add
   `service/src/main/kotlin/ed/unicoach/auth/PasswordResetEmailContext.kt` (a
   `@Serializable data class` with the raw token, mirroring
   `VerificationEmailContext.kt:12-15`) and `PasswordResetEmailRenderer`
   implementing `EmailTemplateRenderer` with `override val template`, building
   `"$resetUrlBase?token=$token"` and returning `RenderedEmail(subject, body)` —
   copy `VerificationEmailRenderer.kt:24-66` including its `EmailSubject.create`
   / `EmailBody.create` validation branches. Register it in the handler list at
   `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt:124` — note
   `EmailSendHandler` `require`s no duplicate template
   (`EmailSendHandler.kt:39-41`). **No new `JobType`** — email variants are
   distinguished by template, never by job type (`EmailTemplate.kt:5-9`).
7. **A landing page** — the reset link needs a page. Today `public-web` owns the
   equivalent, with a side-effect-free `GET /verify-email` + mutating
   `POST /verify-email` (`public-web/.../Routing.kt:80-83,125-146`) precisely so
   a mail scanner's prefetch cannot burn a single-use token. **A reset link has
   the same hazard and MUST use the same two-step shape.**
8. **Tests to mirror** —
   `auth/src/test/kotlin/ed/unicoach/auth/DbEmailVerifierTest.kt`
   (consume/expire/replay),
   `service/src/test/kotlin/ed/unicoach/auth/EmailVerificationServiceTest.kt`,
   `service/src/test/kotlin/ed/unicoach/auth/VerificationEmailRendererTest.kt`,
   `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt`, and the
   end-to-end
   `rest-server/src/test/kotlin/ed/unicoach/rest/EmailSendE2eTest.kt:229`.

---

## 6. Things I could NOT verify

- **Whether `UNICOACH_CLIENT_KEYS` is set in the live prod SSM parameter
  store.** It is absent from `.env.prod`, empty in `.env.template:120`, and
  absent from every `infra/*.tf`. `README.md:576` calls it "operator-seeded
  (gate off)". If an operator seeded it out of band, the gate is ON and every
  browser request 403s. **An operator must confirm before a web slice is
  planned.**
- **The actual value of `slidingWindowThreshold`** passed to
  `SessionExpiryHandler` (`net/.../SessionExpiryHandler.kt:21`) — I read the
  handler, not its construction site in the worker wiring.
- **Whether `SesEmailProvider` is live in prod** vs `LogOnlyEmailProvider`
  (`email/src/main/kotlin/ed/unicoach/email/EmailProviderFactory.kt` selects
  one); I did not read the factory's config key or the deployed value. If email
  does not actually send in an environment, both verification and any future
  reset are dead there.
- I did not run any build or test; all findings are from source reading only.
