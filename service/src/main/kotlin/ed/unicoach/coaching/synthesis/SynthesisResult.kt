package ed.unicoach.coaching.synthesis

import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.StudentId

/**
 * The outcome of one [SynthesisService.synthesize] pass over a [StudentId].
 * [SynthesisHandler] maps it to a queue `JobResult`:
 *
 * - [Success] → `JobResult.Success` (applied, or an idempotent no-op:
 *   soft-deleted student, model not fresh, open-set cap reached, or a lost race).
 * - [SkippedBudgetExhausted] → `JobResult.Success` (named pre-LLM skip: no
 *   spend, no run row, no retry).
 * - [TransientFailure] → `JobResult.RetriableFailure` (provider error,
 *   unparseable output, missing prompt, or a transient DB error); retried up to
 *   `maxAttempts`, then dead-lettered.
 */
sealed interface SynthesisResult {
  data object Success : SynthesisResult

  /**
   * The student's lifetime coaching allowance is spent (RFC 109), so the pass
   * made no LLM call. Carries the [entitlement] the skip was decided on so the
   * log line can state spent against allowance — the operator's answer to "why
   * did this student's synthesis stop running". No `synthesis_runs` row is
   * written: the table records billed calls, and a skip has none.
   */
  data class SkippedBudgetExhausted(
    val studentId: StudentId,
    val entitlement: Entitlement,
  ) : SynthesisResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : SynthesisResult
}
