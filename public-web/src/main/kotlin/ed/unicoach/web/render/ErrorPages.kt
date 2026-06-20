package ed.unicoach.web.render

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
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
) {
  respondHtml(status) {
    siteLayout(heading) {
      section("error") {
        h1 { +heading }
        p { +detail }
      }
    }
  }
}

/** The branded 404 page for any unmatched route. Marker: "404 Not Found". */
suspend fun ApplicationCall.respondNotFoundPage() =
  respondErrorPage(
    HttpStatusCode.NotFound,
    "404 Not Found",
    "The page you are looking for does not exist.",
  )

/** The branded 503 page for an uncaught failure. Marker: "503 Service Unavailable". */
suspend fun ApplicationCall.respondServiceUnavailablePage() =
  respondErrorPage(
    HttpStatusCode.ServiceUnavailable,
    "503 Service Unavailable",
    "Something went wrong on our end. Please try again shortly.",
  )
