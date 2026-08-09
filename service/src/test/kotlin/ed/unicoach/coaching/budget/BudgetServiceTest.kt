package ed.unicoach.coaching.budget

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SubscriptionStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises [BudgetService] against the real `student_llm_cost` spine (RFC 108):
 * the ledger total it reads, the allowance it pairs that total with, the
 * agreement of its two overloads, the [BudgetVerdict] its gate decides from
 * them, and its refusal to report an entitled verdict when the read itself
 * fails.
 *
 * Spend is seeded with the same raw-SQL pattern as `StudentLlmCostDaoTest` — an
 * owner row plus its `llm_requests`/`llm_responses` pair carrying a known
 * `cost_nanodollars`.
 */
class BudgetServiceTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
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
        "TRUNCATE TABLE convos, convo_requests, extraction_runs, synthesis_runs, fit_lens_runs, fit_suggestions, " +
          "llm_requests, llm_responses, llm_responses_raw, colleges, system_prompts, subscriptions, students, users CASCADE",
      )
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Seeding
  // ---------------------------------------------------------------------------

  private var promptCounter = 0

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'bs-$userId@test.com', 'Budget User', 'ahash')")
      stmt.execute("INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)")
    }
    return StudentId(studentId)
  }

  private fun createSystemPrompt(): UUID {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'extraction', ?, 'distill')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, "bp${promptCounter++}")
      stmt.executeUpdate()
    }
    return id
  }

  /**
   * An llm_requests row + its completed llm_responses row at [costNanodollars]
   * (null = uncosted), attributed at [createdAt] (null = the DB clock's now) —
   * the timestamp the subscription meter windows by.
   */
  private fun appendCall(
    costNanodollars: Long?,
    createdAt: Instant? = null,
  ): Long {
    val requestId =
      connection
        .prepareStatement(
          "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) " +
            "VALUES ('anthropic', 'claude-sonnet-4-6', '[]'::jsonb, 1024) RETURNING id",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    connection
      .prepareStatement(
        """
        INSERT INTO llm_responses
          (request_id, outcome, content, model_resolved, stop_reason, cost_nanodollars, cost_is_estimated, input_tokens, output_tokens, latency_ms, created_at)
        VALUES (?, 'completed', '[]'::jsonb, 'm', 'end_turn', ?, ?, 1, 1, 1, COALESCE(?, NOW()))
        """.trimIndent(),
      ).use { stmt ->
        stmt.setLong(1, requestId)
        if (costNanodollars != null) stmt.setLong(2, costNanodollars) else stmt.setNull(2, java.sql.Types.BIGINT)
        if (costNanodollars != null) stmt.setBoolean(3, false) else stmt.setNull(3, java.sql.Types.BOOLEAN)
        if (createdAt != null) {
          stmt.setTimestamp(4, java.sql.Timestamp.from(createdAt))
        } else {
          stmt.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        }
        stmt.executeUpdate()
      }
    return requestId
  }

  /** Attributes [llmRequestId] to [studentId] through a synthesis run (the cheapest single-call owner). */
  private fun attributeToSynthesis(
    studentId: StudentId,
    llmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'applied', ?, ?)",
      ).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.setObject(2, createSystemPrompt())
        stmt.setLong(3, llmRequestId)
        stmt.executeUpdate()
      }
  }

  /** Attributes [llmRequestId] to [studentId] through a chat turn (the second owner). */
  private fun attributeToChatTurn(
    studentId: StudentId,
    llmRequestId: Long,
  ) {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId.value)
      stmt.executeUpdate()
    }
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq'))
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, createSystemPrompt())
        stmt.setLong(3, llmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun spend(
    studentId: StudentId,
    costNanodollars: Long,
    createdAt: Instant? = null,
  ) = attributeToSynthesis(studentId, appendCall(costNanodollars, createdAt))

  /** Upserts a subscription row for [studentId] over `[periodStart, periodEnd)`. */
  private fun subscribe(
    studentId: StudentId,
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    periodStart: Instant = Instant.now().minus(10, ChronoUnit.DAYS),
    periodEnd: Instant = Instant.now().plus(20, ChronoUnit.DAYS),
    productId: String = "coach.uni.UnicoachiOS.monthly10",
    originalTransactionId: String = "bs-${studentId.value}",
  ) {
    SubscriptionsDao
      .upsert(session, studentId, originalTransactionId, productId, status, periodStart, periodEnd)
      .getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `a student with no attributed call is entitled at zero spend`() {
    val studentId = createStudent()

    val verdict = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertEquals(0L, verdict.spent.value)
    assertEquals(5_000_000_000L, verdict.allowance.value)
    assertFalse(verdict.exhausted)
    assertEquals(0, verdict.usedPercent)
  }

  @Test
  fun `spend sums across owners and crosses the allowance`() {
    val studentId = createStudent()
    attributeToSynthesis(studentId, appendCall(1_000_000_000))
    attributeToChatTurn(studentId, appendCall(1_500_000_000))

    val underAllowance = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()
    assertEquals(2_500_000_000L, underAllowance.spent.value, "both owners' calls are summed")
    assertFalse(underAllowance.exhausted)
    assertEquals(50, underAllowance.usedPercent)

    spend(studentId, 3_000_000_000)

    val overAllowance = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()
    assertEquals(5_500_000_000L, overAllowance.spent.value)
    assertTrue(overAllowance.exhausted, "spend past the allowance exhausts")
    assertEquals(100, overAllowance.usedPercent)
  }

  @Test
  fun `another student's spend does not count against this one`() {
    val studentId = createStudent()
    val otherStudentId = createStudent()
    spend(otherStudentId, 9_000_000_000)

    val verdict = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertEquals(0L, verdict.spent.value)
    assertFalse(verdict.exhausted)
  }

  @Test
  fun `an uncosted call contributes zero — the gate fails open on the ledger's gap`() {
    val studentId = createStudent()
    spend(studentId, 1_000_000_000)
    attributeToSynthesis(studentId, appendCall(costNanodollars = null))

    val verdict = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertEquals(1_000_000_000L, verdict.spent.value, "the NULL-cost call flips nothing")
    assertFalse(verdict.exhausted)
  }

  @Test
  fun `both overloads return the same verdict for the same student`() {
    val studentId = createStudent()
    spend(studentId, 2_500_000_000)
    val service = testBudgetService(database, "5.00")

    val inTransaction = service.entitlement(session, studentId).getOrThrow()
    val standalone = runBlocking { service.entitlement(studentId).getOrThrow() }

    assertEquals(inTransaction.spent.value, standalone.spent.value)
    assertEquals(inTransaction.allowance.value, standalone.allowance.value)
    assertEquals(inTransaction.exhausted, standalone.exhausted)
    assertEquals(inTransaction.usedPercent, standalone.usedPercent)
  }

  @Test
  fun `a zero allowance exhausts a student who has spent nothing`() {
    val studentId = createStudent()

    val verdict = testBudgetService(database, "0.00").entitlement(session, studentId).getOrThrow()

    assertTrue(verdict.exhausted, "the kill switch blocks a student with no spend at all")
    assertEquals(100, verdict.usedPercent)
  }

  @Test
  fun `the gate names both outcomes and carries the deciding entitlement into the refusal`() {
    val studentId = createStudent()
    val service = testBudgetService(database, "5.00")
    spend(studentId, 2_500_000_000)

    assertEquals(BudgetVerdict.Entitled, service.verdict(session, studentId).getOrThrow(), "under the allowance the pass proceeds")

    spend(studentId, 2_500_000_000)

    val exhausted =
      assertIs<BudgetVerdict.Exhausted>(
        service.verdict(session, studentId).getOrThrow(),
        "at the allowance the gate refuses under its own name, not a missing value",
      )
    assertEquals(5_000_000_000L, exhausted.entitlement.spent.value, "the refusal states the spend it was decided on")
    assertEquals(5_000_000_000L, exhausted.entitlement.allowance.value, "and the allowance it was decided against")
  }

  // ---------------------------------------------------------------------------
  // Subscribed branch (RFC 110)
  // ---------------------------------------------------------------------------

  /** The plan table's monthly10 budget: y = 0.5 of $9.99. */
  private val periodBudget = 4_995_000_000L

  @Test
  fun `an active subscription meters the period window against the plan budget`() {
    val studentId = createStudent()
    val periodEnd = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    subscribe(studentId, periodEnd = periodEnd)
    spend(studentId, 1_000_000_000)

    val verdict = testBudgetService(database, "0.00").entitlement(session, studentId).getOrThrow()

    assertEquals(EntitlementBasis.Subscription(periodEnd), verdict.basis, "the basis carries the period_end as its reset point")
    assertEquals(1_000_000_000L, verdict.spent.value)
    assertEquals(periodBudget, verdict.allowance.value, "the allowance is y × price, not the free allowance")
    assertFalse(verdict.exhausted, "the zero FREE allowance is irrelevant on the subscribed branch")
    assertEquals(periodEnd, verdict.resetsAt, "resetsAt is the period_end")
  }

  @Test
  fun `period spend at the plan budget exhausts the subscribed student`() {
    val studentId = createStudent()
    subscribe(studentId)
    spend(studentId, periodBudget)

    val verdict = testBudgetService(database, "1000000.00").entitlement(session, studentId).getOrThrow()

    assertTrue(verdict.exhausted, "the generous FREE allowance is irrelevant on the subscribed branch")
    assertEquals(100, verdict.usedPercent)
  }

  @Test
  fun `spend before period_start does not count against the period budget`() {
    val studentId = createStudent()
    subscribe(studentId, periodStart = Instant.now().minus(10, ChronoUnit.DAYS))
    // Free-tier-era spend, well past the plan budget but before the window.
    spend(studentId, 100_000_000_000L, createdAt = Instant.now().minus(30, ChronoUnit.DAYS))

    val verdict = testBudgetService(database, "0.00").entitlement(session, studentId).getOrThrow()

    assertIs<EntitlementBasis.Subscription>(verdict.basis)
    assertEquals(0L, verdict.spent.value, "windowedCost's created_at bound excludes pre-period spend")
    assertFalse(verdict.exhausted)
  }

  @Test
  fun `rollover restores entitlement — overshoot forgiven, nothing carried forward`() {
    val studentId = createStudent()
    val service = testBudgetService(database, "0.00")

    // Window W1 covers now; its spend overshoots the budget → exhausted.
    subscribe(
      studentId,
      periodStart = Instant.now().minus(2, ChronoUnit.DAYS),
      periodEnd = Instant.now().plus(1, ChronoUnit.DAYS),
    )
    spend(studentId, periodBudget * 2, createdAt = Instant.now().minus(1, ChronoUnit.DAYS))
    assertTrue(service.entitlement(session, studentId).getOrThrow().exhausted)

    // The renewal: the same row's window advances past every W1 spend. No reset
    // action, no counter zeroed — the very next windowed read IS the rollover.
    subscribe(
      studentId,
      periodStart = Instant.now().minus(1, ChronoUnit.HOURS),
      periodEnd = Instant.now().plus(29, ChronoUnit.DAYS),
    )

    val rolled = service.entitlement(session, studentId).getOrThrow()
    assertEquals(0L, rolled.spent.value, "W1's overshoot does not reduce W2's budget, and nothing is carried forward")
    assertEquals(periodBudget, rolled.allowance.value, "the same y × price budget every period")
    assertFalse(rolled.exhausted, "the exhausted student is entitled again the instant the row carries the new window")
  }

  @Test
  fun `an expired or out-of-window row falls back to the free branch`() {
    val expiredStudent = createStudent()
    subscribe(expiredStudent, status = SubscriptionStatus.EXPIRED)
    val expired = testBudgetService(database, "5.00").entitlement(session, expiredStudent).getOrThrow()
    assertEquals(EntitlementBasis.FreeAllowance, expired.basis, "a lapsed subscriber meters as a free-tier student")
    assertEquals(null, expired.resetsAt)

    val lapsedStudent = createStudent()
    subscribe(
      lapsedStudent,
      periodStart = Instant.now().minus(60, ChronoUnit.DAYS),
      periodEnd = Instant.now().minus(30, ChronoUnit.DAYS),
    )
    val lapsed = testBudgetService(database, "5.00").entitlement(session, lapsedStudent).getOrThrow()
    assertEquals(EntitlementBasis.FreeAllowance, lapsed.basis, "an elapsed window is not current")
  }

  @Test
  fun `a grace row stays on the subscription branch`() {
    val studentId = createStudent()
    subscribe(studentId, status = SubscriptionStatus.GRACE)

    val verdict = testBudgetService(database, "0.00").entitlement(session, studentId).getOrThrow()

    assertIs<EntitlementBasis.Subscription>(verdict.basis, "grace is entitling")
  }

  @Test
  fun `a current subscription with an unconfigured product fails closed`() {
    val studentId = createStudent()
    subscribe(studentId, productId = "coach.uni.UnicoachiOS.retired99")

    val result = testBudgetService(database, "5.00").entitlement(session, studentId)

    assertTrue(result.isFailure, "config drift never silently grants or denies")
    val message = result.exceptionOrNull()!!.message!!
    assertTrue(message.contains("coach.uni.UnicoachiOS.retired99"), "the failure names the product: $message")
  }

  @Test
  fun `a failed read surfaces as a failure, never an entitled verdict`() {
    val studentId = createStudent()
    val closedDatabase =
      Database(
        DatabaseConfig
          .from(
            ed.unicoach.common.config.AppConfig
              .load("common.conf", "db.conf")
              .getOrThrow(),
          ).getOrThrow(),
      ).also { it.close() }

    val result = runBlocking { testBudgetService(closedDatabase, "5.00").entitlement(studentId) }

    assertTrue(result.isFailure, "an unreadable meter is coaching unavailability, not a free pass")
  }
}
