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

  /**
   * The `input` object of the first `tool_use` block in [content] whose `name`
   * is [expectedName], or null when no such block is present (a forced tool call
   * that produced none, or produced a differently-named block). A matching block
   * with absent/non-object `input` yields an empty object, mirroring
   * [ConvoContent.toolUses]. Forced `tool_choice` yields exactly one `tool_use`
   * block, named for the forced tool; this is the single-payload variant the
   * structured-output calls need, distinct from `ConvoContent.toolUses` (the
   * multi-block list the chat tool-use loop consumes). `renderText` renders
   * `tool_use` blocks as empty, so the structured-output response side must read
   * the payload here.
   *
   * Matching on [expectedName] (not merely `type == "tool_use"`) makes the read
   * the single enforcement point of the forced-tool contract: a block for some
   * other tool cannot be mistaken for the forced payload.
   */
  fun toolUseInput(
    content: JsonElement,
    expectedName: String,
  ): JsonObject? {
    if (content !is JsonArray) return null
    for (block in content) {
      val obj = block as? JsonObject ?: continue
      if (obj["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
      if (obj["name"]?.jsonPrimitive?.contentOrNull != expectedName) continue
      return obj["input"] as? JsonObject ?: JsonObject(emptyMap())
    }
    return null
  }
}
