package ed.unicoach.chat

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

// The real AnthropicStreamTransport: a thin Ktor-client transport over the
// pinned Ktor stack. It POSTs {baseUrl}/v1/messages as a streaming request,
// reads non-2xx bodies into AnthropicHttpException, and parses 2xx byte
// channels into SSE frames. The CIO engine is non-blocking, so the flow is safe
// to collect from any dispatcher with no flowOn shift — the stream contract's
// execution-context obligation is met by construction.
//
// The backing HttpClient is owned and closed by the provider, not here.
class KtorAnthropicStreamTransport(
  private val client: HttpClient,
  private val config: AnthropicConfig,
) : AnthropicStreamTransport {
  override fun stream(body: JsonObject): Flow<AnthropicTransportEvent> =
    flow {
      client
        .preparePost("${config.baseUrl}/v1/messages") {
          header(HEADER_API_KEY, config.apiKey ?: "")
          header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION)
          header(HEADER_ACCEPT, ContentType.Text.EventStream.toString())
          contentType(ContentType.Application.Json)
          setBody(body.toString())
        }.execute { response ->
          val requestId = response.headers[HEADER_REQUEST_ID]
          if (!response.status.isSuccess()) {
            throw AnthropicHttpException(
              status = response.status.value,
              requestId = requestId,
              body = response.readErrorBody(),
            )
          }
          emit(AnthropicTransportEvent.Opened(requestId))
          emitFrames(response.bodyAsChannel())
        }
    }

  // The SSE spec, narrowed to what the Messages API emits: `event:`/`data:`
  // lines accumulate until a blank line dispatches the frame; multiple `data:`
  // lines join with `\n`; `:` comment lines are dropped; a frame with no
  // `event:` line is skipped (no modeled mapping). The plugin is not used — it
  // fails non-2xx responses before their bodies can be read.
  private suspend fun FlowCollector<AnthropicTransportEvent>.emitFrames(channel: ByteReadChannel) {
    var event: String? = null
    val data = StringBuilder()
    var hasData = false

    suspend fun dispatch() {
      if (event != null) {
        emit(AnthropicTransportEvent.Frame(event!!, data.toString()))
      }
      event = null
      data.setLength(0)
      hasData = false
    }

    while (true) {
      val line = channel.readLine(LineEnding.Lenient) ?: break
      when {
        line.isEmpty() -> {
          dispatch()
        }

        line.startsWith(COMMENT_PREFIX) -> {
          Unit
        }

        line.startsWith(EVENT_FIELD) -> {
          event = line.removePrefix(EVENT_FIELD).removePrefix(" ")
        }

        line.startsWith(DATA_FIELD) -> {
          if (hasData) data.append('\n')
          data.append(line.removePrefix(DATA_FIELD).removePrefix(" "))
          hasData = true
        }
      }
    }
    // A trailing frame not terminated by a blank line is still dispatched.
    dispatch()
  }

  private suspend fun HttpResponse.readErrorBody(): String = bodyAsChannel().readRemainingText()

  private suspend fun ByteReadChannel.readRemainingText(): String {
    val builder = StringBuilder()
    while (true) {
      val line = readLine(LineEnding.Lenient) ?: break
      if (builder.isNotEmpty()) builder.append('\n')
      builder.append(line)
    }
    return builder.toString()
  }

  companion object {
    const val ANTHROPIC_VERSION = "2023-06-01"
    private const val HEADER_API_KEY = "x-api-key"
    private const val HEADER_ANTHROPIC_VERSION = "anthropic-version"
    private const val HEADER_ACCEPT = "accept"
    private const val HEADER_REQUEST_ID = "request-id"
    private const val EVENT_FIELD = "event:"
    private const val DATA_FIELD = "data:"
    private const val COMMENT_PREFIX = ":"
  }
}
