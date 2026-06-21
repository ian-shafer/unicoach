# SPEC: `service/src/main/kotlin/ed/unicoach/auth`

## I. Overview

The authentication domain layer. It owns the business logic for user
registration, login credential verification, session-bound user resolution
(`/me`), session revocation (logout), email-verification token issuance and
redemption, and background zombie-session cleanup. Outcomes are returned as
pure-domain sealed interfaces wrapped in `Result<T>`; no transport or
persistence types cross the public surface.

---

## II. Behavioral Contracts

### `AuthService.register(email, name, password, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp): Result<RegisterResult>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Validates input, hashes the password, creates the user, mints a
  session token, and issues an email-verification token — the user, session, and
  verification token are written in a single `Database.withConnection`
  transaction. After the transaction commits successfully, it attempts a
  best-effort verification email.
- **Validation**: `RegistrationValidator` runs first; on any field error returns
  `RegisterResult.ValidationFailure(errors, fieldErrors)` with no DB access.
- **Hashing**: `argon2Hasher.hash(password)` runs before the transaction; a
  thrown exception is caught and returned as `Result.failure(e)`.
- **Session**: When `oldCookieToken` resolves to an existing session, that
  session is reminted (`SessionsDao.remintToken`) to the new user; otherwise a
  new session is created (`SessionsDao.create`).
- **Verification token**: Inside the transaction,
  `EmailVerificationService.issueToken(session, user.id)` persists the token
  hash and captures the raw token. The raw token is held only in a local
  variable for post-commit delivery.
- **Side Effects**: One `users` row, one `sessions` row (created or reminted),
  and one verification-token row, all in one transaction. After commit, one
  outbound email attempt via `EmailVerificationService.sendVerificationEmail`.
- **Email delivery is best-effort**: A failed send does not roll back or fail
  registration — `register` returns `RegisterResult.Success` regardless of send
  outcome (a transient provider outage does not block account creation). The
  freshly registered user is unverified — `user.emailVerifiedAt` is null at this
  point.
- **Idempotency**: Not idempotent. A second registration with the same email
  returns `RegisterResult.DuplicateEmail`.
- **Error mapping**:
  - Success → `Result.success(RegisterResult.Success(user, token))`
  - `DuplicateEmailException` from `UsersDao.create` →
    `Result.success(RegisterResult.DuplicateEmail(email))`
  - Validation error → `Result.success(RegisterResult.ValidationFailure(...))`
  - Any uncaught exception → `Result.failure(e)`

### `AuthService.login(email, password, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp): Result<LoginResult>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Normalizes and parses the email, looks up the user, verifies the
  password, then mints a new session (optionally revoking the session named by
  `oldCookieToken`).
- **Side Effects**: One read query (`UsersDao.findByEmail`); on success an
  optional session revoke plus one `sessions` insert via `SessionsDao.create`.
- **Idempotency**: Not idempotent — a successful login inserts a session row.
- **Error mapping**:
  - Success → `Result.success(LoginResult.Success(user, token))`
  - Unparseable email → `Result.success(LoginResult.InvalidEmail(error))`
  - No such user → `Result.success(LoginResult.UserNotFound)`
  - SSO-only user (no password hash) →
    `Result.success(LoginResult.PasswordNotSet)`
  - Wrong password → `Result.success(LoginResult.PasswordMismatch)`
  - Any uncaught exception → `Result.failure(e)`

### `AuthService.getCurrentUser(tokenHash: TokenHash): Result<User?>` — See [AuthService.kt](./AuthService.kt)

- **Behavior**: Resolves the session for the token hash, then loads the
  associated user.
- **Side Effects**: Two read-only queries (`SessionsDao.findByTokenHash`,
  `UsersDao.findById`). No writes.
- **Absent-user collapse**: Token not found, expired, revoked, anonymous session
  (`session.userId == null`), and soft-deleted user all collapse to
  `Result.success(null)`. The DAO signals the not-found cases as
  `NotFoundException`.
- **Idempotency**: Idempotent (read-only).
- **Error mapping**: Any non-`NotFoundException` DAO failure →
  `Result.failure(e)`.

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

- **Behavior**: Runs in its own transaction. Compare-and-swap consumes the token
  by hash (`VerificationTokensDao.consume`); on success it marks the user
  verified (`UsersDao.markEmailVerified`) and burns all sibling tokens for that
  user (`VerificationTokensDao.consumeAllForUser`), returning
  `VerifyEmailResult.Success(user)`.
- **Failed consume classification**: A zero-row consume (`NotFoundException`) is
  classified via `VerificationTokensDao.findByTokenHash`: hash matches no row →
  `VerifyEmailResult.InvalidToken`; `consumedAt` set →
  `VerifyEmailResult.AlreadyConsumed`; `expiresAt` in the past →
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

### `RegisterResult` (sealed interface) — See [RegisterResult.kt](./RegisterResult.kt)

| Variant             | Carries                 | When returned               |
| ------------------- | ----------------------- | --------------------------- |
| `Success`           | `user`, `token`         | Successful registration     |
| `ValidationFailure` | `errors`, `fieldErrors` | Validator rejects input     |
| `DuplicateEmail`    | `email`                 | Email unique-constraint hit |

### `LoginResult` (sealed interface) — See [LoginResult.kt](./LoginResult.kt)

| Variant            | Carries         | When returned           |
| ------------------ | --------------- | ----------------------- |
| `Success`          | `user`, `token` | Successful login        |
| `InvalidEmail`     | `error`         | Email format is invalid |
| `UserNotFound`     | —               | User does not exist     |
| `PasswordNotSet`   | —               | SSO-only user           |
| `PasswordMismatch` | —               | Incorrect password      |

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
    `EmailVerificationService`, and a `Validator<RegistrationInput>` (defaulting
    to `RegistrationValidator`).
  - `EmailVerificationService` holds `Database`, `EmailService`,
    `TokenGenerator`, and `EmailVerificationConfig`.
- **Database**: Requires a live PostgreSQL connection pool via `Database`. All
  DB access goes through `Database.withConnection`; the layer holds no raw
  `java.sql.Connection`.
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
- **Token storage**: A verification token is persisted only as its SHA-256 hash
  plus an expiry; the raw token exists only in memory between issuance and the
  outbound email link.
- **Coroutine context**: `suspend` methods run on the caller's coroutine
  context; the layer performs no dispatcher switching.
- **`SessionCleanupJob`**: invoked by an external scheduler (e.g. cron);
  `execute()` does not self-schedule.

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
- [x] [RFC-65: Email Verification](../../../../../../../rfc/65-email-verification.md)
