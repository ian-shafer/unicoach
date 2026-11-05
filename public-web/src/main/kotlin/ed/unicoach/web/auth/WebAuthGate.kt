package ed.unicoach.web.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.student.AccountRole
import ed.unicoach.student.AccountRoleService
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.common.logging.SecretQueryParamsKey
import ed.unicoach.web.render.SIGNUP_DOOR_SECRET_PARAMS
import ed.unicoach.web.signedInDoorLanding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond

/** The sign-in form. */
const val SIGN_IN_PATH = "/sign-in"

/** The registration form. */
const val REGISTER_PATH = "/register"

/** Sign-out. A `POST`, because it changes state and must be guarded. */
const val SIGN_OUT_PATH = "/sign-out"

/** The verification confirm/hold screen. */
const val VERIFY_EMAIL_PATH = "/verify-email"

/** Re-send the verification email. Signed-in, because it acts on the caller's own address. */
const val RESEND_VERIFICATION_PATH = "/verify-email/resend"

/** The account page: the address, the change-email door and, for a parent, where their product is. */
const val ACCOUNT_PATH = "/account"

/** The change-email form. */
const val CHANGE_EMAIL_PATH = "/account/email"

/** Your details (RFC 270): the money profile a student reviews and changes, one answer at a time. */
const val YOUR_DETAILS_PATH = "$ACCOUNT_PATH/details"

/** The chat (RFC 262): the signed-in root, and the prefix of every conversation's own page. */
const val CHAT_PATH = "/chat"

/** My colleges (RFC 271): the student's college list, and the prefix of its search, edit and remove pages. */
const val COLLEGES_PATH = "/colleges"

/**
 * Onboarding (RFC 268): "Who are you here for?" — where every check sends a
 * verified account that is neither a student nor a supporter. The name is
 * RFC 262's, from when the page was the student form alone.
 */
const val STUDENT_SIGNUP_PATH = "/get-started"

/** Onboarding's student form (RFC 268): date of birth, graduation date, and the two optional money answers. */
const val STUDENT_FORM_PATH = "$STUDENT_SIGNUP_PATH/student"

/** Onboarding's supporter answer (RFC 268): a `POST` that makes the caller's supporter row. */
const val SUPPORTER_CHOICE_PATH = "$STUDENT_SIGNUP_PATH/supporter"

/** The marketing home. Named like every other path so a rule about it cannot drift from the route. */
const val HOME_PATH = "/"

/**
 * What a path requires of its caller.
 *
 * Four states rather than a boolean, because each one fails DIFFERENTLY and a
 * family must be sent to a different screen by each: an unauthenticated caller
 * needs the sign-in form, an unverified one needs the hold screen that names
 * the address the mail went to, and one who has not said who they are here for
 * needs onboarding (RFC 268).
 */
internal enum class WebAccess {
  /** No session needed. Most of public-web: the marketing site, the legal pages, the tokenized report. */
  OPEN,

  /** A live session. No claim about the email. */
  SIGNED_IN,

  /** A live session whose user has a verified email. */
  VERIFIED,

  /**
   * [VERIFIED], and the account is a student or a supporter (RFC 268, D-E):
   * the chat and every account page. Onboarding itself is [VERIFIED], or no one
   * could reach it.
   */
  ONBOARDED,
}

/**
 * Which access class the RAW request path looks like — an OPTIMISATION, and
 * NOT the authority.
 *
 * Read that literally. This function matches the bytes the client sent, and
 * Ktor resolves a route from something else: empty segments dropped and every
 * segment percent-DECODED. `//account` and `/%61ccount` therefore reach the
 * `/account` handler while this reads them as OPEN, and no amount of
 * normalising here closes that gap, because the only normaliser that agrees
 * with the router is the router. The authority is the wrapper each route is
 * REGISTERED with — [Route.getVerified] and friends in `RouteAccess.kt` — which
 * runs after Ktor has decided which handler this is.
 *
 * What this buys is the cheap early redirect: a signed-out browser asking for
 * `/account` is turned round before routing, so the closed pages answer in one
 * shape whether they exist yet or not. A path this misses is refused a moment
 * later by its own route, and a path this over-matches is a path that would
 * have been refused anyway.
 *
 * The DEFAULT is [WebAccess.OPEN], and that is a deliberate choice for this
 * module rather than a copied habit. `public-web` is a public marketing and
 * legal site whose catch-all static mount answers every unmatched path; a
 * deny-by-default gate would redirect a plain 404 to the sign-in form and send
 * a crawler asking for `/robots.txt` to a login page. The GATED set is what is
 * closed and enumerable, so it is what is enumerated.
 */
internal fun mapPathToAccess(path: String): WebAccess =
  when {
    path == ACCOUNT_PATH || path.startsWith("$ACCOUNT_PATH/") -> WebAccess.ONBOARDED
    path == CHAT_PATH || path.startsWith("$CHAT_PATH/") -> WebAccess.ONBOARDED
    path == COLLEGES_PATH || path.startsWith("$COLLEGES_PATH/") -> WebAccess.ONBOARDED
    path == STUDENT_SIGNUP_PATH || path.startsWith("$STUDENT_SIGNUP_PATH/") -> WebAccess.VERIFIED
    path == SIGN_OUT_PATH || path == RESEND_VERIFICATION_PATH -> WebAccess.SIGNED_IN
    else -> WebAccess.OPEN
  }

/**
 * Pages a signed-in caller is sent past rather than shown.
 *
 * Showing a signed-in family the marketing home page or a second sign-in form
 * is the web equivalent of the iOS app opening on its splash screen: the answer
 * is the app, and since RFC 262 that means the chat — the signed-in root, as
 * the iOS app opens on it ([signedInDoorLanding]).
 */
private val SIGNED_IN_REDIRECTS = setOf(HOME_PATH, SIGN_IN_PATH, REGISTER_PATH)

/**
 * `public-web`'s auth gate (RFC 224).
 *
 * Modelled on `admin-web`'s `installAdminGate` — one
 * `ApplicationCallPipeline.Plugins` interceptor, ahead of routing — but
 * REDIRECT-based rather than status-based, because this is a browser UI and not
 * a JSON API. Where `rest-server`'s `EmailVerificationGate` answers `403
 * email_not_verified`, this sends the same family to the screen that says what
 * to do next and offers the resend button. Same rule, stated in the medium each
 * client understands.
 *
 * A redirect is the SAFE failure here only because it is paired with the
 * routing table: nothing under `/account` renders without a caller, and the
 * gate `finish()`es rather than falling through.
 *
 * And this interceptor is an OPTIMISATION, not the authority — see [mapPathToAccess].
 * It classifies the raw path, which is not the string Ktor routed on, so the
 * enforcement that decides whether a page renders lives on the ROUTE
 * (`getVerified`/`postVerified` in `RouteAccess.kt`) and runs after resolution.
 */
fun Application.installWebAuthGate(
  authService: AuthService,
  config: WebSessionConfig,
  accountRoles: AccountRoleService,
) {
  intercept(ApplicationCallPipeline.Plugins) {
    val access = mapPathToAccess(call.request.path())
    if (access == WebAccess.OPEN) return@intercept

    val caller = call.resolveWebCaller(authService, config)
    if (caller == null) {
      call.respondGateRedirect(SIGN_IN_PATH)
      return@intercept finish()
    }

    val refusal = caller.gateRefusal(access, accountRoles)
    if (refusal != null) {
      call.respondGateRedirect(refusal)
      finish()
    }
  }
}

/** Where the gate sends [this] caller instead of a page that requires [access], or `null` when the caller may pass. */
private suspend fun WebCaller.gateRefusal(
  access: WebAccess,
  accountRoles: AccountRoleService,
): String? =
  when (access) {
    WebAccess.OPEN, WebAccess.SIGNED_IN -> {
      null
    }

    WebAccess.VERIFIED -> {
      unverifiedRefusal()
    }

    WebAccess.ONBOARDED -> {
      unverifiedRefusal() ?: accountRole(accountRoles).let { role -> role.homePath().takeIf { role == AccountRole.None } }
    }
  }

/** The hold screen, for a caller whose email is not yet verified. */
private fun WebCaller.unverifiedRefusal(): String? = VERIFY_EMAIL_PATH.takeIf { user.emailVerifiedAt == null }

/**
 * Sends a signed-in caller past the pages that only make sense signed out.
 *
 * A SEPARATE interceptor from [installWebAuthGate], because it is not access
 * control: it refuses nobody and it protects nothing. It answers a navigation
 * question, it changes for product reasons rather than security ones, and it
 * used to live three `if`s deep inside the gate's OPEN arm — the arm that means
 * "this gate has nothing to say about this path".
 *
 * Only a SAFE method is redirected. A signed-in caller POSTing to `/sign-in` is
 * signing in again, which `AuthService.login` handles by revoking the old row
 * inside the same transaction; bouncing that POST to `/account` would silently
 * drop a form submission.
 *
 * Installed before the gate, which is where the rule ran before, and the two
 * cannot collide: every path in [SIGNED_IN_REDIRECTS] classes as
 * [WebAccess.OPEN], so the gate has nothing to say about any of them.
 *
 * A signup door that carries ONE report token goes back to that report instead
 * (RFC 254), where a signed-in reader is offered "Ask to connect". A parent who
 * opened the link from Messages can look signed out on the page — the session
 * cookie is `SameSite=Strict` — and be signed in by the time they follow its
 * "Create your account"; sending them to `/account` would drop the token.
 */
fun Application.installSignedInLanding(
  authService: AuthService,
  config: WebSessionConfig,
) {
  intercept(ApplicationCallPipeline.Plugins) {
    if (call.request.httpMethod != HttpMethod.Get) return@intercept
    if (call.request.path() !in SIGNED_IN_REDIRECTS) return@intercept
    if (call.resolveWebCaller(authService, config) == null) return@intercept

    // This answer is made BEFORE routing, so the two doors' route-scoped
    // `secretQueryParams` never runs for it: the redaction set is stamped here,
    // or the request log writes the live token these URLs carry.
    call.attributes.put(SecretQueryParamsKey, SIGNUP_DOOR_SECRET_PARAMS)
    call.respondGateRedirect(signedInDoorLanding(call.request.queryParameters))
    finish()
  }
}

/**
 * A gate redirect, at the status the REQUEST'S METHOD makes safe.
 *
 * `303 See Other` for anything but a safe method, for the same reason every
 * successful POST in this module answers `303`: after a `302 Found` a client may
 * repeat the original METHOD, so a refused `POST /account/email` or
 * `POST /sign-out` could be re-sent as a POST at the sign-in form. `303` is
 * defined to turn the follow-up into a `GET`. A `GET`/`HEAD` keeps `302`, which
 * is what every one of these redirects already was.
 */
internal suspend fun ApplicationCall.respondGateRedirect(location: String) {
  response.header(HttpHeaders.Location, location)
  respond(
    if (request.httpMethod == HttpMethod.Get || request.httpMethod == HttpMethod.Head) {
      HttpStatusCode.Found
    } else {
      HttpStatusCode.SeeOther
    },
  )
}
