package ed.unicoach.appstore

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.Duration
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [AppStoreConfig.from] (RFC 110): the two environments and their derived
 * base URLs, the all-or-nothing credential unit, and each named parse refusal.
 */
class AppStoreConfigTest {
  private fun config(
    environment: String = "sandbox",
    issuerId: String? = null,
    keyId: String? = null,
    privateKey: String? = null,
    baseUrl: String? = null,
  ) = ConfigFactory.parseString(
    buildString {
      appendLine("appStore.environment = \"$environment\"")
      appendLine("appStore.bundleId = \"coach.uni.UnicoachiOS\"")
      appendLine("appStore.connectTimeout = \"10s\"")
      appendLine("appStore.requestTimeout = \"15s\"")
      if (issuerId != null) appendLine("appStore.issuerId = \"$issuerId\"")
      if (keyId != null) appendLine("appStore.keyId = \"$keyId\"")
      if (privateKey != null) appendLine("appStore.privateKey = \"$privateKey\"")
      if (baseUrl != null) appendLine("appStore.baseUrl = \"$baseUrl\"")
    },
  )

  @Test
  fun `sandbox parses and derives the sandbox base URL`() {
    val parsed = AppStoreConfig.from(config(environment = "sandbox")).getOrThrow()
    assertEquals(AppStoreEnvironment.SANDBOX, parsed.environment)
    assertEquals("https://api.storekit-sandbox.itunes.apple.com", parsed.baseUrl)
    assertEquals("coach.uni.UnicoachiOS", parsed.bundleId)
    assertEquals(Duration.ofSeconds(10), parsed.connectTimeout)
    assertEquals(Duration.ofSeconds(15), parsed.requestTimeout)
  }

  @Test
  fun `production parses and derives the production base URL`() {
    val parsed = AppStoreConfig.from(config(environment = "production")).getOrThrow()
    assertEquals(AppStoreEnvironment.PRODUCTION, parsed.environment)
    assertEquals("https://api.storekit.itunes.apple.com", parsed.baseUrl)
  }

  @Test
  fun `an explicit baseUrl overrides the derivation`() {
    val parsed = AppStoreConfig.from(config(baseUrl = "http://localhost:9999")).getOrThrow()
    assertEquals("http://localhost:9999", parsed.baseUrl)
  }

  @Test
  fun `an unknown environment fails naming the key`() {
    val failure = AppStoreConfig.from(config(environment = "staging")).exceptionOrNull()
    assertNotNull(failure)
    assertTrue(failure.message!!.contains("appStore.environment"), failure.message)
  }

  @Test
  fun `all-absent credentials parse as the null unit`() {
    val parsed = AppStoreConfig.from(config()).getOrThrow()
    assertNull(parsed.credentials, "an unconfigured box is valid; verify answers 503")
  }

  @Test
  fun `a full credential trio parses into the unit`() {
    val parsed =
      AppStoreConfig
        .from(
          config(
            issuerId = AppStoreTestFixtures.ISSUER_ID,
            keyId = AppStoreTestFixtures.KEY_ID,
            privateKey = AppStoreTestFixtures.privateKeyBase64,
          ),
        ).getOrThrow()
    val credentials = assertNotNull(parsed.credentials)
    assertEquals(AppStoreTestFixtures.ISSUER_ID, credentials.issuerId)
    assertEquals(AppStoreTestFixtures.KEY_ID, credentials.keyId)
    assertEquals("EC", credentials.privateKey.algorithm)
  }

  @Test
  fun `partial credentials fail naming the missing keys`() {
    val failure =
      AppStoreConfig
        .from(config(issuerId = "iss", keyId = "key"))
        .exceptionOrNull()
    assertNotNull(failure)
    assertTrue(failure.message!!.contains("appStore.privateKey"), failure.message)
  }

  @Test
  fun `a malformed base64 private key fails naming the key`() {
    val failure =
      AppStoreConfig
        .from(config(issuerId = "iss", keyId = "key", privateKey = "!!not-base64!!"))
        .exceptionOrNull()
    assertNotNull(failure)
    assertTrue(failure.message!!.contains("appStore.privateKey"), failure.message)
  }

  @Test
  fun `a non-EC private key fails naming the key`() {
    val rsaKey =
      KeyPairGenerator
        .getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()
        .private
    val failure =
      AppStoreConfig
        .from(
          config(
            issuerId = "iss",
            keyId = "key",
            privateKey = Base64.getEncoder().encodeToString(rsaKey.encoded),
          ),
        ).exceptionOrNull()
    assertNotNull(failure)
    assertTrue(failure.message!!.contains("appStore.privateKey"), failure.message)
  }
}
