package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UserAuthIdentitiesDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.ProviderSubject
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleAuthServiceTest {
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
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE users CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

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

  private val authService by lazy {
    AuthService(
      database,
      ed.unicoach.util.Argon2Hasher(),
      ed.unicoach.util.TokenGenerator(),
      emailVerificationService(),
      StubGoogleTokenVerifier(),
    )
  }

  private fun token(
    sub: String,
    email: String,
    verified: Boolean = true,
    name: String? = "Stub User",
  ): String {
    val builder = StringBuilder("stub:sub=$sub;email=$email;email_verified=$verified")
    if (name != null) builder.append(";name=$name")
    return builder.toString()
  }

  private fun login(idToken: String): GoogleLoginResult =
    runBlocking {
      authService.loginWithGoogle(idToken, null, 86400L, null, null).getOrThrow()
    }

  private fun identitiesFor(user: User) = UserAuthIdentitiesDao.listByUser(sqlSession, user.id).getOrThrow()

  @Test
  fun `new user creates user identity and a GOOGLE session`() {
    val result = login(token("new-sub", "newuser@example.com"))
    assertTrue(result is GoogleLoginResult.Success)
    val user = result.user

    assertEquals(null, user.passwordHash)
    val identities = identitiesFor(user)
    assertEquals(1, identities.size)
    assertEquals("new-sub", identities.first().subject.value)

    val sessionRow = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(result.token)).getOrThrow()
    assertEquals(LoginMethod.GOOGLE, sessionRow.loginMethod)
    assertEquals(user.id, sessionRow.userId)
  }

  @Test
  fun `returning login reuses the user and creates no new identity`() {
    val first = login(token("return-sub", "return@example.com")) as GoogleLoginResult.Success
    val second = login(token("return-sub", "return@example.com")) as GoogleLoginResult.Success

    assertEquals(first.user.id, second.user.id)
    assertEquals(1, identitiesFor(second.user).size)
    assertTrue(first.token != second.token, "Returning login mints a fresh session token")
  }

  @Test
  fun `link attaches an identity to an existing password user`() {
    val email = "linkme@example.com"
    val pwd = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    val existing =
      UsersDao
        .create(
          sqlSession,
          NewUser(
            email = (EmailAddress.create(email) as ValidationResult.Valid).value,
            name = (PersonName.create("Password User") as ValidationResult.Valid).value,
            displayName = null,
            passwordHash = pwd,
          ),
        ).getOrThrow()

    val result = login(token("link-sub", email)) as GoogleLoginResult.Success
    assertEquals(existing.id, result.user.id)
    assertTrue(result.user.passwordHash != null, "Linking must preserve the password credential")

    val identities = identitiesFor(result.user)
    assertEquals(1, identities.size)
    assertEquals("link-sub", identities.first().subject.value)
  }

  @Test
  fun `email-not-verified gates before anything is created`() {
    val result = login(token("unverified-sub", "unverified@example.com", verified = false))
    assertTrue(result is GoogleLoginResult.EmailNotVerified)

    val byEmail = UsersDao.findByEmail(sqlSession, (EmailAddress.create("unverified@example.com") as ValidationResult.Valid).value)
    assertTrue(byEmail.isFailure, "No user must be created when email is unverified")
    val identity = UserAuthIdentitiesDao.findByProviderAndSubject(sqlSession, AuthProvider.GOOGLE, subject("unverified-sub"))
    assertTrue(identity.isFailure, "No identity must be created when email is unverified")
  }

  @Test
  fun `account disabled when the identity resolves to a soft-deleted user`() {
    val first = login(token("disabled-sub", "disabled@example.com")) as GoogleLoginResult.Success
    val loaded = UsersDao.findById(sqlSession, first.user.id).getOrThrow()
    UsersDao.delete(sqlSession, first.user.id, loaded.version).getOrThrow()

    val result = login(token("disabled-sub", "disabled@example.com"))
    assertTrue(result is GoogleLoginResult.AccountDisabled)
  }

  @Test
  fun `invalid token yields InvalidToken and creates nothing`() {
    val result = login(StubGoogleTokenVerifier.INVALID_TOKEN)
    assertTrue(result is GoogleLoginResult.InvalidToken)
  }

  @Test
  fun `verification unavailable yields VerificationUnavailable`() {
    val result = login(StubGoogleTokenVerifier.UNAVAILABLE_TOKEN)
    assertTrue(result is GoogleLoginResult.VerificationUnavailable)
  }

  @Test
  fun `name fallback derives the name from the email local-part`() {
    val result = login(token("fallback-sub", "ada.lovelace@example.com", name = null)) as GoogleLoginResult.Success
    assertEquals("ada.lovelace", result.user.name.value)
  }

  @Test
  fun `old cookie is revoked and a fresh session issued`() {
    val first = login(token("cookie-sub", "cookie@example.com")) as GoogleLoginResult.Success

    val second =
      runBlocking {
        authService.loginWithGoogle(token("cookie-sub", "cookie@example.com"), first.token, 86400L, null, null).getOrThrow()
      } as GoogleLoginResult.Success

    val oldSession = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(first.token))
    assertTrue(oldSession.isFailure, "The old cookie's session must be revoked")
    val newSession = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(second.token))
    assertTrue(newSession.isSuccess, "A fresh session must be live")
  }

  @Test
  fun `concurrent first login for the same sub yields one identity and two successes`() {
    val tok = token("race-sub", "race@example.com")
    val results =
      runBlocking {
        listOf(
          async(kotlinx.coroutines.Dispatchers.IO) { authService.loginWithGoogle(tok, null, 86400L, null, null).getOrThrow() },
          async(kotlinx.coroutines.Dispatchers.IO) { authService.loginWithGoogle(tok, null, 86400L, null, null).getOrThrow() },
        ).awaitAll()
      }

    assertTrue(results.all { it is GoogleLoginResult.Success }, "Both racers must succeed, got $results")
    val userIds = results.filterIsInstance<GoogleLoginResult.Success>().map { it.user.id }.toSet()
    assertEquals(1, userIds.size, "Both racers must resolve to the same user")

    val identities =
      UserAuthIdentitiesDao
        .listByUser(
          sqlSession,
          results
            .filterIsInstance<GoogleLoginResult.Success>()
            .first()
            .user.id,
        ).getOrThrow()
    assertEquals(1, identities.size, "Exactly one identity must exist for the raced sub")
  }

  @Test
  fun `concurrent first login for the same new email yields one user with two identities`() {
    val email = "sharedemail@example.com"
    val results =
      runBlocking {
        listOf(
          async(kotlinx.coroutines.Dispatchers.IO) {
            authService.loginWithGoogle(token("email-sub-a", email), null, 86400L, null, null).getOrThrow()
          },
          async(kotlinx.coroutines.Dispatchers.IO) {
            authService.loginWithGoogle(token("email-sub-b", email), null, 86400L, null, null).getOrThrow()
          },
        ).awaitAll()
      }

    assertTrue(results.all { it is GoogleLoginResult.Success }, "Both racers must succeed, got $results")
    val userIds = results.filterIsInstance<GoogleLoginResult.Success>().map { it.user.id }.toSet()
    assertEquals(1, userIds.size, "Both racers must converge on one user for the shared email")

    val user = results.filterIsInstance<GoogleLoginResult.Success>().first().user
    val identities = UserAuthIdentitiesDao.listByUser(sqlSession, user.id).getOrThrow()
    assertEquals(setOf("email-sub-a", "email-sub-b"), identities.map { it.subject.value }.toSet())
  }

  private fun subject(value: String): ProviderSubject = (ProviderSubject.create(value) as ValidationResult.Valid).value
}
