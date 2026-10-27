package ed.unicoach.coaching.extraction

import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.StudentId

/**
 * The outcome of one [ExtractionService.extract] pass. [ExtractionHandler] maps
 * it to a queue `JobResult`:
 *
 * - [Success] → `JobResult.Success` (applied, or an idempotent/soft-deleted
 *   no-op).
 * - [SkippedBudgetExhausted] → `JobResult.Success` (named pre-LLM skip: no
 *   spend, no run row, no retry).
 * - [SupporterConversation] → `JobResult.Success` (named pre-LLM skip: a
 *   supporter's conversation is never distilled onto the student's record).
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

  /**
   * The conversation is a supporter's own (RFC 258), ABOUT the student but not
   * the student's: distilling it would write the parent's words into the
   * student's claims and observations. The pass made no LLM call and wrote no
   * row. No parent turn enqueues extraction, so reaching this is a stray job;
   * the skip is the backstop that makes the cut point hold anyway.
   */
  data class SupporterConversation(
    val convoId: ConvoId,
  ) : ExtractionResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : ExtractionResult
}
