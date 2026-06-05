# SPEC: `service/src/main/kotlin/ed/unicoach/auth`

## I. Overview

This directory is the **authentication domain layer**. It owns the business logic
for user registration, session-bound user resolution (`/me`), session revocation
(logout), and background zombie-session cleanup. It bridges the HTTP boundary
(handled by `rest-server`) and the data layer (handled by `service/db`), exposing
pure domain results via sealed interfaces that contain no HTTP types. Login
credential verification is handled in this layer.

---

## II. Invariants

### General

- `AuthService` MUST NOT import or reference any Ktor, HTTP, or REST-layer types.
- All `AuthService` methods MUST wrap their entire body in `try/catch(Exception)`
  and return `Result.failure(e)` on any uncaught exception.
- `AuthService` MUST NOT own or hold a raw `java.sql.Connection` — all DB access
  MUST go through `Database.withConnection`.

### Registration

- `AuthService.register()` MUST validate input via `RegistrationValidator` before
  any database call. If validation fails, it MUST return
  `RegisterResult.ValidationFailure` without touching the DB.
- If `UsersDao.create()` returns `DuplicateEmailException`, `AuthService`
  MUST return `RegisterResult.DuplicateEmail`, not a database error.
- `AuthService.register()` MUST mint a token and create a session (or remint the
  caller's existing session when `oldCookieToken` is supplied) on success.

### Session Resolution (`getCurrentUser`)

- `AuthService.getCurrentUser()` MUST return `Result.success(null)` for
  all of: token not found, expired token, revoked token, anonymous session
  (`user_id = null`), and soft-deleted user. Every one of these is signalled by
  the DAO as a `NotFoundException`; any other DAO failure MUST surface as
  `Result.failure(e)`.
- `AuthService.getCurrentUser()` MUST NOT perform expiry extension inline — that
  concern belongs to the `rest-server` layer.

### Logout

- `AuthService.logout()` MUST be idempotent. A `NotFoundException` MUST be mapped
  to a `Result.success(Unit)`.
- `AuthService.logout()` MUST NOT clear cookies — that is the routing layer's
  responsibility.

### RegistrationValidator

- The validator MUST enforce: email non-blank, name non-blank, password length
  8–128 characters inclusive, at least 1 uppercase letter, at least 1 lowercase
  letter, and at least 1 digit.
- `RegistrationValidator` MUST NOT perform email format validation beyond
  blank-check — the `EmailAddress` value class owns format correctness.
- `RegistrationValidator.validate()` MUST collect ALL field errors before
  returning, not fail on the first violation.

### SessionCleanupJob

- `SessionCleanupJob.execute()` MUST NOT contain raw SQL — all DB mutation is
  delegated to `SessionsDao.expireZombieSessions()`.
- When `expireZombieSessions()` returns `Result.failure`, the job MUST log the
  error and complete normally (MUST NOT re-throw and MUST NOT exit non-zero).
- On any uncaught `Exception`, the job MUST log the failure and call
  `exitProcess(1)`.

### Result Sealed Interfaces

- The `RegisterResult` and `LoginResult` sealed interfaces and their variants
  MUST NOT carry HTTP status codes or any Ktor/REST-layer types; they model
  expected domain outcomes only.

---

## III. Behavioral Contracts

### `AuthService.register(email, name, password, oldCookieToken, sessionExpirationSeconds, userAgent, initialIp): Result<RegisterResult>` — See [AuthService.kt](./AuthService.kt)

- **Side Effects**: Writes one row to `users` via `UsersDao.create()`, then mints
  a token and creates or remints a session via `SessionsDao`.
- **Validation**: `RegistrationValidator` is called first; returns
  `RegisterResult.ValidationFailure(errors, fieldErrors)` without DB access if
  validation fails.
- **Hashing**: `argon2Hasher.hash(password)` is invoked; thrown exceptions are
  caught and returned as `Result.failure()`.
- **Idempotency**: Not idempotent. Submitting the same email twice returns
  `RegisterResult.DuplicateEmail` on the second call.
- **Error mapping**:
  - `Success` → `Result.success(RegisterResult.Success(user, newToken))`
  - `DuplicateEmailException` → `Result.success(RegisterResult.DuplicateEmail(email))`
  - Uncaught exception → `Result.failure(e)`

### `AuthService.getCurrentUser(tokenHash: TokenHash): Result<User?>` — See [AuthService.kt](./AuthService.kt)

- **Side Effects**: Two read-only DB queries (`SessionsDao.findByTokenHash`,
  `UsersDao.findById`). No writes.
- **Session lookup**: `SessionsDao.findByTokenHash` filters expired and revoked
  sessions. `NotFoundException` → `Result.success(null)`.
  Other exceptions → `Result.failure(e)`.
- **Anonymous session**: If `session.userId == null` → `Result.success(null)`.
- **User lookup**: `UsersDao.findById` with `includeDeleted = false`.
  `NotFoundException` → `Result.success(null)`.
  `Success` → `Result.success(user)`.
- **Idempotency**: Fully idempotent (read-only).
- **Error mapping** (uncaught): `Result.failure(e)`.

### `AuthService.logout(tokenHash: TokenHash): Result<Unit>` — See [AuthService.kt](./AuthService.kt)

- **Side Effects**: One blind `UPDATE` on `sessions` via
  `SessionsDao.revokeByTokenHash()`. Sets `is_revoked = true`,
  increments `version`.
- **Idempotency**: Idempotent. `NotFoundException` (already revoked or missing) maps to
  `Result.success(Unit)`.
- **Error mapping**:
  - `Success` → `Result.success(Unit)`
  - `NotFoundException` → `Result.success(Unit)`
  - Uncaught exception → `Result.failure(e)`

### `RegistrationValidator.validate(input: RegistrationInput): ValidationErrors` — See [RegistrationValidator.kt](./RegistrationValidator.kt)

- **Side Effects**: None. Pure function.
- **Idempotency**: Fully idempotent.
- **Rules enforced** (all errors collected before returning):
  - `email` blank → `FieldError("email", "Email cannot be blank")`
  - `name` blank → `FieldError("name", "Name cannot be blank")`
  - `password.length < 8` → password too short
  - `password.length > 128` → password too long
  - No uppercase char → missing uppercase
  - No lowercase char → missing lowercase
  - No digit → missing digit
- **Returns**: `ValidationErrors(fieldErrors = ...)`. `hasErrors()` returns
  `true` when `fieldErrors` is non-empty.

### `SessionCleanupJob.execute()` — See [SessionCleanupJob.kt](./SessionCleanupJob.kt)

- **Side Effects**: Expires zombie session rows in `sessions` via
  `SessionsDao.expireZombieSessions()`. Emits progress and outcome via an
  injected SLF4J logger.
- **On DB failure** (`Result.failure` from the DAO): logs the error; does not
  re-throw; `execute()` returns normally.
- **On uncaught exception**: logs the error and calls `exitProcess(1)`.
- **Idempotency**: Idempotent (expiry is safe to re-run).

### `RegisterResult` (sealed interface) — See [RegisterResult.kt](./RegisterResult.kt)

| Variant | Carries | When returned |
|---|---|---|
| `Success` | `user: User`, `token: String` | Successful registration |
| `ValidationFailure` | `errors: List<String>`, `fieldErrors: List<FieldError>` | Validator rejects input |
| `DuplicateEmail` | `email: String` | DB unique constraint hit on email |

### `AuthService.login(...)` — See [AuthService.kt](./AuthService.kt)

- **Side Effects**: One read-only DB query for the user via `UsersDao.findByEmail()`. If successful, optionally revokes the old session and creates a new session via `SessionsDao.create()`.
- **Idempotency**: Not idempotent. Creating a session mutates the database.
- **Error mapping**:
  - `Success` → `Result.success(LoginResult.Success(user, newToken))`
  - Invalid email → `Result.success(LoginResult.InvalidEmail(error))`
  - User not found → `Result.success(LoginResult.UserNotFound)`
  - Password not set → `Result.success(LoginResult.PasswordNotSet)`
  - Password mismatch → `Result.success(LoginResult.PasswordMismatch)`
  - Uncaught exception → `Result.failure(e)`

### `LoginResult` (sealed interface) — See [LoginResult.kt](./LoginResult.kt)

| Variant | Carries | When returned |
|---|---|---|
| `Success` | `user: User`, `token: String` | Successful login |
| `InvalidEmail` | `error: ValidationError` | Email format is invalid |
| `UserNotFound` | None | User does not exist |
| `PasswordNotSet` | None | User uses SSO only |
| `PasswordMismatch` | None | Incorrect password |

---

## IV. Infrastructure & Environment

- **Module**: `service` (Gradle). `AuthService` depends on `Database`,
  `Argon2Hasher` (from `common`), and `Validator<RegistrationInput>`.
- **Database**: Requires a live PostgreSQL connection pool via `Database`
  (HikariCP). `AuthService` is constructed with an injected `Database` instance.
- **Coroutine context**: `AuthService`'s `suspend` methods must be called from a
  coroutine scope. The module performs no dispatcher switching and preserves the
  caller's context.
- **No HOCON keys** are read directly by this package. Configuration is injected
  via constructor parameters (`database`, `argon2Hasher`).
- **`SessionCleanupJob`** is intended to be invoked by an external scheduler
  (e.g., cron). `execute()` is a `suspend` function and does NOT self-schedule.

---

## V. History

- [x] [RFC-08: Auth Registration](../../../../../../../rfc/08-auth-registration.md)
- [x] [RFC-10: Auth Login](../../../../../../../rfc/10-auth-login.md)
- [x] [RFC-11: Sessions](../../../../../../../rfc/11-sessions.md)
- [x] [RFC-13: Auth Me](../../../../../../../rfc/13-auth-me.md)
- [x] [RFC-22: Auth Logout](../../../../../../../rfc/22-auth-logout.md)
- [x] [RFC-24: Result Types](../../../../../../../rfc/24-result-types.md) (deleted `AuthResult`/`MeResult`/`LogoutResult`; service layer returns `Result<T>`)
- [x] [RFC-26: Login](../../../../../../../rfc/26-login.md)
- [x] [RFC-28: Coroutine Context Refactor](../../../../../../../rfc/28-coroutine-context.md)
