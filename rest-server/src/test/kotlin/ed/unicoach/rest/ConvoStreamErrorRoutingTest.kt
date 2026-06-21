package ed.unicoach.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ContentDelta
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.auth.SessionConfig
import ed.unicoach.rest.config.ClientKeyGateConfig
import ed.unicoach.rest.config.RequestSizeConfig
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateStudentRequest
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots its own embeddedServer calling appModule(...) directly with an injected
 * fake ChatProvider — the parameterized module is the injection seam, since
 * startServer cannot fake the provider. A volatile script lets each test swap
 * the terminal/deltas the fake emits.
 */
class ConvoStreamErrorRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0

    @Volatile
    private var deltas: List<String> = emptyList()

    @Volatile
    private var terminal: ChatEvent.Terminal = ChatEvent.TransientFailure("t", "r", null)

    private val fakeProvider =
      object : ChatProvider {
        override val id: String = "log"

        override fun stream(request: ChatRequest): Flow<ChatEvent> =
          flow {
            emit(ChatEvent.ContentBlockStart(index = 0, blockType = "text", block = null))
            for (d in deltas) emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text(d)))
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
      val database = Database(DatabaseConfig.from(config).getOrThrow())
      val sessionConfig = SessionConfig.from(config).getOrThrow()
      val requestSizeConfig = RequestSizeConfig.from(config).getOrThrow()
      val coachingConfig = CoachingConfig.from(config).getOrThrow()
      val clientKeyGateConfig = ClientKeyGateConfig.from(config).getOrThrow()
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

      testServer =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
          environment.monitor.subscribe(ApplicationStopped) { database.close() }
          appModule(
            database,
            sessionConfig,
            requestSizeConfig,
            fakeProvider,
            coachingConfig,
            clientKeyGateConfig,
            emailService,
            emailVerificationConfig,
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
    }
  }

  private val mapper = jacksonObjectMapper()

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private suspend fun registerWithStudent(): String {
    val req = RegisterRequest("err${java.util.UUID.randomUUID()}@company.com", "Password123!", "Err User")
    val reg =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    val cookie =
      reg.headers[HttpHeaders.SetCookie]!!
        .split(";")
        .first()
        .trim()
    client.post(buildUrl("/api/v1/students")) {
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      header(HttpHeaders.Cookie, cookie)
      setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
    }
    return cookie
  }

  private fun parseSse(raw: String): List<Pair<String, String>> {
    val frames = mutableListOf<Pair<String, String>>()
    for (block in raw.split("\n\n")) {
      var event: String? = null
      val data = StringBuilder()
      for (line in block.split("\n")) {
        when {
          line.startsWith("event: ") -> event = line.removePrefix("event: ").trim()
          line.startsWith("data: ") -> data.append(line.removePrefix("data: "))
        }
      }
      if (event != null) frames.add(event to data.toString())
    }
    return frames
  }

  @Test
  fun `in-stream transient failure`() =
    runBlocking {
      val cookie = registerWithStudent()
      deltas = listOf("partial ")
      terminal = ChatEvent.TransientFailure("timeout", "r1", null)

      val response =
        client.post(buildUrl("/api/v1/conversations/stream")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val frames = parseSse(response.bodyAsText())
      assertEquals("conversation", frames[0].first)
      assertTrue(frames.any { it.first == "delta" })
      val last = frames.last()
      assertEquals("error", last.first)
      assertTrue(mapper.readTree(last.second)["error"]["code"].asText() == "coach_unavailable")
    }

  @Test
  fun `in-stream permanent failure`() =
    runBlocking {
      val cookie = registerWithStudent()
      deltas = listOf("partial ")
      terminal = ChatEvent.Rejected("nope", "r2", null)

      val response =
        client.post(buildUrl("/api/v1/conversations/stream")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
        }
      assertEquals(HttpStatusCode.OK, response.status)
      val frames = parseSse(response.bodyAsText())
      assertEquals("error", frames.last().first)
      assertEquals("coach_failed", mapper.readTree(frames.last().second)["error"]["code"].asText())
    }

  @Test
  fun `buffered permanent failure`() =
    runBlocking {
      val cookie = registerWithStudent()
      deltas = emptyList()
      terminal = ChatEvent.Rejected("nope", "r3", null)

      val response =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
        }
      assertEquals(HttpStatusCode.InternalServerError, response.status)
      assertTrue(response.bodyAsText().contains("coach_failed"))
    }

  @Test
  fun `buffered transient failure`() =
    runBlocking {
      val cookie = registerWithStudent()
      deltas = emptyList()
      terminal = ChatEvent.TransientFailure("timeout", "r4", null)

      val response =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
        }
      assertEquals(HttpStatusCode.InternalServerError, response.status)
      assertTrue(response.bodyAsText().contains("coach_unavailable"))
    }
}
