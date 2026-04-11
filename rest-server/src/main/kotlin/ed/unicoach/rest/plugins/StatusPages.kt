package ed.unicoach.rest.plugins

import ed.unicoach.rest.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
  install(StatusPages) {
    exception<UnsupportedMediaTypeException> { call, cause ->
      call.respond(
        HttpStatusCode.UnsupportedMediaType,
        ErrorResponse(code = "unsupported_media_type", message = cause.message ?: "Unsupported media type"),
      )
    }
    exception<BadRequestException> { call, cause ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(code = "bad_request", message = "Invalid JSON payload structure"),
      )
    }
  }
}
