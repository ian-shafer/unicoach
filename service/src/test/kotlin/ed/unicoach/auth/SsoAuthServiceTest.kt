package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationError
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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SsoAuthServiceTest {
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
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private fun sendEmailJobCount(): Int = SendEmailJobQueries.count(connection)

  private fun userCount(): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun emailVerificationService(): EmailVerificationService {
    val queueService = ed.unicoach.queue.QueueService(database)
    val evConfig = EmailVerificationConfig.from(appConfig).getOrThrow()
    return EmailVerificationService(database, queueService, ed.unicoach.util.TokenGenerator(), evConfig)
  }

  private val authService by lazy {
    AuthService(
      database,
      ed.unicoach.util.Argon2Hasher(),
      ed.unicoach.util.TokenGenerator(),
      emailVerificationService(),
      GoogleIdTokenVerifier(StubIdTokenVerifier()),
      AppleIdTokenVerifier(StubIdTokenVerifier()),
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

  private fun login(
    idToken: String,
    provider: AuthProvider = AuthProvider.GOOGLE,
    clientProvidedName: String? = null,
    oldCookieToken: String? = null,
  ): SsoLoginResult =
    runBlocking {
      authService.loginWithSso(provider, idToken, clientProvidedName, oldCookieToken, Duration.ofDays(1), null, null).getOrThrow()
    }

  private fun identitiesFor(user: User) = UserAuthIdentitiesDao.listByUser(sqlSession, user.id).getOrThrow()

  /** Seeds an active password user whose email is NOT verified (the pre-RFC-111 default). */
  private fun seedUnverifiedUser(email: String): User {
    val pwd = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    return UsersDao
      .create(
        sqlSession,
        NewUser(
          email = (EmailAddress.create(email) as ValidationResult.Valid).value,
          name = (PersonName.create("Password User") as ValidationResult.Valid).value,
          displayName = null,
          passwordHash = pwd,
        ),
      ).getOrThrow()
  }

  /** Seeds an active password user whose email IS verified — a valid link target. */
  private fun seedVerifiedUser(email: String): User = UsersDao.markEmailVerified(sqlSession, seedUnverifiedUser(email).id).getOrThrow()

  @Test
  fun `new user creates user identity and a GOOGLE session`() {
    val result = login(token("new-sub", "newuser@example.com"))
    assertTrue(result is SsoLoginResult.Success)
    val user = result.user

    assertEquals(null, user.passwordHash)
    val identities = identitiesFor(user)
    assertEquals(1, identities.size)
    assertEquals("new-sub", identities.first().subject.value)

    val sessionRow = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(result.token)).getOrThrow()
    assertEquals(LoginMethod.GOOGLE, sessionRow.loginMethod)
    assertEquals(user.id, sessionRow.userId)

    // The Google path is pre-verified (email_verified gated upstream), so it never
    // enqueues a verification email (RFC 96).
    assertEquals(0, sendEmailJobCount(), "A Google sign-in must enqueue no SEND_EMAIL job")
  }

  @Test
  fun `a newly provisioned SSO user is email-verified`() {
    val result = login(token("verify-new-sub", "verifynew@example.com")) as SsoLoginResult.Success
    assertTrue(result.user.emailVerifiedAt != null, "A freshly provisioned SSO user's email must be verified")
  }

  @Test
  fun `apple login for a new user creates the user, an apple identity, and an APPLE session`() {
    val result = login(token("apple-new-sub", "applenewuser@example.com"), provider = AuthProvider.APPLE)
    assertTrue(result is SsoLoginResult.Success)
    val user = result.user

    val identities = identitiesFor(user)
    assertEquals(1, identities.size)
    assertEquals(AuthProvider.APPLE, identities.first().provider)
    assertEquals("apple-new-sub", identities.first().subject.value)

    val sessionRow = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(result.token)).getOrThrow()
    assertEquals(LoginMethod.APPLE, sessionRow.loginMethod)
    assertEquals(user.id, sessionRow.userId)
  }

  @Test
  fun `returning login reuses the user and creates no new identity`() {
    val first = login(token("return-sub", "return@example.com")) as SsoLoginResult.Success
    val second = login(token("return-sub", "return@example.com")) as SsoLoginResult.Success

    assertEquals(first.user.id, second.user.id)
    assertEquals(1, identitiesFor(second.user).size)
    assertTrue(first.token != second.token, "Returning login mints a fresh session token")
  }

  @Test
  fun `link attaches an identity to an existing verified password user`() {
    val email = "linkme@example.com"
    val existing = seedVerifiedUser(email)

    val result = login(token("link-sub", email)) as SsoLoginResult.Success
    assertEquals(existing.id, result.user.id)
    assertTrue(result.user.passwordHash != null, "Linking must preserve the password credential")

    val identities = identitiesFor(result.user)
    assertEquals(1, identities.size)
    assertEquals("link-sub", identities.first().subject.value)
  }

  @Test
  fun `link succeeds onto a verified email-matched user via apple`() {
    val email = "applelinkme@example.com"
    val existing = seedVerifiedUser(email)

    val result = login(token("apple-link-sub", email), provider = AuthProvider.APPLE) as SsoLoginResult.Success
    assertEquals(existing.id, result.user.id)

    val identities = identitiesFor(result.user)
    assertEquals(1, identities.size)
    assertEquals(AuthProvider.APPLE, identities.first().provider)
  }

  @Test
  fun `linking onto a prior SSO-provisioned user verifies it`() {
    // The matched user carries no password credential: it was provisioned by a
    // FIRST SSO login (Google) moments ago, so its email is already verified
    // (a first sign-in's email always equals the token email) — and it is NOT
    // the attacker-registered-password-account scenario the gate exists to
    // block, so a second provider (Apple) must still link onto it cleanly.
    val email = "cross-provider-link@example.com"
    val first = login(token("cross-google-sub", email)) as SsoLoginResult.Success
    assertTrue(first.user.emailVerifiedAt != null, "Precondition: a first SSO sign-in marks its own email verified")
    assertTrue(first.user.passwordHash == null, "Precondition: SSO provisioning never sets a password")

    val second = login(token("cross-apple-sub", email), provider = AuthProvider.APPLE) as SsoLoginResult.Success
    assertEquals(first.user.id, second.user.id)

    val identities = identitiesFor(second.user)
    assertEquals(2, identities.size)
    assertEquals(setOf(AuthProvider.GOOGLE, AuthProvider.APPLE), identities.map { it.provider }.toSet())
  }

  @Test
  fun `link blocked when the email-matched user is unverified, for both providers`() {
    for (provider in listOf(AuthProvider.GOOGLE, AuthProvider.APPLE)) {
      val email = "unverifiedmatch-${provider.wire}@example.com"
      val existing = seedUnverifiedUser(email)

      val result = login(token("blocked-sub-${provider.wire}", email), provider = provider)
      assertTrue(
        result is SsoLoginResult.LinkBlockedUnverifiedEmail,
        "Expected LinkBlockedUnverifiedEmail for $provider, got $result",
      )

      val identity =
        UserAuthIdentitiesDao.findByProviderAndSubject(sqlSession, provider, subject("blocked-sub-${provider.wire}"))
      assertTrue(identity.isFailure, "No identity must be created when the link is blocked ($provider)")

      val reloaded = UsersDao.findById(sqlSession, existing.id).getOrThrow()
      assertEquals(existing.version, reloaded.version, "The matched user must be untouched ($provider)")
      assertTrue(reloaded.emailVerifiedAt == null, "The matched user must remain unverified ($provider)")

      // The gate must refuse BEFORE a session is minted: a blocked login that
      // nevertheless handed out a session for the matched account is exactly the
      // account-takeover this guard exists to prevent.
      val sessions = SessionsDao.listByUser(sqlSession, existing.id, 10, 0).getOrThrow()
      assertTrue(sessions.isEmpty(), "No session must be minted when the link is blocked ($provider)")
    }
  }

  @Test
  fun `email-not-verified gates before anything is created`() {
    val result = login(token("unverified-sub", "unverified@example.com", verified = false))
    assertTrue(result is SsoLoginResult.EmailNotVerified)

    val byEmail = UsersDao.findByEmail(sqlSession, (EmailAddress.create("unverified@example.com") as ValidationResult.Valid).value)
    assertTrue(byEmail.isFailure, "No user must be created when email is unverified")
    val identity = UserAuthIdentitiesDao.findByProviderAndSubject(sqlSession, AuthProvider.GOOGLE, subject("unverified-sub"))
    assertTrue(identity.isFailure, "No identity must be created when email is unverified")
  }

  @Test
  fun `account disabled when the identity resolves to a soft-deleted user`() {
    val first = login(token("disabled-sub", "disabled@example.com")) as SsoLoginResult.Success
    val loaded = UsersDao.findById(sqlSession, first.user.id).getOrThrow()
    UsersDao.delete(sqlSession, first.user.id, loaded.version).getOrThrow()

    val result = login(token("disabled-sub", "disabled@example.com"))
    assertTrue(result is SsoLoginResult.AccountDisabled)
  }

  @Test
  fun `invalid token yields InvalidToken and creates nothing`() {
    val result = assertIs<SsoLoginResult.InvalidToken>(login(StubIdTokenVerifier.INVALID_TOKEN))
    // The reason separates a verifier rejection from an unstorable claim and
    // keeps the verifier's exception — with its cause chain — for the log.
    val reason = assertIs<InvalidTokenReason.VerificationFailed>(result.reason)
    assertEquals("Stub: token does not match the fake-token format", reason.cause.message)
  }

  @Test
  fun `an over-long email claim yields InvalidToken and creates nothing`() {
    // Rejected up front by EmailAddress rather than by users_email_length_check
    // inside the transaction, which the retry path would have run a second time
    // before letting the ConstraintViolationException escape as a 500.
    val result = assertIs<SsoLoginResult.InvalidToken>(login(token("overlong-email-sub", "a".repeat(243) + "@example.com")))
    val reason = assertIs<InvalidTokenReason.UnusableEmail>(result.reason)
    assertIs<ValidationError.TooLong>(reason.error)
    assertEquals(0, userCount(), "An unusable email claim must not create a user")
  }

  @Test
  fun `verification unavailable yields VerificationUnavailable`() {
    val result = assertIs<SsoLoginResult.VerificationUnavailable>(login(StubIdTokenVerifier.UNAVAILABLE_TOKEN))
    assertEquals("Stub: simulated JWKS unavailability", result.cause.message)
  }

  @Test
  fun `name fallback derives the name from the email local-part`() {
    val result = login(token("fallback-sub", "ada.lovelace@example.com", name = null)) as SsoLoginResult.Success
    assertEquals("ada.lovelace", result.user.name.value)
  }

  @Test
  fun `clientProvidedName is used when provisioning via apple with no token claim`() {
    val result =
      login(
        token("apple-name-sub", "applename@example.com", name = null),
        provider = AuthProvider.APPLE,
        clientProvidedName = "Ada From Client",
      ) as SsoLoginResult.Success
    assertEquals("Ada From Client", result.user.name.value)
  }

  @Test
  fun `token name claim wins over clientProvidedName on google`() {
    val result =
      login(
        token("google-name-sub", "googlename@example.com", name = "Token Name"),
        clientProvidedName = "Should Be Ignored",
      ) as SsoLoginResult.Success
    assertEquals("Token Name", result.user.name.value)
  }

  @Test
  fun `unusable clientProvidedName falls back to the email local-part without failing`() {
    val blankResult =
      login(
        token("blank-name-sub", "blankname@example.com", name = null),
        provider = AuthProvider.APPLE,
        clientProvidedName = "   ",
      ) as SsoLoginResult.Success
    assertEquals("blankname", blankResult.user.name.value)

    val tooLongResult =
      login(
        token("long-name-sub", "longname@example.com", name = null),
        provider = AuthProvider.APPLE,
        clientProvidedName = "x".repeat(256),
      ) as SsoLoginResult.Success
    assertEquals("longname", tooLongResult.user.name.value)
  }

  @Test
  fun `clientProvidedName does not rename an existing linked or matched user`() {
    val email = "norename@example.com"
    val existing = seedVerifiedUser(email)

    val matched =
      login(
        token("norename-sub", email),
        provider = AuthProvider.APPLE,
        clientProvidedName = "New Name",
      ) as SsoLoginResult.Success
    assertEquals(existing.name.value, matched.user.name.value, "An existing matched user must never be renamed")

    // Every subsequent Apple sign-in re-asserts a client-supplied name against
    // the now-linked identity; the returning-login path must ignore it too.
    val linked =
      login(
        token("norename-sub", email),
        provider = AuthProvider.APPLE,
        clientProvidedName = "Newer Name",
      ) as SsoLoginResult.Success
    assertEquals(existing.name.value, linked.user.name.value, "An already-linked user must never be renamed")
  }

  @Test
  fun `old cookie is revoked and a fresh session issued`() {
    val first = login(token("cookie-sub", "cookie@example.com")) as SsoLoginResult.Success

    val second =
      login(token("cookie-sub", "cookie@example.com"), oldCookieToken = first.token) as SsoLoginResult.Success

    val oldSession = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(first.token))
    assertTrue(oldSession.isFailure, "The old cookie's session must be revoked")
    val newSession = SessionsDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(second.token))
    assertTrue(newSession.isSuccess, "A fresh session must be live")
  }

  @Test
  fun `a returning login whose account email still matches marks it verified`() {
    // Seed a legacy SSO-provisioned user the old way: emailVerifiedAt stays
    // null even though the row was created by a provider-verified sign-in.
    val email = "legacy-heal@example.com"
    val user =
      UsersDao
        .create(
          sqlSession,
          NewUser(
            email = (EmailAddress.create(email) as ValidationResult.Valid).value,
            name = (PersonName.create("Legacy SSO User") as ValidationResult.Valid).value,
            displayName = null,
            passwordHash = null,
          ),
        ).getOrThrow()
    UserAuthIdentitiesDao
      .create(
        sqlSession,
        ed.unicoach.db.models.NewAuthIdentity(
          userId = user.id,
          provider = AuthProvider.GOOGLE,
          subject = subject("legacy-heal-sub"),
          email = user.email,
          emailVerified = true,
        ),
      ).getOrThrow()
    assertTrue(user.emailVerifiedAt == null, "Precondition: the legacy row is unverified")

    val result = login(token("legacy-heal-sub", email)) as SsoLoginResult.Success
    assertTrue(result.user.emailVerifiedAt != null, "A returning login whose email still matches must heal the legacy row")
  }

  @Test
  fun `a returning login after changeEmail does not mark the new address verified`() {
    val originalEmail = "movedfrom@example.com"
    val newEmail = "movedto@example.com"
    val provisioned = login(token("moved-sub", originalEmail)) as SsoLoginResult.Success
    assertTrue(provisioned.user.emailVerifiedAt != null, "Precondition: the SSO-provisioned user is verified")

    val changeResult =
      runBlocking { authService.changeEmail(provisioned.user, newEmail).getOrThrow() }
    val movedUser = assertIs<ChangeEmailResult.Success>(changeResult).user
    assertTrue(movedUser.emailVerifiedAt == null, "Precondition: changeEmail resets emailVerifiedAt to null")

    // The token still asserts the OLD provider address; the user's current
    // email has since moved, so the equality guard must not mark it verified.
    val result = login(token("moved-sub", originalEmail)) as SsoLoginResult.Success
    assertEquals(movedUser.id, result.user.id)
    assertTrue(result.user.emailVerifiedAt == null, "A stale token must not verify the user's new, different address")
  }

  @Test
  fun `a linked password account keeps its own verification state`() {
    val email = "linked-idempotent@example.com"
    val existing = seedVerifiedUser(email)

    val result = login(token("linked-idempotent-sub", email)) as SsoLoginResult.Success
    assertEquals(existing.id, result.user.id)
    assertTrue(result.user.passwordHash != null, "Linking must preserve the password credential")
    assertTrue(result.user.emailVerifiedAt != null, "The linked account stays verified")
    // markEmailVerified is idempotent (conditional on emailVerifiedAt IS NULL),
    // so an already-verified match takes no redundant version bump.
    assertEquals(existing.version, result.user.version, "No redundant version bump from a no-op mark")
  }

  @Test
  fun `concurrent first login for the same sub yields one identity and two successes`() {
    val tok = token("race-sub", "race@example.com")
    val results =
      runBlocking {
        listOf(
          async(kotlinx.coroutines.Dispatchers.IO) {
            authService.loginWithSso(AuthProvider.GOOGLE, tok, null, null, Duration.ofDays(1), null, null).getOrThrow()
          },
          async(kotlinx.coroutines.Dispatchers.IO) {
            authService.loginWithSso(AuthProvider.GOOGLE, tok, null, null, Duration.ofDays(1), null, null).getOrThrow()
          },
        ).awaitAll()
      }

    assertTrue(results.all { it is SsoLoginResult.Success }, "Both racers must succeed, got $results")
    val userIds = results.filterIsInstance<SsoLoginResult.Success>().map { it.user.id }.toSet()
    assertEquals(1, userIds.size, "Both racers must resolve to the same user")

    val identities =
      UserAuthIdentitiesDao
        .listByUser(
          sqlSession,
          results
            .filterIsInstance<SsoLoginResult.Success>()
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
            authService
              .loginWithSso(
                AuthProvider.GOOGLE,
                token("email-sub-a", email),
                null,
                null,
                Duration.ofDays(1),
                null,
                null,
              ).getOrThrow()
          },
          async(kotlinx.coroutines.Dispatchers.IO) {
            authService
              .loginWithSso(
                AuthProvider.GOOGLE,
                token("email-sub-b", email),
                null,
                null,
                Duration.ofDays(1),
                null,
                null,
              ).getOrThrow()
          },
        ).awaitAll()
      }

    assertTrue(results.all { it is SsoLoginResult.Success }, "Both racers must succeed, got $results")
    val userIds = results.filterIsInstance<SsoLoginResult.Success>().map { it.user.id }.toSet()
    assertEquals(1, userIds.size, "Both racers must converge on one user for the shared email")

    val user = results.filterIsInstance<SsoLoginResult.Success>().first().user
    val identities = UserAuthIdentitiesDao.listByUser(sqlSession, user.id).getOrThrow()
    assertEquals(setOf("email-sub-a", "email-sub-b"), identities.map { it.subject.value }.toSet())
  }

  private fun subject(value: String): ProviderSubject = (ProviderSubject.create(value) as ValidationResult.Valid).value
}
