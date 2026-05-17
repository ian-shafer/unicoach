package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.TokenHash
import ed.unicoach.error.FieldError
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.MeResponse
import ed.unicoach.rest.models.PublicUser
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.RegisterResponse
import ed.unicoach.rest.rejectUnsupportedMethods
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Removed respondAppError

private fun ApplicationCall.clearSessionCookie(sessionConfig: ed.unicoach.rest.auth.SessionConfig) {
  response.cookies.append(
    name = sessionConfig.cookieName,
    value = "",
    domain = sessionConfig.cookieDomain,
    path = "/",
    secure = sessionConfig.cookieSecure,
    httpOnly = true,
    maxAge = 0L,
    extensions = mapOf("SameSite" to "Strict"),
  )
}

fun Route.authRoutes(
  authService: AuthService,
  database: ed.unicoach.db.Database,
  sessionConfig: ed.unicoach.rest.auth.SessionConfig,
  tokenGenerator: ed.unicoach.util.TokenGenerator,
) {
  route("/api/v1/auth") {
    post("/register") {
      val contentLength = call.request.header("Content-Length")?.toLongOrNull()
      if (contentLength != null && contentLength > 4096) {
        call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large", "Payload exceeds 4KB limit"))
        return@post
      }

      val request = call.receive<RegisterRequest>()

      val oldCookieToken = call.request.cookies[sessionConfig.cookieName]

      val outcome = authService.register(
        email = request.email,
        name = request.name,
        password = request.password,
        oldCookieToken = oldCookieToken,
        sessionExpirationSeconds = sessionConfig.expiration.seconds,
        userAgent = call.request.headers["User-Agent"],
        initialIp = call.request.origin.remoteHost,
      ).getOrThrow()

      when (outcome) {
        is ed.unicoach.auth.RegisterOutcome.Success -> {
          val publicUser =
            PublicUser(
              id = outcome.user.id.value,
              email = outcome.user.email.value,
              name = outcome.user.name.value,
            )

          call.response.cookies.append(
            name = sessionConfig.cookieName,
            value = outcome.token,
            domain = sessionConfig.cookieDomain,
            path = "/",
            secure = sessionConfig.cookieSecure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
          )

          call.respond(HttpStatusCode.Created, RegisterResponse(publicUser))
        }
        is ed.unicoach.auth.RegisterOutcome.ValidationFailure -> {
          val restFieldErrors =
            outcome.fieldErrors.map { FieldError(it.field, it.message) } +
              outcome.errors.map { FieldError("general", it) }
          call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "Invalid registration parameters", restFieldErrors))
        }
        is ed.unicoach.auth.RegisterOutcome.DuplicateEmail -> {
          call.respond(
            HttpStatusCode.Conflict,
            ErrorResponse("conflict", "Email already in use", listOf(FieldError("email", "Email already in use"))),
          )
        }
      }
    }
    route("/me") {
      get {
        val token = call.request.cookies[sessionConfig.cookieName]
        if (token == null) {
          call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Not authenticated"))
          return@get
        }

        val tokenHash = TokenHash.fromRawToken(token)

        val user = authService.getCurrentUser(tokenHash).getOrThrow()
        if (user == null) {
          call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Not authenticated"))
        } else {
          val publicUser =
            PublicUser(
              id = user.id.value,
              email = user.email.value,
              name = user.name.value,
            )
          call.respond(HttpStatusCode.OK, MeResponse(publicUser))
        }
      }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
    route("/logout") {
      post {
        val token = call.request.cookies[sessionConfig.cookieName]
        if (token == null) {
          call.clearSessionCookie(sessionConfig)
          call.respond(HttpStatusCode.NoContent)
          return@post
        }

        val tokenHash = TokenHash.fromRawToken(token)
        authService.logout(tokenHash).getOrThrow()
        call.clearSessionCookie(sessionConfig)
        call.respond(HttpStatusCode.NoContent)
      }
      rejectUnsupportedMethods(HttpMethod.Post)
    }
  }
}
