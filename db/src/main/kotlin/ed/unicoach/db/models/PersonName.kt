package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

@JvmInline
value class PersonName private constructor(
  val value: String,
) {
  companion object {
    fun create(value: String): ValidationResult<PersonName> {
      val t = value.trim()
      return if (t.isBlank()) {
        ValidationResult.Invalid(ValidationError.Blank)
      } else {
        ValidationResult.Valid(PersonName(t))
      }
    }
  }
}
