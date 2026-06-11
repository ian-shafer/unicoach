package ed.unicoach.common.models

sealed interface ValidationError {
  data object Blank : ValidationError

  data class InvalidFormat(
    val expected: String,
  ) : ValidationError

  data class TooLong(
    val maxLength: Int,
  ) : ValidationError
}

sealed interface ValidationResult<out T> {
  data class Valid<T>(
    val value: T,
  ) : ValidationResult<T>

  data class Invalid(
    val error: ValidationError,
  ) : ValidationResult<Nothing>
}
