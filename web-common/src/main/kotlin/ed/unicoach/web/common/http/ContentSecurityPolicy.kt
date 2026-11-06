package ed.unicoach.web.common.http

import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.routing.Route

/**
 * The default policy's directives, in the order the header states them. The one
 * home of [CONTENT_SECURITY_POLICY]'s content: the default and every widened
 * policy are both rendered from this list, so a widened page cannot drift from
 * the default in any directive it did not mean to widen.
 *
 * Declared ABOVE [CONTENT_SECURITY_POLICY] because a file's top-level
 * properties initialise in textual order, and that constant is rendered from
 * this list.
 */
private val DEFAULT_DIRECTIVES: List<Pair<String, List<String>>> =
  listOf(
    "default-src" to listOf("'self'"),
    "script-src" to listOf("'self'"),
    "style-src" to listOf("'self'"),
    "img-src" to listOf("'self'", "data:"),
    "font-src" to listOf("'self'"),
    "connect-src" to listOf("'self'"),
    "form-action" to listOf("'self'"),
    "frame-ancestors" to listOf("'none'"),
    "base-uri" to listOf("'self'"),
    "object-src" to listOf("'none'"),
  )

/**
 * The ONE place the content-security policy is written (RFC 210).
 *
 * Every directive is chosen against what the tree actually serves: no inline
 * `<script>`, no inline `style=`, no external origin, and one form posting to
 * its own origin. So `'unsafe-inline'` appears nowhere, and that is the whole
 * point of setting the policy before there is anything to retro-fit it to — the
 * first inline script anyone writes fails visibly in the browser instead of
 * quietly becoming a habit a later policy can no longer remove.
 *
 * `img-src` allows `data:` because a rendered page may inline a small asset as a
 * data URL; `connect-src 'self'` is what a streaming fetch needs and is already
 * correct because the browser talks to ONE origin. `frame-ancestors 'none'`
 * gives the public site the clickjacking protection it never had, and
 * `object-src 'none'` closes the plugin surface nothing here uses.
 *
 * This is ENFORCING, not report-only: report-only is the safe choice for a
 * policy retro-fitted to a large site, and this site is a handful of pages that
 * provably satisfy it.
 *
 * Rendered by [contentSecurityPolicy] with nothing added (RFC 275), so the
 * default and every widened policy share one directive list.
 */
val CONTENT_SECURITY_POLICY: String = contentSecurityPolicy()

/**
 * The default policy with [extraSources] appended to the directives they name
 * (RFC 275 §6).
 *
 * A page that loads a third party's script — the sign-in page, for the
 * providers' own buttons — needs a policy that names that party and nothing
 * else, while every other page keeps the default. A builder rather than a
 * second literal, so the widened policy is the default plus exactly the
 * sources its caller listed.
 *
 * A directive the default does not state (`frame-src`) is added after the
 * default's, rather than left to `default-src` — a frame source is then a
 * visible, deliberate line in the header. Sources already present are not
 * repeated, and a directive named with no sources adds nothing: an empty
 * `frame-src` would mean `'none'`, which is not what an empty list says.
 */
fun contentSecurityPolicy(extraSources: Map<String, List<String>> = emptyMap()): String {
  val stated = DEFAULT_DIRECTIVES.map { (directive, _) -> directive }.toSet()
  val widened = DEFAULT_DIRECTIVES.map { (directive, sources) -> directive to sources + extraSources[directive].orEmpty() }
  val added = extraSources.filterKeys { it !in stated }.toList()
  return (widened + added)
    .filter { (_, sources) -> sources.isNotEmpty() }
    .joinToString("; ") { (directive, sources) -> (listOf(directive) + sources.distinct()).joinToString(" ") }
}

/**
 * The ENFORCING header name. Ktor's [io.ktor.http.HttpHeaders] does not name it,
 * and the report-only spelling differs by one suffix, so it is written once here
 * rather than typed at each call site — the same reason [X_ROBOTS_TAG_HEADER]
 * exists next door.
 */
const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"

/**
 * Sets a content-security policy on every response under this route.
 *
 * The POLICY is a parameter and [CONTENT_SECURITY_POLICY] is only its default,
 * because the mechanism — one header, stamped in `Setup`, at a routing root —
 * is shared by every web tier while the directive list is not: a tier that one
 * day serves a third-party asset needs its own string, and the alternative is
 * copying this plugin to change one literal.
 *
 * Installed at the ROOT of a module's routing, not on a page route: a policy
 * that covers only the rendered pages leaves the static mount and the
 * `StatusPages` 404/503 uncovered, and those are served by response paths a
 * route-scoped install never reaches.
 *
 * A page that needs a WIDER policy installs this again on its own route (RFC
 * 275): Ktor runs only the innermost install of a route-scoped plugin, so that
 * route's responses carry its policy INSTEAD of the root's — one header, never
 * two, which a browser would enforce as their intersection.
 * `ContentSecurityPolicyTest` holds that claim to the header count.
 *
 * Stamped in the `Setup` phase, beside [setLinkHolderPrivacyHeaders] and for the
 * same reason — the header is a standing property of everything under the route,
 * so it rides an error outcome exactly as it rides a page, and a route added
 * later cannot forget it.
 */
fun Route.setContentSecurityPolicy(policy: String = CONTENT_SECURITY_POLICY) {
  // A blank policy is the one argument that is worse than not calling this at
  // all: a `Content-Security-Policy:` header with no directives restricts
  // nothing, and every test that only asks whether the header is PRESENT still
  // passes. Refused at the call site, where the caller can see what it passed.
  require(policy.isNotBlank()) {
    "the content-security policy is blank: a header with no directives restricts nothing, " +
      "so pass a directive list or call setContentSecurityPolicy() for the shared default"
  }
  install(ContentSecurityPolicyPlugin) { this.policy = policy }
}

/** The policy [ContentSecurityPolicyPlugin] stamps; defaulted so the common case names nothing. */
class ContentSecurityPolicyConfiguration {
  var policy: String = CONTENT_SECURITY_POLICY
}

/** The plugin behind [setContentSecurityPolicy]; installed once, at a routing root. */
val ContentSecurityPolicyPlugin =
  createRouteScopedPlugin("ContentSecurityPolicy", ::ContentSecurityPolicyConfiguration) {
    val policy = pluginConfig.policy
    on(CallSetup) { call ->
      call.response.header(CONTENT_SECURITY_POLICY_HEADER, policy)
    }
  }
