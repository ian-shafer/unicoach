package ed.unicoach.web.auth

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.Session
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.util.TokenGenerator
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.common.http.SessionCookieLifetime
import ed.unicoach.web.common.http.clearSharedSessionCookie
import ed.unicoach.web.common.http.setSharedSessionCookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

/**
 * The caller behind a `public-web` request: the user it belongs to, the hash of
 * the token that resolved it, the session row itself, and that session's CSRF
 * token.
 *
 * [tokenHash] is carried because sign-out needs it to revoke the row, and
 * hashing the cookie twice in one request is work with no reader. [session] is
 * carried because the session's metadata is now read and written here (RFC
 * 265): the form drafts. It is the row as this request last saw it — after a
 * metadata write, [recordSessionWrite] replaces it, so a second write in the
 * same request does not start from a stale version.
 *
 * [csrfToken] — the token every signed-in POST is checked against — is never
 * null, so a `WebCaller` cannot exist without one. It is not a second copy of
 * the token in [session]'s metadata either: the constructor is private, and
 * both ways to build one ([of] and [withSession]) derive the token from the
 * very row they are given. The two cannot disagree, whatever a metadata write
 * changed.
 */
class WebCaller private constructor(
  val tokenHash: TokenHash,
  val user: User,
  val session: Session,
  val csrfToken: CsrfToken,
) {
  /**
   * This caller holding [written], the row a metadata write returned, with the
   * token read again from it.
   *
   * Throws if [written] holds no token: every metadata write starts from a row
   * that had one, so a write that dropped it is a broken writer, and carrying
   * the old token on would render a page whose every form is refused.
   */
  fun withSession(written: Session): WebCaller =
    checkNotNull(of(tokenHash, user, written)) { "session [${written.id.value}] lost its CSRF token in a metadata write" }

  companion object {
    /** The caller [session] makes, or `null` when it holds no CSRF token ([CsrfToken.of]). */
    fun of(
      tokenHash: TokenHash,
      user: User,
      session: Session,
    ): WebCaller? = CsrfToken.of(session.metadata)?.let { WebCaller(tokenHash, user, session, it) }
  }
}

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
 * Resolves identity, and refuses exactly one thing beyond it: a session with no
 * CSRF token (RFC 265 D-F). It does NOT refuse an unverified caller — whether
 * the user's email is verified is the gate's business, exactly as
 * `rest-server`'s KDoc says of its own resolver; a resolver that refused an
 * unverified caller could not render the hold screen that tells a family what
 * to do next. Nor does it check a request's presented token against the
 * session's; that is `handleAccess`'s job, per request method.
 *
 * Returns `null` for every "no caller" case `AuthService.resolveSession` folds
 * together: no cookie, a revoked or expired row, an anonymous session, a
 * soft-deleted user. A read FAULT is not one of those and is rethrown, so a
 * broken database renders the branded 503 rather than silently signing
 * everyone out.
 *
 * It also returns `null`, logged, for a session with no well-formed CSRF token
 * ([WebCaller.of] decides). Every session is minted holding one, so only a row
 * minted before that change, or one no code wrote, lacks it, and signing that
 * family in again is the whole cost; a signed-in page with no token to put in
 * its forms could not submit any of them.
 */
suspend fun ApplicationCall.resolveWebCaller(
  authService: AuthService,
  config: WebSessionConfig,
): WebCaller? {
  attributes.getOrNull(WebCallerKey)?.let { return it }

  val token = request.sessionCookieToken(config) ?: return null
  val tokenHash = TokenHash.fromRawToken(token)
  val authenticated = authService.resolveSession(tokenHash).getOrThrow() ?: return null
  val resolved = WebCaller.of(tokenHash, authenticated.user, authenticated.session)
  if (resolved == null) {
    // The size, never the bytes: an empty token is a row minted before RFC 265,
    // any other size is a row no code wrote, and the two want different fixes.
    sessionLog.info(
      "session [{}] holds a [{}]-byte CSRF token, not the [{}] bytes every session is minted with; resolving it as no caller",
      authenticated.session.id.value,
      authenticated.session.metadata.csrfToken
        .size(),
      TokenGenerator.TOKEN_BYTES,
    )
    return null
  }

  attributes.put(WebCallerKey, resolved)
  return resolved
}

/**
 * [written] — the row a metadata write returned — becomes this request's
 * caller from here on, and the caller is returned.
 *
 * The cached caller is the one every later read in the request sees (see
 * [WebCallerKey]); leaving the pre-write row there would hand the next write a
 * version the row no longer has.
 */
fun ApplicationCall.recordSessionWrite(
  caller: WebCaller,
  written: Session,
): WebCaller {
  val updated = caller.withSession(written)
  attributes.put(WebCallerKey, updated)
  return updated
}

/** Why a session that resolved is nevertheless no caller. */
private val sessionLog = LoggerFactory.getLogger("ed.unicoach.web.auth.WebSession")

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
