package ed.unicoach.db.models

sealed interface ValidationError {
    data object BlankString : ValidationError
    data object InvalidFormat : ValidationError
    data object TooLong : ValidationError
}

sealed interface ValidationResult<out T> {
    data class Valid<T>(val value: T) : ValidationResult<T>
    data class Invalid(val error: ValidationError) : ValidationResult<Nothing>
}
