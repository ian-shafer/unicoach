package ed.unicoach.auth

import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError

/**
 * Verifies a Google ID token and projects it into a [GoogleIdentity]. Hidden
 * behind an interface so it is swappable and offline-testable, mirroring the
 * chat-provider factory pattern.
 *
 * A `failure` carries one of:
 * - [GoogleTokenInvalidException] — any signature or claim failure (malformed,
 *   expired, wrong `aud`/`iss`, bad signature). Permanent: retrying is futile.
 * - [GoogleTokenUnavailableException] — the JWKS endpoint could not be reached.
 *   Transient: a retry may succeed.
 */
interface GoogleTokenVerifier {
  fun verify(idToken: String): Result<GoogleIdentity>
}

/**
 * Fail-closed [GoogleTokenVerifier] for hosts that never serve a Google sign-in
 * route (e.g. admin-server). It rejects every token, so wiring it can never
 * accept a credential — the explicit, production-safe alternative to silently
 * defaulting to a stub. [GoogleTokenVerifierFactory] is the only place a real
 * verifier is selected.
 */
object DisabledGoogleTokenVerifier : GoogleTokenVerifier {
  override fun verify(idToken: String): Result<GoogleIdentity> =
    Result.failure(GoogleTokenInvalidException("Google sign-in is not enabled on this host"))
}

/** A signature or claim failure — the token is permanently unacceptable. */
class GoogleTokenInvalidException(
  message: String = "Google ID token is invalid",
  cause: Throwable? = null,
) : RuntimeException(message, cause),
  PermanentError

/** The JWKS endpoint could not be reached — verification could not be completed. */
class GoogleTokenUnavailableException(
  message: String = "Google JWKS endpoint unavailable",
  cause: Throwable? = null,
) : RuntimeException(message, cause),
  TransientError
