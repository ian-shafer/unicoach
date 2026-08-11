package ed.unicoach.auth

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import java.time.Duration

/**
 * Typed reader for a named SSO provider block of service.conf (`"auth.google"`
 * or `"auth.apple"`), mirroring SessionConfig/ChatConfig's Result-returning,
 * fail-fast contract: `from` fails when the section is absent or unreadable.
 * It performs no value validation — the factory ([IdTokenVerifierFactory]) is
 * the single place an unusable configuration (e.g. empty clientIds under a
 * real provider) is rejected.
 *
 * [clientIds] accepts either a HOCON list or a comma-separated string (the
 * shape the `GOOGLE_CLIENT_IDS`/`APPLE_CLIENT_IDS` env overrides produce);
 * blank entries are dropped.
 */
data class SsoProviderConfig(
  val provider: String,
  val clientIds: List<String>,
  val issuers: List<String>,
  val jwksUri: String,
  val clockSkew: Duration,
  val connectTimeout: Duration,
  val readTimeout: Duration,
) {
  companion object {
    fun from(
      config: Config,
      path: String,
    ): Result<SsoProviderConfig> =
      runCatching {
        if (!config.hasPath(path)) {
          throw ConfigException.Missing(path)
        }
        val block = config.getConfig(path)
        SsoProviderConfig(
          provider = block.getString("provider"),
          clientIds = readStringList(block, "clientIds"),
          issuers = readStringList(block, "issuers"),
          jwksUri = block.getString("jwksUri"),
          clockSkew = block.getDuration("clockSkew"),
          connectTimeout = block.getDuration("connectTimeout"),
          readTimeout = block.getDuration("readTimeout"),
        )
      }

    /**
     * Reads [path] as a HOCON list, falling back to splitting a comma-separated
     * string (the env-override shape). Blank entries are dropped.
     */
    private fun readStringList(
      config: Config,
      path: String,
    ): List<String> =
      try {
        config.getStringList(path).map { it.trim() }.filter { it.isNotEmpty() }
      } catch (e: ConfigException.WrongType) {
        config
          .getString(path)
          .split(",")
          .map { it.trim() }
          .filter { it.isNotEmpty() }
      }
  }
}
