package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.error.FieldError
import ed.unicoach.util.ValidationErrors
import ed.unicoach.util.Validator

data class RegistrationInput(
  val email: String,
  val name: String,
  val password: String,
)

class RegistrationValidator : Validator<RegistrationInput> {
  override fun validate(input: RegistrationInput): ValidationErrors {
    val fieldErrors = mutableListOf<FieldError>()

    // EmailAddress.create encodes the validity rule (non-blank, interior @) and
    // returns Invalid for both blank and malformed input, subsuming the prior
    // isBlank check and making AuthService.register's `as Valid` cast total.
    if (EmailAddress.create(input.email) is ValidationResult.Invalid) {
      fieldErrors.add(FieldError("email", "Email must be a valid email address"))
    }

    if (input.name.isBlank()) {
      fieldErrors.add(FieldError("name", "Name cannot be blank"))
    }

    if (input.password.codePointCount(0, input.password.length) < 8) {
      fieldErrors.add(FieldError("password", "Password must be at least 8 characters long"))
    }
    if (input.password.codePointCount(0, input.password.length) > 128) {
      fieldErrors.add(FieldError("password", "Password must be at most 128 characters long"))
    }

    if (!input.password.any { it in 'A'..'Z' }) {
      fieldErrors.add(FieldError("password", "Password must contain at least 1 uppercase letter"))
    }
    if (!input.password.any { it in 'a'..'z' }) {
      fieldErrors.add(FieldError("password", "Password must contain at least 1 lowercase letter"))
    }
    if (!input.password.any { it in '0'..'9' }) {
      fieldErrors.add(FieldError("password", "Password must contain at least 1 digit"))
    }

    return ValidationErrors(fieldErrors = fieldErrors)
  }
}
