package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSynthesisRun
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SynthesisRun
import ed.unicoach.db.models.SynthesisRunId
import ed.unicoach.db.models.SystemPromptId
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * Data-access layer over the append-only `synthesis_runs` log (RFC 93).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. The log is insert-only; [lastAppliedAt] reads the student's
 * synthesis freshness marker — the latest `created_at` over `applied` rows — the
 * anchor the freshness gate and write-time lost-race re-check key on.
 */
object SynthesisRunsDao :
  Findable<SynthesisRun, SynthesisRunId>,
  Listable<SynthesisRun>,
  Creatable<NewSynthesisRun, SynthesisRun> {
  private fun mapRun(rs: ResultSet): SynthesisRun =
    SynthesisRun(
      id = SynthesisRunId(rs.getLong("id")),
      createdAt = rs.getInstant("created_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      outcome = parseOutcome(rs.getString("outcome")),
      systemPromptId = SystemPromptId(UUID.fromString(rs.getString("system_prompt_id"))),
      provider = rs.getString("provider"),
      modelResolved = rs.getString("model_resolved"),
      commitmentsWritten = rs.getInt("commitments_written"),
      commitmentsDropped = rs.getInt("commitments_dropped"),
      inputTokens = rs.getInt("input_tokens").takeUnless { rs.wasNull() },
      outputTokens = rs.getInt("output_tokens").takeUnless { rs.wasNull() },
      cacheReadTokens = rs.getInt("cache_read_tokens").takeUnless { rs.wasNull() },
      cacheWriteTokens = rs.getInt("cache_write_tokens").takeUnless { rs.wasNull() },
    )

  private fun parseOutcome(value: String): SynthesisOutcome =
    SynthesisOutcome.fromValue(value)
      ?: throw SQLException("Persisted synthesis_runs.outcome is not a valid value: \"$value\"")

  /** Appends one synthesis-run row (success or failure). */
  fun append(
    session: SqlSession,
    input: NewSynthesisRun,
  ): Result<SynthesisRun> = create(session, input)

  override fun create(
    session: SqlSession,
    input: NewSynthesisRun,
  ): Result<SynthesisRun> =
    session.insertReturning(
      table = "synthesis_runs",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "outcome" to { stmt, i -> stmt.setString(i, input.outcome.value) },
          "system_prompt_id" to { stmt, i -> stmt.setObject(i, input.systemPromptId.value) },
          "provider" to { stmt, i -> stmt.setString(i, input.provider) },
          "model_resolved" to { stmt, i -> stmt.setStringOrNull(i, input.modelResolved) },
          "commitments_written" to { stmt, i -> stmt.setInt(i, input.commitmentsWritten) },
          "commitments_dropped" to { stmt, i -> stmt.setInt(i, input.commitmentsDropped) },
          "input_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.inputTokens) },
          "output_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.outputTokens) },
          "cache_read_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.cacheReadTokens) },
          "cache_write_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.cacheWriteTokens) },
        ),
      map = ::mapRun,
      mapError = ::mapRunError,
    )

  /**
   * The student's synthesis freshness marker: the latest `created_at` over
   * `applied` rows, or null when the student has never had a pass apply. `failed`
   * rows are ignored — they billed tokens but did not advance the marker (served
   * by `synthesis_runs_student_applied_idx`).
   */
  fun lastAppliedAt(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Instant?> =
    session.queryOne(
      """
      SELECT MAX(created_at) AS last_applied_at
      FROM synthesis_runs
      WHERE student_id = ? AND outcome = 'applied'
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = { rs -> rs.getInstantOrNull("last_applied_at") },
    )

  /** Resolves one run by id; [NotFoundException] when no row matches. Read-only admin surface (RFC 77). */
  override fun findById(
    session: SqlSession,
    id: SynthesisRunId,
  ): Result<SynthesisRun> =
    session.queryOne(
      "SELECT * FROM synthesis_runs WHERE id = ?",
      bind = { it.setLong(1, id.value) },
      map = ::mapRun,
    )

  /**
   * One page of runs across all students, ordered `id` (monotonic with insertion
   * on the `BIGINT IDENTITY` key) so paging is deterministic. Read-only admin
   * surface (RFC 77).
   */
  override fun list(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<SynthesisRun>> =
    session.queryList(
      """
      SELECT * FROM synthesis_runs
      ORDER BY id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapRun,
    )

  /**
   * One bounded page of a student's runs, ordered `created_at, id` (served by
   * `synthesis_runs_student_idx`). Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<SynthesisRun>> =
    session.queryList(
      """
      SELECT * FROM synthesis_runs
      WHERE student_id = ?
      ORDER BY created_at, id
      LIMIT ? OFFSET ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapRun,
    )

  private fun mapRunError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("synthesis_runs_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("synthesis_runs_system_prompt_id_fkey") -> NotFoundException("System prompt not found")
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
