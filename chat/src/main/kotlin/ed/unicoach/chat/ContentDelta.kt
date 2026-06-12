package ed.unicoach.chat

import kotlinx.serialization.json.JsonElement

// Incremental content payloads for ChatEvent.ContentBlockDelta. `Opaque` is
// the escape hatch for unrecognized provider delta types (e.g. Anthropic's
// signature_delta), preserving fidelity without freezing the taxonomy.
sealed interface ContentDelta {
  data class Text(
    val text: String,
  ) : ContentDelta

  data class Thinking(
    val thinking: String,
  ) : ContentDelta

  data class ToolInput(
    val partialJson: String,
  ) : ContentDelta

  // Unrecognized delta types, verbatim.
  data class Opaque(
    val raw: JsonElement,
  ) : ContentDelta
}
