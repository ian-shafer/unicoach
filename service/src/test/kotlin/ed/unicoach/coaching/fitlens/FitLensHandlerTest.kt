package ed.unicoach.coaching.fitlens

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.chat.ChatResponse
import ed.unicoach.chat.TokenUsage
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitLensHandlerTest {
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
        "TRUNCATE TABLE fit_suggestions, fit_lens_runs, claims, college_list_entries, colleges, " +
          "system_prompts, students, users CASCADE",
      )
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('fit_lens_query', 'v1', 'formulate')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('fit_lens_reason', 'v1', 'reason')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('fit_lens_query', 'v2', 'call record_college_query')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('fit_lens_reason', 'v2', 'call record_fit_reason')")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val config =
    FitLensConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  private fun serviceWith(provider: ChatProvider): FitLensService =
    FitLensService(database, provider, CollegeSearchService(database), config)

  private var unitIdCounter = 500000

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'flh-$userId@test.com', 'FLH', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createClaims(
    studentId: StudentId,
    n: Int,
  ) {
    repeat(n) {
      ClaimsDao
        .create(
          sqlSession,
          NewClaim(
            studentId,
            ClaimOrigin.STUDENT_STATED,
            ClaimKind.GOAL,
            ClaimSubject.STUDENT,
            ClaimTopic.ACADEMICS,
            ClaimVisibility.STUDENT_VISIBLE,
            "claim $it",
          ),
        ).getOrThrow()
    }
  }

  private fun createCollege() {
    CollegesDao
      .upsert(
        sqlSession,
        NewCollege(
          unitId = unitIdCounter++,
          opeid = null,
          name = "Test College",
          city = "Townsville",
          state = "CA",
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = 1,
          undergradEnrollment = 5000,
          admissionRate = 0.5,
          satAvg = 1200,
          costAttendance = 40000,
          netPrice = 20000,
          tuitionInState = 12000,
          tuitionOutState = 30000,
          graduationRate = 0.7,
          medianEarnings = 55000,
          pctPell = 0.4,
          website = null,
        ),
      ).getOrThrow()
  }

  /**
   * Returns [doc] parsed into a forced tool_use block's `input` as a single
   * Completed terminal on every call (RFC 104). [doc] must be a valid JSON object.
   */
  private class DocProvider(
    private val doc: String,
  ) : ChatProvider {
    override val id = "log"

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        val content =
          JsonArray(
            listOf(
              buildJsonObject {
                put("type", "tool_use")
                put("id", "toolu_x")
                put("name", "record_fit_lens")
                put(
                  "input",
                  kotlinx.serialization.json.Json
                    .parseToJsonElement(doc) as kotlinx.serialization.json.JsonObject,
                )
              },
            ),
          )
        emit(
          ChatEvent.Completed(
            response =
              ChatResponse(
                content = content,
                modelResolved = "claude-sonnet-4-6",
                stopReason = "tool_use",
                usage = TokenUsage(10, 5, 0, 0),
                providerRequestId = "req",
              ),
            rawPayload = content,
          ),
        )
      }
  }

  /** Returns a Completed terminal with a text-only block (no tool_use): NoToolUse. */
  private class NoToolUseProvider : ChatProvider {
    override val id = "log"

    override fun stream(request: ChatRequest): Flow<ChatEvent> =
      flow {
        val content =
          JsonArray(
            listOf(
              buildJsonObject {
                put("type", "text")
                put("text", "I could not do that.")
              },
            ),
          )
        emit(
          ChatEvent.Completed(
            response =
              ChatResponse(
                content = content,
                modelResolved = "claude-sonnet-4-6",
                stopReason = "tool_use",
                usage = TokenUsage(10, 5, 0, 0),
                providerRequestId = "req",
              ),
            rawPayload = content,
          ),
        )
      }
  }

  /** Always rejects — drives the service to a transient failure on call #1. */
  private class RejectingProvider : ChatProvider {
    override val id = "log"

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Rejected("nope", null, null)) }
  }

  @Test
  fun `valid payload dispatches to discover and returns Success on a skip`() =
    runBlocking {
      // Too few claims -> a pre-LLM Skipped -> Success. Proves the service really runs.
      val student = createStudent()
      createClaims(student, 1)
      val handler = FitLensHandler(serviceWith(DocProvider("""{}""")))
      val result = handler.execute(buildJsonObject { put("studentId", student.asString) })
      assertEquals(JobResult.Success, result)
    }

  @Test
  fun `malformed payload returns PermanentFailure`() =
    runBlocking {
      val handler = FitLensHandler(serviceWith(RejectingProvider()))
      val result = handler.execute(buildJsonObject { put("nonsense", true) })
      assertTrue(result is JobResult.PermanentFailure, "got $result")
    }

  @Test
  fun `non-uuid studentId returns PermanentFailure`() =
    runBlocking {
      val handler = FitLensHandler(serviceWith(RejectingProvider()))
      val result = handler.execute(buildJsonObject { put("studentId", "not-a-uuid") })
      assertTrue(result is JobResult.PermanentFailure, "got $result")
    }

  @Test
  fun `a Failed pass with unusable model output is dead-lettered as Success`() =
    runBlocking {
      // Enough claims + a college so the pass runs; call #1 returns no tool_use
      // block -> Failed (dead-lettered).
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      val handler = FitLensHandler(serviceWith(NoToolUseProvider()))
      val result = handler.execute(buildJsonObject { put("studentId", student.asString) })
      assertTrue(result is JobResult.Success, "A Failed pass is dead-lettered (no retry) as Success, got: $result")
    }

  @Test
  fun `a transient service error returns RetriableFailure`() =
    runBlocking {
      val student = createStudent()
      createClaims(student, 3)
      createCollege()
      val handler = FitLensHandler(serviceWith(RejectingProvider()))
      val result = handler.execute(buildJsonObject { put("studentId", student.asString) })
      assertTrue(result is JobResult.RetriableFailure, "got $result")
    }

  @Test
  fun `config advertises FIT_LENS, maxAttempts 3, and executionTimeout under lockDuration`() {
    val handler = FitLensHandler(serviceWith(RejectingProvider()))
    assertEquals(JobType.FIT_LENS, handler.jobType)
    assertEquals(3, handler.config.maxAttempts, "maxAttempts must be 3")
    assertTrue(
      handler.config.executionTimeout < handler.config.lockDuration,
      "executionTimeout ${handler.config.executionTimeout} must be < lockDuration ${handler.config.lockDuration}",
    )
  }
}
