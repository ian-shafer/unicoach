package ed.unicoach.web

import ed.unicoach.web.render.respondHomePage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.respondPrivacyPage
import ed.unicoach.web.render.respondServiceUnavailablePage
import ed.unicoach.web.render.respondTermsPage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * The single routing table for public-web. Every HTML page is an explicit
 * dynamic `GET` route registered before the catch-all static mount, so the mount
 * never handles them; the mount serves only chrome-less assets under `static/`.
 *
 * `StatusPages` renders the branded dynamic error pages: a `status` handler for
 * `NotFound` (unmatched routes perform no lookups, so the 404 must be caught
 * structurally at the status layer) and an `exception<Throwable>` catch-all that
 * renders the 503 rather than leaking a stack trace.
 */
fun Application.installPublicWebRouting() {
  install(StatusPages) {
    status(HttpStatusCode.NotFound) { call, _ ->
      call.respondNotFoundPage()
    }
    exception<Throwable> { call, _ ->
      call.respondServiceUnavailablePage()
    }
  }

  routing {
    healthRoute()
    get("/") { call.respondHomePage() }
    get("/terms") { call.respondTermsPage() }
    get("/privacy") { call.respondPrivacyPage() }
    staticResources("/", "static")
  }
}
