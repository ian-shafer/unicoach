package ed.unicoach.auth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdTokenVerifierTest {
  @Test
  fun `stub decodes a valid fake token to the expected identity`() {
    runBlocking {
      val verifier = StubIdTokenVerifier()
      val identity =
        verifier
          .verify("stub:sub=12345;email=ada@example.com;email_verified=true;name=Ada Lovelace")
          .getOrThrow()

      assertEquals("12345", identity.subject)
      assertEquals("ada@example.com", identity.email)
      assertTrue(identity.emailVerified)
      assertEquals("Ada Lovelace", identity.name)
    }
  }

  @Test
  fun `stub treats absent email_verified and name as false and null`() {
    runBlocking {
      val verifier = StubIdTokenVerifier()
      val identity = verifier.verify("stub:sub=9;email=x@example.com").getOrThrow()

      assertTrue(!identity.emailVerified)
      assertEquals(null, identity.name)
    }
  }

  @Test
  fun `stub surfaces an invalid token as IdTokenInvalidException`() {
    runBlocking {
      val verifier = StubIdTokenVerifier()

      val invalid = verifier.verify(StubIdTokenVerifier.INVALID_TOKEN)
      assertTrue(invalid.isFailure && invalid.exceptionOrNull() is IdTokenInvalidException)

      val notPrefixed = verifier.verify("not-a-stub-token")
      assertTrue(notPrefixed.isFailure && notPrefixed.exceptionOrNull() is IdTokenInvalidException)

      val missingFields = verifier.verify("stub:email=only@example.com")
      assertTrue(missingFields.isFailure && missingFields.exceptionOrNull() is IdTokenInvalidException)
    }
  }

  @Test
  fun `stub surfaces the unavailable token as IdTokenUnavailableException`() {
    runBlocking {
      val verifier = StubIdTokenVerifier()
      val result = verifier.verify(StubIdTokenVerifier.UNAVAILABLE_TOKEN)
      assertTrue(result.isFailure && result.exceptionOrNull() is IdTokenUnavailableException)
    }
  }

  @Test
  fun `factory returns the stub for provider stub`() {
    val verifier = IdTokenVerifierFactory.googleFromConfig(stubConfig()).getOrThrow()
    assertTrue(verifier.value is StubIdTokenVerifier)
  }

  @Test
  fun `factory returns the JWKS verifier for provider google with clientIds`() {
    val verifier = IdTokenVerifierFactory.googleFromConfig(providerConfig(clientIds = listOf("client-1"))).getOrThrow()
    assertTrue(verifier.value is JwksIdTokenVerifier)
  }

  @Test
  fun `factory fails when clientIds is empty under provider google`() {
    val result = IdTokenVerifierFactory.googleFromConfig(providerConfig(clientIds = emptyList()))
    assertTrue(result.isFailure && result.exceptionOrNull() is IllegalArgumentException)
    val message = result.exceptionOrNull()!!.message!!
    assertContains(message, "auth.google.clientIds")
    assertContains(message, "GOOGLE_CLIENT_IDS")
  }

  @Test
  fun `factory fails for an unknown provider`() {
    val result = IdTokenVerifierFactory.googleFromConfig(stubConfig().copy(provider = "mystery"))
    assertTrue(result.isFailure && result.exceptionOrNull() is IllegalArgumentException)
    val message = result.exceptionOrNull()!!.message!!
    assertContains(message, "auth.google.provider")
    assertContains(message, "[mystery]")
    assertContains(message, "[stub]")
  }

  @Test
  fun `apple factory returns the JWKS verifier for provider apple with clientIds`() {
    val verifier =
      IdTokenVerifierFactory
        .appleFromConfig(providerConfig(provider = "apple", clientIds = listOf("coach.uni.UnicoachiOS")))
        .getOrThrow()
    assertTrue(verifier.value is JwksIdTokenVerifier)
  }

  @Test
  fun `factory fails when clientIds is empty under provider apple`() {
    val result = IdTokenVerifierFactory.appleFromConfig(providerConfig(provider = "apple", clientIds = emptyList()))
    assertTrue(result.isFailure && result.exceptionOrNull() is IllegalArgumentException)
    val message = result.exceptionOrNull()!!.message!!
    assertContains(message, "auth.apple.clientIds")
    assertContains(message, "APPLE_CLIENT_IDS")
  }

  @Test
  fun `apple factory rejects a google provider block`() {
    val result = IdTokenVerifierFactory.appleFromConfig(providerConfig(provider = "google", clientIds = listOf("client-1")))
    assertTrue(result.isFailure && result.exceptionOrNull() is IllegalArgumentException)
    val message = result.exceptionOrNull()!!.message!!
    assertContains(message, "auth.apple.provider")
    assertContains(message, "[google]")
  }

  private fun stubConfig() = providerConfig(provider = "stub", clientIds = emptyList())

  private fun providerConfig(
    provider: String = "google",
    clientIds: List<String>,
  ) = SsoProviderConfig(
    provider = provider,
    clientIds = clientIds,
    issuers = listOf("accounts.google.com", "https://accounts.google.com"),
    jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
    clockSkew = Duration.ofSeconds(60),
    connectTimeout = Duration.ofSeconds(10),
    readTimeout = Duration.ofSeconds(10),
  )
}
