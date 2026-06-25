package ed.unicoach.web

import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.VerifyEmailResult
import ed.unicoach.web.render.respondHomePage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.respondPrivacyPage
import ed.unicoach.web.render.respondServiceUnavailablePage
import ed.unicoach.web.render.respondTermsPage
import ed.unicoach.web.render.respondVerifyEmailConfirm
import ed.unicoach.web.render.respondVerifyEmailResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * The single routing table for public-web. Every HTML page is an explicit
 * dynamic `GET` route registered before the catch-all static mount, so the mount
 * never handles them; the mount serves only chrome-less assets under `static/`.
 *
 * The email-verification flow is server-side and two-step: `GET /verify-email`
 * renders a confirm form (no backend call, no state change — this preserves the
 * single-use-token guarantee against scanner prefetch), and `POST /verify-email`
 * consumes the token in-process through [emailVerifier] and renders the result
 * page. The open-in-app affordance is iPhone-only ([openInAppUrl]).
 *
 * `StatusPages` renders the branded dynamic error pages: a `status` handler for
 * `NotFound` (unmatched routes perform no lookups, so the 404 must be caught
 * structurally at the status layer) and an `exception<Throwable>` catch-all that
 * renders the 503 rather than leaking a stack trace.
 */
fun Application.installPublicWebRouting(
  emailVerifier: EmailVerifier,
  openInAppUrl: String,
) {
  install(StatusPages) {
    status(HttpStatusCode.NotFound) { call, _ ->
      call.respondNotFoundPage()
    }
    exception<Throwable> { call, _ ->
      call.respondServiceUnavailablePage()
    }
  }

  routing {
    healthRoute()
    get("/") { call.respondHomePage() }
    get("/terms") { call.respondTermsPage() }
    get("/privacy") { call.respondPrivacyPage() }

    // Side-effect-free confirm step: a non-blank token renders the confirm form;
    // an absent/blank token renders InvalidToken directly. No backend call.
    get("/verify-email") {
      val token = call.request.queryParameters["token"]
      if (token.isNullOrBlank()) {
        call.respondVerifyEmailResult(VerifyEmailOutcome.InvalidToken, openInAppUrl, isIPhone = false)
      } else {
        call.respondVerifyEmailConfirm(token)
      }
    }

    // The one state-mutating route: it burns a single-use token. A blank token
    // short-circuits to InvalidToken with no verify call; otherwise the verifier
    // consumes the token in-process and the mapped outcome is rendered.
    post("/verify-email") {
      val token = call.receiveParameters()["token"]
      val isIPhone =
        call.request.headers[HttpHeaders.UserAgent]
          ?.contains("iPhone") ?: false
      val outcome =
        if (token.isNullOrBlank()) {
          VerifyEmailOutcome.InvalidToken
        } else {
          emailVerifier.verify(token).toOutcome()
        }
      call.respondVerifyEmailResult(outcome, openInAppUrl, isIPhone)
    }

    staticResources("/", "static")
  }
}

/**
 * Maps the domain [Result]<[VerifyEmailResult]> to the render view-model
 * [VerifyEmailOutcome]: a compiler-checked exhaustive `when` over the sealed
 * success type, with any failure (a DB fault) folding to [Unavailable].
 */
private fun Result<VerifyEmailResult>.toOutcome(): VerifyEmailOutcome =
  fold(
    onSuccess = { result ->
      when (result) {
        is VerifyEmailResult.Success -> VerifyEmailOutcome.Verified
        VerifyEmailResult.InvalidToken -> VerifyEmailOutcome.InvalidToken
        VerifyEmailResult.Expired -> VerifyEmailOutcome.Expired
        VerifyEmailResult.AlreadyConsumed -> VerifyEmailOutcome.AlreadyUsed
      }
    },
    onFailure = { VerifyEmailOutcome.Unavailable },
  )
