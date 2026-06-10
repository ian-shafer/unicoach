package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class NewConvoRequest(
  val convoId: ConvoId,
  val provider: String,
  val modelRequested: String,
  val systemPromptId: SystemPromptId,
  val requestParams: JsonObject?,
  val content: JsonElement,
)
