package ed.unicoach.db.dao

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.ExtractionRunId
import ed.unicoach.db.models.JsonParseFailureCategory
import ed.unicoach.db.models.NewExtractionRun
import ed.unicoach.db.models.StudentId
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
import kotlin.test.assertTrue

class ExtractionRunsDaoTest {
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
        "TRUNCATE TABLE observations, claim_support, claims, extraction_runs, " +
          "convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
      // Restore the migration-seeded prompts for cross-module suites on the shared DB.
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'er-$userId@test.com', 'ER User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createConvo(studentId: StudentId): ConvoId {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId.value)
      stmt.executeUpdate()
    }
    return ConvoId(convoId)
  }

  private var promptCounter = 0

  private fun createSystemPrompt(): SystemPromptId {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'extraction', ?, 'distill')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, "p${promptCounter++}")
      stmt.executeUpdate()
    }
    return SystemPromptId(id)
  }

  private fun appendRequest(convoId: ConvoId): ConvoRequestId {
    val promptId = createSystemPrompt()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, provider, model_requested, system_prompt_id, content, turn_id)
        VALUES (?, 'anthropic', 'claude-opus-4-8', ?, '[]'::jsonb, nextval('convo_turn_id_seq')) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, promptId.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          return ConvoRequestId(rs.getLong("id"))
        }
      }
  }

  @Test
  fun `watermark is 0 with no runs`() {
    val student = createStudent()
    val convo = createConvo(student)
    assertEquals(0L, ExtractionRunsDao.watermark(session, convo).getOrThrow())
  }

  @Test
  fun `watermark ignores failed rows and returns MAX over applied`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    val r3 = appendRequest(convo)

    ExtractionRunsDao.append(session, run(convo, student, prompt, r1, applied())).getOrThrow()
    ExtractionRunsDao.append(session, run(convo, student, prompt, r2, applied())).getOrThrow()
    // a later failed row must NOT advance the watermark.
    ExtractionRunsDao.append(session, run(convo, student, prompt, r3, failed())).getOrThrow()

    assertEquals(r2.value, ExtractionRunsDao.watermark(session, convo).getOrThrow())
  }

  @Test
  fun `append records outcome, counts, provenance, and all four token columns`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)

    val appended =
      ExtractionRunsDao
        .append(
          session,
          NewExtractionRun(
            convoId = convo,
            studentId = student,
            throughRequestId = req,
            outcome =
              ExtractionOutcome.Applied(
                observationsWritten = 2,
                claimsWritten = 1,
                claimsSuperseded = 0,
              ),
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
    assertEquals(
      ExtractionOutcome.Applied(observationsWritten = 2, claimsWritten = 1, claimsSuperseded = 0),
      appended.outcome,
    )
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
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)

    ExtractionRunsDao
      .append(
        session,
        run(convo, student, prompt, r1, applied(), input = 100, output = 50),
      ).getOrThrow()
    ExtractionRunsDao
      .append(
        session,
        run(convo, student, prompt, r2, failed(), input = 30, output = 0),
      ).getOrThrow()

    connection
      .prepareStatement(
        "SELECT COALESCE(SUM(input_tokens),0) AS i, COALESCE(SUM(output_tokens),0) AS o FROM extraction_runs WHERE student_id = ?",
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
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val appended = ExtractionRunsDao.append(session, run(convo, student, prompt, req, applied())).getOrThrow()

    assertEquals(appended.id, ExtractionRunsDao.findById(session, appended.id).getOrThrow().id)

    val miss = ExtractionRunsDao.findById(session, ExtractionRunId(999_999L))
    assertTrue(miss.exceptionOrNull() is NotFoundException, "got ${miss.exceptionOrNull()}")
  }

  @Test
  fun `list pages and orders by id`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    val r3 = appendRequest(convo)
    val e1 = ExtractionRunsDao.append(session, run(convo, student, prompt, r1, applied())).getOrThrow()
    val e2 = ExtractionRunsDao.append(session, run(convo, student, prompt, r2, applied())).getOrThrow()
    val e3 = ExtractionRunsDao.append(session, run(convo, student, prompt, r3, failed())).getOrThrow()

    assertEquals(listOf(e1.id, e2.id), ExtractionRunsDao.list(session, 2, 0).getOrThrow().map { it.id })
    assertEquals(listOf(e3.id), ExtractionRunsDao.list(session, 2, 2).getOrThrow().map { it.id })
  }

  @Test
  fun `listByStudent returns the student's runs with token-and-count columns intact, bounded, excluding other students`() {
    val student = createStudent()
    val other = createStudent()
    val convo = createConvo(student)
    val otherConvo = createConvo(other)
    val prompt = createSystemPrompt()
    val r1 = appendRequest(convo)
    val r2 = appendRequest(convo)
    val rOther = appendRequest(otherConvo)

    val applied =
      ExtractionRunsDao
        .append(
          session,
          NewExtractionRun(
            convoId = convo,
            studentId = student,
            throughRequestId = r1,
            outcome =
              ExtractionOutcome.Applied(
                observationsWritten = 3,
                claimsWritten = 2,
                claimsSuperseded = 1,
              ),
            systemPromptId = prompt,
            provider = "log",
            modelResolved = "claude-sonnet-4-6",
            inputTokens = 100,
            outputTokens = 50,
            cacheReadTokens = 10,
            cacheWriteTokens = 5,
          ),
        ).getOrThrow()
    val failed =
      ExtractionRunsDao
        .append(session, run(convo, student, prompt, r2, failed(), input = 30, output = 0))
        .getOrThrow()
    ExtractionRunsDao
      .append(session, run(otherConvo, other, prompt, rOther, applied()))
      .getOrThrow()

    val mine = ExtractionRunsDao.listByStudent(session, student, 50, 0).getOrThrow()
    assertEquals(listOf(applied.id, failed.id), mine.map { it.id })

    val appliedRow = mine.first { it.id == applied.id }
    assertEquals(
      ExtractionOutcome.Applied(observationsWritten = 3, claimsWritten = 2, claimsSuperseded = 1),
      appliedRow.outcome,
    )
    assertEquals(100, appliedRow.inputTokens)
    assertEquals(50, appliedRow.outputTokens)
    assertEquals(10, appliedRow.cacheReadTokens)
    assertEquals(5, appliedRow.cacheWriteTokens)

    // Bounded by limit/offset.
    assertEquals(listOf(applied.id), ExtractionRunsDao.listByStudent(session, student, 1, 0).getOrThrow().map { it.id })
    assertEquals(listOf(failed.id), ExtractionRunsDao.listByStudent(session, student, 1, 1).getOrThrow().map { it.id })
  }

  @Test
  fun `outcome outside applied,failed is rejected`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, provider)
            VALUES (?, ?, ?, 'bogus', ?, 'log')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `UPDATE on extraction_runs raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val appended =
      ExtractionRunsDao.append(session, run(convo, student, prompt, req, applied())).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use { it.execute("UPDATE extraction_runs SET outcome = 'failed' WHERE id = ${appended.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  @Test
  fun `DELETE on extraction_runs raises P0001`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val appended =
      ExtractionRunsDao.append(session, run(convo, student, prompt, req, applied())).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM extraction_runs WHERE id = ${appended.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

  /** A default `Applied` outcome with zero counts, for cases that don't assert on the payload. */
  private fun applied(): ExtractionOutcome.Applied = ExtractionOutcome.Applied(0, 0, 0)

  /** A default `Failed` outcome, for cases that don't assert on the specific reason. */
  private fun failed(): ExtractionOutcome.Failed = ExtractionOutcome.Failed(JsonParseFailureCategory.MALFORMED_JSON, "test failure")

  private fun run(
    convo: ConvoId,
    student: StudentId,
    prompt: SystemPromptId,
    req: ConvoRequestId,
    outcome: ExtractionOutcome,
    input: Int? = null,
    output: Int? = null,
  ): NewExtractionRun =
    NewExtractionRun(
      convoId = convo,
      studentId = student,
      throughRequestId = req,
      outcome = outcome,
      systemPromptId = prompt,
      provider = "log",
      modelResolved = "m",
      inputTokens = input,
      outputTokens = output,
    )

  @Test
  fun `append persists a failed run's failure_category and failure_reason`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)

    val appended =
      ExtractionRunsDao
        .append(
          session,
          NewExtractionRun(
            convoId = convo,
            studentId = student,
            throughRequestId = req,
            outcome =
              ExtractionOutcome.Failed(
                category = JsonParseFailureCategory.INVALID_FIELD,
                reason = "field [quote]=[missing or non-string]",
              ),
            systemPromptId = prompt,
            provider = "log",
            modelResolved = null,
          ),
        ).getOrThrow()

    // The Failed outcome round-trips through mapRun back to an equal Failed variant.
    assertEquals(
      ExtractionOutcome.Failed(
        category = JsonParseFailureCategory.INVALID_FIELD,
        reason = "field [quote]=[missing or non-string]",
      ),
      appended.outcome,
    )
  }

  @Test
  fun `a failed row with failure_category NULL is rejected`() {
    // A Failed outcome without a category is unrepresentable in Kotlin, so drive
    // the DB CHECK directly via raw SQL: outcome = 'failed' with a null
    // failure_category must be rejected by extraction_runs_failure_consistency_check.
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, provider)
            VALUES (?, ?, ?, 'failed', ?, 'log')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `an applied row with failure_category set is rejected`() {
    // Also unrepresentable via the ADT: raw-SQL an 'applied' row with a
    // failure_category set — extraction_runs_failure_consistency_check rejects it.
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, provider, failure_category, failure_reason)
            VALUES (?, ?, ?, 'applied', ?, 'log', 'malformed_json', 'should not be allowed')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
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
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, provider, failure_category)
            VALUES (?, ?, ?, 'applied', ?, 'log', 'malformed_json')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `an invalid failure_category value is rejected`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val ex =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, provider, failure_category, failure_reason)
            VALUES (?, ?, ?, 'failed', ?, 'log', 'bogus_category', 'x')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `a failure_reason over 2048 chars is rejected`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val result =
      ExtractionRunsDao.append(
        session,
        NewExtractionRun(
          convoId = convo,
          studentId = student,
          throughRequestId = req,
          outcome =
            ExtractionOutcome.Failed(
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
