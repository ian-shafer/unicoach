package ed.unicoach.web

import com.typesafe.config.Config
import ed.unicoach.common.config.normalizeUrlBase
import ed.unicoach.web.common.http.SiteOrigin
import java.time.Duration

/**
 * The session cookie `public-web` issues and reads (RFC 224).
 *
 * The cookie's IDENTITY — name, domain, `Secure` — is read from the SHARED
 * `session` block that now lives in `common/src/main/resources/session.conf`
 * (D-B). That is the whole mechanism behind "one login across web and app":
 * `rest-server` reads the same three values from the same file, so the two
 * servers cannot drift into two different cookies for one `sessions` row.
 *
 * The LIFETIME is read from a DIFFERENT block on purpose. `publicWeb.session.expiration`
 * is 30 days (D-D, Ian's override of brief decision D21 at the RFC 224 gate),
 * while `rest-server` keeps `session.expiration = "7d"`. A browser session and
 * an iOS session are different products: lengthening every iOS session to match
 * the web would widen the window in which a stolen token is still good, which
 * is a security change nobody asked for. Two rows with two lifetimes is already
 * what a family signing in on both surfaces gets.
 *
 * [expiration] is used for BOTH the `sessions` row and the cookie's `Max-Age`,
 * and a test asserts they agree: a cookie outliving its row shows a signed-in
 * shell to a signed-out user, and the reverse signs a family out early.
 */
data class WebSessionConfig(
  val cookieName: String,
  val cookieDomain: String,
  val cookieSecure: Boolean,
  val expiration: Duration,
) {
  companion object {
    fun from(config: Config): Result<WebSessionConfig> =
      runCatching {
        require(config.hasPath("session")) { "Missing configuration section: session" }
        require(config.hasPath("publicWeb.session.expiration")) {
          "Missing configuration key: publicWeb.session.expiration"
        }

        val shared = config.getConfig("session")

        WebSessionConfig(
          cookieName = shared.getString("cookieName"),
          cookieDomain = shared.getString("cookieDomain"),
          cookieSecure = shared.getBoolean("cookieSecure"),
          expiration = config.getDuration("publicWeb.session.expiration"),
        )
      }
  }
}

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
 *
 * [origin] is the ONE public-web origin (RFC 155 D-J), stated in
 * `service.conf` and driven by `PUBLIC_WEB_URL_BASE`. It is held PARSED rather
 * than as the configured string: RFC 224's same-origin guard compares an inbound
 * `Origin`/`Referer` against it, a guard with nothing to compare against would
 * have to fail open, and a `String` would let this object be built with a value
 * the guard then has to reject a second time, at boot, elsewhere. Parsed here,
 * an unusable origin cannot produce a `PublicWebConfig` at all.
 *
 * [session] is the RFC 224 session cookie contract; see [WebSessionConfig] for
 * why its identity and its lifetime come from two different blocks.
 */
data class PublicWebConfig(
  val host: String,
  val port: Int,
  val openInAppUrl: String?,
  val origin: SiteOrigin,
  val session: WebSessionConfig,
) {
  companion object {
    fun from(config: Config): Result<PublicWebConfig> =
      runCatching {
        require(config.hasPath("publicWeb")) { "Missing configuration section: publicWeb" }
        val publicWeb = config.getConfig("publicWeb")
        val server = publicWeb.getConfig("server")
        // Named like the checks around it, because this key is NOT in
        // public-web.conf: `publicWeb.urlBase` is stated in service.conf (RFC 155
        // D-J), so a loader that lists public-web.conf alone would otherwise get
        // a raw HOCON exception instead of the fact.
        require(publicWeb.hasPath("urlBase")) {
          "Missing configuration key: publicWeb.urlBase — it is stated in service.conf (RFC 155 D-J), " +
            "which every loader of public-web.conf must load alongside it"
        }

        PublicWebConfig(
          host = server.getString("host"),
          port = server.getInt("port"),
          openInAppUrl =
            if (publicWeb.hasPath("openInApp.url")) publicWeb.getString("openInApp.url") else null,
          origin = parseOrigin(publicWeb.getString("urlBase")),
          session = WebSessionConfig.from(config).getOrThrow(),
        )
      }

    /**
     * The configured origin, or one invalid-configuration-value failure naming
     * the key.
     *
     * Both ways [raw] can be unusable — not a URL at all, and a URL that is not
     * an origin — are ONE fact to an operator, so they read as one typed refusal
     * rather than as `java.net`'s parser fault beside this file's own message.
     * The raw value is named in both, and the normalizer's `URISyntaxException`
     * is carried as the cause rather than discarded.
     */
    private fun parseOrigin(raw: String): SiteOrigin {
      val normalized =
        runCatching { normalizeUrlBase(raw) }.getOrElse { cause ->
          throw IllegalArgumentException(
            "Invalid configuration value: publicWeb.urlBase [$raw] is not a URL, " +
              "so the same-origin guard would have no origin to compare against",
            cause,
          )
        }
      return SiteOrigin.parse(normalized)
        ?: throw IllegalArgumentException(
          "Invalid configuration value: publicWeb.urlBase [$raw] is not an absolute URL with a scheme and host, " +
            "so the same-origin guard would have no origin to compare against",
        )
    }
  }
}
