package ed.unicoach.admin.render

import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.error.TransientError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.uri
import kotlinx.html.h1
import kotlinx.html.p
import org.slf4j.LoggerFactory

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

suspend fun ApplicationCall.respondInternalError() =
  respondErrorPage(
    HttpStatusCode.InternalServerError,
    "500 Internal Server Error",
    "An unexpected error occurred. The failure has been logged.",
  )

/**
 * The single DAO-failure classifier shared by the generic engine routes and the
 * resource-registered owner-nested action endpoints: a not-found renders 404, an
 * OCC conflict renders the 409 reload page, a genuine transient failure renders
 * the 503 retry page, and any other failure is an unexpected server error (500),
 * not a transient one. Centralizing it keeps every write path's error contract
 * identical.
 */
suspend fun ApplicationCall.respondDaoError(error: Throwable) {
  when (error) {
    is NotFoundException -> respondNotFound()
    is ConcurrentModificationException -> respondConflict()
    is TransientError -> respondServiceUnavailable()
    else -> {
      adminErrorLog.error("Unexpected DAO failure handling admin request [${request.uri}]", error)
      respondInternalError()
    }
  }
}

private val adminErrorLog = LoggerFactory.getLogger("ed.unicoach.admin.AdminErrors")

/**
 * The admin server's top-level failure net. Every throwable that escapes a
 * handler is logged with its request URI, then classified: a genuine
 * [TransientError] renders the 503 retry page, while anything else is an
 * unexpected server fault rendered as 500. Crucially, nothing is silently
 * swallowed — a 5xx without a log entry must never happen.
 */
fun Application.configureAdminStatusPages() {
  install(StatusPages) {
    exception<Throwable> { call, cause ->
      when (cause) {
        is TransientError -> {
          adminErrorLog.warn("Transient failure handling admin request [${call.request.uri}]", cause)
          call.respondServiceUnavailable()
        }

        else -> {
          adminErrorLog.error("Unhandled failure handling admin request [${call.request.uri}]", cause)
          call.respondInternalError()
        }
      }
    }
  }
}
