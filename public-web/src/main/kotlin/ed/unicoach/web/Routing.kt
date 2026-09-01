package ed.unicoach.web

import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.VerifyEmailResult
import ed.unicoach.common.config.TOKEN_QUERY_PARAM
import ed.unicoach.web.common.http.setLinkHolderPrivacyHeaders
import ed.unicoach.web.common.logging.secretQueryParams
import ed.unicoach.web.render.respondCostReportPage
import ed.unicoach.web.render.respondHomePage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.respondPrivacyPage
import ed.unicoach.web.render.respondServiceUnavailablePage
import ed.unicoach.web.render.respondTermsPage
import ed.unicoach.web.render.respondVerifyEmailConfirm
import ed.unicoach.web.render.respondVerifyEmailResult
import ed.unicoach.web.report.CostReportOutcome
import ed.unicoach.web.report.CostReportSource
import ed.unicoach.web.report.MissReason
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

/**
 * The path a share link points at, and the query key it carries its token in.
 *
 * Named because they are HALF OF A CONTRACT the compiler cannot see. The other
 * half is `costReport.shareUrlBase` in `service.conf` — the base `:service`
 * speaks into a parent's text thread — and a link already sent cannot be
 * updated. Renaming either side alone 404s every live link, silently, so a test
 * pins the packaged default against these two names.
 */
const val REPORT_PATH = "/report"

/**
 * The query key the token rides in, read from the ONE place both sides of the
 * contract read it: `:service` composes the link with [tokenLink] and this
 * module reads it back with the same name, so the two cannot drift apart.
 */
const val REPORT_TOKEN_PARAM = TOKEN_QUERY_PARAM

/**
 * The one place a public-web failure is visible.
 *
 * The branded 503 says nothing on purpose, so a cause not logged HERE is lost:
 * `CallLogging` prints the status line, not the throwable, and `StatusPages`
 * does not log a cause it handled. `admin-web` already logs its own; public-web
 * was the outlier.
 */
private val publicWebErrorLog = LoggerFactory.getLogger("ed.unicoach.web.PublicWebErrors")

/** The report route's own log: WHY a link resolved to nothing, which the rendered page must never say. */
private val reportLog = LoggerFactory.getLogger("ed.unicoach.web.CostReport")

/**
 * The single routing table for public-web. Every HTML page is an explicit
 * dynamic `GET` route registered before the catch-all static mount, so the mount
 * never handles them; the mount serves only chrome-less assets under `static/`.
 *
 * `GET /report` is the Family Cost Report (RFC 155): a tokenized, logged-out
 * page whose whole read is [costReportSource]. It is registered with
 * `secretQueryParams("token")` so the raw share token never reaches the request
 * log, and it is side-effect free, because the link is designed to be pasted
 * into a message and will be prefetched.
 *
 * The email-verification flow is server-side and two-step: `GET /verify-email`
 * renders a confirm form (no backend call, no state change — this preserves the
 * single-use-token guarantee against scanner prefetch), and `POST /verify-email`
 * consumes the token in-process through [emailVerifier] and renders the result
 * page. The open-in-app affordance is iPhone-only and optional ([openInAppUrl]);
 * a `null`/blank URL omits it.
 *
 * `StatusPages` renders the branded dynamic error pages: a `status` handler for
 * `NotFound` (unmatched routes perform no lookups, so the 404 must be caught
 * structurally at the status layer) and an `exception<Throwable>` catch-all that
 * renders the 503 rather than leaking a stack trace.
 */
fun Application.installPublicWebRouting(
  emailVerifier: EmailVerifier,
  costReportSource: CostReportSource,
  openInAppUrl: String?,
) {
  install(StatusPages) {
    status(HttpStatusCode.NotFound) { call, _ ->
      call.respondNotFoundPage()
    }
    exception<Throwable> { call, cause ->
      // The PATH, never the uri and never the query: /report carries a LIVE
      // share token in its query, and the log-redaction seam is not reachable
      // from here. A method and a path are what an operator needs; the token is
      // not, ever.
      publicWebErrorLog.error(
        "public-web call failed: method=[{}] path=[{}]",
        call.request.httpMethod.value,
        call.request.path(),
        cause,
      )
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

    reportRoute(costReportSource)

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
    // PRE-EXISTING and outside RFC 155's mandate, fixed deliberately: the fold
    // dropped its DB fault at the mapping site and nothing upstream logged it,
    // so a broken verification read left no evidence anywhere. The rendered
    // outcome is unchanged.
    onFailure = { cause ->
      publicWebErrorLog.error("Email verification read failed", cause)
      VerifyEmailOutcome.Unavailable
    },
  )

/**
 * `GET /report?token=<raw>` — the Family Cost Report (RFC 155).
 *
 * A `route` block rather than a bare `get` so [secretQueryParams] can be
 * installed route-scoped: it stamps the redaction set in the `Setup` phase, so
 * even a pre-handler failure on this route logs `token=[redacted]` instead of
 * the live secret. RFC 155 D-D is that seam's first caller, and it is why the
 * token is a query param rather than a path segment: the seam redacts query
 * params only.
 *
 * The three headers (D-H) are set BEFORE the outcome is known, so they ride the
 * 404 and the 503 exactly as they ride the report: `no-store` keeps a shared
 * proxy from holding a copy of a family's numbers, `X-Robots-Tag` keeps the page
 * out of an index that reads only the response head, and `no-referrer` keeps the
 * token out of the `Referer` of any link a parent follows from the page.
 *
 * A missing, blank, unknown or revoked token renders the SAME branded 404 as any
 * unmatched route — one page, one body, no way to learn from it that a token was
 * once real. A read fault throws and is rendered as the branded 503 by
 * `StatusPages`, never folded into the 404, which would report a broken database
 * as a dead link.
 */
private fun Route.reportRoute(costReportSource: CostReportSource) {
  route(REPORT_PATH) {
    secretQueryParams(REPORT_TOKEN_PARAM)
    setLinkHolderPrivacyHeaders()
    get {
      // EXACTLY one token, never the first of several. `queryParameters[name]`
      // is `getAll(name).firstOrNull()`, so `?token=junk&token=<live>` was a
      // request we have no reading of, answered by discarding the surplus.
      val presented = call.request.queryParameters.getAll(REPORT_TOKEN_PARAM)
      val token = presented?.singleOrNull()
      val outcome =
        when {
          presented != null && presented.size > 1 -> CostReportOutcome.NotFound(MissReason.REPEATED_TOKEN_PARAM)
          token.isNullOrBlank() -> CostReportOutcome.NotFound(MissReason.BLANK_TOKEN)
          else -> costReportSource.getByShareToken(token).getOrThrow()
        }
      when (outcome) {
        is CostReportOutcome.Found -> {
          call.respondCostReportPage(outcome.profile)
        }

        // The reason is LOGGED, never rendered: the 404 body is byte-identical
        // for every one of these, which is what stops a stranger holding a dead
        // link learning that the token was once real.
        is CostReportOutcome.NotFound -> {
          reportLog.debug("cost report link resolved to nothing: reason=[{}]", outcome.reason)
          call.respondNotFoundPage()
        }
      }
    }
  }
}
