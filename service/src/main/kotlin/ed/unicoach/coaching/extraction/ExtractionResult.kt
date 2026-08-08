package ed.unicoach.coaching.extraction

import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.StudentId

/**
 * The outcome of one [ExtractionService.extract] pass. [ExtractionHandler] maps
 * it to a queue `JobResult`:
 *
 * - [Success] → `JobResult.Success` (applied, or an idempotent/soft-deleted
 *   no-op).
 * - [SkippedBudgetExhausted] → `JobResult.Success` (named pre-LLM skip: no
 *   spend, no run row, no retry).
 * - [TransientFailure] → `JobResult.RetriableFailure` (provider error,
 *   unparseable output, stale claim target, or a transient DB error); retried up
 *   to `maxAttempts`, then dead-lettered.
 */
sealed interface ExtractionResult {
  data object Success : ExtractionResult

  /**
   * The student's lifetime coaching allowance is spent (RFC 109), so the pass
   * made no LLM call. Carries the [entitlement] the skip was decided on so the
   * log line can state spent against allowance — the operator's answer to "why
   * did this student stop extracting". No `extraction_runs` row is written: the
   * table records billed calls, and a skip has none.
   */
  data class SkippedBudgetExhausted(
    val studentId: StudentId,
    val entitlement: Entitlement,
  ) : ExtractionResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : ExtractionResult
}
