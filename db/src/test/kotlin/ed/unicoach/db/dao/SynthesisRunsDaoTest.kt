package ed.unicoach.db.dao

import ed.unicoach.db.models.JsonParseFailureCategory
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewSynthesisRun
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SynthesisOutcome
import ed.unicoach.db.models.SynthesisRunId
import ed.unicoach.db.models.SystemPromptId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynthesisRunsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      // system_prompts is deliberately NOT truncated: it is the migration-seeded,
      // immutable catalog (RFC 33/0007) that every other module's tests on this
      // shared database read. bin/test re-migrates before every run, so it is
      // already complete; wiping it and hand-restoring a stale list left the seeds
      // partial for whoever ran next (RFC 129).
      stmt.execute(
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, llm_requests, llm_responses, llm_responses_raw, students, users CASCADE",
      )
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sr-$userId@test.com', 'Sr User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  // Unique per row rather than a per-instance counter: system_prompts is no
  // longer truncated between tests (see resetDatabase), and JUnit builds a fresh
  // instance per test, so a counter would collide on (name, version).
  private fun createSystemPrompt(): SystemPromptId {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'synthesis', ?, 'reflect')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, "v-$id")
      stmt.executeUpdate()
    }
    return SystemPromptId(id)
  }

  private fun appendLlmRequest(): Long {
    connection
      .prepareStatement(
        "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('anthropic', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getLong("id")
        }
      }
  }

  /** A default `Applied` outcome, for cases that don't assert on the counts. */
  private fun applied(
    written: Int = 0,
    dropped: Int = 0,
  ): SynthesisOutcome.Applied = SynthesisOutcome.Applied(commitmentsWritten = written, commitmentsDropped = dropped)

  /** A default `Failed` outcome, for cases that don't assert on the specific reason. */
  private fun failed(): SynthesisOutcome.Failed = SynthesisOutcome.Failed(JsonParseFailureCategory.MALFORMED_JSON, "test failure")

  private fun run(
    student: StudentId,
    prompt: SystemPromptId,
    outcome: SynthesisOutcome,
  ): NewSynthesisRun =
    NewSynthesisRun(
      studentId = student,
      outcome = outcome,
      systemPromptId = prompt,
      llmRequestId = LlmRequestId(appendLlmRequest()),
    )

  @Test
  fun `lastAppliedAt is null with no runs`() {
    val student = createStudent()
    assertNull(SynthesisRunsDao.lastAppliedAt(session, student).getOrThrow())
  }

  @Test
  fun `lastAppliedAt ignores failed rows and returns MAX over applied`() {
    val student = createStudent()
    val prompt = createSystemPrompt()

    val a1 = SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1))).getOrThrow()
    val a2 = SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1))).getOrThrow()
    // a later failed row must NOT advance the marker.
    SynthesisRunsDao.append(session, run(student, prompt, failed())).getOrThrow()

    val marker = SynthesisRunsDao.lastAppliedAt(session, student).getOrThrow()
    // The marker is the latest applied row's created_at (a2 was appended after a1).
    assertEquals(
      SynthesisRunsDao.findById(session, a2.id).getOrThrow().createdAt,
      marker,
    )
    assertTrue(marker!! >= SynthesisRunsDao.findById(session, a1.id).getOrThrow().createdAt)
  }

  @Test
  fun `append records outcome, counts, provenance, and the llm_request reference`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val llmRequestId = LlmRequestId(appendLlmRequest())

    val appended =
      SynthesisRunsDao
        .append(
          session,
          NewSynthesisRun(
            studentId = student,
            outcome = SynthesisOutcome.Applied(commitmentsWritten = 2, commitmentsDropped = 1),
            systemPromptId = prompt,
            llmRequestId = llmRequestId,
          ),
        ).getOrThrow()

    // The applied round-trip yields an Applied variant carrying the counts and no failure payload.
    assertEquals(SynthesisOutcome.Applied(commitmentsWritten = 2, commitmentsDropped = 1), appended.outcome)
    assertEquals(prompt, appended.systemPromptId)
    assertEquals(llmRequestId, appended.llmRequestId)
  }

  @Test
  fun `each appended run pins its own llm_request reference`() {
    // Token spend moved to llm_responses (RFC 106); the per-student cost ledger is
    // the student_llm_cost spine, covered in StudentLlmCostDaoTest (RFC 108). Here we
    // only sanity-check that each run persists a distinct llm_request pin.
    val student = createStudent()
    val prompt = createSystemPrompt()

    val a = SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1))).getOrThrow()
    val b = SynthesisRunsDao.append(session, run(student, prompt, failed())).getOrThrow()

    assertTrue(a.llmRequestId.value != b.llmRequestId.value, "each run pins its own llm_request")

    connection
      .prepareStatement("SELECT COUNT(*) AS c FROM synthesis_runs WHERE student_id = ?")
      .use { stmt ->
        stmt.setObject(1, student.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(2, rs.getInt("c"))
        }
      }
  }

  @Test
  fun `findById returns the run for a known id and NotFound for an unknown id`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val appended = SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1))).getOrThrow()

    assertEquals(appended.id, SynthesisRunsDao.findById(session, appended.id).getOrThrow().id)

    val miss = SynthesisRunsDao.findById(session, SynthesisRunId(999_999L))
    assertTrue(miss.exceptionOrNull() is NotFoundException, "got ${miss.exceptionOrNull()}")
  }

  @Test
  fun `outcome outside applied,failed is rejected`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'bogus', ?, ?)",
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
            stmt.setLong(3, appendLlmRequest())
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `UPDATE on synthesis_runs raises P0001`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val appended = SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1))).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use { it.execute("UPDATE synthesis_runs SET outcome = 'failed' WHERE id = ${appended.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `DELETE on synthesis_runs raises P0001`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val appended = SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1))).getOrThrow()
    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM synthesis_runs WHERE id = ${appended.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `append persists a failed row's failure_category and failure_reason`() {
    val student = createStudent()
    val prompt = createSystemPrompt()

    val appended =
      SynthesisRunsDao
        .append(
          session,
          NewSynthesisRun(
            studentId = student,
            outcome =
              SynthesisOutcome.Failed(
                category = JsonParseFailureCategory.INVALID_FIELD,
                reason = "field [lens]=[missing]",
              ),
            systemPromptId = prompt,
            llmRequestId = LlmRequestId(appendLlmRequest()),
          ),
        ).getOrThrow()

    // The Failed outcome round-trips through mapRun back to an equal Failed variant.
    assertEquals(
      SynthesisOutcome.Failed(
        category = JsonParseFailureCategory.INVALID_FIELD,
        reason = "field [lens]=[missing]",
      ),
      appended.outcome,
    )
  }

  @Test
  fun `a failed row with failure_category NULL is rejected`() {
    // A Failed outcome without a category is unrepresentable via the ADT, so drive
    // the DB CHECK directly: outcome = 'failed' with a null failure_category is
    // rejected by synthesis_runs_failure_consistency_check.
    val student = createStudent()
    val prompt = createSystemPrompt()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'failed', ?, ?)",
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
            stmt.setLong(3, appendLlmRequest())
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `an applied row with failure_category set is rejected`() {
    // Unrepresentable via the ADT: raw-SQL an 'applied' row with a
    // failure_category set — synthesis_runs_failure_consistency_check rejects it.
    val student = createStudent()
    val prompt = createSystemPrompt()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id, failure_category, failure_reason)
            VALUES (?, 'applied', ?, ?, 'malformed_json', 'should not be allowed')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
            stmt.setLong(3, appendLlmRequest())
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `one column set with the other null is rejected regardless of outcome`() {
    // The pairing CHECK: failure_category set with failure_reason null (here on an
    // 'applied' row) is rejected regardless of outcome. Unrepresentable via the
    // ADT, so exercised through raw SQL.
    val student = createStudent()
    val prompt = createSystemPrompt()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id, failure_category)
            VALUES (?, 'applied', ?, ?, 'malformed_json')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
            stmt.setLong(3, appendLlmRequest())
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `an invalid failure_category value is rejected`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id, failure_category, failure_reason)
            VALUES (?, 'failed', ?, ?, 'bogus_category', 'x')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
            stmt.setLong(3, appendLlmRequest())
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `a failure_reason over 2048 chars is rejected`() {
    val student = createStudent()
    val prompt = createSystemPrompt()
    val result =
      SynthesisRunsDao.append(
        session,
        NewSynthesisRun(
          studentId = student,
          outcome =
            SynthesisOutcome.Failed(
              category = JsonParseFailureCategory.MALFORMED_JSON,
              reason = "x".repeat(2049),
            ),
          systemPromptId = prompt,
          llmRequestId = LlmRequestId(appendLlmRequest()),
        ),
      )
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }
}
