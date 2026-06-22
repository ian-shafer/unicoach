package ed.unicoach.auth

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.NewVerificationToken
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.email.EmailConfig
import ed.unicoach.email.EmailProvider
import ed.unicoach.email.EmailService
import ed.unicoach.email.OutboundEmail
import ed.unicoach.email.ProviderResult
import ed.unicoach.util.TokenGenerator
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EmailVerificationServiceTest {
  companion object {
    private lateinit var database: Database
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
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
      stmt.execute("TRUNCATE TABLE email_sends")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private class RecordingProvider(
    private val outcome: ProviderResult = ProviderResult.Sent("pm"),
  ) : EmailProvider {
    override val id: String = "recording"
    var sendCount = 0
    var captured: OutboundEmail? = null

    override suspend fun send(email: OutboundEmail): ProviderResult {
      sendCount++
      captured = email
      return outcome
    }
  }

  private fun emailConfig(): EmailConfig =
    EmailConfig
      .from(
        ConfigFactory.parseString(
          """
          email.defaultFrom = "noreply@unicoach.app"
          email.provider = "log"
          email.ses.region = "us-east-1"
          """.trimIndent(),
        ),
      ).getOrThrow()

  private fun config(
    ttl: Duration = Duration.ofHours(24),
    base: String = "https://unicoach.app/verify-email",
  ): EmailVerificationConfig =
    EmailVerificationConfig
      .from(
        ConfigFactory.parseString(
          """
          emailVerification.tokenTtl = "${ttl.toHours()} hours"
          emailVerification.verifyUrlBase = "$base"
          """.trimIndent(),
        ),
      ).getOrThrow()

  private fun service(
    provider: EmailProvider = RecordingProvider(),
    cfg: EmailVerificationConfig = config(),
  ): EmailVerificationService {
    val emailService = EmailService(database, provider, emailConfig())
    return EmailVerificationService(database, emailService, TokenGenerator(), cfg)
  }

  private var userCounter = 0

  private fun createUser(verified: Boolean = false): User {
    val local = "evs-${userCounter++}"
    val email = (EmailAddress.create("$local@example.com") as ValidationResult.Valid).value
    val name = (PersonName.create("EVS User") as ValidationResult.Valid).value
    val pass = (PasswordHash.create("ahash") as ValidationResult.Valid).value
    val user =
      UsersDao
        .create(sqlSession, NewUser(email = email, name = name, displayName = null, passwordHash = pass))
        .getOrThrow()
    if (verified) {
      return UsersDao.markEmailVerified(sqlSession, user.id).getOrThrow()
    }
    return user
  }

  @Test
  fun `issueToken inserts a token whose hash matches and whose expiry is now plus ttl`() =
    runTest {
      val user = createUser()
      val ttl = Duration.ofHours(24)
      val before = Instant.now()
      val raw = service(cfg = config(ttl = ttl)).issueToken(sqlSession, user.id).getOrThrow()

      val token = VerificationTokensDao.findByTokenHash(sqlSession, TokenHash.fromRawToken(raw)).getOrThrow()
      assertEquals(user.id, token.userId)
      val expectedLow = before.plus(ttl).minus(1, ChronoUnit.MINUTES)
      val expectedHigh = Instant.now().plus(ttl).plus(1, ChronoUnit.MINUTES)
      assertTrue(token.expiresAt.isAfter(expectedLow) && token.expiresAt.isBefore(expectedHigh), "expiresAt ~ now + ttl")
    }

  @Test
  fun `verify happy path marks the user verified and burns sibling tokens`() =
    runTest {
      val user = createUser()
      val svc = service()
      val raw = svc.issueToken(sqlSession, user.id).getOrThrow()
      // A sibling outstanding token that must be burned by verify.
      val siblingHash = TokenHash.fromRawToken("sibling-raw")
      VerificationTokensDao
        .create(sqlSession, NewVerificationToken(user.id, siblingHash, Instant.now().plus(1, ChronoUnit.DAYS)))
        .getOrThrow()

      val result = svc.verify(raw).getOrThrow()
      assertTrue(result is VerifyEmailResult.Success, "Expected Success, got $result")
      assertTrue(result.user.emailVerifiedAt != null)

      val sibling = VerificationTokensDao.findByTokenHash(sqlSession, siblingHash).getOrThrow()
      assertTrue(sibling.consumedAt != null, "Sibling token must be burned")
    }

  @Test
  fun `verify with an unknown token yields InvalidToken`() =
    runTest {
      val result = service().verify("never-issued").getOrThrow()
      assertTrue(result is VerifyEmailResult.InvalidToken, "Expected InvalidToken, got $result")
    }

  @Test
  fun `verify with an expired token yields Expired`() =
    runTest {
      val user = createUser()
      val raw = "expired-raw"
      VerificationTokensDao
        .create(sqlSession, NewVerificationToken(user.id, TokenHash.fromRawToken(raw), Instant.now().minus(1, ChronoUnit.DAYS)))
        .getOrThrow()

      val result = service().verify(raw).getOrThrow()
      assertTrue(result is VerifyEmailResult.Expired, "Expected Expired, got $result")
    }

  @Test
  fun `verify with a consumed token yields AlreadyConsumed`() =
    runTest {
      val user = createUser()
      val raw = "consumed-raw"
      VerificationTokensDao
        .create(sqlSession, NewVerificationToken(user.id, TokenHash.fromRawToken(raw), Instant.now().plus(1, ChronoUnit.DAYS)))
        .getOrThrow()
      VerificationTokensDao.consume(sqlSession, TokenHash.fromRawToken(raw)).getOrThrow()

      val result = service().verify(raw).getOrThrow()
      assertTrue(result is VerifyEmailResult.AlreadyConsumed, "Expected AlreadyConsumed, got $result")
    }

  @Test
  fun `resend for an unverified user invalidates the prior token and issues a new consumable one`() =
    runTest {
      val user = createUser()
      val provider = RecordingProvider()
      val svc = service(provider = provider)
      val oldRaw = svc.issueToken(sqlSession, user.id).getOrThrow()

      val result = svc.resend(user).getOrThrow()
      assertTrue(result is ResendResult.Sent, "Expected Sent, got $result")
      assertTrue(provider.sendCount >= 1, "resend must attempt a send")

      // The old token is no longer consumable.
      val oldConsume = VerificationTokensDao.consume(sqlSession, TokenHash.fromRawToken(oldRaw))
      assertTrue(oldConsume.isFailure, "Old token must be invalidated by resend")

      // Exactly one fresh, consumable token exists for the user.
      val freshCount =
        countRows("SELECT COUNT(*) FROM verification_tokens WHERE user_id = '${user.id.value}' AND consumed_at IS NULL")
      assertEquals(1, freshCount, "Resend must leave exactly one outstanding token")
    }

  @Test
  fun `resend for a verified user yields AlreadyVerified and issues no token and sends nothing`() =
    runTest {
      val user = createUser(verified = true)
      val provider = RecordingProvider()
      val svc = service(provider = provider)

      val result = svc.resend(user).getOrThrow()
      assertTrue(result is ResendResult.AlreadyVerified, "Expected AlreadyVerified, got $result")
      assertEquals(0, provider.sendCount, "No email may be sent for a verified user")
      assertEquals(
        0,
        countRows("SELECT COUNT(*) FROM verification_tokens WHERE user_id = '${user.id.value}'"),
        "No token may be issued for a verified user",
      )
    }

  @Test
  fun `sendVerificationEmail builds the link and a provider rejection fails without throwing`() =
    runTest {
      val provider = RecordingProvider(ProviderResult.Rejected("nope"))
      val svc = service(provider = provider, cfg = config(base = "https://unicoach.app/verify-email"))
      val to = (EmailAddress.create("link@example.com") as ValidationResult.Valid).value

      val result = svc.sendVerificationEmail(to, "tok-123")
      assertTrue(result.isFailure, "A provider rejection must surface as Result.failure")
      assertTrue(
        provider.captured
          ?.body
          ?.value
          ?.contains("https://unicoach.app/verify-email?token=tok-123") == true,
        "Body must carry the verify link",
      )
    }

  private fun countRows(sql: String): Int {
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }
}
