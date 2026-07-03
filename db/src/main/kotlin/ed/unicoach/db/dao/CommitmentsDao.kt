package ed.unicoach.db.dao

import ed.unicoach.db.models.Commitment
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CommitmentStatus
import ed.unicoach.db.models.CommitmentTriggerKind
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.StudentId
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.util.UUID

/**
 * Data-access layer over the mutable `commitments` entity (RFC 93). Stateless
 * `object`, one [SqlSession] per call, transaction boundaries owned by the
 * caller. No optimistic-concurrency guard: `commitments` has no `version` column
 * (modeled on `claims`); concurrent same-student passes serialize on the student
 * advisory lock ([AdvisoryLockDao]), not on OCC. Lifecycle transitions
 * ([markFulfilled], [drop]) set the resolution columns consistently so the DB
 * lifecycle-consistency CHECKs hold.
 */
object CommitmentsDao :
  Findable<Commitment, CommitmentId>,
  Listable<Commitment>,
  Creatable<NewCommitment, Commitment> {
  internal fun mapCommitment(rs: ResultSet): Commitment =
    Commitment(
      id = CommitmentId(UUID.fromString(rs.getString("id"))),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      lens = parseEnum(rs.getString("lens"), CommitmentLens::fromValue, "lens"),
      disclosure = parseEnum(rs.getString("disclosure"), CommitmentDisclosure::fromValue, "disclosure"),
      status = parseEnum(rs.getString("status"), CommitmentStatus::fromValue, "status"),
      statement = rs.getString("statement"),
      triggerKind = parseEnum(rs.getString("trigger_kind"), CommitmentTriggerKind::fromValue, "trigger_kind"),
      triggerAt = rs.getInstantOrNull("trigger_at"),
      fulfilledAt = rs.getInstantOrNull("fulfilled_at"),
      disclosedInConvoId = rs.getString("disclosed_in_convo_id")?.let { ConvoId(UUID.fromString(it)) },
      droppedAt = rs.getInstantOrNull("dropped_at"),
      dropReason = rs.getString("drop_reason"),
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
      ?: throw SQLException("Persisted commitments.$column is not a valid enum value: \"$value\"")

  override fun create(
    session: SqlSession,
    input: NewCommitment,
  ): Result<Commitment> =
    session.insertReturning(
      table = "commitments",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "lens" to { stmt, i -> stmt.setString(i, input.lens.value) },
          "disclosure" to { stmt, i -> stmt.setString(i, input.disclosure.value) },
          "statement" to { stmt, i -> stmt.setString(i, input.statement) },
          "trigger_at" to { stmt, i ->
            if (input.triggerAt !=
              null
            ) {
              stmt.setTimestamp(i, Timestamp.from(input.triggerAt))
            } else {
              stmt.setNull(i, java.sql.Types.TIMESTAMP)
            }
          },
        ),
      map = ::mapCommitment,
      mapError = ::mapCommitmentError,
    )

  override fun findById(
    session: SqlSession,
    id: CommitmentId,
  ): Result<Commitment> =
    session.queryOne(
      "SELECT * FROM commitments WHERE id = ?",
      bind = { it.setObject(1, id.value) },
      map = ::mapCommitment,
    )

  /**
   * The student's open commitments in all disclosures (synthesis dedup context +
   * stale-drop candidates), ordered `created_at, id` (served by
   * `commitments_student_status_idx`).
   */
  fun listOpenByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<Commitment>> =
    session.queryList(
      """
      SELECT * FROM commitments
      WHERE student_id = ? AND status = 'open'
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapCommitment,
    )

  /**
   * The opener read (delivery): the student's open *explicit* commitments,
   * ordered `created_at, id` (served by `commitments_student_open_explicit_idx`).
   * Internal commitments are excluded — they are never surfaced.
   */
  fun listOpenExplicitByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<Commitment>> =
    session.queryList(
      """
      SELECT * FROM commitments
      WHERE student_id = ? AND status = 'open' AND disclosure = 'explicit'
      ORDER BY created_at, id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = ::mapCommitment,
    )

  /**
   * Marks a commitment fulfilled: sets `status='fulfilled'`, `fulfilled_at=NOW()`,
   * and `disclosed_in_convo_id=convoId` in one write so the row satisfies
   * `commitments_fulfilled_consistency_check`. The `update_timestamp` trigger
   * bumps `updated_at`. [NotFoundException] when no row matches the id.
   */
  fun markFulfilled(
    session: SqlSession,
    id: CommitmentId,
    convoId: ConvoId,
  ): Result<Commitment> =
    session.mutateReturning(
      """
      UPDATE commitments
      SET status = 'fulfilled',
          fulfilled_at = NOW(),
          disclosed_in_convo_id = ?
      WHERE id = ?
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, id.value)
      },
      map = ::mapCommitment,
      mapError = ::mapCommitmentError,
    )

  /**
   * Drops a commitment: sets `status='dropped'`, `dropped_at=NOW()`, and
   * `drop_reason=reason` in one write so the row satisfies
   * `commitments_dropped_consistency_check`. [NotFoundException] when no row
   * matches the id.
   */
  fun drop(
    session: SqlSession,
    id: CommitmentId,
    reason: String,
  ): Result<Commitment> =
    session.mutateReturning(
      """
      UPDATE commitments
      SET status = 'dropped',
          dropped_at = NOW(),
          drop_reason = ?
      WHERE id = ?
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        stmt.setString(1, reason)
        stmt.setObject(2, id.value)
      },
      map = ::mapCommitment,
      mapError = ::mapCommitmentError,
    )

  /**
   * One page of a student's commitments in *all* statuses, ordered `created_at, id`.
   * Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<Commitment>> =
    session.queryList(
      """
      SELECT * FROM commitments
      WHERE student_id = ?
      ORDER BY created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapCommitment,
    )

  /**
   * One page of commitments across all students, ordered `row_created_at, id` so
   * paging is deterministic. Read-only admin surface (RFC 77).
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<Commitment>> =
    session.queryList(
      """
      SELECT * FROM commitments
      ORDER BY row_created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapCommitment,
    )

  private fun mapCommitmentError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("commitments_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("commitments_disclosed_in_convo_id_fkey") -> NotFoundException("Disclosing convo not found")
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
