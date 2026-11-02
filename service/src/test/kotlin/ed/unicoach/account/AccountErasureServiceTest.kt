package ed.unicoach.account

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.models.UserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The erasure service (RFC 266 §8), over the real walk: each outcome, and the
 * re-run that finishes a dead-lettered erase job. Each erasure runs in its own
 * committed transaction, as production's do, so the re-run also proves the
 * walk works on a pooled connection that ran it before.
 */
class AccountErasureServiceTest {
  private val database = CoachingTestDb.database
  private val sqlSession = CoachingTestDb.sqlSession
  private val service = AccountErasureService(database)

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
    val connection = CoachingTestDb.connection
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
    committed { ErasureFixture.build(sqlSession).also { ErasureFixture.close(sqlSession, it.userId) } }

  @Test
  fun `a closed account is erased, with per-table counts`() {
    val account = closedAccount()

    val tables = assertIs<ErasureOutcome.Erased>(erase(account.userId)).tables.associateBy { it.table }

    assertEquals(1L, tables.getValue("users").erased, "the account row itself")
    assertEquals(1L, tables.getValue("students").moved, "the student's account key")
    assertEquals(1L, tables.getValue("sessions").deleted, "D10: the session")
    assertTrue(tables.getValue("jobs").deleted >= 28, "every job naming the account, in every status")
    assertTrue(tables.getValue("llm_requests").erased >= account.llmRequestIds.size.toLong(), "the LLM log the account owns")
    assertTrue(tables.values.all { it.moved + it.erased + it.deleted > 0 }, "only tables the walk touched are reported")
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
  fun `a re-run is Erased with nothing left to do`() {
    val account = closedAccount()
    assertTrue(assertIs<ErasureOutcome.Erased>(erase(account.userId)).tables.isNotEmpty())

    assertEquals(ErasureOutcome.Erased(emptyList()), erase(account.userId))
    assertEquals(ErasureOutcome.Erased(emptyList()), erase(account.userId))
  }
}
