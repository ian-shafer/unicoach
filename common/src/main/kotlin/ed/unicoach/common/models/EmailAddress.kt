package ed.unicoach.common.models

/**
 * An email address. Trimmed and lowercased; rejected when blank, longer than 254
 * characters, or lacking an interior `@` — the same three rules the
 * `users_email_*` and `user_auth_identities_email_*` CHECK constraints enforce,
 * so any value of this type is storable in either column. Widening a constraint
 * without widening this type only narrows what the app accepts; narrowing one
 * without narrowing this type puts the CHECK back in the failure path.
 */
@JvmInline
value class EmailAddress private constructor(
  val value: String,
) {
  companion object {
    private const val MAX_LENGTH = 254

    fun create(value: String): ValidationResult<EmailAddress> {
      val t = value.trim().lowercase()
      return when {
        t.isBlank() -> ValidationResult.Invalid(ValidationError.Blank)

        // Counted the way Postgres `length()` counts, in characters: `String.length`
        // would score a surrogate pair as two and reject an address the CHECK
        // accepts — which UsersDao's row mapper then reads back through an `as Valid`
        // cast that assumes every stored address passes here.
        t.codePointCount(0, t.length) > MAX_LENGTH -> ValidationResult.Invalid(ValidationError.TooLong(MAX_LENGTH))

        !hasInteriorAtSign(t) -> ValidationResult.Invalid(ValidationError.InvalidFormat(expected = "local@domain"))

        else -> ValidationResult.Valid(EmailAddress(t))
      }
    }

    private fun hasInteriorAtSign(value: String): Boolean {
      val atIndex = value.indexOf('@')
      return atIndex > 0 && atIndex < value.length - 1
    }
  }
}
