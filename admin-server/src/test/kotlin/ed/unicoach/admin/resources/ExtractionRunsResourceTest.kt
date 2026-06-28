package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtractionRunsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A user/student/convo/request chain plus one applied run; returns its id string. */
  private fun seedRun(): String {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val convo = AdminTestSupport.seedConvo(student.id)
    val req = AdminTestSupport.seedConvoRequest(convo.id)
    return AdminTestSupport
      .seedExtractionRun(student.id, convo.id, req.id)
      .id.value
      .toString()
  }

  @Test
  fun `list shows a seeded row with token columns and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val runId = seedRun()

      val list = client().get("/extraction-run") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(runId), "List must render the run row")
      assertTrue(body.contains("Input Tokens"), "List must show the token columns (inList = true)")
      assertFalse(body.contains("Cache Read Tokens"), "List must omit inList=false token columns")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/extraction-run"), "Dashboard must link to /extraction-run")
      assertTrue(dashboard.contains("Extraction Run"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail shows all fields including the inList=false provenance and cache token columns`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val runId = seedRun()

      val detail = client().get("/extraction-run/$runId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("Cache Read Tokens"), "Detail must render inList=false columns")
      assertTrue(body.contains("System Prompt ID"), "Detail must render provenance columns")
    }

  @Test
  fun `read-only - no write routes resolve and no edit or delete affordance renders`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val runId = seedRun()

      assertEquals(HttpStatusCode.NotFound, client().get("/extraction-run/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(HttpStatusCode.NotFound, client().get("/extraction-run/$runId/edit") { header(HttpHeaders.Cookie, cookie) }.status)
      val del =
        client().submitForm(url = "/extraction-run/$runId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/extraction-run/$runId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/extraction-run/$runId/edit"), "No edit control")
      assertFalse(detail.contains("/extraction-run/$runId/delete"), "No delete control")
    }

  @Test
  fun `a malformed id segment returns the not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val malformed = client().get("/extraction-run/not-a-number") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, malformed.status, "parseId must reject non-numeric segments")
    }

  @Test
  fun `an unauthenticated request is redirected to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val res = client().get("/extraction-run")
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/login", res.headers[HttpHeaders.Location])
    }
}
