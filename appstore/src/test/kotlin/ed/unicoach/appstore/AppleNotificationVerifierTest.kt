package ed.unicoach.appstore

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [AppleNotificationVerifier] (RFC 112): which inbound payloads are
 * applied, which are refused as unauthenticated, which are refused as somebody
 * else's, and which are refused as a shape nobody has seen.
 *
 * The three-way split is the point. [AppleNotificationOutcome.Untrusted] is what
 * an attacker can provoke at will; [AppleNotificationOutcome.ForeignTarget] is
 * Apple-signed but not ours; a [Result.failure] is a payload shape Apple has
 * never sent, which must reach an operator rather than be guessed at.
 */
class AppleNotificationVerifierTest {
  private val chain = AppStoreTestFixtures.certificateChain()

  private fun verifier(
    environment: String = "production",
    bundleId: String = AppStoreTestFixtures.BUNDLE_ID,
  ) = AppleNotificationVerifier(
    AppleJwsVerifier(setOf(chain.root)),
    AppStoreTestFixtures.appStoreConfig(environment, bundleId),
  )

  private suspend fun verified(signedPayload: String): AppleNotification =
    assertIs<AppleNotificationOutcome.Verified>(verifier().read(signedPayload).getOrThrow()).notification

  // ---------------------------------------------------------------------------
  // Applied
  // ---------------------------------------------------------------------------

  @Test
  fun `a verified notification carries its identifiers and the nested transaction's id`() =
    runTest {
      val signed =
        AppStoreTestFixtures.signedNotification(
          chain,
          notificationType = "DID_RENEW",
          subtype = "BILLING_RECOVERY",
          notificationUuid = "3ab1f4de-0000-4000-8000-000000000001",
        )

      val notification = verified(signed)

      assertEquals("3ab1f4de-0000-4000-8000-000000000001", notification.notificationUuid)
      assertEquals("DID_RENEW", notification.notificationType)
      assertEquals("BILLING_RECOVERY", notification.subtype)
      assertEquals(AppStoreEnvironment.PRODUCTION, notification.environment)
      assertEquals(AppStoreTestFixtures.BUNDLE_ID, notification.bundleId)
      assertEquals(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID, notification.originalTransactionId)
    }

  @Test
  fun `an unknown notificationType is carried verbatim, never matched`() =
    runTest {
      // A type Apple adds later must refresh state like any other; recognising
      // types is not this verifier's job.
      val notification = verified(AppStoreTestFixtures.signedNotification(chain, notificationType = "SOMETHING_APPLE_ADDS_IN_2029"))

      assertEquals("SOMETHING_APPLE_ADDS_IN_2029", notification.notificationType)
      assertEquals(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID, notification.originalTransactionId)
    }

  @Test
  fun `a data block with no signedTransactionInfo verifies with a null transaction id`() =
    runTest {
      // Apple's TEST notification: nothing to refresh, but nothing wrong either.
      val notification =
        verified(AppStoreTestFixtures.signedNotification(chain, notificationType = "TEST", subtype = null, signedTransactionInfo = null))

      assertEquals("TEST", notification.notificationType)
      assertNull(notification.subtype)
      assertNull(notification.originalTransactionId)
    }

  @Test
  fun `a summary block instead of data verifies with a null transaction id`() =
    runTest {
      // The RENEWAL_EXTENSION / SUMMARY shape carries no `data` at all; bundleId
      // and environment are read from `summary` and still held against config.
      val notification = verified(AppStoreTestFixtures.signedSummaryNotification(chain))

      assertEquals("RENEWAL_EXTENSION", notification.notificationType)
      assertEquals("SUMMARY", notification.subtype)
      assertEquals(AppStoreTestFixtures.BUNDLE_ID, notification.bundleId)
      assertEquals(AppStoreEnvironment.PRODUCTION, notification.environment)
      assertNull(notification.originalTransactionId)
    }

  // ---------------------------------------------------------------------------
  // Untrusted — nothing here proves the bytes came from Apple
  // ---------------------------------------------------------------------------

  @Test
  fun `a nested transaction on a foreign chain is Untrusted`() =
    runTest {
      // Rule 1 of the design: the nested JWS is VERIFIED, not merely decoded. The
      // outer notification is genuinely ours; only the transaction inside it is
      // another root's, and a decode-only reader would have accepted it.
      val foreign = AppStoreTestFixtures.certificateChain()
      val signed =
        AppStoreTestFixtures.signedNotification(
          chain,
          signedTransactionInfo = foreign.sign(AppStoreTestFixtures.transactionClaims()),
        )

      val outcome = assertIs<AppleNotificationOutcome.Untrusted>(verifier().read(signed).getOrThrow())

      assertTrue(outcome.reason.contains("nested [signedTransactionInfo]"), outcome.reason)
    }

  @Test
  fun `a notification signed by a foreign chain is Untrusted`() =
    runTest {
      val foreign = AppStoreTestFixtures.certificateChain()

      val outcome =
        assertIs<AppleNotificationOutcome.Untrusted>(verifier().read(AppStoreTestFixtures.signedNotification(foreign)).getOrThrow())

      assertTrue(outcome.reason.contains("not an Apple-signed notification"), outcome.reason)
      // The rendering boundary: the [JwsRefusal] the verifier hands back becomes
      // operator-facing prose HERE, so the check that fired is still legible in a log.
      assertTrue(outcome.reason.contains("does not validate to a pinned Apple trust anchor"), outcome.reason)
    }

  @Test
  fun `a blank signedPayload is Untrusted with no chain work`() =
    runTest {
      val outcome = assertIs<AppleNotificationOutcome.Untrusted>(verifier().read("   ").getOrThrow())

      assertTrue(outcome.reason.contains("must not be blank"), outcome.reason)
    }

  @Test
  fun `a non-JWS signedPayload is Untrusted`() =
    runTest {
      val outcome = assertIs<AppleNotificationOutcome.Untrusted>(verifier().read("definitely-not-a-jws").getOrThrow())

      assertTrue(outcome.reason.contains("not an Apple-signed notification"), outcome.reason)
    }

  @Test
  fun `a signedPayload over MAX_JWS is Untrusted before any verification`() =
    runTest {
      val oversized = "a".repeat((AppleNotificationVerifier.MAX_JWS.bytes + 1).toInt())

      val outcome = assertIs<AppleNotificationOutcome.Untrusted>(verifier().read(oversized).getOrThrow())

      assertTrue(outcome.reason.contains("at most ${AppleNotificationVerifier.MAX_JWS.bytes} characters"), outcome.reason)
    }

  // ---------------------------------------------------------------------------
  // ForeignTarget — Apple-signed, but not ours
  // ---------------------------------------------------------------------------

  @Test
  fun `a bundleId this box does not serve is ForeignTarget`() =
    runTest {
      val signed = AppStoreTestFixtures.signedNotification(chain, bundleId = "com.someone.else")

      val outcome = assertIs<AppleNotificationOutcome.ForeignTarget.WrongBundle>(verifier().read(signed).getOrThrow())

      assertEquals("com.someone.else", outcome.targeted)
      assertEquals(AppStoreTestFixtures.BUNDLE_ID, outcome.served)
    }

  @Test
  fun `a Sandbox notification against a production box is ForeignTarget`() =
    runTest {
      val signed = AppStoreTestFixtures.signedNotification(chain, environment = AppStoreTestFixtures.SANDBOX_ENVIRONMENT)

      val outcome =
        assertIs<AppleNotificationOutcome.ForeignTarget.WrongEnvironment>(verifier("production").read(signed).getOrThrow())

      assertEquals(AppStoreEnvironment.SANDBOX, outcome.targeted)
      assertEquals(AppStoreEnvironment.PRODUCTION, outcome.served)
    }

  @Test
  fun `a summary block carrying a foreign bundleId is ForeignTarget`() =
    runTest {
      // The target check reads whichever block is present, not `data` alone — a
      // summary-only notification for another bundle must not slip through.
      val signed = AppStoreTestFixtures.signedSummaryNotification(chain, bundleId = "com.someone.else")

      val outcome = assertIs<AppleNotificationOutcome.ForeignTarget.WrongBundle>(verifier().read(signed).getOrThrow())

      assertEquals("com.someone.else", outcome.targeted)
      assertEquals(AppStoreTestFixtures.BUNDLE_ID, outcome.served)
    }

  // ---------------------------------------------------------------------------
  // Result.failure — a shape Apple has never sent
  // ---------------------------------------------------------------------------

  @Test
  fun `a payload carrying neither data nor summary is a failure, not a refusal`() =
    runTest {
      val outcome = verifier().read(AppStoreTestFixtures.signedShapelessNotification(chain))

      assertTrue(outcome.isFailure)
      assertTrue(outcome.exceptionOrNull()!!.message!!.contains("neither a [data] nor a [summary] block"), "${outcome.exceptionOrNull()}")
    }

  @Test
  fun `a payload with no notificationUUID is a failure, not a refusal`() =
    runTest {
      val outcome = verifier().read(AppStoreTestFixtures.signedNotification(chain, notificationUuid = null))

      assertTrue(outcome.isFailure)
      assertTrue(outcome.exceptionOrNull()!!.message!!.contains("[notificationUUID]"), "${outcome.exceptionOrNull()}")
    }

  @Test
  fun `a target block with no environment is a failure, not a refusal`() =
    runTest {
      val outcome = verifier().read(AppStoreTestFixtures.signedNotification(chain, environment = null))

      assertTrue(outcome.isFailure)
      assertTrue(outcome.exceptionOrNull()!!.message!!.contains("[environment]"), "${outcome.exceptionOrNull()}")
    }

  @Test
  fun `an unknown environment string is a failure, not a refusal`() =
    runTest {
      val outcome = verifier().read(AppStoreTestFixtures.signedNotification(chain, environment = "Xcode"))

      assertTrue(outcome.isFailure)
      assertTrue(outcome.exceptionOrNull()!!.message!!.contains("neither Sandbox nor Production"), "${outcome.exceptionOrNull()}")
    }

  @Test
  fun `a verified transaction with no originalTransactionId is a failure, not a refusal`() =
    runTest {
      val signed =
        AppStoreTestFixtures.signedNotification(
          chain,
          signedTransactionInfo = chain.sign(AppStoreTestFixtures.transactionClaims(originalTransactionId = null)),
        )

      val outcome = verifier().read(signed)

      assertTrue(outcome.isFailure)
      assertTrue(outcome.exceptionOrNull()!!.message!!.contains("[originalTransactionId]"), "${outcome.exceptionOrNull()}")
    }
}
