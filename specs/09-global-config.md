## Executive Summary

This specification implements a centralized configuration architecture using the
`com.typesafe:config` library. Currently, `rest-server` uses Ktor's
`ApplicationConfig` while `service` uses `System.getenv()`. This change
deprecates manual mapping and unifies configuration parsing in the `common`
module. All application layers will parse their respective module-specific
`.conf` files (e.g. `rest-server.conf`, `service.conf`) and environment
variables through a single HOCON pipeline natively wrapped in functional
`Result` types.

## Detailed Design

### Configuration Core (`common`)

- Add the `typesafe-config` dependency to the `common` module using the `api`
  configuration.
- **Architectural Invariant:** There must be exactly one `.conf` file per module
  (e.g., `common.conf`, `service.conf`). Creating environment-specific config
  files like `rest-server-test.conf` is strictly forbidden. Environment
  variations must be securely specified through `.env*` files (e.g.,
  `.env.test`). The `.conf` files must structurally map these values using
  standard HOCON substitution syntax (e.g.,
  `database.jdbcUrl = ${DATABASE_JDBCURL}`). This guarantees `.env*` files act
  as the sole source of mutable deployment bounds fed seamlessly through
  environment variables into the explicit `.conf` structural tree.
- Implement `AppConfig` to centralize configuration loading. It must expose a
  `load(vararg resources: String): Result<Config>` function wrapped in
  `runCatching` that loads and merges the listed resources sequentially
  (right-most arguments have highest precedence). Pass the combined config directly into 
  `ConfigFactory.load(mergedConfig)` to natively process system environment overrides 
  and defaults without manually reinventing `.withFallback()` boilerplate. OS-level 
  priority mapping natively functions automatically via the internal `.conf` HOCON 
  `${ENV_VAR}` substitutions directly.
- Implement `SecretString` to wrap sensitive configuration values like JWT
  secrets. It must be a standard `class` (absolutely NOT a Kotlin `data class`)
  to guarantee that default `copy()`, `equals()`, or `toString()`
  implementations do not bypass the obfuscation boundary implicitly.

### Service Layer Config (`service`)

- Implement `DatabaseConfig` in the `service` module to manage database
  connection properties.
- Implement `DatabaseConfig.from(config: Config): Result<DatabaseConfig>` to
  extract `database.jdbcUrl`, `database.user`, `database.password`,
  `database.maximumPoolSize`, and `database.connectionTimeout` using
  `config.getString()` and `config.getInt()`. Supply these properties to
  `HikariConfig` to initialize the `HikariDataSource`.
- Wrap the extraction logic in a `runCatching` block to return a
  `Result.failure` containing the native `ConfigException`. Extracted properties
  dynamically evaluate structural checks rejecting `.isBlank()` variables to
  prevent silent default nulls.
- **Resource Lifecycle:** The resulting `HikariDataSource` must be registered
  with Ktor's `environment.monitor.subscribe(ApplicationStopped)` hook
  structurally from `main()` to guarantee the connection pool is securely
  `close()`'d preventing port exhaustion on restart.

### Web Layer Config (`rest-server`)

- Implement `JwtConfig.from(config: Config): Result<JwtConfig>` to extract and
  store the JWT `issuer`, `audience`, and `secret` inside a `runCatching` block
  proactively trapping `.isBlank()` strings natively mapped to
  `ConfigException`. The secret must be stored as a `SecretString`.
- Remove Ktor's generic `ApplicationConfig` property routing. In `main()`,
  initialize configuration by chaining
  `AppConfig.load("common.conf", "service.conf", "rest-server.conf")` and
  `DatabaseConfig.from(...)`.
- **Framework Compatibility:** The extracted Typesafe `Config` must be bridged
  back into an `HoconApplicationConfig` adapter immediately before instantiating
  the HTTP engine. This guarantees Ktor's community plugins (like built-in auth
  or internal routing modules) do not crash from missing configs.
- Bind the network layer by extracting `config.getString("server.host")` and
  `config.getInt("server.port")` explicitly mapping them to
  `embeddedServer(Netty, host = ..., port = ...)`.
- On startup failure across any `Result`, the extracted configuration must
  bubble out natively via `getOrThrow()` to allow the Java stack unwinding and
  frameworks (like JUnit) to capture and report the unhandled exception cleanly
  rather than subverting process teardown implicitly via `exitProcess(1)`.

## Tests

### Unit Tests

- `AppConfigTest`: Verify `load(vararg)` prioritizes right-most module overrides
  correctly against arbitrary input configurations.
- `AppConfigTest`: Verify `System.getenv()` forcefully overrides all identically
  named fields.
- `SecretStringTest`: Verify `.toString()` outputs a masked generic string
  preventing raw secret exposure, and ensure the class lacks generated
  `data class` components.
- `DatabaseConfigTest`: Verify missing configuration fields evaluate natively to
  `Result.failure(ConfigException)` strictly asserting via
  `assertTrue { result.isFailure }`. Guarantee the test strictly avoids
  instantiating a raw `HikariDataSource` to enforce zero-network isolation.
- `JwtConfigTest`: Verify missing secrets, audiences, or issuers evaluate
  natively to `Result.failure(ConfigException)`.

### Central Integration Test Fixture

- Remove Ktor's `testApplication { }` block from integration tests.
- Isolate the server bootstrap mechanism into a `startServer(wait: Boolean)`
  function exported separately from `main()`. Integration tests execute this
  initiation natively via `startServer(wait = false)`, guaranteeing 100%
  startup parity.
- Retrieve the natively bound port explicitly bypassing anti-pattern polling loops
  directly via `testServer.engine.resolvedConnectors().first().port`.
- **Environment Targeting:** Tests must dynamically inject test boundaries (like
  the local docker-compose test database) solely through mapping `.env.test`
  into the standard process environment prior to configuration rather than
  mapping global JVM system variables manually or defining nested test `.conf`
  inheritance layers.
- Execute HTTP requests against the application using a standard
  `HttpClient(CIO)` pointed to `http://localhost:<bound_port>`.

## Implementation Plan

1. **Common Parsing Integration (`common`)**
   - Add `typesafe-config` to `gradle/libs.versions.toml`.
   - Add `typesafe-config` to `common/build.gradle.kts` as an `api` dependency.
   - Create `common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt` to
     expose `AppConfig.load(vararg resources: String): Result<Config>`.
   - Create `common/src/main/kotlin/ed/unicoach/common/config/SecretString.kt`
     explicitly avoiding `data class`.
   - Create `common/src/main/resources/common.conf` as the base configuration
     defining default fallbacks (e.g. `connectionTimeout`).
   - Implement `AppConfigTest.kt` and `SecretStringTest.kt`.
2. **Service Boundaries (`service`)**
   - Retain `service.conf` for service specific configuration defining database
     bindings.
   - Implement `DatabaseConfig.from(config)` resolving `maximumPoolSize` and
     `connectionTimeout`.
   - Implement `DatabaseConfigTest.kt` bounding failure states.
   - Update `UsersDaoTest.kt` to load configuration via
     `AppConfig.load("common.conf", "service.conf")`.
3. **Web Boundary Extraction (`rest-server`)**
   - Retain `rest-server.conf` for web specific configuration defining
     `server.host` and `server.port`.
   - Implement `JwtConfig.kt` parsing `audience` alongside the `SecretString`
     extracted string.
   - Implement `JwtConfigTest.kt` bounding extraction failures.
   - Update `Application.kt` to extract a `startServer()` bootstrap flow resolving configurations
     iteratively natively via `.getOrThrow()`, register `ApplicationStopped` shutdown hooks for the DAO pool,
     and bridge `AppConfig` into an environment application config struct.
4. **Integration Test Refactor (`rest-server`)**
   - Bind the local docker-compose test database securely via `.env.test` loaded
     natively into the system environment prior to test execution while avoiding
     physical `.conf` layer creations.
   - Update `RoutingTest.kt` and `AuthRoutingTest.kt` to execute `startServer(wait = false)` directly mapping the returned Netty bindings dynamically into `HttpClient(CIO)`.
   - Configure `HttpClient(CIO)` to target the bound port securely without test
     frameworks parsing boundaries implicitly.

## Files Modified

- `gradle/libs.versions.toml` [MODIFY]
- `common/build.gradle.kts` [MODIFY]
- `common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt` [NEW]
- `common/src/main/kotlin/ed/unicoach/common/config/SecretString.kt` [NEW]
- `common/src/main/resources/common.conf` [NEW]
- `common/src/test/kotlin/ed/unicoach/common/config/AppConfigTest.kt` [NEW]
- `common/src/test/kotlin/ed/unicoach/common/config/SecretStringTest.kt` [NEW]
- `service/src/main/resources/service.conf` [NEW]
- `service/src/main/kotlin/ed/unicoach/db/Database.kt` [MODIFY]
- `service/src/main/kotlin/ed/unicoach/db/DatabaseConfig.kt` [NEW]
- `service/src/test/kotlin/ed/unicoach/db/dao/UsersDaoTest.kt` [MODIFY]
- `service/src/test/kotlin/ed/unicoach/db/DatabaseConfigTest.kt` [NEW]
- `rest-server/build.gradle.kts` [MODIFY]
- `rest-server/src/main/resources/rest-server.conf` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/JwtConfig.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/RoutingTest.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/AuthRoutingTest.kt` [MODIFY]
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/JwtConfigTest.kt` [NEW]
- `.env.test` [MODIFY]
