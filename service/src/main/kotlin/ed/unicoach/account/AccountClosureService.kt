package ed.unicoach.account

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.dao.SupporterLinkRequestsDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UserAuthIdentitiesDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.LinkSide
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.family.LinkEndedNotice

/** What [AccountClosureService.close] did. */
sealed interface CloseAccountOutcome {
  data object Closed : CloseAccountOutcome

  /** No live `users` row: already closed, or never there. */
  data object NotFound : CloseAccountOutcome
}

/**
 * Closes an account of any shape — student, supporter, both, or neither (RFC
 * 252). Access ends at once, every connection is cut and each other party is
 * told, and nothing of the closer's stays reachable. Rows are soft-deleted,
 * not erased: erasure is a later slice.
 *
 * Every write below names the closer's own keys in its WHERE, and a table
 * holding keys from both parties (today `supporter_student_links`,
 * `supporter_invites` and `supporter_link_requests`) is written from the
 * closer's side only. A new such table needs the same treatment here; nothing
 * checks for it.
 *
 * Kept, on purpose: the closer's `subscriptions` row (Apple bills until the
 * person cancels; it rebinds if the same Apple ID subscribes on a new
 * account) and their LLM cost rows (past spend is still spend).
 */
class AccountClosureService(
  private val database: Database,
  private val linkEndedNotice: LinkEndedNotice,
) {
  /**
   * ONE transaction: a failure anywhere — the link-ended enqueue included
   * (ASYNC_WORK.md rule 2) — rolls the whole close back.
   */
  suspend fun close(userId: UserId): Result<CloseAccountOutcome> =
    runCatching {
      database.withConnection { session ->
        val user = lockLiveUser(session, userId) ?: return@withConnection CloseAccountOutcome.NotFound
        val student = StudentsDao.findByUserIdOrNull(session, userId).getOrThrow()
        val supporter = SupportersDao.findByUserId(session, userId).getOrThrow()

        severLinksAndTellOthers(session, student, supporter)
        revokeOutstandingLinks(session, userId, student)
        closeRows(session, user, student, supporter)
        endSignIn(session, userId)
        CloseAccountOutcome.Closed
      }
    }

  /** The live `users` row, locked; null when it is already closed or was never there. */
  private fun lockLiveUser(
    session: SqlSession,
    userId: UserId,
  ): User? =
    UsersDao.findByIdForUpdate(session, userId).getOrElse { failure ->
      if (failure is NotFoundException) return null
      throw failure
    }

  private fun severLinksAndTellOthers(
    session: SqlSession,
    student: Student?,
    supporter: Supporter?,
  ) {
    if (student != null) {
      SupporterStudentLinksDao
        .severAll(session, LinkSide.Student(student.id))
        .getOrThrow()
        .forEach { linkEndedNotice.tellSupporter(session, it) }
      SupporterLinkRequestsDao.cancelAllPendingFor(session, LinkSide.Student(student.id)).getOrThrow()
    }
    if (supporter != null) {
      SupporterStudentLinksDao
        .severAll(session, LinkSide.Supporter(supporter.id))
        .getOrThrow()
        .forEach { linkEndedNotice.tellStudent(session, it) }
      SupporterLinkRequestsDao.cancelAllPendingFor(session, LinkSide.Supporter(supporter.id)).getOrThrow()
    }
  }

  /** The closer's unused invite links and their Family Cost Report link. */
  private fun revokeOutstandingLinks(
    session: SqlSession,
    userId: UserId,
    student: Student?,
  ) {
    SupporterInvitesDao.revokeAllByInviter(session, userId).getOrThrow()
    if (student != null) CostReportSharesDao.revokeLive(session, student.id).getOrThrow()
  }

  private fun closeRows(
    session: SqlSession,
    user: User,
    student: Student?,
    supporter: Supporter?,
  ) {
    if (student != null) StudentsDao.delete(session, student.id, student.version).getOrThrow()
    if (supporter != null) SupportersDao.delete(session, supporter.id, supporter.version).getOrThrow()
    UsersDao.delete(session, user.id, user.version).getOrThrow()
  }

  /** Runs after [closeRows]: the identity delete guard admits only a closed owner. */
  private fun endSignIn(
    session: SqlSession,
    userId: UserId,
  ) {
    UserAuthIdentitiesDao.deleteForClosedUser(session, userId).getOrThrow()
    SessionsDao.revokeAllForUser(session, userId).getOrThrow()
  }
}
