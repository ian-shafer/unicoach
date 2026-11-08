package ed.unicoach.auth

import ch.qos.logback.classic.Level
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.json.deserialize
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.AccountTokensDao
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.captureLogs
import ed.unicoach.db.models.AccountTokenType
import ed.unicoach.db.models.LoginMethod
import ed.unicoach.db.models.NewSession
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserEdit
import ed.unicoach.db.models.UserId
import ed.unicoach.db.session.SessionMetadata
import ed.unicoach.email.EmailAccountKey
import ed.unicoach.email.EmailTemplate
import ed.unicoach.queue.QueueService
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PasswordResetServiceTest {
  companion object {
    private const val NEW_PASSWORD = "BrandNew1pass"
    private const val OLD_PASSWORD = "OriginalPass1"
    private val EXPIRATION = Duration.ofDays(30)
    private val UNKNOWN_TOKEN = ResetLink.Dead.NotValid(ResetLink.Dead.NotValidReason.UNKNOWN_TOKEN)
    private val OWNER_CHANGED = ResetLink.Dead.NotValid(ResetLink.Dead.NotValidReason.OWNER_CHANGED)

    private lateinit var database: Database
    private lateinit var connection: Connection
    private val appConfig = AppConfig.load("common.conf", "db.conf", "service.conf").getOrThrow()

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val dbConfig = DatabaseConfig.from(appConfig).getOrThrow()
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
      stmt.execute("TRUNCATE TABLE sessions, users CASCADE")
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val argon2Hasher = Argon2Hasher()
  private val tokenGenerator = TokenGenerator()
  private val verifiedHookCalls = java.util.concurrent.CopyOnWriteArrayList<UserId>()

  private fun service(queueService: QueueService = QueueService(database)) =
    PasswordResetService(
      database,
      queueService,
      tokenGenerator,
      argon2Hasher,
      SessionMinter(tokenGenerator),
      PasswordResetConfig.from(appConfig).getOrThrow(),
    ) { _, userId -> verifiedHookCalls.add(userId) }

  private val resets by lazy { service() }

  private val authService by lazy {
    AuthService(
      database,
      argon2Hasher,
      tokenGenerator,
      EmailVerificationService(
        database,
        QueueService(database),
        tokenGenerator,
        EmailVerificationConfig.from(appConfig).getOrThrow(),
      ),
      GoogleIdTokenVerifier(StubIdTokenVerifier()),
      AppleIdTokenVerifier(StubIdTokenVerifier()),
      { _, _ -> },
    )
  }

  private var userCounter = 0

  private fun nextEmail(): String = "reset-${userCounter++}@example.com"

  private fun address(value: String): EmailAddress = (EmailAddress.create(value) as ValidationResult.Valid).value

  private fun createUser(
    email: String = nextEmail(),
    password: String? = OLD_PASSWORD,
    verified: Boolean = true,
  ): User {
    val name = (PersonName.create("Reset User") as ValidationResult.Valid).value
    val hash = password?.let { (PasswordHash.create(runBlocking { argon2Hasher.hash(it) }) as ValidationResult.Valid).value }
    val created = UsersDao.create(sqlSession, NewUser(address(email), name, null, hash)).getOrThrow()
    return if (verified) UsersDao.markEmailVerified(sqlSession, created.id).getOrThrow() else created
  }

  private fun requestLink(user: User): String {
    runBlocking { assertEquals(PasswordResetRequest.Submitted, resets.request(user.email.value).getOrThrow()) }
    return latestToken(user)
  }

  /** The raw token from the newest job addressed to [user]: the only place it exists but the mail. */
  private fun latestToken(user: User): String {
    val job = SendEmailJobQueries.payloads(connection).last { it.to == user.email.value }
    return job.context.deserialize<PasswordResetEmailContext>().resetToken
  }

  private fun reset(
    token: String,
    password: String = NEW_PASSWORD,
  ): PasswordResetResult =
    runBlocking {
      resets.reset(token, password, null, EXPIRATION, "Test/1.0", "127.0.0.1").getOrThrow()
    }

  private fun inspect(token: String): ResetLink = runBlocking { resets.inspect(token).getOrThrow() }

  private fun login(
    user: User,
    password: String,
  ): LoginResult =
    runBlocking {
      authService.login(user.email.value, password, null, EXPIRATION, "Test/1.0", "127.0.0.1").getOrThrow()
    }

  private fun openSession(
    user: User,
    rawToken: String,
  ) {
    SessionsDao
      .create(
        sqlSession,
        NewSession(
          user.id,
          TokenHash.fromRawToken(rawToken),
          "Test/1.0",
          "127.0.0.1",
          SessionMetadata.getDefaultInstance(),
          EXPIRATION,
          LoginMethod.PASSWORD,
        ),
      ).getOrThrow()
  }

  private fun resolves(rawToken: String): User? = runBlocking { authService.getCurrentUser(TokenHash.fromRawToken(rawToken)).getOrThrow() }

  private fun tokenRows(user: User): Int = RawSql.count(connection, "SELECT COUNT(*) FROM account_tokens WHERE user_id = ?", user.id.value)

  private fun deadLinkLogs(exercise: () -> Unit): List<String> =
    captureLogs(Level.INFO, exercise).filter { it.startsWith("password reset link dead") }

  private fun assertSignsInWith(
    user: User,
    password: String,
  ) {
    assertIs<LoginResult.Success>(login(user, password))
  }

  private fun assertRefuses(
    user: User,
    password: String,
  ) {
    assertIs<LoginResult.PasswordMismatch>(login(user, password))
  }

  @Test
  fun `request for a live account writes one token and one SEND_EMAIL job`() {
    val user = createUser()

    val token = requestLink(user)

    assertEquals(1, tokenRows(user))
    val job = SendEmailJobQueries.payloads(connection).single()
    assertEquals(user.email.value, job.to)
    assertEquals(EmailTemplate.PASSWORD_RESET, job.template)
    assertEquals(EmailAccountKey.Live(user.id), job.account)
    assertEquals(ResetLink.Usable, inspect(token))
  }

  @Test
  fun `request normalizes the address as login does`() {
    val user = createUser()

    runBlocking { resets.request("  ${user.email.value.uppercase()} ").getOrThrow() }

    assertEquals(1, tokenRows(user))
  }

  @Test
  fun `request for no account writes nothing and answers Submitted`() {
    runBlocking {
      assertEquals(PasswordResetRequest.Submitted, resets.request("nobody@example.com").getOrThrow())
    }

    assertEquals(0, RawSql.count(connection, "SELECT COUNT(*) FROM account_tokens"))
    assertEquals(0, SendEmailJobQueries.count(connection))
  }

  @Test
  fun `request for a closed account writes nothing and answers Submitted`() {
    val user = createUser()
    UsersDao.delete(sqlSession, user.id, user.version).getOrThrow()

    runBlocking {
      assertEquals(PasswordResetRequest.Submitted, resets.request(user.email.value).getOrThrow())
    }

    assertEquals(0, tokenRows(user))
    assertEquals(0, SendEmailJobQueries.count(connection))
  }

  @Test
  fun `request for a malformed address answers InvalidEmail`() {
    runBlocking {
      assertIs<PasswordResetRequest.InvalidEmail>(resets.request("not-an-address").getOrThrow())
    }
    assertEquals(0, SendEmailJobQueries.count(connection))
  }

  @Test
  fun `a second request burns the first link`() {
    val user = createUser()
    val first = requestLink(user)
    val second = requestLink(user)

    assertEquals(ResetLink.Dead.AlreadyUsed, inspect(first))
    assertEquals(ResetLink.Usable, inspect(second))
  }

  @Test
  fun `an Apple-only account with no password gets a link and can set one`() {
    val user = createUser(password = null)

    val result = reset(requestLink(user))

    assertIs<PasswordResetResult.Done>(result)
    assertSignsInWith(user, NEW_PASSWORD)
  }

  @Test
  fun `a student, a supporter and an account with neither role reset through the same calls`() {
    val student = createUser()
    StudentRowFixture.insert(connection, java.util.UUID.randomUUID(), student.id.value)
    val supporter = createUser()
    SupportersDao.create(sqlSession, NewSupporter(supporter.id)).getOrThrow()
    val neither = createUser()

    for (user in listOf(student, supporter, neither)) {
      assertIs<PasswordResetResult.Done>(reset(requestLink(user)))
      assertSignsInWith(user, NEW_PASSWORD)
    }
  }

  @Test
  fun `a failed enqueue rolls back the token row`() {
    val user = createUser()
    val failing = service(QueueService(database, FailingJobsDao()))

    val result = runBlocking { failing.request(user.email.value) }

    assertNotNull(result.exceptionOrNull())
    assertEquals(0, tokenRows(user))
  }

  @Test
  fun `reset changes the hash, so the old password fails and the new one succeeds`() {
    val user = createUser()

    assertIs<PasswordResetResult.Done>(reset(requestLink(user)))

    assertRefuses(user, OLD_PASSWORD)
    assertSignsInWith(user, NEW_PASSWORD)
  }

  @Test
  fun `reset returns a session token that resolves to the user`() {
    val user = createUser()

    val done = assertIs<PasswordResetResult.Done>(reset(requestLink(user)))

    assertEquals(user.id, done.user.id)
    assertEquals(user.id, resolves(done.sessionToken)?.id)
  }

  @Test
  fun `reset revokes every earlier session on the account and none on another account`() {
    val user = createUser()
    val other = createUser()
    openSession(user, "phone")
    openSession(user, "laptop")
    openSession(other, "someone-else")

    assertIs<PasswordResetResult.Done>(reset(requestLink(user)))

    assertNull(resolves("phone"))
    assertNull(resolves("laptop"))
    assertEquals(other.id, resolves("someone-else")?.id)
  }

  @Test
  fun `reset revokes the session in the old cookie even when it belongs to another account`() {
    val user = createUser()
    val other = createUser()
    openSession(other, "shared-browser")
    val token = requestLink(user)

    val done =
      assertIs<PasswordResetResult.Done>(
        runBlocking { resets.reset(token, NEW_PASSWORD, "shared-browser", EXPIRATION, null, null).getOrThrow() },
      )

    assertNull(resolves("shared-browser"))
    assertEquals(user.id, resolves(done.sessionToken)?.id)
  }

  @Test
  fun `reset burns the account's other links`() {
    val user = createUser()
    val token = requestLink(user)
    // A second live link, which a newer request would otherwise have burned.
    RawSql.execute(
      connection,
      "INSERT INTO account_tokens (user_id, type, user_version, token_hash, expires_at) " +
        "VALUES (?, 'password_reset', ?, ?, NOW() + interval '1 hour')",
      user.id.value,
      user.version,
      TokenHash.fromRawToken("other-link").value,
    )

    assertIs<PasswordResetResult.Done>(reset(token))

    assertEquals(ResetLink.Dead.AlreadyUsed, inspect("other-link"))
  }

  @Test
  fun `reset twice is Dead AlreadyUsed and the password is unchanged`() {
    val user = createUser()
    val token = requestLink(user)
    assertIs<PasswordResetResult.Done>(reset(token))

    assertEquals(PasswordResetResult.Dead(ResetLink.Dead.AlreadyUsed), reset(token, "AnotherPass2"))

    assertRefuses(user, "AnotherPass2")
    assertSignsInWith(user, NEW_PASSWORD)
  }

  @Test
  fun `reset on an expired token is Dead Expired`() {
    val user = createUser()
    val token = requestLink(user)
    RawSql.execute(connection, "UPDATE account_tokens SET expires_at = NOW() - interval '1 minute' WHERE user_id = ?", user.id.value)

    assertEquals(PasswordResetResult.Dead(ResetLink.Dead.Expired), reset(token))
    assertSignsInWith(user, OLD_PASSWORD)
  }

  @Test
  fun `reset on an unknown token is Dead NotValid`() {
    assertEquals(PasswordResetResult.Dead(UNKNOWN_TOKEN), reset("never-issued"))
  }

  @Test
  fun `reset after changeEmail is Dead NotValid and leaves the password alone`() {
    val user = createUser()
    val token = requestLink(user)
    runBlocking { authService.changeEmail(user, nextEmail()).getOrThrow() }

    assertEquals(OWNER_CHANGED, inspect(token))
    assertEquals(PasswordResetResult.Dead(OWNER_CHANGED), reset(token))

    val moved = UsersDao.findById(sqlSession, user.id).getOrThrow()
    assertSignsInWith(moved, OLD_PASSWORD)
  }

  @Test
  fun `reset after an operator's edit of the address is Dead NotValid and leaves the password alone`() {
    val user = createUser()
    val token = requestLink(user)
    val current = UsersDao.findById(sqlSession, user.id).getOrThrow()
    val moved =
      UsersDao
        .update(
          sqlSession,
          UserEdit(current.id, current.version, address(nextEmail()), current.name, current.displayName, current.isAdmin),
        ).getOrThrow()

    assertEquals(PasswordResetResult.Dead(OWNER_CHANGED), reset(token))

    assertSignsInWith(moved, OLD_PASSWORD)
  }

  @Test
  fun `reset on a closed account is Dead NotValid`() {
    val user = createUser()
    val token = requestLink(user)
    UsersDao.delete(sqlSession, user.id, user.version).getOrThrow()

    assertEquals(PasswordResetResult.Dead(OWNER_CHANGED), reset(token))
  }

  @Test
  fun `an unknown token logs only its outcome and reason`() {
    val lines = deadLinkLogs { inspect("never-issued") }

    assertEquals(listOf("password reset link dead: outcome=[NotValid(reason=UNKNOWN_TOKEN)]"), lines)
  }

  @Test
  fun `a moved address logs its reason, the token row and the user, and never the raw token`() {
    val user = createUser()
    val token = requestLink(user)
    val row = AccountTokensDao.findByTokenHash(sqlSession, AccountTokenType.PASSWORD_RESET, TokenHash.fromRawToken(token)).getOrThrow()
    runBlocking { authService.changeEmail(user, nextEmail()).getOrThrow() }

    val line = deadLinkLogs { inspect(token) }.single()

    assertContains(line, "outcome=[NotValid(reason=OWNER_CHANGED)]")
    assertContains(line, "tokenId=[${row.id.asString}]")
    assertContains(line, "userId=[${user.id.asString}]")
    assertFalse(line.contains(token), "the raw token reached the log: $line")
  }

  @Test
  fun `a post on a dead link records it once`() {
    val user = createUser()
    val token = requestLink(user)
    assertIs<PasswordResetResult.Done>(reset(token))

    val line = deadLinkLogs { reset(token) }.single()

    assertContains(line, "outcome=[AlreadyUsed]")
  }

  @Test
  fun `a rule-breaking password is Rejected and the token stays usable`() {
    val user = createUser()
    val token = requestLink(user)

    val rejected = assertIs<PasswordResetResult.Rejected>(reset(token, "short"))

    assertEquals(listOf(PasswordViolation.TooShort(8), PasswordViolation.NoUppercase, PasswordViolation.NoDigit), rejected.violations)
    assertEquals(ResetLink.Usable, inspect(token))
    assertIs<PasswordResetResult.Done>(reset(token))
  }

  @Test
  fun `a rule-breaking password on a dead link reports the dead link`() {
    assertEquals(PasswordResetResult.Dead(UNKNOWN_TOKEN), reset("never-issued", "short"))
  }

  @Test
  fun `reset on an unverified account marks it verified and runs afterVerified once`() {
    val user = createUser(verified = false)

    val done = assertIs<PasswordResetResult.Done>(reset(requestLink(user)))

    assertNotNull(done.user.emailVerifiedAt)
    assertNotNull(UsersDao.findById(sqlSession, user.id).getOrThrow().emailVerifiedAt)
    assertEquals(listOf(user.id), verifiedHookCalls.toList())
  }

  @Test
  fun `reset on a verified account does not run afterVerified`() {
    val user = createUser(verified = true)

    assertIs<PasswordResetResult.Done>(reset(requestLink(user)))

    assertEquals(emptyList(), verifiedHookCalls.toList())
  }

  @Test
  fun `two concurrent resets produce exactly one Done`() {
    val user = createUser()
    val token = requestLink(user)

    val results =
      runBlocking(Dispatchers.Default) {
        List(2) { async { resets.reset(token, NEW_PASSWORD, null, EXPIRATION, null, null).getOrThrow() } }.awaitAll()
      }

    assertEquals(1, results.count { it is PasswordResetResult.Done }, "results: $results")
    assertEquals(1, results.count { it == PasswordResetResult.Dead(ResetLink.Dead.AlreadyUsed) }, "results: $results")
  }
}
