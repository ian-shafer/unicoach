package ed.unicoach.auth

import ed.unicoach.db.models.User

/** Outcome of a verify-email attempt against a supplied raw token. */
sealed interface VerifyEmailResult {
  data class Success(
    val user: User,
  ) : VerifyEmailResult

  /** The token hash matches no row. */
  data object InvalidToken : VerifyEmailResult

  /** The token exists but its expiry has passed. */
  data object Expired : VerifyEmailResult

  /** The token exists but was already consumed. */
  data object AlreadyConsumed : VerifyEmailResult
}
