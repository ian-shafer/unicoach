package ed.unicoach.db.dao

import ed.unicoach.db.models.LlmCostAggregate
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.UserId
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
import kotlin.test.assertFailsWith

/**
 * Real-DB coverage for [AccountLlmCostDao] over the `account_llm_cost` spine
 * (`db/schema/account-llm-cost.sql`, RFC 244): the meter the budget gate reads now
 * that the entitlement subject is an ACCOUNT.
 *
 * Three claims are load-bearing and each is asserted against numbers read from the
 * OTHER two spines in the same test, not against a constant:
 * 1. For an account that is only a student, this view's total EQUALS
 *    `student_llm_cost`'s for that student — the regression the key move promises.
 * 2. For a dual-role account (one human holding both a `students` and a
 *    `supporters` row, which `SupporterService.ensure` permits), the two arms SUM.
 *    That is the right answer for one account with one subscription.
 * 3. The reading policy is the shared one ([LLM_COST_AGGREGATE_PROJECTION]):
 *    uncosted calls contribute 0 and are counted, estimated calls are counted AND
 *    in the sum.
 *
 * The costed call itself comes from [LlmCallRowFixture]; the owner rows are raw
 * SQL on both sides — `convo_requests` for the student arm (one of the four owner
 * tables 0041 unions is enough to reach the view) and `supporter_llm_costs` for
 * the supporter arm, whose first product writer is `family/06`.
 *
 * The two owner-side views are compared against by reading them DIRECTLY
 * ([spineTotal]): since RFC 244 there is one cost DAO, so a per-role read exists
 * nowhere to borrow.
 */
class AccountLlmCostDaoTest {
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
      // immutable catalog every module's tests on this shared database read.
      stmt.execute(
        "TRUNCATE TABLE supporter_llm_costs, supporters, convo_requests, convos, llm_requests, llm_responses, " +
          "llm_responses_raw, students, users CASCADE",
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
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Account Cost User', 'ahash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "alcd-$userId@test.com")
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  private fun createStudent(userId: UserId): StudentId {
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return StudentId(studentId)
  }

  /** Through the sanctioned writer, so the row is exactly what the product's own mint makes. */
  private fun createSupporter(userId: UserId): SupporterId = SupportersDao.create(session, NewSupporter(userId)).getOrThrow().id

  private fun createSystemPrompt(): UUID {
    val id = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'coach', ?, 'coach')")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.setString(2, "v-$id")
        stmt.executeUpdate()
      }
    return id
  }

  /** One completed call at a known frozen cost — [LlmCallRowFixture], the fixture every cost spine seeds through. */
  private fun appendCall(
    costNanodollars: Long?,
    costIsEstimated: Boolean?,
    createdAt: Instant? = null,
  ): Long = LlmCallRowFixture.insert(connection, costNanodollars, costIsEstimated, createdAt)

  /** A chat turn charged to the student — one of the four owner tables 0041 unions. */
  private fun studentSpend(
    studentId: StudentId,
    costNanodollars: Long?,
    costIsEstimated: Boolean? = costNanodollars?.let { false },
    createdAt: Instant? = null,
  ) {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId.value)
      stmt.executeUpdate()
    }
    val requestId = appendCall(costNanodollars, costIsEstimated, createdAt)
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq'))
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, createSystemPrompt())
        stmt.setLong(3, requestId)
        stmt.executeUpdate()
      }
  }

  /** The row `family/06` will write per parent-coaching call. */
  private fun supporterSpend(
    supporterId: SupporterId,
    costNanodollars: Long?,
    costIsEstimated: Boolean? = costNanodollars?.let { false },
    createdAt: Instant? = null,
  ) = LlmCallRowFixture.attributeToSupporter(
    connection,
    supporterId.value,
    appendCall(costNanodollars, costIsEstimated, createdAt),
  )

  private fun lifetime(userId: UserId) = AccountLlmCostDao.lifetimeCost(session, userId).getOrThrow()

  /**
   * One owner-side view's own answer, read directly through the projection the
   * account spine reads it with ([LLM_COST_AGGREGATE_PROJECTION]). The claims
   * below compare the account's total against the view it re-keys, and since
   * RFC 244 left one cost DAO there is no per-role read to ask instead.
   */
  private fun spineTotal(
    view: String,
    keyColumn: String,
    key: UUID,
  ): LlmCostAggregate =
    connection.prepareStatement("$LLM_COST_AGGREGATE_PROJECTION FROM $view WHERE $keyColumn = ?").use { stmt ->
      stmt.setObject(1, key)
      stmt.executeQuery().use { rs ->
        rs.next()
        mapLlmCostAggregate(rs)
      }
    }

  // ---------------------------------------------------------------------------
  // The regression: a student-only account reads what it always read
  // ---------------------------------------------------------------------------

  @Test
  fun `an account with no spend reads a genuine zero`() {
    val userId = createUser()
    createStudent(userId)

    val cost = lifetime(userId)

    assertEquals(0L, cost.costNanodollars.value, "COALESCE gives 0, not a NULL, for an account that has spent nothing")
    assertEquals(0, cost.uncostedCalls)
    assertEquals(0, cost.estimatedCalls)
  }

  @Test
  fun `a student-only account's total EQUALS the student spine's total for that student`() {
    val userId = createUser()
    val studentId = createStudent(userId)
    studentSpend(studentId, 100)
    studentSpend(studentId, 250)
    studentSpend(studentId, costNanodollars = null, costIsEstimated = null)
    studentSpend(studentId, 7, costIsEstimated = true)

    val account = lifetime(userId)
    val student = spineTotal("student_llm_cost", "student_id", studentId.value)

    assertEquals(
      student.costNanodollars.value,
      account.costNanodollars.value,
      "the union's supporter arm is empty for this account, so re-keying changed no number",
    )
    assertEquals(student.uncostedCalls, account.uncostedCalls)
    assertEquals(student.estimatedCalls, account.estimatedCalls)
    assertEquals(357L, account.costNanodollars.value, "and the number itself: 100 + 250 + 0 (uncosted) + 7 (estimated)")
    assertEquals(1, account.uncostedCalls)
    assertEquals(1, account.estimatedCalls)
  }

  @Test
  fun `a supporter-only account meters its own spend, which no student spine can see`() {
    val userId = createUser()
    val supporterId = createSupporter(userId)
    supporterSpend(supporterId, 900)

    assertEquals(900L, lifetime(userId).costNanodollars.value, "a parent's spend is attributable — this slice's criterion")

    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COALESCE(SUM(cost_nanodollars), 0) AS cost FROM student_llm_cost").use { rs ->
        rs.next()
        assertEquals(0L, rs.getLong("cost"), "and it is on no student's meter")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // The dual-role account: one account, one subscription, one total
  // ---------------------------------------------------------------------------

  @Test
  fun `a dual-role account sums its student and supporter spend`() {
    val userId = createUser()
    val studentId = createStudent(userId)
    val supporterId = createSupporter(userId)
    studentSpend(studentId, 1_000)
    supporterSpend(supporterId, 250)

    val cost = lifetime(userId)

    assertEquals(1_250L, cost.costNanodollars.value, "one account, one subscription, one total")
    assertEquals(
      1_000L,
      spineTotal("student_llm_cost", "student_id", studentId.value).costNanodollars.value,
      "and neither owner-side view was rewritten: the student spine still answers only the student's share",
    )
    assertEquals(
      250L,
      spineTotal("supporter_llm_cost", "supporter_id", supporterId.value).costNanodollars.value,
    )
  }

  @Test
  fun `one account's spend is never counted for another`() {
    val payer = createUser()
    val payerStudent = createStudent(payer)
    studentSpend(payerStudent, 500)

    val other = createUser()
    val otherSupporter = createSupporter(other)
    supporterSpend(otherSupporter, 3)

    assertEquals(500L, lifetime(payer).costNanodollars.value)
    assertEquals(3L, lifetime(other).costNanodollars.value, "the user_id predicate is doing real work on both arms")
  }

  @Test
  fun `two identical calls both count — the union does not collapse them`() {
    val userId = createUser()
    val studentId = createStudent(userId)
    val at = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    studentSpend(studentId, 40, createdAt = at)
    studentSpend(studentId, 40, createdAt = at)

    assertEquals(80L, lifetime(userId).costNanodollars.value, "UNION ALL: same instant, same cost, still two calls")
  }

  // ---------------------------------------------------------------------------
  // The window — the subscription meter's read
  // ---------------------------------------------------------------------------

  @Test
  fun `windowedCost includes the start instant and excludes the end`() {
    val userId = createUser()
    val studentId = createStudent(userId)
    val supporterId = createSupporter(userId)
    val start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(10, ChronoUnit.DAYS)
    val end = start.plus(30, ChronoUnit.DAYS)

    studentSpend(studentId, 11, createdAt = start.minusMillis(1))
    studentSpend(studentId, 22, createdAt = start)
    supporterSpend(supporterId, 33, createdAt = end.minusMillis(1))
    supporterSpend(supporterId, 44, createdAt = end)

    val windowed = AccountLlmCostDao.windowedCost(session, userId, start, end).getOrThrow()

    assertEquals(55L, windowed.costNanodollars.value, "[start, end): 22 + 33, on both arms")
    assertEquals(110L, lifetime(userId).costNanodollars.value, "and the lifetime read still sees all four")
  }

  @Test
  fun `windowedCost refuses an inverted window rather than answering zero`() {
    val userId = createUser()
    val now = Instant.now()

    assertFailsWith<IllegalArgumentException> {
      AccountLlmCostDao.windowedCost(session, userId, now, now.minusSeconds(1))
    }
    assertFailsWith<IllegalArgumentException>("an empty window is not a no-spend window either") {
      AccountLlmCostDao.windowedCost(session, userId, now, now)
    }
  }

  @Test
  fun `an uncosted call is counted as a gap and an estimated one is in the sum, on both arms`() {
    val userId = createUser()
    val studentId = createStudent(userId)
    val supporterId = createSupporter(userId)
    studentSpend(studentId, costNanodollars = null, costIsEstimated = null)
    studentSpend(studentId, 5, costIsEstimated = true)
    supporterSpend(supporterId, costNanodollars = null, costIsEstimated = null)
    supporterSpend(supporterId, 6, costIsEstimated = true)

    val cost = lifetime(userId)

    assertEquals(11L, cost.costNanodollars.value, "estimated costs ARE in the sum (RFC 108's bias, inherited)")
    assertEquals(2, cost.uncostedCalls, "one gap from each arm, so the reader knows the total is an under-count")
    assertEquals(2, cost.estimatedCalls)
  }

  @Test
  fun `an orphan call on either side is excluded`() {
    val userId = createUser()
    val studentId = createStudent(userId)
    createSupporter(userId)
    studentSpend(studentId, 70)

    // A logged, costed call no owner row references — the crash window. Both arms
    // join INNER, so it is unattributed rather than misattributed.
    appendCall(costNanodollars = 9_999, costIsEstimated = false)

    val cost = lifetime(userId)
    assertEquals(70L, cost.costNanodollars.value)
    assertEquals(0, cost.uncostedCalls, "and it is not counted as a gap either, because it is not attributed")
  }
}
