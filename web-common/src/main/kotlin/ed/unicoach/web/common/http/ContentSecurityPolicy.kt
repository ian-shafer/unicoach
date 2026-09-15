package ed.unicoach.web.common.http

import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.routing.Route

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
 */
const val CONTENT_SECURITY_POLICY =
  "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
    "font-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; " +
    "base-uri 'self'; object-src 'none'"

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
