package ed.unicoach.web.common.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [SiteOrigin.parse]'s scheme allowlist (RFC 224 D-C).
 *
 * The origin the same-origin guard can ever compare against is served over
 * `http` or `https`, so any other scheme must parse to `null` — regardless of
 * whether a port was stated. The old code validated the scheme only when the
 * port was ABSENT, so `ftp://host:21` parsed as an origin while `ftp://host`
 * did not: one input family, two verdicts, decided by whether a port happened
 * to be typed.
 */
class SiteOriginTest {
  @Test
  fun `an http or https origin parses with its default or explicit port`() {
    assertEquals(SiteOrigin("https", "app.example.test", 443), SiteOrigin.parse("https://app.example.test"))
    assertEquals(SiteOrigin("http", "app.example.test", 80), SiteOrigin.parse("http://app.example.test"))
    assertEquals(SiteOrigin("https", "app.example.test", 8443), SiteOrigin.parse("https://app.example.test:8443"))
  }

  @Test
  fun `an unknown scheme is refused whether or not a port is stated`() {
    assertNull(SiteOrigin.parse("ftp://host:21"), "an explicit port must not smuggle an unknown scheme past the allowlist")
    assertNull(SiteOrigin.parse("ftp://host"), "an unknown scheme without a port must not parse as an origin")
  }
}
