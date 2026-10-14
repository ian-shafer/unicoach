package ed.unicoach.db.dao

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.LinkSide
import ed.unicoach.db.models.NewSupporterLinkRequest
import ed.unicoach.db.models.PendingSupporterLinkRequest
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterLinkRequest
import ed.unicoach.db.models.SupporterLinkRequestId
import ed.unicoach.db.models.SupporterLinkRequestResolution
import ed.unicoach.db.models.SupporterLinkRequestState
import ed.unicoach.db.models.SupporterStudentLinkId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Store for `supporter_link_requests` (RFC 254): a parent's request to connect
 * with the student whose Family Cost Report link they opened.
 *
 * The [SupporterInvitesDao] shape: a stateless `object` over [SqlSession], every
 * method returning [Result], and the caller's transaction. A request is written
 * once and resolved once, by compare-and-swap on `resolution IS NULL`.
 */
object SupporterLinkRequestsDao {
  /**
   * The name the `00d` self-request guard raises with `USING CONSTRAINT = ...`,
   * restated because the mapper routes on it. `SupporterLinkRequestsDaoTest`
   * provokes it from the real database, so a rename in the migration alone fails
   * there.
   */
  private const val NOT_SELF = "supporter_link_requests_not_self"

  private val FOREIGN_KEY_MESSAGES =
    mapOf(
      "supporter_link_requests_supporter_id_fkey" to "Requesting supporter not found",
      "supporter_link_requests_student_id_fkey" to "Requested student not found",
      "supporter_link_requests_cost_report_share_id_fkey" to "Cost report share not found",
    )

  /**
   * What makes a pending request VISIBLE to its student: the claimant's
   * supporter row and user row are live, and the user's email is VERIFIED.
   *
   * Written once, because the list the student reads and the row the student
   * approves or declines must agree. Anyone can register as somebody else's
   * address, so a request from an unverified claimant is never shown and can
   * never be acted on — and a closed claimant is nobody to connect with.
   */
  private const val VISIBLE_PENDING_FROM =
    """
    FROM supporter_link_requests r
    JOIN supporters sup ON sup.id = r.supporter_id
    JOIN users u ON u.id = sup.user_id
    WHERE r.student_id = ?
      AND r.resolution IS NULL
      AND sup.deleted_at IS NULL
      AND u.deleted_at IS NULL
      AND u.email_verified_at IS NOT NULL
    """

  private const val VISIBLE_PENDING_COLUMNS =
    "SELECT r.id AS request_id, r.supporter_id, r.created_at AS requested_at, u.name AS claimant_name, u.email AS claimant_email"

  private fun mapRequest(rs: ResultSet): SupporterLinkRequest {
    val id = SupporterLinkRequestId(UUID.fromString(rs.getString("id")))
    return SupporterLinkRequest(
      id = id,
      supporterId = SupporterId(UUID.fromString(rs.getString("supporter_id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      costReportShareId = CostReportShareId(UUID.fromString(rs.getString("cost_report_share_id"))),
      notifiedAt = rs.getInstantOrNull("notified_at"),
      state = stateOf(rs, id),
      createdAt = rs.getInstant("created_at"),
    )
  }

  /**
   * The row's [SupporterLinkRequestState], from the three columns the table's
   * CHECKs pair up. A row that breaks them is located corruption here, never a
   * value a caller holds.
   */
  private fun stateOf(
    rs: ResultSet,
    id: SupporterLinkRequestId,
  ): SupporterLinkRequestState {
    val location = "supporter_link_requests (row [${id.asString}])"
    val raw = rs.getString("resolution") ?: return SupporterLinkRequestState.Pending
    val resolvedAt = rs.getInstant("resolved_at")
    return when (
      SupporterLinkRequestResolution.fromValue(raw)
        ?: throw corruptValue(raw, "SupporterLinkRequestResolution", "$location.resolution")
    ) {
      SupporterLinkRequestResolution.APPROVED -> {
        val rawLink = rs.getString("link_id") ?: throw corruptValue("null", "SupporterStudentLinkId", "$location.link_id")
        SupporterLinkRequestState.Approved(resolvedAt, SupporterStudentLinkId(UUID.fromString(rawLink)))
      }

      SupporterLinkRequestResolution.DECLINED -> {
        SupporterLinkRequestState.Declined(resolvedAt)
      }

      SupporterLinkRequestResolution.CANCELLED -> {
        SupporterLinkRequestState.Cancelled(resolvedAt)
      }
    }
  }

  /**
   * One visible pending request. A stored name or email that fails its own
   * validator is corruption — `users`' CHECKs already refuse both — and throws
   * naming the request.
   */
  private fun mapPending(rs: ResultSet): PendingSupporterLinkRequest {
    val id = SupporterLinkRequestId(UUID.fromString(rs.getString("request_id")))
    val rawName = rs.getString("claimant_name")
    val rawEmail = rs.getString("claimant_email")
    val location = "users (claimant of request [${id.asString}])"
    val name =
      when (val parsed = PersonName.create(rawName)) {
        is ValidationResult.Valid -> parsed.value
        is ValidationResult.Invalid -> throw CorruptPersistedValueException(rawName, parsed.error, "$location.name")
      }
    val email =
      when (val parsed = EmailAddress.create(rawEmail)) {
        is ValidationResult.Valid -> parsed.value
        is ValidationResult.Invalid -> throw CorruptPersistedValueException(rawEmail, parsed.error, "$location.email")
      }
    return PendingSupporterLinkRequest(
      requestId = id,
      supporterId = SupporterId(UUID.fromString(rs.getString("supporter_id"))),
      name = name,
      email = email,
      requestedAt = rs.getInstant("requested_at"),
    )
  }

  /**
   * Inserts a PENDING request, or answers `null` when this pair already has one.
   *
   * `ON CONFLICT ... DO NOTHING` against the partial pending-pair index rather
   * than a typed violation: two claims racing for one pair both asked for the
   * same thing, and the loser's answer is "already requested", not a fault that
   * poisons its transaction.
   */
  fun create(
    session: SqlSession,
    input: NewSupporterLinkRequest,
  ): Result<SupporterLinkRequest?> =
    session
      .mutateReturning(
        """
        INSERT INTO supporter_link_requests (supporter_id, student_id, cost_report_share_id)
        VALUES (?, ?, ?)
        ON CONFLICT (supporter_id, student_id) WHERE resolution IS NULL DO NOTHING
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setObject(1, input.supporterId.value)
          stmt.setObject(2, input.studentId.value)
          stmt.setObject(3, input.costReportShareId.value)
        },
        map = ::mapRequest,
        mapError = ::mapCreateError,
      ).orNullOnNotFound()

  /** The PENDING request for one pair, or null. */
  fun findPendingPair(
    session: SqlSession,
    supporterId: SupporterId,
    studentId: StudentId,
  ): Result<SupporterLinkRequest?> =
    session
      .queryOne(
        "SELECT * FROM supporter_link_requests WHERE supporter_id = ? AND student_id = ? AND resolution IS NULL",
        bind = { stmt ->
          stmt.setObject(1, supporterId.value)
          stmt.setObject(2, studentId.value)
        },
        map = ::mapRequest,
      ).orNullOnNotFound()

  /** Whether the student ever DECLINED this supporter — a decline is final for the pair (RFC 254 decision 4). */
  fun hasDeclinedPair(
    session: SqlSession,
    supporterId: SupporterId,
    studentId: StudentId,
  ): Result<Boolean> =
    session.queryOne(
      """
      SELECT EXISTS (
        SELECT 1 FROM supporter_link_requests
        WHERE supporter_id = ? AND student_id = ? AND resolution = ?
      )
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, supporterId.value)
        stmt.setObject(2, studentId.value)
        stmt.setString(3, SupporterLinkRequestResolution.DECLINED.value)
      },
      map = { it.getBoolean(1) },
    )

  /**
   * The requests [studentId] can see and act on, oldest first — pending, from a
   * live and verified claimant. Served by `supporter_link_requests_student_pending_idx`.
   */
  fun listPendingVisibleToStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<PendingSupporterLinkRequest>> =
    session.queryList(
      "$VISIBLE_PENDING_COLUMNS $VISIBLE_PENDING_FROM ORDER BY r.created_at, r.id",
      bind = { it.setObject(1, studentId.value) },
      map = ::mapPending,
    )

  /**
   * The one request [requestId] when [studentId] can see it, LOCKED for the
   * approve or decline that follows; null for every request the student cannot
   * act on — another student's, unknown, resolved, or from a closed or
   * unverified claimant. One null, so a door on it cannot probe ids.
   */
  fun findPendingVisibleToStudentForUpdate(
    session: SqlSession,
    studentId: StudentId,
    requestId: SupporterLinkRequestId,
  ): Result<PendingSupporterLinkRequest?> =
    session
      .queryOne(
        "$VISIBLE_PENDING_COLUMNS $VISIBLE_PENDING_FROM AND r.id = ? FOR UPDATE OF r",
        bind = { stmt ->
          stmt.setObject(1, studentId.value)
          stmt.setObject(2, requestId.value)
        },
        map = ::mapPending,
      ).orNullOnNotFound()

  /** How many of [supporterId]'s requests are still pending — the parent home's waiting line. */
  fun countPendingForSupporter(
    session: SqlSession,
    supporterId: SupporterId,
  ): Result<Int> =
    session.queryOne(
      "SELECT count(*) FROM supporter_link_requests WHERE supporter_id = ? AND resolution IS NULL",
      bind = { it.setObject(1, supporterId.value) },
      map = { it.getInt(1) },
    )

  /** [supporterId]'s pending requests whose student notice is not yet decided (`notified_at IS NULL`), oldest first. */
  fun listUnnotifiedForSupporter(
    session: SqlSession,
    supporterId: SupporterId,
  ): Result<List<SupporterLinkRequest>> =
    session.queryList(
      """
      SELECT * FROM supporter_link_requests
      WHERE supporter_id = ? AND resolution IS NULL AND notified_at IS NULL
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, supporterId.value) },
      map = ::mapRequest,
    )

  /**
   * Records that the student's notice was decided — sent or deliberately not.
   * `notified_at IS NULL` in the WHERE makes it once: null when another run
   * already decided.
   */
  fun markNotified(
    session: SqlSession,
    id: SupporterLinkRequestId,
  ): Result<SupporterLinkRequest?> =
    session
      .mutateReturning(
        "UPDATE supporter_link_requests SET notified_at = NOW() WHERE id = ? AND notified_at IS NULL RETURNING *",
        bind = { it.setObject(1, id.value) },
        map = ::mapRequest,
      ).orNullOnNotFound()

  /**
   * The student approves the PENDING request [id], resolving it onto [linkId] —
   * the link the approval stands on. Null when [studentId] is not its student or
   * it is not pending (see [resolve]).
   */
  fun approve(
    session: SqlSession,
    id: SupporterLinkRequestId,
    studentId: StudentId,
    linkId: SupporterStudentLinkId,
  ): Result<SupporterLinkRequest?> = resolve(session, id, studentId, SupporterLinkRequestResolution.APPROVED, linkId)

  /** The student declines the PENDING request [id]; null as for [approve]. */
  fun decline(
    session: SqlSession,
    id: SupporterLinkRequestId,
    studentId: StudentId,
  ): Result<SupporterLinkRequest?> = resolve(session, id, studentId, SupporterLinkRequestResolution.DECLINED, null)

  /**
   * Resolves the PENDING request [id] when [studentId] is its student; null
   * otherwise. The student id rides in the same WHERE that resolves the row, so
   * the caller's side IS the authorisation (the RFC 249 `unlink` shape), and
   * "not yours", "never existed" and "already resolved" are one null.
   *
   * Private: [approve] and [decline] are the only pairings of [resolution] and
   * [linkId] that `supporter_link_requests_link_check` admits.
   */
  private fun resolve(
    session: SqlSession,
    id: SupporterLinkRequestId,
    studentId: StudentId,
    resolution: SupporterLinkRequestResolution,
    linkId: SupporterStudentLinkId?,
  ): Result<SupporterLinkRequest?> =
    session
      .mutateReturning(
        """
        UPDATE supporter_link_requests
        SET resolution = ?, resolved_at = NOW(), link_id = ?
        WHERE id = ? AND student_id = ? AND resolution IS NULL
        RETURNING *
        """.trimIndent(),
        bind = { stmt ->
          stmt.setString(1, resolution.value)
          stmt.setObject(2, linkId?.value)
          stmt.setObject(3, id.value)
          stmt.setObject(4, studentId.value)
        },
        map = ::mapRequest,
      ).orNullOnNotFound()

  /**
   * Cancels every PENDING request [side] holds (RFC 254) — the account close's
   * write, from either end. Returns the cancelled rows.
   */
  fun cancelAllPendingFor(
    session: SqlSession,
    side: LinkSide,
  ): Result<List<SupporterLinkRequest>> =
    session.queryList(
      """
      UPDATE supporter_link_requests
      SET resolution = ?, resolved_at = NOW()
      WHERE ${side.column} = ? AND resolution IS NULL
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        stmt.setString(1, SupporterLinkRequestResolution.CANCELLED.value)
        stmt.setObject(2, side.boundId)
      },
      map = ::mapRequest,
    )

  /**
   * Cancels every PENDING request made from [shareId] (RFC 254): the report link
   * it came from was turned off, so a request it carried is withdrawn with it —
   * including one from a claimant who has not verified yet, which would
   * otherwise reach the student on verification. Returns the cancelled rows.
   */
  fun cancelAllPendingFromShare(
    session: SqlSession,
    shareId: CostReportShareId,
  ): Result<List<SupporterLinkRequest>> =
    session.queryList(
      """
      UPDATE supporter_link_requests
      SET resolution = ?, resolved_at = NOW()
      WHERE cost_report_share_id = ? AND resolution IS NULL
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        stmt.setString(1, SupporterLinkRequestResolution.CANCELLED.value)
        stmt.setObject(2, shareId.value)
      },
      map = ::mapRequest,
    )

  /**
   * The self-request guard arrives typed by its NAME — a `P0001` like every
   * trigger refusal, so the SQLSTATE alone cannot say which rule fired. The
   * foreign keys name the endpoint that is missing.
   */
  private fun mapCreateError(e: SQLException): Exception {
    val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
    if (e.sqlState == SQLSTATE_RAISE_EXCEPTION && serverError?.constraint == NOT_SELF) {
      return ConstraintViolationException(e, serverError.constraint, serverError.detail, serverError.message ?: "Self-request refused")
    }
    return mapChildWriteError(e, FOREIGN_KEY_MESSAGES)
  }
}
