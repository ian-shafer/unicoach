package ed.unicoach.db.dao

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ExtractionOutcome
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
        INSERT INTO convo_requests (convo_id, provider, model_requested, system_prompt_id, content)
        VALUES (?, 'anthropic', 'claude-opus-4-8', ?, '[]'::jsonb) RETURNING id
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

    ExtractionRunsDao.append(session, run(convo, student, prompt, r1, ExtractionOutcome.APPLIED)).getOrThrow()
    ExtractionRunsDao.append(session, run(convo, student, prompt, r2, ExtractionOutcome.APPLIED)).getOrThrow()
    // a later failed row must NOT advance the watermark.
    ExtractionRunsDao.append(session, run(convo, student, prompt, r3, ExtractionOutcome.FAILED)).getOrThrow()

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
            outcome = ExtractionOutcome.APPLIED,
            systemPromptId = prompt,
            provider = "log",
            modelResolved = "claude-sonnet-4-6",
            observationsWritten = 2,
            claimsWritten = 1,
            claimsSuperseded = 0,
            inputTokens = 100,
            outputTokens = 50,
            cacheReadTokens = 10,
            cacheWriteTokens = 5,
          ),
        ).getOrThrow()

    assertEquals(ExtractionOutcome.APPLIED, appended.outcome)
    assertEquals(prompt, appended.systemPromptId)
    assertEquals("log", appended.provider)
    assertEquals("claude-sonnet-4-6", appended.modelResolved)
    assertEquals(2, appended.observationsWritten)
    assertEquals(1, appended.claimsWritten)
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
        run(convo, student, prompt, r1, ExtractionOutcome.APPLIED, input = 100, output = 50),
      ).getOrThrow()
    ExtractionRunsDao
      .append(
        session,
        run(convo, student, prompt, r2, ExtractionOutcome.FAILED, input = 30, output = 0),
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
  fun `failed row with nonzero write counts is rejected`() {
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
          outcome = ExtractionOutcome.FAILED,
          systemPromptId = prompt,
          provider = "log",
          modelResolved = null,
          claimsWritten = 1,
        ),
      )
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
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
      ExtractionRunsDao.append(session, run(convo, student, prompt, req, ExtractionOutcome.APPLIED)).getOrThrow()

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
      ExtractionRunsDao.append(session, run(convo, student, prompt, req, ExtractionOutcome.APPLIED)).getOrThrow()

    val ex =
      runCatching {
        connection.createStatement().use { it.execute("DELETE FROM extraction_runs WHERE id = ${appended.id.value}") }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "got $ex")
  }

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
}
