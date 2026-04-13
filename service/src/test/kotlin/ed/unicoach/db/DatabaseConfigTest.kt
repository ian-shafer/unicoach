package ed.unicoach.db

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class DatabaseConfigTest {
  @Test
  fun `missing required configurations evaluate to failure with ConfigException`() {
    val emptyConfig = ConfigFactory.parseString("database {}")
    val result = DatabaseConfig.from(emptyConfig)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConfigException.Missing)
  }

  @Test
  fun `blank jdbcUrl fails with IllegalArgumentException`() {
    val badConfig =
      ConfigFactory.parseString(
        """
        database { 
          jdbcUrl = " "
          user = "hello"
          maximumPoolSize = 10
          connectionTimeout = 30000
        }
        """.trimIndent(),
      )

    val result = DatabaseConfig.from(badConfig)

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
  }
}
