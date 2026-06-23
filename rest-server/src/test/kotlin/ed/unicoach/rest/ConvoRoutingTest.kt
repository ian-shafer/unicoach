package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateStudentRequest
import ed.unicoach.rest.models.PostMessageRequest
import ed.unicoach.rest.models.RegisterRequest
import ed.unicoach.rest.models.UpdateConversationRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConvoRoutingTest {
  companion object {
    private lateinit var testServer: EmbeddedServer<*, *>
    private lateinit var client: HttpClient
    private var boundPort: Int = 0
    private lateinit var dbConnection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      testServer = startServer(wait = false, port = 0)
      boundPort =
        runBlocking {
          testServer.engine
            .resolvedConnectors()
            .first()
            .port
        }
      client = HttpClient(CIO)

      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      dbConnection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
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

  private fun buildUrl(path: String) = "http://localhost:$boundPort$path"

  private fun uniqueEmail(): String = "convo${java.util.UUID.randomUUID()}@company.com"

  /** Marks a registered user verified by direct SQL so it passes the verification gate. */
  private fun markEmailVerified(email: String) {
    dbConnection
      .prepareStatement(
        "UPDATE users SET version = version + 1, email_verified_at = NOW() WHERE email = ? AND email_verified_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeUpdate()
      }
  }

  private suspend fun registerAndGetCookie(): String {
    val email = uniqueEmail()
    val req = RegisterRequest(email, "Password123!", "Convo User")
    val response =
      client.post(buildUrl("/api/v1/auth/register")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(mapper.writeValueAsString(req))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    markEmailVerified(email)
    return response.headers[HttpHeaders.SetCookie]!!
      .split(";")
      .first()
      .trim()
  }

  private suspend fun registerWithStudent(): String {
    val cookie = registerAndGetCookie()
    val response =
      client.post(buildUrl("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateStudentRequest("2028")))
      }
    assertEquals(HttpStatusCode.Created, response.status)
    return cookie
  }

  private suspend fun createConvo(
    cookie: String,
    message: String,
    name: String? = null,
  ): JsonNode {
    val response =
      client.post(buildUrl("/api/v1/conversations")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(CreateConversationRequest(message, name)))
      }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return mapper.readTree(response.bodyAsText())
  }

  // ===========================================================================

  @Test
  fun `full buffered lifecycle`() =
    runBlocking {
      val cookie = registerWithStudent()

      // create -> 201, derived name, coach echo, role-prefixed ids
      val created = createConvo(cookie, "I want help choosing colleges")
      val convoId = created["conversation"]["id"].asText()
      assertTrue(created["conversation"]["name"].asText().isNotBlank())
      assertTrue(created["userMessage"]["id"].asText().startsWith("u_"))
      assertTrue(created["coachMessage"]["id"].asText().startsWith("c_"))
      assertTrue(created["coachMessage"]["content"].asText().contains("echo"))
      assertEquals("user", created["userMessage"]["role"].asText())
      assertEquals("coach", created["coachMessage"]["role"].asText())

      // list -> one convo, lastActivityAt non-null
      val list = mapper.readTree(get("/api/v1/conversations", cookie).second)
      assertEquals(1, list["conversations"].size())
      assertNotNull(list["conversations"][0]["lastActivityAt"].asText())
      assertTrue(!list["conversations"][0]["lastActivityAt"].isNull)

      // get
      val (getStatus, getBody) = get("/api/v1/conversations/$convoId", cookie)
      assertEquals(HttpStatusCode.OK, getStatus)
      val createdAtUpdated = mapper.readTree(getBody)["conversation"]["updatedAt"].asText()

      // PATCH rename -> 200, updatedAt advanced
      val renamed =
        client.patch(buildUrl("/api/v1/conversations/$convoId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateConversationRequest(name = "Renamed Plan")))
        }
      assertEquals(HttpStatusCode.OK, renamed.status)
      val renamedNode = mapper.readTree(renamed.bodyAsText())
      assertEquals("Renamed Plan", renamedNode["conversation"]["name"].asText())
      assertTrue(renamedNode["conversation"]["updatedAt"].asText() >= createdAtUpdated)

      // PATCH archive -> 200, archivedAt set; default list empty; status=archived lists it
      val archived =
        client.patch(buildUrl("/api/v1/conversations/$convoId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(UpdateConversationRequest(archived = true)))
        }
      assertEquals(HttpStatusCode.OK, archived.status)
      assertTrue(!mapper.readTree(archived.bodyAsText())["conversation"]["archivedAt"].isNull)

      assertEquals(0, mapper.readTree(get("/api/v1/conversations", cookie).second)["conversations"].size())
      assertEquals(1, mapper.readTree(get("/api/v1/conversations?status=archived", cookie).second)["conversations"].size())

      // post message -> 201
      val posted =
        client.post(buildUrl("/api/v1/conversations/$convoId/messages")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(PostMessageRequest("second message")))
        }
      assertEquals(HttpStatusCode.Created, posted.status, posted.bodyAsText())

      // get messages -> chronological user/coach
      val messages = mapper.readTree(get("/api/v1/conversations/$convoId/messages", cookie).second)["messages"]
      assertEquals(4, messages.size())
      assertEquals("user", messages[0]["role"].asText())
      assertEquals("coach", messages[1]["role"].asText())
      assertEquals("user", messages[2]["role"].asText())
      assertEquals("coach", messages[3]["role"].asText())

      // DELETE -> 204; get -> 404
      val deleted = client.delete(buildUrl("/api/v1/conversations/$convoId")) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NoContent, deleted.status)
      assertEquals(HttpStatusCode.NotFound, get("/api/v1/conversations/$convoId", cookie).first)
    }

  @Test
  fun `401 unauthorized on every operation without a cookie`() =
    runBlocking {
      val randomId =
        java.util.UUID
          .randomUUID()
          .toString()
      val noCookie =
        listOf(
          suspend { client.get(buildUrl("/api/v1/conversations")).status },
          suspend {
            client
              .post(buildUrl("/api/v1/conversations")) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(mapper.writeValueAsString(CreateConversationRequest("x", null)))
              }.status
          },
          suspend { client.get(buildUrl("/api/v1/conversations/$randomId")).status },
          suspend { client.get(buildUrl("/api/v1/conversations/$randomId/messages")).status },
        )
      for (call in noCookie) {
        assertEquals(HttpStatusCode.Unauthorized, call())
      }
      // assert lowercase code present
      val body = client.get(buildUrl("/api/v1/conversations")).bodyAsText()
      assertTrue(body.contains("unauthorized"))
    }

  @Test
  fun `409 student_profile_required and 200 empty list and 404 for id ops with no profile`() =
    runBlocking {
      val cookie = registerAndGetCookie() // no student profile
      val create =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
        }
      assertEquals(HttpStatusCode.Conflict, create.status)
      assertTrue(create.bodyAsText().contains("student_profile_required"))

      val stream =
        client.post(buildUrl("/api/v1/conversations/stream")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("hello", null)))
        }
      assertEquals(HttpStatusCode.Conflict, stream.status)

      val (listStatus, listBody) = get("/api/v1/conversations", cookie)
      assertEquals(HttpStatusCode.OK, listStatus)
      assertEquals(0, mapper.readTree(listBody)["conversations"].size())

      val randomId =
        java.util.UUID
          .randomUUID()
          .toString()
      assertEquals(HttpStatusCode.NotFound, get("/api/v1/conversations/$randomId", cookie).first)
    }

  @Test
  fun `400 validation_failed for bad inputs`() =
    runBlocking {
      val cookie = registerWithStudent()

      // whitespace-only message
      val blank =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("   ", null)))
        }
      assertEquals(HttpStatusCode.BadRequest, blank.status)
      assertTrue(blank.bodyAsText().contains("validation_failed"))

      // 256-char name
      val longName =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("ok", "n".repeat(256))))
        }
      assertEquals(HttpStatusCode.BadRequest, longName.status)

      val convoId = createConvo(cookie, "valid start")["conversation"]["id"].asText()

      // PATCH {}
      val emptyPatch =
        client.patch(buildUrl("/api/v1/conversations/$convoId")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody("{}")
        }
      assertEquals(HttpStatusCode.BadRequest, emptyPatch.status)
      assertTrue(emptyPatch.bodyAsText().contains("validation_failed"))

      // ?status=bogus
      assertEquals(HttpStatusCode.BadRequest, get("/api/v1/conversations?status=bogus", cookie).first)
    }

  @Test
  fun `404 not_found identical for random, malformed, and foreign convo`() =
    runBlocking {
      val cookie = registerWithStudent()
      val otherCookie = registerWithStudent()
      val foreignId = createConvo(otherCookie, "not yours")["conversation"]["id"].asText()

      val random = get("/api/v1/conversations/${java.util.UUID.randomUUID()}", cookie)
      val malformed = get("/api/v1/conversations/not-a-uuid", cookie)
      val foreign = get("/api/v1/conversations/$foreignId", cookie)

      assertEquals(HttpStatusCode.NotFound, random.first)
      assertEquals(HttpStatusCode.NotFound, malformed.first)
      assertEquals(HttpStatusCode.NotFound, foreign.first)
      // identical body (no existence leak)
      assertEquals(random.second, malformed.second)
      assertEquals(random.second, foreign.second)
    }

  @Test
  fun `413 override on convo POST paths and default elsewhere`() =
    runBlocking {
      val cookie = registerWithStudent()

      // ~100 KiB message succeeds on the prefix-overridden path
      val big = "a".repeat(100_000)
      val ok =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest(big, null)))
        }
      assertEquals(HttpStatusCode.Created, ok.status, "100KiB message should be accepted under the 512KiB prefix override")

      // > 512 KiB body -> 413
      val tooBig = "a".repeat(600_000)
      val rejected =
        client.post(buildUrl("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest(tooBig, null)))
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, rejected.status)
      assertTrue(rejected.bodyAsText().contains("payload_too_large"))

      // an unrelated route still enforces 8 KiB (auth login)
      val loginBig =
        client.post(buildUrl("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody("x".repeat(9000))
        }
      assertEquals(HttpStatusCode.PayloadTooLarge, loginBig.status)
    }

  @Test
  fun `streamConversation SSE`() =
    runBlocking {
      val cookie = registerWithStudent()
      val (status, contentType, raw) = sse("/api/v1/conversations/stream", cookie, CreateConversationRequest("stream me", null))
      assertEquals(HttpStatusCode.OK, status)
      assertTrue(contentType!!.contains("text/event-stream"))

      val frames = parseSse(raw)
      assertEquals("conversation", frames.first().first)
      assertTrue(frames.any { it.first == "delta" })
      assertEquals("message", frames.last().first)

      val deltaConcat = frames.filter { it.first == "delta" }.joinToString("") { mapper.readTree(it.second)["text"].asText() }
      val terminalContent = mapper.readTree(frames.last().second)["message"]["content"].asText()
      assertEquals(terminalContent, deltaConcat)

      val convoId = mapper.readTree(frames.first().second)["conversation"]["id"].asText()
      val messages = mapper.readTree(get("/api/v1/conversations/$convoId/messages", cookie).second)["messages"]
      assertEquals(2, messages.size())
    }

  @Test
  fun `streamMessage SSE`() =
    runBlocking {
      val cookie = registerWithStudent()
      val convoId = createConvo(cookie, "seed for stream")["conversation"]["id"].asText()
      val (status, _, raw) = sse("/api/v1/conversations/$convoId/messages/stream", cookie, PostMessageRequest("stream turn"))
      assertEquals(HttpStatusCode.OK, status)
      val frames = parseSse(raw)
      assertEquals("user_message", frames.first().first)
      assertTrue(frames.none { it.first == "conversation" })
      assertEquals("message", frames.last().first)
    }

  @Test
  fun `405 with Allow on unsupported methods`() =
    runBlocking {
      val cookie = registerWithStudent()
      val convoId = createConvo(cookie, "for 405")["conversation"]["id"].asText()
      // DELETE on the collection root is unsupported
      val resp = client.delete(buildUrl("/api/v1/conversations")) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.MethodNotAllowed, resp.status)
      assertNotNull(resp.headers[HttpHeaders.Allow])
      // POST on the {id} item route is unsupported
      val resp2 = client.post(buildUrl("/api/v1/conversations/$convoId")) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.MethodNotAllowed, resp2.status)
    }

  // --- helpers ---

  private suspend fun get(
    path: String,
    cookie: String,
  ): Pair<HttpStatusCode, String> {
    val response = client.get(buildUrl(path)) { header(HttpHeaders.Cookie, cookie) }
    return response.status to response.bodyAsText()
  }

  private suspend fun sse(
    path: String,
    cookie: String,
    body: Any,
  ): Triple<HttpStatusCode, String?, String> {
    val response =
      client.post(buildUrl(path)) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, cookie)
        setBody(mapper.writeValueAsString(body))
      }
    return Triple(response.status, response.headers[HttpHeaders.ContentType], response.bodyAsText())
  }

  /** Parses raw SSE text into ordered (event, data) frames. */
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
}
