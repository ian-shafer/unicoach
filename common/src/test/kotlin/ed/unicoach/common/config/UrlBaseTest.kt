package ed.unicoach.common.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The single trailing-slash rule for a public-web link base (RFC 155 D-J).
 *
 * `service.conf` composes each page link as `${publicWeb.urlBase}"/report"`, so
 * an origin written the way a browser prints it (`https://app.uni.coach/`) is
 * one keystroke away from `https://app.uni.coach//report`. This pins the rule
 * both typed readers apply, so neither grows its own.
 */
class UrlBaseTest {
  @Test
  fun `a trailing slash on the origin does not double the separator`() {
    assertEquals("https://app.uni.coach/report", normalizeUrlBase("https://app.uni.coach//report"))
  }

  @Test
  fun `an already-clean base is returned unchanged`() {
    assertEquals("https://app.uni.coach/report", normalizeUrlBase("https://app.uni.coach/report"))
  }

  @Test
  fun `the scheme separator is never collapsed`() {
    assertEquals("http://localhost:8082", normalizeUrlBase("http://localhost:8082"))
  }

  @Test
  fun `a trailing slash on the composed base is dropped, so appending a query composes one link`() {
    assertEquals("https://app.uni.coach/report", normalizeUrlBase("https://app.uni.coach/report/"))
  }

  @Test
  fun `surrounding whitespace from a dotenv value is dropped`() {
    assertEquals("https://app.uni.coach/report", normalizeUrlBase("  https://app.uni.coach/report  "))
  }

  @Test
  fun `more than two slashes still collapse to one`() {
    assertEquals("https://app.uni.coach/report", normalizeUrlBase("https://app.uni.coach///report"))
  }

  /**
   * The slash rule is about the PATH. A hand-rolled split on the first `://`
   * rewrote every slash after it, so a base carrying a URL in its query came
   * back with that URL's own scheme separator collapsed — a link that no longer
   * points where the operator wrote it.
   */
  @Test
  fun `a url inside the query keeps its own scheme separator`() {
    assertEquals(
      "https://app.uni.coach/r?next=https://y.example",
      normalizeUrlBase("https://app.uni.coach/r?next=https://y.example"),
    )
  }

  /**
   * A scheme-relative base is a HOST, not a path. Collapsing its leading `//`
   * demoted `app.uni.coach` to the first segment of a path, and every link
   * composed from it would have been served by whatever host the browser was
   * already on.
   */
  @Test
  fun `a scheme-relative base keeps its host`() {
    assertEquals("//app.uni.coach/report", normalizeUrlBase("//app.uni.coach/report"))
  }
}
