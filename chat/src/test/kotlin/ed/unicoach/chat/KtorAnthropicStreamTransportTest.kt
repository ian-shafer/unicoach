package ed.unicoach.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorAnthropicStreamTransportTest {
  private val config =
    AnthropicConfig
      .from(
        com.typesafe.config.ConfigFactory.parseString(
          """
          chat.anthropic {
            apiKey = "sk-ant-test"
            baseUrl = "https://api.anthropic.test"
            connectTimeoutMs = 10000
            socketTimeoutMs = 60000
          }
          """.trimIndent(),
        ),
      ).getOrThrow()

  private val body = buildJsonObject { put("model", "claude-opus-4-8") }

  @Test
  fun `a 200 stream parses into Opened plus frames`() =
    runTest {
      val sseBody =
        AnthropicTestFixtures.sse(
          "message_start" to """{"type":"message_start"}""",
          "content_block_delta" to """{"type":"content_block_delta"}""",
        ) +
          // A frame whose data spans two `data:` lines joins with `\n`.
          "event: multi\ndata: line one\ndata: line two\n\n"

      val transport = transportServing(sseBody)

      val events = transport.stream(body).toList()

      val opened = assertIs<AnthropicTransportEvent.Opened>(events.first())
      assertEquals(AnthropicTestFixtures.REQUEST_ID, opened.requestId)

      val frames = events.drop(1).map { assertIs<AnthropicTransportEvent.Frame>(it) }
      assertEquals(3, frames.size)
      assertEquals("message_start", frames[0].event)
      assertEquals("""{"type":"message_start"}""", frames[0].data)
      assertEquals("content_block_delta", frames[1].event)
      assertEquals("multi", frames[2].event)
      assertEquals("line one\nline two", frames[2].data)
    }

  @Test
  fun `the request carries the wire contract`() =
    runTest {
      var captured: HttpRequestData? = null
      val engine =
        MockEngine { request ->
          captured = request
          respond(
            content = AnthropicTestFixtures.sse("message_stop" to """{"type":"message_stop"}"""),
            status = HttpStatusCode.OK,
            headers = headersOf("request-id", AnthropicTestFixtures.REQUEST_ID),
          )
        }
      val transport = KtorAnthropicStreamTransport(HttpClient(engine), config)

      transport.stream(body).toList()

      val request = assertIs<HttpRequestData>(captured)
      assertEquals("https://api.anthropic.test/v1/messages", request.url.toString())
      assertEquals("POST", request.method.value)
      assertEquals("sk-ant-test", request.headers["x-api-key"])
      assertEquals("2023-06-01", request.headers["anthropic-version"])
      assertTrue(request.headers["accept"]!!.contains("text/event-stream"))
      assertTrue(
        request.body.contentType
          .toString()
          .contains("application/json"),
      )
      assertEquals(body, sentBody(request))
    }

  @Test
  fun `a non-2xx response throws AnthropicHttpException`() =
    runTest {
      val errorBody = """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""
      val engine =
        MockEngine {
          respond(
            content = errorBody,
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf("request-id", "req_err_429"),
          )
        }
      val transport = KtorAnthropicStreamTransport(HttpClient(engine), config)

      val failure = assertFailsWith<AnthropicHttpException> { transport.stream(body).toList() }

      assertEquals(429, failure.status)
      assertEquals("req_err_429", failure.requestId)
      assertEquals(errorBody, failure.body)
    }

  @Test
  fun `the flow is cold`() =
    runTest {
      var calls = 0
      val engine =
        MockEngine {
          calls++
          respond(
            content = AnthropicTestFixtures.sse("message_stop" to """{"type":"message_stop"}"""),
            status = HttpStatusCode.OK,
            headers = headersOf("request-id", AnthropicTestFixtures.REQUEST_ID),
          )
        }
      val transport = KtorAnthropicStreamTransport(HttpClient(engine), config)
      val flow = transport.stream(body)

      flow.toList()
      flow.toList()

      assertEquals(2, calls)
    }

  private fun transportServing(sseBody: String): KtorAnthropicStreamTransport {
    val engine =
      MockEngine {
        respond(
          content = sseBody,
          status = HttpStatusCode.OK,
          headers = headersOf("request-id", AnthropicTestFixtures.REQUEST_ID),
        )
      }
    return KtorAnthropicStreamTransport(HttpClient(engine), config)
  }

  private suspend fun sentBody(request: HttpRequestData): JsonObject {
    val text =
      when (val content = request.body) {
        is io.ktor.http.content.TextContent -> content.text
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> String(content.bytes())
        is io.ktor.http.content.OutgoingContent.ReadChannelContent ->
          String(content.readFrom().toByteArray())
        else -> error("unexpected body type [${content::class}]")
      }
    return kotlinx.serialization.json.Json
      .parseToJsonElement(text) as JsonObject
  }
}
