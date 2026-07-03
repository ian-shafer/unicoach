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

class CommitmentsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A user with a student that owns one commitment; returns the commitment id string. */
  private fun seedCommitment(statement: String): String {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    return AdminTestSupport
      .seedCommitment(student.id, statement = statement)
      .id.value
      .toString()
  }

  @Test
  fun `list shows a seeded row, omits inList=false columns, and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val statement = "STATEMENT_MARKER_${UUID.randomUUID()}"
      val commitmentId = seedCommitment(statement)

      val list = client().get("/commitment") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(commitmentId), "List must render the commitment row")
      assertTrue(body.contains("gap"), "List must render the lens cell")
      assertFalse(body.contains(statement), "List must omit the statement (inList = false)")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/commitment"), "Dashboard must link to /commitment")
      assertTrue(dashboard.contains("Commitment"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail shows all fields including the inList=false statement`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val statement = "DETAIL_STATEMENT_${UUID.randomUUID()}"
      val commitmentId = seedCommitment(statement)

      val detail = client().get("/commitment/$commitmentId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains(statement), "Detail must render the full statement")
      assertTrue(body.contains("Trigger Kind"), "Detail must render inList=false fields like Trigger Kind")
    }

  @Test
  fun `read-only - no write routes resolve and no edit or delete affordance renders`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val commitmentId = seedCommitment("read only ${UUID.randomUUID()}")

      assertEquals(HttpStatusCode.NotFound, client().get("/commitment/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(HttpStatusCode.NotFound, client().get("/commitment/$commitmentId/edit") { header(HttpHeaders.Cookie, cookie) }.status)
      val del =
        client().submitForm(url = "/commitment/$commitmentId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/commitment/$commitmentId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/commitment/$commitmentId/edit"), "No edit control")
      assertFalse(detail.contains("/commitment/$commitmentId/delete"), "No delete control")
      assertFalse(detail.contains("/commitment/$commitmentId/undelete"), "No undelete control")
    }

  @Test
  fun `a malformed id segment returns the not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val malformed = client().get("/commitment/not-a-uuid") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, malformed.status, "parseId must reject non-UUID segments")
    }

  @Test
  fun `an unauthenticated request is redirected to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val res = client().get("/commitment")
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/login", res.headers[HttpHeaders.Location])
    }

  @Test
  fun `detail renders a Supporting claims panel linking to the claim detail`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val commitment = AdminTestSupport.seedCommitment(student.id)
      val claim = AdminTestSupport.seedClaim(student.id)
      AdminTestSupport.seedCommitmentSupport(commitment.id, claim.id)

      val detail = client().get("/commitment/${commitment.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("Supporting claims"), "The edge panel must render")
      assertTrue(
        detail.contains("/claim/${claim.id.value}"),
        "The supporting-claim row must link to the canonical /claim/{id} path",
      )
    }

  @Test
  fun `the supporting-claims panel is empty for an uncited commitment`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val commitmentId = seedCommitment("no cited claims ${UUID.randomUUID()}")

      val detail = client().get("/commitment/$commitmentId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("Supporting claims"), "The (empty) edge panel must still render")
      assertFalse(detail.contains("/claim/"), "No supporting-claim rows for an uncited commitment")
    }
}
