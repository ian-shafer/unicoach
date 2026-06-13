package ed.unicoach.coaching

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatRole
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.LogOnlyChatProvider
import ed.unicoach.chat.TokenUsage
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
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
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoachingServiceTest {
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
      stmt.execute(
        "TRUNCATE TABLE convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
    }
    // Re-seed coach/v1 (migration 0011) — the truncate above clears it.
    connection
      .prepareStatement(
        "INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')",
      ).use { it.executeUpdate() }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val config =
    CoachingConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  private fun service(provider: ChatProvider = LogOnlyChatProvider()): CoachingService = CoachingService(database, provider, config)

  // --- fixtures ---

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'svc-$userId@test.com', 'Svc User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

  // --- fakes ---

  /** Emits the given delta texts, then the supplied terminal. */
  private class ScriptedProvider(
    override val id: String = "log",
    private val deltas: List<String> = emptyList(),
    private val terminal: ChatEvent.Terminal,
    private val deltaDelayMs: Long = 0,
    private val onRequest: (ChatRequest) -> Unit = {},
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        onRequest(request)
        emit(ChatEvent.ContentBlockStart(index = 0, blockType = "text", block = null))
        for (d in deltas) {
          if (deltaDelayMs > 0) delay(deltaDelayMs)
          emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text(d)))
        }
        emit(terminal)
      }
  }

  /** A provider whose flow throws after one delta (a defect). */
  private class ThrowingProvider(
    override val id: String = "log",
  ) : ChatProvider {
    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("partial")))
        throw RuntimeException("boom")
      }
  }

  private fun completedTerminal(text: String): ChatEvent.Completed {
    val content = json("""[{"type":"text","text":"$text"}]""")
    return ChatEvent.Completed(
      response =
        ChatResponse(
          content = content,
          modelResolved = "claude-sonnet-4-6",
          stopReason = "end_turn",
          usage = TokenUsage(inputTokens = 3, outputTokens = 5, cacheReadTokens = null, cacheWriteTokens = null),
          providerRequestId = "req_123",
        ),
      rawPayload = json("""{"id":"req_123"}"""),
    )
  }

  private fun rejected(payload: JsonElement? = null) =
    ChatEvent.Rejected(reason = "bad request", providerRequestId = "req_rej", rawPayload = payload)

  private fun transient(payload: JsonElement? = null) =
    ChatEvent.TransientFailure(reason = "timeout", providerRequestId = "req_tr", rawPayload = payload)

  private suspend fun drain(reply: Flow<ReplyEvent>): List<ReplyEvent> = reply.toList()

  private fun terminalOf(events: List<ReplyEvent>): ReplyEvent.Terminal = events.filterIsInstance<ReplyEvent.Terminal>().single()

  private fun deltaText(events: List<ReplyEvent>): String = events.filterIsInstance<ReplyEvent.Delta>().joinToString("") { it.text }

  // --- helpers reading rows ---

  private fun countRows(
    table: String,
    convoId: ConvoId,
  ): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE convo_id = ?").use { stmt ->
      stmt.setObject(1, convoId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  private fun countConvos(convoId: ConvoId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM convos WHERE id = ?").use { stmt ->
      stmt.setObject(1, convoId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  private fun countRaw(): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM convo_responses_raw").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  /** Raw rows attached to error responses (stop_reason='error'); isolates failing-turn raws from the seed's success raw. */
  private fun countErrorRaw(): Int {
    connection
      .prepareStatement(
        "SELECT COUNT(*) FROM convo_responses_raw raw " +
          "JOIN convo_responses r ON r.id = raw.response_id WHERE r.stop_reason = 'error'",
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getInt(1)
        }
      }
  }

  // ===========================================================================
  // Turn happy path
  // ===========================================================================

  @Test
  fun `startConvo persists convo, request, response, and raw`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "Help me pick colleges", null).getOrThrow()
      assertTrue(started is StartConvoResult.Started)
      val events = drain(started.reply)
      val terminal = terminalOf(events)
      assertTrue(terminal is ReplyEvent.Completed)

      val convoId = started.convo.id
      assertEquals(1, countConvos(convoId))
      assertEquals(1, countRows("convo_requests", convoId))
      assertEquals(1, countRows("convo_responses", convoId))
      assertEquals(1, countRaw())

      // request provenance
      val turns = ConvosDao.listTurns(sqlSession, convoId).getOrThrow()
      val request = turns.single().request
      assertEquals("log", request.provider)
      assertEquals(config.model, request.modelRequested)
      val promptId = coachV1PromptId()
      assertEquals(promptId, request.systemPromptId.value)

      // delta concatenation == persisted coach content
      assertEquals(ConvoContent.renderText(terminal.response.content!!), deltaText(events))
    }

  private fun coachV1PromptId(): UUID {
    connection.prepareStatement("SELECT id FROM system_prompts WHERE name='coach' AND version='v1'").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return UUID.fromString(rs.getString("id"))
      }
    }
  }

  @Test
  fun `startConvo derives the name and uses explicit name verbatim`() {
    runBlocking {
      val student = createStudent()
      val longMessage = "  Hello   there\n\nI need\thelp  ".plus("x".repeat(200))
      val derived = service().startConvo(student, longMessage, null).getOrThrow()
      assertTrue(derived is StartConvoResult.Started)
      assertTrue(derived.convo.name.value.length <= 80)
      assertTrue(
        !derived.convo.name.value
          .contains("\n") &&
          !derived.convo.name.value
            .contains("  "),
      )
      drain(derived.reply)

      val explicit = service().startConvo(student, "anything", "My Custom Name").getOrThrow()
      assertTrue(explicit is StartConvoResult.Started)
      assertEquals("My Custom Name", explicit.convo.name.value)
      drain(explicit.reply)
    }
  }

  @Test
  fun `startConvo validation rejects blank, oversized, and invalid name`() =
    runBlocking {
      val student = createStudent()
      assertTrue(service().startConvo(student, "   ", null).getOrThrow() is StartConvoResult.ValidationFailure)
      assertTrue(service().startConvo(student, "x".repeat(100_001), null).getOrThrow() is StartConvoResult.ValidationFailure)
      assertTrue(service().startConvo(student, "ok", " ").getOrThrow() is StartConvoResult.ValidationFailure)
      // nothing persisted
      assertEquals(0, ConvosDao.listByStudentWithActivity(sqlSession, student, ArchiveScope.ALL).getOrThrow().size)
    }

  @Test
  fun `postTurn replays only visible history`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "first", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(
          deltas = listOf("reply"),
          terminal = completedTerminal("reply"),
          onRequest = { captured = it },
        )
      val post = service(provider).postTurn(student, started.convo.id, "second").getOrThrow() as PostTurnResult.Started
      drain(post.reply)

      val req = captured!!
      assertEquals(3, req.messages.size)
      assertEquals(ChatRole.USER, req.messages[0].role)
      assertEquals("first", req.messages[0].text)
      assertEquals(ChatRole.ASSISTANT, req.messages[1].role)
      assertEquals(ChatRole.USER, req.messages[2].role)
      assertEquals("second", req.messages[2].text)
      assertEquals("You are Uni, a warm coach.", req.system)
      assertEquals(config.maxTokens, req.maxTokens)
    }

  // ===========================================================================
  // Failure semantics
  // ===========================================================================

  @Test
  fun `failed turns are invisible`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "first ok", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)
      val convoId = started.convo.id

      val failing = ScriptedProvider(deltas = listOf("x"), terminal = transient())
      val post = service(failing).postTurn(student, convoId, "doomed").getOrThrow() as PostTurnResult.Started
      val events = drain(post.reply)
      assertTrue(terminalOf(events) is ReplyEvent.Failed)

      // error row persisted with content null
      assertEquals(2, countRows("convo_requests", convoId))
      assertEquals(2, countRows("convo_responses", convoId))

      // listTurns omits the failed turn; next replay omits it
      val visible = service().listTurns(student, convoId).getOrThrow()
      assertTrue(visible is ListTurnsResult.Found)
      assertEquals(1, visible.turns.size)

      var captured: ChatRequest? = null
      val capture = ScriptedProvider(deltas = listOf("r"), terminal = completedTerminal("r"), onRequest = { captured = it })
      val again = service(capture).postTurn(student, convoId, "third").getOrThrow() as PostTurnResult.Started
      drain(again.reply)
      // history = [USER first, ASSISTANT first-reply, USER third] (failed turn absent)
      val replay = captured!!
      assertEquals(3, replay.messages.size)
      assertEquals("third", replay.messages[2].text)
    }

  @Test
  fun `terminal mapping rejected vs transient with raw iff payload`() =
    runBlocking {
      val student = createStudent()
      val s1 = service().startConvo(student, "seed1", null).getOrThrow() as StartConvoResult.Started
      drain(s1.reply)
      val convoId = s1.convo.id

      val rejectedEvents =
        drain(
          (
            service(
              ScriptedProvider(terminal = rejected(json("""{"err":"x"}"""))),
            ).postTurn(student, convoId, "a").getOrThrow() as PostTurnResult.Started
          ).reply,
        )
      val rejTerminal = terminalOf(rejectedEvents)
      assertTrue(rejTerminal is ReplyEvent.Failed && !rejTerminal.retriable)

      val transientEvents =
        drain(
          (
            service(
              ScriptedProvider(terminal = transient(null)),
            ).postTurn(student, convoId, "b").getOrThrow() as PostTurnResult.Started
          ).reply,
        )
      val trTerminal = terminalOf(transientEvents)
      assertTrue(trTerminal is ReplyEvent.Failed && trTerminal.retriable)

      // rejected carried a payload (1 error raw row), transient did not.
      assertEquals(1, countErrorRaw())
    }

  @Test
  fun `provider defect maps to retriable failure`() =
    runBlocking {
      val student = createStudent()
      val s1 = service().startConvo(student, "seed", null).getOrThrow() as StartConvoResult.Started
      drain(s1.reply)
      val convoId = s1.convo.id

      val events = drain((service(ThrowingProvider()).postTurn(student, convoId, "x").getOrThrow() as PostTurnResult.Started).reply)
      val terminal = terminalOf(events)
      assertTrue(terminal is ReplyEvent.Failed && terminal.retriable)
      assertEquals(2, countRows("convo_responses", convoId))
    }

  @Test
  fun `cancellation persists the error row`() =
    runBlocking {
      val student = createStudent()
      val s1 = service().startConvo(student, "seed", null).getOrThrow() as StartConvoResult.Started
      drain(s1.reply)
      val convoId = s1.convo.id

      val slow = ScriptedProvider(deltas = listOf("a", "b", "c", "d"), terminal = completedTerminal("abcd"), deltaDelayMs = 200)
      val post = service(slow).postTurn(student, convoId, "x").getOrThrow() as PostTurnResult.Started

      var sawCompleted = false
      val job =
        launch {
          post.reply.collect { ev ->
            if (ev is ReplyEvent.Completed) sawCompleted = true
            if (ev is ReplyEvent.Delta) {
              // Cancel after the first delta.
              coroutineContext.job.cancel()
            }
          }
        }
      job.join()
      assertTrue(!sawCompleted, "Completed must not be observed after cancellation")
      // The NonCancellable finalizer wrote an error response row for the request.
      assertEquals(2, countRows("convo_responses", convoId))
    }

  @Test
  fun `failed first turn soft-deletes the convo`() {
    runBlocking {
      val student = createStudent()
      val failing = ScriptedProvider(deltas = listOf("x"), terminal = transient())
      val started = service(failing).startConvo(student, "doomed first", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      // convo soft-deleted; getConvo -> NotFound
      assertTrue(service().getConvo(student, started.convo.id).getOrThrow() is GetConvoResult.NotFound)
      val deleted = ConvosDao.findById(sqlSession, started.convo.id, SoftDeleteScope.DELETED).getOrThrow()
      assertNotNull(deleted.deletedAt)
    }
  }

  @Test
  fun `postTurn ownership`() =
    runBlocking {
      val student = createStudent()
      val other = createStudent()
      val started = service().startConvo(student, "mine", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)
      val convoId = started.convo.id

      assertTrue(service().postTurn(student, ConvoId(UUID.randomUUID()), "x").getOrThrow() is PostTurnResult.NotFound)
      assertTrue(service().postTurn(other, convoId, "x").getOrThrow() is PostTurnResult.NotFound)

      // archived convo is writable
      service().updateConvo(student, convoId, ConvoUpdate(archived = true)).getOrThrow()
      assertTrue(service().postTurn(student, convoId, "still ok").getOrThrow() is PostTurnResult.Started)

      // soft-deleted convo is NotFound
      service().deleteConvo(student, convoId).getOrThrow()
      assertTrue(service().postTurn(student, convoId, "x").getOrThrow() is PostTurnResult.NotFound)
    }

  @Test
  fun `missing system prompt is a failure not an outcome`() =
    runBlocking {
      val student = createStudent()
      val badConfig =
        CoachingConfig
          .from(
            com.typesafe.config.ConfigFactory.parseString(
              """coaching { model="m", maxTokens=10, systemPromptName="nope", systemPromptVersion="v9" }""",
            ),
          ).getOrThrow()
      val svc = CoachingService(database, LogOnlyChatProvider(), badConfig)
      val result = svc.startConvo(student, "hi", null)
      assertTrue(result.isFailure)
      assertTrue(result.exceptionOrNull() is IllegalStateException, "got ${result.exceptionOrNull()}")
    }

  // ===========================================================================
  // Lifecycle
  // ===========================================================================

  @Test
  fun `lifecycle list get update delete`() =
    runBlocking {
      val student = createStudent()
      val a = service().startConvo(student, "first conv", null).getOrThrow() as StartConvoResult.Started
      drain(a.reply)
      val b = service().startConvo(student, "second conv", null).getOrThrow() as StartConvoResult.Started
      drain(b.reply)

      // list active: both, ordered by activity desc (b started later)
      val active = service().listConvos(student, ArchiveScope.UNARCHIVED).getOrThrow()
      assertEquals(setOf(a.convo.id, b.convo.id), active.map { it.convo.id }.toSet())

      // rename advances updatedAt; archive does not
      val renamed = service().updateConvo(student, a.convo.id, ConvoUpdate(name = "Renamed")).getOrThrow()
      assertTrue(renamed is UpdateConvoResult.Success)
      assertTrue(
        renamed.listing.convo.updatedAt
          .isAfter(a.convo.updatedAt),
      )
      val beforeArchive = renamed.listing.convo.updatedAt
      val archived = service().updateConvo(student, a.convo.id, ConvoUpdate(archived = true)).getOrThrow() as UpdateConvoResult.Success
      assertEquals(beforeArchive, archived.listing.convo.updatedAt)
      assertNotNull(archived.listing.convo.archivedAt)

      // active list now excludes a; archived list includes it
      assertEquals(
        setOf(b.convo.id),
        service()
          .listConvos(student, ArchiveScope.UNARCHIVED)
          .getOrThrow()
          .map { it.convo.id }
          .toSet(),
      )
      assertEquals(
        setOf(a.convo.id),
        service()
          .listConvos(student, ArchiveScope.ARCHIVED)
          .getOrThrow()
          .map { it.convo.id }
          .toSet(),
      )

      // rename + archive in one call applies both
      val both =
        service()
          .updateConvo(
            student,
            b.convo.id,
            ConvoUpdate(name = "Both", archived = true),
          ).getOrThrow() as UpdateConvoResult.Success
      assertEquals("Both", both.listing.convo.name.value)
      assertNotNull(both.listing.convo.archivedAt)

      // PATCH with neither field -> ValidationFailure
      assertTrue(service().updateConvo(student, a.convo.id, ConvoUpdate()).getOrThrow() is UpdateConvoResult.ValidationFailure)

      // delete then any operation -> NotFound; second delete -> NotFound
      assertTrue(service().deleteConvo(student, a.convo.id).getOrThrow() is DeleteConvoResult.Success)
      assertTrue(service().deleteConvo(student, a.convo.id).getOrThrow() is DeleteConvoResult.NotFound)
      assertTrue(service().getConvo(student, a.convo.id).getOrThrow() is GetConvoResult.NotFound)
      assertTrue(service().listTurns(student, a.convo.id).getOrThrow() is ListTurnsResult.NotFound)
      assertTrue(service().updateConvo(student, a.convo.id, ConvoUpdate(name = "x")).getOrThrow() is UpdateConvoResult.NotFound)
    }

  @Test
  fun `latency recorded on completed`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "hi", null).getOrThrow() as StartConvoResult.Started
      val events = drain(started.reply)
      assertTrue(terminalOf(events) is ReplyEvent.Completed)
      val turn = ConvosDao.listTurns(sqlSession, started.convo.id).getOrThrow().single()
      val latency = turn.response!!.latencyMs
      assertNotNull(latency)
      assertTrue(latency >= 0)
    }

  @Test
  fun `terminal persistence write failure is reported as failure`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "seed", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)
      val convoId = started.convo.id

      // Pre-flight commits the user request row; capture it via Started.userTurn.
      val post =
        service(
          ScriptedProvider(deltas = listOf("ok"), terminal = completedTerminal("ok")),
        ).postTurn(student, convoId, "x").getOrThrow() as PostTurnResult.Started
      val requestId = post.userTurn.id.value

      // Inject a fault: a pre-existing response row for the SAME request_id. The
      // request_id UNIQUE constraint makes the service's tx-2 insert fail, so a
      // non-durable reply must surface as Failed(retriable=true), never Completed.
      connection
        .prepareStatement(
          "INSERT INTO convo_responses (request_id, convo_id, content, model_resolved, stop_reason) " +
            "VALUES (?, ?, '[{\"type\":\"text\",\"text\":\"pre\"}]'::jsonb, 'm', 'end_turn')",
        ).use { stmt ->
          stmt.setLong(1, requestId)
          stmt.setObject(2, convoId.value)
          stmt.executeUpdate()
        }

      val events = drain(post.reply)
      val terminal = terminalOf(events)
      assertTrue(terminal is ReplyEvent.Failed && terminal.retriable, "got $terminal")
      assertTrue(events.none { it is ReplyEvent.Completed })
    }
}
