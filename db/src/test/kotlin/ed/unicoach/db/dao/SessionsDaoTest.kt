package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.TokenHash
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import kotlin.test.assertTrue

class SessionsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE sessions CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @Test
  fun `verifies mapping, insertion, and retrieval of anonymous sessions`() {
    val hash = byteArrayOf(1, 2, 3)
    val newSession =
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hash),
        userAgent = "test-agent",
        initialIp = "127.0.0.1",
        metadata = "{\"foo\":\"bar\"}",
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult is DaoResult.Success)

    val retrievedResult = SessionsDao.findByTokenHash(session, TokenHash(hash))
    assertTrue(retrievedResult is DaoResult.Success)
    assertTrue((retrievedResult as DaoResult.Success).value.userId == null)
  }

  @Test
  fun `metadata size caps strictly enforce constraints at 2KB`() {
    val hash = byteArrayOf(1, 2, 3, 4)
    val oversizedMetadata = "{\"big\":\"" + "a".repeat(2048) + "\"}"
    val newSession =
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hash),
        userAgent = "test-agent",
        initialIp = "127.0.0.1",
        metadata = oversizedMetadata,
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult is DaoResult.PermanentError)
  }

  @Test
  fun `expiry extension actively shifts expiry forwards securely`() {
    val hash = byteArrayOf(4, 5, 6)
    val newSession =
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hash),
        userAgent = "agent",
        initialIp = null,
        metadata = null,
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult is DaoResult.Success)

    val created = (createResult as DaoResult.Success).value
    val extendResult = SessionsDao.extendExpiry(session, created.id, created.version)
    assertTrue(extendResult is DaoResult.Success)

    val extended = (extendResult as DaoResult.Success).value
    assertTrue(extended.expiresAt.isAfter(created.expiresAt))
  }

  @Test
  fun `revokeByTokenHash revokes active session`() {
    val hash = byteArrayOf(7, 8, 9)
    val tokenHash = TokenHash(hash)
    val newSession =
      NewSession(
        userId = null,
        tokenHash = tokenHash,
        userAgent = "agent",
        initialIp = null,
        metadata = null,
        expiration = Duration.ofDays(7),
      )

    val createResult = SessionsDao.create(session, newSession)
    assertTrue(createResult is DaoResult.Success)

    val revokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(revokeResult is DaoResult.Success)

    val original = (createResult as DaoResult.Success).value
    val revoked = (revokeResult as DaoResult.Success).value
    assertTrue(revoked.version == original.version + 1)
    
    val findResult = SessionsDao.findByTokenHash(session, tokenHash)
    assertTrue(findResult is DaoResult.PermanentError.NotFound)
  }

  @Test
  fun `revokeByTokenHash returns NotFound for nonexistent token`() {
    val hash = byteArrayOf(10, 11, 12)
    val tokenHash = TokenHash(hash)

    val revokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(revokeResult is DaoResult.PermanentError.NotFound)
  }

  @Test
  fun `revokeByTokenHash returns NotFound for already-revoked session`() {
    val hash = byteArrayOf(13, 14, 15)
    val tokenHash = TokenHash(hash)
    val newSession =
      NewSession(
        userId = null,
        tokenHash = tokenHash,
        userAgent = "agent",
        initialIp = null,
        metadata = null,
        expiration = Duration.ofDays(7),
      )

    SessionsDao.create(session, newSession)
    SessionsDao.revokeByTokenHash(session, tokenHash)

    val secondRevokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(secondRevokeResult is DaoResult.PermanentError.NotFound)
  }
}
