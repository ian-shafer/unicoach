package ed.unicoach.db.dao

import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.FitLensRun
import ed.unicoach.db.models.FitLensRunId
import ed.unicoach.db.models.NewFitLensRun
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPromptId
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * Data-access layer over the append-only `fit_lens_runs` log (RFC 98).
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. The log is insert-only; [lastAppliedAt] reads the student's
 * fit-lens freshness marker (latest `created_at` over `applied` rows), and
 * [consecutiveFailuresSince] counts the `failed` runs since the last `applied`
 * run — the read-phase failure circuit breaker's input.
 */
object FitLensRunsDao :
  Findable<FitLensRun, FitLensRunId>,
  Listable<FitLensRun>,
  Creatable<NewFitLensRun, FitLensRun> {
  /** The flat suggestion/failure column values an outcome variant maps to on insert. */
  private data class FitLensOutcomeColumns(
    val suggestionsWritten: Int,
    val failureCategory: String?,
    val failureReason: String?,
  )

  private fun mapRun(rs: ResultSet): FitLensRun =
    FitLensRun(
      id = FitLensRunId(rs.getLong("id")),
      createdAt = rs.getInstant("created_at"),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      outcome = mapOutcome(rs),
      querySystemPromptId = SystemPromptId(UUID.fromString(rs.getString("query_system_prompt_id"))),
      reasonSystemPromptId = SystemPromptId(UUID.fromString(rs.getString("reason_system_prompt_id"))),
      provider = rs.getString("provider"),
      modelResolved = rs.getString("model_resolved"),
      matchesConsidered = rs.getInt("matches_considered").takeUnless { rs.wasNull() },
      inputTokens = rs.getInt("input_tokens").takeUnless { rs.wasNull() },
      outputTokens = rs.getInt("output_tokens").takeUnless { rs.wasNull() },
      cacheReadTokens = rs.getInt("cache_read_tokens").takeUnless { rs.wasNull() },
      cacheWriteTokens = rs.getInt("cache_write_tokens").takeUnless { rs.wasNull() },
    )

  /**
   * Reconstructs the [FitLensOutcome] ADT from the flat outcome/count/failure
   * columns. The failure columns are non-null on a `failed` row by CHECK, so the
   * `Failed` construction is total; a corrupt row (raw SQL bypassing the app)
   * surfaces as an [SQLException] here, never a silent misread.
   */
  private fun mapOutcome(rs: ResultSet): FitLensOutcome =
    when (val outcome = rs.getString("outcome")) {
      "applied" -> {
        FitLensOutcome.Applied(suggestionsWritten = rs.getInt("suggestions_written"))
      }

      "failed" -> {
        FitLensOutcome.Failed(
          category = parseFailureCategory(rs.getString("failure_category")),
          reason = rs.getString("failure_reason"),
        )
      }

      else -> {
        throw SQLException("Persisted fit_lens_runs.outcome is not a valid value: [$outcome]")
      }
    }

  private fun parseFailureCategory(value: String): FitLensFailureCategory =
    FitLensFailureCategory.fromValue(value)
      ?: throw SQLException("Persisted fit_lens_runs.failure_category is not a valid value: [$value]")

  /** Appends one fit-lens-run row (`applied` or `failed`). */
  fun append(
    session: SqlSession,
    input: NewFitLensRun,
  ): Result<FitLensRun> = create(session, input)

  override fun create(
    session: SqlSession,
    input: NewFitLensRun,
  ): Result<FitLensRun> {
    // Destructure the outcome ADT into the flat columns: an Applied row carries
    // its suggestions count and null failure columns; a Failed row carries zero
    // suggestions and the failure category/reason. The exhaustive `when` forces
    // every variant to be handled, so a future third outcome fails to compile
    // here rather than silently writing default columns. matches_considered
    // varies independently of the outcome, so it binds straight from the flat
    // field.
    val cols =
      when (val outcome = input.outcome) {
        is FitLensOutcome.Applied -> {
          FitLensOutcomeColumns(
            suggestionsWritten = outcome.suggestionsWritten,
            failureCategory = null,
            failureReason = null,
          )
        }

        is FitLensOutcome.Failed -> {
          FitLensOutcomeColumns(
            suggestionsWritten = 0,
            failureCategory = outcome.category.value,
            failureReason = outcome.reason,
          )
        }
      }
    return session.insertReturning(
      table = "fit_lens_runs",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "outcome" to { stmt, i -> stmt.setString(i, input.outcome.value) },
          "query_system_prompt_id" to { stmt, i -> stmt.setObject(i, input.querySystemPromptId.value) },
          "reason_system_prompt_id" to { stmt, i -> stmt.setObject(i, input.reasonSystemPromptId.value) },
          "provider" to { stmt, i -> stmt.setString(i, input.provider) },
          "model_resolved" to { stmt, i -> stmt.setStringOrNull(i, input.modelResolved) },
          "suggestions_written" to { stmt, i -> stmt.setInt(i, cols.suggestionsWritten) },
          "matches_considered" to { stmt, i -> stmt.setIntOrNull(i, input.matchesConsidered) },
          "input_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.inputTokens) },
          "output_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.outputTokens) },
          "cache_read_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.cacheReadTokens) },
          "cache_write_tokens" to { stmt, i -> stmt.setIntOrNull(i, input.cacheWriteTokens) },
          "failure_category" to { stmt, i -> stmt.setStringOrNull(i, cols.failureCategory) },
          "failure_reason" to { stmt, i -> stmt.setStringOrNull(i, cols.failureReason) },
        ),
      map = ::mapRun,
      mapError = ::mapRunError,
    )
  }

  /**
   * The student's fit-lens freshness marker: the latest `created_at` over
   * `applied` rows, or null when the student has never had a pass apply. `failed`
   * rows are ignored — they billed tokens but did not advance the marker (served
   * by `fit_lens_runs_student_applied_idx`).
   */
  fun lastAppliedAt(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Instant?> =
    session.queryOne(
      """
      SELECT MAX(created_at) AS last_applied_at
      FROM fit_lens_runs
      WHERE student_id = ? AND outcome = 'applied'
      """.trimIndent(),
      bind = { it.setObject(1, studentId.value) },
      map = { rs -> rs.getInstantOrNull("last_applied_at") },
    )

  /**
   * The count of `failed` runs since the student's last `applied` run — the
   * failure circuit-breaker input. Counts from the first run when the student has
   * never had a pass apply, and resets to 0 the moment the most recent run is
   * `applied`. Implemented as: `failed` runs whose `id` is greater than the max
   * `id` of any `applied` run (or all `failed` runs when none applied).
   */
  fun consecutiveFailuresSince(
    session: SqlSession,
    studentId: StudentId,
  ): Result<Int> =
    session.queryOne(
      """
      SELECT COUNT(*) AS failures
      FROM fit_lens_runs
      WHERE student_id = ?
        AND outcome = 'failed'
        AND id > COALESCE(
          (SELECT MAX(id) FROM fit_lens_runs WHERE student_id = ? AND outcome = 'applied'),
          0
        )
      """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setObject(2, studentId.value)
      },
      map = { rs -> rs.getInt("failures") },
    )

  /** Resolves one run by id; [NotFoundException] when no row matches. Read-only admin surface (RFC 77). */
  override fun findById(
    session: SqlSession,
    id: FitLensRunId,
  ): Result<FitLensRun> =
    session.queryOne(
      "SELECT * FROM fit_lens_runs WHERE id = ?",
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
  ): Result<List<FitLensRun>> =
    session.queryList(
      """
      SELECT * FROM fit_lens_runs
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
   * `fit_lens_runs_student_idx`). Read-only admin surface (RFC 77).
   */
  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    limit: Int,
    offset: Int,
  ): Result<List<FitLensRun>> =
    session.queryList(
      """
      SELECT * FROM fit_lens_runs
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
          message.contains("fit_lens_runs_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("fit_lens_runs_query_system_prompt_id_fkey") -> NotFoundException("Query system prompt not found")
          message.contains("fit_lens_runs_reason_system_prompt_id_fkey") -> NotFoundException("Reason system prompt not found")
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
