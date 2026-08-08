package ed.unicoach.coaching.synthesis

import ed.unicoach.chat.ChatEvent
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.ChatRequest
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.budget.exhaustedBudgetService
import ed.unicoach.coaching.budget.generousBudgetService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.ClaimsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.ClaimKind
import ed.unicoach.db.models.ClaimOrigin
import ed.unicoach.db.models.ClaimSubject
import ed.unicoach.db.models.ClaimTopic
import ed.unicoach.db.models.ClaimVisibility
import ed.unicoach.db.models.NewClaim
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
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

class SynthesisHandlerTest {
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
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "college_list_entries, colleges, convos, convo_requests, llm_requests, llm_responses, llm_responses_raw, " +
          "system_prompts, students, users CASCADE",
      )
      // Restore all migration-seeded prompts for cross-module suites on the shared DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v2', 'call the record_synthesis tool')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val config =
    SynthesisConfig
      .from(
        ed.unicoach.common.config.AppConfig
          .load("service.conf")
          .getOrThrow(),
      ).getOrThrow()

  /** These cases are about the handler's payload/result mapping, not the gate. */
  private val generousBudget = generousBudgetService(database)

  private val exhaustedBudget = exhaustedBudgetService(database)

  private fun serviceWith(
    provider: ChatProvider,
    budget: BudgetService = generousBudget,
  ): SynthesisService = SynthesisService(database, ed.unicoach.coaching.LlmCallLog(provider, database), config, budget)

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sh-$userId@test.com', 'Sh User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createClaim(studentId: StudentId) {
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
          "wants CS",
        ),
      ).getOrThrow()
  }

  /** Always rejects — drives the service to a transient failure. */
  private class RejectingProvider : ChatProvider {
    override val id = "log"

    override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Rejected("nope", null, null)) }
  }

  @Test
  fun `valid payload delegates and returns Success`() =
    runBlocking {
      // A soft-deleted student produces a Success no-op — the service really runs.
      val student = createStudent()
      ed.unicoach.db.dao.StudentsDao
        .delete(sqlSession, student, currentVersion = 1)
        .getOrThrow()
      val handler = SynthesisHandler(serviceWith(RejectingProvider()))
      val payload = buildJsonObject { put("studentId", student.value.toString()) }

      val result = handler.execute(payload)
      assertEquals(JobResult.Success, result)
    }

  @Test
  fun `malformed payload returns PermanentFailure`() =
    runBlocking {
      val handler = SynthesisHandler(serviceWith(RejectingProvider()))
      val result = handler.execute(buildJsonObject { put("nonsense", true) })
      assertTrue(result is JobResult.PermanentFailure, "got $result")
    }

  @Test
  fun `non-uuid studentId returns PermanentFailure`() =
    runBlocking {
      val handler = SynthesisHandler(serviceWith(RejectingProvider()))
      val result = handler.execute(buildJsonObject { put("studentId", "not-a-uuid") })
      assertTrue(result is JobResult.PermanentFailure, "got $result")
    }

  @Test
  fun `transient service error returns RetriableFailure`() =
    runBlocking {
      // A student with a fresh active claim + a rejecting provider → transient.
      val student = createStudent()
      createClaim(student)
      val handler = SynthesisHandler(serviceWith(RejectingProvider()))
      val payload = buildJsonObject { put("studentId", student.value.toString()) }

      val result = handler.execute(payload)
      assertTrue(result is JobResult.RetriableFailure, "got $result")
    }

  @Test
  fun `a budget skip returns Success, so the job neither retries nor dead-letters`() =
    runBlocking {
      // A student with a fresh active claim would otherwise run the pass; the
      // exhausted allowance turns it into a named skip inside the read phase.
      val student = createStudent()
      createClaim(student)
      val handler = SynthesisHandler(serviceWith(RejectingProvider(), budget = exhaustedBudget))
      val payload = buildJsonObject { put("studentId", student.value.toString()) }

      assertEquals(JobResult.Success, handler.execute(payload), "retrying cannot restore an allowance")
    }

  @Test
  fun `config advertises SYNTHESIZE_STUDENT and executionTimeout is less than lockDuration`() {
    val handler = SynthesisHandler(serviceWith(RejectingProvider()))
    assertEquals(JobType.SYNTHESIZE_STUDENT, handler.jobType)
    assertTrue(
      handler.config.executionTimeout < handler.config.lockDuration,
      "executionTimeout ${handler.config.executionTimeout} must be < lockDuration ${handler.config.lockDuration}",
    )
  }
}
