package ed.unicoach.web

import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticAssetsTest {
  @Test
  fun `site css is served from the static asset mount with text css content type`() =
    testApplication {
      application { publicWebModule() }

      val response = client.get("/site.css")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.CSS.toString()),
        "expected text/css content type for /site.css",
      )
    }
}
