package ed.unicoach.db.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * A row of the append-only `llm_requests` log (RFC 106): the full logical
 * request envelope of one LLM provider call, provider-agnostic and carrying no
 * domain columns. [content] is the sent message array; [tools] the requested
 * tool schemas (null when none); [toolChoice] / [params] the optional request
 * knobs. Attribution and provenance live in the domain row (`convo_requests`,
 * `*_runs`) that references this call by id.
 */
data class LlmRequest(
  override val id: LlmRequestId,
  override val createdAt: Instant,
  val provider: String,
  val modelRequested: String,
  val system: String?,
  val content: JsonArray,
  val maxTokens: Int,
  val tools: JsonArray?,
  val toolChoice: JsonObject?,
  val params: JsonObject?,
) : Identifiable<LlmRequestId>,
  Created
