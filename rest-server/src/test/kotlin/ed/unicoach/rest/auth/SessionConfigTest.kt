package ed.unicoach.rest.auth

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
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
}
