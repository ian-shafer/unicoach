package ed.unicoach.auth

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleTokenVerifierTest {
  @Test
  fun `stub decodes a valid fake token to the expected identity`() {
    val verifier = StubGoogleTokenVerifier()
    val identity =
      verifier
        .verify("stub:sub=12345;email=ada@example.com;email_verified=true;name=Ada Lovelace")
        .getOrThrow()

    assertEquals("12345", identity.subject)
    assertEquals("ada@example.com", identity.email)
    assertTrue(identity.emailVerified)
    assertEquals("Ada Lovelace", identity.name)
  }

  @Test
  fun `stub treats absent email_verified and name as false and null`() {
    val verifier = StubGoogleTokenVerifier()
    val identity = verifier.verify("stub:sub=9;email=x@example.com").getOrThrow()

    assertTrue(!identity.emailVerified)
    assertEquals(null, identity.name)
  }

  @Test
  fun `stub surfaces an invalid token as GoogleTokenInvalidException`() {
    val verifier = StubGoogleTokenVerifier()

    val invalid = verifier.verify(StubGoogleTokenVerifier.INVALID_TOKEN)
    assertTrue(invalid.isFailure && invalid.exceptionOrNull() is GoogleTokenInvalidException)

    val notPrefixed = verifier.verify("not-a-stub-token")
    assertTrue(notPrefixed.isFailure && notPrefixed.exceptionOrNull() is GoogleTokenInvalidException)

    val missingFields = verifier.verify("stub:email=only@example.com")
    assertTrue(missingFields.isFailure && missingFields.exceptionOrNull() is GoogleTokenInvalidException)
  }

  @Test
  fun `stub surfaces the unavailable token as GoogleTokenUnavailableException`() {
    val verifier = StubGoogleTokenVerifier()
    val result = verifier.verify(StubGoogleTokenVerifier.UNAVAILABLE_TOKEN)
    assertTrue(result.isFailure && result.exceptionOrNull() is GoogleTokenUnavailableException)
  }

  @Test
  fun `factory returns the stub for provider stub`() {
    val verifier = GoogleTokenVerifierFactory.fromConfig(stubConfig()).getOrThrow()
    assertTrue(verifier is StubGoogleTokenVerifier)
  }

  @Test
  fun `factory returns the JWKS verifier for provider google with clientIds`() {
    val verifier = GoogleTokenVerifierFactory.fromConfig(googleConfig(clientIds = listOf("client-1"))).getOrThrow()
    assertTrue(verifier is JwksGoogleTokenVerifier)
  }

  @Test
  fun `factory fails when clientIds is empty under provider google`() {
    val result = GoogleTokenVerifierFactory.fromConfig(googleConfig(clientIds = emptyList()))
    assertTrue(result.isFailure && result.exceptionOrNull() is IllegalArgumentException)
  }

  @Test
  fun `factory fails for an unknown provider`() {
    val result = GoogleTokenVerifierFactory.fromConfig(stubConfig().copy(provider = "mystery"))
    assertTrue(result.isFailure && result.exceptionOrNull() is IllegalArgumentException)
  }

  private fun stubConfig() = googleConfig(provider = "stub", clientIds = emptyList())

  private fun googleConfig(
    provider: String = "google",
    clientIds: List<String>,
  ) = GoogleAuthConfig(
    provider = provider,
    clientIds = clientIds,
    issuers = listOf("accounts.google.com", "https://accounts.google.com"),
    jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
    clockSkew = Duration.ofSeconds(60),
    connectTimeout = Duration.ofSeconds(10),
    readTimeout = Duration.ofSeconds(10),
  )
}
