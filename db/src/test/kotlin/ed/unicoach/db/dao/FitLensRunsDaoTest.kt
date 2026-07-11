package ed.unicoach.db.dao

import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewFitLensRun
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitLensRunsDaoTest {
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
        "TRUNCATE TABLE fit_lens_runs, llm_requests, llm_responses, llm_responses_raw, system_prompts, students, users CASCADE",
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'flr-$userId@test.com', 'FLR User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private var promptCounter = 0

  private fun createPrompt(name: String): SystemPromptId {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, ?, ?, 'body')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, name)
      stmt.setString(3, "p${promptCounter++}")
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

  /** A default `Applied` outcome, for cases that don't assert on the suggestions count. */
  private fun applied(suggestionsWritten: Int = 0): FitLensOutcome.Applied = FitLensOutcome.Applied(suggestionsWritten = suggestionsWritten)

  /** A default `Failed` outcome, for cases that don't assert on the specific reason. */
  private fun failed(): FitLensOutcome.Failed = FitLensOutcome.Failed(FitLensFailureCategory.MALFORMED_OUTPUT, "test failure")

  private fun append(
    student: StudentId,
    outcome: FitLensOutcome,
    queryPrompt: SystemPromptId,
    reasonPrompt: SystemPromptId,
    matchesConsidered: Int? = 0,
    reasonCallMade: Boolean = true,
  ) = FitLensRunsDao
    .append(
      session,
      NewFitLensRun(
        studentId = student,
        outcome = outcome,
        querySystemPromptId = queryPrompt,
        reasonSystemPromptId = reasonPrompt,
        queryLlmRequestId = LlmRequestId(appendLlmRequest()),
        reasonLlmRequestId = if (reasonCallMade) LlmRequestId(appendLlmRequest()) else null,
        matchesConsidered = matchesConsidered,
      ),
    ).getOrThrow()

  @Test
  fun `append persists applied and failed rows with both call pins and both prompt pins`() {
    val student = createStudent()
    val queryPrompt = createPrompt("fit_lens_query")
    val reasonPrompt = createPrompt("fit_lens_reason")

    val applied = append(student, applied(suggestionsWritten = 1), queryPrompt, reasonPrompt)
    // The applied round-trip yields an Applied variant carrying the suggestions count.
    assertEquals(FitLensOutcome.Applied(suggestionsWritten = 1), applied.outcome)
    assertEquals(queryPrompt, applied.querySystemPromptId)
    assertEquals(reasonPrompt, applied.reasonSystemPromptId)
    // A full pass makes both calls; both call pins round-trip (RFC 106).
    assertNotNull(applied.queryLlmRequestId, "the query call is pinned on a full pass")
    assertNotNull(applied.reasonLlmRequestId, "the reason call is pinned on a full pass")

    val failed =
      append(
        student,
        FitLensOutcome.Failed(
          category = FitLensFailureCategory.INVALID_CONTENT,
          reason = "collegeId [11111111-1111-1111-1111-111111111111] is outside the retrieved match set",
        ),
        queryPrompt,
        reasonPrompt,
        matchesConsidered = null,
        // A query call that bails before the reason call leaves reason_llm_request_id null.
        reasonCallMade = false,
      )
    // The Failed outcome round-trips through mapRun to an equal Failed variant;
    // matches_considered stays a flat field, null when the retrieve never ran.
    assertEquals(
      FitLensOutcome.Failed(
        category = FitLensFailureCategory.INVALID_CONTENT,
        reason = "collegeId [11111111-1111-1111-1111-111111111111] is outside the retrieved match set",
      ),
      failed.outcome,
    )
    assertNull(failed.matchesConsidered, "matches_considered is null when the retrieve never ran")
    assertNotNull(failed.queryLlmRequestId, "the query call is pinned even on a bailed pass")
    assertNull(failed.reasonLlmRequestId, "reason_llm_request_id is null when the pass bails before the reason call")
  }

  @Test
  fun `lastAppliedAt returns the latest applied created_at and ignores failed rows`() {
    val student = createStudent()
    val queryPrompt = createPrompt("fit_lens_query")
    val reasonPrompt = createPrompt("fit_lens_reason")

    assertNull(FitLensRunsDao.lastAppliedAt(session, student).getOrThrow(), "null when never applied")

    val first = append(student, applied(), queryPrompt, reasonPrompt)
    // A later failed run must not advance the freshness marker.
    append(student, failed(), queryPrompt, reasonPrompt)

    val marker = FitLensRunsDao.lastAppliedAt(session, student).getOrThrow()
    assertEquals(first.createdAt, marker, "The freshness marker is the latest applied created_at, not the later failed run")
  }

  @Test
  fun `consecutiveFailuresSince counts failed runs since the last applied and resets after an applied`() {
    val student = createStudent()
    val queryPrompt = createPrompt("fit_lens_query")
    val reasonPrompt = createPrompt("fit_lens_reason")

    assertEquals(0, FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(), "0 with no runs")

    // Never applied: counts from the first run.
    append(student, failed(), queryPrompt, reasonPrompt)
    append(student, failed(), queryPrompt, reasonPrompt)
    assertEquals(2, FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(), "counts from the first run when never applied")

    // An applied run resets the breaker.
    append(student, applied(), queryPrompt, reasonPrompt)
    assertEquals(0, FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(), "reset the moment an applied run lands")

    // Failures accumulate again after the applied.
    append(student, failed(), queryPrompt, reasonPrompt)
    assertEquals(
      1,
      FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(),
      "counts only the failures since the last applied",
    )
  }

  @Test
  fun `a row with failure_category set and failure_reason null (or vice versa) is rejected`() {
    // Both half-populated states are unrepresentable via the outcome ADT, so drive
    // the new 0036 pairing CHECK directly with raw SQL on an 'applied' row (which
    // the pre-0036 consistency check did not touch).
    val student = createStudent()
    val queryPrompt = createPrompt("fit_lens_query")
    val reasonPrompt = createPrompt("fit_lens_reason")

    // query_llm_request_id is NOT NULL (RFC 106), so seed a real call id — the row
    // is otherwise valid and the pairing CHECK is what must reject it.
    val queryRequestId = appendLlmRequest()

    val categoryOnly =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO fit_lens_runs (student_id, outcome, query_system_prompt_id, reason_system_prompt_id, query_llm_request_id, failure_category)
            VALUES (?, 'applied', ?, ?, ?, 'malformed_output')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, queryPrompt.value)
            stmt.setObject(3, reasonPrompt.value)
            stmt.setLong(4, queryRequestId)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(categoryOnly is java.sql.SQLException && categoryOnly.sqlState == "23514", "got $categoryOnly")

    val reasonOnly =
      runCatching {
        connection
          .prepareStatement(
            """
            INSERT INTO fit_lens_runs (student_id, outcome, query_system_prompt_id, reason_system_prompt_id, query_llm_request_id, failure_reason)
            VALUES (?, 'applied', ?, ?, ?, 'orphaned reason')
            """.trimIndent(),
          ).use { stmt ->
            stmt.setObject(1, student.value)
            stmt.setObject(2, queryPrompt.value)
            stmt.setObject(3, reasonPrompt.value)
            stmt.setLong(4, queryRequestId)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(reasonOnly is java.sql.SQLException && reasonOnly.sqlState == "23514", "got $reasonOnly")
  }
}
