package ed.unicoach.rest.config

import com.typesafe.config.Config
import ed.unicoach.common.util.DataSize

data class RequestSizeConfig(
  val defaultMax: DataSize,
  val routeOverrides: Map<String, DataSize>,
) {
  companion object {
    fun from(config: Config): Result<RequestSizeConfig> {
      return try {
        if (!config.hasPath("server.requestSize")) {
          return Result.failure(IllegalArgumentException("Missing configuration section: server.requestSize"))
        }

        val requestSizeConfig = config.getConfig("server.requestSize")

        val defaultMax = DataSize.ofBytes(requestSizeConfig.getBytes("maxSize"))

        val routeOverrides =
          if (requestSizeConfig.hasPath("routeOverrides")) {
            val overridesConfig = requestSizeConfig.getConfig("routeOverrides")
            overridesConfig
              .root()
              .keys
              .associateWith { path -> DataSize.ofBytes(overridesConfig.getBytes("\"$path\"")) }
          } else {
            emptyMap()
          }

        Result.success(RequestSizeConfig(defaultMax, routeOverrides))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }
}
