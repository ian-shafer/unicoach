package ed.unicoach.chat

import kotlinx.coroutines.flow.Flow

// Provider-agnostic port for LLM chat completions; one method, streaming-first.
//
// Stream contract (binding on every adapter):
// - The flow emits zero or more non-terminal ChatEvents, then exactly one
//   ChatEvent.Terminal, then completes normally. Nothing follows a terminal.
// - Expected provider outcomes are NEVER thrown; they are returned as
//   Rejected / TransientFailure terminal events. An exception escaping the
//   flow (other than CancellationException) is a defect, not an API channel;
//   the caller treats it as transient.
// - The flow is cold: re-collecting is a fresh transmission (at-least-once,
//   no idempotency key).
// - Collector cancellation cancels the in-flight provider call; no partial
//   response is surfaced or persisted at the port level.
// - Implementations own their execution context: the flow must be safe to
//   collect from any dispatcher, with any internal blocking I/O shifted off
//   the collector's context by the adapter (flowOn), never delegated to the
//   caller.
// - Event order is the provider's order; block `index` correlates
//   ContentBlockStart/Delta/Stop for interleaved blocks.
interface ChatProvider {
  // Provider identity; written verbatim to convo_requests.provider by the caller.
  val id: String

  // Cold flow: each collection performs one provider call.
  fun stream(request: ChatRequest): Flow<ChatEvent>
}

// A ChatProvider whose owner must end it: what ChatProviderFactory builds. The
// factory's product may hold a client (AnthropicChatProvider's HttpClient), so
// its lifetime is in the TYPE its caller receives — closed with no cast, and a
// product that held a client without saying so would not compile. A stub with
// nothing to release implements close() as a no-op, explicitly.
interface CloseableChatProvider :
  ChatProvider,
  AutoCloseable

// Non-streaming accumulation: collects the stream, returns its terminal event
// (the last, by contract). A stream that violates the contract — empty, or
// ending on a non-terminal — throws IllegalStateException, the correct
// channel: a contract violation is a defect, not an outcome.
suspend fun ChatProvider.chat(request: ChatRequest): ChatEvent.Terminal {
  var lastEvent: ChatEvent? = null
  stream(request).collect { event -> lastEvent = event }
  return when (val last = lastEvent) {
    is ChatEvent.Terminal -> last
    null -> throw IllegalStateException("chat provider [$id] stream completed without emitting any event")
    else -> throw IllegalStateException("chat provider [$id] stream ended on a non-terminal event [$last]")
  }
}
