package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.util.Argon2Hasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    private lateinit var appConfig: com.typesafe.config.Config

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf", "email.conf")
          .getOrThrow()
      appConfig = config
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

  private fun emailVerificationService(): EmailVerificationService {
    val emailConfig =
      ed.unicoach.email.EmailConfig
        .from(appConfig)
        .getOrThrow()
    val provider =
      ed.unicoach.email.EmailProviderFactory
        .fromConfig(emailConfig)
        .getOrThrow()
    val emailService = ed.unicoach.email.EmailService(database, provider, emailConfig)
    val evConfig = EmailVerificationConfig.from(appConfig).getOrThrow()
    return EmailVerificationService(database, emailService, ed.unicoach.util.TokenGenerator(), evConfig)
  }

  private fun newAuthService() =
    AuthService(database, argon2Hasher, ed.unicoach.util.TokenGenerator(), emailVerificationService(), StubGoogleTokenVerifier())

  private class RejectingProvider : ed.unicoach.email.EmailProvider {
    override val id: String = "rejecting"

    override suspend fun send(email: ed.unicoach.email.OutboundEmail): ed.unicoach.email.ProviderResult =
      ed.unicoach.email.ProviderResult
        .Rejected("test rejection")
  }

  private fun authServiceWithRejectingEmail(): AuthService {
    val emailConfig =
      ed.unicoach.email.EmailConfig
        .from(appConfig)
        .getOrThrow()
    val emailService = ed.unicoach.email.EmailService(database, RejectingProvider(), emailConfig)
    val evConfig = EmailVerificationConfig.from(appConfig).getOrThrow()
    val evService = EmailVerificationService(database, emailService, ed.unicoach.util.TokenGenerator(), evConfig)
    return AuthService(database, argon2Hasher, ed.unicoach.util.TokenGenerator(), evService, StubGoogleTokenVerifier())
  }

  private fun countRows(sql: String): Int {
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  private val authService by lazy { newAuthService() }

  private fun createTestUser(): ed.unicoach.db.models.User {
    val email = (EmailAddress.create("testme@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("Test User") as ValidationResult.Valid).value
    val pwdHash = (PasswordHash.create("\$argon2id\$v=19\$m=65536,t=3,p=1\$dummysalt\$dummyhash") as ValidationResult.Valid).value

    val result =
      UsersDao.create(
        sqlSession,
        NewUser(email = email, name = name, displayName = null, passwordHash = pwdHash),
      )
    assertTrue(result.isSuccess)
    return result.getOrNull()!!
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
          loginMethod = if (userId != null) LoginMethod.PASSWORD else null,
        ),
      )
    assertTrue(result.isSuccess)
  }

  // Tests domain validation boundary correctly
  @Test
  fun `test validation rejection for weak passwords`() =
    runTest {
      // Weak passwords are rejected before any persistence occurs; the registrations
      // run against the same Postgres harness as the other tests in this class.
      val service = newAuthService()

      val res1 = service.register("email@test.com", "Name", "short", null, 86400L, null, null)
      assertTrue(res1.isSuccess && res1.getOrNull() is RegisterResult.ValidationFailure)

      val res2 = service.register("email@test.com", "Name", "nouppercasenonumber", null, 86400L, null, null)
      assertTrue(res2.isSuccess && res2.getOrNull() is RegisterResult.ValidationFailure)

      val res3 = service.register("email@test.com", "Name", "UPPERCASENONUMBER", null, 86400L, null, null)
      assertTrue(res3.isSuccess && res3.getOrNull() is RegisterResult.ValidationFailure)
    }

  @Test
  fun `authenticated session returns user`() =
    runTest {
      val user = createTestUser()
      val tokenHash = TokenHash(byteArrayOf(10, 20, 30))
      createSession(userId = user.id, tokenHash = tokenHash)

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result.isSuccess)
      val authUser = result.getOrNull()!!
      assertTrue(authUser.email.value == "testme@example.com")
      assertTrue(authUser.name.value == "Test User")
    }

  @Test
  fun `anonymous session returns unauthenticated`() =
    runTest {
      val tokenHash = TokenHash(byteArrayOf(11, 21, 31))
      createSession(userId = null, tokenHash = tokenHash)

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result.isSuccess && result.getOrNull() == null)
    }

  @Test
  fun `invalid token returns unauthenticated`() =
    runTest {
      val tokenHash = TokenHash(byteArrayOf(99, 98, 97))

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result.isSuccess && result.getOrNull() == null)
    }

  @Test
  fun `expired session returns unauthenticated`() =
    runTest {
      val user = createTestUser()
      val tokenHash = TokenHash(byteArrayOf(12, 22, 32))
      createSession(userId = user.id, tokenHash = tokenHash, expiration = Duration.ofSeconds(-1))

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result.isSuccess && result.getOrNull() == null)
    }

  @Test
  fun `soft-deleted user returns unauthenticated`() =
    runTest {
      val user = createTestUser()

      // Soft-delete the user
      val deleteResult = UsersDao.delete(sqlSession, user.id, user.version)
      assertTrue(deleteResult.isSuccess)

      val tokenHash = TokenHash(byteArrayOf(13, 23, 33))
      createSession(userId = user.id, tokenHash = tokenHash)

      val result = authService.getCurrentUser(tokenHash)
      assertTrue(result.isSuccess && result.getOrNull() == null)
    }

  @Test
  fun `logout revokes active session`() =
    runTest {
      val user = createTestUser()
      val tokenHash = TokenHash(byteArrayOf(50, 51, 52))
      createSession(userId = user.id, tokenHash = tokenHash)

      val result = authService.logout(tokenHash)
      assertTrue(result.isSuccess)

      val findResult = SessionsDao.findByTokenHash(sqlSession, tokenHash)
      assertTrue(findResult.isFailure && findResult.exceptionOrNull() is NotFoundException)
    }

  @Test
  fun `logout returns Success for nonexistent token`() =
    runTest {
      val tokenHash = TokenHash(byteArrayOf(60, 61, 62))
      val result = authService.logout(tokenHash)
      assertTrue(result.isSuccess)
    }

  @Test
  fun `logout returns Success for already-revoked session`() =
    runTest {
      val user = createTestUser()
      val tokenHash = TokenHash(byteArrayOf(70, 71, 72))
      createSession(userId = user.id, tokenHash = tokenHash)

      SessionsDao.revokeByTokenHash(sqlSession, tokenHash)

      val result = authService.logout(tokenHash)
      assertTrue(result.isSuccess)
    }

  @Test
  fun `login with valid credentials yields Success`() =
    runTest {
      val email = "login_test@example.com"
      val password = "Password123"
      authService.register(email, "Login Test", password, null, 86400L, null, null)

      val result = authService.login(email, password, null, 86400L, null, null)
      assertTrue(result.isSuccess)
      val loginResult = result.getOrNull()
      assertTrue(loginResult is LoginResult.Success)
      assertTrue(loginResult.user.email.value == email)

      val loginHash = TokenHash.fromRawToken(loginResult.token)
      val loginSession = SessionsDao.findByTokenHash(sqlSession, loginHash).getOrThrow()
      assertTrue(loginSession.loginMethod == LoginMethod.PASSWORD, "login must mint a PASSWORD session")
    }

  @Test
  fun `register creates a PASSWORD session`() =
    runTest {
      val email = "register_method@example.com"
      val regResult = authService.register(email, "Register Method", "Password123", null, 86400L, null, null)
      val success = regResult.getOrNull() as RegisterResult.Success

      val regHash = TokenHash.fromRawToken(success.token)
      val regSession = SessionsDao.findByTokenHash(sqlSession, regHash).getOrThrow()
      assertTrue(regSession.loginMethod == LoginMethod.PASSWORD, "register must mint a PASSWORD session")
    }

  @Test
  fun `login with invalid email yields UserNotFound`() =
    runTest {
      val result = authService.login("nonexistent@example.com", "Password123", null, 86400L, null, null)
      assertTrue(result.isSuccess)
      assertTrue(result.getOrNull() is LoginResult.UserNotFound)
    }

  @Test
  fun `login with invalid password yields PasswordMismatch`() =
    runTest {
      val email = "login_bad_pwd@example.com"
      authService.register(email, "Bad Pwd", "Password123", null, 86400L, null, null)

      val result = authService.login(email, "WrongPassword", null, 86400L, null, null)
      assertTrue(result.isSuccess)
      assertTrue(result.getOrNull() is LoginResult.PasswordMismatch)
    }

  @Test
  fun `login with old cookie revokes old session and creates new`() =
    runTest {
      val email = "login_old_cookie@example.com"
      val password = "Password123"
      val regResult = authService.register(email, "Old Cookie", password, null, 86400L, null, null)
      val oldToken = (regResult.getOrNull() as RegisterResult.Success).token

      val result = authService.login(email, password, oldToken, 86400L, null, null)
      assertTrue(result.isSuccess)
      val loginResult = result.getOrNull() as LoginResult.Success

      // Old token should be revoked
      val oldHash = TokenHash.fromRawToken(oldToken)
      val findResult = SessionsDao.findByTokenHash(sqlSession, oldHash)
      assertTrue(findResult.isFailure && findResult.exceptionOrNull() is NotFoundException)

      // New token should be valid
      val newHash = TokenHash.fromRawToken(loginResult.token)
      val newFindResult = SessionsDao.findByTokenHash(sqlSession, newHash)
      assertTrue(newFindResult.isSuccess)
    }

  @Test
  fun `login with nonexistent old cookie creates new session without error`() =
    runTest {
      val email = "login_fake_old_cookie@example.com"
      val password = "Password123"
      authService.register(email, "Fake Old Cookie", password, null, 86400L, null, null)

      val fakeOldToken = "some_nonexistent_token"
      val result = authService.login(email, password, fakeOldToken, 86400L, null, null)
      assertTrue(result.isSuccess)
      val loginResult = result.getOrNull() as LoginResult.Success

      val newHash = TokenHash.fromRawToken(loginResult.token)
      val newFindResult = SessionsDao.findByTokenHash(sqlSession, newHash)
      assertTrue(newFindResult.isSuccess)
    }

  @Test
  fun `register inserts one verification token and one email_sends row`() =
    runTest {
      connection.createStatement().use { it.execute("TRUNCATE TABLE email_sends") }
      val email = "verify_register@example.com"
      val result = authService.register(email, "Verify Register", "Password123", null, 86400L, null, null)
      assertTrue(result.isSuccess && result.getOrNull() is RegisterResult.Success)
      val user = (result.getOrNull() as RegisterResult.Success).user

      assertEquals(
        1,
        countRows("SELECT COUNT(*) FROM verification_tokens WHERE user_id = '${user.id.value}'"),
        "Exactly one verification token must exist for the new user",
      )
      assertEquals(1, countRows("SELECT COUNT(*) FROM email_sends"), "Log provider must record one email_sends row")
    }

  @Test
  fun `register succeeds even when the email provider rejects the send`() =
    runTest {
      connection.createStatement().use { it.execute("TRUNCATE TABLE email_sends") }
      val service = authServiceWithRejectingEmail()
      val email = "verify_reject@example.com"
      val result = service.register(email, "Verify Reject", "Password123", null, 86400L, null, null)

      assertTrue(result.isSuccess && result.getOrNull() is RegisterResult.Success, "Registration must succeed despite send rejection")
      val user = (result.getOrNull() as RegisterResult.Success).user

      // The user and verification token still exist (transactional), even though the
      // best-effort send was rejected.
      assertEquals(
        1,
        countRows("SELECT COUNT(*) FROM verification_tokens WHERE user_id = '${user.id.value}'"),
        "Verification token must persist despite send rejection",
      )
      assertTrue(UsersDao.findById(sqlSession, user.id).isSuccess, "User must persist despite send rejection")
    }
}
