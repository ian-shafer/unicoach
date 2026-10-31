package ed.unicoach.web.render

import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.section

/** The placeholder `/get-started` renders until `web/06` builds the student signup form there. */
const val GET_STARTED_HEADING = "The coach needs a little about you first."
const val GET_STARTED_DETAIL = "For now, set that up in the Unicoach app on iPhone — setting it up here on the web is coming soon."

/**
 * `/get-started` — the student signup page's reserved path (RFC 262 D-B). A
 * placeholder until `web/06` builds the form here, so the chat's redirect to it
 * never has to move.
 */
suspend fun ApplicationCall.respondGetStartedPage() {
  respondHtml {
    siteLayout(GET_STARTED_HEADING, width = ShellWidth.WIDE, nav = ShellNav.SignedIn) {
      section("get-started") {
        h1 { +GET_STARTED_HEADING }
        p { +GET_STARTED_DETAIL }
      }
    }
  }
}
