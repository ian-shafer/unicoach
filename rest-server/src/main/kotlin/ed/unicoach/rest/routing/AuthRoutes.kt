package ed.unicoach.rest.routing

import ed.unicoach.auth.AuthService
import ed.unicoach.db.models.AuthProvider
import ed.unicoach.db.models.TokenHash
import ed.unicoach.error.FieldError
import ed.unicoach.rest.auth.resolveCaller
import ed.unicoach.rest.models.AppleLoginRequest
import ed.unicoach.rest.models.ChangeEmailRequest
import ed.unicoach.rest.models.ChangeEmailResponse
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.models.GoogleLoginRequest
import ed.unicoach.rest.models.LoginRequest
import ed.unicoach.rest.models.LoginResponse
import ed.unicoach.rest.models.MeResponse
import ed.unicoach.rest.models.PublicUser
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.RegisterResponse
import ed.unicoach.rest.models.VerifyEmailRequest
import ed.unicoach.rest.models.VerifyEmailResponse
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

private fun ApplicationCall.setSessionCookie(
  token: String,
  sessionConfig: ed.unicoach.rest.auth.SessionConfig,
) {
  response.cookies.append(
    name = sessionConfig.cookieName,
    value = token,
    domain = sessionConfig.cookieDomain,
    path = "/",
    secure = sessionConfig.cookieSecure,
    httpOnly = true,
    extensions = mapOf("SameSite" to "Strict"),
  )
}

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
  private val emailVerificationService: ed.unicoach.auth.EmailVerificationService,
  private val emailVerifier: ed.unicoach.auth.EmailVerifier,
) {
  fun registerRoutes(route: Route) {
    route.route("/api/v1/auth") {
      route("/register") {
        post { handleRegister() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/login") {
        post { handleLogin() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/google") {
        post { handleGoogleLogin() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/apple") {
        post { handleAppleLogin() }
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
      route("/verify-email") {
        post { handleVerifyEmail() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/resend-verification") {
        post { handleResendVerification() }
        rejectUnsupportedMethods(HttpMethod.Post)
      }
      route("/change-email") {
        post { handleChangeEmail() }
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
          sessionExpiration = sessionConfig.expiration,
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
    call.setSessionCookie(outcome.token, sessionConfig)
    call.respond(HttpStatusCode.Created, RegisterResponse(PublicUser.from(outcome.user)))
  }

  private suspend fun RoutingContext.respondRegisterValidationFailure(outcome: ed.unicoach.auth.RegisterResult.ValidationFailure) {
    val restFieldErrors =
      outcome.fieldErrors.map { FieldError(it.field, it.message) } +
        outcome.errors.map { FieldError("general", it) }
    call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCode.VALIDATION_FAILED, "Invalid registration parameters", restFieldErrors))
  }

  private suspend fun RoutingContext.respondRegisterDuplicateEmail() {
    call.respond(
      HttpStatusCode.Conflict,
      ErrorResponse(ErrorCode.CONFLICT, "Email already in use", listOf(FieldError("email", "Email already in use"))),
    )
  }

  private suspend fun RoutingContext.handleMe() {
    val user = call.resolveCaller(authService, sessionConfig)?.user
    if (user == null) {
      call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Not authenticated"))
    } else {
      call.respond(HttpStatusCode.OK, MeResponse(PublicUser.from(user)))
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
          sessionExpiration = sessionConfig.expiration,
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
    call.setSessionCookie(outcome.token, sessionConfig)
    call.respond(HttpStatusCode.OK, LoginResponse(PublicUser.from(outcome.user)))
  }

  private suspend fun RoutingContext.respondLoginUnauthorized(outcome: ed.unicoach.auth.LoginResult) {
    call.application.environment.log
      .info("Login failed: $outcome")
    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Invalid email or password", null))
  }

  private suspend fun RoutingContext.handleVerifyEmail() {
    val request = call.receive<VerifyEmailRequest>()
    val outcome = emailVerifier.verify(request.token).getOrThrow()
    when (outcome) {
      is ed.unicoach.auth.VerifyEmailResult.Success -> {
        call.respond(HttpStatusCode.OK, VerifyEmailResponse(PublicUser.from(outcome.user)))
      }

      is ed.unicoach.auth.VerifyEmailResult.InvalidToken -> {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCode.INVALID_TOKEN, "Verification token is invalid"))
      }

      is ed.unicoach.auth.VerifyEmailResult.Expired -> {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCode.TOKEN_EXPIRED, "Verification token has expired"))
      }

      is ed.unicoach.auth.VerifyEmailResult.AlreadyConsumed -> {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCode.TOKEN_ALREADY_USED, "Verification token has already been used"))
      }
    }
  }

  private suspend fun RoutingContext.handleGoogleLogin() {
    val request = call.receive<GoogleLoginRequest>()
    // GoogleLoginRequest (RFC 64) carries no name field — an absent `name`
    // claim falls back to the email local-part in AuthService.deriveName. Only
    // the Apple route, whose token never carries a name at all, supplies a
    // client-provided one.
    handleSsoLogin(AuthProvider.GOOGLE, request.idToken, clientProvidedName = null)
  }

  private suspend fun RoutingContext.handleAppleLogin() {
    val request = call.receive<AppleLoginRequest>()
    handleSsoLogin(AuthProvider.APPLE, request.idToken, clientProvidedName = request.name)
  }

  /**
   * The request-side half of the SSO contract, shared by both routes: each
   * handler owns only its provider's deserialization, mirroring the shared
   * [respondSsoLoginOutcome] on the response side.
   */
  private suspend fun RoutingContext.handleSsoLogin(
    provider: AuthProvider,
    idToken: String,
    clientProvidedName: String?,
  ) {
    val outcome =
      authService
        .loginWithSso(
          provider = provider,
          idToken = idToken,
          clientProvidedName = clientProvidedName,
          oldCookieToken = call.request.cookies[sessionConfig.cookieName],
          sessionExpiration = sessionConfig.expiration,
          userAgent = call.request.headers["User-Agent"],
          initialIp = call.request.origin.remoteHost,
        ).getOrThrow()

    respondSsoLoginOutcome(outcome, provider)
  }

  /**
   * Shared outcome-to-response mapper for both SSO routes (RFC 111 generalizes
   * RFC 64's Google-only mapper). [provider] parameterizes only log/message
   * wording — the status/code mapping is identical for both providers,
   * including [ed.unicoach.auth.SsoLoginResult.LinkBlockedUnverifiedEmail],
   * which either route can now return since the linking gate is shared.
   */
  private suspend fun RoutingContext.respondSsoLoginOutcome(
    outcome: ed.unicoach.auth.SsoLoginResult,
    provider: AuthProvider,
  ) {
    val providerLabel =
      when (provider) {
        AuthProvider.GOOGLE -> "Google"
        AuthProvider.APPLE -> "Apple"
      }
    when (outcome) {
      is ed.unicoach.auth.SsoLoginResult.Success -> {
        respondSsoLoginSuccess(outcome)
      }

      is ed.unicoach.auth.SsoLoginResult.InvalidToken -> {
        call.application.environment.log
          .info("SSO login failed [provider=$providerLabel] [outcome=$outcome]")
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Invalid $providerLabel ID token", null))
      }

      is ed.unicoach.auth.SsoLoginResult.EmailNotVerified -> {
        call.application.environment.log
          .info("SSO login failed [provider=$providerLabel] [outcome=$outcome]")
        call.respond(
          HttpStatusCode.Forbidden,
          ErrorResponse(ErrorCode.EMAIL_NOT_VERIFIED, "$providerLabel account email is not verified", null),
        )
      }

      is ed.unicoach.auth.SsoLoginResult.LinkBlockedUnverifiedEmail -> {
        call.application.environment.log
          .info("SSO login failed [provider=$providerLabel] [outcome=$outcome]")
        call.respond(
          HttpStatusCode.Forbidden,
          ErrorResponse(ErrorCode.ACCOUNT_EMAIL_NOT_VERIFIED, "The matched account's email is not verified", null),
        )
      }

      is ed.unicoach.auth.SsoLoginResult.AccountDisabled -> {
        call.application.environment.log
          .info("SSO login failed [provider=$providerLabel] [outcome=$outcome]")
        call.respond(HttpStatusCode.Forbidden, ErrorResponse(ErrorCode.ACCOUNT_DISABLED, "Account is disabled", null))
      }

      is ed.unicoach.auth.SsoLoginResult.VerificationUnavailable -> {
        call.application.environment.log
          .warn("SSO login failed [provider=$providerLabel] [outcome=$outcome]")
        call.respond(
          HttpStatusCode.ServiceUnavailable,
          ErrorResponse(ErrorCode.SERVICE_UNAVAILABLE, "$providerLabel sign-in is temporarily unavailable", null),
        )
      }
    }
  }

  private suspend fun RoutingContext.handleResendVerification() {
    val user = call.resolveCaller(authService, sessionConfig)?.user
    if (user == null) {
      call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Not authenticated"))
      return
    }

    // Idempotent: both Sent and AlreadyVerified collapse to 204 (no state leak).
    emailVerificationService.resend(user).getOrThrow()
    call.respond(HttpStatusCode.NoContent)
  }

  private suspend fun RoutingContext.respondSsoLoginSuccess(outcome: ed.unicoach.auth.SsoLoginResult.Success) {
    call.setSessionCookie(outcome.token, sessionConfig)
    call.respond(HttpStatusCode.OK, LoginResponse(PublicUser.from(outcome.user)))
  }

  private suspend fun RoutingContext.handleChangeEmail() {
    val user = call.resolveCaller(authService, sessionConfig)?.user
    if (user == null) {
      call.respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorCode.UNAUTHORIZED, "Not authenticated"))
      return
    }

    val request = call.receive<ChangeEmailRequest>()
    val outcome = authService.changeEmail(user, request.email).getOrThrow()
    when (outcome) {
      is ed.unicoach.auth.ChangeEmailResult.Success -> {
        call.respond(HttpStatusCode.OK, ChangeEmailResponse(PublicUser.from(outcome.user)))
      }

      is ed.unicoach.auth.ChangeEmailResult.ValidationFailure -> {
        call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(ErrorCode.VALIDATION_FAILED, "Invalid email", listOf(FieldError("email", outcome.message))),
        )
      }

      is ed.unicoach.auth.ChangeEmailResult.DuplicateEmail -> {
        call.respond(
          HttpStatusCode.Conflict,
          ErrorResponse(ErrorCode.CONFLICT, "Email already in use", listOf(FieldError("email", "Email already in use"))),
        )
      }
    }
  }
}
