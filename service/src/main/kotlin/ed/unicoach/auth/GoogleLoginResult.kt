package ed.unicoach.auth

import ed.unicoach.db.models.User

/** The outcome of [AuthService.loginWithGoogle]. */
sealed interface GoogleLoginResult {
  data class Success(
    val user: User,
    val token: String,
  ) : GoogleLoginResult

  /** The token was malformed, expired, or failed signature/claim verification. */
  data object InvalidToken : GoogleLoginResult

  /** The token verified but its `email_verified` claim was false. */
  data object EmailNotVerified : GoogleLoginResult

  /** The matched/linked user is soft-deleted. */
  data object AccountDisabled : GoogleLoginResult

  /** Google's JWKS endpoint was unreachable (transient). */
  data object VerificationUnavailable : GoogleLoginResult
}
