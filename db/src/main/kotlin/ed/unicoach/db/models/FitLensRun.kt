package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `fit_lens_runs` log (RFC 98): one completed fit-lens
 * pass over a student. Serves as the student's fit-lens freshness marker (latest
 * `created_at` over `applied` rows) and the provenance of the pass (the two
 * prompt pins). Since RFC 106 the provider/model and per-call token spend live in
 * the generic call log; a pass makes up to two billed calls, referenced by
 * [queryLlmRequestId] and [reasonLlmRequestId]. Every write path always has a
 * query call, so [queryLlmRequestId] is non-null; only [reasonLlmRequestId] is
 * nullable — it stays null when the pass bails before the reason call (a
 * Rejected/TransientFailure query call, or a zero-match retrieve). [outcome] is
 * the sealed [FitLensOutcome] ADT (RFC 101). [matchesConsidered] is the size of
 * the retrieved set call #2 saw (0 for a completed zero-match retrieve; null only
 * when the retrieve never ran).
 */
data class FitLensRun(
  override val id: FitLensRunId,
  override val createdAt: Instant,
  val studentId: StudentId,
  val outcome: FitLensOutcome,
  val querySystemPromptId: SystemPromptId,
  val reasonSystemPromptId: SystemPromptId,
  val queryLlmRequestId: LlmRequestId,
  val reasonLlmRequestId: LlmRequestId?,
  val matchesConsidered: Int?,
) : Identifiable<FitLensRunId>,
  Created
