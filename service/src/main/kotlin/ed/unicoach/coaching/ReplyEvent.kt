package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoRequest
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * The service-level reply stream for one turn. The handler relays text deltas
 * as they arrive, then sees exactly one [Terminal]. [Completed] carries what the
 * handler needs to surface the coach's answer and enqueue extraction — the
 * closing [ConvoRequest] row (its id is the extraction watermark target), the
 * completed coach [content], and the response's [createdAt]. The response body
 * itself lives in the generic call log (RFC 106), owned by [LlmCallLog].
 * [Failed] carries the retriable distinction and a server-side reason (never
 * surfaced verbatim to the client).
 */
sealed interface ReplyEvent {
  data class Delta(
    val text: String,
  ) : ReplyEvent

  sealed interface Terminal : ReplyEvent

  data class Completed(
    val convoRequest: ConvoRequest,
    val content: JsonElement,
    val createdAt: Instant,
  ) : Terminal

  data class Failed(
    val retriable: Boolean,
    val reason: String,
  ) : Terminal
}
