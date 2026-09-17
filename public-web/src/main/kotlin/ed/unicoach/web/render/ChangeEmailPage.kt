package ed.unicoach.web.render

import ed.unicoach.db.models.User
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
 * The three states the change-email form is ever rendered in.
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
 * This page carries a NAMED deviation rather than a silent one (RFC 224 D-E).
 * Brief decision D12 binds every form on a surface where a session exists, and
 * this one qualifies: the caller is signed in. The form-state rail D12 would
 * write into is `web/05`, and it does not exist yet, so this form re-renders
 * the typed value on error and stores NO draft. `web/05` must sweep it in when
 * the rail lands. That re-render does not satisfy D4 and this comment does not
 * claim it does — it is ordinary form handling and nothing more. Dropping
 * change-email from the slice was the alternative, and its acceptance criteria
 * forbid it.
 *
 * Changing the address clears verification in `:service` and mails the new
 * address, which is why the page says so BEFORE the family submits rather than
 * after: being bounced to a verification hold screen is a reasonable
 * consequence and a poor surprise.
 */

suspend fun ApplicationCall.respondChangeEmailPage(
  user: User,
  state: ChangeEmailFormState = ChangeEmailFormState.Fresh,
) {
  // ShellNav.SignedIn unconditionally: this page is behind the gate, so a
  // caller who reaches it HAS a session.
  respondHtml(state.status) {
    siteLayout(CHANGE_EMAIL_HEADING, nav = ShellNav.SignedIn) {
      authFormCard(
        heading = CHANGE_EMAIL_HEADING,
        action = CHANGE_EMAIL_PATH,
        error =
          when (state) {
            ChangeEmailFormState.Fresh -> null
            is ChangeEmailFormState.Invalid -> state.message
            is ChangeEmailFormState.Duplicate -> CHANGE_EMAIL_DUPLICATE_ERROR
          },
        intro = {
          p { +"Your account currently uses ${user.email.value}." }
          p { +"We will send a verification email to the new address. You will need to verify it before you can use your account." }
        },
        after = {
          p {
            a(href = ACCOUNT_PATH) { +"Back to your account" }
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
