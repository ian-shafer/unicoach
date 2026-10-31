package ed.unicoach.web.chat

import ed.unicoach.coaching.ConvoContent
import ed.unicoach.coaching.ReplyEvent
import ed.unicoach.coaching.studentSentence
import ed.unicoach.web.render.coachMessageHtml
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The web chat's OWN browser-facing stream (RFC 262 §6, spec preamble 9).
 *
 * Nothing outside `public-web` reads it, so `rest-server`'s event names are not
 * a constraint — only [ReplyEvent] is. The framing is the SSE framing
 * (`event:` / one-line `data:` / blank line), chosen because its incremental
 * parser is trivial, not for compatibility:
 *
 * | event       | data                                   |
 * | ----------- | -------------------------------------- |
 * | `started`   | `{"conversationUrl": "/chat/<uuid>"}`  |
 * | `delta`     | `{"text": "…"}`                        |
 * | `completed` | `{"html": "<the rendered coach message>"}` |
 * | `failed`    | `{"retriable": bool, "message": "…"}`  |
 *
 * Exactly one terminal, last. `completed.html` is the coach message through
 * the ONE Markdown renderer — the very fragment the page renders for that
 * exchange — so the client never parses Markdown and never builds HTML from
 * model text: it paints deltas as text and inserts only this. `failed` carries
 * `:service`'s sentence ([studentSentence]); [ReplyEvent.Failed.reason] never
 * leaves the server.
 *
 * No `id:`, `retry:` or heartbeat, and so no resume (D-C2): a disconnect
 * cancels the turn on the server, exactly as on the REST stream.
 *
 * `chat.ts`'s `decodeEvent` restates these names and fields — the two languages
 * share no source — skips a name it does not know, and drops (and reports) a
 * known event whose data it cannot decode. Both sides are held to
 * `src/test/resources/chat-client-contract.json`: `ChatClientContractTest`
 * asserts the served stream against it, `chat_test.ts` the decoder.
 */
internal object ChatEvents {
  const val STARTED = "started"
  const val DELTA = "delta"
  const val COMPLETED = "completed"
  const val FAILED = "failed"
}

/**
 * Opens the stream and relays [reply]: `started` first, the deltas, then its one
 * terminal. Every refusal must already have been answered — once the bytes
 * writer opens the status is 200, and the only failure left is a `failed` event.
 */
internal suspend fun ApplicationCall.respondChatStream(
  reply: Flow<ReplyEvent>,
  conversationUrl: String,
) {
  response.header(HttpHeaders.CacheControl, "no-store")
  respondBytesWriter(contentType = ContentType.Text.EventStream) {
    writeStarted(conversationUrl)
    reply.collect { event ->
      when (event) {
        is ReplyEvent.Delta -> writeDelta(event)
        is ReplyEvent.Completed -> writeCompleted(event)
        is ReplyEvent.Failed -> writeFailed(event)
      }
    }
  }
}

private suspend fun ByteWriteChannel.writeStarted(conversationUrl: String) {
  writeEvent(ChatEvents.STARTED, buildJsonObject { put("conversationUrl", conversationUrl) })
}

private suspend fun ByteWriteChannel.writeDelta(event: ReplyEvent.Delta) {
  writeEvent(ChatEvents.DELTA, buildJsonObject { put("text", event.text) })
}

/** The reply through the ONE Markdown renderer: the fragment the page renders for this exchange. */
private suspend fun ByteWriteChannel.writeCompleted(event: ReplyEvent.Completed) {
  val html = coachMessageHtml(ConvoContent.renderText(event.content))
  writeEvent(ChatEvents.COMPLETED, buildJsonObject { put("html", html) })
}

/** `:service`'s sentence for the failure; [ReplyEvent.Failed.reason] stays on the server. */
private suspend fun ByteWriteChannel.writeFailed(event: ReplyEvent.Failed) {
  writeEvent(
    ChatEvents.FAILED,
    buildJsonObject {
      put("retriable", event.retriable)
      put("message", event.studentSentence())
    },
  )
}

/**
 * One frame, flushed. kotlinx's compact JSON escapes every newline, so a
 * payload is always ONE `data:` line, whatever the reply contains.
 */
private suspend fun ByteWriteChannel.writeEvent(
  name: String,
  data: JsonObject,
) {
  writeStringUtf8("event: $name\ndata: $data\n\n")
  flush()
}
