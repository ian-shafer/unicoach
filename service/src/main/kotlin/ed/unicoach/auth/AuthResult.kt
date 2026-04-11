package ed.unicoach.auth

import ed.unicoach.db.models.User
import ed.unicoach.db.models.FieldError
import ed.unicoach.error.AppError

sealed interface AuthResult {
    data class Success(
        val user: User,
        val token: String
    ) : AuthResult

    data class ValidationFailure(
        val errors: List<String>,
        val fieldErrors: List<FieldError>
    ) : AuthResult

    data class DuplicateEmail(
        val email: String
    ) : AuthResult

    data class DatabaseFailure(
        override val rootCause: AppError
    ) : AuthResult, AppError
}
