package ed.unicoach.db.models

/**
 * Insert input for the `fit_lens_runs` log (RFC 98); omits the DB-generated id
 * and `created_at`. [outcome] is the sealed [FitLensOutcome] ADT (RFC 101): an
 * `Applied` carries [FitLensOutcome.Applied.suggestionsWritten], a `Failed` the
 * failure category/reason — an `applied`-with-a-reason or a `failed`-with-a-count
 * cannot be constructed. The four token fields sum the pass's two billed calls
 * and are nullable (recorded when the provider reports usage); [matchesConsidered]
 * is null only when the retrieve never ran. Both vary independently of the
 * outcome, so they stay flat top-level fields.
 */
data class NewFitLensRun(
  val studentId: StudentId,
  val outcome: FitLensOutcome,
  val querySystemPromptId: SystemPromptId,
  val reasonSystemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val matchesConsidered: Int? = null,
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheReadTokens: Int? = null,
  val cacheWriteTokens: Int? = null,
)
