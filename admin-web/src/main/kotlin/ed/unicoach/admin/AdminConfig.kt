package ed.unicoach.admin

import com.typesafe.config.Config
import java.time.ZoneId

/**
 * Display conventions shared by every admin view (RFC 79, RFC 83): the timezone
 * all datetimes render in, the glyphs for id links, booleans, and the copy
 * button, and the number of trailing characters a compacted UUID id keeps.
 * Parsed from the `admin.display` section; [timezone] is validated through
 * [ZoneId.of] and [idTailChars] through `require(it > 0)`, so a malformed zone or
 * a non-positive tail width fails fast at startup.
 */
data class DisplayConfig(
  val timezone: ZoneId,
  val idLinkGlyph: String,
  val boolTrueGlyph: String,
  val boolFalseGlyph: String,
  val idTailChars: Int,
  val copyGlyph: String,
)

/**
 * Admin server configuration: the internal-only bind host/port, the admin
 * session cookie attributes, and the [DisplayConfig] render conventions. Parsed
 * fail-fast at startup, mirroring [ed.unicoach.db.DatabaseConfig.from]. The admin
 * server shares no configuration object with `rest-server`; these settings live
 * in `admin-web.conf`.
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
        val server = admin.getConfig("web")
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
              idTailChars =
                display.getInt("idTailChars").also {
                  require(it > 0) { "admin.display.idTailChars must be positive, got: [$it]" }
                },
              copyGlyph = display.getString("copyGlyph"),
            ),
        )
      }
  }
}
