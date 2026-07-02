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

class ObservationsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A user/student/convo/request chain plus one observation; returns its id string and quote. */
  private fun seedObservation(quote: String): String {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val convo = AdminTestSupport.seedConvo(student.id)
    val req = AdminTestSupport.seedConvoRequest(convo.id)
    return AdminTestSupport
      .seedObservation(student.id, convo.id, req.id, quote = quote)
      .id.value
      .toString()
  }

  @Test
  fun `list shows a seeded row, omits the quote, and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val quote = "QUOTE_MARKER_${UUID.randomUUID()}"
      val obsId = seedObservation(quote)

      val list = client().get("/observation") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(obsId), "List must render the observation row")
      assertFalse(body.contains(quote), "List must omit the quote (inList = false)")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/observation"), "Dashboard must link to /observation")
      assertTrue(dashboard.contains("Observation"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail shows all fields including the inList=false quote`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val quote = "DETAIL_QUOTE_${UUID.randomUUID()}"
      val obsId = seedObservation(quote)

      val detail = client().get("/observation/$obsId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains(quote), "Detail must render the full quote")
      assertTrue(body.contains("Source Request ID"), "Detail must render inList=false fields")
    }

  @Test
  fun `read-only - no write routes resolve and no edit or delete affordance renders`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val obsId = seedObservation("read only ${UUID.randomUUID()}")

      assertEquals(HttpStatusCode.NotFound, client().get("/observation/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(HttpStatusCode.NotFound, client().get("/observation/$obsId/edit") { header(HttpHeaders.Cookie, cookie) }.status)
      val del =
        client().submitForm(url = "/observation/$obsId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/observation/$obsId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/observation/$obsId/edit"), "No edit control")
      assertFalse(detail.contains("/observation/$obsId/delete"), "No delete control")
    }

  @Test
  fun `a malformed id segment returns the not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val malformed = client().get("/observation/not-a-number") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, malformed.status, "parseId must reject non-numeric segments")
    }

  @Test
  fun `an unauthenticated request is redirected to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val res = client().get("/observation")
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/login", res.headers[HttpHeaders.Location])
    }

  @Test
  fun `observation detail links convoId to convo and sourceRequestId to convo-request`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id)
      val req = AdminTestSupport.seedConvoRequest(convo.id)
      val obs = AdminTestSupport.seedObservation(student.id, convo.id, req.id)

      val detail = client().get("/observation/${obs.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("/convo/${convo.id.value}"), "convoId must link to /convo/{id}")
      assertTrue(detail.contains("/convo-request/${req.id.value}"), "sourceRequestId must link to /convo-request/{id}")
    }

  @Test
  fun `detail renders a Supported claims panel linking to the claim detail`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id)
      val req = AdminTestSupport.seedConvoRequest(convo.id)
      val claim = AdminTestSupport.seedClaim(student.id)
      val obs = AdminTestSupport.seedObservation(student.id, convo.id, req.id)
      AdminTestSupport.seedClaimSupport(claim.id, obs.id)

      val detail = client().get("/observation/${obs.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("Supported claims"), "The edge panel must render")
      assertTrue(
        detail.contains("/claim/${claim.id.value}"),
        "The supported-claim row must link to the canonical /claim/{id} path",
      )
    }
}
