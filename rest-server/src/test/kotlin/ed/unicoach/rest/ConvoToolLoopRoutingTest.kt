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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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

    /**
     * The tool_use block the first provider call replays. One scripted provider
     * serves every case and only the block differs, so each case ARMS it through
     * [resetLoop] — `search_colleges` or `find_college` (RFC 154).
     *
     * Deliberately null until armed, with no default. JUnit guarantees no method
     * order, so a default would let a case that forgot to arm inherit whatever
     * the previous case installed and still pass — a green test asserting the
     * wrong tool. Unarmed is now a named failure instead.
     */
    private val toolUseBlock =
      java.util.concurrent.atomic
        .AtomicReference<String?>(null)

    /** Every ChatRequest the loop made, in order: the continuation is where the tool_result rides. */
    private val requests = java.util.Collections.synchronizedList(mutableListOf<ChatRequest>())

    /** The low 30 bits: keeps a random UUID's tail inside a POSITIVE `int` for `ipeds_unit_id`. */
    private const val IPEDS_UNIT_ID_MASK = 0x3FFFFFFFL

    private const val SEARCH_COLLEGES_TOOL_USE =
      """[{"type":"tool_use","id":"toolu_0","name":"search_colleges","input":{"cipPrefix":"26"}}]"""

    /**
     * The `find_college` block, BUILT rather than interpolated: this tool's
     * whole point is a fuzzy name, and a realistic one ("St. Mary's", a quoted
     * nickname) interpolated into a JSON literal produces invalid JSON that
     * fails at parse time, two layers away from the case that wrote it.
     */
    private fun findCollegeToolUse(name: String): String =
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "tool_use")
            put("id", "toolu_find_0")
            put("name", "find_college")
            putJsonObject("input") { put("name", name) }
          },
        )
      }.toString()

    private fun toolUseTerminal(): ChatEvent.Terminal {
      val content =
        Json.parseToJsonElement(
          requireNotNull(toolUseBlock.get()) { "arm the scripted provider with resetLoop(...) first" },
        )
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
            // Recorded, because the CONTINUATION request is the only place the
            // dispatched tool's own answer is observable from outside the loop.
            requests.add(request)
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
      val googleTokenVerifier = ed.unicoach.auth.GoogleIdTokenVerifier(ed.unicoach.auth.StubIdTokenVerifier())
      val appleTokenVerifier = ed.unicoach.auth.AppleIdTokenVerifier(ed.unicoach.auth.StubIdTokenVerifier())
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
            appleTokenVerifier,
            queueService,
            extractionConfig,
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

  private fun url(path: String) = "http://localhost:$port$path"

  /**
   * Arms the scripted provider for one turn: the first call replays [toolUse],
   * every later one ends the turn. The recorded requests are cleared with the
   * counter, so a case reads only its own turn.
   */
  private fun resetLoop(toolUse: String) {
    callCount.set(0)
    requests.clear()
    toolUseBlock.set(toolUse)
  }

  /**
   * A uniquely-named college this test alone matches, on the shared un-truncated
   * test DB, with both derived tables re-derived in the SAME transaction as the
   * row (the [CollegeSearchRoutingTest] rule): `college_name_words` for the
   * one-keystroke arm and `college_search_index` for the substring arm and the
   * index-built gate. Under autocommit the DELETE inside `rebuildNameWords`
   * would commit alone, and the embedded server is live on this database.
   */
  private fun seedCollege(name: String): java.util.UUID {
    val id = java.util.UUID.randomUUID()
    val uniqueIpedsUnitId = (id.leastSignificantBits and IPEDS_UNIT_ID_MASK).toInt()
    val session =
      object : ed.unicoach.db.dao.SqlSession {
        override fun prepareStatement(sql: String): java.sql.PreparedStatement = connection.prepareStatement(sql)
      }
    connection.autoCommit = false
    try {
      connection
        .prepareStatement(
          """
          INSERT INTO colleges (id, ipeds_unit_id, name, city, state, control, undergrad_enrollment_headcount)
          VALUES (?, ?, ?, 'Townsville', 'CA', 1, 5000)
          """.trimIndent(),
        ).use { stmt ->
          stmt.setObject(1, id)
          stmt.setInt(2, uniqueIpedsUnitId)
          stmt.setString(3, name)
          stmt.executeUpdate()
        }
      ed.unicoach.db.dao.CollegesDao
        .rebuildNameWords(session)
        .getOrThrow()
      ed.unicoach.db.dao.CollegesDao
        .rebuildSearchIndex(session)
        .getOrThrow()
      connection.commit()
    } catch (e: Throwable) {
      connection.rollback()
      throw e
    } finally {
      connection.autoCommit = true
    }
    return id
  }

  /**
   * The single `tool_result` block the loop sent back to the provider on
   * [request] — the continuation is the only place the dispatched tool's answer
   * is observable from outside the loop.
   */
  private fun toolResultBlock(request: ChatRequest): JsonObject =
    Json
      .parseToJsonElement(
        request.messages
          .last()
          .content
          .toString(),
      ).jsonArray
      .map { it.jsonObject }
      .single { it["type"]?.jsonPrimitive?.content == "tool_result" }

  /** The tool's own JSON answer, which rides inside [block] as a content STRING. */
  private fun toolAnswer(block: JsonObject): JsonObject =
    Json
      .parseToJsonElement(block["content"]!!.jsonPrimitive.content)
      .jsonObject

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
      resetLoop(SEARCH_COLLEGES_TOOL_USE)
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

  /**
   * RFC 154's acceptance criterion, at the chat level: the name goes in and a
   * `college_id` comes back, over the same fuzzy path the iOS picker uses. The
   * registry the appModule wires must SERVE `find_college` — an unknown tool
   * would come back as an `is_error` tool_result instead — and the answer must
   * carry the seeded college's id, so the coach has an id to hand
   * `update_college_list`.
   */
  @Test
  fun `a find_college tool round resolves a named school to its college_id`() =
    runBlocking {
      // An apostrophe on purpose: the tool_use block is BUILT, so a realistic
      // fuzzy name cannot break the JSON the provider replays.
      val name = "Keystone O'Fuzzy College ${java.util.UUID.randomUUID()}"
      val seededId = seedCollege(name)
      resetLoop(findCollegeToolUse(name))
      val cookie = registerWithStudent()

      val created =
        client.post(url("/api/v1/conversations")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, cookie)
          setBody(mapper.writeValueAsString(CreateConversationRequest("add $name to my list", null)))
        }
      assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())

      // Two provider calls: the tool_use opener and the continuation carrying
      // the tool's answer.
      assertEquals(2, requests.size, "the tool loop must make two provider calls")
      val block = toolResultBlock(requests[1])
      assertEquals(
        null,
        block["is_error"],
        "an is_error tool_result means the registry does not serve find_college: [$block]",
      )
      val result = toolAnswer(block)
      assertEquals(null, result["error"], "the lookup must answer, not refuse: [$result]")
      val matches = result["colleges"]!!.jsonArray.map { it.jsonObject }
      assertEquals(
        listOf(seededId.toString()),
        matches.map { it["college_id"]!!.jsonPrimitive.content },
        "the named school must resolve to the id every other college tool takes",
      )

      // And the registry advertises it on the turn, so the model can reach it at
      // all: the opener's tool list names find_college beside search_colleges.
      val advertised = requests[0].tools.map { it["name"]!!.jsonPrimitive.content }
      assertTrue(advertised.contains("find_college"), "the registry must advertise find_college, got [$advertised]")
      assertTrue(advertised.contains("search_colleges"), "beside the structured search it does not replace")
    }
}
