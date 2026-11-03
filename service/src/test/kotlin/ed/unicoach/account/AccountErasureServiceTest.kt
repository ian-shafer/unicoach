package ed.unicoach.account

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.common.json.deserialize
import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.UserId
import ed.unicoach.email.EmailAccountKey
import ed.unicoach.email.EmailJobPayload
import ed.unicoach.queue.QueueService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The erasure service (RFC 266 §8, RFC 267 §4), over the real walk: each
 * outcome, the confirmation email it enqueues in the erasure's own
 * transaction, and the re-run that finishes a dead-lettered erase job. Each
 * erasure runs in its own committed transaction, as production's do.
 */
class AccountErasureServiceTest {
  private val database = CoachingTestDb.database
  private val connection = CoachingTestDb.connection
  private val sqlSession = CoachingTestDb.sqlSession
  private val service = AccountErasureService(database, QueueService(database))

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate("users", "jobs", "llm_requests")
  }

  private fun erase(userId: UserId): ErasureOutcome = runBlocking { service.erase(userId).getOrThrow() }

  /**
   * Builds and commits [ErasureFixture]'s account in ONE transaction: its
   * intended-subject answer is checked by deferred triggers at commit, which an
   * autocommit statement-by-statement build would trip half-way.
   */
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

  private fun closedAccount(verified: Boolean = true): ErasureFixture.Account =
    committed {
      ErasureFixture.build(sqlSession).also {
        if (verified) UsersDao.markEmailVerified(sqlSession, it.userId).getOrThrow()
        ErasureFixture.close(sqlSession, it.userId)
      }
    }

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

  private fun isErased(account: ErasureFixture.Account) =
    long("SELECT count(*) FROM deleted_users WHERE id = ?", account.userId.value) == 1L

  private fun addressOf(account: ErasureFixture.Account): String? =
    connection.prepareStatement("SELECT email FROM users WHERE id = ?").use { stmt ->
      stmt.setObject(1, account.userId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getString(1)
      }
    }

  /**
   * The queued `ACCOUNT_ERASED` emails. Read by template in SQL: the fixture's
   * own `SEND_EMAIL` jobs include payloads no [EmailJobPayload] decodes.
   */
  private fun erasureEmails(): List<EmailJobPayload> =
    connection
      .prepareStatement("SELECT payload FROM jobs WHERE job_type = 'SEND_EMAIL' AND payload->>'template' = 'ACCOUNT_ERASED'")
      .use { stmt ->
        stmt.executeQuery().use { rs ->
          buildList { while (rs.next()) add(Json.decodeFromString<JsonObject>(rs.getString(1)).deserialize<EmailJobPayload>()) }
        }
      }

  @Test
  fun `a closed account is erased, with per-table counts, and its verified address is told`() {
    val account = closedAccount()

    val erased = assertIs<ErasureOutcome.Erased>(erase(account.userId))
    val tables = erased.tables.associateBy { it.table }

    assertEquals(1L, tables.getValue("users").erased, "the account row itself")
    assertEquals(1L, tables.getValue("students").moved, "the student's account key")
    assertEquals(1L, tables.getValue("sessions").deleted, "D10: the session")
    assertTrue(tables.getValue("llm_requests").erased >= account.llmRequestIds.size.toLong(), "the LLM log the account owns")
    assertTrue(tables.values.all { it.moved + it.erased + it.deleted > 0 }, "only tables the walk touched are reported")

    assertEquals(ErasureConfirmation.ENQUEUED, erased.confirmation)
    val email = erasureEmails().single()
    assertEquals(account.email.lowercase(), email.to, "the address the account had when it was erased")
    assertEquals(EmailAccountKey.Deleted(account.userId), email.account, "keyed on the erased id, so the receipt is the deleted account's")
    assertTrue(email.context.isEmpty(), "the name is erased with the account; the email carries none")
  }

  @Test
  fun `a live account is NotClosed`() {
    val account = committed { ErasureFixture.build(sqlSession) }
    assertEquals(ErasureOutcome.NotClosed, erase(account.userId))
  }

  @Test
  fun `an unknown id is NotFound`() {
    assertEquals(ErasureOutcome.NotFound, erase(UserId(UUID.randomUUID())))
  }

  @Test
  fun `a re-run is AlreadyErased and sends no second email`() {
    val account = closedAccount()
    assertIs<ErasureOutcome.Erased>(erase(account.userId))
    assertEquals(1, erasureEmails().size)

    assertEquals(ErasureOutcome.AlreadyErased, erase(account.userId))
    assertEquals(ErasureOutcome.AlreadyErased, erase(account.userId))
    assertEquals(1, erasureEmails().size)
  }

  // 7 (RFC 267): D-B, an unverified address was never shown to be the person's.
  @Test
  fun `an account with an unverified address is erased with no email`() {
    val account = closedAccount(verified = false)

    val erased = assertIs<ErasureOutcome.Erased>(erase(account.userId))

    assertEquals(ErasureConfirmation.NO_VERIFIED_ADDRESS, erased.confirmation)
    assertTrue(isErased(account))
    assertEquals(emptyList(), erasureEmails())
  }

  // 8 (RFC 267): the confirmation is a required enqueue (ASYNC_WORK.md rule 2).
  @Test
  fun `a failing email enqueue rolls the erasure back`() {
    val account = closedAccount()
    connection.createStatement().use { stmt ->
      stmt.execute(
        """
        CREATE FUNCTION send_email_test_bomb() RETURNS trigger AS
        'BEGIN RAISE EXCEPTION ''SEND_EMAIL insert refused by test''; END;' LANGUAGE plpgsql;
        """.trimIndent(),
      )
      stmt.execute(
        "CREATE TRIGGER trigger_zz_send_email_test_bomb BEFORE INSERT ON jobs " +
          "FOR EACH ROW WHEN (NEW.job_type = 'SEND_EMAIL') EXECUTE PROCEDURE send_email_test_bomb()",
      )
    }
    try {
      assertTrue(runBlocking { service.erase(account.userId) }.isFailure, "the erasure must fail with its email")
    } finally {
      connection.createStatement().use { stmt ->
        stmt.execute("DROP TRIGGER trigger_zz_send_email_test_bomb ON jobs")
        stmt.execute("DROP FUNCTION send_email_test_bomb()")
      }
    }

    assertTrue(!isErased(account), "no deleted_users row")
    assertEquals(account.email.lowercase(), addressOf(account), "the address is intact")
  }
}
