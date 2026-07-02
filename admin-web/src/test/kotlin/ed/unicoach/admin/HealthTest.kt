package ed.unicoach.admin

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
  @Test
  fun `healthz returns 200 with a constant body and no auth`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }

      val response = client.get("/healthz")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(response.bodyAsText().contains("ok"))
    }
}
