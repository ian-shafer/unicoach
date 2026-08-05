package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.dao.LlmCallsDao
import ed.unicoach.db.models.FrozenCost
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmRequestId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the RFC 106 admin call surface: the [LlmRequestsResource] call-detail
 * rendering (request/response JSON, tokens, stop reason, latency; a bodiless call
 * omits the raw block) and the unlinked-call anti-join backing the dedicated
 * filtered list, asserted directly through [LlmCallsDao.listUnlinkedCalls] (its
 * documented backing query).
 */
class LlmRequestsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  private fun json(raw: String): JsonArray = Json.parseToJsonElement(raw) as JsonArray

  private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

  @Test
  fun `call detail renders request content, tools, response content, tokens, stop reason, and latency`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val llmRequestId =
        AdminTestSupport.seedLlmRequest(
          content = json("""[{"role":"user","content":[{"type":"text","text":"REQ_MARKER"}]}]"""),
          tools = json("""[{"name":"emit_result","description":"TOOL_MARKER"}]"""),
          toolChoice = obj { put("type", "tool") },
        )
      AdminTestSupport.seedLlmResponse(
        llmRequestId,
        outcome =
          LlmCallOutcome.Completed(
            content = Json.parseToJsonElement("""[{"type":"text","text":"RESP_MARKER"}]"""),
            modelResolved = "claude-opus-4-8",
            stopReason = "end_turn",
          ),
        inputTokens = 111,
        outputTokens = 222,
        latencyMs = 456,
        rawPayload = Json.parseToJsonElement("""{"raw":"RAW_MARKER"}"""),
      )

      val id = llmRequestId.value.toString()
      val detail = client().get("/llm-request/$id") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("<pre class=\"json-pretty\">"), "JSON fields must render through the pretty-printer")
      assertTrue(body.contains("REQ_MARKER"), "Request content must render")
      assertTrue(body.contains("TOOL_MARKER"), "Request tools must render")
      assertTrue(body.contains("RESP_MARKER"), "Response content must render")
      assertTrue(body.contains("RAW_MARKER"), "Raw payload must render")
      assertTrue(body.contains("end_turn"), "Stop reason must render")
      assertTrue(body.contains("111"), "Input tokens must render")
      assertTrue(body.contains("222"), "Output tokens must render")
      assertTrue(body.contains("456"), "Latency must render")
      assertTrue(body.contains("completed"), "Outcome must render")
    }

  @Test
  fun `call detail renders the frozen cost as dollars, nano-dollars, and the estimated flag`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val llmRequestId = AdminTestSupport.seedLlmRequest()
      AdminTestSupport.seedLlmResponse(
        llmRequestId,
        cost = FrozenCost(nanodollars = Nanodollars.of(3_000_000), estimated = false),
      )

      val id = llmRequestId.value.toString()
      val body = client().get("/llm-request/$id") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(body.contains("0.003000"), "costNanodollars must render as USD (nano-dollars / 1e9 at 6 dp)")
      assertTrue(body.contains("3000000"), "the raw nano-dollar integer must render in the hover title")
      assertTrue(body.contains("Cost Estimated"), "the estimated-flag field label must render")
    }

  @Test
  fun `call detail renders a NULL-cost call with blank cost fields, without error`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val llmRequestId = AdminTestSupport.seedLlmRequest()
      AdminTestSupport.seedLlmResponse(llmRequestId, cost = null)

      val id = llmRequestId.value.toString()
      val detail = client().get("/llm-request/$id") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "a NULL-cost call must render without error")
      val body = detail.bodyAsText()
      assertTrue(body.contains("Cost (USD)"), "the cost field label still renders (blank cell)")
    }

  @Test
  fun `a call with no raw payload omits the raw block without error`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      // A bodiless failure terminal: no llm_responses_raw row.
      val llmRequestId = AdminTestSupport.seedLlmRequest()
      AdminTestSupport.seedLlmResponse(
        llmRequestId,
        outcome =
          LlmCallOutcome.Failed(
            kind = ed.unicoach.db.models.LlmFailureKind.TRANSIENT_FAILURE,
            reason = "FAILURE_MARKER upstream 503",
          ),
        inputTokens = null,
        outputTokens = null,
        cacheReadTokens = null,
        cacheWriteTokens = null,
        rawPayload = null,
      )

      val id = llmRequestId.value.toString()
      val detail = client().get("/llm-request/$id") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "A call with no raw payload must render without error")
      val body = detail.bodyAsText()
      assertTrue(body.contains("transient_failure"), "The failure outcome must render")
      assertTrue(body.contains("FAILURE_MARKER upstream 503"), "The failure reason must render")
      assertTrue(body.contains("Raw Payload"), "The raw-payload field label still renders (blank cell)")
    }

  @Test
  fun `list renders a seeded call and the dashboard lists the nav entry`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val llmRequestId = AdminTestSupport.seedLlmRequest()
      AdminTestSupport.seedLlmResponse(llmRequestId)
      val id = llmRequestId.value.toString()

      val list = client().get("/llm-request") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      assertTrue(list.bodyAsText().contains(id), "List must render the call row")

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(dashboard.contains("/llm-request"), "Dashboard must link to /llm-request")
      assertTrue(dashboard.contains("LLM Requests"), "Dashboard nav must list the resource")
    }

  @Test
  fun `GET llm-request id returns 404 for a malformed id`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val res = client().get("/llm-request/not-a-number") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, res.status, "parseId must reject non-numeric segments")
    }

  /**
   * Inserts an `llm_requests` row with an explicit backdated `created_at` (the
   * append-only guard blocks UPDATE, not an INSERT that supplies the timestamp), so
   * a call can be aged past the unlinked-call threshold. Returns its id.
   */
  private fun seedOldUnlinkedCall(ageHours: Long): LlmRequestId =
    runBlocking {
      AdminTestSupport.database
        .withConnection { session ->
          session
            .prepareStatement(
              """
              INSERT INTO llm_requests (created_at, provider, model_requested, system, content, max_tokens)
              VALUES (NOW() - ($ageHours || ' hours')::interval, 'anthropic', 'claude-opus-4-8', NULL, '[]'::jsonb, 1024)
              RETURNING id
              """.trimIndent(),
            ).use { stmt ->
              stmt.executeQuery().use { rs ->
                rs.next()
                Result.success(LlmRequestId(rs.getLong("id")))
              }
            }
        }.getOrThrow()
    }

  @Test
  fun `unlinked-call anti-join returns an old orphan, excludes a referenced call and a young orphan`() {
    // An old orphan referenced by no domain row (backdated well past the 1h threshold).
    val oldOrphan = seedOldUnlinkedCall(ageHours = 3)

    // A recent call referenced by a domain row (an extraction run): excluded regardless of age.
    val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
    val student = AdminTestSupport.seedStudent(user.id)
    val convo = AdminTestSupport.seedConvo(student.id)
    val req = AdminTestSupport.seedConvoRequest(convo.id)
    val referencedCall = AdminTestSupport.seedLlmRequest()
    AdminTestSupport.seedExtractionRun(student.id, convo.id, req.id, llmRequestId = referencedCall)

    // A young orphan (just created, no owner): excluded by the age gate.
    val youngOrphan = AdminTestSupport.seedLlmRequest()

    val unlinked =
      runBlocking {
        AdminTestSupport.database
          .withConnection { session ->
            LlmCallsDao.listUnlinkedCalls(session, Duration.ofHours(1), limit = 200, offset = 0)
          }.getOrThrow()
      }
    val ids = unlinked.map { it.request.id }

    assertTrue(ids.contains(oldOrphan), "The old orphan call must be returned")
    assertFalse(ids.contains(referencedCall), "A domain-referenced call must be excluded")
    assertFalse(ids.contains(youngOrphan), "An orphan younger than the threshold must be excluded")
  }

  @Test
  fun `the unlinked-call route renders the old orphan and links to its detail`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val oldOrphan = seedOldUnlinkedCall(ageHours = 3)

      val page = client().get("/llm-request/unlinked") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, page.status)
      val body = page.bodyAsText()
      val id = oldOrphan.value.toString()
      assertTrue(body.contains("/llm-request/$id"), "The unlinked list must link to the old orphan's detail")
    }
}
