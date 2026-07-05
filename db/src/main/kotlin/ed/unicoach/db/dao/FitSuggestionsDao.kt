package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.FitSuggestion
import ed.unicoach.db.models.FitSuggestionForOpener
import ed.unicoach.db.models.FitSuggestionId
import ed.unicoach.db.models.FitSuggestionStatus
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.StudentId
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the mutable `fit_suggestions` entity (RFC 98).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. No optimistic-concurrency guard: `fit_suggestions` has no `version`
 * column (modeled on `commitments`); concurrent same-student passes serialize on
 * the student advisory lock ([AdvisoryLockDao]), not on OCC. The
 * `UNIQUE(student_id, college_id)` constraint is the deterministic novelty
 * backstop, surfaced from [create] as a [ConstraintViolationException].
 */
object FitSuggestionsDao :
  Findable<FitSuggestion, FitSuggestionId>,
  Listable<FitSuggestion>,
  Creatable<NewFitSuggestion, FitSuggestion> {
  internal fun mapSuggestion(rs: ResultSet): FitSuggestion =
    FitSuggestion(
      id = FitSuggestionId(UUID.fromString(rs.getString("id"))),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      collegeId = CollegeId(UUID.fromString(rs.getString("college_id"))),
      status = parseStatus(rs.getString("status")),
      rationale = rs.getString("rationale"),
      surfacedAt = rs.getInstantOrNull("surfaced_at"),
      surfacedInConvoId = rs.getString("surfaced_in_convo_id")?.let { ConvoId(UUID.fromString(it)) },
    )

  /**
   * Reconstructs the status from its persisted string. The DB CHECK already
   * guarantees a member value is stored, so a null here indicates row
   * corruption, surfaced as a [SQLException] (→ [DatabaseException]), never a
   * user-facing failure.
   */
  private fun parseStatus(value: String): FitSuggestionStatus =
    FitSuggestionStatus.fromValue(value)
      ?: throw SQLException("Persisted fit_suggestions.status is not a valid value: \"$value\"")

  override fun create(
    session: SqlSession,
    input: NewFitSuggestion,
  ): Result<FitSuggestion> =
    session.insertReturning(
      table = "fit_suggestions",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "college_id" to { stmt, i -> stmt.setObject(i, input.collegeId.value) },
          "rationale" to { stmt, i -> stmt.setString(i, input.rationale) },
        ),
      map = ::mapSuggestion,
      mapError = ::mapSuggestionError,
    )

  override fun findById(
    session: SqlSession,
    id: FitSuggestionId,
  ): Result<FitSuggestion> =
    session.queryOne(
      "SELECT * FROM fit_suggestions WHERE id = ?",
      bind = { it.setObject(1, id.value) },
      map = ::mapSuggestion,
    )

  /**
   * Every college id ever suggested to the student, regardless of status — the
   * write-time novelty recheck source for prior `fit_suggestions` (served by
   * `fit_suggestions_student_idx`).
   */
  fun listSuggestedCollegeIds(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<CollegeId>> =
    session.queryList(
      "SELECT college_id FROM fit_suggestions WHERE student_id = ? ORDER BY created_at, id",
      bind = { it.setObject(1, studentId.value) },
      map = { CollegeId(UUID.fromString(it.getString("college_id"))) },
    )

  /**
   * The opener read (delivery): the student's open suggestions joined to their
   * `colleges` display fields, ordered `created_at, id` (served by
   * `fit_suggestions_student_open_idx`). Surfaced rows are excluded.
   */
  fun listOpenForOpener(
    session: SqlSession,
    studentId: StudentId,
  ): Result<List<FitSuggestionForOpener>> =
    session.queryList(
      """
      SELECT fs.id AS id, c.name AS college_name, c.city AS city, c.state AS state, fs.rationale AS rationale
      FROM fit_suggestions fs
      JOIN colleges c ON c.id = fs.college_id
      WHERE fs.student_id = ? AND fs.status = 'open'
      ORDER BY fs.created_at, fs.id
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = { rs ->
        FitSuggestionForOpener(
          id = FitSuggestionId(UUID.fromString(rs.getString("id"))),
          collegeName = rs.getString("college_name"),
          city = rs.getString("city"),
          state = rs.getString("state"),
          rationale = rs.getString("rationale"),
        )
      },
    )

  /**
   * Marks a suggestion surfaced: sets `status='surfaced'`, `surfaced_at=NOW()`,
   * and `surfaced_in_convo_id=convoId` in one write so the row satisfies
   * `fit_suggestions_surfaced_consistency_check`. The `update_timestamp` trigger
   * bumps `updated_at`. [NotFoundException] when no row matches the id.
   */
  fun markSurfaced(
    session: SqlSession,
    id: FitSuggestionId,
    convoId: ConvoId,
  ): Result<FitSuggestion> =
    session.mutateReturning(
      """
      UPDATE fit_suggestions
      SET status = 'surfaced',
          surfaced_at = NOW(),
          surfaced_in_convo_id = ?
      WHERE id = ?
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, id.value)
      },
      map = ::mapSuggestion,
      mapError = ::mapSuggestionError,
    )

  /**
   * One page of a student's suggestions in all statuses, ordered `created_at, id`.
   * Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<FitSuggestion>> =
    session.queryList(
      """
      SELECT * FROM fit_suggestions
      WHERE student_id = ?
      ORDER BY created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapSuggestion,
    )

  /**
   * One page of suggestions across all students, ordered `row_created_at, id` so
   * paging is deterministic. Read-only admin surface (RFC 77).
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<FitSuggestion>> =
    session.queryList(
      """
      SELECT * FROM fit_suggestions
      ORDER BY row_created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapSuggestion,
    )

  private fun mapSuggestionError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("fit_suggestions_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("fit_suggestions_college_id_fkey") -> NotFoundException("Suggested college not found")
          message.contains("fit_suggestions_surfaced_in_convo_id_fkey") -> NotFoundException("Surfacing convo not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> {
        // Populate the violated constraint name and server DETAIL line
        // ([CollegesDao]'s precedent) so the write phase can discriminate the
        // benign novelty collision (`fit_suggestions_student_college_unique`)
        // from a genuine CHECK violation (e.g. an over-length rationale) rather
        // than collapsing both into a silent no-op.
        val serverError = (e as? PSQLException)?.serverErrorMessage
        ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
