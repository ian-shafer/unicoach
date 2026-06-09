package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

@JvmInline
value class PasswordHash private constructor(
  val value: String,
) {
  companion object {
    fun create(value: String): ValidationResult<PasswordHash> {
      val t = value.trim()
      return if (t.isBlank()) {
        ValidationResult.Invalid(ValidationError.BlankString)
      } else {
        ValidationResult.Valid(PasswordHash(t))
      }
    }
  }
}
