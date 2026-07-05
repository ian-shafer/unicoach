package ed.unicoach.coaching.fitlens

import ed.unicoach.common.json.deserialize
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.FitLensPayload
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Thin `JobHandler` adapter the worker registers for [JobType.FIT_LENS] (RFC 98).
 * Deserializes the [FitLensPayload] and delegates to [FitLensService.discover],
 * mapping its [FitLensResult] to a queue `JobResult`:
 *
 * - [FitLensResult.Applied]/[FitLensResult.Skipped] → [JobResult.Success].
 * - [FitLensResult.Failed] → [JobResult.Success] (dead-lettered): the pass ran to
 *   completion with unusable model output; a same-model retry would just re-bill
 *   two LLM calls, so the weekly tick (bounded by the failure circuit breaker) is
 *   the only re-run path. This is the deliberate divergence from synthesis.
 * - [FitLensResult.TransientFailure] → [JobResult.RetriableFailure]: a transient
 *   provider/DB blip rides out across up to `maxAttempts = 3` attempts.
 *
 * A malformed payload is a [JobResult.PermanentFailure] (no retry helps).
 * `maxAttempts = 3` (fewer than synthesis's 5, since each attempt is heavier —
 * two LLM calls plus retrieval). `executionTimeout` (8m) is strictly less than
 * `lockDuration` (15m) so a slow pass cannot outlive its queue lock; same-student
 * write correctness is guarded by the advisory lock, not `concurrency`.
 */
class FitLensHandler(
  private val fitLensService: FitLensService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(FitLensHandler::class.java)

  override val jobType = JobType.FIT_LENS

  override val config =
    JobTypeConfig(
      concurrency = 2,
      maxAttempts = 3,
      lockDuration = 15.minutes,
      executionTimeout = 8.minutes,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val studentId: StudentId
    try {
      val data = payload.deserialize<FitLensPayload>()
      studentId = StudentId(UUID.fromString(data.studentId))
    } catch (e: Exception) {
      logger.warn("Discarding fit-lens job with malformed payload [{}]", payload, e)
      return JobResult.PermanentFailure("Malformed payload: ${e.message}")
    }

    return when (val result = fitLensService.discover(studentId)) {
      is FitLensResult.Applied -> {
        JobResult.Success
      }

      is FitLensResult.Skipped -> {
        JobResult.Success
      }

      // Dead-lettered: a completed pass with unusable output is never retried. Log
      // the specific parse-failure reason so the dropped pass is diagnosable.
      is FitLensResult.Failed -> {
        logger.warn("Dead-lettering fit-lens job for student=[{}]: [{}]", studentId.asString, result.reason.toDisplay())
        JobResult.Success
      }

      is FitLensResult.TransientFailure -> {
        JobResult.RetriableFailure(result.message, result.cause)
      }
    }
  }
}
