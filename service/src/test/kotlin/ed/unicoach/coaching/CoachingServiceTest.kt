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
import ed.unicoach.db.dao.CommitmentsDao
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.dao.FitSuggestionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CommitmentDisclosure
import ed.unicoach.db.models.CommitmentId
import ed.unicoach.db.models.CommitmentLens
import ed.unicoach.db.models.CommitmentStatus
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.FitSuggestionId
import ed.unicoach.db.models.FitSuggestionStatus
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCommitment
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        "TRUNCATE TABLE commitment_support, commitments, fit_suggestions, convos, convo_requests, " +
          "llm_requests, llm_responses, llm_responses_raw, " +
          "claims, colleges, system_prompts, students, users CASCADE",
      )
    }
    // Restore all migration-seeded prompts for cross-module suites on the shared DB.
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
    }
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

  private fun service(provider: ChatProvider = LogOnlyChatProvider()): CoachingService =
    CoachingService(database, LlmCallLog(provider, database), config)

  /** A config with the RFC 93 opener injection disabled. */
  private val noSurfaceConfig =
    CoachingConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString("coaching.surfaceCommitments = false")
          .withFallback(
            ed.unicoach.common.config.AppConfig
              .load("service.conf")
              .getOrThrow(),
          ),
      ).getOrThrow()

  private fun noSurfaceService(provider: ChatProvider): CoachingService =
    CoachingService(database, LlmCallLog(provider, database), noSurfaceConfig)

  /** A config with the RFC 98 fit-lens opener contribution disabled (commitments still on). */
  private val noSurfaceFitConfig =
    CoachingConfig
      .from(
        com.typesafe.config.ConfigFactory
          .parseString("coaching.surfaceFitSuggestions = false")
          .withFallback(
            ed.unicoach.common.config.AppConfig
              .load("service.conf")
              .getOrThrow(),
          ),
      ).getOrThrow()

  private fun noSurfaceFitService(provider: ChatProvider): CoachingService =
    CoachingService(database, LlmCallLog(provider, database), noSurfaceFitConfig)

  private var unitIdCounter = 800000

  /** Inserts a college and returns its id. */
  private fun createCollege(
    name: String = "Reed College",
    city: String = "Portland",
    state: String = "OR",
  ): CollegeId =
    ed.unicoach.db.dao.CollegesDao
      .upsert(
        sqlSession,
        NewCollege(
          unitId = unitIdCounter++,
          opeid = null,
          name = name,
          city = city,
          state = state,
          region = 8,
          locale = 13,
          latitude = 45.0,
          longitude = -122.0,
          control = 2,
          undergradEnrollment = 1400,
          admissionRate = 0.35,
          satAvg = 1400,
          costAttendance = 70000,
          netPrice = 30000,
          tuitionInState = 60000,
          tuitionOutState = 60000,
          graduationRate = 0.8,
          medianEarnings = 60000,
          pctPell = 0.15,
          website = null,
        ),
      ).getOrThrow()
      .id

  /** Creates an open fit suggestion for the student and returns its id. */
  private fun createFitSuggestion(
    studentId: StudentId,
    collegeId: CollegeId,
    rationale: String,
  ): FitSuggestionId =
    FitSuggestionsDao
      .create(sqlSession, NewFitSuggestion(studentId, collegeId, rationale))
      .getOrThrow()
      .id

  private fun fitSuggestionStatus(id: FitSuggestionId): FitSuggestionStatus = FitSuggestionsDao.findById(sqlSession, id).getOrThrow().status

  private fun fitSuggestionConvo(id: FitSuggestionId): ConvoId? = FitSuggestionsDao.findById(sqlSession, id).getOrThrow().surfacedInConvoId

  // --- fixtures ---

  private fun createCommitment(
    studentId: StudentId,
    statement: String,
    disclosure: CommitmentDisclosure = CommitmentDisclosure.EXPLICIT,
  ): CommitmentId =
    CommitmentsDao
      .create(sqlSession, NewCommitment(studentId, CommitmentLens.GAP, disclosure, statement))
      .getOrThrow()
      .id

  private fun commitmentStatus(id: CommitmentId): CommitmentStatus = CommitmentsDao.findById(sqlSession, id).getOrThrow().status

  private fun commitmentConvo(id: CommitmentId): ConvoId? = CommitmentsDao.findById(sqlSession, id).getOrThrow().disclosedInConvoId

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

  private fun messageText(message: ed.unicoach.chat.ChatMessage): String = ConvoContent.renderText(message.content)

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

  /** A Completed terminal whose response is a tool_use turn requesting the given tool calls. */
  private fun toolUseTerminal(vararg calls: Pair<String, String>): ChatEvent.Completed {
    val blocks =
      calls.mapIndexed { i, (name, inputJson) ->
        """{"type":"tool_use","id":"toolu_$i","name":"$name","input":$inputJson}"""
      }
    val content = json("[${blocks.joinToString(",")}]")
    return ChatEvent.Completed(
      response =
        ChatResponse(
          content = content,
          modelResolved = "claude-sonnet-4-6",
          stopReason = "tool_use",
          usage = TokenUsage(inputTokens = 7, outputTokens = 11, cacheReadTokens = null, cacheWriteTokens = null),
          providerRequestId = "req_tooluse",
        ),
      rawPayload = json("""{"id":"req_tooluse"}"""),
    )
  }

  /**
   * A provider that returns a scripted terminal per call in sequence (call 0, 1,
   * …), streaming no deltas. Captures each request for assertions on the running
   * message list and the advertised tools.
   */
  private class SequencedProvider(
    override val id: String = "log",
    private val terminals: List<ChatEvent.Terminal>,
    private val onRequest: (ChatRequest) -> Unit = {},
  ) : ChatProvider {
    private var call = 0

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        onRequest(request)
        val terminal = terminals[call.coerceAtMost(terminals.size - 1)]
        call++
        emit(terminal)
      }
  }

  /** A ChatTool spy: records inputs, returns a canned object (or throws). */
  private class SpyTool(
    override val name: String,
    private val result: JsonObject = buildJsonObject { },
    private val throwOnExecute: Boolean = false,
  ) : ed.unicoach.chat.ChatTool {
    val inputs = mutableListOf<JsonObject>()

    override val definition = buildJsonObject { put("name", name) }

    override suspend fun execute(input: JsonObject): JsonObject {
      inputs.add(input)
      if (throwOnExecute) throw RuntimeException("tool boom")
      return result
    }
  }

  private fun registry(vararg t: ed.unicoach.chat.ChatTool) = ed.unicoach.chat.ToolRegistry(t.toList())

  private fun serviceWith(
    provider: ChatProvider,
    tools: ed.unicoach.chat.ToolRegistry,
    cfg: CoachingConfig = config,
  ): CoachingService = CoachingService(database, LlmCallLog(provider, database), cfg, tools)

  /**
   * A dispatcher over [Dispatchers.IO] that can PARK exactly one dispatched block
   * on a latch — the deterministic seam for the mid-tool-loop cancellation gap
   * (RFC 106 Case B). The real Postgres runs underneath every block; the gate only
   * controls *when* one block is allowed to run.
   *
   * When [armOnce] is set, the next dispatched block is captured (not run):
   * [parked] completes so the test knows the guarded window is open, and the block
   * is held until [release] is completed. This lets the test cancel the collecting
   * coroutine while the continuation's `appendRequestRow` transaction is parked —
   * after `LlmCallLog.recordStreaming` committed the continuation's `llm_requests`
   * row (on the ungated DB) but before its cold events flow is ever collected —
   * exactly the orphaned-continuation window the opener/continuation guard closes.
   */
  private class GatedDispatcher : CoroutineDispatcher() {
    // While < 0 the gate is disarmed. When armed to N >= 0 it parks the (N+1)-th
    // dispatched block: e.g. arm(0) parks the very next dispatch; arm(1) lets one
    // dispatch through and parks the one after it.
    @Volatile
    private var skipsRemaining: Int = -1

    val parked = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    /** Arm the gate so the [skip]-th subsequent dispatch (0 = the next one) is parked. */
    fun arm(skip: Int) {
      skipsRemaining = skip
    }

    @Synchronized
    private fun claimPark(): Boolean {
      if (skipsRemaining < 0) return false
      if (skipsRemaining == 0) {
        skipsRemaining = -1
        return true
      }
      skipsRemaining -= 1
      return false
    }

    override fun dispatch(
      context: CoroutineContext,
      block: Runnable,
    ) {
      if (claimPark()) {
        // Capture this one block and hold it until released, off the IO threads so
        // as not to starve the pool. Signal the test that the window is open.
        Thread {
          parked.complete(Unit)
          runBlocking { release.await() }
          Dispatchers.IO.dispatch(context, block)
        }.apply { isDaemon = true }.start()
      } else {
        Dispatchers.IO.dispatch(context, block)
      }
    }
  }

  /**
   * Builds a service whose OWN `database` runs on [gate] (so its `appendRequestRow`
   * transaction can be parked) while `LlmCallLog` keeps the ungated real [database]
   * (so the continuation's `llm_requests` row commits normally). Both point at the
   * same Postgres, so rows written through either are visible to the assertions.
   */
  private fun gatedServiceWith(
    provider: ChatProvider,
    tools: ed.unicoach.chat.ToolRegistry,
    gate: GatedDispatcher,
    cfg: CoachingConfig = config,
  ): CoachingService = gatedServiceAndDb(provider, tools, gate, cfg).first

  /**
   * Like [gatedServiceWith], but also returns the gated [Database] so a defect-path
   * test can [Database.close] it while the target `appendRequestRow` transaction is
   * parked — the next connection acquisition then throws a non-cancellation
   * `SQLException`, reproducing a transient DB failure on the `convo_requests`
   * INSERT AFTER `LlmCallLog` (on the ungated real [database]) committed the
   * `llm_requests` row. This is the throw-mode of the same seam the cancellation
   * Case B tests use, targeting the identical post-commit window.
   */
  private fun gatedServiceAndDb(
    provider: ChatProvider,
    tools: ed.unicoach.chat.ToolRegistry,
    gate: GatedDispatcher,
    cfg: CoachingConfig = config,
  ): Pair<CoachingService, Database> {
    val dbConfig =
      DatabaseConfig
        .from(
          ed.unicoach.common.config.AppConfig
            .load("common.conf", "db.conf", "service.conf")
            .getOrThrow(),
        ).getOrThrow()
    val gatedDatabase = Database(dbConfig, gate)
    return CoachingService(gatedDatabase, LlmCallLog(provider, database), cfg, tools) to gatedDatabase
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

  /**
   * Count of `llm_responses` rows for a convo's requests. Since RFC 106 the
   * response lives in the generic call log, reached via
   * `convo_requests.llm_request_id`, so a per-convo response count is this join,
   * not a `convo_responses.convo_id` filter.
   */
  private fun countResponses(convoId: ConvoId): Int {
    connection
      .prepareStatement(
        "SELECT COUNT(*) FROM llm_responses resp " +
          "JOIN convo_requests r ON r.llm_request_id = resp.request_id WHERE r.convo_id = ?",
      ).use { stmt ->
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

  /** Request `kind` strings for a convo, in id order. */
  private fun requestKinds(convoId: ConvoId): List<String> {
    val out = mutableListOf<String>()
    connection.prepareStatement("SELECT kind FROM convo_requests WHERE convo_id = ? ORDER BY id").use { stmt ->
      stmt.setObject(1, convoId.value)
      stmt.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString("kind")) }
    }
    return out
  }

  /** Request `turn_id` values for a convo, in id order. */
  private fun turnIds(convoId: ConvoId): List<Long> {
    val out = mutableListOf<Long>()
    connection.prepareStatement("SELECT turn_id FROM convo_requests WHERE convo_id = ? ORDER BY id").use { stmt ->
      stmt.setObject(1, convoId.value)
      stmt.executeQuery().use { rs -> while (rs.next()) out.add(rs.getLong("turn_id")) }
    }
    return out
  }

  /**
   * Response (stop_reason, output_tokens) pairs for a convo, in id order. Since
   * RFC 106 the response lives in the generic call log, reached via
   * `convo_requests.llm_request_id -> llm_responses`. A completed response carries
   * a `stop_reason`; a failure carries none, so its `stop_reason` reads as
   * `outcome` (e.g. `transient_failure`) — the failing-turn rows the callers here
   * only ever assert on completed shapes.
   */
  private fun responseRows(convoId: ConvoId): List<Pair<String, Int?>> {
    val out = mutableListOf<Pair<String, Int?>>()
    connection
      .prepareStatement(
        "SELECT COALESCE(resp.stop_reason, resp.outcome) AS sr, resp.output_tokens AS ot FROM llm_responses resp " +
          "JOIN convo_requests r ON r.llm_request_id = resp.request_id WHERE r.convo_id = ? ORDER BY resp.id",
      ).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.executeQuery().use { rs ->
          while (rs.next()) {
            val ot = rs.getInt("ot").takeUnless { rs.wasNull() }
            out.add(rs.getString("sr") to ot)
          }
        }
      }
    return out
  }

  /** Total `llm_requests` rows in the DB (single-convo Case B tests truncate between runs). */
  private fun countAllLlmRequests(): Int = countAll("llm_requests")

  /** Total `llm_responses` rows in the DB. */
  private fun countAllLlmResponses(): Int = countAll("llm_responses")

  private fun countAll(table: String): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM $table").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  /** Every `llm_responses.outcome` in id order. */
  private fun allResponseOutcomes(): List<String> {
    val out = mutableListOf<String>()
    connection.prepareStatement("SELECT outcome FROM llm_responses ORDER BY id").use { stmt ->
      stmt.executeQuery().use { rs -> while (rs.next()) out.add(rs.getString("outcome")) }
    }
    return out
  }

  private fun countRaw(): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM llm_responses_raw").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  /** Raw rows attached to failed responses; isolates failing-turn raws from the seed's completed raw. */
  private fun countErrorRaw(): Int {
    connection
      .prepareStatement(
        "SELECT COUNT(*) FROM llm_responses_raw raw " +
          "JOIN llm_responses r ON r.id = raw.response_id WHERE r.outcome <> 'completed'",
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
      assertEquals(1, countResponses(convoId))
      assertEquals(1, countRaw())

      // request provenance: the coaching row's system prompt, and the joined
      // generic call log's provider/model (RFC 106 moved those off convo_requests).
      val turns = ConvosDao.listTurns(sqlSession, convoId).getOrThrow()
      val turn = turns.single()
      assertEquals("log", turn.call!!.request.provider)
      assertEquals(config.model, turn.call!!.request.modelRequested)
      val promptId = coachV1PromptId()
      assertEquals(promptId, turn.request.systemPromptId.value)

      // delta concatenation == persisted coach content
      assertEquals(ConvoContent.renderText(terminal.content), deltaText(events))
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
      assertEquals("first", messageText(req.messages[0]))
      assertEquals(ChatRole.ASSISTANT, req.messages[1].role)
      assertEquals(ChatRole.USER, req.messages[2].role)
      assertEquals("second", messageText(req.messages[2]))
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
      assertEquals(2, countResponses(convoId))

      // listTurns omits the failed turn; next replay omits it
      val visible = service().listTurns(student, convoId).getOrThrow()
      assertTrue(visible is ListTurnsResult.Found)
      assertEquals(1, visible.exchanges.size)

      var captured: ChatRequest? = null
      val capture = ScriptedProvider(deltas = listOf("r"), terminal = completedTerminal("r"), onRequest = { captured = it })
      val again = service(capture).postTurn(student, convoId, "third").getOrThrow() as PostTurnResult.Started
      drain(again.reply)
      // history = [USER first, ASSISTANT first-reply, USER third] (failed turn absent)
      val replay = captured!!
      assertEquals(3, replay.messages.size)
      assertEquals("third", messageText(replay.messages[2]))
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
      assertEquals(2, countResponses(convoId))
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
      assertEquals(2, countResponses(convoId))
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
              """coaching { model="m", maxTokens=10, systemPromptName="nope", systemPromptVersion="v9", surfaceCommitments=true, surfaceFitSuggestions=true, maxToolRounds=8 }""",
            ),
          ).getOrThrow()
      val svc = CoachingService(database, LlmCallLog(LogOnlyChatProvider(), database), badConfig)
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
      val latency = turn.call!!.response!!.latencyMs
      assertTrue(latency >= 0)
    }

  @Test
  fun `terminal persistence write failure is reported as failure`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "seed", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)
      val convoId = started.convo.id

      // Pre-flight commits the user request row (and its llm_requests row); capture
      // the log-owned llm_request_id via Started.userTurn.
      val post =
        service(
          ScriptedProvider(deltas = listOf("ok"), terminal = completedTerminal("ok")),
        ).postTurn(student, convoId, "x").getOrThrow() as PostTurnResult.Started
      val llmRequestId = post.userTurn.llmRequestId.value

      // Inject a fault: a pre-existing llm_responses row for the SAME llm_request_id.
      // The request_id UNIQUE constraint on llm_responses makes LlmCallLog's terminal
      // response write fail, so a non-durable reply must surface as
      // Failed(retriable=true), never Completed (RFC 106 moved the response into the
      // generic call log).
      connection
        .prepareStatement(
          "INSERT INTO llm_responses (request_id, outcome, content, model_resolved, stop_reason, latency_ms) " +
            "VALUES (?, 'completed', '[{\"type\":\"text\",\"text\":\"pre\"}]'::jsonb, 'm', 'end_turn', 0)",
        ).use { stmt ->
          stmt.setLong(1, llmRequestId)
          stmt.executeUpdate()
        }

      val events = drain(post.reply)
      val terminal = terminalOf(events)
      assertTrue(terminal is ReplyEvent.Failed && terminal.retriable, "got $terminal")
      assertTrue(events.none { it is ReplyEvent.Completed })
    }

  // ===========================================================================
  // Pull delivery (RFC 93): next-session opener
  // ===========================================================================

  @Test
  fun `startConvo composes an open explicit commitment into the system text and marks it fulfilled on success`() =
    runBlocking {
      val student = createStudent()
      val commitmentId = createCommitment(student, "MARKER_finances_conversation")

      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"), onRequest = { captured = it })
      val started = service(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
      val events = drain(started.reply)
      assertTrue(terminalOf(events) is ReplyEvent.Completed)

      // The commitment statement rode the composed system prompt.
      val systemText = captured!!.system!!
      assertTrue(systemText.contains("MARKER_finances_conversation"), "commitment must be in the system text")
      assertTrue(systemText.contains("You are Uni"), "the base prompt must still be present")

      // A successful first turn marks it fulfilled against this convo.
      assertEquals(CommitmentStatus.FULFILLED, commitmentStatus(commitmentId))
      assertEquals(started.convo.id, commitmentConvo(commitmentId))
    }

  @Test
  fun `a failed first turn leaves the commitment open`() =
    runBlocking {
      val student = createStudent()
      val commitmentId = createCommitment(student, "raise the activities gap")

      val failing = ScriptedProvider(deltas = listOf("x"), terminal = transient())
      val started = service(failing).startConvo(student, "doomed first", null).getOrThrow() as StartConvoResult.Started
      assertTrue(terminalOf(drain(started.reply)) is ReplyEvent.Failed)

      // The convo was soft-deleted; the commitment stays open to re-surface next session.
      assertEquals(CommitmentStatus.OPEN, commitmentStatus(commitmentId))
      assertNull(commitmentConvo(commitmentId))
    }

  @Test
  fun `internal and already-resolved commitments are not surfaced`() =
    runBlocking {
      val student = createStudent()
      val internalId = createCommitment(student, "INTERNAL_note", disclosure = CommitmentDisclosure.INTERNAL)

      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"), onRequest = { captured = it })
      val started = service(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      // Internal commitment is neither in the prompt nor marked fulfilled.
      assertEquals("You are Uni, a warm coach.", captured!!.system)
      assertEquals(CommitmentStatus.OPEN, commitmentStatus(internalId))
    }

  @Test
  fun `postTurn never surfaces commitments`() =
    runBlocking {
      val student = createStudent()
      // First turn (with no commitments) so a convo exists.
      val started = service().startConvo(student, "first", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      // Now add an open explicit commitment; postTurn must NOT surface it.
      val commitmentId = createCommitment(student, "SHOULD_NOT_APPEAR_midconvo")
      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("r"), terminal = completedTerminal("r"), onRequest = { captured = it })
      val post = service(provider).postTurn(student, started.convo.id, "second").getOrThrow() as PostTurnResult.Started
      drain(post.reply)

      assertEquals("You are Uni, a warm coach.", captured!!.system)
      assertEquals(CommitmentStatus.OPEN, commitmentStatus(commitmentId))
    }

  @Test
  fun `surfaceCommitments = false leaves the system text unchanged`() =
    runBlocking {
      val student = createStudent()
      val commitmentId = createCommitment(student, "SHOULD_NOT_APPEAR_disabled")

      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"), onRequest = { captured = it })
      val started = noSurfaceService(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      assertEquals("You are Uni, a warm coach.", captured!!.system)
      assertEquals(CommitmentStatus.OPEN, commitmentStatus(commitmentId))
    }

  // ===========================================================================
  // Pull delivery (RFC 98): fit-lens next-session opener
  // ===========================================================================

  @Test
  fun `startConvo composes an open fit suggestion into the system text and marks it surfaced on success`() =
    runBlocking {
      val student = createStudent()
      val college = createCollege(name = "MARKER_Reed_College")
      val suggestionId = createFitSuggestion(student, college, "small liberal-arts fit for your writing interest")

      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"), onRequest = { captured = it })
      val started = service(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
      val events = drain(started.reply)
      assertTrue(terminalOf(events) is ReplyEvent.Completed)

      val systemText = captured!!.system!!
      assertTrue(systemText.contains("I found a school you'd love: MARKER_Reed_College"), "the fit line must be in the system text")
      assertTrue(systemText.contains("small liberal-arts fit"), "the rationale must be composed in")
      assertTrue(systemText.contains("You are Uni"), "the base prompt must still be present")

      // A successful first turn marks it surfaced against this convo.
      assertEquals(FitSuggestionStatus.SURFACED, fitSuggestionStatus(suggestionId))
      assertEquals(started.convo.id, fitSuggestionConvo(suggestionId))
    }

  @Test
  fun `a failed first turn leaves the fit suggestion open`() =
    runBlocking {
      val student = createStudent()
      val college = createCollege()
      val suggestionId = createFitSuggestion(student, college, "grounded pitch")

      val failing = ScriptedProvider(deltas = listOf("x"), terminal = transient())
      val started = service(failing).startConvo(student, "doomed first", null).getOrThrow() as StartConvoResult.Started
      assertTrue(terminalOf(drain(started.reply)) is ReplyEvent.Failed)

      // The convo was soft-deleted; the suggestion stays open to re-surface next session.
      assertEquals(FitSuggestionStatus.OPEN, fitSuggestionStatus(suggestionId))
      assertNull(fitSuggestionConvo(suggestionId))
    }

  @Test
  fun `surfaceFitSuggestions = false leaves the system text unchanged and the commitment opener unaffected`() =
    runBlocking {
      val student = createStudent()
      val college = createCollege()
      val suggestionId = createFitSuggestion(student, college, "SHOULD_NOT_APPEAR_disabled")
      // An open explicit commitment must still be surfaced (the two gates are independent).
      val commitmentId = createCommitment(student, "COMMITMENT_still_surfaced")

      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"), onRequest = { captured = it })
      val started = noSurfaceFitService(provider).startConvo(student, "hello", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      val systemText = captured!!.system!!
      assertFalse(systemText.contains("SHOULD_NOT_APPEAR_disabled"), "the fit suggestion must not be surfaced when the gate is off")
      assertFalse(systemText.contains("I found a school"), "no fit line when the gate is off")
      assertTrue(systemText.contains("COMMITMENT_still_surfaced"), "the commitment opener is unaffected by the fit gate")

      assertEquals(FitSuggestionStatus.OPEN, fitSuggestionStatus(suggestionId))
      assertEquals(CommitmentStatus.FULFILLED, commitmentStatus(commitmentId))
    }

  @Test
  fun `postTurn never surfaces fit suggestions`() =
    runBlocking {
      val student = createStudent()
      val started = service().startConvo(student, "first", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      val college = createCollege()
      val suggestionId = createFitSuggestion(student, college, "SHOULD_NOT_APPEAR_midconvo")
      var captured: ChatRequest? = null
      val provider =
        ScriptedProvider(deltas = listOf("r"), terminal = completedTerminal("r"), onRequest = { captured = it })
      val post = service(provider).postTurn(student, started.convo.id, "second").getOrThrow() as PostTurnResult.Started
      drain(post.reply)

      assertEquals("You are Uni, a warm coach.", captured!!.system)
      assertEquals(FitSuggestionStatus.OPEN, fitSuggestionStatus(suggestionId))
    }

  // ===========================================================================
  // Tool-use loop (RFC 94)
  // ===========================================================================

  @Test
  fun `loop happy path dispatches a tool then produces the final answer`() =
    runBlocking {
      val student = createStudent()
      val tool = SpyTool("search_colleges", result = json("""{"colleges":[],"count":0}""") as JsonObject)
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("search_colleges" to """{"states":["CA"]}"""), completedTerminal("here you go")),
        )
      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "colleges in CA?", null).getOrThrow() as StartConvoResult.Started
      val events = drain(started.reply)
      val terminal = terminalOf(events)
      assertTrue(terminal is ReplyEvent.Completed, "got $terminal")

      val convoId = started.convo.id
      // Two request rows: user then tool_result; two responses: tool_use then end_turn.
      assertEquals(listOf("user", "tool_result"), requestKinds(convoId))
      assertEquals(listOf("tool_use", "end_turn"), responseRows(convoId).map { it.first })
      // Both rows of the excursion share one turn_id (the opener's, threaded onto
      // the continuation, never re-minted).
      val ids = turnIds(convoId)
      assertEquals(2, ids.size)
      assertEquals(ids[0], ids[1])
      assertEquals(json("""{"states":["CA"]}""") as JsonObject, tool.inputs.single())
      assertEquals("here you go", ConvoContent.renderText(terminal.content))
    }

  // ---------------------------------------------------------------------------
  // Mid-tool-loop cancellation (RFC 106).
  //
  // A continuation's llm_requests row is committed by openContinuation (via
  // recordStreaming) before its cold events flow is collected. A client disconnect
  // can land in two sub-cases:
  //
  //   * Case A — disconnect WHILE the continuation's flow is being collected: the
  //     in-flight provider stream cancels cooperatively and LlmCallLog.recordStreaming's
  //     OWN catch writes the cancelled response row (under NonCancellable). The
  //     `... (Case A)` test below exercises this: the continuation flow reaches
  //     awaitCancellation() — i.e. its body IS running under collection — when the
  //     collector is cancelled.
  //
  //   * Case B — disconnect in the openContinuation -> collectCall GAP, after
  //     recordStreaming commits the continuation's llm_requests row but before its
  //     cold flow is ever collected. The row would dangle unless the opener/
  //     continuation guard writes its cancelled response. This window is now
  //     covered deterministically by the two `Case B - ...` tests above, which use
  //     the GatedDispatcher to park the continuation's / opener's appendRequestRow
  //     transaction and cancel the collector while it is parked — reproducing the
  //     exact gap. The continuation Case B test FAILS if the openContinuation guard
  //     is removed (the continuation request is left with no llm_responses row),
  //     which is the red -> green proof that the guard closes the orphan window.
  //     The idempotency of writeCancelledIfAbsent (so Case A's already-written row
  //     is never doubled) is additionally covered by LlmCallLogTest.
  // ---------------------------------------------------------------------------

  /**
   * A ChatTool that, on execute, arms [gate] so the CoachingService transaction
   * dispatched *next* — the continuation's `appendRequestRow` — is parked, then
   * awaits [dispatched] before returning. Executing the tool is the last step
   * before `openContinuation`, so arming here targets exactly the continuation's
   * request-row write (its `llm_requests` row is committed by `recordStreaming` on
   * the ungated DB just before).
   */
  private class GateArmingTool(
    override val name: String,
    private val gate: GatedDispatcher,
  ) : ed.unicoach.chat.ChatTool {
    override val definition = buildJsonObject { put("name", name) }

    override suspend fun execute(input: JsonObject): JsonObject {
      // Executing the tool is the last step before openContinuation, whose first
      // gated transaction (after recordStreaming commits the continuation's
      // llm_requests row on the ungated DB) is appendRequestRow. Park that one.
      gate.arm(0)
      return buildJsonObject { put("count", 0) }
    }
  }

  @Test
  fun `Case B - cancelling in the openContinuation gap writes the continuation's cancelled row (fails without the guard)`() =
    runBlocking {
      // Case B, deterministic: a disconnect lands in the openContinuation ->
      // collectCall GAP — after recordStreaming commits the continuation's
      // llm_requests row but before its cold events flow is ever collected. The
      // GatedDispatcher parks the continuation's appendRequestRow transaction so the
      // collector can be cancelled while parked in exactly that window; the
      // openContinuation guard (FIX 1) must write the continuation's cancelled row.
      // Without that guard the continuation's request dangles with no llm_responses
      // row — the orphan this test proves is closed.
      val student = createStudent()
      val gate = GatedDispatcher()
      // Call 0 returns tool_use; the tool arms the gate; the continuation (call 1)
      // is never collected because the collector is cancelled in the parked window.
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("search_colleges" to "{}"), completedTerminal("unreached")),
        )
      val started =
        gatedServiceWith(provider, registry(GateArmingTool("search_colleges", gate)), gate)
          .startConvo(student, "colleges?", null)
          .getOrThrow() as StartConvoResult.Started
      val convoId = started.convo.id

      val collector = launch { started.reply.collect { } }

      // Wait until the continuation's appendRequestRow transaction is parked (its
      // llm_requests row is already committed), then cancel the collector so the
      // continuation's cold flow is never collected.
      gate.parked.await()
      collector.cancel()
      // Let the parked transaction resume; on resume withContext observes the
      // cancellation and the openContinuation guard runs.
      gate.release.complete(Unit)
      collector.join()

      // Two llm_requests rows were committed: the opener and the continuation. The
      // continuation's appendRequestRow (convo_requests) transaction was parked and
      // rolled back on cancellation, so the continuation llm_requests row is NOT
      // reachable through convo_requests — it is exactly the orphan the guard must
      // still give a response row. Assert on the generic call log directly.
      assertEquals(2, countAllLlmRequests(), "opener + continuation llm_requests rows both committed")
      // Every llm_requests row has exactly one llm_responses row: the opener's
      // tool_use completion + the continuation's guard-written cancelled row.
      assertEquals(2, countAllLlmResponses(), "every llm_requests row has exactly one llm_responses row — no orphan")
      assertEquals(
        listOf("completed", "cancelled"),
        allResponseOutcomes(),
        "opener completed (tool_use); the orphaned continuation got its cancelled row from the guard",
      )
      // The convo itself shows only the opener request row (the continuation's
      // convo_requests row rolled back with its parked transaction).
      assertEquals(listOf("user"), requestKinds(convoId), "only the opener convo_requests row survives the cancelled continuation")
    }

  @Test
  fun `Case B - cancelling in the openUserTurn gap writes the opener's cancelled row`() =
    runBlocking {
      // The openUserTurn analogue: the opener's llm_requests row is committed by
      // recordStreaming, then the gate parks its appendRequestRow (here the
      // nextTurnId transaction is the first gated dispatch after arming). Cancelling
      // in that window must leave the opener's request with exactly one cancelled
      // response row (openUserTurn's guard, FIX 1), not a dangling request.
      val student = createStudent()
      val gate = GatedDispatcher()
      // Arm the gate at the moment startConvo hands back the reply flow, so the very
      // first gated CoachingService transaction inside openUserTurn (nextTurnId /
      // appendRequestRow) is parked.
      val provider = ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"))
      val svc = gatedServiceWith(provider, registry(), gate)

      // openUserTurn runs inside startConvo. The gated CoachingService transactions
      // are, in order: (1) startConvo's prep block, (2) openUserTurn's nextTurnId —
      // the first gated transaction AFTER recordStreaming commits the opener's
      // llm_requests row. Park that second one so the opener's request row is
      // already committed but its cold flow is never collected.
      gate.arm(1)
      val opener =
        launch {
          runCatching { svc.startConvo(student, "colleges?", null) }
        }

      gate.parked.await()
      opener.cancel()
      gate.release.complete(Unit)
      opener.join()

      // Exactly one opener llm_requests row, with exactly one cancelled response.
      assertEquals(1, countAllLlmRequests(), "the opener llm_requests row is committed")
      assertEquals(1, countAllLlmResponses(), "the opener's request has exactly one llm_responses row")
      assertEquals(listOf("cancelled"), allResponseOutcomes(), "the opener's sole response is cancelled")
    }

  // ---------------------------------------------------------------------------
  // Defect path (RFC 106): a NON-cancellation exception in the post-commit
  // convo_requests write. openUserTurn / openContinuation each committed their
  // llm_requests row via recordStreaming; if the following appendRequestRow (or
  // nextTurnId) transaction throws a plain exception — e.g. a transient DB error —
  // the cold events flow is never collected, so the just-committed request would
  // dangle unless the opener's catch(defect) writes its internal_error response.
  //
  // Deterministic seam (throw-mode of the Case B gate): the gate parks the target
  // transaction AFTER recordStreaming committed the llm_requests row on the ungated
  // LlmCallLog DB but BEFORE the gated DB's connection is acquired; the test closes
  // the gated Database while parked, so on release the connection acquisition throws
  // a non-cancellation SQLException — the transient DB failure this path guards.
  // ---------------------------------------------------------------------------

  @Test
  fun `a defect in the openContinuation gap writes the continuation's internal_error row (fails without the guard)`() =
    runBlocking {
      // A non-cancellation exception lands in the openContinuation -> collectCall
      // gap. GatedDispatcher parks the continuation's appendRequestRow transaction
      // (its llm_requests row is already committed on the ungated DB); closing the
      // gated Database while parked makes the resumed transaction throw a plain
      // SQLException. openContinuation's catch(defect) (ITEM 2) must write the
      // continuation's internal_error row. Without that guard the continuation's
      // request dangles with no llm_responses row — the orphan this test proves closed.
      val student = createStudent()
      val gate = GatedDispatcher()
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("search_colleges" to "{}"), completedTerminal("unreached")),
        )
      val (svc, gatedDb) = gatedServiceAndDb(provider, registry(GateArmingTool("search_colleges", gate)), gate)
      val started = svc.startConvo(student, "colleges?", null).getOrThrow() as StartConvoResult.Started
      val convoId = started.convo.id

      // The reply flow drives the loop: the opener completes with tool_use, the tool
      // arms the gate, and the continuation's appendRequestRow is parked. The defect
      // surfaces to buildReplyFlow's outer catch(defect) as a failedEvent, so the
      // collector completes normally (no cancellation).
      val collector = launch { started.reply.collect { } }

      // Wait until the continuation's appendRequestRow transaction is parked (its
      // llm_requests row is already committed), then close the gated Database so its
      // connection acquisition throws on resume.
      gate.parked.await()
      gatedDb.close()
      gate.release.complete(Unit)
      collector.join()

      // Two llm_requests rows were committed: the opener and the continuation. The
      // continuation's appendRequestRow threw and rolled back, so the continuation
      // llm_requests row is NOT reachable through convo_requests — exactly the orphan
      // the guard must still give a response row. Assert on the generic call log.
      assertEquals(2, countAllLlmRequests(), "opener + continuation llm_requests rows both committed")
      assertEquals(2, countAllLlmResponses(), "every llm_requests row has exactly one llm_responses row — no orphan")
      assertEquals(
        listOf("completed", "internal_error"),
        allResponseOutcomes(),
        "opener completed (tool_use); the orphaned continuation got its internal_error row from the guard",
      )
      // Only the opener request row survives (the continuation's convo_requests row
      // rolled back with its failed transaction).
      assertEquals(listOf("user"), requestKinds(convoId), "only the opener convo_requests row survives the failed continuation")
    }

  @Test
  fun `a defect in the openUserTurn gap writes the opener's internal_error row`() =
    runBlocking {
      // The openUserTurn analogue: the opener's llm_requests row is committed by
      // recordStreaming, then the gate parks its nextTurnId transaction (the first
      // gated dispatch after arming). Closing the gated Database while parked makes
      // the resumed transaction throw a plain SQLException; openUserTurn's
      // catch(defect) (ITEM 2) must leave the opener's request with exactly one
      // internal_error response row, not a dangling request.
      val student = createStudent()
      val gate = GatedDispatcher()
      val provider = ScriptedProvider(deltas = listOf("hi"), terminal = completedTerminal("hi"))
      val (svc, gatedDb) = gatedServiceAndDb(provider, registry(), gate)

      // The gated CoachingService transactions in order: (1) startConvo's prep block,
      // (2) openUserTurn's nextTurnId — the first gated transaction AFTER recordStreaming
      // commits the opener's llm_requests row. Park that second one.
      gate.arm(1)
      val opener =
        launch {
          runCatching { svc.startConvo(student, "colleges?", null) }
        }

      gate.parked.await()
      gatedDb.close()
      gate.release.complete(Unit)
      opener.join()

      // Exactly one opener llm_requests row, with exactly one internal_error response.
      assertEquals(1, countAllLlmRequests(), "the opener llm_requests row is committed")
      assertEquals(1, countAllLlmResponses(), "the opener's request has exactly one llm_responses row")
      assertEquals(listOf("internal_error"), allResponseOutcomes(), "the opener's sole response is internal_error")
    }

  @Test
  fun `cancelling a tool continuation writes exactly one cancelled row for its request (Case A)`() =
    runBlocking {
      // Case A: call 0 returns a tool_use terminal, so a continuation is opened via
      // openContinuation — committing the continuation's llm_requests row. The
      // continuation's flow is then collected and reaches awaitCancellation(); the
      // client disconnects WHILE it is outstanding, so recordStreaming's own catch
      // writes the cancelled row. The continuation's request must not dangle: it
      // gets exactly one cancelled llm_responses row.
      val student = createStudent()
      val tool = SpyTool("search_colleges", result = json("""{"count":0}""") as JsonObject)
      // Call 1 (the continuation) hangs after signalling it has been reached, so
      // the collector can be cancelled while the continuation is outstanding.
      val continuationReached = CompletableDeferred<Unit>()
      val provider =
        object : ChatProvider {
          override val id = "log"
          private var call = 0

          override fun stream(request: ChatRequest): Flow<ChatEvent> =
            flow {
              val n = call++
              if (n == 0) {
                emit(toolUseTerminal("search_colleges" to "{}"))
              } else {
                // Continuation call: signal, then hang until cancelled.
                continuationReached.complete(Unit)
                awaitCancellation()
              }
            }
        }

      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "colleges?", null).getOrThrow() as StartConvoResult.Started
      val convoId = started.convo.id

      val collector =
        launch {
          started.reply.collect { }
        }
      continuationReached.await()
      collector.cancel()
      collector.join()

      // Two request rows (user opener + tool_result continuation), and every
      // request has exactly one response — no dangling continuation request.
      assertEquals(listOf("user", "tool_result"), requestKinds(convoId))
      val outcomes = responseRows(convoId).map { it.first }
      assertEquals(2, outcomes.size, "one response per request, no dangling request")
      // The opener completed with tool_use; the continuation was cancelled.
      assertEquals(listOf("tool_use", "cancelled"), outcomes)
      assertEquals(2, countResponses(convoId), "exactly one llm_responses row per convo_requests row")
    }

  @Test
  fun `a two-round excursion stamps every request row with one shared turn_id, distinct from the next turn`() =
    runBlocking {
      val student = createStudent()
      val tool = SpyTool("search_colleges", result = json("""{"count":0}""") as JsonObject)
      // Round 1: tool_use; round 2 (continuation): tool_use again; round 3: final answer.
      val provider =
        SequencedProvider(
          terminals =
            listOf(
              toolUseTerminal("search_colleges" to "{}"),
              toolUseTerminal("search_colleges" to "{}"),
              completedTerminal("all done"),
            ),
        )
      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "colleges?", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      val convoId = started.convo.id
      // Three request rows (user + two tool_result), all sharing one turn_id.
      assertEquals(listOf("user", "tool_result", "tool_result"), requestKinds(convoId))
      val ids = turnIds(convoId)
      assertEquals(3, ids.size)
      assertEquals(1, ids.toSet().size)

      // A subsequent plain turn on the same convo gets a distinct turn_id.
      drain((serviceWith(provider, registry(tool)).postTurn(student, convoId, "thanks").getOrThrow() as PostTurnResult.Started).reply)
      val afterFollowUp = turnIds(convoId)
      assertTrue(afterFollowUp.last() != ids.first(), "follow-up turn must have a distinct turn_id, got $afterFollowUp")
    }

  @Test
  fun `per-call token recording bills the continuation call`() =
    runBlocking {
      val student = createStudent()
      val tool = SpyTool("search_colleges", result = json("""{"count":0}""") as JsonObject)
      val provider =
        SequencedProvider(terminals = listOf(toolUseTerminal("search_colleges" to "{}"), completedTerminal("done")))
      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "hi", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      val rows = responseRows(started.convo.id)
      // Both calls carry their scripted output_tokens (11 for the tool_use call, 5 for end_turn).
      assertEquals(listOf(11, 5), rows.map { it.second })
    }

  @Test
  fun `parallel tool_use dispatches all and answers with matched ids`() =
    runBlocking {
      val student = createStudent()
      val tool = SpyTool("search_colleges", result = json("""{"count":1}""") as JsonObject)
      var continuationRequest: ChatRequest? = null
      val provider =
        SequencedProvider(
          terminals =
            listOf(
              toolUseTerminal("search_colleges" to """{"states":["CA"]}""", "search_colleges" to """{"states":["NY"]}"""),
              completedTerminal("both done"),
            ),
          onRequest = { if (it.messages.any { m -> m.role == ChatRole.ASSISTANT }) continuationRequest = it },
        )
      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "compare", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      assertEquals(2, tool.inputs.size)
      // The continuation's last message is a user tool_result carrying two blocks, ids matched in order.
      val toolResult = continuationRequest!!.messages.last()
      assertEquals(ChatRole.USER, toolResult.role)
      val blocks = toolResult.content.jsonArray
      assertEquals(2, blocks.size)
      assertEquals(
        "toolu_0",
        blocks[0]
          .jsonObject
          .getValue("tool_use_id")
          .jsonPrimitive.content,
      )
      assertEquals(
        "toolu_1",
        blocks[1]
          .jsonObject
          .getValue("tool_use_id")
          .jsonPrimitive.content,
      )
      assertTrue(
        blocks.all {
          it.jsonObject
            .getValue("type")
            .jsonPrimitive.content == "tool_result"
        },
      )
    }

  @Test
  fun `unknown tool name yields an is_error result and the loop still finishes`() =
    runBlocking {
      val student = createStudent()
      var continuation: ChatRequest? = null
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("does_not_exist" to "{}"), completedTerminal("recovered")),
          onRequest = { if (it.messages.any { m -> m.role == ChatRole.ASSISTANT }) continuation = it },
        )
      // Registry has a different tool, so does_not_exist is a hallucination.
      val started =
        serviceWith(provider, registry(SpyTool("search_colleges"))).startConvo(student, "x", null).getOrThrow() as StartConvoResult.Started
      val terminal = terminalOf(drain(started.reply))
      assertTrue(terminal is ReplyEvent.Completed)

      val block =
        continuation!!
          .messages
          .last()
          .content.jsonArray
          .single()
          .jsonObject
      assertEquals(
        true,
        block
          .getValue("is_error")
          .jsonPrimitive.content
          .toBoolean(),
      )
    }

  @Test
  fun `throwing tool yields an is_error result and the loop still finishes`() =
    runBlocking {
      val student = createStudent()
      var continuation: ChatRequest? = null
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("search_colleges" to "{}"), completedTerminal("ok")),
          onRequest = { if (it.messages.any { m -> m.role == ChatRole.ASSISTANT }) continuation = it },
        )
      val started =
        serviceWith(provider, registry(SpyTool("search_colleges", throwOnExecute = true)))
          .startConvo(student, "x", null)
          .getOrThrow() as StartConvoResult.Started
      assertTrue(terminalOf(drain(started.reply)) is ReplyEvent.Completed)

      val block =
        continuation!!
          .messages
          .last()
          .content.jsonArray
          .single()
          .jsonObject
      assertEquals(
        true,
        block
          .getValue("is_error")
          .jsonPrimitive.content
          .toBoolean(),
      )
    }

  @Test
  fun `a tool domain error is a normal tool_result, not is_error`() =
    runBlocking {
      val student = createStudent()
      var continuation: ChatRequest? = null
      val tool = SpyTool("search_colleges", result = json("""{"error":"bad cip"}""") as JsonObject)
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("search_colleges" to "{}"), completedTerminal("adapted")),
          onRequest = { if (it.messages.any { m -> m.role == ChatRole.ASSISTANT }) continuation = it },
        )
      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "x", null).getOrThrow() as StartConvoResult.Started
      assertTrue(terminalOf(drain(started.reply)) is ReplyEvent.Completed)

      val block =
        continuation!!
          .messages
          .last()
          .content.jsonArray
          .single()
          .jsonObject
      // No is_error key: the model reads the tool's own {"error":...} as a normal result.
      assertTrue(block["is_error"] == null, "domain error must not set is_error")
    }

  @Test
  fun `round cap forces a final no-tools call`() =
    runBlocking {
      val student = createStudent()
      val cappedConfig =
        CoachingConfig
          .from(
            com.typesafe.config.ConfigFactory.parseString(
              """coaching { model="claude-sonnet-4-6", maxTokens=10, systemPromptName="coach", systemPromptVersion="v1", maxToolRounds=2 }""",
            ),
          ).getOrThrow()
      var forcedRequest: ChatRequest? = null
      // Always returns tool_use except record the final (no-tools) request.
      val provider =
        SequencedProvider(
          terminals =
            listOf(
              toolUseTerminal("search_colleges" to "{}"),
              toolUseTerminal("search_colleges" to "{}"),
              completedTerminal("forced answer"),
            ),
          onRequest = { if (it.tools.isEmpty() && it.messages.size > 1) forcedRequest = it },
        )
      val started =
        serviceWith(provider, registry(SpyTool("search_colleges")), cappedConfig)
          .startConvo(student, "loop", null)
          .getOrThrow() as StartConvoResult.Started
      assertTrue(terminalOf(drain(started.reply)) is ReplyEvent.Completed)

      // cap + 1 = 3 request rows (user + 2 tool_result), all responses recorded.
      assertEquals(3, requestKinds(started.convo.id).size)
      assertEquals(3, responseRows(started.convo.id).size)
      assertNotNull(forcedRequest, "a forced no-tools call must be made at the cap")
    }

  @Test
  fun `continuation failure deletes the convo on the first turn`() =
    runBlocking {
      val student = createStudent()
      val provider =
        SequencedProvider(terminals = listOf(toolUseTerminal("search_colleges" to "{}"), transient()))
      val started =
        serviceWith(provider, registry(SpyTool("search_colleges"))).startConvo(student, "x", null).getOrThrow() as StartConvoResult.Started
      val terminal = terminalOf(drain(started.reply))
      assertTrue(terminal is ReplyEvent.Failed)

      // Both calls recorded (tool_use then error), then the first-turn convo is soft-deleted.
      assertTrue(service().getConvo(student, started.convo.id).getOrThrow() is GetConvoResult.NotFound)
      val deleted = ConvosDao.findById(sqlSession, started.convo.id, SoftDeleteScope.DELETED).getOrThrow()
      assertNotNull(deleted.deletedAt)
    }

  @Test
  fun `continuation failure on a later turn leaves the convo invisible`() =
    runBlocking {
      val student = createStudent()
      // First turn succeeds (seed a visible exchange).
      val seed = service().startConvo(student, "seed ok", null).getOrThrow() as StartConvoResult.Started
      drain(seed.reply)
      val convoId = seed.convo.id

      val provider = SequencedProvider(terminals = listOf(toolUseTerminal("search_colleges" to "{}"), transient()))
      val post =
        serviceWith(
          provider,
          registry(SpyTool("search_colleges")),
        ).postTurn(student, convoId, "doomed").getOrThrow() as PostTurnResult.Started
      assertTrue(terminalOf(drain(post.reply)) is ReplyEvent.Failed)

      // Convo still exists; only the seed exchange is visible.
      assertTrue(service().getConvo(student, convoId).getOrThrow() is GetConvoResult.Found)
      val visible = service().listTurns(student, convoId).getOrThrow() as ListTurnsResult.Found
      assertEquals(1, visible.exchanges.size)
    }

  @Test
  fun `replay after an excursion omits tool plumbing`() =
    runBlocking {
      val student = createStudent()
      val tool = SpyTool("search_colleges", result = json("""{"count":0}""") as JsonObject)
      val provider =
        SequencedProvider(terminals = listOf(toolUseTerminal("search_colleges" to "{}"), completedTerminal("final coach text")))
      val started =
        serviceWith(provider, registry(tool)).startConvo(student, "user question", null).getOrThrow() as StartConvoResult.Started
      drain(started.reply)

      // Next turn's replay collapses the excursion to [USER text, ASSISTANT final].
      var replay: ChatRequest? = null
      val next = ScriptedProvider(deltas = listOf("r"), terminal = completedTerminal("r"), onRequest = { replay = it })
      val post = service(next).postTurn(student, started.convo.id, "next").getOrThrow() as PostTurnResult.Started
      drain(post.reply)

      val messages = replay!!.messages
      assertEquals(3, messages.size)
      assertEquals("user question", messageText(messages[0]))
      assertEquals("final coach text", messageText(messages[1]))
      assertEquals("next", messageText(messages[2]))
      // No empty user message, no tool_use content leaked into replay.
      assertTrue(messages.none { messageText(it).isBlank() })
    }

  @Test
  fun `live college-search round-trip dispatches the real tool and produces a final answer`() =
    runBlocking {
      val student = createStudent()
      // The real adapter over the real search tool/service (empty DB -> count 0,
      // a valid domain outcome — the wiring, not the data, is under test).
      val realTool = CollegeChatTool(ed.unicoach.college.CollegeSearchTool(ed.unicoach.college.CollegeSearchService(database)))
      var continuation: ChatRequest? = null
      val provider =
        SequencedProvider(
          terminals = listOf(toolUseTerminal("search_colleges" to """{"cipPrefix":"26"}"""), completedTerminal("no biology matches yet")),
          onRequest = { if (it.messages.any { m -> m.role == ChatRole.ASSISTANT }) continuation = it },
        )
      val started =
        serviceWith(provider, registry(realTool)).startConvo(student, "biology colleges?", null).getOrThrow() as StartConvoResult.Started
      val terminal = terminalOf(drain(started.reply))
      assertTrue(terminal is ReplyEvent.Completed)
      assertEquals("no biology matches yet", ConvoContent.renderText(terminal.content))

      // The continuation's tool_result carries the real serialized search result JSON.
      val resultText =
        continuation!!
          .messages
          .last()
          .content.jsonArray
          .single()
          .jsonObject
          .getValue("content")
          .jsonPrimitive.content
      assertTrue(resultText.contains("\"count\":0"), "expected real result JSON, got: $resultText")
    }
}
