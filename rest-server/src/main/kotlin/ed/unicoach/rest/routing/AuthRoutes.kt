package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthResult
import ed.unicoach.auth.AuthService
import ed.unicoach.error.FieldError
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.PublicUser
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.RegisterResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

suspend fun ApplicationCall.respondAppError(
  error: AuthResult,
  status: HttpStatusCode,
) {
  when (error) {
    is AuthResult.ValidationFailure -> {
      val restFieldErrors =
        error.fieldErrors.map { FieldError(it.field, it.message) } +
          error.errors.map { FieldError("general", it) }
      respond(status, ErrorResponse("validation_failed", "Invalid registration parameters", restFieldErrors))
    }
    is AuthResult.DuplicateEmail -> {
      respond(status, ErrorResponse("conflict", "Email already in use", listOf(FieldError("email", "Email already in use"))))
    }
    is AuthResult.DatabaseFailure -> {
      respond(status, ErrorResponse("internal_error", "An internal error occurred"))
    }
    else -> respond(HttpStatusCode.InternalServerError, ErrorResponse("unknown_error", "An unknown error occurred"))
  }
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

      when (val result = authService.register(request.email, request.name, request.password)) {
        is AuthResult.Success -> {
          val publicUser =
            PublicUser(
              id = result.user.id.value,
              email = result.user.email.value,
              name = result.user.name.value,
            )

          val newToken = tokenGenerator.generateToken()
          val newHash =
            java.security.MessageDigest
              .getInstance("SHA-256")
              .digest(newToken.toByteArray(Charsets.UTF_8))

          val oldCookieToken = call.request.cookies[sessionConfig.cookieName]

          try {
            database.withConnection { session ->
              var wasReminted = false
              if (oldCookieToken != null) {
                val oldHash =
                  java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(oldCookieToken.toByteArray(Charsets.UTF_8))
                val found =
                  ed.unicoach.db.dao.SessionsDao
                    .findByTokenHash(session, oldHash)
                if (found is ed.unicoach.db.dao.SessionFindResult.Success) {
                  ed.unicoach.db.dao.SessionsDao.remintToken(
                    session = session,
                    id = found.session.id,
                    currentVersion = found.session.version,
                    newUserId = result.user.id,
                    newTokenHash = newHash,
                    newExpirationSeconds = sessionConfig.expiration.seconds,
                  )
                  wasReminted = true
                }
              }

              if (!wasReminted) {
                ed.unicoach.db.dao.SessionsDao.create(
                  session = session,
                  newSession =
                    ed.unicoach.db.models.NewSession(
                      userId = result.user.id,
                      tokenHash =
                        ed.unicoach.db.models
                          .TokenHash(newHash),
                      userAgent = call.request.headers["User-Agent"],
                      initialIp = call.request.origin.remoteHost,
                      metadata = null,
                      expiration = sessionConfig.expiration,
                    ),
                )
              }
            }
          } catch (e: Exception) {
          }

          call.response.cookies.append(
            name = sessionConfig.cookieName,
            value = newToken,
            domain = sessionConfig.cookieDomain,
            path = "/",
            secure = sessionConfig.cookieSecure,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
          )

          call.respond(HttpStatusCode.Created, RegisterResponse(publicUser))
        }
        else -> {
          val status =
            when (result) {
              is AuthResult.ValidationFailure -> HttpStatusCode.BadRequest
              is AuthResult.DuplicateEmail -> HttpStatusCode.Conflict
              is AuthResult.DatabaseFailure -> HttpStatusCode.InternalServerError
              else -> HttpStatusCode.InternalServerError
            }
          call.respondAppError(result, status)
        }
      }
    }
  }
}
