package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.auth.AppleIdTokenVerifier
import ed.unicoach.auth.GoogleIdTokenVerifier
import ed.unicoach.auth.StubIdTokenVerifier
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.PostMessageRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.web.common.logging.RequestLoggingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the REST surface of the coaching budget gate (RFC 109) against a real
 * server booted from the packaged `service.conf`: the 402 on all four turn
 * endpoints, the read routes that stay open behind it, and the usage endpoint.
 *
 * Exhaustion is seeded by raw SQL rather than by config override — one attributed
 * `llm_requests`/`llm_responses` pair costed far above any sane allowance —
 * mirroring `StudentLlmCostDaoTest`'s helper. That keeps the server on the real
 * configured allowance, so this suite exercises exactly the wiring production
 * runs.
 */
class CoachingBudgetRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var dbConnection: java.sql.Connection
    private lateinit var budgetConfig: BudgetConfig
    private var boundPort: Int = 0

    /** A provider that always answers, so an admitted turn completes. */
    private val fakeProvider =
      object : ChatProvider {
        override val id: String = "log"

        override fun stream(request: ChatRequest): Flow<ChatEvent> =
          flow {
            val content =
              buildJsonArray {
                add(
                  buildJsonObject {
                    put("type", "text")
                    put("text", "ok")
                  },
                )
              }
            emit(ChatEvent.ContentBlockStart(index = 0, blockType = "text", block = null))
            emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("ok")))
            emit(
              ChatEvent.Completed(
                response =
                  ChatResponse(
                    content = content,
                    modelResolved = "log",
                    stopReason = "end_turn",
                    usage = TokenUsage(1, 1, 0, 0),
                    providerRequestId = "req",
                  ),
                rawPayload = content,
              ),
            )
          }
      }

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      val database = Database(dbConfig)
      dbConnection = java.sql.DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
      budgetConfig = BudgetConfig.from(config).getOrThrow()

      testServer =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { database.close() }
          appModule(
            database,
            SessionConfig.from(config).getOrThrow(),
            RequestSizeConfig.from(config).getOrThrow(),
            ed.unicoach.coaching.LlmCallLog(fakeProvider, database),
            CoachingConfig.from(config).getOrThrow(),
            ClientKeyGateConfig.from(config).getOrThrow(),
            ed.unicoach.auth.EmailVerificationConfig
              .from(config)
              .getOrThrow(),
            GoogleIdTokenVerifier(StubIdTokenVerifier()),
            AppleIdTokenVerifier(StubIdTokenVerifier()),
            ed.unicoach.queue.QueueService(database),
            ed.unicoach.coaching.extraction.ExtractionConfig
              .from(config)
              .getOrThrow(),
            RequestLoggingConfig.from(config).getOrThrow(),
            budgetConfig,
            offlineAppStoreServerApi(),
            subscriptionPlansFrom(config),
          )
        }
      testServer.start(wait = false)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::testServer.isInitialized) testServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::dbConnection.isInitialized && !dbConnection.isClosed) dbConnection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun url(path: String) = "http://localhost:$boundPort$path"

  // ---------------------------------------------------------------------------
  // Callers
  // ---------------------------------------------------------------------------

  /** A verified user WITHOUT a student profile. Returns the session cookie and email. */
  private suspend fun registerVerifiedUser(): Pair<String, String> {
    val email = "budget${UUID.randomUUID()}@company.com"
    val registration =
      client.post(url("/api/v1/auth/register")) {
        contentType(ContentType.Application.Json)
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Budget User")))
      }
    val cookie =
      registration.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    dbConnection
      .prepareStatement("UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ?")
      .use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
    return cookie to email
  }

  /** A verified user WITH a student profile. */
  private suspend fun registerStudent(): Caller {
    val (cookie, email) = registerVerifiedUser()
    client.post(url("/api/v1/students")) {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
    }
    return Caller(cookie, studentIdOf(email))
  }

  private class Caller(
    val cookie: String,
    val studentId: UUID,
  )

  private fun studentIdOf(email: String): UUID =
    dbConnection
      .prepareStatement("SELECT s.id FROM students s JOIN users u ON u.id = s.user_id WHERE u.email = ?")
      .use { stmt ->
        stmt.setString(1, email)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getObject("id") as UUID
        }
      }

  // ---------------------------------------------------------------------------
  // Spend seeding
  // ---------------------------------------------------------------------------

  /**
   * Attributes one costed call of [costNanodollars] to [studentId] through a
   * synthesis run — the cheapest single-call owner on the `student_llm_cost`
   * spine.
   */
  private fun seedSpend(
    studentId: UUID,
    costNanodollars: Long,
  ) {
    val llmRequestId =
      dbConnection
        .prepareStatement(
          "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) " +
            "VALUES ('anthropic', 'claude-sonnet-4-6', '[]'::jsonb, 1024) RETURNING id",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    dbConnection
      .prepareStatement(
        """
        INSERT INTO llm_responses
          (request_id, outcome, content, model_resolved, stop_reason, cost_nanodollars, cost_is_estimated, input_tokens, output_tokens, latency_ms)
        VALUES (?, 'completed', '[]'::jsonb, 'm', 'end_turn', ?, false, 1, 1, 1)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setLong(1, llmRequestId)
        stmt.setLong(2, costNanodollars)
        stmt.executeUpdate()
      }
    dbConnection
      .prepareStatement(
        "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'applied', ?, ?)",
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.setObject(2, seedSystemPrompt())
        stmt.setLong(3, llmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun seedSystemPrompt(): UUID {
    val id = UUID.randomUUID()
    dbConnection
      .prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'synthesis', ?, 'reflect')")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.setString(2, "budget-${UUID.randomUUID()}")
        stmt.executeUpdate()
      }
    return id
  }

  /** Spend well past any sane allowance — $10^6, six orders above the packaged $5. */
  private fun exhaust(studentId: UUID) = seedSpend(studentId, 1_000_000_000_000_000L)

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  private suspend fun startConvo(
    caller: Caller,
    path: String = "/api/v1/conversations",
  ): HttpResponse =
    client.post(url(path)) {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Cookie, caller.cookie)
      setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
    }

  private suspend fun postMessage(
    caller: Caller,
    convoId: String,
    suffix: String = "",
  ): HttpResponse =
    client.post(url("/api/v1/conversations/$convoId/messages$suffix")) {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Cookie, caller.cookie)
      setBody(mapper.writeValueAsString(PostMessageRequest("again")))
    }

  private suspend fun HttpResponse.errorCode(): String = mapper.readTree(bodyAsText()).get("code").asText()

  /** Creates a conversation while the caller is still entitled, returning its id. */
  private suspend fun seedConvo(caller: Caller): String {
    val created = startConvo(caller)
    assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
    return mapper
      .readTree(created.bodyAsText())
      .get("conversation")
      .get("id")
      .asText()
  }

  // ---------------------------------------------------------------------------
  // 402 on the four turn endpoints
  // ---------------------------------------------------------------------------

  @Test
  fun `creating a conversation while exhausted is 402 and creates nothing`() =
    runBlocking {
      val caller = registerStudent()
      exhaust(caller.studentId)

      val response = startConvo(caller)

      assertEquals(HttpStatusCode.PaymentRequired, response.status)
      assertEquals("coaching_budget_exhausted", response.errorCode())

      val list =
        client.get(url("/api/v1/conversations")) {
          header(HttpHeaders.Cookie, caller.cookie)
        }
      assertEquals(HttpStatusCode.OK, list.status)
      assertEquals(
        0,
        mapper
          .readTree(list.bodyAsText())
          .get("conversations")
          .size(),
        "the refusal precedes persistence, so no conversation exists",
      )
    }

  @Test
  fun `posting a message while exhausted is 402`() =
    runBlocking {
      val caller = registerStudent()
      val convoId = seedConvo(caller)
      exhaust(caller.studentId)

      val response = postMessage(caller, convoId)

      assertEquals(HttpStatusCode.PaymentRequired, response.status)
      assertEquals("coaching_budget_exhausted", response.errorCode())
    }

  @Test
  fun `the streaming create endpoint refuses with plain JSON, never an SSE stream`() =
    runBlocking {
      val caller = registerStudent()
      exhaust(caller.studentId)

      val response = startConvo(caller, path = "/api/v1/conversations/stream")

      assertEquals(HttpStatusCode.PaymentRequired, response.status)
      assertEquals("coaching_budget_exhausted", response.errorCode())
      assertEquals(
        ContentType.Application.Json.contentType,
        response.contentType()?.contentType,
        "the refusal happens in pre-flight, before the stream opens",
      )
      assertEquals(
        ContentType.Application.Json.contentSubtype,
        response.contentType()?.contentSubtype,
      )
    }

  @Test
  fun `the streaming message endpoint refuses with plain JSON, never an SSE stream`() =
    runBlocking {
      val caller = registerStudent()
      val convoId = seedConvo(caller)
      exhaust(caller.studentId)

      val response = postMessage(caller, convoId, suffix = "/stream")

      assertEquals(HttpStatusCode.PaymentRequired, response.status)
      assertEquals("coaching_budget_exhausted", response.errorCode())
      assertEquals(ContentType.Application.Json.contentSubtype, response.contentType()?.contentSubtype)
    }

  @Test
  fun `read routes stay open for an exhausted student`() =
    runBlocking {
      val caller = registerStudent()
      val convoId = seedConvo(caller)
      exhaust(caller.studentId)

      val list = client.get(url("/api/v1/conversations")) { header(HttpHeaders.Cookie, caller.cookie) }
      assertEquals(HttpStatusCode.OK, list.status, "reading history costs nothing")
      assertEquals(
        1,
        mapper
          .readTree(list.bodyAsText())
          .get("conversations")
          .size(),
      )

      val messages =
        client.get(url("/api/v1/conversations/$convoId/messages")) { header(HttpHeaders.Cookie, caller.cookie) }
      assertEquals(HttpStatusCode.OK, messages.status, "the block screen must still render past conversations")
    }

  // ---------------------------------------------------------------------------
  // GET /api/v1/students/me/coaching-usage
  // ---------------------------------------------------------------------------

  private suspend fun getUsage(cookie: String?): HttpResponse =
    client.get(url("/api/v1/students/me/coaching-usage")) {
      if (cookie != null) header(HttpHeaders.Cookie, cookie)
    }

  private suspend fun HttpResponse.usage(): Pair<Int, Boolean> {
    val usage = mapper.readTree(bodyAsText()).get("usage")
    return usage.get("usedPercent").asInt() to usage.get("exhausted").asBoolean()
  }

  @Test
  fun `usage is 401 without a session`() =
    runBlocking {
      val response = getUsage(cookie = null)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("unauthorized", response.errorCode())
    }

  @Test
  fun `usage is 409 for a user with no student profile`() =
    runBlocking {
      val (cookie, _) = registerVerifiedUser()

      val response = getUsage(cookie)

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("student_profile_required", response.errorCode())
    }

  @Test
  fun `usage reads zero for a fresh student`() =
    runBlocking {
      val caller = registerStudent()

      val response = getUsage(caller.cookie)

      assertEquals(HttpStatusCode.OK, response.status)
      val (usedPercent, exhausted) = response.usage()
      assertEquals(0, usedPercent)
      assertFalse(exhausted)
    }

  @Test
  fun `usage carries a null resetsAt on the free tier — the lifetime allowance never resets`() =
    runBlocking {
      val caller = registerStudent()

      val response = getUsage(caller.cookie)

      assertEquals(HttpStatusCode.OK, response.status)
      val usage = mapper.readTree(response.bodyAsText()).get("usage")
      assertTrue(usage.has("resetsAt"), "the widened response names the field even when null")
      assertTrue(usage.get("resetsAt").isNull)
    }

  @Test
  fun `usage reports a mid-range percentage for partial spend`() =
    runBlocking {
      val caller = registerStudent()
      // Half the CONFIGURED allowance, so the expectation tracks service.conf
      // rather than hardcoding today's $5.
      seedSpend(caller.studentId, budgetConfig.freeAllowance.value / 2)

      val response = getUsage(caller.cookie)

      assertEquals(HttpStatusCode.OK, response.status)
      val (usedPercent, exhausted) = response.usage()
      assertEquals(50, usedPercent)
      assertFalse(exhausted)
    }

  @Test
  fun `usage reports a blocked student at 100 percent`() =
    runBlocking {
      val caller = registerStudent()
      exhaust(caller.studentId)

      val response = getUsage(caller.cookie)

      assertEquals(HttpStatusCode.OK, response.status)
      val (usedPercent, exhausted) = response.usage()
      assertEquals(100, usedPercent)
      assertTrue(exhausted, "the client is told the block condition, never left to re-derive it")
    }

  @Test
  fun `usage rejects a non-GET method`() =
    runBlocking {
      val caller = registerStudent()

      val response =
        client.post(url("/api/v1/students/me/coaching-usage")) {
          header(HttpHeaders.Cookie, caller.cookie)
        }

      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }
}
