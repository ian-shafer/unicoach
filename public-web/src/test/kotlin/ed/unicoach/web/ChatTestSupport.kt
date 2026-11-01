package ed.unicoach.web

import ed.unicoach.chat.AnthropicChatProvider
import ed.unicoach.chat.AnthropicTestFixtures.errorFrame
import ed.unicoach.chat.AnthropicTestFixtures.frame
import ed.unicoach.chat.AnthropicTestFixtures.opened
import ed.unicoach.chat.AnthropicTransportEvent
import ed.unicoach.chat.Replay
import ed.unicoach.chat.ScriptedAnthropicTransport
import ed.unicoach.coaching.CoachingService
import ed.unicoach.db.models.User
import ed.unicoach.web.auth.CSRF_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chat suites' scaffolding (RFC 262): a coach over the REAL Anthropic
 * provider scripted at its transport seam, so a reply, a delta run and a
 * failure are the production provider's own mapping of the wire, and a signed-in
 * student to drive it as.
 */
object ChatTestSupport {
  /** A completed text reply arriving as [deltas], in order. */
  fun textReplay(vararg deltas: String): Replay = Replay(opening() + deltas.map(::textDelta) + closing())

  /**
   * [deltas] and then a mid-stream provider error of [errorType]: an
   * `overloaded_error` is retriable, an `invalid_request_error` is not.
   */
  fun failingReplay(
    errorType: String,
    vararg deltas: String,
  ): Replay = Replay(opening() + deltas.map(::textDelta) + errorFrame(errorType, "SECRET-PROVIDER-REASON"))

  /** A coach whose provider answers each call with the next of [replays]. */
  fun coach(
    vararg replays: Replay,
    freeAllowanceUsd: String? = null,
  ): CoachingService =
    WebAuthTestSupport.coachingService(
      AnthropicChatProvider(ScriptedAnthropicTransport(replays.toList()), AutoCloseable {}),
      freeAllowanceUsd,
    )

  /** A verified user with a `students` row, signed in: their session token. */
  fun signedInStudent(): Pair<User, String> {
    val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
    WebAuthTestSupport.seedStudent(user)
    return user to WebAuthTestSupport.login(user.email.value)
  }

  /**
   * A composer POST, as `chat.ts` sends it: same-origin, form-encoded, asking
   * for the stream, with the session's CSRF token in the header (RFC 265).
   * [csrf] is `null` to send no header at all.
   */
  suspend fun sendMessage(
    client: HttpClient,
    path: String,
    token: String,
    form: Parameters,
    csrf: String? = WebAuthTestSupport.csrfTokenFor(token),
  ): HttpResponse =
    client.post(path) {
      header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
      header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
      header(HttpHeaders.Accept, "text/event-stream, text/html")
      csrf?.let { header(CSRF_HEADER, it) }
      setBody(FormDataContent(form))
    }

  fun message(text: String): Parameters = Parameters.build { append("message", text) }

  /** One server-sent event of the chat's own stream. */
  data class Event(
    val name: String,
    val data: String,
  )

  /** The stream's events, in order, parsed from the served bytes. */
  fun eventsOf(body: String): List<Event> =
    body
      .split("\n\n")
      .filter { it.isNotBlank() }
      .map { frame ->
        val lines = frame.lines()
        Event(
          name = lines.single { it.startsWith("event: ") }.removePrefix("event: "),
          data = lines.single { it.startsWith("data: ") }.removePrefix("data: "),
        )
      }

  private fun opening(): List<AnthropicTransportEvent> =
    listOf(
      opened(),
      frame(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_web","type":"message","role":"assistant","model":"m","content":[],"stop_reason":null,"usage":{"input_tokens":5,"output_tokens":1}}}""",
      ),
      frame("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
    )

  private fun textDelta(text: String): AnthropicTransportEvent =
    frame(
      "content_block_delta",
      """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${JsonPrimitive(text)}}}""",
    )

  private fun closing(): List<AnthropicTransportEvent> =
    listOf(
      frame("content_block_stop", """{"type":"content_block_stop","index":0}"""),
      frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}"""),
      frame("message_stop", """{"type":"message_stop"}"""),
    )
}
