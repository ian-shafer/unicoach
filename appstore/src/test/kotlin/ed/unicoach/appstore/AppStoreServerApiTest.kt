package ed.unicoach.appstore

import com.auth0.jwt.JWT
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [AppStoreServerApi.subscriptionStatus] over the scripted transport (RFC
 * 110): the request it builds, the status mapping, the current-transaction
 * selection, the grace window, and the three-way outcome taxonomy
 * (Found/NotFound/Unavailable vs. bug-grade [Result.failure]).
 */
class AppStoreServerApiTest {
  private fun api(transport: AppStoreTransport): AppStoreServerApi = AppStoreServerApi(transport, AppStoreTestFixtures.authTokens())

  private val purchaseDate = Instant.parse("2026-08-01T00:00:00Z")
  private val expiresDate = Instant.parse("2026-09-01T00:00:00Z")

  @Test
  fun `a happy 200 maps to Found with the decoded product, status, and period`() =
    runTest {
      val transport = ScriptedAppStoreTransport.of(200, AppStoreTestFixtures.activeStatusResponseBody())

      val lookup = api(transport).subscriptionStatus("200000123456789").getOrThrow()

      val found = assertIs<AppStoreSubscriptionLookup.Found>(lookup)
      assertEquals(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID, found.subscription.originalTransactionId)
      assertEquals(AppStoreTestFixtures.PRODUCT_ID, found.subscription.productId)
      assertEquals(AppleSubscriptionStatus.ACTIVE, found.subscription.status)
      assertEquals(purchaseDate, found.subscription.periodStart, "purchaseDate epoch-ms decodes to the Instant")
      assertEquals(expiresDate, found.subscription.periodEnd)
    }

  @Test
  fun `the requested path and bearer token are the lookup's contract`() =
    runTest {
      val transport = ScriptedAppStoreTransport.of(200, AppStoreTestFixtures.activeStatusResponseBody())

      api(transport).subscriptionStatus("42").getOrThrow()

      val call = transport.calls.single()
      assertEquals("/inApps/v1/subscriptions/42", call.path)
      val minted = JWT.decode(call.bearerToken)
      assertEquals(AppStoreTestFixtures.ISSUER_ID, minted.issuer, "the recorded bearer token parses as the minted JWT")
      assertEquals(AppStoreTestFixtures.KEY_ID, minted.keyId)
    }

  @Test
  fun `status integers 1 through 5 map to the five enum values`() =
    runTest {
      val expected =
        mapOf(
          1 to AppleSubscriptionStatus.ACTIVE,
          2 to AppleSubscriptionStatus.EXPIRED,
          3 to AppleSubscriptionStatus.BILLING_RETRY,
          4 to AppleSubscriptionStatus.GRACE,
          5 to AppleSubscriptionStatus.REVOKED,
        )
      // Only GRACE reads the renewal info, and it requires a gracePeriodExpiresDate; the other four ignore it.
      val renewalInfo = AppStoreTestFixtures.signedRenewalInfo(gracePeriodExpiresDate = Instant.parse("2026-09-17T00:00:00Z"))
      for ((statusInt, status) in expected) {
        val body =
          AppStoreTestFixtures.statusResponseBody(
            AppStoreTestFixtures.LastTransaction(
              status = statusInt,
              signedTransactionInfo = AppStoreTestFixtures.signedTransaction(),
              signedRenewalInfo = renewalInfo,
            ),
          )
        val lookup =
          api(ScriptedAppStoreTransport.of(200, body))
            .subscriptionStatus("1")
            .getOrThrow()
        assertEquals(status, assertIs<AppStoreSubscriptionLookup.Found>(lookup).subscription.status, "status int [$statusInt]")
      }
    }

  @Test
  fun `an unknown status integer is a failure, never a guess`() =
    runTest {
      val body =
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(status = 6, signedTransactionInfo = AppStoreTestFixtures.signedTransaction()),
        )

      val result = api(ScriptedAppStoreTransport.of(200, body)).subscriptionStatus("1")

      assertTrue(result.isFailure, "a new Apple state must be looked at, not guessed at")
    }

  @Test
  fun `grace reads the renewal info's gracePeriodExpiresDate as the period end`() =
    runTest {
      val graceEnd = Instant.parse("2026-09-17T00:00:00Z")
      val body =
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = 4,
            signedTransactionInfo = AppStoreTestFixtures.signedTransaction(),
            signedRenewalInfo = AppStoreTestFixtures.signedRenewalInfo(gracePeriodExpiresDate = graceEnd),
          ),
        )

      val lookup = api(ScriptedAppStoreTransport.of(200, body)).subscriptionStatus("1").getOrThrow()

      val found = assertIs<AppStoreSubscriptionLookup.Found>(lookup)
      assertEquals(AppleSubscriptionStatus.GRACE, found.subscription.status)
      assertEquals(graceEnd, found.subscription.periodEnd, "the grace window extends the entitlement AND the meter window")
    }

  @Test
  fun `grace without a gracePeriodExpiresDate is a failure, never the elapsed expiresDate`() =
    runTest {
      val body =
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = 4,
            signedTransactionInfo = AppStoreTestFixtures.signedTransaction(),
            signedRenewalInfo = AppStoreTestFixtures.signedRenewalInfo(gracePeriodExpiresDate = null),
          ),
        )

      val result = api(ScriptedAppStoreTransport.of(200, body)).subscriptionStatus("1")

      // Grace runs past expiresDate, so falling back to it would un-entitle the subscriber grace exists to protect.
      val message = assertIs<IllegalArgumentException>(result.exceptionOrNull()).message.orEmpty()
      assertTrue(message.contains("gracePeriodExpiresDate"), "the refusal names the missing field: [$message]")
      assertTrue(message.contains(expiresDate.toString()), "and reports the entry it refused: [$message]")
    }

  @Test
  fun `of multiple lastTransactions the greatest expiresDate wins`() =
    runTest {
      val laterExpiry = Instant.parse("2026-10-01T00:00:00Z")
      val body =
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = 2,
            signedTransactionInfo = AppStoreTestFixtures.signedTransaction(productId = "older.product"),
          ),
          AppStoreTestFixtures.LastTransaction(
            status = 1,
            signedTransactionInfo = AppStoreTestFixtures.signedTransaction(expiresDate = laterExpiry),
          ),
        )

      val lookup = api(ScriptedAppStoreTransport.of(200, body)).subscriptionStatus("1").getOrThrow()

      val found = assertIs<AppStoreSubscriptionLookup.Found>(lookup)
      assertEquals(AppStoreTestFixtures.PRODUCT_ID, found.subscription.productId, "the current transaction is the latest-expiring one")
      assertEquals(laterExpiry, found.subscription.periodEnd)
    }

  @Test
  fun `an entry lacking expiresDate is not auto-renewable — NotFound`() =
    runTest {
      val body =
        AppStoreTestFixtures.statusResponseBody(
          AppStoreTestFixtures.LastTransaction(
            status = 1,
            signedTransactionInfo = AppStoreTestFixtures.signedTransaction(expiresDate = null),
          ),
        )

      val lookup = api(ScriptedAppStoreTransport.of(200, body)).subscriptionStatus("1").getOrThrow()

      assertIs<AppStoreSubscriptionLookup.NotFound>(lookup)
    }

  @Test
  fun `Apple 404 is NotFound`() =
    runTest {
      val lookup =
        api(ScriptedAppStoreTransport.of(404, """{"errorCode":4040010}"""))
          .subscriptionStatus("1")
          .getOrThrow()
      assertIs<AppStoreSubscriptionLookup.NotFound>(lookup)
    }

  @Test
  fun `401, 429, 500, and a thrown IOException are each Unavailable naming their cause`() =
    runTest {
      for (status in listOf(401, 429, 500)) {
        val lookup =
          api(ScriptedAppStoreTransport.of(status, "trouble"))
            .subscriptionStatus("1")
            .getOrThrow()
        val unavailable = assertIs<AppStoreSubscriptionLookup.Unavailable>(lookup, "HTTP [$status]")
        assertTrue(unavailable.reason.contains("[$status]"), "reason names the status: [${unavailable.reason}]")
        assertTrue(unavailable.reason.contains("trouble"), "reason carries the body: [${unavailable.reason}]")
      }

      val lookup =
        api(ScriptedAppStoreTransport.throwing(IOException("connection reset")))
          .subscriptionStatus("1")
          .getOrThrow()
      val unavailable = assertIs<AppStoreSubscriptionLookup.Unavailable>(lookup, "IO failure")
      assertTrue(unavailable.reason.contains("IOException"), "reason names the exception: [${unavailable.reason}]")
      assertTrue(unavailable.reason.contains("connection reset"), "reason carries the message: [${unavailable.reason}]")
    }

  @Test
  fun `an unparsable 200 body is a failure — bug-grade, not Unavailable`() =
    runTest {
      val result = api(ScriptedAppStoreTransport.of(200, "not json")).subscriptionStatus("1")
      assertTrue(result.isFailure)
    }

  @Test
  fun `null tokens answer Unavailable with zero transport calls`() =
    runTest {
      val transport = ScriptedAppStoreTransport.of(200, AppStoreTestFixtures.activeStatusResponseBody())

      val lookup = AppStoreServerApi(transport, tokens = null).subscriptionStatus("1").getOrThrow()

      val unavailable = assertIs<AppStoreSubscriptionLookup.Unavailable>(lookup)
      assertTrue(
        unavailable.reason.contains("credentials not configured"),
        "reason names the unconfigured box: [${unavailable.reason}]",
      )
      assertEquals(0, transport.calls.size, "unconfigured credentials never reach the network")
    }
}
