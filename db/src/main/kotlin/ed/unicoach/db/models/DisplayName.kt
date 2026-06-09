package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

@JvmInline
value class DisplayName private constructor(
  val value: String,
) {
  companion object {
    fun create(value: String): ValidationResult<DisplayName> {
      val t = value.trim()
      return if (t.isBlank()) {
        ValidationResult.Invalid(ValidationError.BlankString)
      } else {
        ValidationResult.Valid(DisplayName(t))
      }
    }
  }
}
