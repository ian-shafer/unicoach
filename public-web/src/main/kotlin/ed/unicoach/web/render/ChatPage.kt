package ed.unicoach.web.render

import ed.unicoach.coaching.COACH_ZONE
import ed.unicoach.coaching.budget.Entitlement
import ed.unicoach.coaching.budget.EntitlementBasis
import ed.unicoach.coaching.studentSentence
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.ConvoId
import ed.unicoach.error.FieldError
import ed.unicoach.web.auth.CHAT_PATH
import ed.unicoach.web.auth.CsrfToken
import ed.unicoach.web.markdown.markdown
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.noScript
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.stream.createHTML
import kotlinx.html.textArea

/** The chat page's title, and the header link's label: one name for the one page. */
const val CHAT_TITLE = "Chat"

/** The empty state of a new conversation — the iOS app's own prompt. */
const val CHAT_EMPTY_PROMPT = "What's on your mind about college?"

/** The composer's label. */
const val COMPOSER_LABEL = "Message the coach"

/** The composer's submit button. */
const val SEND_LABEL = "Send"

/** What a student with JavaScript off is told: sending needs it (RFC 262 D-D). */
const val NOSCRIPT_LINE = "Sending a message needs JavaScript turned on."

/** The form field the composer posts its text in. */
const val CHAT_MESSAGE_FIELD = "message"

/** The path segment that names one conversation: `/chat/{conversationId}`, and its Your conversations routes (RFC 272). */
const val CONVERSATION_ID_PARAM = "conversationId"

/**
 * A conversation's own page — the `conversationUrl` the stream names, the
 * composer's `action` on it, and where Your conversations opens it (RFC 272).
 */
fun conversationPath(convoId: ConvoId): String = "$CHAT_PATH/${convoId.asString}"

// The hooks below are restated in `chat.ts`, which mounts on them: no source is
// shared across the two languages. Both halves are held to ONE fixture,
// `src/test/resources/chat-client-contract.json` — `ChatClientContractTest`
// asserts the served page against it and `chat_test.ts` the client's constants —
// so a rename on either side alone fails a test rather than leaving a page whose
// composer never mounts.

/** The composer form's class: the hook `chat.ts` mounts on and the stylesheet styles. */
const val COMPOSER_CLASS = "chat-composer"

/** The scrolling column of exchanges, which the client appends to. */
const val TRANSCRIPT_CLASS = "chat-transcript"

/** A new conversation's empty-state line, which the client removes on the first send. */
const val CHAT_EMPTY_CLASS = "chat-empty"

/** One exchange — a student message and the reply to it — as the page renders it and the client builds it. */
const val EXCHANGE_CLASS = "exchange"

/** The id of the last exchange: page load lands there. */
const val LATEST_ANCHOR = "latest"

/**
 * The classes a coach message carries. ONE constant for the page's transcript
 * and the stream's `completed` fragment ([coachMessageHtml]), so the two render
 * the same element and the client's swap is invisible.
 */
const val COACH_MESSAGE_CLASSES = "message coach"

/** The classes a student's own message carries. */
const val STUDENT_MESSAGE_CLASSES = "message student"

/**
 * The attribute on the composer that carries the retriable failure sentence.
 *
 * The client needs that sentence for the one failure no server event reports —
 * a stream that ends without a terminal, or a fetch that never got a response —
 * and it is read here, from `:service`'s [studentSentence], rather than typed a
 * second time in TypeScript (RFC 262 §2c: one home for the failure words).
 */
const val RETRIABLE_FAILURE_ATTRIBUTE = "data-retriable-failure"

/** A notice the server answers a refused send with, in place of the reply (RFC 262 §5). */
const val CHAT_NOTICE_CLASS = "chat-notice"

/**
 * The response header that marks a body as one of OUR notices. `chat.ts` puts a
 * non-stream body in the transcript only when it carries this header; anything
 * else — the branded 503 or 404 page, a proxy's error page — is the retriable
 * failure instead (§7), never pasted in. Its presence is the mark; the value
 * carries nothing.
 */
const val CHAT_NOTICE_HEADER = "X-Chat-Notice"

/** The heading of the out-of-allowance notice — the iOS paywall's own title. */
const val OUT_OF_ALLOWANCE_TITLE = "Coaching is paused"

/**
 * What the web says about continuing once the allowance is spent (brief 0013
 * D18). Interim copy until `web/10` owns it.
 */
const val OUT_OF_ALLOWANCE_NEXT_STEP =
  "To keep going, subscribe in the Unicoach app on iPhone. Subscribing on the web isn't available yet. " +
    "Everything else here still works."

/**
 * What a send that was not a submission of the composer is told: the page's own
 * composer never sends one, so only a hand-built request reads it.
 */
const val MESSAGE_NOT_SENT = "That message couldn't be sent."

/** A conversation that is missing, deleted or not the caller's. */
const val CONVERSATION_UNAVAILABLE = "This conversation isn't available."

/** The link a refused conversation offers instead — the iOS app's own label for the same action. */
const val NEW_CONVERSATION_LABEL = "New conversation"

/** One visible exchange, as the page renders it: the student's words and the coach's Markdown. */
data class ChatExchange(
  val studentText: String,
  val coachMarkdown: String,
)

/**
 * The chat (RFC 262): a conversation's visible exchanges and the composer.
 *
 * Server-rendered, so a conversation READS without JavaScript; SENDING needs
 * it, and the `<noscript>` line says so (D-D). The composer is a real
 * `<form method="post">` whose `action` names the conversation it continues —
 * [action] is `/chat` for a new one — and `chat.ts` is what submits it.
 *
 * Rendered at [ShellWidth.CHAT]: the composer is pinned to the bottom of a
 * full-height column and the transcript scrolls above it (D9).
 */
suspend fun ApplicationCall.respondChatPage(
  action: String,
  exchanges: List<ChatExchange>,
  nav: ShellNav.SignedIn,
) {
  respondHtml {
    siteLayout(CHAT_TITLE, width = ShellWidth.CHAT, nav = nav) {
      section("chat") {
        h1("visually-hidden") { +CHAT_TITLE }
        div(TRANSCRIPT_CLASS) {
          if (exchanges.isEmpty()) p(CHAT_EMPTY_CLASS) { +CHAT_EMPTY_PROMPT }
          exchanges.forEachIndexed { index, exchange ->
            div(EXCHANGE_CLASS) {
              if (index == exchanges.lastIndex) id = LATEST_ANCHOR
              studentMessage(exchange.studentText)
              coachMessage(exchange.coachMarkdown)
            }
          }
        }
        composer(action, nav.csrf)
      }
    }
  }
}

/** The student's words exactly as typed: text, never Markdown. */
private fun FlowContent.studentMessage(text: String) {
  div(STUDENT_MESSAGE_CLASSES) { +text }
}

/** A coach message: the reply's Markdown through the ONE renderer and its sanitisation policy. */
fun FlowContent.coachMessage(markdownText: String) {
  div(COACH_MESSAGE_CLASSES) { markdown(markdownText) }
}

/**
 * The coach message as an HTML fragment — what the stream's `completed` event
 * carries. The same element [coachMessage] renders into the page, so the
 * client swaps the streamed plain text for exactly what a reload would show.
 */
fun coachMessageHtml(markdownText: String): String = createHTML(prettyPrint = false).div(COACH_MESSAGE_CLASSES) { markdown(markdownText) }

/**
 * The composer. Its [csrf] hidden field is what `chat.ts` reads and sends as
 * the `X-CSRF-Token` header (RFC 265); the message body it posts is built from
 * the text area alone.
 */
private fun FlowContent.composer(
  action: String,
  csrf: CsrfToken,
) {
  signedInForm(
    action = action,
    csrf = csrf,
    classes = COMPOSER_CLASS,
    attributes = mapOf(RETRIABLE_FAILURE_ATTRIBUTE to studentSentence(retriable = true)),
  ) {
    label {
      htmlFor = COMPOSER_TEXTAREA_ID
      +COMPOSER_LABEL
    }
    textArea(rows = "3") {
      id = COMPOSER_TEXTAREA_ID
      name = CHAT_MESSAGE_FIELD
    }
    button(type = ButtonType.submit) { +SEND_LABEL }
    noScript { p { +NOSCRIPT_LINE } }
  }
}

private const val COMPOSER_TEXTAREA_ID = "chat-message"

/**
 * A send refused BEFORE the stream opened (RFC 262 §5), rendered as a fragment
 * the client puts where the reply would have gone. Every variant is our own
 * template: nothing in a notice comes from request text.
 */
sealed interface ChatNotice {
  /** The status the notice is answered at: the refusal it reports, readable by the client. */
  val status: HttpStatusCode

  /** `:service` refused the message; [fieldErrors] are its own, each read out as its sentence. */
  data class Refused(
    val fieldErrors: List<FieldError>,
  ) : ChatNotice {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /**
   * `:service` was never asked: the body was not a submission of the form this
   * page rendered, so there is no rule to name — only that the message did not
   * go ([MESSAGE_NOT_SENT]). [cause] says which shape it was, for the log; the
   * student reads one sentence for all of them.
   */
  data class Malformed(
    val cause: MalformedSend,
  ) : ChatNotice {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /** The allowance is spent (§8): the basis sentence and what to do next, never a figure. */
  data class OutOfAllowance(
    val entitlement: Entitlement,
  ) : ChatNotice {
    override val status: HttpStatusCode get() = HttpStatusCode.PaymentRequired
  }

  /** The conversation is missing, deleted or another student's — one answer for all three. */
  data object Unavailable : ChatNotice {
    override val status: HttpStatusCode get() = HttpStatusCode.NotFound
  }
}

/** Why a send was not a submission of the composer, which posts exactly one `message` field. */
enum class MalformedSend {
  /** The body was not form-encoded at all. */
  UNREADABLE_BODY,

  /** The form carried no `message` field. */
  MISSING_FIELD,

  /** The form carried `message` more than once. */
  REPEATED_FIELD,
}

/** [notice] as the HTML fragment the client inserts. */
fun chatNoticeHtml(notice: ChatNotice): String =
  createHTML(prettyPrint = false).div(CHAT_NOTICE_CLASS) {
    when (notice) {
      is ChatNotice.Refused -> {
        p { +notice.fieldErrors.joinToString(" ") { it.message } }
      }

      is ChatNotice.Malformed -> {
        p { +MESSAGE_NOT_SENT }
      }

      is ChatNotice.OutOfAllowance -> {
        h2 { +OUT_OF_ALLOWANCE_TITLE }
        p { +exhaustedSentence(notice.entitlement) }
        p { +OUT_OF_ALLOWANCE_NEXT_STEP }
      }

      ChatNotice.Unavailable -> {
        p { +CONVERSATION_UNAVAILABLE }
        p { a(href = CHAT_PATH) { +NEW_CONVERSATION_LABEL } }
      }
    }
  }

/**
 * The sentence for the basis the allowance was spent on — the iOS paywall's
 * sentences (`PaywallCopy`), one per basis. The reset date is read in the
 * coach's own zone, the zone every date the coach says is read in.
 */
internal fun exhaustedSentence(entitlement: Entitlement): String =
  when (val basis = entitlement.basis) {
    EntitlementBasis.FreeAllowance -> "You've used your free coaching."

    is EntitlementBasis.Subscription -> "You've used this period's coaching. It resets ${UsDate.of(
      basis.resetsAt.atZone(COACH_ZONE).toLocalDate(),
    )}."

    EntitlementBasis.AdminExemption -> "You've used your coaching allowance."
  }
