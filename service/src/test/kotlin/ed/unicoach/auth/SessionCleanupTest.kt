package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
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

class SessionCleanupTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
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

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @Test
  fun `execute synchronously deletes expired and revoked sessions`() {
    val job = SessionCleanupJob(database)

    val hashValid = byteArrayOf(1)
    val hashExpired = byteArrayOf(2)

    SessionsDao.create(
      sqlSession,
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hashValid),
        userAgent = "A",
        initialIp = "1",
        metadata = null,
        expiration = Duration.ofDays(7),
      ),
    )

    SessionsDao.create(
      sqlSession,
      NewSession(
        userId = null,
        tokenHash =
          ed.unicoach.db.models
            .TokenHash(hashExpired),
        userAgent = "B",
        initialIp = "2",
        metadata = null,
        expiration = Duration.ofSeconds(-1),
      ),
    )

    // It should purge the 0 second one
    job.execute()

    val validResult = SessionsDao.findByTokenHash(sqlSession, TokenHash(hashValid))
    assertTrue(validResult is ed.unicoach.db.dao.SessionFindResult.Success)

    val expiredResult = SessionsDao.findByTokenHash(sqlSession, TokenHash(hashExpired))
    assertTrue(expiredResult is ed.unicoach.db.dao.SessionFindResult.NotFound)
  }
}
