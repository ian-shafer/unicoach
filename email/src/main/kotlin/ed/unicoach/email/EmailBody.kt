package ed.unicoach.email

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult

@JvmInline
value class EmailBody private constructor(
  val value: String,
) {
  companion object {
    // Character bound chosen to approximate the queue's 64 KiB payload cap; not
    // an exact byte equivalence — multi-byte UTF-8 may exceed 64 KiB on the wire.
    const val MAX_BODY_LENGTH = 65536

    fun create(value: String): ValidationResult<EmailBody> =
      when {
        value.isBlank() -> ValidationResult.Invalid(ValidationError.Blank)
        value.length > MAX_BODY_LENGTH -> ValidationResult.Invalid(ValidationError.TooLong(maxLength = MAX_BODY_LENGTH))
        else -> ValidationResult.Valid(EmailBody(value))
      }
  }
}
