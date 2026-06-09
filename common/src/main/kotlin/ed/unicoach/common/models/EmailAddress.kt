package ed.unicoach.common.models

@JvmInline
value class EmailAddress private constructor(
  val value: String,
) {
  companion object {
    fun create(value: String): ValidationResult<EmailAddress> {
      val t = value.trim().lowercase()
      return when {
        t.isBlank() -> ValidationResult.Invalid(ValidationError.BlankString)
        !hasInteriorAtSign(t) -> ValidationResult.Invalid(ValidationError.InvalidFormat)
        else -> ValidationResult.Valid(EmailAddress(t))
      }
    }

    private fun hasInteriorAtSign(value: String): Boolean {
      val atIndex = value.indexOf('@')
      return atIndex > 0 && atIndex < value.length - 1
    }
  }
}
