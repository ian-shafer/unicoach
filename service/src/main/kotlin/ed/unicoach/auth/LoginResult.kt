package ed.unicoach.auth

import ed.unicoach.db.models.User
import ed.unicoach.db.models.ValidationError

sealed interface LoginResult {
    data class Success(val user: User, val token: String) : LoginResult
    data class InvalidEmail(val error: ValidationError) : LoginResult
    data object UserNotFound : LoginResult
    data object PasswordNotSet : LoginResult
    data object PasswordMismatch : LoginResult
}
