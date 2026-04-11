package ed.unicoach.util

import ed.unicoach.error.FieldError

interface Validator<T> {
    fun validate(input: T): ValidationErrors
}

data class ValidationErrors(
    val errors: List<String> = emptyList(),
    val fieldErrors: List<FieldError> = emptyList()
) {
    fun hasErrors() = errors.isNotEmpty() || fieldErrors.isNotEmpty()
}
