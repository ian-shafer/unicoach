package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.AppStoreTransport
import ed.unicoach.appstore.AppStoreTransportResponse
import ed.unicoach.appstore.ScriptedAppStoreTransport
import ed.unicoach.auth.StubGoogleTokenVerifier
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
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.SubscriptionVerifyRequest
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
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins `POST /api/v1/subscriptions/verify` and the subscribed coaching meter
 * end to end (RFC 110), against a real server whose `appModule` is wired with a
 * real [AppStoreServerApi] over a per-test swappable scripted transport: the
 * route's arm mapping, the row the happy path records, and the two e2e flows
 * the app will live until the webhook RFC — the entitlement flip on first
 * verify, and the verify-driven period rollover.
 */
class SubscriptionRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var dbConnection: java.sql.Connection
    private var boundPort: Int = 0

    /** The monthly10 plan's period budget: y = 0.5 of $9.99. */
    private const val PERIOD_BUDGET = 4_995_000_000L

    /** Unique Apple keys across the suite, so bindings never collide. */
    private val OTID_COUNTER = AtomicLong(900_000_000_000L)

    /** Per-test swap point for the Apple conversation, fixed at boot. */
    private object SwappableTransport : AppStoreTransport {
      @Volatile
      var delegate: AppStoreTransport =
        AppStoreTransport { path, _ -> throw IllegalStateException("No App Store script installed for [$path]") }

      override suspend fun get(
        path: String,
        bearerToken: String,
      ): AppStoreTransportResponse = delegate.get(path, bearerToken)
    }

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
            StubGoogleTokenVerifier(),
            ed.unicoach.queue.QueueService(database),
            ed.unicoach.coaching.extraction.ExtractionConfig
              .from(config)
              .getOrThrow(),
            RequestLoggingConfig.from(config).getOrThrow(),
            BudgetConfig.from(config).getOrThrow(),
            AppStoreServerApi(SwappableTransport, AppStoreTestFixtures.authTokens()),
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
  // Callers (CoachingBudgetRoutingTest's helpers)
  // ---------------------------------------------------------------------------

  private suspend fun registerUser(verified: Boolean = true): Pair<String, String> {
    val email = "sub${UUID.randomUUID()}@company.com"
    val registration =
      client.post(url("/api/v1/auth/register")) {
        contentType(ContentType.Application.Json)
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Sub User")))
      }
    val cookie =
      registration.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    if (verified) {
      dbConnection
        .prepareStatement("UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ?")
        .use { stmt ->
          stmt.setString(1, email)
          stmt.executeUpdate()
        }
    }
    return cookie to email
  }

  private suspend fun registerStudent(): Caller {
    val (cookie, email) = registerUser()
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
  // Apple scripting + verify requests
  // ---------------------------------------------------------------------------

  /** Unique per test-case use, so one suite's bindings never collide on Apple's key. */
  private fun uniqueOriginalTransactionId(): String = OTID_COUNTER.incrementAndGet().toString()

  private fun installActiveScript(
    originalTransactionId: String,
    periodStart: Instant,
    periodEnd: Instant,
    status: Int = 1,
  ) {
    SwappableTransport.delegate =
      ScriptedAppStoreTransport.of(
        200,
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = status,
            signedTransactionInfo =
              AppStoreTestFixtures.signedTransaction(
                originalTransactionId = originalTransactionId,
                purchaseDate = periodStart,
                expiresDate = periodEnd,
              ),
          ),
        ),
      )
  }

  private suspend fun postVerify(
    cookie: String?,
    signedTransaction: String = AppStoreTestFixtures.signedTransaction(),
  ): HttpResponse =
    client.post(url("/api/v1/subscriptions/verify")) {
      contentType(ContentType.Application.Json)
      if (cookie != null) header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(SubscriptionVerifyRequest(signedTransaction)))
    }

  private suspend fun HttpResponse.errorCode(): String = mapper.readTree(bodyAsText()).get("code").asText()

  // ---------------------------------------------------------------------------
  // Spend seeding (CoachingBudgetRoutingTest's helper, plus a created_at knob)
  // ---------------------------------------------------------------------------

  private fun seedSpend(
    studentId: UUID,
    costNanodollars: Long,
    createdAt: Instant,
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
          (request_id, outcome, content, model_resolved, stop_reason, cost_nanodollars, cost_is_estimated, input_tokens, output_tokens, latency_ms, created_at)
        VALUES (?, 'completed', '[]'::jsonb, 'm', 'end_turn', ?, false, 1, 1, 1, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setLong(1, llmRequestId)
        stmt.setLong(2, costNanodollars)
        stmt.setTimestamp(3, java.sql.Timestamp.from(createdAt))
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
        stmt.setString(2, "sub-${UUID.randomUUID()}")
        stmt.executeUpdate()
      }
    return id
  }

  private suspend fun startConvo(caller: Caller): HttpResponse =
    client.post(url("/api/v1/conversations")) {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Cookie, caller.cookie)
      setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
    }

  private suspend fun usage(caller: Caller): com.fasterxml.jackson.databind.JsonNode {
    val response = client.get(url("/api/v1/students/me/coaching-usage")) { header(HttpHeaders.Cookie, caller.cookie) }
    assertEquals(HttpStatusCode.OK, response.status)
    return mapper.readTree(response.bodyAsText()).get("usage")
  }

  // ---------------------------------------------------------------------------
  // Preamble refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `verify is 401 without a session`() =
    runBlocking {
      val response = postVerify(cookie = null)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("unauthorized", response.errorCode())
    }

  @Test
  fun `verify is 409 for a user with no student profile`() =
    runBlocking {
      val (cookie, _) = registerUser()

      val response = postVerify(cookie)

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("student_profile_required", response.errorCode())
    }

  @Test
  fun `verify is 403 for an unverified email — the gate covers the route automatically`() =
    runBlocking {
      val (cookie, _) = registerUser(verified = false)

      val response = postVerify(cookie)

      assertEquals(HttpStatusCode.Forbidden, response.status)
      assertEquals("email_not_verified", response.errorCode())
    }

  @Test
  fun `verify rejects a non-POST method`() =
    runBlocking {
      val response = client.get(url("/api/v1/subscriptions/verify"))
      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

  // ---------------------------------------------------------------------------
  // Arm mapping
  // ---------------------------------------------------------------------------

  @Test
  fun `a happy verify answers 200 and records the row`() =
    runBlocking {
      val caller = registerStudent()
      val otid = uniqueOriginalTransactionId()
      val periodStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      val periodEnd = Instant.now().plus(25, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      installActiveScript(otid, periodStart, periodEnd)

      val response = postVerify(caller.cookie)

      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      val subscription = mapper.readTree(response.bodyAsText()).get("subscription")
      assertEquals("active", subscription.get("status").asText())
      assertEquals(AppStoreTestFixtures.PRODUCT_ID, subscription.get("productId").asText())
      assertTrue(subscription.get("currentPeriodEnd").isTextual, "currentPeriodEnd is ISO-8601 text on the wire")
      assertEquals(periodEnd, Instant.parse(subscription.get("currentPeriodEnd").asText()))

      dbConnection
        .prepareStatement("SELECT status, product_id FROM subscriptions WHERE student_id = ? AND original_transaction_id = ?")
        .use { stmt ->
          stmt.setObject(1, caller.studentId)
          stmt.setString(2, otid)
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "the row is in the DB")
            assertEquals("active", rs.getString("status"))
            assertEquals(AppStoreTestFixtures.PRODUCT_ID, rs.getString("product_id"))
          }
        }
    }

  @Test
  fun `garbage signedTransaction answers 400 validation_failed`() =
    runBlocking {
      val caller = registerStudent()

      val response = postVerify(caller.cookie, signedTransaction = "not-a-jws")

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("validation_failed", response.errorCode())
      assertEquals(
        "signedTransaction",
        mapper
          .readTree(response.bodyAsText())
          .get("fieldErrors")
          .single()
          .get("field")
          .asText(),
      )
    }

  @Test
  fun `an Apple 404 answers 404 subscription_not_found`() =
    runBlocking {
      val caller = registerStudent()
      SwappableTransport.delegate = ScriptedAppStoreTransport.of(404, "{}")

      val response = postVerify(caller.cookie)

      assertEquals(HttpStatusCode.NotFound, response.status)
      assertEquals("subscription_not_found", response.errorCode())
    }

  @Test
  fun `a rebind attempt answers 409 subscription_owned_by_other_account`() =
    runBlocking {
      val owner = registerStudent()
      val interloper = registerStudent()
      val otid = uniqueOriginalTransactionId()
      val periodStart = Instant.now().minus(5, ChronoUnit.DAYS)
      val periodEnd = Instant.now().plus(25, ChronoUnit.DAYS)

      installActiveScript(otid, periodStart, periodEnd)
      assertEquals(HttpStatusCode.OK, postVerify(owner.cookie).status)

      installActiveScript(otid, periodStart, periodEnd)
      val response = postVerify(interloper.cookie)

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertEquals("subscription_owned_by_other_account", response.errorCode())
    }

  @Test
  fun `an Apple outage answers 503 service_unavailable`() =
    runBlocking {
      val caller = registerStudent()
      SwappableTransport.delegate = ScriptedAppStoreTransport.throwing(IOException("connection reset"))

      val response = postVerify(caller.cookie)

      assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
      assertEquals("service_unavailable", response.errorCode())
    }

  // ---------------------------------------------------------------------------
  // The two e2e flows
  // ---------------------------------------------------------------------------

  @Test
  fun `entitlement flip — an exhausted free-tier student is admitted after verify`() =
    runBlocking {
      val caller = registerStudent()
      val periodStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      val periodEnd = Instant.now().plus(25, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

      // Free-tier exhaustion, spent BEFORE the subscription window opens.
      seedSpend(caller.studentId, 1_000_000_000_000_000L, createdAt = periodStart.minus(30, ChronoUnit.DAYS))

      val blocked = startConvo(caller)
      assertEquals(HttpStatusCode.PaymentRequired, blocked.status)
      assertEquals("coaching_budget_exhausted", blocked.errorCode())

      installActiveScript(uniqueOriginalTransactionId(), periodStart, periodEnd)
      assertEquals(HttpStatusCode.OK, postVerify(caller.cookie).status)

      val admitted = startConvo(caller)
      assertEquals(HttpStatusCode.Created, admitted.status, "the subscribed branch admits the turn: ${admitted.bodyAsText()}")

      val meter = usage(caller)
      assertFalse(meter.get("exhausted").asBoolean())
      assertTrue(meter.get("resetsAt").isTextual, "the period meter carries its reset point")
      assertEquals(periodEnd, Instant.parse(meter.get("resetsAt").asText()))
    }

  @Test
  fun `rollover — a re-verify with a renewed window restores the exhausted subscriber`() =
    runBlocking {
      val caller = registerStudent()
      val otid = uniqueOriginalTransactionId()
      val firstStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      val firstEnd = Instant.now().plus(25, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

      installActiveScript(otid, firstStart, firstEnd)
      assertEquals(HttpStatusCode.OK, postVerify(caller.cookie).status)

      // Period spend past the plan budget → 402 on the subscription meter.
      seedSpend(caller.studentId, PERIOD_BUDGET * 2, createdAt = Instant.now().minus(1, ChronoUnit.DAYS))
      val blocked = startConvo(caller)
      assertEquals(HttpStatusCode.PaymentRequired, blocked.status)
      assertEquals("coaching_budget_exhausted", blocked.errorCode())

      // The renewal the app re-posts: the same subscription's window advances
      // past every earlier spend. No reset action anywhere — the windowed read
      // IS the rollover (overshoot forgiven, nothing carried forward).
      val renewedStart = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
      val renewedEnd = Instant.now().plus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      installActiveScript(otid, renewedStart, renewedEnd)
      assertEquals(HttpStatusCode.OK, postVerify(caller.cookie).status)

      val admitted = startConvo(caller)
      assertEquals(HttpStatusCode.Created, admitted.status, admitted.bodyAsText())

      val meter = usage(caller)
      assertEquals(0, meter.get("usedPercent").asInt(), "the new window starts at spent = 0 against the same budget")
      assertEquals(renewedEnd, Instant.parse(meter.get("resetsAt").asText()))
    }
}
