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

class HealthTest {
  @Test
  fun `healthz returns 200 with the ok body and json content type and no auth`() =
    testApplication {
      application { publicWebModule() }

      val response = client.get("/healthz")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("{\"status\":\"ok\"}", response.bodyAsText())
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Application.Json.toString()),
      )
    }
}
