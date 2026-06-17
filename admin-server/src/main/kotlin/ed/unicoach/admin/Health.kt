package ed.unicoach.admin

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Liveness endpoint. Unauthenticated and dependency-free: it never touches the
 * database or any backing service, so an operator's probe reflects only whether
 * the process is accepting connections. The gate exempts `/healthz`.
 */
fun Route.healthRoute() {
  get("/healthz") {
    call.respondText(
      text = "{\"status\":\"ok\"}",
      contentType = ContentType.Application.Json,
    )
  }
}
