package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestScore
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AdmissionTestScoreId
import ed.unicoach.db.models.NewAdmissionTestScore
import ed.unicoach.db.models.PartialDateColumns
import ed.unicoach.db.models.StudentId
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * Data-access layer over the versioned mutable `admission_test_scores` entity
 * (RFC 207): one row per (student, component, sitting), soft-deleted rather
 * than erased. Stateless `object`, one [SqlSession] per call, transaction
 * boundaries owned by the caller.
 *
 * The sitting is stored on RFC 209's shape (`taken_on` DATE at the period
 * start, `taken_precision`) and decoded here to the [ed.unicoach.db.models
 * .PartialDate] every layer above holds. [PartialDateColumns] is the only
 * reader of the pair; a disagreeing pair is row corruption and is refused, not
 * silently shortened.
 */
object AdmissionTestScoresDao {
  internal fun mapScore(rs: ResultSet): AdmissionTestScore {
    val id = AdmissionTestScoreId(UUID.fromString(rs.getString("id")))
    val componentRaw = rs.getString("component")
    val component =
      AdmissionTestScoreComponent.fromValue(componentRaw)
        ?: throw CorruptPersistedValueException(
          componentRaw,
          ed.unicoach.common.models.ValidationError
            .InvalidFormat(expected = "a known AdmissionTestScoreComponent value"),
          location = "admission_test_scores.component (row [${id.value}])",
        )
    val testRaw = rs.getString("test")
    val test =
      AdmissionTest.fromValue(testRaw)
        ?: throw CorruptPersistedValueException(
          testRaw,
          ed.unicoach.common.models.ValidationError
            .InvalidFormat(expected = "a known AdmissionTest value"),
          location = "admission_test_scores.test (row [${id.value}])",
        )
    val sitting =
      when (val decoded = PartialDateColumns.decode(rs.getDate("taken_on").toLocalDate(), rs.getString("taken_precision"))) {
        is ValidationResult.Valid -> decoded.value

        is ValidationResult.Invalid -> throw CorruptPersistedValueException(
          value = "taken_on=${rs.getDate("taken_on")} taken_precision=${rs.getString("taken_precision")}",
          error = decoded.error,
        )
      }
    return AdmissionTestScore(
      id = id,
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      test = test,
      component = component,
      score = rs.getInt("score"),
      sitting = sitting,
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )
  }

  /** The student's live scores, newest sitting first. The only read in the tree. */
  fun listLiveByStudent(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<AdmissionTestScore>> =
    session.queryList(
      "SELECT * FROM admission_test_scores WHERE student_id = ? AND deleted_at IS NULL " +
        "ORDER BY taken_on DESC, id",
      bind = { it.setObject(1, studentId.value) },
      map = ::mapScore,
    )

  /**
   * Atomic create-or-update of one (component, sitting): one
   * `INSERT ... ON CONFLICT (student_id, component, taken_on) WHERE deleted_at
   * IS NULL DO UPDATE` (Postgres infers the partial unique index), so a
   * correction updates the row rather than leaving two answers to one question,
   * and two concurrent first writes cannot race into a raw uniqueness error.
   *
   * `taken_precision` is NOT in the conflict target: saying "March 2026" and
   * then "March 14, 2026" for the same component is one correction -- both
   * store 2026-03-01, and the second writes the day precision onto the first
   * row. Two sittings in the same year, with only the year known, collapse to
   * one row, which is the honest statement of what we hold.
   */
  fun upsertForStudent(
    session: SqlSession,
    input: NewAdmissionTestScore,
  ): Result<AdmissionTestScore> {
    val sql =
      """
      INSERT INTO admission_test_scores (
        student_id, test, component, score, taken_on, taken_precision
      )
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (student_id, component, taken_on) WHERE deleted_at IS NULL DO UPDATE SET
        version = admission_test_scores.version + 1,
        score = EXCLUDED.score,
        taken_precision = EXCLUDED.taken_precision
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, input.studentId.value)
        stmt.setString(2, input.test.value)
        stmt.setString(3, input.component.value)
        stmt.setInt(4, input.score)
        stmt.setObject(5, PartialDateColumns.periodStart(input.sitting))
        stmt.setString(6, PartialDateColumns.precision(input.sitting).value)
      },
      map = ::mapScore,
      mapError = ::mapCreateUpdateError,
    )
  }

  /**
   * Soft-deletes the live row for one (component, sitting), or fails with
   * [NotFoundException] when there is none -- a remove that did not remove
   * anything is a call the model should be told about, not a silent success.
   */
  fun removeForStudent(
    session: SqlSession,
    studentId: StudentId,
    component: AdmissionTestScoreComponent,
    sitting: ed.unicoach.db.models.PartialDate,
  ): Result<AdmissionTestScore> {
    val sql =
      """
      UPDATE admission_test_scores
      SET deleted_at = NOW(), version = admission_test_scores.version + 1
      WHERE student_id = ? AND component = ? AND taken_on = ? AND deleted_at IS NULL
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setString(2, component.value)
        stmt.setObject(3, PartialDateColumns.periodStart(sitting))
      },
      map = ::mapScore,
      mapError = ::mapCreateUpdateError,
      onNoRow = {
        NotFoundException("No live score for student [${studentId.value}] component [${component.value}] sitting [${sitting.toIso()}]")
      },
    )
  }

  /** The `admission_test_scores.student_id` FK, whose violation is the expected owning-student-vanished outcome. */
  private const val STUDENT_FK_CONSTRAINT = "admission_test_scores_student_id_fkey"

  /**
   * SQLSTATE discrimination for create/update operations, on
   * [MoneyProfilesDao.mapCreateUpdateError]'s shape: `23503` on the student FK
   * is an expected miss ([NotFoundException]); any other `23503` is an
   * integrity defect; `23505`/`23514` carry the violated constraint name and
   * server DETAIL so a caller can tell the active-uniqueness violation from the
   * scale/component CHECKs.
   */
  private fun mapCreateUpdateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val serverError = (e as? PSQLException)?.serverErrorMessage
        if (serverError?.constraint == STUDENT_FK_CONSTRAINT) {
          NotFoundException(
            "Owning student not found (admission_test_scores.student_id FK" +
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
