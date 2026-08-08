package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.VerificationEmailRenderer
import ed.unicoach.chat.AnthropicChatProvider
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.email.EmailConfig
import ed.unicoach.email.EmailSendHandler
import ed.unicoach.email.EmailService
import ed.unicoach.email.ScriptedSesSendOperation
import ed.unicoach.email.SesEmailProvider
import ed.unicoach.email.SesFixtures
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.QueueWorker
import ed.unicoach.queue.dao.JobsDao
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.VerifyEmailRequest
import ed.unicoach.web.common.logging.RequestLoggingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * RFC 107: register -> transactional enqueue -> real QueueWorker -> EmailSendHandler
 * -> real SesEmailProvider over a faked SesSendOperation seam -> DB. The seam
 * replays recorded SES SDK shapes; everything above it is real (the provider's
 * exception mapping, EmailService's ledger write, the verification renderer). The
 * worker is booted per test with a per-test SES script and polled to a terminal
 * job status. A second server wired with a FailingJobsDao proves atomic rollback.
 */
class EmailSendE2eTest {
  companion object {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var failingServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var connection: Connection
    private lateinit var database: Database
    private lateinit var emailConfig: EmailConfig
    private lateinit var verifyUrlBase: String
    private var port: Int = 0
    private var failingPort: Int = 0

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

      val sessionConfig = SessionConfig.from(config).getOrThrow()
      val requestSizeConfig = RequestSizeConfig.from(config).getOrThrow()
      val coachingConfig = CoachingConfig.from(config).getOrThrow()
      val clientKeyGateConfig = ClientKeyGateConfig.from(config).getOrThrow()
      val requestLoggingConfig = RequestLoggingConfig.from(config).getOrThrow()
      val emailVerificationConfig = EmailVerificationConfig.from(config).getOrThrow()
      val googleTokenVerifier = ed.unicoach.auth.StubGoogleTokenVerifier()
      val extractionConfig = ExtractionConfig.from(config).getOrThrow()
      emailConfig = EmailConfig.from(config).getOrThrow()
      verifyUrlBase = emailVerificationConfig.verifyUrlBase

      // The chat path is not exercised by the register flow; wire the real provider
      // (wrapped by the real LlmCallLog, RFC 106) over an unused seam for uniformity.
      val llmCallLog = LlmCallLog(AnthropicChatProvider(SwappableAnthropicTransport(), AutoCloseable {}), database)

      fun boot(queueService: QueueService): EmbeddedServer<*, *> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            llmCallLog,
            coachingConfig,
            clientKeyGateConfig,
            emailVerificationConfig,
            googleTokenVerifier,
            queueService,
            extractionConfig,
            requestLoggingConfig,
            BudgetConfig.from(config).getOrThrow(),
          )
        }

      server = boot(QueueService(database))
      server.start(wait = false)
      port =
        runBlocking {
          server.engine
            .resolvedConnectors()
            .first()
            .port
        }

      failingServer = boot(QueueService(database, FailingJobsDao(JobType.SEND_EMAIL)))
      failingServer.start(wait = false)
      failingPort =
        runBlocking {
          failingServer.engine
            .resolvedConnectors()
            .first()
            .port
        }

      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::server.isInitialized) server.stop(1000, 5000)
      if (::failingServer.isInitialized) failingServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
      if (::database.isInitialized) database.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  @BeforeEach
  fun resetTables() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE email_sends")
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private fun url(
    p: Int,
    path: String,
  ) = "http://localhost:$p$path"

  private suspend fun register(
    p: Int,
    email: String,
  ): HttpStatusCode {
    val reg =
      client.post(url(p, "/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Email User")))
      }
    return reg.status
  }

  private fun sendEmailJobId(to: String): String? =
    connection
      .prepareStatement("SELECT id::text FROM jobs WHERE job_type = 'SEND_EMAIL' AND payload->>'to' = ?")
      .use { stmt ->
        stmt.setString(1, to)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
      }

  private data class SendRow(
    val status: String,
    val provider: String,
    val providerMessageId: String?,
    val errorMessage: String?,
    val body: String,
  )

  private fun emailSendRow(to: String): SendRow? =
    connection
      .prepareStatement(
        "SELECT status, provider, provider_message_id, error_message, body FROM email_sends WHERE recipient_email = ?",
      ).use { stmt ->
        stmt.setString(1, to)
        stmt.executeQuery().use { rs ->
          if (rs.next()) SendRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) else null
        }
      }

  private fun countUsers(email: String): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM users WHERE email = ?").use { stmt ->
      stmt.setString(1, email)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun emailHandler(ses: ScriptedSesSendOperation): EmailSendHandler =
    EmailSendHandler(
      EmailService(database, SesEmailProvider(ses, AutoCloseable {}), emailConfig),
      listOf(VerificationEmailRenderer(verifyUrlBase)),
    )

  private fun startWorker(handler: EmailSendHandler): Pair<QueueWorker, CoroutineScope> {
    val worker = QueueWorker(database, JobsDao(), listOf(handler))
    val scope = CoroutineScope(Dispatchers.IO)
    worker.start(scope)
    return worker to scope
  }

  private fun uniqueEmail() = "e2e${java.util.UUID.randomUUID()}@company.com"

  // ---------------------------------------------------------------------------

  @Test
  fun `registration enqueues exactly one SEND_EMAIL job`() =
    runBlocking {
      val email = uniqueEmail()
      assertEquals(HttpStatusCode.Created, register(port, email))

      val jobId = assertNotNull(sendEmailJobId(email), "registration must enqueue a SEND_EMAIL job")
      assertEquals("SCHEDULED", jobStatus(connection, jobId))
    }

  @Test
  fun `the worker sends, records a SENT row, and the transmitted link carries the raw verify token`() =
    runBlocking {
      val email = uniqueEmail()
      assertEquals(HttpStatusCode.Created, register(port, email))
      val jobId = assertNotNull(sendEmailJobId(email))

      val ses = SesFixtures.sent("ses-m-1")
      val (worker, _) = startWorker(emailHandler(ses))
      try {
        awaitJobStatus(connection, jobId, "COMPLETED")
      } finally {
        worker.stop(5.seconds)
      }

      val row = assertNotNull(emailSendRow(email), "a SENT ledger row must exist")
      assertEquals("SENT", row.status)
      assertEquals("ses", row.provider)
      assertEquals("ses-m-1", row.providerMessageId)

      // Only the token hash is persisted; the raw token rode only in the enqueued
      // job and the transmitted email. Pull it off the wire request and verify it.
      val transmittedBody: String =
        ses.bodyTexts.single() ?: error("the SES request must carry a text body")
      val token =
        Regex("token=([^\\s&\"]+)").find(transmittedBody)?.groupValues?.get(1)
          ?: error("no verify token in transmitted body: $transmittedBody")

      val verify =
        client.post(url(port, "/api/v1/auth/verify-email")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(VerifyEmailRequest(token)))
        }
      assertEquals(HttpStatusCode.OK, verify.status, "the transmitted raw token must verify: ${verify.bodyAsText()}")
    }

  @Test
  fun `a rejected send records a REJECTED row and dead-letters the job`() =
    runBlocking {
      val email = uniqueEmail()
      assertEquals(HttpStatusCode.Created, register(port, email))
      val jobId = assertNotNull(sendEmailJobId(email))

      val (worker, _) = startWorker(emailHandler(SesFixtures.rejected("recipient rejected by ses")))
      try {
        awaitJobStatus(connection, jobId, "DEAD_LETTERED")
      } finally {
        worker.stop(5.seconds)
      }

      val row = assertNotNull(emailSendRow(email))
      assertEquals("REJECTED", row.status)
      assertTrue(
        row.errorMessage?.contains("recipient rejected by ses") == true,
        "the REJECTED row must preserve the SES reason, got ${row.errorMessage}",
      )
      assertEquals("PERMANENT_FAILURE", latestJobAttemptStatus(connection, jobId))
    }

  @Test
  fun `a transient send writes no row and reschedules the job for retry`() =
    runBlocking {
      val email = uniqueEmail()
      assertEquals(HttpStatusCode.Created, register(port, email))
      val jobId = assertNotNull(sendEmailJobId(email))

      val (worker, _) = startWorker(emailHandler(SesFixtures.transient("throttled by ses")))
      try {
        awaitJobAttempts(connection, jobId, 1)
      } finally {
        worker.stop(5.seconds)
      }

      // Transmit-then-record hazard on the transient branch: nothing is recorded,
      // and the job is left for retry (backoff base 2s => still SCHEDULED).
      assertNull(emailSendRow(email), "a transient failure must write no email_sends row")
      assertEquals("RETRIABLE_FAILURE", latestJobAttemptStatus(connection, jobId))
      assertEquals("SCHEDULED", jobStatus(connection, jobId))
    }

  @Test
  fun `a failed enqueue rolls back user creation (atomic transaction)`() =
    runBlocking {
      val email = uniqueEmail()
      // The failing server's QueueService throws on the SEND_EMAIL insert, which
      // aborts the register transaction.
      assertEquals(HttpStatusCode.InternalServerError, register(failingPort, email))

      assertEquals(0, countUsers(email), "a failed enqueue must roll back the user insert")
      assertNull(sendEmailJobId(email), "no SEND_EMAIL job may be committed on rollback")
    }
}
