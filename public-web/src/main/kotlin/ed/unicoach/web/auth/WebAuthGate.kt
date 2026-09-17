package ed.unicoach.web.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.web.WebSessionConfig
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

/** The signed-in home. */
const val ACCOUNT_PATH = "/account"

/** The change-email form. */
const val CHANGE_EMAIL_PATH = "/account/email"

/** The marketing home. Named like every other path so a rule about it cannot drift from the route. */
const val HOME_PATH = "/"

/**
 * What a path requires of its caller.
 *
 * Three states rather than a boolean, because "signed in" and "signed in AND
 * verified" fail DIFFERENTLY and a family must be sent to a different screen by
 * each: an unauthenticated caller needs the sign-in form, and an unverified one
 * needs the hold screen that names the address the mail went to.
 */
internal enum class WebAccess {
  /** No session needed. Most of public-web: the marketing site, the legal pages, the tokenized report. */
  OPEN,

  /** A live session. No claim about the email. */
  SIGNED_IN,

  /** A live session whose user has a verified email. */
  VERIFIED,
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
 * REGISTERED with — [Route.getVerified] and friends in `Routing.kt` — which
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
    path == ACCOUNT_PATH || path.startsWith("$ACCOUNT_PATH/") -> WebAccess.VERIFIED
    path == SIGN_OUT_PATH || path == RESEND_VERIFICATION_PATH -> WebAccess.SIGNED_IN
    else -> WebAccess.OPEN
  }

/**
 * Pages a signed-in caller is sent past rather than shown.
 *
 * Showing a signed-in family the marketing home page or a second sign-in form
 * is the web equivalent of the iOS app opening on its splash screen: the answer
 * is the app, so this is the closest `web/02` can get to the slice's
 * first-session test before `web/04` exists.
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
 * (`getVerified`/`postVerified` in `Routing.kt`) and runs after resolution.
 */
fun Application.installWebAuthGate(
  authService: AuthService,
  config: WebSessionConfig,
) {
  intercept(ApplicationCallPipeline.Plugins) {
    val access = mapPathToAccess(call.request.path())
    if (access == WebAccess.OPEN) return@intercept

    val caller = call.resolveWebCaller(authService, config)
    if (caller == null) {
      call.respondGateRedirect(SIGN_IN_PATH)
      return@intercept finish()
    }

    if (access == WebAccess.VERIFIED && caller.user.emailVerifiedAt == null) {
      call.respondGateRedirect(VERIFY_EMAIL_PATH)
      return@intercept finish()
    }
  }
}

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
 */
fun Application.installSignedInLanding(
  authService: AuthService,
  config: WebSessionConfig,
) {
  intercept(ApplicationCallPipeline.Plugins) {
    if (call.request.httpMethod != HttpMethod.Get) return@intercept
    if (call.request.path() !in SIGNED_IN_REDIRECTS) return@intercept
    if (call.resolveWebCaller(authService, config) == null) return@intercept

    call.respondGateRedirect(ACCOUNT_PATH)
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
