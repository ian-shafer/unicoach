package ed.unicoach.web

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Liveness endpoint. Dependency-free: it never touches any backing service
 * (there is none), so an operator's probe reflects only whether the process is
 * accepting connections.
 */
fun Route.healthRoute() {
  get("/healthz") {
    call.respondText(
      text = "{\"status\":\"ok\"}",
      contentType = ContentType.Application.Json,
    )
  }
}
