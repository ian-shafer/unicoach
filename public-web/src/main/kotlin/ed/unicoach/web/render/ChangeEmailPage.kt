package ed.unicoach.web.render

import ed.unicoach.db.models.User
import ed.unicoach.db.session.ChangeEmailDraft
import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.FormSlot
import ed.unicoach.web.auth.ACCOUNT_PATH
import ed.unicoach.web.auth.CHANGE_EMAIL_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.p

/** Heading marker proving the change-email page rendered. */
const val CHANGE_EMAIL_HEADING = "Change your email address"

/**
 * The ONE message a change-email attempt whose address is already taken gets.
 *
 * Deliberately not "that address is already registered", for the reason
 * [REGISTER_DUPLICATE_ERROR] is not either: a signed-in caller could otherwise
 * walk a list of addresses and learn which ones have accounts.
 */
const val CHANGE_EMAIL_DUPLICATE_ERROR = "That email address cannot be used. Please try a different one."

/**
 * The change-email form's place in the session's drafts (RFC 265): the field
 * `session_metadata.proto` gives it, `FormDrafts.change_email`.
 */
val CHANGE_EMAIL_DRAFT =
  FormSlot<ChangeEmailDraft>(
    read = { drafts -> if (drafts.hasChangeEmail()) drafts.changeEmail else null },
    write = { drafts, draft -> drafts.setChangeEmail(draft) },
    clear = { drafts -> drafts.clearChangeEmail() },
  )

/**
 * The four states the change-email form is ever rendered in.
 *
 * Sealed and status-owning for the reason the sign-in and registration states
 * are: `status` and `error` as two independent defaulted parameters let a `409`
 * carry no message and a `200` carry one, and only a call site kept them
 * together.
 */
sealed interface ChangeEmailFormState {
  val status: HttpStatusCode

  /** The typed replacement address, re-rendered on a failure; `null` on a fresh form. */
  val newEmail: String?

  /** A form nobody has submitted yet. */
  data object Fresh : ChangeEmailFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
    override val newEmail: String? get() = null
  }

  /**
   * The form rendered from the session's draft (RFC 265): the address as the
   * family last typed it, with no error. The error belonged to that attempt and
   * is derived again by the next submission.
   */
  data class Drafted(
    val draft: ChangeEmailDraft,
  ) : ChangeEmailFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
    override val newEmail: String get() = draft.newEmail
  }

  /**
   * `:service` refused the address, and [message] is `:service`'s own sentence
   * rendered verbatim. A message written here would be a second statement of a
   * rule `:service` owns (D13), agreeing with it only until one of the two
   * changed.
   */
  data class Invalid(
    override val newEmail: String,
    val message: String,
  ) : ChangeEmailFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /** The address is in use. One message, [CHANGE_EMAIL_DUPLICATE_ERROR]. */
  data class Duplicate(
    override val newEmail: String,
  ) : ChangeEmailFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.Conflict
  }
}

/**
 * The change-email form.
 *
 * Brief decision D12 binds every form on a surface where a session exists, and
 * this one qualifies: the caller is signed in. It is the draft rail's first user
 * (RFC 265 D-C): a refused address is kept in the session as [CHANGE_EMAIL_DRAFT]
 * and the GET renders it back ([ChangeEmailFormState.Drafted]), so leaving the
 * page does not cost the family what they typed. [draftNotKept] adds the
 * notice saying why what they typed was not kept, when it was not.
 *
 * Changing the address clears verification in `:service` and mails the new
 * address, which is why the page says so BEFORE the family submits rather than
 * after: being bounced to a verification hold screen is a reasonable
 * consequence and a poor surprise.
 */

suspend fun ApplicationCall.respondChangeEmailPage(
  user: User,
  nav: ShellNav.SignedIn,
  state: ChangeEmailFormState = ChangeEmailFormState.Fresh,
  draftNotKept: DraftNotKept? = null,
) {
  // A signed-in nav, by type: this page is behind the gate, so a caller who
  // reaches it HAS a session, and its form echoes that session's CSRF token.
  respondHtml(state.status) {
    siteLayout(CHANGE_EMAIL_HEADING, nav = nav) {
      authFormCard(
        heading = CHANGE_EMAIL_HEADING,
        action = CHANGE_EMAIL_PATH,
        csrf = nav.csrf,
        error =
          when (state) {
            ChangeEmailFormState.Fresh, is ChangeEmailFormState.Drafted -> null
            is ChangeEmailFormState.Invalid -> state.message
            is ChangeEmailFormState.Duplicate -> CHANGE_EMAIL_DUPLICATE_ERROR
          },
        intro = {
          p { +"Your account currently uses ${user.email.value}." }
          p { +"We will send a verification email to the new address. You will need to verify it before you can use your account." }
          draftNotKeptNotice(draftNotKept)
        },
        after = {
          p {
            a(href = ACCOUNT_PATH) { +"Back to $SETTINGS_HEADING" }
          }
        },
      ) {
        textField(
          name = EMAIL_FIELD,
          labelText = "New email",
          type = InputType.email,
          value = state.newEmail,
          autocompleteToken = "email",
        )
        button(type = ButtonType.submit) { +"Change email" }
      }
    }
  }
}
