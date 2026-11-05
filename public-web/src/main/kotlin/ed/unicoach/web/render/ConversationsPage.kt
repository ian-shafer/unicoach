package ed.unicoach.web.render

import ed.unicoach.coaching.COACH_ZONE
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.web.auth.CHAT_PATH
import ed.unicoach.web.auth.CONVERSATIONS_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.ul
import java.time.LocalDate

// Your conversations (RFC 272): the student's unarchived conversations, most
// recently used first, each openable in the chat, archivable and deletable, and
// the confirmation that deletes one. Copy is the iOS app's
// (`ConversationListView` and its delete dialog), verbatim, except where marked
// new.

/** The page's name: its heading and its title. */
const val YOUR_CONVERSATIONS_TITLE = "Your conversations"

/** The header link that opens the page (RFC 272, D-A). */
const val CONVERSATIONS_LINK = "Conversations"

/** The list's way to a new conversation. */
const val NEW_CONVERSATION = "New conversation"

// The empty state, iOS verbatim.
const val NO_CONVERSATIONS_YET = "No conversations yet"

const val START_FOR_COACHING = "Start a conversation to get coaching."

const val START_A_CONVERSATION = "Start a conversation"

/** The button on every row that archives it, at once. */
const val ARCHIVE_LABEL = "Archive"

/** The link on every row that opens the delete confirmation, and that page's button that deletes. */
const val DELETE_LABEL = "Delete"

const val DELETE_CONVERSATION_HEADING = "Delete this conversation?"

/** What deleting loses (RFC 272, new, D-C). */
const val DELETE_CONVERSATION_LOST =
  "You won't be able to open it or read it again, here or in the iPhone app. This can't be undone."

/**
 * What deleting keeps (RFC 272, new, D-C): what the coach learned has no join
 * to the deleted conversation, so it stays. The details page is named by its
 * own title, so this sentence cannot call it something the header does not.
 */
const val DELETE_CONVERSATION_KEPT =
  "What your coach has already learned from it is kept. Your college list and $YOUR_DETAILS_TITLE don't change."

/** The receipt after a delete (RFC 272, new). A deleted conversation is gone from every read, so it has no name. */
const val DELETED_RECEIPT = "Conversation deleted."

/** The receipt after an archive (RFC 272, new), naming the conversation archived. */
fun archivedReceipt(name: String): String = "Archived “$name”."

/** The class the list page's and the confirmation's section carries. */
const val CONVERSATIONS_CLASS = "conversations"

/** The class the list of conversation cards carries. */
const val CONVERSATION_LIST_CLASS = "conversation-list"

/** The class a card's last-used date carries. */
const val CONVERSATION_DATE_CLASS = "conversation-date"

/** The class a card's Archive/Delete row, and the confirmation's Delete/Cancel row, carry. */
const val CONVERSATION_ACTIONS_CLASS = "conversation-actions"

/** The class the "New conversation" and "Start a conversation" links carry: a link styled as the site's primary button. */
const val CONVERSATION_NEW_CLASS = "conversation-new"

/** The query key that names the conversation a redirect just archived. */
const val ARCHIVED_QUERY_PARAM = "archived"

/** The query key that asks the list for the delete receipt, and the one value that does. */
const val DELETED_QUERY_PARAM = "deleted"

const val DELETED_QUERY_VALUE = "1"

/** The list with the delete receipt. */
const val DELETED_LIST_PATH = "$CONVERSATIONS_PATH?$DELETED_QUERY_PARAM=$DELETED_QUERY_VALUE"

/** One conversation's archive: a `POST`. */
fun conversationArchivePath(convoId: ConvoId): String = "$CONVERSATIONS_PATH/${convoId.asString}/archive"

/** One conversation's delete confirmation, and the `POST` that deletes it. */
fun conversationDeletePath(convoId: ConvoId): String = "$CONVERSATIONS_PATH/${convoId.asString}/delete"

/** The list with the archive receipt for [convoId]. */
fun archivedListPath(convoId: ConvoId): String = "$CONVERSATIONS_PATH?$ARCHIVED_QUERY_PARAM=${convoId.asString}"

/**
 * What the redirect that led to the list asked it to say. [archivedName] is the
 * name of the conversation the archive receipt names, read from the service —
 * never from the URL — or `null` for no archive receipt.
 */
data class ConversationsArrival(
  val archivedName: String?,
  val deleted: Boolean,
)

/**
 * The day [this] conversation was last used, in [COACH_ZONE]: its latest turn,
 * or when it was last changed if it has none, as the iOS app falls back. A
 * calendar date and never a relative one — a server cannot know the reader's
 * "yesterday" (RFC 272 §3).
 */
private val ConvoWithActivity.lastUsedOn: LocalDate
  get() = LocalDate.ofInstant(lastActivityAt ?: convo.updatedAt, COACH_ZONE)

/**
 * `/conversations`: the receipts the redirect asked for, then one card per
 * conversation in the order the service lists them, or the empty state.
 */
suspend fun ApplicationCall.respondConversationsPage(
  nav: ShellNav.SignedIn,
  conversations: List<ConvoWithActivity>,
  arrival: ConversationsArrival,
) {
  respondHtml(HttpStatusCode.OK) {
    siteLayout(YOUR_CONVERSATIONS_TITLE, nav = nav) {
      section(CONVERSATIONS_CLASS) {
        h1 { +YOUR_CONVERSATIONS_TITLE }
        arrival.archivedName?.let { name -> p(NOTICE_CLASS) { +archivedReceipt(name) } }
        if (arrival.deleted) p(NOTICE_CLASS) { +DELETED_RECEIPT }
        if (conversations.isEmpty()) {
          p { +NO_CONVERSATIONS_YET }
          p { +START_FOR_COACHING }
          newConversationLink(START_A_CONVERSATION)
        } else {
          newConversationLink(NEW_CONVERSATION)
          conversationList(nav, conversations)
        }
      }
    }
  }
}

/** A link to a new conversation in the chat, labelled [label], styled as the site's primary button. */
private fun FlowContent.newConversationLink(label: String) {
  p { a(href = CHAT_PATH, classes = CONVERSATION_NEW_CLASS) { +label } }
}

/**
 * One card per conversation: its name opening it in the chat, the day it was
 * last used beneath, then Archive — a form, since it changes state at once —
 * and Delete, a link to the confirmation. Both always show: a desktop has no
 * swipe to reveal them.
 */
private fun FlowContent.conversationList(
  nav: ShellNav.SignedIn,
  conversations: List<ConvoWithActivity>,
) {
  ul(CONVERSATION_LIST_CLASS) {
    for (listing in conversations) {
      val convoId = listing.convo.id
      li {
        a(href = conversationPath(convoId)) { +listing.convo.name.value }
        p(CONVERSATION_DATE_CLASS) { +UsDate.of(listing.lastUsedOn) }
        div(CONVERSATION_ACTIONS_CLASS) {
          signedInForm(conversationArchivePath(convoId), nav.csrf) {
            button(type = ButtonType.submit) { +ARCHIVE_LABEL }
          }
          a(href = conversationDeletePath(convoId)) { +DELETE_LABEL }
        }
      }
    }
  }
}

/**
 * `/conversations/{conversationId}/delete`: "Delete this conversation?", its
 * name, what is lost and what is kept, and one button that deletes it — or
 * Cancel, back to the list.
 */
suspend fun ApplicationCall.respondDeleteConversationPage(
  nav: ShellNav.SignedIn,
  convoId: ConvoId,
  name: String,
) {
  respondHtml(HttpStatusCode.OK) {
    siteLayout(DELETE_CONVERSATION_HEADING, nav = nav) {
      section(CONVERSATIONS_CLASS) {
        h1 { +DELETE_CONVERSATION_HEADING }
        p { +name }
        p { +DELETE_CONVERSATION_LOST }
        p { +DELETE_CONVERSATION_KEPT }
        signedInForm(conversationDeletePath(convoId), nav.csrf) {
          div(CONVERSATION_ACTIONS_CLASS) {
            button(type = ButtonType.submit) { +DELETE_LABEL }
            a(href = CONVERSATIONS_PATH) { +CANCEL_LABEL }
          }
        }
      }
    }
  }
}
