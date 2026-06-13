package ed.unicoach.coaching

import ed.unicoach.db.models.ConvoResponse

/**
 * The service-level reply stream for one turn. The handler relays text deltas
 * as they arrive, then sees exactly one [Terminal]. [Completed] carries the
 * persisted coach response; [Failed] carries the retriable distinction and a
 * server-side reason (never surfaced verbatim to the client).
 */
sealed interface ReplyEvent {
  data class Delta(
    val text: String,
  ) : ReplyEvent

  sealed interface Terminal : ReplyEvent

  data class Completed(
    val response: ConvoResponse,
  ) : Terminal

  data class Failed(
    val retriable: Boolean,
    val reason: String,
  ) : Terminal
}
