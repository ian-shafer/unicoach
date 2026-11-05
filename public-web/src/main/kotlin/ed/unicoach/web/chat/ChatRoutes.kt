package ed.unicoach.web.chat

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.ConvoContent
import ed.unicoach.coaching.ListTurnsResult
import ed.unicoach.coaching.PostTurnResult
import ed.unicoach.coaching.StartConvoResult
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.budget.budgetSkipMessage
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.Student
import ed.unicoach.error.FieldError
import ed.unicoach.student.AccountRole
import ed.unicoach.student.AccountRoleService
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.auth.CHAT_PATH
import ed.unicoach.web.auth.STUDENT_SIGNUP_PATH
import ed.unicoach.web.auth.accountRole
import ed.unicoach.web.auth.getOnboarded
import ed.unicoach.web.auth.homePath
import ed.unicoach.web.auth.postOnboarded
import ed.unicoach.web.auth.resolveWebCaller
import ed.unicoach.web.auth.respondGateRedirect
import ed.unicoach.web.common.http.canonicalUuidOrNull
import ed.unicoach.web.formParametersOrNull
import ed.unicoach.web.render.CHAT_MESSAGE_FIELD
import ed.unicoach.web.render.CHAT_NOTICE_HEADER
import ed.unicoach.web.render.CONVERSATION_ID_PARAM
import ed.unicoach.web.render.ChatExchange
import ed.unicoach.web.render.ChatNotice
import ed.unicoach.web.render.MalformedSend
import ed.unicoach.web.render.OwnNotFoundBody
import ed.unicoach.web.render.ShellNav
import ed.unicoach.web.render.chatNoticeHtml
import ed.unicoach.web.render.conversationPath
import ed.unicoach.web.render.respondChatPage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.signedInNav
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

/** The chat routes' own log: who was refused coaching and why, which the notice never says. */
private val chatLog = LoggerFactory.getLogger("ed.unicoach.web.Chat")

/**
 * The web chat (RFC 262 §4): the coach, in-process (brief 0013 D13).
 *
 * | Method + path     | Does                                                    |
 * | ----------------- | ------------------------------------------------------- |
 * | `GET /chat`       | a NEW conversation: the empty state and the composer    |
 * | `GET /chat/{id}`  | that conversation's visible exchanges and the composer  |
 * | `POST /chat`      | `startConvo`, answered as the stream ([respondChatStream]) |
 * | `POST /chat/{id}` | `postTurn`, answered as the stream                      |
 *
 * Every route is registered ONBOARDED through [getOnboarded] / [postOnboarded]
 * (RFC 268), which run after Ktor has resolved the route — the authority, as on
 * every gated page (RFC 224) — and the whole tree carries the [ChatStudentGate].
 * The same-origin guard already refuses a cross-site POST before any of this
 * runs.
 *
 * A send's refusals are known BEFORE the stream opens and persist nothing, so
 * each is answered as a [ChatNotice] at a real status — never as an event: a
 * spent allowance is a notice where the reply would have been, not a stream
 * that breaks mid-sentence (§5, §8).
 *
 * `/get-started`, where [ChatStudentGate] sends a caller with no role, is not a
 * chat page: it is onboarding's (RFC 268), registered by `getStartedRoutes`.
 */
internal fun Route.chatRoutes(
  coachingService: CoachingService,
  accountRoles: AccountRoleService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  route(CHAT_PATH) {
    install(ChatStudentGate) {
      this.authService = authService
      this.sessionConfig = sessionConfig
      this.accountRoles = accountRoles
    }

    getOnboarded("", authService, sessionConfig, accountRoles) { caller ->
      call.respondChatPage(action = CHAT_PATH, exchanges = emptyList(), nav = caller.signedInNav)
    }

    postOnboarded("", authService, sessionConfig, accountRoles) {
      startConversation(coachingService, call.chatStudent())
    }

    getOnboarded("/{$CONVERSATION_ID_PARAM}", authService, sessionConfig, accountRoles) { caller ->
      showConversation(coachingService, call.chatStudent(), caller.signedInNav)
    }

    postOnboarded("/{$CONVERSATION_ID_PARAM}", authService, sessionConfig, accountRoles) {
      continueConversation(coachingService, call.chatStudent())
    }
  }
}

/** `GET /chat/{id}`: the visible exchanges, or the branded 404 — which never says whose a missing one was. */
private suspend fun RoutingContext.showConversation(
  coachingService: CoachingService,
  student: Student,
  nav: ShellNav.SignedIn,
) {
  val convoId = call.pathConvoId() ?: return call.respondNotFoundPage(nav)
  when (val outcome = coachingService.listTurns(student.id, convoId).getOrThrow()) {
    is ListTurnsResult.Found -> {
      call.respondChatPage(
        action = conversationPath(convoId),
        exchanges =
          outcome.exchanges.map { exchange ->
            ChatExchange(ConvoContent.renderText(exchange.userContent), ConvoContent.renderText(exchange.finalContent))
          },
        nav = nav,
      )
    }

    ListTurnsResult.NotFound -> {
      call.respondNotFoundPage(nav)
    }
  }
}

/** `POST /chat`: a new conversation's first turn. */
private suspend fun RoutingContext.startConversation(
  coachingService: CoachingService,
  student: Student,
) {
  val message =
    when (val submission = submittedMessage()) {
      is Submission.Message -> submission.text
      is Submission.Malformed -> return call.respondMalformed(submission.cause)
    }
  when (val outcome = coachingService.startConvo(student.id, message, name = null).getOrThrow()) {
    is StartConvoResult.ValidationFailure -> call.respondNotice(refused(outcome.fieldErrors))
    is StartConvoResult.BudgetExhausted -> call.respondOutOfAllowance(student, outcome.entitlement)
    is StartConvoResult.Started -> call.respondChatStream(outcome.reply, conversationPath(outcome.convo.id))
  }
}

/** `POST /chat/{id}`: the next turn of a conversation the student owns. */
private suspend fun RoutingContext.continueConversation(
  coachingService: CoachingService,
  student: Student,
) {
  val convoId = call.pathConvoId() ?: return call.respondNotice(ChatNotice.Unavailable)
  val message =
    when (val submission = submittedMessage()) {
      is Submission.Message -> submission.text
      is Submission.Malformed -> return call.respondMalformed(submission.cause)
    }
  when (val outcome = coachingService.postTurn(student.id, convoId, message).getOrThrow()) {
    is PostTurnResult.ValidationFailure -> call.respondNotice(refused(outcome.fieldErrors))
    PostTurnResult.NotFound -> call.respondNotice(ChatNotice.Unavailable)
    is PostTurnResult.BudgetExhausted -> call.respondOutOfAllowance(student, outcome.entitlement)
    is PostTurnResult.Started -> call.respondChatStream(outcome.reply, conversationPath(outcome.convo.id))
  }
}

/** A send's body: the composer's text, or the reason it was not a submission of the composer. */
private sealed interface Submission {
  data class Message(
    val text: String,
  ) : Submission

  data class Malformed(
    val cause: MalformedSend,
  ) : Submission
}

/**
 * The composer's text, or why the body is not a submission of the form this
 * page renders — which posts exactly one `message` field. A BLANK message is a
 * real submission and goes to `:service`, whose own rule refuses it.
 */
private suspend fun RoutingContext.submittedMessage(): Submission {
  val form = call.formParametersOrNull() ?: return Submission.Malformed(MalformedSend.UNREADABLE_BODY)
  val values = form.getAll(CHAT_MESSAGE_FIELD).orEmpty()
  return when (values.size) {
    0 -> Submission.Malformed(MalformedSend.MISSING_FIELD)
    1 -> Submission.Message(values.single())
    else -> Submission.Malformed(MalformedSend.REPEATED_FIELD)
  }
}

/** The path's conversation id, iff it is a canonical UUID: the chat's and Your conversations' (RFC 272) one reading of it. */
internal fun ApplicationCall.pathConvoId(): ConvoId? = parameters[CONVERSATION_ID_PARAM]?.let(::canonicalUuidOrNull)?.let(::ConvoId)

/** `:service`'s own field errors, carried whole to the notice; the web adds no rule of its own (D13). */
private fun refused(fieldErrors: List<FieldError>): ChatNotice.Refused = ChatNotice.Refused(fieldErrors)

/**
 * A send that was not a submission of the composer: logged with WHICH shape it
 * was — the notice says one sentence for every [MalformedSend] — and answered
 * with the malformed notice.
 */
private suspend fun ApplicationCall.respondMalformed(cause: MalformedSend) {
  chatLog.info("chat send on [{}] refused as malformed: [{}]", request.path(), cause)
  respondNotice(ChatNotice.Malformed(cause))
}

/**
 * The spent allowance (RFC 109), logged with the deciding entitlement through
 * the same [budgetSkipMessage] the REST door writes — this line is the
 * operator's only "why was this student blocked" trace — and answered with the
 * out-of-allowance notice, which names no figure.
 */
private suspend fun ApplicationCall.respondOutOfAllowance(
  student: Student,
  entitlement: Entitlement,
) {
  chatLog.info(budgetSkipMessage("chat", student.id, entitlement))
  respondNotice(ChatNotice.OutOfAllowance(entitlement))
}

/**
 * A notice fragment at its own status, never cached: it is about this send. Its
 * 404 is its own body, so the branded 404 page is told to leave it be, and
 * [CHAT_NOTICE_HEADER] marks it as ours — the one thing that lets `chat.ts` put
 * it in the transcript.
 */
private suspend fun ApplicationCall.respondNotice(notice: ChatNotice) {
  attributes.put(OwnNotFoundBody, Unit)
  response.header(HttpHeaders.CacheControl, "no-store")
  response.header(CHAT_NOTICE_HEADER, "1")
  respondText(chatNoticeHtml(notice), ContentType.Text.Html.withCharset(Charsets.UTF_8), notice.status)
}

/** The caller's student, resolved once by [ChatStudentGate] for this request. */
private val ChatStudentKey = AttributeKey<Student>("ed.unicoach.web.chat.Student")

/**
 * The student [ChatStudentGate] resolved. A handler on the chat tree only runs
 * for a verified caller the gate let through, and the gate lets one through
 * only by storing their student — so a missing attribute is a wiring defect,
 * and fails as one.
 */
private fun ApplicationCall.chatStudent(): Student =
  attributes.getOrNull(ChatStudentKey)
    ?: error("no student on [${request.local.uri}]: the chat route ran without ChatStudentGate")

/** [ChatStudentGate]'s collaborators. */
private class ChatStudentGateConfig {
  lateinit var authService: AuthService
  lateinit var sessionConfig: WebSessionConfig
  lateinit var accountRoles: AccountRoleService
}

/**
 * The chat needs a `students` row, and a verified user without one is sent
 * where they can get one (RFC 262 D-B, Ian at approval: "catch this in an
 * interceptor").
 *
 * ROUTE-SCOPED, on the `/chat` tree, so it runs after Ktor has resolved the
 * route: no spelling of the path (`//chat`, `/%63hat`) reaches a chat handler
 * around it — the RFC 224 lesson that a pre-routing path match is an
 * optimisation and never the authority.
 *
 * It decides only for a VERIFIED caller. A signed-out or unverified one passes
 * through untouched, and the route's own [getOnboarded] answers them exactly as
 * every other gated page does — this gate never invents a second answer to
 * "who may see this".
 *
 * It reads the caller's [AccountRole] (RFC 268), the read the onboarding wall
 * and `/get-started` make, so the three cannot disagree:
 *
 * - a student → stored for the handler, which reads it typed;
 * - a supporter → `/account`, where the parent note already says the parents'
 *   coach is in the iPhone app;
 * - neither → [STUDENT_SIGNUP_PATH], onboarding.
 *
 * An account holding both rows is a student, as on iOS. A redirect is `302`
 * for a `GET` and `303` for a `POST` ([respondGateRedirect]); answering here
 * marks the call handled, and Ktor runs no route handler for a handled call.
 */
private val ChatStudentGate =
  createRouteScopedPlugin("ChatStudentGate", ::ChatStudentGateConfig) {
    val authService = pluginConfig.authService
    val sessionConfig = pluginConfig.sessionConfig
    val accountRoles = pluginConfig.accountRoles

    onCall { call ->
      val caller = call.resolveWebCaller(authService, sessionConfig) ?: return@onCall
      if (caller.user.emailVerifiedAt == null) return@onCall

      val role = caller.accountRole(accountRoles)
      if (role is AccountRole.Student) call.attributes.put(ChatStudentKey, role.student) else call.respondGateRedirect(role.homePath())
    }
  }
