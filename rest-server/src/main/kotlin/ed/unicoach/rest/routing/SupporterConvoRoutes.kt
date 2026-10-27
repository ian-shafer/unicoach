package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.budget.budgetSkipMessage
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import ed.unicoach.error.FieldError
import ed.unicoach.family.ConvoRefusal
import ed.unicoach.family.StudentRefusal
import ed.unicoach.family.SupporterCoachingService
import ed.unicoach.family.SupporterConvoList
import ed.unicoach.family.SupporterTurnStart
import ed.unicoach.family.SupporterTurns
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.canonicalUuidOrNull
import ed.unicoach.rest.models.ConversationCreatedEvent
import ed.unicoach.rest.models.CreateSupporterConversationRequest
import ed.unicoach.rest.models.MessageListResponse
import ed.unicoach.rest.models.PostMessageRequest
import ed.unicoach.rest.models.SupporterConversation
import ed.unicoach.rest.models.SupporterConversationListResponse
import ed.unicoach.rest.models.UserMessageEvent
import ed.unicoach.rest.pathId
import ed.unicoach.rest.rejectUnsupportedMethods
import ed.unicoach.rest.respondCoachingBudgetExhausted
import ed.unicoach.rest.respondConvoNotFound
import ed.unicoach.rest.respondNoSuchStudent
import ed.unicoach.rest.respondSupporterNotFound
import ed.unicoach.rest.respondValidationFailed
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** A supporter's own coach conversations (RFC 258), under the supporter home. */
const val SUPPORTER_CONVERSATIONS_PATH = "$SUPPORTER_ME_PATH/conversations"

/** The path parameter naming one of the supporter's conversations. */
const val SUPPORTER_CONVERSATION_ID_PARAM = "conversationId"

/**
 * The supporter's coach (RFC 258, product slice family/06): list their own
 * conversations, start one about a linked student, read one, and continue one.
 *
 * Delegates [CallerAuthentication], not [CallerResolution]: a parent has no
 * student profile, so `student_profile_required` is not this door's to send —
 * [SupporterRoutes]' reasoning.
 *
 * The frames, the headers and the pre-stream refusals are the student
 * conversation door's ([ConvoRouteHandler]); the SSE writer is the one they
 * share ([streamConversationReply]). Every refusal is answered before a single
 * SSE byte. Nothing here enqueues extraction: a parent's words never become the
 * student's claims.
 *
 * The subject is a body field on the one route that creates; a turn takes its
 * subject from the conversation row. No route binds `{studentId}`, so the
 * subject seam's route sweep has no student route here to check.
 */
class SupporterConvoRoutes(
  authService: AuthService,
  sessionConfig: SessionConfig,
  private val supporterCoachingService: SupporterCoachingService,
) : CallerAuthentication by SessionCallerAuthentication(authService, sessionConfig) {
  private val logger = org.slf4j.LoggerFactory.getLogger(SupporterConvoRoutes::class.java)

  fun registerRoutes(route: Route) {
    route.route(SUPPORTER_CONVERSATIONS_PATH) {
      get { handleList() }
      rejectUnsupportedMethods(HttpMethod.Get)
      route("/stream") {
        post { handleStreamCreate() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/{$SUPPORTER_CONVERSATION_ID_PARAM}/messages") {
        get { handleListMessages() }
        rejectUnsupportedMethods(HttpMethod.Get)
        route("/stream") {
          post { handleStreamMessage() }
          rejectUnsupportedMethods(HttpMethod.Post)
        }
      }
    }
  }

  private suspend fun RoutingContext.handleList() {
    val user = resolveUser() ?: return respondUnauthorized()

    when (val outcome = supporterCoachingService.listConvos(user.id).getOrThrow()) {
      is SupporterConvoList.Found -> {
        call.respond(
          HttpStatusCode.OK,
          SupporterConversationListResponse(outcome.conversations.map(::supporterConversationOf)),
        )
      }

      SupporterConvoList.NoSupporter -> {
        respondSupporterNotFound()
      }
    }
  }

  private suspend fun RoutingContext.handleListMessages() {
    val user = resolveUser() ?: return respondUnauthorized()
    val convoId = pathConvoId() ?: return respondNotAConversationId()

    when (val outcome = supporterCoachingService.listTurns(user.id, convoId).getOrThrow()) {
      is SupporterTurns.Found -> call.respond(HttpStatusCode.OK, MessageListResponse(messagesOf(outcome.exchanges)))
      SupporterTurns.NoSupporter -> respondSupporterNotFound()
      is SupporterTurns.ConvoNotFound -> respondRefusedConvo(user.id, convoId, outcome.reason)
    }
  }

  private suspend fun RoutingContext.handleStreamCreate() {
    val user = resolveUser() ?: return respondUnauthorized()
    val request = call.receive<CreateSupporterConversationRequest>()
    val studentId = canonicalUuidOrNull(request.studentId)?.let(::StudentId) ?: return respondNotAStudentId(request.studentId)

    val outcome = supporterCoachingService.startConvo(user.id, studentId, request.message).getOrThrow()
    respondTurn(user.id, outcome, convoId = null) { started, writer ->
      writeSseEvent(
        writer,
        "conversation",
        ConversationCreatedEvent(
          conversation = conversationOf(started.convo, lastActivityAt = started.userTurn.createdAt),
          userMessage = userMessageOf(started.userTurn, request.message),
        ),
      )
    }
  }

  private suspend fun RoutingContext.handleStreamMessage() {
    val user = resolveUser() ?: return respondUnauthorized()
    val convoId = pathConvoId() ?: return respondNotAConversationId()
    val request = call.receive<PostMessageRequest>()

    val outcome = supporterCoachingService.postTurn(user.id, convoId, request.message).getOrThrow()
    respondTurn(user.id, outcome, convoId) { started, writer ->
      writeSseEvent(writer, "user_message", UserMessageEvent(userMessage = userMessageOf(started.userTurn, request.message)))
    }
  }

  /**
   * Every refusal as plain JSON before the stream; a started turn streams
   * through the shared writer. [convoId] is the conversation a continuation
   * named (null on a start), for the refusal log.
   */
  private suspend fun RoutingContext.respondTurn(
    userId: UserId,
    outcome: SupporterTurnStart,
    convoId: ConvoId?,
    writeOpening: suspend (SupporterTurnStart.Started, io.ktor.utils.io.ByteWriteChannel) -> Unit,
  ) {
    when (outcome) {
      is SupporterTurnStart.Invalid -> respondValidationFailed(outcome.fieldErrors)
      SupporterTurnStart.NoSupporter -> respondSupporterNotFound()
      is SupporterTurnStart.StudentNotFound -> respondUnreachableStudent(userId, convoId, outcome.reason)
      is SupporterTurnStart.ConvoNotFound -> respondRefusedConvo(userId, convoId, outcome.reason)
      is SupporterTurnStart.BudgetExhausted -> respondBudgetExhausted(userId, outcome.entitlement)
      is SupporterTurnStart.Started -> streamConversationReply(outcome.reply, { writeOpening(outcome, it) }, onCompleted = {})
    }
  }

  private fun supporterConversationOf(listing: ConvoWithActivity): SupporterConversation =
    SupporterConversation(
      id = listing.convo.id.asString,
      studentId = listing.convo.studentId.asString,
      name = listing.convo.name.value,
      createdAt = listing.convo.createdAt,
      updatedAt = listing.convo.updatedAt,
    )

  private fun RoutingContext.pathConvoId(): ConvoId? = pathId(SUPPORTER_CONVERSATION_ID_PARAM, ::ConvoId)

  /** `400` — a malformed id names no conversation, so saying so reveals nothing. */
  private suspend fun RoutingContext.respondNotAConversationId() {
    respondValidationFailed(
      listOf(
        FieldError(SUPPORTER_CONVERSATION_ID_PARAM, "Not a conversation id: [${call.pathParameters[SUPPORTER_CONVERSATION_ID_PARAM]}]"),
      ),
    )
  }

  /** `400` — a malformed id names no student. */
  private suspend fun RoutingContext.respondNotAStudentId(raw: String) {
    respondValidationFailed(listOf(FieldError("studentId", "Not a student id: [$raw]")))
  }

  /**
   * `404 convo_not_found` — one body for every [reason], so a caller cannot tell
   * a stale id from somebody else's conversation. The reason goes to the log.
   */
  private suspend fun RoutingContext.respondRefusedConvo(
    userId: UserId,
    convoId: ConvoId?,
    reason: ConvoRefusal,
  ) {
    logger.info("supporter conversation refused user=[{}] convo=[{}] reason=[{}]", userId.asString, convoId?.asString, reason)
    respondConvoNotFound()
  }

  /**
   * `404 student_not_found` — one body for every [reason], so a caller cannot
   * confirm a student exists. The reason goes to the log.
   */
  private suspend fun RoutingContext.respondUnreachableStudent(
    userId: UserId,
    convoId: ConvoId?,
    reason: StudentRefusal,
  ) {
    logger.info("supporter turn refused user=[{}] convo=[{}] reason=[{}]", userId.asString, convoId?.asString, reason)
    respondNoSuchStudent()
  }

  /**
   * The parent's OWN allowance is spent: the student's refusal shape, `402
   * coaching_budget_exhausted`, with no dollars, tokens or provider in the body.
   * The deciding entitlement goes to the log, keyed by the account.
   */
  private suspend fun RoutingContext.respondBudgetExhausted(
    userId: UserId,
    entitlement: Entitlement,
  ) {
    logger.info(budgetSkipMessage("supporter chat", userId, entitlement))
    respondCoachingBudgetExhausted()
  }
}
