package ed.unicoach.coaching.report

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.report.ReportTestDb.SHARE_TOKEN_SECRET
import ed.unicoach.coaching.report.ReportTestDb.SHARE_URL_BASE
import ed.unicoach.coaching.report.ReportTestDb.serviceWith
import ed.unicoach.coaching.report.ReportTestDb.tokenOf
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CommitmentStatus
import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.ShareEventKind
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

  // ---------------------------------------------------------------------------
  // RFC 160: share events, recorded in the same transaction as the mutation
  // ---------------------------------------------------------------------------

  private fun eventsOf(studentId: StudentId): List<Pair<ShareEventKind, CostReportShareId?>> =
    ShareEventsDao
      .listByStudent(sqlSession, studentId)
      .getOrThrow()
      .map { it.kind to it.shareId }

  private fun liveShareId(studentId: StudentId): CostReportShareId =
    assertNotNull(CostReportSharesDao.findLiveByStudent(sqlSession, studentId).getOrThrow()).id

  @Test
  fun `each share outcome records exactly one event naming the right row`() =
    runBlocking {
      val studentId = createStudent()

      // Minted: the new row.
      service.share(studentId).getOrThrow()
      val mintedId = liveShareId(studentId)
      assertEquals(listOf(ShareEventKind.MINTED to mintedId), eventsOf(studentId))

      // Repeat: the SAME live row — the previously invisible case.
      service.share(studentId).getOrThrow()
      assertEquals(
        listOf(ShareEventKind.MINTED to mintedId, ShareEventKind.REPEAT to mintedId),
        eventsOf(studentId),
      )

      // Reissued: ONE event naming the NEW row; the stale row's interior
      // revocation is part of the reissue, never a separate 'revoked'.
      serviceWith(ROTATED_SECRET).share(studentId).getOrThrow()
      val reissuedId = liveShareId(studentId)
      assertNotEquals(mintedId, reissuedId)
      assertEquals(
        listOf(
          ShareEventKind.MINTED to mintedId,
          ShareEventKind.REPEAT to mintedId,
          ShareEventKind.REISSUED to reissuedId,
        ),
        eventsOf(studentId),
      )

      // Revoked: the revoked row.
      serviceWith(ROTATED_SECRET).revoke(studentId).getOrThrow()
      assertEquals(
        ShareEventKind.REVOKED to reissuedId,
        eventsOf(studentId).last(),
      )
      assertEquals(4, eventsOf(studentId).size)
    }

  @Test
  fun `Unavailable and NothingLive record no event`() =
    runBlocking {
      val studentId = createStudent()

      assertEquals(ShareCostReportOutcome.Unavailable, serviceWith(secret = null).share(studentId).getOrThrow())
      assertEquals(RevokeCostReportOutcome.NothingLive, service.revoke(studentId).getOrThrow())

      assertEquals(emptyList(), eventsOf(studentId), "nothing happened, so nothing is logged")
    }

  @Test
  fun `the event insert is atomic with the share mutation - a failed event rolls the mint back`() =
    runBlocking {
      val studentId = createStudent()
      // Make every share_events insert fail, so the only way a share row can
      // survive is if the event were recorded in a DIFFERENT transaction.
      CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.execute(
          """
          CREATE FUNCTION share_events_test_bomb() RETURNS trigger AS
          'BEGIN RAISE EXCEPTION ''share_events insert refused by test''; END;' LANGUAGE plpgsql;
          """.trimIndent(),
        )
        stmt.execute(
          "CREATE TRIGGER trigger_zz_share_events_test_bomb BEFORE INSERT ON share_events " +
            "FOR EACH ROW EXECUTE PROCEDURE share_events_test_bomb()",
        )
      }
      try {
        val result = service.share(studentId)
        assertTrue(result.isFailure, "the share must fail with its event, got [$result]")
        assertEquals(0, rowCount(studentId), "the mint must roll back with the failed event")
        assertEquals(emptyList(), eventsOf(studentId))
      } finally {
        CoachingTestDb.connection.createStatement().use { stmt ->
          stmt.execute("DROP TRIGGER trigger_zz_share_events_test_bomb ON share_events")
          stmt.execute("DROP FUNCTION share_events_test_bomb()")
        }
      }
    }

  // ---------------------------------------------------------------------------
  // RFC 160: the durable opt-out
  // ---------------------------------------------------------------------------

  @Test
  fun `stopOffers records one opted_out event and a second call records nothing new`() =
    runBlocking {
      val studentId = createStudent()

      val recorded = assertIs<StopCostReportOffersOutcome.Recorded>(service.stopOffers(studentId).getOrThrow())
      assertEquals(ShareEventKind.OPTED_OUT, recorded.event.kind, "the outcome carries the event the write produced")
      assertEquals(studentId, recorded.event.studentId)
      assertEquals(StopCostReportOffersOutcome.AlreadyStopped, service.stopOffers(studentId).getOrThrow())

      assertEquals(listOf(ShareEventKind.OPTED_OUT to null as CostReportShareId?), eventsOf(studentId))
      assertTrue(ShareEventsDao.hasOptOut(sqlSession, studentId).getOrThrow())
    }

  @Test
  fun `stopOffers leaves a live share alone and sharing keeps working after it`() =
    runBlocking {
      val studentId = createStudent()
      val link = linkOf(service.share(studentId).getOrThrow())

      service.stopOffers(studentId).getOrThrow()

      assertEquals(1, liveRowCount(studentId), "opting out of the suggestion revokes nothing")
      // A student who opted out of nudges can still ask to share: same link back.
      val again = linkOf(service.share(studentId).getOrThrow())
      assertEquals(link.url, again.url)
      // And needs no secret: the opt-out is about the log, not the token.
      val noSecretStudent = createStudent()
      val noSecretOutcome = serviceWith(secret = null).stopOffers(noSecretStudent).getOrThrow()
      assertTrue(noSecretOutcome is StopCostReportOffersOutcome.Recorded, "got [$noSecretOutcome]")
    }

  /** An open share_report commitment, as the synthesis nudge step writes it. */
  private fun insertOpenShareNudge(studentId: StudentId): Commitment =
    CommitmentsDao
      .create(
        sqlSession,
        NewCommitment(
          studentId = studentId,
          lens = CommitmentLens.SHARE_REPORT,
          disclosure = CommitmentDisclosure.EXPLICIT,
          statement = "suggest sharing the family cost report",
        ),
      ).getOrThrow()

  private fun shareNudges(studentId: StudentId): List<Commitment> =
    CommitmentsDao
      .listByStudent(sqlSession, studentId, limit = 100, offset = 0)
      .getOrThrow()
      .filter { it.lens == CommitmentLens.SHARE_REPORT }

  @Test
  fun `stopOffers drops an already-written open nudge so the next opener has nothing to surface`() =
    runBlocking {
      val studentId = createStudent()
      insertOpenShareNudge(studentId)

      assertIs<StopCostReportOffersOutcome.Recorded>(service.stopOffers(studentId).getOrThrow())

      val nudge = shareNudges(studentId).single()
      assertEquals(CommitmentStatus.DROPPED, nudge.status, "the opt-out must resolve the standing nudge")
      assertEquals(CostReportShareService.SHARE_OFFERS_OPTED_OUT_DROP_REASON, nudge.dropReason)
      assertEquals(
        emptyList(),
        CommitmentsDao.listOpenExplicitByStudent(sqlSession, studentId).getOrThrow(),
        "the opener reads open explicit commitments; the nudge must not be among them",
      )
    }

  @Test
  fun `the AlreadyStopped path sweeps a nudge that raced in after a first opt-out`() =
    runBlocking {
      val studentId = createStudent()
      assertIs<StopCostReportOffersOutcome.Recorded>(service.stopOffers(studentId).getOrThrow())
      // A sweep whose eligibility read predated the opt-out commits its nudge late.
      insertOpenShareNudge(studentId)

      assertEquals(StopCostReportOffersOutcome.AlreadyStopped, service.stopOffers(studentId).getOrThrow())

      assertTrue(
        shareNudges(studentId).none { it.status == CommitmentStatus.OPEN },
        "the second opt-out call must sweep the raced-in nudge",
      )
    }

  private companion object {
    /** A second key, long enough to be one: [ShareTokenSecret] refuses a short value at construction. */
    const val ROTATED_SECRET = "a-rotated-share-token-secret-long-enough"

    const val CONCURRENT_SHARES = 6
  }
}
