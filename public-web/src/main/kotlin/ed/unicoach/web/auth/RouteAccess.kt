package ed.unicoach.web.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.StudentId
import ed.unicoach.student.AccountRole
import ed.unicoach.student.AccountRoleService
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.common.http.SAFE_METHODS
import ed.unicoach.web.render.respondForbiddenPage
import ed.unicoach.web.render.signedInNav
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

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
internal fun Route.getSignedIn(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = get(path) { handleAccess(HandledAccess.SIGNED_IN, authService, sessionConfig, handler) }

/** The `POST` half of [getSignedIn]; same contract, same reasoning. */
internal fun Route.postSignedIn(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = post(path) { handleAccess(HandledAccess.SIGNED_IN, authService, sessionConfig, handler) }

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
internal fun Route.getVerified(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = get(path) { handleAccess(HandledAccess.VERIFIED, authService, sessionConfig, handler) }

/** The `POST` half of [getVerified]; same contract, same reasoning. */
internal fun Route.postVerified(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = post(path) { handleAccess(HandledAccess.VERIFIED, authService, sessionConfig, handler) }

/**
 * [getVerified] for a route that also needs an ONBOARDED account (RFC 268,
 * D-E): one with a student or a supporter row. A verified caller with neither
 * is sent to [STUDENT_SIGNUP_PATH], where they answer "Who are you here for?".
 *
 * Declared where the route is registered and enforced after routing, for
 * [getVerified]'s reason: the pre-routing gate's path match is an
 * optimisation, and no spelling of `/account` may reach a handler around it.
 * The role is read through [accountRole], the same read the gate and the
 * chat's student gate make.
 */
internal fun Route.getOnboarded(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  accountRoles: AccountRoleService,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = get(path) { handleOnboarded(authService, sessionConfig, accountRoles, handler) }

/** The `POST` half of [getOnboarded]; same contract, same reasoning. */
internal fun Route.postOnboarded(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  accountRoles: AccountRoleService,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = post(path) { handleOnboarded(authService, sessionConfig, accountRoles, handler) }

/**
 * [getOnboarded] for a page only a STUDENT has — Your details (RFC 270), My
 * colleges (RFC 271), Your conversations (RFC 272) — handing the handler the
 * caller's [StudentId] too.
 *
 * The onboarding wall admits a supporter, and a supporter has no money profile,
 * no college list and no student conversations, so one is sent where their
 * role lives (`/account`) with nothing read or written; an account with no
 * role is sent to onboarding, as [getOnboarded] sends it. The role check is
 * HERE and not in each handler: a handler cannot be written without the
 * [StudentId], so it cannot forget to ask.
 */
internal fun Route.getStudent(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  accountRoles: AccountRoleService,
  handler: suspend RoutingContext.(WebCaller, StudentId) -> Unit,
) = get(path) { handleStudent(authService, sessionConfig, accountRoles, handler) }

/** The `POST` half of [getStudent]; same contract, same reasoning. */
internal fun Route.postStudent(
  path: String,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  accountRoles: AccountRoleService,
  handler: suspend RoutingContext.(WebCaller, StudentId) -> Unit,
) = post(path) { handleStudent(authService, sessionConfig, accountRoles, handler) }

/**
 * [handleAccess] at [HandledAccess.VERIFIED], then the role check: every refusal
 * the verified class gives — sign-in, the CSRF 403, the hold screen — comes
 * first and reads exactly as it does on a verified route, and only a caller who
 * passes all of them is asked whether they have onboarded.
 */
private suspend fun RoutingContext.handleOnboarded(
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  accountRoles: AccountRoleService,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) = handleAccess(HandledAccess.VERIFIED, authService, sessionConfig) { caller ->
  val role = caller.accountRole(accountRoles)
  if (role == AccountRole.None) call.respondGateRedirect(role.homePath()) else handler(caller)
}

/**
 * [handleAccess] at [HandledAccess.VERIFIED], then one role read: a student is
 * handed to [handler] with their id, and every other role is sent home — the
 * same answer [handleOnboarded] gives an account with no role.
 */
private suspend fun RoutingContext.handleStudent(
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  accountRoles: AccountRoleService,
  handler: suspend RoutingContext.(WebCaller, StudentId) -> Unit,
) = handleAccess(HandledAccess.VERIFIED, authService, sessionConfig) { caller ->
  when (val role = caller.accountRole(accountRoles)) {
    is AccountRole.Student -> handler(caller, role.student.id)
    is AccountRole.Supporter, AccountRole.None -> call.respondGateRedirect(role.homePath())
  }
}

/**
 * This caller's [AccountRole]: the pre-routing gate, the chat's student gate,
 * the onboarding wall and `/get-started` all ask through [AccountRoleService],
 * the one reader, so they cannot disagree about who has onboarded.
 */
internal suspend fun WebCaller.accountRole(accountRoles: AccountRoleService): AccountRole = accountRoles.roleOf(user.id).getOrThrow()

/**
 * Where an account with this role belongs: a student in the chat, a supporter
 * on `/account`, and an account that has not said who it is here for in
 * onboarding. Every redirect that sends an account "home" reads it here, so a
 * new role or a moved home is one edit.
 */
internal fun AccountRole.homePath(): String =
  when (this) {
    is AccountRole.Student -> CHAT_PATH
    is AccountRole.Supporter -> ACCOUNT_PATH
    AccountRole.None -> STUDENT_SIGNUP_PATH
  }

/**
 * The access classes [handleAccess] can enforce on its own. [WebAccess.ONBOARDED]
 * is not one: its role check needs an [AccountRoleService], so it is admitted
 * only through [getOnboarded]/[postOnboarded] and [getStudent]/[postStudent],
 * and leaving it out of this type means a route cannot name it here and skip
 * the wall. [WebAccess.OPEN] needs no wrapper at all.
 */
internal enum class HandledAccess {
  /** A live session. No claim about the email. */
  SIGNED_IN,

  /** A live session whose user has a verified email. */
  VERIFIED,
}

/**
 * The one admission check every wrapper shares: resolve, refuse or hand the
 * handler its caller.
 *
 * Each refusal is the DOMAIN answer the gate gives for the same state — the
 * sign-in form for no session, the hold screen for an unverified one — rather
 * than an error page, so a caller who arrives by an unusual spelling reads
 * exactly what a caller who arrives by the usual one reads.
 *
 * A state-changing request must also echo the session's CSRF synchronizer token
 * (brief 0013 D22, RFC 265), checked HERE so every session-bearing POST is
 * covered by construction — [postSignedIn], [postVerified], the routes that
 * call this directly, and whatever a later slice registers. The method decides,
 * not the wrapper: the safe methods are the closed set, the same-origin
 * guard's own [SAFE_METHODS], so a method nobody listed is checked rather than
 * waved through. A missing or wrong token gets the same branded 403 that guard
 * renders; this is the inner layer of the same defence, and a family reads one
 * answer for both.
 *
 * Free on a path the gate already classified: `resolveWebCaller` reads the
 * caller back off `call.attributes`.
 */
internal suspend fun RoutingContext.handleAccess(
  access: HandledAccess,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
  handler: suspend RoutingContext.(WebCaller) -> Unit,
) {
  val caller = call.resolveWebCaller(authService, sessionConfig)
  if (caller == null) return call.respondGateRedirect(SIGN_IN_PATH)
  if (call.request.httpMethod !in SAFE_METHODS) {
    val refusal = call.csrfRefusal(caller.csrfToken)
    if (refusal != null) {
      // WARN, with the method, the path and WHY, and never the token: a stale
      // page after a fresh sign-in (a MISMATCH) and a request that never carried
      // the form both land here, and the log is the only place the two can be
      // counted apart.
      csrfLog.warn(
        "CSRF token refused [{} {}] for session [{}]: {}",
        call.request.httpMethod.value,
        call.request.path(),
        caller.session.id.value,
        refusal,
      )
      return call.respondForbiddenPage(caller.signedInNav)
    }
  }
  when (access) {
    HandledAccess.SIGNED_IN -> {
      Unit
    }

    HandledAccess.VERIFIED -> {
      if (caller.user.emailVerifiedAt == null) return call.respondGateRedirect(VERIFY_EMAIL_PATH)
    }
  }
  handler(caller)
}

private val csrfLog = LoggerFactory.getLogger("ed.unicoach.web.auth.Csrf")
