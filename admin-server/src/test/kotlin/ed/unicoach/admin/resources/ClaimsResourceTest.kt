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

class ClaimsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A user with a student that owns one claim; returns the claim id string. */
  private fun seedClaim(statement: String): String {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    return AdminTestSupport
      .seedClaim(student.id, statement = statement)
      .id.value
      .toString()
  }

  @Test
  fun `list shows a seeded row, omits inList=false columns, and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val statement = "STATEMENT_MARKER_${UUID.randomUUID()}"
      val claimId = seedClaim(statement)

      val list = client().get("/claim") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(claimId), "List must render the claim row")
      assertTrue(body.contains("academics"), "List must render the topic cell")
      assertFalse(body.contains(statement), "List must omit the statement (inList = false)")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/claim"), "Dashboard must link to /claim")
      assertTrue(dashboard.contains("Claim"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail shows all fields including the inList=false statement`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val statement = "DETAIL_STATEMENT_${UUID.randomUUID()}"
      val claimId = seedClaim(statement)

      val detail = client().get("/claim/$claimId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains(statement), "Detail must render the full statement")
      assertTrue(body.contains("Visibility"), "Detail must render inList=false fields like Visibility")
    }

  @Test
  fun `read-only - no write routes resolve and no edit or delete affordance renders`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val claimId = seedClaim("read only ${UUID.randomUUID()}")

      assertEquals(HttpStatusCode.NotFound, client().get("/claim/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(HttpStatusCode.NotFound, client().get("/claim/$claimId/edit") { header(HttpHeaders.Cookie, cookie) }.status)
      val del =
        client().submitForm(url = "/claim/$claimId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/claim/$claimId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/claim/$claimId/edit"), "No edit control")
      assertFalse(detail.contains("/claim/$claimId/delete"), "No delete control")
      assertFalse(detail.contains("/claim/$claimId/undelete"), "No undelete control")
    }

  @Test
  fun `a malformed id segment returns the not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val malformed = client().get("/claim/not-a-uuid") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, malformed.status, "parseId must reject non-UUID segments")
    }

  @Test
  fun `an unauthenticated request is redirected to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val res = client().get("/claim")
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/login", res.headers[HttpHeaders.Location])
    }

  @Test
  fun `detail renders a Supporting observations panel linking to the observation detail`() =
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

      val detail = client().get("/claim/${claim.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("Supporting observations"), "The edge panel must render")
      assertTrue(
        detail.contains("/observation/${obs.id.value}"),
        "The supporting-observation row must link to the canonical /observation/{id} path",
      )
    }
}
