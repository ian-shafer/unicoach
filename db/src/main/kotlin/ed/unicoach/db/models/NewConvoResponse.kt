package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement

data class NewConvoResponse(
  val requestId: ConvoRequestId,
  val convoId: ConvoId,
  val content: JsonElement?,
  val modelResolved: String?,
  val stopReason: String,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheReadTokens: Int?,
  val cacheWriteTokens: Int?,
  val providerRequestId: String?,
  val latencyMs: Int?,
)
