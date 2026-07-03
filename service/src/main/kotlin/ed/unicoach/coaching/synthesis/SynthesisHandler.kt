package ed.unicoach.coaching.synthesis

import ed.unicoach.common.json.deserialize
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import ed.unicoach.queue.SynthesisPayload
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Thin `JobHandler` adapter the worker registers for [JobType.SYNTHESIZE_STUDENT]
 * (RFC 93). Deserializes the [SynthesisPayload] and delegates to
 * [SynthesisService.synthesize]; a malformed payload is a [JobResult.PermanentFailure]
 * (no retry helps), a transient service error a [JobResult.RetriableFailure].
 *
 * Same-student commitment-write correctness is guarded by the student advisory
 * lock, not by `concurrency = 1`, so distinct students synthesize in parallel up
 * to [JobTypeConfig.concurrency]. `executionTimeout` (5m) is strictly less than
 * `lockDuration` (10m) so a slow pass cannot outlive its queue lock.
 */
class SynthesisHandler(
  private val synthesisService: SynthesisService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(SynthesisHandler::class.java)

  override val jobType = JobType.SYNTHESIZE_STUDENT

  override val config =
    JobTypeConfig(
      concurrency = 4,
      maxAttempts = 5,
      lockDuration = 10.minutes,
      executionTimeout = 5.minutes,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val studentId: StudentId
    try {
      val data = payload.deserialize<SynthesisPayload>()
      studentId = StudentId(UUID.fromString(data.studentId))
    } catch (e: Exception) {
      logger.warn("Discarding synthesis job with malformed payload [{}]", payload, e)
      return JobResult.PermanentFailure("Malformed payload: ${e.message}")
    }

    return when (val result = synthesisService.synthesize(studentId)) {
      is SynthesisResult.Success -> JobResult.Success
      is SynthesisResult.TransientFailure -> JobResult.RetriableFailure(result.message, result.cause)
    }
  }
}
