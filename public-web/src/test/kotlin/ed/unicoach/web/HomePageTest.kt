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
      application { publicWebModule() }

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
}
