package ed.unicoach.net.handlers

import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SessionCreateResult
import ed.unicoach.db.dao.SessionFindResult
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.TokenHash
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.SessionExpiryPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionExpiryHandlerTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        DatabaseConfig
          .from(config)
          .getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) {
        database.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    database.withConnection { session ->
      session.prepareStatement("TRUNCATE TABLE sessions CASCADE").use { it.execute() }
      session.prepareStatement("TRUNCATE TABLE jobs CASCADE").use { it.execute() }
    }
  }

  private val handler =
    SessionExpiryHandler(
      database = database,
      slidingWindowThreshold = Duration.ofDays(2),
    )

  private fun createSessionWithExpiration(
    tokenBytes: ByteArray,
    expiration: Duration,
  ): SessionCreateResult.Success {
    val result =
      database.withConnection { session ->
        SessionsDao.create(
          session,
          NewSession(
            userId = null,
            tokenHash = TokenHash(tokenBytes),
            userAgent = "test-agent",
            initialIp = "127.0.0.1",
            metadata = null,
            expiration = expiration,
          ),
        )
      }
    assertTrue(result is SessionCreateResult.Success)
    return result
  }

  private fun buildPayload(tokenBytes: ByteArray): JsonObject {
    val encoded = Base64.getEncoder().encodeToString(tokenBytes)
    return SessionExpiryPayload(tokenHash = encoded).asJson()
  }

  @Test
  fun `handler extends session expiry when within sliding window`() {
    val tokenBytes = byteArrayOf(10, 20, 30)
    val created = createSessionWithExpiration(tokenBytes, Duration.ofDays(1))
    val originalExpiresAt = created.session.expiresAt
    val payload = buildPayload(tokenBytes)

    val result = runBlocking { handler.execute(payload) }
    assertEquals(JobResult.Success, result)

    val found =
      database.withConnection { session ->
        SessionsDao.findByTokenHash(session, TokenHash(tokenBytes))
      }
    assertTrue(found is SessionFindResult.Success)
    assertTrue(found.session.expiresAt.isAfter(originalExpiresAt))
  }

  @Test
  fun `handler skips extension when expiry is outside sliding window`() {
    val tokenBytes = byteArrayOf(11, 21, 31)
    val created = createSessionWithExpiration(tokenBytes, Duration.ofDays(7))
    val originalExpiresAt = created.session.expiresAt
    val payload = buildPayload(tokenBytes)

    val result = runBlocking { handler.execute(payload) }
    assertEquals(JobResult.Success, result)

    val found =
      database.withConnection { session ->
        SessionsDao.findByTokenHash(session, TokenHash(tokenBytes))
      }
    assertTrue(found is SessionFindResult.Success)

    val tolerance = Duration.ofSeconds(1)
    assertTrue(found.session.expiresAt.isAfter(originalExpiresAt.minus(tolerance)))
    assertTrue(found.session.expiresAt.isBefore(originalExpiresAt.plus(tolerance)))
  }

  @Test
  fun `handler returns Success when session is not found`() {
    val tokenBytes = byteArrayOf(99, 98, 97)
    val payload = buildPayload(tokenBytes)

    val result = runBlocking { handler.execute(payload) }
    assertEquals(JobResult.Success, result)
  }

  @Test
  fun `handler returns Success on version mismatch`() {
    val tokenBytes = byteArrayOf(12, 22, 32)
    createSessionWithExpiration(tokenBytes, Duration.ofDays(1))

    val payload = buildPayload(tokenBytes)

    // First execution successfully extends the session
    val firstResult = runBlocking { handler.execute(payload) }
    assertEquals(JobResult.Success, firstResult)

    // Second execution sees the session is now outside the sliding window
    // (expiresAt is ~7 days away after extension) and returns Success as a no-op
    val secondResult = runBlocking { handler.execute(payload) }
    assertEquals(JobResult.Success, secondResult)

    // Verify the session was extended only once (version bumped once from 1 to 2)
    val found =
      database.withConnection { session ->
        SessionsDao.findByTokenHash(session, TokenHash(tokenBytes))
      }
    assertTrue(found is SessionFindResult.Success)
    assertEquals(2, found.session.version)
  }

  @Test
  fun `handler returns PermanentFailure on malformed payload`() {
    val payload = JsonObject(mapOf("wrong" to JsonPrimitive(123)))
    val result = runBlocking { handler.execute(payload) }
    assertTrue(result is JobResult.PermanentFailure)
  }

  @Test
  fun `handler returns PermanentFailure on invalid Base64`() {
    val payload = JsonObject(mapOf("tokenHash" to JsonPrimitive("%%%not-base64%%")))
    val result = runBlocking { handler.execute(payload) }
    assertTrue(result is JobResult.PermanentFailure)
  }
}
