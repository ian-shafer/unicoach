package ed.unicoach.chat

// The internal transport seam's output: what one streaming Messages API call
// surfaces above the HTTP layer, before any ChatEvent mapping. The provider,
// not the seam, turns these (and any thrown exception) into ChatEvent
// terminals.
sealed interface AnthropicTransportEvent {
  // 2xx response accepted; requestId = the `request-id` response header. Emitted
  // once, before any Frame, so failures after acceptance can still carry the
  // provider's request id.
  data class Opened(
    val requestId: String?,
  ) : AnthropicTransportEvent

  // One SSE frame, verbatim: event name + raw data payload (unparsed).
  data class Frame(
    val event: String,
    val data: String,
  ) : AnthropicTransportEvent
}
