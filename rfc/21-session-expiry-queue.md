# 21 Session Expiry Queue

## Executive Summary

This specification moves session expiry extension from synchronous inline
updates to asynchronous processing via the job queue. A thin Ktor plugin fires
after each response, reads the session cookie, hashes it, and enqueues a
`SESSION_EXTEND_EXPIRY` job. The queue worker handler deserializes the token
hash, looks up the session, checks if the expiry is within the sliding window
(less than 2 days remaining), and calls `SessionsDao.extendExpiry()` if needed.
No application-level deduplication is required — optimistic concurrency control
on the session version prevents double-extension, and the sliding window
naturally closes after the first successful extension. This spec also introduces
the `net` Gradle module as the home for production job handlers, adds
`expiresAt` to the `Session` model, extracts `TokenHash.fromRawToken()` as a
shared utility, and adds the first production `JobType` variant.

## Detailed Design

### Session Model Change

The `Session` data class in `db/src/main/kotlin/ed/unicoach/db/models/Session.kt`
currently omits `expires_at`. Add `expiresAt: Instant` to the model:

```kotlin
data class Session(
    val id: UUID,
    val version: Int,
    val createdAt: Instant,
    val userId: UserId?,
    val metadata: String?,
    val userAgent: String?,
    val initialIp: String?,
    val expiresAt: Instant,
)
```

Update `SessionsDao.mapSession()` to read the column:

```kotlin
val expiresAt = rs.getTimestamp("expires_at").toInstant()
```

And pass it to the `Session` constructor. All existing callers receive the field
automatically since they access `Session` instances returned by DAO methods, not
constructed directly.

### TokenHash.fromRawToken()

The SHA-256 hashing logic currently exists as a private `hashToken()` function
in `AuthRoutes.kt`. Extract it to a companion method on `TokenHash`:

```kotlin
class TokenHash(val value: ByteArray) {
    // ... existing equals/hashCode ...

    companion object {
        fun fromRawToken(token: String): TokenHash {
            val hash = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
            return TokenHash(hash)
        }
    }
}
```

Update `AuthRoutes.kt` to replace the private `hashToken()` function with calls
to `TokenHash.fromRawToken()`.

### JobType Variant

Add the first production variant to the `JobType` enum in
`queue/src/main/kotlin/ed/unicoach/queue/JobType.kt`:

```kotlin
enum class JobType(val value: String) {
    TEST_JOB("TEST_JOB"),
    TEST_JOB_B("TEST_JOB_B"),
    SESSION_EXTEND_EXPIRY("SESSION_EXTEND_EXPIRY"),
    ;
    // ...
}
```

### `net` Module

A new Gradle module acting as the integration layer between the queue
infrastructure and domain logic. Job handlers that bridge `JobHandler` (from
`queue`) with domain DAOs (from `db`) live here.

- **Module**: `net`
- **Package**: `ed.unicoach.net`
- **Dependencies**: `common`, `db`, `queue`

`net/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":db"))
    implementation(project(":queue"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}
```

The `kotlin-serialization` plugin is applied because the handler deserializes a
`@Serializable` payload class from `JsonObject`.

### NetConfig

Located at `net/src/main/kotlin/ed/unicoach/net/NetConfig.kt`. Parses the `net`
HOCON block:

```hocon
# net/src/main/resources/net.conf
net {
    session {
        slidingWindowThreshold = 2 days
    }
}
```

```kotlin
class NetConfig private constructor(
    val sessionSlidingWindowThreshold: java.time.Duration,
) {
    companion object {
        fun from(config: Config): Result<NetConfig> = runCatching {
            val netConfig = config.getConfig("net")
            val threshold = netConfig.getDuration("session.slidingWindowThreshold")
            NetConfig(sessionSlidingWindowThreshold = threshold)
        }
    }
}
```

`queue-worker` MUST add `"net.conf"` to its `AppConfig.load(...)` call and
validate via `NetConfig.from(config).getOrThrow()` to ensure a misconfigured
classpath causes a hard startup failure. `rest-server` does not load `net.conf`
— it only enqueues jobs and does not consume `NetConfig` values.

Note: The sliding window threshold (`2 days`) and the extension duration
(`7 days`, hard-coded in `SessionsDao.extendExpiry()`) are intentionally
decoupled. The threshold controls *when* to trigger an extension; the extension
duration is the session TTL, governed by the DAO.

### SessionExpiryPayload

Located at
`queue/src/main/kotlin/ed/unicoach/queue/SessionExpiryPayload.kt`. This class
lives in the `queue` module (not `net`) so that both `rest-server` (enqueuer)
and `net` (handler) can access it without creating a cross-dependency:

```kotlin
@Serializable
data class SessionExpiryPayload(
    val tokenHash: String,
)
```

The `tokenHash` field is the Base64-encoded SHA-256 hash of the raw session
token. Base64 encoding is used because `ByteArray` does not serialize cleanly
into JSON.

### SessionExpiryHandler

Located at
`net/src/main/kotlin/ed/unicoach/net/handlers/SessionExpiryHandler.kt`.
Implements `JobHandler` from the `queue` module.

```kotlin
class SessionExpiryHandler(
    private val database: Database,
    private val slidingWindowThreshold: java.time.Duration,
) : JobHandler {
    override val jobType = JobType.SESSION_EXTEND_EXPIRY
    override val config = JobTypeConfig(
        concurrency = 1,
        maxAttempts = 3,
        lockDuration = 1.minutes,
        executionTimeout = 30.seconds,
    )

    override suspend fun execute(payload: JsonObject): JobResult {
        val data: SessionExpiryPayload
        val tokenHash: TokenHash
        try {
            data = payload.deserialize<SessionExpiryPayload>()
            val hashBytes = Base64.getDecoder().decode(data.tokenHash)
            tokenHash = TokenHash(hashBytes)
        } catch (e: Exception) {
            return JobResult.PermanentFailure(
                "Malformed payload: ${e.message}"
            )
        }

        return database.withConnection { session ->
            when (val findResult = SessionsDao.findByTokenHash(session, tokenHash)) {
                is SessionFindResult.NotFound -> JobResult.Success // expired/revoked, no-op
                is SessionFindResult.DatabaseFailure ->
                    JobResult.RetriableFailure(
                        findResult.error.exception.message ?: "Database error"
                    )
                is SessionFindResult.Success ->
                    extendIfApproaching(session, findResult.session)
            }
        }
    }

    private fun extendIfApproaching(
        session: SqlSession,
        sess: Session,
    ): JobResult {
        val threshold = Instant.now().plus(slidingWindowThreshold)
        if (sess.expiresAt.isAfter(threshold)) {
            return JobResult.Success // not approaching expiry
        }
        return when (val result =
            SessionsDao.extendExpiry(session, sess.id, sess.version)) {
            is SessionUpdateResult.Success -> JobResult.Success
            is SessionUpdateResult.NotFound ->
                JobResult.Success // version mismatch, already updated
            is SessionUpdateResult.DatabaseFailure ->
                JobResult.RetriableFailure(
                    result.error.exception.message ?: "Database error"
                )
        }
    }
}
```

Key behaviors:

- **Idempotent**: If the session was already extended (version changed), the OCC
  check in `extendExpiry()` returns `NotFound`, and the handler returns
  `JobResult.Success` — no retry.
- **Graceful on missing sessions**: If the session expired, was revoked, or was
  deleted between enqueue and execution, `findByTokenHash()` returns `NotFound`,
  and the handler returns `JobResult.Success` — no retry.
- **Retries on DB failures**: Transient database errors return
  `JobResult.RetriableFailure` to leverage the queue's backoff and retry
  mechanism.
- **No metadata writes**: The handler does not write to the session's `metadata`
  JSONB column. Deduplication relies entirely on OCC versioning and the sliding
  window check.
- **Blocking JDBC is safe here**: The `execute()` function is `suspend` but calls
  `database.withConnection` (blocking JDBC) without a `withContext(Dispatchers.IO)`
  wrapper. This is safe because `QueueWorker` already dispatches handler execution
  on an IO-bound context.

### SessionExpiryPlugin

Located at
`rest-server/src/main/kotlin/ed/unicoach/rest/plugins/SessionExpiryPlugin.kt`.
A Ktor application plugin that fires after each response is sent.

```kotlin
class SessionExpiryPluginConfig {
    lateinit var sessionConfig: SessionConfig
    lateinit var queueService: QueueService
    var ignorePathPrefixes: Set<String> = emptySet()
}

val SessionExpiryPlugin = createApplicationPlugin(
    name = "SessionExpiryPlugin",
    createConfiguration = ::SessionExpiryPluginConfig,
) {
    val cookieName = pluginConfig.sessionConfig.cookieName
    val queueService = pluginConfig.queueService
    val ignorePathPrefixes = pluginConfig.ignorePathPrefixes

    on(ResponseSent) { call ->
        val token = call.request.cookies[cookieName] ?: return@on
        val path = call.request.uri
        if (ignorePathPrefixes.any { path.startsWith(it) }) return@on
        val status = call.response.status()?.value ?: return@on
        if (status !in 200..299) return@on

        // Fire-and-forget on the application scope. On shutdown, Ktor cancels
        // this scope and the coroutine is silently dropped. The next request
        // after restart will re-enqueue.
        call.application.launch(Dispatchers.IO) {
            try {
                val tokenHash = TokenHash.fromRawToken(token)
                val encodedHash = Base64.getEncoder()
                    .encodeToString(tokenHash.value)
                val payload = SessionExpiryPayload(
                    tokenHash = encodedHash
                ).asJson()
                when (val result = queueService.enqueue(
                    JobType.SESSION_EXTEND_EXPIRY,
                    payload,
                )) {
                    is EnqueueResult.Success -> { /* no-op */ }
                    is EnqueueResult.DatabaseFailure ->
                        LoggerFactory.getLogger("SessionExpiryPlugin")
                            .error("Failed to enqueue session expiry job: {}", result.error)
                }
            } catch (e: Exception) {
                // Fire-and-forget. Log and swallow.
                LoggerFactory.getLogger("SessionExpiryPlugin")
                    .error("Failed to enqueue session expiry job", e)
            }
        }
    }
}
```

Key behaviors:

- **Zero DB reads**: The plugin only reads the cookie and enqueues. All session
  lookup and expiry logic lives in the handler.
- **Fire-and-forget**: The coroutine is launched on the application scope with
  `Dispatchers.IO`. If the enqueue fails or the server shuts down, the job is
  silently dropped. The next request re-enqueues.
- **No-op without cookie**: If no session cookie is present, the hook returns
  immediately.
- **Filtered by path denylist**: Requests whose path matches any prefix in
  `ignorePathPrefixes` are skipped. Only requests with a 2xx response status
  trigger enqueue. Health probes, static assets, and error responses are
  skipped to avoid unbounded queue write amplification. The denylist is
  configured in `rest-server.conf` under the `sessionExpiry` block.
- **Error-level logging**: Enqueue failures are logged at `error` level since
  they indicate a database issue worth investigating.

### Wiring

#### rest-server

`rest-server/build.gradle.kts` MUST add `alias(libs.plugins.kotlin.serialization)`
to its `plugins` block. The `SessionExpiryPlugin` calls
`SessionExpiryPayload(...).asJson()`, where `asJson()` is an `inline reified`
function that requires the kotlinx-serialization compiler plugin at the call
site to generate the serializer for `SessionExpiryPayload`.

`rest-server/src/main/resources/rest-server.conf` MUST add a `sessionExpiry`
block with the `ignorePathPrefixes` list:

```hocon
sessionExpiry {
    ignorePathPrefixes = ["/health"]
}
```

In `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`, inside the
`embeddedServer` lambda (not in `appModule()`), create a `QueueService`, parse
the `ignorePathPrefixes` from config, and install the plugin:

```kotlin
val queueService = QueueService(database)

val ignorePathPrefixes = config
    .getStringList("sessionExpiry.ignorePathPrefixes")
    .toSet()

val server = embeddedServer(Netty, port = portInt, host = hostStr) {
    // ... existing code ...
    appModule(database, sessionConfig)

    install(SessionExpiryPlugin) {
        this.sessionConfig = sessionConfig
        this.queueService = queueService
        this.ignorePathPrefixes = ignorePathPrefixes
    }
}
```

Installing the plugin outside `appModule()` avoids changing the `appModule()`
signature and prevents the plugin from being active in existing integration
tests that call `appModule()` directly.

#### queue-worker

In `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt`, add the
handler to the registration list:

```kotlin
val netConfig = NetConfig.from(config).getOrThrow()

val handlers = listOf<JobHandler>(
    SessionExpiryHandler(database, netConfig.sessionSlidingWindowThreshold),
)
```

Add `"net.conf"` to the `AppConfig.load(...)` call.

Add `implementation(project(":net"))` to `queue-worker/build.gradle.kts`.

### UoW Compatibility

This design is forward-compatible with a future Unit of Work (UoW) pattern for
session mutations. The plugin fires `on(ResponseSent)`, which executes after any
UoW finalizer has committed to the database. The handler reads fresh session
state from the database and uses OCC versioning, so it tolerates version changes
made by concurrent UoW commits.

### Error Handling and Edge Cases

- **Server shutdown during enqueue**: The fire-and-forget coroutine is cancelled.
  No job is created. The next request re-enqueues.
- **Handler runs after session deletion**: `findByTokenHash()` returns
  `NotFound`. Handler returns `Success`. No retry.
- **Concurrent duplicate jobs**: Multiple requests enqueue for the same session.
  The first handler extends the expiry and bumps the version. Subsequent
  handlers see a version mismatch (via OCC in `extendExpiry`) or see `expiresAt`
  outside the sliding window. Both cases return `Success`. No retry.
- **Session already extended by another mechanism**: OCC version mismatch.
  Handler returns `Success`. The sliding window check on the next request
  confirms no further extension is needed.

### Dependencies

- `net` module: `common`, `db`, `queue`
- `queue-worker`: adds `net`
- `rest-server`: no new module dependencies (already depends on `queue`, which
  now contains `SessionExpiryPayload`)
- No new external libraries

## Tests

### Handler Integration Tests (against real Postgres)

`net/src/test/kotlin/ed/unicoach/net/handlers/SessionExpiryHandlerTest.kt`:

Tests use a real PostgreSQL database. `@BeforeAll` loads config via
`AppConfig.load("common.conf", "db.conf")`, creates `DatabaseConfig.from(config)`,
and instantiates `Database(dbConfig)`. `@AfterAll` calls `database.close()` to
shut down the HikariCP pool. Each test truncates `sessions CASCADE` and
`jobs CASCADE` in `@BeforeEach` via a raw `database.withConnection` call.

- **`handler extends session expiry when within sliding window`**: Create a
  session with `expiration = Duration.ofDays(1)` (expires in 1 day, within the
  2-day sliding window). Capture the original `expiresAt`. Encode the token
  hash as Base64 and build the payload `JsonObject`. Call
  `handler.execute(payload)`. Assert `JobResult.Success`. Read the session
  back via `findByTokenHash()` and assert `expiresAt` is after the original
  value. Do NOT compare against `Instant.now()` — the application and database
  clocks may differ.

- **`handler skips extension when expiry is outside sliding window`**: Create a
  session with `expiration = Duration.ofDays(7)` (expires in 7 days, well
  outside the 2-day window). Call `handler.execute(payload)`. Assert
  `JobResult.Success`. Read the session back and assert `expiresAt` is
  unchanged (still approximately 7 days from creation, not re-extended).

- **`handler returns Success when session is not found`**: Build a payload with
  a token hash that does not match any session. Call `handler.execute(payload)`.
  Assert `JobResult.Success`.

- **`handler returns Success on version mismatch`**: Create a session within the
  sliding window. Execute the handler once to extend the session (bumps
  version). Execute the handler a second time. Assert `JobResult.Success` on
  both calls. Read the session back and assert `version == 2` (extended only
  once). The second execution sees `expiresAt` outside the sliding window
  and returns `Success` as a no-op, proving idempotency.

- **`handler returns PermanentFailure on malformed payload`**: Build a
  `JsonObject` with missing or mistyped fields (e.g., `{"wrong": 123}`). Call
  `handler.execute(payload)`. Assert `JobResult.PermanentFailure`.

- **`handler returns PermanentFailure on invalid Base64`**: Build a
  `JsonObject` with `{"tokenHash": "%%%not-base64%%"}`. Call
  `handler.execute(payload)`. Assert `JobResult.PermanentFailure`.

### Plugin Unit Tests

`rest-server/src/test/kotlin/ed/unicoach/rest/plugins/SessionExpiryPluginTest.kt`:

Tests use Ktor's `testApplication` with a mock `QueueService`. The plugin is
installed with a test `SessionConfig` (known cookie name). A minimal route
(e.g., `GET /api/v1/ping` returning 200) is registered for triggering the hook.

- **`plugin does not enqueue when no session cookie is present`**: Send a
  request to `/api/v1/ping` with no cookies. Assert `queueService.enqueue()` is
  never called.

- **`plugin does not enqueue for non-API paths`**: Send a request to
  `/health` with a valid session cookie. Assert `queueService.enqueue()` is
  never called.

- **`plugin does not enqueue for non-2xx responses`**: Register a route that
  returns 401. Send a request to that route with a valid session cookie. Assert
  `queueService.enqueue()` is never called.

- **`plugin enqueues for valid API request with session cookie`**: Send a
  request to `/api/v1/ping` with a valid session cookie. Assert
  `queueService.enqueue()` is called once with `JobType.SESSION_EXTEND_EXPIRY`
  and a payload containing the Base64-encoded SHA-256 hash of the cookie value.

- **`plugin logs error and does not crash on enqueue failure`**: Configure the
  mock `queueService.enqueue()` to return `EnqueueResult.DatabaseFailure`.
  Send a request to `/api/v1/ping` with a valid session cookie. Assert the
  response is still 200 (the plugin does not interfere with response delivery).

### Existing Test Updates

- **`SessionsDaoTest`**: The existing
  `expiry extension actively shifts expiry forwards securely` test MUST be
  updated to assert the new `expiresAt` field on the returned `Session` object
  (verify `extended.expiresAt` is after `original.expiresAt`). Do NOT compare
  against `Instant.now()` — the application and database clocks may differ.
  The other two tests require no changes — the new `expiresAt` field is
  populated automatically by `mapSession()`.

## Implementation Plan

1. **Add `expiresAt` to `Session` model**: Update
   `db/src/main/kotlin/ed/unicoach/db/models/Session.kt` to add the field.
   Update `SessionsDao.mapSession()` to read `expires_at` from the `ResultSet`.
   Update `SessionsDaoTest` to assert `expiresAt` on the expiry extension test.
   Verify: `./bin/test ed.unicoach.db.dao.SessionsDaoTest`.

2. **Extract `TokenHash.fromRawToken()`**: Add the companion method to
   `db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt`. Update
   `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` to
   replace the private `hashToken()` function with `TokenHash.fromRawToken()`.
   Verify: `./bin/test ed.unicoach.rest`.

3. **Add `SESSION_EXTEND_EXPIRY` to `JobType`**: Update
   `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt`. Verify:
   `./gradlew :queue:build`.

4. **Create `net` module skeleton**: Create `net/build.gradle.kts`. Add
   `include("net")` to `settings.gradle.kts`. Create
   `net/src/main/resources/net.conf` with the `net.session` configuration block.
   Create `NetConfig.kt`. Create the package directory structure. Verify:
   `./gradlew :net:build`.

5. **Implement `SessionExpiryPayload` and `SessionExpiryHandler`**: Create
   `SessionExpiryPayload` in `queue/src/main/kotlin/ed/unicoach/queue/`. Create
   `SessionExpiryHandler` in `net/src/main/kotlin/ed/unicoach/net/handlers/`.
   Verify: `./gradlew :net:build` compiles.

6. **Implement handler tests**: Create `SessionExpiryHandlerTest.kt` with all
   six test cases. Verify: `./bin/test ed.unicoach.net.handlers`.

7. **Implement `SessionExpiryPlugin`**: Add
    `alias(libs.plugins.kotlin.serialization)` to
    `rest-server/build.gradle.kts`. Create the Ktor plugin in
    `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/SessionExpiryPlugin.kt`.
    Verify: `./gradlew :rest-server:build` compiles.

8. **Wire plugin in rest-server**: Update
    `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` to create
    `QueueService` and install the plugin in the `embeddedServer` lambda. Verify:
    `./gradlew :rest-server:build` compiles.

9. **Implement plugin tests**: Create `SessionExpiryPluginTest.kt` with all
   five test cases. Verify:
   `./bin/test ed.unicoach.rest.plugins.SessionExpiryPluginTest`.

10. **Wire handler in queue-worker**: Add
    `implementation(project(":net"))` to `queue-worker/build.gradle.kts`. Update
    `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` to add
    `"net.conf"` to `AppConfig.load(...)`, parse `NetConfig`, and register
    `SessionExpiryHandler` with the configured threshold. Verify:
    `./gradlew :queue-worker:build` compiles.

## Files Modified

- `db/src/main/kotlin/ed/unicoach/db/models/Session.kt` [MODIFY]
- `db/src/main/kotlin/ed/unicoach/db/dao/SessionsDao.kt` [MODIFY]
- `db/src/main/kotlin/ed/unicoach/db/models/TokenHash.kt` [MODIFY]
- `db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt` [MODIFY]
- `queue/src/main/kotlin/ed/unicoach/queue/JobType.kt` [MODIFY]
- `settings.gradle.kts` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/AuthRoutes.kt` [MODIFY]
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` [MODIFY]
- `rest-server/src/main/resources/rest-server.conf` [MODIFY]
- `rest-server/build.gradle.kts` [MODIFY]
- `queue-worker/build.gradle.kts` [MODIFY]
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` [MODIFY]
- `net/build.gradle.kts` [NEW]
- `net/src/main/resources/net.conf` [NEW]
- `net/src/main/kotlin/ed/unicoach/net/NetConfig.kt` [NEW]
- `queue/src/main/kotlin/ed/unicoach/queue/SessionExpiryPayload.kt` [NEW]
- `net/src/main/kotlin/ed/unicoach/net/handlers/SessionExpiryHandler.kt` [NEW]
- `net/src/test/kotlin/ed/unicoach/net/handlers/SessionExpiryHandlerTest.kt` [NEW]
- `rest-server/src/main/kotlin/ed/unicoach/rest/plugins/SessionExpiryPlugin.kt` [NEW]
- `rest-server/src/test/kotlin/ed/unicoach/rest/plugins/SessionExpiryPluginTest.kt` [NEW]
