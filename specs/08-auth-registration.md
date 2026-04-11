## Executive Summary

This specification defines the strict email/password registration flow for the
Unicoach platform. It introduces a `POST /api/v1/auth/register` REST endpoint
that defensively deserializes user credentials computationally restricted by
rigid payload limits, handles explicit connection lifecycles, hashes the
password via Argon2id, and persists the entity securely using `UsersDao`. Upon
explicit success, the server statelessly mints and returns a 7-day JSON Web
Token (JWT) bounded by `HS256` signatures to authenticate the user
automatically, communicating seamlessly via strict value-bound JSON payloads
handled natively by Jackson.

## Detailed Design

### Security & Scope Declarations

- **Rate Limiting (Out of Scope):** Request throttling and DDoS mitigation are
  intentionally deferred to the ingress controller or API gateway.
- **User Enumeration Trade-off:** The explicit `409 Conflict` return implicitly
  allows an attacker to enumerate registered emails. This is an accepted product
  trade-off prioritized over complex asynchronous "Check your email" loop
  registration.
- **Verification Trust Trade-off:** The architecture explicitly returns an
  operational 7-day JWT immediately upon database insertion natively explicitly
  granting immediate functional API access. This inherently trusts the email
  domain symmetrically _prior_ to any future async "Email Verification"
  pipelines being integrated structurally.
- **Token Repudiation (Out of Scope):** The architecture currently omits token
  revocation blocklists (logout invalidation) or rotation limits, implicitly
  accepting the 7-day JWT expiration as the sole boundary for session
  termination.
- **CORS Context:** Access relies exclusively on global Ktor `install(CORS)`
  configuration, conditionally requiring routing extensions cleanly
  acknowledging `OPTIONS` preflight cache checks dynamically prior to
  terminating via `rejectUnsupportedMethods`.
- **Multi-Module Architecture & Hexagonal Alignment:**
  - Cryptographic utilities (`Argon2Hasher`, `JwtGenerator`) represent pure
    domain-agnostic logic residing in `common`.
  - Data abstractions (`UsersDao`, DB Models) and business orchestrators
    (`AuthService`) will cleanly migrate into the `service` module alongside the
    `PostgreSQL JDBC` driver constraint limits.
  - `rest-server` is severely stripped down to strictly parsing HTTP JSON
    boundaries natively invoking the isolated `service` API mathematically.
- **Denial of Service & Buffer Mitigation Limits:** The endpoint MUST
  definitively assert `Content-Type: application/json` explicitly prior to
  buffer mappings conditionally throwing `415 Unsupported Media Type` natively,
  and MUST restrict maximum HTTP request payload lengths to `4KB` proactively
  natively prior to streaming mechanically.
- **Egress & Ingress Obfuscation Limits:** Standard Ktor `CallLogging` pipelines
  MUST explicitly filter or mask logging structures parsing `RegisterRequest`
  (protecting plain-text passwords inherently) AND `RegisterResponse`
  (protecting newly minted bearer tokens entirely) neutralizing pure-text
  secrets flowing into tracing metrics organically.
- **Event Loop Starvation Safety:** Argon2 computation heavily monopolizes CPU
  threads. Native Kotlin coroutine implementations MUST explicitly jump to
  `withContext(Dispatchers.IO)` before initiating cryptographic iterations to
  unconditionally guarantee the Ktor Netty event loop avoids catastrophic
  starvation dropping concurrent connections.

### Data Models

- `data class RegisterRequest(val email: String, val password: String, val name: String)`
  - _Constraints:_ `email` must be trimmed, converted strictly to lowercase, and
    truncated to max 254 chars. `name` must be min 1, max 100 chars, trimmed.
  - _Entropy Controls:_ `password` must be min 8, max 128 chars conditionally
    enforcing rigorous structural complexity mathematically (minimum 1
    uppercase, 1 lowercase, 1 digit) unconditionally mitigating botnet weak-hash
    logic completely natively.
- `data class PublicUser(val id: UserId, val email: String, val name: String)`
  - _Serialization Constraint:_ All nested value classes (e.g. explicitly
    leveraging Domain `UserId` dynamically rather than primitive Strings) MUST
    be unboxed seamlessly by Jackson formatters natively preventing
    `{ "id": { "value": "uuid" } }` memory leaks traversing boundaries blindly.
- `data class RegisterResponse(val user: PublicUser, val token: String)`
- `data class FieldError(val field: String, val message: String)`
- `data class ErrorResponse(val code: String, val message: String, val fieldErrors: List<FieldError>? = null)`
  - _Global Baseline:_ Both `FieldError` and `ErrorResponse` MUST be declared as
    generic baseline application constructs cleanly applied uniformly across all
    future REST routes, guaranteeing structural compatibility organically
    preventing diverging error formats system-wide.

### Cryptographic General Utilities (`common` module)

- **Argon2Hasher:** Exposes a generalized `hash(plaintext: String): String`
  function utilizing the memory-hard configuration of `de.mkammerer:argon2-jvm`.
  It must ingest its security parameters horizontally from HOCON configuration
  (`argon2.memory`, `argon2.iterations`, `argon2.parallelism`) to ensure
  environment-specific tuning.
  - _Constraint:_ Computation MUST be structurally bound by an explicit timeout
    constraint (e.g., `withTimeout(2000L)`) mitigating event-loop starvation
    definitively.
- **JwtGenerator:** Ingests target configurations (`jwt.secret`, `jwt.issuer`)
  statically traversing a deterministically injected `java.time.Clock`. It
  purely executes
  `mint(subjectId: String, claims: Map<String, Any> = emptyMap()): String`,
  structurally generalizing the tool to accommodate arbitrary scopes cleanly
  natively. It returns the raw encoded JWT token statically ensuring `common`
  remains completely functionally decoupled from HTTP response DTOs. Expiration
  is hard-locked dynamically against the injected `Clock` natively parsing
  `+ 7 days` systematically.

### Database Infrastructure (`service` module)

- **Database Configuration & HikariCP:** We introduce a formal `Database` class
  mapped natively to a `DatabaseConfig` primitive definition (parsed rigidly
  from HOCON attributes like `url`, `user`, `maximumPoolSize`,
  `connectionTimeout: 3000ms`). The state natively retains a configured
  `HikariDataSource`.
- **Explicit Transaction Boundary:** The layout exposes a strict
  `fun <T> withConnection(block: (SqlSession) -> T): T`. It mathematically
  enforces pure transaction lifecycles by extracting a connection with
  `autoCommit = false`, applying a
  `try { block(session); commit(); } catch { rollback(); throw e; }` sequential
  constraint natively. CRITICAL: Context MUST evaluate explicitly through a
  `finally { conn.autoCommit = originalAutoCommit }` sequence defensively;
  pooling mechanics silently leak `autoCommit = false` sessions unconditionally
  contaminating disjoint threads dynamically if skipped natively.
- **SqlSession & Dao Relocation:** The `SqlSession` interface, `UsersDao`
  object, and purely functional database `models` must structurally relocate
  into `service`.

### Domain Orchestration (`service` module)

- **`AuthService.kt`**: Maps a cohesive business layer isolating HTTP semantics
  from raw data access unconditionally. Exposes a pure domain orchestration
  method `register(email: String, name: String, password: String): AuthResult`
  definitively blocking any external REST DTO mappings (like `RegisterRequest`)
  from contaminating the `service` layer natively.
- **Value Class Aggregation**: `AuthService` natively initiates string mappings
  sequentially into Kotlin Value Classes (`EmailAddress.create`,
  `PasswordHash.create`). If domain creation returns `ValidationResult.Invalid`,
  `AuthService` maps and aggregates them organically recursively mathematically
  trapping constraints natively without invoking JDBC parameters
  unconditionally.
- **`AuthResult` Sealed Hierarchy**: `AuthService` translates raw DAO unions
  cleanly up boundaries using an explicitly modeled
  `sealed interface AuthResult` organically preventing external dependencies
  mathematically.
  - `Success(user, token)`: Contains domain primitive success maps cleanly
    natively.
  - `ValidationFailure(val errors: List<FieldError>)`: Gracefully tracks
    malformed bounds preventing database invocations.
  - `DuplicateEmail`: Encapsulates DAO 23505 constraint violations for `users_email_unique_active_idx`.
  - `DatabaseFailure(override val rootCause: AppError)`: Maps persistence exceptions or constraint violations. Catch blocks MUST pass ALL root cause data upward explicitly satisfying the `AppError` contract. The ultimate error handler (e.g., routing) must receive the unaltered root cause of the error, ensuring data is not prematurely filtered or stripped natively.
### API Contract (`POST /api/v1/auth/register`)

- **Evaluation Loop Mapping:**
  - Enforces explicit `application/json` mapping cleanly throwing `415`
    organically implicitly before reading stream contents mathematically.
  - Restricts payload stream immediately rejecting bodies over `4KB` proactively
    natively via `call.request.contentLength()` explicitly failing prior to
    Jackson binding.
  - Intercepts explicit `JsonMappingException` and
    `ContentTransformationException` pipeline exceptions identically mapping
    primitive syntax failures symmetrically into robust `FieldError` format
    targets natively within `StatusPages`.
  - Normalizes primitive strings explicitly iteratively bounds logic naturally
    delegating seamlessly to `AuthService.register(email, name, password)`.
- **Exhaustive Output Mapping:**
  - `AuthResult.Success`: Generates `RegisterResponse` passing token explicitly
    within JSON boundaries (transport assumption maps to Bearer token semantics
    implicitly resolving `201 Created`).
  - **Error Routing Constraint:** Do not manually construct `ErrorResponse` DTOs inside `when` branches in `AuthRoutes.kt`. Delegate failure paths to a shared extension function (e.g., `suspend fun ApplicationCall.respondAppError(error: AppError, status: HttpStatusCode)`). The 400 ValidationFailure, 409 DuplicateEmail, and 500 DatabaseFailure mappings MUST route through this extension.

### Serialization Configuration

To strictly enforce Jackson bounds mapping JSON structurally to primitive value
classes correctly natively (unboxing automatically):

- Install standard `ContentNegotiation` plugin.
- Configure Jackson explicitly loading `KotlinModule.Builder().build()` and
  registering any specific UUID/ValueClass scalar module logic definitively.
- MUST explicitly configure Jackson with
  `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` and
  `DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES` set to `true`
  strictly fulfilling the allowlist principle mathematically rejecting any
  unpredictable surplus JSON payload mappings organically, applying the same
  principle natively to global `StatusPages` error mapping.

### Dependencies

- `de.mkammerer:argon2-jvm`: New dependency inside the `common` module. Must
  explicitly resolve version `2.12` or explicitly latest stable enforcing modern
  Argon2id capabilities.
- `com.auth0:java-jwt`: (or equivalent JWT primitive layer) New dependency
  inside the `common` module to generate tokens gracefully without importing
  massive HTTP dependencies.
- `com.zaxxer:HikariCP`: New dependency mapped definitively inside the `service`
  module ensuring stable `6.x` pooled routing limits.
- `io.ktor:ktor-server-auth-jwt`: New dependency inside `rest-server`. Resolving
  exact version matching existing core Ktor version in `libs.versions.toml`
  natively.
- `io.ktor:ktor-serialization-jackson`: New dependency inside `rest-server`.

## Tests

### Unit & Service Tests (`common` & `service`)

- **JwtGeneratorTest:** Verify `mint()` generates a token using a sequentially
  fixed `Clock` instance natively.
- **Argon2HasherTest:** Hash a known password iteratively structurally
  validating configuration correctness natively dynamically guaranteeing
  internal JNA (Java Native Access) dependency linkages compile correctly
  gracefully mapping the underlying C-wrapper limits natively.
- **AuthServiceTest:** Executes business domain boundary integration tests
  utilizing the `Database` configurations. MUST explicitly generate inputs
  violating complexity rules (lacking uppercase, numerals, <8 chars) statically
  verifying boundary rejection defensively natively.

### Integration Tests (Ktor Routes)

Integration testing runs forcefully against Ktor's `testApplication` engine
evaluating standard HTTP boundaries executing against the ephemeral PostgreSQL
instance.

- _CRITICAL Constraint:_ Test suites dynamically manipulating data constraints
  globally (`DELETE FROM users`) MUST definitively force JUnit
  `@Execution(ExecutionMode.SAME_THREAD)` limits unconditionally mitigating
  catastrophic structural overlap flakiness intrinsically present within async
  parallel builds logically.
- _CRITICAL Constraint:_ Suite MUST cleanly evaluate `DELETE FROM users`
  isolation hooks enforcing total state cleanup boundaries unconditionally
  before and after HTTP dispatches.
- **Valid Registration State Simulation:** Dispatch JSON `RegisterRequest`
  payload, verifying `201 Created` mapping both `user` and `token` correctly
  gracefully natively.
- **CORS Configuration Validation Hooks:** Dispatch pure
  `OPTIONS /api/v1/auth/register` queries cleanly evaluating cross-origin
  framework rules actively natively ensuring routing preflight caches actively
  bypass native HTTP mappings returning `200 OK` globally safely.
- **Header Structure Verification Constraints:** Execute test payloads using raw
  `text/plain` headers implicitly triggering specific
  `415 Unsupported Media Type` protections natively structurally before stream
  exhaustion logic natively invokes bounds statically.
- **Unique Invariant & Malicious Vector Rejection:** Dispatch valid user
  `TestUser@Company.com` creating account context. Immediately dispatch
  structurally identical collision test mimicking HTTP injection
  `testuser@company.com` strictly verifying the domain mapping enforces 409
  boundaries case-insensitively correctly.
- **Large Buffer Mitigation Rejection:** Submit a JSON payload artificially
  padded with 10KB of whitespace characters logically asserting an upstream
  `413 Payload Too Large` natively or explicit connection closure before parsing
  memory is exhausted.
- **StatusPages Deserialization Boundaries:** Dispatch explicitly out-of-bounds
  payloads (<8 char passwords, empty names, null bytes) mathematically asserting
  `400 Bad Request` mapped cleanly by `StatusPages` propagating detailed error
  strings uniformly within `ErrorResponse.details`.
- **Timing Attack Mitigation:** Verify programmatically submitting a duplicate
  payload incurs computationally similar delays defensively by wrapping requests
  in `kotlin.system.measureTimeMillis` and asserting variance is `< 100ms`.

## Implementation Plan

1.  **Module Initialization & Dependency Sourcing:**
    - Initialize the `common` AND `service` modules via `settings.gradle.kts`.
    - Install `argon2-jvm` (verifying `jna` transitives align logically) and
      `java-jwt` (v4.x) into `common/build.gradle.kts`. Update
      `gradle/libs.versions.toml` to structurally bind `HikariCP`, `argon2-jvm`,
      and `java-jwt` versions explicitly.
    - Install `HikariCP` strictly into `service/build.gradle.kts`. Map
      `testImplementation(libs.postgresql)`, `junit` logically ensuring database
      assertions map independently correctly organically.
    - **Hexagonal Relocation:** Move the `postgresql` JDBC dependency completely
      out of `rest-server` and definitively into `service`. Shift
      `ed/unicoach/db/*` (Dao, Models, SqlSession) sequentially into the
      `service` module recursively.
    - Setup module dependencies defensively tracking
      `implementation(project(...))` loops.
    - (_Verification: `./gradlew classes` successfully compiles the newly
      arranged artifact graph._)
2.  **Configuration Wiring:** Update `rest-server.conf` adding HOCON properties
    accurately handling `jwt` and `argon2` tuning blocks. Update
    `application-test.conf`.
3.  **General Utility Implementation:** Implement `Argon2Hasher` as an injectable class and construct a generic `Validator<T>` logically abstracted structurally mapping directly into the base `common` module. Write `Database.kt` to implement a `withConnection` 
    wrapper handling Hikari connection pools using strict `try/catch/finally` 
    transaction rollbacks.
    - **CRITICAL CONSTRAINT:** Implement `Argon2HasherTest.kt` and `JwtGeneratorTest.kt` before proceeding.
4.  **Domain Orchestration Implementation (`service`):** Define `AuthResult.kt`.
    Write `AuthService.kt` defining a `register` function shifting computation
    context into `withContext(Dispatchers.IO)` around the Argon2 operation
    before calling `Database.withConnection` and executing `UsersDao.create`.
    - **CRITICAL CONSTRAINT:** Implement `AuthServiceTest.kt` testing valid sequences and boundaries. This step is incomplete until the unit test is written.
5.  **Semantic Models Implementation (`rest-server`):** Generate clean
    HTTP-bound `RegisterRequest`, `PublicUser`, `RegisterResponse`, and
    `ErrorResponse` artifacts uniquely.
6.  **Route Controller Registration & Serialization:** Develop
    `Serialization.kt` explicitly registering Jackson mapping bounds. Develop
    `StatusPages.kt`. Develop `AuthRoutes.kt` cleanly resolving HTTP payloads by
    forwarding cleanly to `AuthService.register()` logically mapping output
    responses automatically.
7.  **Integration Testing Bounds:** Create `AuthRoutingTest.kt`, isolating the
    PostgreSQL database. Use integration assertions confirming valid and mapped
    `400/409` states.
    - **CRITICAL CONSTRAINT:** Implement `AuthRoutingTest.kt` enforcing all 7 validation scenarios referenced in the `Tests` section. Verify via `./bin/test`.
8.  **Dependency Matrix Update:** Explicitly attach `configureSerialization()`,
    `configureStatusPages()`, and generic `auth` loops into target module
    mapping in `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`
    structurally routing endpoints recursively structurally connecting
    `Routing.kt`. (_Verification: Run `./bin/test` ensuring the application
    server boots naturally handling integration logic fully without injection
    exceptions._)
9.  **API Documentation:** Update `api-specs/openapi.yaml` to define the new
    endpoints natively.

## Files Modified

- `settings.gradle.kts` [MODIFY]
- `common/build.gradle.kts` [NEW]
- `service/build.gradle.kts` [NEW]
- `gradle/libs.versions.toml` [MODIFY]
- `rest-server/build.gradle.kts` [MODIFY]
- `rest-server/src/main/resources/rest-server.conf` [MODIFY]
- `rest-server/src/main/resources/application-test.conf` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt` [DELETE]
- `service/src/main/kotlin/ed/unicoach/db/dao/SqlSession.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/dao/UsersDao.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/models/AuthMethod.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/models/User.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/models/EmailAddress.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/models/PersonName.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/models/PasswordHash.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/DatabaseConfig.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/Database.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/auth/AuthResult.kt` [NEW]
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` [NEW]
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt` [NEW]
- `common/src/main/kotlin/ed/unicoach/util/Argon2Hasher.kt` [NEW]
- `common/src/main/kotlin/ed/unicoach/util/JwtGenerator.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/util/Argon2HasherTest.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/util/JwtGeneratorTest.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/RegisterRequest.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/PublicUser.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/RegisterResponse.kt`
  [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/FieldError.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/ErrorResponse.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/Serialization.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/StatusPages.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routes/AuthRoutes.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Routing.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [NEW]
- `api-specs/openapi.yaml` [MODIFY]
