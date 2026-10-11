package ed.unicoach.web.report

import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.web.WebAuthTestSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The in-process report read against the real database (RFC 252): a share left
 * LIVE for a closed account still renders nothing, whichever of its two rows is
 * the closed one. The close revokes the share as well; this pins the read's own
 * check, so no other path can leave a closed account's college list on a
 * parent's saved link.
 */
class ServiceCostReportSourceTest {
  private val database = WebAuthTestSupport.database
  private val source = ServiceCostReportSource(database, CollegeCostService(database))

  /** The mint shape: 43 base64url characters, the 32 bytes of a digest. */
  private fun rawToken(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

  /** Seeds a student with a LIVE share, proves it renders, and returns the raw token. */
  private suspend fun liveShare(studentId: StudentId): String {
    val raw = rawToken()
    database.withConnection { session ->
      val id = CostReportSharesDao.nextId(session).getOrThrow()
      CostReportSharesDao.create(session, NewCostReportShare(id, studentId, TokenHash.fromRawToken(raw))).getOrThrow()
    }
    assertIs<CostReportOutcome.Found>(source.getByShareToken(raw).getOrThrow(), "a live account's live share renders")
    return raw
  }

  @Test
  fun `a live share of a closed student row does not render`(): Unit =
    runBlocking {
      val studentId = WebAuthTestSupport.seedStudent(WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail()))
      val raw = liveShare(studentId)

      database.withConnection { session ->
        val student = StudentsDao.findById(session, studentId, SoftDeleteScope.ACTIVE).getOrThrow()
        StudentsDao.delete(session, studentId, student.version).getOrThrow()
      }

      assertEquals(CostReportOutcome.NotFound(MissReason.ACCOUNT_CLOSED), source.getByShareToken(raw).getOrThrow())
    }

  @Test
  fun `a live share of a student whose users row alone is closed does not render`(): Unit =
    runBlocking {
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val raw = liveShare(WebAuthTestSupport.seedStudent(user))

      // The operator admin's shape: the users row closed, the students row left live.
      database.withConnection { session ->
        val live = UsersDao.findById(session, user.id, SoftDeleteScope.ACTIVE).getOrThrow()
        UsersDao.delete(session, user.id, live.version).getOrThrow()
      }

      assertEquals(CostReportOutcome.NotFound(MissReason.ACCOUNT_CLOSED), source.getByShareToken(raw).getOrThrow())
    }
}
