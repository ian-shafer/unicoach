package ed.unicoach.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared wire-messages serializer (RFC 106): the single definition both
 * `AnthropicChatProvider.requestBody` and `LlmCallLog` (the `llm_requests`
 * capture) build the `messages` array from, so the logged request is
 * byte-identical to the sent one.
 */
class ChatMessageTest {
  private fun content(text: String): JsonObject = Json.parseToJsonElement("""{"type":"text","text":"$text"}""").jsonObject

  @Test
  fun `serializeChatMessages maps roles and passes content through`() {
    val messages =
      listOf(
        ChatMessage.text(ChatRole.USER, "hello"),
        ChatMessage.text(ChatRole.ASSISTANT, "hi there"),
      )

    val array = ChatMessage.serializeChatMessages(messages)

    assertEquals(2, array.size)
    // Role mapping: USER -> "user", ASSISTANT -> "assistant".
    assertEquals("user", array[0].jsonObject["role"]!!.jsonPrimitive.content)
    assertEquals("assistant", array[1].jsonObject["role"]!!.jsonPrimitive.content)
    // Content passthrough: the verbatim content-block array of each message.
    assertEquals(messages[0].content, array[0].jsonObject["content"])
    assertEquals(messages[1].content, array[1].jsonObject["content"])
  }

  @Test
  fun `serializeChatMessages preserves multi-block content verbatim`() {
    val blocks =
      Json.parseToJsonElement(
        """[{"type":"tool_use","id":"t1","name":"search","input":{}},{"type":"text","text":"done"}]""",
      )
    val message = ChatMessage(role = ChatRole.ASSISTANT, content = blocks)

    val array = ChatMessage.serializeChatMessages(listOf(message))

    assertEquals(1, array.size)
    assertEquals("assistant", array[0].jsonObject["role"]!!.jsonPrimitive.content)
    // The content block array is emitted unchanged (same JSON element).
    assertEquals(blocks, array[0].jsonObject["content"])
    assertEquals(2, array[0].jsonObject["content"]!!.jsonArray.size)
  }

  @Test
  fun `serializeChatMessages on an empty list yields an empty array`() {
    assertEquals(0, ChatMessage.serializeChatMessages(emptyList()).size)
  }
}
