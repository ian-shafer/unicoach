package ed.unicoach.appstore

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.time.Duration

/**
 * Mints the App Store Server API bearer token via java-jwt's
 * [Algorithm.ECDSA256] (the hardened primitive; no hand-rolled signing): header
 * `{alg: ES256, kid, typ: JWT}`; claims `{iss: issuerId, iat: now, exp: now +
 * 5m, aud: "appstoreconnect-v1", bid: bundleId}`. Minted per call — an ECDSA
 * sign is cheap and statelessness beats an expiry cache.
 */
class AppStoreAuthTokens(
  private val credentials: AppStoreCredentials,
  private val bundleId: String,
  private val clock: Clock,
) {
  fun mint(): String {
    val now = clock.instant()
    return JWT
      .create()
      .withKeyId(credentials.keyId)
      .withIssuer(credentials.issuerId)
      .withIssuedAt(now)
      .withExpiresAt(now + TOKEN_LIFETIME)
      .withAudience(AUDIENCE)
      .withClaim(BUNDLE_ID_CLAIM, bundleId)
      .sign(Algorithm.ECDSA256(null as ECPublicKey?, credentials.privateKey))
  }

  companion object {
    const val AUDIENCE = "appstoreconnect-v1"
    const val BUNDLE_ID_CLAIM = "bid"
    val TOKEN_LIFETIME: Duration = Duration.ofMinutes(5)
  }
}
