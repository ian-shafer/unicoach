# RFC 70: Change-email flow (backend)

## Executive Summary

A user who mistyped their email at registration is stranded: the verification
link went to an address they do not control, and
`POST
/api/v1/auth/resend-verification` can only re-send to the same wrong
address. This RFC adds `POST /api/v1/auth/change-email`, an authenticated
endpoint that rewrites the session user's email and re-arms verification.

In one transaction the endpoint: rewrites `users.email`, clears
`email_verified_at` back to `NULL`, and burns the user's outstanding
verification tokens; then issues a fresh token and best-effort delivers it to
the new address post-commit. It mirrors the registration path exactly
(`UsersDao` write + `EmailVerificationService.issueToken` inside the
transaction, `sendVerificationEmail` after commit).

The write reuses the existing active-user uniqueness guarantee
(`users_email_unique_active_idx`); a collision surfaces as a clean
`409
conflict`, identical to registration's duplicate-email response. A new
`UsersDao` writer is required because today's `markEmailVerified` only sets
`email_verified_at` to a non-null timestamp — nothing clears it back to `NULL`.

No schema change: `verification_tokens` is not extended with a purpose/type
column. There is a single token kind, and `consumeAllForUser` already
invalidates every outstanding token for the user, which is exactly the required
semantics.

Out of scope: gate enforcement (blocking unverified users from product surfaces)
and all client/iOS UI.

## Detailed Design

### Data model

No migration. The flow uses the schema as it stands:

- `users.email_verified_at TIMESTAMPTZ NULL` — set back to `NULL` by the new
  writer; the existing `log_user_version()` trigger captures the change into
  `users_versions` automatically (no trigger edit).
- `users_email_unique_active_idx` — the partial unique index
  (`UNIQUE (email) WHERE deleted_at IS NULL`) that enforces active-user
  uniqueness. The write relies on it; collisions raise SQLSTATE `23505`.
- `verification_tokens` — unchanged. Burning the user's outstanding tokens uses
  the existing `VerificationTokensDao.consumeAllForUser`. A purpose/type column
  would only be justified if two token kinds had to coexist for one user; they
  do not, so it is omitted.

### New DAO writer: `UsersDao.changeEmail`

A versioned conditional writer that rewrites the email and resets verification
in a single statement:

```kotlin
fun changeEmail(
  session: SqlSession,
  id: UserId,
  newEmail: EmailAddress,
): Result<User>
```

```sql
UPDATE users
SET version = version + 1, email = ?, email_verified_at = NULL
WHERE id = ? AND deleted_at IS NULL
RETURNING *
```

It mirrors `markEmailVerified`: `mapError = ::mapCreateUpdateError` (so a
`23505` on `users_email_unique_active_idx` maps to `DuplicateEmailException`),
`onNoRow = { NotFoundException() }`. It is an isolated writer, not routed
through the generic `update`/`UserEdit` surface — the same isolation
`markEmailVerified` and the auth-column writers have.

It uses a `version = version + 1` conditional update rather than an OCC
(`WHERE version = ?`) write: the caller holds a freshly-read session user, and
concurrent double-submits are self-correcting (each burns the other's token via
`consumeAllForUser`), so an OCC lost-update rejection would add a failure mode
with no correctness benefit.

### Service layer: `AuthService.changeEmail`

```kotlin
suspend fun changeEmail(
  user: User,
  newEmail: String,
): Result<ChangeEmailResult>
```

Structurally identical to `register`'s transaction shape:

1. Normalize/validate `newEmail` via `EmailAddress.create` (which trims and
   lowercases — the same normalization registration stores under, so uniqueness
   is compared on equal footing). Invalid →
   `ChangeEmailResult.ValidationFailure`.
2. In `database.withConnection`:
   - `UsersDao.changeEmail(session, user.id, emailAddr)`; a
     `DuplicateEmailException` →
     `ChangeEmailResult.DuplicateEmail(emailAddr.value)`.
   - `VerificationTokensDao.consumeAllForUser(session, user.id)` — invalidate
     outstanding tokens (any in-flight link to the old address is dead).
   - `EmailVerificationService.issueToken(session, user.id)` — capture the raw
     token for post-commit delivery (atomic with the email rewrite).
3. Post-commit, best-effort:
   `EmailVerificationService.sendVerificationEmail(
   updatedUser.email, rawToken)`.
   A send failure does not fail the request — the user can resend (now to the
   corrected address).
4. Return `ChangeEmailResult.Success(updatedUser)` (with
   `emailVerifiedAt ==
   null`).

`AuthService` already holds `emailVerificationService`; `UsersDao` and
`VerificationTokensDao` are called statically as elsewhere in the class. No new
constructor dependency, so the DI wiring in `Application.kt` is untouched.

New result type `ChangeEmailResult` (sibling of `RegisterResult`):

```kotlin
sealed interface ChangeEmailResult {
  data class Success(val user: User) : ChangeEmailResult
  data class ValidationFailure(val message: String) : ChangeEmailResult
  data class DuplicateEmail(val email: String) : ChangeEmailResult
}
```

`ValidationFailure` carries a single `message`, not
`RegisterResult.ValidationFailure`'s `(errors, fieldErrors)` pair: this endpoint
validates one field via `EmailAddress.create`, which yields exactly one error,
so the handler wraps it into a single `FieldError("email", message)` (see the
`400` row of the API contract).

### API contract

`POST /api/v1/auth/change-email`, registered under the existing `/api/v1/auth`
route group in `AuthRouteHandler`, authenticated via the session cookie exactly
as `resend-verification` and `me` are (read cookie → `TokenHash` →
`authService.getCurrentUser`).

Request:

```kotlin
data class ChangeEmailRequest(val email: String)
```

Success response (`200 OK`):

```kotlin
data class ChangeEmailResponse(val user: PublicUser)
```

`PublicUser.emailVerified` is `false` in this response (the user was just reset
to unverified).

Error responses (auth-route family → lowercase `code`):

| Condition                              | Status | Body                                                                                               |
| -------------------------------------- | ------ | -------------------------------------------------------------------------------------------------- |
| No/invalid session cookie              | `401`  | `ErrorResponse("unauthorized", "Not authenticated")`                                               |
| `newEmail` fails `EmailAddress.create` | `400`  | `ErrorResponse("validation_failed", "Invalid email", [FieldError("email", <msg>)])`                |
| Active-user email collision            | `409`  | `ErrorResponse("conflict", "Email already in use", [FieldError("email", "Email already in use")])` |
| Method ≠ POST                          | `405`  | via `rejectUnsupportedMethods(HttpMethod.Post)` (`Allow: POST`)                                    |
| Other DB constraint violation          | `500`  | propagated via `getOrThrow` (e.g. `23514` length, or a `23505` not on the active-email index)      |

The `409` body matches `respondRegisterDuplicateEmail`'s output (that function
is private to `AuthRouteHandler`; the body is replicated, not invoked), keeping
the duplicate-email contract uniform across registration and change-email.

### Error handling / edge cases

- **New email equals current email**: processes normally — version bumps,
  `email_verified_at` resets to `NULL`, a fresh token is issued and sent. The
  partial unique index does not fire (the row collides only with itself).
  Treated as a valid re-arm, not an error. The verification email is
  deliberately re-sent to the already-correct address: re-arming verification
  doubles as a resend, and special-casing it would add a branch with no benefit.
- **Collision against a soft-deleted user's email**: not a collision — the index
  is partial (`WHERE deleted_at IS NULL`), so only active users constrain the
  new address. The write succeeds.
- **Post-commit send failure**: swallowed and logged inside
  `sendVerificationEmail` (existing behavior); the request still returns `200`.
  The email rewrite and token issuance already committed.
- **User row absent** (session valid but user hard-gone): `changeEmail` returns
  `NotFoundException`, propagated as a `getOrThrow` failure (500-class) — the
  same posture `getCurrentUser`-backed handlers already have.
- **Email length/format beyond interior-`@`**: out of scope.
  `EmailAddress.create` enforces only a non-blank interior `@`; it does not cap
  length. A valid-format address exceeding 254 chars trips the DB
  `users_email_length_check` (SQLSTATE `23514`), which `mapCreateUpdateError`
  maps to `ConstraintViolationException` — propagated via `getOrThrow` as a 500.
  This inherits register's behavior exactly; tightening it is not part of this
  RFC.

### Authentication: session cookie only, no re-auth

Any authenticated user — verified or not — may change their email with only a
live session; the request carries no password re-entry. The new address is
always reset to unverified and must be verified before it grants any gated
capability, so the session cookie is the sole gate here and re-authentication
can be layered later without changing this contract (gate enforcement is a
separate RFC).

### Dependencies

No new libraries. Reuses `EmailAddress`, `UsersDao`, `VerificationTokensDao`,
`EmailVerificationService` (`issueToken`, `sendVerificationEmail`),
`AuthService.getCurrentUser`, `PublicUser`, `ErrorResponse`, `FieldError`,
`rejectUnsupportedMethods`, and the existing `EmailVerificationConfig`.

## Tests

### `UsersDaoTest` (db) — `changeEmail`

- **rewrites email and clears verification**: seed a verified user
  (`markEmailVerified`), call `changeEmail` with a new address; assert returned
  `email` is the new value, `emailVerifiedAt == null`, and `version` incremented
  by 1.
- **clears verification for an unverified user**: unverified user →
  `changeEmail` → email updated, still unverified, version bumped.
- **history captured**: after `changeEmail`, `listVersions` contains a new row
  whose `email` is the new address and whose `emailVerifiedAt` is null.
- **collision raises DuplicateEmailException**: two active users A and B;
  `changeEmail(A, B.email)` fails with `DuplicateEmailException`; A's row is
  unchanged.
- **collision excludes soft-deleted**: soft-delete user B, then
  `changeEmail(A, B.email)` succeeds.
- **same-email re-arm**: `changeEmail(A, A.email)` succeeds, clears
  verification, bumps version.
- **absent user raises NotFoundException**: `changeEmail` on a random `UserId` →
  `NotFoundException`.

### `AuthServiceTest` (service) — `changeEmail`

Mirrors the existing harness in this file: an `AuthService` built over a real
`EmailService` backed by the log provider, with sends verified by querying the
`email_sends` table (no in-memory sink exists). The send-failure case swaps in
`RejectingProvider`, exactly as
`register succeeds even when the email provider
rejects the send` does.
Recipient is read from `email_sends.recipient_email`; token-state counts use
`verification_tokens` queries via the existing `countRows` helper.

- **success path**: registered user → `changeEmail` with a valid new email;
  assert `ChangeEmailResult.Success` with updated email and
  `emailVerifiedAt == null`; assert a verification email was sent to the **new**
  address by querying `email_sends.recipient_email` for the new value; assert
  exactly one unconsumed token now exists for the user and the pre-existing
  token is consumed.
- **outstanding tokens burned**: issue an extra token before the call; after
  `changeEmail` the old token is `consumed_at`-stamped and a new unconsumed
  token exists.
- **duplicate email**: second user owns the target address →
  `ChangeEmailResult.DuplicateEmail`; first user's email and verified-state
  unchanged; no new `email_sends` row for the target address.
- **invalid email**: `changeEmail(user, "not-an-email")` →
  `ChangeEmailResult.ValidationFailure`; no DB mutation; no new `email_sends`
  row.
- **normalization**: `changeEmail(user, "  New@Example.COM ")` stores
  `new@example.com`.
- **same-email re-arm**: `changeEmail(user, user.email)` (a verified user
  re-submitting their current address) → `ChangeEmailResult.Success` with
  `emailVerifiedAt == null`; the pre-existing token is consumed and exactly one
  fresh unconsumed token now exists (verification re-armed, not an error).
- **send failure is non-fatal**: build the `AuthService` over
  `RejectingProvider` (mirroring `authServiceWithRejectingEmail`); `changeEmail`
  still returns `Success` and the email rewrite and token issuance are
  committed.

### `EmailVerificationRoutingTest` (rest-server) — `change-email`

This suite already authenticates via the session cookie pair returned by
`registerUser` and persists only the token hash; the raw token survives solely
in the delivered email. The change-email cases therefore recover the new raw
token the same way the live verify flow would: read the latest
`email_sends.body` for the new address and parse the `?token=` query value out
of the verify link (`EmailVerificationService.sendVerificationEmail` builds
`"${config.verifyUrlBase}?token=$rawToken"`). No raw token is inserted via
`insertToken` for the change-email path — that helper exists only because the
verify/resend tests fabricate a token without an email.

- **200 on success**: authenticated request with a valid new email → `200`, body
  `ChangeEmailResponse` with the new email and `emailVerified == false`.
- **401 unauthenticated**: no session cookie → `401 unauthorized`.
- **401 stale session**: cookie present but unknown token → `401 unauthorized`.
- **400 invalid email**: authenticated, body `{"email":"bogus"}` →
  `400
  validation_failed` with a `email` field error.
- **409 duplicate**: authenticated user requests an email already held by
  another active user → `409 conflict` with a `email` field error.
- **405 wrong method**: `GET /api/v1/auth/change-email` → `405` with
  `Allow: POST` (matching the sibling `verify-email`/`resend-verification` 405
  tests).
- **end-to-end re-verify**: change email, recover the new raw token by parsing
  `?token=` out of the latest `email_sends.body` row for the new address, then
  `POST /verify-email` with it → `200` and the user is verified at the new
  address.

## Implementation Plan

1. **Add `UsersDao.changeEmail`.** Implement the versioned conditional writer
   (SQL above) in `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`, next to
   `markEmailVerified`, using `mutateReturning` with
   `mapError = ::mapCreateUpdateError` and `onNoRow = { NotFoundException() }`.
   - Verify:
     `nix develop -c bin/test db --tests "ed.unicoach.db.dao.UsersDaoTest"`

2. **Add `UsersDaoTest` cases** for `changeEmail` (rewrite+clear, history,
   collision, soft-delete exclusion, same-email, not-found) in
   `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt`.
   - Verify:
     `nix develop -c bin/test db --tests "ed.unicoach.db.dao.UsersDaoTest"`

3. **Add `ChangeEmailResult`** at
   `service/src/main/kotlin/ed/unicoach/auth/ChangeEmailResult.kt` (the sealed
   interface above).
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`

4. **Add `AuthService.changeEmail`** in
   `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`: validate via
   `EmailAddress.create`; in one transaction call `UsersDao.changeEmail`,
   `VerificationTokensDao.consumeAllForUser`, `issueToken`; post-commit
   `sendVerificationEmail`; map `DuplicateEmailException` to
   `ChangeEmailResult.DuplicateEmail`.
   - Verify: `nix develop -c ./gradlew :service:compileKotlin`

5. **Add `AuthServiceTest` cases** for `changeEmail` in
   `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`.
   - Verify:
     `nix develop -c bin/test service --tests "ed.unicoach.auth.AuthServiceTest"`

6. **Add request/response models**: `ChangeEmailRequest.kt` and
   `ChangeEmailResponse.kt` under
   `rest-server/src/main/kotlin/ed/unicoach/rest/models/`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`

7. **Wire the route** in
   `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`: add a
   `route("/change-email") { post { handleChangeEmail() }; rejectUnsupportedMethods(HttpMethod.Post) }`
   block and a `handleChangeEmail` handler that authenticates via cookie (as
   `resend-verification`), calls `authService.changeEmail`, and maps
   `Success`/`ValidationFailure`/`DuplicateEmail` to `200`/`400`/`409`.
   - Verify: `nix develop -c ./gradlew :rest-server:compileKotlin`

8. **Add `EmailVerificationRoutingTest` cases** for `change-email` in
   `rest-server/src/test/kotlin/ed/unicoach/rest/EmailVerificationRoutingTest.kt`.
   - Verify:
     `nix develop -c bin/test rest-server --tests "ed.unicoach.rest.EmailVerificationRoutingTest"`

9. **Full gate.**
   - Verify: `nix develop -c bin/test check --force`

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` — add `changeEmail`
  writer.
- `db/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` — `changeEmail` tests.
- `service/src/main/kotlin/ed/unicoach/auth/ChangeEmailResult.kt` — **new**
  sealed result.
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` — add `changeEmail`.
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` — `changeEmail`
  tests.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ChangeEmailRequest.kt` —
  **new**.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ChangeEmailResponse.kt` —
  **new**.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` —
  register route + `handleChangeEmail`.
- `rest-server/src/test/kotlin/ed/unicoach/rest/EmailVerificationRoutingTest.kt`
  — route tests.
