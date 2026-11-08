package ed.unicoach.auth

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.User

/** The answer to a request for a reset link. */
sealed interface PasswordResetRequest {
  /** One answer whether or not the address has an account, so the form cannot be used to find out. */
  data object Submitted : PasswordResetRequest

  /** The address is not well-formed, which says nothing about whether an account exists. */
  data class InvalidEmail(
    val error: ValidationError,
  ) : PasswordResetRequest
}

/** What a mailed link can do right now. */
sealed interface ResetLink {
  data object Usable : ResetLink

  sealed interface Dead : ResetLink {
    /** [reason] is for logs and audit, never the page: every cause reads the same to the person holding the link. */
    data class NotValid(
      val reason: NotValidReason,
    ) : Dead

    enum class NotValidReason {
      /** Nothing presented matches a token that was issued: an unknown hash, or no single token to look up. */
      UNKNOWN_TOKEN,

      /** A real, unspent, unexpired token whose account has closed or moved to another address. */
      OWNER_CHANGED,
    }

    data object Expired : Dead

    /** Spent, or burned by a newer request. */
    data object AlreadyUsed : Dead
  }
}

/** The answer to a posted new password. */
sealed interface PasswordResetResult {
  data class Done(
    val user: User,
    val sessionToken: String,
  ) : PasswordResetResult

  /** The password breaks a rule. The token was not spent. */
  data class Rejected(
    val violations: List<PasswordViolation>,
  ) : PasswordResetResult

  data class Dead(
    val link: ResetLink.Dead,
  ) : PasswordResetResult
}
