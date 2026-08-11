package ed.unicoach.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.RateLimitReachedException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.security.interfaces.RSAPublicKey
import java.time.Duration

/**
 * Production [IdTokenVerifier] shared by both providers: verifies the RS256
 * signature against the configured JWKS and checks `iss`/`aud`/`exp`/`iat`,
 * then reads `sub`, `email`, `email_verified`, `name`. One class serves both
 * Google and Apple, differing only by constructor inputs ([jwkProvider],
 * [issuers], [clientIds], [clockSkew]).
 *
 * A JWKS-fetch failure — an unreachable endpoint, or a fetch bucket exhausted by
 * key rotation — surfaces as [IdTokenUnavailableException] (transient); any
 * key-type, signature or claim failure surfaces as [IdTokenInvalidException]
 * (permanent).
 */
class JwksIdTokenVerifier(
  private val jwkProvider: JwkProvider,
  private val issuers: List<String>,
  private val clientIds: List<String>,
  private val clockSkew: Duration,
  // The JWKS fetch is blocking network IO bounded by the configured
  // connect/read timeouts. The verifier owns that context so no caller has to
  // know it blocks (as Database and Argon2Hasher do).
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdTokenVerifier {
  override suspend fun verify(idToken: String): Result<FederatedIdentity> = withContext(dispatcher) { verifyBlocking(idToken) }

  private fun verifyBlocking(idToken: String): Result<FederatedIdentity> {
    val decoded =
      try {
        JWT.decode(idToken)
      } catch (e: Exception) {
        return Result.failure(IdTokenInvalidException("ID token could not be decoded", e))
      }

    val publicKey =
      try {
        val key = jwkProvider.get(decoded.keyId).publicKey
        // RS256 is the only algorithm verified below, so an RSA key is the whole
        // permitted set; any other key type is permanently unusable, not transient.
        key as? RSAPublicKey
          ?: return Result.failure(IdTokenInvalidException("Signing key is not an RSA key [kid=${decoded.keyId}]"))
      } catch (e: JwkException) {
        // jwks-rsa reports both key-lookup and fetch failures as JwkException; an
        // unreachable endpoint or an exhausted fetch bucket is transient, while an
        // unknown/absent key is a token fault.
        return if (isTransient(e)) {
          Result.failure(IdTokenUnavailableException("Could not fetch signing key [kid=${decoded.keyId}, issuers=$issuers]", e))
        } else {
          Result.failure(IdTokenInvalidException("Unknown signing key [kid=${decoded.keyId}, issuers=$issuers]", e))
        }
      } catch (e: Exception) {
        return Result.failure(
          IdTokenUnavailableException("Could not fetch signing key [kid=${decoded.keyId}, issuers=$issuers]", e),
        )
      }

    val verified =
      try {
        val algorithm = Algorithm.RSA256(publicKey, null)
        JWT
          .require(algorithm)
          .withIssuer(*issuers.toTypedArray())
          .withAnyOfAudience(*clientIds.toTypedArray())
          .acceptLeeway(clockSkew.seconds)
          .build()
          .verify(idToken)
      } catch (e: JWTVerificationException) {
        return Result.failure(IdTokenInvalidException("ID token failed verification", e))
      }

    // Both providers mint a single-audience token. withAnyOfAudience above only
    // asserts that `aud` CONTAINS an accepted id, so a token minted for another
    // client as well would pass with the surplus ignored; require the exact shape.
    val audience = verified.audience.orEmpty()
    if (audience.size != 1 || audience.single() !in clientIds) {
      return Result.failure(
        IdTokenInvalidException("ID token audience is not exactly one accepted client id [audience=$audience]"),
      )
    }

    return readIdentity(verified)
  }

  private fun readIdentity(jwt: DecodedJWT): Result<FederatedIdentity> {
    val subject = jwt.subject?.takeIf { it.isNotBlank() }
    val email = jwt.getClaim("email").asString()?.takeIf { it.isNotBlank() }
    if (subject == null || email == null) {
      // Names the absent claims only — never their values, which are the end
      // user's identifiers and must not reach a log line.
      val missing = listOfNotNull("sub".takeIf { subject == null }, "email".takeIf { email == null })
      return Result.failure(
        IdTokenInvalidException("ID token is missing required claims [missing=$missing, issuer=${jwt.issuer}, kid=${jwt.keyId}]"),
      )
    }
    return Result.success(
      FederatedIdentity(
        subject = subject,
        email = email,
        emailVerified = readEmailVerified(jwt),
        name = jwt.getClaim("name").asString(),
      ),
    )
  }

  /**
   * `email_verified` arrives as a JSON boolean from Google, and as the string
   * `"true"`/`"false"` from Apple. Any other value, or absence, reads as
   * unverified.
   */
  private fun readEmailVerified(jwt: DecodedJWT): Boolean {
    val claim = jwt.getClaim("email_verified")
    if (claim.isNull || claim.isMissing) return false
    claim.asBoolean()?.let { return it }
    return claim.asString() == "true"
  }

  /**
   * Whether a [JwkException] describes a condition a retry may clear, as opposed
   * to a fault in the token itself.
   *
   * [RateLimitReachedException] is checked by type because it is constructed with
   * no cause at all: the fetch bucket refills on a timer, so an exhausted bucket
   * is transient, but a cause-chain walk alone would read it as an unknown key
   * and answer 401 where 503 is owed.
   */
  private fun isTransient(e: JwkException): Boolean = e is RateLimitReachedException || hasTransportCause(e)

  private fun hasTransportCause(e: Throwable?): Boolean {
    var cur = e
    while (cur != null) {
      if (cur is SocketTimeoutException || cur is java.net.UnknownHostException || cur is java.io.IOException) return true
      cur = cur.cause
    }
    return false
  }
}
