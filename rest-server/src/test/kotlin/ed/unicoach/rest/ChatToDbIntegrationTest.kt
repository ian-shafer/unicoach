package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.chat.AnthropicChatProvider
import ed.unicoach.chat.AnthropicTestFixtures
import ed.unicoach.chat.Replay
import ed.unicoach.chat.ScriptedAnthropicTransport
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.web.common.logging.RequestLoggingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RFC 107: chat provider -> DB, through Ktor routing. The server is wired with the
 * REAL [AnthropicChatProvider] over a [SwappableAnthropicTransport] seam (a dumb
 * wire replayer of recorded Anthropic SSE frames). Everything above the seam is
 * real: the provider's SSE parsing and terminal classification, CoachingService,
 * the DAO writes, and the Postgres rows. Extraction is disabled here (it is
 * covered end-to-end in OfflineCoachingE2eTest); convo/jobs tables are truncated
 * per test for row isolation.
 */
class ChatToDbIntegrationTest {
  companion object {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var connection: Connection
    private val transport = SwappableAnthropicTransport()
    private var port: Int = 0

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      val database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

      val sessionConfig = SessionConfig.from(config).getOrThrow()
      val requestSizeConfig = RequestSizeConfig.from(config).getOrThrow()
      val coachingConfig = CoachingConfig.from(config).getOrThrow()
      val clientKeyGateConfig = ClientKeyGateConfig.from(config).getOrThrow()
      val requestLoggingConfig = RequestLoggingConfig.from(config).getOrThrow()
      val queueService = ed.unicoach.queue.QueueService(database)
      val emailVerificationConfig =
        ed.unicoach.auth.EmailVerificationConfig
          .from(config)
          .getOrThrow()
      val googleTokenVerifier = ed.unicoach.auth.GoogleIdTokenVerifier(ed.unicoach.auth.StubIdTokenVerifier())
      val appleTokenVerifier = ed.unicoach.auth.AppleIdTokenVerifier(ed.unicoach.auth.StubIdTokenVerifier())
      val extractionDisabled =
        ExtractionConfig
          .from(
            com.typesafe.config.ConfigFactory
              .parseString("extraction.enabled = false")
              .withFallback(config),
          ).getOrThrow()

      // The real provider over the fake transport seam, wrapped by the real
      // LlmCallLog (RFC 106) — the production recorder that writes llm_requests /
      // llm_responses / llm_responses_raw, which these tests assert on.
      val llmCallLog = LlmCallLog(AnthropicChatProvider(transport, AutoCloseable {}), database)

      server =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            llmCallLog,
            coachingConfig,
            clientKeyGateConfig,
            emailVerificationConfig,
            googleTokenVerifier,
            appleTokenVerifier,
            queueService,
            extractionDisabled,
            requestLoggingConfig,
            BudgetConfig.from(config).getOrThrow(),
            offlineAppStoreServerApi(),
            subscriptionPlansFrom(config),
            testAppleNotificationVerifier(),
            costReportConfigFrom(config),
          )
        }
      server.start(wait = false)
      port =
        runBlocking {
          server.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::server.isInitialized) server.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  @BeforeEach
  fun resetConvoTables() {
    connection.createStatement().use { stmt ->
      // llm_requests CASCADE clears convo_requests, llm_responses, and
      // llm_responses_raw (RFC 106); convos clears the conversation shell.
      stmt.execute("TRUNCATE TABLE convos, llm_requests CASCADE")
      stmt.execute("TRUNCATE TABLE jobs CASCADE")
    }
  }

  private fun url(path: String) = "http://localhost:$port$path"

  private fun markEmailVerified(email: String) {
    connection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private suspend fun registerWithStudent(): String {
    val email = "chat${java.util.UUID.randomUUID()}@company.com"
    val reg =
      client.post(url("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Chat User")))
      }
    assertEquals(HttpStatusCode.Created, reg.status)
    markEmailVerified(email)
    val cookie =
      reg.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    val sr =
      client.post(url("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }
    assertEquals(HttpStatusCode.Created, sr.status)
    return cookie
  }

  private suspend fun createConvo(
    cookie: String,
    message: String,
  ): io.ktor.client.statement.HttpResponse =
    client.post(url("/api/v1/conversations")) {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateConversationRequest(message, null)))
    }

  // --- row queries -----------------------------------------------------------

  private data class RequestRow(
    val id: Long,
    val provider: String,
    val modelRequested: String,
    val systemPromptId: String,
  )

  private fun requestRows(convoId: String): List<RequestRow> {
    val out = mutableListOf<RequestRow>()
    connection
      .prepareStatement(
        // RFC 106 moved provider/model_requested off convo_requests onto the logged
        // call (llm_requests), reached via convo_requests.llm_request_id.
        // system_prompt_id stays on convo_requests (a system_prompts FK, RFC 0007).
        """
        SELECT cr.id, lr.provider, lr.model_requested, cr.system_prompt_id::text
        FROM convo_requests cr JOIN llm_requests lr ON cr.llm_request_id = lr.id
        WHERE cr.convo_id = ?::uuid ORDER BY cr.id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, convoId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            out.add(RequestRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
          }
        }
      }
    return out
  }

  private data class ResponseRow(
    val id: Long,
    val stopReason: String,
    val modelResolved: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val cacheReadTokens: Int?,
    val cacheWriteTokens: Int?,
    val providerRequestId: String?,
  )

  private fun responseRows(convoId: String): List<ResponseRow> {
    val out = mutableListOf<ResponseRow>()
    connection
      .prepareStatement(
        // RFC 106: the response lives in llm_responses, reached from the convo turn
        // via convo_requests.llm_request_id = llm_responses.request_id (1:1).
        """
        SELECT resp.id, resp.stop_reason, resp.model_resolved, resp.input_tokens, resp.output_tokens,
               resp.cache_read_tokens, resp.cache_write_tokens, resp.provider_request_id
        FROM convo_requests cr JOIN llm_responses resp ON resp.request_id = cr.llm_request_id
        WHERE cr.convo_id = ?::uuid ORDER BY cr.id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, convoId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            out.add(
              ResponseRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getObject(4) as Int?,
                rs.getObject(5) as Int?,
                rs.getObject(6) as Int?,
                rs.getObject(7) as Int?,
                rs.getString(8),
              ),
            )
          }
        }
      }
    return out
  }

  private fun rawPayload(responseId: Long): String? =
    connection.prepareStatement("SELECT payload FROM llm_responses_raw WHERE response_id = ?").use { stmt ->
      stmt.setLong(1, responseId)
      stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

  // ---------------------------------------------------------------------------

  @Test
  fun `a plain turn persists request, response, and raw rows with usage and stop_reason`() =
    runBlocking {
      transport.script(AnthropicTestFixtures.canonicalTextReplay)
      val cookie = registerWithStudent()
      val resp = createConvo(cookie, "hello coach")
      assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
      val convoId = mapper.readTree(resp.bodyAsText())["conversation"]["id"].asText()

      val requests = requestRows(convoId)
      assertEquals(1, requests.size, "expected one request row")
      assertEquals("anthropic", requests[0].provider)
      assertTrue(requests[0].modelRequested.isNotBlank())
      assertTrue(requests[0].systemPromptId.isNotBlank())

      val responses = responseRows(convoId)
      assertEquals(1, responses.size, "expected one response row")
      val r = responses[0]
      assertEquals("end_turn", r.stopReason)
      assertEquals(AnthropicTestFixtures.MODEL, r.modelResolved)
      // Usage folds message_start (input 12, cache_read 3, cache_write 0) with
      // message_delta (output 8) — recorded in canonicalTextReplay.
      assertEquals(12, r.inputTokens)
      assertEquals(8, r.outputTokens)
      assertEquals(3, r.cacheReadTokens)
      assertEquals(0, r.cacheWriteTokens)
      assertEquals(AnthropicTestFixtures.MESSAGE_ID, r.providerRequestId)

      val raw = assertNotNull(rawPayload(r.id), "expected an llm_responses_raw row")
      val rawJson = Json.parseToJsonElement(raw).jsonObject
      assertEquals(AnthropicTestFixtures.MESSAGE_ID, rawJson["id"]!!.jsonPrimitive.content)
      assertTrue(raw.contains("Hello, world"), "raw payload must carry the recorded text")
    }

  @Test
  fun `the wire body the real provider built maps the ChatRequest`() =
    runBlocking {
      transport.script(AnthropicTestFixtures.canonicalTextReplay)
      val cookie = registerWithStudent()
      assertEquals(HttpStatusCode.Created, createConvo(cookie, "map me").status)

      val body = assertNotNull(transport.current.body, "the real provider must have called the transport")
      assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
      assertTrue(body.containsKey("model"))
      assertTrue(body.containsKey("max_tokens"))
      assertTrue(body.containsKey("system"))
      // The new user input rides as a content block array whose text is the message.
      assertTrue(body.toString().contains("map me"), "the wire body must carry the user message")
    }

  @Test
  fun `a tool-dispatch turn continues with a TOOL_RESULT and collapses the transcript`() =
    runBlocking {
      transport.script(
        AnthropicTestFixtures.searchCollegesToolUseReplay,
        AnthropicTestFixtures.canonicalTextReplay,
      )
      val cookie = registerWithStudent()
      val resp = createConvo(cookie, "biology colleges?")
      assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
      val convoId = mapper.readTree(resp.bodyAsText())["conversation"]["id"].asText()

      // Two provider calls: the tool_use opener and the continuation.
      assertEquals(2, transport.current.calls, "the tool loop must make two provider calls")
      // The continuation request's last message carries a tool_result block.
      val continuation = transport.current.bodies[1]
      val lastMessage =
        continuation["messages"]!!
          .jsonArray
          .last()
          .jsonObject
      val blockTypes =
        lastMessage["content"]!!
          .jsonArray
          .map { it.jsonObject["type"]!!.jsonPrimitive.content }
      assertTrue(blockTypes.contains("tool_result"), "the continuation must carry a tool_result, got $blockTypes")

      // Both excursion rows persisted: opener stop_reason tool_use, continuation end_turn.
      val stopReasons = responseRows(convoId).map { it.stopReason }
      assertEquals(listOf("tool_use", "end_turn"), stopReasons)

      // The transcript endpoint collapses the excursion to one user + one coach message.
      val messages =
        client.get(url("/api/v1/conversations/$convoId/messages")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, messages.status)
      val list: JsonNode = mapper.readTree(messages.bodyAsText())["messages"]
      assertEquals(2, list.size(), "expected user + final coach, got ${messages.bodyAsText()}")
      assertEquals("user", list[0]["role"].asText())
      assertEquals("coach", list[1]["role"].asText())
    }

  @Test
  fun `a provider transient failure fails the turn without a success response row`() =
    runBlocking {
      // A recorded transport IO failure: Opened then a thrown IOException, which the
      // real provider maps to a TransientFailure terminal.
      transport.current =
        ScriptedAnthropicTransport(
          listOf(Replay(listOf(AnthropicTestFixtures.opened()), throwing = IOException("connection reset"))),
        )
      val cookie = registerWithStudent()
      val resp = createConvo(cookie, "this will fail")
      assertEquals(HttpStatusCode.InternalServerError, resp.status)

      // No successful (end_turn) response row was written for the failed turn.
      connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT COUNT(*) FROM llm_responses WHERE stop_reason = 'end_turn'").use { rs ->
          rs.next()
          assertEquals(0, rs.getInt(1), "a failed turn must not leave a successful response row")
        }
      }
    }
}
