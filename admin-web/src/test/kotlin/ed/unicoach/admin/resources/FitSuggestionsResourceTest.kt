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
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FitSuggestionsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  private var ipedsUnitId = 400000

  /** A user + student + college that owns one open fit suggestion; returns its id string. */
  private fun seedSuggestion(rationale: String): String {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val college = AdminTestSupport.seedCollege(ipedsUnitId++)
    return AdminTestSupport
      .seedFitSuggestion(student.id, college.id, rationale)
      .id.value
      .toString()
  }

  @Test
  fun `list shows a seeded row, omits inList=false columns, and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val rationale = "RATIONALE_MARKER_${UUID.randomUUID()}"
      val suggestionId = seedSuggestion(rationale)

      val list = client().get("/fit-suggestion") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(suggestionId), "List must render the suggestion row")
      assertTrue(body.contains("open"), "List must render the status cell")
      assertFalse(body.contains(rationale), "List must omit the rationale (inList = false)")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/fit-suggestion"), "Dashboard must link to /fit-suggestion")
      assertTrue(dashboard.contains("Fit Suggestion"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail renders the rationale and surfacing columns`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val rationale = "RATIONALE_MARKER_${UUID.randomUUID()}"
      val suggestionId = seedSuggestion(rationale)

      val detail = client().get("/fit-suggestion/$suggestionId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains(rationale), "Detail must render the full rationale")
      assertTrue(body.contains("Surfaced In Convo"), "Detail must render the surfacing columns")
    }

  @Test
  fun `read-only - no write routes resolve and no edit or delete affordance renders`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val suggestionId = seedSuggestion("a rationale")

      assertEquals(HttpStatusCode.NotFound, client().get("/fit-suggestion/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(
        HttpStatusCode.NotFound,
        client().get("/fit-suggestion/$suggestionId/edit") { header(HttpHeaders.Cookie, cookie) }.status,
      )
      val del =
        client().submitForm(url = "/fit-suggestion/$suggestionId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/fit-suggestion/$suggestionId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/fit-suggestion/$suggestionId/edit"), "No edit control")
      assertFalse(detail.contains("/fit-suggestion/$suggestionId/delete"), "No delete control")
    }

  @Test
  fun `an unauthenticated request is redirected to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val res = client().get("/fit-suggestion")
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/login", res.headers[HttpHeaders.Location])
    }
}
