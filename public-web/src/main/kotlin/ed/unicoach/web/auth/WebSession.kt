package ed.unicoach.web.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.common.http.SessionCookieLifetime
import ed.unicoach.web.common.http.clearSharedSessionCookie
import ed.unicoach.web.common.http.setSharedSessionCookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.AttributeKey

/**
 * The caller behind a `public-web` request: the user it belongs to and the hash
 * of the token that resolved it.
 *
 * [tokenHash] is carried because sign-out needs it to revoke the row, and
 * hashing the cookie twice in one request is work with no reader. The `Session`
 * row itself is NOT carried: nothing in `public-web/src/main` reads it, and a
 * `:db` row crossing into the routing and render layers is a storage
 * representation with no consumer.
 */
data class WebCaller(
  val tokenHash: TokenHash,
  val user: User,
)

/**
 * `public-web`'s own session resolution (RFC 224 D-A).
 *
 * This is NEW code rather than `rest-server`'s `resolveCaller` moved somewhere
 * shared, and that was a measurement rather than a preference: moving
 * `SessionConfig` touches ~27 files, and moving `resolveCaller` would give
 * `:web-common` — a module that depends on no project module at all — edges to
 * `:service` and `:db` that `admin-web` and `rest-server` would then inherit.
 * What is genuinely shared is the cookie's VALUE contract, and `session.conf`
 * plus `:web-common`'s one cookie writer give that a single home (D-B). A
 * shared Kotlin type would not have made the two cookies match; identical
 * configuration does.
 *
 * Resolves IDENTITY ONLY. Whether the user's email is verified is the gate's
 * business, exactly as `rest-server`'s KDoc says of its own resolver — a
 * resolver that refused an unverified caller could not render the hold screen
 * that tells a family what to do next.
 *
 * Returns `null` for every "no caller" case `AuthService.resolveSession` folds
 * together: no cookie, a revoked or expired row, an anonymous session, a
 * soft-deleted user. A read FAULT is not one of those and is rethrown, so a
 * broken database renders the branded 503 rather than silently signing
 * everyone out.
 */
suspend fun ApplicationCall.resolveWebCaller(
  authService: AuthService,
  config: WebSessionConfig,
): WebCaller? {
  attributes.getOrNull(WebCallerKey)?.let { return it }

  val token = request.sessionCookieToken(config) ?: return null
  val tokenHash = TokenHash.fromRawToken(token)
  val authenticated = authService.resolveSession(tokenHash).getOrThrow() ?: return null

  val resolved = WebCaller(tokenHash, authenticated.user)
  attributes.put(WebCallerKey, resolved)
  return resolved
}

/**
 * The raw session token this request presented, or `null`.
 *
 * Separate from [resolveWebCaller] because sign-in needs the OLD cookie without
 * resolving it: `AuthService.login` takes it as `oldCookieToken` and revokes
 * that row inside the same transaction that inserts the new one, which is how
 * session fixation is handled in `:service` rather than here (D13).
 */
fun ApplicationRequest.sessionCookieToken(config: WebSessionConfig): String? = cookies[config.cookieName]?.takeIf { it.isNotBlank() }

/**
 * Writes the session cookie for a web sign-in.
 *
 * The lifetime is the SAME [java.time.Duration] the `sessions` row was created
 * with (D-D), so the cookie and the row expire together AT MINT. They are passed
 * from one [WebSessionConfig.expiration] for exactly that reason: a cookie
 * outliving its row shows a signed-in shell to a signed-out user, and a row
 * outliving its cookie signs a family out early with no explanation.
 *
 * "AT MINT" is the LIMIT, and it is named rather than hidden. One path rewrites
 * a row's lifetime afterwards: `SessionsDao.extendExpiry`, the `rest-server`
 * sliding refresh, which hard-codes `NOW() + 7 days` into any row it touches —
 * including a 30-day row this server minted. The slice's headline claim is that
 * ONE row serves both surfaces, so a web-minted session used from the iOS app
 * reaches that statement, and the two lifetimes part company there.
 *
 * What that costs is bounded, and it is the browser that pays. The row can end
 * up SHORTER than this cookie, so the browser keeps sending a cookie whose row
 * has expired; `resolveWebCaller` resolves nothing, the gate sends the family to
 * the sign-in form, and they sign in again. No stale signed-in page is rendered
 * from it, because every page resolves the caller for its own request and the
 * shell follows that resolution rather than the cookie's presence.
 */
fun ApplicationCall.setWebSessionCookie(
  config: WebSessionConfig,
  token: String,
) = setSharedSessionCookie(
  name = config.cookieName,
  token = token,
  domain = config.cookieDomain,
  secure = config.cookieSecure,
  lifetime = SessionCookieLifetime.MatchingRow(config.expiration),
)

/** Expires the session cookie in the browser. Delegates to the one writer (D-B). */
fun ApplicationCall.clearWebSessionCookie(config: WebSessionConfig) =
  clearSharedSessionCookie(
    name = config.cookieName,
    domain = config.cookieDomain,
    secure = config.cookieSecure,
  )

/**
 * One request sees ONE caller.
 *
 * A CONSISTENCY guarantee, not a performance measure. The gate resolves the
 * caller, the route wrapper behind it resolves the caller, and the render layer
 * asks again for the header — and those reads must not be able to disagree. A
 * row revoked between two of them would gate one way and render the other,
 * which is a signed-out family holding a signed-in page.
 *
 * At this project's scale the avoided work is not the point: the second read is
 * one SHA-256 and one primary-key `SELECT`. `rest-server`'s `resolveCaller` does
 * the same thing for the same reason, and two web tiers disagreeing about what
 * "the caller" means would be worse than either choice.
 */
private val WebCallerKey = AttributeKey<WebCaller>("ed.unicoach.web.auth.WebCaller")
