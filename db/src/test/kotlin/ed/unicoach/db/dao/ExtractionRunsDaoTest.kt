package ed.unicoach.db.dao

import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ExtractionOutcome
import ed.unicoach.db.models.ExtractionRunId
import ed.unicoach.db.models.JsonParseFailureCategory
import ed.unicoach.db.models.LlmRequestId
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
      // system_prompts is deliberately NOT truncated: it is the migration-seeded,
      // immutable catalog (RFC 33/0007) that every other module's tests on this
      // shared database read. bin/test re-migrates before every run, so it is
      // already complete; wiping it and hand-restoring a stale list left the seeds
      // partial for whoever ran next (RFC 129).
      stmt.execute(
        "TRUNCATE TABLE observations, claim_support, claims, extraction_runs, " +
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

  // Unique per row rather than a per-instance counter: system_prompts is no
  // longer truncated between tests (see resetDatabase), and JUnit builds a fresh
  // instance per test, so a counter would collide on (name, version).
  private fun createSystemPrompt(): SystemPromptId {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'extraction', ?, 'distill')").use { stmt ->
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

  private fun appendRequest(convoId: ConvoId): ConvoRequestId {
    val promptId = createSystemPrompt()
    val llmRequestId = appendLlmRequest()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq')) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.setObject(2, promptId.value)
        stmt.setLong(3, llmRequestId)
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
  fun `append records outcome, counts, provenance, and the llm_request reference`() {
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val req = appendRequest(convo)
    val llmRequestId = LlmRequestId(appendLlmRequest())

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
            llmRequestId = llmRequestId,
          ),
        ).getOrThrow()

    // The applied round-trip yields an Applied variant carrying the counts and no failure payload.
    assertEquals(
      ExtractionOutcome.Applied(observationsWritten = 2, claimsWritten = 1, claimsSuperseded = 0),
      appended.outcome,
    )
    assertEquals(prompt, appended.systemPromptId)
    assertEquals(llmRequestId, appended.llmRequestId)
  }

  @Test
  fun `an absent llm_request reference is rejected`() {
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
          outcome = applied(),
          systemPromptId = prompt,
          llmRequestId = LlmRequestId(999_999L),
        ),
      )
    assertTrue(result.exceptionOrNull() is NotFoundException, "got ${result.exceptionOrNull()}")
  }

  @Test
  fun `dummy token-sum removed`() {
    // Token spend moved to llm_responses (RFC 106); the per-student cost ledger is
    // the student_llm_cost spine, covered in StudentLlmCostDaoTest (RFC 108).
    val student = createStudent()
    val convo = createConvo(student)
    val prompt = createSystemPrompt()
    val r1 = appendRequest(convo)

    ExtractionRunsDao.append(session, run(convo, student, prompt, r1, applied())).getOrThrow()

    connection
      .prepareStatement(
        "SELECT COUNT(*) AS c FROM extraction_runs WHERE student_id = ?",
      ).use { stmt ->
        stmt.setObject(1, student.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(1, rs.getInt("c"))
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
  fun `listByStudent returns the student's runs with count columns intact, bounded, excluding other students`() {
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
            llmRequestId = LlmRequestId(appendLlmRequest()),
          ),
        ).getOrThrow()
    val failed =
      ExtractionRunsDao
        .append(session, run(convo, student, prompt, r2, failed()))
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
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id)
            VALUES (?, ?, ?, 'bogus', ?, ?)
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.setLong(5, appendLlmRequest())
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
  ): NewExtractionRun =
    NewExtractionRun(
      convoId = convo,
      studentId = student,
      throughRequestId = req,
      outcome = outcome,
      systemPromptId = prompt,
      llmRequestId = LlmRequestId(appendLlmRequest()),
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
            llmRequestId = LlmRequestId(appendLlmRequest()),
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
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id)
            VALUES (?, ?, ?, 'failed', ?, ?)
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.setLong(5, appendLlmRequest())
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
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id, failure_category, failure_reason)
            VALUES (?, ?, ?, 'applied', ?, ?, 'malformed_json', 'should not be allowed')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.setLong(5, appendLlmRequest())
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
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id, failure_category)
            VALUES (?, ?, ?, 'applied', ?, ?, 'malformed_json')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.setLong(5, appendLlmRequest())
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
            INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id, failure_category, failure_reason)
            VALUES (?, ?, ?, 'failed', ?, ?, 'bogus_category', 'x')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, convo.value)
            stmt.setObject(2, student.value)
            stmt.setLong(3, req.value)
            stmt.setObject(4, prompt.value)
            stmt.setLong(5, appendLlmRequest())
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
          llmRequestId = LlmRequestId(appendLlmRequest()),
        ),
      )
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }
}
