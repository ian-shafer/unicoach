# RFC-26: Login

## Executive Summary

This specification defines the session-based email/password authentication flow
for unicoach. It introduces a `POST /api/v1/auth/login` REST endpoint that
deserializes user credentials restricted by payload limits, validates
credentials, and verifies the password against a stored Argon2id hash. Upon
explicit success, the server establishes a session cookie. If an existing
session is present on the login request, it will be overwritten and the old
session deleted from the database.

## Detailed Design

### Security & Scope Declarations

- **Timing Attack Vulnerability (Accepted Risk):** This design explicitly omits
  timing attack mitigations. The system will fail fast if an email is not found,
  rather than executing a dummy Argon2 computation. This creates a known
  vulnerability where attackers can enumerate registered user emails based on
  response time variances. This is accepted as a known risk.
- **Payload Bloat / DoS Vulnerability (Accepted Risk):** Explicit 4KB payload
  limit enforcement on the `/login` route is deferred. Currently, there is no
  centralized Ktor plugin to enforce `Content-Length` bounds, and implementing
  it per-route is an anti-pattern. This leaves the endpoint temporarily
  susceptible to buffer bloat until a global `PayloadLimitPlugin` is introduced
  in a future RFC.
- **Event Loop Starvation Safety:** Argon2 hash verification monopolizes CPU
  threads. Native Kotlin coroutine implementations MUST explicitly jump to a
  dedicated bounded cryptographic dispatcher before initiating cryptographic
  comparisons to unconditionally guarantee the Ktor Netty event loop and
  database IO threads avoid thread starvation.

### Data Models

- `data class LoginRequest(val email: String, val password: String)`
  - Constraints: Strict length caps to prevent Argon2 buffer padding DoS
    attacks.
- `data class LoginResponse(val user: PublicUser)`
  - Constraints: Reuses the `PublicUser` data model. The session token is
    transmitted statelessly via the `Set-Cookie` HTTP header, so it is omitted
    from the JSON body.

### API Contracts

**Endpoint:** `POST /api/v1/auth/login`

- **Payload Limits:** The endpoint MUST enforce
  `Content-Type: application/json`. (Note: Explicit 4KB payload size limiting is
  temporarily deferred as a known risk).
- **Response (Success):** HTTP `200 OK` with the `LoginResponse` object. A new
  session cookie is set in the headers.
- **Response (Unauthorized):** HTTP `401 Unauthorized` for an invalid email or
  invalid password. The response body MUST use the existing
  `ed.unicoach.rest.models.ErrorResponse` schema:
  `{"code": String, "message": String, "fieldErrors": List<FieldError>?}`.
- **Session Overwrite Behavior:** If a valid session cookie is provided during a
  new login request, the existing session MUST be revoked in the database and
  overwritten by the newly authenticated session.

### Domain Orchestration

- **Service Signature:**
  `suspend fun login(email: String, password: String, oldCookieToken: String?, sessionExpirationSeconds: Long, userAgent: String?, initialIp: String?): Result<LoginResult>`
  in `AuthService.kt`.
- **Domain Outcomes:** We will introduce a sealed interface to encapsulate
  expected domain outcomes:
  ```kotlin
  sealed interface LoginResult {
      data class Success(val user: User, val token: String) : LoginResult
      data object Unauthorized : LoginResult
  }
  ```
- **Execution Flow:**
  1. Input validation (trimming and lowering email).
  2. Map email to `EmailAddress` and retrieve user via `UsersDao.findByEmail`.
     Return `Result.success(LoginResult.Unauthorized)` if the user is not found.
  3. Execute `Argon2Hasher.verify` using the dedicated Crypto dispatcher. Return
     `Result.success(LoginResult.Unauthorized)` if the verification yields
     false.
  4. If `oldCookieToken` is provided, delete the old session via
     `SessionsDao.revokeByTokenHash`.
  5. Generate a new token and insert a new session via `SessionsDao.create`.
  6. Return `Result.success(LoginResult.Success(user, newToken))`.

### Dependencies

- Kotlin `Result<T>` global pattern.
- `Argon2Hasher` and `TokenGenerator` (already injected into `AuthService`).

## Tests

### Unit Tests (`service` module)

- **`AuthServiceTest`**:
  - Assert that a valid email and correct password yield
    `Result.success(LoginResult.Success)`.
  - Assert that an invalid email immediately yields
    `Result.success(LoginResult.Unauthorized)`.
  - Assert that a valid email but incorrect password yields
    `Result.success(LoginResult.Unauthorized)`.
  - Assert that providing an `oldCookieToken` correctly calls
    `SessionsDao.revokeByTokenHash` before creating the new session.
  - Assert that providing an `oldCookieToken` that does not exist in the
    database completes without error (no-op deletion) and successfully creates
    the new session.

### Integration Tests (`rest-server` module)

- **`AuthRoutingTest`**:
  - Valid Login Simulation: Insert a test user, dispatch a `LoginRequest`,
    verify `200 OK`, and explicitly assert the `Set-Cookie` header is accurately
    established.
  - Session Overwrite Verification: Log in successfully to acquire a session
    cookie. Dispatch a second `/login` request attaching the original cookie.
    Verify the server returns a _new_ session cookie and that the original
    session was deleted from the database.
  - Malformed Credentials Verification: Submit an invalid password and ensure
    the server responds with a standard `401 Unauthorized`.

## Implementation Plan

1. **Concurrency Configuration (`common`)**
   - Create `common/src/main/kotlin/ed/unicoach/util/CryptoDispatcher.kt`
     defining a custom coroutine dispatcher `Dispatchers.Crypto` backed by a
     fixed thread pool suitable for CPU-bound hashing.
   - Verification: Run `./gradlew :common:compileKotlin`.
2. **Domain Outcome Models (`service`)**
   - Create `service/src/main/kotlin/ed/unicoach/auth/LoginResult.kt` defining
     the sealed interface `LoginResult` with `Success` and `Unauthorized`
     variants.
   - Verification: Run `./gradlew :service:compileKotlin` to ensure syntax is
     valid.
3. **Domain Orchestration Updates (`service`)**
   - Modify `AuthService.kt` to add the `login` function according to the Domain
     Orchestration spec. Ensure `withContext(Dispatchers.Crypto)` wraps
     `Argon2Hasher.verify` to prevent thread starvation.
   - Add unit tests to `AuthServiceTest.kt` verifying the `Unauthorized` and
     `Success` branches, including `oldCookieToken` handling.
   - Verification: Run
     `./gradlew :service:test --tests "ed.unicoach.auth.AuthServiceTest"` to
     confirm correct service layer behavior.
4. **API Models (`rest-server`)**
   - Create `LoginRequest.kt` and `LoginResponse.kt` in
     `rest-server/src/main/kotlin/ed/unicoach/rest/models/`.
   - Verification: Run `./gradlew :rest-server:compileKotlin`.
5. **Route Controller Registration (`rest-server`)**
   - Modify `AuthRouteHandler` in `AuthRoutes.kt` to handle `post("/login")`.
   - Extract `oldCookieToken` and `userAgent` from the request headers. Extract
     `initialIp` from `call.request.origin.remoteHost`. Extract
     `sessionExpirationSeconds` from the application configuration.
   - Invoke `AuthService.login` using these extracted parameters and handle the
     outcome.
   - Map `LoginResult.Unauthorized` to
     `call.respond(HttpStatusCode.Unauthorized, ErrorResponse(...))`.
   - Map `LoginResult.Success` to append the new session cookie and respond
     `200 OK` with `LoginResponse`.
   - Verification: Run `./gradlew :rest-server:compileKotlin`.
6. **Integration Testing (`rest-server`)**
   - Add tests to `AuthRoutingTest.kt` covering successful login, invalid
     credentials, and session overwrite logic.
   - Verification: Run
     `./gradlew :rest-server:test --tests "ed.unicoach.rest.AuthRoutingTest"` to
     assert the complete HTTP lifecycle.
7. **API Documentation**
   - Update `api-specs/openapi.yaml` to define the `/api/v1/auth/login`
     endpoint, clearly specifying the request schema, the `200` response schema,
     and the `401` error.
   - Verification: Visually inspect the YAML syntax.

## Files Modified

- `common/src/main/kotlin/ed/unicoach/util/CryptoDispatcher.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/auth/LoginResult.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/LoginRequest.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/LoginResponse.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [MODIFY]
- `api-specs/openapi.yaml` [MODIFY]
