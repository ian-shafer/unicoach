package ed.unicoach.chat

import kotlinx.serialization.json.JsonArray
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
    /**
     * Serializes a message list to the wire `messages` array — one
     * `{"role","content"}` object per message, `role` mapped USER → `"user"` and
     * ASSISTANT → `"assistant"`, `content` passed through verbatim. The single
     * definition of the on-the-wire messages shape: both
     * `AnthropicChatProvider.requestBody` (what is transmitted) and the RFC-106
     * `llm_requests.content` capture serialize through this one function, so the
     * logged request is byte-identical to the sent one.
     */
    fun serializeChatMessages(messages: List<ChatMessage>): JsonArray =
      buildJsonArray {
        for (message in messages) {
          add(
            buildJsonObject {
              put("role", if (message.role == ChatRole.USER) "user" else "assistant")
              put("content", message.content)
            },
          )
        }
      }

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
