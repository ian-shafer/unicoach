package ed.unicoach.web.common.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.routing.Route

/**
 * The one robots directive for a page that must never be indexed, written once.
 *
 * It is stated twice on the wire and has to be the same string both times: as
 * the `X-Robots-Tag` response header, for a crawler that reads only the response
 * head, and as the `<meta name="robots">` in the body, for one that reads the
 * body. Two hand-typed literals in two modules is a head and a body that can
 * disagree about whether a family's page may be indexed.
 */
const val ROBOTS_NOINDEX = "noindex, nofollow"

/** The header a crawler reads when it reads only the response head. */
const val X_ROBOTS_TAG_HEADER = "X-Robots-Tag"

/** No shared cache keeps a copy of a family's figures. */
const val CACHE_CONTROL_NO_STORE = "no-store"

/** The header that keeps the link out of the `Referer` of anything the parent follows from the page. */
const val REFERRER_POLICY_HEADER = "Referrer-Policy"

/**
 * Its one value here (RFC 254, superseding RFC 155 D-H's `no-referrer`): the
 * URL carries a live credential, so it never rides to ANOTHER origin, and every
 * outbound link from these pages is cross-origin and gets no `Referer` at all.
 *
 * Not `no-referrer`, because under the Fetch standard a POST from a
 * `no-referrer` document sends `Origin: null`, which the same-origin guard
 * refuses — so every form on these pages (sign-in, register, accept, ask to
 * connect) was refused in a real browser. `same-origin` sends the real origin
 * on a same-origin POST. The one new place the token travels is a same-origin
 * `Referer` to this server, which the guard reduces to its origin before it
 * logs it.
 */
const val REFERRER_POLICY_SAME_ORIGIN = "same-origin"

/**
 * The one other value (RFC 275 §6): what a link-holder page sends when it
 * embeds a frame that needs to know which site it is on — Google's sign-in
 * button. Cross-origin it sends the ORIGIN only, never the path or the query,
 * so the credential in the URL still never leaves the site; same-origin it
 * sends the full URL, exactly as [REFERRER_POLICY_SAME_ORIGIN] does.
 */
const val REFERRER_POLICY_STRICT_ORIGIN_WHEN_CROSS_ORIGIN = "strict-origin-when-cross-origin"

/**
 * Marks a route as PRIVATE TO WHOEVER HOLDS ITS LINK: no shared cache keeps a
 * copy, no crawler indexes it, and no link to another origin carries its URL in
 * a `Referer` (RFC 155 D-H, RFC 254).
 *
 * Route-scoped and stamped in the `Setup` phase, beside `secretQueryParams` and
 * for the same reason: the headers are a standing property of the ROUTE, so they
 * ride every outcome under it — the page, the 404 for a dead token, and the 503
 * for a read fault — and a handler added under the route later cannot forget
 * them. Set inside one handler they covered exactly that handler.
 */
fun Route.setLinkHolderPrivacyHeaders(referrerPolicy: LinkHolderReferrerPolicy = LinkHolderReferrerPolicy.SAME_ORIGIN) {
  install(PrivateToLinkHolderPlugin) { this.referrerPolicy = referrerPolicy }
}

/**
 * The two `Referrer-Policy` values a link-holder page may send. A closed set
 * rather than a string, because each one is a claim that the credential in the
 * URL never reaches another origin, and a free-form value could be
 * `unsafe-url`.
 */
enum class LinkHolderReferrerPolicy(
  val value: String,
) {
  /** No `Referer` to any other origin at all. */
  SAME_ORIGIN(REFERRER_POLICY_SAME_ORIGIN),

  /** The origin alone to another origin, for a page that embeds a provider's frame (RFC 275). */
  STRICT_ORIGIN_WHEN_CROSS_ORIGIN(REFERRER_POLICY_STRICT_ORIGIN_WHEN_CROSS_ORIGIN),
}

/** The referrer policy [PrivateToLinkHolderPlugin] stamps; defaulted so the common case names nothing. */
class PrivateToLinkHolderConfiguration {
  var referrerPolicy: LinkHolderReferrerPolicy = LinkHolderReferrerPolicy.SAME_ORIGIN
}

/** The plugin behind [setLinkHolderPrivacyHeaders]; installed only on the routes that opt in. */
val PrivateToLinkHolderPlugin =
  createRouteScopedPlugin("PrivateToLinkHolder", ::PrivateToLinkHolderConfiguration) {
    val referrerPolicy = pluginConfig.referrerPolicy.value
    on(CallSetup) { call ->
      call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_NO_STORE)
      call.response.header(X_ROBOTS_TAG_HEADER, ROBOTS_NOINDEX)
      call.response.header(REFERRER_POLICY_HEADER, referrerPolicy)
    }
  }
