package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

/**
 * A person's name as stored in `users.name`. Trimmed; rejected when blank or
 * longer than 255 characters, matching the `users_name_length_check` constraint
 * (db/schema/0001.create-users.sql).
 */
@JvmInline
value class PersonName private constructor(
  val value: String,
) {
  companion object {
    private const val MAX_LENGTH = 255

    fun create(value: String): ValidationResult<PersonName> {
      val t = value.trim()
      return when {
        t.isBlank() -> ValidationResult.Invalid(ValidationError.Blank)
        t.length > MAX_LENGTH -> ValidationResult.Invalid(ValidationError.TooLong(maxLength = MAX_LENGTH))
        else -> ValidationResult.Valid(PersonName(t))
      }
    }
  }
}
