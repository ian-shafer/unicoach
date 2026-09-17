package ed.unicoach.web.common.http

import io.ktor.server.application.ApplicationCall
import java.time.Duration

// The ONE place the `UNICOACH_SESSION` cookie's fixed attributes are written
// (RFC 224 D-B).
//
// `rest-server` and `public-web` issue the SAME cookie for the SAME `sessions`
// row, so "one login across web and app" is a claim about bytes, not about
// intent. Before this, the attribute set was copied at five sites with no test
// naming any of them; the two servers that share the session now share one
// writer, and a change to `SameSite` (scheduled for `web/05`, brief 0013 D22)
// is one edit here rather than a search for copies.
//
// What is fixed here and what is a parameter is the whole design:
//
// - **Fixed**: `Path=/`, `HttpOnly`, `SameSite=Strict`. These are properties of
//   the cookie CONTRACT. A caller that wanted a different value would be
//   issuing a different cookie, so they are private to this file rather than
//   named constants a caller could read or reach around.
// - **Parameters**: name, domain and `Secure` come from the shared `session`
//   config block, and the LIFETIME is per-client on purpose — `rest-server`
//   issues a browser-session cookie (its client is the iOS app), `public-web`
//   matches its 30-day `sessions` row (RFC 224 D-D).
//
// `admin-web` keeps its own writer: `admin_session` is a different cookie with
// a different lifetime, and it shares no session row with these two.

/** The cookie is site-wide: every path on the origin must see the session. */
private const val PATH: String = "/"

/**
 * Sent only on same-site requests. Ktor has no first-class `SameSite`
 * parameter, so it rides the `extensions` map — which is exactly why it was
 * so easy to copy wrongly before this file existed.
 */
private val EXTENSIONS: Map<String, String> = mapOf("SameSite" to "Strict")

/**
 * How long the browser keeps the session cookie.
 *
 * Sealed and REQUIRED rather than a defaulted `Long?`. "I chose a
 * browser-session cookie" and "I forgot to state a lifetime" are different
 * decisions, and a default made them the same program text: a new caller that
 * omitted the parameter silently issued a cookie that dies with the browser and
 * never failed to compile.
 */
sealed interface SessionCookieLifetime {
  /**
   * No `Max-Age` at all: the cookie dies with the browser session.
   *
   * `rest-server`'s choice, stated as a choice — its client is the iOS app,
   * which does not depend on browser cookie persistence.
   */
  data object BrowserSession : SessionCookieLifetime

  /**
   * The cookie expires with its `sessions` row.
   *
   * Built from the SAME [Duration] the row was created with, because that is the
   * only lifetime that is correct: a cookie outliving its row shows a signed-in
   * shell to a signed-out user, and a row outliving its cookie signs a family
   * out early with no explanation.
   */
  data class MatchingRow(
    val expiration: Duration,
  ) : SessionCookieLifetime

  /**
   * The row is revoked, so the cookie must go now: `Max-Age=0`, the deletion
   * form.
   *
   * Its own variant rather than `MatchingRow(Duration.ZERO)`, because "matches a
   * row" and "has no row" are different facts.
   */
  data object Expired : SessionCookieLifetime
}

/**
 * Writes the session cookie carrying [token] on this call's response.
 *
 * @param lifetime which of the three cookies this caller is issuing. See
 *   [SessionCookieLifetime] for why it is required and sealed rather than a
 *   nullable number of seconds.
 */
fun ApplicationCall.setSharedSessionCookie(
  name: String,
  token: String,
  domain: String?,
  secure: Boolean,
  lifetime: SessionCookieLifetime,
) {
  response.cookies.append(
    name = name,
    value = token,
    domain = domain,
    path = PATH,
    secure = secure,
    httpOnly = true,
    maxAge =
      when (lifetime) {
        SessionCookieLifetime.BrowserSession -> null
        SessionCookieLifetime.Expired -> 0L
        is SessionCookieLifetime.MatchingRow -> lifetime.expiration.seconds
      },
    extensions = EXTENSIONS,
  )
}

/**
 * Expires the session cookie in the browser.
 *
 * Expressed as the primitive it IS — the same cookie with an empty value and a
 * zero lifetime — rather than as a second `append` call. A browser only replaces
 * a cookie whose name, domain and path all agree, so the deletion form has to
 * carry the identical attribute set; writing it out again would have put that
 * set in TWO places in the one file whose purpose is to hold it in one.
 */
fun ApplicationCall.clearSharedSessionCookie(
  name: String,
  domain: String?,
  secure: Boolean,
) = setSharedSessionCookie(
  name = name,
  token = "",
  domain = domain,
  secure = secure,
  // The row this cookie named is revoked, so the cookie must go now.
  lifetime = SessionCookieLifetime.Expired,
)
