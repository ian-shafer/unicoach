package ed.unicoach.db.dao

import ed.unicoach.db.models.JsonParseFailureCategory
import ed.unicoach.db.models.LlmRequestId
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
  /** The flat count/failure column values an outcome variant maps to on insert. */
  private data class SynthesisOutcomeColumns(
    val commitmentsWritten: Int,
    val commitmentsDropped: Int,
    val failureCategory: String?,
    val failureReason: String?,
  )

  private fun mapRun(rs: ResultSet): SynthesisRun =
    SynthesisRun(
      id = SynthesisRunId(rs.getLong("id")),
      createdAt = rs.getInstant("created_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      outcome = mapOutcome(rs),
      systemPromptId = SystemPromptId(UUID.fromString(rs.getString("system_prompt_id"))),
      llmRequestId = LlmRequestId(rs.getLong("llm_request_id")),
    )

  /**
   * Reconstructs the [SynthesisOutcome] ADT from the flat outcome/count/failure
   * columns. The failure columns are non-null on a `failed` row by CHECK, so the
   * `Failed` construction is total; a corrupt row (raw SQL bypassing the app)
   * surfaces as an [SQLException] here, never a silent misread.
   */
  private fun mapOutcome(rs: ResultSet): SynthesisOutcome =
    when (val outcome = rs.getString("outcome")) {
      "applied" -> {
        SynthesisOutcome.Applied(
          commitmentsWritten = rs.getInt("commitments_written"),
          commitmentsDropped = rs.getInt("commitments_dropped"),
        )
      }

      "failed" -> {
        SynthesisOutcome.Failed(
          category = parseFailureCategory(rs.getString("failure_category")),
          reason = rs.getString("failure_reason"),
        )
      }

      else -> {
        throw SQLException("Persisted synthesis_runs.outcome is not a valid value: [$outcome]")
      }
    }

  private fun parseFailureCategory(value: String): JsonParseFailureCategory =
    JsonParseFailureCategory.fromValue(value)
      ?: throw SQLException("Persisted synthesis_runs.failure_category is not a valid value: [$value]")

  /** Appends one synthesis-run row (success or failure). */
  fun append(
    session: SqlSession,
    input: NewSynthesisRun,
  ): Result<SynthesisRun> = create(session, input)

  override fun create(
    session: SqlSession,
    input: NewSynthesisRun,
  ): Result<SynthesisRun> {
    // Destructure the outcome ADT into the flat columns: an Applied row carries
    // its write counts and null failure columns; a Failed row carries zero
    // counts and the failure category/reason. The exhaustive `when` forces every
    // variant to be handled, so a future third outcome fails to compile here
    // rather than silently writing default columns. The DAO is the sole boundary
    // between the ADT and the flat row + CHECK.
    val cols =
      when (val outcome = input.outcome) {
        is SynthesisOutcome.Applied -> {
          SynthesisOutcomeColumns(
            commitmentsWritten = outcome.commitmentsWritten,
            commitmentsDropped = outcome.commitmentsDropped,
            failureCategory = null,
            failureReason = null,
          )
        }

        is SynthesisOutcome.Failed -> {
          SynthesisOutcomeColumns(
            commitmentsWritten = 0,
            commitmentsDropped = 0,
            failureCategory = outcome.category.value,
            failureReason = outcome.reason,
          )
        }
      }
    return session.insertReturning(
      table = "synthesis_runs",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "outcome" to { stmt, i -> stmt.setString(i, input.outcome.value) },
          "system_prompt_id" to { stmt, i -> stmt.setObject(i, input.systemPromptId.value) },
          "llm_request_id" to { stmt, i -> stmt.setLong(i, input.llmRequestId.value) },
          "commitments_written" to { stmt, i -> stmt.setInt(i, cols.commitmentsWritten) },
          "commitments_dropped" to { stmt, i -> stmt.setInt(i, cols.commitmentsDropped) },
          "failure_category" to { stmt, i -> stmt.setStringOrNull(i, cols.failureCategory) },
          "failure_reason" to { stmt, i -> stmt.setStringOrNull(i, cols.failureReason) },
        ),
      map = ::mapRun,
      mapError = ::mapRunError,
    )
  }

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
          message.contains("synthesis_runs_llm_request_id_fkey") -> NotFoundException("LLM request not found")
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
