package ed.unicoach.web

import ed.unicoach.web.render.FAFSA_PATH
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shell's navigation, signed out and signed in (RFC 224).
 *
 * The DOOR — "a Sign in link in the shell's navigation" — is the slice's stated
 * entry point, and it lives in the shared header rather than on a page for the
 * reason every shared thing here lives in one place: a per-page link is missing
 * from the page that forgot it, and a door nobody can find is not a door.
 */
class ShellNavTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  @Test
  fun `every signed-out page carries exactly one sign-in link, and no account link`() =
    testApplication {
      application { testPublicWebModule() }

      for (path in listOf("/", "/terms", "/privacy", FAFSA_PATH, "/sign-in", "/register")) {
        // OK FIRST, and the path read from the page that owns it. The branded
        // 404 renders this very header, so both assertions below pass for a
        // path that is no longer a page at all -- which is what a renamed
        // route would leave behind.
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status, "[$path] is not a page")
        // The HEADER, not the whole page. The registration page also links to
        // sign-in from its own body ("Already have an account?"), which is a
        // different link with a different job; counting over the whole document
        // would make this test pass or fail on page copy.
        val header = headerOf(response.bodyAsText())
        assertEquals(
          1,
          Regex("<a href=\"/sign-in\">Sign in</a>").findAll(header).count(),
          "[$path] does not carry exactly one shell sign-in link; header was [$header]",
        )
        assertTrue(!header.contains("href=\"/account\""), "[$path] offers an account link to a signed-out visitor")
        assertTrue(!header.contains("action=\"/sign-out\""), "[$path] offers a sign-out control to a signed-out visitor")
      }
    }

  @Test
  fun `the signed-in shell carries the account link and the sign-out form, and no sign-in link`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val shellHeader = headerOf(client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }.bodyAsText())

      assertTrue(shellHeader.contains("href=\"/account\""), "the signed-in header carries no account link")
      assertTrue(shellHeader.contains("action=\"/sign-out\""), "the signed-in header carries no sign-out form")
      assertTrue(shellHeader.contains("method=\"post\""), "sign-out is not a POST: an anchor would be followed by every prefetcher")
      assertTrue(!shellHeader.contains("href=\"/sign-in\""), "the signed-in header still offers the door")
    }

  /**
   * The header is resolved PER REQUEST, on every page — not wired into the three
   * responders that happen to be about a session.
   *
   * The sign-out control lives in the shared header, so a page that renders the
   * signed-out header is a page a signed-in family cannot sign out from, and a
   * page that offers them a door they are already through. The legal pages, the
   * guide and the branded 404 are the ones a signed-in family reaches most
   * easily, so they are the ones asserted.
   */
  @Test
  fun `a signed-in family gets the signed-in header on every page, error pages included`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      // EACH PATH WITH THE STATUS IT OWES. The 404 page is deliberately in the
      // set -- it is a page a signed-in family reaches -- and it renders the
      // same header as every other, so without the expected status beside it a
      // real page that had been renamed away would pass here as "the 404".
      val pages =
        listOf(
          "/terms" to HttpStatusCode.OK,
          "/privacy" to HttpStatusCode.OK,
          "/guides/who-pays-for-college-advice" to HttpStatusCode.OK,
          FAFSA_PATH to HttpStatusCode.OK,
          "/no-such-page" to HttpStatusCode.NotFound,
        )
      for ((path, expected) in pages) {
        val response =
          noFollowClient().get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }
        assertEquals(expected, response.status, "[$path] did not answer as the page this case is about")
        val shellHeader = headerOf(response.bodyAsText())
        assertTrue(
          shellHeader.contains("action=\"/sign-out\""),
          "[$path] renders no sign-out control for a signed-in family: they cannot sign out from this page",
        )
        assertTrue(
          !shellHeader.contains("href=\"/sign-in\""),
          "[$path] offers the sign-in door to a family who is already signed in",
        )
      }
    }

  /**
   * The first production caller of the [ed.unicoach.web.render.ShellWidth.WIDE]
   * seam RFC 210 opened — whose own KDoc named this page.
   */
  @Test
  fun `the account page renders at the wide measure`() =
    testApplication {
      val email = WebAuthTestSupport.uniqueEmail()
      WebAuthTestSupport.seedUser(email)
      application { testPublicWebModule() }
      val token = WebAuthTestSupport.login(email)

      val response = client.get("/account") { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.bodyAsText().contains("<body class=\"wide-measure\">"),
        "the signed-in home is not at the wide measure",
      )
    }

  /**
   * The pages that existed before RFC 224 render the SAME body they did. The
   * shell's new anchor is shared chrome and is asserted above; what must not
   * have happened is a page's own content changing because a nav parameter was
   * threaded through it.
   */
  @Test
  fun `a page that existed before RFC 224 renders its own content unchanged`() =
    testApplication {
      application { testPublicWebModule() }

      val body = client.get("/").bodyAsText()
      val main = body.substringAfter("<main>").substringBefore("</main>")

      assertTrue(main.contains("unicoach"), "the home page body no longer renders: [$main]")
      assertTrue(!main.contains("/sign-in"), "the door leaked out of the shared header and into the page body")
      assertTrue(body.contains("<body>"), "the default shell width no longer renders a bare <body>")
    }

  /**
   * The shared header element only. Every assertion here is about the SHELL's
   * navigation, and a page's own body may legitimately link to the same places.
   */
  private fun headerOf(body: String): String =
    body
      .substringAfter("<header class=\"site-header\">", "")
      .substringBefore("</header>")
      .also { check(it.isNotBlank()) { "no site-header in the rendered page: [$body]" } }
}
