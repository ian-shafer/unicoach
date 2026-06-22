package ed.unicoach.db.models

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

/**
 * A provider's stable subject identifier (Google's `sub` claim) — the only key
 * used to resolve a returning login. Trimmed; rejected when blank or longer than
 * 255 characters, matching `user_auth_identities_subject_*` constraints.
 */
@JvmInline
value class ProviderSubject private constructor(
  val value: String,
) {
  companion object {
    private const val MAX_LENGTH = 255

    fun create(value: String): ValidationResult<ProviderSubject> {
      val t = value.trim()
      return when {
        t.isBlank() -> ValidationResult.Invalid(ValidationError.Blank)
        t.length > MAX_LENGTH -> ValidationResult.Invalid(ValidationError.TooLong(MAX_LENGTH))
        else -> ValidationResult.Valid(ProviderSubject(t))
      }
    }
  }
}
