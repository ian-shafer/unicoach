package ed.unicoach.chat

import kotlinx.serialization.json.JsonElement

// The parsed reply, field-aligned with NewConvoResponse so the coaching
// service's mapping is mechanical. `stopReason` stays a verbatim provider
// string (faithful capture; the DB column is free TEXT with the same stance).
// `latency_ms` is excluded: the caller measures wall-clock around the port
// call.
data class ChatResponse(
  // Assistant content blocks, verbatim-parsed → convo_responses.content.
  val content: JsonElement,
  // Exact model that ran → convo_responses.model_resolved.
  val modelResolved: String,
  // Provider stop reason verbatim → convo_responses.stop_reason.
  val stopReason: String,
  // → convo_responses.*_tokens columns.
  val usage: TokenUsage,
  // → convo_responses.provider_request_id.
  val providerRequestId: String?,
)
