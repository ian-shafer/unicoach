package ed.unicoach.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicChatProviderTest {
  private val request =
    ChatRequest(
      model = "claude-opus-4-8",
      system = "You are a coach.",
      messages =
        listOf(
          ChatMessage(ChatRole.USER, "hello"),
          ChatMessage(ChatRole.ASSISTANT, "hi"),
          ChatMessage(ChatRole.USER, "weather?"),
        ),
      maxTokens = 256,
    )

  @Test
  fun `id is the wire identity anthropic`() {
    provider(Replay(emptyList())).use { assertEquals("anthropic", it.id) }
  }

  @Test
  fun `the canonical text stream maps 1 to 1`() =
    runTest {
      val events = collect(AnthropicTestFixtures.canonicalTextReplay)

      // ping dropped; exactly one terminal, last, Completed.
      val terminals = events.filterIsInstance<ChatEvent.Terminal>()
      assertEquals(1, terminals.size)
      assertIs<ChatEvent.Completed>(events.last())
      assertTrue(events.none { it is ChatEvent.Raw })

      val start = assertIs<ChatEvent.MessageStart>(events[0])
      assertEquals(AnthropicTestFixtures.MESSAGE_ID, start.providerRequestId)
      assertEquals(AnthropicTestFixtures.MODEL, start.model)
      assertEquals(TokenUsage(12, 1, 3, 0), start.usage)

      val blockStart = assertIs<ChatEvent.ContentBlockStart>(events[1])
      assertEquals(0, blockStart.index)
      assertEquals("text", blockStart.blockType)
      // Empty-open rule: {"type":"text","text":""} -> null.
      assertNull(blockStart.block)

      val deltas = events.filterIsInstance<ChatEvent.ContentBlockDelta>()
      assertEquals(2, deltas.size)
      assertEquals("Hello", (deltas[0].delta as ContentDelta.Text).text)
      assertEquals(", world", (deltas[1].delta as ContentDelta.Text).text)

      assertEquals(0, events.filterIsInstance<ChatEvent.ContentBlockStop>().single().index)

      val messageDelta = events.filterIsInstance<ChatEvent.MessageDelta>().single()
      assertEquals("end_turn", messageDelta.stopReason)
      assertEquals(TokenUsage(null, 8, null, null), messageDelta.usage)
    }

  @Test
  fun `rawPayload equals the non-streaming body`() =
    runTest {
      val completed = assertIs<ChatEvent.Completed>(collect(AnthropicTestFixtures.canonicalTextReplay).last())
      val raw = assertIs<JsonObject>(completed.rawPayload)

      assertEquals(AnthropicTestFixtures.MESSAGE_ID, raw["id"]!!.jsonPrimitive.content)
      assertEquals(AnthropicTestFixtures.MODEL, raw["model"]!!.jsonPrimitive.content)
      assertEquals("end_turn", raw["stop_reason"]!!.jsonPrimitive.content)

      val block = raw["content"]!!.jsonArray.single().jsonObject
      assertEquals("text", block["type"]!!.jsonPrimitive.content)
      assertEquals("Hello, world", block["text"]!!.jsonPrimitive.content)

      // Cumulative usage: message_start input_tokens preserved, output_tokens
      // overwritten by message_delta.
      val usage = raw["usage"]!!.jsonObject
      assertEquals(12, usage["input_tokens"]!!.jsonPrimitive.int)
      assertEquals(8, usage["output_tokens"]!!.jsonPrimitive.int)
    }

  @Test
  fun `the response projects the accumulated message`() =
    runTest {
      val completed = assertIs<ChatEvent.Completed>(collect(AnthropicTestFixtures.canonicalTextReplay).last())
      val raw = completed.rawPayload.jsonObject
      val response = completed.response

      assertEquals(raw["content"], response.content)
      assertEquals(raw["model"]!!.jsonPrimitive.content, response.modelResolved)
      assertEquals(raw["stop_reason"]!!.jsonPrimitive.content, response.stopReason)
      assertEquals(raw["id"]!!.jsonPrimitive.content, response.providerRequestId)
      assertEquals(TokenUsage(12, 8, 3, 0), response.usage)
    }

  @Test
  fun `tool_use accumulates parsed input`() =
    runTest {
      val events = collect(listOf(AnthropicTestFixtures.opened()) + AnthropicTestFixtures.toolUseFrames)

      val blockStart = assertIs<ChatEvent.ContentBlockStart>(events[1])
      assertEquals("tool_use", blockStart.blockType)
      val block = assertIs<JsonObject>(blockStart.block)
      assertEquals("toolu_01", block["id"]!!.jsonPrimitive.content)
      assertEquals("get_weather", block["name"]!!.jsonPrimitive.content)

      val toolInputs = events.filterIsInstance<ChatEvent.ContentBlockDelta>().map { it.delta }
      assertTrue(toolInputs.all { it is ContentDelta.ToolInput })

      val completed = assertIs<ChatEvent.Completed>(events.last())
      val accBlock =
        completed.rawPayload.jsonObject["content"]!!
          .jsonArray
          .single()
          .jsonObject
      // input parsed from the concatenated partial_json, an object not a string.
      assertEquals("SF", accBlock["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
    }

  @Test
  fun `tool_use with an empty buffer accumulates an empty object`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened(),
          AnthropicTestFixtures.frame(
            "message_start",
            """{"type":"message_start","message":{"id":"m","model":"x","content":[],"usage":{}}}""",
          ),
          AnthropicTestFixtures.frame(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"n","input":{}}}""",
          ),
          AnthropicTestFixtures.frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
          AnthropicTestFixtures.frame("message_stop", """{"type":"message_stop"}"""),
        )

      val completed = assertIs<ChatEvent.Completed>(collect(frames).last())
      val accBlock =
        completed.rawPayload.jsonObject["content"]!!
          .jsonArray
          .single()
          .jsonObject
      assertEquals(emptyMap(), accBlock["input"]!!.jsonObject)
    }

  @Test
  fun `thinking and signature deltas`() =
    runTest {
      val events = collect(listOf(AnthropicTestFixtures.opened()) + AnthropicTestFixtures.thinkingFrames)

      val deltas = events.filterIsInstance<ChatEvent.ContentBlockDelta>().map { it.delta }
      assertEquals("Let me think", (deltas[0] as ContentDelta.Thinking).thinking)
      // signature_delta surfaces as Opaque on the stream.
      assertIs<ContentDelta.Opaque>(deltas[1])

      val completed = assertIs<ChatEvent.Completed>(events.last())
      val accBlock =
        completed.rawPayload.jsonObject["content"]!!
          .jsonArray
          .single()
          .jsonObject
      // ... and folds into the accumulated block's signature.
      assertEquals("sig-abc", accBlock["signature"]!!.jsonPrimitive.content)
      assertEquals("Let me think", accBlock["thinking"]!!.jsonPrimitive.content)
    }

  @Test
  fun `an unknown delta type is Opaque and not folded`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened(),
          AnthropicTestFixtures.frame(
            "message_start",
            """{"type":"message_start","message":{"id":"m","model":"x","content":[],"usage":{}}}""",
          ),
          AnthropicTestFixtures.frame(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
          ),
          AnthropicTestFixtures.frame(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"future_delta","mystery":"x"}}""",
          ),
          AnthropicTestFixtures.frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
          AnthropicTestFixtures.frame("message_stop", """{"type":"message_stop"}"""),
        )

      val events = collect(frames)
      val delta = events.filterIsInstance<ChatEvent.ContentBlockDelta>().single()
      val opaque = assertIs<ContentDelta.Opaque>(delta.delta)
      assertEquals(
        "future_delta",
        opaque.raw.jsonObject["type"]!!
          .jsonPrimitive.content,
      )

      val completed = assertIs<ChatEvent.Completed>(events.last())
      val accBlock =
        completed.rawPayload.jsonObject["content"]!!
          .jsonArray
          .single()
          .jsonObject
      // unchanged: just the opening {"type":"text","text":""}.
      assertEquals(setOf("type", "text"), accBlock.keys)
    }

  @Test
  fun `an unknown SSE event type surfaces as Raw`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened(),
          AnthropicTestFixtures.frame(
            "message_start",
            """{"type":"message_start","message":{"id":"m","model":"x","content":[],"usage":{}}}""",
          ),
          AnthropicTestFixtures.frame("future_event", """{"hello":"world"}"""),
          AnthropicTestFixtures.frame("not_json", """plain text not json"""),
          AnthropicTestFixtures.frame("message_stop", """{"type":"message_stop"}"""),
        )

      val events = collect(frames)
      val raws = events.filterIsInstance<ChatEvent.Raw>()
      assertEquals(2, raws.size)
      assertEquals("future_event", raws[0].event)
      assertEquals(
        "world",
        raws[0]
          .data.jsonObject["hello"]!!
          .jsonPrimitive.content,
      )
      // unparseable -> JsonPrimitive of the raw text.
      assertEquals("not_json", raws[1].event)
      assertEquals("plain text not json", raws[1].data.jsonPrimitive.content)

      assertIs<ChatEvent.Completed>(events.last())
    }

  @Test
  fun `the request body maps ChatRequest`() =
    runTest {
      val captured = CapturingTransport(AnthropicTestFixtures.canonicalTextReplay)
      AnthropicChatProvider(captured, AutoCloseable {}).use { it.stream(request).toList() }
      val body = captured.body!!

      assertEquals("claude-opus-4-8", body["model"]!!.jsonPrimitive.content)
      assertEquals(256, body["max_tokens"]!!.jsonPrimitive.int)
      assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
      assertEquals("You are a coach.", body["system"]!!.jsonPrimitive.content)

      val messages = body["messages"]!!.jsonArray
      assertEquals(3, messages.size)
      assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
      assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
      assertEquals("weather?", messages[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

  @Test
  fun `system is omitted when null`() =
    runTest {
      val captured = CapturingTransport(AnthropicTestFixtures.canonicalTextReplay)
      AnthropicChatProvider(captured, AutoCloseable {}).use {
        it.stream(request.copy(system = null)).toList()
      }
      assertNull(captured.body!!["system"])
    }

  @Test
  fun `params pass through and typed fields win collisions`() =
    runTest {
      val params =
        kotlinx.serialization.json.buildJsonObject {
          put("temperature", kotlinx.serialization.json.JsonPrimitive(0.5))
          put("model", kotlinx.serialization.json.JsonPrimitive("override-model"))
          put("max_tokens", kotlinx.serialization.json.JsonPrimitive(1))
          put("stream", kotlinx.serialization.json.JsonPrimitive(false))
        }
      val captured = CapturingTransport(AnthropicTestFixtures.canonicalTextReplay)
      AnthropicChatProvider(captured, AutoCloseable {}).use {
        it.stream(request.copy(params = params)).toList()
      }
      val body = captured.body!!

      assertEquals(0.5, body["temperature"]!!.jsonPrimitive.content.toDouble())
      // typed fields win the collision.
      assertEquals("claude-opus-4-8", body["model"]!!.jsonPrimitive.content)
      assertEquals(256, body["max_tokens"]!!.jsonPrimitive.int)
      assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
    }

  @Test
  fun `an error event after message_start classifies with the message id`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened("req-opened"),
          AnthropicTestFixtures.frame(
            "message_start",
            """{"type":"message_start","message":{"id":"${AnthropicTestFixtures.MESSAGE_ID}","model":"x","content":[],"usage":{}}}""",
          ),
          AnthropicTestFixtures.errorFrame("overloaded_error", "overloaded"),
        )

      val terminal = collect(frames).last()
      val failure = assertIs<ChatEvent.TransientFailure>(terminal)
      assertEquals(AnthropicTestFixtures.MESSAGE_ID, failure.providerRequestId)
      assertTrue(failure.reason.contains("overloaded_error"))
      val raw = assertIs<JsonObject>(failure.rawPayload)
      assertEquals("overloaded_error", raw["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

  @Test
  fun `an error event before message_start carries the Opened request id`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened("req-opened"),
          AnthropicTestFixtures.errorFrame("invalid_request_error", "bad"),
        )

      val terminal = collect(frames).last()
      val rejected = assertIs<ChatEvent.Rejected>(terminal)
      assertEquals("req-opened", rejected.providerRequestId)
    }

  @Test
  fun `an unrecognized error type is transient`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened(),
          AnthropicTestFixtures.errorFrame("brand_new_error", "novel"),
        )

      assertIs<ChatEvent.TransientFailure>(collect(frames).last())
    }

  @Test
  fun `permanent HTTP statuses reject`() =
    runTest {
      val statusToType =
        mapOf(
          400 to "invalid_request_error",
          401 to "authentication_error",
          403 to "permission_error",
          404 to "not_found_error",
          413 to "request_too_large",
        )
      for ((status, type) in statusToType) {
        val exception = AnthropicHttpException(status, "req-$status", AnthropicTestFixtures.errorBody(type))
        val terminal = collect(throwingReplay(exception)).last()
        val rejected = assertIs<ChatEvent.Rejected>(terminal)
        assertTrue(rejected.reason.contains("[$status]"), "reason for $status: ${rejected.reason}")
        assertTrue(rejected.reason.contains(type))
        assertEquals("req-$status", rejected.providerRequestId)
        assertEquals(
          type,
          rejected.rawPayload!!
            .jsonObject["error"]!!
            .jsonObject["type"]!!
            .jsonPrimitive.content,
        )
      }
    }

  @Test
  fun `retriable HTTP statuses are transient`() =
    runTest {
      for (status in listOf(408, 429, 500, 529)) {
        val exception = AnthropicHttpException(status, "req-$status", AnthropicTestFixtures.errorBody("api_error"))
        assertIs<ChatEvent.TransientFailure>(collect(throwingReplay(exception)).last())
      }
    }

  @Test
  fun `an unparseable 4xx body falls back to the status bucket`() =
    runTest {
      val exception = AnthropicHttpException(422, "req-422", "not json at all")
      val terminal = collect(throwingReplay(exception)).last()
      val rejected = assertIs<ChatEvent.Rejected>(terminal)
      assertEquals("not json at all", (rejected.rawPayload as JsonPrimitive).contentOrNull)
    }

  @Test
  fun `transport IO failures are transient with null provenance`() =
    runTest {
      val terminal = collect(throwingReplay(IOException("connection reset"))).last()
      val failure = assertIs<ChatEvent.TransientFailure>(terminal)
      assertNull(failure.providerRequestId)
      assertNull(failure.rawPayload)
      assertTrue(failure.reason.startsWith("transport"))
    }

  @Test
  fun `a truncated stream is transient`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened(),
          AnthropicTestFixtures.frame(
            "message_start",
            """{"type":"message_start","message":{"id":"${AnthropicTestFixtures.MESSAGE_ID}","model":"x","content":[],"usage":{}}}""",
          ),
          AnthropicTestFixtures.frame(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
          ),
          AnthropicTestFixtures.frame(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
          ),
          // no terminal frame: stream just ends.
        )

      val events = collect(frames)
      val failure = assertIs<ChatEvent.TransientFailure>(events.last())
      assertTrue(failure.reason.startsWith("truncated stream"))
      assertEquals(AnthropicTestFixtures.MESSAGE_ID, failure.providerRequestId)
      assertEquals(1, events.filterIsInstance<ChatEvent.Terminal>().size)
    }

  @Test
  fun `an out-of-order frame is a protocol violation`() =
    runTest {
      val frames =
        listOf(
          AnthropicTestFixtures.opened(),
          AnthropicTestFixtures.frame(
            "message_start",
            """{"type":"message_start","message":{"id":"m","model":"x","content":[],"usage":{}}}""",
          ),
          // delta for index 0 with no open block.
          AnthropicTestFixtures.frame(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}""",
          ),
        )

      val terminal = collect(frames).last()
      val failure = assertIs<ChatEvent.TransientFailure>(terminal)
      assertTrue(failure.reason.startsWith("protocol violation"), failure.reason)
    }

  @Test
  fun `frames after message_stop are not collected`() =
    runTest {
      val frames =
        AnthropicTestFixtures.canonicalTextReplay +
          AnthropicTestFixtures.frame("message_start", """{"type":"message_start","message":{"id":"x","content":[]}}""")

      val events = collect(frames)
      assertIs<ChatEvent.Completed>(events.last())
      assertEquals(1, events.filterIsInstance<ChatEvent.Terminal>().size)
    }

  @Test
  fun `every failure path emits exactly one terminal`() =
    runTest {
      val cases =
        listOf(
          Replay(listOf(AnthropicTestFixtures.opened(), AnthropicTestFixtures.errorFrame("overloaded_error"))),
          Replay(listOf(AnthropicTestFixtures.opened(), AnthropicTestFixtures.errorFrame("invalid_request_error"))),
          Replay(listOf(AnthropicTestFixtures.opened(), AnthropicTestFixtures.errorFrame("brand_new_error"))),
          throwingReplay(AnthropicHttpException(400, "r", AnthropicTestFixtures.errorBody("invalid_request_error"))),
          throwingReplay(AnthropicHttpException(500, "r", AnthropicTestFixtures.errorBody("api_error"))),
          throwingReplay(AnthropicHttpException(422, "r", "no")),
          throwingReplay(IOException("io")),
        )
      for (case in cases) {
        val events = collect(case)
        assertEquals(1, events.filterIsInstance<ChatEvent.Terminal>().size)
        assertIs<ChatEvent.Terminal>(events.last())
      }
    }

  @Test
  fun `collector cancellation cancels the transport`() =
    runTest {
      var transportCancelled = false
      val transport =
        AnthropicStreamTransport {
          flow {
            emit(AnthropicTestFixtures.opened())
            emit(
              AnthropicTestFixtures.frame(
                "message_start",
                """{"type":"message_start","message":{"id":"m","content":[]}}""",
              ),
            )
            // hang so the collector cancels mid-stream.
            CompletableDeferred<Unit>().await()
          }.onCompletion { cause -> if (cause != null) transportCancelled = true }
        }
      val provider = AnthropicChatProvider(transport, AutoCloseable {})

      coroutineScope {
        val started = CompletableDeferred<Unit>()
        val job =
          provider
            .stream(request)
            .onEach { started.complete(Unit) }
            .launchIn(this)
        started.await()
        yield()
        job.cancel()
        job.join()
      }
      assertTrue(transportCancelled)
      provider.close()
    }

  @Test
  fun `the flow is cold`() =
    runTest {
      val transport = CapturingTransport(AnthropicTestFixtures.canonicalTextReplay)
      val provider = AnthropicChatProvider(transport, AutoCloseable {})
      val flow = provider.stream(request)

      flow.toList()
      flow.toList()

      assertEquals(2, transport.calls)
      provider.close()
    }

  @Test
  fun `close closes the injected resource`() {
    var closed = false
    val provider = AnthropicChatProvider(FakeTransport(Replay(emptyList())), AutoCloseable { closed = true })

    provider.close()

    assertTrue(closed)
  }

  // --- helpers ---------------------------------------------------------------

  private suspend fun collect(events: List<AnthropicTransportEvent>): List<ChatEvent> = collect(Replay(events))

  private suspend fun collect(replay: Replay): List<ChatEvent> = provider(replay).use { it.stream(request).toList() }

  private fun provider(replay: Replay): AnthropicChatProvider = AnthropicChatProvider(FakeTransport(replay), AutoCloseable {})

  // A recorded transport sequence: events to emit, optionally followed by a
  // thrown exception (HTTP/IO failures originate as a thrown exception from the
  // transport flow).
  private data class Replay(
    val events: List<AnthropicTransportEvent>,
    val throwing: Throwable? = null,
  )

  private fun throwingReplay(error: Throwable): Replay = Replay(emptyList(), error)

  private class FakeTransport(
    private val replay: Replay,
  ) : AnthropicStreamTransport {
    override fun stream(body: JsonObject): Flow<AnthropicTransportEvent> =
      flow {
        for (event in replay.events) emit(event)
        replay.throwing?.let { throw it }
      }
  }

  private class CapturingTransport(
    private val events: List<AnthropicTransportEvent>,
  ) : AnthropicStreamTransport {
    var body: JsonObject? = null
    var calls = 0

    override fun stream(body: JsonObject): Flow<AnthropicTransportEvent> {
      this.body = body
      calls++
      return flow { for (event in events) emit(event) }
    }
  }
}
