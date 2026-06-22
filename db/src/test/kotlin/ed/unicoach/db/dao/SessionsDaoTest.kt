package ed.unicoach.db.dao

import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.SessionId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
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

  private fun createUser(): UserId {
    val rawId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$rawId', 'sess-$rawId@test.com', 'Sess User', 'ahash')",
      )
    }
    return UserId(rawId)
  }

  private fun createSession(
    userId: UserId?,
    hash: ByteArray,
  ) = SessionsDao
    .create(
      session,
      NewSession(
        userId = userId,
        tokenHash = TokenHash(hash),
        userAgent = "agent",
        initialIp = null,
        metadata = null,
        expiration = Duration.ofDays(7),
        loginMethod = if (userId != null) LoginMethod.PASSWORD else null,
      ),
    ).getOrThrow()

  @Test
  fun `findById returns the session by id and NotFound for unknown id`() {
    val user = createUser()
    val created = createSession(user, byteArrayOf(20, 21, 22))

    val found = SessionsDao.findById(session, created.id)
    assertTrue(found.isSuccess)
    assertEquals(created.id, found.getOrThrow().id)

    val missing = SessionsDao.findById(session, SessionId(UUID.randomUUID()))
    assertTrue(missing.isFailure && missing.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `listByUser filters to the owner and list pages newest-first`() {
    val owner = createUser()
    val other = createUser()

    val s1 = createSession(owner, byteArrayOf(30, 1))
    Thread.sleep(5)
    val s2 = createSession(owner, byteArrayOf(30, 2))
    Thread.sleep(5)
    createSession(other, byteArrayOf(30, 3))

    val byOwner = SessionsDao.listByUser(session, owner, limit = 50, offset = 0).getOrThrow()
    assertEquals(setOf(s1.id, s2.id), byOwner.map { it.id }.toSet())
    // created_at DESC: s2 (newer) before s1.
    assertEquals(s2.id, byOwner.first().id)

    val all = SessionsDao.list(session, limit = 50, offset = 0).getOrThrow()
    assertTrue(all.size >= 3)
    // Paging advances the cursor.
    val page0 = SessionsDao.list(session, limit = 1, offset = 0).getOrThrow()
    val page1 = SessionsDao.list(session, limit = 1, offset = 1).getOrThrow()
    assertTrue(page0.first().id != page1.first().id)
  }

  @Test
  fun `destroy physically removes the row`() {
    val user = createUser()
    val created = createSession(user, byteArrayOf(40, 41))

    val deleteResult = SessionsDao.destroy(session, created.id)
    assertTrue(deleteResult.isSuccess)

    val refetch = SessionsDao.findById(session, created.id)
    assertTrue(refetch.isFailure && refetch.exceptionOrNull() is NotFoundException)

    val deleteMissing = SessionsDao.destroy(session, SessionId(UUID.randomUUID()))
    assertTrue(deleteMissing.isFailure && deleteMissing.exceptionOrNull() is NotFoundException)
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
    assertTrue(createResult.isSuccess)

    val retrievedResult = SessionsDao.findByTokenHash(session, TokenHash(hash))
    assertTrue(retrievedResult.isSuccess)
    assertTrue(retrievedResult.getOrNull()!!.userId == null)
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
    assertTrue(createResult.isFailure)
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
    assertTrue(createResult.isSuccess)

    val created = createResult.getOrNull()!!
    val extendResult = SessionsDao.extendExpiry(session, created.id, created.version)
    assertTrue(extendResult.isSuccess)

    val extended = extendResult.getOrNull()!!
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
    assertTrue(createResult.isSuccess)

    val revokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(revokeResult.isSuccess)

    val original = createResult.getOrNull()!!
    val revoked = revokeResult.getOrNull()!!
    assertTrue(revoked.version == original.version + 1)

    val findResult = SessionsDao.findByTokenHash(session, tokenHash)
    assertTrue(findResult.isFailure && findResult.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `revokeByTokenHash returns NotFound for nonexistent token`() {
    val hash = byteArrayOf(10, 11, 12)
    val tokenHash = TokenHash(hash)

    val revokeResult = SessionsDao.revokeByTokenHash(session, tokenHash)
    assertTrue(revokeResult.isFailure && revokeResult.exceptionOrNull() is NotFoundException)
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
    assertTrue(secondRevokeResult.isFailure && secondRevokeResult.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `create round-trips login_method for password and google`() {
    val user = createUser()

    val passwordSession =
      SessionsDao
        .create(
          session,
          NewSession(
            userId = user,
            tokenHash = TokenHash(byteArrayOf(50, 1)),
            userAgent = null,
            initialIp = null,
            metadata = null,
            expiration = Duration.ofDays(7),
            loginMethod = LoginMethod.PASSWORD,
          ),
        ).getOrThrow()
    assertEquals(LoginMethod.PASSWORD, passwordSession.loginMethod)

    val googleSession =
      SessionsDao
        .create(
          session,
          NewSession(
            userId = user,
            tokenHash = TokenHash(byteArrayOf(50, 2)),
            userAgent = null,
            initialIp = null,
            metadata = null,
            expiration = Duration.ofDays(7),
            loginMethod = LoginMethod.GOOGLE,
          ),
        ).getOrThrow()
    assertEquals(LoginMethod.GOOGLE, googleSession.loginMethod)
  }

  @Test
  fun `create with a user but no login_method is rejected by the presence check`() {
    val user = createUser()
    val result =
      SessionsDao.create(
        session,
        NewSession(
          userId = user,
          tokenHash = TokenHash(byteArrayOf(51, 1)),
          userAgent = null,
          initialIp = null,
          metadata = null,
          expiration = Duration.ofDays(7),
          loginMethod = null,
        ),
      )
    assertTrue(result.isFailure, "Authenticated session without a login_method must be rejected")
  }

  @Test
  fun `create with no user but a login_method is rejected by the presence check`() {
    val result =
      SessionsDao.create(
        session,
        NewSession(
          userId = null,
          tokenHash = TokenHash(byteArrayOf(52, 1)),
          userAgent = null,
          initialIp = null,
          metadata = null,
          expiration = Duration.ofDays(7),
          loginMethod = LoginMethod.PASSWORD,
        ),
      )
    assertTrue(result.isFailure, "Anonymous session carrying a login_method must be rejected")
  }

  @Test
  fun `create anonymous session with null login_method succeeds`() {
    val created = createSession(null, byteArrayOf(53, 1))
    assertTrue(created.userId == null)
    assertEquals(null, created.loginMethod)
  }

  @Test
  fun `remintToken sets login_method on the formerly anonymous row`() {
    val anon = createSession(null, byteArrayOf(54, 1))
    val user = createUser()

    val reminted =
      SessionsDao
        .remintToken(
          session = session,
          id = anon.id,
          currentVersion = anon.version,
          newUserId = user,
          newTokenHash = byteArrayOf(54, 2),
          newExpirationSeconds = Duration.ofDays(7).seconds,
          newLoginMethod = LoginMethod.GOOGLE,
        ).getOrThrow()

    assertEquals(user, reminted.userId)
    assertEquals(LoginMethod.GOOGLE, reminted.loginMethod)
  }
}
