package ed.unicoach.coaching.fitlens

import ed.unicoach.db.models.StudentId

/**
 * The outcome of one [FitLensService.discover] pass over a [StudentId].
 * [FitLensHandler] maps it to a queue `JobResult`. fit-lens deliberately splits
 * two failure modes by cause (RFC 98):
 *
 * - [Applied] → `JobResult.Success`: a full pass completed. A billed `applied`
 *   `fit_lens_runs` row was written (with or without a suggestion) and the
 *   freshness marker advanced.
 * - [Skipped] → `JobResult.Success`: an idempotent no-op. A pre-LLM gate
 *   (minClaims, freshness, failure circuit breaker) spent no tokens and wrote no
 *   run row; a post-LLM skip (zero matches, or the reason call returned `{}`)
 *   spent tokens and wrote an `applied` row with `suggestions_written = 0`.
 * - [Failed] → `JobResult.Success` (dead-lettered, no retry): the pass ran to
 *   completion but the model output is unusable (unparseable/type-invalid
 *   `CollegeQuery`, or a `collegeId` outside the match set). A billed `failed`
 *   run row records the spend; a same-model re-parse cannot succeed, so the
 *   weekly tick (bounded by the circuit breaker) is the only re-run path.
 * - [TransientFailure] → `JobResult.RetriableFailure`: a genuinely transient
 *   service error before the pass produced a usable result. No run row is
 *   written and it does not count toward the circuit breaker; the queue retries
 *   up to `maxAttempts`.
 */
sealed interface FitLensResult {
  data object Applied : FitLensResult

  data class Skipped(
    val reason: String,
  ) : FitLensResult

  data class Failed(
    val reason: String,
  ) : FitLensResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : FitLensResult
}
