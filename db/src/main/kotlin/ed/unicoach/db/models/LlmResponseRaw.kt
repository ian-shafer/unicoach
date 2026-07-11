package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * A row of the append-only `llm_responses_raw` log (RFC 106): the verbatim
 * provider response body, keyed 1:1 to its [LlmResponse] by making the FK the
 * PK. Present only when the terminal carried a body (0..1 per response).
 */
data class LlmResponseRaw(
  val responseId: LlmResponseId,
  val createdAt: Instant,
  val payload: JsonElement,
)
