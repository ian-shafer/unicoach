package ed.unicoach.web.render

import ed.unicoach.web.VerifyEmailOutcome
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
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
suspend fun ApplicationCall.respondVerifyEmailConfirm(token: String) {
  respondHtml {
    siteLayout("Verify email") {
      section("verify-email") {
        h1 { +CONFIRM_HEADING }
        p { +"Click the button below to confirm your email address." }
        form(action = "/verify-email", method = kotlinx.html.FormMethod.post) {
          hiddenInput(name = "token") { value = token }
          button(type = kotlinx.html.ButtonType.submit) { +"Verify email" }
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
) {
  val (heading, detail) =
    when (outcome) {
      VerifyEmailOutcome.Verified ->
        VERIFIED_HEADING to "Your email address has been verified. You're all set."
      VerifyEmailOutcome.InvalidToken ->
        INVALID_TOKEN_HEADING to "This verification link is not valid. Please request a new one."
      VerifyEmailOutcome.Expired ->
        EXPIRED_HEADING to "This verification link has expired. Please request a new one."
      VerifyEmailOutcome.AlreadyUsed ->
        ALREADY_USED_HEADING to "Your email address is already verified."
      VerifyEmailOutcome.Unavailable ->
        UNAVAILABLE_HEADING to "We couldn't complete verification right now. Please try again shortly."
    }

  val showOpenInApp =
    isIPhone &&
      !openInAppUrl.isNullOrBlank() &&
      (outcome == VerifyEmailOutcome.Verified || outcome == VerifyEmailOutcome.AlreadyUsed)

  respondHtml {
    siteLayout("Verify email") {
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
