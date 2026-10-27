package ed.unicoach.coaching.budget

import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.LlmCallRowFixture
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.UserId
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
 * Exercises [BudgetService] against the real cost spines: the ledger total it
 * reads, the allowance it pairs that total with, the agreement of its overloads,
 * the [BudgetVerdict] its gate decides from them, and its refusal to report an
 * entitled verdict when the read itself fails.
 *
 * The subject is the ACCOUNT since RFC 244, so the student tests below are the
 * REGRESSION: every one of them is the test it was before the key moved, and the
 * numbers are the numbers it read then — the account's meter (`account_llm_cost`)
 * re-keys `student_llm_cost` and unions a supporter arm that is empty for a
 * student-only account. The supporter tests are the new half, on the same two
 * branches with the same allowances: no supporter-specific allowance, and
 * [EntitlementBasis] stays a sealed pair.
 *
 * Spend is seeded through [LlmCallRowFixture] — the one costed-call fixture every
 * cost spine's suite uses — plus the owner row that attributes it.
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
      // system_prompts is deliberately NOT truncated: it is the migration-seeded,
      // immutable catalog (RFC 33/0007) that every other module's tests on this
      // shared database read. bin/test re-migrates before every run, so it is
      // already complete; wiping it and hand-restoring a stale list left the seeds
      // partial for whoever ran next (RFC 129).
      stmt.execute(
        "TRUNCATE TABLE convos, convo_requests, extraction_runs, synthesis_runs, fit_lens_runs, fit_suggestions, " +
          "supporter_llm_costs, supporters, llm_requests, llm_responses, llm_responses_raw, colleges, " +
          "subscriptions, students, users CASCADE",
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

  private fun createStudent(isAdmin: Boolean = false): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash, is_admin) VALUES ('$userId', 'bs-$userId@test.com', 'Budget User', 'ahash', $isAdmin)",
      )
      StudentRowFixture.insert(connection, studentId, userId)
    }
    return StudentId(studentId)
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
   * One completed call at [costNanodollars] (null = uncosted), timestamped
   * [createdAt] (null = the DB clock's now) — the timestamp the subscription
   * meter windows by. [LlmCallRowFixture] is the one fixture that writes this
   * pair.
   */
  private fun appendCall(
    costNanodollars: Long?,
    createdAt: Instant? = null,
  ): Long = LlmCallRowFixture.insert(connection, costNanodollars, createdAt = createdAt)

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
    connection.prepareStatement("INSERT INTO convos (id, student_id, name, party) VALUES (?, ?, 'Convo', 'student')").use { stmt ->
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

  /**
   * Upserts a subscription row for [userId]'s ACCOUNT over
   * `[periodStart, periodEnd)` — who pays, since RFC 244.
   */
  private fun subscribeAccount(
    userId: UserId,
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    periodStart: Instant = Instant.now().minus(10, ChronoUnit.DAYS),
    periodEnd: Instant = Instant.now().plus(20, ChronoUnit.DAYS),
    productId: String = "coach.uni.UnicoachiOS.monthly10",
    originalTransactionId: String = "bs-${userId.value}",
  ) {
    SubscriptionsDao
      .upsert(session, userId, originalTransactionId, productId, status, periodStart, periodEnd)
      .getOrThrow()
  }

  /**
   * [subscribeAccount] for a student fixture: the row lands on the account that
   * student profile belongs to, which is the hop the gate itself makes. Every
   * subscribed-branch test below reads unchanged through it, which is the point —
   * the key moved and the answers did not.
   */
  private fun subscribe(
    studentId: StudentId,
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    periodStart: Instant = Instant.now().minus(10, ChronoUnit.DAYS),
    periodEnd: Instant = Instant.now().plus(20, ChronoUnit.DAYS),
    productId: String = "coach.uni.UnicoachiOS.monthly10",
    originalTransactionId: String = "bs-${studentId.value}",
  ) = subscribeAccount(accountOf(studentId), status, periodStart, periodEnd, productId, originalTransactionId)

  /** The one indexed hop from a student to the account that pays for them. */
  private fun accountOf(studentId: StudentId): UserId =
    connection.prepareStatement("SELECT user_id FROM students WHERE id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        UserId(UUID.fromString(rs.getString("user_id")))
      }
    }

  /** An account with a `supporters` row and no student profile — a parent (RFC 241). */
  private fun createSupporter(isAdmin: Boolean = false): SupporterId {
    val userId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash, is_admin) VALUES ('$userId', 'bsp-$userId@test.com', 'Parent', 'ahash', $isAdmin)",
      )
    }
    return SupportersDao.create(session, NewSupporter(UserId(userId))).getOrThrow().id
  }

  /** The account a `supporters` row belongs to — the payer on the adult side. */
  private fun accountOfSupporter(supporterId: SupporterId): UserId =
    connection.prepareStatement("SELECT user_id FROM supporters WHERE id = ?").use { stmt ->
      stmt.setObject(1, supporterId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        UserId(UUID.fromString(rs.getString("user_id")))
      }
    }

  /** The row `family/06` will write per parent-coaching call, at a known cost. */
  private fun supporterSpend(
    supporterId: SupporterId,
    costNanodollars: Long,
    createdAt: Instant? = null,
  ) = LlmCallRowFixture.attributeToSupporter(connection, supporterId.value, appendCall(costNanodollars, createdAt))

  /**
   * The parent surface's read, made exactly as `GET
   * /api/v1/supporters/me/coaching-usage` makes it (RFC 244): the account the
   * `supporters` row hangs off, through the account-keyed entry. There is no
   * supporter-keyed entry to call — the account is the subject, and a supporter's
   * spend reaches it through `account_llm_cost`.
   */
  private fun entitlementOfSupporter(
    service: BudgetService,
    supporterId: SupporterId,
  ): Result<Entitlement> = runBlocking { service.entitlement(accountOfSupporter(supporterId)) }

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

  // ---------------------------------------------------------------------------
  // The adult side (RFC 244): the same two branches, on the supporter's account
  // ---------------------------------------------------------------------------

  @Test
  fun `a supporter with no subscription meters the free allowance`() {
    val supporterId = createSupporter()
    supporterSpend(supporterId, 2_500_000_000)

    val entitlement = entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId).getOrThrow()

    assertEquals(EntitlementBasis.FreeAllowance, entitlement.basis, "the same basis every unsubscribed account gets")
    assertEquals(2_500_000_000L, entitlement.spent.value, "a parent's spend is attributable — this slice's criterion")
    assertEquals(5_000_000_000L, entitlement.allowance.value, "the same $5.00, with no supporter-specific key")
    assertFalse(entitlement.exhausted)
    assertEquals(50, entitlement.usedPercent)
    assertEquals(null, entitlement.resetsAt, "a lifetime allowance never resets")
  }

  @Test
  fun `a supporter exhausts at the free allowance`() {
    val supporterId = createSupporter()
    supporterSpend(supporterId, 5_000_000_000)

    // The verdict itself is subject-agnostic ([verdictOf] reads `exhausted`) and
    // is pinned on the student side; what the adult side has to prove is that the
    // parent's own spend reaches the meter the gate decides from.
    val entitlement = entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId).getOrThrow()

    assertEquals(5_000_000_000L, entitlement.spent.value)
    assertEquals(5_000_000_000L, entitlement.allowance.value)
    assertEquals(100, entitlement.usedPercent)
    assertTrue(entitlement.exhausted, "at the allowance the meter is spent, which is what the gate refuses on")
  }

  @Test
  fun `a supporter under the allowance is not exhausted`() {
    val supporterId = createSupporter()
    supporterSpend(supporterId, 1L)

    assertFalse(entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId).getOrThrow().exhausted)
  }

  @Test
  fun `a supporter whose account holds a subscription meters over its period`() {
    val supporterId = createSupporter()
    val periodEnd = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    subscribeAccount(accountOfSupporter(supporterId), periodEnd = periodEnd)
    supporterSpend(supporterId, 1_000_000_000)
    // Pre-period spend, well past the plan budget: the window must exclude it.
    supporterSpend(supporterId, 100_000_000_000L, createdAt = Instant.now().minus(30, ChronoUnit.DAYS))

    val entitlement = entitlementOfSupporter(testBudgetService(database, "0.00"), supporterId).getOrThrow()

    assertEquals(EntitlementBasis.Subscription(periodEnd), entitlement.basis, "the account paid, so the period is the meter")
    assertEquals(1_000_000_000L, entitlement.spent.value, "only spend inside [period_start, period_end)")
    assertEquals(periodBudget, entitlement.allowance.value, "the same y × price budget a student's subscription buys")
    assertFalse(entitlement.exhausted, "the zero FREE allowance is irrelevant on the subscribed branch")
    assertEquals(periodEnd, entitlement.resetsAt)
  }

  @Test
  fun `one account, one subscription — a dual-role account reads one meter from both surfaces`() {
    // The account RFC 244 is keyed on: one human who is both a student and a
    // supporter. Apple will not sell them two subscriptions, so their one
    // subscription carries their spend on both sides, and the two surfaces must
    // never quote different percentages.
    val studentId = createStudent()
    val userId = accountOf(studentId)
    val supporterId = SupportersDao.create(session, NewSupporter(userId)).getOrThrow().id
    val periodEnd = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
    subscribeAccount(userId, periodEnd = periodEnd)
    spend(studentId, 1_000_000_000)
    supporterSpend(supporterId, 500_000_000)

    val service = testBudgetService(database, "5.00")
    val asStudent = service.entitlement(session, studentId).getOrThrow()
    val asSupporter = entitlementOfSupporter(service, supporterId).getOrThrow()

    assertEquals(1_500_000_000L, asStudent.spent.value, "the account's two arms sum")
    assertEquals(asStudent.spent.value, asSupporter.spent.value, "and both surfaces read the same total")
    assertEquals(asStudent.allowance.value, asSupporter.allowance.value)
    assertEquals(asStudent.usedPercent, asSupporter.usedPercent, "a blocked parent and a 30% student bar cannot coexist")
    assertEquals(EntitlementBasis.Subscription(periodEnd), asSupporter.basis)
  }

  @Test
  fun `another account's spend does not count against a supporter`() {
    val supporterId = createSupporter()
    val otherSupporter = createSupporter()
    supporterSpend(otherSupporter, 9_000_000_000)
    val studentId = createStudent()
    spend(studentId, 9_000_000_000)

    val entitlement = entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId).getOrThrow()

    assertEquals(0L, entitlement.spent.value, "nobody rides another account's meter, in either direction")
    assertFalse(entitlement.exhausted)
  }

  @Test
  fun `a lapsed subscription on a supporter's account falls back to the free branch`() {
    val supporterId = createSupporter()
    subscribeAccount(accountOfSupporter(supporterId), status = SubscriptionStatus.REVOKED)

    val entitlement = entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId).getOrThrow()

    assertEquals(EntitlementBasis.FreeAllowance, entitlement.basis, "a revoked row is not current, on either side of the house")
  }

  @Test
  fun `config drift on a supporter's subscription fails closed`() {
    val supporterId = createSupporter()
    subscribeAccount(accountOfSupporter(supporterId), productId = "coach.uni.UnicoachiOS.retired99")

    val result = entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId)

    assertTrue(result.isFailure, "config drift never silently grants or denies")
    val message = result.exceptionOrNull()!!.message!!
    assertTrue(message.contains("coach.uni.UnicoachiOS.retired99"), "the failure names the product: $message")
  }

  @Test
  fun `a student id that names no row fails CLOSED as config drift does, not as an absence`() {
    val studentId = StudentId(UUID.randomUUID())

    val result = testBudgetService(database, "5.00").entitlement(session, studentId)

    assertTrue(result.isFailure, "an unknown subject is a broken reference, not a subject with a full allowance")
    val cause = result.exceptionOrNull()!!
    // WHICH failure decides what the client is told. `NotFoundException` is this
    // corpus's word for an ordinary absence and `rest/plugins/StatusPages.kt`
    // publishes it as `404 permanent_error` carrying the DAO's own message — so a
    // corrupt subject reference would be announced as "no such student", with a
    // status that tells the client to stop asking. It is a 500, like the config
    // drift the gate's own doc equates it with, and that is this assertion.
    assertFalse(cause is NotFoundException, "a dangling reference published as a 404 is the defect: $cause")
    val failure = assertIs<UnknownBudgetSubjectException>(cause)
    assertIs<IllegalStateException>(failure, "fails closed as config drift does, not as an absence")
    assertEquals(
      studentId,
      failure.studentId,
      "and the failure CARRIES the reference that dangles, as the typed id, not as prose in its message",
    )
  }

  @Test
  fun `a failed read for a supporter surfaces as a failure, never an entitled verdict`() {
    val supporterId = createSupporter()
    val closedDatabase =
      Database(
        DatabaseConfig
          .from(
            ed.unicoach.common.config.AppConfig
              .load("common.conf", "db.conf")
              .getOrThrow(),
          ).getOrThrow(),
      ).also { it.close() }

    val result = entitlementOfSupporter(testBudgetService(closedDatabase, "5.00"), supporterId)

    assertTrue(result.isFailure, "an unreadable meter is coaching unavailability, not a free pass")
  }

  // ---------------------------------------------------------------------------
  // The admin exemption (RFC 255): the operator's own account is never blocked
  // ---------------------------------------------------------------------------

  @Test
  fun `an admin account past the free allowance is still entitled`() {
    val studentId = createStudent(isAdmin = true)
    spend(studentId, 9_000_000_000)

    val entitlement = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertFalse(entitlement.exhausted)
    assertEquals(0, entitlement.usedPercent)
    assertEquals(Nanodollars.MAX_FOR_PERCENTAGE.value, entitlement.allowance.value)
    assertEquals(EntitlementBasis.AdminExemption, entitlement.basis)
    assertEquals(null, entitlement.resetsAt)
  }

  @Test
  fun `an admin account's real spend is still reported`() {
    val studentId = createStudent(isAdmin = true)
    spend(studentId, 2_500_000_000)

    val entitlement = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertEquals(2_500_000_000L, entitlement.spent.value)
    assertFalse(entitlement.exhausted)
  }

  @Test
  fun `an admin holding a current subscription is still exempt`() {
    val studentId = createStudent(isAdmin = true)
    subscribe(studentId)
    spend(studentId, periodBudget)

    val entitlement = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertFalse(entitlement.exhausted)
    assertEquals(EntitlementBasis.AdminExemption, entitlement.basis)
  }

  @Test
  fun `an admin is exempt through the supporter surface too`() {
    val supporterId = createSupporter(isAdmin = true)
    supporterSpend(supporterId, 9_000_000_000)

    val entitlement = entitlementOfSupporter(testBudgetService(database, "5.00"), supporterId).getOrThrow()

    assertFalse(entitlement.exhausted)
    assertEquals(0, entitlement.usedPercent)
  }

  @Test
  fun `a non-admin account with the same spend still exhausts`() {
    val studentId = createStudent()
    spend(studentId, 9_000_000_000)

    val entitlement = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertTrue(entitlement.exhausted)
    assertEquals(100, entitlement.usedPercent)
  }

  @Test
  fun `a soft-deleted admin account is not exempt`() {
    val studentId = createStudent(isAdmin = true)
    val userId = accountOf(studentId)
    connection.createStatement().use { stmt ->
      stmt.execute("UPDATE users SET deleted_at = now(), version = version + 1 WHERE id = '${userId.value}'")
    }
    spend(studentId, 9_000_000_000)

    val entitlement = testBudgetService(database, "5.00").entitlement(session, studentId).getOrThrow()

    assertTrue(entitlement.exhausted)
    assertEquals(100, entitlement.usedPercent)
  }
}
