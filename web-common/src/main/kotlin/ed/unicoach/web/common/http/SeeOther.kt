package ed.unicoach.web.common.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * `303 See Other` — post-redirect-get for an HTML form.
 *
 * Not `respondRedirect`, which emits `302 Found`. The difference is not
 * cosmetic: after a `302` a browser may repeat the original METHOD, so a reload
 * can re-POST a sign-in or an email change, while `303` is defined to turn the
 * follow-up into a `GET`. The whole reason a successful POST redirects is to
 * make the reload safe, and `302` leaves that half-done.
 *
 * In `:web-common` because post-redirect-get is not one app's rule: it is
 * correct for every HTML form in the repo, and a version private to one routing
 * table is a version the next server cannot reach.
 */
suspend fun ApplicationCall.respondSeeOther(location: String) {
  response.header(HttpHeaders.Location, location)
  respond(HttpStatusCode.SeeOther)
}
