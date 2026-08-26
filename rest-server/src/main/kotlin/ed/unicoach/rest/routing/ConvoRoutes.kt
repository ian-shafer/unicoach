package ed.unicoach.rest.routing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.ConvoContent
import ed.unicoach.coaching.ConvoUpdate
import ed.unicoach.coaching.DeleteConvoResult
import ed.unicoach.coaching.GetConvoResult
import ed.unicoach.coaching.ListTurnsResult
import ed.unicoach.coaching.PostTurnResult
import ed.unicoach.coaching.ReplyEvent
import ed.unicoach.coaching.StartConvoResult
import ed.unicoach.coaching.UpdateConvoResult
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.budget.budgetSkipMessage
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.StudentId
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.models.Conversation
import ed.unicoach.rest.models.ConversationCreatedEvent
import ed.unicoach.rest.models.ConversationListResponse
import ed.unicoach.rest.models.ConversationResponse
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateConversationResponse
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.Message
import ed.unicoach.rest.models.MessageCompletedEvent
import ed.unicoach.rest.models.MessageDeltaEvent
import ed.unicoach.rest.models.MessageListResponse
import ed.unicoach.rest.models.PostMessageRequest
import ed.unicoach.rest.models.PostMessageResponse
import ed.unicoach.rest.models.StreamErrorEvent
import ed.unicoach.rest.models.UpdateConversationRequest
import ed.unicoach.rest.models.UserMessageEvent
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondValidationFailed
import ed.unicoach.student.StudentService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

class ConvoRouteHandler(
  authService: AuthService,
  studentService: StudentService,
  private val coachingService: CoachingService,
  sessionConfig: SessionConfig,
  private val queueService: ed.unicoach.queue.QueueService,
  private val extractionConfig: ed.unicoach.coaching.extraction.ExtractionConfig,
) : CallerResolution by SessionCallerResolution(authService, studentService, sessionConfig) {
  private val logger = org.slf4j.LoggerFactory.getLogger(ConvoRouteHandler::class.java)

  // SSE mapper: like configureSerialization but INDENT_OUTPUT must be off (a
  // multi-line data: payload breaks SSE framing).
  private val sseMapper: ObjectMapper =
    ObjectMapper()
      .registerKotlinModule()
      .registerModule(JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .disable(SerializationFeature.INDENT_OUTPUT)

  fun registerRoutes(route: Route) {
    route.route("/api/v1/conversations") {
      route("") {
        post { handleCreate() }
        get { handleList() }
        rejectUnsupportedMethods(HttpMethod.Post, HttpMethod.Get)
      }
      route("/stream") {
        post { handleStreamCreate() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/{conversationId}") {
        get { handleGet() }
        patch { handleUpdate() }
        delete { handleDelete() }
        rejectUnsupportedMethods(HttpMethod.Get, HttpMethod.Patch, HttpMethod.Delete)
      }
      route("/{conversationId}/messages") {
        get { handleListMessages() }
        post { handlePostMessage() }
        rejectUnsupportedMethods(HttpMethod.Get, HttpMethod.Post)
      }
      route("/{conversationId}/messages/stream") {
        post { handleStreamMessage() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
    }
  }

  private suspend fun RoutingContext.respondNotFound() {
    call.respond(HttpStatusCode.NotFound, ErrorResponse(ErrorCode.NOT_FOUND, "No such conversation"))
  }

  private fun RoutingContext.pathConvoId(): ConvoId? {
    val raw = call.parameters["conversationId"] ?: return null
    return runCatching { ConvoId(UUID.fromString(raw)) }.getOrNull()
  }

  // ---------------------------------------------------------------------------
  // Buffered handlers
  // ---------------------------------------------------------------------------

  private suspend fun RoutingContext.handleCreate() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val request = call.receive<CreateConversationRequest>()

    when (val outcome = coachingService.startConvo(student.id, request.message, request.name).getOrThrow()) {
      is StartConvoResult.ValidationFailure -> {
        call.respond(HttpStatusCode.BadRequest, validationError(outcome.fieldErrors))
      }

      is StartConvoResult.BudgetExhausted -> {
        respondBudgetExhausted(student.id, outcome.entitlement)
      }

      is StartConvoResult.Started -> {
        when (val terminal = drain(outcome.reply)) {
          is ReplyEvent.Completed -> {
            enqueueExtraction(outcome.convo.id, terminal.convoRequest.id)
            call.respond(
              HttpStatusCode.Created,
              CreateConversationResponse(
                conversation = conversationOf(outcome.convo, lastActivityAt = outcome.userTurn.createdAt),
                userMessage = userMessageOf(outcome.userTurn, request.message),
                coachMessage = coachMessageOf(terminal),
              ),
            )
          }

          is ReplyEvent.Failed -> {
            call.respond(HttpStatusCode.InternalServerError, failedError(terminal))
          }
        }
      }
    }
  }

  private suspend fun RoutingContext.handleList() {
    val user = resolveUser() ?: return respondUnauthorized()
    val archive = archiveScopeFromQuery() ?: return respondValidationFailed("status", "Unknown status value")
    val student = resolveStudent(user) ?: return call.respond(HttpStatusCode.OK, ConversationListResponse(emptyList()))

    val listings = coachingService.listConvos(student.id, archive).getOrThrow()
    call.respond(HttpStatusCode.OK, ConversationListResponse(listings.map(::conversationOf)))
  }

  private suspend fun RoutingContext.handleGet() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondNotFound()
    val convoId = pathConvoId() ?: return respondNotFound()

    when (val outcome = coachingService.getConvo(student.id, convoId).getOrThrow()) {
      is GetConvoResult.Found -> call.respond(HttpStatusCode.OK, ConversationResponse(conversationOf(outcome.listing)))
      GetConvoResult.NotFound -> respondNotFound()
    }
  }

  private suspend fun RoutingContext.handleUpdate() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondNotFound()
    val convoId = pathConvoId() ?: return respondNotFound()
    val request = call.receive<UpdateConversationRequest>()

    when (val outcome = coachingService.updateConvo(student.id, convoId, ConvoUpdate(request.name, request.archived)).getOrThrow()) {
      is UpdateConvoResult.Success -> call.respond(HttpStatusCode.OK, ConversationResponse(conversationOf(outcome.listing)))
      is UpdateConvoResult.ValidationFailure -> call.respond(HttpStatusCode.BadRequest, validationError(outcome.fieldErrors))
      UpdateConvoResult.NotFound -> respondNotFound()
    }
  }

  private suspend fun RoutingContext.handleDelete() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondNotFound()
    val convoId = pathConvoId() ?: return respondNotFound()

    when (coachingService.deleteConvo(student.id, convoId).getOrThrow()) {
      DeleteConvoResult.Success -> call.respond(HttpStatusCode.NoContent)
      DeleteConvoResult.NotFound -> respondNotFound()
    }
  }

  private suspend fun RoutingContext.handleListMessages() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondNotFound()
    val convoId = pathConvoId() ?: return respondNotFound()

    when (val outcome = coachingService.listTurns(student.id, convoId).getOrThrow()) {
      is ListTurnsResult.Found -> call.respond(HttpStatusCode.OK, MessageListResponse(messagesOf(outcome.exchanges)))
      ListTurnsResult.NotFound -> respondNotFound()
    }
  }

  private suspend fun RoutingContext.handlePostMessage() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondNotFound()
    val convoId = pathConvoId() ?: return respondNotFound()
    val request = call.receive<PostMessageRequest>()

    when (val outcome = coachingService.postTurn(student.id, convoId, request.message).getOrThrow()) {
      is PostTurnResult.ValidationFailure -> {
        call.respond(HttpStatusCode.BadRequest, validationError(outcome.fieldErrors))
      }

      PostTurnResult.NotFound -> {
        respondNotFound()
      }

      is PostTurnResult.BudgetExhausted -> {
        respondBudgetExhausted(student.id, outcome.entitlement)
      }

      is PostTurnResult.Started -> {
        when (val terminal = drain(outcome.reply)) {
          is ReplyEvent.Completed -> {
            enqueueExtraction(outcome.convo.id, terminal.convoRequest.id)
            call.respond(
              HttpStatusCode.Created,
              PostMessageResponse(
                userMessage = userMessageOf(outcome.userTurn, request.message),
                coachMessage = coachMessageOf(terminal),
              ),
            )
          }

          is ReplyEvent.Failed -> {
            call.respond(HttpStatusCode.InternalServerError, failedError(terminal))
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // SSE handlers
  // ---------------------------------------------------------------------------

  private suspend fun RoutingContext.handleStreamCreate() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondStudentProfileRequired()
    val request = call.receive<CreateConversationRequest>()

    when (val outcome = coachingService.startConvo(student.id, request.message, request.name).getOrThrow()) {
      is StartConvoResult.ValidationFailure -> {
        call.respond(HttpStatusCode.BadRequest, validationError(outcome.fieldErrors))
      }

      is StartConvoResult.BudgetExhausted -> {
        respondBudgetExhausted(student.id, outcome.entitlement)
      }

      is StartConvoResult.Started -> {
        streamReply(outcome.reply, outcome.convo.id) { writer ->
          writeSseEvent(
            writer,
            "conversation",
            ConversationCreatedEvent(
              conversation = conversationOf(outcome.convo, lastActivityAt = outcome.userTurn.createdAt),
              userMessage = userMessageOf(outcome.userTurn, request.message),
            ),
          )
        }
      }
    }
  }

  private suspend fun RoutingContext.handleStreamMessage() {
    val user = resolveUser() ?: return respondUnauthorized()
    val student = resolveStudent(user) ?: return respondNotFound()
    val convoId = pathConvoId() ?: return respondNotFound()
    val request = call.receive<PostMessageRequest>()

    when (val outcome = coachingService.postTurn(student.id, convoId, request.message).getOrThrow()) {
      is PostTurnResult.ValidationFailure -> {
        call.respond(HttpStatusCode.BadRequest, validationError(outcome.fieldErrors))
      }

      PostTurnResult.NotFound -> {
        respondNotFound()
      }

      is PostTurnResult.BudgetExhausted -> {
        respondBudgetExhausted(student.id, outcome.entitlement)
      }

      is PostTurnResult.Started -> {
        streamReply(outcome.reply, outcome.convo.id) { writer ->
          writeSseEvent(writer, "user_message", UserMessageEvent(userMessage = userMessageOf(outcome.userTurn, request.message)))
        }
      }
    }
  }

  /**
   * Opens the SSE response, writes the opening event, relays deltas, then exactly
   * one terminal frame. On a successful terminal ([ReplyEvent.Completed]) it
   * enqueues the extraction job after the frame is written — never on a failed
   * one (RFC 66) — with the final response's request id, so the watermark
   * advances over any tool excursion in one pass (RFC 94).
   */
  private suspend fun RoutingContext.streamReply(
    reply: Flow<ReplyEvent>,
    convoId: ConvoId,
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
            enqueueExtraction(convoId, event.convoRequest.id)
          }

          is ReplyEvent.Failed -> {
            writeSseEvent(this, "error", StreamErrorEvent(error = failedError(event)))
          }
        }
      }
    }
  }

  private suspend fun writeSseEvent(
    writer: ByteWriteChannel,
    event: String,
    payload: Any,
  ) {
    val data = sseMapper.writeValueAsString(payload)
    writer.writeStringUtf8("event: $event\ndata: $data\n\n")
    writer.flush()
  }

  // ---------------------------------------------------------------------------
  // Outcome → HTTP helpers
  // ---------------------------------------------------------------------------

  /**
   * The coaching allowance is spent (RFC 109). The SSE handlers reach this from
   * the turn pre-flight, before `respondBytesWriter` opens the stream, so both
   * the plain and the streaming endpoint answer with this same plain-JSON 402 —
   * never a mid-stream error frame. The body names no dollars, tokens, or
   * provider.
   *
   * The deciding [Entitlement] goes to the log instead, through the same
   * [budgetSkipMessage] the background passes use: a chat refusal writes no run
   * row either, so this line is the operator's only "why was this student
   * blocked" trace — and chat is the pass that produces the most of them.
   */
  private suspend fun RoutingContext.respondBudgetExhausted(
    studentId: StudentId,
    entitlement: Entitlement,
  ) {
    logger.info(budgetSkipMessage("chat", studentId, entitlement))
    call.respond(
      HttpStatusCode.PaymentRequired,
      ErrorResponse(ErrorCode.COACHING_BUDGET_EXHAUSTED, "Coaching allowance exhausted"),
    )
  }

  /** Single-field convenience over the shared [ed.unicoach.rest.respondValidationFailed]. */
  private suspend fun RoutingContext.respondValidationFailed(
    field: String,
    message: String,
  ) {
    respondValidationFailed(listOf(ed.unicoach.error.FieldError(field, message)))
  }

  private fun validationError(fieldErrors: List<ed.unicoach.error.FieldError>): ErrorResponse =
    ErrorResponse(ErrorCode.VALIDATION_FAILED, "Validation failed", fieldErrors)

  private fun failedError(failed: ReplyEvent.Failed): ErrorResponse =
    if (failed.retriable) {
      ErrorResponse(ErrorCode.COACH_UNAVAILABLE, "The coach is temporarily unavailable — try again.")
    } else {
      ErrorResponse(ErrorCode.COACH_FAILED, "The coach could not respond to this message.")
    }

  private fun RoutingContext.archiveScopeFromQuery(): ArchiveScope? =
    when (call.request.queryParameters["status"]) {
      null, "active" -> ArchiveScope.UNARCHIVED
      "archived" -> ArchiveScope.ARCHIVED
      else -> null
    }

  /** Drains the cold reply flow to its single terminal (buffered endpoints). */
  private suspend fun drain(reply: Flow<ReplyEvent>): ReplyEvent.Terminal {
    var terminal: ReplyEvent.Terminal? = null
    reply.collect { event -> if (event is ReplyEvent.Terminal) terminal = event }
    return terminal ?: ReplyEvent.Failed(retriable = true, reason = "no terminal")
  }

  /**
   * Enqueues an [ed.unicoach.queue.JobType.EXTRACT_CONVERSATION] job for the
   * conversation, debounced by `extraction.debounce` (RFC 66). Called only on a
   * successful terminal turn, never on a failed one; skipped entirely when
   * `extraction.enabled = false`. The enqueue is fire-and-forget — a queue
   * failure must not alter the turn's HTTP/SSE response, so it is logged and
   * swallowed.
   */
  private suspend fun enqueueExtraction(
    convoId: ConvoId,
    throughRequestId: ConvoRequestId,
  ) {
    if (!extractionConfig.enabled) return
    val payload =
      ed.unicoach.queue
        .ExtractionPayload(convoId = convoId.asString, throughRequestId = throughRequestId.value)
    try {
      val result =
        queueService.enqueue(
          jobType = ed.unicoach.queue.JobType.EXTRACT_CONVERSATION,
          payload = mapPayload(payload),
          delay = extractionConfig.debounce,
        )
      if (result is ed.unicoach.queue.EnqueueResult.DatabaseFailure) {
        logger.warn("extraction enqueue failed for convo=[{}]", convoId.asString, result.error)
      }
    } catch (e: Exception) {
      logger.warn("extraction enqueue threw for convo=[{}]", convoId.asString, e)
    }
  }

  /** Serializes an [ed.unicoach.queue.ExtractionPayload] into the kotlinx JsonObject the queue API takes. */
  private fun mapPayload(payload: ed.unicoach.queue.ExtractionPayload): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
      put("convoId", payload.convoId)
      put("throughRequestId", payload.throughRequestId)
    }

  // ---------------------------------------------------------------------------
  // Projections
  // ---------------------------------------------------------------------------

  private fun conversationOf(listing: ConvoWithActivity): Conversation = conversationOf(listing.convo, listing.lastActivityAt)

  private fun conversationOf(
    convo: ed.unicoach.db.models.Convo,
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
  private fun userMessageOf(
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
  private fun coachMessageOf(terminal: ReplyEvent.Completed): Message =
    Message(
      id = "c_${terminal.convoRequest.id.value}",
      role = "coach",
      content = ConvoContent.renderText(terminal.content),
      createdAt = terminal.createdAt,
    )

  private fun messagesOf(exchanges: List<ed.unicoach.coaching.VisibleExchange>): List<Message> =
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
}
