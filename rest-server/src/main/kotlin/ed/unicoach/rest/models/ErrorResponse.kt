package ed.unicoach.rest.models

import ed.unicoach.error.FieldError

data class ErrorResponse(
  val code: ErrorCode,
  val message: String,
  val fieldErrors: List<FieldError>? = null,
)
