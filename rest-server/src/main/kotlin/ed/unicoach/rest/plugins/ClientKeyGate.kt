package ed.unicoach.rest.plugins

import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.security.MessageDigest

const val CLIENT_KEY_HEADER = "X-Unicoach-Client-Key"

fun Application.configureClientKeyGate(config: ClientKeyGateConfig) {
  intercept(ApplicationCallPipeline.Plugins) {
    if (config.validKeys.isEmpty()) {
      return@intercept
    }

    if (call.request.path() in config.allowlistPaths) {
      return@intercept
    }

    val provided = call.request.headers[CLIENT_KEY_HEADER]
    if (provided == null || !matchesAnyKey(provided, config.validKeys)) {
      call.respond(
        HttpStatusCode.Forbidden,
        ErrorResponse(code = ErrorCode.FORBIDDEN, message = "Valid client key required."),
      )
      finish()
    }
  }
}

/**
 * Constant-time membership check. Folds over the entire key set with boolean-OR
 * accumulation so the first match does not short-circuit, keeping which key
 * matched and how many were checked unobservable through timing.
 */
private fun matchesAnyKey(
  provided: String,
  validKeys: Set<String>,
): Boolean {
  val providedBytes = provided.toByteArray(Charsets.UTF_8)
  var matched = false
  for (key in validKeys) {
    matched = MessageDigest.isEqual(providedBytes, key.toByteArray(Charsets.UTF_8)) || matched
  }
  return matched
}
