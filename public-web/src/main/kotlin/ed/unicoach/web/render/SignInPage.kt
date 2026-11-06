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
 * The states the sign-in form is ever rendered in.
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

  /**
   * A refused provider sign-in (RFC 275 §4), re-rendered at [refusal]'s status
   * with its sentence above the buttons.
   *
   * One arm per provider, because the two refusals carry different things:
   * only [Apple] has a posted name to put back, so a Google refusal holding a
   * name does not compile — it has no slot to hold one.
   */
  sealed interface SsoRefused : SignInFormState {
    val door: SsoDoor
    val refusal: SsoRefusal

    override val status: HttpStatusCode get() = refusal.status

    /** A refused Google post. */
    data class Google(
      override val refusal: SsoRefusal,
    ) : SsoRefused {
      override val door: SsoDoor get() = SsoDoor.GOOGLE
    }

    /**
     * A refused Apple post. [name] is the name it carried, put back in the
     * Apple form so a retry sends it again: Apple resolves the name only on a
     * user's FIRST authorization, and the retry's token carries none. `null`
     * when the post carried none.
     */
    data class Apple(
      override val refusal: SsoRefusal,
      val name: String?,
    ) : SsoRefused {
      override val door: SsoDoor get() = SsoDoor.APPLE
    }
  }
}

/**
 * Every way a provider sign-in is refused, each with its status and its
 * sentence (RFC 275 §4, D-F). The words name the provider ([SsoDoor.label]) and
 * always offer the email form, because a family refused at one door has the
 * other one on the same page.
 */
enum class SsoRefusal(
  val status: HttpStatusCode,
) {
  /** The provider's token did not verify — including a token for an audience we do not accept. */
  TOKEN_REJECTED(HttpStatusCode.Unauthorized),

  /** The post carried no token at all: the same sentence, at the status a malformed request earns. */
  TOKEN_MISSING(HttpStatusCode.BadRequest),

  /** The provider says the address on the account is unverified. */
  EMAIL_NOT_VERIFIED(HttpStatusCode.Forbidden),

  /** An unverified password account already holds the address, so linking it would be a takeover. */
  LINK_BLOCKED_UNVERIFIED_EMAIL(HttpStatusCode.Forbidden),

  /** The account is closed. */
  ACCOUNT_CLOSED(HttpStatusCode.Forbidden),

  /** The provider's keys could not be fetched. */
  UNAVAILABLE(HttpStatusCode.ServiceUnavailable),
  ;

  /** The sentence the page renders for this refusal at [door]. */
  fun sentence(door: SsoDoor): String {
    val provider = door.label
    return when (this) {
      TOKEN_REJECTED, TOKEN_MISSING -> {
        "We couldn't sign you in with $provider. Please try again, or use your email and password."
      }

      EMAIL_NOT_VERIFIED -> {
        "Your $provider account's email address isn't verified, so we can't use it to sign you in. " +
          "Verify it with $provider, or use your email and password."
      }

      LINK_BLOCKED_UNVERIFIED_EMAIL -> {
        "There's already an account for this email address, and its address hasn't been verified yet. " +
          "Sign in with your email and password and verify it, then try $provider again."
      }

      ACCOUNT_CLOSED -> {
        "This account has been closed, so it can't be signed in to."
      }

      UNAVAILABLE -> {
        "$provider sign-in isn't available right now. Please try again in a few minutes, or use your email and password."
      }
    }
  }
}

/**
 * The sign-in form, in one of the states [SignInFormState] allows, with the
 * provider buttons [sso] configures above it (RFC 275).
 *
 * [sso] has no default: the route that renders this page is also the route
 * that widens the policy for it, and both read the same value, so a caller
 * cannot render buttons the policy does not admit by forgetting one of them.
 */
suspend fun ApplicationCall.respondSignInPage(
  state: SignInFormState = SignInFormState.Fresh,
  nav: ShellNav = ShellNav.SignedOut,
  token: String? = null,
  report: String? = null,
  sso: SsoButtons,
) {
  val refused = state as? SignInFormState.SsoRefused
  respondHtml(state.status) {
    siteLayout(SIGN_IN_HEADING, nav = nav, headScripts = sso.scripts) {
      authFormCard(
        heading = SIGN_IN_HEADING,
        action = SIGN_IN_PATH,
        // A signed-out door: there is no session whose token it could echo.
        csrf = null,
        error =
          when (state) {
            SignInFormState.Fresh, is SignInFormState.SsoRefused -> null
            is SignInFormState.Rejected -> SIGN_IN_ERROR
          },
        // A provider's refusal is said beside the provider buttons, not above
        // the email form it is not about.
        intro = {
          ssoBlock(
            sso,
            token,
            report,
            refusal = refused?.refusal?.sentence(refused.door),
            appleName = (refused as? SignInFormState.SsoRefused.Apple)?.name,
          )
        },
        after = {
          p {
            +"New to unicoach? "
            a(href = pathCarryingToken(REGISTER_PATH, token, report)) { +"Create an account" }
          }
        },
      ) {
        tokenField(token)
        reportField(report)
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
