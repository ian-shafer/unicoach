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

class LegalPagesTest {
  @Test
  fun `terms renders dynamically through the shared layout`() =
    testApplication {
      application { publicWebModule(FakeEmailVerifier(), TEST_OPEN_IN_APP_URL) }

      val response = client.get("/terms")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.Html.toString()),
      )

      val body = response.bodyAsText()
      // TermsPage marker — proves the legal copy renders dynamically, not a static file.
      assertTrue(body.contains("Terms of Service"), "missing Terms marker")
      // Shared chrome proves it rendered through Layout.
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    }

  @Test
  fun `privacy renders dynamically through the shared layout`() =
    testApplication {
      application { publicWebModule(FakeEmailVerifier(), TEST_OPEN_IN_APP_URL) }

      val response = client.get("/privacy")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.Html.toString()),
      )

      val body = response.bodyAsText()
      // PrivacyPage marker — proves the legal copy renders dynamically, not a static file.
      assertTrue(body.contains("Privacy Policy"), "missing Privacy marker")
      // Shared chrome proves it rendered through Layout.
      assertTrue(body.contains("class=\"site-header\""), "missing shared header chrome")
      assertTrue(body.contains("class=\"site-footer\""), "missing shared footer chrome")
    }
}
