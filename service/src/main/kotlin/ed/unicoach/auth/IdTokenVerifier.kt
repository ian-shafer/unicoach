package ed.unicoach.auth

import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError

/**
 * Verifies a federated ID token (Google or Apple) and projects it into a
 * [FederatedIdentity]. Hidden behind an interface so it is swappable and
 * offline-testable, mirroring the chat-provider factory pattern.
 *
 * Implementations own their execution context: `verify` is safe to call from
 * any dispatcher, with any internal blocking IO (the JWKS fetch) shifted off
 * the caller's context by the adapter, never delegated to the caller.
 *
 * A `failure` carries one of:
 * - [IdTokenInvalidException] — any signature or claim failure (malformed,
 *   expired, wrong `aud`/`iss`, bad signature). Permanent: retrying is futile.
 * - [IdTokenUnavailableException] — the signing key could not be fetched.
 *   Transient: a retry may succeed.
 */
interface IdTokenVerifier {
  suspend fun verify(idToken: String): Result<FederatedIdentity>
}

/**
 * An [IdTokenVerifier] bound at the type level to the provider whose tokens it
 * may accept. [AuthService]'s two verifier slots no longer share a type, so
 * cross-wiring them is a compile error rather than a silent cross-provider
 * accept (`/auth/apple` honouring a Google-audience token).
 */
@JvmInline
value class GoogleIdTokenVerifier(
  val value: IdTokenVerifier,
)

/** The Apple-bound counterpart of [GoogleIdTokenVerifier]. */
@JvmInline
value class AppleIdTokenVerifier(
  val value: IdTokenVerifier,
)

/**
 * Fail-closed [IdTokenVerifier] for hosts that never serve an SSO route (e.g.
 * admin-web). It rejects every token, so wiring it can never accept a
 * credential — the explicit, production-safe alternative to silently
 * defaulting to a stub. [IdTokenVerifierFactory] is the only place a real
 * verifier is selected.
 */
object DisabledIdTokenVerifier : IdTokenVerifier {
  override suspend fun verify(idToken: String): Result<FederatedIdentity> =
    Result.failure(IdTokenInvalidException("SSO sign-in is not enabled on this host"))
}

/** A signature or claim failure — the token is permanently unacceptable. */
class IdTokenInvalidException(
  message: String = "ID token is invalid",
  cause: Throwable? = null,
) : RuntimeException(message, cause),
  PermanentError

/** The JWKS endpoint could not be reached — verification could not be completed. */
class IdTokenUnavailableException(
  message: String = "JWKS endpoint unavailable",
  cause: Throwable? = null,
) : RuntimeException(message, cause),
  TransientError
