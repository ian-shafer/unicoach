package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.coaching.LlmPriceBook
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.rest.models.CreateConversationRequest
import ed.unicoach.rest.models.CreateSupporterConversationRequest
import ed.unicoach.rest.models.PostMessageRequest
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals

/**
 * The served stack for the parent-coach suites (RFC 258): the real `appModule`
 * over the real database, with the chat provider swapped for [ScriptedCoach] —
 * the transport seam the e2e suites use (RFC 107) — so a test decides what the
 * model answers, including which tools it calls, and every call is still logged
 * by the real `LlmCallLog` into `llm_requests`.
 *
 * Families are made through the doors ([FamilyRoutes.over]).
 */
class ParentCoachServer private constructor(
  val family: FamilyRoutes,
  val coach: ScriptedCoach,
  val db: Connection,
  private val client: HttpClient,
) : AutoCloseable {
  private val mapper = jacksonObjectMapper()

  /** One SSE response: its status, its content type, the raw body, and the parsed frames. */
  data class Streamed(
    val status: HttpStatusCode,
    val contentType: String?,
    val body: String,
  ) {
    /** `event` and `data` of each frame, in order. */
    val frames: List<Pair<String, String>>
      get() =
        body.split("\n\n").mapNotNull { block ->
          val event = block.lines().firstOrNull { it.startsWith("event: ") }?.removePrefix("event: ")
          val data = block.lines().firstOrNull { it.startsWith("data: ") }?.removePrefix("data: ")
          if (event != null && data != null) event to data else null
        }
  }

  override fun close() {
    family.close()
    client.close()
    db.close()
  }

  fun url(path: String) = family.url(path)

  suspend fun get(
    path: String,
    cookie: String?,
  ): Pair<HttpStatusCode, String> {
    val response = client.get(url(path)) { if (cookie != null) header(HttpHeaders.Cookie, cookie) }
    return response.status to response.bodyAsText()
  }

  suspend fun postJson(
    path: String,
    cookie: String?,
    body: String,
  ): Streamed {
    val response =
      client.post(url(path)) {
        if (cookie != null) header(HttpHeaders.Cookie, cookie)
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(body)
      }
    return Streamed(response.status, response.headers[HttpHeaders.ContentType], response.bodyAsText())
  }

  /** `POST /api/v1/supporters/me/conversations/stream`. */
  suspend fun startParentConvo(
    cookie: String,
    studentId: String,
    message: String = STARTER,
  ): Streamed = postJson(START_PATH, cookie, mapper.writeValueAsString(CreateSupporterConversationRequest(studentId, message)))

  /** `POST /api/v1/supporters/me/conversations/{id}/messages/stream`. */
  suspend fun postParentMessage(
    cookie: String,
    convoId: String,
    message: String,
  ): Streamed = postJson("$CONVERSATIONS_PATH/$convoId/messages/stream", cookie, mapper.writeValueAsString(PostMessageRequest(message)))

  /** The student's own buffered create (`POST /api/v1/conversations`); the new convo's id. */
  suspend fun startStudentConvo(
    cookie: String,
    message: String,
  ): String {
    val response = postJson("/api/v1/conversations", cookie, mapper.writeValueAsString(CreateConversationRequest(message)))
    assertEquals(HttpStatusCode.Created, response.status, response.body)
    return mapper.readTree(response.body)["conversation"]["id"].asText()
  }

  /** The conversation id an SSE start announced in its `conversation` frame. */
  fun startedConvoId(streamed: Streamed): String {
    val (event, data) = streamed.frames.first()
    assertEquals("conversation", event, streamed.body)
    return mapper.readTree(data)["conversation"]["id"].asText()
  }

  fun json(body: String): JsonNode = mapper.readTree(body)

  /** The highest `llm_requests.id` so far: the watermark a test's own calls are counted above. */
  fun llmRequestWatermark(): Long =
    db.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM llm_requests").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getLong(1)
      }
    }

  /** Every `llm_requests` body above [watermark], whole: system, messages (tool results included) and tools. */
  fun llmRequestBodiesAbove(watermark: Long): List<String> =
    db
      .prepareStatement(
        "SELECT COALESCE(system, '') || ' ' || content::text || ' ' || COALESCE(tools::text, '') FROM llm_requests WHERE id > ? ORDER BY id",
      ).use { stmt ->
        stmt.setLong(1, watermark)
        stmt.executeQuery().use { rs ->
          buildList { while (rs.next()) add(rs.getString(1)) }
        }
      }

  fun count(
    sql: String,
    vararg args: Any,
  ): Int =
    db.prepareStatement(sql).use { stmt ->
      args.forEachIndexed { i, arg -> stmt.setObject(i + 1, arg) }
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /**
   * A [ChatProvider] answering from a queue of terminals: each call takes the
   * next one, and an empty queue answers a plain text reply. Every request is
   * recorded. Its id is `log`, the one test provider `llm_requests` admits.
   */
  class ScriptedCoach : ChatProvider {
    override val id: String = "log"

    private val queued = ConcurrentLinkedQueue<ChatEvent.Terminal>()
    val requests = java.util.Collections.synchronizedList(mutableListOf<ChatRequest>())

    fun reset() {
      queued.clear()
      requests.clear()
    }

    fun enqueue(vararg terminals: ChatEvent.Terminal) {
      queued.addAll(terminals)
    }

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        requests += request
        val terminal = queued.poll() ?: text(DEFAULT_REPLY)
        // A text answer streams as one delta first, so the SSE door has a
        // `delta` frame to relay before its terminal, as a real reply does.
        if (terminal is ChatEvent.Completed && terminal.response.stopReason == "end_turn") {
          emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("…")))
        }
        emit(terminal)
      }

    companion object {
      const val DEFAULT_REPLY = "Here is what the list would cost your family, and what to do next."

      fun text(text: String): ChatEvent.Completed {
        val content =
          buildJsonArray {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", text)
              },
            )
          }
        return ChatEvent.Completed(ChatResponse(content, "claude-sonnet-4-6", "end_turn", TokenUsage(10, 20, 0, 0), "req_text"), content)
      }

      /** A `tool_use` terminal calling each named tool once with an empty input. */
      fun toolUse(vararg tools: String): ChatEvent.Completed {
        val content =
          buildJsonArray {
            tools.forEachIndexed { i, name ->
              add(
                buildJsonObject {
                  put("type", "tool_use")
                  put("id", "toolu_$i")
                  put("name", name)
                  putJsonObject("input") {}
                },
              )
            }
          }
        return ChatEvent.Completed(ChatResponse(content, "claude-sonnet-4-6", "tool_use", TokenUsage(10, 20, 0, 0), "req_tool"), content)
      }
    }
  }

  companion object {
    const val CONVERSATIONS_PATH = "/api/v1/supporters/me/conversations"
    const val START_PATH = "$CONVERSATIONS_PATH/stream"
    const val STARTER = "What would her list cost our family?"

    fun start(budgetConfig: (com.typesafe.config.Config) -> BudgetConfig = { BudgetConfig.from(it).getOrThrow() }): ParentCoachServer {
      val config =
        AppConfig
          .load("common.conf", "db.conf", "session.conf", "service.conf", "chat.conf", "rest-server.conf", "queue.conf", "email.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      val coach = ScriptedCoach()
      val server =
        startAppModuleServer(
          config,
          Database(dbConfig),
          budgetConfig(config),
          { path, _ -> throw IllegalStateException("Unexpected App Store call to [$path]") },
          coach,
          // The real price book, so every parent call freezes a real cost: the
          // cost suite sums those costs, and an unpriced call would sum to 0.
          LlmPriceBook.from(config).getOrThrow(),
        )
      return ParentCoachServer(
        FamilyRoutes.over(server),
        coach,
        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: ""),
        HttpClient(CIO),
      )
    }

    /** Parses a JSON literal, for fixtures. */
    fun parse(raw: String) = Json.parseToJsonElement(raw)
  }
}
