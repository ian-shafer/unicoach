package ed.unicoach.family

import ed.unicoach.coaching.report.ReportShareLookup
import ed.unicoach.coaching.report.ReportShares
import ed.unicoach.coaching.report.ShareToken
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ActiveSupporterLinkException
import ed.unicoach.db.dao.ClosedStudentLinkException
import ed.unicoach.db.dao.ClosedSupporterLinkException
import ed.unicoach.db.dao.ShareEventsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterAlreadyExistsException
import ed.unicoach.db.dao.SupporterLinkRequestsDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.CostReportShare
import ed.unicoach.db.models.LinkedSupporter
import ed.unicoach.db.models.NewSupporterLinkRequest
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterLinkRequest
import ed.unicoach.db.models.SupporterLinkRequestId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import org.slf4j.LoggerFactory

/**
 * What the report page offers its reader (RFC 254), decided by the VIEWER and
 * never by a fact about the student. It carries no student id, so a renderer
 * holding it still cannot print one (RFC 155 D-F).
 */
enum class ClaimView {
  /** Signed out: create an account, or sign in. */
  SIGN_UP,

  /** Signed in, not connected, nothing pending: ask to connect. */
  ASK_TO_CONNECT,

  /** Signed in, a request is waiting for the student. */
  PENDING,

  /** Nothing to offer: the student themselves, a reader whose supporter row is closed, already connected, or declined before. */
  NONE,
}

/** What a claim from a report link did. */
sealed interface ClaimOutcome {
  /**
   * A new pending request. [awaitingVerification] when the claimant's own email
   * is not verified yet, so the student has been told nothing.
   */
  data class Requested(
    val awaitingVerification: Boolean,
  ) : ClaimOutcome

  /** This pair already had a pending request; nothing new was written. */
  data class AlreadyRequested(
    val awaitingVerification: Boolean,
  ) : ClaimOutcome

  /** The claimant already holds a live link to this student. */
  data object AlreadyConnected : ClaimOutcome

  /** No request was made. [reason] is for the LOG alone: every refusal reads the same to the claimant. */
  data class NotClaimed(
    val reason: ClaimRefusal,
  ) : ClaimOutcome
}

/** Why a claim made no request. Log only, never rendered. */
enum class ClaimRefusal {
  /** Not a token this server could have minted (blank included). */
  MALFORMED_TOKEN,

  /** No live share carries the token: unknown or revoked. */
  NO_LIVE_SHARE,

  /** The share is live and its account is closed. */
  ACCOUNT_CLOSED,

  /** The claimant is the student who shared the report. */
  OWN_REPORT,

  /** The student declined this claimant before; a decline is final for the pair. */
  DECLINED_BEFORE,

  /** The claimant's own supporter row is closed, so no supporter can be minted for them. */
  SUPPORTER_CLOSED,
}

/** What the student's approve did. */
sealed interface ApproveRequestOutcome {
  /** The request is approved and [supporter] is the live link it stands on. */
  data class Approved(
    val supporter: LinkedSupporter,
  ) : ApproveRequestOutcome

  /**
   * Not a request this student can act on — what
   * [ed.unicoach.db.dao.SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate]
   * answers null for, or a closed endpoint the link insert refused. One answer
   * on the wire; [reason] is for the log alone.
   */
  data class NotFound(
    val reason: RequestNotFoundReason,
  ) : ApproveRequestOutcome
}

/** Why an approve found nothing to act on. Log only, never rendered. */
enum class RequestNotFoundReason {
  /** The locked read found no pending request this student can see. */
  NOT_VISIBLE,

  /** The student's own row is closed; the link insert refused it. */
  STUDENT_CLOSED,

  /** The claimant's supporter row closed between the read and the link insert. */
  SUPPORTER_CLOSED,
}

/** What the student's decline did. */
sealed interface DeclineRequestOutcome {
  data object Declined : DeclineRequestOutcome

  /** What [ed.unicoach.db.dao.SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate] answers null for. */
  data object NotFound : DeclineRequestOutcome
}

/**
 * A parent's request to connect with the student whose Family Cost Report link
 * they opened (RFC 254, brief 0014 D19) — the ONE writer of
 * `supporter_link_requests`.
 *
 * Claiming never links anybody. It makes a request the student approves in one
 * tap, because the link is not proof of who sent it: it may have been
 * forwarded. The claimant gets their own account either way.
 */
class SupporterLinkRequestService(
  private val database: Database,
  private val supporterService: SupporterService,
  private val notice: LinkRequestedNotice,
) {
  private val logger = LoggerFactory.getLogger(SupporterLinkRequestService::class.java)

  /**
   * [userId] asks to connect with the student whose report [rawToken] opens, in
   * its own transaction.
   *
   * The claimant's `supporters` row is minted even when no request is made (a
   * pending or declined pair, or an existing link), so the account lands in the
   * parent home: signing up succeeds on its own (D18). A CLOSED row is read, not
   * minted over, and answers [ClaimRefusal.SUPPORTER_CLOSED].
   *
   * A concurrent mint of that row — two claims, or a claim racing
   * `POST /api/v1/supporters` — rolls this transaction back; one retry reads
   * the row that won.
   */
  suspend fun claimFromReport(
    userId: UserId,
    rawToken: String,
  ): Result<ClaimOutcome> =
    attemptClaim(userId, rawToken).recoverCatching { failure ->
      if (failure !is SupporterAlreadyExistsException) throw failure
      logger.info("report claim lost a race minting the claimant's supporter row, retrying once: userId=[{}]", userId.asString, failure)
      attemptClaim(userId, rawToken).getOrElse { throw it.also { retry -> retry.addSuppressed(failure) } }
    }

  private suspend fun attemptClaim(
    userId: UserId,
    rawToken: String,
  ): Result<ClaimOutcome> =
    runCatching {
      if (!ShareToken.isWellFormed(rawToken)) return@runCatching ClaimOutcome.NotClaimed(ClaimRefusal.MALFORMED_TOKEN)
      database.withConnection { session ->
        val share =
          when (val lookup = ReportShares.lookup(session, TokenHash.fromRawToken(rawToken))) {
            is ReportShareLookup.Open -> lookup.share
            ReportShareLookup.NoLiveShare -> return@withConnection ClaimOutcome.NotClaimed(ClaimRefusal.NO_LIVE_SHARE)
            ReportShareLookup.AccountClosed -> return@withConnection ClaimOutcome.NotClaimed(ClaimRefusal.ACCOUNT_CLOSED)
          }
        refusalBeforeMint(session, userId, share.studentId)?.let { return@withConnection ClaimOutcome.NotClaimed(it) }
        requestFrom(session, supporterService.getOrCreate(session, userId), userId, share)
      }
    }

  /**
   * The refusals decided before a `supporters` row is minted for [userId] — the
   * claim's, and so the report page's: [claimView] offers nothing where one applies.
   */
  private fun refusalBeforeMint(
    session: SqlSession,
    userId: UserId,
    studentId: StudentId,
  ): ClaimRefusal? =
    when {
      isOwnReport(session, userId, studentId) -> ClaimRefusal.OWN_REPORT
      isSupporterClosed(session, userId) -> ClaimRefusal.SUPPORTER_CLOSED
      else -> null
    }

  /** A CLOSED `supporters` row is read, not minted over: an ALL-scope read, where ACTIVE would see "never minted". */
  private fun isSupporterClosed(
    session: SqlSession,
    userId: UserId,
  ): Boolean = SupportersDao.findByUserId(session, userId, SoftDeleteScope.ALL).getOrThrow()?.deletedAt != null

  private fun isAwaitingVerification(
    session: SqlSession,
    userId: UserId,
  ): Boolean = UsersDao.findById(session, userId, SoftDeleteScope.ACTIVE).getOrThrow().emailVerifiedAt == null

  /** What [supporter]'s claim on [share] does, by where the pair stands. */
  private fun requestFrom(
    session: SqlSession,
    supporter: Supporter,
    userId: UserId,
    share: CostReportShare,
  ): ClaimOutcome {
    val awaitingVerification = isAwaitingVerification(session, userId)
    return when (standingOf(session, supporter.id, share.studentId)) {
      PairStanding.CONNECTED -> ClaimOutcome.AlreadyConnected
      PairStanding.PENDING -> ClaimOutcome.AlreadyRequested(awaitingVerification)
      PairStanding.DECLINED -> ClaimOutcome.NotClaimed(ClaimRefusal.DECLINED_BEFORE)
      PairStanding.OPEN -> insertRequest(session, supporter.id, share, awaitingVerification)
    }
  }

  private fun insertRequest(
    session: SqlSession,
    supporterId: SupporterId,
    share: CostReportShare,
    awaitingVerification: Boolean,
  ): ClaimOutcome {
    val request =
      SupporterLinkRequestsDao
        .create(session, NewSupporterLinkRequest(supporterId, share.studentId, share.id))
        .getOrThrow()
        // A concurrent claim for the same pair won the pending-pair index.
        ?: return ClaimOutcome.AlreadyRequested(awaitingVerification)
    if (!awaitingVerification) notifyStudent(session, request)
    return ClaimOutcome.Requested(awaitingVerification)
  }

  /**
   * What the report page offers [viewer] for [studentId]'s report, in the page's
   * own session. Viewer state only: the answer never depends on a fact about the
   * student but who the viewer is to them.
   */
  fun claimView(
    session: SqlSession,
    viewer: UserId?,
    studentId: StudentId,
  ): ClaimView {
    if (viewer == null) return ClaimView.SIGN_UP
    if (refusalBeforeMint(session, viewer, studentId) != null) return ClaimView.NONE
    val supporter = SupportersDao.findByUserId(session, viewer).getOrThrow() ?: return ClaimView.ASK_TO_CONNECT
    return when (standingOf(session, supporter.id, studentId)) {
      PairStanding.CONNECTED, PairStanding.DECLINED -> ClaimView.NONE
      PairStanding.PENDING -> ClaimView.PENDING
      PairStanding.OPEN -> ClaimView.ASK_TO_CONNECT
    }
  }

  /** Whether [userId] is the student who owns [studentId] — their own report offers nothing. */
  private fun isOwnReport(
    session: SqlSession,
    userId: UserId,
    studentId: StudentId,
  ): Boolean = StudentsDao.findById(session, studentId, SoftDeleteScope.ALL).getOrThrow().userId == userId

  /**
   * Where [supporterId] stands with [studentId] — the ONE ladder the report's
   * offer ([claimView]) and a claim ([attemptClaim]) both read, so the page never
   * offers what the claim refuses.
   */
  private fun standingOf(
    session: SqlSession,
    supporterId: SupporterId,
    studentId: StudentId,
  ): PairStanding =
    when {
      SupporterStudentLinksDao.findActive(session, supporterId, studentId).getOrThrow() != null -> PairStanding.CONNECTED
      SupporterLinkRequestsDao.findPendingPair(session, supporterId, studentId).getOrThrow() != null -> PairStanding.PENDING
      SupporterLinkRequestsDao.hasDeclinedPair(session, supporterId, studentId).getOrThrow() -> PairStanding.DECLINED
      else -> PairStanding.OPEN
    }

  /** Where a supporter stands with a student; see [standingOf]. */
  private enum class PairStanding { CONNECTED, PENDING, DECLINED, OPEN }

  /**
   * [userId]'s email was just verified, inside the verifying transaction: tell
   * the student about each of their pending requests that was waiting on it.
   *
   * Until then a request is invisible to the student and sends nothing.
   * Without this gate anyone could register as `mum@example.com` and the student
   * would approve an impostor.
   */
  fun releaseOnVerified(
    session: SqlSession,
    userId: UserId,
  ) {
    val supporter = SupportersDao.findByUserId(session, userId).getOrThrow() ?: return
    SupporterLinkRequestsDao
      .listUnnotifiedForSupporter(session, supporter.id)
      .getOrThrow()
      .forEach { notifyStudent(session, it) }
  }

  /**
   * Decides the student's notice for [request], once. A student who opted out
   * of share offers (`stop_cost_report_offers`) gets no email — the request is
   * still listed — and either way `notified_at` is set: it records the
   * DECISION, not a send, so the decision is never made twice.
   */
  private fun notifyStudent(
    session: SqlSession,
    request: SupporterLinkRequest,
  ) {
    // Another run decided first: nothing to do.
    SupporterLinkRequestsDao.markNotified(session, request.id).getOrThrow() ?: return
    if (ShareEventsDao.hasOptOut(session, request.studentId).getOrThrow()) {
      logger.info("Link-requested email for request [{}] not sent: the student opted out of share offers", request.id.asString)
      return
    }
    notice.tellStudent(session, request)
  }

  /**
   * The student approves [requestId]: a live link with the catch-all
   * relationship, the same `findActive ?: create` write an invite accept makes,
   * and the request resolved to it — one transaction.
   *
   * The request is LOCKED first and resolved last, with the link id in the same
   * statement, because `supporter_link_requests_link_check` admits no approved
   * row without its link.
   */
  suspend fun approve(
    studentId: StudentId,
    requestId: SupporterLinkRequestId,
  ): Result<ApproveRequestOutcome> =
    attemptApprove(studentId, requestId).recoverCatching { failure ->
      when (failure) {
        // A concurrent accept of an invite linked this pair between our read and
        // our insert. One retry finds that link and resolves onto it.
        is ActiveSupporterLinkException -> {
          logger.info("request approve lost a race on the active-link index, retrying once: requestId=[{}]", requestId.asString, failure)
          attemptApprove(studentId, requestId).getOrElse { throw it.also { retry -> retry.addSuppressed(failure) } }
        }

        // A closed endpoint is a refusal, answered like every other request the
        // student cannot act on, and carrying which endpoint it was.
        is ClosedStudentLinkException -> {
          logger.info("request approve refused, the student is closed: requestId=[{}]", requestId.asString, failure)
          ApproveRequestOutcome.NotFound(RequestNotFoundReason.STUDENT_CLOSED)
        }

        is ClosedSupporterLinkException -> {
          logger.info("request approve refused, the claimant's supporter row is closed: requestId=[{}]", requestId.asString, failure)
          ApproveRequestOutcome.NotFound(RequestNotFoundReason.SUPPORTER_CLOSED)
        }

        else -> {
          throw failure
        }
      }
    }

  private suspend fun attemptApprove(
    studentId: StudentId,
    requestId: SupporterLinkRequestId,
  ): Result<ApproveRequestOutcome> =
    runCatching {
      database.withConnection { session ->
        val pending =
          SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate(session, studentId, requestId).getOrThrow()
            ?: return@withConnection ApproveRequestOutcome.NotFound(RequestNotFoundReason.NOT_VISIBLE)
        val link =
          SupporterStudentLinksDao.findActive(session, pending.supporterId, studentId).getOrThrow()
            ?: SupporterStudentLinksDao
              .create(session, NewSupporterStudentLink(pending.supporterId, studentId, SupporterRelationship.UNSTATED))
              .getOrThrow()
        checkNotNull(
          SupporterLinkRequestsDao.approve(session, requestId, studentId, link.id).getOrThrow(),
        ) { "request [${requestId.asString}] was locked pending and did not resolve" }
        ApproveRequestOutcome.Approved(LinkedSupporter(linkId = link.id, studentId = studentId, name = pending.name))
      }
    }

  /** The student declines [requestId]. Nothing is sent; a decline is final for the pair. */
  suspend fun decline(
    studentId: StudentId,
    requestId: SupporterLinkRequestId,
  ): Result<DeclineRequestOutcome> =
    runCatching {
      database.withConnection { session ->
        SupporterLinkRequestsDao.findPendingVisibleToStudentForUpdate(session, studentId, requestId).getOrThrow()
          ?: return@withConnection DeclineRequestOutcome.NotFound
        checkNotNull(
          SupporterLinkRequestsDao.decline(session, requestId, studentId).getOrThrow(),
        ) { "request [${requestId.asString}] was locked pending and did not resolve" }
        DeclineRequestOutcome.Declined
      }
    }
}
