# RFC 24: Result Types Refactoring

## Executive Summary

Proposes refactor of error handling to exclusively use Kotlin's standard
`Result<T>` type for any function that can return an expected error. This will
replace all ad-hoc monolithic sealed result types (e.g., `AuthResult`,
`DaoResult`, `MeResult`, `LogoutResult`). Expected domain and system errors will
be wrapped in `Result.failure()`. Unexpected, truly exceptional states will be
thrown as standard exceptions and handled at a single global point (e.g., the
Ktor routing layer). As part of this refactor, all associated technical
specifications (`SPEC.md` files) and relevant agent skills will be updated to
codify this new architectural pattern.

## Detailed Design

### Error Hierarchy

- **Remove `AppError` interface**: This will be replaced by a concrete exception
  hierarchy.
- **Error Tagging (Traits)**: To provide flexibility beyond a rigid exception
  hierarchy, we will define marker interfaces (traits) in the `common` module.
  This allows exceptions to be tagged with multiple characteristics:
  - `interface TransientError`: For retryable errors.
  - `interface PermanentError`: For non-retryable errors.
- **Base Exception**: Domain exceptions will inherit from standard
  `RuntimeException` and implement the relevant trait interfaces (e.g.,
  `class ValidationException : RuntimeException(), PermanentError`).
- **Returning Errors**: Functions in the DAO and Service layers will return
  `Result<T>` for system, database, and infrastructure errors. Expected errors
  will be returned as `Result.failure(exception)`, _not_ thrown.
- **Domain Outcomes vs System Errors**: Expected domain states (e.g., a
  `ValidationFailure` or `DuplicateEmail`) are _not_ system errors. They should
  be modeled as part of the successful `T` payload using a sealed interface
  (e.g., `Result<RegisterOutcome>` where `RegisterOutcome` has `Success` and
  `ValidationFailure` variants). `Result.failure()` is strictly reserved for
  true errors.
- **Single-Point Error Handling (Routing Layer)**: The Ktor routing layer will
  call `.getOrThrow()` on the `Result` objects returned by services. Ktor's
  `StatusPages` plugin will act as the single global error handler, mapping
  traits to HTTP responses (e.g., `TransientError` -> `503 Service Unavailable`,
  `ValidationException` -> `400 Bad Request`).

### Data Models

N/A

### API Contracts

- Ktor `StatusPages` will return a standardized JSON error response body for all
  exceptions it handles: `{ "error": "<ErrorType>", "message": "<Details>" }`.
- Route error status codes will be normalized:
  - `TransientError` mapped to HTTP 503 (Service Unavailable).
  - `PermanentError` (like `ValidationException`) mapped to HTTP 400 (Bad
    Request) or HTTP 422 (Unprocessable Entity) based on trait or class.

### Dependencies

- Ktor `StatusPages` (Existing - v2.3.x or later)

## Tests

1. **Service Unit Tests (`AuthServiceTest`)**:
   - Assert `AuthService.login` returns `Result.success(LoginOutcome.Success)`
     on valid credentials.
   - Assert `AuthService.login` returns
     `Result.success(LoginOutcome.InvalidCredentials)` on bad password.
   - Assert `AuthService.login` returns `Result.failure(TransientError)` when DB
     fails.
2. **DAO Unit Tests (`UsersDaoTest`)**:
   - Assert `UsersDao.findByEmail` returns `Result.success(user)` on existing
     user.
   - Assert `UsersDao.findByEmail` returns `Result.failure(TransientError)` on
     connection loss.
3. **StatusPages Integration Tests (`StatusPagesTest`)**:
   - Mock a route throwing `TransientError` and verify a
     `503 Service Unavailable` response with the expected JSON payload.
   - Mock a route throwing `ValidationException` and verify a `400 Bad Request`
     response with the expected JSON payload.

## Implementation Plan

1. **Define Core Error Hierarchy**: In
   `common/src/main/kotlin/ed/unicoach/error/`, delete `AppError.kt`. Create
   `ExceptionTraits.kt` with `TransientError` and `PermanentError`.
   - _Verification_: `gradle :common:compileKotlin`
2. **Update Database Layer (Result Type)**: Delete `DaoResult.kt`. Refactor
   `UsersDao.kt` to return `Result<T>`.
   - _Verification_: `gradle :db:test --tests "ed.unicoach.db.dao.UsersDaoTest"`
3. **Update Database Layer (Sessions)**: Refactor `SessionsDao.kt` to return
   `Result<T>`. Update `db/src/main/kotlin/ed/unicoach/db/dao/SPEC.md`.
   - _Verification_:
     `gradle :db:test --tests "ed.unicoach.db.dao.SessionsDaoTest"`
4. **Update Service Layer (AuthResult & Sessions)**: Refactor `AuthResult.kt` to
   `RegisterOutcome` (sealed interface) and update `AuthService.kt` to return
   `Result<RegisterOutcome>`. Migrate session creation and token reminting logic
   into `AuthService`, and inject `TokenGenerator`. Update `Application.kt` to
   provide this dependency.
   - _Verification_: `gradle :service:compileKotlin`
5. **Update Service Layer (MeResult & LogoutResult)**: Delete `MeResult.kt` and
   `LogoutResult.kt`. Update usages in `AuthService.kt` and
   `SessionCleanupJob.kt`. Update
   `service/src/main/kotlin/ed/unicoach/auth/SPEC.md`.
   - _Verification_: `gradle :service:test`
6. **Update Routing Layer**: Configure `StatusPages.kt` to handle
   `TransientError` and `PermanentError` mapping them to JSON. Update
   `AuthRoutes.kt` and `SessionExpiryHandler.kt` to use `.getOrThrow()`. Update
   `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/SPEC.md` and
   `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SPEC.md` and
   `net/src/main/kotlin/ed/unicoach/net/handlers/SPEC.md`.
   - _Verification_: `gradle :rest-server:test`
7. **Update Queue Layer (Results)**: Refactor `Results.kt` and `JobsDao.kt` to
   align with the standard Kotlin `Result<T>` pattern. Update `QueueService.kt`
   to handle the new return types.
   - _Verification_: `gradle :queue:test`
8. **Documentation Sync**: Update
   `queue/src/main/kotlin/ed/unicoach/queue/dao/SPEC.md`,
   `common/src/main/kotlin/ed/unicoach/error/SPEC.md`, and relevant
   `.agents/skills` files.
   - _Verification_: Manual review of `SPEC.md` diffs.

## Files Modified

- **Common Layer**:
  - `common/src/main/kotlin/ed/unicoach/error/AppError.kt` [DELETE]
  - `common/src/main/kotlin/ed/unicoach/error/ExceptionTraits.kt` [NEW]
  - `common/src/main/kotlin/ed/unicoach/error/SPEC.md`
- **Database Layer**:
  - `db/src/main/kotlin/ed/unicoach/db/dao/DaoResult.kt` [DELETE]
  - `db/src/main/kotlin/ed/unicoach/db/dao/DaoExceptions.kt` [NEW]
  - `db/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt`
  - `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt`
  - `db/src/main/kotlin/ed/unicoach/db/dao/SPEC.md`
- **Service Layer**:
  - `service/src/main/kotlin/ed/unicoach/auth/AuthResult.kt` [MODIFY/RENAME]
  - `service/src/main/kotlin/ed/unicoach/auth/MeResult.kt` [DELETE]
  - `service/src/main/kotlin/ed/unicoach/auth/LogoutResult.kt` [DELETE]
  - `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
  - `service/src/main/kotlin/ed/unicoach/auth/SessionCleanupJob.kt`
  - `service/src/main/kotlin/ed/unicoach/auth/SPEC.md`
- **Routing/Net Layer**:
  - `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
  - `rest-server/src/main/kotlin/ed/unicoach/rest/SPEC.md`
  - `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt`
  - `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt`
  - `net/src/main/kotlin/ed/unicoach/net/handlers/SessionExpiryHandler.kt`
  - `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/SPEC.md`
  - `rest-server/src/main/kotlin/ed/unicoach/rest/routing/SPEC.md`
  - `net/src/main/kotlin/ed/unicoach/net/handlers/SPEC.md`
- **Queue Layer**:
  - `queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt`
  - `queue/src/main/kotlin/ed/unicoach/queue/dao/JobsDao.kt`
  - `queue/src/main/kotlin/ed/unicoach/queue/dao/Results.kt`
  - `queue/src/main/kotlin/ed/unicoach/queue/dao/SPEC.md`
- **Other**:
  - `service/build.gradle.kts`
