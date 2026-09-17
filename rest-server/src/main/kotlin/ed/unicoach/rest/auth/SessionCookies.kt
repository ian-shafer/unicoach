package ed.unicoach.rest.auth

import ed.unicoach.web.common.http.SessionCookieLifetime
import ed.unicoach.web.common.http.clearSharedSessionCookie
import ed.unicoach.web.common.http.setSharedSessionCookie
import io.ktor.server.application.ApplicationCall

/**
 * `rest-server`'s adapter onto `:web-common`'s single session-cookie writer
 * (RFC 224 D-B): the ONE place a [SessionConfig] is unpacked into cookie
 * attributes.
 *
 * It exists because the unpacking was written twice — once in `AuthRoutes` and
 * once in `StudentRoutes` — which is the same duplicate the shared writer was
 * introduced to remove, one layer up. Two routes that clear the same cookie
 * cannot now unpack the config differently.
 *
 * The lifetime is [SessionCookieLifetime.BrowserSession], stated as a CHOICE:
 * this server's client is the iOS app, which does not depend on browser cookie
 * persistence. `public-web` passes its own 30-day row lifetime (RFC 224 D-D).
 */
internal fun ApplicationCall.setSessionCookie(
  sessionConfig: SessionConfig,
  token: String,
) = setSharedSessionCookie(
  name = sessionConfig.cookieName,
  token = token,
  domain = sessionConfig.cookieDomain,
  secure = sessionConfig.cookieSecure,
  lifetime = SessionCookieLifetime.BrowserSession,
)

/** Expires the session cookie. Same unpacking, same one place. */
internal fun ApplicationCall.clearSessionCookie(sessionConfig: SessionConfig) =
  clearSharedSessionCookie(
    name = sessionConfig.cookieName,
    domain = sessionConfig.cookieDomain,
    secure = sessionConfig.cookieSecure,
  )
