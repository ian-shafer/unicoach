package ed.unicoach.auth

import ed.unicoach.error.ExceptionWrapper

sealed interface LogoutResult {
  data object Success : LogoutResult

  data class DatabaseFailure(
    val error: ExceptionWrapper,
  ) : LogoutResult
}
