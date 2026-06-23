package ed.unicoach.rest.plugins

import ed.unicoach.auth.AuthService
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.auth.resolveCaller
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond

/**
 * A `Plugins`-phase interceptor that blocks an authenticated-but-unverified
 * caller from protected endpoints with `403 email_not_verified`. Modeled on
 * [configureClientKeyGate]; registered after it so the coarse client-key check
 * runs ahead of this finer verification check.
 *
 * Behaviour, in order:
 * 1. Exempt paths (`/healthz`, anything under `/api/v1/auth/`) pass
 *    unconditionally — the entire allowlist, keeping the verification lifecycle
 *    reachable while unverified. Every other path is gated by default.
 * 2. A request with no resolvable caller passes — the downstream handler applies
 *    its own auth check and emits its `401 unauthorized`. The gate never converts
 *    an unauthenticated request into a 403.
 * 3. An authenticated caller whose `emailVerifiedAt` is null is rejected with
 *    `403 email_not_verified`. A verified caller falls through; its resolved
 *    caller is already cached for the handler.
 */
fun Application.configureEmailVerificationGate(
  authService: AuthService,
  sessionConfig: SessionConfig,
) {
  intercept(ApplicationCallPipeline.Plugins) {
    val path = call.request.path()
    if (path == "/healthz" || path.startsWith("/api/v1/auth/")) {
      return@intercept
    }

    val caller = call.resolveCaller(authService, sessionConfig) ?: return@intercept

    if (caller.user.emailVerifiedAt == null) {
      call.respond(
        HttpStatusCode.Forbidden,
        ErrorResponse(ErrorCode.EMAIL_NOT_VERIFIED, "Email verification required."),
      )
      finish()
    }
  }
}
