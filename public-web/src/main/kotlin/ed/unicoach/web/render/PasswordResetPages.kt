package ed.unicoach.web.render

import ed.unicoach.auth.PasswordViolation
import ed.unicoach.auth.ResetLink
import ed.unicoach.web.auth.FORGOT_PASSWORD_PATH
import ed.unicoach.web.auth.RESET_PASSWORD_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.P
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.section

/** Heading of the request form, and the words of the link to it from the sign-in form. */
const val FORGOT_PASSWORD_HEADING = "Forgot your password?"

/** Heading marker proving the one answer the request form gives rendered. */
const val PASSWORD_RESET_SENT_HEADING = "Check your email"

/** Heading marker proving the form a usable link opens rendered. */
const val RESET_PASSWORD_HEADING = "Choose a new password"

/** The states the request form is ever rendered in. */
sealed interface ForgotPasswordFormState {
  val status: HttpStatusCode

  /** A form nobody has submitted yet. */
  data object Fresh : ForgotPasswordFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /**
   * A malformed address, re-rendered at `400` with what was typed: format says
   * nothing about whether an account exists. [email] is `null` when the post
   * carried no single email field.
   */
  data class Malformed(
    val email: String?,
  ) : ForgotPasswordFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }
}

/** The request form. A signed-out door: there is no session whose token it could echo. */
suspend fun ApplicationCall.respondForgotPasswordPage(
  state: ForgotPasswordFormState = ForgotPasswordFormState.Fresh,
  nav: ShellNav = ShellNav.SignedOut,
) {
  respondHtml(state.status) {
    siteLayout(FORGOT_PASSWORD_HEADING, nav = nav) {
      authFormCard(
        heading = FORGOT_PASSWORD_HEADING,
        action = FORGOT_PASSWORD_PATH,
        csrf = null,
        intro = { p { +"Enter your email address and we'll send you a link to choose a new one." } },
        after = { p { a(href = SIGN_IN_PATH) { +"Back to sign in" } } },
      ) {
        textField(
          name = EMAIL_FIELD,
          labelText = "Email",
          type = InputType.email,
          value = (state as? ForgotPasswordFormState.Malformed)?.email,
          autocompleteToken = "username",
        )
        button(type = ButtonType.submit) { +"Send link" }
      }
    }
  }
}

/**
 * The ONE answer a well-formed address gets, whether or not it has an account.
 * It takes nothing about the request, so nothing about the request can show.
 */
suspend fun ApplicationCall.respondPasswordResetSent(nav: ShellNav = ShellNav.SignedOut) {
  respondHtml {
    siteLayout(PASSWORD_RESET_SENT_HEADING, nav = nav) {
      section(AUTH_FORM_CLASS) {
        h1 { +PASSWORD_RESET_SENT_HEADING }
        p {
          +"If there's a Unicoach account for that address, we've sent it a link to choose a new password. "
          +"If nothing arrives, check your spam folder or try again in a few minutes."
        }
      }
    }
  }
}

/** The states the new-password form is ever rendered in. */
sealed interface ResetPasswordFormState {
  val status: HttpStatusCode

  /** A form nobody has submitted yet. */
  data object Fresh : ResetPasswordFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /** The rules the posted password broke. */
  data class Rejected(
    val violations: List<PasswordViolation>,
  ) : ResetPasswordFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }
}

/** The form a usable link opens; [token] rides a hidden field on the `POST`. The password is never re-rendered. */
suspend fun ApplicationCall.respondResetPasswordPage(
  token: String,
  state: ResetPasswordFormState = ResetPasswordFormState.Fresh,
  nav: ShellNav = ShellNav.SignedOut,
) {
  respondHtml(state.status) {
    siteLayout(RESET_PASSWORD_HEADING, nav = nav) {
      authFormCard(
        heading = RESET_PASSWORD_HEADING,
        action = RESET_PASSWORD_PATH,
        csrf = null,
        intro = { if (state is ResetPasswordFormState.Rejected) formErrors(state.violations.map { it.sentence }) },
      ) {
        hiddenInput(name = TOKEN_FIELD) { value = token }
        passwordField(name = PASSWORD_FIELD, labelText = "New password", autocompleteToken = "new-password")
        button(type = ButtonType.submit) { +"Save new password" }
      }
    }
  }
}

/** The page for a link that can do nothing. Each one links to the request form, so nobody is stranded. */
suspend fun ApplicationCall.respondDeadResetLink(
  link: ResetLink.Dead,
  nav: ShellNav = ShellNav.SignedOut,
) {
  val (status, heading) =
    when (link) {
      is ResetLink.Dead.NotValid -> HttpStatusCode.BadRequest to "Link not valid"
      ResetLink.Dead.Expired -> HttpStatusCode.Gone to "Link expired"
      ResetLink.Dead.AlreadyUsed -> HttpStatusCode.Gone to "Link already used"
    }
  respondHtml(status) {
    siteLayout(heading, nav = nav) {
      section(AUTH_FORM_CLASS) {
        h1 { +heading }
        p {
          when (link) {
            is ResetLink.Dead.NotValid -> {
              +"This link isn't valid. You can "
              askForANewOne()
              +"."
            }

            ResetLink.Dead.Expired -> {
              +"This link has expired. You can "
              askForANewOne()
              +"."
            }

            ResetLink.Dead.AlreadyUsed -> {
              +"This link has already been used. You can "
              askForANewOne()
              +", or "
              a(href = SIGN_IN_PATH) { +"sign in" }
              +"."
            }
          }
        }
      }
    }
  }
}

private fun P.askForANewOne() {
  a(href = FORGOT_PASSWORD_PATH) { +"ask for a new one" }
}
