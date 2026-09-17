package ed.unicoach.web.render

import ed.unicoach.db.models.User
import ed.unicoach.web.VerifyEmailOutcome
import ed.unicoach.web.auth.RESEND_VERIFICATION_PATH
import ed.unicoach.web.auth.VERIFY_EMAIL_PATH
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FormMethod
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.section

// The email-verification confirm and result pages. Both render through
// siteLayout (per the render-package invariant that every page renders through
// that one seam), reusing the existing chrome classes and adding no new styling.
//
// The confirm page is the load-bearing side-effect-free step: it renders a
// POST /verify-email form carrying the token in a hidden field and performs no
// verification, so an email-scanner prefetch of the mailed GET link cannot burn
// the single-use token.

// Heading marker proving the confirm page rendered.
private const val CONFIRM_HEADING = "Confirm your email"

/** Heading marker proving the verification HOLD screen rendered. */
const val VERIFY_HOLD_HEADING = "Check your email"

/**
 * The ONE answer a resend gets, whether a token was issued or the user was
 * already verified.
 *
 * `EmailVerificationService.resend` returns `Sent` or `AlreadyVerified`, and
 * `rest-server` answers `204` to both for the reason this page says the same
 * sentence to both: a different answer per branch tells whoever is holding the
 * session whether the address is already confirmed, which is state they did not
 * have before they pressed the button.
 */
const val RESEND_CONFIRMATION = "If your email address still needs verifying, we have sent a new link to it."

/**
 * The confirmation a family reads after a successful email change.
 *
 * It is owned by THIS page rather than by the account page, and rendered HERE,
 * because changing the address clears verification in `:service` — so the gate
 * holds the family at this screen and `/account` is the one page they cannot
 * reach until the new address is verified. The sentence was written for this
 * screen anyway.
 */
const val EMAIL_CHANGED_CONFIRMATION =
  "Your email address has been changed. Check the new address for a verification link."

// Per-outcome heading markers, each distinct so a test can assert which rendered.
private const val VERIFIED_HEADING = "Email verified"
private const val INVALID_TOKEN_HEADING = "Link not valid"
private const val EXPIRED_HEADING = "Link expired"
private const val ALREADY_USED_HEADING = "Already verified"
private const val UNAVAILABLE_HEADING = "Verification unavailable"

/**
 * The confirm page: a section with a `method="post" action="/verify-email"` form
 * containing a hidden `token` input and a submit button. No backend call, no
 * state change — submission is what consumes the token.
 */
suspend fun ApplicationCall.respondVerifyEmailConfirm(
  token: String,
  nav: ShellNav = ShellNav.SignedOut,
) {
  respondHtml {
    siteLayout("Verify email", nav = nav) {
      section("verify-email") {
        h1 { +CONFIRM_HEADING }
        p { +"Click the button below to confirm your email address." }
        form(action = VERIFY_EMAIL_PATH, method = FormMethod.post) {
          hiddenInput(name = TOKEN_FIELD) { value = token }
          button(type = ButtonType.submit) { +"Verify email" }
        }
      }
    }
  }
}

/**
 * The result page for a completed verify attempt. Each [outcome] gets a distinct
 * heading marker and copy. On [VerifyEmailOutcome.Verified] and
 * [VerifyEmailOutcome.AlreadyUsed], when [isIPhone] is true and [openInAppUrl]
 * is non-blank, the body includes an "Open in app" link. The URL is rendered
 * verbatim as an opaque `href` (kotlinx.html escapes it as an attribute value);
 * there is no parsing or scheme validation. The link is inert markup here — iOS
 * handling is out of scope.
 */
suspend fun ApplicationCall.respondVerifyEmailResult(
  outcome: VerifyEmailOutcome,
  openInAppUrl: String?,
  isIPhone: Boolean,
  nav: ShellNav = ShellNav.SignedOut,
) {
  val (heading, detail) =
    when (outcome) {
      VerifyEmailOutcome.Verified -> {
        VERIFIED_HEADING to "Your email address has been verified. You're all set."
      }

      VerifyEmailOutcome.InvalidToken -> {
        INVALID_TOKEN_HEADING to "This verification link is not valid. Please request a new one."
      }

      VerifyEmailOutcome.Expired -> {
        EXPIRED_HEADING to "This verification link has expired. Please request a new one."
      }

      VerifyEmailOutcome.AlreadyUsed -> {
        ALREADY_USED_HEADING to "Your email address is already verified."
      }

      VerifyEmailOutcome.Unavailable -> {
        UNAVAILABLE_HEADING to "We couldn't complete verification right now. Please try again shortly."
      }
    }

  val showOpenInApp =
    isIPhone &&
      !openInAppUrl.isNullOrBlank() &&
      (outcome == VerifyEmailOutcome.Verified || outcome == VerifyEmailOutcome.AlreadyUsed)

  respondHtml {
    siteLayout("Verify email", nav = nav) {
      section("verify-email") {
        h1 { +heading }
        p { +detail }
        if (showOpenInApp) {
          p {
            a(href = openInAppUrl) { +"Open in app" }
          }
        }
      }
    }
  }
}

/**
 * The verification HOLD screen: what a signed-in but unverified family sees
 * instead of their account.
 *
 * This is the web's rendering of the rule `rest-server`'s
 * `EmailVerificationGate` enforces. The API answers `403 email_not_verified`,
 * which is the right answer to a program; a browser gets the screen that names
 * the address the mail went to and offers the button that sends it again. Same
 * rule, stated in the medium each client understands — and the reason the gate
 * in `public-web` redirects here rather than rendering a status page.
 *
 * Naming the address is the load-bearing part. "Check your email" on its own is
 * useless to a parent who does not remember which address they registered with,
 * or who typed it wrongly — and typing it wrongly is precisely the case that
 * lands here and cannot be escaped without being told.
 */
suspend fun ApplicationCall.respondVerifyEmailHold(
  user: User,
  notice: WebNotice? = null,
) {
  respondHtml {
    siteLayout("Verify email", nav = ShellNav.SignedIn) {
      section("verify-email") {
        h1 { +VERIFY_HOLD_HEADING }
        p { +"We sent a verification link to ${user.email.value}. Open it to finish setting up your account." }
        notice(notice)
        form(action = RESEND_VERIFICATION_PATH, method = FormMethod.post) {
          button(type = ButtonType.submit) { +"Send it again" }
        }
        p { +"Signed in with the wrong address? Change it from your account page once you have verified, or sign out and register again." }
      }
    }
  }
}
