package ed.unicoach.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomePageTest {
  @Test
  fun `home renders dynamically through the shared layout`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.Html.toString()),
      )

      val body = response.bodyAsText()
      // Brand marker proves the HomePage body rendered (end-to-end positioning, not essay-only).
      assertTrue(body.contains("Your college coach"), "missing home brand marker")
      // Shared chrome marker proves it rendered through Layout (header + footer).
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
      // The shared stylesheet link is present.
      assertTrue(body.contains("href=\"/site.css\""), "missing /site.css link")
    }

  /**
   * The RFC 225 bundle reaches the page as an EXTERNAL module, and it is the
   * page's ONLY script — so with JavaScript off the home page is what it was
   * before this slice apart from that one tag.
   *
   * Asserted by [assertLoadsBundleAsItsOnlyScript] over the served bytes, which
   * the legal and who-pays suites call too. What the bundle then INSERTS is not
   * asserted here: no server response contains it, and the Deno tests drive the
   * real functions against a document instead.
   */
  @Test
  fun `home loads the built bundle as its only script`() =
    testApplication {
      application { testPublicWebModule() }

      assertLoadsBundleAsItsOnlyScript("/", client.get("/").bodyAsText())
    }
}
