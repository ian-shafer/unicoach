package ed.unicoach.db.dao

import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Subscription
import ed.unicoach.db.models.SubscriptionStatus
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SubscriptionsDao] against the real versioned `subscriptions`
 * entity (RFC 110): the insert-or-refresh upsert with its state-distinct and
 * ownership guards, the gate's [SubscriptionsDao.findCurrent] read, the
 * Apple-key lookup, and the schema's own refusals (CHECKs, uniqueness, the delete
 * and versioning triggers).
 *
 * The subject is an ACCOUNT since RFC 244 (`subscriptions-key-on-account.sql`), so
 * most fixtures here are a bare `users` row: a subscription needs no student
 * profile any more, which is the blocker that migration removes. The tests that
 * still mint a `students` row are the ones that assert the move kept its promise —
 * a subscribed student's account reads exactly what their student id read before —
 * and the ones that check what the migration LEFT behind on the live table.
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

  /**
   * A bare account — a `users` row and NO student profile. The ordinary fixture
   * here since RFC 244: `subscriptions.user_id` references `users`, so this is
   * every shape the table now accepts, including the parent-shaped account that
   * could not hold a row at all before the migration.
   */
  private fun createAccount(): UserId {
    val userId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sub-$userId@test.com', 'Sub User', 'ahash')")
    }
    return UserId(userId)
  }

  /**
   * An account that also holds a student profile — the only shape that existed
   * before RFC 244, kept for the tests that assert the key move changed no answer.
   */
  private fun createStudentAccount(): Pair<UserId, StudentId> {
    val userId = createAccount()
    val studentId = UUID.randomUUID()
    StudentRowFixture.insert(connection, studentId, userId.value)
    return userId to StudentId(studentId)
  }

  // Whole-second instants so the TIMESTAMPTZ (microsecond) round-trip is exact.
  private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
  private val periodStart: Instant = now.minus(1, ChronoUnit.DAYS)
  private val periodEnd: Instant = now.plus(29, ChronoUnit.DAYS)

  private val productId = "coach.uni.UnicoachiOS.monthly10"

  private fun upsert(
    userId: UserId,
    originalTransactionId: String = "100000123456789",
    productId: String = this.productId,
    status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    periodStart: Instant = this.periodStart,
    periodEnd: Instant = this.periodEnd,
  ): SubscriptionUpsert =
    SubscriptionsDao
      .upsert(session, userId, originalTransactionId, productId, status, periodStart, periodEnd)
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
    val userId = createAccount()

    val applied = assertIs<SubscriptionUpsert.Applied>(upsert(userId))
    val row = applied.subscription

    assertEquals(1, row.version)
    assertEquals(userId, row.userId)
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
    val userId = createAccount()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    val refreshed =
      assertIs<SubscriptionUpsert.Applied>(
        upsert(
          userId,
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
    assertTrue(
      refreshed.updatedAt.isAfter(first.updatedAt),
      "trigger_03 stamps updated_at: the upsert sets no updated_at of its own, so only the trigger can move it",
    )
    assertEquals(2, versionRowCount(refreshed))
    assertTrue(hasVersionRow(refreshed, version = 2))
  }

  @Test
  fun `upsert skips a no-op refresh — no version bump, no history churn`() {
    val userId = createAccount()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    val unchanged = assertIs<SubscriptionUpsert.Unchanged>(upsert(userId)).subscription

    assertEquals(first.id, unchanged.id)
    assertEquals(1, unchanged.version, "the app-launch re-verify mints no version")
    assertEquals(first.updatedAt, unchanged.updatedAt, "updated_at untouched")
    assertEquals(1, versionRowCount(unchanged), "no new history row")
  }

  @Test
  fun `upsert refuses to rebind another account's subscription`() {
    val owner = createAccount()
    val interloper = createAccount()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(owner)).subscription

    val refused = assertIs<SubscriptionUpsert.OwnedByOtherAccount>(upsert(interloper, status = SubscriptionStatus.EXPIRED))

    assertEquals(owner, refused.existing.userId, "the refusal names the account the row is bound to")
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
    assertEquals(owner, row.userId, "the row stays bound to the first verifier")
    assertEquals(SubscriptionStatus.ACTIVE, row.status, "the interloper's state was not applied")
    assertEquals(1, versionRowCount(row), "no new history row")
  }

  /**
   * The upsert's third classification — the row the conflict arm did not write
   * and the follow-up SELECT cannot find — fails in THIS module's exception
   * vocabulary, not the driver's. It is thrown from a `mapCatching` rather than
   * from `queryOne`'s envelope, so nothing maps it on the way out: a raw
   * `SQLException` here reaches `:service` carrying neither `PermanentError` nor
   * `TransientError`, and the webhook's refresh handler retries a state the DAO
   * calls impossible and permanent.
   *
   * The arm cannot be reached through the real table — `subscriptions` carries
   * the physical-delete guard asserted below, which is WHY it is impossible — so
   * the classifying READ is blinded instead: a session that appends `AND FALSE`
   * to the lookup `upsert` makes after a no-op write. Should that statement's
   * text ever change, this session blinds nothing, the upsert answers
   * [SubscriptionUpsert.Unchanged] and the assertions below fail on a null
   * failure rather than passing quietly.
   */
  @Test
  fun `the vanished-row arm fails as a classified DaoException, not a raw driver exception`() {
    val userId = createAccount()
    upsert(userId) // The row now exists, so a re-verify of the same state writes nothing.
    val blinded =
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement =
          connection.prepareStatement(
            if (sql.startsWith("SELECT * FROM subscriptions WHERE original_transaction_id")) "$sql AND FALSE" else sql,
          )
      }

    val failure =
      SubscriptionsDao
        .upsert(blinded, userId, "100000123456789", productId, SubscriptionStatus.ACTIVE, periodStart, periodEnd)
        .exceptionOrNull()

    assertFalse(failure is SQLException, "a raw driver exception crosses into :service unclassified: $failure")
    val classified = assertIs<DatabaseException>(failure, "the impossible state is this module's own PermanentError")
    val detail = assertNotNull(classified.cause?.message)
    assertTrue(detail.contains("100000123456789"), "the failure names the contested transaction: $detail")
    assertTrue(detail.contains(userId.asString), "and the account the write was keyed on: $detail")
  }

  // ---------------------------------------------------------------------------
  // findCurrent
  // ---------------------------------------------------------------------------

  @Test
  fun `findCurrent filters status — only active and grace entitle`() {
    val activeAccount = createAccount()
    upsert(activeAccount, originalTransactionId = "1", status = SubscriptionStatus.ACTIVE)
    assertNotNull(SubscriptionsDao.findCurrent(session, activeAccount).getOrThrow())

    val graceAccount = createAccount()
    upsert(graceAccount, originalTransactionId = "2", status = SubscriptionStatus.GRACE)
    assertNotNull(SubscriptionsDao.findCurrent(session, graceAccount).getOrThrow())

    listOf(SubscriptionStatus.EXPIRED, SubscriptionStatus.BILLING_RETRY, SubscriptionStatus.REVOKED)
      .forEachIndexed { i, status ->
        val account = createAccount()
        upsert(account, originalTransactionId = "3$i", status = status)
        assertNull(SubscriptionsDao.findCurrent(session, account).getOrThrow(), "[$status] must not entitle")
      }
  }

  @Test
  fun `findCurrent filters the window — an elapsed or future period is not current`() {
    val lapsed = createAccount()
    upsert(
      lapsed,
      originalTransactionId = "10",
      periodStart = now.minus(60, ChronoUnit.DAYS),
      periodEnd = now.minus(30, ChronoUnit.DAYS),
    )
    assertNull(SubscriptionsDao.findCurrent(session, lapsed).getOrThrow(), "period_end in the past is not current")

    val future = createAccount()
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
    val userId = createAccount()
    upsert(userId, originalTransactionId = "20", periodEnd = now.plus(10, ChronoUnit.DAYS))
    upsert(userId, originalTransactionId = "21", periodEnd = now.plus(20, ChronoUnit.DAYS))

    val current = SubscriptionsDao.findCurrent(session, userId).getOrThrow()

    assertNotNull(current)
    assertEquals("21", current.originalTransactionId)
  }

  // ---------------------------------------------------------------------------
  // findByOriginalTransactionId
  // ---------------------------------------------------------------------------

  @Test
  fun `findByOriginalTransactionId answers present and absent keys`() {
    val userId = createAccount()
    upsert(userId, originalTransactionId = "42")

    val present = SubscriptionsDao.findByOriginalTransactionId(session, "42").getOrThrow()
    assertNotNull(present)
    assertEquals(userId, present.userId)

    assertNull(SubscriptionsDao.findByOriginalTransactionId(session, "43").getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // Schema refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a status outside the closed set raises`() {
    val userId = createAccount()
    assertFailsWith<SQLException> {
      connection
        .prepareStatement(
          "INSERT INTO subscriptions (user_id, original_transaction_id, product_id, status, period_start, period_end) " +
            "VALUES (?, '90', 'p', 'paused', NOW(), NOW() + INTERVAL '30 days')",
        ).use { stmt ->
          stmt.setObject(1, userId.value)
          stmt.executeUpdate()
        }
    }
  }

  @Test
  fun `an inverted period raises`() {
    val userId = createAccount()
    val result =
      SubscriptionsDao.upsert(
        session,
        userId,
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
    val userId = createAccount()
    upsert(userId, originalTransactionId = "92")
    assertFailsWith<SQLException> {
      connection
        .prepareStatement(
          "INSERT INTO subscriptions (user_id, original_transaction_id, product_id, status, period_start, period_end) " +
            "VALUES (?, '92', 'p', 'active', NOW(), NOW() + INTERVAL '30 days')",
        ).use { stmt ->
          stmt.setObject(1, userId.value)
          stmt.executeUpdate()
        }
    }
  }

  @Test
  fun `physical delete raises`() {
    val userId = createAccount()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription
    assertFailsWith<SQLException> {
      connection.prepareStatement("DELETE FROM subscriptions WHERE id = ?").use { stmt ->
        stmt.setObject(1, row.id.value)
        stmt.executeUpdate()
      }
    }
  }

  @Test
  fun `a raw UPDATE that skips the version bump raises`() {
    val userId = createAccount()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE subscriptions SET status = 'expired' WHERE id = ?").use { stmt ->
        stmt.setObject(1, row.id.value)
        stmt.executeUpdate()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // RFC 244 — the owner is an ACCOUNT
  // ---------------------------------------------------------------------------

  @Test
  fun `an account with no student profile can hold a subscription`() {
    val userId = createAccount()
    assertEquals(0, studentRowCount(userId), "the fixture is an account with no students row")

    val row = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    assertEquals(userId, row.userId)
    val current = SubscriptionsDao.findCurrent(session, userId).getOrThrow()
    assertNotNull(current, "the gate reads it by account — the refusal RFC 244 removed was by ROLE")
    assertEquals(row.id, current.id)
  }

  @Test
  fun `a subscribed student's account reads exactly what their student id keyed before`() {
    val (userId, studentId) = createStudentAccount()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    val current = SubscriptionsDao.findCurrent(session, userId).getOrThrow()

    assertNotNull(current, "the key moved; the answer did not")
    assertEquals(row.id, current.id)
    assertEquals(
      userId,
      accountOfStudent(studentId),
      "the hop a caller holding a StudentId makes is students.user_id, and it lands on this row's owner",
    )
  }

  @Test
  fun `two accounts hold their own subscriptions and see only their own`() {
    val first = createAccount()
    val second = createAccount()
    upsert(first, originalTransactionId = "200")
    upsert(second, originalTransactionId = "201")

    assertEquals("200", SubscriptionsDao.findCurrent(session, first).getOrThrow()?.originalTransactionId)
    assertEquals("201", SubscriptionsDao.findCurrent(session, second).getOrThrow()?.originalTransactionId)

    val third = createAccount()
    assertNull(
      SubscriptionsDao.findCurrent(session, third).getOrThrow(),
      "no account rides another's subscription: an account that paid nothing is current for nothing",
    )
  }

  // ---------------------------------------------------------------------------
  // What subscriptions-key-on-account.sql LEFT BEHIND
  //
  // The backfill itself cannot be measured here — `bin/test` does
  // db-reset (drop → create → migrate), so it runs on an EMPTY table (UPDATE 0)
  // and `ALTER COLUMN user_id SET NOT NULL` IS its assertion. What these tests
  // hold is the state the migration leaves on a live table: the three guards it
  // DROPPED for the backfill are back, the two it left attached still refuse, and
  // the restated history writer names the new column.
  // ---------------------------------------------------------------------------

  @Test
  fun `the restated history writer records the account`() {
    val userId = createAccount()
    val first = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    // A real refresh, so the AFTER trigger fires on an UPDATE and not only on the
    // INSERT: a stale function body naming student_id would raise 42703 here.
    val refreshed =
      assertIs<SubscriptionUpsert.Applied>(upsert(userId, status = SubscriptionStatus.GRACE)).subscription

    assertEquals(2, refreshed.version)
    assertEquals(2, versionRowCount(refreshed), "trigger_04 was re-created, so both states are on record")
    assertEquals(userId.value, versionRowUserId(first, version = 1), "the version 1 row carries the account")
    assertEquals(userId.value, versionRowUserId(refreshed, version = 2), "so does the version 2 row")
  }

  @Test
  fun `subscriptions_versions carries user_id and not student_id`() {
    assertTrue(hasColumn("subscriptions", "user_id"))
    assertFalse(hasColumn("subscriptions", "student_id"), "the student key left the live table")
    assertTrue(hasColumn("subscriptions_versions", "user_id"))
    assertFalse(hasColumn("subscriptions_versions", "student_id"), "and the history table")
  }

  @Test
  fun `an UPDATE of an immutable column still raises after the migration`() {
    val userId = createAccount()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    // created_at is immutable; the version bump keeps enforce_versioning happy so
    // the refusal under test is trigger_00a's and not another guard's.
    val failure =
      assertFailsWith<SQLException> {
        connection
          .prepareStatement("UPDATE subscriptions SET created_at = NOW(), version = version + 1 WHERE id = ?")
          .use { stmt ->
            stmt.setObject(1, row.id.value)
            stmt.executeUpdate()
          }
      }

    assertTrue(
      failure.message?.contains("immutable") == true,
      "prevent_immutable_updates was never dropped, so it still answers: [${failure.message}]",
    )
    assertEquals(1, versionRowCount(row), "and nothing was written")
  }

  @Test
  fun `an UPDATE of a physical timestamp still raises after the migration`() {
    val userId = createAccount()
    val row = assertIs<SubscriptionUpsert.Applied>(upsert(userId)).subscription

    assertFailsWith<SQLException> {
      connection
        .prepareStatement("UPDATE subscriptions SET row_created_at = NOW(), version = version + 1 WHERE id = ?")
        .use { stmt ->
          stmt.setObject(1, row.id.value)
          stmt.executeUpdate()
        }
    }
    assertEquals(1, versionRowCount(row))
  }

  @Test
  fun `the account key references users and refuses an unknown account`() {
    val stranger = UserId(UUID.randomUUID())

    val result =
      SubscriptionsDao.upsert(
        session,
        stranger,
        originalTransactionId = "300",
        productId = productId,
        status = SubscriptionStatus.ACTIVE,
        periodStart = periodStart,
        periodEnd = periodEnd,
      )

    assertTrue(result.isFailure, "user_id REFERENCES users(id): a row for no account is unrepresentable")
  }

  private fun studentRowCount(userId: UserId): Int =
    connection.prepareStatement("SELECT COUNT(*) AS n FROM students WHERE user_id = ?").use { stmt ->
      stmt.setObject(1, userId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt("n")
      }
    }

  /** The one indexed hop a caller holding a [StudentId] makes to reach the payer. */
  private fun accountOfStudent(studentId: StudentId): UserId =
    connection.prepareStatement("SELECT user_id FROM students WHERE id = ?").use { stmt ->
      stmt.setObject(1, studentId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        UserId(UUID.fromString(rs.getString("user_id")))
      }
    }

  private fun versionRowUserId(
    subscription: Subscription,
    version: Int,
  ): UUID =
    connection
      .prepareStatement("SELECT user_id FROM subscriptions_versions WHERE id = ? AND version = ?")
      .use { stmt ->
        stmt.setObject(1, subscription.id.value)
        stmt.setInt(2, version)
        stmt.executeQuery().use { rs ->
          rs.next()
          UUID.fromString(rs.getString("user_id"))
        }
      }

  private fun hasColumn(
    table: String,
    column: String,
  ): Boolean =
    connection
      .prepareStatement("SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?")
      .use { stmt ->
        stmt.setString(1, table)
        stmt.setString(2, column)
        stmt.executeQuery().use { it.next() }
      }
}
