package ed.unicoach.rest.plugins

import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.DuplicateEmailException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ed.unicoach.rest.StatusPages")

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
          code = ErrorCode.BAD_REQUEST,
          message = "Request body could not be read as the expected application/json payload",
        ),
      )
    }
    exception<PayloadTooLargeException> { call, _ ->
      call.respond(
        HttpStatusCode.PayloadTooLarge,
        ErrorResponse(ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds the maximum allowed size"),
      )
    }
    exception<BadRequestException> { call, cause ->
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(code = ErrorCode.BAD_REQUEST, message = "Invalid JSON payload structure"),
      )
    }
    exception<Throwable> { call, cause ->
      val (status, body) =
        when (cause) {
          is PermanentError -> {
            val status =
              when (cause) {
                is NotFoundException -> HttpStatusCode.NotFound

                is DuplicateEmailException -> HttpStatusCode.Conflict

                is DatabaseException,
                is CorruptPersistedValueException,
                -> HttpStatusCode.InternalServerError

                else -> HttpStatusCode.BadRequest
              }
            status to ErrorResponse(code = ErrorCode.PERMANENT_ERROR, message = cause.message ?: "Bad request")
          }

          is TransientError -> {
            HttpStatusCode.ServiceUnavailable to
              ErrorResponse(code = ErrorCode.INTERNAL_ERROR, message = cause.message ?: "Internal server error")
          }

          else -> {
            HttpStatusCode.InternalServerError to
              ErrorResponse(code = ErrorCode.INTERNAL_ERROR, message = "An internal error occurred")
          }
        }

      // A 5xx is a server fault: log the whole cause chain (stack trace) so the
      // root cause — e.g. the SQLException a DatabaseException wraps — is
      // recoverable from the log rather than collapsed into a generic message.
      // 4xx are expected domain outcomes already captured by request logging, so
      // they stay at debug to avoid drowning real faults.
      val request = "${call.request.httpMethod.value} ${call.request.uri}"
      if (status.value >= 500) {
        logger.error("[$request] failed with [${status.value}]", cause)
      } else {
        logger.debug("[$request] rejected with [${status.value}]: [${body.message}]")
      }
      call.respond(status, body)
    }
  }
}
