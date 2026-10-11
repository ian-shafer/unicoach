package ed.unicoach.db.dao

import ed.unicoach.db.models.InviterRole
import ed.unicoach.db.models.NewSupporterInvite
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.SupporterInvite
import ed.unicoach.db.models.SupporterInviteId
import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

/**
 * Single-use supporter-invite credential store (RFC 232).
 * Built on the [Creatable] capability; modelled on [VerificationTokensDao] and
 * [CostReportSharesDao]. Only the SHA-256 hash is persisted; the raw token
 * rides only in the invite link.
 */
object SupporterInvitesDao : Creatable<NewSupporterInvite, SupporterInvite> {
  /**
   * An invite is LIVE while it is neither consumed nor revoked nor past its
   * expiry — the security definition of this table, so it is written ONCE and
   * every statement asks the identical question. Three copies is how `revoke`
   * came to omit the expiry clause its two siblings carried.
   */
  private const val LIVE_PREDICATE = "consumed_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()"

  /**
   * The three foreign keys out of this child table, each with the sentence a
   * refusal answers with. A `23503` names the constraint it violated, so the
   * caller learns WHICH endpoint went missing rather than meeting an
   * unclassified database fault.
   */
  private val FOREIGN_KEY_MESSAGES =
    mapOf(
      "supporter_invites_inviter_user_id_fkey" to "Inviting user not found",
      "supporter_invites_student_id_fkey" to "Inviting student not found",
      "supporter_invites_supporter_id_fkey" to "Inviting supporter not found",
    )

  private fun mapInvite(rs: ResultSet): SupporterInvite {
    val id = SupporterInviteId(UUID.fromString(rs.getString("id")))
    val studentIdStr = rs.getString("student_id")
    val supporterIdStr = rs.getString("supporter_id")
    val relStr = rs.getString("relationship")
    val roleStr = rs.getString("inviter_role")

    val relationship =
      SupporterRelationship.fromValue(relStr)
        ?: throw corruptValue(relStr, "SupporterRelationship", "supporter_invites.relationship (row [${id.asString}])")
    val inviterRole =
      InviterRole.fromValue(roleStr)
        ?: throw corruptValue(roleStr, "InviterRole", "supporter_invites.inviter_role (row [${id.asString}])")

    return SupporterInvite(
      id = id,
      tokenHash = TokenHash(rs.getBytes("token_hash")),
      inviterUserId = UserId(UUID.fromString(rs.getString("inviter_user_id"))),
      inviterRole = inviterRole,
      studentId = studentIdStr?.let { StudentId(UUID.fromString(it)) },
      supporterId = supporterIdStr?.let { SupporterId(UUID.fromString(it)) },
      relationship = relationship,
      expiresAt = rs.getInstant("expires_at"),
      consumedAt = rs.getInstantOrNull("consumed_at"),
      revokedAt = rs.getInstantOrNull("revoked_at"),
      createdAt = rs.getInstant("created_at"),
    )
  }

  override fun create(
    session: SqlSession,
    input: NewSupporterInvite,
  ): Result<SupporterInvite> {
    val sql =
      """
      INSERT INTO supporter_invites (
        token_hash, inviter_user_id, inviter_role, student_id, supporter_id, relationship, expires_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setBytes(1, input.tokenHash.value)
        stmt.setObject(2, input.inviterUserId.value)
        stmt.setString(3, input.inviterRole.value)
        stmt.setObject(4, input.studentId?.value)
        stmt.setObject(5, input.supporterId?.value)
        stmt.setString(6, input.relationship.value)
        stmt.setTimestamp(7, Timestamp.from(input.expiresAt))
      },
      map = ::mapInvite,
      mapError = ::mapInviteError,
    )
  }

  fun findByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<SupporterInvite?> =
    session
      .queryOne(
        "SELECT * FROM supporter_invites WHERE token_hash = ?",
        bind = { it.setBytes(1, tokenHash.value) },
        map = ::mapInvite,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()

  fun findLiveByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<SupporterInvite?> =
    session
      .queryOne(
        "SELECT * FROM supporter_invites WHERE token_hash = ? AND $LIVE_PREDICATE",
        bind = { it.setBytes(1, tokenHash.value) },
        map = ::mapInvite,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()

  /**
   * The LIVE invites this STUDENT minted, oldest first (RFC 240).
   *
   * A LIST and not a row or a count: an inviter may hold several live invites at
   * once by design (RFC 232 §2 — a student with two parents invites both), so
   * there is nothing here for a singular read to return, and the caller that
   * only asks "any?" reads `isEmpty()` while the surface that lists them later
   * needs the rows themselves.
   *
   * Served by `supporter_invites_student_id_active_idx`, whose predicate is
   * `consumed_at IS NULL AND revoked_at IS NULL` — one term short of [LIVE_PREDICATE],
   * which is why the expiry clause must stay in the SQL and cannot be inherited
   * from the index. This is the STUDENT endpoint only: a supporter-minted invite
   * names `supporter_id` and is not this student's to hold.
   */
  fun findLiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<SupporterInvite>> =
    session.queryList(
      """
      SELECT * FROM supporter_invites
      WHERE student_id = ? AND $LIVE_PREDICATE
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapInvite,
    )

  fun consume(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<SupporterInvite?> {
    val sql =
      """
      UPDATE supporter_invites
      SET consumed_at = NOW()
      WHERE token_hash = ? AND $LIVE_PREDICATE
      RETURNING *
      """.trimIndent()
    return session
      .mutateReturning(
        sql,
        bind = { it.setBytes(1, tokenHash.value) },
        map = ::mapInvite,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()
  }

  fun revoke(
    session: SqlSession,
    id: SupporterInviteId,
    inviterUserId: UserId,
  ): Result<SupporterInvite?> {
    val sql =
      """
      UPDATE supporter_invites
      SET revoked_at = NOW()
      WHERE id = ? AND inviter_user_id = ? AND $LIVE_PREDICATE
      RETURNING *
      """.trimIndent()
    return session
      .mutateReturning(
        sql,
        bind = { stmt ->
          stmt.setObject(1, id.value)
          stmt.setObject(2, inviterUserId.value)
        },
        map = ::mapInvite,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()
  }

  /**
   * Revokes every LIVE invite [inviterUserId] minted, in either role (RFC 252) —
   * the account close's write. Returns the revoked rows; empty when there were
   * none.
   *
   * Keyed on `inviter_user_id`, the one column every invite carries whoever
   * minted it, so a dual-role account's student-minted and supporter-minted
   * invites go together. A closed inviter's invite is already refused at accept
   * time; revoking it makes the row say so too.
   */
  fun revokeAllByInviter(
    session: SqlSession,
    inviterUserId: UserId,
  ): Result<List<SupporterInvite>> =
    session.queryList(
      """
      UPDATE supporter_invites
      SET revoked_at = NOW()
      WHERE inviter_user_id = ? AND $LIVE_PREDICATE
      RETURNING *
      """.trimIndent(),
      bind = { it.setObject(1, inviterUserId.value) },
      map = ::mapInvite,
    )

  /**
   * The shared child-table write mapping, not a fourth private copy of it: this
   * table has three foreign keys, and the copy this replaced had no `23503` arm
   * at all, so a missing endpoint arrived as an unclassified fault.
   */
  private fun mapInviteError(e: SQLException): Exception = mapChildWriteError(e, FOREIGN_KEY_MESSAGES)
}
