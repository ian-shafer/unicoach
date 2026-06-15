package ed.unicoach.rest.config

import com.typesafe.config.Config

data class ClientKeyGateConfig(
  val validKeys: Set<String>,
  val allowlistPaths: Set<String>,
) {
  companion object {
    fun from(config: Config): Result<ClientKeyGateConfig> {
      return try {
        if (!config.hasPath("clientKeyGate")) {
          return Result.failure(IllegalArgumentException("Missing configuration section: clientKeyGate"))
        }

        val gateConfig = config.getConfig("clientKeyGate")

        val validKeys =
          gateConfig
            .getString("keys")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val allowlistPaths =
          gateConfig
            .getStringList("allowlistPaths")
            .toSet()

        Result.success(ClientKeyGateConfig(validKeys, allowlistPaths))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }
}
