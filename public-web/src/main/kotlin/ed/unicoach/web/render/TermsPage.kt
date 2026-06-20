package ed.unicoach.web.render

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.section

/**
 * The Terms of Service body. The copy is static, but it renders dynamically
 * through [siteLayout] — not from a static HTML file — so it inherits the shared
 * chrome. The marker "Terms of Service" proves this body rendered.
 */
suspend fun ApplicationCall.respondTermsPage() {
  respondHtml {
    siteLayout("Terms of Service") {
      section("legal") {
        h1 { +"Terms of Service" }
        p {
          +(
            "These Terms of Service govern your access to and use of unicoach. " +
              "By using the service, you agree to these terms."
          )
        }

        h2 { +"Use of the service" }
        p {
          +(
            "unicoach provides college-essay coaching tools for personal, " +
              "non-commercial use. You are responsible for the content you submit " +
              "and for keeping your account credentials secure."
          )
        }

        h2 { +"Acceptable use" }
        p {
          +(
            "You agree not to misuse the service, including by attempting to " +
              "disrupt it, access it through unauthorized means, or submit content " +
              "that infringes the rights of others."
          )
        }

        h2 { +"Changes to these terms" }
        p {
          +(
            "We may update these terms from time to time. Continued use of the " +
              "service after a change constitutes acceptance of the updated terms."
          )
        }
      }
    }
  }
}
