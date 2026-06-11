package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

@JvmInline
value class EmailSubject private constructor(
  val value: String,
) {
  companion object {
    const val MAX_SUBJECT_LENGTH = 255

    fun create(value: String): ValidationResult<EmailSubject> =
      when {
        value.isBlank() -> ValidationResult.Invalid(ValidationError.Blank)
        value.length > MAX_SUBJECT_LENGTH -> ValidationResult.Invalid(ValidationError.TooLong(maxLength = MAX_SUBJECT_LENGTH))
        else -> ValidationResult.Valid(EmailSubject(value))
      }
  }
}
