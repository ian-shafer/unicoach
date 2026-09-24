package ed.unicoach.family

import ed.unicoach.common.config.tokenLink
import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.ActiveSupporterLinkException
import ed.unicoach.db.dao.ClosedStudentLinkException
import ed.unicoach.db.dao.ClosedSupporterLinkException
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.SelfSupporterLinkException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.SupporterInvitesDao
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.NewSupporterInvite
import ed.unicoach.db.models.NewSupporterStudentLink
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterInvite
import ed.unicoach.db.models.SupporterInviteId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.SupporterStudentLink
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.util.TokenGenerator
import org.slf4j.LoggerFactory
import java.time.Instant

sealed interface AcceptInviteOutcome {
  data class Success(
    val link: SupporterStudentLink,
    val invite: SupporterInvite,
  ) : AcceptInviteOutcome

  /**
   * The token cannot make a link. [reason] is for the LOG alone — every caller
   * answers the same refusal for all of them — and it is carried because the
   * states that reach here (blank, unknown, expired, consumed, revoked, a lost
   * CAS consume, a closed endpoint, a closed inviter) used to arrive as one
   * argument-less object that logged only the word "InvalidToken".
   */
  data class InvalidToken(
    val reason: InviteMissReason,
  ) : AcceptInviteOutcome

  data object SelfLinkProhibited : AcceptInviteOutcome

  data object StudentProfileRequired : AcceptInviteOutcome
}

/**
 * WHY a presented invite token resolved to nothing (RFC 232), the
 * [ed.unicoach.web.report.MissReason] shape the `/report` read path already
 * uses. LOG ONLY — never rendered and never on the wire, because the refusal
 * page is byte-identical for every one of these and distinguishing them would
 * tell a stranger holding a dead link that the token was once real. An operator
 * answering "the link I sent my wife 404s" cannot otherwise tell a truncated URL
 * from a revoked invite.
 *
 * The four DEAD states are separate members for exactly that reason: one
 * `NO_LIVE_INVITE` collapsed the four this KDoc promises to tell apart, from the
 * one channel allowed to tell them apart.
 */
enum class InviteMissReason {
  /** No token was presented at all, or a blank one. */
  BLANK_TOKEN,

  /** More than one token presented: two credentials, a request we have no reading of. */
  REPEATED_TOKEN_PARAM,

  /** A token was presented and no invite at all carries its hash. */
  UNKNOWN_TOKEN,

  /** A real invite, past its `expires_at`. */
  EXPIRED_INVITE,

  /** A real invite, already accepted. */
  CONSUMED_INVITE,

  /** A real invite, revoked by its inviter. */
  REVOKED_INVITE,

  /** The CAS consume lost: another accept burned this invite between our read and our write. */
  CONSUME_LOST,

  /** An endpoint of the link is closed, so this invite can never link anyone. */
  ENDPOINT_CLOSED,

  /**
   * The invite is live and its inviter's account is not: nobody to name on the
   * page, and nobody an accept could honestly link the recipient to.
   */
  INVITER_UNREADABLE,
}

sealed interface InvitePreviewOutcome {
  /**
   * The FACTS the accept page renders, and only those.
   *
   * Deliberately NOT the `supporter_invites` row: its `token_hash` is the
   * credential this table keeps at rest, and a read model carrying it hands the
   * digest to every layer above for no reader at all.
   */
  data class Valid(
    val inviterUserId: UserId,
    val inviterName: String,
    val inviterRole: InviterRole,
    val relationship: SupporterRelationship,
  ) : InvitePreviewOutcome

  /** [reason] is for the log alone; the page is identical for every case. */
  data class Invalid(
    val reason: InviteMissReason,
  ) : InvitePreviewOutcome
}

data class MintedInvite(
  val id: SupporterInviteId,
  val rawToken: String,
  val inviteUrl: String,
  val inviterRole: InviterRole,
  val relationship: SupporterRelationship,
  val expiresAt: Instant,
)

/**
 * WHICH side minted an invite, and the endpoint row it points at (brief 0014
 * D21). A VALUE, so the mint flow stays a sequence of named steps instead of
 * carrying a `Triple` that only exists because the decision had no name.
 */
private sealed interface InviterEndpoint {
  val role: InviterRole
  val studentId: StudentId?
  val supporterId: SupporterId?

  data class Student(
    val id: StudentId,
  ) : InviterEndpoint {
    override val role = InviterRole.STUDENT
    override val studentId = id
    override val supporterId: SupporterId? = null
  }

  data class Supporter(
    val id: SupporterId,
  ) : InviterEndpoint {
    override val role = InviterRole.SUPPORTER
    override val studentId: StudentId? = null
    override val supporterId = id
  }
}

/**
 * The two endpoints of the link an invite would make, or the one refusal
 * resolving them can produce. A VALUE for the same reason [InviterEndpoint] is.
 */
private sealed interface LinkEndpoints {
  data class Resolved(
    val supporterId: SupporterId,
    val studentId: StudentId,
  ) : LinkEndpoints

  /** A supporter-minted invite accepted by a user who has no student profile yet. */
  data object StudentProfileMissing : LinkEndpoints
}

class SupporterInviteService(
  private val database: Database,
  private val config: SupporterInviteConfig,
  private val tokenGenerator: TokenGenerator,
) {
  private val logger = LoggerFactory.getLogger(SupporterInviteService::class.java)

  /**
   * The active student owned by [userId], or null when this user is not one —
   * the ordinary state of every supporter, so it is a VALUE here and not a
   * failure to decode out of the error channel.
   */
  private fun findStudentOrNull(
    session: SqlSession,
    userId: UserId,
  ): Student? = StudentsDao.findByUserIdOrNull(session, userId).getOrThrow()

  /** The supporter row owned by [userId], created on demand (brief 0014 D21). */
  private fun getOrCreateSupporter(
    session: SqlSession,
    userId: UserId,
  ): Supporter =
    SupportersDao.findByUserId(session, userId).getOrThrow()
      ?: SupportersDao.create(session, NewSupporter(userId)).getOrThrow()

  /**
   * The inviter's ACTIVE user row, or null when that account is closed.
   *
   * `supporter_invites.inviter_user_id` CASCADEs on a HARD delete only, and
   * closing an account is a SOFT delete, so a LIVE invite can name a user no
   * active read can see. BOTH doors ask: the preview has nobody to name on the
   * page, and the accept must not link a recipient to a closed account.
   */
  private fun findInviterOrNull(
    session: SqlSession,
    invite: SupporterInvite,
  ): User? = UsersDao.findByIdOrNull(session, invite.inviterUserId).getOrThrow()

  /** A user with a student profile invites AS the student; anyone else invites as a supporter. */
  private fun resolveInviterEndpoint(
    session: SqlSession,
    inviterUserId: UserId,
  ): InviterEndpoint =
    findStudentOrNull(session, inviterUserId)
      ?.let { InviterEndpoint.Student(it.id) }
      ?: InviterEndpoint.Supporter(getOrCreateSupporter(session, inviterUserId).id)

  /**
   * [relationship] has no default here. The product default is the REST door's
   * to apply — it is what an absent wire field means — and a second default in
   * this signature is a second place to change it.
   */
  suspend fun create(
    inviterUserId: UserId,
    relationship: SupporterRelationship,
  ): Result<MintedInvite> =
    runCatching {
      database.withConnection { session ->
        val inviter = resolveInviterEndpoint(session, inviterUserId)

        val rawToken = tokenGenerator.generateToken()
        val tokenHash = TokenHash.fromRawToken(rawToken)
        val expiresAt = Instant.now().plus(config.tokenTtl)

        val created =
          SupporterInvitesDao
            .create(
              session,
              NewSupporterInvite(
                tokenHash = tokenHash,
                inviterUserId = inviterUserId,
                inviterRole = inviter.role,
                studentId = inviter.studentId,
                supporterId = inviter.supporterId,
                relationship = relationship,
                expiresAt = expiresAt,
              ),
            ).getOrThrow()

        MintedInvite(
          id = created.id,
          rawToken = rawToken,
          inviteUrl = tokenLink(config.inviteUrlBase, rawToken),
          inviterRole = inviter.role,
          relationship = relationship,
          expiresAt = expiresAt,
        )
      }
    }

  /**
   * WHY no LIVE invite carries [tokenHash] — read for the LOG alone, on the
   * REFUSAL path alone, and never rendered: the page is byte-identical for all
   * four. `findLiveByTokenHash` stays the authority on what is live; this only
   * classifies a miss it has already made.
   */
  private fun classifyMiss(
    session: SqlSession,
    tokenHash: TokenHash,
  ): InviteMissReason {
    val dead =
      SupporterInvitesDao.findByTokenHash(session, tokenHash).getOrThrow()
        ?: return InviteMissReason.UNKNOWN_TOKEN
    return when {
      dead.revokedAt != null -> InviteMissReason.REVOKED_INVITE
      dead.consumedAt != null -> InviteMissReason.CONSUMED_INVITE
      else -> InviteMissReason.EXPIRED_INVITE
    }
  }

  suspend fun preview(rawToken: String): Result<InvitePreviewOutcome> =
    runCatching {
      if (rawToken.isBlank()) return@runCatching InvitePreviewOutcome.Invalid(InviteMissReason.BLANK_TOKEN)
      val tokenHash = TokenHash.fromRawToken(rawToken)
      database.withConnection { session ->
        val invite =
          SupporterInvitesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
            ?: return@withConnection InvitePreviewOutcome.Invalid(classifyMiss(session, tokenHash))

        // A VALUE and not the error channel: see [findInviterOrNull]. Throwing
        // here served a 500 on a page whose whole contract is one uniform
        // branded 404.
        val inviterUser =
          findInviterOrNull(session, invite)
            ?: return@withConnection InvitePreviewOutcome.Invalid(InviteMissReason.INVITER_UNREADABLE)

        InvitePreviewOutcome.Valid(
          inviterUserId = invite.inviterUserId,
          inviterName = inviterUser.name.value,
          inviterRole = invite.inviterRole,
          relationship = invite.relationship,
        )
      }
    }

  /**
   * Accept [rawToken] on behalf of [recipientUserId].
   *
   * Three predictable states of the link write are answered as OUTCOMES rather
   * than left in the error channel, and all three are answered OUTSIDE the
   * transaction on purpose: the refusal arrives as an exception from the insert,
   * and only by letting it leave [Database.withConnection] does the CAS consume
   * roll back with it. Recovering inside would commit a burnt token.
   *
   * Every arm RECORDS what it did. A silent retry and a silent refusal cannot be
   * counted, and each line names the recipient, because the operator holding a
   * family's complaint has nothing else to join on.
   */
  suspend fun accept(
    rawToken: String,
    recipientUserId: UserId,
  ): Result<AcceptInviteOutcome> =
    attemptAccept(rawToken, recipientUserId).recoverCatching { failure ->
      when {
        // A CONCURRENT accept linked this pair between our read and our insert.
        // Our transaction rolled back, so the invite is still live: one retry
        // finds the link that now exists and consumes against it, which is the
        // outcome both accepts were asking for. One retry, not a loop — a
        // second refusal is not a race.
        failure is ActiveSupporterLinkException -> {
          logger.info(
            "supporter invite accept lost a race on the active-link index, retrying once: recipientUserId=[{}]",
            recipientUserId.asString,
            failure,
          )
          // The FIRST failure rides with the second: a retry that also fails
          // must not leave the race it lost unrecorded in the chain.
          attemptAccept(rawToken, recipientUserId).getOrElse { retryFailure ->
            throw retryFailure.also { it.addSuppressed(failure) }
          }
        }

        // An endpoint of the link is closed, so this invite can never link
        // anyone. That is a refusal, not a fault — a 500 here would land on a
        // recipient who has just registered — and it gets the SAME answer a
        // dead token gets, because a distinguishable one would tell the holder
        // of a link that the account behind it exists.
        failure is ClosedStudentLinkException || failure is ClosedSupporterLinkException -> {
          logger.info(
            "supporter invite accept refused, a link endpoint is closed: recipientUserId=[{}]",
            recipientUserId.asString,
            failure,
          )
          AcceptInviteOutcome.InvalidToken(InviteMissReason.ENDPOINT_CLOSED)
        }

        // The database's own self-link guard: the case the inviter-id check
        // cannot see, where one person holds both endpoints of the link. It is
        // the most interesting failure this block sees — the service-level
        // check did not see the case — so it is the last one that may be silent.
        failure is SelfSupporterLinkException -> {
          logger.warn(
            "supporter invite accept reached the database self-link guard: recipientUserId=[{}]",
            recipientUserId.asString,
            failure,
          )
          AcceptInviteOutcome.SelfLinkProhibited
        }

        else -> {
          throw failure
        }
      }
    }

  /**
   * The two endpoints this invite would link, resolved. Minting the recipient's
   * supporter row is part of resolving them (brief 0014 D21) and stays here, so
   * the accept flow above reads as its own sequence of steps.
   */
  private fun resolveLinkEndpoints(
    session: SqlSession,
    invite: SupporterInvite,
    recipientUserId: UserId,
  ): LinkEndpoints =
    when (invite.inviterRole) {
      InviterRole.STUDENT -> {
        LinkEndpoints.Resolved(
          supporterId = getOrCreateSupporter(session, recipientUserId).id,
          studentId = invite.requireStudentId(),
        )
      }

      InviterRole.SUPPORTER -> {
        findStudentOrNull(session, recipientUserId)
          ?.let { LinkEndpoints.Resolved(supporterId = invite.requireSupporterId(), studentId = it.id) }
          ?: LinkEndpoints.StudentProfileMissing
      }
    }

  /** The LIVE link for this pair, or a new one: a second invite must not mint a duplicate. */
  private fun liveOrNewLink(
    session: SqlSession,
    endpoints: LinkEndpoints.Resolved,
    relationship: SupporterRelationship,
  ): SupporterStudentLink =
    SupporterStudentLinksDao.findActive(session, endpoints.supporterId, endpoints.studentId).getOrThrow()
      ?: SupporterStudentLinksDao
        .create(session, NewSupporterStudentLink(endpoints.supporterId, endpoints.studentId, relationship))
        .getOrThrow()

  private suspend fun attemptAccept(
    rawToken: String,
    recipientUserId: UserId,
  ): Result<AcceptInviteOutcome> =
    runCatching {
      if (rawToken.isBlank()) return@runCatching AcceptInviteOutcome.InvalidToken(InviteMissReason.BLANK_TOKEN)
      val tokenHash = TokenHash.fromRawToken(rawToken)
      database.withConnection { session ->
        val invite =
          SupporterInvitesDao.findLiveByTokenHash(session, tokenHash).getOrThrow()
            ?: return@withConnection AcceptInviteOutcome.InvalidToken(classifyMiss(session, tokenHash))

        // The SAME refusal the preview makes, at the door that writes. A closed
        // inviter fires no cascade onto the invite row, so the row alone cannot
        // be trusted: without this, `POST /invite` — and the `/sign-in` and
        // `/register` doors, which never preview at all — would burn the token
        // and link the recipient to an account nobody can reach.
        if (findInviterOrNull(session, invite) == null) {
          return@withConnection AcceptInviteOutcome.InvalidToken(InviteMissReason.INVITER_UNREADABLE)
        }

        if (invite.inviterUserId == recipientUserId) {
          return@withConnection AcceptInviteOutcome.SelfLinkProhibited
        }

        val endpoints = resolveLinkEndpoints(session, invite, recipientUserId)
        if (endpoints !is LinkEndpoints.Resolved) {
          return@withConnection AcceptInviteOutcome.StudentProfileRequired
        }

        val consumed =
          SupporterInvitesDao.consume(session, tokenHash).getOrThrow()
            ?: return@withConnection AcceptInviteOutcome.InvalidToken(InviteMissReason.CONSUME_LOST)

        AcceptInviteOutcome.Success(
          link = liveOrNewLink(session, endpoints, invite.relationship),
          invite = consumed,
        )
      }
    }

  /**
   * The invite [inviteId] as it stands after revocation, or `null` when
   * [inviterUserId] holds no LIVE invite by that id.
   *
   * The mutated ROW, not a flag describing it: the DAO already returns it
   * (`RETURNING *`), and a caller that revokes reads `revokedAt` off the value
   * it was handed instead of re-reading the table.
   */
  suspend fun revoke(
    inviteId: SupporterInviteId,
    inviterUserId: UserId,
  ): Result<SupporterInvite?> =
    runCatching {
      database.withConnection { session ->
        val revoked = SupporterInvitesDao.revoke(session, inviteId, inviterUserId).getOrThrow()
        if (revoked == null) {
          logger.info(
            "supporter invite revoke matched no live invite: inviteId=[{}] inviterUserId=[{}]",
            inviteId.asString,
            inviterUserId.asString,
          )
        }
        revoked
      }
    }
}

/**
 * `supporter_invites_inviter_endpoints_check` refuses a role with no matching
 * endpoint, so a row that reaches acceptance holding one is CORRUPTION, not a
 * dead link. It throws the house's LOCATED exception — the `mapInvite`
 * treatment of a corrupt enum in the same table — because the row, the role and
 * the column are facts a monitor or a repair job must read as PROPERTIES rather
 * than regex back out of a sentence.
 */
private fun SupporterInvite.requireStudentId(): StudentId =
  studentId ?: throw CorruptPersistedValueException(
    value = inviterRole.value,
    error = ValidationError.InvalidFormat(expected = "a student_id beside inviter_role=student"),
    location = "supporter_invites.student_id (row [${id.asString}])",
  )

/** See [requireStudentId]. */
private fun SupporterInvite.requireSupporterId(): SupporterId =
  supporterId ?: throw CorruptPersistedValueException(
    value = inviterRole.value,
    error = ValidationError.InvalidFormat(expected = "a supporter_id beside inviter_role=supporter"),
    location = "supporter_invites.supporter_id (row [${id.asString}])",
  )
