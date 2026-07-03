package ed.unicoach.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One message on the wire. [content] is the Anthropic message-`content` shape —
 * an array of content blocks (`text`, `tool_use`, `tool_result`, ...) — not a
 * flat string, because the tool-use loop must express block kinds a string
 * cannot: an assistant turn carrying a `tool_use` block, and a user turn
 * carrying a `tool_result` block. This is the same content-block array
 * `ConvoContent` persists and renders, so persistence, replay, and REST share
 * one representation.
 */
data class ChatMessage(
  val role: ChatRole,
  // Content-block array, e.g. [{"type":"text","text":...}].
  val content: JsonElement,
) {
  companion object {
    /** Convenience for the common single-text-block message. */
    fun text(
      role: ChatRole,
      text: String,
    ): ChatMessage =
      ChatMessage(
        role = role,
        content =
          buildJsonArray {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", text)
              },
            )
          },
      )
  }
}
