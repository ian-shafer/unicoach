package ed.unicoach.coaching.extraction

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.ExtractionPayload
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Schedules the extraction pass over a conversation after a successful student
 * turn (RFC 66), from the SERVICE layer (RFC 262, spec preamble 10).
 *
 * It used to live in `rest-server`'s `ConvoRoutes`, which meant a door that
 * called `CoachingService` in-process — the web chat — would have run the turn
 * and silently skipped the enqueue, and nothing would have noticed until a
 * student's facts went missing. Here, a door cannot skip it: `CoachingService`
 * takes one of these UNDEFAULTED, and the student turn calls it from its own
 * success hook.
 *
 * Debounced by `extraction.debounce`, skipped entirely when
 * `extraction.enabled = false`, and never called for a failed turn.
 */
class ExtractionScheduler(
  private val queueService: QueueService,
  private val config: ExtractionConfig,
) {
  private val logger = LoggerFactory.getLogger(ExtractionScheduler::class.java)

  /**
   * Fire-and-forget: a queue failure is logged and swallowed, never alters the
   * turn. A failed enqueue costs one extraction pass, which the next turn's
   * enqueue covers; failing the turn would cost the student the reply they just
   * paid for.
   *
   * [throughRequestId] is the turn's FINAL request, so the watermark advances
   * over any tool excursion in one pass (RFC 94).
   */
  suspend fun schedule(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
  ) {
    if (!config.enabled) return
    val payload = ExtractionPayload(convoId = convoId.asString, throughRequestId = throughRequestId.value)
    try {
      val result =
        queueService.enqueue(
          jobType = JobType.EXTRACT_CONVERSATION,
          payload = payload.toJson(),
          delay = config.debounce,
        )
      if (result is EnqueueResult.DatabaseFailure) {
        logger.warn("extraction enqueue failed for convo=[{}]", convoId.asString, result.error)
      }
    } catch (cancellation: CancellationException) {
      // A disconnect is not a queue failure: it must keep unwinding the turn.
      throw cancellation
    } catch (e: Exception) {
      logger.warn("extraction enqueue threw for convo=[{}]", convoId.asString, e)
    }
  }

  /** Serializes the payload into the kotlinx JsonObject the queue API takes. */
  private fun ExtractionPayload.toJson(): JsonObject =
    buildJsonObject {
      put("convoId", convoId)
      put("throughRequestId", throughRequestId)
    }
}
