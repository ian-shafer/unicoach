package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-DB coverage for the `supporter_llm_cost` per-call attribution spine
 * (`db/schema/supporter-llm-cost.sql`, RFC 244): the supporter-side dollar total,
 * lifetime and `[start, end)`-windowed, plus the two counters that bound it —
 * `uncostedCalls` (NULL-cost calls) and `estimatedCalls` (default-priced calls).
 *
 * Read through [AccountLlmCostDao], because that is the only cost read production
 * makes: `account_llm_cost` re-keys this view onto the account the `supporters`
 * row hangs off, so every claim below is the claim as the budget gate sees it.
 * Each supporter here holds an account of its own, so the account's total IS this
 * view's total for that supporter.
 *
 * The spine is `student_llm_cost`'s mirror (0041, RFC 108) and inherits RFC 106's
 * attribution semantics — per-call grain, orphan exclusion, the response's
 * `created_at` as the window key — so each of those is re-pinned here on the
 * supporter side rather than assumed from the student side's tests.
 *
 * Every row below is inserted by hand, directly against the tables: these
 * tests pin the shape of the meter independent of its writer
 * ([SupporterLlmCostsDao]), because the writer and the budget gate both believe
 * whatever the meter tells them.
 *
 * The [StudentLlmCostViewTest] pattern: one connection in `@BeforeAll`, a
 * truncate before each test, and [LlmCallRowFixture] for the call itself.
 */
class SupporterLlmCostViewTest {
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
      // immutable catalog (RFC 33/0007) every other module's tests on this shared
      // database read (RFC 129). Nothing here needs one anyway — a supporter cost
      // row names no prompt.
      stmt.execute(
        "TRUNCATE TABLE supporter_llm_costs, supporters, llm_requests, llm_responses, llm_responses_raw, " +
          "students, users CASCADE",
      )
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Seeding
  // ---------------------------------------------------------------------------

  private fun createUser(): UserId {
    val userId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Supporter Cost User', 'ahash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "slcd-$userId@test.com")
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  /** Through the sanctioned writer, so the row is exactly what the product's own mint makes. */
  private fun createSupporter(): SupporterId = SupportersDao.create(session, NewSupporter(createUser())).getOrThrow().id

  /** One completed call at a known frozen cost — [LlmCallRowFixture], the fixture every cost spine seeds through. */
  private fun appendCall(
    costNanodollars: Long?,
    costIsEstimated: Boolean?,
    createdAt: Instant? = null,
  ): Long = LlmCallRowFixture.insert(connection, costNanodollars, costIsEstimated, createdAt)

  /** The owner row `family/06` will write: this call is this supporter's spend. */
  private fun attribute(
    supporterId: SupporterId,
    llmRequestId: Long,
  ) = LlmCallRowFixture.attributeToSupporter(connection, supporterId.value, llmRequestId)

  private fun spend(
    supporterId: SupporterId,
    costNanodollars: Long?,
    costIsEstimated: Boolean? = costNanodollars?.let { false },
    createdAt: Instant? = null,
  ) = attribute(supporterId, appendCall(costNanodollars, costIsEstimated, createdAt))

  /** The account a `supporters` row belongs to — the key the gate's meter is read by. */
  private fun accountOf(supporterId: SupporterId): UserId =
    connection.prepareStatement("SELECT user_id FROM supporters WHERE id = ?").use { stmt ->
      stmt.setObject(1, supporterId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        UserId(UUID.fromString(rs.getString("user_id")))
      }
    }

  /** This supporter's lifetime spend as the gate reads it: through the account their row hangs off. */
  private fun lifetime(supporterId: SupporterId) = AccountLlmCostDao.lifetimeCost(session, accountOf(supporterId)).getOrThrow()

  /** [lifetime] over `[start, end)`, the subscription meter's read. */
  private fun windowed(
    supporterId: SupporterId,
    start: Instant,
    end: Instant,
  ) = AccountLlmCostDao.windowedCost(session, accountOf(supporterId), start, end)

  // ---------------------------------------------------------------------------
  // The total
  // ---------------------------------------------------------------------------

  @Test
  fun `a supporter with no attributed call reads a genuine zero`() {
    val supporter = createSupporter()

    val cost = lifetime(supporter)

    assertEquals(0L, cost.costNanodollars.value, "COALESCE gives 0, not a NULL, for a supporter who has spent nothing")
    assertEquals(0, cost.uncostedCalls)
    assertEquals(0, cost.estimatedCalls)
  }

  @Test
  fun `lifetimeCost sums every attributed call for the supporter`() {
    val supporter = createSupporter()
    spend(supporter, 100)
    spend(supporter, 250)
    spend(supporter, 1_000_000_000)

    val cost = lifetime(supporter)

    assertEquals(100L + 250L + 1_000_000_000L, cost.costNanodollars.value)
    assertEquals(0, cost.uncostedCalls)
    assertEquals(0, cost.estimatedCalls)
  }

  @Test
  fun `one supporter's call is never counted for another`() {
    val payer = createSupporter()
    val other = createSupporter()
    spend(payer, 900)
    spend(other, 7)

    assertEquals(900L, lifetime(payer).costNanodollars.value)
    assertEquals(7L, lifetime(other).costNanodollars.value, "the supporter_id predicate is doing real work")
  }

  @Test
  fun `a supporter's spend is invisible to the student spine`() {
    val supporter = createSupporter()
    spend(supporter, 500)

    // The claim RFC 244 §4 rests on: adding this spine adds no writer to any
    // existing path, so no existing query changes its answer. `student_llm_cost`
    // unions four STUDENT-keyed owner tables and this row is in none of them.
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COALESCE(SUM(cost_nanodollars), 0) AS cost FROM student_llm_cost").use { rs ->
        rs.next()
        assertEquals(0L, rs.getLong("cost"), "a supporter's call does not appear on the student spine")
      }
    }
  }

  @Test
  fun `an unattributed orphan call is excluded from the total`() {
    val supporter = createSupporter()
    spend(supporter, 100)

    // A crash-window orphan: a logged, costed call no owner row references. The
    // view's INNER join is what excludes it — RFC 106's semantics, unchanged.
    appendCall(costNanodollars = 9999, costIsEstimated = false)

    val cost = lifetime(supporter)
    assertEquals(100L, cost.costNanodollars.value, "the orphan's cost is unattributed, never misattributed")
    assertEquals(0, cost.uncostedCalls, "and it is not even counted as a gap, because it is not attributed")
  }

  @Test
  fun `a call with no response row yet contributes nothing`() {
    val supporter = createSupporter()
    spend(supporter, 100)

    // The reverse window: the owner row is written in the caller's transaction and
    // the response in its own (LlmCallLog), so an attributed request with no
    // response is a real intermediate state. The INNER join reads it as absent
    // rather than as a NULL-cost gap.
    val pending =
      connection
        .prepareStatement(
          "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) " +
            "VALUES ('anthropic', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    attribute(supporter, pending)

    val cost = lifetime(supporter)
    assertEquals(100L, cost.costNanodollars.value)
    assertEquals(0, cost.uncostedCalls, "no response row is not the same fact as a response with no cost")
  }

  // ---------------------------------------------------------------------------
  // The two counters
  // ---------------------------------------------------------------------------

  @Test
  fun `an uncosted call is counted in uncostedCalls and contributes 0 to the sum`() {
    val supporter = createSupporter()
    spend(supporter, 300)
    spend(supporter, costNanodollars = null, costIsEstimated = null)

    val cost = lifetime(supporter)

    assertEquals(300L, cost.costNanodollars.value, "the NULL-cost call adds 0 via COALESCE")
    assertEquals(1, cost.uncostedCalls, "and is counted, so a reader knows the total is an under-count")
    assertEquals(0, cost.estimatedCalls)
  }

  @Test
  fun `an estimated call's cost IS in the sum, and the two counters are independent`() {
    val supporter = createSupporter()
    spend(supporter, 700, costIsEstimated = true)
    spend(supporter, costNanodollars = null, costIsEstimated = null)

    val cost = lifetime(supporter)

    assertEquals(700L, cost.costNanodollars.value, "an estimated call is priced, unlike an uncosted one (RFC 108)")
    assertEquals(1, cost.uncostedCalls)
    assertEquals(1, cost.estimatedCalls)
  }

  // ---------------------------------------------------------------------------
  // The window
  // ---------------------------------------------------------------------------

  @Test
  fun `windowedCost sums only calls in the half-open window`() {
    val supporter = createSupporter()
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val end = start.plusSeconds(2 * 3600)

    spend(supporter, 1, createdAt = start.minusSeconds(3600))
    spend(supporter, 10, createdAt = start)
    spend(supporter, 100, createdAt = start.plusSeconds(3600))
    spend(supporter, 1000, createdAt = end)

    val windowed = windowed(supporter, start, end).getOrThrow()

    assertEquals(10L + 100L, windowed.costNanodollars.value, "start is inclusive and end exclusive")
    assertEquals(1L + 10L + 100L + 1000L, lifetime(supporter).costNanodollars.value, "the lifetime read has no bound")
  }

  @Test
  fun `the window bounds the two counters as well as the sum`() {
    val supporter = createSupporter()
    val start = Instant.parse("2026-02-01T00:00:00Z")
    val end = start.plusSeconds(3600)

    spend(supporter, costNanodollars = null, costIsEstimated = null, createdAt = start.minusSeconds(60))
    spend(supporter, 5, costIsEstimated = true, createdAt = start.minusSeconds(60))
    spend(supporter, costNanodollars = null, costIsEstimated = null, createdAt = start)
    spend(supporter, 50, costIsEstimated = true, createdAt = start)

    val windowed = windowed(supporter, start, end).getOrThrow()

    assertEquals(50L, windowed.costNanodollars.value)
    assertEquals(1, windowed.uncostedCalls, "the pre-window gap is not this period's gap")
    assertEquals(1, windowed.estimatedCalls)
  }

  @Test
  fun `an inverted window is refused rather than read as an empty one`() {
    val supporter = createSupporter()
    val start = Instant.parse("2026-03-01T00:00:00Z")

    val failure =
      assertFailsWith<IllegalArgumentException> {
        windowed(supporter, start, start.minusSeconds(1))
      }

    assertTrue(failure.message!!.contains("must be before"), "the refusal states the rule: ${failure.message}")
  }

  // ---------------------------------------------------------------------------
  // The table's own guarantees
  // ---------------------------------------------------------------------------

  @Test
  fun `one call cannot be charged to two supporters`() {
    val first = createSupporter()
    val second = createSupporter()
    val requestId = appendCall(costNanodollars = 100, costIsEstimated = false)
    attribute(first, requestId)

    // The PRIMARY KEY on llm_request_id is what says this, and it is the reason
    // the column is the key rather than merely a foreign key.
    assertFailsWith<SQLException> { attribute(second, requestId) }

    assertEquals(100L, lifetime(first).costNanodollars.value)
    assertEquals(0L, lifetime(second).costNanodollars.value)
  }

  @Test
  fun `an attribution row cannot be updated`() {
    val supporter = createSupporter()
    val other = createSupporter()
    val requestId = appendCall(costNanodollars = 100, costIsEstimated = false)
    attribute(supporter, requestId)

    // Append-only, like llm_requests (0038) and convo_requests: attribution is a
    // fact about a call that already happened, so there is no correct UPDATE and
    // re-pointing one would move somebody else's spend onto this supporter.
    val refusal =
      assertFailsWith<SQLException> {
        connection.prepareStatement("UPDATE supporter_llm_costs SET supporter_id = ? WHERE llm_request_id = ?").use { stmt ->
          stmt.setObject(1, other.value)
          stmt.setLong(2, requestId)
          stmt.executeUpdate()
        }
      }

    assertTrue(
      refusal.message!!.contains("append-only"),
      "prevent_log_update's own sentence, not the entity guard's: ${refusal.message}",
    )
    assertEquals(100L, lifetime(supporter).costNanodollars.value, "and the spend stayed where it was")
  }

  @Test
  fun `a call may not be attributed to a supporter that does not exist`() {
    val requestId = appendCall(costNanodollars = 100, costIsEstimated = false)

    assertFailsWith<SQLException> { attribute(SupporterId(UUID.randomUUID()), requestId) }
  }
}
