package ed.unicoach.coaching

import ed.unicoach.auth.FailingJobsDao
import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.ChatTool
import ed.unicoach.chat.ContentDelta
import ed.unicoach.chat.TokenUsage
import ed.unicoach.chat.ToolRegistry
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.coaching.extraction.ExtractionScheduler
import ed.unicoach.coaching.extraction.testExtractionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The extraction enqueue, from the SERVICE layer (RFC 262, spec preamble 10).
 *
 * It used to be `rest-server`'s, so a door calling [CoachingService]
 * in-process skipped it. These cases drive the real service over the real
 * database and read the `jobs` table: a completed student turn schedules
 * exactly one `EXTRACT_CONVERSATION` job through its FINAL request, and a
 * failed turn, a disabled switch and a failing queue each leave the turn's
 * outcome alone.
 */
class CoachingServiceExtractionTest {
  private val database = CoachingTestDb.database
  private val config =
    CoachingConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  @BeforeEach
  fun reset() {
    CoachingTestDb.truncate(
      "jobs",
      "convos",
      "convo_requests",
      "llm_requests",
      "llm_responses",
      "llm_responses_raw",
      "students",
      "users",
    )
  }

  private fun service(
    provider: ChatProvider,
    extraction: ExtractionScheduler = testExtractionScheduler(database),
    tools: ToolRegistry = ToolRegistry(emptyList()),
  ): CoachingService = CoachingService(database, LlmCallLog(provider, database), config, generousBudgetService(database), extraction, tools)

  @Test
  fun `a completed startConvo enqueues one job through the request Completed carries`() {
    runBlocking {
      val student = CoachingTestDb.createStudent("extract")
      val started = service(Scripted(completed("hi"))).startConvo(student, "Help me", null).getOrThrow()
      val terminal = assertIs<ReplyEvent.Completed>(drain(assertIs<StartConvoResult.Started>(started).reply))

      assertEquals(listOf(started.convo.id.asString to terminal.convoRequest.id.value), extractionJobs())
    }
  }

  @Test
  fun `a turn with a tool round is extracted through its FINAL request, not the opener`() {
    runBlocking {
      val student = CoachingTestDb.createStudent("extract")
      val provider = Scripted(toolUse("echo"), completed("done"))
      val started =
        assertIs<StartConvoResult.Started>(
          service(provider, tools = ToolRegistry(listOf(EchoTool))).startConvo(student, "Use a tool", null).getOrThrow(),
        )
      val terminal = assertIs<ReplyEvent.Completed>(drain(started.reply))

      // The watermark must advance over the whole excursion (RFC 94): the opener
      // is the user request, and Completed carries the continuation.
      assertNotEquals(started.userTurn.id, terminal.convoRequest.id, "the tool round must produce a second request")
      assertEquals(listOf(started.convo.id.asString to terminal.convoRequest.id.value), extractionJobs())
    }
  }

  @Test
  fun `a completed postTurn enqueues one more job, for its own request`() {
    runBlocking {
      val student = CoachingTestDb.createStudent("extract")
      val svc = service(Scripted(completed("one"), completed("two")))
      val first = assertIs<StartConvoResult.Started>(svc.startConvo(student, "First", null).getOrThrow())
      drain(first.reply)

      val second = assertIs<PostTurnResult.Started>(svc.postTurn(student, first.convo.id, "Second").getOrThrow())
      val terminal = assertIs<ReplyEvent.Completed>(drain(second.reply))

      val jobs = extractionJobs()
      assertEquals(2, jobs.size, "one job per completed turn: $jobs")
      assertEquals(first.convo.id.asString to terminal.convoRequest.id.value, jobs.last())
    }
  }

  @Test
  fun `a failed turn enqueues nothing, retriable or not`() {
    runBlocking {
      for (failure in listOf(transient(), rejected())) {
        val student = CoachingTestDb.createStudent("extract")
        val started = assertIs<StartConvoResult.Started>(service(Scripted(failure)).startConvo(student, "Hi", null).getOrThrow())
        assertIs<ReplyEvent.Failed>(drain(started.reply))
      }

      assertEquals(emptyList(), extractionJobs())
    }
  }

  @Test
  fun `extraction disabled enqueues nothing`() {
    runBlocking {
      val student = CoachingTestDb.createStudent("extract")
      val disabled = testExtractionScheduler(database, enabled = false)
      val started =
        assertIs<StartConvoResult.Started>(service(Scripted(completed("hi")), disabled).startConvo(student, "Hi", null).getOrThrow())
      assertIs<ReplyEvent.Completed>(drain(started.reply))

      assertEquals(emptyList(), extractionJobs())
    }
  }

  @Test
  fun `a queue DatabaseFailure is swallowed and the turn still completes`() {
    runBlocking {
      val student = CoachingTestDb.createStudent("extract")
      val failing = testExtractionScheduler(database, jobsDao = FailingJobsDao())
      val started =
        assertIs<StartConvoResult.Started>(service(Scripted(completed("hi")), failing).startConvo(student, "Hi", null).getOrThrow())

      assertIs<ReplyEvent.Completed>(drain(started.reply), "a failed enqueue must never alter the turn")
      assertEquals(emptyList(), extractionJobs())
    }
  }

  /** Every queued extraction job as (convoId, throughRequestId), in insertion order. */
  private fun extractionJobs(): List<Pair<String, Long>> =
    CoachingTestDb.connection
      .prepareStatement(
        "SELECT payload->>'convoId', (payload->>'throughRequestId')::bigint FROM jobs " +
          "WHERE job_type = 'EXTRACT_CONVERSATION' ORDER BY 2",
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          buildList { while (rs.next()) add(rs.getString(1) to rs.getLong(2)) }
        }
      }

  private suspend fun drain(reply: Flow<ReplyEvent>): ReplyEvent.Terminal = reply.toList().filterIsInstance<ReplyEvent.Terminal>().single()

  /** Answers call N with the Nth scripted terminal, after one text delta. */
  private class Scripted(
    vararg terminals: ChatEvent.Terminal,
  ) : ChatProvider {
    private val queue = ArrayDeque(terminals.toList())
    override val id: String = "log"

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        emit(ChatEvent.ContentBlockDelta(index = 0, delta = ContentDelta.Text("…")))
        emit(queue.removeFirst())
      }
  }

  /** A tool the scripted tool round names; it answers with an empty object. */
  private object EchoTool : ChatTool {
    override val name: String = "echo"
    override val definition: JsonObject = buildJsonObject { put("name", name) }

    override suspend fun execute(input: JsonObject): JsonObject = buildJsonObject { }
  }

  private fun completed(text: String): ChatEvent.Completed = response("""[{"type":"text","text":"$text"}]""", "end_turn")

  private fun toolUse(name: String): ChatEvent.Completed =
    response("""[{"type":"tool_use","id":"toolu_1","name":"$name","input":{}}]""", ConvoProjection.TOOL_USE_STOP_REASON)

  private fun response(
    content: String,
    stopReason: String,
  ): ChatEvent.Completed {
    val json = Json.parseToJsonElement(content)
    return ChatEvent.Completed(
      response = ChatResponse(json, "claude-sonnet-4-6", stopReason, TokenUsage(3, 5, null, null), "req_x"),
      rawPayload = json,
    )
  }

  private fun transient() = ChatEvent.TransientFailure(reason = "timeout", providerRequestId = "req_t", rawPayload = null)

  private fun rejected() = ChatEvent.Rejected(reason = "bad request", providerRequestId = "req_r", rawPayload = null)
}
