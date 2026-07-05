package ed.unicoach.auth

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.json.deserialize
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.VerificationTokensDao
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.email.EmailJobPayload
import ed.unicoach.email.EmailTemplate
import ed.unicoach.queue.QueueService
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
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // A real QueueService over a JobsDao whose insert always reports a database
  // failure, to drive the rollback path through the production
  // EnqueueResult.DatabaseFailure mapping (no facade subclassing).
  private fun failingQueueService(): QueueService = QueueService(database, FailingJobsDao())

  private fun config(
    ttl: Duration = Duration.ofHours(24),
    base: String = "https://uni.coach/verify-email",
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
    queueService: QueueService = QueueService(database),
    cfg: EmailVerificationConfig = config(),
  ): EmailVerificationService = EmailVerificationService(database, queueService, TokenGenerator(), cfg)

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

  private fun sendEmailJobs(): List<EmailJobPayload> = SendEmailJobQueries.payloads(connection)

  private fun countRows(sql: String): Int {
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `enqueue inserts a SEND_EMAIL job carrying the token`() =
    runTest {
      val user = createUser()
      val svc = service()

      val rawToken =
        database.withConnection { session ->
          val token = svc.issueToken(session, user.id).getOrThrow()
          svc.enqueue(session, user.email, token).getOrThrow()
          token
        }

      val jobs = sendEmailJobs()
      assertEquals(1, jobs.size, "Exactly one SEND_EMAIL job must be enqueued")
      val job = jobs.single()
      assertEquals(EmailTemplate.EMAIL_VERIFICATION, job.template)
      assertEquals(user.email.value, job.to)
      assertEquals(rawToken, job.context.deserialize<VerificationEmailContext>().verifyToken)
    }

  @Test
  fun `enqueue failure rolls back the issued token`() =
    runTest {
      val user = createUser()
      val svc = service(queueService = failingQueueService())

      val result =
        runCatching {
          database.withConnection { session ->
            svc.issueToken(session, user.id).getOrThrow()
            svc.enqueue(session, user.email, "tok").getOrThrow()
          }
        }

      assertTrue(result.isFailure, "A failing enqueue must fail the surrounding transaction")
      assertEquals(
        0,
        countRows("SELECT COUNT(*) FROM verification_tokens WHERE user_id = '${user.id.value}'"),
        "The issued token must roll back with the failed enqueue",
      )
      assertEquals(0, SendEmailJobQueries.count(connection), "No job may persist")
    }

  @Test
  fun `resend for a verified user enqueues nothing`() =
    runTest {
      val user = createUser(verified = true)
      val svc = service()

      val result = svc.resend(user).getOrThrow()
      assertTrue(result is ResendResult.AlreadyVerified, "Expected AlreadyVerified, got $result")
      assertEquals(0, sendEmailJobs().size, "A verified user resend enqueues no job")
    }

  @Test
  fun `resend burns prior tokens, issues one, enqueues one job`() =
    runTest {
      val user = createUser()
      val svc = service()
      // Seed a prior outstanding token.
      database.withConnection { session -> svc.issueToken(session, user.id).getOrThrow() }

      val result = svc.resend(user).getOrThrow()
      assertTrue(result is ResendResult.Sent, "Expected Sent, got $result")

      assertEquals(
        1,
        countRows("SELECT COUNT(*) FROM verification_tokens WHERE user_id = '${user.id.value}' AND consumed_at IS NULL"),
        "Resend must leave exactly one outstanding token",
      )
      assertEquals(1, sendEmailJobs().size, "Resend must enqueue exactly one SEND_EMAIL job")
    }
}
