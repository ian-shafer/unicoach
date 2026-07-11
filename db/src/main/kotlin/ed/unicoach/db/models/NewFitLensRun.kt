package ed.unicoach.db.models

/**
 * Insert input for the `fit_lens_runs` log (RFC 98); omits the DB-generated id
 * and `created_at`. [outcome] is the sealed [FitLensOutcome] ADT (RFC 101).
 * A pass makes up to two billed calls, referenced by [queryLlmRequestId] and
 * [reasonLlmRequestId] (RFC 106). Every write path always has a query call (the
 * query call is made before any run row is written), so [queryLlmRequestId] is
 * non-null; only [reasonLlmRequestId] is nullable — it stays null when the pass
 * bails before the reason call (a Rejected/TransientFailure query call, or a
 * zero-match retrieve). [matchesConsidered] is null only when the retrieve never
 * ran.
 */
data class NewFitLensRun(
  val studentId: StudentId,
  val outcome: FitLensOutcome,
  val querySystemPromptId: SystemPromptId,
  val reasonSystemPromptId: SystemPromptId,
  val queryLlmRequestId: LlmRequestId,
  val reasonLlmRequestId: LlmRequestId?,
  val matchesConsidered: Int? = null,
)
