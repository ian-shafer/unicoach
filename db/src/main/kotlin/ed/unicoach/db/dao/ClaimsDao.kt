package ed.unicoach.db.dao

import ed.unicoach.db.models.Claim
import ed.unicoach.db.models.ClaimId
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimRevision
import ed.unicoach.db.models.ClaimStatus
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.StudentId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the mutable `claims` entity (RFC 66). Stateless
 * `object`, one [SqlSession] per call, transaction boundaries owned by the
 * caller. No optimistic-concurrency guard: `claims` has no `version` column
 * (RFC 66 disabled versioning); concurrent same-student passes serialize on the
 * student advisory lock ([AdvisoryLockDao]), not on OCC.
 */
object ClaimsDao :
  Findable<Claim, ClaimId>,
  Listable<Claim>,
  Creatable<NewClaim, Claim> {
  internal fun mapClaim(rs: ResultSet): Claim =
    Claim(
      id = ClaimId(UUID.fromString(rs.getString("id"))),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      origin = parseEnum(rs.getString("origin"), ClaimOrigin::fromValue, "origin"),
      status = parseEnum(rs.getString("status"), ClaimStatus::fromValue, "status"),
      kind = parseEnum(rs.getString("kind"), ClaimKind::fromValue, "kind"),
      subject = parseEnum(rs.getString("subject"), ClaimSubject::fromValue, "subject"),
      topic = parseEnum(rs.getString("topic"), ClaimTopic::fromValue, "topic"),
      visibility = parseEnum(rs.getString("visibility"), ClaimVisibility::fromValue, "visibility"),
      statement = rs.getString("statement"),
      confidence = rs.getInt("confidence"),
      supersededById = rs.getString("superseded_by_id")?.let { ClaimId(UUID.fromString(it)) },
      supersededAt = rs.getInstantOrNull("superseded_at"),
      retractedAt = rs.getInstantOrNull("retracted_at"),
    )

  /**
   * Reconstructs an enum from its persisted string. The DB CHECK already
   * guarantees a member value is stored, so a null here indicates row
   * corruption, surfaced as a [SQLException] (→ [DatabaseException]), never a
   * user-facing failure.
   */
  internal fun <E> parseEnum(
    value: String,
    fromValue: (String) -> E?,
    column: String,
  ): E =
    fromValue(value)
      ?: throw SQLException("Persisted claims.$column is not a valid enum value: \"$value\"")

  override fun create(
    session: SqlSession,
    input: NewClaim,
  ): Result<Claim> =
    session.insertReturning(
      table = "claims",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "origin" to { stmt, i -> stmt.setString(i, input.origin.value) },
          "kind" to { stmt, i -> stmt.setString(i, input.kind.value) },
          "subject" to { stmt, i -> stmt.setString(i, input.subject.value) },
          "topic" to { stmt, i -> stmt.setString(i, input.topic.value) },
          "visibility" to { stmt, i -> stmt.setString(i, input.visibility.value) },
          "statement" to { stmt, i -> stmt.setString(i, input.statement) },
        ),
      map = ::mapClaim,
      mapError = ::mapClaimError,
    )

  override fun findById(
    session: SqlSession,
    id: ClaimId,
  ): Result<Claim> =
    session.queryOne(
      "SELECT * FROM claims WHERE id = ?",
      bind = { it.setObject(1, id.value) },
      map = ::mapClaim,
    )

  /**
   * One page of claims across all students, ordered `row_created_at, id` so paging
   * is deterministic. Read-only admin surface (RFC 77).
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<Claim>> =
    session.queryList(
      """
      SELECT * FROM claims
      ORDER BY row_created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapClaim,
    )

  /**
   * One page of a student's claims in *all* statuses (distinct from the active-only
   * [listActiveByStudent]), ordered `created_at, id`. Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<Claim>> =
    session.queryList(
      """
      SELECT * FROM claims
      WHERE student_id = ?
      ORDER BY created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapClaim,
    )

  /** The student's active claims (the hot read, served by `claims_student_active_idx`). */
  fun listActiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<Claim>> =
    session.queryList(
      """
      SELECT * FROM claims
      WHERE student_id = ? AND status = 'active'
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapClaim,
    )

  /**
   * Revises a claim's lifecycle: sets `status`, `confidence`, and the
   * supersession/retraction pointers consistent with the new [ClaimRevision.status]
   * so the DB lifecycle-consistency CHECKs hold. The `update_timestamp` trigger
   * bumps `updated_at`. [NotFoundException] when no row matches the id.
   */
  fun revise(
    session: SqlSession,
    id: ClaimId,
    revision: ClaimRevision,
  ): Result<Claim> {
    // superseded_at / retracted_at are derived from the target status (NOW() or
    // NULL) so the row always satisfies claims_superseded_consistency_check and
    // claims_retracted_consistency_check.
    val supersededAtClause =
      if (revision.status == ClaimStatus.SUPERSEDED) "NOW()" else "NULL"
    val retractedAtClause =
      if (revision.status == ClaimStatus.RETRACTED) "NOW()" else "NULL"
    val sql =
      """
      UPDATE claims
      SET status = ?,
          confidence = ?,
          superseded_by_id = ?,
          superseded_at = $supersededAtClause,
          retracted_at = $retractedAtClause
      WHERE id = ?
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setString(1, revision.status.value)
        stmt.setInt(2, revision.confidence)
        if (revision.supersededById != null) {
          stmt.setObject(3, revision.supersededById.value)
        } else {
          stmt.setNull(3, java.sql.Types.OTHER)
        }
        stmt.setObject(4, id.value)
      },
      map = ::mapClaim,
      mapError = ::mapClaimError,
    )
  }

  private fun mapClaimError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("claims_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("claims_superseded_by_id_fkey") -> NotFoundException("Superseding claim not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> {
        ConstraintViolationException(e)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
