package ed.unicoach.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

// The internal seam between HTTP and ChatEvent mapping, playing the role
// SesSendOperation plays for SES: the real implementation is Ktor; tests supply
// a replayed sequence.
//
// One streaming Messages API call per collection. The returned flow emits
// Opened once (response accepted), then one Frame per SSE frame, then completes
// when the stream closes. It throws AnthropicHttpException for non-2xx
// responses (body read first); connect/read/parse failures propagate as their
// underlying exceptions. The provider, not the seam, turns all of these into
// terminals.
fun interface AnthropicStreamTransport {
  fun stream(body: JsonObject): Flow<AnthropicTransportEvent>
}
