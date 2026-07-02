package ed.unicoach.web

import com.typesafe.config.Config

/**
 * Public-web server configuration: the bind host/port for the internet-facing
 * marketing/legal site. Parsed fail-fast at startup, mirroring
 * [ed.unicoach.db.DatabaseConfig.from] and `AdminConfig.from`. The public-web
 * server shares no configuration object with `rest-server` or `admin-web`;
 * these settings live in `public-web.conf`.
 */
data class PublicWebConfig(
  val host: String,
  val port: Int,
  val openInAppUrl: String,
) {
  companion object {
    fun from(config: Config): Result<PublicWebConfig> =
      runCatching {
        require(config.hasPath("publicWeb")) { "Missing configuration section: publicWeb" }
        val publicWeb = config.getConfig("publicWeb")
        val server = publicWeb.getConfig("server")
        val openInApp = publicWeb.getConfig("openInApp")

        PublicWebConfig(
          host = server.getString("host"),
          port = server.getInt("port"),
          openInAppUrl = openInApp.getString("url"),
        )
      }
  }
}
