package ed.unicoach.admin

import com.typesafe.config.Config

/**
 * Admin server configuration: the internal-only bind host/port and the admin
 * session cookie attributes. Parsed fail-fast at startup, mirroring
 * [ed.unicoach.db.DatabaseConfig.from]. The admin server shares no configuration
 * object with `rest-server`; these settings live in `admin-server.conf`.
 */
data class AdminConfig(
  val host: String,
  val port: Int,
  val cookieName: String,
  val cookieDomain: String,
  val cookieSecure: Boolean,
  val sessionExpirationSeconds: Long,
) {
  companion object {
    fun from(config: Config): Result<AdminConfig> =
      runCatching {
        require(config.hasPath("admin")) { "Missing configuration section: admin" }
        val admin = config.getConfig("admin")
        val server = admin.getConfig("server")
        val session = admin.getConfig("session")

        AdminConfig(
          host = server.getString("host"),
          port = server.getInt("port"),
          cookieName = session.getString("cookieName"),
          cookieDomain = session.getString("cookieDomain"),
          cookieSecure = session.getBoolean("cookieSecure"),
          sessionExpirationSeconds = session.getLong("expirationSeconds"),
        )
      }
  }
}
