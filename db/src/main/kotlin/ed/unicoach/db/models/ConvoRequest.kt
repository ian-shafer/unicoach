package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class ConvoRequest(
  override val id: ConvoRequestId,
  val convoId: ConvoId,
  override val createdAt: Instant,
  val provider: String,
  val modelRequested: String,
  val systemPromptId: SystemPromptId,
  val requestParams: JsonObject?,
  val content: JsonElement,
) : Identifiable<ConvoRequestId>,
  Created
