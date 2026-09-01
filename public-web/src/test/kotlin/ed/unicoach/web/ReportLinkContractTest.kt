package ed.unicoach.web

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of the share-link contract the compiler cannot see (RFC 155).
 *
 * `:service` builds the link a student sends from `costReport.shareUrlBase`;
 * `public-web` serves it at [REPORT_PATH] and reads the token from
 * [REPORT_TOKEN_PARAM]. Nothing binds the two, and a link already in a parent's
 * text thread cannot be updated — so renaming either side alone 404s every live
 * link with no build failure and no test failure anywhere else.
 *
 * This is that failure. It reads the PACKAGED `service.conf` (on this module's
 * test classpath through the `:service` dependency) rather than a copy of its
 * text, so it pins what actually ships.
 */
class ReportLinkContractTest {
  /**
   * The packaged default, resolved against a stand-in environment: every other
   * `${?VAR}` in the file is left unresolved, so this test is about ONE key and
   * cannot be broken by an unrelated one.
   */
  private val shareUrlBase: String =
    ConfigFactory
      .parseResourcesAnySyntax("service.conf")
      .withFallback(ConfigFactory.parseString("""APP_DOMAIN = "example.test", PUBLIC_WEB_PORT = 8082"""))
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      .getString("costReport.shareUrlBase")

  @Test
  fun `the packaged share-url default points at the path this module serves`() {
    assertTrue(
      shareUrlBase.endsWith(REPORT_PATH),
      "service.conf's costReport.shareUrlBase [$shareUrlBase] no longer ends at [$REPORT_PATH]: " +
        "every link already shared would 404",
    )
  }

  @Test
  fun `the route reads the token from the query key the link carries`() {
    assertEquals("token", REPORT_TOKEN_PARAM, "the share link appends ?token=<raw>; renaming this orphans every live link")
  }
}
