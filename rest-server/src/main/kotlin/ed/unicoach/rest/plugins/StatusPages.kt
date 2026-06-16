package ed.unicoach.rest.plugins

import ed.unicoach.db.dao.CorruptPersistedAuthMethodException
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.rest.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
  install(StatusPages) {
    // Ktor maps an unreadable request body (JSON null, an unparseable payload, or a
    // non-application/json content type) to a 415 *response status* via
    // CannotTransformContentToTypeException, which is not a typed exception any
    // handler above intercepts. Rewrite that opaque 415 (text/plain) into the
    // contract's 400 JSON ErrorResponse. Responding 400 here does not recurse —
    // there is no status(400) handler.
    status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          code = "bad_request",
          message = "Request body could not be read as the expected application/json payload",
        ),
      )
    }
    exception<PayloadTooLargeException> { call, _ ->
      call.respond(
        HttpStatusCode.PayloadTooLarge,
        ErrorResponse("payload_too_large", "Request body exceeds the maximum allowed size"),
      )
    }
    exception<BadRequestException> { call, cause ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(code = "bad_request", message = "Invalid JSON payload structure"),
      )
    }
    exception<Throwable> { call, cause ->
      when (cause) {
        is PermanentError -> {
          val status =
            when (cause) {
              is NotFoundException -> HttpStatusCode.NotFound

              is DuplicateEmailException -> HttpStatusCode.Conflict

              is DatabaseException,
              is CorruptPersistedValueException,
              is CorruptPersistedAuthMethodException,
              -> HttpStatusCode.InternalServerError

              else -> HttpStatusCode.BadRequest
            }
          val message = cause.message ?: "Bad request"
          call.respond(status, ErrorResponse(code = "permanent_error", message = message))
        }

        is TransientError -> {
          val message = cause.message ?: "Internal server error"
          call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(code = "internal_error", message = message))
        }

        else -> {
          call.respond(HttpStatusCode.InternalServerError, ErrorResponse(code = "internal_error", message = "An internal error occurred"))
        }
      }
    }
  }
}
