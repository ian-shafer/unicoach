package ed.unicoach.account

import ch.qos.logback.classic.Level
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.common.json.asJson
import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.dao.captureLogs
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.UserId
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobStatus
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.QueueWorker
import ed.unicoach.queue.dao.JobFindResult
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The erase job (RFC 267 §4), against the real walk and, for the dead-letter
 * path, a real [QueueWorker]: what each outcome of `eraseClose` is to the queue.
 */
class AccountErasureHandlerTest {
  private val database = CoachingTestDb.database
  private val connection = CoachingTestDb.connection
  private val sqlSession = CoachingTestDb.sqlSession
  private val service = AccountErasureService(database, QueueService(database))
  private val handler = AccountErasureHandler(service)

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate("users", "jobs", "llm_requests")
  }

  // --- fixtures -------------------------------------------------------------------

  /** See AccountErasureServiceTest: the fixture's deferred checks need ONE committed transaction. */
  private fun <T> committed(block: () -> T): T {
    connection.autoCommit = false
    try {
      return block().also { connection.commit() }
    } catch (e: Exception) {
      connection.rollback()
      throw e
    } finally {
      connection.autoCommit = true
    }
  }

  private fun closedAccount(): ErasureFixture.Account =
    committed {
      ErasureFixture.build(sqlSession).also {
        UsersDao.markEmailVerified(sqlSession, it.userId).getOrThrow()
        ErasureFixture.close(sqlSession, it.userId)
      }
    }

  private fun closedAt(userId: UserId): Instant = UsersDao.findById(sqlSession, userId, SoftDeleteScope.ALL).getOrThrow().deletedAt!!

  /** The payload the close writes: the account, and the close it was scheduled for. */
  private fun payloadFor(userId: UserId): JsonObject = AccountErasurePayload(userId.asString, closedAt(userId).toString()).asJson()

  private fun reopen(userId: UserId) {
    val user = UsersDao.findById(sqlSession, userId, SoftDeleteScope.ALL).getOrThrow()
    UsersDao.undelete(sqlSession, userId, user.version).getOrThrow()
  }

  private fun execute(payload: JsonObject): JobResult = runBlocking { handler.execute(payload) }

  private fun long(
    sql: String,
    vararg args: Any?,
  ): Long =
    connection.prepareStatement(sql).use { stmt ->
      args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getLong(1)
      }
    }

  private fun isErased(userId: UserId) = long("SELECT count(*) FROM deleted_users WHERE id = ?", userId.value) == 1L

  private fun erasureEmails(): Long =
    long("SELECT count(*) FROM jobs WHERE job_type = 'SEND_EMAIL' AND payload->>'template' = 'ACCOUNT_ERASED'")

  private fun withTrigger(
    table: String,
    block: () -> Unit,
  ) {
    connection.createStatement().use { stmt ->
      stmt.execute(
        """
        CREATE FUNCTION erasure_test_bomb() RETURNS trigger AS
        'BEGIN RAISE EXCEPTION ''$table insert refused by test''; END;' LANGUAGE plpgsql;
        """.trimIndent(),
      )
      stmt.execute("CREATE TRIGGER trigger_zz_erasure_test_bomb BEFORE INSERT ON $table FOR EACH ROW EXECUTE PROCEDURE erasure_test_bomb()")
    }
    try {
      block()
    } finally {
      connection.createStatement().use { stmt ->
        stmt.execute("DROP TRIGGER trigger_zz_erasure_test_bomb ON $table")
        stmt.execute("DROP FUNCTION erasure_test_bomb()")
      }
    }
  }

  // --- 3: a failing walk retries, dead-letters and is finished by the admin door ----

  @Test
  fun `a failing walk is retriable, dead-letters after three attempts, and leaves the account as it was`() {
    val account = closedAccount()
    val addressBefore = account.email.lowercase()

    withTrigger("deleted_users") {
      assertIs<JobResult.RetriableFailure>(execute(payloadFor(account.userId)))

      val jobsDao = JobsDao()
      val inserted = jobsDao.insert(sqlSession, NewJob(JobType.ERASE_ACCOUNT, payloadFor(account.userId), null, null))
      val jobId = assertIs<JobInsertResult.Success>(inserted).job.id
      val errors =
        captureLogs(Level.ERROR) {
          runBlocking {
            val worker = QueueWorker(database, jobsDao, listOf(handler))
            worker.start(CoroutineScope(Dispatchers.IO))
            try {
              withTimeout(30.seconds) {
                while (jobsDao
                    .findById(
                      sqlSession,
                      jobId,
                    ).let { it is JobFindResult.Success && it.job.status != JobStatus.DEAD_LETTERED }
                ) {
                  // The retry backoff, advanced: a rescheduled job is due now, and the UPDATE wakes the worker.
                  connection.prepareStatement("UPDATE jobs SET scheduled_at = NOW() WHERE id = ? AND status = 'SCHEDULED'").use {
                    it.setObject(1, jobId)
                    it.executeUpdate()
                  }
                  delay(100)
                }
              }
            } finally {
              worker.stop(5.seconds)
            }
          }
        }

      assertEquals(3L, long("SELECT count(*) FROM job_attempts WHERE job_id = ?", jobId), "the queue's three attempts")
      assertEquals(1, errors.count { it.contains("Dead-lettered job [$jobId]") && it.contains(JobType.ERASE_ACCOUNT.value) }, "$errors")
    }

    assertTrue(!isErased(account.userId), "no deleted_users row: the walk rolled back whole")
    assertEquals(
      addressBefore,
      UsersDao
        .findById(sqlSession, account.userId, SoftDeleteScope.ALL)
        .getOrThrow()
        .email.value,
    )
    assertFailsWith<SQLException>("the email_sends guard is armed again: no erasure was declared") {
      connection.prepareStatement("UPDATE email_sends SET subject = 'changed' WHERE user_id = ?").use {
        it.setObject(1, account.userId.value)
        it.executeUpdate()
      }
    }
    assertEquals(0L, erasureEmails())

    // The operator's cue: the admin door finishes it, and tells the person.
    assertIs<ErasureOutcome.Erased>(runBlocking { service.erase(account.userId).getOrThrow() })
    assertTrue(isErased(account.userId))
    assertEquals(1L, erasureEmails())
  }

  // --- 4: reopened, and closed again --------------------------------------------------

  @Test
  fun `a reopened account is NotClosed, and a job for a superseded close writes nothing`() {
    val account = closedAccount()
    val firstClose = payloadFor(account.userId)
    reopen(account.userId)

    assertEquals(ErasureOutcome.NotClosed, runBlocking { service.eraseClose(account.userId, Instant.EPOCH).getOrThrow() })
    assertEquals(JobResult.Success, execute(firstClose), "reopened: nothing to erase, and not a failure")
    assertTrue(!isErased(account.userId))

    ErasureFixture.close(sqlSession, account.userId)
    val secondClose = payloadFor(account.userId)

    assertEquals(JobResult.Success, execute(firstClose), "closed again: the later close has its own job")
    assertTrue(!isErased(account.userId), "the first close's job writes nothing")
    assertEquals(0L, erasureEmails())

    assertEquals(JobResult.Success, execute(secondClose))
    assertTrue(isErased(account.userId), "the second close's job erases")
    assertEquals(1L, erasureEmails())
  }

  @Test
  fun `eraseClose names each outcome`() {
    val account = closedAccount()
    val closedAt = closedAt(account.userId)

    assertEquals(
      CloseErasureOutcome.CloseSuperseded,
      runBlocking { service.eraseClose(account.userId, closedAt.minusSeconds(1)).getOrThrow() },
    )
    assertIs<ErasureOutcome.Erased>(runBlocking { service.eraseClose(account.userId, closedAt).getOrThrow() })
    assertEquals(ErasureOutcome.AlreadyErased, runBlocking { service.eraseClose(account.userId, closedAt).getOrThrow() })
    assertEquals(ErasureOutcome.NotFound, runBlocking { service.eraseClose(UserId(UUID.randomUUID()), closedAt).getOrThrow() })
  }

  // --- 5: what can never erase dead-letters at once ----------------------------------

  @Test
  fun `an unknown account and an unparseable payload are PermanentFailure`() {
    val unknown = AccountErasurePayload(UUID.randomUUID().toString(), Instant.now().toString()).asJson()
    assertIs<JobResult.PermanentFailure>(execute(unknown))

    for (malformed in listOf(
      JsonObject(mapOf("userId" to JsonPrimitive("not-a-uuid"), "closedAt" to JsonPrimitive(Instant.now().toString()))),
      JsonObject(mapOf("userId" to JsonPrimitive(UUID.randomUUID().toString()), "closedAt" to JsonPrimitive("yesterday"))),
      JsonObject(mapOf("userId" to JsonPrimitive(UUID.randomUUID().toString()))),
    )) {
      assertIs<JobResult.PermanentFailure>(execute(malformed), "$malformed")
    }
  }

  @Test
  fun `a close time written with an offset names the same close`() {
    val account = closedAccount()
    val offset =
      java.time.OffsetDateTime
        .ofInstant(closedAt(account.userId), java.time.ZoneOffset.ofHours(-7))
        .toString()

    assertEquals(JobResult.Success, execute(AccountErasurePayload(account.userId.asString, offset).asJson()))
    assertTrue(isErased(account.userId), "the backfill writes the close in the session's zone; it is the same instant")
  }

  // --- 6: a duplicate job sends no second email ---------------------------------------

  @Test
  fun `a second job for an erased account is AlreadyErased and sends no second email`() {
    val account = closedAccount()
    val closedAt = closedAt(account.userId)
    val payload = payloadFor(account.userId)

    assertEquals(JobResult.Success, execute(payload))
    assertEquals(1L, erasureEmails())

    assertEquals(ErasureOutcome.AlreadyErased, runBlocking { service.eraseClose(account.userId, closedAt).getOrThrow() })
    assertEquals(JobResult.Success, execute(payload))
    assertEquals(1L, erasureEmails(), "no second email")
  }
}
