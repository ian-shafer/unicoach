package ed.unicoach.web

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.ChangeEmailResult
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.EmailVerifier
import ed.unicoach.auth.LoginResult
import ed.unicoach.auth.RegisterResult
import ed.unicoach.auth.VerifyEmailResult
import ed.unicoach.common.config.TOKEN_QUERY_PARAM
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.family.AcceptInviteOutcome
import ed.unicoach.family.ClaimOutcome
import ed.unicoach.family.InviteMissReason
import ed.unicoach.family.InvitePreviewOutcome
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterLinkRequestService
import ed.unicoach.family.SupporterService
import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.auth.ACCOUNT_PATH
import ed.unicoach.web.auth.CHANGE_EMAIL_PATH
import ed.unicoach.web.auth.HOME_PATH
import ed.unicoach.web.auth.REGISTER_PATH
import ed.unicoach.web.auth.RESEND_VERIFICATION_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import ed.unicoach.web.auth.SIGN_OUT_PATH
import ed.unicoach.web.auth.VERIFY_EMAIL_PATH
import ed.unicoach.web.auth.WebAccess
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.clearWebSessionCookie
import ed.unicoach.web.auth.resolveWebCaller
import ed.unicoach.web.auth.respondGateRedirect
import ed.unicoach.web.auth.sessionCookieToken
import ed.unicoach.web.auth.setWebSessionCookie
import ed.unicoach.web.common.http.respondSeeOther
import ed.unicoach.web.common.http.setContentSecurityPolicy
import ed.unicoach.web.common.http.setLinkHolderPrivacyHeaders
import ed.unicoach.web.common.logging.secretQueryParams
import ed.unicoach.web.fafsa.FafsaDeadlineSource
import ed.unicoach.web.render.ChangeEmailFormState
import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.FAFSA_PATH
import ed.unicoach.web.render.InviteViewer
import ed.unicoach.web.render.NAME_FIELD
import ed.unicoach.web.render.PASSWORD_FIELD
import ed.unicoach.web.render.REPORT_FIELD
import ed.unicoach.web.render.RegisterFormState
import ed.unicoach.web.render.SIGNUP_DOOR_SECRET_PARAMS
import ed.unicoach.web.render.ShellNav
import ed.unicoach.web.render.SignInFormState
import ed.unicoach.web.render.TOKEN_FIELD
import ed.unicoach.web.render.WHO_PAYS_PATH
import ed.unicoach.web.render.WebNotice
import ed.unicoach.web.render.respondAccountPage
import ed.unicoach.web.render.respondChangeEmailPage
import ed.unicoach.web.render.respondCostReportPage
import ed.unicoach.web.render.respondFafsaPage
import ed.unicoach.web.render.respondHomePage
import ed.unicoach.web.render.respondInvitePage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.respondPrivacyPage
import ed.unicoach.web.render.respondRegisterPage
import ed.unicoach.web.render.respondServiceUnavailablePage
import ed.unicoach.web.render.respondSignInPage
import ed.unicoach.web.render.respondTermsPage
import ed.unicoach.web.render.respondVerifyEmailConfirm
import ed.unicoach.web.render.respondVerifyEmailHold
import ed.unicoach.web.render.respondVerifyEmailResult
import ed.unicoach.web.render.respondWhoPaysPage
import ed.unicoach.web.report.CostReportOutcome
import ed.unicoach.web.report.CostReportSource
import ed.unicoach.web.report.MissReason
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

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
 * The path a supporter invite link points at (RFC 232). Named for the same
 * reason [REPORT_PATH] is: half of a contract the compiler cannot see, where the
 * other half is `supporterInvite.inviteUrlBase` in `service.conf`.
 */
const val INVITE_PATH = "/invite"

/**
 * The report page's own form target (RFC 254): a signed-in reader asks to
 * connect with the student who shared the report.
 */
const val REPORT_CONNECT_PATH = "$REPORT_PATH/connect"

/**
 * The report page for [rawToken], as a same-origin path — where a signed-in
 * reader holding a report token is sent instead of a second signup form (RFC
 * 254). Percent-encoded for the reason [ed.unicoach.web.render.pathCarryingToken] gives.
 *
 * Not built by `pathCarryingToken`, on purpose: that joins the INVITE door key
 * [TOKEN_FIELD], and this joins [REPORT_TOKEN_PARAM], the key `:service` mints
 * the report link with. The two spell `token` today; they are different
 * contracts, and coupling them would let a rename of one move the other.
 */
fun reportPagePath(rawToken: String): String = "$REPORT_PATH?$REPORT_TOKEN_PARAM=${rawToken.encodeURLParameter()}"

/**
 * Where a signed-in reader following a signup door in [parameters] lands (RFC
 * 254): back to the ONE report token the door carries, else [ACCOUNT_PATH]. The
 * token is read by [usableReportToken], the rule the doors themselves read it
 * by, so a value not in the mint shape is never turned into a redirect.
 */
fun signedInDoorLanding(parameters: Parameters): String =
  presentedToken(parameters.getAll(REPORT_FIELD)).usableReportToken()?.let(::reportPagePath) ?: ACCOUNT_PATH

/**
 * The user-agent substring that means "iPhone".
 *
 * The open-in-app affordance on the verification result page is iPhone-only, so
 * this is the one device fact the web tier reads — and it is named rather than
 * typed inside a handler, like every other marker in this file.
 */
private const val IPHONE_USER_AGENT_TOKEN = "iPhone"

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

/** The auth routes' own log: WHY a refusal happened, which the rendered page must never say. */
private val authLog = LoggerFactory.getLogger("ed.unicoach.web.Auth")

/** The invite routes' own log: WHY a link resolved to nothing or an accept was refused, never rendered. */
private val inviteLog = LoggerFactory.getLogger("ed.unicoach.web.SupporterInvite")

/** The report claim's own log (RFC 254): WHY a claim made no request, never rendered and never the token. */
private val claimLog = LoggerFactory.getLogger("ed.unicoach.web.ReportClaim")

/**
 * A year, as a DURATION. `max-age` is counted in seconds, so the header spells
 * this as `31536000` — a number nobody reads as "a year" without doing the
 * arithmetic, and one that can be edited into ten years or ten hours with
 * nothing to notice. The unit lives at the boundary that requires it, below.
 *
 * A year is SAFE only because the bundle's name contains a hash of its own bytes
 * (RFC 225 D-D): a new build is a NEW URL, so a browser holding this response
 * forever is holding the only content that URL will ever have. The same header
 * on a stable name would pin last week's code onto every returning visitor for a
 * year, with no way to recall it.
 */
val IMMUTABLE_BUNDLE_MAX_AGE: Duration = 365.days

/**
 * The header value, with the seconds DERIVED from [IMMUTABLE_BUNDLE_MAX_AGE] at
 * the one place a primitive is required — HTTP's own wire format.
 *
 * `StaticAssetsTest` deliberately types the expected header out in full rather
 * than reading this: a test that compares a constant with itself proves nothing
 * about the promise being made.
 */
val IMMUTABLE_BUNDLE_CACHE_CONTROL = "public, max-age=${IMMUTABLE_BUNDLE_MAX_AGE.inWholeSeconds}, immutable"

/**
 * Serves the hashed bundle — and ONLY it — as cacheable forever.
 *
 * Route-scoped on the static mount and keyed on the request path, because the
 * mount answers for every asset under `static/` and the claim holds for exactly
 * one of them. `/site.css` is a stable name whose bytes change in place, so it
 * keeps today's behaviour: no `Cache-Control` at all, revalidated normally.
 *
 * Stamped in the `Setup` phase, beside `setLinkHolderPrivacyHeaders` and for the
 * same reason — the static mount writes its response from its own path, and a
 * header set after that is a header the client never sees.
 */
private fun Route.cacheImmutableBundle() {
  install(ImmutableBundleCachePlugin)
}

/** The plugin behind [cacheImmutableBundle]; installed only on the static mount. */
private val ImmutableBundleCachePlugin =
  createRouteScopedPlugin("ImmutableBundleCache") {
    on(CallSetup) { call ->
      if (call.request.path() == WebAssets.MAIN) {
        call.response.header(HttpHeaders.CacheControl, IMMUTABLE_BUNDLE_CACHE_CONTROL)
      }
    }
  }

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
  fafsaDeadlines: FafsaDeadlineSource,
  openInAppUrl: String?,
  authService: AuthService,
  emailVerificationService: EmailVerificationService,
  sessionConfig: WebSessionConfig,
  supporterInviteService: SupporterInviteService,
  supporterService: SupporterService,
  linkRequestService: SupporterLinkRequestService,
) {
  install(StatusPages) {
    status(HttpStatusCode.NotFound) { call, _ ->
      call.respondNotFoundPage(call.shellNavOrSignedOut(authService, sessionConfig))
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
      // The nav is resolved DEFENSIVELY here and nowhere else. This handler is
      // reached because something already failed, and the likeliest something is
      // the database — the one collaborator `shellNav` reads. A second throw
      // inside the error page would replace the branded 503 with a bare Ktor
      // failure, so an unresolvable caller falls back to the signed-out header.
      call.respondServiceUnavailablePage(call.shellNavOrSignedOut(authService, sessionConfig))
    }
  }

  routing {
    // At the ROOT, above every route, so the policy rides the rendered pages,
    // the static mount AND the StatusPages 404/503 alike (RFC 210). Installed on
    // a page route it would have covered the pages and missed exactly the
    // responses an attacker reaches first.
    setContentSecurityPolicy()

    healthRoute()
    // Every page render takes the nav RESOLVED FOR THIS REQUEST. The sign-out
    // control lives in the shared header, so a page that renders the signed-out
    // header is a page a signed-in family cannot sign out from — and is a page
    // that offers them a door they are already through.
    get(HOME_PATH) { call.respondHomePage(call.shellNav(authService, sessionConfig)) }
    get("/terms") { call.respondTermsPage(call.shellNav(authService, sessionConfig)) }
    get("/privacy") { call.respondPrivacyPage(call.shellNav(authService, sessionConfig)) }
    get(WHO_PAYS_PATH) { call.respondWhoPaysPage(call.shellNav(authService, sessionConfig)) }
    get(FAFSA_PATH) { call.respondFafsaPage(fafsaDeadlines, call.shellNav(authService, sessionConfig)) }

    // Side-effect-free confirm step: a non-blank token renders the confirm form.
    //
    // WITHOUT a token the page means something different, and RFC 224 makes the
    // difference explicit: for a signed-in caller this is the verification HOLD
    // screen the auth gate redirects an unverified family to, and for a
    // signed-out visitor there is nothing to hold and nothing to verify, so the
    // sign-in form is the honest answer. Before this slice there was no session
    // to ask about and the page rendered "Link not valid" for both.
    get(VERIFY_EMAIL_PATH) {
      // EXACTLY one token, never the first of several, matching /report: a URL
      // carrying two tokens is a request we have no reading of, so a repeated
      // key falls into the hold-screen arm rather than silently discarding the
      // surplus.
      val token = presentedToken(call.request.queryParameters.getAll(TOKEN_FIELD)).usableToken()
      if (token == null) {
        call.respondVerificationHold(authService, sessionConfig)
      } else {
        call.respondVerifyEmailConfirm(token, call.shellNav(authService, sessionConfig))
      }
    }

    // The one pre-RFC-224 state-mutating route: it burns a single-use token. A
    // blank token short-circuits to InvalidToken with no verify call; otherwise
    // the verifier consumes the token in-process and the mapped outcome is
    // rendered. Unchanged here, except that the same-origin guard now covers it
    // like every other state-changing request (D-C); the token in the body
    // remains its actual authorisation.
    post(VERIFY_EMAIL_PATH) {
      // Read DEFENSIVELY, and EXACTLY one token: a body that is not a form at
      // all, or that carries zero or two `token` fields, is a request we have no
      // reading of and renders the same page as any bad token — never a 503.
      val token = presentedToken(call.formParametersOrNull()?.getAll(TOKEN_FIELD)).usableToken()
      val isIPhone =
        call.request
          .userAgent()
          ?.contains(IPHONE_USER_AGENT_TOKEN) ?: false
      val outcome =
        if (token == null) {
          VerifyEmailOutcome.InvalidToken
        } else {
          emailVerifier.verify(token).toOutcome()
        }
      call.respondVerifyEmailResult(outcome, openInAppUrl, isIPhone, call.shellNav(authService, sessionConfig))
    }

    authRoutes(authService, emailVerificationService, sessionConfig, supporterInviteService, supporterService, linkRequestService)

    reportRoute(costReportSource, authService, sessionConfig, linkRequestService)

    inviteRoute(supporterInviteService, authService, sessionConfig)

    // ONE name for the classpath package this mount serves, read from
    // [WebAssets] — the same constant [WebAssets] resolves the packaged bundle
    // against. Two literals here would be free to disagree, and the disagreement
    // shows up as a 404 for the script, not as a failure.
    staticResources("/", WebAssets.STATIC_RESOURCE_PACKAGE).cacheImmutableBundle()
  }
}

/**
 * Accept the invite a just-authenticated recipient presented, and say which
 * notice their next page must carry (RFC 232).
 *
 * `null` means NO invite was presented, so the caller redirects to its ordinary
 * destination. Everything else is an answer about the invite.
 *
 * The outcome is BOUND, not discarded. `SupporterInviteService.accept` returns its three refusals
 * as SUCCESSFUL values, so `getOrThrow()` unwraps the `Result` and hands the
 * refusal straight back: a dead token, a self-addressed invite or a supporter
 * with no student profile used to redirect with "You are now connected" and no
 * link behind it.
 *
 * A refusal gets ONE sentence for all three cases, exactly as `POST /invite`
 * serves one body for all three: naming which one fired would make the door an
 * enumeration oracle for a token the holder may not own. The reason is LOGGED.
 *
 * This is deliberately NOT one transaction with the registration or the login
 * above it — see the acceptance mechanics in RFC 232. A fault here leaves the
 * account created and the invite still LIVE, which is the recoverable half:
 * the recipient opens the same link again. That recovery is what the FAULT arm
 * below renders: the caller is signed in and their account is made, so a 500
 * would strand them on an error page instead of the sentence that tells them
 * to open the link again. It is logged at ERROR, because unlike a refusal it
 * is nobody's ordinary state.
 */
private suspend fun acceptPresentedInvite(
  supporterInviteService: SupporterInviteService,
  presented: PresentedToken,
  recipientUserId: UserId,
  door: String,
): WebNotice? {
  val inviteToken =
    when (presented) {
      // The ONLY silent arm: this request presented no invite at all, so the
      // door redirects to its ordinary destination.
      PresentedToken.Absent -> {
        return null
      }

      // A token key that carried nothing, and a key carrying two credentials,
      // are both presented invites we have no reading of. Answering them the way
      // an absent one is answered drops a presented credential in silence, where
      // every other refusal in this file says so.
      PresentedToken.Blank -> {
        return refusedPresentedInvite(InviteMissReason.BLANK_TOKEN, recipientUserId, door)
      }

      PresentedToken.Repeated -> {
        return refusedPresentedInvite(InviteMissReason.REPEATED_TOKEN_PARAM, recipientUserId, door)
      }

      is PresentedToken.One -> {
        presented.raw
      }
    }
  val outcome =
    supporterInviteService.accept(inviteToken, recipientUserId).getOrElse { failure ->
      inviteLog.error(
        "supporter invite accept faulted: door=[{}] userId=[{}]",
        door,
        recipientUserId.asString,
        failure,
      )
      return WebNotice.INVITE_NOT_ACCEPTED
    }
  return when (outcome) {
    is AcceptInviteOutcome.Success -> {
      WebNotice.INVITE_ACCEPTED
    }

    is AcceptInviteOutcome.InvalidToken,
    AcceptInviteOutcome.SelfLinkProhibited,
    AcceptInviteOutcome.StudentProfileRequired,
    -> {
      inviteLog.debug(
        "supporter invite accept refused: door=[{}] userId=[{}] outcome=[{}]",
        door,
        recipientUserId.asString,
        outcome,
      )
      WebNotice.INVITE_NOT_ACCEPTED
    }
  }
}

/**
 * A presented invite this door could not even read. Recorded with the DOOR and
 * the CALLER, because "supporter invite accept refused on authentication" is
 * true of both authenticating doors, for any outcome, for any user — and the
 * operator holding a family's complaint has nothing else to join on.
 */
private fun refusedPresentedInvite(
  reason: InviteMissReason,
  recipientUserId: UserId,
  door: String,
): WebNotice {
  inviteLog.debug(
    "supporter invite accept refused: door=[{}] userId=[{}] reason=[{}]",
    door,
    recipientUserId.asString,
    reason,
  )
  return WebNotice.INVITE_NOT_ACCEPTED
}

/**
 * Ask to connect from the report a just-authenticated reader presented (RFC
 * 254), and say which notice their next page must carry. `null` means NO
 * report was presented.
 *
 * The [acceptPresentedInvite] shape: NOT one transaction with the registration
 * or login before it, so a claim that fails never fails the signup — the
 * account exists and the reader can open the report again. Every refusal and
 * every fault is ONE sentence, [WebNotice.CLAIM_NOT_SENT], so the notice says
 * nothing about the student; the reason is LOGGED, the token never.
 */
private suspend fun claimPresentedReport(
  linkRequestService: SupporterLinkRequestService,
  presented: PresentedToken,
  userId: UserId,
  door: String,
): WebNotice? =
  when (presented) {
    PresentedToken.Absent -> null
    PresentedToken.Blank, PresentedToken.Repeated -> refusedPresentedReport(presented, userId, door)
    is PresentedToken.One -> claimReport(linkRequestService, presented.raw, userId, door)
  }

/** A presented report this door could not even read; see [refusedPresentedInvite]. */
private fun refusedPresentedReport(
  presented: PresentedToken,
  userId: UserId,
  door: String,
): WebNotice {
  claimLog.debug("report claim refused: door=[{}] userId=[{}] presented=[{}]", door, userId.asString, presented)
  return WebNotice.CLAIM_NOT_SENT
}

private suspend fun claimReport(
  linkRequestService: SupporterLinkRequestService,
  rawToken: String,
  userId: UserId,
  door: String,
): WebNotice {
  val outcome =
    linkRequestService.claimFromReport(userId, rawToken).getOrElse { failure ->
      claimLog.error("report claim faulted: door=[{}] userId=[{}]", door, userId.asString, failure)
      return WebNotice.CLAIM_NOT_SENT
    }
  return noticeForClaim(outcome, userId, door)
}

private fun noticeForClaim(
  outcome: ClaimOutcome,
  userId: UserId,
  door: String,
): WebNotice =
  when (outcome) {
    is ClaimOutcome.Requested -> {
      claimSentNotice(outcome.awaitingVerification)
    }

    is ClaimOutcome.AlreadyRequested -> {
      claimSentNotice(outcome.awaitingVerification)
    }

    ClaimOutcome.AlreadyConnected, is ClaimOutcome.NotClaimed -> {
      claimLog.debug("report claim made no request: door=[{}] userId=[{}] outcome=[{}]", door, userId.asString, outcome)
      WebNotice.CLAIM_NOT_SENT
    }
  }

/** A request is standing: the student has been asked, or will be once the reader confirms their email. */
private fun claimSentNotice(awaitingVerification: Boolean): WebNotice =
  if (awaitingVerification) WebNotice.CLAIM_PENDING else WebNotice.CLAIM_SENT

/** Where a sign-in lands: the invite's notice wins on `/account`; a claim alone lands where its notice can be read. */
private fun signInLandingUrl(
  inviteNotice: WebNotice?,
  claimNotice: WebNotice?,
  user: User,
): String =
  if (inviteNotice == null && claimNotice != null) {
    claimLandingUrl(claimNotice, verified = user.emailVerifiedAt != null)
  } else {
    noticeUrl(ACCOUNT_PATH, inviteNotice)
  }

/**
 * Where a claim's notice is read: `/account` for a verified reader, and the
 * verification hold screen for an unverified one — `/account` would bounce them
 * there and drop the notice on the way.
 */
private fun claimLandingUrl(
  notice: WebNotice,
  verified: Boolean,
): String = noticeUrl(if (verified) ACCOUNT_PATH else VERIFY_EMAIL_PATH, notice)

/**
 * Sign in, register, sign out, resend verification, the signed-in home and
 * change email (RFC 224).
 *
 * Registered in the ONE routing table, like every other public-web route, and
 * deliberately NOT protected here: the auth gate and the same-origin guard are
 * pipeline interceptors installed in `publicWebModule`, so they cover a route
 * whether or not whoever wrote it remembered them. What this block contains is
 * only what each route DOES.
 *
 * Every successful POST answers `303 See Other` — post-redirect-get everywhere,
 * so a reload cannot resubmit a sign-in, a registration or an email change, and
 * the browser's back button does not offer to.
 *
 * Every failing POST re-renders its own form at a status a client can read,
 * carrying the typed values. That re-render is ordinary form handling and is
 * NOT the session form-state rule D12; see the page KDocs and the D12 honesty
 * test in `SignInRoutingTest`.
 */
private fun Route.authRoutes(
  authService: AuthService,
  emailVerificationService: EmailVerificationService,
  sessionConfig: WebSessionConfig,
  supporterInviteService: SupporterInviteService,
  supporterService: SupporterService,
  linkRequestService: SupporterLinkRequestService,
) {
  // `/invite` sends a signed-out recipient to these two doors with the RAW
  // invite token in the query string, so both scope it secret exactly as
  // `/invite` does (RFC 232). Redacting it on `/invite` alone kept the token
  // out of one log line and wrote it into the next one — and redaction covers
  // the LOG only, so both doors also carry `/invite`'s link-holder privacy
  // headers: a URL holding a live single-use credential must not be kept by a
  // shared cache, indexed, or handed on in a `Referer`. The report's own token
  // rides the same two doors on its own key (RFC 254), and is as secret.
  route(SIGN_IN_PATH) {
    secretQueryParams(*SIGNUP_DOOR_SECRET_PARAMS.toTypedArray())
    setLinkHolderPrivacyHeaders()

    get {
      // Through the ONE reader, like every other token door in this file:
      // `queryParameters[name]` is `getAll(name).firstOrNull()`, so a link
      // carrying two tokens used to plant the first of them in the hidden field.
      call.respondSignInPage(
        nav = call.shellNav(authService, sessionConfig),
        token = presentedToken(call.request.queryParameters.getAll(TOKEN_FIELD)).usableToken(),
        report = presentedToken(call.request.queryParameters.getAll(REPORT_FIELD)).usableReportToken(),
      )
    }

    post {
      val form =
        call.formParametersOrNull() ?: return@post call.respondSignInPage(
          SignInFormState.Rejected(""),
          call.shellNav(authService, sessionConfig),
        )
      val email = form.formField(EMAIL_FIELD)
      val password = form.formField(PASSWORD_FIELD)
      val presentedInvite = presentedToken(form.getAll(TOKEN_FIELD))
      val presentedReport = presentedToken(form.getAll(REPORT_FIELD))
      if (email == null || password == null) {
        return@post call.respondSignInPage(
          SignInFormState.Rejected(email.orEmpty()),
          call.shellNav(authService, sessionConfig),
          presentedInvite.usableToken(),
          presentedReport.usableReportToken(),
        )
      }

      val result =
        authService
          .login(
            email = email,
            password = password,
            // The CURRENT cookie, so `:service` can revoke the row it names in the
            // same transaction that inserts the new one. Session fixation is
            // handled there and not here (D13): `mintSession` is the only code
            // that knows what "replacing a session" means.
            oldCookieToken = call.request.sessionCookieToken(sessionConfig),
            sessionExpiration = sessionConfig.expiration,
            userAgent = call.request.userAgent(),
            // The socket PEER, not a forwarded header: a header an attacker sets
            // is spoofable by construction, and this value is what an operator
            // reads when they ask "where did this session come from" — a number
            // that misleads them there is worse than none. Behind our TLS proxy
            // the peer is the proxy, which is a known, honest limitation.
            initialIp = call.request.origin.remoteHost,
          ).getOrThrow()

      when (result) {
        is LoginResult.Success -> {
          call.setWebSessionCookie(sessionConfig, result.token)
          val inviteNotice =
            acceptPresentedInvite(supporterInviteService, presentedInvite, result.user.id, SIGN_IN_PATH)
          val claimNotice = claimPresentedReport(linkRequestService, presentedReport, result.user.id, SIGN_IN_PATH)
          call.respondSeeOther(signInLandingUrl(inviteNotice, claimNotice, result.user))
        }

        // All FOUR failures, one answer. The branches are listed rather than
        // folded into an `else` so that a fifth failure case added to
        // `LoginResult` fails to compile here instead of quietly falling into a
        // default — which is how a new branch grows its own distinguishable
        // message and turns the form into an enumeration oracle.
        is LoginResult.InvalidEmail,
        LoginResult.UserNotFound,
        LoginResult.PasswordNotSet,
        LoginResult.PasswordMismatch,
        -> {
          // The reason is LOGGED, never rendered: the page says one sentence for
          // all four (SIGN_IN_ERROR), because naming the failure turns the form
          // into an enumeration oracle. A refusal recorded nowhere leaves a
          // locked-out family and a credential-stuffing run indistinguishable.
          // `rest-server` logs the same fact at the same boundary.
          authLog.info("web sign-in refused: outcome=[{}]", result)
          call.respondSignInPage(
            SignInFormState.Rejected(email),
            call.shellNav(authService, sessionConfig),
            presentedInvite.usableToken(),
            presentedReport.usableReportToken(),
          )
        }
      }
    }
  }

  // Secret-scoped and link-holder private for the same reason as the sign-in
  // door above (RFC 232).
  route(REGISTER_PATH) {
    secretQueryParams(*SIGNUP_DOOR_SECRET_PARAMS.toTypedArray())
    setLinkHolderPrivacyHeaders()

    get {
      // The single-token rule, as on the sign-in door above.
      call.respondRegisterPage(
        nav = call.shellNav(authService, sessionConfig),
        token = presentedToken(call.request.queryParameters.getAll(TOKEN_FIELD)).usableToken(),
        report = presentedToken(call.request.queryParameters.getAll(REPORT_FIELD)).usableReportToken(),
      )
    }

    post {
      val form =
        call.formParametersOrNull() ?: return@post call.respondRegisterPage(
          RegisterFormState.Invalid("", "", emptyList(), emptyList()),
          call.shellNav(authService, sessionConfig),
        )
      val name = form.formField(NAME_FIELD)
      val email = form.formField(EMAIL_FIELD)
      val password = form.formField(PASSWORD_FIELD)
      val presentedInvite = presentedToken(form.getAll(TOKEN_FIELD))
      val presentedReport = presentedToken(form.getAll(REPORT_FIELD))
      if (name == null || email == null || password == null) {
        return@post call.respondRegisterPage(
          RegisterFormState.Invalid(name.orEmpty(), email.orEmpty(), emptyList(), emptyList()),
          call.shellNav(authService, sessionConfig),
          presentedInvite.usableToken(),
          presentedReport.usableReportToken(),
        )
      }

      val result =
        authService
          .register(
            email = email,
            name = name,
            password = password,
            oldCookieToken = call.request.sessionCookieToken(sessionConfig),
            sessionExpiration = sessionConfig.expiration,
            userAgent = call.request.userAgent(),
            // Same peer-over-forwarded-header reasoning as the sign-in call above.
            initialIp = call.request.origin.remoteHost,
          ).getOrThrow()

      when (result) {
        is RegisterResult.Success -> {
          call.setWebSessionCookie(sessionConfig, result.token)
          // To the hold screen, not to /account: registration leaves the address
          // unverified, so /account would immediately bounce them here anyway and
          // the extra redirect would be the family's first impression of the
          // product.
          val inviteNotice =
            acceptPresentedInvite(supporterInviteService, presentedInvite, result.user.id, REGISTER_PATH)
          val claimNotice = claimPresentedReport(linkRequestService, presentedReport, result.user.id, REGISTER_PATH)
          call.respondSeeOther(noticeUrl(VERIFY_EMAIL_PATH, inviteNotice ?: claimNotice))
        }

        is RegisterResult.ValidationFailure -> {
          // BOTH halves: `RegistrationValidator` states every rule as a
          // `FieldError` and leaves `errors` empty, so carrying one half alone
          // renders a 400 that names no problem at all. `rest-server` merges the
          // same two channels at the same boundary (AuthRoutes).
          call.respondRegisterPage(
            RegisterFormState.Invalid(name, email, result.fieldErrors, result.errors),
            call.shellNav(authService, sessionConfig),
            presentedInvite.usableToken(),
            presentedReport.usableReportToken(),
          )
        }

        is RegisterResult.DuplicateEmail -> {
          call.respondRegisterPage(
            RegisterFormState.Duplicate(name, email),
            call.shellNav(authService, sessionConfig),
            presentedInvite.usableToken(),
            presentedReport.usableReportToken(),
          )
        }
      }
    }
  }

  postSignedIn(SIGN_OUT_PATH, authService, sessionConfig) { caller ->
    authService.logout(caller.tokenHash).getOrThrow()
    call.clearWebSessionCookie(sessionConfig)
    call.respondSeeOther(HOME_PATH)
  }

  postSignedIn(RESEND_VERIFICATION_PATH, authService, sessionConfig) { caller ->
    // The OUTCOME is logged, never rendered: `Sent` and `AlreadyVerified` get
    // the same page, mirroring the API's single 204, because rendering the
    // difference hands whoever holds the session a fact about the account they
    // did not have before pressing the button. Unrecorded, "no mail arrived"
    // and "already verified" are the same report.
    val outcome = emailVerificationService.resend(caller.user).getOrThrow()
    authLog.info("verification resend for user=[{}] outcome=[{}]", caller.user.id, outcome)
    // Redirected rather than rendered, and this is the one route where
    // post-redirect-get is more than tidiness: rendering the confirmation from
    // the POST leaves a reload re-submitting it, and a reload of THIS form sends
    // another email. The confirmation is rendered by the GET that follows.
    call.respondSeeOther(noticeUrl(VERIFY_EMAIL_PATH, WebNotice.RESENT))
  }

  getVerified(ACCOUNT_PATH, authService, sessionConfig) { caller ->
    val isSupporter = supporterService.find(caller.user.id).getOrThrow() != null
    call.respondAccountPage(caller.user, isSupporter, notice = call.requestedNotice())
  }

  getVerified(CHANGE_EMAIL_PATH, authService, sessionConfig) { caller ->
    call.respondChangeEmailPage(caller.user)
  }

  postVerified(CHANGE_EMAIL_PATH, authService, sessionConfig) { caller ->
    // A body that is not a form, or that carries zero or two `email` fields, is
    // not this form's submission: re-render at 400 without inventing a rule the
    // web tier does not own. The empty message says no `:service` rule fired
    // because `:service` was never called.
    val newEmail = call.formParametersOrNull()?.formField(EMAIL_FIELD)
    if (newEmail == null) {
      return@postVerified call.respondChangeEmailPage(caller.user, ChangeEmailFormState.Invalid("", ""))
    }

    when (val result = authService.changeEmail(caller.user, newEmail).getOrThrow()) {
      // To the VERIFICATION HOLD SCREEN, not to /account. `:service` clears
      // `email_verified_at` as part of the change, so the gate classes /account
      // as VERIFIED and bounces the family to /verify-email — dropping the query
      // string, and with it the confirmation, on the way. The notice rides to
      // the page they actually land on, and the message ("Check the new address
      // for a verification link") was written for that page anyway.
      is ChangeEmailResult.Success -> {
        call.respondSeeOther(noticeUrl(VERIFY_EMAIL_PATH, WebNotice.EMAIL_CHANGED))
      }

      // :service's own message, carried verbatim by the state that renders it
      // (see ChangeEmailFormState.Invalid); the duplicate sentence is the page's.
      is ChangeEmailResult.ValidationFailure -> {
        call.respondChangeEmailPage(caller.user, ChangeEmailFormState.Invalid(newEmail, result.message))
      }

      is ChangeEmailResult.DuplicateEmail -> {
        call.respondChangeEmailPage(caller.user, ChangeEmailFormState.Duplicate(newEmail))
      }
    }
  }
}

/**
 * The query key a one-off confirmation rides in.
 *
 * A KEY rather than the message itself, because a message passed through the
 * URL is a message an attacker can write: a link to
 * `/account?notice=<their+text>` would render their sentence inside our shell,
 * with our chrome around it. The key selects from the closed set [WebNotice];
 * an unknown key renders nothing at all.
 *
 * Declared ABOVE the routes that use them: Kotlin resolves a top-level property
 * only after its declaration within one file.
 */
private const val NOTICE_QUERY_PARAM = "notice"

/**
 * The confirmation this request asks for, or `null` for an absent or unknown
 * key. One reader, so the key and its closed value set are read in one place.
 */
private fun ApplicationCall.requestedNotice(): WebNotice? = request.queryParameters[NOTICE_QUERY_PARAM]?.let(WebNotice::fromKey)

/**
 * [path] carrying [notice], or [path] alone when there is nothing to say: the
 * WRITER half of [requestedNotice], so the query key is spelled once per side.
 *
 * Every door that redirects with a confirmation goes through here. Five sites
 * built the same string by hand, two of them repeating the same
 * `if (notice == null)` in two different shapes.
 */
private fun noticeUrl(
  path: String,
  notice: WebNotice?,
): String = if (notice == null) path else "$path?$NOTICE_QUERY_PARAM=${notice.key}"

/**
 * The submitted form, or `null` when the body was not one.
 *
 * `receiveParameters` throws for a body that is not form-encoded, and an
 * uncaught throw here is answered by the branded 503 — a server fault page for
 * a malformed client request. The CALLER re-renders its own form instead,
 * because which form to show is the route's knowledge and not this helper's.
 */
private suspend fun ApplicationCall.formParametersOrNull(): Parameters? =
  runCatching { receiveParameters() }
    .onFailure { cause -> publicWebErrorLog.debug("unreadable form body on [{}]", request.path(), cause) }
    .getOrNull()

/**
 * The one value [name] carries in this form body.
 *
 * `null` for a field that is absent OR repeated — neither is a submission of the
 * form this module rendered, and `.orEmpty()` used to make both
 * indistinguishable from a family leaving the box blank. A blank box posts a
 * blank VALUE, which is a real submission and is passed through to `:service`;
 * an absent or repeated key is a request shape, and is re-rendered instead.
 */
private fun Parameters.formField(name: String): String? = getAll(name)?.singleOrNull()

/**
 * Which header THIS request gets.
 *
 * The sign-out control lives in the shared header (RFC 224), so the header is
 * the only thing on the site that can sign a family out — and a page rendering
 * the signed-out header is a page they cannot leave and a page that offers them
 * a door they are already through. Every responder therefore takes a nav
 * resolved here, open pages included: the gate resolves no caller on an open
 * path, so nothing else in the pipeline can know.
 *
 * Cheap by construction: on a gated path the gate already resolved the caller
 * and `resolveWebCaller` reads it back off `call.attributes`.
 */
private suspend fun ApplicationCall.shellNav(
  authService: AuthService,
  sessionConfig: WebSessionConfig,
): ShellNav = if (resolveWebCaller(authService, sessionConfig) != null) ShellNav.SignedIn else ShellNav.SignedOut

/**
 * [shellNav] for every page rendered BECAUSE something already failed.
 *
 * Those handlers — the 503 catch-all, the 404 status handler and the same-origin
 * guard's 403 — run when the likeliest fault is the database, which is the one
 * collaborator resolving a caller reads. Throwing there would replace the
 * branded page with a bare Ktor failure, so a fault folds to the signed-out
 * header and is logged rather than rendered.
 */
internal suspend fun ApplicationCall.shellNavOrSignedOut(
  authService: AuthService,
  sessionConfig: WebSessionConfig,
): ShellNav =
  runCatching { shellNav(authService, sessionConfig) }
    .onFailure { cause ->
      publicWebErrorLog.warn(
        "could not resolve the caller for the error page's header: method=[{}] path=[{}]",
        request.httpMethod.value,
        request.path(),
        cause,
      )
    }.getOrDefault(ShellNav.SignedOut)

/**
 * `GET /verify-email` with NO token: the hold screen the auth gate redirects an
 * unverified family to.
 *
 * A named helper because the PATH means two different things and the handler
 * above chooses between them; without this, one handler held a token-confirm
 * page, a signed-in hold screen and a signed-out redirect, and resolved a
 * session and a notice key on the way.
 *
 * A signed-out visitor has nothing to hold and nothing to verify, so the
 * sign-in form is the honest answer. Before RFC 224 there was no session to ask
 * about and the page rendered "Link not valid" to everyone.
 */
private suspend fun ApplicationCall.respondVerificationHold(
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  val caller = resolveWebCaller(authService, sessionConfig) ?: return respondGateRedirect(SIGN_IN_PATH)
  respondVerifyEmailHold(caller.user, notice = requestedNotice())
}

/**
 * Registers a `GET` that only a signed-in caller reaches, and HANDS the handler
 * that caller.
 *
 * The auth gate still runs ahead of routing; this makes the gate's guarantee
 * visible in the handler's TYPE instead of re-asserting it at runtime. Before
 * this, a handler asked for the caller and a helper threw
 * `IllegalStateException` when there was none — so "signed out", the most
 * ordinary state a public web app has, was one mis-typed `mapPathToAccess` entry away
 * from being rendered as the branded 503. Two independent statements of "which
 * routes need a caller" had to agree, and nothing but a test list checked that
 * they did.
 *
 * Now there is no branch to forget: a handler cannot be written without a
 * caller, and a route that needs one cannot be registered without the resolution
 * that produces it. The signed-out arm answers the DOMAIN answer the gate
 * already gives — the sign-in form — rather than an error page.
 *
 * The resolution is free on a gated path: the gate resolved it for this request
 * and `resolveWebCaller` reads it back off `call.attributes`.
 */
private fun Route.getSignedIn(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = get(path) { handleAccess(WebAccess.SIGNED_IN, authService, sessionConfig, handler) }

/** The `POST` half of [getSignedIn]; same contract, same reasoning. */
private fun Route.postSignedIn(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = post(path) { handleAccess(WebAccess.SIGNED_IN, authService, sessionConfig, handler) }

/**
 * [getSignedIn] for a route that also needs a VERIFIED address.
 *
 * This exists because the pre-routing gate cannot be the authority. It
 * classifies `call.request.path()` — the raw bytes — and Ktor routes on
 * something else: empty segments dropped, every segment percent-decoded. So
 * `GET //account` and `GET /%61ccount` reached this very handler while the gate
 * read them as OPEN and skipped the verification check, and the old
 * `getSignedIn` re-asserted only that SOME caller existed. An unverified family
 * rendered the account page, and `POST //account/email` actually changed their
 * address — from the screen this slice promises to hold them at.
 *
 * The fix is not a better normaliser. A string-match against a path you did not
 * resolve is the defect whatever the spelling rules are, so the access class is
 * DECLARED where the route is declared and enforced where Ktor has already said
 * which route this is.
 */
private fun Route.getVerified(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = get(path) { handleAccess(WebAccess.VERIFIED, authService, sessionConfig, handler) }

/** The `POST` half of [getVerified]; same contract, same reasoning. */
private fun Route.postVerified(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = post(path) { handleAccess(WebAccess.VERIFIED, authService, sessionConfig, handler) }

/**
 * The one admission check the four wrappers share: resolve, refuse or hand the
 * handler its caller.
 *
 * Each refusal is the DOMAIN answer the gate gives for the same state — the
 * sign-in form for no session, the hold screen for an unverified one — rather
 * than an error page, so a caller who arrives by an unusual spelling reads
 * exactly what a caller who arrives by the usual one reads.
 *
 * Free on a path the gate already classified: `resolveWebCaller` reads the
 * caller back off `call.attributes`.
 */
private suspend fun RoutingContext.handleAccess(
  access: WebAccess,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) {
  val caller = call.resolveWebCaller(authService, sessionConfig)
  if (caller == null) return call.respondGateRedirect(SIGN_IN_PATH)
  if (access == WebAccess.VERIFIED && caller.user.emailVerifiedAt == null) {
    return call.respondGateRedirect(VERIFY_EMAIL_PATH)
  }
  handler(caller)
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
 * out of an index that reads only the response head, and `same-origin` (RFC 254,
 * superseding D-H's `no-referrer`) keeps the token out of the `Referer` of any
 * link a parent follows off the site, while letting the page's own form POST
 * carry a real `Origin` the same-origin guard can admit.
 *
 * After the report the page makes ONE offer, chosen by who is reading (RFC
 * 254): the reader is resolved here and handed to the port, which decides the
 * [ed.unicoach.family.ClaimView] in the report's own session.
 *
 * A missing, blank, unknown or revoked token renders the SAME branded 404 as any
 * unmatched route — one page, one body, no way to learn from it that a token was
 * once real. A read fault throws and is rendered as the branded 503 by
 * `StatusPages`, never folded into the 404, which would report a broken database
 * as a dead link.
 */
private fun Route.reportRoute(
  costReportSource: CostReportSource,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  linkRequestService: SupporterLinkRequestService,
) {
  route(REPORT_PATH) {
    secretQueryParams(REPORT_TOKEN_PARAM)
    setLinkHolderPrivacyHeaders()
    get {
      // EXACTLY one token, never the first of several. `queryParameters[name]`
      // is `getAll(name).firstOrNull()`, so `?token=junk&token=<live>` was a
      // request we have no reading of, answered by discarding the surplus.
      when (val presented = presentedToken(call.request.queryParameters.getAll(REPORT_TOKEN_PARAM))) {
        PresentedToken.Repeated -> {
          call.respondReportMiss(MissReason.REPEATED_TOKEN_PARAM, authService, sessionConfig)
        }

        PresentedToken.Absent,
        PresentedToken.Blank,
        -> {
          call.respondReportMiss(MissReason.BLANK_TOKEN, authService, sessionConfig)
        }

        is PresentedToken.One -> {
          call.respondPresentedReport(costReportSource, presented.raw, authService, sessionConfig)
        }
      }
    }
  }

  // A signed-in reader asks to connect from the page (RFC 254). SIGNED_IN, not
  // VERIFIED, like `POST /invite`: an unverified reader's request waits, unseen,
  // until they confirm their address. The token rides in the body on its own
  // key, and the query is scoped secret too, so a hand-built URL is not logged.
  route(REPORT_CONNECT_PATH) {
    secretQueryParams(REPORT_FIELD)
    post {
      handleAccess(WebAccess.SIGNED_IN, authService, sessionConfig) { caller ->
        val presented = presentedToken(call.formParametersOrNull()?.getAll(REPORT_FIELD))
        val notice =
          claimPresentedReport(linkRequestService, presented, caller.user.id, REPORT_CONNECT_PATH)
            ?: WebNotice.CLAIM_NOT_SENT
        call.respondSeeOther(claimLandingUrl(notice, verified = caller.user.emailVerifiedAt != null))
      }
    }
  }
}

/** The report behind [rawToken], with the one offer chosen by who is reading it, or the miss. */
private suspend fun ApplicationCall.respondPresentedReport(
  costReportSource: CostReportSource,
  rawToken: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  val viewer = resolveWebCaller(authService, sessionConfig)?.user?.id
  when (val outcome = costReportSource.getByShareToken(rawToken, viewer).getOrThrow()) {
    is CostReportOutcome.Found -> {
      respondCostReportPage(
        profile = outcome.profile,
        nav = if (viewer != null) ShellNav.SignedIn else ShellNav.SignedOut,
        claim = outcome.claim,
        token = rawToken,
      )
    }

    is CostReportOutcome.NotFound -> {
      respondReportMiss(outcome.reason, authService, sessionConfig)
    }
  }
}

/**
 * The reason is LOGGED, never rendered: the 404 body is byte-identical for
 * every miss, which is what stops a stranger holding a dead link learning that
 * the token was once real.
 */
private suspend fun ApplicationCall.respondReportMiss(
  reason: MissReason,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  reportLog.debug("cost report link resolved to nothing: reason=[{}]", reason)
  respondNotFoundPage(shellNav(authService, sessionConfig))
}

/**
 * `GET /invite?token=<raw>` and `POST /invite` — the supporter invite accept
 * page (RFC 232).
 *
 * The GET is OPEN and side-effect free: it previews the invite and renders the
 * ceiling statement before any sign-in or sign-up, so a scanner prefetch of the
 * link cannot burn the single-use token. The POST is SIGNED_IN but NOT VERIFIED
 * (correction X8): a parent or student can accept before verifying their email,
 * or the flow deadlocks behind `EmailVerificationGate`.
 *
 * A missing, blank, unknown, consumed, expired or revoked token renders the SAME
 * branded 404 as any unmatched route — one body, no oracle that a token was once
 * real, the `/report` precedent.
 */
private fun Route.inviteRoute(
  supporterInviteService: SupporterInviteService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  route(INVITE_PATH) {
    secretQueryParams(TOKEN_FIELD)
    setLinkHolderPrivacyHeaders()
    get {
      // EXACTLY one token, never the first of several — the same rule as /report,
      // and branched on the TYPE so every member of it is named here.
      when (val presented = presentedToken(call.request.queryParameters.getAll(TOKEN_FIELD))) {
        // Two tokens is not "no token": an operator reading this line has to be
        // able to tell a truncated link from a link pasted twice.
        PresentedToken.Repeated -> {
          call.respondInviteMiss(InviteMissReason.REPEATED_TOKEN_PARAM, authService, sessionConfig)
        }

        PresentedToken.Absent,
        PresentedToken.Blank,
        -> {
          call.respondInviteMiss(InviteMissReason.BLANK_TOKEN, authService, sessionConfig)
        }

        is PresentedToken.One -> {
          call.respondPreviewedInvite(presented.raw, supporterInviteService, authService, sessionConfig)
        }
      }
    }

    post {
      // SIGNED_IN but NOT VERIFIED (correction X8), through the same wrapper the
      // auth routes use so the access class is stated once rather than resolved
      // by hand.
      handleAccess(WebAccess.SIGNED_IN, authService, sessionConfig) { caller ->
        val token =
          when (val presented = presentedToken(call.formParametersOrNull()?.getAll(TOKEN_FIELD))) {
            // Every other miss on this door logs WHY before rendering the
            // identical page; that is the stated reason the page can be uniform,
            // so this arm is not the one silent one.
            PresentedToken.Repeated -> {
              call.respondInviteMiss(InviteMissReason.REPEATED_TOKEN_PARAM, authService, sessionConfig)
              return@handleAccess
            }

            PresentedToken.Absent,
            PresentedToken.Blank,
            -> {
              call.respondInviteMiss(InviteMissReason.BLANK_TOKEN, authService, sessionConfig)
              return@handleAccess
            }

            is PresentedToken.One -> {
              presented.raw
            }
          }
        val outcome = supporterInviteService.accept(token, caller.user.id).getOrThrow()
        when (outcome) {
          is AcceptInviteOutcome.Success -> {
            call.respondSeeOther(noticeUrl(ACCOUNT_PATH, WebNotice.INVITE_ACCEPTED))
          }

          is AcceptInviteOutcome.InvalidToken,
          AcceptInviteOutcome.SelfLinkProhibited,
          AcceptInviteOutcome.StudentProfileRequired,
          -> {
            inviteLog.debug(
              "supporter invite accept refused: door=[{}] userId=[{}] outcome=[{}]",
              INVITE_PATH,
              caller.user.id.asString,
              outcome,
            )
            call.respondNotFoundPage(call.shellNav(authService, sessionConfig))
          }
        }
      }
    }
  }
}

/**
 * The invite behind [token], rendered for whoever is reading it.
 *
 * WHO is reading decides what the page offers. The inviter's own accept can only
 * be refused (`SelfLinkProhibited`), and that refusal renders the same branded
 * 404 a dead token gets — so offering them the button tells the person who
 * minted a LIVE link that it is dead.
 */
private suspend fun ApplicationCall.respondPreviewedInvite(
  token: String,
  supporterInviteService: SupporterInviteService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  when (val outcome = supporterInviteService.preview(token).getOrThrow()) {
    is InvitePreviewOutcome.Valid -> {
      val caller = resolveWebCaller(authService, sessionConfig)
      respondInvitePage(
        inviterName = outcome.inviterName,
        inviterRole = outcome.inviterRole,
        relationship = outcome.relationship,
        token = token,
        viewer = inviteViewer(caller?.user?.id, outcome.inviterUserId),
      )
    }

    is InvitePreviewOutcome.Invalid -> {
      respondInviteMiss(outcome.reason, authService, sessionConfig)
    }
  }
}

/** WHO is reading the invite, which is what decides the action the page offers. */
private fun inviteViewer(
  callerUserId: UserId?,
  inviterUserId: UserId,
): InviteViewer =
  when (callerUserId) {
    null -> InviteViewer.SIGNED_OUT
    inviterUserId -> InviteViewer.INVITER
    else -> InviteViewer.SIGNED_IN
  }

/**
 * The reason is LOGGED, never rendered: the 404 body is byte-identical for every
 * dead token, which is what stops a stranger holding one learning that it was
 * ever real.
 */
private suspend fun ApplicationCall.respondInviteMiss(
  reason: InviteMissReason,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  inviteLog.debug("supporter invite link resolved to nothing: reason=[{}]", reason)
  respondNotFoundPage(shellNav(authService, sessionConfig))
}
