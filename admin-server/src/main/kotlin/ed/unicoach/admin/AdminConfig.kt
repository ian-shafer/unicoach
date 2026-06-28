package ed.unicoach.admin

import com.typesafe.config.Config
import java.time.ZoneId

/**
 * Display conventions shared by every admin view (RFC 79): the timezone all
 * datetimes render in, and the glyphs for id links and booleans. Parsed from the
 * `admin.display` section; [timezone] is validated through [ZoneId.of] so a
 * malformed zone fails fast at startup.
 */
data class DisplayConfig(
  val timezone: ZoneId,
  val idLinkGlyph: String,
  val boolTrueGlyph: String,
  val boolFalseGlyph: String,
)

/**
 * Admin server configuration: the internal-only bind host/port, the admin
 * session cookie attributes, and the [DisplayConfig] render conventions. Parsed
 * fail-fast at startup, mirroring [ed.unicoach.db.DatabaseConfig.from]. The admin
 * server shares no configuration object with `rest-server`; these settings live
 * in `admin-server.conf`.
 */
data class AdminConfig(
  val host: String,
  val port: Int,
  val cookieName: String,
  val cookieDomain: String,
  val cookieSecure: Boolean,
  val sessionExpirationSeconds: Long,
  val display: DisplayConfig,
) {
  companion object {
    fun from(config: Config): Result<AdminConfig> =
      runCatching {
        require(config.hasPath("admin")) { "Missing configuration section: admin" }
        val admin = config.getConfig("admin")
        val server = admin.getConfig("server")
        val session = admin.getConfig("session")
        val display = admin.getConfig("display")

        AdminConfig(
          host = server.getString("host"),
          port = server.getInt("port"),
          cookieName = session.getString("cookieName"),
          cookieDomain = session.getString("cookieDomain"),
          cookieSecure = session.getBoolean("cookieSecure"),
          sessionExpirationSeconds = session.getLong("expirationSeconds"),
          display =
            DisplayConfig(
              timezone = ZoneId.of(display.getString("timezone")),
              idLinkGlyph = display.getString("idLinkGlyph"),
              boolTrueGlyph = display.getString("boolTrueGlyph"),
              boolFalseGlyph = display.getString("boolFalseGlyph"),
            ),
        )
      }
  }
}
