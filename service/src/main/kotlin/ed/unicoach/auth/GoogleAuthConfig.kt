package ed.unicoach.auth

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import java.time.Duration

/**
 * Typed reader for the `auth.google` block of service.conf, mirroring
 * SessionConfig/ChatConfig's Result-returning, fail-fast contract: `from` fails
 * when the section is absent or unreadable. It performs no value validation —
 * the factory ([GoogleTokenVerifierFactory]) is the single place an unusable
 * configuration (e.g. empty clientIds under provider "google") is rejected.
 *
 * [clientIds] accepts either a HOCON list or a comma-separated string (the shape
 * the `GOOGLE_CLIENT_IDS` env override produces); blank entries are dropped.
 */
data class GoogleAuthConfig(
  val provider: String,
  val clientIds: List<String>,
  val issuers: List<String>,
  val jwksUri: String,
  val clockSkew: Duration,
  val connectTimeout: Duration,
  val readTimeout: Duration,
) {
  companion object {
    fun from(config: Config): Result<GoogleAuthConfig> =
      runCatching {
        if (!config.hasPath("auth.google")) {
          throw ConfigException.Missing("auth.google")
        }
        val google = config.getConfig("auth.google")
        GoogleAuthConfig(
          provider = google.getString("provider"),
          clientIds = readStringList(google, "clientIds"),
          issuers = readStringList(google, "issuers"),
          jwksUri = google.getString("jwksUri"),
          clockSkew = google.getDuration("clockSkew"),
          connectTimeout = google.getDuration("connectTimeout"),
          readTimeout = google.getDuration("readTimeout"),
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
