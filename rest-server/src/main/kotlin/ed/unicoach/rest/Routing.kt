package ed.unicoach.rest

import ed.unicoach.rest.routing.AuthRouteHandler
import ed.unicoach.rest.routing.ConvoRouteHandler
import ed.unicoach.rest.routing.StudentRouteHandler
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

fun Application.configureRouting(
  authService: ed.unicoach.auth.AuthService,
  studentService: ed.unicoach.student.StudentService,
  coachingService: ed.unicoach.coaching.CoachingService,
  sessionConfig: ed.unicoach.rest.auth.SessionConfig,
) {
  val authRouteHandler = AuthRouteHandler(authService, sessionConfig)
  val studentRouteHandler = StudentRouteHandler(authService, studentService, sessionConfig)
  val convoRouteHandler = ConvoRouteHandler(authService, studentService, coachingService, sessionConfig)
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
    authRouteHandler.registerRoutes(this)
    studentRouteHandler.registerRoutes(this)
    convoRouteHandler.registerRoutes(this)
  }
}
