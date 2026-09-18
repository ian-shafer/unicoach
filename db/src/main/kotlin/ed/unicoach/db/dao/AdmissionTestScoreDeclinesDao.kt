package ed.unicoach.db.dao

import ed.unicoach.db.models.AdmissionTestScoreDecline
import ed.unicoach.db.models.AdmissionTestScoreDeclineId
import ed.unicoach.db.models.StudentId
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over `admission_test_score_declines` (RFC 207): the live
 * refusal, and nothing else. Deliberately NOT a versioned aggregate -- the
 * `cost_report_shares` shape (RFC 155): its only mutations are insert and
 * setting `withdrawn_at` once, so no OCC version and no history table.
 */
object AdmissionTestScoreDeclinesDao {
  internal fun mapDecline(rs: ResultSet): AdmissionTestScoreDecline =
    AdmissionTestScoreDecline(
      id = AdmissionTestScoreDeclineId(UUID.fromString(rs.getString("id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      createdAt = rs.getInstant("created_at"),
      withdrawnAt = rs.getInstantOrNull("withdrawn_at"),
    )

  /** The student's current refusal, or [NotFoundException] when she has none. */
  fun findLiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<AdmissionTestScoreDecline> =
    session.queryOne(
      "SELECT * FROM admission_test_score_declines WHERE student_id = ? AND withdrawn_at IS NULL",
      bind = { it.setObject(1, studentId.value) },
      map = ::mapDecline,
      onNoRow = { NotFoundException("No live test-score decline for student [${studentId.value}]") },
    )

  /**
   * Records a refusal, idempotently: declining when a live refusal already
   * exists returns the existing row rather than inserting a second one. The
   * `ON CONFLICT ... DO UPDATE SET withdrawn_at = <same>` is a no-op update that
   * makes `RETURNING *` yield the existing row.
   */
  fun insertDecline(
    session: SqlSession,
    studentId: StudentId,
  ): Result<AdmissionTestScoreDecline> {
    val sql =
      """
      INSERT INTO admission_test_score_declines (student_id) VALUES (?)
      ON CONFLICT (student_id) WHERE withdrawn_at IS NULL DO UPDATE SET
        withdrawn_at = admission_test_score_declines.withdrawn_at
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt -> stmt.setObject(1, studentId.value) },
      map = ::mapDecline,
      mapError = ::mapCreateError,
    )
  }

  /**
   * Withdraws the live refusal, returning the withdrawn row, or
   * [NotFoundException] when there is none -- recording a score over a decline
   * the student never made should not fail silently.
   */
  fun withdrawLive(
    session: SqlSession,
    studentId: StudentId,
  ): Result<AdmissionTestScoreDecline> {
    val sql =
      """
      UPDATE admission_test_score_declines
      SET withdrawn_at = NOW()
      WHERE student_id = ? AND withdrawn_at IS NULL
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt -> stmt.setObject(1, studentId.value) },
      map = ::mapDecline,
      mapError = ::mapCreateError,
      onNoRow = { NotFoundException("No live test-score decline for student [${studentId.value}]") },
    )
  }

  private const val STUDENT_FK_CONSTRAINT = "admission_test_score_declines_student_id_fkey"

  private fun mapCreateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        if (serverError?.constraint == STUDENT_FK_CONSTRAINT) {
          NotFoundException(
            "Owning student not found (admission_test_score_declines.student_id FK" +
              (serverError.detail?.let { ": [$it]" } ?: "") + ")",
            cause = e,
          )
        } else {
          ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
        }
      }

      "23505", "23514" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
