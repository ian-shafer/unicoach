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

class CollegesResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  @Test
  fun `list shows the configured columns and a nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      AdminTestSupport.seedCollege(unitId = 910100, name = "Coastal State University", city = "Seaside", state = "CA")

      val list = client().get("/college") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains("Coastal State University"), "List must render the college name")
      assertTrue(body.contains("Seaside"), "List must render the city")
      assertTrue(body.contains("Admission Rate"), "List must show the inList=true admission-rate column")
      assertFalse(body.contains("OPEID"), "List must omit the inList=false OPEID column header")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/college"), "Dashboard must link to /college")
      assertTrue(dashboard.contains("College"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail shows the full row and the version-history panel`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      // Two upserts → version 2 → two history rows.
      AdminTestSupport.seedCollege(unitId = 910200, name = "Original Name")
      val college = AdminTestSupport.seedCollege(unitId = 910200, name = "Renamed College")
      val id = college.id.value.toString()

      val detail = client().get("/college/$id") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("Renamed College"), "Detail must render the current name")
      assertTrue(body.contains("OPEID"), "Detail must render inList=false fields like OPEID")
      assertTrue(body.contains("Version"), "Detail must render the version field")
      assertTrue(body.contains("Version history"), "Detail must render the version-history panel")
      assertTrue(body.contains("Original Name"), "History panel must show the prior version's value")
    }

  @Test
  fun `resource is read-only`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val college = AdminTestSupport.seedCollege(unitId = 910300)
      val id = college.id.value.toString()

      // The create form (`/new`) and edit form re-check nullability and serve the
      // not-found page; both write handlers are null.
      assertEquals(HttpStatusCode.NotFound, client().get("/college/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(HttpStatusCode.NotFound, client().get("/college/$id/edit") { header(HttpHeaders.Cookie, cookie) }.status)

      // The create POST is registered only when `create` is non-null (per
      // AdminRouting); with a null handler no POST route exists on `/college`,
      // which already has a GET list route, so Ktor reports 405 Method Not Allowed.
      val create =
        client().submitForm(url = "/college", formParameters = parameters {}) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.MethodNotAllowed, create.status)

      // The undelete POST is likewise registered only when `undelete` is non-null;
      // with a null handler the path `/college/{id}/undelete` matches no route at
      // all, so Ktor reports 404 Not Found.
      val undelete =
        client().submitForm(url = "/college/$id/undelete", formParameters = parameters {}) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, undelete.status)

      // The delete POST is unconditionally registered; it re-checks nullability
      // and serves the not-found page when the handler is null.
      val del =
        client().submitForm(url = "/college/$id/delete", formParameters = parameters {}) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/college/$id") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/college/$id/edit"), "No edit control")
      assertFalse(detail.contains("/college/$id/delete"), "No delete control")
    }

  @Test
  fun `unknown id is a not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val randomId = UUID.randomUUID().toString()
      val res = client().get("/college/$randomId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status)
    }
}
