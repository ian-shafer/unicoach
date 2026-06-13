package ed.unicoach.rest.config

import com.typesafe.config.Config
import ed.unicoach.common.util.DataSize

data class RequestSizeConfig(
  val defaultMax: DataSize,
  val routeOverrides: Map<String, DataSize>,
  val routePrefixOverrides: Map<String, DataSize> = emptyMap(),
) {
  companion object {
    fun from(config: Config): Result<RequestSizeConfig> {
      return try {
        if (!config.hasPath("server.requestSize")) {
          return Result.failure(IllegalArgumentException("Missing configuration section: server.requestSize"))
        }

        val requestSizeConfig = config.getConfig("server.requestSize")

        val defaultMax = DataSize.ofBytes(requestSizeConfig.getBytes("maxSize"))

        val routeOverrides = readOverrides(requestSizeConfig, "routeOverrides")
        val routePrefixOverrides = readOverrides(requestSizeConfig, "routePrefixOverrides")

        Result.success(RequestSizeConfig(defaultMax, routeOverrides, routePrefixOverrides))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }

    private fun readOverrides(
      requestSizeConfig: Config,
      key: String,
    ): Map<String, DataSize> =
      if (requestSizeConfig.hasPath(key)) {
        val overridesConfig = requestSizeConfig.getConfig(key)
        overridesConfig
          .root()
          .keys
          .associateWith { path -> DataSize.ofBytes(overridesConfig.getBytes("\"$path\"")) }
      } else {
        emptyMap()
      }
  }
}
