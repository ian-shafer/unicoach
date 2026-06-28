package ed.unicoach.db.dao

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.ExtractionRun
import ed.unicoach.db.models.ExtractionRunId
import ed.unicoach.db.models.NewExtractionRun
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPromptId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the append-only `extraction_runs` log (RFC 66).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. The log is insert-only; [watermark] reads the highest applied
 * target for a conversation, the idempotency anchor for incremental extraction.
 */
object ExtractionRunsDao :
  Findable<ExtractionRun, ExtractionRunId>,
  Listable<ExtractionRun>,
  Creatable<NewExtractionRun, ExtractionRun> {
  private fun mapRun(rs: ResultSet): ExtractionRun =
    ExtractionRun(
      id = ExtractionRunId(rs.getLong("id")),
      createdAt = rs.getInstant("created_at"),
      convoId = ConvoId(UUID.fromString(rs.getString("convo_id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      throughRequestId = ConvoRequestId(rs.getLong("through_request_id")),
      outcome = parseOutcome(rs.getString("outcome")),
      systemPromptId = SystemPromptId(UUID.fromString(rs.getString("system_prompt_id"))),
      provider = rs.getString("provider"),
      modelResolved = rs.getString("model_resolved"),
      observationsWritten = rs.getInt("observations_written"),
      claimsWritten = rs.getInt("claims_written"),
      claimsSuperseded = rs.getInt("claims_superseded"),
      inputTokens = rs.getInt("input_tokens").takeUnless { rs.wasNull() },
      outputTokens = rs.getInt("output_tokens").takeUnless { rs.wasNull() },
      cacheReadTokens = rs.getInt("cache_read_tokens").takeUnless { rs.wasNull() },
      cacheWriteTokens = rs.getInt("cache_write_tokens").takeUnless { rs.wasNull() },
    )

  private fun parseOutcome(value: String): ExtractionOutcome =
    ExtractionOutcome.fromValue(value)
      ?: throw SQLException("Persisted extraction_runs.outcome is not a valid value: \"$value\"")

  /** Appends one extraction-run row (success or failure). */
  fun append(
    session: SqlSession,
    input: NewExtractionRun,
  ): Result<ExtractionRun> = create(session, input)

  override fun create(
    session: SqlSession,
    input: NewExtractionRun,
  ): Result<ExtractionRun> =
    session.insertReturning(
      table = "extraction_runs",
      columns =
        linkedMapOf<String, Bind>(
          "convo_id" to { stmt, i -> stmt.setObject(i, input.convoId.value) },
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "through_request_id" to { stmt, i -> stmt.setLong(i, input.throughRequestId.value) },
          "outcome" to { stmt, i -> stmt.setString(i, input.outcome.value) },
          "system_prompt_id" to { stmt, i -> stmt.setObject(i, input.systemPromptId.value) },
          "provider" to { stmt, i -> stmt.setString(i, input.provider) },
          "model_resolved" to { stmt, i -> stmt.setStringOrNull(i, input.modelResolved) },
          "observations_written" to { stmt, i -> stmt.setInt(i, input.observationsWritten) },
          "claims_written" to { stmt, i -> stmt.setInt(i, input.claimsWritten) },
          "claims_superseded" to { stmt, i -> stmt.setInt(i, input.claimsSuperseded) },
          "input_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.inputTokens) },
          "output_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.outputTokens) },
          "cache_read_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.cacheReadTokens) },
          "cache_write_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.cacheWriteTokens) },
        ),
      map = ::mapRun,
      mapError = ::mapRunError,
    )

  /**
   * The conversation's extraction watermark: the highest `through_request_id`
   * over `applied` rows, or 0 when none. `failed` rows are ignored — they billed
   * tokens but did not advance the window.
   */
  fun watermark(
    session: SqlSession,
    convoId: ConvoId,
  ): Result<Long> =
    session.queryOne(
      """
      SELECT COALESCE(MAX(through_request_id), 0) AS watermark
      FROM extraction_runs
      WHERE convo_id = ? AND outcome = 'applied'
      """.trimIndent(),
      bind = { it.setObject(1, convoId.value) },
      map = { rs -> rs.getLong("watermark") },
    )

  /** Resolves one run by id; [NotFoundException] when no row matches. Read-only admin surface (RFC 77). */
  override fun findById(
    session: SqlSession,
    id: ExtractionRunId,
  ): Result<ExtractionRun> =
    session.queryOne(
      "SELECT * FROM extraction_runs WHERE id = ?",
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
  ): Result<List<ExtractionRun>> =
    session.queryList(
      """
      SELECT * FROM extraction_runs
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
   * `extraction_runs_student_idx`). Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<ExtractionRun>> =
    session.queryList(
      """
      SELECT * FROM extraction_runs
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
          message.contains("extraction_runs_convo_id_fkey") -> NotFoundException("Convo not found")
          message.contains("extraction_runs_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("extraction_runs_through_request_id_fkey") -> NotFoundException("Through request not found")
          message.contains("extraction_runs_system_prompt_id_fkey") -> NotFoundException("System prompt not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> ConstraintViolationException(e)

      else -> mapDatabaseError(e)
    }
}
