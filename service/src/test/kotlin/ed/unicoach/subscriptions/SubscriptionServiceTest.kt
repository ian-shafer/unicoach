package ed.unicoach.subscriptions

import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.ScriptedAppStoreTransport
import ed.unicoach.coaching.budget.testSubscriptionPlans
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.dao.captureWarns
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.db.models.UserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [SubscriptionService.verify] end to end below the route (RFC 110):
 * the real [AppStoreServerApi] over the scripted transport, the real
 * `subscriptions` table behind [SubscriptionsDao], and the full arm taxonomy —
 * including that validation refuses before any Apple call, and that refused
 * flows write no row.
 */
class SubscriptionServiceTest {
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
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE subscriptions, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /**
   * A bare account — a `users` row and no student profile. The ordinary fixture
   * since RFC 244: [SubscriptionService.verify] binds to an ACCOUNT, so this is the
   * caller the old student-keyed flow could not serve at all.
   */
  private fun createAccount(): UserId {
    val userId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'ss-$userId@test.com', 'Sub User', 'ahash')")
    }
    return UserId(userId)
  }

  /** An account that also holds a student profile — the only shape that existed before RFC 244. */
  private fun createStudentAccount(): UserId {
    val userId = createAccount()
    StudentRowFixture.insert(connection, UUID.randomUUID(), userId.value)
    return userId
  }

  private fun service(transport: ScriptedAppStoreTransport): SubscriptionService =
    SubscriptionService(
      database,
      AppStoreServerApi(transport, AppStoreTestFixtures.authTokens()),
      testSubscriptionPlans(),
    )

  private fun verify(
    userId: UserId,
    transport: ScriptedAppStoreTransport,
    signedTransaction: String = AppStoreTestFixtures.signedTransaction(),
  ): VerifyResult = runBlocking { service(transport).verify(userId, signedTransaction).getOrThrow() }

  private fun activeTransport(
    purchaseDate: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    expiresDate: Instant = Instant.parse("2026-09-01T00:00:00Z"),
  ) =
    ScriptedAppStoreTransport.of(200, AppStoreTestFixtures.activeStatusResponseBody(purchaseDate = purchaseDate, expiresDate = expiresDate))

  private fun studentRowCount(): Int =
    connection.prepareStatement("SELECT COUNT(*) AS n FROM students").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt("n")
      }
    }

  private fun rowCount(): Int =
    connection.prepareStatement("SELECT COUNT(*) AS n FROM subscriptions").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt("n")
      }
    }

  // ---------------------------------------------------------------------------
  // Happy paths
  // ---------------------------------------------------------------------------

  @Test
  fun `a verified transaction inserts a row matching the decoded fixture`() {
    val userId = createAccount()

    val result = verify(userId, activeTransport())

    val verified = assertIs<VerifyResult.Verified>(result)
    assertEquals(userId, verified.subscription.userId)
    assertEquals(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID, verified.subscription.originalTransactionId)
    assertEquals(AppStoreTestFixtures.PRODUCT_ID, verified.subscription.productId)
    assertEquals(SubscriptionStatus.ACTIVE, verified.subscription.status)
    assertEquals(Instant.parse("2026-08-01T00:00:00Z"), verified.subscription.periodStart)
    assertEquals(Instant.parse("2026-09-01T00:00:00Z"), verified.subscription.periodEnd)
    assertEquals(1, verified.subscription.version)
  }

  @Test
  fun `a caller with NO student profile binds a subscription`() {
    // RFC 244 §5: billing is per ACCOUNT, so the caller's roles are irrelevant
    // here. This was impossible before this slice — `subscriptions.student_id NOT
    // NULL` made a supporter-owned row unrepresentable and the route refused by
    // role — and it is the blocker correction X1 named.
    val parentOnlyAccount = createAccount()

    val bound = assertIs<VerifyResult.Verified>(verify(parentOnlyAccount, activeTransport())).subscription

    assertEquals(parentOnlyAccount, bound.userId, "an account with no students row owns its own subscription")
    assertEquals(0, studentRowCount(), "and the fixture really had no student profile")
  }

  @Test
  fun `an account that also holds a student profile binds exactly as before`() {
    val studentAccount = createStudentAccount()

    val bound = assertIs<VerifyResult.Verified>(verify(studentAccount, activeTransport())).subscription

    assertEquals(studentAccount, bound.userId, "the student's row now hangs off their account: the key moved, not the answer")
    assertEquals(1, studentRowCount())
  }

  @Test
  fun `re-verify with changed state updates the row in place — the idempotent refresh`() {
    val userId = createAccount()
    val first = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription

    val renewed =
      verify(
        userId,
        activeTransport(
          purchaseDate = Instant.parse("2026-09-01T00:00:00Z"),
          expiresDate = Instant.parse("2026-10-01T00:00:00Z"),
        ),
      )

    val refreshed = assertIs<VerifyResult.Verified>(renewed).subscription
    assertEquals(first.id, refreshed.id, "the same row, refreshed")
    assertEquals(2, refreshed.version)
    assertEquals(Instant.parse("2026-10-01T00:00:00Z"), refreshed.periodEnd)
    assertEquals(1, rowCount())
  }

  @Test
  fun `re-verify with identical state is still Verified with the version unmoved`() {
    val userId = createAccount()
    val first = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription

    val again = verify(userId, activeTransport())

    val unchanged = assertIs<VerifyResult.Verified>(again).subscription
    assertEquals(first.id, unchanged.id)
    assertEquals(1, unchanged.version, "an app-launch re-verify mints no version")
  }

  @Test
  fun `an expired-status fixture is still Verified and the row records expired`() {
    val userId = createAccount()
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(status = 2, signedTransactionInfo = AppStoreTestFixtures.signedTransaction()),
      )

    val result = verify(userId, ScriptedAppStoreTransport.of(200, body))

    val verified = assertIs<VerifyResult.Verified>(result)
    assertEquals(SubscriptionStatus.EXPIRED, verified.subscription.status, "the row reflects Apple truth; entitlement is the gate's job")
  }

  // ---------------------------------------------------------------------------
  // Validation — refused before any Apple call
  // ---------------------------------------------------------------------------

  @Test
  fun `blank, oversized, undecodable, and non-numeric-claim JWSes fail validation with zero transport calls`() {
    val userId = createAccount()
    val badInputs =
      mapOf(
        "blank" to "   ",
        "oversized" to "a.${"b".repeat(SubscriptionService.MAX_JWS.bytes.toInt())}.c",
        "undecodable" to "not-a-jws-at-all",
        "non-numeric transactionId" to AppStoreTestFixtures.sign(mapOf("transactionId" to "not-numeric")),
        "missing transactionId" to AppStoreTestFixtures.sign(mapOf("productId" to "p")),
      )

    for ((label, signedTransaction) in badInputs) {
      val transport = activeTransport()
      val result = verify(userId, transport, signedTransaction = signedTransaction)
      val failure = assertIs<VerifyResult.ValidationFailure>(result, label)
      assertEquals("signedTransaction", failure.fieldErrors.single().field, label)
      assertEquals(0, transport.calls.size, "[$label] must not reach Apple")
    }
    assertEquals(0, rowCount(), "no refused flow writes a row")
  }

  // ---------------------------------------------------------------------------
  // Apple's arms
  // ---------------------------------------------------------------------------

  @Test
  fun `an Apple 404 is UnknownTransaction and writes no row`() {
    val userId = createAccount()

    val result = verify(userId, ScriptedAppStoreTransport.of(404, "{}"))

    assertIs<VerifyResult.UnknownTransaction>(result)
    assertEquals(0, rowCount())
  }

  @Test
  fun `an unknown product is UnknownProduct and writes no row`() {
    val userId = createAccount()
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(
          status = 1,
          signedTransactionInfo = AppStoreTestFixtures.signedTransaction(productId = "coach.uni.UnicoachiOS.retired99"),
        ),
      )

    val result = verify(userId, ScriptedAppStoreTransport.of(200, body))

    val unknown = assertIs<VerifyResult.UnknownProduct>(result)
    assertEquals("coach.uni.UnicoachiOS.retired99", unknown.productId)
    assertEquals(0, rowCount(), "a row the gate would immediately fail closed on is never created")
  }

  @Test
  fun `a transport failure is AppStoreUnavailable and writes no row`() {
    val userId = createAccount()

    val result = verify(userId, ScriptedAppStoreTransport.throwing(IOException("connection reset")))

    val unavailable = assertIs<VerifyResult.AppStoreUnavailable>(result)
    assertTrue(
      unavailable.reason.contains("connection reset"),
      "the lookup's reason crosses into the service outcome: [${unavailable.reason}]",
    )
    assertEquals(0, rowCount())
  }

  // ---------------------------------------------------------------------------
  // Ownership
  // ---------------------------------------------------------------------------

  @Test
  fun `a second account re-posting the first account's JWS is refused, counted, and the binding holds`() {
    val owner = createAccount()
    val interloper = createAccount()
    assertIs<VerifyResult.Verified>(verify(owner, activeTransport()))

    // THE SHARED APPLE ID (RFC 244 §2). One Apple ID holds at most one active
    // subscription in a group, so the second account on it is handed the first
    // account's transaction. Coverage was deliberately not built; the refusal is
    // counted instead, and the count is what would justify building it.
    val warnings = captureWarns { assertIs<VerifyResult.OwnedByOtherAccount>(verify(interloper, activeTransport())) }

    val counted = warnings.filter { it.startsWith("subscription_apple_id_already_bound ") }
    assertEquals(
      listOf(
        "subscription_apple_id_already_bound " +
          "original_transaction_id=[${AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID}] " +
          "owner_user_id=[${owner.asString}] caller_user_id=[${interloper.asString}]",
      ),
      counted,
      "ONE line, with the stable event name leading and BOTH accounts on it — this is the measurement",
    )

    val row =
      SubscriptionsDao
        .findByOriginalTransactionId(session, AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID)
        .getOrThrow()
    assertNotNull(row)
    assertEquals(owner, row.userId, "the row stays bound to the first verifier")
    assertNull(
      SubscriptionsDao.findCurrent(session, interloper).getOrThrow(),
      "and the refused account is entitled by nothing: it does not ride the owner's subscription",
    )
  }

  // ---------------------------------------------------------------------------
  // refresh — the webhook's flow (RFC 112)
  // ---------------------------------------------------------------------------

  private fun refresh(
    transport: ScriptedAppStoreTransport,
    originalTransactionId: String = AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID,
  ): RefreshResult = runBlocking { service(transport).refresh(originalTransactionId).getOrThrow() }

  /** The row as the database holds it now, by Apple's key. */
  private fun row(originalTransactionId: String = AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID) =
    SubscriptionsDao.findByOriginalTransactionId(session, originalTransactionId).getOrThrow()

  @Test
  fun `a refresh naming no local row is NotBound, with zero transport calls`() {
    // The webhook refreshes but never binds: no account has verified this, and
    // the notification carries no identity to invent one from.
    val transport = activeTransport()

    val result = refresh(transport)

    assertIs<RefreshResult.NotBound>(result)
    assertEquals(0, transport.calls.size, "an unbound notification costs no Apple call")
    assertEquals(0, rowCount())
  }

  @Test
  fun `a refresh applies changed state under the row's own account`() {
    // refresh is passed no account at all — the binding comes from the row.
    val userId = createAccount()
    val first = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription

    val result =
      refresh(
        activeTransport(
          purchaseDate = Instant.parse("2026-09-01T00:00:00Z"),
          expiresDate = Instant.parse("2026-10-01T00:00:00Z"),
        ),
      )

    val refreshed = assertIs<RefreshResult.Refreshed>(result).subscription
    assertEquals(first.id, refreshed.id, "the same row")
    assertEquals(2, refreshed.version)
    assertEquals(Instant.parse("2026-10-01T00:00:00Z"), refreshed.periodEnd)
    assertEquals(userId, refreshed.userId, "the account the row already named")
    assertEquals(1, rowCount())
  }

  @Test
  fun `a refresh of identical state is Refreshed with the version unmoved`() {
    // Apple may deliver the same notification twice; the second refresh must
    // mint no version row. Idempotency is a property of the design, not a dedup table.
    val userId = createAccount()
    assertIs<VerifyResult.Verified>(verify(userId, activeTransport()))

    val result = refresh(activeTransport())

    assertEquals(1, assertIs<RefreshResult.Refreshed>(result).subscription.version)
    assertEquals(1, assertNotNull(row()).version)
  }

  @Test
  fun `a refresh records non-entitling statuses`() {
    // EXPIRED and REVOKED are Apple truth like any other; the gate, not this
    // flow, decides what they entitle.
    for ((appleStatus, expected) in mapOf(2 to SubscriptionStatus.EXPIRED, 5 to SubscriptionStatus.REVOKED)) {
      resetDatabase()
      val userId = createAccount()
      assertIs<VerifyResult.Verified>(verify(userId, activeTransport()))
      val body =
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(status = appleStatus, signedTransactionInfo = AppStoreTestFixtures.signedTransaction()),
        )

      val result = refresh(ScriptedAppStoreTransport.of(200, body))

      assertEquals(expected, assertIs<RefreshResult.Refreshed>(result, "status $appleStatus").subscription.status)
      assertEquals(expected, assertNotNull(row()).status)
    }
  }

  @Test
  fun `a refresh of an unknown product leaves the row untouched`() {
    val userId = createAccount()
    val bound = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(
          status = 1,
          signedTransactionInfo = AppStoreTestFixtures.signedTransaction(productId = "coach.uni.UnicoachiOS.retired99"),
        ),
      )

    val result = refresh(ScriptedAppStoreTransport.of(200, body))

    assertEquals("coach.uni.UnicoachiOS.retired99", assertIs<RefreshResult.UnknownProduct>(result).productId)
    assertEquals(bound.version, assertNotNull(row()).version, "nothing written for a product this box cannot budget")
  }

  @Test
  fun `an Apple 404 on refresh is UnknownTransaction and leaves the row untouched`() {
    val userId = createAccount()
    val bound = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription

    val result = refresh(ScriptedAppStoreTransport.of(404, "{}"))

    assertIs<RefreshResult.UnknownTransaction>(result)
    assertEquals(bound.version, assertNotNull(row()).version)
  }

  @Test
  fun `a transport failure on refresh is AppStoreUnavailable and leaves the row untouched`() {
    val userId = createAccount()
    val bound = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription

    val result = refresh(ScriptedAppStoreTransport.throwing(IOException("connection reset")))

    assertTrue(assertIs<RefreshResult.AppStoreUnavailable>(result).reason.contains("connection reset"))
    assertEquals(bound.version, assertNotNull(row()).version)
  }

  @Test
  fun `a refresh Apple answers for a different transaction is MismatchedTransaction and writes nothing, under either key`() {
    // Get All Subscription Statuses answers for every auto-renewable
    // subscription the customer holds, and the client reduces that to the entry
    // with the greatest expiresDate — so the id that comes back is not
    // guaranteed to be the id asked about, and applying it would refresh the
    // wrong row.
    val userId = createAccount()
    val bound = assertIs<VerifyResult.Verified>(verify(userId, activeTransport())).subscription
    val otherId = "555000111222333"
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(
          status = 1,
          signedTransactionInfo =
            AppStoreTestFixtures.signedTransaction(
              originalTransactionId = otherId,
              expiresDate = Instant.parse("2027-01-01T00:00:00Z"),
            ),
        ),
      )

    val result = refresh(ScriptedAppStoreTransport.of(200, body))

    val mismatch = assertIs<RefreshResult.MismatchedTransaction>(result)
    assertEquals(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID, mismatch.expected)
    assertEquals(otherId, mismatch.answered, "the outcome carries the id Apple actually answered with")
    assertEquals(bound.version, assertNotNull(row()).version, "the requested row is untouched")
    assertNull(row(otherId), "and the returned transaction mints no row of its own")
    assertEquals(1, rowCount())
  }
}
