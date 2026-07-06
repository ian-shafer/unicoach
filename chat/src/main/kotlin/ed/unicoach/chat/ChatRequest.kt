package ed.unicoach.chat

import kotlinx.serialization.json.JsonObject

// Provider-agnostic input for one completion call. The port does not validate:
// `model` is the string the caller pins into convo_requests.model_requested,
// and `system` is the resolved prompt body — the system_prompts catalog and
// system_prompt_id FK are the caller's concern. `params` is an opaque vendor
// object because convo_requests.request_params is deliberately opaque JSONB
// interpreted via `provider`; typed fields are reserved for parameters every
// vendor shares.
data class ChatRequest(
  // Provider model id, e.g. "claude-opus-4-8".
  val model: String,
  // System prompt body (verbatim text, not an id).
  val system: String?,
  // Replayed history + the new turn, oldest first.
  val messages: List<ChatMessage>,
  // Response token ceiling.
  val maxTokens: Int,
  // Verbatim Anthropic tool specs (name/description/input_schema), each an
  // opaque JsonObject matching the port's stance that content blocks and vendor
  // params are opaque. Serialized into the body's `tools` array; the key is
  // omitted when empty, so tool-less callers stay byte-identical on the wire.
  val tools: List<JsonObject> = emptyList(),
  // Verbatim Anthropic tool_choice object, e.g. {"type":"tool","name":…};
  // serialized as `tool_choice` when present, omitted when null so
  // tool_choice-less callers stay byte-identical on the wire. Symmetric with
  // [tools] — request-shaping, not the persisted opaque vendor [params].
  val toolChoice: JsonObject? = null,
  // Vendor passthrough, mirrors convo_requests.request_params.
  val params: JsonObject? = null,
)
