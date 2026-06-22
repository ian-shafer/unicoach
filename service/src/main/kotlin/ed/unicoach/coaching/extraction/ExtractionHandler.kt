package ed.unicoach.coaching.extraction

import ed.unicoach.common.json.deserialize
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.queue.ExtractionPayload
import ed.unicoach.queue.JobHandler
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.JobTypeConfig
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Thin `JobHandler` adapter the worker registers for [JobType.EXTRACT_CONVERSATION]
 * (RFC 66). Deserializes the [ExtractionPayload] and delegates to
 * [ExtractionService.extract]; a malformed payload is a [JobResult.PermanentFailure]
 * (no retry helps), a transient service error a [JobResult.RetriableFailure].
 *
 * Same-student claim-write correctness is guarded by the student advisory lock,
 * not by `concurrency = 1`, so distinct students extract in parallel up to
 * [JobTypeConfig.concurrency]. `executionTimeout` (5m) is strictly less than
 * `lockDuration` (10m) so a slow pass cannot outlive its queue lock.
 */
class ExtractionHandler(
  private val extractionService: ExtractionService,
) : JobHandler {
  private val logger = LoggerFactory.getLogger(ExtractionHandler::class.java)

  override val jobType = JobType.EXTRACT_CONVERSATION

  override val config =
    JobTypeConfig(
      concurrency = 4,
      maxAttempts = 5,
      lockDuration = 10.minutes,
      executionTimeout = 5.minutes,
    )

  override suspend fun execute(payload: JsonObject): JobResult {
    val convoId: ConvoId
    val throughRequestId: ConvoRequestId
    try {
      val data = payload.deserialize<ExtractionPayload>()
      convoId = ConvoId(UUID.fromString(data.convoId))
      throughRequestId = ConvoRequestId(data.throughRequestId)
    } catch (e: Exception) {
      logger.warn("Discarding extraction job with malformed payload [{}]", payload, e)
      return JobResult.PermanentFailure("Malformed payload: ${e.message}")
    }

    return when (val result = extractionService.extract(convoId, throughRequestId)) {
      is ExtractionResult.Success -> JobResult.Success
      is ExtractionResult.TransientFailure -> JobResult.RetriableFailure(result.message, result.cause)
    }
  }
}
