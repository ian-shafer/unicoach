package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.CoachingConfig
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 94: end-to-end assertions over a real coaching turn that runs one tool
 * round. The appModule wires the real ToolRegistry (search_colleges over an
 * empty DB → count 0, a valid outcome), and a scripted fake provider returns
 * tool_use then a final text answer. Asserts the extraction enqueue targets the
 * CONTINUATION request id (not the user turn's) and the transcript endpoint
 * collapses the excursion to one user + one coach message.
 */
class ConvoToolLoopRoutingTest {
  companion object {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private lateinit var connection: Connection
    private var port: Int = 0

    // A fresh call counter per stream() collection is not enough (the loop makes
    // two collections), so track across calls per-conversation via a thread-safe
    // counter. The first collection returns tool_use; every later one end_turn.
    private val callCount =
      java.util.concurrent.atomic
        .AtomicInteger(0)

    private fun toolUseTerminal(): ChatEvent.Terminal {
      val content = Json.parseToJsonElement("""[{"type":"tool_use","id":"toolu_0","name":"search_colleges","input":{"cipPrefix":"26"}}]""")
      return ChatEvent.Completed(
        response =
          ChatResponse(
            content = content,
            modelResolved = "log",
            stopReason = "tool_use",
            usage = TokenUsage(1, 1, 0, 0),
            providerRequestId = "req_tool",
          ),
        rawPayload = content,
      )
    }

    private fun finalTerminal(): ChatEvent.Terminal {
      val content = Json.parseToJsonElement("""[{"type":"text","text":"no matches yet"}]""")
      return ChatEvent.Completed(
        response =
          ChatResponse(
            content = content,
            modelResolved = "log",
            stopReason = "end_turn",
            usage = TokenUsage(1, 1, 0, 0),
            providerRequestId = "req_final",
          ),
        rawPayload = content,
      )
    }

    private val fakeProvider =
      object : ChatProvider {
        override val id: String = "log"

        override fun stream(request: ChatRequest): Flow<ChatEvent> =
          flow {
            val n = callCount.getAndIncrement()
            emit(if (n == 0) toolUseTerminal() else finalTerminal())
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
      val requestLoggingConfig = RequestLoggingConfig.from(config).getOrThrow()
      val queueService = ed.unicoach.queue.QueueService(database)
      val emailVerificationConfig =
        ed.unicoach.auth.EmailVerificationConfig
          .from(config)
          .getOrThrow()
      val googleTokenVerifier = ed.unicoach.auth.StubGoogleTokenVerifier()
      val extractionConfig = ExtractionConfig.from(config).getOrThrow()

      server =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            ed.unicoach.coaching.LlmCallLog(fakeProvider, database),
            coachingConfig,
            clientKeyGateConfig,
            emailVerificationConfig,
            googleTokenVerifier,
            queueService,
            extractionConfig,
            requestLoggingConfig,
            BudgetConfig.from(config).getOrThrow(),
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
    val email = "tool${java.util.UUID.randomUUID()}@company.com"
    val reg =
      client.post(url("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(RegisterRequest(email, "Password123!", "Tool User")))
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

  private fun extractionThroughIds(convoId: String): List<Long> {
    val out = mutableListOf<Long>()
    connection
      .prepareStatement("SELECT payload FROM jobs WHERE job_type = 'EXTRACT_CONVERSATION' AND payload->>'convoId' = ?")
      .use { stmt ->
        stmt.setString(1, convoId)
        stmt.executeQuery().use { rs ->
          while (rs.next()) out.add(mapper.readTree(rs.getString("payload"))["throughRequestId"].asLong())
        }
      }
    return out
  }

  @Test
  fun `a tool-round turn enqueues extraction with the continuation request id and collapses the transcript`() =
    runBlocking {
      callCount.set(0)
      val cookie = registerWithStudent()
      val created =
        client.post(url("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("biology colleges?", null)))
        }
      assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
      val body: JsonNode = mapper.readTree(created.bodyAsText())
      val convoId = body["conversation"]["id"].asText()
      val userRequestId = body["userMessage"]["id"].asText().removePrefix("u_").toLong()

      // The enqueue targets the continuation request id: strictly greater than the user turn's.
      val through = extractionThroughIds(convoId)
      assertEquals(1, through.size, "expected exactly one extraction job")
      assertTrue(
        through.single() > userRequestId,
        "enqueue must target the continuation request id, got ${through.single()} vs user $userRequestId",
      )

      // The transcript endpoint collapses the excursion to one user + one coach message.
      val messages =
        client.get(url("/api/v1/conversations/$convoId/messages")) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.OK, messages.status)
      val list = mapper.readTree(messages.bodyAsText())["messages"]
      assertEquals(2, list.size(), "expected exactly user + final coach, got ${messages.bodyAsText()}")
      assertEquals("user", list[0]["role"].asText())
      assertEquals("biology colleges?", list[0]["content"].asText())
      assertEquals("coach", list[1]["role"].asText())
      assertEquals("no matches yet", list[1]["content"].asText())
    }
}
