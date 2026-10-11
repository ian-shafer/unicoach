package ed.unicoach.family

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CostReportSharesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SupporterAlreadyExistsException
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.LinkSide
import ed.unicoach.db.models.LinkedStudent
import ed.unicoach.db.models.LinkedSupporter
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterStudentLinkId
import ed.unicoach.db.models.UnlinkOutcome
import ed.unicoach.db.models.UserId
import org.slf4j.LoggerFactory

/**
 * What a supporter's home surface is built from (RFC 241): their `supporters`
 * row, and the roster of students they hold an ACTIVE link to.
 *
 * [students] is empty for a supporter who has not linked anybody, which is the
 * ordinary state of a parent who has just signed up — not an error, and not a
 * reason to withhold the home.
 */
data class SupporterHome(
  val supporter: Supporter,
  val students: List<SupportedStudent>,
)

/**
 * One student on a supporter's roster, with the OTHER live adults supporting
 * that student (RFC 249) — names only, oldest link first, never the supporter
 * reading it.
 */
data class SupportedStudent(
  val student: LinkedStudent,
  val otherSupporterNames: List<PersonName>,
)

/**
 * Everything the student's "people connected to you" list is built from (RFC
 * 249): the adults, the ceiling in the student's voice, and whether the
 * student's Family Cost Report link is live — the one thing a disconnect does
 * NOT turn off, so the list says so exactly when it is true.
 */
data class StudentSupporters(
  val supporters: List<LinkedSupporter>,
  val ceiling: CeilingStatement,
  val costReportLinkLive: Boolean,
)

/**
 * WHETHER this call minted the `supporters` row, beside the home when there is
 * one — and the one refusal that is not a fault: a CLOSED row.
 *
 * The [Created]/[AlreadyPresent] distinction exists for the REST door alone:
 * `POST /api/v1/supporters` is idempotent and answers `201` the first time and
 * `200` afterwards, so the two cases must arrive here as different values. A
 * boolean would have done the same job and read, at the call site, as
 * `if (created)` — one letter from its opposite, and unnameable in a `when`.
 *
 * Both of those arms carry the SAME type of home, because the two answers
 * differ in the status line and in nothing else.
 *
 * [Closed] is the third answer. `supporters_user_id_unique_idx` is TOTAL, so a
 * soft-deleted row holds its `user_id` forever and no live row can be minted
 * for that user again. There is no re-opening path (`family/09` closes the
 * whole account instead, RFC 252), so the refusal is an ANSWER this door gives — named here, so the route can name it too — and not
 * an exception the error channel carries to an untyped 400.
 */
sealed interface EnsureSupporterOutcome {
  /** The two arms that hold a home; they differ in the status line and nothing else. */
  sealed interface Present : EnsureSupporterOutcome {
    val home: SupporterHome
  }

  data class Created(
    override val home: SupporterHome,
  ) : Present

  data class AlreadyPresent(
    override val home: SupporterHome,
  ) : Present

  /** The caller's supporter row was CLOSED, and there is no re-opening path. */
  data object Closed : EnsureSupporterOutcome
}

/**
 * The supporter side of an account (RFC 241): make one, and read the home it
 * lands on. Since RFC 249 it also owns both ends' view of a link — the
 * student's list of adults, the co-supporters on the home — and the
 * disconnect either end can make.
 *
 * It is the ONE writer of a `supporters` row above `:db`.
 * [getOrCreate] moved here out of [SupporterInviteService], which now
 * calls it inside its own transaction, so accepting an invite and signing up as
 * a parent mint that row by the same two statements rather than by two copies of
 * them (brief 0014 D21).
 *
 * Nothing here resolves a student other than through a link the supporter
 * already holds, and nothing reads a linked student's profile: that is
 * `family/05/the-subject-seam`, and [LinkedStudent] is what keeps this service
 * from deciding it by accident.
 */
class SupporterService(
  private val database: Database,
  private val linkEndedNotice: LinkEndedNotice,
) {
  private val logger = LoggerFactory.getLogger(SupporterService::class.java)

  /**
   * The supporter row owned by [userId] within the caller's transaction,
   * created on demand (brief 0014 D21) — the one sanctioned mint above `:db`.
   *
   * On a [SqlSession] and not on its own connection, because the two callers
   * need it to be part of a LARGER transaction: an accept that rolls back must
   * not leave a supporter row behind, and [ensure] reads the roster in
   * the same breath.
   *
   * THROWS [SupporterAlreadyExistsException] when a concurrent mint won, or when
   * this user's row is CLOSED: `supporters_user_id_unique_idx` is TOTAL. The
   * caller's transaction is poisoned at that point, so recovery belongs to
   * whoever owns the transaction — [ensure] answers
   * [EnsureSupporterOutcome.AlreadyPresent] or [EnsureSupporterOutcome.Closed]
   * on a NEW connection, and [SupporterInviteService.accept] retries once and
   * then refuses. An in-transaction caller that classifies neither ships the
   * DAO's own sentence to its client as an untyped 400.
   */
  fun getOrCreate(
    session: SqlSession,
    userId: UserId,
  ): Supporter = ensureRow(session, userId).supporter

  /**
   * [userId]'s supporter row and roster, minting the row when they have none —
   * the write behind `POST /api/v1/supporters`.
   *
   * Idempotent, and deliberately silent about whether the caller is ALSO a
   * student: a `supporters` row is a home surface, not a claim about what
   * somebody is not, and the database's own self-link guard already refuses the
   * one dangerous case.
   */
  suspend fun ensure(userId: UserId): Result<EnsureSupporterOutcome> =
    runCatching {
      database.withConnection { session ->
        when (val row = ensureRow(session, userId)) {
          is EnsuredRow.Found -> EnsureSupporterOutcome.AlreadyPresent(homeOf(session, row.supporter))
          is EnsuredRow.Created -> EnsureSupporterOutcome.Created(homeOf(session, row.supporter))
        }
      }
    }.recoverCatching { failure -> recoverLostMint(userId, failure) }

  /**
   * [userId]'s supporter row, or `null` when they hold none — the identity read
   * behind `GET /api/v1/supporters/me/coaching-usage` (RFC 244).
   *
   * [homeFor] without the roster, because the meter's subject is the supporter and
   * not the students they support: coaching usage is a fact about the parent's own
   * account, so loading a roster the answer never reads would be work spent to
   * produce nothing. `null` is the same VALUE it is there — every student, and
   * every user who has never been invited, is in that state — and the route
   * answers it with the same `404`.
   */
  suspend fun find(userId: UserId): Result<Supporter?> =
    runCatching {
      database.withConnection { session -> SupportersDao.findByUserId(session, userId).getOrThrow() }
    }

  /**
   * [userId]'s home, or `null` when they hold no `supporters` row — the read
   * behind `GET /api/v1/supporters/me`.
   *
   * `null` is a VALUE and not a failure: every student, and every user who has
   * never been invited, is in this state, and the door answers it with the same
   * `404` shape `GET /api/v1/students/me` gives the mirror-image absence.
   */
  suspend fun homeFor(userId: UserId): Result<SupporterHome?> =
    runCatching {
      database.withConnection { session ->
        SupportersDao.findByUserId(session, userId).getOrThrow()?.let { homeOf(session, it) }
      }
    }

  /**
   * The adults connected to [studentId], the ceiling as the student reads it,
   * and whether their Family Cost Report link is live (RFC 249) — the read
   * behind `GET /api/v1/students/me/supporters`.
   */
  suspend fun supportersOf(studentId: StudentId): Result<StudentSupporters> =
    runCatching {
      database.withConnection { session ->
        StudentSupporters(
          supporters = SupporterStudentLinksDao.listActiveSupportersOf(session, studentId).getOrThrow(),
          ceiling = SupporterCeiling.toStudent,
          costReportLinkLive = CostReportSharesDao.findLiveByStudent(session, studentId).getOrThrow() != null,
        )
      }
    }

  /**
   * The student ends one adult's access (RFC 249), and the adult is told the
   * link ended (RFC 252) — inside this transaction, so a failed enqueue undoes
   * the disconnect. Nothing else changes: both accounts, the student's
   * conversations, the adult's own rows, any unused invite link and the Family
   * Cost Report link all stay as they are.
   */
  suspend fun disconnectAsStudent(
    studentId: StudentId,
    linkId: SupporterStudentLinkId,
  ): Result<UnlinkOutcome> =
    runCatching {
      database.withConnection { session ->
        SupporterStudentLinksDao.unlink(session, linkId, LinkSide.Student(studentId)).getOrThrow().also { outcome ->
          if (outcome is UnlinkOutcome.Unlinked) linkEndedNotice.tellSupporter(session, outcome.link)
        }
      }
    }

  /**
   * The supporter ends their own access to one student (RFC 249), and the
   * student is told the link ended (RFC 252), on this transaction. A caller
   * with no live `supporters` row holds no link, so the answer is
   * [UnlinkOutcome.NotFound], the same as for a link they never held.
   */
  suspend fun disconnectAsSupporter(
    userId: UserId,
    linkId: SupporterStudentLinkId,
  ): Result<UnlinkOutcome> =
    runCatching {
      database.withConnection { session ->
        val supporter = SupportersDao.findByUserId(session, userId).getOrThrow()
        if (supporter == null) {
          // The door answers this exactly as it answers a link the caller never
          // held, so this line is the only place the two misses are told apart.
          logger.info("Disconnect of link [{}] by user [{}]: the user has no live supporters row", linkId.asString, userId.asString)
          return@withConnection UnlinkOutcome.NotFound
        }
        SupporterStudentLinksDao.unlink(session, linkId, LinkSide.Supporter(supporter.id)).getOrThrow().also { outcome ->
          if (outcome is UnlinkOutcome.Unlinked) linkEndedNotice.tellStudent(session, outcome.link)
        }
      }
    }

  /**
   * Whether the row was there already, carried out of the transaction that
   * asked. PRIVATE: only [ensure] can tell a client about it, and
   * [getOrCreate] discards it.
   */
  private sealed interface EnsuredRow {
    val supporter: Supporter

    data class Found(
      override val supporter: Supporter,
    ) : EnsuredRow

    data class Created(
      override val supporter: Supporter,
    ) : EnsuredRow
  }

  private fun ensureRow(
    session: SqlSession,
    userId: UserId,
  ): EnsuredRow =
    SupportersDao
      .findByUserId(session, userId)
      .getOrThrow()
      ?.let { EnsuredRow.Found(it) }
      ?: EnsuredRow.Created(SupportersDao.create(session, NewSupporter(userId)).getOrThrow())

  /**
   * The roster, each student carrying the other adults supporting them (RFC
   * 249). The students are this supporter's own live links, so nobody outside
   * them is reachable; the supporter's own links are the rows dropped.
   */
  private fun homeOf(
    session: SqlSession,
    supporter: Supporter,
  ): SupporterHome {
    val roster = SupporterStudentLinksDao.listActiveLinkedStudents(session, supporter.id).getOrThrow()
    val others = otherSupporterNamesByStudent(session, roster)
    return SupporterHome(
      supporter = supporter,
      students = roster.map { SupportedStudent(it, others[it.studentId].orEmpty()) },
    )
  }

  /** The live adults on [roster]'s students, minus the roster's own links — the supporter reading it. */
  private fun otherSupporterNamesByStudent(
    session: SqlSession,
    roster: List<LinkedStudent>,
  ): Map<StudentId, List<PersonName>> {
    val ownLinks = roster.mapTo(HashSet()) { it.linkId }
    return SupporterStudentLinksDao
      .listActiveSupportersOf(session, roster.map { it.studentId })
      .getOrThrow()
      .filterNot { it.linkId in ownLinks }
      .groupBy({ it.studentId }, { it.name })
  }

  /**
   * A CONCURRENT call minted the row between this one's read and its insert —
   * two taps on one button, which an idempotent door must answer twice the same
   * way. Our transaction rolled back, so the recovery is a SECOND one: a
   * Postgres transaction that has taken a constraint violation can run nothing
   * else, and a re-read inside the failed one would fail as `25P02`.
   *
   * It is [EnsureSupporterOutcome.AlreadyPresent] and not `Created`, because
   * this call did not create it — the winner did, and `201` claims otherwise.
   *
   * The OTHER reader of that same refusal is a user whose supporter row was
   * CLOSED: `supporters_user_id_unique_idx` is TOTAL, so a soft-deleted row
   * holds its `user_id` forever and no live row appears on the re-read. There
   * is no re-opening path (`SupportersDao.delete`'s KDoc), so this must not
   * invent one — but it is a predictable domain state and not a fault, so it
   * leaves as [EnsureSupporterOutcome.Closed] rather than as the index's own
   * exception, which says "already exists" about a row that does not.
   *
   * The re-read is [homeFor] itself, which is exactly this read on a NEW
   * connection — the property the recovery depends on.
   */
  private suspend fun recoverLostMint(
    userId: UserId,
    failure: Throwable,
  ): EnsureSupporterOutcome {
    if (failure !is SupporterAlreadyExistsException) throw failure
    val home =
      homeFor(userId).getOrElse { reread ->
        // The re-read IS the recovery, so its failure is what the caller sees —
        // but the violation that sent us here is the only record that this call
        // raced, and it leaves suppressed by that failure rather than not at all.
        throw reread.also { it.addSuppressed(failure) }
      }
    if (home == null) {
      logger.info(
        "supporter mint refused: the one-per-user index holds a CLOSED row, which family/09 owns: userId=[{}]",
        userId.asString,
        failure,
      )
      return EnsureSupporterOutcome.Closed
    }
    logger.info(
      "supporter mint lost a race on the one-per-user index, answering with the row that won: userId=[{}]",
      userId.asString,
      failure,
    )
    return EnsureSupporterOutcome.AlreadyPresent(home)
  }
}
