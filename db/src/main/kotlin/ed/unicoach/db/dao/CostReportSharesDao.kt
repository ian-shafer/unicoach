package ed.unicoach.db.dao

import ed.unicoach.db.models.CostReportShare
import ed.unicoach.db.models.CostReportShareId
import ed.unicoach.db.models.NewCostReportShare
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TokenHash
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Store for the student's revocable Family Cost Report share link (RFC 155).
 * Built on the [Creatable] capability for create; the remaining operations are
 * concrete methods. Modeled on [VerificationTokensDao] (a hashed credential):
 * only the SHA-256 hash is persisted; the raw token rides only in the link the
 * student sends.
 *
 * The difference from a verification token is lifetime. This credential has no
 * expiry and is read many times — a parent re-opens the link months later — so
 * the read path is a plain lookup rather than a claim, and the single mutation
 * is revocation.
 */
object CostReportSharesDao : Creatable<NewCostReportShare, CostReportShare> {
  private fun mapShare(rs: ResultSet): CostReportShare =
    CostReportShare(
      id = CostReportShareId(UUID.fromString(rs.getString("id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      tokenHash = TokenHash(rs.getBytes("token_hash")),
      revokedAt = rs.getInstantOrNull("revoked_at"),
      createdAt = rs.getInstant("created_at"),
    )

  /**
   * Mints the id a share row will be inserted under, from the same `uuidv7()`
   * generator the column default uses. The caller needs it BEFORE the insert
   * because the share token is derived from it (RFC 155): the row stores only
   * `SHA-256(token)`, and the token is `HMAC(secret, id)`, so the id is an
   * input to the value stored beside it.
   */
  fun nextId(session: SqlSession): Result<CostReportShareId> = session.nextUuidV7().map(::CostReportShareId)

  override fun create(
    session: SqlSession,
    input: NewCostReportShare,
  ): Result<CostReportShare> {
    val sql =
      """
      INSERT INTO cost_report_shares (id, student_id, token_hash)
      VALUES (?, ?, ?)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, input.id.value)
        stmt.setObject(2, input.studentId.value)
        stmt.setBytes(3, input.tokenHash.value)
      },
      map = ::mapShare,
      mapError = ::mapShareError,
    )
  }

  /**
   * Reads the student's live (unrevoked) share, or null when they have none.
   * Used by the mint path to honour "one live share per student": asking to
   * share twice returns the same link rather than minting a second one.
   *
   * NULL rather than [NotFoundException]: a student with nothing shared is the
   * ordinary state of every student who has not shared yet, not a failed read,
   * and [orNullOnNotFound] is the repo's own way of saying so — so no caller
   * has to hand-fold an exception back into an absence.
   */
  fun findLiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<CostReportShare?> =
    session
      .queryOne(
        "SELECT * FROM cost_report_shares WHERE student_id = ? AND revoked_at IS NULL",
        bind = { it.setObject(1, studentId.value) },
        map = ::mapShare,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()

  /**
   * Resolves a presented token hash to its live share, or null when nothing live
   * carries it. A revoked row is invisible here, exactly like an unknown hash —
   * the page must not be able to tell a stranger that a token was once real, and
   * a null that cannot say which case it is guarantees it cannot.
   */
  fun findLiveByTokenHash(
    session: SqlSession,
    tokenHash: TokenHash,
  ): Result<CostReportShare?> =
    session
      .queryOne(
        "SELECT * FROM cost_report_shares WHERE token_hash = ? AND revoked_at IS NULL",
        bind = { it.setBytes(1, tokenHash.value) },
        map = ::mapShare,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()

  /**
   * Compare-and-swap revoke: atomically stamps `revoked_at` on the student's one
   * live share and returns the revoked row, or null when there was nothing live
   * to revoke. Nothing live is an ordinary outcome — revoking twice is allowed
   * and the second call simply reports that it found nothing — so it is an
   * absence, never a failure.
   */
  fun revokeLive(
    session: SqlSession,
    studentId: StudentId,
  ): Result<CostReportShare?> {
    val sql =
      """
      UPDATE cost_report_shares
      SET revoked_at = NOW()
      WHERE student_id = ? AND revoked_at IS NULL
      RETURNING *
      """.trimIndent()
    return session
      .mutateReturning(
        sql,
        bind = { it.setObject(1, studentId.value) },
        map = ::mapShare,
        onNoRow = { NotFoundException() },
      ).orNullOnNotFound()
  }

  /**
   * The one-live-share index is a RACE OUTCOME, not a fault.
   *
   * `cost_report_shares_one_live_per_student_idx` refuses a second live row for
   * one student, so two share requests in flight together end with the loser's
   * insert rejected — and the loser's student HAS a link, minted by the winner.
   * Mapped to [ConstraintViolationException] (the `23505` shape every other DAO
   * here uses) rather than left to [mapDatabaseError]'s generic
   * [DatabaseException], so the caller can tell that outcome apart without
   * importing SQLSTATE strings.
   *
   * `23514` rides with it as the repo-wide pair every DAO here maps: this table
   * declares no CHECK constraint today, and one added later must arrive as the
   * same constraint failure rather than as an unclassified database fault.
   */
  private fun mapShareError(e: SQLException): Exception =
    when (e.sqlState) {
      "23505", "23514" -> ConstraintViolationException(e)
      else -> mapDatabaseError(e)
    }
}
