package ed.unicoach.rest.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.event.Level

/**
 * Logs one line per HTTP request at INFO — method, request URI, and the final
 * response status. Installed first in `appModule` so its interceptor wraps the
 * whole pipeline and reports the status the client actually received, including
 * responses short-circuited by a gate (client-key, email-verification) or
 * rewritten by content negotiation (e.g. a `406` when the `Accept` header matches
 * no registered converter). Without it the server emits no per-request line, so a
 * client-visible failure leaves no server-side trace.
 */
fun Application.configureRequestLogging() {
  install(CallLogging) {
    level = Level.INFO
    format { call ->
      val method = call.request.httpMethod.value
      val status =
        call.response
          .status()
          ?.value
          ?.toString() ?: "no-response"
      "$method ${call.request.uri} -> $status"
    }
  }
}
