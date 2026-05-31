# RFC 28: Coroutine Context Refactor

## Executive Summary
`AuthService`, `Argon2Hasher`, and `Database` currently manage coroutine dispatchers at inconsistent layers. `Argon2Hasher.hash`/`verify` hard-code `Dispatchers.IO` internally; `AuthService` redundantly wraps utility calls in its own `withContext` blocks (including a latent bug where `register` hashes passwords on `Dispatchers.IO` while `login` verifies on `Dispatchers.Crypto`); `Database.withConnection` is a blocking, non-suspend function that callers must defensively wrap.

This refactor enforces the Context-Preserving Coroutine Principle: each utility owns its own dispatcher switch via an injected `CoroutineDispatcher`, and `AuthService` becomes a pure domain orchestrator with zero `withContext` blocks and no knowledge of thread-execution details. `Database` defaults to `Dispatchers.IO` (blocking JDBC); `Argon2Hasher` defaults to `Dispatchers.Crypto` (CPU-bound Argon2, dedicated pool). Dispatcher injection also enables deterministic unit testing via `TestDispatcher`.

## Detailed Design

### Dispatcher Semantics Change
This refactor changes the dispatcher on which password hashing runs. Currently `AuthService.register` executes `argon2Hasher.hash` inside `withContext(Dispatchers.IO)`, while `login` executes `argon2Hasher.verify` inside `withContext(Dispatchers.Crypto)`. After this change, all Argon2 work runs on the injected dispatcher, defaulting to `Dispatchers.Crypto`. This corrects the inconsistency and prevents CPU-bound hashing from occupying IO threads.

### Utility Modifications
- **`Argon2Hasher` constructor injection:** Add `dispatcher: CoroutineDispatcher = Dispatchers.Crypto` as a constructor parameter. `hash()` and `verify()` replace their internal `withContext(Dispatchers.IO)` with `withContext(dispatcher)`. The existing `withTimeout(timeoutMs)` and `wipeArray` cleanup logic are preserved.
- **`Database` constructor injection:** Add `dispatcher: CoroutineDispatcher = Dispatchers.IO` as a constructor parameter. `createRawConnection()`, `close()`, and the Hikari init block are unchanged.
- **`Database.withConnection` becomes suspend:** Signature changes from `fun <T> withConnection(block: (SqlSession) -> T): T` to `suspend fun <T> withConnection(block: (SqlSession) -> T): T`, with the existing transaction body wrapped in `withContext(dispatcher)`. The `block` parameter remains non-suspending; JDBC calls inside it run on `dispatcher`.
- **`db` module dependency:** `db/build.gradle.kts` currently declares no coroutines dependency. Add `implementation(libs.kotlinx.coroutines.core)` (alias already defined in `gradle/libs.versions.toml` as `kotlinx-coroutines-core`, version `1.10.1`) so `withContext`/`CoroutineDispatcher` resolve.
- **`CryptoDispatcher` note:** The `Dispatchers.Crypto` extension getter allocates a new `asCoroutineDispatcher()` wrapper over a shared, process-wide `cryptoThreadPool` on every access. Because the default argument is evaluated once per `Argon2Hasher` construction, this is acceptable (the underlying thread pool is shared). No change to `CryptoDispatcher.kt` is required.

### AuthService Modifications
`AuthService` removes every `withContext` block and the now-unused `kotlinx.coroutines.Dispatchers` / `withContext` / `ed.unicoach.util.Crypto` imports. Specific removal sites:
- `register`: `withContext(Dispatchers.IO) { argon2Hasher.hash(...) }` and the outer `withContext(Dispatchers.IO) { database.withConnection { ... } }`.
- `getCurrentUser`: `withContext(Dispatchers.IO) { database.withConnection { ... } }`.
- `logout`: `withContext(Dispatchers.IO) { database.withConnection { ... } }`.
- `login`: `withContext(Dispatchers.IO)` around the user lookup, `withContext(Dispatchers.Crypto)` around `argon2Hasher.verify`, and `withContext(Dispatchers.IO)` around session creation.

After removal, `AuthService` calls `database.withConnection { ... }` and `argon2Hasher.hash/verify(...)` directly; the utilities perform the dispatcher switch. The surrounding `try/catch` and `Result` mapping are preserved.

### Caller Cascade (Database.withConnection → suspend)
Making `withConnection` suspend propagates the suspend requirement to its callers:
- **`QueueWorker`** (`workerLoop`, `listenLoop`, `stuckJobReaperLoop`, `completedJobReaperLoop`): already `suspend` functions launched within a `CoroutineScope`. No signature change required; they keep calling `withConnection` directly.
- **`SessionExpiryHandler.execute`**: already `suspend`. No signature change required.
- **`QueueService.enqueue`**: currently non-suspend; becomes `suspend fun enqueue(...)`. Its sole production caller is `SessionExpiryPlugin`, which already invokes `enqueue` inside `call.application.launch(Dispatchers.IO) { ... }`; therefore `SessionExpiryPlugin.kt` requires no source change and is intentionally excluded from Files Modified.
- **`SessionCleanupJob.execute`**: currently non-suspend; becomes `suspend fun execute()`. It has no production caller (constructed only in `SessionCleanupTest`); the test is the only consumer to update.

### Error Handling
No error-handling semantics change. `withContext` does not alter exception propagation: exceptions thrown inside `withConnection`'s `block` still propagate after `rollback()`, and `AuthService`'s existing `try/catch (e: Exception) -> Result.failure(e)` wrappers remain. `withTimeout` in `Argon2Hasher` continues to raise `TimeoutCancellationException` on the injected dispatcher.

### Dependencies
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` (existing, `1.10.1`) — newly added to the `db` module classpath for `withContext`/`CoroutineDispatcher`.
- `org.jetbrains.kotlinx:kotlinx-coroutines-test` (existing, `1.10.1`) — used by tests for `runTest` and `TestDispatcher`.

## Tests

### Argon2HasherTest (`common`)
- `hash() executes on the injected dispatcher`: inject a `StandardTestDispatcher`; assert the dispatcher ran the coroutine (e.g. via `runTest` scheduler advancement) and a non-blank hash is returned.
- `verify() executes on the injected dispatcher`: inject a `StandardTestDispatcher`; assert `verify(hash, password)` returns `true` for a matching password and `false` for a mismatch, executed on the injected dispatcher.
- `hash() default dispatcher is Dispatchers.Crypto`: construct without a dispatcher argument; assert hashing still succeeds (smoke test of the default).

### AuthServiceTest (`service`)
- All existing register/login/logout/getCurrentUser test bodies migrate to `runTest`.
- `register hashes via injected hasher dispatcher and persists user`: inject a `Database` with a `TestDispatcher` and an `Argon2Hasher` with a `TestDispatcher`; assert `RegisterOutcome.Success`.
- `register duplicate email returns DuplicateEmail`: unchanged assertions under `runTest`.
- `login success verifies password and mints token`: assert `LoginResult.Success`.
- `login password mismatch returns PasswordMismatch`.
- `getCurrentUser returns user for valid token` / `returns null for missing session`.
- `logout revokes token` / `logout on missing token returns success`.

### SessionCleanupTest (`service`)
- `execute purges zombie sessions`: migrate to `runTest`; assert `expireZombieSessions` is invoked through the suspend `withConnection`.

### QueueServiceTest (`queue`)
`enqueue` becomes suspend; each of the four existing `@Test` bodies, which currently call `service.enqueue(...)` at top level, moves into `runTest`:
- `enqueue creates SCHEDULED job with immediate scheduled_at`.
- `enqueue with delay sets future scheduled_at`.
- `enqueue with custom max_attempts stores value on job`.
- `enqueue with null max_attempts stores NULL`.

### QueueWorkerTest (`queue`)
- Existing tests that drive `withConnection` migrate to `runTest`; assert claim/execute/complete and retry/dead-letter transitions are unchanged.

### JobsDaoTest (`queue`)
- Any test invoking `Database.withConnection` migrates to `runTest`; DAO assertions unchanged.

### SessionExpiryHandlerTest (`net`)
- Existing tests migrate to `runTest`; assert `extendIfApproaching` outcomes (`Success`, `RetriableFailure`, `PermanentFailure`) unchanged.

### SessionExpiryPluginTest (`rest-server`)
The breaking change here is the suspend `withConnection`, not `enqueue` (the plugin enqueues inside its own coroutine). This test calls `withConnection` from a JUnit5 `@BeforeEach resetDatabase()` and a non-suspend `countJobs()` helper, neither of which can be wrapped in `runTest`:
- Wrap the `withConnection` call in `resetDatabase()` in `runBlocking { }`.
- Wrap the `withConnection` call in `countJobs()` in `runBlocking { }` (helper signature stays non-suspend so it remains callable from inside `testApplication { }`).
- The five `testApplication { }` test bodies are already suspend contexts and need no `runTest` wrapper; their assertions (`countJobs()` results, response status) are unchanged.

### General test-fixture note
Any `@BeforeAll`/`@BeforeEach`/non-suspend helper across the listed test classes that invokes the now-suspend `Database.withConnection` must wrap the call in `runBlocking { }`. JUnit5 lifecycle methods are not suspend and cannot use `runTest`. Per-test bodies that directly invoke suspend functions move into `runTest`.

## Implementation Plan
1. Add `implementation(libs.kotlinx.coroutines.core)` to `db/build.gradle.kts`. Verify with `./gradlew :db:compileKotlin`.
2. Update `Argon2Hasher` to accept `dispatcher: CoroutineDispatcher = Dispatchers.Crypto` and replace `withContext(Dispatchers.IO)` with `withContext(dispatcher)` in `hash` and `verify`. Verify with `./gradlew :common:test --tests "ed.unicoach.util.Argon2HasherTest"`.
3. Update `Database` to accept `dispatcher: CoroutineDispatcher = Dispatchers.IO`, convert `withConnection` to a `suspend` function wrapping its body in `withContext(dispatcher)`. Verify with `./gradlew :db:compileKotlin`.
4. Update `AuthService` to remove all `withContext` blocks (register x2, getCurrentUser, logout, login x3) and the unused imports, calling `database.withConnection` and `argon2Hasher` directly. Verify with `./gradlew :service:test --tests "ed.unicoach.auth.AuthServiceTest"`.
5. Convert `SessionCleanupJob.execute` to `suspend`. Verify with `./gradlew :service:compileKotlin`.
6. Convert `QueueService.enqueue` to `suspend`. Verify with `./gradlew :queue:compileKotlin`. Confirm `QueueWorker` and `SessionExpiryHandler` compile without signature changes (already suspend): `./gradlew :queue:compileKotlin :net:compileKotlin`.
7. Migrate affected test classes (`AuthServiceTest`, `SessionCleanupTest`, `QueueServiceTest`, `QueueWorkerTest`, `JobsDaoTest`, `SessionExpiryHandlerTest`, `SessionExpiryPluginTest`). Move per-test bodies that call suspend functions into `runTest`; wrap `withConnection` calls inside JUnit5 `@BeforeAll`/`@BeforeEach`/non-suspend helpers (e.g. `SessionExpiryPluginTest.resetDatabase` and `countJobs`) in `runBlocking { }`. Inject `TestDispatcher`s where dispatcher behavior is asserted. Verify each module's tests pass.
8. Run the full suite `./gradlew test` to confirm no blocking JDBC call leaks into a non-IO context and all flows pass.

## Files Modified
- `common/src/main/kotlin/ed/unicoach/util/Argon2Hasher.kt`
- `common/src/test/kotlin/ed/unicoach/util/Argon2HasherTest.kt`
- `db/build.gradle.kts`
- `db/src/main/kotlin/ed/unicoach/db/Database.kt`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt`
- `service/src/main/kotlin/ed/unicoach/auth/SessionCleanupJob.kt`
- `service/src/test/kotlin/ed/unicoach/auth/AuthServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/auth/SessionCleanupTest.kt`
- `queue/src/main/kotlin/ed/unicoach/queue/QueueService.kt`
- `queue/src/main/kotlin/ed/unicoach/queue/QueueWorker.kt`
- `queue/src/test/kotlin/ed/unicoach/queue/QueueWorkerTest.kt`
- `queue/src/test/kotlin/ed/unicoach/queue/QueueServiceTest.kt`
- `queue/src/test/kotlin/ed/unicoach/queue/dao/JobsDaoTest.kt`
- `net/src/test/kotlin/ed/unicoach/net/handlers/SessionExpiryHandlerTest.kt`
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/SessionExpiryPluginTest.kt`
