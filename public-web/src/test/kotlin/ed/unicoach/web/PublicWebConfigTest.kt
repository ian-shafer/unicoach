package ed.unicoach.web

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicWebConfigTest {
  /**
   * The minimum a parse needs, as the packaged conf supplies it: the `publicWeb`
   * block from `public-web.conf` and `service.conf`, plus the SHARED `session`
   * block from `session.conf` (RFC 224 D-B). The three are separate files in
   * production and are stated separately here for the same reason.
   */
  private fun conf(
    host: String = "0.0.0.0",
    port: String = "port = 9090",
    urlBaseLine: String = """urlBase = "https://app.example.test"""",
    openInApp: String = """url = "https://unicoach.test/app"""",
    webExpiration: String = """expiration = "30d"""",
    sharedSession: String = SHARED_SESSION,
  ) = ConfigFactory.parseString(
    """
    $sharedSession
    publicWeb {
      server {
        host = "$host"
        $port
      }
      openInApp {
        $openInApp
      }
      $urlBaseLine
      session {
        $webExpiration
      }
    }
    """.trimIndent(),
  )

  @Test
  fun `parses host port and openInApp from the publicWeb section`() {
    val parsed = PublicWebConfig.from(conf()).getOrThrow()

    assertEquals("0.0.0.0", parsed.host)
    assertEquals(9090, parsed.port)
    assertEquals("https://unicoach.test/app", parsed.openInAppUrl)
  }

  @Test
  fun `missing publicWeb section fails fast`() {
    val result = PublicWebConfig.from(ConfigFactory.parseString("{}"))

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
  }

  @Test
  fun `missing required port key fails fast with ConfigException`() {
    val result = PublicWebConfig.from(conf(port = ""))

    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConfigException.Missing)
  }

  @Test
  fun `absent openInApp url parses to null (optional, no boot crash)`() {
    // Mirrors production: `url = ${?PUBLIC_WEB_OPEN_IN_APP_URL}` with the var unset
    // leaves the key absent. The affordance is optional, so this must parse, not
    // fail — an unset deep link simply omits the "Open in app" link.
    assertNull(PublicWebConfig.from(conf(openInApp = "")).getOrThrow().openInAppUrl)
  }

  /**
   * The origin the same-origin guard compares against (RFC 224 D-C), normalized
   * by the ONE trailing-slash rule (RFC 155 D-J) rather than by a rule this
   * reader invents. An operator who writes the origin the way a browser prints
   * it must not get a different guard from one who does not.
   */
  @Test
  fun `urlBase is normalized by the one shared trailing-slash rule`() {
    val parsed = PublicWebConfig.from(conf(urlBaseLine = """urlBase = "https://app.example.test/"""")).getOrThrow()

    assertEquals("https://app.example.test", parsed.origin.url)
    assertEquals(443, parsed.origin.port, "an https origin with no explicit port is port 443, not -1")
  }

  @Test
  fun `a missing urlBase fails fast rather than leaving the same-origin guard nothing to compare against`() {
    val result = PublicWebConfig.from(conf(urlBaseLine = ""))

    assertTrue(result.isFailure, "public-web parsed with no origin: the same-origin guard could then only fail open")
  }

  /**
   * A value that is not a URL at all fails the parse as an
   * invalid-configuration-value refusal, the SAME failure type a non-origin URL
   * gets. The shared normalizer throws [java.net.URISyntaxException]; this
   * reader folds it into one named message so a mistyped `PUBLIC_WEB_URL_BASE`
   * never surfaces as a bare `java.net` parser fault.
   */
  @Test
  fun `a urlBase that is not a URL at all cannot produce a config object`() {
    val result = PublicWebConfig.from(conf(urlBaseLine = """urlBase = "not a url""""))

    assertTrue(result.isFailure, "public-web built a config out of a urlBase that is not a URL")
    assertTrue(
      result.exceptionOrNull() is IllegalArgumentException,
      "an invalid configuration VALUE must read as one; got [${result.exceptionOrNull()}]",
    )
  }

  /**
   * The case this reader itself has to catch: a value that IS a URI and is NOT
   * an origin — no scheme, no host.
   *
   * It must fail HERE, not later inside the guard's installer. A config object
   * that cannot be built invalid is strictly better than one carrying a value
   * something downstream has to re-reject, and the guard is the wrong place to
   * find out: it could only fail open.
   */
  @Test
  fun `a urlBase with no scheme or host is refused as an invalid configuration value`() {
    val result = PublicWebConfig.from(conf(urlBaseLine = """urlBase = "app.example.test""""))

    assertTrue(result.isFailure, "public-web built a config whose site origin has no scheme or host")
    assertTrue(
      result.exceptionOrNull() is IllegalArgumentException,
      "an invalid configuration VALUE must read as one; got [${result.exceptionOrNull()}]",
    )
  }

  /**
   * The SPLIT (RFC 224 D-D), asserted from both halves at once because the split
   * is the decision: the cookie's identity is shared with `rest-server` and its
   * lifetime is not.
   */
  @Test
  fun `the session identity comes from the shared block and the lifetime from publicWeb`() {
    val session = PublicWebConfig.from(conf()).getOrThrow().session

    assertEquals("UNICOACH_SESSION", session.cookieName)
    assertEquals("shared.example.test", session.cookieDomain)
    assertTrue(session.cookieSecure)
    assertEquals(Duration.ofDays(30), session.expiration)
  }

  @Test
  fun `a missing shared session block fails fast rather than inventing a cookie`() {
    val result = PublicWebConfig.from(conf(sharedSession = ""))

    assertTrue(result.isFailure, "public-web parsed a session config with no shared session block")
  }

  @Test
  fun `a missing web session expiration fails fast rather than falling back to rest-server's 7 days`() {
    val result = PublicWebConfig.from(conf(webExpiration = ""))

    assertTrue(
      result.isFailure,
      "publicWeb.session.expiration is absent and the parse succeeded: the web would silently inherit the 7-day iOS lifetime",
    )
  }

  /**
   * The PACKAGED defaults, resolved offline the way the server resolves them.
   * The assertions above are about the reader; this one is about what ships.
   */
  @Test
  fun `the packaged conf ships a 30-day web session and a 7-day shared one`() {
    val packaged =
      ConfigFactory
        .parseString(
          """
          APP_DOMAIN = cookie.example.test
          SESSION_COOKIE_SECURE = false
          PUBLIC_WEB_PORT = 8082
          """.trimIndent(),
        ).withFallback(ConfigFactory.parseResources("public-web.conf"))
        .withFallback(ConfigFactory.parseResources("session.conf"))
        .resolve(
          com.typesafe.config.ConfigResolveOptions
            .defaults()
            .setUseSystemEnvironment(false),
        )

    assertEquals(Duration.ofDays(30), packaged.getDuration("publicWeb.session.expiration"))
    assertEquals(Duration.ofDays(7), packaged.getDuration("session.expiration"))
  }

  private companion object {
    val SHARED_SESSION =
      """
      session {
        cookieName = "UNICOACH_SESSION"
        cookieDomain = "shared.example.test"
        cookieSecure = true
        expiration = "7d"
      }
      """.trimIndent()
  }
}
