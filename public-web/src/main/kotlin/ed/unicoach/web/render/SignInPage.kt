package ed.unicoach.web.render

import ed.unicoach.web.auth.REGISTER_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.p

/** Heading marker proving the sign-in page rendered. */
const val SIGN_IN_HEADING = "Sign in"

/**
 * The ONE message every failed sign-in gets.
 *
 * `AuthService.login` distinguishes four failures — an unparseable address, no
 * such user, a user with no password set, a wrong password — and all four
 * collapse to this sentence, exactly as `rest-server` and `admin-web` already
 * collapse them. Telling a stranger which one occurred turns the form into an
 * account-enumeration oracle: "no such user" and "wrong password" are the two
 * halves of a list of who has an account here.
 *
 * The web tier makes no authentication judgement of its own (brief 0013 D13);
 * it renders what `:service` returned, and what it renders is this.
 */
const val SIGN_IN_ERROR = "Email or password is incorrect."

/**
 * The two states the sign-in form is ever rendered in.
 *
 * A sealed type that OWNS its status, rather than a `status` parameter and an
 * `error` parameter that happen to be passed together. The rule is "a rejected
 * credential must not answer `200`", and as two independent defaulted parameters
 * nothing enforced it: `respondSignInPage(HttpStatusCode.Unauthorized)` with no
 * message, and `respondSignInPage(error = SIGN_IN_ERROR)` at `200`, both
 * compiled. Neither is expressible now.
 *
 * [Rejected] carries no message of its own for the same reason the form renders
 * one sentence for four failures: there is only [SIGN_IN_ERROR] to render, and a
 * message parameter would be somewhere for a second one to appear.
 */
sealed interface SignInFormState {
  val status: HttpStatusCode

  /** A form nobody has submitted yet. */
  data object Fresh : SignInFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  /**
   * A refused attempt, re-rendered at `401` with the typed address.
   *
   * [email] is carried so a family that mistyped a password does not retype
   * their address. That is ORDINARY FORM HANDLING and is NOT session storage:
   * brief decision D12 ("wherever a session exists, every form stores its data
   * in it") does not reach this page, because D5 mints no session for a
   * signed-out visitor and there is therefore nothing to store a draft in. This
   * re-render does NOT satisfy D4 and nothing here claims it does —
   * `SignInRoutingTest` asserts that no session row exists after a failed
   * attempt.
   */
  data class Rejected(
    val email: String,
  ) : SignInFormState {
    // 401, not 400: a sign-in failure is an AUTHENTICATION failure. A
    // registration validation failure answers 400 and a duplicate answers 409,
    // and the difference is policy — a client that treats 401 as "no session"
    // must not be told a typo is a data problem.
    override val status: HttpStatusCode get() = HttpStatusCode.Unauthorized
  }
}

/** The sign-in form, in one of the two states [SignInFormState] allows. */
suspend fun ApplicationCall.respondSignInPage(
  state: SignInFormState = SignInFormState.Fresh,
  nav: ShellNav = ShellNav.SignedOut,
) {
  respondHtml(state.status) {
    siteLayout(SIGN_IN_HEADING, nav = nav) {
      authFormCard(
        heading = SIGN_IN_HEADING,
        action = SIGN_IN_PATH,
        error =
          when (state) {
            SignInFormState.Fresh -> null
            is SignInFormState.Rejected -> SIGN_IN_ERROR
          },
        after = {
          p {
            +"New to unicoach? "
            a(href = REGISTER_PATH) { +"Create an account" }
          }
        },
      ) {
        textField(
          name = EMAIL_FIELD,
          labelText = "Email",
          type = InputType.email,
          value = (state as? SignInFormState.Rejected)?.email,
          autocompleteToken = "username",
        )
        passwordField(name = PASSWORD_FIELD, labelText = "Password", autocompleteToken = "current-password")
        button(type = ButtonType.submit) { +"Sign in" }
      }
    }
  }
}
