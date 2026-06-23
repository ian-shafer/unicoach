package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.extraction.ExtractionConfig
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 66 enqueue assertions: a successful coaching turn enqueues exactly one
 * EXTRACT_CONVERSATION job carrying {convoId, throughRequestId}; a failed turn
 * enqueues nothing; `extraction.enabled = false` enqueues nothing; and the
 * enqueue does not alter the turn's HTTP response.
 *
 * Two servers boot via appModule (the injection seam): one extraction-enabled,
 * one disabled. A volatile terminal lets the enabled server's fake provider swap
 * between a successful Completed and a TransientFailure per test.
 */
class ConvoExtractionEnqueueTest {
  companion object {
    private lateinit var enabledServer: EmbeddedServer<*, *>
    private lateinit var disabledServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var connection: Connection
    private var enabledPort: Int = 0
    private var disabledPort: Int = 0

    @Volatile
    private var terminal: ChatEvent.Terminal = completedTerminal()

    private fun completedTerminal(): ChatEvent.Terminal {
      val content =
        buildJsonArray {
          add(
            buildJsonObject {
              put("type", "text")
              put("text", "ok")
            },
          )
        }
      return ChatEvent.Completed(
        response =
          ChatResponse(
            content = content,
            modelResolved = "log",
            stopReason = "end_turn",
            usage = TokenUsage(1, 1, 0, 0),
            providerRequestId = "req",
          ),
        rawPayload = content,
      )
    }

    private val fakeProvider =
      object : ChatProvider {
        override val id: String = "log"

        override fun stream(request: ChatRequest): Flow<ChatEvent> =
          flow {
            emit(ChatEvent.ContentBlockStart(index = 0, blockType = "text", block = null))
            emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("ok")))
            emit(terminal)
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
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")

      val sessionConfig = SessionConfig.from(config).getOrThrow()
      val requestSizeConfig = RequestSizeConfig.from(config).getOrThrow()
      val coachingConfig = CoachingConfig.from(config).getOrThrow()
      val clientKeyGateConfig = ClientKeyGateConfig.from(config).getOrThrow()
      val queueService = ed.unicoach.queue.QueueService(database)
      val emailConfig =
        ed.unicoach.email.EmailConfig
          .from(config)
          .getOrThrow()
      val emailProvider =
        ed.unicoach.email.EmailProviderFactory
          .fromConfig(emailConfig)
          .getOrThrow()
      val emailService = ed.unicoach.email.EmailService(database, emailProvider, emailConfig)
      val emailVerificationConfig =
        ed.unicoach.auth.EmailVerificationConfig
          .from(config)
          .getOrThrow()
      val googleTokenVerifier = ed.unicoach.auth.StubGoogleTokenVerifier()

      val enabledConfig = ExtractionConfig.from(config).getOrThrow()
      val disabledConfig =
        ExtractionConfig
          .from(
            com.typesafe.config.ConfigFactory
              .parseString("extraction.enabled = false")
              .withFallback(config),
          ).getOrThrow()

      enabledServer =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            fakeProvider,
            coachingConfig,
            clientKeyGateConfig,
            emailService,
            emailVerificationConfig,
            googleTokenVerifier,
            queueService,
            enabledConfig,
          )
        }
      enabledServer.start(wait = false)
      enabledPort =
        runBlocking {
          enabledServer.engine
            .resolvedConnectors()
            .first()
            .port
        }

      disabledServer =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            fakeProvider,
            coachingConfig,
            clientKeyGateConfig,
            emailService,
            emailVerificationConfig,
            googleTokenVerifier,
            queueService,
            disabledConfig,
          )
        }
      disabledServer.start(wait = false)
      disabledPort =
        runBlocking {
          disabledServer.engine
            .resolvedConnectors()
            .first()
            .port
        }

      client = HttpClient(CIO)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::enabledServer.isInitialized) enabledServer.stop(1000, 5000)
      if (::disabledServer.isInitialized) disabledServer.stop(1000, 5000)
      if (::client.isInitialized) client.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun url(
    port: Int,
    path: String,
  ) = "http://localhost:$port$path"

  /** Marks a registered user verified by direct SQL so it passes the verification gate. */
  private fun markEmailVerified(email: String) {
    connection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private suspend fun registerWithStudent(port: Int): String {
    val email = "enq${java.util.UUID.randomUUID()}@company.com"
    val req = RegisterRequest(email, "Password123!", "Enq User")
    val reg =
      client.post(url(port, "/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    assertEquals(HttpStatusCode.Created, reg.status)
    markEmailVerified(email)
    val cookie =
      reg.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    val sr =
      client.post(url(port, "/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }
    assertEquals(HttpStatusCode.Created, sr.status)
    return cookie
  }

  private suspend fun createConvo(
    port: Int,
    cookie: String,
    message: String = "I want help with colleges",
  ): io.ktor.client.statement.HttpResponse =
    client.post(url(port, "/api/v1/conversations")) {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateConversationRequest(message, null)))
    }

  private fun extractionJobs(convoId: String): List<Pair<String, Long>> {
    val out = mutableListOf<Pair<String, Long>>()
    connection
      .prepareStatement(
        "SELECT payload FROM jobs WHERE job_type = 'EXTRACT_CONVERSATION' AND payload->>'convoId' = ?",
      ).use { stmt ->
        stmt.setString(1, convoId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            val payload = mapper.readTree(rs.getString("payload"))
            out.add(payload["convoId"].asText() to payload["throughRequestId"].asLong())
          }
        }
      }
    return out
  }

  // ---------------------------------------------------------------------------

  @Test
  fun `successful turn enqueues exactly one job with matching convoId and throughRequestId`() =
    runBlocking {
      terminal = completedTerminal()
      val cookie = registerWithStudent(enabledPort)
      val resp = createConvo(enabledPort, cookie)
      assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
      val body: JsonNode = mapper.readTree(resp.bodyAsText())
      val convoId = body["conversation"]["id"].asText()
      // userMessage id is "u_<requestId>".
      val expectedThrough = body["userMessage"]["id"].asText().removePrefix("u_").toLong()

      val jobs = extractionJobs(convoId)
      assertEquals(1, jobs.size, "expected exactly one extraction job")
      assertEquals(convoId, jobs[0].first)
      assertEquals(expectedThrough, jobs[0].second)
    }

  @Test
  fun `successful turn response is unchanged by the enqueue`() =
    runBlocking {
      terminal = completedTerminal()
      val cookie = registerWithStudent(enabledPort)
      val resp = createConvo(enabledPort, cookie)
      assertEquals(HttpStatusCode.Created, resp.status)
      val body = mapper.readTree(resp.bodyAsText())
      assertTrue(body["userMessage"]["id"].asText().startsWith("u_"))
      assertTrue(body["coachMessage"]["id"].asText().startsWith("c_"))
    }

  @Test
  fun `failed turn enqueues no new job for the conversation`() =
    runBlocking {
      // First turn succeeds → exactly one job for this convo.
      terminal = completedTerminal()
      val cookie = registerWithStudent(enabledPort)
      val created = mapper.readTree(createConvo(enabledPort, cookie).bodyAsText())
      val convoId = created["conversation"]["id"].asText()
      assertEquals(1, extractionJobs(convoId).size)

      // Second turn fails → no additional job enqueued for this convo.
      terminal = ChatEvent.TransientFailure("down", "r", null)
      val postResp =
        client.post(url(enabledPort, "/api/v1/conversations/$convoId/messages")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(PostMessageRequest("this will fail")))
        }
      assertEquals(HttpStatusCode.InternalServerError, postResp.status)
      assertEquals(1, extractionJobs(convoId).size, "a failed turn must not enqueue a new job")

      terminal = completedTerminal()
    }

  @Test
  fun `extraction disabled enqueues nothing`() =
    runBlocking {
      terminal = completedTerminal()
      val cookie = registerWithStudent(disabledPort)
      val resp = createConvo(disabledPort, cookie)
      assertEquals(HttpStatusCode.Created, resp.status)
      val convoId = mapper.readTree(resp.bodyAsText())["conversation"]["id"].asText()
      assertEquals(0, extractionJobs(convoId).size, "disabled extraction must enqueue nothing")
    }

  @Test
  fun `subsequent message turn enqueues a job through the new user turn`() =
    runBlocking {
      terminal = completedTerminal()
      val cookie = registerWithStudent(enabledPort)
      val created = mapper.readTree(createConvo(enabledPort, cookie).bodyAsText())
      val convoId = created["conversation"]["id"].asText()

      val postResp =
        client.post(url(enabledPort, "/api/v1/conversations/$convoId/messages")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(PostMessageRequest("another message")))
        }
      assertEquals(HttpStatusCode.Created, postResp.status, postResp.bodyAsText())
      val postThrough =
        mapper
          .readTree(postResp.bodyAsText())["userMessage"]["id"]
          .asText()
          .removePrefix("u_")
          .toLong()

      val jobs = extractionJobs(convoId)
      assertEquals(2, jobs.size, "create + post should enqueue two jobs")
      assertTrue(jobs.any { it.second == postThrough }, "a job must target the post turn's request id")
    }
}
