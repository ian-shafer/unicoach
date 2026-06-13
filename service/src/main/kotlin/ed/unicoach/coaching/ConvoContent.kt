package ed.unicoach.coaching

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Single owner of the content-block representation shared by persistence,
 * provider replay, and the REST projection. A user turn is stored as a one-text
 * block array; rendering flattens a block array back to plain text.
 */
object ConvoContent {
  /** `[{"type": "text", "text": text}]` — the stored shape of every user turn. */
  fun userContent(text: String): JsonElement =
    buildJsonArray {
      add(
        buildJsonObject {
          put("type", "text")
          put("text", text)
        },
      )
    }

  /**
   * Concatenated `text` of every block whose `type == "text"`; `""` for anything
   * that is not a block array (faithful, lossy-free for the shapes this service
   * persists and the provider returns).
   */
  fun renderText(content: JsonElement): String {
    if (content !is JsonArray) return ""
    return buildString {
      for (block in content) {
        val obj = block as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        if (type != "text") continue
        val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
        append(text)
      }
    }
  }
}
