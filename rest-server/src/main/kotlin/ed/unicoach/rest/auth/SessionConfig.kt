package ed.unicoach.rest.auth

import com.typesafe.config.Config
import java.time.Duration

data class SessionConfig(
  val expiration: Duration,
  val cookieName: String,
  val cookieDomain: String,
  val cookieSecure: Boolean,
) {
  companion object {
    fun from(config: Config): Result<SessionConfig> {
      return try {
        if (!config.hasPath("session")) {
          return Result.failure(IllegalArgumentException("Missing configuration section: session"))
        }

        val sessionConfig = config.getConfig("session")

        val expiration = sessionConfig.getDuration("expiration")
        val cookieName = sessionConfig.getString("cookieName")
        val cookieDomain = sessionConfig.getString("cookieDomain")
        val cookieSecure = sessionConfig.getBoolean("cookieSecure")

        Result.success(SessionConfig(expiration, cookieName, cookieDomain, cookieSecure))
      } catch (e: Exception) {
        Result.failure(e)
      }
    }
  }
}
