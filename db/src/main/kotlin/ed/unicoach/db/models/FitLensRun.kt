package ed.unicoach.db.models

import java.time.Instant

/**
 * A row of the append-only `fit_lens_runs` log (RFC 98): one completed fit-lens
 * pass over a student. Serves as the student's fit-lens freshness marker (latest
 * `created_at` over `applied` rows), the provenance of the pass (the two prompt
 * pins, provider/model), and the per-student token ledger — the four token
 * columns hold the SUM of the pass's two billed calls, recorded for every
 * completed pass including failures. [outcome] is the sealed [FitLensOutcome]
 * ADT (RFC 101): an `Applied` carries the suggestions count, a `Failed` the
 * cause. [matchesConsidered] is the size of the retrieved set call #2 saw (0 for
 * a completed zero-match retrieve; null only when the retrieve never ran — a
 * `failed` pass that died at LLM call #1) and varies independently of the
 * outcome, so it stays flat.
 */
data class FitLensRun(
  override val id: FitLensRunId,
  override val createdAt: Instant,
  val studentId: StudentId,
  val outcome: FitLensOutcome,
  val querySystemPromptId: SystemPromptId,
  val reasonSystemPromptId: SystemPromptId,
  val provider: String,
  val modelResolved: String?,
  val matchesConsidered: Int?,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,
  val cacheWriteTokens: Int?,
) : Identifiable<FitLensRunId>,
  Created
