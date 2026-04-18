package ed.unicoach.db.models

@JvmInline
value class EmailAddress private constructor(
  val value: String,
) {
  companion object {
    fun create(value: String): ValidationResult<EmailAddress> {
      val t = value.trim().lowercase()
      return if (t.isBlank()) {
        ValidationResult.Invalid(ValidationError.BlankString)
      } else {
        ValidationResult.Valid(EmailAddress(t))
      }
    }
  }
}
