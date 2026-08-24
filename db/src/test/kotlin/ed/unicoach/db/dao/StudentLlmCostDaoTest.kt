package ed.unicoach.db.dao

import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [StudentLlmCostDao] over the `student_llm_cost` per-call attribution
 * spine (db/schema/0041, RFC 108): the per-student dollar total, lifetime and
 * `[start, end)`-windowed, plus the two counters that bound it — `uncostedCalls`
 * (NULL-cost calls) and `estimatedCalls` (default-priced calls).
 *
 * The spine keeps RFC 106's attribution semantics unchanged (four-owner union,
 * soft-deleted-convo inclusion, orphan exclusion), so those are re-pinned here at
 * the lowered per-call grain.
 */
class StudentLlmCostDaoTest {
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

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
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
        "TRUNCATE TABLE convos, convo_requests, extraction_runs, synthesis_runs, fit_lens_runs, fit_suggestions, " +
          "llm_requests, llm_responses, llm_responses_raw, colleges, students, users CASCADE",
      )
    }
  }

  private fun createStudent(): UUID {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'slc-$userId@test.com', 'SLC User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return studentId
  }

  // Unique per row rather than a per-instance counter: system_prompts is no
  // longer truncated between tests (see resetDatabase), and JUnit builds a fresh
  // instance per test, so a counter would collide on (name, version).
  private fun createSystemPrompt(): UUID {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'extraction', ?, 'distill')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, "v-$id")
      stmt.executeUpdate()
    }
    return id
  }

  /**
   * Inserts an llm_requests row + its 1:1 completed llm_responses row carrying the
   * given frozen cost (and optional created_at, for windowing); returns the
   * request id. [costNanodollars]/[costIsEstimated] are both null for an uncosted
   * call, or both non-null otherwise (the 0041 pairing CHECK).
   */
  private fun appendCall(
    costNanodollars: Long?,
    costIsEstimated: Boolean?,
    createdAt: Instant? = null,
  ): Long {
    val requestId =
      connection
        .prepareStatement(
          "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('anthropic', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    val sql =
      if (createdAt == null) {
        """
        INSERT INTO llm_responses
          (request_id, outcome, content, model_resolved, stop_reason, cost_nanodollars, cost_is_estimated, input_tokens, output_tokens, latency_ms)
        VALUES (?, 'completed', '[]'::jsonb, 'm', 'end_turn', ?, ?, 1, 1, 1)
        """.trimIndent()
      } else {
        """
        INSERT INTO llm_responses
          (request_id, created_at, outcome, content, model_resolved, stop_reason, cost_nanodollars, cost_is_estimated, input_tokens, output_tokens, latency_ms)
        VALUES (?, ?, 'completed', '[]'::jsonb, 'm', 'end_turn', ?, ?, 1, 1, 1)
        """.trimIndent()
      }
    connection.prepareStatement(sql).use { stmt ->
      var i = 1
      stmt.setLong(i++, requestId)
      if (createdAt != null) stmt.setTimestamp(i++, Timestamp.from(createdAt))
      if (costNanodollars != null) stmt.setLong(i++, costNanodollars) else stmt.setNull(i++, java.sql.Types.BIGINT)
      if (costIsEstimated != null) stmt.setBoolean(i++, costIsEstimated) else stmt.setNull(i++, java.sql.Types.BOOLEAN)
      stmt.executeUpdate()
    }
    return requestId
  }

  private fun createConvo(studentId: UUID): UUID {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId)
      stmt.executeUpdate()
    }
    return convoId
  }

  private fun appendConvoRequest(
    convoId: UUID,
    llmRequestId: Long,
  ) {
    val promptId = createSystemPrompt()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq'))
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, promptId)
        stmt.setLong(3, llmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun appendExtractionRun(
    convoId: UUID,
    studentId: UUID,
    throughRequestId: Long,
    llmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id)
        VALUES (?, ?, ?, 'applied', ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, studentId)
        stmt.setLong(3, throughRequestId)
        stmt.setObject(4, createSystemPrompt())
        stmt.setLong(5, llmRequestId)
        stmt.executeUpdate()
      }
  }

  /** Returns the created convo_requests id (an extraction watermark target). */
  private fun appendConvoRequestReturningId(
    convoId: UUID,
    llmRequestId: Long,
  ): Long {
    val promptId = createSystemPrompt()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq')) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, promptId)
        stmt.setLong(3, llmRequestId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getLong("id")
        }
      }
  }

  private fun appendSynthesisRun(
    studentId: UUID,
    llmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'applied', ?, ?)",
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.setObject(2, createSystemPrompt())
        stmt.setLong(3, llmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun appendFitLensRun(
    studentId: UUID,
    queryLlmRequestId: Long,
    reasonLlmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO fit_lens_runs
          (student_id, outcome, query_system_prompt_id, reason_system_prompt_id, query_llm_request_id, reason_llm_request_id, suggestions_written, matches_considered)
        VALUES (?, 'applied', ?, ?, ?, ?, 0, 0)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.setObject(2, createSystemPrompt())
        stmt.setObject(3, createSystemPrompt())
        stmt.setLong(4, queryLlmRequestId)
        stmt.setLong(5, reasonLlmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun lifetime(studentId: UUID) = StudentLlmCostDao.lifetimeCost(session, StudentId(studentId)).getOrThrow()

  @Test
  fun `lifetimeCost sums a student's cost across all four owners, fit-lens pair included`() {
    val studentA = createStudent()

    val convo = createConvo(studentA)
    val chatCall = appendCall(costNanodollars = 100, costIsEstimated = false)
    val throughRequest = appendConvoRequestReturningId(convo, chatCall)

    val extractionCall = appendCall(costNanodollars = 200, costIsEstimated = false)
    appendExtractionRun(convo, studentA, throughRequest, extractionCall)

    val synthesisCall = appendCall(costNanodollars = 400, costIsEstimated = false)
    appendSynthesisRun(studentA, synthesisCall)

    val queryCall = appendCall(costNanodollars = 800, costIsEstimated = false)
    val reasonCall = appendCall(costNanodollars = 1600, costIsEstimated = false)
    appendFitLensRun(studentA, queryCall, reasonCall)

    val cost = lifetime(studentA)
    assertEquals(
      100L + 200L + 400L + 800L + 1600L,
      cost.costNanodollars.value,
    )
    assertEquals(0, cost.uncostedCalls)
    assertEquals(0, cost.estimatedCalls)
  }

  @Test
  fun `a fit-lens pair's two calls both count to its own student`() {
    val studentA = createStudent()
    val studentB = createStudent()

    val queryA = appendCall(costNanodollars = 5, costIsEstimated = false)
    val reasonA = appendCall(costNanodollars = 7, costIsEstimated = false)
    appendFitLensRun(studentA, queryA, reasonA)

    val queryB = appendCall(costNanodollars = 11, costIsEstimated = false)
    val reasonB = appendCall(costNanodollars = 13, costIsEstimated = false)
    appendFitLensRun(studentB, queryB, reasonB)

    assertEquals(12L, lifetime(studentA).costNanodollars.value)
    assertEquals(24L, lifetime(studentB).costNanodollars.value)
  }

  @Test
  fun `an unattributed orphan call is excluded from every total`() {
    val studentA = createStudent()
    val synthesisCall = appendCall(costNanodollars = 100, costIsEstimated = false)
    appendSynthesisRun(studentA, synthesisCall)

    // A crash-window orphan: a logged, costed call no domain owner references.
    appendCall(costNanodollars = 9999, costIsEstimated = false)

    val cost = lifetime(studentA)
    assertEquals(100L, cost.costNanodollars.value, "the orphan's cost is unattributed, never misattributed")
    assertEquals(0, cost.uncostedCalls)
  }

  @Test
  fun `a soft-deleted convo's genuinely-billed cost is still counted`() {
    val studentA = createStudent()
    val convo = createConvo(studentA)
    val chatCall = appendCall(costNanodollars = 50, costIsEstimated = false)
    appendConvoRequest(convo, chatCall)

    connection.prepareStatement("UPDATE convos SET deleted_at = NOW() WHERE id = ?").use { stmt ->
      stmt.setObject(1, convo)
      stmt.executeUpdate()
    }

    assertEquals(50L, lifetime(studentA).costNanodollars.value, "the join ignores convos.deleted_at")
  }

  @Test
  fun `an uncosted call is counted in uncostedCalls and contributes 0 to the sum`() {
    val studentA = createStudent()

    val costed = appendCall(costNanodollars = 300, costIsEstimated = false)
    appendSynthesisRun(studentA, costed)
    val uncosted = appendCall(costNanodollars = null, costIsEstimated = null)
    appendSynthesisRun(studentA, uncosted)

    val cost = lifetime(studentA)
    assertEquals(300L, cost.costNanodollars.value, "the NULL-cost call adds 0 via COALESCE")
    assertEquals(1, cost.uncostedCalls)
    assertEquals(0, cost.estimatedCalls)
  }

  @Test
  fun `an estimated call is counted in estimatedCalls and its cost is in the sum, counters independent`() {
    val studentA = createStudent()

    val estimated = appendCall(costNanodollars = 700, costIsEstimated = true)
    appendSynthesisRun(studentA, estimated)
    val uncosted = appendCall(costNanodollars = null, costIsEstimated = null)
    appendSynthesisRun(studentA, uncosted)

    val cost = lifetime(studentA)
    assertEquals(700L, cost.costNanodollars.value, "the estimated call's cost IS in the sum, unlike an uncosted one")
    assertEquals(1, cost.uncostedCalls)
    assertEquals(1, cost.estimatedCalls)
  }

  @Test
  fun `windowedCost sums only calls in the half-open window`() {
    val studentA = createStudent()
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val end = start.plusSeconds(2 * 3600)

    // Before start (excluded), at start (included), mid (included), at end (excluded).
    val before = appendCall(costNanodollars = 1, costIsEstimated = false, createdAt = start.minusSeconds(3600))
    appendSynthesisRun(studentA, before)
    val atStart = appendCall(costNanodollars = 10, costIsEstimated = false, createdAt = start)
    appendSynthesisRun(studentA, atStart)
    val mid = appendCall(costNanodollars = 100, costIsEstimated = false, createdAt = start.plusSeconds(3600))
    appendSynthesisRun(studentA, mid)
    val atEnd = appendCall(costNanodollars = 1000, costIsEstimated = false, createdAt = end)
    appendSynthesisRun(studentA, atEnd)

    val windowed = StudentLlmCostDao.windowedCost(session, StudentId(studentA), start, end).getOrThrow()
    assertEquals(
      10L + 100L,
      windowed.costNanodollars.value,
      "start inclusive, end exclusive, before excluded",
    )

    // Lifetime still sees all four.
    assertEquals(
      1L + 10L + 100L + 1000L,
      lifetime(studentA).costNanodollars.value,
    )
  }

  @Test
  fun `windowedCost rejects an inverted or empty window`() {
    val studentA = createStudent()
    val t = Instant.parse("2026-01-01T00:00:00Z")

    assertTrue(
      runCatching { StudentLlmCostDao.windowedCost(session, StudentId(studentA), t, t) }
        .exceptionOrNull() is IllegalArgumentException,
      "an empty window (start == end) must be rejected",
    )
    assertTrue(
      runCatching { StudentLlmCostDao.windowedCost(session, StudentId(studentA), t.plusSeconds(1), t) }
        .exceptionOrNull() is IllegalArgumentException,
      "an inverted window (start > end) must be rejected",
    )
  }

  @Test
  fun `a student with no attributed call reads zero on every field`() {
    val studentA = createStudent()
    val cost = lifetime(studentA)
    assertEquals(0L, cost.costNanodollars.value)
    assertEquals(0, cost.uncostedCalls)
    assertEquals(0, cost.estimatedCalls)
  }
}
