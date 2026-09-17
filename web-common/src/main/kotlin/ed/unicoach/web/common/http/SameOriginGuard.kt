package ed.unicoach.web.common.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.net.URI

/**
 * The scheme, host and port of one origin — the three things RFC 6454 says an
 * origin IS, compared as a value rather than as a string.
 *
 * String comparison would be wrong in both directions here. `https://a.test`
 * and `https://a.test:443` are the SAME origin and differ as text, so a string
 * check refuses a legitimate browser; and a trailing slash or a difference in
 * case would do the same. Parsing once and comparing the three components is
 * the only reading that does not encode a guess about how the operator typed
 * the configured value.
 */
data class SiteOrigin(
  val scheme: String,
  val host: String,
  val port: Int,
) {
  /**
   * The origin written back as a URL — what a browser puts in an `Origin`
   * header. The port is omitted when it is the scheme's default, because that is
   * the spelling a browser uses and this value is compared against browsers.
   */
  val url: String get() = if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"

  companion object {
    /**
     * Parses [raw] as an absolute URL and keeps its origin, or returns `null`
     * when it is not one.
     *
     * `null` for an unparseable or non-absolute value, and the CALLER decides
     * what that means: for the configured site origin it is a boot failure, and
     * for a header a remote party sent it is a refusal. Those are different
     * outcomes and this function deliberately does not pick between them.
     *
     * A browser sends the opaque origin as the literal `null` for a sandboxed
     * or file:// document. It has no scheme or host, so it parses to `null`
     * here and is refused — which is the right answer: an opaque origin is by
     * definition not this site.
     */
    fun parse(raw: String?): SiteOrigin? {
      val trimmed = raw?.trim().orEmpty()
      if (trimmed.isEmpty()) return null

      val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
      val scheme = uri.scheme?.lowercase() ?: return null
      // The scheme allowlist, checked for its OWN sake. An origin this site can
      // ever be served over is http or https; anything else is refused whether
      // or not it states a port.
      val defaultPort = defaultPort(scheme) ?: return null
      val host = uri.host?.lowercase() ?: return null
      val port = if (uri.port != -1) uri.port else defaultPort

      return SiteOrigin(scheme, host, port)
    }

    private fun defaultPort(scheme: String): Int? =
      when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> null
      }
  }
}

/**
 * The methods a same-origin check SKIPS: the two safe ones, and only those.
 *
 * Stated as the exemption rather than as the set to check, because the
 * exemption is the closed set. A safe method is followed by every link,
 * prefetch and crawler, none of which sends an `Origin`; every other method is
 * checked whether or not this file has heard of it, so a route added by a later
 * slice cannot arrive unguarded.
 */
private val SAFE_METHODS =
  setOf(
    HttpMethod.Get,
    HttpMethod.Head,
  )

/**
 * The cross-site request forgery defence for a cookie-authenticated HTML server
 * (RFC 224 D-C).
 *
 * It lives in `:web-common` beside the CSP writer and the session-cookie writer
 * because nothing in it is `public-web`'s: RFC 6454 origin parsing and a set of
 * state-changing methods know nothing about sign-in, and `admin-web` serves
 * cookie-authenticated form POSTs too. The one part that IS per-server — how a
 * refusal is answered — is the [onRefused] parameter.
 *
 * Brief 0013 D22 schedules CSRF TOKENS for `web/05`. RFC 224 lands the
 * sign-in and registration POSTs, and for those two `SameSite=Strict` protects
 * nothing at all: `SameSite` governs when a cookie is SENT, never whether a
 * response's `Set-Cookie` is APPLIED. A cross-site form POST to `/sign-in`
 * still gets its cookie stored, so an attacker can sign a victim's browser into
 * the ATTACKER's account and read back everything the family then types into
 * it. Shipping a public sign-in form with that hole and a note in the RFC was a
 * real option and was rejected.
 *
 * This is an outer layer, not a replacement: `web/05` still lands synchronizer
 * tokens per D22, and they compose with this.
 *
 * The policy, stated exactly because the edges are where these go wrong:
 *
 * - Every method but `GET` and `HEAD`, the two a same-origin check skips
 *   because they change no state.
 * - `Origin` is compared to the configured site origin. When `Origin` is
 *   PRESENT it is the claim, and an unparseable one (a browser's opaque
 *   `Origin: null`, a malformed value) is a refusal — never a fall back to a
 *   weaker header. When `Origin` is ABSENT, the ORIGIN OF `Referer` is used —
 *   the path and query of a `Referer` are not part of the comparison and must
 *   not be, or the check would depend on which page the form was served from.
 * - When BOTH are absent the request is REFUSED. This is the strict arm and it
 *   is the decision, not a default that fell out. Its cost is named: a browser
 *   or privacy tool that strips both headers cannot sign in, which is the same
 *   old-browser tail that already ignores `SameSite`. The alternative —
 *   allowing when both are missing — quietly re-opens the hole, because "no
 *   headers" is exactly what an attacker would send if it were accepted.
 *
 * `POST /verify-email` is included because this is a blanket rule; the
 * single-use token in its body remains its actual authorisation.
 *
 * Installed as an `ApplicationCallPipeline.Plugins` interceptor rather than
 * route-scoped, so a route added by a later slice is covered the day it is
 * written instead of the day someone remembers to opt it in.
 *
 * @param expected the ONE public-web origin, ALREADY PARSED. It is a
 *   [SiteOrigin] and not a `String` because a guard with nothing to compare
 *   against could only fail open, and a security control that silently does
 *   nothing is worse than none — so the value is rejected where each server
 *   reads its configuration (`PublicWebConfig` in public-web), and an
 *   unparseable origin cannot produce a config object for this to be handed.
 * @param onRefused how THIS server answers a refusal. A parameter because the
 *   CHECK is domain-agnostic and the PAGE is not: public-web renders its branded
 *   403 in the shell the caller's own session earned, and a JSON surface would
 *   answer a body its client can read.
 */
fun Application.installSameOriginGuard(
  expected: SiteOrigin,
  onRefused: suspend ApplicationCall.() -> Unit,
) {
  // The enforced origin, said out loud at boot beside the bind address. Parsing
  // proves the value IS an origin, never that it is THIS server's origin:
  // behind a TLS proxy with PUBLIC_WEB_URL_BASE unset the guard compares
  // a browser's https://app.uni.coach against http://localhost:8082 and refuses
  // every POST including sign-in. Logging the comparand turns that from a silent
  // site-wide outage into one line an operator can read.
  val guardLog = log
  guardLog.info(
    "same-origin guard enforcing [{}] for every method but [{}]",
    expected.url,
    SAFE_METHODS.joinToString(",") { it.value },
  )

  intercept(ApplicationCallPipeline.Plugins) {
    if (call.request.httpMethod in SAFE_METHODS) return@intercept

    val rawOrigin = call.request.header(HttpHeaders.Origin)
    val rawReferer = call.request.header(HttpHeaders.Referrer)
    val refererOrigin = SiteOrigin.parse(rawReferer)
    val presented =
      when (rawOrigin) {
        // Absent: fall back to the ORIGIN of Referer, per RFC 224 D-C.
        null -> refererOrigin

        // Present: this IS the claim, and an unparseable one (the opaque `null`
        // a sandboxed or data: document sends) is a refusal, never a reason to
        // consult a weaker header.
        else -> SiteOrigin.parse(rawOrigin)
      }

    if (presented != expected) {
      // At WARN, with the method, the path, the EXPECTED origin and BOTH
      // headers as sent: a forged cross-site POST, a browser that strips the
      // headers, an opaque `Origin: null` and a mis-set PUBLIC_WEB_URL_BASE all
      // land here and are only distinguishable by these. The page itself says
      // none of it (see respondForbiddenPage). The Referer is reduced to its
      // ORIGIN because its path and query can hold a live share token.
      guardLog.warn(
        "same-origin guard refused [{} {}] expecting [{}]: Origin=[{}] Referer origin=[{}]",
        call.request.httpMethod.value,
        call.request.path(),
        expected.url,
        rawOrigin ?: "absent",
        refererOrigin?.url ?: if (rawReferer == null) "absent" else "unparseable",
      )
      call.onRefused()
      finish()
    }
  }
}
