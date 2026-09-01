package ed.unicoach.coaching.report

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.report.ReportTestDb.SHARE_TOKEN_SECRET
import ed.unicoach.coaching.report.ReportTestDb.SHARE_URL_BASE
import ed.unicoach.coaching.report.ReportTestDb.serviceWith
import ed.unicoach.coaching.report.ReportTestDb.tokenOf
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The mint/revoke half of RFC 155, against a real database: the guarantees the
 * page and the two chat tools both rest on. The raw token is asserted to be
 * ABSENT from the row it created — the one property a share link cannot be
 * allowed to lose.
 */
class CostReportShareServiceTest {
  @BeforeEach
  fun resetDatabase() {
    ReportTestDb.reset()
  }

  private val sqlSession = ReportTestDb.sqlSession

  private val service = serviceWith()

  /** The link of a successful share, or a failure of the test if sharing declined. */
  private fun linkOf(outcome: ShareCostReportOutcome): ShareCostReportOutcome.Link =
    outcome as? ShareCostReportOutcome.Link ?: fail("expected a link, got [$outcome]")

  /** Whether a revoke found something live, read off the case rather than from a bare bit. */
  private fun wasRevoked(outcome: RevokeCostReportOutcome): Boolean = outcome is RevokeCostReportOutcome.Revoked

  private fun createStudent(): StudentId = ReportTestDb.createStudent("crs")

  private fun rowCount(studentId: StudentId): Int = countWhere("SELECT COUNT(*) FROM cost_report_shares WHERE student_id = ?", studentId)

  private fun liveRowCount(studentId: StudentId): Int =
    countWhere("SELECT COUNT(*) FROM cost_report_shares WHERE student_id = ? AND revoked_at IS NULL", studentId)

  private fun countWhere(
    sql: String,
    studentId: StudentId,
  ): Int =
    CoachingTestDb.connection.prepareStatement(sql).use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  @Test
  fun `minting returns a link whose raw token is nowhere in the row it created`() =
    runBlocking {
      val studentId = createStudent()

      val link = linkOf(service.share(studentId).getOrThrow())

      assertTrue(link.url.startsWith("$SHARE_URL_BASE?token="), "the link must be the configured base plus a token param: [${link.url}]")
      val rawToken = tokenOf(link.url)
      assertTrue(rawToken.isNotEmpty(), "the link must carry a token")
      assertIs<ShareCostReportOutcome.Minted>(link, "a first share mints and kills nothing")

      // The hash resolves the token, and the raw string appears in no column of
      // the row: a text scan of the whole row, not just of token_hash, so a
      // future column that quietly persisted the secret fails here.
      val live = CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(rawToken)).getOrThrow()
      assertEquals(studentId, assertNotNull(live).studentId)
      val wholeRow = "SELECT cost_report_shares::text FROM cost_report_shares WHERE student_id = ?"
      CoachingTestDb.connection.prepareStatement(wholeRow).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "the row must exist")
          assertFalse(rs.getString(1).contains(rawToken), "the raw token must never be stored: [${rs.getString(1)}]")
        }
      }
    }

  @Test
  fun `sharing twice returns the same link and mints nothing`() =
    runBlocking {
      val studentId = createStudent()

      val first = linkOf(service.share(studentId).getOrThrow())
      val second = linkOf(service.share(studentId).getOrThrow())

      // The D-B promise: a parent's saved link cannot be orphaned by the student
      // asking "what was that link again?".
      assertEquals(first.url, second.url, "the same row derives the same token, so re-sharing hands back the same link")
      assertIs<ShareCostReportOutcome.Existing>(second, "nothing was replaced, so the case must say \"the same link\"")
      assertEquals(1, liveRowCount(studentId), "a repeat share must not mint a second row")
      assertEquals(1, rowCount(studentId), "and must not leave a revoked one behind either")
    }

  @Test
  fun `a rotated secret revokes the stale share, mints a fresh link and says the old one is dead`() =
    runBlocking {
      val studentId = createStudent()
      val before = linkOf(service.share(studentId).getOrThrow())

      // Rotation: the token no longer derives to the stored hash, so every link
      // issued under the old key is already unreachable.
      val after = linkOf(serviceWith(ROTATED_SECRET).share(studentId).getOrThrow())

      assertNotEquals(before.url, after.url, "a new key derives a new token")
      assertIs<ShareCostReportOutcome.Reissued>(after, "the coach must be able to say the old link stopped working")
      assertEquals(1, liveRowCount(studentId), "still at most one live share")
      assertNull(
        CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(tokenOf(before.url))).getOrThrow(),
        "the link issued under the old key resolves to nothing",
      )
      // Ends on a Unit-returning assertion on purpose: an expression body whose
      // last call is `assertNotNull` infers a non-Unit return type, and JUnit
      // drops a non-void @Test at discovery -- the test would compile, pass
      // review, and never run.
      assertTrue(
        CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(tokenOf(after.url))).getOrThrow() != null,
        "the new link resolves",
      )
    }

  @Test
  fun `an unconfigured secret declines instead of failing, and writes nothing`() =
    runBlocking {
      val studentId = createStudent()

      val outcome = serviceWith(secret = null).share(studentId).getOrThrow()

      assertEquals(ShareCostReportOutcome.Unavailable, outcome, "a missing secret is a decline, not a thrown read")
      assertEquals(0, rowCount(studentId), "a decline must not leave a row behind")
      // Revoking still works without a secret: it is about the row, not the token.
      assertFalse(wasRevoked(serviceWith(secret = null).revoke(studentId).getOrThrow()))
    }

  @Test
  fun `revoking kills the live link and is safe to repeat`() =
    runBlocking {
      val studentId = createStudent()
      val link = linkOf(service.share(studentId).getOrThrow())

      val revoked = service.revoke(studentId).getOrThrow()
      assertIs<RevokeCostReportOutcome.Revoked>(revoked, "a live link was revoked")
      assertTrue(revoked.share.revokedAt != null, "the revoked row carries the stamp that says when the links died")
      assertEquals(0, liveRowCount(studentId), "nothing is live after a revoke")
      assertNull(
        CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(tokenOf(link.url))).getOrThrow(),
        "a revoked token resolves to nothing, exactly like an unknown one",
      )
      assertEquals(
        RevokeCostReportOutcome.NothingLive,
        service.revoke(studentId).getOrThrow(),
        "a second revoke finds nothing live and is not an error",
      )
    }

  @Test
  fun `sharing after a revoke mints a different token and the revoked one stays dead`() =
    runBlocking {
      val studentId = createStudent()
      val first = linkOf(service.share(studentId).getOrThrow())
      service.revoke(studentId).getOrThrow()

      val second = linkOf(service.share(studentId).getOrThrow())

      assertNotEquals(tokenOf(first.url), tokenOf(second.url), "a fresh row, so a fresh secret - never the revoked one")
      assertIs<ShareCostReportOutcome.Minted>(second, "the student revoked it themselves; there is nothing to announce")
      assertNull(
        CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(tokenOf(first.url))).getOrThrow(),
        "revocation is forever",
      )
    }

  @Test
  fun `two students never share a link`() =
    runBlocking {
      val one = createStudent()
      val other = createStudent()

      val oneLink = linkOf(service.share(one).getOrThrow())
      val otherLink = linkOf(service.share(other).getOrThrow())

      assertNotEquals(oneLink.url, otherLink.url)
      assertEquals(
        one,
        CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(tokenOf(oneLink.url))).getOrThrow()?.studentId,
      )
      assertEquals(
        other,
        CostReportSharesDao.findLiveByTokenHash(sqlSession, TokenHash.fromRawToken(tokenOf(otherLink.url))).getOrThrow()?.studentId,
      )
    }

  /**
   * TWO SHARES AT ONCE.
   *
   * The one-live-share partial unique index refuses the loser's insert, and the
   * loser's student HAS a link — the winner's. Before the re-read, the coach was
   * told "cost report share failed" while a perfectly good link was live.
   *
   * Every call is asserted to succeed and to hand back the SAME url, so the
   * assertion is total whether or not a given run actually collides; the
   * barrier makes the collision the ordinary case rather than a lucky one.
   */
  @Test
  fun `concurrent shares all return the one live link instead of a failed write`() =
    runBlocking {
      val studentId = createStudent()
      val start = CompletableDeferred<Unit>()

      val outcomes =
        coroutineScope {
          (1..CONCURRENT_SHARES)
            .map {
              async(Dispatchers.IO) {
                start.await()
                service.share(studentId)
              }
            }.also { start.complete(Unit) }
            .awaitAll()
        }

      val urls = outcomes.map { linkOf(it.getOrThrow()).url }.toSet()
      assertEquals(1, urls.size, "every concurrent share must hand back the one live link: [$urls]")
      assertEquals(1, liveRowCount(studentId), "the index still permits exactly one live share")
      assertEquals(1, rowCount(studentId), "a lost race must not leave a revoked row behind")
    }

  private companion object {
    /** A second key, long enough to be one: [ShareTokenSecret] refuses a short value at construction. */
    const val ROTATED_SECRET = "a-rotated-share-token-secret-long-enough"

    const val CONCURRENT_SHARES = 6
  }
}
