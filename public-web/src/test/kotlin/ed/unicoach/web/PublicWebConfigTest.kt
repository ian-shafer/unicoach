package ed.unicoach.web

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicWebConfigTest {
  @Test
  fun `parses host and port from the publicWeb server section`() {
    val config =
      ConfigFactory.parseString(
        """
        publicWeb {
          server {
            host = "0.0.0.0"
            port = 9090
          }
        }
        """.trimIndent(),
      )

    val result = PublicWebConfig.from(config)

    assertTrue(result.isSuccess)
    val parsed = result.getOrThrow()
    assertEquals("0.0.0.0", parsed.host)
    assertEquals(9090, parsed.port)
  }

  @Test
  fun `missing publicWeb section fails fast`() {
    val result = PublicWebConfig.from(ConfigFactory.parseString("{}"))

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
  }

  @Test
  fun `missing required port key fails fast with ConfigException`() {
    val config =
      ConfigFactory.parseString(
        """
        publicWeb {
          server {
            host = "127.0.0.1"
          }
        }
        """.trimIndent(),
      )

    val result = PublicWebConfig.from(config)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConfigException.Missing)
  }
}
