package ed.unicoach.coaching.synthesis

import ed.unicoach.db.models.StudentId

/**
 * The outcome of one [SynthesisService.synthesize] pass over a [StudentId].
 * [SynthesisHandler] maps it to a queue `JobResult`:
 *
 * - [Success] → `JobResult.Success` (applied, or an idempotent no-op:
 *   soft-deleted student, model not fresh, open-set cap reached, or a lost race).
 * - [TransientFailure] → `JobResult.RetriableFailure` (provider error,
 *   unparseable output, missing prompt, or a transient DB error); retried up to
 *   `maxAttempts`, then dead-lettered.
 */
sealed interface SynthesisResult {
  data object Success : SynthesisResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : SynthesisResult
}
