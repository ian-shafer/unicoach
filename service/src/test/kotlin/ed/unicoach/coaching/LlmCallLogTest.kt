package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatMessage
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.LlmCallsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmFailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LlmCallLogTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE llm_requests, llm_responses, llm_responses_raw CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

  private val request =
    ChatRequest(
      model = "claude-sonnet-4-6",
      system = "You are a coach.",
      messages = listOf(ChatMessage.text(ChatRole.USER, "hello")),
      maxTokens = 1024,
    )

  private fun completed(text: String = "hi"): ChatEvent.Completed =
    ChatEvent.Completed(
      response =
        ChatResponse(
          content = json("""[{"type":"text","text":"$text"}]"""),
          modelResolved = "claude-sonnet-4-6-99",
          stopReason = "end_turn",
          usage = TokenUsage(inputTokens = 3, outputTokens = 5, cacheReadTokens = 1, cacheWriteTokens = 0),
          providerRequestId = "req_123",
        ),
      rawPayload = json("""{"id":"req_123"}"""),
    )

  /** A provider emitting scripted deltas then a scripted terminal. */
  private class ScriptedProvider(
    override val id: String = "log",
    private val deltas: List<String> = emptyList(),
    private val terminal: ChatEvent.Terminal,
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        for (d in deltas) emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text(d)))
        emit(terminal)
      }
  }

  /** A provider whose flow throws (a port-contract defect). */
  private class ThrowingProvider(
    override val id: String = "log",
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        throw RuntimeException("boom")
      }
  }

  /** A provider that emits one delta then suspends forever until cancelled. */
  private class HangingProvider(
    override val id: String = "log",
    private val emitted: CompletableDeferred<Unit>,
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("partial")))
        emitted.complete(Unit)
        kotlinx.coroutines.awaitCancellation()
      }
  }

  private fun log(
    provider: ChatProvider,
    nanoTime: () -> Long = System::nanoTime,
  ): LlmCallLog = LlmCallLog(provider, database, nanoTime)

  private fun responseRowCount(): Int =
    connection.createStatement().use { st ->
      st.executeQuery("SELECT count(*) FROM llm_responses").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun responseRowCountFor(requestId: ed.unicoach.db.models.LlmRequestId): Int =
    connection.prepareStatement("SELECT count(*) FROM llm_responses WHERE request_id = ?").use { st ->
      st.setLong(1, requestId.value)
      st.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /** A minimal `NewLlmRequest` for seeding a bare `llm_requests` row directly. */
  private fun newRequestOf(provider: String): ed.unicoach.db.models.NewLlmRequest =
    ed.unicoach.db.models.NewLlmRequest(
      provider = provider,
      modelRequested = "claude-sonnet-4-6",
      system = null,
      content = kotlinx.serialization.json.JsonArray(emptyList()),
      maxTokens = 1024,
      tools = null,
      toolChoice = null,
      params = null,
    )

  @Test
  fun `record completed writes request, response, raw and returns terminal`() =
    runBlocking {
      val logged = log(ScriptedProvider(terminal = completed())).record(request)

      val call = LlmCallsDao.findCallByRequestId(session, logged.llmRequestId).getOrThrow()
      assertEquals("log", call.request.provider)
      assertEquals("claude-sonnet-4-6", call.request.modelRequested)
      val outcome = call.response!!.outcome
      assertTrue(outcome is LlmCallOutcome.Completed)
      assertEquals("end_turn", outcome.stopReason)
      assertEquals(3, call.response!!.inputTokens)
      assertEquals(1, call.response!!.cacheReadTokens)
      assertTrue(call.response!!.latencyMs >= 0)
      assertEquals(json("""{"id":"req_123"}"""), call.raw!!.payload)
      // The terminal is returned unchanged.
      assertTrue(logged.terminal is ChatEvent.Completed)
    }

  @Test
  fun `record rejected records reason and no raw when bodiless, returns terminal`() =
    runBlocking {
      val terminal = ChatEvent.Rejected(reason = "bad request", providerRequestId = "req_r", rawPayload = null)
      val logged = log(ScriptedProvider(terminal = terminal)).record(request)

      val call = LlmCallsDao.findCallByRequestId(session, logged.llmRequestId).getOrThrow()
      assertEquals(LlmCallOutcome.Failed(LlmFailureKind.REJECTED, "bad request"), call.response!!.outcome)
      assertEquals("req_r", call.response!!.providerRequestId)
      assertNull(call.raw)
      assertTrue(logged.terminal is ChatEvent.Rejected)
    }

  @Test
  fun `record transient with body writes raw`() =
    runBlocking {
      val terminal =
        ChatEvent.TransientFailure(reason = "timeout", providerRequestId = null, rawPayload = json("""{"err":1}"""))
      val logged = log(ScriptedProvider(terminal = terminal)).record(request)

      val call = LlmCallsDao.findCallByRequestId(session, logged.llmRequestId).getOrThrow()
      assertEquals(LlmCallOutcome.Failed(LlmFailureKind.TRANSIENT_FAILURE, "timeout"), call.response!!.outcome)
      assertEquals(json("""{"err":1}"""), call.raw!!.payload)
    }

  @Test
  fun `record on an escaping exception writes internal_error and rethrows`() =
    runBlocking {
      val callLog = log(ThrowingProvider())
      val ex = runCatching { callLog.record(request) }.exceptionOrNull()
      assertTrue(ex is RuntimeException && ex.message == "boom", "got $ex")

      // Exactly one internal_error response row, distinct from transient_failure,
      // whose reason is the classifier's "SimpleName: message".
      connection.createStatement().use { st ->
        st.executeQuery("SELECT outcome, reason FROM llm_responses").use { rs ->
          assertTrue(rs.next())
          assertEquals("internal_error", rs.getString("outcome"))
          assertEquals("RuntimeException: boom", rs.getString("reason"))
        }
      }
    }

  @Test
  fun `recordStreaming exposes id up front and relays every event in order`() =
    runBlocking {
      val call = log(ScriptedProvider(deltas = listOf("he", "llo"), terminal = completed("hello"))).recordStreaming(request)
      // Id is available before collection.
      assertTrue(call.llmRequestId.value > 0)

      val relayed = call.events.toList()
      val deltaText =
        relayed
          .filterIsInstance<ChatEvent.ContentBlockDelta>()
          .joinToString("") { (it.delta as ContentDelta.Text).text }
      assertEquals("hello", deltaText)
      assertTrue(relayed.last() is ChatEvent.Completed)

      val logged = LlmCallsDao.findCallByRequestId(session, call.llmRequestId).getOrThrow()
      assertTrue(logged.response!!.outcome is LlmCallOutcome.Completed)
    }

  @Test
  fun `recordStreaming cancellation writes exactly one cancelled row and rethrows`() =
    runBlocking {
      // Case A: the client disconnects WHILE the stream is being collected. The
      // recordStreaming cold flow catches the cancellation and writes the row.
      val emitted = CompletableDeferred<Unit>()
      val call = log(HangingProvider(emitted = emitted)).recordStreaming(request)

      val collector: Job =
        launch {
          call.events.collect { }
        }
      emitted.await()
      collector.cancel()
      collector.join()

      // Under NonCancellable the cancelled response row is written — exactly one,
      // for this request.
      val logged = LlmCallsDao.findCallByRequestId(session, call.llmRequestId).getOrThrow()
      assertEquals(
        LlmCallOutcome.Failed(LlmFailureKind.CANCELLED, LlmCallLog.CANCELLED_REASON),
        logged.response!!.outcome,
      )
      assertEquals(1, responseRowCountFor(call.llmRequestId), "exactly one cancelled response row for the request")
    }

  @Test
  fun `writeCancelledIfAbsent writes a cancelled row when the request has no response`() =
    runBlocking {
      // Case B (the gap): a request opened but never collected — no response row.
      // writeCancelledIfAbsent supplies the one cancelled row.
      val requestId =
        database.withConnection { s ->
          LlmCallsDao.appendRequest(s, newRequestOf("log")).getOrThrow().id
        }
      assertEquals(0, responseRowCountFor(requestId), "precondition: no response yet")

      log(ScriptedProvider(terminal = completed())).writeCancelledIfAbsent(requestId)

      val logged = LlmCallsDao.findCallByRequestId(session, requestId).getOrThrow()
      assertEquals(
        LlmCallOutcome.Failed(LlmFailureKind.CANCELLED, LlmCallLog.CANCELLED_REASON),
        logged.response!!.outcome,
      )
      assertEquals(0, logged.response!!.latencyMs, "the provider call never ran")
      assertNull(logged.response!!.inputTokens)
      assertNull(logged.response!!.providerRequestId)
      assertNull(logged.raw)
      assertEquals(1, responseRowCountFor(requestId))
    }

  @Test
  fun `writeCancelledIfAbsent is a no-op when a completed response already exists`() =
    runBlocking {
      // The collecting path already wrote the terminal response; a later
      // writeCancelledIfAbsent must not throw and must not add a second row.
      val logged = log(ScriptedProvider(terminal = completed())).record(request)
      assertEquals(1, responseRowCountFor(logged.llmRequestId), "precondition: completed row present")

      log(ScriptedProvider(terminal = completed())).writeCancelledIfAbsent(logged.llmRequestId)

      val call = LlmCallsDao.findCallByRequestId(session, logged.llmRequestId).getOrThrow()
      assertTrue(call.response!!.outcome is LlmCallOutcome.Completed, "the completed row is preserved")
      assertEquals(1, responseRowCountFor(logged.llmRequestId), "no second response row")
    }

  @Test
  fun `writeInternalErrorIfAbsent writes an internal_error row when the request has no response`() =
    runBlocking {
      // The defect path: a request opened but never collected — no response row.
      // writeInternalErrorIfAbsent supplies the one internal_error row, stamping the
      // cause the same way record's own in-flow catch does.
      val requestId =
        database.withConnection { s ->
          LlmCallsDao.appendRequest(s, newRequestOf("log")).getOrThrow().id
        }
      assertEquals(0, responseRowCountFor(requestId), "precondition: no response yet")

      val cause = RuntimeException("boom on convo_requests insert")
      log(ScriptedProvider(terminal = completed())).writeInternalErrorIfAbsent(requestId, cause)

      val logged = LlmCallsDao.findCallByRequestId(session, requestId).getOrThrow()
      assertEquals(
        LlmCallOutcome.Failed(LlmFailureKind.INTERNAL_ERROR, "RuntimeException: boom on convo_requests insert"),
        logged.response!!.outcome,
      )
      assertEquals(0, logged.response!!.latencyMs, "the provider call never ran")
      assertNull(logged.response!!.inputTokens)
      assertNull(logged.response!!.providerRequestId)
      assertNull(logged.raw)
      assertEquals(1, responseRowCountFor(requestId))
    }

  @Test
  fun `writeInternalErrorIfAbsent is a no-op when a completed response already exists`() =
    runBlocking {
      // The collecting path already wrote the terminal response; a later
      // writeInternalErrorIfAbsent must not throw and must not add a second row.
      val logged = log(ScriptedProvider(terminal = completed())).record(request)
      assertEquals(1, responseRowCountFor(logged.llmRequestId), "precondition: completed row present")

      log(ScriptedProvider(terminal = completed()))
        .writeInternalErrorIfAbsent(logged.llmRequestId, RuntimeException("late defect"))

      val call = LlmCallsDao.findCallByRequestId(session, logged.llmRequestId).getOrThrow()
      assertTrue(call.response!!.outcome is LlmCallOutcome.Completed, "the completed row is preserved")
      assertEquals(1, responseRowCountFor(logged.llmRequestId), "no second response row")
    }

  @Test
  fun `latency uses the injected clock`() =
    runBlocking {
      // A nanoTime read twice: the record start (0) and the response-write end
      // (5ms), yielding a deterministic latency_ms of 5.
      val ticks = ArrayDeque(listOf(0L, 5_000_000L))
      val fakeNano = { ticks.removeFirst() }
      val logged = log(ScriptedProvider(terminal = completed()), nanoTime = fakeNano).record(request)
      val call = LlmCallsDao.findCallByRequestId(session, logged.llmRequestId).getOrThrow()
      assertEquals(5, call.response!!.latencyMs)
    }

  @Test
  fun `every request gets exactly one response row`() =
    runBlocking {
      log(ScriptedProvider(terminal = completed())).record(request)
      log(ScriptedProvider(terminal = ChatEvent.Rejected("x", null, null))).record(request)
      assertEquals(2, responseRowCount())
    }
}
