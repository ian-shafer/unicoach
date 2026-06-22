package ed.unicoach.coaching.extraction

/**
 * The outcome of one [ExtractionService.extract] pass. [ExtractionHandler] maps
 * it to a queue `JobResult`:
 *
 * - [Success] → `JobResult.Success` (applied, or an idempotent/soft-deleted
 *   no-op).
 * - [TransientFailure] → `JobResult.RetriableFailure` (provider error,
 *   unparseable output, stale claim target, or a transient DB error); retried up
 *   to `maxAttempts`, then dead-lettered.
 */
sealed interface ExtractionResult {
  data object Success : ExtractionResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : ExtractionResult
}
