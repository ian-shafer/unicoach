package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CreateResult
import ed.unicoach.db.dao.SessionCreateResult
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.EmailAddress
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.ValidationResult
import ed.unicoach.util.Argon2Hasher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import kotlin.test.assertTrue

class AuthServiceTest {
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
      if (::database.isInitialized) {
        database.close()
      }
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE sessions, users CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val argon2Hasher = Argon2Hasher()
  private val authService by lazy { AuthService(database, argon2Hasher) }

  private fun createTestUser(): ed.unicoach.db.models.User {
    val email = (EmailAddress.create("testme@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("Test User") as ValidationResult.Valid).value
    val pwdHash = (PasswordHash.create("\$argon2id\$v=19\$m=65536,t=3,p=1\$dummysalt\$dummyhash") as ValidationResult.Valid).value

    val result =
      UsersDao.create(
        sqlSession,
        NewUser(email = email, name = name, displayName = null, authMethod = AuthMethod.Password(pwdHash)),
      )
    assertTrue(result is CreateResult.Success)
    return result.user
  }

  private fun createSession(
    userId: ed.unicoach.db.models.UserId?,
    tokenHash: TokenHash,
    expiration: Duration = Duration.ofDays(7),
  ) {
    val result =
      SessionsDao.create(
        sqlSession,
        NewSession(
          userId = userId,
          tokenHash = tokenHash,
          userAgent = "test-agent",
          initialIp = "127.0.0.1",
          metadata = null,
          expiration = expiration,
        ),
      )
    assertTrue(result is SessionCreateResult.Success)
  }

  // Tests domain validation boundary correctly
  @Test
  fun `test validation rejection for weak passwords`() =
    runBlocking {
      // We can pass a dummy db and jwt config because it should fail validation before hitting them
      val rawConfig =
        com.typesafe.config.ConfigFactory.parseString(
          """
          database {
            jdbcUrl = "jdbc:h2:mem:test"
            user = "user"
            maximumPoolSize = 10
            connectionTimeout = 30000
          }
          """.trimIndent(),
        )
      val dummyDb = Database(DatabaseConfig.from(rawConfig).getOrThrow())
      val service = AuthService(dummyDb, argon2Hasher)

      val res1 = service.register("email@test.com", "Name", "short")
      assertTrue(res1 is AuthResult.ValidationFailure)

      val res2 = service.register("email@test.com", "Name", "nouppercasenonumber")
      assertTrue(res2 is AuthResult.ValidationFailure)

      val res3 = service.register("email@test.com", "Name", "UPPERCASENONUMBER")
      assertTrue(res3 is AuthResult.ValidationFailure)

      dummyDb.close()
    }

  @Test
  fun `authenticated session returns user`() =
    runBlocking {
      val user = createTestUser()
      val tokenHash = TokenHash(byteArrayOf(10, 20, 30))
      createSession(userId = user.id, tokenHash = tokenHash)

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result is MeResult.Authenticated)
      assertTrue(result.user.email.value == "testme@example.com")
      assertTrue(result.user.name.value == "Test User")
    }

  @Test
  fun `anonymous session returns unauthenticated`() =
    runBlocking {
      val tokenHash = TokenHash(byteArrayOf(11, 21, 31))
      createSession(userId = null, tokenHash = tokenHash)

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result is MeResult.Unauthenticated)
    }

  @Test
  fun `invalid token returns unauthenticated`() =
    runBlocking {
      val tokenHash = TokenHash(byteArrayOf(99, 98, 97))

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result is MeResult.Unauthenticated)
    }

  @Test
  fun `expired session returns unauthenticated`() =
    runBlocking {
      val user = createTestUser()
      val tokenHash = TokenHash(byteArrayOf(12, 22, 32))
      createSession(userId = user.id, tokenHash = tokenHash, expiration = Duration.ofSeconds(-1))

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result is MeResult.Unauthenticated)
    }

  @Test
  fun `soft-deleted user returns unauthenticated`() =
    runBlocking {
      val user = createTestUser()

      // Soft-delete the user
      val deleteResult = UsersDao.delete(sqlSession, user.id, user.versionId)
      assertTrue(deleteResult is ed.unicoach.db.dao.DeleteResult.Success)

      val tokenHash = TokenHash(byteArrayOf(13, 23, 33))
      createSession(userId = user.id, tokenHash = tokenHash)

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result is MeResult.Unauthenticated)
    }
}
