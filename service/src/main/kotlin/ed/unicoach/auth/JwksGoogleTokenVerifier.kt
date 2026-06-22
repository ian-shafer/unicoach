package ed.unicoach.auth

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import java.net.SocketTimeoutException
import java.security.interfaces.RSAPublicKey
import java.time.Duration

/**
 * Production [GoogleTokenVerifier]: verifies the RS256 signature against Google's
 * JWKS and checks `iss`/`aud`/`exp`/`iat`, then reads `sub`, `email`,
 * `email_verified`, `name`.
 *
 * A JWKS-fetch/transport failure surfaces as [GoogleTokenUnavailableException]
 * (transient); any signature or claim failure surfaces as
 * [GoogleTokenInvalidException] (permanent).
 */
class JwksGoogleTokenVerifier(
  private val jwkProvider: JwkProvider,
  private val issuers: List<String>,
  private val clientIds: List<String>,
  private val clockSkew: Duration,
) : GoogleTokenVerifier {
  override fun verify(idToken: String): Result<GoogleIdentity> {
    val decoded =
      try {
        JWT.decode(idToken)
      } catch (e: Exception) {
        return Result.failure(GoogleTokenInvalidException("Google ID token could not be decoded", e))
      }

    val publicKey =
      try {
        jwkProvider.get(decoded.keyId).publicKey as RSAPublicKey
      } catch (e: JwkException) {
        // jwks-rsa wraps a fetch/transport failure in JwkException; an unreachable
        // endpoint is transient, while an unknown/absent key is a token fault.
        return if (isTransport(e)) {
          Result.failure(GoogleTokenUnavailableException("Could not fetch Google signing key", e))
        } else {
          Result.failure(GoogleTokenInvalidException("Unknown Google signing key", e))
        }
      } catch (e: Exception) {
        return Result.failure(GoogleTokenUnavailableException("Could not fetch Google signing key", e))
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
        return Result.failure(GoogleTokenInvalidException("Google ID token failed verification", e))
      }

    return readIdentity(verified)
  }

  private fun readIdentity(jwt: DecodedJWT): Result<GoogleIdentity> {
    val subject = jwt.subject?.takeIf { it.isNotBlank() }
    val email = jwt.getClaim("email").asString()?.takeIf { it.isNotBlank() }
    if (subject == null || email == null) {
      return Result.failure(GoogleTokenInvalidException("Google ID token is missing sub or email"))
    }
    val emailVerified = jwt.getClaim("email_verified")
    return Result.success(
      GoogleIdentity(
        subject = subject,
        email = email,
        // Absent email_verified is treated as false.
        emailVerified = !emailVerified.isNull && !emailVerified.isMissing && emailVerified.asBoolean() == true,
        name = jwt.getClaim("name").asString(),
      ),
    )
  }

  private fun isTransport(e: Throwable?): Boolean {
    var cur = e
    while (cur != null) {
      if (cur is SocketTimeoutException || cur is java.net.UnknownHostException || cur is java.io.IOException) return true
      cur = cur.cause
    }
    return false
  }
}
