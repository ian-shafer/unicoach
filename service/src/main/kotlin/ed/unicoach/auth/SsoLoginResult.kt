package ed.unicoach.auth

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.User

/** The outcome of [AuthService.loginWithSso]. */
sealed interface SsoLoginResult {
  data class Success(
    val user: User,
    val token: String,
  ) : SsoLoginResult

  /**
   * The token was malformed, expired, failed signature/claim verification, or
   * carried a `sub`/`email` this system cannot store. [reason] is internal
   * diagnostic context — the route still answers a generic 401.
   */
  data class InvalidToken(
    val reason: InvalidTokenReason,
  ) : SsoLoginResult

  /** The token verified but its `email_verified` claim was false. */
  data object EmailNotVerified : SsoLoginResult

  /** The matched/linked user is soft-deleted. */
  data object AccountDisabled : SsoLoginResult

  /**
   * The provider's JWKS endpoint was unreachable (transient). [cause] keeps the
   * transport failure for logging; the caller still sees a generic 503.
   */
  data class VerificationUnavailable(
    val cause: IdTokenUnavailableException,
  ) : SsoLoginResult

  /**
   * The token verified and its email matches an existing active user, but
   * that user holds a password credential whose email was never verified.
   * Blocks the *unverified* pre-hijacking case only: an attacker-registered
   * account that the victim later verifies (by clicking the mail
   * [AuthService.register] sent them) still links, leaving the attacker's
   * password in place. That residual vector is open. Nothing is written when
   * this is returned. (A match with no password credential — a prior SSO
   * provisioning — is never blocked here regardless of `emailVerifiedAt`; see
   * [AuthService.resolveOrProvisionUser].)
   */
  data object LinkBlockedUnverifiedEmail : SsoLoginResult
}

/**
 * Why an ID token was rejected. Logged, never sent to the caller. Carries only
 * the failure's shape — the offending claim value is user data and stays out.
 */
sealed interface InvalidTokenReason {
  /** The verifier rejected the token (decode, signature, `aud`/`iss`, expiry). */
  data class VerificationFailed(
    val cause: IdTokenInvalidException,
  ) : InvalidTokenReason

  /** The verified `sub` claim is not a storable [ed.unicoach.db.models.ProviderSubject]. */
  data class UnusableSubject(
    val error: ValidationError,
  ) : InvalidTokenReason

  /** The verified `email` claim is not a storable [ed.unicoach.common.models.EmailAddress]. */
  data class UnusableEmail(
    val error: ValidationError,
  ) : InvalidTokenReason
}
