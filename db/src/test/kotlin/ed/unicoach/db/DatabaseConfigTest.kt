package ed.unicoach.db

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseConfigTest {
  private fun resolvedJdbcUrl(substitutions: Map<String, String>): String =
    ConfigFactory.parseMap(substitutions)
      .withFallback(ConfigFactory.parseResources("db.conf"))
      .resolve()
      .getString("database.jdbcUrl")

  @Test
  fun `jdbcUrl defaults host to localhost when DATABASE_HOST is absent`() {
    val jdbcUrl =
      resolvedJdbcUrl(
        mapOf(
          "POSTGRES_PORT" to "5432",
          "POSTGRES_DB" to "unicoach",
        ),
      )

    assertEquals("jdbc:postgresql://localhost:5432/unicoach", jdbcUrl)
  }

  @Test
  fun `jdbcUrl uses DATABASE_HOST override when present`() {
    val jdbcUrl =
      resolvedJdbcUrl(
        mapOf(
          "POSTGRES_PORT" to "5432",
          "POSTGRES_DB" to "unicoach",
          "DATABASE_HOST" to "rds.example",
        ),
      )

    assertEquals("jdbc:postgresql://rds.example:5432/unicoach", jdbcUrl)
  }

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
