package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.AppStoreTransport
import ed.unicoach.appstore.AppStoreTransportResponse
import ed.unicoach.appstore.AppleNotificationVerifier
import ed.unicoach.appstore.ScriptedAppStoreTransport
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
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.queue.JobType
import ed.unicoach.queue.NewJob
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.dao.JobInsertResult
import ed.unicoach.queue.dao.JobsDao
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.AppleNotificationRequest
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.SubscriptionVerifyRequest
import ed.unicoach.rest.plugins.CLIENT_KEY_HEADER
import ed.unicoach.rest.routing.AppleNotificationRouteHandler
import ed.unicoach.subscriptions.SubscriptionRefreshHandler
import ed.unicoach.subscriptions.SubscriptionService
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins `POST /api/v1/subscriptions/apple-notifications` (RFC 112) against a real
 * server: which deliveries are accepted, which are refused and how, and — the
 * RFC's whole reason for existing — that a renewal now reaches a paying
 * subscriber's row without the app posting anything.
 *
 * The server's verifier is pinned to a locally minted root this suite holds the
 * signing key for, because no test can produce a certificate Apple issued. What
 * that leaves unproven is the marker OID against a real Apple leaf, which RFC
 * 112 gates on one real sandbox notification before the production URL is
 * entered in App Store Connect.
 */
class AppleNotificationRoutingTest {
  companion object {
    private lateinit var openServer: EmbeddedServer<*, *>
    private lateinit var gatedServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var dbConnection: java.sql.Connection
    private lateinit var database: Database
    private var openPort: Int = 0
    private var gatedPort: Int = 0

    /** The chain the suite signs every notification with; the server is pinned to its root. */
    private val chain = AppStoreTestFixtures.certificateChain()

    /** The key the client-key-gated server accepts, so a sibling path's 403 is provable. */
    private const val CLIENT_KEY = "a-configured-client-key"

    /**
     * The monthly10 plan's period budget, read from the very [subscriptionPlans]
     * the servers are wired with, so a `service.conf` price or ratio change moves
     * this suite's exhaustion threshold with it instead of silently diverging.
     * Lazy because [subscriptionPlans] is built in [setupAll].
     */
    private val periodBudget: Long by lazy {
      checkNotNull(subscriptionPlans.periodBudget(AppStoreTestFixtures.PRODUCT_ID)) {
        "no configured plan for [${AppStoreTestFixtures.PRODUCT_ID}] in subscriptions.plans"
      }.value
    }

    /** Unique Apple keys across the suite, so bindings never collide. */
    private val OTID_COUNTER = AtomicLong(940_000_000_000L)

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

    /**
     * A [JobsDao] whose inserts can be failed mid-suite, so the enqueue-failure
     * arm is reachable without booting a server of its own.
     */
    private object SwitchableJobsDao : JobsDao() {
      @Volatile
      var failInserts = false

      override fun insert(
        session: SqlSession,
        newJob: NewJob,
      ): JobInsertResult =
        if (failInserts) {
          JobInsertResult.DatabaseFailure(RuntimeException("injected enqueue failure"))
        } else {
          super.insert(session, newJob)
        }
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

    private lateinit var appStoreServerApi: AppStoreServerApi
    private lateinit var notificationVerifier: AppleNotificationVerifier
    private lateinit var subscriptionPlans: ed.unicoach.subscriptions.SubscriptionPlans

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      dbConnection = java.sql.DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

      appStoreServerApi = AppStoreServerApi(SwappableTransport, AppStoreTestFixtures.authTokens())
      notificationVerifier = testAppleNotificationVerifier(chain.root)
      subscriptionPlans = subscriptionPlansFrom(config)

      val shippedGate = ClientKeyGateConfig.from(config).getOrThrow()
      openServer = boot(config, shippedGate)
      // The gate is inert in dev, where `keys` is empty — so the allowlist entry
      // is only observable against a server whose keys are configured.
      gatedServer = boot(config, shippedGate.copy(validKeys = setOf(CLIENT_KEY)))

      openPort = portOf(openServer)
      gatedPort = portOf(gatedServer)
      client = HttpClient(CIO)
    }

    private fun boot(
      config: com.typesafe.config.Config,
      clientKeyGateConfig: ClientKeyGateConfig,
    ): EmbeddedServer<*, *> {
      val server =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            SessionConfig.from(config).getOrThrow(),
            RequestSizeConfig.from(config).getOrThrow(),
            ed.unicoach.coaching.LlmCallLog(fakeProvider, database),
            CoachingConfig.from(config).getOrThrow(),
            clientKeyGateConfig,
            ed.unicoach.auth.EmailVerificationConfig
              .from(config)
              .getOrThrow(),
            GoogleIdTokenVerifier(StubIdTokenVerifier()),
            AppleIdTokenVerifier(StubIdTokenVerifier()),
            QueueService(database, SwitchableJobsDao),
            ed.unicoach.coaching.extraction.ExtractionConfig
              .from(config)
              .getOrThrow(),
            RequestLoggingConfig.from(config).getOrThrow(),
            BudgetConfig.from(config).getOrThrow(),
            appStoreServerApi,
            subscriptionPlans,
            notificationVerifier,
          )
        }
      server.start(wait = false)
      return server
    }

    private fun portOf(server: EmbeddedServer<*, *>): Int =
      runBlocking {
        server.engine
          .resolvedConnectors()
          .first()
          .port
      }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::openServer.isInitialized) openServer.stop(1000, 5000)
      if (::gatedServer.isInitialized) gatedServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::dbConnection.isInitialized && !dbConnection.isClosed) dbConnection.close()
      if (::database.isInitialized) database.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun url(path: String) = "http://localhost:$openPort$path"

  @BeforeEach
  fun resetJobsAndEnqueue() {
    SwitchableJobsDao.failInserts = false
    dbConnection.createStatement().use { it.execute("DELETE FROM jobs WHERE job_type = 'REFRESH_SUBSCRIPTION'") }
  }

  // ---------------------------------------------------------------------------
  // Requests
  // ---------------------------------------------------------------------------

  private suspend fun postNotification(
    signedPayload: String,
    port: Int = openPort,
    clientKey: String? = null,
  ): HttpResponse =
    client.post("http://localhost:$port${AppleNotificationRouteHandler.PATH}") {
      contentType(ContentType.Application.Json)
      if (clientKey != null) header(CLIENT_KEY_HEADER, clientKey)
      setBody(mapper.writeValueAsString(AppleNotificationRequest(signedPayload)))
    }

  private suspend fun HttpResponse.errorCode(): String = mapper.readTree(bodyAsText()).get("code").asText()

  private fun uniqueOriginalTransactionId(): String = OTID_COUNTER.incrementAndGet().toString()

  /** A notification for [originalTransactionId], signed by [signingChain]. */
  private fun notificationFor(
    originalTransactionId: String,
    notificationType: String = "DID_RENEW",
    subtype: String? = "BILLING_RECOVERY",
    signingChain: AppStoreTestFixtures.TestCertificateChain = chain,
  ): String =
    AppStoreTestFixtures.signedNotification(
      signingChain,
      notificationType = notificationType,
      subtype = subtype,
      signedTransactionInfo = signingChain.sign(AppStoreTestFixtures.transactionClaims(originalTransactionId = originalTransactionId)),
    )

  // ---------------------------------------------------------------------------
  // Enqueued jobs
  // ---------------------------------------------------------------------------

  private fun refreshJobPayloads(): List<JsonObject> =
    dbConnection
      .prepareStatement("SELECT payload FROM jobs WHERE job_type = 'REFRESH_SUBSCRIPTION' ORDER BY created_at")
      .use { stmt ->
        stmt.executeQuery().use { rs ->
          buildList {
            while (rs.next()) add(Json.parseToJsonElement(rs.getString("payload")) as JsonObject)
          }
        }
      }

  private fun JsonObject.text(field: String): String? = this[field]?.jsonPrimitive?.content

  // ---------------------------------------------------------------------------
  // Accepted
  // ---------------------------------------------------------------------------

  @Test
  fun `an Apple-signed notification is accepted with no session cookie and enqueues one refresh`() {
    runBlocking {
      val otid = uniqueOriginalTransactionId()

      val response = postNotification(notificationFor(otid))

      assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
      assertEquals("", response.bodyAsText(), "Apple reads only the status code")
      val payloads = refreshJobPayloads()
      assertEquals(1, payloads.size, "exactly one refresh job")
      assertEquals(otid, payloads.single().text("originalTransactionId"))
      assertEquals("DID_RENEW", payloads.single().text("notificationType"))
      assertEquals("BILLING_RECOVERY", payloads.single().text("subtype"))
      assertTrue(payloads.single().text("notificationUuid")!!.isNotBlank(), "the delivery id rides along as log context")
    }
  }

  @Test
  fun `a TEST notification is accepted and enqueues nothing`() {
    runBlocking {
      val response =
        postNotification(
          AppStoreTestFixtures.signedNotification(chain, notificationType = "TEST", subtype = null, signedTransactionInfo = null),
        )

      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(0, refreshJobPayloads().size, "nothing to refresh")
    }
  }

  // ---------------------------------------------------------------------------
  // Refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a notification signed by a foreign root is 401 and enqueues nothing`() {
    runBlocking {
      val foreign = AppStoreTestFixtures.certificateChain()

      val response = postNotification(notificationFor(uniqueOriginalTransactionId(), signingChain = foreign))

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("unauthorized", response.errorCode())
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `a garbage signedPayload is 401 and enqueues nothing`() {
    runBlocking {
      val response = postNotification("not-a-jws-at-all")

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("unauthorized", response.errorCode())
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `a notification for another bundle is 400 and enqueues nothing`() {
    runBlocking {
      val response = postNotification(AppStoreTestFixtures.signedNotification(chain, bundleId = "com.someone.else"))

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("validation_failed", response.errorCode())
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `a Sandbox notification against this production box is 400 and enqueues nothing`() {
    runBlocking {
      val response =
        postNotification(AppStoreTestFixtures.signedNotification(chain, environment = AppStoreTestFixtures.SANDBOX_ENVIRONMENT))

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertEquals("validation_failed", response.errorCode())
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `an Apple-signed payload in an unseen shape is 500 and enqueues nothing`() {
    runBlocking {
      // A shape Apple has never sent must reach an operator, not be guessed at.
      val response = postNotification(AppStoreTestFixtures.signedNotification(chain, notificationUuid = null))

      assertEquals(HttpStatusCode.InternalServerError, response.status)
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `a failed enqueue is 500, so Apple retries, and no job row exists`() {
    runBlocking {
      SwitchableJobsDao.failInserts = true

      val response = postNotification(notificationFor(uniqueOriginalTransactionId()))

      assertEquals(HttpStatusCode.InternalServerError, response.status)
      assertEquals("internal_error", response.errorCode())
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `a non-POST method is 405 with Allow POST`() {
    runBlocking {
      val response = client.get(url(AppleNotificationRouteHandler.PATH))

      assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
      assertEquals("POST", response.headers[HttpHeaders.Allow])
    }
  }

  // ---------------------------------------------------------------------------
  // The two gates the endpoint has to clear
  // ---------------------------------------------------------------------------

  @Test
  fun `a body over the route override is 413 before any verification`() {
    runBlocking {
      // 60 KiB: past the 48 KiB override, so the body never reaches the service.
      val response = postNotification("a".repeat(60 * 1024))

      assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
      assertEquals(0, refreshJobPayloads().size)
    }
  }

  @Test
  fun `a body between MAX_JWS and the route override reaches the service and is refused legibly`() {
    runBlocking {
      // The headroom is live: this is refused as unauthenticated by the
      // verifier's own bound, not as a bare 413 that says nothing about why.
      val oversizedJws = "a".repeat((AppleNotificationVerifier.MAX_JWS.bytes + 1024).toInt())

      val response = postNotification(oversizedJws)

      assertEquals(HttpStatusCode.Unauthorized, response.status)
      assertEquals("unauthorized", response.errorCode())
    }
  }

  @Test
  fun `with client keys configured, this path answers without the header while a sibling path is 403`() {
    runBlocking {
      // Apple sends no X-Unicoach-Client-Key. Without the allowlist entry the
      // endpoint is unreachable in every environment where the gate is armed,
      // and no other test would notice.
      val sibling = client.get("http://localhost:$gatedPort/api/v1/auth/me")
      assertEquals(HttpStatusCode.Forbidden, sibling.status, "the gate is armed on this server")

      val allowlisted = postNotification(notificationFor(uniqueOriginalTransactionId()), port = gatedPort)

      assertEquals(HttpStatusCode.OK, allowlisted.status, allowlisted.bodyAsText())
      assertEquals(1, refreshJobPayloads().size, "the request reached the handler, not the gate")
    }
  }

  // ---------------------------------------------------------------------------
  // The staleness gap this RFC exists to close
  // ---------------------------------------------------------------------------

  @Test
  fun `a renewal notification restores an exhausted subscriber without any verify call`() {
    runBlocking {
      val caller = registerStudent()
      val otid = uniqueOriginalTransactionId()
      val firstStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      val firstEnd = Instant.now().plus(25, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

      // Purchase time: the only /verify in this test, and it binds the row.
      installActiveScript(otid, firstStart, firstEnd)
      assertEquals(HttpStatusCode.OK, postVerify(caller.cookie).status)

      // Period spend past the plan budget → the subscriber is refused a turn.
      seedSpend(caller.studentId, periodBudget * 2, createdAt = Instant.now().minus(1, ChronoUnit.DAYS))
      val blocked = startConvo(caller)
      assertEquals(HttpStatusCode.PaymentRequired, blocked.status)
      assertEquals("coaching_budget_exhausted", blocked.errorCode())

      // The renewal arrives from Apple, not from the app.
      val renewedStart = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
      val renewedEnd = Instant.now().plus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
      assertEquals(HttpStatusCode.OK, postNotification(notificationFor(otid)).status)

      // The worker's polling loop is QueueWorkerTest's subject, not this one's:
      // what matters here is that the job the route actually enqueued carries
      // enough for the handler to close the gap.
      installActiveScript(otid, renewedStart, renewedEnd)
      val enqueued = refreshJobPayloads().single()
      assertEquals(otid, enqueued.text("originalTransactionId"))
      val result =
        SubscriptionRefreshHandler(SubscriptionService(database, appStoreServerApi, subscriptionPlans)).execute(enqueued)
      assertEquals(ed.unicoach.queue.JobResult.Success, result)

      val admitted = startConvo(caller)
      assertEquals(HttpStatusCode.Created, admitted.status, admitted.bodyAsText())

      val meter = usage(caller)
      assertEquals(0, meter.get("usedPercent").asInt(), "the new window starts at spent = 0 against the same budget")
      assertEquals(renewedEnd, Instant.parse(meter.get("resetsAt").asText()))
    }
  }

  // ---------------------------------------------------------------------------
  // Callers and seeding (SubscriptionRoutingTest's helpers)
  // ---------------------------------------------------------------------------

  private class Caller(
    val cookie: String,
    val studentId: UUID,
  )

  private suspend fun registerStudent(): Caller {
    val email = "notif${UUID.randomUUID()}@company.com"
    val registration =
      client.post(url("/api/v1/auth/register")) {
        contentType(ContentType.Application.Json)
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Notif User")))
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
    client.post(url("/api/v1/students")) {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
    }
    return Caller(cookie, studentIdOf(email))
  }

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

  private fun installActiveScript(
    originalTransactionId: String,
    periodStart: Instant,
    periodEnd: Instant,
  ) {
    SwappableTransport.delegate =
      ScriptedAppStoreTransport.of(
        200,
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = 1,
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

  private suspend fun postVerify(cookie: String): HttpResponse =
    client.post(url("/api/v1/subscriptions/verify")) {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(SubscriptionVerifyRequest(AppStoreTestFixtures.signedTransaction())))
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
        stmt.setString(2, "notif-${UUID.randomUUID()}")
        stmt.executeUpdate()
      }
    return id
  }

  @Test
  fun `an unbound notification is accepted and its refresh binds nothing`() {
    runBlocking {
      // The normal state for a purchase whose app crashed before posting.
      val otid = uniqueOriginalTransactionId()
      assertEquals(HttpStatusCode.OK, postNotification(notificationFor(otid)).status)

      val result =
        SubscriptionRefreshHandler(SubscriptionService(database, appStoreServerApi, subscriptionPlans))
          .execute(refreshJobPayloads().single())

      assertEquals(ed.unicoach.queue.JobResult.Success, result)
      assertNull(
        dbConnection
          .prepareStatement("SELECT id FROM subscriptions WHERE original_transaction_id = ?")
          .use { stmt ->
            stmt.setString(1, otid)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("id") else null }
          },
        "the webhook refreshes but never binds",
      )
    }
  }
}
