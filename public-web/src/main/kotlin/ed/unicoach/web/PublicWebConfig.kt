package ed.unicoach.web

import com.typesafe.config.Config

/**
 * Public-web server configuration: the bind host/port for the internet-facing
 * marketing/legal site. Parsed fail-fast at startup, mirroring
 * [ed.unicoach.db.DatabaseConfig.from] and `AdminConfig.from`. The public-web
 * server shares no configuration object with `rest-server` or `admin-web`;
 * these settings live in `public-web.conf`.
 *
 * [openInAppUrl] is optional: `public-web.conf` feeds it from the optional
 * substitution `${?PUBLIC_WEB_OPEN_IN_APP_URL}`, so an unset var leaves the key
 * absent and this parses to `null` — the iPhone-only "Open in app" affordance is
 * simply omitted (see `respondVerifyEmailResult`). It is not required to boot.
 */
data class PublicWebConfig(
  val host: String,
  val port: Int,
  val openInAppUrl: String?,
) {
  companion object {
    fun from(config: Config): Result<PublicWebConfig> =
      runCatching {
        require(config.hasPath("publicWeb")) { "Missing configuration section: publicWeb" }
        val publicWeb = config.getConfig("publicWeb")
        val server = publicWeb.getConfig("server")

        PublicWebConfig(
          host = server.getString("host"),
          port = server.getInt("port"),
          openInAppUrl =
            if (publicWeb.hasPath("openInApp.url")) publicWeb.getString("openInApp.url") else null,
        )
      }
  }
}
