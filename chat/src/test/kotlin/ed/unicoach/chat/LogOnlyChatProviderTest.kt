package ed.unicoach.chat

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogOnlyChatProviderTest {
  private val provider = LogOnlyChatProvider()

  private val request =
    ChatRequest(
      model = "claude-opus-4-8",
      system = "You are a coach.",
      messages =
        listOf(
          ChatMessage(ChatRole.USER, "hello"),
          ChatMessage(ChatRole.ASSISTANT, "hi there"),
          ChatMessage(ChatRole.USER, "ok"),
        ),
      maxTokens = 64,
    )

  private val ChatRequest.inputChars: Int
    get() = (system?.length ?: 0) + messages.sumOf { it.text.length }

  @Test
  fun `id is the wire identity log`() {
    assertEquals("log", provider.id)
  }

  @Test
  fun `stream emits a well-formed event sequence`() =
    runTest {
      val events = provider.stream(request).toList()

      val first = assertIs<ChatEvent.MessageStart>(events.first())
      assertEquals(request.model, first.model)
      assertEquals(TokenUsage(request.inputChars, null, null, null), first.usage)

      val terminals = events.filterIsInstance<ChatEvent.Terminal>()
      assertEquals(1, terminals.size)
      assertEquals(events.last(), terminals.single())
      assertIs<ChatEvent.Completed>(events.last())
    }

  @Test
  fun `event order correlates one text block`() =
    runTest {
      val events = provider.stream(request).toList()

      val blockStartIndex = events.indexOfFirst { it is ChatEvent.ContentBlockStart }
      val deltaIndices = events.indices.filter { events[it] is ChatEvent.ContentBlockDelta }
      val blockStopIndex = events.indexOfFirst { it is ChatEvent.ContentBlockStop }

      val blockStart = assertIs<ChatEvent.ContentBlockStart>(events[blockStartIndex])
      assertEquals(0, blockStart.index)
      assertEquals("text", blockStart.blockType)
      assertNull(blockStart.block)
      assertTrue(deltaIndices.all { it > blockStartIndex })

      for (i in deltaIndices) {
        val delta = assertIs<ChatEvent.ContentBlockDelta>(events[i])
        assertEquals(0, delta.index)
        assertIs<ContentDelta.Text>(delta.delta)
      }

      assertEquals(0, assertIs<ChatEvent.ContentBlockStop>(events[blockStopIndex]).index)
      assertTrue(blockStopIndex > deltaIndices.last())

      val messageDelta = events.filterIsInstance<ChatEvent.MessageDelta>().single()
      assertEquals("end_turn", messageDelta.stopReason)
      val reply = responseText(events)
      assertEquals(TokenUsage(request.inputChars, reply.length, null, null), messageDelta.usage)
    }

  @Test
  fun `accumulated deltas equal the response content text`() =
    runTest {
      val events = provider.stream(request).toList()

      val accumulated =
        events
          .filterIsInstance<ChatEvent.ContentBlockDelta>()
          .joinToString("") { (it.delta as ContentDelta.Text).text }

      assertEquals(responseText(events), accumulated)
    }

  @Test
  fun `reply is deterministic and echoes the last message`() =
    runTest {
      val stream = provider.stream(request)
      val firstCollection = stream.toList()
      val secondCollection = stream.toList()

      assertEquals("[log] echo: ok", responseText(firstCollection))
      assertEquals(responseText(firstCollection), responseText(secondCollection))

      // Cold flow: each collection is a fresh call with a fresh id; everything
      // else is identical.
      assertEquals(normalized(firstCollection), normalized(secondCollection))
      assertNotEquals(providerRequestId(firstCollection), providerRequestId(secondCollection))
    }

  @Test
  fun `long replies chunk at the delta bound`() =
    runTest {
      val longText = "x".repeat(LogOnlyChatProvider.DELTA_CHUNK_SIZE * 3)
      val longRequest = request.copy(messages = listOf(ChatMessage(ChatRole.USER, longText)), maxTokens = 1)

      val events = provider.stream(longRequest).toList()
      val chunks =
        events
          .filterIsInstance<ChatEvent.ContentBlockDelta>()
          .map { (it.delta as ContentDelta.Text).text }

      assertTrue(chunks.size >= 2)
      assertTrue(chunks.all { it.length <= LogOnlyChatProvider.DELTA_CHUNK_SIZE })
      // maxTokens does not truncate the echo.
      assertEquals(LogOnlyChatProvider.ECHO_PREFIX + longText, responseText(events))
    }

  @Test
  fun `completed response carries the request envelope`() =
    runTest {
      val events = provider.stream(request).toList()
      val response = assertIs<ChatEvent.Completed>(events.last()).response

      assertEquals(request.model, response.modelResolved)
      assertNotNull(response.providerRequestId)
      assertEquals(request.inputChars, response.usage.inputTokens)
      assertEquals(responseText(events).length, response.usage.outputTokens)
      assertNull(response.usage.cacheReadTokens)
      assertNull(response.usage.cacheWriteTokens)
    }

  @Test
  fun `raw payload is a structured stub object`() =
    runTest {
      val completed = assertIs<ChatEvent.Completed>(provider.stream(request).toList().last())

      val payload = assertIs<JsonObject>(completed.rawPayload)
      assertEquals(JsonPrimitive("log"), payload["provider"])
      assertEquals(completed.response.content, payload["content"])
    }

  @Test
  fun `empty messages list yields the empty echo`() =
    runTest {
      val events = provider.stream(request.copy(messages = emptyList())).toList()

      assertEquals("[log] echo: ", responseText(events))
    }

  @Test
  fun `chat extension returns the terminal event`() =
    runTest {
      val terminal = provider.chat(request)

      val completed = assertIs<ChatEvent.Completed>(terminal)
      val fresh = assertIs<ChatEvent.Completed>(provider.stream(request).toList().last())
      assertEquals(
        fresh.response.copy(providerRequestId = null),
        completed.response.copy(providerRequestId = null),
      )
    }

  private fun responseText(events: List<ChatEvent>): String {
    val completed = events.filterIsInstance<ChatEvent.Completed>().single()
    return completed.response.content
      .jsonArray
      .single()
      .jsonObject
      .getValue("text")
      .jsonPrimitive
      .content
  }

  private fun providerRequestId(events: List<ChatEvent>): String? =
    events
      .filterIsInstance<ChatEvent.MessageStart>()
      .single()
      .providerRequestId

  // Rewrites every providerRequestId-bearing field to a fixed placeholder so
  // two collections of the cold flow can be compared for full equality.
  private fun normalized(events: List<ChatEvent>): List<ChatEvent> =
    events.map { event ->
      when (event) {
        is ChatEvent.MessageStart -> {
          event.copy(providerRequestId = "<id>")
        }

        is ChatEvent.Completed -> {
          ChatEvent.Completed(
            response = event.response.copy(providerRequestId = "<id>"),
            rawPayload = JsonObject(event.rawPayload.jsonObject + ("id" to JsonPrimitive("<id>"))),
          )
        }

        else -> {
          event
        }
      }
    }
}
