package ed.unicoach.db.dao

import ed.unicoach.db.models.FitLensFailureCategory
import ed.unicoach.db.models.FitLensOutcome
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
import kotlin.test.assertNull

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
      stmt.execute("TRUNCATE TABLE fit_lens_runs, system_prompts, students, users CASCADE")
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

  private fun append(
    student: StudentId,
    outcome: FitLensOutcome,
    queryPrompt: SystemPromptId,
    reasonPrompt: SystemPromptId,
    suggestionsWritten: Int = 0,
    matchesConsidered: Int? = 0,
    input: Int? = 100,
    output: Int? = 50,
    // fit_lens_runs_failure_consistency_check requires both set exactly when
    // outcome = FAILED; default to a stand-in pair so FAILED calls that don't
    // care about the specific reason (most of this file) still satisfy it.
    failureCategory: FitLensFailureCategory? =
      if (outcome == FitLensOutcome.FAILED) FitLensFailureCategory.MALFORMED_OUTPUT else null,
    failureReason: String? = if (outcome == FitLensOutcome.FAILED) "test failure" else null,
  ) = FitLensRunsDao
    .append(
      session,
      NewFitLensRun(
        studentId = student,
        outcome = outcome,
        querySystemPromptId = queryPrompt,
        reasonSystemPromptId = reasonPrompt,
        provider = "log",
        modelResolved = "claude-sonnet-4-6",
        suggestionsWritten = suggestionsWritten,
        matchesConsidered = matchesConsidered,
        inputTokens = input,
        outputTokens = output,
        cacheReadTokens = 0,
        cacheWriteTokens = 0,
        failureCategory = failureCategory,
        failureReason = failureReason,
      ),
    ).getOrThrow()

  @Test
  fun `append persists applied and failed rows with summed tokens and both prompt pins`() {
    val student = createStudent()
    val queryPrompt = createPrompt("fit_lens_query")
    val reasonPrompt = createPrompt("fit_lens_reason")

    val applied = append(student, FitLensOutcome.APPLIED, queryPrompt, reasonPrompt, suggestionsWritten = 1, input = 300, output = 120)
    assertEquals(FitLensOutcome.APPLIED, applied.outcome)
    assertEquals(1, applied.suggestionsWritten)
    assertEquals(queryPrompt, applied.querySystemPromptId)
    assertEquals(reasonPrompt, applied.reasonSystemPromptId)
    assertEquals(300, applied.inputTokens)
    assertEquals(120, applied.outputTokens)

    val failed =
      append(
        student,
        FitLensOutcome.FAILED,
        queryPrompt,
        reasonPrompt,
        suggestionsWritten = 0,
        matchesConsidered = null,
        failureCategory = FitLensFailureCategory.INVALID_CONTENT,
        failureReason = "collegeId [11111111-1111-1111-1111-111111111111] is outside the retrieved match set",
      )
    assertEquals(FitLensOutcome.FAILED, failed.outcome)
    assertEquals(0, failed.suggestionsWritten)
    assertNull(failed.matchesConsidered, "matches_considered is null when the retrieve never ran")
    assertEquals(FitLensFailureCategory.INVALID_CONTENT, failed.failureCategory)
    assertEquals(
      "collegeId [11111111-1111-1111-1111-111111111111] is outside the retrieved match set",
      failed.failureReason,
    )

    assertNull(applied.failureCategory, "an applied run carries no failure category")
    assertNull(applied.failureReason, "an applied run carries no failure reason")
  }

  @Test
  fun `lastAppliedAt returns the latest applied created_at and ignores failed rows`() {
    val student = createStudent()
    val queryPrompt = createPrompt("fit_lens_query")
    val reasonPrompt = createPrompt("fit_lens_reason")

    assertNull(FitLensRunsDao.lastAppliedAt(session, student).getOrThrow(), "null when never applied")

    val first = append(student, FitLensOutcome.APPLIED, queryPrompt, reasonPrompt)
    // A later failed run must not advance the freshness marker.
    append(student, FitLensOutcome.FAILED, queryPrompt, reasonPrompt)

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
    append(student, FitLensOutcome.FAILED, queryPrompt, reasonPrompt)
    append(student, FitLensOutcome.FAILED, queryPrompt, reasonPrompt)
    assertEquals(2, FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(), "counts from the first run when never applied")

    // An applied run resets the breaker.
    append(student, FitLensOutcome.APPLIED, queryPrompt, reasonPrompt)
    assertEquals(0, FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(), "reset the moment an applied run lands")

    // Failures accumulate again after the applied.
    append(student, FitLensOutcome.FAILED, queryPrompt, reasonPrompt)
    assertEquals(
      1,
      FitLensRunsDao.consecutiveFailuresSince(session, student).getOrThrow(),
      "counts only the failures since the last applied",
    )
  }
}
