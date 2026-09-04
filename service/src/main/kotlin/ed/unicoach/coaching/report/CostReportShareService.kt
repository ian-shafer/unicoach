package ed.unicoach.coaching.report

import ed.unicoach.common.config.tokenLink
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CostReportShare
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.ShareEvent
import ed.unicoach.db.models.ShareEventKind
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The ONE failure the share door reports: the mint or the revoke could not be
 * performed.
 *
 * [ShareCostReportOutcome.Unavailable] is a configured state and stays a
 * SUCCESS; this is the write that did not happen. It names no driver, no
 * SQLSTATE and no connection — a caller has nothing else to decide — and its
 * cause carries the underlying fault for the log. Without it the published
 * `Result` failure type was "whatever the pool or the driver threw", so the next
 * caller that wants to branch on it would have to import SQL types to do so.
 */
class CostReportShareFailedException(
  cause: Throwable,
) : Exception("Family Cost Report share operation failed", cause)

/**
 * The outcome of asking to share the Family Cost Report.
 *
 * [Unavailable] is a configuration state, not a failure of this student's
 * request: without the share-token secret no link can be derived, so the tool
 * says so and the coach can tell the student plainly. Every other surface —
 * including a page already shared — keeps working, because viewing a report
 * needs no secret.
 */
sealed interface ShareCostReportOutcome {
  /**
   * A live link the student can send. WHICH case it is decides what the coach
   * says about it, and the three cases are disjoint by construction.
   *
   * Two independent booleans used to stand here, and one of their four
   * combinations was nonsense: "I handed back your old link, and your old link
   * is dead" compiled, and the coach could speak it. A case cannot be two
   * things at once, so it cannot say two things at once.
   *
   * The case carries the RAW TOKEN and the base it hangs off, not a formatted
   * string: the token is the value this outcome is really about, and a caller
   * that needs it (a test, a QR code, a second base) would otherwise have to
   * parse it back out of prose. [url] is derived from the pair by the one
   * composer both sides of the link contract read ([tokenLink]).
   */
  sealed interface Link : ShareCostReportOutcome {
    /** The raw share token — the credential itself, nowhere in the database. */
    val rawToken: String

    /** The configured public-web base this link hangs off. */
    val urlBase: String

    /** The link the parent opens: the configured base plus the raw token as a query parameter. */
    val url: String get() = tokenLink(urlBase, rawToken)
  }

  /**
   * The link the student already had, re-derived. A repeat ask returns the SAME
   * url, and the coach that says "the same link as before" is telling the truth
   * the student needs to hear — a new link would mean the one already in their
   * mother's text thread had stopped working.
   */
  data class Existing(
    override val rawToken: String,
    override val urlBase: String,
  ) : Link

  /** The student's first live link, or the first after they revoked one themselves. Nothing died for it. */
  data class Minted(
    override val rawToken: String,
    override val urlBase: String,
  ) : Link

  /**
   * A link minted after the server's share-token secret rotated: every link
   * issued before it is ALREADY dead, and the coach must say so rather than let
   * a link change hands silently.
   */
  data class Reissued(
    override val rawToken: String,
    override val urlBase: String,
  ) : Link

  /** No share-token secret is configured, so no link can be derived. */
  data object Unavailable : ShareCostReportOutcome
}

/**
 * What revoking found. Two outcomes, both ordinary: revoking twice is allowed,
 * and the second call is not an error.
 *
 * A bare `Boolean` stood here, which threw away the row the DAO hands back —
 * including the [CostReportShare.revokedAt] stamp, the one fact a caller could
 * state about WHEN the links died. The wire boolean is derived from the case at
 * the tool edge, where a boolean is what the coach reads.
 */
sealed interface RevokeCostReportOutcome {
  /** A live share existed and is now dead, with every link the student ever sent. [share] carries its stamp. */
  data class Revoked(
    val share: CostReportShare,
  ) : RevokeCostReportOutcome

  /** Nothing was live, so nothing changed — the second call, not an error. */
  data object NothingLive : RevokeCostReportOutcome
}

/**
 * What "never suggest sharing again" found (RFC 160). Two outcomes, both
 * ordinary: opting out twice is allowed, and the second call records nothing
 * new — "never" was already on file.
 */
sealed interface StopCostReportOffersOutcome {
  /** The first opt-out: [event] is the `opted_out` share event that now stands, forever. */
  data class Recorded(
    val event: ShareEvent,
  ) : StopCostReportOffersOutcome

  /** The student had already opted out; nothing was written. */
  data object AlreadyStopped : StopCostReportOffersOutcome
}

/**
 * Mint and revoke for the student's Family Cost Report share link (RFC 155).
 *
 * The link is a derived credential, not a stored one. The row keeps only
 * `SHA-256(token)`; the token itself is `HMAC-SHA256(secret, row id)`
 * ([ShareTokenDeriver]), so it can be re-derived for as long as the row and the
 * secret both stand, and it is nowhere in the database. That is what makes
 * RFC 155 D-B true rather than aspirational: asking to share twice returns THE
 * SAME link, so re-sending it cannot orphan a link a parent has already saved.
 *
 * At most one share per student is live at a time (the partial unique index),
 * so revoking is a promise about every link the student has ever sent.
 *
 * [deriver] is a CONSTRUCTOR PARAMETER and is null exactly when no share-token
 * secret is configured. Built by the composition root rather than here: a
 * service that reaches into its config to build its own collaborator cannot be
 * handed a different one, and "unset secret" then reads as the absent
 * collaborator it is.
 */
class CostReportShareService(
  private val database: Database,
  private val config: CostReportConfig,
  private val deriver: ShareTokenDeriver?,
) {
  /**
   * Returns the student's live link, minting one if they have none.
   *
   * The re-share path re-derives the token from the live row's id and CHECKS it
   * against the stored hash before handing it back — the check is what makes a
   * secret rotation visible instead of silent. A row whose token no longer
   * derives is revoked (it is already unusable: nobody holding that link can be
   * resolved by any hash we can now compute) and replaced, in the same
   * transaction, and the outcome says so.
   *
   * TWO SHARES AT ONCE is an ordinary outcome with a correct answer, not a
   * failed write. The one-live-share index refuses the loser's insert, and the
   * loser's student has a perfectly good link — the winner's. So the constraint
   * violation is answered by re-reading ONCE rather than by telling the coach
   * that sharing failed while a live link exists.
   */
  suspend fun share(studentId: StudentId): Result<ShareCostReportOutcome> {
    val deriver = this.deriver ?: return Result.success(ShareCostReportOutcome.Unavailable)
    return try {
      Result.success(mintOrReuse(studentId, deriver))
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a write that failed: the same
      // rule the cost read follows (CollegeCostService.getForStudent).
      throw e
    } catch (e: ConstraintViolationException) {
      // Only the one-live-share index refusal is the benign race with a
      // correct answer on re-read. Any OTHER violated constraint (e.g. one of
      // the share_events constraints, now inside this transaction) is a real
      // write failure and is reported as itself, not papered over by a re-read.
      if (e.constraint != ONE_LIVE_SHARE_INDEX) return Result.failure(CostReportShareFailedException(e))
      logger.info("share for student=[{}] lost the one-live-share race; re-reading", studentId.value, e)
      getOrCreateShareAfterConflict(studentId, deriver)
    } catch (e: Exception) {
      Result.failure(CostReportShareFailedException(e))
    }
  }

  /**
   * Revokes the student's live share, if any. Returns the REVOKED ROW inside
   * [RevokeCostReportOutcome.Revoked] — the share carrying its stamped
   * [CostReportShare.revokedAt] — and [RevokeCostReportOutcome.NothingLive] when
   * there was nothing live, so a second call is an ordinary absence rather than
   * an error. Needs no secret: revoking is about the row, not about the token it
   * stands for.
   */
  suspend fun revoke(studentId: StudentId): Result<RevokeCostReportOutcome> =
    asShareResult {
      database.withConnection { session ->
        val revoked = revokeLive(session, studentId)
        if (revoked == null) {
          // NothingLive records nothing: nothing happened (RFC 160).
          RevokeCostReportOutcome.NothingLive
        } else {
          // The event rides the same transaction as the revoke it describes.
          ShareEventsDao.record(session, studentId, revoked.id, ShareEventKind.REVOKED).getOrThrow()
          RevokeCostReportOutcome.Revoked(revoked)
        }
      }
    }

  /**
   * Records the student's durable "never suggest sharing again" (RFC 160): one
   * `opted_out` share event, read by the synthesis share-nudge step as a
   * permanent suppression. Idempotent — a second call finds the event already
   * on file and writes nothing. It touches NO share row: a live link stays
   * live, and `share_cost_report` keeps working — the student opted out of the
   * suggestion, not of the ability. Needs no secret (it is about the log, not
   * the token).
   */
  suspend fun stopOffers(studentId: StudentId): Result<StopCostReportOffersOutcome> =
    asShareResult {
      database.withConnection { session ->
        dropOpenShareNudges(session, studentId)
        recordOptOutIfNew(session, studentId)
      }
    }

  /**
   * An already-written nudge must not outlive the opt-out: drops any open
   * share_report commitment in the caller's transaction, so the next opener
   * has nothing to surface. Runs on the AlreadyStopped path too, catching a
   * nudge that raced in after a first opt-out.
   */
  private fun dropOpenShareNudges(
    session: SqlSession,
    studentId: StudentId,
  ) {
    CommitmentsDao
      .listOpenByStudent(session, studentId)
      .getOrThrow()
      .filter { it.lens == CommitmentLens.SHARE_REPORT }
      .forEach { open ->
        CommitmentsDao.drop(session, open.id, SHARE_OFFERS_OPTED_OUT_DROP_REASON).getOrThrow()
      }
  }

  /** Writes the opted_out event unless "never" is already on file, and says which it was. */
  private fun recordOptOutIfNew(
    session: SqlSession,
    studentId: StudentId,
  ): StopCostReportOffersOutcome =
    if (ShareEventsDao.hasOptOut(session, studentId).getOrThrow()) {
      StopCostReportOffersOutcome.AlreadyStopped
    } else {
      StopCostReportOffersOutcome.Recorded(ShareEventsDao.recordOptOut(session, studentId).getOrThrow())
    }

  /**
   * The service-wide failure envelope: cancellation is the caller unwinding and
   * propagates; every other failure is wrapped as [CostReportShareFailedException].
   * ([share] keeps its own catch chain for the one-live-share race and delegates
   * the same fallthrough shape.)
   */
  private inline fun <T> asShareResult(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(CostReportShareFailedException(e))
    }

  /** The read-then-mint, in ONE transaction: the whole of what a share request does. */
  private suspend fun mintOrReuse(
    studentId: StudentId,
    deriver: ShareTokenDeriver,
  ): ShareCostReportOutcome =
    database.withConnection { session ->
      when (val share = liveShareFor(session, studentId, deriver)) {
        LiveShare.None -> mintLink(session, studentId, deriver, stale = null)
        is LiveShare.Reusable -> reuseLink(session, studentId, share)
        is LiveShare.StaleToken -> mintLink(session, studentId, deriver, stale = share.share)
      }
    }

  /**
   * Answers a repeat ask (RFC 160): records the `repeat` event — the previously
   * invisible case: a repeat touches no share row, so the event is its only
   * trace, in the same transaction as the read that answered it — and returns
   * the link the student already holds.
   */
  private fun reuseLink(
    session: SqlSession,
    studentId: StudentId,
    share: LiveShare.Reusable,
  ): ShareCostReportOutcome.Existing {
    ShareEventsDao.record(session, studentId, share.share.id, ShareEventKind.REPEAT).getOrThrow()
    return ShareCostReportOutcome.Existing(share.rawToken, config.shareUrlBase)
  }

  /**
   * The second read after the one-live-share index refused our insert. It is one
   * retry and no more: if it also fails, the failure is reported as itself.
   */
  private suspend fun getOrCreateShareAfterConflict(
    studentId: StudentId,
    deriver: ShareTokenDeriver,
  ): Result<ShareCostReportOutcome> = asShareResult { mintOrReuse(studentId, deriver) }

  /**
   * What the student's live row is worth right now: nothing, a link, or a row
   * whose token no longer derives.
   *
   * The stale case used to be flattened into the same null as "no live row", so
   * the mint path re-inferred the rotation from a SECOND query — and under READ
   * COMMITTED a row committed between the two reads set the same flag, which
   * made both the [ShareCostReportOutcome.Reissued] outcome and its warning
   * claims the code had not checked. Here the reason is carried, with the row.
   */
  private fun liveShareFor(
    session: SqlSession,
    studentId: StudentId,
    deriver: ShareTokenDeriver,
  ): LiveShare {
    val live = CostReportSharesDao.findLiveByStudent(session, studentId).getOrThrow() ?: return LiveShare.None
    val rawToken = deriver.derive(live.id)
    return if (TokenHash.fromRawToken(rawToken) == live.tokenHash) {
      LiveShare.Reusable(rawToken, live)
    } else {
      LiveShare.StaleToken(live)
    }
  }

  /**
   * Mints the student's link: revoke whatever was live, insert a fresh row, and
   * derive the token from the id that row was inserted under.
   *
   * [stale] is non-null exactly when the live row's token no longer derives —
   * decided by [liveShareFor], which had the row in hand — so it can only be
   * the secret-rotation case, and the outcome says the earlier link is dead.
   */
  private fun mintLink(
    session: SqlSession,
    studentId: StudentId,
    deriver: ShareTokenDeriver,
    stale: CostReportShare?,
  ): ShareCostReportOutcome.Link {
    // The revoke happens ONLY for a stale row. There is nothing to revoke in the
    // None case by construction -- the read that produced it found no live row --
    // and revoking unconditionally made a lost race silently kill the WINNER's
    // link: the loser's blocked `UPDATE ... WHERE revoked_at IS NULL` woke up
    // after the winner committed and revoked the row a parent already held.
    // Without it the loser's insert simply hits the one-live-share index, and
    // [share] answers that by re-reading.
    stale?.let {
      warnStaleShareRevoked(it)
      revokeLive(session, studentId)
    }
    val id = CostReportSharesDao.nextId(session).getOrThrow()
    val rawToken = deriver.derive(id)
    CostReportSharesDao
      .create(
        session,
        NewCostReportShare(id = id, studentId = studentId, tokenHash = TokenHash.fromRawToken(rawToken)),
      ).getOrThrow()
    // The event rides the mint's own transaction (RFC 160). The stale row's
    // revocation inside a reissue is part of the reissue, not a separate
    // 'revoked' event: one thing happened to the student's link, so one row.
    // Mint-vs-reissue is decided ONCE, binding the event kind and the outcome
    // as a pair so the correspondence cannot drift.
    val (kind, outcome) =
      if (stale == null) {
        ShareEventKind.MINTED to ShareCostReportOutcome.Minted(rawToken, config.shareUrlBase)
      } else {
        ShareEventKind.REISSUED to ShareCostReportOutcome.Reissued(rawToken, config.shareUrlBase)
      }
    ShareEventsDao.record(session, studentId, id, kind).getOrThrow()
    return outcome
  }

  /**
   * Says the rotation out loud, WITH THE ROW IN HAND: which share, whose, and
   * when it was minted are the whole of what an operator can act on — a single
   * stale row is one corrupt row, a run of them across students is a secret
   * rotation.
   */
  private fun warnStaleShareRevoked(stale: CostReportShare) {
    logger.warn(
      "share row no longer derives its stored token hash; revoking and reissuing (share-token secret rotated?): " +
        "share_id=[{}] student_id=[{}] created_at=[{}]",
      stale.id.value,
      stale.studentId.value,
      stale.createdAt,
    )
  }

  /** The compare-and-swap revoke: no live row is an absence the DAO already reports as null. */
  private fun revokeLive(
    session: SqlSession,
    studentId: StudentId,
  ): CostReportShare? = CostReportSharesDao.revokeLive(session, studentId).getOrThrow()

  companion object {
    private val logger = LoggerFactory.getLogger(CostReportShareService::class.java)

    /** The 0073 partial unique index whose refusal means "the winner already minted this student's link". */
    private const val ONE_LIVE_SHARE_INDEX = "cost_report_shares_one_live_per_student_idx"

    /**
     * Persisted `commitments.drop_reason` token for the opt-out sweep, in the
     * same machine-matchable vocabulary as SynthesisService's `stale_basis`.
     */
    const val SHARE_OFFERS_OPTED_OUT_DROP_REASON = "share_offers_opted_out"
  }
}

/**
 * The student's one live share row, as the mint path needs it.
 *
 * Private to this file: it is how the read hands its REASON to the write, and it
 * never crosses the service boundary.
 */
private sealed interface LiveShare {
  /** The student has no live share row. */
  data object None : LiveShare

  /** The live row's stored hash still derives, so [rawToken] is the link the student already holds. [share] is that row. */
  data class Reusable(
    val rawToken: String,
    val share: CostReportShare,
  ) : LiveShare

  /** The live row's stored hash no longer derives: a link nothing can resolve, carried so it can be named. */
  data class StaleToken(
    val share: CostReportShare,
  ) : LiveShare
}
