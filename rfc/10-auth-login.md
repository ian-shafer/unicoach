## Executive Summary

This specification defines the email/password authentication flow for unicoach.
It introduces a `POST /api/v1/auth/login` REST endpoint that defensively
deserializes user credentials restricted by payload limits, retrieves the entity
utilizing `UsersDao`, and verifies the password against the stored Argon2id
hash. To strictly prevent timing-based user enumeration, the flow enforces dummy
cryptographic verification unconditionally when users are not found. Upon
explicit success, the server statelessly mints and returns a 7-day JSON Web
Token (JWT), matching the initialization semantics of the registration flow.

## Detailed Design

### Security & Scope Declarations

- **Rate Limiting (Out of Scope):** Request throttling and brute-force lockouts
  are intentionally deferred to the ingress controller or API gateway.
- **Timing Attack Mitigation (User Enumeration):** The authentication workflow
  MUST explicitly mitigate time-based enumeration. If a `LoginRequest` specifies
  an email that does not exist in the database, the server MUST NOT immediately
  return a `401 Unauthorized`. Instead, it MUST execute a dummy Argon2
  computation against a static ghost hash to precisely mirror the computational
  penalty (~500ms) of a valid user lookup before returning the generic `401`.
- **Suspended Account Status Leakage:** Accounts marked `is_active = false` MUST
  map directly into the Timing Attack Mitigation flow natively. They MUST NOT
  return distinct `403 Forbidden` statuses organically. An inactive account must
  mathematically mirror a non-existent account completely preventing termination
  status leakage.
- **Event Loop Starvation Safety:** Argon2 hash verification heavily monopolizes
  CPU threads. Native Kotlin coroutine implementations MUST explicitly jump to a
  dedicated bounded cryptographic dispatcher before initiating cryptographic
  comparisons to unconditionally guarantee the Ktor Netty event loop and
  database IO threads avoid catastrophic starvation.
- **Token Repudiation (Out of Scope):** The architecture currently omits token
  revocation blocklists (logout invalidation) or rotation limits, implicitly
  accepting the 7-day JWT expiration as the sole boundary for session
  termination.
- **Denial of Service & Buffer Mitigation Limits:** The endpoint MUST
  definitively assert `Content-Type: application/json` explicitly and restrict
  maximum HTTP request payload lengths to `4KB` proactively natively prior to
  streaming mechanically, mirroring the registration endpoint limits.
- **Egress & Ingress Obfuscation Limits:** Standard Ktor `CallLogging` pipelines
  MUST explicitly filter or mask logging structures parsing `LoginRequest`
  (protecting plain-text passwords inherently) AND `LoginResponse` (protecting
  newly minted bearer tokens entirely).

### Data Models

- `data class LoginRequest(val email: String, val password: String)`
  - _Constraints:_ `email` bound iteratively using the same `EmailAddress` value
    class definitions as registration. `password` enforces basic string bounds
    natively (`min 1`, strictly capped at `maxLength = 256`) specifically to
    prevent Argon2 buffer padding DoS attacks. It avoids the structural
    complexity requirements of registration natively (as the user already
    exists, enforcing complexity on login is logically redundant).
- `data class LoginResponse(val user: PublicUser, val token: String)`
  - _Serialization Constraint:_ Strictly reuses the exact `PublicUser` object
    definition established in `08-auth-registration.md`.

### Cryptographic General Utilities (`common` module)

- **Argon2Hasher:** Exposes a generalized
  `verify(hash: String, plaintext: String): Boolean` function.
  - _Ghost Hash Mechanism:_ The module MUST expose an internal constant
    `DUMMY_HASH` that is dynamically computed exactly once during application
    startup utilizing the active HOCON `argon2` tuning parameters natively. It
    MUST NOT be hardcoded, ensuring the mathematical salt varies unpredictably
    across server lifecycles, mitigating pre-computed Rainbow Table profiling
    against the dummy verification time logic natively.
  - _Dedicated Crypto Dispatcher Constraint:_ Computation MUST execute on a
    dedicated thread pool (e.g.,
    `Executors.newFixedThreadPool(16).asCoroutineDispatcher()`) distinct from
    `Dispatchers.IO`. If an attacker floods the login endpoint, isolating Argon2
    prevents the attack from exhausting the global IO bounds responsible for
    database access natively. It MUST also be bound by an explicit timeout
    (e.g., `withTimeout(2000L)`).

### Database Infrastructure (`service` module)

- **UsersDao Relocation Context:** The layout expands the existing `UsersDao` to
  include `fun findByEmail(session: SqlSession, email: EmailAddress): User?`.
  - Maps to `SELECT * FROM users WHERE email = ? AND is_active = true`.
    Optionally filters inactive/suspended users logically mapped natively.
- **Configuration Parsing Limits:** The application MUST implicitly fail-fast
  unconditionally at `main()` startup logically throwing explicit initialization
  errors if critical `HOCON` keys (`jwt.secret`, database properties) are
  structurally missing, preventing the routing tree from binding organically
  natively.
- **PII Hikari Limits:** Database connections MUST explicitly forcefully disable
  logging of SQL parameters (e.g. `log-statement-text = false` or Hikari
  equivalents) directly mitigating PostgreSQL logs from implicitly extruding
  `EmailAddress` strings recursively to `stdout` conditionally.

### Domain Orchestration (`service` module)

- **`AuthService.kt` Expansion**: Adds
  `login(email: String, password: String): AuthResult`.
  - Natively initiates string bindings symmetrically, heavily enforcing
    `email.trim().lowercase()` mapping logic iteratively into Kotlin Value
    Classes (`EmailAddress.create`). If the email value mapping fails, it
    natively directly returns an explicitly modeled `ValidationFailure` without
    executing DB logic natively.
  - Explicitly wraps `UsersDao.findByEmail` in `Database.withReadOnlyConnection`
    enforcing `Connection.TRANSACTION_READ_COMMITTED` to prevent unnecessary
    write-locks globally. Database queries MUST map directly through
    `withContext(Dispatchers.IO)` cleanly preventing Netty locks unconditionally
    natively. Subsequently, `Argon2Hasher` MUST execute in its isolated Crypto
    Dispatcher accurately avoiding JDBC resource contention natively.
  - If a user is found, calls
    `Argon2Hasher.verify(user.passwordHash.value, password)`.
  - If a user is NOT found, calls `Argon2Hasher.verify(DUMMY_HASH, password)`.
  - Both paths MUST return a pure, newly defined `AuthResult.Unauthorized`
    explicitly modeled within the sealed hierarchy if verification yields false
    or user was missing.
  - Success path returns `AuthResult.Success(user, token)` identically
    leveraging
    `JwtGenerator.mint(subjectId = user.id.value, issuer = config.jwtIssuer)`.
    The JWT MUST cleanly assert target environment audience constraints
    natively.

### API Contract (`POST /api/v1/auth/login`)

- **Evaluation Loop Mapping:**
  - Enforces explicit `application/json` mapping cleanly throwing `415`.
  - Restricts payload stream immediately rejecting bodies over `4KB` proactively
    natively via `call.request.contentLength()`.

**OpenAPI YAML Specification**:

```yaml
/api/v1/auth/login:
  post:
    summary: Authenticate a user
    operationId: loginUser
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/LoginRequest"
    responses:
      "200":
        description: User authenticated successfully
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/LoginResponse"
      "400":
        description: Validation failure (malformed payload)
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/ErrorResponse"
      "401":
        description: Unauthorized (invalid credentials)
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/ErrorResponse"
      "415":
        description: Unsupported Media Type (must be application/json)
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/ErrorResponse"
      "500":
        description: Internal Server Error
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/ErrorResponse"

  # ... added to components/schemas:
  LoginRequest:
    type: object
    required: [email, password]
    properties:
      email:
        type: string
        format: email
        minLength: 1
      password:
        type: string
        minLength: 1
  LoginResponse:
    type: object
    required: [user, token]
    properties:
      user:
        $ref: "#/components/schemas/PublicUser"
      token:
        type: string
```

- **Exhaustive Output Mapping:**
  - `AuthResult.Success`: Generates `LoginResponse` returning `200 OK`.
  - `AuthResult.Unauthorized`: Uses the global `StatusPages` error mapping to
    return `401 Unauthorized` with an
    `ErrorResponse("UNAUTHORIZED", "Invalid email or password")`. The message
    MUST NOT specify whether the email or the password was incorrect.
  - `AuthResult.ValidationFailure` & `AuthResult.DatabaseFailure`: Propagates
    identically to the registration layout mapping natively.
  - `JsonParseException` & `ContentTransformationException`: The global
    `StatusPages` MUST strictly catch Jackson pipeline failures mapping
    malformed syntaxes linearly into `400 Bad Request` avoiding 500 leaks.

## Tests

### Unit & Service Tests (`common` & `service`)

- **ApplicationConfigTest:** Programmatically instantiate application
  configurations utilizing empty or malformed HOCON properties to natively
  assert fail-fast mechanisms explicitly throw `IllegalArgumentException` or
  `null` constraints synchronously checking limits.
- **UsersDaoTest:** Verify `findByEmail` correctly retrieves existing records
  iteratively mapping database structs to value classes, and structurally
  guaranteeing `null` is returned for inactive or non-existent emails natively.
- **Argon2HasherTest:** Enforce explicitly testing the `verify` bounds comparing
  a known hash cleanly natively dynamically.
- **AuthServiceTest:**
  - Verifies successful authentication maps cleanly natively to
    `AuthResult.Success`.
  - Explicitly executes test dispatch sequences asserting invalid passwords
    yield `AuthResult.Unauthorized`.
  - Explicitly executes test dispatch sequences asserting non-existent and
    suspended emails yield `AuthResult.Unauthorized` organically natively.

### Integration Tests (Ktor Routes)

- **CORS Configuration Validation Hooks:** Dispatch pure
  `OPTIONS /api/v1/auth/login` queries enforcing routing preflight cross-origin
  framework rules actively natively ensuring routing checks bypass strict
  boundaries returning `200 OK`.
- **Valid Login State Simulation:** Insert a test user explicitly into the DB.
  Dispatch JSON `LoginRequest` payload, verifying `200 OK` mapping both `user`
  and `token` correctly gracefully natively.
- **Normalization Integrity Assertion:** Submit a valid user request mapped with
  `" email@COMPANY.com "` enforcing the orchestration layer explicitly trims and
  lowercases inputs mathematically before triggering database lookups
  successfully.
- **Suspended Account Emulation:** Map `is_active = false` onto a valid user
  organically. Dispatch legitimate credentials asserting the execution correctly
  propagates a pure `401 Unauthorized` identical to missing users systematically
  preventing `403` leakage natively.
- **Timing Attack Mitigation Assertion:** Programmatically wrap requests to a
  missing user `unknown@company.com` and an existing user with an invalid
  password using `kotlin.system.measureTimeMillis`. Verify the execution
  variance is computationally similar (e.g., `< 100ms` variance), asserting the
  dummy hash successfully absorbs the expected cycle penalties cleanly.
- **Header Structure & Large Buffer Verification Constraints:** Dispatch missing
  generic components triggering `415` and dispatch 10KB payloads asserting `413`
  natively structurally tracking the exact identical protections applied to
  `/register`.
- **Masking Assertion:** Verify logging interceptors proactively mask
  `LoginRequest.password` payloads explicitly preventing text bleeds completely
  natively.

## Implementation Plan

1.  **General Utility Updates (`common`):**
    - Modify `Argon2Hasher.kt` to include the `verify(hash, plaintext): Boolean`
      method.
    - Introduce a hardcoded structurally sound `DUMMY_HASH` string within
      `Argon2Hasher` to absorb penalty cycles safely natively.
    - Write unit tests in `Argon2HasherTest.kt`.
2.  **Database Updates (`service`):**
    - Enhance `UsersDao.kt` by adding
      `findByEmail(session: SqlSession, email: EmailAddress): User?`.
    - Enhance `UsersDaoTest.kt` verifying positive and null states strictly
      testing database retrieval paths independently logically.
3.  **Domain Orchestration Updates (`service`):**
    - Extend `AuthResult.kt` to define `data object Unauthorized : AuthResult`.
    - Enhance `AuthService.kt` to implement the `login(email, password)`
      coroutine mathematically invoking `withContext(Dispatchers.IO)` cleanly
      mapping `Argon2Hasher.verify`.
    - Modify `AuthServiceTest.kt` to thoroughly validate the ghost-hash
      invocation behavior recursively natively.
4.  **Semantic Models Update (`rest-server`):**
    - Create `LoginRequest.kt` and `LoginResponse.kt` artifacts uniquely bound
      mapping strictly logically natively.
5.  **Route Controller Registration (`rest-server`):**
    - Modify `AuthRoutes.kt` to handle `post("/login")`.
    - Intercept `AuthResult.Unauthorized` explicitly routing to
      `respondAppError(..., HttpStatusCode.Unauthorized)` correctly mapping
      natively.
6.  **Integration Testing Bounds:**
    - Enhance `AuthRoutingTest.kt` to encapsulate all testing criteria outlined
      cleanly natively executing the time variance analysis forcefully.
7.  **API Documentation:** Update `api-specs/openapi.yaml` to define `/login`
    natively.

## Files Modified

- `common/src/main/kotlin/ed/unicoach/util/Argon2Hasher.kt` [MODIFY]
- `common/src/test/kotlin/ed/unicoach/util/Argon2HasherTest.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/ApplicationConfigTest.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/auth/AuthResult.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/LoginRequest.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/LoginResponse.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routes/AuthRoutes.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [MODIFY]
- `api-specs/openapi.yaml` [MODIFY]
