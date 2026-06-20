package ed.unicoach.web.render

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.section

/**
 * The brand/awareness landing body, injected into [siteLayout]'s content slot so
 * it carries the shared header/footer chrome. The marker "Your college coach"
 * proves this body rendered.
 */
suspend fun ApplicationCall.respondHomePage() {
  respondHtml {
    siteLayout("Home") {
      section("hero") {
        h1 { +"Your college coach" }
        p {
          +(
            "unicoach guides high-school students through the entire path to college — " +
              "exploring schools, choosing the best fit, standardized testing, " +
              "application strategy, the applications themselves, and college costs."
          )
        }
        p {
          a(href = "/terms") { +"Read our Terms" }
        }
      }
    }
  }
}
