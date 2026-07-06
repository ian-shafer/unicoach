package ed.unicoach.db.dao

import ed.unicoach.db.models.JsonParseFailureCategory
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
      stmt.execute(
        "TRUNCATE TABLE commitment_support, commitments, synthesis_runs, observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
      // Restore all migration-seeded prompts for cross-module suites on the shared DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
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

  private var promptCounter = 0

  private fun createSystemPrompt(): SystemPromptId {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'synthesis', ?, 'reflect')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, "p${promptCounter++}")
      stmt.executeUpdate()
    }
    return SystemPromptId(id)
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
    input: Int? = null,
    output: Int? = null,
  ): NewSynthesisRun =
    NewSynthesisRun(
      studentId = student,
      outcome = outcome,
      systemPromptId = prompt,
      provider = "log",
      modelResolved = "m",
      inputTokens = input,
      outputTokens = output,
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
  fun `append records outcome, counts, provenance, and all four token columns`() {
    val student = createStudent()
    val prompt = createSystemPrompt()

    val appended =
      SynthesisRunsDao
        .append(
          session,
          NewSynthesisRun(
            studentId = student,
            outcome = SynthesisOutcome.Applied(commitmentsWritten = 2, commitmentsDropped = 1),
            systemPromptId = prompt,
            provider = "log",
            modelResolved = "claude-sonnet-4-6",
            inputTokens = 100,
            outputTokens = 50,
            cacheReadTokens = 10,
            cacheWriteTokens = 5,
          ),
        ).getOrThrow()

    // The applied round-trip yields an Applied variant carrying the counts and no failure payload.
    assertEquals(SynthesisOutcome.Applied(commitmentsWritten = 2, commitmentsDropped = 1), appended.outcome)
    assertEquals(prompt, appended.systemPromptId)
    assertEquals("log", appended.provider)
    assertEquals("claude-sonnet-4-6", appended.modelResolved)
    assertEquals(100, appended.inputTokens)
    assertEquals(50, appended.outputTokens)
    assertEquals(10, appended.cacheReadTokens)
    assertEquals(5, appended.cacheWriteTokens)
  }

  @Test
  fun `per-student token sum aggregates across an applied and a failed row`() {
    val student = createStudent()
    val prompt = createSystemPrompt()

    SynthesisRunsDao.append(session, run(student, prompt, applied(written = 1), input = 100, output = 50)).getOrThrow()
    SynthesisRunsDao.append(session, run(student, prompt, failed(), input = 30, output = 0)).getOrThrow()

    connection
      .prepareStatement(
        "SELECT COALESCE(SUM(input_tokens),0) AS i, COALESCE(SUM(output_tokens),0) AS o FROM synthesis_runs WHERE student_id = ?",
      ).use { stmt ->
        stmt.setObject(1, student.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(130, rs.getInt("i"))
          assertEquals(50, rs.getInt("o"))
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
            "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, provider) VALUES (?, 'bogus', ?, 'log')",
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
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
            provider = "log",
            modelResolved = null,
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
            "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, provider) VALUES (?, 'failed', ?, 'log')",
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
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
            INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, provider, failure_category, failure_reason)
            VALUES (?, 'applied', ?, 'log', 'malformed_json', 'should not be allowed')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
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
            INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, provider, failure_category)
            VALUES (?, 'applied', ?, 'log', 'malformed_json')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
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
            INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, provider, failure_category, failure_reason)
            VALUES (?, 'failed', ?, 'log', 'bogus_category', 'x')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, prompt.value)
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
          provider = "log",
          modelResolved = null,
        ),
      )
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }
}
