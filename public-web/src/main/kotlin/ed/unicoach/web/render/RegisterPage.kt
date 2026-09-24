package ed.unicoach.web.render

import ed.unicoach.error.FieldError
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

/** Heading marker proving the registration page rendered. */
const val REGISTER_HEADING = "Create your account"

/**
 * The ONE message a registration whose address is already taken gets.
 *
 * Deliberately the same shape as the generic sign-in message and deliberately
 * NOT "that email is already registered": the latter is the same enumeration
 * oracle the sign-in form refuses to be, reached through the other door. A
 * family who really does have an account is told where to go instead.
 */
const val REGISTER_DUPLICATE_ERROR =
  "We could not create an account with those details. If you already have an account, sign in instead."

/**
 * The three states the registration form is ever rendered in.
 */
sealed interface RegisterFormState {
  val status: HttpStatusCode

  /** The typed values re-rendered into the form; `null` when nothing was submitted. */
  val name: String?

  val email: String?

  /** A form nobody has submitted yet. */
  data object Fresh : RegisterFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
    override val name: String? get() = null
    override val email: String? get() = null
  }

  /**
   * `AuthService.register`'s validation failures, verbatim and unjudged.
   *
   * BOTH halves are carried. `RegistrationValidator` states every rule as a
   * [FieldError] and leaves [formErrors] empty, so a page that reads only the
   * flat list renders a 400 with nothing on it. The web tier writes no
   * validation rule of its own (D13); it renders what `:service` returned.
   */
  data class Invalid(
    override val name: String,
    override val email: String,
    val fieldErrors: List<FieldError>,
    val formErrors: List<String>,
  ) : RegisterFormState {
    /** Every message `:service` produced, field-level first, for one reader at the edge. */
    val messages: List<String> get() = fieldErrors.map { it.message } + formErrors

    override val status: HttpStatusCode get() = HttpStatusCode.BadRequest
  }

  /** The address is taken. One message, [REGISTER_DUPLICATE_ERROR], deliberately not an enumeration oracle. */
  data class Duplicate(
    override val name: String,
    override val email: String,
  ) : RegisterFormState {
    override val status: HttpStatusCode get() = HttpStatusCode.Conflict
  }
}

/**
 * The registration form, in one of the three states [RegisterFormState] allows.
 *
 * `RegisterResult` has exactly two failures and they are mutually exclusive, so
 * the page that renders them must not be able to show both — or to answer `409`
 * showing neither. As two independent defaulted parameters (`errors` and
 * `error`) both of those compiled; the sealed state owns the status and the
 * message channel together, so neither is expressible.
 *
 * The typed name and email are re-rendered on a failed attempt for the same
 * reason the sign-in form re-renders its address, and with the same caveat: this
 * is ordinary form handling, NOT the session form-state rule D12, which cannot
 * reach a page served to a visitor who has no session (D5). The password is
 * never re-rendered.
 */
suspend fun ApplicationCall.respondRegisterPage(
  state: RegisterFormState = RegisterFormState.Fresh,
  nav: ShellNav = ShellNav.SignedOut,
  token: String? = null,
) {
  respondHtml(state.status) {
    siteLayout(REGISTER_HEADING, nav = nav) {
      authFormCard(
        heading = REGISTER_HEADING,
        action = REGISTER_PATH,
        error =
          when (state) {
            RegisterFormState.Fresh, is RegisterFormState.Invalid -> null
            is RegisterFormState.Duplicate -> REGISTER_DUPLICATE_ERROR
          },
        intro = {
          // The validation failures are a LIST and sit where the single
          // form-level message would: they are the same rank of problem.
          if (state is RegisterFormState.Invalid) formErrors(state.messages)
        },
        after = {
          p {
            +"Already have an account? "
            a(href = pathCarryingToken(SIGN_IN_PATH, token)) { +SIGN_IN_HEADING }
          }
        },
      ) {
        tokenField(token)
        textField(
          name = NAME_FIELD,
          labelText = "Your name",
          type = InputType.text,
          value = state.name,
          autocompleteToken = "name",
        )
        textField(
          name = EMAIL_FIELD,
          labelText = "Email",
          type = InputType.email,
          value = state.email,
          autocompleteToken = "username",
        )
        passwordField(name = PASSWORD_FIELD, labelText = "Password", autocompleteToken = "new-password")
        button(type = ButtonType.submit) { +"Create account" }
      }
    }
  }
}
