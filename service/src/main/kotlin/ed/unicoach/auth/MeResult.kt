package ed.unicoach.auth

import ed.unicoach.db.models.User
import ed.unicoach.error.ExceptionWrapper

sealed interface MeResult {
  data class Authenticated(
    val user: User,
  ) : MeResult

  data object Unauthenticated : MeResult

  data class DatabaseFailure(
    val error: ExceptionWrapper,
  ) : MeResult
}
