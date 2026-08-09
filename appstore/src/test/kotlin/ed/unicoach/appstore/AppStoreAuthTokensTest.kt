package ed.unicoach.appstore

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.junit.jupiter.api.Test
import java.security.interfaces.ECPrivateKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

/**
 * Pins [AppStoreAuthTokens.mint] (RFC 110): the minted token verifies with the
 * test public key via java-jwt (real ES256, no hand-rolled signing), and
 * carries exactly the header and claims Apple's API requires.
 */
class AppStoreAuthTokensTest {
  // Fixed at a whole second so iat/exp round-trip JWT's second precision exactly.
  private val fixedNow: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val tokens = AppStoreTestFixtures.authTokens(Clock.fixed(fixedNow, ZoneOffset.UTC))

  @Test
  fun `the minted token verifies with the test public key and carries the API's claims`() {
    val token = tokens.mint()

    val verified =
      JWT
        .require(Algorithm.ECDSA256(AppStoreTestFixtures.publicKey, null as ECPrivateKey?))
        .withIssuer(AppStoreTestFixtures.ISSUER_ID)
        .acceptLeeway(600)
        .build()
        .verify(token)

    assertEquals("ES256", verified.algorithm)
    assertEquals(AppStoreTestFixtures.KEY_ID, verified.keyId)
    assertEquals(AppStoreTestFixtures.ISSUER_ID, verified.issuer)
    assertEquals(listOf(AppStoreAuthTokens.AUDIENCE), verified.audience)
    assertEquals("appstoreconnect-v1", verified.audience.single())
    assertEquals(AppStoreTestFixtures.BUNDLE_ID, verified.getClaim(AppStoreAuthTokens.BUNDLE_ID_CLAIM).asString())
    assertEquals(fixedNow, verified.issuedAtAsInstant)
    assertEquals(
      AppStoreAuthTokens.TOKEN_LIFETIME,
      java.time.Duration.between(verified.issuedAtAsInstant, verified.expiresAtAsInstant),
      "exp − iat is the 5-minute lifetime, measured against the fixed clock",
    )
  }
}
