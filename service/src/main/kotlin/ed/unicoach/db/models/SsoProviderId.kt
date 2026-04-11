package ed.unicoach.db.models

@JvmInline
value class SsoProviderId private constructor(
  val value: String,
) {
  companion object {
    fun create(value: String): ValidationResult<SsoProviderId> {
      val t = value.trim()
      return if (t.isBlank()) {
        ValidationResult.Invalid(ValidationError.BlankString)
      } else {
        ValidationResult.Valid(SsoProviderId(t))
      }
    }
  }
}
