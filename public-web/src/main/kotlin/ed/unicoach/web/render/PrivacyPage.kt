package ed.unicoach.web.render

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.section

/**
 * The Privacy Policy body. The copy is static, but it renders dynamically
 * through [siteLayout] — not from a static HTML file — so it inherits the shared
 * chrome. The marker "Privacy Policy" proves this body rendered.
 */
suspend fun ApplicationCall.respondPrivacyPage() {
  respondHtml {
    siteLayout("Privacy Policy") {
      section("legal") {
        h1 { +"Privacy Policy" }
        p {
          +(
            "This Privacy Policy explains what information unicoach collects, how " +
              "it is used, and the choices you have."
          )
        }

        h2 { +"Information we collect" }
        p {
          +(
            "We collect the account information you provide — such as your name " +
              "and email address — and the essay content you submit so the service " +
              "can give you feedback."
          )
        }

        h2 { +"How we use information" }
        p {
          +(
            "Your information is used to operate and improve the coaching service. " +
              "We do not sell your personal information."
          )
        }

        h2 { +"Your choices" }
        p {
          +(
            "You may request access to or deletion of your account information by " +
              "contacting us. Some information may be retained as required to operate " +
              "the service or to comply with legal obligations."
          )
        }
      }
    }
  }
}
