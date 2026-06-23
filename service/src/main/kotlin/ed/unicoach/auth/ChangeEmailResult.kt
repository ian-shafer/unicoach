package ed.unicoach.auth

import ed.unicoach.db.models.User

sealed interface ChangeEmailResult {
  data class Success(
    val user: User,
  ) : ChangeEmailResult

  data class ValidationFailure(
    val message: String,
  ) : ChangeEmailResult

  data class DuplicateEmail(
    val email: String,
  ) : ChangeEmailResult
}
