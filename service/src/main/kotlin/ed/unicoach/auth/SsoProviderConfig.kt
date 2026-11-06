package ed.unicoach.auth

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import ed.unicoach.common.config.optionalString
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
 *
 * [webClientId] is the website's own client (RFC 275): Google's Web OAuth
 * client, or Apple's Services ID. It is optional, and a blank value reads as
 * absent, because an unset `${?GOOGLE_WEB_CLIENT_ID}` and an empty one in a
 * dotenv mean the same thing to an operator: the web buttons are off.
 */
data class SsoProviderConfig(
  val provider: String,
  val clientIds: List<String>,
  val webClientId: String?,
  val issuers: List<String>,
  val jwksUri: String,
  val clockSkew: Duration,
  val connectTimeout: Duration,
  val readTimeout: Duration,
) {
  /**
   * Every token audience this provider accepts: [clientIds] plus [webClientId]
   * when it is set, de-duplicated (RFC 275 D-C).
   *
   * DERIVED rather than configured, so the web's ID has ONE home. Asking an
   * operator to list it in `clientIds` as well would make the button and the
   * verifier two readers of two values that are supposed to agree — the
   * misconfigured audience the web slice exists to rule out. The button reads
   * [webClientId]; the verifier accepts it because it is in this list.
   */
  val acceptedAudiences: List<String>
    get() = (clientIds + listOfNotNull(webClientId)).distinct()

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
          // The shared reading of "unset" (blank is absent); trimmed, as
          // every `clientIds` entry is, so a quoted padded ID still matches.
          webClientId = block.optionalString("webClientId")?.trim(),
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
