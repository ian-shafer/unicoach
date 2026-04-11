package ed.unicoach.rest.models

import ed.unicoach.error.FieldError

data class ErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: List<FieldError>? = null
)
