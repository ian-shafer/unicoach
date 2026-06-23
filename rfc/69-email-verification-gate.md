# RFC 69: Email-Verification Gate + Error-Code Unification (Backend)

## Executive Summary

Registration and login issue a full session before the user proves control of
their email; `PublicUser.emailVerified` reports verification state but nothing
enforces it. This RFC enforces it: an authenticated user whose
`email_verified_at` is null is blocked from protected endpoints with **HTTP 403
`email_not_verified`**, except an allowlist keeping the verification lifecycle
reachable.

Enforcement is a `Plugins`-phase interceptor, `configureEmailVerificationGate`,
modeled on the existing `configureClientKeyGate` (`ClientKeyGate.kt`): it runs
before route handlers, resolves the caller, and rejects only an
authenticated-but-unverified caller on a non-exempt path. The resolved caller —
session row and user — is cached on `call.attributes`, so the gate and the
downstream handler share one lookup instead of resolving the session twice per
request. Exemption is secure-by-default — every path is gated except
`/api/v1/auth/*` and `/healthz` — so the verification lifecycle stays reachable
while unverified with zero per-route configuration.

The gate spans two route families with opposed error-code casing —
`StudentRoutes` emits UPPERCASE codes, `ConvoRoutes` emits lowercase — so a
single gate code would otherwise have to pick a side. This RFC removes the
dilemma by **unifying all REST error codes to lowercase snake_case**, the casing
already used by every family except `StudentRoutes`. A new `ErrorCode` enum
becomes the single source of truth for the wire code (rationale in Detailed
Design); a guard test and an `INVARIANTS.md` rule make the casing
unconstructible going forward. The five UPPERCASE student codes are lowercased —
a breaking wire change — so the iOS string matches that depend on them are
updated in lockstep, and `bin/test-fuzz` verifies its registered user so the
contract-fuzz suite still reaches the gated surface.

Out of scope: verify/resend mechanics (shipped), the change-email
implementation, and client work beyond the error-code string matches forced by
the casing change.

## Detailed Design

### Data Models

No schema change. The marker `users.email_verified_at` and its
`User.emailVerifiedAt:
Instant?` projection already exist; the gate reads
`emailVerifiedAt != null`, the same predicate `PublicUser.emailVerified` already
computes.

### `ErrorCode` enum — the wire-code source of truth

A closed enum of every REST error code, in `rest-server`'s models layer
alongside `ErrorResponse`. Each entry pairs an idiomatic Kotlin name with its
lowercase wire string, serialized by Jackson via `@get:JsonValue`:

```kotlin
enum class ErrorCode(@get:JsonValue val wire: String) {
  // Shared / auth / conversation families (already lowercase)
  UNAUTHORIZED("unauthorized"),
  VALIDATION_FAILED("validation_failed"),
  CONFLICT("conflict"),
  INVALID_TOKEN("invalid_token"),
  TOKEN_EXPIRED("token_expired"),
  TOKEN_ALREADY_USED("token_already_used"),
  NOT_FOUND("not_found"),
  STUDENT_PROFILE_REQUIRED("student_profile_required"),
  COACH_UNAVAILABLE("coach_unavailable"),
  COACH_FAILED("coach_failed"),

  // Student family — normalized from UPPERCASE by this RFC
  // VALIDATION_ERROR is a legacy synonym of VALIDATION_FAILED, kept distinct
  // here (casing-only change); a future RFC unifies the two.
  VALIDATION_ERROR("validation_error"),
  STUDENT_NOT_FOUND("student_not_found"),
  STUDENT_ALREADY_EXISTS("student_already_exists"),
  VERSION_CONFLICT("version_conflict"),

  // Cross-cutting plugins (already lowercase)
  BAD_REQUEST("bad_request"),
  PAYLOAD_TOO_LARGE("payload_too_large"),
  PERMANENT_ERROR("permanent_error"),
  INTERNAL_ERROR("internal_error"),
  FORBIDDEN("forbidden"),

  // Email-verification gate (new)
  EMAIL_NOT_VERIFIED("email_not_verified"),
}
```

`ErrorResponse.code` changes type from `String` to `ErrorCode`:

```kotlin
data class ErrorResponse(
  val code: ErrorCode,
  val message: String,
  val fieldErrors: List<FieldError>? = null,
)
```

The wire JSON is unchanged for every family except `StudentRoutes`, whose five
codes go UPPERCASE → lowercase. `StudentRoutes`'s former `UNAUTHORIZED` folds
into the shared `UNAUTHORIZED("unauthorized")` entry — auth and student now emit
the same `unauthorized` for an unauthenticated call.

The casing split that motivated this — `StudentRoutes` UPPERCASE, everything
else lowercase — was never a deliberate contract; it was one family's deviation
that drifted because codes were inline string literals with no shared
definition. The enum removes the free-string hole: a code that is not lowercase
snake_case cannot be added without editing the enum, and the guard test (below)
fails if it is. Student codes are normalized by **pure casing** — each keeps its
distinct identity (`validation_error`, not a merge into `validation_failed`) —
to bound the change to casing alone; the residual `validation_failed` /
`validation_error` synonym is left intact rather than introducing a second,
semantic break.

### Email-verification gate

A `Plugins`-phase interceptor that blocks authenticated-but-unverified callers:

```kotlin
fun Application.configureEmailVerificationGate(
  authService: AuthService,
  sessionConfig: SessionConfig,
)
```

Behaviour, in order, mirroring `configureClientKeyGate`'s structure
(`intercept(ApplicationCallPipeline.Plugins)`):

1. **Exempt paths pass unconditionally.** If `call.request.path()` equals
   `/healthz` or starts with `/api/v1/auth/`, the interceptor returns without
   acting. This is the entire allowlist: the whole auth family (register, login,
   me, logout, verify-email, resend-verification, and the future change-email)
   plus the liveness probe. Every other path is gated — new protected families
   are blocked by default until explicitly exempted. The exemption is the prefix
   `/api/v1/auth/` (trailing slash), so a bare `/api/v1/auth` would be gated; no
   such endpoint is registered, so this is inert.
2. **No resolvable caller passes.**
   `call.resolveCaller(authService,
   sessionConfig)` (below) returns `null` —
   a missing session cookie, or a cookie that `resolveSession` resolves to
   `null` — and the interceptor returns without acting. The downstream handler
   then applies its own authentication check and emits its existing
   `401 unauthorized` — the gate never converts an unauthenticated request into
   a 403.
3. **Authenticated + unverified is rejected.** When `resolveCaller` returns a
   caller and `caller.user.emailVerifiedAt == null`, respond `403` with
   `ErrorResponse(ErrorCode.EMAIL_NOT_VERIFIED, "Email verification required.")`
   and `finish()`. A verified caller falls through; its `ResolvedCaller` is
   already cached, so the handler reuses it without re-resolving.

Installation: the gate call,
`configureEmailVerificationGate(authService, sessionConfig)`, is inserted in
`appModule` at the existing `authService`-construction point just before
`configureRouting` — which already sits after `configureClientKeyGate` and
`configureStatusPages` (both run near the top). The resulting interceptor
registration order is what the Ordering mechanism below relies on.

Ordering mechanism: both gates register via
`intercept(ApplicationCallPipeline.Plugins)`. Same-phase interceptors run in
registration order, so installing the verification gate after the client-key
gate orders the client-key gate (coarse: is this a known client) ahead of the
verification gate (fine: is this verified user permitted). Correctness — an
unverified caller with a bad or absent client key gets the client-key gate's
`403 forbidden`, not `403 email_not_verified` — depends on this registration
order and is pinned by an `EmailVerificationGateTest` case.

### Caller resolution and per-call caching

The gate and every gated handler resolve the same caller; without sharing, each
gated request pays the `sessions`+`users` lookup twice (once in the gate, once
in the handler). Resolution is performed once and the result cached on
`call.attributes` for the request's lifetime. This mirrors `admin-server`'s
`installAdminGate` (`AdminAuth.kt`), which already resolves the caller in a
`Plugins`-phase gate and caches the resulting `User` on `call.attributes`
(`CurrentAdminKey`) for downstream handlers — the same pattern, generalized here
to carry the session and token hash alongside the user.

`AuthService` exposes the resolved session alongside the user. On the
verified-user path the session row was already read during resolution and
discarded; it is now returned:

```kotlin
data class AuthenticatedSession(val session: Session, val user: User)

suspend fun AuthService.resolveSession(tokenHash: TokenHash): Result<AuthenticatedSession?>
```

`AuthenticatedSession.user` is non-null, so `resolveSession` can only return a
populated `AuthenticatedSession` when both a live session row **and** its user
exist. Today `getCurrentUser` collapses three distinct user-absent outcomes to
`Result.success(null)`, and `resolveSession` must reproduce all three as
`Result.success(null)` — **not** an `AuthenticatedSession` with a null user,
which the type forbids:

1. **No session row** — `findByTokenHash` returns `NotFoundException` (token
   unknown, expired, or revoked).
2. **Anonymous session** — the row exists but `session.userId == null`.
3. **Soft-deleted user** — the row exists with a `userId`, but
   `UsersDao.findById` returns `NotFoundException` (the user was soft-deleted).

Only case (b)/(c) discards a real session row; that is acceptable, because a
session with no live user is not a resolved caller and the gate has nothing to
act on. `getCurrentUser` is retained as a thin delegate
(`resolveSession(tokenHash).map { it?.user }`) for the user-only callers in
`admin-server` (`AdminAuth.kt`, which reads `user.isAdmin`) and the exempt auth
handlers; removing it would pull those modules into this change for no benefit.
Because the three user-absent cases all map to `null` in both functions, the
delegate is behaviourally identical to today's `getCurrentUser` and the existing
`AuthServiceTest` cases (anonymous, invalid token, expired, soft-deleted) stay
green. A DB fault remains a failed `Result` (see the `StatusPages` note below
for how the closed-pool case is mapped).

The cached value, its attribute key, and the shared accessor live in
`rest-server`'s `auth` package:

```kotlin
data class ResolvedCaller(val tokenHash: TokenHash, val session: Session, val user: User)

val ResolvedCallerKey = AttributeKey<ResolvedCaller>("ResolvedCaller")

suspend fun ApplicationCall.resolveCaller(
  authService: AuthService,
  sessionConfig: SessionConfig,
): ResolvedCaller?
```

The receiver is `ApplicationCall`, invoked as `call.resolveCaller(...)`. This is
deliberate: the gate runs as an `intercept(ApplicationCallPipeline.Plugins)`
lambda whose receiver is the pipeline context (it reaches the call via `call`),
**not** a `RoutingContext`. The gated handlers (`StudentRoutes`, `ConvoRoutes`,
`AuthRoutes`) are `RoutingContext` extensions, but they too expose `call`. A
`RoutingContext`-receiver extension would be uncallable from the gate
interceptor; an `ApplicationCall` receiver is callable from both, so all callers
go through `call.resolveCaller(...)`.

`resolveCaller` is the single resolution path: it returns the cached
`ResolvedCaller` if `ResolvedCallerKey` is present; otherwise it reads the
session cookie, computes `TokenHash.fromRawToken(cookie)` once, calls
`resolveSession`, and — only on a non-null result — stores and returns a
`ResolvedCaller` carrying that `tokenHash`. A missing cookie or null resolution
returns `null` and caches nothing, so the cached type is non-null and "attribute
present" implies "identity resolved by an actual lookup." It resolves identity
only and asserts nothing about email verification — that stays the gate's
responsibility.

The gate, the gated handlers, and any future interceptor all call
`resolveCaller`; the first call on a request populates the attribute and the
rest read it. The "first populates, the rest read" contract holds because the
gate runs in the `Plugins` phase, which strictly precedes routing and handler
execution: on a gated path the gate's `resolveCaller` is the first call, so it
performs the one lookup and the handler reads the cached value. Because the gate
never runs on exempt paths, the attribute is absent there, so a handler calling
`resolveCaller` on an exempt path always falls back to a fresh lookup — secure
by default: no handler can mistake an un-run gate for a resolved caller. Both
properties — cache population order and the exempt-path fallback — depend on the
gate being a `Plugins`-phase interceptor registered in `appModule` before
`configureRouting`.

The gated handlers consume the cache through `resolveCaller`:
`StudentRoutes`/`ConvoRoutes` `resolveUser` become
`call.resolveCaller(authService, sessionConfig)?.user`;
`StudentRoutes.handleDelete` takes both `user` and `tokenHash` from the one
call, dropping its separate cookie→`tokenHash` derivation. The exempt
`AuthRoutes.handleMe` and `handleResendVerification` also call `resolveCaller`
(always a fallback resolution, exercising the secure-by-default path);
`handleLogout` resolves no user — it revokes by `tokenHash` even for an orphan
token — and keeps its own `tokenHash` path.

The gate installs after `configureStatusPages` (see Installation), so a DB
failure inside `resolveSession` propagates through `getOrThrow` to the
`StatusPages` handler, identical to a handler's own resolution failing; the
resulting status follows the same `StatusPages` branching as any handler. The
exact wire code depends on where the fault is raised relative to the DAO's error
mapping. `SessionsDao.findByTokenHash` wraps any exception it catches via
`mapDatabaseError`, which yields a `DatabaseException` (a `PermanentError` →
`500 permanent_error`) for a generic SQL state or a `TransientDatabaseException`
(a `TransientError` → `503 internal_error`) for a connection-class state
(`08…`). But a **closed connection pool** faults earlier:
`Database.withConnection` acquires `dataSource.connection` before the DAO's
`try` block runs, so the closed-pool `SQLException` never reaches
`mapDatabaseError`. It escapes `withConnection` raw, is caught by
`getCurrentUser`/`resolveSession`'s own outer `try` as
`Result.failure(rawSQLException)`, and `resolveCaller`'s `getOrThrow` rethrows
it unchanged. `StatusPages` then routes it through the generic-`Throwable`
branch (neither `PermanentError` nor `TransientError`) → `500 internal_error`.

### API Contracts

- **New rejection contract.** Any gated route, called by an authenticated user
  whose email is unverified, returns `403`
  `{"code":"email_not_verified","message":"Email verification required.","fieldErrors":null}`.
  One code across all gated families, lowercase, consistent with the
  cross-cutting `ClientKeyGate` 403 (`forbidden`).
- **Student-family codes change casing.** `UNAUTHORIZED → unauthorized`,
  `STUDENT_NOT_FOUND → student_not_found`,
  `VALIDATION_ERROR → validation_error`,
  `STUDENT_ALREADY_EXISTS → student_already_exists`,
  `VERSION_CONFLICT →
  version_conflict`. Status codes and messages are
  unchanged.
- **All other families unchanged on the wire.**
- **OpenAPI unchanged.** `api-specs/openapi.yaml` is intentionally not modified
  (and absent from Files Modified): its `ErrorResponse.code` stays
  `type: string` with no `enum`, since the Kotlin enum is the source of truth
  and a wire enum would couple the contract-fuzz response check to the full code
  set for no client benefit. The gate's cross-cutting 403 is not documented
  per-path, matching the undocumented `ClientKeyGate` 403.

### Error Handling / Edge Cases

- **Unauthenticated vs unverified.** The gate acts only on a resolved user, so
  an anonymous request to a gated route still receives the handler's `401`, not
  `403`. Order matters: missing-session is checked before the verified
  predicate.
- **Allowlist reachability while unverified.** `me` reports state, `logout`
  clears the session, `resend-verification` issues a fresh token, and
  `verify-email` consumes one — all under `/api/v1/auth/`, all exempt, so an
  unverified user can complete or abandon verification.
- **Existing unverified users are locked out on deploy.** Enforcement is
  unconditional — no config toggle, no backfill (a deliberate choice: a backfill
  would record an unverified address as verified, a factual lie). Every current
  user with `email_verified_at IS NULL` receives `403` on student and
  conversation routes until they verify via the exempt routes. Acceptable
  because verification shipped only in the immediately preceding work and the
  unverified population is the freshly-registered set, which already has a live
  token and a resend path.
- **Contract-fuzz reaches the gated surface.** `bin/test-fuzz` registers a user
  and injects its cookie into every request; that user is unverified, so without
  intervention every `students` operation would return `403` and Schemathesis
  would report drift against the spec. The harness gains a post-registration
  step that marks the user verified by direct SQL on the fuzz DB
  (`UPDATE users SET email_verified_at = NOW() WHERE email = …`), after which
  the gated routes return their documented statuses. This verify SQL is the only
  fuzz change this RFC makes; the pre-existing `/api/v1/conversations*`
  exclusion (`--exclude-path-regex conversations`) is independent of the gate:
  the fuzz user has no student profile, so every conversation operation returns
  `409 student_profile_required` rather than its documented status, and the
  family is streaming/stateful/destructive (SSE turn streams, conversation
  create/delete, coach-invoking message posts) — none of which the static-cookie
  contract referee exercises. That exclusion predates the gate and is unrelated
  to it.
- **Guard against future casing drift.** A unit test asserts every
  `ErrorCode.wire` matches `^[a-z][a-z0-9_]*$`; combined with the enum-typed
  `ErrorResponse.code`, a mis-cased or stringly-typed code cannot reach the
  wire.

### Dependencies

No new modules or third-party libraries. `AuthService.getCurrentUser` (retained;
`resolveSession` is added alongside it), `SessionConfig`, `TokenHash`,
`ClientKeyGate` (as the structural model), `StatusPages`, and the fuzz harness
all exist. The iOS client is updated in this repo but built and tested
out-of-band via `xcodebuild` (`bin/build-ios`), not the Kotlin/Gradle pipeline.

## Tests

### `rest-server` — `ErrorCodeTest` (new)

- Every `ErrorCode.wire` matches `^[a-z][a-z0-9_]*$` (the casing invariant,
  mechanically enforced over `ErrorCode.values()`).
- `ErrorCode.wire` values are distinct (no two entries serialize to the same
  string).
- An `ErrorResponse(ErrorCode.EMAIL_NOT_VERIFIED, …)` serializes to JSON whose
  `code` is the string `"email_not_verified"` (confirms `@JsonValue` wiring).

### `rest-server` — `EmailVerificationGateTest` (new)

Boots the server (the `*RoutingTest` pattern) and exercises the gate against a
representative gated route (`GET /api/v1/students/me`) and the exempt routes.

- Unverified authenticated user → gated route returns `403` and body contains
  `email_not_verified`.
- Verified authenticated user → same route passes the gate (returns its normal
  status, not `403`).
- No session cookie → gated route returns `401 unauthorized` (handler path), not
  `403` — the gate does not hijack unauthenticated requests.
- Invalid/garbage cookie → `401 unauthorized`, not `403`.
- Unverified user reaches every exempt route: `GET /api/v1/auth/me` → `200`;
  `POST /api/v1/auth/resend-verification` → `204`;
  `POST /api/v1/auth/verify-email` with a bogus token → `400 invalid_token`
  (reached the handler, not gated); `POST /api/v1/auth/logout` → success;
  `GET /healthz` → `200`.
- Unverified user on a second gated family (`POST /api/v1/conversations`) →
  `403 email_not_verified`, confirming one code across both families.
- After an unverified user verifies (consume the issued token via
  `POST /api/v1/auth/verify-email`), the previously-`403`
  `GET /api/v1/students/me` passes the gate.
- Client-key gate fires before the verification gate: an unverified
  authenticated user with a bad or absent client key on a gated route returns
  `403 forbidden` (the client-key gate's code), not `403 email_not_verified`.
  Pins the same-phase registration order asserted in Detailed Design.
- A forced `resolveSession` failure on a gated route returns
  `500 internal_error`, confirming the `getOrThrow` throw is caught by the
  `StatusPages` handler installed before the gate rather than escaping the
  pre-handler intercept. Pin the mechanism: close the Hikari pool. The closed
  pool faults at `Database.withConnection`'s `dataSource.connection`
  acquisition, before `SessionsDao.findByTokenHash`'s `try`/`mapDatabaseError`
  runs, so the `SQLException` escapes raw — it is **not** wrapped into a
  `DatabaseException` (`PermanentError` → `500 permanent_error`) or a
  `TransientDatabaseException` (`TransientError` → `503`). `resolveSession`'s
  outer `try` returns it as a failed `Result`, `resolveCaller`'s `getOrThrow`
  rethrows it, and `StatusPages` routes the raw `SQLException` through its
  generic-`Throwable` branch → `500 internal_error`. Assert the body `code` is
  `internal_error`, not `permanent_error`. The standard `*RoutingTest` boot path
  (`startServer(wait = false, port = 0)`) constructs the `Database` internally
  and returns only an `EmbeddedServer`, exposing no `Database.close()` handle —
  so this case must wire its own `Database` under
  `testApplication { appModule(…) }` (precedent: `SessionExpiryPluginTest`) and
  call `database.close()` on it to force the closed-pool fault. Because the test
  owns and closes its own `Database`, it cannot poison the shared
  `@BeforeAll`-booted server.

### `service` — `AuthServiceTest` (extend)

- `resolveSession` returns `AuthenticatedSession(session, user)` for a live
  token — the `session` matches the row created at registration/login, the
  `user` matches the account.
- `resolveSession` returns `null` for an unknown/garbage token and for an
  expired session (parity with the retained `getCurrentUser` cases).
- `resolveSession` returns `null` for an **anonymous session row**
  (`userId == null`) — proving the user-absent-but-row-present case maps to
  `null` rather than an `AuthenticatedSession` with a missing user. (The
  soft-deleted-user case is already covered by the retained `getCurrentUser`
  test; `resolveSession` shares the same code path.)
- The existing `getCurrentUser` cases remain green, proving the delegate
  preserves the user-only contract across all three user-absent sub-cases.

### `rest-server` — `CallerResolutionTest` (new)

`testApplication` with a route that invokes `resolveCaller`.

- **Cache hit short-circuits resolution.** With `ResolvedCallerKey`
  pre-populated, `resolveCaller` backed by an `AuthService` over a closed pool
  returns the cached `ResolvedCaller` and never throws — proving it does not
  re-resolve.
- **Miss resolves and caches.** Empty attributes plus a seeded valid session →
  `resolveCaller` returns the caller and `ResolvedCallerKey` is now present.
- **Reuse within a call.** Two `resolveCaller` calls on one request return the
  same value; closing the pool after the first leaves the second unaffected.
- **No cookie.** Empty attributes, no cookie → `null`, attribute absent (the
  fallback path, secure-by-default).
- **Present-but-invalid cookie caches nothing.** Empty attributes plus a cookie
  whose token `resolveSession` resolves to `null` (no matching session) →
  `resolveCaller` returns `null` **and** `ResolvedCallerKey` is absent on
  `call.attributes` afterward. Directly proves the "cache only on a non-null
  resolution" rule — distinct from the no-cookie case, which never calls
  `resolveSession`.

### `rest-server` — `StudentRoutingTest` (extend)

Update the six existing `bodyAsText().contains("…")` student-code assertions to
their lowercase forms: `validation_error` (×2), `student_not_found` (×2),
`student_already_exists` (×1), `version_conflict` (×1). The file has no
`UNAUTHORIZED` body assertion — its `401` cases assert status only — so no
`unauthorized` string change applies here. These assertions are the regression
proof that the student family now emits lowercase.

### `rest-server` — `AuthRoutingTest`, `ConvoRoutes` tests (unchanged)

Already assert lowercase codes; the `ErrorResponse.code` retype is transparent
to `bodyAsText().contains(...)` assertions. Recompilation is the only effect.

### iOS — out-of-band (`xcodebuild`)

- `StudentClientTests` and the affected view-model tests update their
  server-student-code fixtures to lowercase; client-synthesized UPPERCASE codes
  (`TIMEOUT`, `NETWORK_ERROR`, `SERVER_ERROR`, `VALIDATION`) are left untouched.
- The app-logic branches that match `UNAUTHORIZED` / `STUDENT_ALREADY_EXISTS`
  match their lowercase forms; the corresponding view-model tests that drive
  those branches feed lowercase fixtures.
- Verified by `nix develop -c bin/build-ios` (or `xcodebuild` against the
  `UnicoachiOS` scheme); this is not covered by `bin/test`.

### Contract fuzz — `bin/test-fuzz`

After the harness marks its registered user verified, the full `--checks all`
run over the seven implemented, non-destructive operations exits `0` (the gated
`students` operations return their documented statuses rather than `403`).

## Implementation Plan

Run all Kotlin/DB commands inside the Nix dev shell. Verify with
`nix develop -c bin/test --force rest-server` (plain runs may be all-cache
no-ops). iOS steps run outside the dev shell.

1. **`ErrorCode` enum.** Add
   `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt` with the
   full closed set and `@get:JsonValue` wire strings.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

2. **Retype `ErrorResponse.code`.** Change `code: String` → `code: ErrorCode` in
   `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorResponse.kt`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin` (this now
     fails at every construction site — fixed in steps 3–4).

3. **Migrate plugin construction sites.** Replace string literals with
   `ErrorCode` members in `plugins/ClientKeyGate.kt` (`FORBIDDEN`) and
   `plugins/StatusPages.kt` (`BAD_REQUEST`, `PAYLOAD_TOO_LARGE`,
   `PERMANENT_ERROR`, `INTERNAL_ERROR`).
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

4. **Migrate routing construction sites.** Replace literals with `ErrorCode`
   members in `routing/AuthRoutes.kt`, `routing/ConvoRoutes.kt`, and
   `routing/StudentRoutes.kt`. The student sites adopt the lowercased entries
   (`UNAUTHORIZED`, `VALIDATION_ERROR`, `STUDENT_NOT_FOUND`,
   `STUDENT_ALREADY_EXISTS`, `VERSION_CONFLICT`).
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

5. **`ErrorCode` guard test.** Add
   `rest-server/src/test/kotlin/ed/unicoach/rest/models/ErrorCodeTest.kt`
   (casing regex over `values()`, distinct wire strings, `email_not_verified`
   serialization).
   - Verify:
     `nix develop -c bin/test --force rest-server --tests "ed.unicoach.rest.models.ErrorCodeTest"`.

6. **`INVARIANTS.md`.** Add
   `rest-server/src/main/kotlin/ed/unicoach/rest/models/INVARIANTS.md` with one
   Rule + Why: all REST error codes are lowercase snake_case, defined only in
   `ErrorCode`; `ErrorResponse.code` is `ErrorCode`-typed so the wire form
   cannot be stringly constructed or mis-cased.
   - Verify:
     `nix develop -c deno fmt --check rest-server/src/main/kotlin/ed/unicoach/rest/models/INVARIANTS.md`.

7. **`resolveSession` + delegate.** Add `AuthenticatedSession` and
   `resolveSession` to
   `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`; reduce
   `getCurrentUser` to delegate to it.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`.

8. **`AuthServiceTest`.** Add the `resolveSession` cases to
   `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` — live token,
   unknown/expired token, and the anonymous-session-row (`userId == null`) →
   `null` case.
   - Verify:
     `nix develop -c bin/test --force service --tests "ed.unicoach.auth.AuthServiceTest"`.

9. **Caller-resolution helper.** Add
   `rest-server/src/main/kotlin/ed/unicoach/rest/auth/CallerResolution.kt`
   (`ResolvedCaller`, `ResolvedCallerKey`, `ApplicationCall.resolveCaller`).
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

10. **Adopt `resolveCaller` in handlers.** Rewrite `resolveUser` in
    `routing/StudentRoutes.kt` and `routing/ConvoRoutes.kt` to delegate to
    `resolveCaller`; switch `StudentRoutes.handleDelete` to take `user` and
    `tokenHash` from it; switch `AuthRoutes.handleMe` and
    `handleResendVerification` to `resolveCaller`. `handleLogout` is unchanged.
    - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

11. **Gate plugin.** Add
    `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/EmailVerificationGate.kt`
    (`configureEmailVerificationGate(authService, sessionConfig)`) with the
    exempt-path / no-caller / unverified logic, resolving via `resolveCaller`.
    - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

12. **Wire the gate.** In
    `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`'s `appModule`,
    insert `configureEmailVerificationGate(authService, sessionConfig)` at the
    existing `authService`-construction point (lower in `appModule`, already
    after both `configureClientKeyGate` and `configureStatusPages`), immediately
    before the `configureRouting(...)` call.

- Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`.

13. **`CallerResolutionTest`.** Add
    `rest-server/src/test/kotlin/ed/unicoach/rest/auth/CallerResolutionTest.kt`
    (cache-hit short-circuit, miss-resolves-and-caches, reuse-within-call,
    no-cookie fallback, present-but-invalid-cookie caches nothing).
    - Verify:
      `nix develop -c bin/test --force rest-server --tests "ed.unicoach.rest.auth.CallerResolutionTest"`.

14. **Gate tests + student-routing casing.** Add
    `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/EmailVerificationGateTest.kt`;
    update the lowercase assertions in
    `rest-server/src/test/kotlin/ed/unicoach/rest/StudentRoutingTest.kt`.
    - Verify: `nix develop -c bin/test --force rest-server`.

15. **Fuzz harness.** In `bin/test-fuzz`, after the user is registered and the
    session captured, mark the user verified via `psql` against the fuzz DB
    (`UPDATE users SET email_verified_at = NOW() WHERE email = "$FUZZ_EMAIL";`)
    before invoking Schemathesis.
    - Verify: `nix develop -c bin/test-fuzz` (exits `0`).

16. **iOS app-logic string matches.** In
    `ios-app/UnicoachiOS/AppViewModel.swift` flip the `"UNAUTHORIZED"` branch in
    `resolveProfileState` (the student-route path via
    `studentClient.fetchProfile()`) to `"unauthorized"` — after the casing
    unification the student route emits lowercase, so leaving it UPPERCASE would
    fall through to `.serverError` and misclassify the unauthenticated state.
    Leave `checkSession`'s existing `"unauthorized"` branch as is — it matches
    the auth route (`authClient.me()`), which is already lowercase and
    unaffected by this RFC. In `ios-app/UnicoachiOS/OnboardingViewModel.swift`
    change `"STUDENT_ALREADY_EXISTS"` to `"student_already_exists"`. Leave
    client-synthesized UPPERCASE codes untouched.
    - Verify: `nix develop -c bin/build-ios` (out-of-band; not part of
      `bin/test`).

17. **iOS test fixtures.** Lowercase the server-student-code fixtures and
    assertions in `ios-app/UnicoachiOSTests/StudentClientTests.swift`,
    `ios-app/UnicoachiOSTests/AppViewModelTests.swift`, and
    `ios-app/UnicoachiOSTests/OnboardingViewModelTests.swift`. Do not alter
    client-synthesized codes.
    - Verify: `nix develop -c bin/build-ios` plus the iOS test scheme via
      `xcodebuild` (out-of-band).

18. **Full gate.** `nix develop -c bin/test check` (tests + ktlint) and
    `nix develop -c bin/test-fuzz`.

## Files Modified

Created:

- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorCode.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/INVARIANTS.md`
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/EmailVerificationGate.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/auth/CallerResolution.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/models/ErrorCodeTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/EmailVerificationGateTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/auth/CallerResolutionTest.kt`

Modified:

- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorResponse.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/ConvoRoutes.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/ClientKeyGate.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt`
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/StudentRoutingTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`
- `bin/test-fuzz`
- `ios-app/UnicoachiOS/AppViewModel.swift`
- `ios-app/UnicoachiOS/OnboardingViewModel.swift`
- `ios-app/UnicoachiOSTests/StudentClientTests.swift`
- `ios-app/UnicoachiOSTests/AppViewModelTests.swift`
- `ios-app/UnicoachiOSTests/OnboardingViewModelTests.swift`
