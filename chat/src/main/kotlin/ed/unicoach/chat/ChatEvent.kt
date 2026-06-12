package ed.unicoach.chat

import kotlinx.serialization.json.JsonElement

// Streaming event taxonomy; one provider call yields a stream of these. The
// non-terminal events carry the live view (deltas for SSE relay); the terminal
// events carry the outcome. Exactly one Terminal ends every stream.
//
// `blockType` is a verbatim provider string, not an enum: the column it
// ultimately informs (convo_responses.content) is opaque JSONB, and an enum
// would force a lossy "other" bucket every time a vendor adds a block type.
sealed interface ChatEvent {
  // Provider acknowledged the request and began a message.
  data class MessageStart(
    // Provider's message/request id.
    val providerRequestId: String?,
    // Model actually serving the request.
    val model: String?,
    // E.g. input tokens, known at start.
    val usage: TokenUsage?,
  ) : ChatEvent

  // A content block opened at `index`.
  data class ContentBlockStart(
    val index: Int,
    // Provider's block type verbatim: "text", "thinking", "tool_use", ...
    val blockType: String,
    // Verbatim initial block object when it carries data beyond its type
    // (a tool_use block opens with its id and name); null when the block
    // opens empty.
    val block: JsonElement?,
  ) : ChatEvent

  // Incremental content for the block at `index`.
  data class ContentBlockDelta(
    val index: Int,
    val delta: ContentDelta,
  ) : ChatEvent

  // The block at `index` is complete.
  data class ContentBlockStop(
    val index: Int,
  ) : ChatEvent

  // Message-level update (stop reason, cumulative usage).
  data class MessageDelta(
    val stopReason: String?,
    val usage: TokenUsage?,
  ) : ChatEvent

  // Exactly one Terminal ends every stream.
  sealed interface Terminal : ChatEvent

  // The provider produced a usable reply.
  data class Completed(
    val response: ChatResponse,
    // Verbatim provider payload for convo_responses_raw — the canonical
    // provider response object (for a streaming call, the accumulated final
    // message), never the SSE event array.
    val rawPayload: JsonElement,
  ) : Terminal

  // Permanent failure; no retry helps. An error an adapter does not recognize
  // MUST map to TransientFailure, never Rejected — a bounded retry is safer
  // than silently dropping a turn.
  data class Rejected(
    // Human-readable classification, bracketed-value style.
    val reason: String,
    // Provider's request id when one was assigned.
    val providerRequestId: String?,
    // Verbatim provider error body when one exists.
    val rawPayload: JsonElement?,
  ) : Terminal

  // Retriable failure.
  data class TransientFailure(
    val reason: String,
    val providerRequestId: String?,
    val rawPayload: JsonElement?,
  ) : Terminal
}
