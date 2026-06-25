package ed.unicoach.web

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorPagesTest {
  @Test
  fun `unmatched route renders the branded 404 through the shared layout`() =
    testApplication {
      application { publicWebModule(FakeEmailVerifier(), TEST_OPEN_IN_APP_URL) }

      val response = client.get("/does-not-exist")
      assertEquals(HttpStatusCode.NotFound, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.Html.toString()),
      )

      val body = response.bodyAsText()
      // Branded not-found marker proves the StatusPages handler rendered, not a default Ktor body.
      assertTrue(body.contains("404 Not Found"), "missing branded 404 marker")
      // Shared chrome proves it rendered through Layout.
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    }

  @Test
  fun `an uncaught exception renders the branded 503 through the shared layout`() =
    testApplication {
      application {
        // publicWebModule installs StatusPages (incl. exception<Throwable>).
        publicWebModule(FakeEmailVerifier(), TEST_OPEN_IN_APP_URL)
        // A test-only route that throws so the catch-all 503 handler fires.
        routing {
          get("/boom") {
            call // touch the call so the lambda is non-trivial
            throw IllegalStateException("boom")
          }
        }
      }

      val response = client.get("/boom")
      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.Html.toString()),
      )

      val body = response.bodyAsText()
      // Branded service-unavailable marker proves the exception<Throwable> handler rendered.
      assertTrue(body.contains("503 Service Unavailable"), "missing branded 503 marker")
      // Shared chrome proves it rendered through Layout.
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    }
}
