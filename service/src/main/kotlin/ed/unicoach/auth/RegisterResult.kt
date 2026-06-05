package ed.unicoach.auth

import ed.unicoach.db.models.User
import ed.unicoach.error.FieldError

sealed interface RegisterResult {
  data class Success(
    val user: User,
    val token: String,
  ) : RegisterResult

  data class ValidationFailure(
    val errors: List<String>,
    val fieldErrors: List<FieldError>,
  ) : RegisterResult

  data class DuplicateEmail(
    val email: String,
  ) : RegisterResult
}
