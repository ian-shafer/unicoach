package ed.unicoach.db.dao

import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Subscription
import ed.unicoach.db.models.SubscriptionStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SubscriptionsDao] against the real versioned `subscriptions`
 * entity (RFC 110): the insert-or-refresh upsert with its state-distinct and
 * ownership guards, the gate's [SubscriptionsDao.findCurrent] read, the Apple-key
 * lookup, and the schema's own refusals (CHECKs, uniqueness, the delete and
 * versioning triggers).
 */
class SubscriptionsDaoTest {
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
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE subscriptions, students, users CASCADE")
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
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sub-$userId@test.com', 'Sub User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  // Whole-second instants so the TIMESTAMPTZ (microsecond) round-trip is exact.
  private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val periodStart: Instant = now.minus(1, ChronoUnit.DAYS)
  private val periodEnd: Instant = now.plus(29, ChronoUnit.DAYS)

  private val productId = "coach.uni.UnicoachiOS.monthly10"

  private fun upsert(
    studentId: StudentId,
    originalTransactionId: String = "100000123456789",
    productId: String = this.productId,
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    periodStart: Instant = this.periodStart,
    periodEnd: Instant = this.periodEnd,
  ): SubscriptionUpsert =
    SubscriptionsDao
      .upsert(session, studentId, originalTransactionId, productId, status, periodStart, periodEnd)
      .getOrThrow()

  private fun versionRowCount(subscription: Subscription): Int =
    connection
      .prepareStatement("SELECT COUNT(*) AS n FROM subscriptions_versions WHERE id = ?")
      .use { stmt ->
        stmt.setObject(1, subscription.id.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt("n")
        }
      }

  private fun hasVersionRow(
    subscription: Subscription,
    version: Int,
  ): Boolean =
    connection
      .prepareStatement("SELECT 1 FROM subscriptions_versions WHERE id = ? AND version = ?")
      .use { stmt ->
        stmt.setObject(1, subscription.id.value)
        stmt.setInt(2, version)
        stmt.executeQuery().use { it.next() }
      }

  // ---------------------------------------------------------------------------
  // upsert
  // ---------------------------------------------------------------------------

  @Test
  fun `upsert inserts a fresh key at version 1 with one history row`() {
    val studentId = createStudent()

    val applied = assertIs<SubscriptionUpsert.Applied>(upsert(studentId))
    val row = applied.subscription

    assertEquals(1, row.version)
    assertEquals(studentId, row.studentId)
    assertEquals("100000123456789", row.originalTransactionId)
    assertEquals(productId, row.productId)
    assertEquals(SubscriptionStatus.ACTIVE, row.status, "status round-trips through SubscriptionStatus")
    assertEquals(periodStart, row.periodStart)
    assertEquals(periodEnd, row.periodEnd)
    assertEquals(1, versionRowCount(row))
    assertTrue(hasVersionRow(row, version = 1))
  }

  @Test
  fun `upsert refreshes the same key in place, bumping version and history`() {
    val studentId = createStudent()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(studentId)).subscription

    val refreshed =
      assertIs<SubscriptionUpsert.Applied>(
        upsert(
          studentId,
          status = SubscriptionStatus.GRACE,
          periodStart = periodStart.plus(30, ChronoUnit.DAYS),
          periodEnd = periodEnd.plus(30, ChronoUnit.DAYS),
        ),
      ).subscription

    assertEquals(first.id, refreshed.id, "the same row is refreshed, not a sibling inserted")
    assertEquals(2, refreshed.version)
    assertEquals(SubscriptionStatus.GRACE, refreshed.status)
    assertEquals(periodStart.plus(30, ChronoUnit.DAYS), refreshed.periodStart)
    assertEquals(periodEnd.plus(30, ChronoUnit.DAYS), refreshed.periodEnd)
    assertTrue(!refreshed.updatedAt.isBefore(first.updatedAt), "updated_at advanced")
    assertEquals(2, versionRowCount(refreshed))
    assertTrue(hasVersionRow(refreshed, version = 2))
  }

  @Test
  fun `upsert skips a no-op refresh — no version bump, no history churn`() {
    val studentId = createStudent()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(studentId)).subscription

    val unchanged = assertIs<SubscriptionUpsert.Unchanged>(upsert(studentId)).subscription

    assertEquals(first.id, unchanged.id)
    assertEquals(1, unchanged.version, "the app-launch re-verify mints no version")
    assertEquals(first.updatedAt, unchanged.updatedAt, "updated_at untouched")
    assertEquals(1, versionRowCount(unchanged), "no new history row")
  }

  @Test
  fun `upsert refuses to rebind another student's subscription`() {
    val owner = createStudent()
    val interloper = createStudent()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(owner)).subscription

    val refused = assertIs<SubscriptionUpsert.OwnedByOtherStudent>(upsert(interloper, status = SubscriptionStatus.EXPIRED))

    assertEquals(owner, refused.existing.studentId, "the refusal names the student the row is bound to")
    assertEquals(
      first.originalTransactionId,
      refused.existing.originalTransactionId,
      "the refusal names the contested transaction",
    )
    val row =
      SubscriptionsDao
        .findByOriginalTransactionId(session, first.originalTransactionId)
        .getOrThrow()
    assertNotNull(row)
    assertEquals(owner, row.studentId, "the row stays bound to the first verifier")
    assertEquals(SubscriptionStatus.ACTIVE, row.status, "the interloper's state was not applied")
    assertEquals(1, versionRowCount(row), "no new history row")
  }

  // ---------------------------------------------------------------------------
  // findCurrent
  // ---------------------------------------------------------------------------

  @Test
  fun `findCurrent filters status — only active and grace entitle`() {
    val activeStudent = createStudent()
    upsert(activeStudent, originalTransactionId = "1", status = SubscriptionStatus.ACTIVE)
    assertNotNull(SubscriptionsDao.findCurrent(session, activeStudent).getOrThrow())

    val graceStudent = createStudent()
    upsert(graceStudent, originalTransactionId = "2", status = SubscriptionStatus.GRACE)
    assertNotNull(SubscriptionsDao.findCurrent(session, graceStudent).getOrThrow())

    listOf(SubscriptionStatus.EXPIRED, SubscriptionStatus.BILLING_RETRY, SubscriptionStatus.REVOKED)
      .forEachIndexed { i, status ->
        val student = createStudent()
        upsert(student, originalTransactionId = "3$i", status = status)
        assertNull(SubscriptionsDao.findCurrent(session, student).getOrThrow(), "[$status] must not entitle")
      }
  }

  @Test
  fun `findCurrent filters the window — an elapsed or future period is not current`() {
    val lapsed = createStudent()
    upsert(
      lapsed,
      originalTransactionId = "10",
      periodStart = now.minus(60, ChronoUnit.DAYS),
      periodEnd = now.minus(30, ChronoUnit.DAYS),
    )
    assertNull(SubscriptionsDao.findCurrent(session, lapsed).getOrThrow(), "period_end in the past is not current")

    val future = createStudent()
    upsert(
      future,
      originalTransactionId = "11",
      periodStart = now.plus(30, ChronoUnit.DAYS),
      periodEnd = now.plus(60, ChronoUnit.DAYS),
    )
    assertNull(SubscriptionsDao.findCurrent(session, future).getOrThrow(), "period_start in the future is not current")
  }

  @Test
  fun `findCurrent picks the latest period_end of two entitling rows`() {
    val studentId = createStudent()
    upsert(studentId, originalTransactionId = "20", periodEnd = now.plus(10, ChronoUnit.DAYS))
    upsert(studentId, originalTransactionId = "21", periodEnd = now.plus(20, ChronoUnit.DAYS))

    val current = SubscriptionsDao.findCurrent(session, studentId).getOrThrow()

    assertNotNull(current)
    assertEquals("21", current.originalTransactionId)
  }

  // ---------------------------------------------------------------------------
  // findByOriginalTransactionId
  // ---------------------------------------------------------------------------

  @Test
  fun `findByOriginalTransactionId answers present and absent keys`() {
    val studentId = createStudent()
    upsert(studentId, originalTransactionId = "42")

    val present = SubscriptionsDao.findByOriginalTransactionId(session, "42").getOrThrow()
    assertNotNull(present)
    assertEquals(studentId, present.studentId)

    assertNull(SubscriptionsDao.findByOriginalTransactionId(session, "43").getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // Schema refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a status outside the closed set raises`() {
    val studentId = createStudent()
    assertFailsWith<SQLException> {
      connection
        .prepareStatement(
          "INSERT INTO subscriptions (student_id, original_transaction_id, product_id, status, period_start, period_end) " +
            "VALUES (?, '90', 'p', 'paused', NOW(), NOW() + INTERVAL '30 days')",
        ).use { stmt ->
          stmt.setObject(1, studentId.value)
          stmt.executeUpdate()
        }
    }
  }

  @Test
  fun `an inverted period raises`() {
    val studentId = createStudent()
    val result =
      SubscriptionsDao.upsert(
        session,
        studentId,
        originalTransactionId = "91",
        productId = productId,
        status = SubscriptionStatus.ACTIVE,
        periodStart = periodEnd,
        periodEnd = periodStart,
      )
    assertTrue(result.isFailure, "period_start >= period_end must be refused by the CHECK")
  }

  @Test
  fun `a duplicate original_transaction_id insert raises outside the upsert`() {
    val studentId = createStudent()
    upsert(studentId, originalTransactionId = "92")
    assertFailsWith<SQLException> {
      connection
        .prepareStatement(
          "INSERT INTO subscriptions (student_id, original_transaction_id, product_id, status, period_start, period_end) " +
            "VALUES (?, '92', 'p', 'active', NOW(), NOW() + INTERVAL '30 days')",
        ).use { stmt ->
          stmt.setObject(1, studentId.value)
          stmt.executeUpdate()
        }
    }
  }

  @Test
  fun `physical delete raises`() {
    val studentId = createStudent()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(studentId)).subscription
    assertFailsWith<SQLException> {
      connection.prepareStatement("DELETE FROM subscriptions WHERE id = ?").use { stmt ->
        stmt.setObject(1, row.id.value)
        stmt.executeUpdate()
      }
    }
  }

  @Test
  fun `a raw UPDATE that skips the version bump raises`() {
    val studentId = createStudent()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(studentId)).subscription
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE subscriptions SET status = 'expired' WHERE id = ?").use { stmt ->
        stmt.setObject(1, row.id.value)
        stmt.executeUpdate()
      }
    }
  }
}
