package ed.unicoach.db.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Insert input for the `llm_requests` log (RFC 106); omits the DB-generated id
 * and `created_at`. Holds the full logical request envelope verbatim.
 * [content] is the sent message array (a `JsonArray`, satisfying the
 * `llm_requests_content_is_array_check` CHECK); [tools] is a `JsonArray` or null;
 * [toolChoice] / [params] are objects or null. No domain columns — attribution
 * lives in the domain row that references the returned call id.
 */
data class NewLlmRequest(
  val provider: String,
  val modelRequested: String,
  val system: String?,
  val content: JsonArray,
  val maxTokens: Int,
  val tools: JsonArray?,
  val toolChoice: JsonObject?,
  val params: JsonObject?,
)
