package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement
import java.time.Instant

data class ConvoResponseRaw(
  val responseId: ConvoResponseId,
  val createdAt: Instant,
  val payload: JsonElement,
)
