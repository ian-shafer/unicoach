package ed.unicoach.auth

import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.PersonName
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

    // EmailAddress.create encodes the validity rule (non-blank, at most 254
    // characters, interior @) and returns Invalid for every violation, subsuming
    // the prior isBlank check and making AuthService.register's `as Valid` cast
    // total — so an over-long address is a 400 here, not a CHECK violation and a
    // 500 at INSERT time.
    if (EmailAddress.create(input.email) is ValidationResult.Invalid) {
      fieldErrors.add(FieldError("email", "Email must be a valid email address"))
    }

    // PersonName.create owns the name rules (non-blank, within the users.name
    // length bound) and is the same gate register's `as Valid` cast relies on,
    // so validating through it keeps that cast total for every rule the type
    // grows.
    val name = PersonName.create(input.name)
    if (name is ValidationResult.Invalid) {
      fieldErrors.add(FieldError("name", mapNameError(name.error)))
    }

    passwordViolations(input.password).forEach { fieldErrors.add(FieldError("password", it.sentence)) }

    return ValidationErrors(fieldErrors = fieldErrors)
  }

  /** Renders the rule [PersonName] rejected on as the caller-facing `name` message. */
  private fun mapNameError(error: ValidationError): String =
    when (error) {
      is ValidationError.Blank -> "Name cannot be blank"
      is ValidationError.TooLong -> "Name must be at most ${error.maxLength} characters long"
      is ValidationError.InvalidFormat -> "Name must be of the form ${error.expected}"
    }
}
