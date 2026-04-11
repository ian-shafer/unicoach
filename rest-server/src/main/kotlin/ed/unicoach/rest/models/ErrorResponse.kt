package ed.unicoach.rest.models

data class ErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: List<FieldError>? = null
)
