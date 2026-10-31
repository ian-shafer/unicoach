package ed.unicoach.rest.routing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ed.unicoach.coaching.ConvoContent
import ed.unicoach.coaching.ReplyEvent
import ed.unicoach.coaching.VisibleExchange
import ed.unicoach.coaching.studentSentence
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.rest.models.Conversation
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.Message
import ed.unicoach.rest.models.MessageCompletedEvent
import ed.unicoach.rest.models.MessageDeltaEvent
import ed.unicoach.rest.models.StreamErrorEvent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// The conversation wire every coach door shares (RFC 258): the SSE writer and
// the projections from a turn to its JSON. The student's `ConvoRoutes` and the
// supporter's `SupporterConvoRoutes` both stream through these, so the two
// coaches speak one set of frames and one iOS decoder reads both.

// SSE mapper: like configureSerialization but INDENT_OUTPUT must be off (a
// multi-line data: payload breaks SSE framing).
private val sseMapper: ObjectMapper =
  ObjectMapper()
    .registerKotlinModule()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(SerializationFeature.INDENT_OUTPUT)

/**
 * Opens the SSE response, writes the opening event, relays deltas, then exactly
 * one terminal frame. Nothing runs after the `message` frame any more: the
 * student turn's extraction enqueue moved into `:service` (RFC 262), where it
 * runs before `Completed` is emitted, so no door can skip it.
 *
 * Every refusal must be answered before this is called: once the bytes writer
 * opens, the status is 200 and the only refusal left is an `error` frame.
 */
internal suspend fun RoutingContext.streamConversationReply(
  reply: Flow<ReplyEvent>,
  writeOpening: suspend (ByteWriteChannel) -> Unit,
) {
  call.response.header(HttpHeaders.CacheControl, "no-store")
  call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
    writeOpening(this)
    reply.collect { event ->
      when (event) {
        is ReplyEvent.Delta -> {
          writeSseEvent(this, "delta", MessageDeltaEvent(text = event.text))
        }

        is ReplyEvent.Completed -> {
          writeSseEvent(this, "message", MessageCompletedEvent(message = coachMessageOf(event)))
        }

        is ReplyEvent.Failed -> {
          writeSseEvent(this, "error", StreamErrorEvent(error = coachFailedError(event)))
        }
      }
    }
  }
}

/** One SSE frame: `event:` then a single-line `data:` JSON payload, flushed. */
internal suspend fun writeSseEvent(
  writer: ByteWriteChannel,
  event: String,
  payload: Any,
) {
  val data = sseMapper.writeValueAsString(payload)
  writer.writeStringUtf8("event: $event\ndata: $data\n\n")
  writer.flush()
}

/**
 * The retriable / permanent coach failure, as the client reads it. The sentence
 * is `:service`'s ([studentSentence], RFC 262), so the web chat says the same.
 */
internal fun coachFailedError(failed: ReplyEvent.Failed): ErrorResponse =
  ErrorResponse(if (failed.retriable) ErrorCode.COACH_UNAVAILABLE else ErrorCode.COACH_FAILED, failed.studentSentence())

internal fun conversationOf(
  convo: Convo,
  lastActivityAt: Instant?,
): Conversation =
  Conversation(
    id = convo.id.asString,
    name = convo.name.value,
    createdAt = convo.createdAt,
    updatedAt = convo.updatedAt,
    lastActivityAt = lastActivityAt,
    archivedAt = convo.archivedAt,
  )

/**
 * The user message for a just-opened turn. Since RFC 106 the user's text is not
 * stored on `convo_requests`; the route has it from the request body, so it is
 * passed in as [text] and the id/createdAt come from the opener [request] row.
 */
internal fun userMessageOf(
  request: ConvoRequest,
  text: String,
): Message =
  Message(
    id = "u_${request.id.value}",
    role = "user",
    content = text,
    createdAt = request.createdAt,
  )

/** The coach message for a completed reply terminal (RFC 106: content comes from the terminal, not a response row). */
internal fun coachMessageOf(terminal: ReplyEvent.Completed): Message =
  Message(
    id = "c_${terminal.convoRequest.id.value}",
    role = "coach",
    content = ConvoContent.renderText(terminal.content),
    createdAt = terminal.createdAt,
  )

internal fun messagesOf(exchanges: List<VisibleExchange>): List<Message> =
  buildList {
    for (exchange in exchanges) {
      add(userMessageOf(exchange.userRequest, ConvoContent.renderText(exchange.userContent)))
      add(
        Message(
          id = "c_${exchange.finalResponse.requestId.value}",
          role = "coach",
          content = ConvoContent.renderText(exchange.finalContent),
          createdAt = exchange.finalResponse.createdAt,
        ),
      )
    }
  }
