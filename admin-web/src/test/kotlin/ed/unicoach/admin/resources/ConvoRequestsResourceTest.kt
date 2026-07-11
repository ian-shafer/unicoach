package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConvoRequestsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /**
   * Seeds a convo with one request whose owning `llm_requests` row is stamped and,
   * when [withResponse], a completed `llm_responses` terminal is joined. Returns
   * the ids the assertions need.
   */
  private fun seedTurn(withResponse: Boolean): TurnIds {
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val convo = AdminTestSupport.seedConvo(student.id)
    val llmRequestId = AdminTestSupport.seedLlmRequest()
    val req = AdminTestSupport.seedConvoRequest(convo.id, llmRequestId = llmRequestId)
    if (withResponse) {
      AdminTestSupport.seedLlmResponse(llmRequestId)
    }
    return TurnIds(
      requestId = req.id.value.toString(),
      convoId = convo.id.value.toString(),
      llmRequestId = llmRequestId.value.toString(),
    )
  }

  private data class TurnIds(
    val requestId: String,
    val convoId: String,
    val llmRequestId: String,
  )

  @Test
  fun `GET convo-request lists turns`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val first = seedTurn(withResponse = true)
      val second = seedTurn(withResponse = true)

      val list = client().get("/convo-request") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val body = list.bodyAsText()
      assertTrue(body.contains("/convo-request/${first.requestId}"), "List must render the first turn")
      assertTrue(body.contains("/convo-request/${second.requestId}"), "List must render the second turn")
      // Ordered id DESC: the later (higher-id) request appears before the earlier.
      assertTrue(
        body.indexOf("/convo-request/${second.requestId}") < body.indexOf("/convo-request/${first.requestId}"),
        "Turns must be ordered most-recent first",
      )
    }

  @Test
  fun `GET convo-request id links to the logged call and shows the reply stop reason`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val turn = seedTurn(withResponse = true)

      val detail = client().get("/convo-request/${turn.requestId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      // The I/O envelope moved to the generic call log (RFC 106): the coaching row links there.
      assertTrue(
        body.contains("/llm-request/${turn.llmRequestId}"),
        "The llmRequestId cell must link to the generic call log",
      )
      assertTrue(body.contains("Response Stop Reason"), "The at-a-glance stop-reason row still renders")
      assertTrue(body.contains("end_turn"), "The joined terminal's stop reason renders at a glance")
      // The token / model / content columns moved to the linked call; they no longer render here.
      assertFalse(body.contains("Input Tokens"), "Moved token columns must not render on the convo-request page")
      assertFalse(body.contains("Request Content"), "Moved request-content column must not render here")
    }

  @Test
  fun `GET convo-request id renders a request with no response`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val turn = seedTurn(withResponse = false)

      val detail = client().get("/convo-request/${turn.requestId}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "A request without a response must still render")
      val body = detail.bodyAsText()
      assertTrue(body.contains("Response Stop Reason"), "Response field labels still render")
      // No terminal yet: the at-a-glance stop reason is blank, and the call link still renders.
      assertTrue(body.contains("/llm-request/${turn.llmRequestId}"), "The llmRequestId link renders even with no terminal")
    }

  @Test
  fun `convoId links to convo and systemPromptId links to system-prompt`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val turn = seedTurn(withResponse = true)

      val body = client().get("/convo-request/${turn.requestId}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(body.contains("/convo/${turn.convoId}"), "convoId must link to /convo/{id}")
      assertTrue(body.contains("/system-prompt/"), "systemPromptId must link to /system-prompt/{id}")
    }

  @Test
  fun `GET convo-request id compacts convoId and systemPromptId but leaves the BIGINT id raw`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id)
      val req = AdminTestSupport.seedConvoRequest(convo.id)
      val convoId = req.convoId.value.toString()
      val systemPromptId = req.systemPromptId.value.toString()
      val requestId = req.id.value.toString()

      val detail = client().get("/convo-request/$requestId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()

      // convoId (refSlug "convo") and systemPromptId (refSlug "system-prompt") are now
      // FieldType.UUID: each compacts and keeps its navigation href.
      AdminTestSupport.assertCompactUuid(body, convoId, "/convo/$convoId")
      AdminTestSupport.assertCompactUuid(body, systemPromptId, "/system-prompt/$systemPromptId")

      // The BIGINT primary key stays FieldType.TEXT: rendered raw with no copy button.
      // This is the regression guard that the BIGINT column was not flipped to UUID.
      assertTrue(body.contains(requestId), "The BIGINT request id must render raw")
      assertFalse(body.contains("data-full=\"$requestId\""), "The BIGINT TEXT id must not emit a copy button")
    }

  @Test
  fun `GET convo-request id returns 404 for missing id`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val res = client().get("/convo-request/999999999") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status)
    }

  @Test
  fun `GET convo-request id returns 404 for malformed id`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val res = client().get("/convo-request/not-a-number") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status, "parseId must reject non-numeric segments")
    }
}
