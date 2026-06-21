package ed.unicoach.auth

/** Outcome of a resend-verification attempt for an authenticated user. */
sealed interface ResendResult {
  /** A fresh token was issued and a verification email was attempted. */
  data object Sent : ResendResult

  /** The user is already verified; no token issued, no email sent. */
  data object AlreadyVerified : ResendResult
}
