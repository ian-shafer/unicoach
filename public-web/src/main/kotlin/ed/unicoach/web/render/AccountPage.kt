package ed.unicoach.web.render

import ed.unicoach.db.models.User
import ed.unicoach.web.auth.CHANGE_EMAIL_PATH
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.a
import kotlinx.html.dd
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.section

/** Heading marker proving the signed-in home rendered. */
const val ACCOUNT_HEADING = "Your account"

/** The class the signed-in summary carries, so its definition-list rules find it. */
const val ACCOUNT_CLASS = "account"

/**
 * The signed-in home.
 *
 * Rendered at [ShellWidth.WIDE] — the first production caller of the seam RFC
 * 210 opened, whose KDoc named this page. It is not prose read in lines; it is
 * the shell every later web slice hangs its content in, and the reading measure
 * would crop that to a column before any of it exists.
 *
 * It says very little on purpose. `web/02` is the gate the later slices sit
 * behind, not the product behind it: the account view, the college list and the
 * chat arrive in their own slices. What it must do today is prove a family is
 * signed in, name the address the session belongs to, and offer the two things
 * this slice actually ships — changing that address, and signing out (which
 * lives in the shared header, so it is reachable from every page).
 *
 * [notice] is the post-redirect-get confirmation: every successful POST in this
 * slice answers `303` and the message is rendered by the GET that follows, so a
 * reload cannot resubmit the form that produced it. It is a [WebNotice] and not
 * a sentence, so an unknown query key renders nothing at all.
 */
suspend fun ApplicationCall.respondAccountPage(
  user: User,
  notice: WebNotice? = null,
) {
  respondHtml {
    siteLayout(ACCOUNT_HEADING, width = ShellWidth.WIDE, nav = ShellNav.SignedIn) {
      section(ACCOUNT_CLASS) {
        h1 { +ACCOUNT_HEADING }
        notice(notice)
        dl {
          dt { +"Name" }
          dd { +user.name.value }
          dt { +"Email" }
          dd { +user.email.value }
        }
        p {
          // Label and path both read from the page itself, so this link cannot
          // name it one thing while the page calls itself another.
          a(href = CHANGE_EMAIL_PATH) { +CHANGE_EMAIL_HEADING }
        }
      }
    }
  }
}
