package ed.unicoach.rest.plugins

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class JwtConfigTest {
  @Test
  fun `missing required configurations evaluate to failure with ConfigException`() {
    val emptyConfig = ConfigFactory.parseString("jwt {}")
    val result = JwtConfig.from(emptyConfig)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConfigException.Missing)
  }

  @Test
  fun `blank secret fails with IllegalArgumentException`() {
    val badConfig =
      ConfigFactory.parseString(
        """
        jwt { 
          secret = "   "
          issuer = "ed.unicoach"
          audience = "ed.unicoach"
        }
        """.trimIndent(),
      )

    val result = JwtConfig.from(badConfig)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
  }
}
