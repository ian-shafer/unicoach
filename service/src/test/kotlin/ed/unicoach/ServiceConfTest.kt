package ed.unicoach

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the packaged service.conf's required-env contract: the file resolves with
 * exactly APP_DOMAIN, PUBLIC_WEB_PORT, and GOOGLE_AUTH_PROVIDER supplied, and
 * fails fast without them. Every env that boots a service.conf consumer must
 * provide all three (.env.dev locally, the .env.<env>/SSM env on the deploy host);
 * a new required substitution added to service.conf breaks the first test until
 * its variable is documented in that set. GOOGLE_AUTH_PROVIDER is a required
 * substitution (no .conf default) so a forgotten prod override fails the JVM at
 * boot instead of silently running the offline `stub` verifier (RFC 95).
 */
class ServiceConfTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `resolves offline with exactly APP_DOMAIN, PUBLIC_WEB_PORT, and GOOGLE_AUTH_PROVIDER`() {
    val requiredEnv =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = stub
        """.trimIndent(),
      )

    val resolved =
      requiredEnv
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)

    assertEquals(
      "http://localhost:8082/verify-email",
      resolved.getString("emailVerification.verifyUrlBase"),
    )
    assertEquals("stub", resolved.getString("auth.google.provider"))
  }

  @Test
  fun `fails to resolve without the required environment`() {
    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      ConfigFactory.parseResources("service.conf").resolve(offlineOptions)
    }
  }

  @Test
  fun `required GOOGLE_AUTH_PROVIDER fails to resolve when unset`() {
    // Supply every OTHER required substitution but omit GOOGLE_AUTH_PROVIDER, so
    // the failure isolates to that toggle: an unset provider must fail the JVM at
    // boot, never fall back to the offline stub verifier.
    val withoutToggle =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        """.trimIndent(),
      )

    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      withoutToggle
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)
    }
  }

  @Test
  fun `GOOGLE_AUTH_PROVIDER resolves to the set value`() {
    val withToggle =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = google
        """.trimIndent(),
      )

    val resolved =
      withToggle
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)

    assertEquals("google", resolved.getString("auth.google.provider"))
  }
}
