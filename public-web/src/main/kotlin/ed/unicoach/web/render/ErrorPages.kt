package ed.unicoach.web.render

import ed.unicoach.web.auth.SIGN_IN_PATH
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.SECTION
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.section

/**
 * A branded error page rendered through [siteLayout] so it carries the same
 * header/footer chrome as every other page — never a default Ktor body or a raw
 * stack trace.
 */
private suspend fun ApplicationCall.respondErrorPage(
  status: HttpStatusCode,
  heading: String,
  detail: String,
  nav: ShellNav,
  extra: SECTION.() -> Unit = {},
) {
  respondHtml(status) {
    siteLayout(heading, nav = nav) {
      section("error") {
        h1 { +heading }
        p { +detail }
        extra()
      }
    }
  }
}

/**
 * The branded 404 page for any unmatched route. Marker: "404 Not Found".
 *
 * [nav] is a REQUIRED parameter on every error page, not a defaulted one: an
 * error page is still a page of this site, and a signed-in family meeting one
 * must keep the header that lets them sign out. A default would have made
 * "signed out" the quiet answer at exactly the call sites nobody re-reads.
 */
suspend fun ApplicationCall.respondNotFoundPage(nav: ShellNav) =
  respondErrorPage(
    HttpStatusCode.NotFound,
    "404 Not Found",
    "The page you are looking for does not exist.",
    nav,
  )

/** The branded 503 page for an uncaught failure. Marker: "503 Service Unavailable". */
suspend fun ApplicationCall.respondServiceUnavailablePage(nav: ShellNav) =
  respondErrorPage(
    HttpStatusCode.ServiceUnavailable,
    "503 Service Unavailable",
    "Something went wrong on our end. Please try again shortly.",
    nav,
  )

/**
 * The same-origin guard's refusal (RFC 224 D-C). Marker: "403 Request blocked".
 *
 * A blank 403 would be indistinguishable from the site being broken, and the
 * people most likely to see this one are not attackers — they are a family on a
 * browser or privacy tool that strips both `Origin` and `Referer`, for whom the
 * strict arm of the guard is the reason the form did not work. So the page says
 * what happened in a sentence they can act on and links back to the form, in
 * the ordinary site shell.
 *
 * It says nothing about which header was missing or what value was expected:
 * that is detail an attacker probing the guard would use and a family cannot.
 */
suspend fun ApplicationCall.respondForbiddenPage(nav: ShellNav) =
  respondErrorPage(
    HttpStatusCode.Forbidden,
    "403 Request blocked",
    "For your security we could not confirm this request came from unicoach. " +
      "This usually means the form was submitted from another site, or your browser is not sending the information we check. " +
      "Please open the page again and try once more.",
    nav,
  ) {
    p {
      a(href = SIGN_IN_PATH) { +"Go to sign in" }
    }
  }
