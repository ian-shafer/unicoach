package ed.unicoach.auth

import com.auth0.jwk.Jwk
import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.RateLimitReachedException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real [JwksIdTokenVerifier] against locally signed JWTs and an
 * in-test [JwkProvider] fake serving an in-test RSA keypair's public half — no
 * network access. RFC 64 shipped [JwksIdTokenVerifier] (then
 * `JwksGoogleTokenVerifier`) untested; RFC 111's Apple `email_verified`
 * string/boolean quirk lives here, so this class closes that gap.
 */
class JwksIdTokenVerifierTest {
  companion object {
    private const val ISSUER = "https://issuer.test"
    private const val CLIENT_ID = "test-client-id"
    private const val KEY_ID = "test-kid"
    private const val UNKNOWN_KEY_ID = "unknown-kid"
    private const val TRANSPORT_FAIL_KEY_ID = "transport-fail-kid"
    private const val EC_KEY_ID = "ec-kid"
    private const val RATE_LIMITED_KEY_ID = "rate-limited-kid"

    private lateinit var publicKey: RSAPublicKey
    private lateinit var privateKey: RSAPrivateKey
    private lateinit var algorithm: Algorithm
    private lateinit var ecPublicKey: ECPublicKey

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
      publicKey = keyPair.public as RSAPublicKey
      privateKey = keyPair.private as RSAPrivateKey
      algorithm = Algorithm.RSA256(publicKey, privateKey)
      val ecKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
      ecPublicKey = ecKeyPair.public as ECPublicKey
    }
  }

  /** Fake [JwkProvider]: resolves [KEY_ID] to the in-test public key, no network access. */
  private val fakeJwkProvider =
    object : JwkProvider {
      override fun get(keyId: String): Jwk =
        when (keyId) {
          KEY_ID -> {
            Jwk.fromValues(
              mapOf(
                "kid" to KEY_ID,
                "kty" to "RSA",
                "n" to encodeUnsignedBigInt(publicKey.modulus),
                "e" to encodeUnsignedBigInt(publicKey.publicExponent),
              ),
            )
          }

          // A well-formed JWKS entry the verifier can never use: RS256 needs an
          // RSA key, so this EC key must be refused permanently, not as transient.
          EC_KEY_ID -> {
            Jwk.fromValues(
              mapOf(
                "kid" to EC_KEY_ID,
                "kty" to "EC",
                "crv" to "P-256",
                "x" to encodeUnsignedBigInt(ecPublicKey.w.affineX),
                "y" to encodeUnsignedBigInt(ecPublicKey.w.affineY),
              ),
            )
          }

          TRANSPORT_FAIL_KEY_ID -> {
            throw JwkException("Could not reach the JWKS endpoint", java.io.IOException("simulated transport failure"))
          }

          // Exactly what RateLimitedJwkProvider throws once its bucket is empty:
          // a JwkException carrying no cause at all.
          RATE_LIMITED_KEY_ID -> {
            throw RateLimitReachedException(60_000L)
          }

          else -> {
            throw JwkException("Unknown key id [$keyId]")
          }
        }
    }

  private fun verifier(clockSkew: Duration = Duration.ofSeconds(60)) =
    JwksIdTokenVerifier(
      jwkProvider = fakeJwkProvider,
      issuers = listOf(ISSUER),
      clientIds = listOf(CLIENT_ID),
      clockSkew = clockSkew,
    )

  private fun encodeUnsignedBigInt(value: BigInteger): String {
    var bytes = value.toByteArray()
    if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun buildToken(
    subject: String? = "user-sub",
    email: String? = "user@example.com",
    emailVerifiedBoolean: Boolean? = null,
    emailVerifiedString: String? = null,
    name: String? = null,
    audiences: List<String> = listOf(CLIENT_ID),
    issuer: String = ISSUER,
    expiresAt: Instant = Instant.now().plusSeconds(3600),
    keyId: String = KEY_ID,
  ): String {
    val builder =
      JWT
        .create()
        .withIssuer(issuer)
        .withAudience(*audiences.toTypedArray())
        .withExpiresAt(expiresAt)
        .withIssuedAt(Instant.now())
        .withKeyId(keyId)
    if (subject != null) builder.withSubject(subject)
    if (email != null) builder.withClaim("email", email)
    if (emailVerifiedBoolean != null) builder.withClaim("email_verified", emailVerifiedBoolean)
    if (emailVerifiedString != null) builder.withClaim("email_verified", emailVerifiedString)
    if (name != null) builder.withClaim("name", name)
    return builder.sign(algorithm)
  }

  @Test
  fun `boolean email_verified true yields an identity with emailVerified true`() {
    runBlocking {
      val identity = verifier().verify(buildToken(emailVerifiedBoolean = true)).getOrThrow()
      assertTrue(identity.emailVerified)
    }
  }

  @Test
  fun `string email_verified true yields an identity with emailVerified true, the Apple shape`() {
    runBlocking {
      val identity = verifier().verify(buildToken(emailVerifiedString = "true")).getOrThrow()
      assertTrue(identity.emailVerified)
    }
  }

  @Test
  fun `string email_verified false yields an identity with emailVerified false`() {
    runBlocking {
      val identity = verifier().verify(buildToken(emailVerifiedString = "false")).getOrThrow()
      assertTrue(!identity.emailVerified)
    }
  }

  @Test
  fun `absent email_verified yields an identity with emailVerified false`() {
    runBlocking {
      val identity = verifier().verify(buildToken()).getOrThrow()
      assertTrue(!identity.emailVerified)
    }
  }

  @Test
  fun `missing email claim fails as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier().verify(buildToken(email = null))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `missing sub claim fails as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier().verify(buildToken(subject = null))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `audience not in clientIds fails as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier().verify(buildToken(audiences = listOf("some-other-client")))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  /**
   * java-jwt's `withAnyOfAudience` is a `containsAny` assertion, so a token
   * minted for us *and* another client satisfies it with the surplus entry
   * never examined. The verifier's own exact-shape check is what rejects it.
   */
  @Test
  fun `an accepted audience alongside a surplus one fails as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier().verify(buildToken(audiences = listOf(CLIENT_ID, "some-other-client")))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `issuer mismatch fails as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier().verify(buildToken(issuer = "https://not-the-configured-issuer.test"))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `expired beyond clockSkew leeway fails as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier(clockSkew = Duration.ofSeconds(60)).verify(buildToken(expiresAt = Instant.now().minusSeconds(120)))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `unknown kid surfaces a non-transport JwkException as IdTokenInvalidException`() {
    runBlocking {
      val result = verifier().verify(buildToken(keyId = UNKNOWN_KEY_ID))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `a non-RSA signing key fails as IdTokenInvalidException, not as transient unavailability`() {
    runBlocking {
      val result = verifier().verify(buildToken(keyId = EC_KEY_ID))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `JwkProvider transport failure fails as IdTokenUnavailableException`() {
    runBlocking {
      val result = verifier().verify(buildToken(keyId = TRANSPORT_FAIL_KEY_ID))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenUnavailableException)
    }
  }

  @Test
  fun `an exhausted JWKS fetch bucket fails as IdTokenUnavailableException, not a token fault`() {
    runBlocking {
      val result = verifier().verify(buildToken(keyId = RATE_LIMITED_KEY_ID))
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenUnavailableException)
    }
  }

  @Test
  fun `absent name claim yields an identity with name null`() {
    runBlocking {
      val identity = verifier().verify(buildToken()).getOrThrow()
      assertNull(identity.name)
    }
  }

  @Test
  fun `a fully populated token decodes to the expected identity`() {
    runBlocking {
      val identity =
        verifier()
          .verify(buildToken(subject = "sub-123", email = "ada@example.com", emailVerifiedBoolean = true, name = "Ada Lovelace"))
          .getOrThrow()
      assertEquals("sub-123", identity.subject)
      assertEquals("ada@example.com", identity.email)
      assertTrue(identity.emailVerified)
      assertEquals("Ada Lovelace", identity.name)
    }
  }
}
