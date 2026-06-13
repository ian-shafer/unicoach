package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.TokenHash
import ed.unicoach.error.FieldError
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.LoginRequest
import ed.unicoach.rest.models.LoginResponse
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
import io.ktor.server.routing.RoutingContext

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

class AuthRouteHandler(
  private val authService: AuthService,
  private val sessionConfig: ed.unicoach.rest.auth.SessionConfig,
) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/auth") {
      post("/register") { handleRegister() }
      route("/login") {
        post { handleLogin() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/me") {
        get { handleMe() }
        rejectUnsupportedMethods(HttpMethod.Get)
      }
      route("/logout") {
        post { handleLogout() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
    }
  }

  private suspend fun RoutingContext.handleRegister() {
    val request = call.receive<RegisterRequest>()

    val oldCookieToken = call.request.cookies[sessionConfig.cookieName]

    val outcome =
      authService
        .register(
          email = request.email,
          name = request.name,
          password = request.password,
          oldCookieToken = oldCookieToken,
          sessionExpirationSeconds = sessionConfig.expiration.seconds,
          userAgent = call.request.headers["User-Agent"],
          initialIp = call.request.origin.remoteHost,
        ).getOrThrow()

    respondRegisterOutcome(outcome)
  }

  private suspend fun RoutingContext.respondRegisterOutcome(outcome: ed.unicoach.auth.RegisterResult) {
    when (outcome) {
      is ed.unicoach.auth.RegisterResult.Success -> respondRegisterSuccess(outcome)
      is ed.unicoach.auth.RegisterResult.ValidationFailure -> respondRegisterValidationFailure(outcome)
      is ed.unicoach.auth.RegisterResult.DuplicateEmail -> respondRegisterDuplicateEmail()
    }
  }

  private suspend fun RoutingContext.respondRegisterSuccess(outcome: ed.unicoach.auth.RegisterResult.Success) {
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

  private suspend fun RoutingContext.respondRegisterValidationFailure(outcome: ed.unicoach.auth.RegisterResult.ValidationFailure) {
    val restFieldErrors =
      outcome.fieldErrors.map { FieldError(it.field, it.message) } +
        outcome.errors.map { FieldError("general", it) }
    call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_failed", "Invalid registration parameters", restFieldErrors))
  }

  private suspend fun RoutingContext.respondRegisterDuplicateEmail() {
    call.respond(
      HttpStatusCode.Conflict,
      ErrorResponse("conflict", "Email already in use", listOf(FieldError("email", "Email already in use"))),
    )
  }

  private suspend fun RoutingContext.handleMe() {
    val token = call.request.cookies[sessionConfig.cookieName]
    if (token == null) {
      call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Not authenticated"))
      return
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

  private suspend fun RoutingContext.handleLogout() {
    val token = call.request.cookies[sessionConfig.cookieName]
    if (token == null) {
      call.clearSessionCookie(sessionConfig)
      call.respond(HttpStatusCode.NoContent)
      return
    }

    val tokenHash = TokenHash.fromRawToken(token)
    authService.logout(tokenHash).getOrThrow()
    call.clearSessionCookie(sessionConfig)
    call.respond(HttpStatusCode.NoContent)
  }

  private suspend fun RoutingContext.handleLogin() {
    val request = call.receive<LoginRequest>()
    val oldCookieToken = call.request.cookies[sessionConfig.cookieName]

    val outcome =
      authService
        .login(
          email = request.email,
          password = request.password,
          oldCookieToken = oldCookieToken,
          sessionExpirationSeconds = sessionConfig.expiration.seconds,
          userAgent = call.request.headers["User-Agent"],
          initialIp = call.request.origin.remoteHost,
        ).getOrThrow()

    respondLoginOutcome(outcome)
  }

  private suspend fun RoutingContext.respondLoginOutcome(outcome: ed.unicoach.auth.LoginResult) {
    when (outcome) {
      is ed.unicoach.auth.LoginResult.Success -> respondLoginSuccess(outcome)
      is ed.unicoach.auth.LoginResult.InvalidEmail -> respondLoginUnauthorized(outcome)
      is ed.unicoach.auth.LoginResult.UserNotFound -> respondLoginUnauthorized(outcome)
      is ed.unicoach.auth.LoginResult.PasswordNotSet -> respondLoginUnauthorized(outcome)
      is ed.unicoach.auth.LoginResult.PasswordMismatch -> respondLoginUnauthorized(outcome)
    }
  }

  private suspend fun RoutingContext.respondLoginSuccess(outcome: ed.unicoach.auth.LoginResult.Success) {
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

    call.respond(HttpStatusCode.OK, LoginResponse(publicUser))
  }

  private suspend fun RoutingContext.respondLoginUnauthorized(outcome: ed.unicoach.auth.LoginResult) {
    call.application.environment.log
      .info("Login failed: $outcome")
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Invalid email or password", null))
  }
}
