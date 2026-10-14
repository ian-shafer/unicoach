package ed.unicoach.coaching.report

import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.CostReportShare
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.TokenHash

/** What a presented report token's hash resolved to, in the reader's own session. */
sealed interface ReportShareLookup {
  /** A live share whose account is open. */
  data class Open(
    val share: CostReportShare,
  ) : ReportShareLookup

  /** No live share carries this hash — unknown or revoked, never told apart. */
  data object NoLiveShare : ReportShareLookup

  /**
   * A LIVE share whose account is closed — the `students` row or the owning
   * `users` row (RFC 252). The close revokes the share, so this means some other
   * path left it live.
   */
  data object AccountClosed : ReportShareLookup
}

/**
 * The ONE answer to "which open account does this report link belong to" (RFC
 * 254): the `/report` page and a claim from it must agree on which links are
 * live, so both read it here, in the caller's session.
 *
 * A stateless `object` over the caller's [SqlSession], in the DAO shape and on
 * the [ed.unicoach.coaching.testing.SchoolDayTestingReader] precedent, not an
 * injected class: everything below it is a static DAO, so an injected seam
 * here would let a test stub nothing its callers do not already run against
 * the real database.
 */
object ReportShares {
  fun lookup(
    session: SqlSession,
    tokenHash: TokenHash,
  ): ReportShareLookup {
    // Absence and revocation are ONE null from the DAO (a revoked row is
    // invisible there), so this read cannot tell them apart even by accident.
    val share =
      CostReportSharesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
        ?: return ReportShareLookup.NoLiveShare
    // Both reads in ALL scope, testing `deleted_at` here: the operator admin can
    // close a users row without its students row, and a missing row is
    // corruption that fails the read.
    val student = StudentsDao.findById(session, share.studentId, SoftDeleteScope.ALL).getOrThrow()
    val closed =
      student.deletedAt != null ||
        UsersDao.findById(session, student.userId, SoftDeleteScope.ALL).getOrThrow().deletedAt != null
    return if (closed) ReportShareLookup.AccountClosed else ReportShareLookup.Open(share)
  }
}
