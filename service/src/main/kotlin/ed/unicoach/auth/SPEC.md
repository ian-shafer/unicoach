# SPEC: `service/src/main/kotlin/ed/unicoach/auth`

## I. Overview

The **authentication domain layer**. It owns the business logic for user
registration, password login, Google federated sign-in, session-bound caller
resolution (token hash to live session plus its user), session revocation
(logout), email-verification token issuance and redemption, and background
zombie-session cleanup. Credential verification (password hashing, Google
ID-token verification) lives here. Outcomes are returned as pure-domain sealed
interfaces wrapped in `Result<T>`; no transport or persistence types cross the
public surface.

---

## II. Behavioral Contracts

### `AuthService.register(email, name, password, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp): Result<RegisterResult>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Validates input, hashes the password, creates the user, mints a
  session token, and issues an email-verification token — the user, session, and
  verification token are written in a single `Database.withConnection`
  transaction. After the transaction commits, it attempts a best-effort
  verification email.
- **Validation**: `RegistrationValidator` runs first; on any field error returns
  `RegisterResult.ValidationFailure(errors, fieldErrors)` with no DB access.
- **Hashing**: `argon2Hasher.hash(password)` runs before the transaction; a
  thrown exception is caught and returned as `Result.failure(e)`.
- **Session**: When `oldCookieToken` resolves to an existing session, that
  session is reminted (`SessionsDao.remintToken`) to the new user; otherwise a
  new session is created (`SessionsDao.create`). Either way the session carries
  `LoginMethod.PASSWORD`.
- **Verification token**: Inside the transaction,
  `EmailVerificationService.issueToken(session, user.id)` persists the token
  hash and returns the raw token, which is held only in a local variable for
  post-commit delivery.
- **Side Effects**: One `users` row, one `sessions` row (created or reminted),
  and one verification-token row, all in one transaction. After commit, one
  outbound email attempt via `EmailVerificationService.sendVerificationEmail`.
- **Email delivery is best-effort**: A failed send does not roll back or fail
  registration — `register` returns `RegisterResult.Success` regardless of send
  outcome (a transient provider outage does not block account creation). The
  freshly registered user is unverified (`user.emailVerifiedAt` is null).
- **Idempotency**: Not idempotent. A second registration with the same email
  returns `RegisterResult.DuplicateEmail`.
- **Error mapping**:
  - Success → `Result.success(RegisterResult.Success(user, token))`
  - `DuplicateEmailException` from `UsersDao.create` →
    `Result.success(RegisterResult.DuplicateEmail(email))`
  - Validation error → `Result.success(RegisterResult.ValidationFailure(...))`
  - Any uncaught exception → `Result.failure(e)`

### `AuthService.login(email, password, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp): Result<LoginResult>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Trims and lowercases the email, parses it
  (`EmailAddress.create`), looks up the user, verifies the password, then mints
  a new session via `mintSession` (revoking a live `oldCookieToken` session
  first) with `LoginMethod.PASSWORD`.
- **Side Effects**: One read-only lookup via `UsersDao.findByEmail`. On a valid
  password, an optional old-session revoke plus one `sessions` insert via
  `SessionsDao.create` (with `LoginMethod.PASSWORD`).
- **Password presence**: Reads `user.passwordHash`; a `null` hash (Google-only
  account) returns `LoginResult.PasswordNotSet`.
- **Idempotency**: Not idempotent — a successful login inserts a session row.
- **Error mapping**:
  - Success → `Result.success(LoginResult.Success(user, newToken))`
  - Unparseable/malformed email →
    `Result.success(LoginResult.InvalidEmail(error))`
  - No such user → `Result.success(LoginResult.UserNotFound)`
  - User has no password hash (Google-only) →
    `Result.success(LoginResult.PasswordNotSet)`
  - Wrong password → `Result.success(LoginResult.PasswordMismatch)`
  - Any uncaught exception → `Result.failure(e)`

### `AuthService.loginWithGoogle(idToken, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp): Result<GoogleLoginResult>` — See [AuthService.kt](./AuthService.kt)

Establishes a session from a Google ID token. Both first-time signup and a
returning login return `GoogleLoginResult.Success` — the result does not
distinguish them, so account existence is not disclosed.

- **Flow**:
  1. `googleTokenVerifier.verify(idToken)`. A `GoogleTokenUnavailableException`
     maps to `GoogleLoginResult.VerificationUnavailable`; a
     `GoogleTokenInvalidException` maps to `GoogleLoginResult.InvalidToken`; any
     other failure → `Result.failure(e)`.
  2. Gate on the verified identity: `emailVerified == false` →
     `GoogleLoginResult.EmailNotVerified`.
  3. Re-validate the identity's `subject` (`ProviderSubject.create`) and `email`
     (`EmailAddress.create`); either being invalid →
     `GoogleLoginResult.InvalidToken`.
  4. Run the sign-in transaction (`runGoogleSignIn`); retried **once** on a
     `ConstraintViolationException` or `DuplicateEmailException` from a
     concurrent first-login race (the aborted transaction cannot re-read, so the
     whole block re-runs and the loser resolves as a returning login). Any other
     exception → `Result.failure(e)`.
- **Identity resolution** (`resolveOrProvisionUser`, within the transaction):
  - **Returning login**: an existing `(GOOGLE, subject)` identity loads its user
    across all soft-delete states (`SoftDeleteScope.ALL`). A `deletedAt != null`
    user → `GoogleLoginResult.AccountDisabled`; otherwise the user is resolved.
  - **Link**: no `(GOOGLE, subject)` identity but an active email-matched user
    exists → a `user_auth_identities` row (`emailVerified = true`) is linked
    onto that user.
  - **Create**: neither exists → a new `users` row is created
    (`passwordHash = null`, name from the `name` claim or, when absent/blank,
    the email local-part via `deriveName`) plus a linked `user_auth_identities`
    row.
- **Session**: On a resolved user, `mintSession` revokes a live old-cookie
  session then creates a fresh session with `LoginMethod.GOOGLE`.
- **Side Effects**: Network/crypto in the verifier; within one transaction,
  reads `user_auth_identities` and `users`, may insert a `users` row and/or a
  `user_auth_identities` row, and writes a `sessions` row.
- **Idempotency**: Not idempotent (mints a session, may provision a user). A
  second sign-in for an already-provisioned subject resolves as a returning
  login.
- **Name derivation** (`deriveName`): a candidate that cannot form a valid
  `PersonName` throws `IllegalStateException` (surfacing as a `Result.failure`,
  i.e. a 500); there is no silent placeholder.

### `AuthService.resolveSession(tokenHash: TokenHash): Result<AuthenticatedSession?>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Resolves a token hash to the live session row paired with the
  user account it belongs to. Looks up the session by token hash, then loads the
  session's user. A populated `AuthenticatedSession` is returned only when both
  a live session and its user exist; the pairing gives downstream callers the
  session row (e.g. for its state) alongside the resolved user without a second
  lookup.
- **Side Effects**: Two read-only queries (`SessionsDao.findByTokenHash`,
  `UsersDao.findById`) within a single `Database.withConnection` scope. No
  writes.
- **Absent-caller collapse**: Three user-absent outcomes collapse to
  `Result.success(null)` — no session row (DAO `NotFoundException`), an
  anonymous session (`session.userId == null`), and a soft-deleted user
  (`NotFoundException` from `findById`). Together these cover token-not-found,
  expired, and revoked (all surfaced by the session lookup as
  `NotFoundException`). Expiry extension is not performed here.
- **Idempotency**: Idempotent (read-only).
- **Error mapping**: Any non-`NotFoundException` DAO failure →
  `Result.failure(e)`.

### `AuthService.getCurrentUser(tokenHash: TokenHash): Result<User?>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: The user-only projection of `resolveSession` — delegates to it
  and maps a populated result to its `user`, retained for callers that need only
  the user. Each user-absent outcome maps to `Result.success(null)`.
- **Side Effects**: Inherits `resolveSession`'s two read-only queries. No
  writes.
- **Idempotency**: Idempotent (read-only).
- **Error mapping**: Propagates `resolveSession`'s — any non-`NotFoundException`
  DAO failure → `Result.failure(e)`.

### `AuthService.logout(tokenHash: TokenHash): Result<Unit>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Revokes the session matching the token hash via
  `SessionsDao.revokeByTokenHash`.
- **Side Effects**: One blind `UPDATE` on `sessions` (sets `is_revoked`,
  increments `version`).
- **Idempotency**: Idempotent. A `NotFoundException` (already revoked or absent)
  maps to `Result.success(Unit)`.
- **Error mapping**: Any other uncaught exception → `Result.failure(e)`.

### `EmailVerificationService.issueToken(session: SqlSession, userId: UserId): Result<String>` — See [EmailVerificationService.kt](./EmailVerificationService.kt)

- **Behavior**: Generates a raw token, derives its token hash, computes
  `now + config.tokenTtl` as the expiry, and inserts the hash + expiry via
  `VerificationTokensDao.create` **inside the caller's supplied transaction**
  (atomic with the surrounding work). Returns the raw token for post-commit
  delivery.
- **Side Effects**: One verification-token row in the caller's transaction. At
  rest only the hash is stored; the raw token never persists.
- **Idempotency**: Not idempotent — each call inserts a new token row.
- **Error mapping**: A DAO insert failure surfaces as `Result.failure`.

### `EmailVerificationService.sendVerificationEmail(to: EmailAddress, rawToken: String): Result<Unit>` (suspend) — See [EmailVerificationService.kt](./EmailVerificationService.kt)

- **Behavior**: Builds the verify link as `config.verifyUrlBase` with the raw
  token appended as a `?token=` query parameter, constructs a fixed-literal
  subject and body, and sends via `EmailService.send`. Best-effort, post-commit.
- **Side Effects**: One outbound email send attempt. No DB access.
- **Error handling**: Every failure path folds into a logged `Result.failure`
  without throwing — a subject/body construction rejection (an `Invalid`
  validation outcome) yields `Result.failure(IllegalStateException)`; a provider
  rejection is logged and the underlying failure is returned. The caller treats
  the result as advisory.
- **Idempotency**: Not idempotent — each call attempts another send.

### `EmailVerificationService.verify(rawToken: String): Result<VerifyEmailResult>` (suspend) — See [EmailVerificationService.kt](./EmailVerificationService.kt)

- **Behavior**: Runs in its own transaction. A compare-and-swap consumes the
  token by hash (`VerificationTokensDao.consume`); on success it marks the user
  verified (`UsersDao.markEmailVerified`) and burns all sibling tokens for that
  user (`VerificationTokensDao.consumeAllForUser`), returning
  `VerifyEmailResult.Success(user)`.
- **Failed consume classification**: A zero-row consume (`NotFoundException`) is
  classified via `VerificationTokensDao.findByTokenHash`: hash matches no row →
  `VerifyEmailResult.InvalidToken`; `consumedAt` set →
  `VerifyEmailResult.AlreadyConsumed`; `expiresAt` not after now →
  `VerifyEmailResult.Expired`; otherwise `VerifyEmailResult.InvalidToken`.
- **Side Effects**: On success, marks the user verified and consumes the user's
  outstanding tokens (the redeemed one plus its siblings). The classification
  path is read-only.
- **Idempotency**: Redeeming the same raw token twice yields
  `VerifyEmailResult.Success` on the first call and
  `VerifyEmailResult.AlreadyConsumed` on the second — the verified state is set
  once.
- **Error mapping**: An unexpected DAO/transaction failure surfaces as
  `Result.failure`; the four expected outcomes are `Result.success`.

### `EmailVerificationService.resend(user: User): Result<ResendResult>` (suspend) — See [EmailVerificationService.kt](./EmailVerificationService.kt)

- **Behavior**: For an already-verified user (`user.emailVerifiedAt != null`),
  returns `ResendResult.AlreadyVerified` with no DB write and no send.
  Otherwise, in its own transaction, burns the user's outstanding tokens
  (`consumeAllForUser`) and issues a fresh one, then attempts a best-effort
  post-commit send, returning `ResendResult.Sent`.
- **Side Effects**: For an unverified user, consumes outstanding tokens and
  inserts one new token (one transaction), then one outbound email attempt.
- **Email delivery is best-effort**: A send failure does not undo the issued
  token; the result is still `ResendResult.Sent`.
- **Idempotency**: Each call for an unverified user replaces any live token with
  a fresh one, leaving a single consumable token. A call for a verified user is
  a no-op.
- **Error mapping**: A failure issuing the token surfaces as `Result.failure`;
  the two expected outcomes are `Result.success`.

### `RegistrationValidator.validate(input: RegistrationInput): ValidationErrors` — See [RegistrationValidator.kt](./RegistrationValidator.kt)

- **Behavior**: Pure validation. Collects all field errors before returning.
- **Side Effects**: None.
- **Rules enforced**:
  - `email` blank or malformed (delegated to `EmailAddress.create`) → `email`
    field error. Validating format makes the downstream
    `as ValidationResult.Valid` cast in `register` total.
  - `name` blank → `name` field error.
  - password length, measured in Unicode **code points**, outside `8..128` →
    length field error (an astral surrogate-pair character counts once).
  - missing ASCII uppercase (`A`–`Z`), lowercase (`a`–`z`), or digit (`0`–`9`) →
    the corresponding complexity field error. Complexity classes are ASCII-only;
    a non-ASCII letter or digit satisfies no rule.
- **Returns**: `ValidationErrors`; `hasErrors()` is true when any field error
  exists.
- **Idempotency**: Idempotent (pure).

### `SessionCleanupJob.execute()` (suspend) — See [SessionCleanupJob.kt](./SessionCleanupJob.kt)

- **Behavior**: Delegates zombie-session expiry to
  `SessionsDao.expireZombieSessions`; emits progress and outcome through an
  injected SLF4J logger. Does not self-schedule.
- **Side Effects**: Expires zombie `sessions` rows.
- **Error handling**: A `Result.failure` from the DAO is logged and `execute()`
  returns normally. An uncaught exception is logged and the process exits via
  `exitProcess(1)`.
- **Idempotency**: Idempotent (re-running expiry is safe).

### `GoogleTokenVerifier.verify(idToken: String): Result<GoogleIdentity>` — See [GoogleTokenVerifier.kt](./GoogleTokenVerifier.kt)

- **Behavior**: Verifies a Google ID token and projects it into a
  `GoogleIdentity` (subject, email, emailVerified, optional name). Hidden behind
  an interface so it is swappable and offline-testable.
- **Failure carriers**:
  - `GoogleTokenInvalidException` (a `PermanentError`) — any signature or claim
    failure: malformed, expired, wrong `aud`/`iss`, bad signature, missing
    `sub`/`email`, unknown signing key.
  - `GoogleTokenUnavailableException` (a `TransientError`) — the JWKS endpoint
    could not be reached.
- **`DisabledGoogleTokenVerifier`**: a fail-closed object that rejects every
  token with `GoogleTokenInvalidException`. Wired on hosts with no Google route
  (e.g. admin-server) so a credential can never be accepted there.

### `JwksGoogleTokenVerifier` — See [JwksGoogleTokenVerifier.kt](./JwksGoogleTokenVerifier.kt)

- **Behavior**: Production verifier. Decodes the JWT, fetches the RSA key from a
  `JwkProvider` by `kid`, verifies the RS256 signature plus `iss` / `aud`
  (any-of the configured `clientIds`) / `exp` / `iat` with `clockSkew` leeway,
  then reads `sub`, `email`, `email_verified`, `name`.
- **Side Effects**: Network — fetches Google's JWKS (cached/rate-limited).
- **Email-verified**: an absent or non-`true` `email_verified` claim yields
  `emailVerified = false`.
- **Error mapping**: a JWKS fetch/transport failure (timeout, unknown host, IO)
  → `GoogleTokenUnavailableException`; a decode, signature, claim, unknown-key,
  or missing-`sub`/`email` failure → `GoogleTokenInvalidException`.

### `StubGoogleTokenVerifier` — See [StubGoogleTokenVerifier.kt](./StubGoogleTokenVerifier.kt)

- **Behavior**: Offline test/dev verifier (`PROVIDER_ID = "stub"`); no network,
  no crypto. Parses the fake-token format `stub:` followed by `field=value`
  pairs separated by `;`. Recognised fields: `sub` (required), `email`
  (required), `email_verified` (defaults `false`), `name` (optional).
- **Error mapping**: `stub:invalid`, a missing prefix, or a missing
  `sub`/`email` → `GoogleTokenInvalidException`; the literal `stub:unavailable`
  → `GoogleTokenUnavailableException` (exercising the transient path offline).

### `GoogleAuthConfig.from(config: Config): Result<GoogleAuthConfig>` — See [GoogleAuthConfig.kt](./GoogleAuthConfig.kt)

- **Behavior**: Typed reader for the `auth.google` block of `service.conf`,
  mirroring `SessionConfig`/`ChatConfig`'s Result-returning, fail-fast contract.
  Returns `Result.failure` when the section is absent or unreadable. Performs
  **no value validation** — the factory is the single place an unusable
  configuration is rejected.
- **Fields**: `provider`, `clientIds`, `issuers`, `jwksUri`, `clockSkew`,
  `connectTimeout`, `readTimeout`.
- **List parsing**: `clientIds` and `issuers` accept either a HOCON list or a
  comma-separated string (the shape the `GOOGLE_CLIENT_IDS` env override
  produces); blank entries are dropped.

### `GoogleTokenVerifierFactory.fromConfig(config: GoogleAuthConfig): Result<GoogleTokenVerifier>` — See [GoogleTokenVerifierFactory.kt](./GoogleTokenVerifierFactory.kt)

- **Behavior**: Selector on `config.provider`, mirroring `ChatProviderFactory`:
  - `"stub"` → `StubGoogleTokenVerifier`
  - `"google"` → `JwksGoogleTokenVerifier` (fails fast when `clientIds` is
    empty)
  - any other value → `Result.failure(IllegalArgumentException)` (no silent
    fallback).

### `AuthenticatedSession` (data class) — See [AuthService.kt](./AuthService.kt)

A resolved caller: a live session row paired with its existing user account.
Returned by `resolveSession` only on success — the present `user` is the
type-level signal that resolution found both a live session and its account, so
a caller holding an `AuthenticatedSession` needs no further existence checks.

### `RegisterResult` (sealed interface) — See [RegisterResult.kt](./RegisterResult.kt)

| Variant             | Carries                                                 | When returned                     |
| ------------------- | ------------------------------------------------------- | --------------------------------- |
| `Success`           | `user: User`, `token: String`                           | Successful registration           |
| `ValidationFailure` | `errors: List<String>`, `fieldErrors: List<FieldError>` | Validator rejects input           |
| `DuplicateEmail`    | `email: String`                                         | DB unique constraint hit on email |

### `LoginResult` (sealed interface) — See [LoginResult.kt](./LoginResult.kt)

| Variant            | Carries                       | When returned                |
| ------------------ | ----------------------------- | ---------------------------- |
| `Success`          | `user: User`, `token: String` | Successful login             |
| `InvalidEmail`     | `error: ValidationError`      | Email format is invalid      |
| `UserNotFound`     | None                          | User does not exist          |
| `PasswordNotSet`   | None                          | Account has no password hash |
| `PasswordMismatch` | None                          | Incorrect password           |

### `GoogleLoginResult` (sealed interface) — See [GoogleLoginResult.kt](./GoogleLoginResult.kt)

| Variant                   | Carries                       | When returned                                   |
| ------------------------- | ----------------------------- | ----------------------------------------------- |
| `Success`                 | `user: User`, `token: String` | Sign-in succeeded (signup or returning login)   |
| `InvalidToken`            | None                          | Token failed verification or carried bad claims |
| `EmailNotVerified`        | None                          | `email_verified` was false                      |
| `AccountDisabled`         | None                          | The resolved user is soft-deleted               |
| `VerificationUnavailable` | None                          | Google's JWKS endpoint was unreachable          |

### `GoogleIdentity` (data class) — See [GoogleIdentity.kt](./GoogleIdentity.kt)

The claims read from a verified Google ID token (`subject`, `email`,
`emailVerified`, optional `name`).

### `VerifyEmailResult` (sealed interface) — See [VerifyEmailResult.kt](./VerifyEmailResult.kt)

| Variant           | Carries | When returned                          |
| ----------------- | ------- | -------------------------------------- |
| `Success`         | `user`  | Token consumed; user marked verified   |
| `InvalidToken`    | —       | Token hash matches no row              |
| `Expired`         | —       | Token exists but its expiry has passed |
| `AlreadyConsumed` | —       | Token exists but was already consumed  |

### `ResendResult` (sealed interface) — See [ResendResult.kt](./ResendResult.kt)

| Variant           | Carries | When returned                                 |
| ----------------- | ------- | --------------------------------------------- |
| `Sent`            | —       | A fresh token was issued and a send attempted |
| `AlreadyVerified` | —       | User already verified; no token, no send      |

---

## III. Infrastructure & Environment

- **Module**: `service` (Gradle).
- **Dependencies (constructor-injected)**:
  - `AuthService` holds `Database`, `Argon2Hasher`, `TokenGenerator`,
    `EmailVerificationService`, a `GoogleTokenVerifier`, and a
    `Validator<RegistrationInput>` (defaulting to `RegistrationValidator`).
  - `EmailVerificationService` holds `Database`, `EmailService`,
    `TokenGenerator`, and `EmailVerificationConfig`.
- **Database**: Requires a live PostgreSQL connection pool via `Database`. All
  DB access goes through `Database.withConnection`; the layer holds no raw
  `java.sql.Connection`. The Google sign-in path runs its identity resolution
  and session-minting in a single transaction.
- **Credential model**: A user's credential is a nullable `password_hash` plus
  zero-or-more `user_auth_identities` rows (e.g. a `(GOOGLE, subject)` federated
  identity). The "at least one usable credential" guarantee is an application
  invariant enforced in `AuthService`, not a row-local DB CHECK (the
  `users_auth_method_check` / `sso_provider_id` column was dropped in migration
  `0017.drop-users-sso-provider-id.sql`).
- **Config — `EmailVerificationConfig`** — See
  [EmailVerificationConfig.kt](./EmailVerificationConfig.kt): the
  `from(config): Result<EmailVerificationConfig>` factory reads the
  `emailVerification` config block and returns `Result.failure` (carrying the
  underlying config exception) when a key is absent or unreadable; it performs
  no value validation. Keys read:
  - `emailVerification.tokenTtl` (a `Duration`) — bounds how long an issued
    verification token stays consumable.
  - `emailVerification.verifyUrlBase` (a `String`) — the link prefix the
    verification email points at; the raw token is appended as a `?token=` query
    parameter.
- **Google configuration**: `GoogleAuthConfig` reads the `auth.google` block of
  `service.conf` — `provider`, `clientIds` (env override `GOOGLE_CLIENT_IDS`),
  `issuers`, `jwksUri`, `clockSkew`, `connectTimeout`, `readTimeout`. The
  verifier and other collaborators are injected via constructor.
- **Token storage**: A verification token is persisted only as its SHA-256 hash
  plus an expiry; the raw token exists only in memory between issuance and the
  outbound email link.
- **Coroutine context**: `suspend` methods run on the caller's coroutine
  context; the layer performs no dispatcher switching.
- **`SessionCleanupJob`**: invoked by an external scheduler (e.g. cron);
  `execute()` is a `suspend` function and does not self-schedule.

---

## IV. History

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md)
- [x] [RFC-10: Auth Login](../../../../../../../rfc/10-auth-login.md)
- [x] [RFC-11: Sessions](../../../../../../../rfc/11-sessions.md)
- [x] [RFC-13: Auth Me](../../../../../../../rfc/13-auth-me.md)
- [x] [RFC-22: Auth Logout](../../../../../../../rfc/22-auth-logout.md)
- [x] [RFC-24: Result Types](../../../../../../../rfc/24-result-types.md)
- [x] [RFC-26: Login](../../../../../../../rfc/26-login.md)
- [x] [RFC-28: Coroutine Context Refactor](../../../../../../../rfc/28-coroutine-context.md)
- [x] [RFC-34: Transactional Email Service](../../../../../../../rfc/34-transactional-email-service.md)
- [x] [RFC-52: Make the REST Surface Fuzz-Clean](../../../../../../../rfc/52-make-rest-surface-fuzz-clean.md)
- [x] [RFC-62: DAO Capability Interfaces and Shared Query Scaffolding](../../../../../../../rfc/62-dao-interfaces.md)
      — Renamed the `SessionsDao.create` named argument to `input` at the
      `AuthService` call sites. No behavioral change.
- [x] [RFC-64: Google SSO Login](../../../../../../../rfc/64-google-sso-login.md)
      — Added the `GoogleTokenVerifier` abstraction (`Jwks`/`Stub`/`Disabled`
      verifiers, factory, and `GoogleAuthConfig`) and
      `AuthService.loginWithGoogle`, which provisions or resolves a federated
      user and mints a `LoginMethod.GOOGLE` session. The credential model became
      a nullable `password_hash` plus `user_auth_identities` rows (removed
      `authMethod`/`AuthMethod`/`SsoProviderId` and dropped the
      `sso_provider_id` column / `users_auth_method_check` in migration
      `0017.drop-users-sso-provider-id.sql`). `register`/`login` now thread
      `LoginMethod.PASSWORD` through shared session minting, and `login` reads
      `user.passwordHash` (returning `PasswordNotSet` when null).
- [x] [RFC-65: Email Verification](../../../../../../../rfc/65-email-verification.md)
      — Added `EmailVerificationService` (token issuance/redemption/resend) and
      `EmailVerificationConfig`; `register` issues a verification token inside
      its transaction and sends a best-effort verification email post-commit.
- [x] [RFC-69: Email Verification Gate](../../../../../../../rfc/69-email-verification-gate.md)
      — Added `AuthService.resolveSession` returning `AuthenticatedSession` (the
      live session paired with its user), with `getCurrentUser` retained as a
      thin user-only delegate over it.
