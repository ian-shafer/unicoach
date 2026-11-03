package ed.unicoach.rest

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import ed.unicoach.account.AccountClosureService
import ed.unicoach.account.AccountErasedEmailRenderer
import ed.unicoach.account.AccountErasureConfig
import ed.unicoach.account.AccountErasureHandler
import ed.unicoach.account.AccountErasureScheduler
import ed.unicoach.account.AccountErasureService
import ed.unicoach.account.CloseAccountOutcome
import ed.unicoach.account.ErasureOutcome
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.email.EmailConfig
import ed.unicoach.email.EmailSendHandler
import ed.unicoach.email.EmailService
import ed.unicoach.email.ScriptedSesSendOperation
import ed.unicoach.email.SesEmailProvider
import ed.unicoach.email.SesFixtures
import ed.unicoach.emailRenderers
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.QueueWorker
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Settings -> Delete account, end to end (RFC 267): the close schedules the
 * erasure; a real [QueueWorker] with the erase and email handlers runs it when
 * it is due, and the email it enqueues is sent through the real
 * [SesEmailProvider] over a scripted SES seam (the RFC 107 end-to-end seam) and
 * recorded as the deletion receipt. Everything above the seam is real: the
 * close, the walk, the queue, the renderer, the ledger write.
 *
 * "The DB clock advanced past N" is the job's `scheduled_at` moved into the
 * past (the QueueWorkerTest precedent): there is no DB-clock helper.
 */
class AccountErasureEndToEndTest {
  companion object {
    private lateinit var config: Config
    private lateinit var database: Database
    private lateinit var connection: Connection
    private lateinit var emailConfig: EmailConfig
    private lateinit var verifyUrlBase: String

    private const val MESSAGE_ID = "ses-erased-1"

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      config = AppConfig.load("common.conf", "db.conf", "service.conf", "queue.conf", "email.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
      emailConfig = EmailConfig.from(config).getOrThrow()
      verifyUrlBase = EmailVerificationConfig.from(config).getOrThrow().verifyUrlBase
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized) connection.close()
      if (::database.isInitialized) database.close()
    }
  }

  // Another suite's SCHEDULED jobs would be claimed by this test's worker.
  @BeforeEach
  fun reset() {
    connection.createStatement().use { it.execute("TRUNCATE TABLE jobs CASCADE") }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun erasureConfig(graceDays: Int): AccountErasureConfig =
    AccountErasureConfig.from(ConfigFactory.parseString("accountErasure.graceDays = $graceDays")).getOrThrow()

  private fun closure(graceDays: Int): AccountClosureService {
    val queue = QueueService(database)
    return AccountClosureService(database, LinkEndedNotice(queue), AccountErasureScheduler(queue, erasureConfig(graceDays)))
  }

  /**
   * [ErasureFixture]'s account with a verified address, committed in one
   * transaction (its deferred checks run at commit). The jobs the fixture
   * writes are removed: they stand for work of other kinds, which this worker
   * would otherwise run.
   */
  private fun verifiedAccount(): ErasureFixture.Account {
    connection.autoCommit = false
    try {
      val account = ErasureFixture.build(session)
      UsersDao.markEmailVerified(session, account.userId).getOrThrow()
      connection.createStatement().use { it.execute("DELETE FROM jobs") }
      connection.commit()
      return account
    } catch (e: Exception) {
      connection.rollback()
      throw e
    } finally {
      connection.autoCommit = true
    }
  }

  private fun ses(): ScriptedSesSendOperation = SesFixtures.sent(MESSAGE_ID)

  private fun <T> withWorker(
    ses: ScriptedSesSendOperation,
    block: suspend () -> T,
  ): T =
    runBlocking {
      val queue = QueueService(database)
      val handlers =
        listOf(
          AccountErasureHandler(AccountErasureService(database, queue)),
          EmailSendHandler(EmailService(database, SesEmailProvider(ses, AutoCloseable {}), emailConfig), emailRenderers(verifyUrlBase)),
        )
      val worker = QueueWorker(database, JobsDao(), handlers)
      worker.start(CoroutineScope(Dispatchers.IO))
      try {
        block()
      } finally {
        worker.stop(5.seconds)
      }
    }

  private fun jobId(jobType: String): String =
    RawSql.single(connection, "SELECT id::text FROM jobs WHERE job_type = ?", jobType) { it.getString(1) }

  private fun count(
    sql: String,
    vararg args: Any?,
  ): Long = RawSql.single(connection, sql, *args) { it.getLong(1) }

  private fun receipt(account: ErasureFixture.Account): String =
    RawSql.single(
      connection,
      "SELECT to_jsonb(e)::text FROM email_sends e WHERE e.deleted_user_id = ? AND e.provider_message_id = ?",
      account.userId.value,
      MESSAGE_ID,
    ) { it.getString(1) }

  @Test
  fun `a closed account is erased when its grace is up, and told once`() {
    val account = verifiedAccount()
    val address = account.email.lowercase()
    assertEquals(CloseAccountOutcome.Closed, runBlocking { closure(graceDays = 7).close(account.userId).getOrThrow() })
    val eraseJob = jobId("ERASE_ACCOUNT")
    val ses = ses()

    withWorker(ses) {
      // Two of the worker's passes over a job that is not yet due.
      delay(1500)
      assertEquals("SCHEDULED", jobStatus(connection, eraseJob), "the worker does not run the job before it is due")
      assertEquals(0L, count("SELECT count(*) FROM deleted_users WHERE id = ?", account.userId.value))

      RawSql.execute(connection, "UPDATE jobs SET scheduled_at = NOW() - INTERVAL '1 second' WHERE id = ?::uuid", eraseJob)
      awaitJobStatus(connection, eraseJob, "COMPLETED")
      awaitJobStatus(connection, jobId("SEND_EMAIL"), "COMPLETED")
    }

    assertEquals(1L, count("SELECT count(*) FROM deleted_users WHERE id = ?", account.userId.value), "erased")
    assertNull(RawSql.single(connection, "SELECT email FROM users WHERE id = ?", account.userId.value) { it.getString(1) }, "the walk ran")
    assertEquals(
      1L,
      count(
        "SELECT count(*) FROM jobs WHERE job_type = 'SEND_EMAIL' AND payload->>'template' = 'ACCOUNT_ERASED' AND payload->>'to' = ?",
        address,
      ),
      "one confirmation, to the address the account had",
    )
    assertEquals(1, ses.bodyTexts.size, "one email sent")
    val rendered = AccountErasedEmailRenderer().render(JsonObject(emptyMap())).getOrThrow()
    RawSql.single(
      connection,
      "SELECT user_id, recipient_email, subject, body FROM email_sends WHERE deleted_user_id = ? AND provider_message_id = ?",
      account.userId.value,
      MESSAGE_ID,
    ) { rs ->
      assertNull(rs.getObject("user_id"), "the receipt is the erased account's, not a live one's")
      assertEquals(address, rs.getString("recipient_email"))
      assertEquals(rendered.subject.value, rs.getString("subject"))
      assertEquals(rendered.body.value, rs.getString("body"), "the receipt keeps its body")
    }

    // The admin door on the erased account: nothing to do, nothing touched, no second email.
    val receiptBefore = receipt(account)
    assertEquals(
      ErasureOutcome.AlreadyErased,
      runBlocking {
        AccountErasureService(database, QueueService(database)).erase(account.userId).getOrThrow()
      },
    )
    assertEquals(receiptBefore, receipt(account), "the receipt is unchanged")
    assertEquals(1L, count("SELECT count(*) FROM jobs WHERE job_type = 'SEND_EMAIL'"), "no second email is enqueued")
  }

  @Test
  fun `with no grace the close is erased with no clock moved`() {
    val account = verifiedAccount()
    val ses = ses()

    withWorker(ses) {
      assertEquals(CloseAccountOutcome.Closed, closure(graceDays = 0).close(account.userId).getOrThrow())
      awaitJobStatus(connection, jobId("ERASE_ACCOUNT"), "COMPLETED")
      awaitJobStatus(connection, jobId("SEND_EMAIL"), "COMPLETED")
    }

    assertEquals(1L, count("SELECT count(*) FROM deleted_users WHERE id = ?", account.userId.value))
    assertEquals(1, ses.bodyTexts.size)
    receipt(account)
  }
}
