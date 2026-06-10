package ed.unicoach.db.models

import kotlinx.serialization.json.JsonElement
import java.time.Instant

data class ConvoResponse(
  override val id: ConvoResponseId,
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
  override val createdAt: Instant,
) : Identifiable<ConvoResponseId>,
  Created
