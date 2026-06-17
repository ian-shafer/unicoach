package ed.unicoach.admin.render

import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.error.TransientError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.h1
import kotlinx.html.p

/** A standalone (no-nav) HTML error page with a status code and message. */
suspend fun ApplicationCall.respondErrorPage(
  status: HttpStatusCode,
  heading: String,
  detail: String,
) {
  respondHtml(status) {
    adminPage(heading, nav = false) {
      h1 { +heading }
      p { +detail }
    }
  }
}

suspend fun ApplicationCall.respondNotFound(detail: String = "The requested resource was not found.") =
  respondErrorPage(HttpStatusCode.NotFound, "404 Not Found", detail)

suspend fun ApplicationCall.respondConflict() =
  respondErrorPage(
    HttpStatusCode.Conflict,
    "409 Conflict",
    "This row changed since you loaded it. Reload and try again.",
  )

suspend fun ApplicationCall.respondServiceUnavailable() =
  respondErrorPage(
    HttpStatusCode.ServiceUnavailable,
    "503 Service Unavailable",
    "A transient database error occurred. Please retry.",
  )

/**
 * The single DAO-failure classifier shared by the generic engine routes and the
 * resource-registered owner-nested action endpoints: a not-found renders 404, an
 * OCC conflict renders the 409 reload page, and any transient/other DB failure
 * renders 503. Centralizing it keeps every write path's error contract identical.
 */
suspend fun ApplicationCall.respondDaoError(error: Throwable) {
  when (error) {
    is NotFoundException -> respondNotFound()
    is ConcurrentModificationException -> respondConflict()
    is TransientError -> respondServiceUnavailable()
    else -> respondServiceUnavailable()
  }
}
