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

class SynthesisRunsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A user/student plus one applied run; returns its ids. */
  private fun seedRun(): SeededRun {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val llmRequestId = AdminTestSupport.seedLlmRequest()
    val run = AdminTestSupport.seedSynthesisRun(student.id, llmRequestId = llmRequestId)
    return SeededRun(runId = run.id.value.toString(), llmRequestId = llmRequestId.value.toString())
  }

  private data class SeededRun(
    val runId: String,
    val llmRequestId: String,
  )

  @Test
  fun `list shows a seeded row and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val runId = seedRun().runId

      val list = client().get("/synthesis-run") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains(runId), "List must render the run row")
      // The token columns moved to the linked call (RFC 106): they no longer render here.
      assertFalse(body.contains("Input Tokens"), "List must not show the moved token columns")
      assertTrue(body.contains("Commitments Written"), "List must show the commitments-written column")
      assertTrue(body.contains("LLM Request ID"), "List must show the linked-call column")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/synthesis-run"), "Dashboard must link to /synthesis-run")
      assertTrue(dashboard.contains("Synthesis Run"), "Dashboard nav must list the resource")
    }

  @Test
  fun `detail links to the generic call log and no longer renders moved token columns`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val seeded = seedRun()

      val detail = client().get("/synthesis-run/${seeded.runId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertFalse(body.contains("Cache Read Tokens"), "Detail must not render the moved token columns")
      assertTrue(body.contains("System Prompt ID"), "Detail must render provenance columns")
      assertTrue(
        body.contains("/llm-request/${seeded.llmRequestId}"),
        "The llmRequestId cell must link to the generic call log",
      )
    }

  @Test
  fun `read-only - no write routes resolve and no edit or delete affordance renders`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val runId = seedRun().runId

      assertEquals(HttpStatusCode.NotFound, client().get("/synthesis-run/new") { header(HttpHeaders.Cookie, cookie) }.status)
      assertEquals(HttpStatusCode.NotFound, client().get("/synthesis-run/$runId/edit") { header(HttpHeaders.Cookie, cookie) }.status)
      val del =
        client().submitForm(url = "/synthesis-run/$runId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, del.status)

      val detail = client().get("/synthesis-run/$runId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(detail.contains("/synthesis-run/$runId/edit"), "No edit control")
      assertFalse(detail.contains("/synthesis-run/$runId/delete"), "No delete control")
    }

  @Test
  fun `a malformed id segment returns the not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val malformed = client().get("/synthesis-run/not-a-number") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, malformed.status, "parseId must reject non-numeric segments")
    }

  @Test
  fun `an unauthenticated request is redirected to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val res = client().get("/synthesis-run")
      assertEquals(HttpStatusCode.Found, res.status)
      assertEquals("/login", res.headers[HttpHeaders.Location])
    }

  @Test
  fun `a failed run renders with zero counts`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val runId =
        AdminTestSupport
          .seedSynthesisRun(
            student.id,
            outcome =
              ed.unicoach.db.models.SynthesisOutcome
                .Failed(ed.unicoach.db.models.JsonParseFailureCategory.MALFORMED_JSON, "test failure"),
          ).id.value
          .toString()

      val detail = client().get("/synthesis-run/$runId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("failed"), "Detail must render the failed outcome")
    }

  @Test
  fun `a failed row's failureCategory renders on the list and failureReason renders on the detail`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val runId =
        AdminTestSupport
          .seedSynthesisRun(
            student.id,
            outcome =
              ed.unicoach.db.models.SynthesisOutcome
                .Failed(ed.unicoach.db.models.JsonParseFailureCategory.INVALID_FIELD, "field [lens]=[missing]"),
          ).id.value
          .toString()

      val list = client().get("/synthesis-run") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(list.contains("invalid_field"), "List must render the failure category")

      val detail = client().get("/synthesis-run/$runId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("field [lens]=[missing]"), "Detail must render the failure reason")
    }
}
