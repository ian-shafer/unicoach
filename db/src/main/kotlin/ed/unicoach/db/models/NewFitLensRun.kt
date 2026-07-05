package ed.unicoach.db.models

/**
 * Insert input for the `fit_lens_runs` log (RFC 98); omits the DB-generated id
 * and `created_at`. [suggestionsWritten] defaults to 0 (a `failed` run records
 * zero; the DB CHECK enforces this). The four token fields sum the pass's two
 * billed calls and are nullable (recorded when the provider reports usage);
 * [matchesConsidered] is null only when the retrieve never ran.
 */
data class NewFitLensRun(
  val studentId: StudentId,
  val outcome: FitLensOutcome,
  val querySystemPromptId: SystemPromptId,
  val reasonSystemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val suggestionsWritten: Int = 0,
  val matchesConsidered: Int? = null,
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheReadTokens: Int? = null,
  val cacheWriteTokens: Int? = null,
)
