package ed.unicoach.coaching.fitlens

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.StudentId
import java.time.Instant

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
    val reason: SkipReason,
  ) : FitLensResult

  data class Failed(
    val reason: FailureReason,
  ) : FitLensResult

  data class TransientFailure(
    val message: String,
    val cause: Throwable? = null,
  ) : FitLensResult
}

/**
 * Why a pass was [FitLensResult.Skipped] instead of run to completion. Every
 * variant carries the [studentId] the skip was decided for, so a value is
 * self-describing independent of any surrounding log context.
 */
sealed interface SkipReason {
  val studentId: StudentId

  data class StudentNotFound(
    override val studentId: StudentId,
  ) : SkipReason

  data class StudentSoftDeleted(
    override val studentId: StudentId,
  ) : SkipReason

  data class BelowMinClaimsFloor(
    override val studentId: StudentId,
    val activeClaims: Int,
    val minClaims: Int,
  ) : SkipReason

  data class ModelUnchangedSinceLastApplied(
    override val studentId: StudentId,
    val freshness: Instant,
    val lastAppliedAt: Instant,
  ) : SkipReason

  data class FailureCircuitBreakerOpen(
    override val studentId: StudentId,
    val consecutiveFailures: Int,
    val maxConsecutiveFailures: Int,
  ) : SkipReason

  data class ZeroSearchMatches(
    override val studentId: StudentId,
  ) : SkipReason

  data class ReasonReturnedNoFit(
    override val studentId: StudentId,
  ) : SkipReason
}

/** Renders a [SkipReason] to the human string logged at the edge boundary. */
fun SkipReason.toDisplay(): String =
  when (this) {
    is SkipReason.StudentNotFound -> {
      "student not found (student=${studentId.asString})"
    }

    is SkipReason.StudentSoftDeleted -> {
      "student soft-deleted (student=${studentId.asString})"
    }

    is SkipReason.BelowMinClaimsFloor -> {
      "below minClaims floor (student=${studentId.asString})"
    }

    is SkipReason.ModelUnchangedSinceLastApplied -> {
      "model unchanged since last applied run (student=${studentId.asString})"
    }

    is SkipReason.FailureCircuitBreakerOpen -> {
      "failure circuit breaker open ($consecutiveFailures failures) (student=${studentId.asString})"
    }

    is SkipReason.ZeroSearchMatches -> {
      "zero search matches (student=${studentId.asString})"
    }

    is SkipReason.ReasonReturnedNoFit -> {
      "reason returned no fit (student=${studentId.asString})"
    }
  }

/**
 * Why a completed pass produced unusable output and was [FitLensResult.Failed].
 * Split by which LLM call's output could not be used: call #1's [CollegeQuery]
 * (`Query*`), or call #2's fit reasoning (`Reason*`). Every variant carries the
 * [studentId] the failure was recorded for, so a value is self-describing
 * independent of any surrounding log context.
 */
sealed interface FailureReason {
  val studentId: StudentId

  data class QueryNotJsonObject(
    override val studentId: StudentId,
  ) : FailureReason

  data class QueryMalformedJson(
    override val studentId: StudentId,
    val detail: String?,
  ) : FailureReason

  data class QueryTypeInvalidField(
    override val studentId: StudentId,
    val field: String?,
  ) : FailureReason

  data class ReasonNotJsonObject(
    override val studentId: StudentId,
  ) : FailureReason

  data class ReasonMalformedJson(
    override val studentId: StudentId,
    val detail: String?,
  ) : FailureReason

  data class ReasonInvalidCollegeId(
    override val studentId: StudentId,
    val raw: String,
  ) : FailureReason

  data class ReasonCollegeIdOutsideMatchSet(
    override val studentId: StudentId,
    val collegeId: CollegeId,
  ) : FailureReason

  data class ReasonRationaleMissing(
    override val studentId: StudentId,
  ) : FailureReason

  data class ReasonRationaleTooLong(
    override val studentId: StudentId,
    val length: Int,
    val max: Int,
  ) : FailureReason
}

/** Renders a [FailureReason] to the human string logged at the edge boundary. */
fun FailureReason.toDisplay(): String =
  when (this) {
    is FailureReason.QueryNotJsonObject -> {
      "root is not a JSON object (student=${studentId.asString})"
    }

    is FailureReason.QueryMalformedJson -> {
      "malformed JSON: $detail (student=${studentId.asString})"
    }

    is FailureReason.QueryTypeInvalidField -> {
      "type-invalid field [$field] (student=${studentId.asString})"
    }

    is FailureReason.ReasonNotJsonObject -> {
      "root is not a JSON object (student=${studentId.asString})"
    }

    is FailureReason.ReasonMalformedJson -> {
      "malformed JSON: $detail (student=${studentId.asString})"
    }

    is FailureReason.ReasonInvalidCollegeId -> {
      "collegeId is not a UUID: [$raw] (student=${studentId.asString})"
    }

    is FailureReason.ReasonCollegeIdOutsideMatchSet -> {
      "collegeId [${collegeId.asString}] is outside the retrieved match set (student=${studentId.asString})"
    }

    is FailureReason.ReasonRationaleMissing -> {
      "rationale is missing or blank (student=${studentId.asString})"
    }

    is FailureReason.ReasonRationaleTooLong -> {
      "rationale exceeds $max chars ($length) (student=${studentId.asString})"
    }
  }

/**
 * The coarse, persisted [FitLensFailureCategory] a [FailureReason] falls
 * into — a pure function of which variant it is, so a category can never
 * drift out of sync with the reason it was derived from.
 */
val FailureReason.category: FitLensFailureCategory
  get() =
    when (this) {
      is FailureReason.QueryNotJsonObject,
      is FailureReason.QueryMalformedJson,
      is FailureReason.QueryTypeInvalidField,
      is FailureReason.ReasonNotJsonObject,
      is FailureReason.ReasonMalformedJson,
      -> FitLensFailureCategory.MALFORMED_OUTPUT

      is FailureReason.ReasonInvalidCollegeId,
      is FailureReason.ReasonCollegeIdOutsideMatchSet,
      is FailureReason.ReasonRationaleMissing,
      is FailureReason.ReasonRationaleTooLong,
      -> FitLensFailureCategory.INVALID_CONTENT
    }
