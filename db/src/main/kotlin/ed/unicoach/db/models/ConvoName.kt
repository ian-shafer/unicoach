package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

@JvmInline
value class ConvoName private constructor(
  val value: String,
) {
  companion object {
    private const val MAX_LENGTH = 255

    fun create(value: String): ValidationResult<ConvoName> {
      val t = value.trim()
      return when {
        t.isBlank() -> ValidationResult.Invalid(ValidationError.Blank)
        t.length > MAX_LENGTH -> ValidationResult.Invalid(ValidationError.TooLong(maxLength = MAX_LENGTH))
        else -> ValidationResult.Valid(ConvoName(t))
      }
    }
  }
}
