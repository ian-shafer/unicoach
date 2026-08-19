package ed.unicoach.subscriptions

import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.ScriptedAppStoreTransport
import ed.unicoach.coaching.budget.testSubscriptionPlans
import ed.unicoach.common.json.asJson
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.models.StudentId
import ed.unicoach.queue.JobResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.SubscriptionRefreshPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [SubscriptionRefreshHandler]'s queue verdicts (RFC 112) — which outcomes
 * a delivery is allowed to retry on, and which dead-letter immediately.
 *
 * Driven through a real [SubscriptionService] over the real `subscriptions`
 * table and a scripted Apple transport, so each verdict is reached by putting
 * the world in the state that produces it rather than by asserting against a
 * stubbed outcome the production code might never return.
 */
class SubscriptionRefreshHandlerTest {
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

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'rh-$userId@test.com', 'Sub User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun service(transport: ScriptedAppStoreTransport): SubscriptionService =
    SubscriptionService(database, AppStoreServerApi(transport, AppStoreTestFixtures.authTokens()), testSubscriptionPlans())

  private fun handler(transport: ScriptedAppStoreTransport) = SubscriptionRefreshHandler(service(transport))

  private val payload: JsonObject =
    SubscriptionRefreshPayload(
      originalTransactionId = AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID,
      notificationUuid = "8f7c1b12-0000-4000-8000-00000000000a",
      notificationType = "DID_RENEW",
      subtype = "BILLING_RECOVERY",
    ).asJson()

  private fun activeTransport(
    purchaseDate: Instant = Instant.parse("2026-08-01T00:00:00Z"),
    expiresDate: Instant = Instant.parse("2026-09-01T00:00:00Z"),
  ) =
    ScriptedAppStoreTransport.of(200, AppStoreTestFixtures.activeStatusResponseBody(purchaseDate = purchaseDate, expiresDate = expiresDate))

  /** Binds the fixture subscription to a fresh student, so a refresh has a row to act on. */
  private fun bindSubscription(): StudentId {
    val studentId = createStudent()
    val bound = runBlocking { service(activeTransport()).verify(studentId, AppStoreTestFixtures.signedTransaction()).getOrThrow() }
    assertIs<VerifyResult.Verified>(bound)
    return studentId
  }

  private fun execute(
    transport: ScriptedAppStoreTransport,
    body: JsonObject = payload,
  ): JobResult = runBlocking { handler(transport).execute(body) }

  // ---------------------------------------------------------------------------
  // Registration
  // ---------------------------------------------------------------------------

  @Test
  fun `the handler serves REFRESH_SUBSCRIPTION`() {
    assertEquals(JobType.REFRESH_SUBSCRIPTION, handler(activeTransport()).jobType)
  }

  @Test
  fun `executionTimeout is strictly less than lockDuration`() {
    // The queue-lock invariant: a slow Apple call must be abandoned before the
    // lock it holds expires, or a second worker picks the job up alongside it.
    val config = handler(activeTransport()).config

    assertTrue(
      config.executionTimeout < config.lockDuration,
      "executionTimeout [${config.executionTimeout}] must be under lockDuration [${config.lockDuration}]",
    )
  }

  // ---------------------------------------------------------------------------
  // The outcome table
  // ---------------------------------------------------------------------------

  @Test
  fun `an undeserializable payload is a PermanentFailure and calls nothing`() {
    // A poison message: no retry can make it parse.
    val transport = activeTransport()

    val result = execute(transport, buildJsonObject { put("nothing", "useful") })

    assertIs<JobResult.PermanentFailure>(result)
    assertEquals(0, transport.calls.size)
  }

  @Test
  fun `a refreshed subscription is a Success`() {
    bindSubscription()

    val result =
      execute(
        activeTransport(
          purchaseDate = Instant.parse("2026-09-01T00:00:00Z"),
          expiresDate = Instant.parse("2026-10-01T00:00:00Z"),
        ),
      )

    assertIs<JobResult.Success>(result)
  }

  @Test
  fun `an unbound subscription is a Success — the webhook never binds`() {
    val transport = activeTransport()

    val result = execute(transport)

    assertIs<JobResult.Success>(result)
    assertEquals(0, transport.calls.size, "no row, no Apple call")
  }

  @Test
  fun `an unconfigured product is a PermanentFailure naming it`() {
    bindSubscription()
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(
          status = 1,
          signedTransactionInfo = AppStoreTestFixtures.signedTransaction(productId = "coach.uni.UnicoachiOS.retired99"),
        ),
      )

    val result = execute(ScriptedAppStoreTransport.of(200, body))

    // Config drift: no number of retries adds the missing plan entry.
    assertTrue(assertIs<JobResult.PermanentFailure>(result).message.contains("coach.uni.UnicoachiOS.retired99"))
  }

  @Test
  fun `a transaction Apple does not know is a RetriableFailure`() {
    // Apple sent the notification, so Apple's API should know the subscription.
    bindSubscription()

    val result = execute(ScriptedAppStoreTransport.of(404, "{}"))

    assertTrue(assertIs<JobResult.RetriableFailure>(result).message.contains(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID))
  }

  @Test
  fun `a transaction Apple answers about instead is a RetriableFailure naming both ids`() {
    // Apple's Get All Subscription Statuses answers for the whole customer, so
    // the id that comes back may not be the id asked about. The operator reading
    // job_attempts.error_message must see which id Apple actually answered with,
    // not "App Store does not know" a subscription it demonstrably does.
    bindSubscription()
    val answeredId = "555000111222333"
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(
          status = 1,
          signedTransactionInfo =
            AppStoreTestFixtures.signedTransaction(
              originalTransactionId = answeredId,
              expiresDate = Instant.parse("2027-01-01T00:00:00Z"),
            ),
        ),
      )

    val message = assertIs<JobResult.RetriableFailure>(execute(ScriptedAppStoreTransport.of(200, body))).message

    assertTrue(message.contains(answeredId), message)
    assertTrue(message.contains(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID), message)
    assertTrue(message.contains("8f7c1b12-0000-4000-8000-00000000000a"), message)
  }

  @Test
  fun `an unreachable App Store is a RetriableFailure carrying the reason and the throwable`() {
    bindSubscription()
    val transportFailure = IOException("connection reset")

    val result = execute(ScriptedAppStoreTransport.throwing(transportFailure))

    val retriable = assertIs<JobResult.RetriableFailure>(result)
    assertTrue(retriable.message.contains("connection reset"), retriable.message)
    // The worker logs a retriable verdict's cause; the stack trace only reaches
    // that log if the throwable travels the whole way from Apple's transport.
    assertSame(transportFailure, retriable.cause)
  }

  @Test
  fun `a Result failure is rethrown for the worker's default to classify`() {
    // An Apple response shape this box cannot read is bug-grade: the handler
    // invents no classification for it, so the root cause reaches the worker log
    // unaltered.
    bindSubscription()
    val body =
      AppStoreTestFixtures.statusResponseBody(
        AppStoreTestFixtures.LastTransaction(status = 99, signedTransactionInfo = AppStoreTestFixtures.signedTransaction()),
      )

    val thrown = assertFailsWith<IllegalArgumentException> { execute(ScriptedAppStoreTransport.of(200, body)) }

    assertTrue(thrown.message!!.contains("99"), thrown.message!!)
  }
}
