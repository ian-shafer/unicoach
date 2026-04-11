package ed.unicoach.auth

import ed.unicoach.db.models.FieldError

interface Validator<T> {
    fun validate(input: T): ValidationErrors
}

data class ValidationErrors(
    val errors: List<String> = emptyList(),
    val fieldErrors: List<FieldError> = emptyList()
) {
    fun hasErrors() = errors.isNotEmpty() || fieldErrors.isNotEmpty()
}

data class RegistrationInput(
    val email: String,
    val name: String,
    val password: String
)

class RegistrationValidator : Validator<RegistrationInput> {
    override fun validate(input: RegistrationInput): ValidationErrors {
        val fieldErrors = mutableListOf<FieldError>()
        
        if (input.email.isBlank()) {
            fieldErrors.add(FieldError("email", "Email cannot be blank"))
        }

        if (input.name.isBlank()) {
            fieldErrors.add(FieldError("name", "Name cannot be blank"))
        }

        if (input.password.length < 8) {
            fieldErrors.add(FieldError("password", "Password must be at least 8 characters long"))
        }
        if (input.password.length > 128) {
            fieldErrors.add(FieldError("password", "Password must be at most 128 characters long"))
        }
        
        if (!input.password.any { it.isUpperCase() }) {
            fieldErrors.add(FieldError("password", "Password must contain at least 1 uppercase letter"))
        }
        if (!input.password.any { it.isLowerCase() }) {
            fieldErrors.add(FieldError("password", "Password must contain at least 1 lowercase letter"))
        }
        if (!input.password.any { it.isDigit() }) {
            fieldErrors.add(FieldError("password", "Password must contain at least 1 digit"))
        }

        return ValidationErrors(fieldErrors = fieldErrors)
    }
}
