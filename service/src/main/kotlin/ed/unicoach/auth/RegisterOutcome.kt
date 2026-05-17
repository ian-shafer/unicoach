package ed.unicoach.auth

import ed.unicoach.db.models.User
import ed.unicoach.error.FieldError

sealed interface RegisterOutcome {
  data class Success(
    val user: User,
    val token: String,
  ) : RegisterOutcome

  data class ValidationFailure(
    val errors: List<String>,
    val fieldErrors: List<FieldError>,
  ) : RegisterOutcome

  data class DuplicateEmail(
    val email: String,
  ) : RegisterOutcome
}
