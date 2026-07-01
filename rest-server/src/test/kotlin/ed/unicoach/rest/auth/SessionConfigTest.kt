package ed.unicoach.rest.auth

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionConfigTest {
  @Test
  fun `successfully parses valid session configuration`() {
    val rawConfig =
      ConfigFactory.parseString(
        """
        session {
            cookieName = "TEST_COOKIE"
            cookieDomain = "example.com"
            cookieSecure = true
            expiration = "14d"
        }
        """.trimIndent(),
      )

    val result = SessionConfig.from(rawConfig)
    assertTrue(result.isSuccess)
    val config = result.getOrThrow()

    assertEquals("TEST_COOKIE", config.cookieName)
    assertEquals("example.com", config.cookieDomain)
    assertTrue(config.cookieSecure)
    assertEquals(Duration.ofDays(14), config.expiration)
  }

  @Test
  fun `fails gracefully when session block is missing`() {
    val rawConfig = ConfigFactory.parseString("{}")
    val result = SessionConfig.from(rawConfig)
    assertTrue(result.isFailure)
  }

  @Test
  fun `fails gracefully when types are incorrect`() {
    val rawConfig =
      ConfigFactory.parseString(
        """
        session {
            cookieName = "COOKIE"
            cookieDomain = "example.com"
            cookieSecure = "NotABoolean"
            expiration = "7d"
        }
        """.trimIndent(),
      )

    val result = SessionConfig.from(rawConfig)
    assertTrue(result.isFailure)
  }

  @Test
  fun `derives cookieDomain from APP_DOMAIN substitution in rest-server conf`() {
    // cookieDomain and server.port now both pull from required ${VAR} substitutions
    // (no HOCON default); both must be supplied for the packaged conf to resolve.
    val config =
      ConfigFactory
        .parseString(
          """
          APP_DOMAIN = cookie.example.test
          SERVER_PORT = 8080
          """.trimIndent(),
        ).withFallback(ConfigFactory.parseResources("rest-server.conf"))
        .resolve()

    val result = SessionConfig.from(config)
    assertTrue(result.isSuccess)
    assertEquals("cookie.example.test", result.getOrThrow().cookieDomain)
  }

  @Test
  fun `cookieDomain is required from APP_DOMAIN with no HOCON default`() {
    // The literal "localhost" default was removed: cookieDomain = ${APP_DOMAIN} is
    // a required substitution. Resolving the packaged conf without APP_DOMAIN (and
    // without system-env fallback) leaves it unresolved and resolution fails.
    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      ConfigFactory
        .parseResources("rest-server.conf")
        .resolve(
          ConfigResolveOptions
            .defaults()
            .setUseSystemEnvironment(false),
        )
    }
  }
}
