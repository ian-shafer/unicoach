package ed.unicoach

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the packaged service.conf's required-env contract: the file resolves
 * with exactly APP_DOMAIN and PUBLIC_WEB_PORT supplied, and fails fast without
 * them. Every env that boots a service.conf consumer must provide both (.env
 * locally, the SSM prefix on the deploy host); a new required substitution
 * added to service.conf breaks the first test until its variable is documented
 * in that set.
 */
class ServiceConfTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `resolves offline with exactly APP_DOMAIN and PUBLIC_WEB_PORT`() {
    val requiredEnv =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
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
  }

  @Test
  fun `fails to resolve without the required environment`() {
    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      ConfigFactory.parseResources("service.conf").resolve(offlineOptions)
    }
  }
}
