package ed.unicoach.rest

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Route.rejectUnsupportedMethods(vararg methods: HttpMethod) {
  handle {
    val allowedString = methods.joinToString(", ") { it.value }
    call.response.headers.append(HttpHeaders.Allow, allowedString)
    call.respondText(
      text = "Method Not Allowed. Expected: $allowedString",
      status = HttpStatusCode.MethodNotAllowed,
    )
  }
}

fun Application.configureRouting(authService: ed.unicoach.auth.AuthService) {
  routing {
    route("/hello") {
      get {
        call.respondText(
          text = "Hello, Ian. I love you 😘",
          contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
        )
      }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
    ed.unicoach.rest.routing.authRoutes(authService)
  }
}
