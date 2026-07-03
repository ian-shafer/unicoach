package ed.unicoach.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The block-array content shape [ChatMessage.content] carries is owned here, in
 * the `chat` module: the port defines the wire shape, so the flatten-to-text
 * helper both the stub provider and the `service` projection need lives with it
 * rather than being copied per consumer.
 */
object ContentBlocks {
  /**
   * Concatenated `text` of every block whose `type == "text"`; `""` for anything
   * that is not a block array. Faithful and lossless for the shapes the service
   * persists and providers return; `tool_use`/`tool_result` blocks render empty.
   */
  fun renderText(content: JsonElement): String {
    if (content !is JsonArray) return ""
    return buildString {
      for (block in content) {
        val obj = block as? JsonObject ?: continue
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "text") continue
        append(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
      }
    }
  }
}
