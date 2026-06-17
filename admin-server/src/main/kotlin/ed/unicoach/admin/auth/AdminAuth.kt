package ed.unicoach.admin.auth

import ed.unicoach.admin.AdminConfig
import ed.unicoach.admin.render.adminPage
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.LoginResult
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p

/**
 * The authenticated admin resolved by the gate, made available to downstream
 * handlers via call attributes. Handlers that run behind the gate can rely on
 * it being present.
 */
val CurrentAdminKey = AttributeKey<User>("CurrentAdmin")

val ApplicationCall.currentAdmin: User
  get() = attributes[CurrentAdminKey]

private const val GENERIC_LOGIN_ERROR = "invalid email or password"

private fun ApplicationCall.setSessionCookie(
  config: AdminConfig,
  token: String,
) {
  response.cookies.append(
    name = config.cookieName,
    value = token,
    domain = config.cookieDomain.ifBlank { null },
    path = "/",
    secure = config.cookieSecure,
    httpOnly = true,
    maxAge = config.sessionExpirationSeconds,
    extensions = mapOf("SameSite" to "Strict"),
  )
}

private fun ApplicationCall.clearSessionCookie(config: AdminConfig) {
  response.cookies.append(
    name = config.cookieName,
    value = "",
    domain = config.cookieDomain.ifBlank { null },
    path = "/",
    secure = config.cookieSecure,
    httpOnly = true,
    maxAge = 0L,
    extensions = mapOf("SameSite" to "Strict"),
  )
}

private suspend fun ApplicationCall.respondLoginForm(
  status: HttpStatusCode,
  error: String?,
) {
  respondHtml(status) {
    adminPage("Admin Login", nav = false) {
      h1 { +"Admin Login" }
      if (error != null) {
        p("error") { +error }
      }
      form(action = "/login", method = FormMethod.post) {
        div {
          label { +"Email" }
          input(type = InputType.text, name = "email")
        }
        div {
          label { +"Password" }
          input(type = InputType.password, name = "password")
        }
        button(type = kotlinx.html.ButtonType.submit) { +"Log in" }
      }
    }
  }
}

/**
 * Registers the unauthenticated login/logout routes. Login authenticates any
 * valid user; authorization (the `is_admin` check) is enforced separately by the
 * gate, so a successful login still cannot reach a gated page without admin.
 */
fun Route.adminAuthRoutes(
  authService: AuthService,
  config: AdminConfig,
) {
  get("/login") {
    call.respondLoginForm(HttpStatusCode.OK, error = null)
  }

  post("/login") {
    val params = call.receiveParameters()
    val email = params["email"].orEmpty()
    val password = params["password"].orEmpty()

    val result =
      authService
        .login(
          email = email,
          password = password,
          oldCookieToken = null,
          sessionExpirationSeconds = config.sessionExpirationSeconds,
          userAgent = call.request.headers["User-Agent"],
          initialIp = call.request.origin.remoteHost,
        ).getOrThrow()

    when (result) {
      is LoginResult.Success -> {
        call.setSessionCookie(config, result.token)
        call.respondRedirect("/")
      }

      else -> call.respondLoginForm(HttpStatusCode.Unauthorized, error = GENERIC_LOGIN_ERROR)
    }
  }

  post("/logout") {
    val token = call.request.cookies[config.cookieName]
    if (token != null) {
      authService.logout(TokenHash.fromRawToken(token)).getOrThrow()
    }
    call.clearSessionCookie(config)
    call.respondRedirect("/login")
  }
}

/**
 * Installs the `is_admin` gate on every request whose path is not exempt
 * (`/login`, `/logout`, `/healthz`). Resolution outcomes:
 * - missing/invalid cookie or unknown token -> redirect to `/login`;
 * - resolved user with `is_admin == false` -> 403 "not authorized" page;
 * - resolved admin -> request proceeds with the [User] in call attributes.
 */
fun Application.installAdminGate(
  authService: AuthService,
  config: AdminConfig,
) {
  val exemptPaths = setOf("/login", "/logout", "/healthz")

  intercept(ApplicationCallPipeline.Plugins) {
    val path =
      call.request.local.uri
        .substringBefore('?')
    if (path in exemptPaths) {
      return@intercept
    }

    val token = call.request.cookies[config.cookieName]
    if (token.isNullOrBlank()) {
      call.respondRedirect("/login")
      return@intercept finish()
    }

    val user = authService.getCurrentUser(TokenHash.fromRawToken(token)).getOrThrow()
    if (user == null) {
      call.respondRedirect("/login")
      return@intercept finish()
    }

    if (!user.isAdmin) {
      call.respondHtml(HttpStatusCode.Forbidden) {
        adminPage("Not Authorized", nav = false) {
          h1 { +"403 Not Authorized" }
          p { +"Your account is not an administrator." }
        }
      }
      return@intercept finish()
    }

    call.attributes.put(CurrentAdminKey, user)
  }
}
