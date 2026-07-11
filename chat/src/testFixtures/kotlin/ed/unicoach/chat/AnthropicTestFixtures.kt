package ed.unicoach.chat

// Canonical recorded streams, defined once so the transport tests (which parse
// SSE text) and the provider tests (which replay AnthropicTransportEvent lists)
// agree on the wire shape. Each `*SseBody` is the raw SSE text a 200 response
// carries; each `*Frames` is the equivalent Frame list the transport would emit
// after Opened. The two are kept in lockstep by hand.
//
// Fidelity guard: every recorded stream here is a byte-faithful copy of the
// Anthropic Messages streaming protocol, and each `*SseBody` decodes cleanly
// through the real KtorAnthropicStreamTransport parser (KtorAnthropicStreamTransportTest).
// That is what keeps these captures honest — they are not loose approximations.
object AnthropicTestFixtures {
  // The `request-id` response header value used across transport fixtures.
  const val REQUEST_ID = "req_canonical_001"

  // The provider's message id, carried in message_start.
  const val MESSAGE_ID = "msg_canonical_001"

  const val MODEL = "claude-opus-4-8"

  // --- Canonical text stream -------------------------------------------------
  // message_start, content_block_start (empty text), two text_deltas, a ping,
  // content_block_stop, message_delta, message_stop. The text block opens empty
  // so the empty-open rule yields a null ContentBlockStart.block.
  val canonicalTextSseBody =
    sse(
      "message_start" to
        """{"type":"message_start","message":{"id":"$MESSAGE_ID","type":"message","role":"assistant","model":"$MODEL","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":3,"cache_creation_input_tokens":0}}}""",
      "content_block_start" to
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
      "content_block_delta" to
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
      "content_block_delta" to
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world"}}""",
      "ping" to """{"type":"ping"}""",
      "content_block_stop" to """{"type":"content_block_stop","index":0}""",
      "message_delta" to
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":8}}""",
      "message_stop" to """{"type":"message_stop"}""",
    )

  val canonicalTextFrames =
    listOf(
      frame(
        "message_start",
        """{"type":"message_start","message":{"id":"$MESSAGE_ID","type":"message","role":"assistant","model":"$MODEL","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":3,"cache_creation_input_tokens":0}}}""",
      ),
      frame(
        "content_block_start",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world"}}""",
      ),
      frame("ping", """{"type":"ping"}"""),
      frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
      frame(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":8}}""",
      ),
      frame("message_stop", """{"type":"message_stop"}"""),
    )

  // Convenience: the canonical text stream as a transport replay (Opened first).
  val canonicalTextReplay = listOf(opened()) + canonicalTextFrames

  // --- tool_use stream -------------------------------------------------------
  // A tool_use block opens with id/name (so block is the verbatim object), then
  // two input_json_delta frames whose concatenation is `{"city":"SF"}`.
  val toolUseFrames =
    listOf(
      frame(
        "message_start",
        """{"type":"message_start","message":{"id":"$MESSAGE_ID","type":"message","role":"assistant","model":"$MODEL","content":[],"stop_reason":null,"usage":{"input_tokens":20}}}""",
      ),
      frame(
        "content_block_start",
        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"SF\"}"}}""",
      ),
      frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
      frame(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":15}}""",
      ),
      frame("message_stop", """{"type":"message_stop"}"""),
    )

  // --- search_colleges tool_use stream (chat tool loop) ----------------------
  // A tool_use block naming `search_colleges` — a tool the real ToolRegistry
  // serves — whose accumulated input is `{"cipPrefix":"26"}`. stop_reason is
  // tool_use, so the coaching loop dispatches the tool and makes a continuation
  // call (served by a second scripted replay).
  val searchCollegesToolUseFrames =
    listOf(
      frame(
        "message_start",
        """{"type":"message_start","message":{"id":"$MESSAGE_ID","type":"message","role":"assistant","model":"$MODEL","content":[],"stop_reason":null,"usage":{"input_tokens":40,"output_tokens":1}}}""",
      ),
      frame(
        "content_block_start",
        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_search_01","name":"search_colleges","input":{}}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"cipPrefix\":"}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"26\"}"}}""",
      ),
      frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
      frame(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":18}}""",
      ),
      frame("message_stop", """{"type":"message_stop"}"""),
    )

  // The search_colleges tool_use as a full transport replay (Opened first).
  val searchCollegesToolUseReplay = listOf(opened()) + searchCollegesToolUseFrames

  // --- thinking + signature stream ------------------------------------------
  val thinkingFrames =
    listOf(
      frame(
        "message_start",
        """{"type":"message_start","message":{"id":"$MESSAGE_ID","type":"message","role":"assistant","model":"$MODEL","content":[],"stop_reason":null,"usage":{"input_tokens":5}}}""",
      ),
      frame(
        "content_block_start",
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think"}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-abc"}}""",
      ),
      frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
      frame(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
      ),
      frame("message_stop", """{"type":"message_stop"}"""),
    )

  // A single-tool_use recorded stream for an arbitrary forced tool: opens a
  // tool_use block named [toolName] and streams [inputJson] (a complete JSON
  // object) as one input_json_delta, closing with stop_reason tool_use. Used to
  // build the forced-tool extraction/synthesis captures whose input must match
  // each tool's input_schema (authored from the tool's parse code).
  fun forcedToolReplay(
    toolName: String,
    inputJson: String,
    inputTokens: Int = 200,
    outputTokens: Int = 120,
  ): List<AnthropicTransportEvent> =
    listOf(
      opened(),
      frame(
        "message_start",
        """{"type":"message_start","message":{"id":"$MESSAGE_ID","type":"message","role":"assistant","model":"$MODEL","content":[],"stop_reason":null,"usage":{"input_tokens":$inputTokens,"output_tokens":1}}}""",
      ),
      frame(
        "content_block_start",
        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_forced_01","name":"$toolName","input":{}}}""",
      ),
      frame(
        "content_block_delta",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":${JsonStringEscaping.quote(
          inputJson,
        )}}}""",
      ),
      frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
      frame(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":$outputTokens}}""",
      ),
      frame("message_stop", """{"type":"message_stop"}"""),
    )

  // An HTTP error body for a given Anthropic error type.
  fun errorBody(
    type: String,
    message: String = "boom",
  ): String = """{"type":"error","error":{"type":"$type","message":"$message"}}"""

  // A mid-stream `error` frame for a given type.
  fun errorFrame(
    type: String,
    message: String = "boom",
  ): AnthropicTransportEvent.Frame = frame("error", errorBody(type, message))

  fun opened(requestId: String? = REQUEST_ID): AnthropicTransportEvent.Opened = AnthropicTransportEvent.Opened(requestId)

  fun frame(
    event: String,
    data: String,
  ): AnthropicTransportEvent.Frame = AnthropicTransportEvent.Frame(event, data)

  // Builds an SSE body from (event, data) pairs, with a `:` comment line and the
  // blank-line frame terminators the spec requires.
  fun sse(vararg frames: Pair<String, String>): String =
    buildString {
      append(": canonical recorded stream\n\n")
      for ((event, data) in frames) {
        append("event: ").append(event).append('\n')
        append("data: ").append(data).append('\n')
        append('\n')
      }
    }
}

// Minimal JSON string escaping for embedding a partial_json payload verbatim
// inside a recorded SSE frame's data (the real input_json_delta carries the
// tool input as a JSON-encoded string). Reuses kotlinx-serialization so the
// escaping matches the wire, rather than hand-rolling it.
private object JsonStringEscaping {
  fun quote(raw: String): String =
    kotlinx.serialization.json
      .JsonPrimitive(raw)
      .toString()
}
