package ed.unicoach.db

import com.typesafe.config.Config
import ed.unicoach.common.config.getNonBlankString

class DatabaseConfig private constructor(
  val jdbcUrl: String,
  val user: String,
  val password: String?,
  val maximumPoolSize: Int,
  val connectionTimeout: Long,
) {
  companion object {
    fun from(config: Config): Result<DatabaseConfig> =
      runCatching {
        val jdbcUrl = config.getNonBlankString("database.jdbcUrl")
        val user = config.getNonBlankString("database.user")

        // Passwords might be legitimately empty in local dev, but if path exists it shouldn't be null
        val password = if (config.hasPath("database.password")) config.getString("database.password") else null

        val maximumPoolSize = config.getInt("database.maximumPoolSize")
        val connectionTimeout = config.getLong("database.connectionTimeout")

        DatabaseConfig(
          jdbcUrl = jdbcUrl,
          user = user,
          password = password,
          maximumPoolSize = maximumPoolSize,
          connectionTimeout = connectionTimeout,
        )
      }
  }
}
