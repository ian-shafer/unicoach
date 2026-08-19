package ed.unicoach.subscriptions

import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreSubscriptionLookup
import ed.unicoach.appstore.AppleJws
import ed.unicoach.appstore.AppleSubscription
import ed.unicoach.appstore.AppleSubscriptionStatus
import ed.unicoach.appstore.jwsBoundsFailure
import ed.unicoach.common.util.DataSize
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SubscriptionUpsert
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.Subscription
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.error.FieldError
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * The two ways Apple's subscription state reaches the `subscriptions` table, and
 * the one mapping they share.
 *
 * [verify] (RFC 110) is the purchase-time call: bound + decode the posted
 * StoreKit 2 signed transaction (a lookup key only — the client JWS is never
 * trusted and never signature-verified), fetch the authoritative subscription
 * state from Apple over TLS, and upsert the row under the authenticated student.
 * It is the only path that BINDS a subscription to a student.
 *
 * [refresh] (RFC 112) is the webhook's call: the same fetch and upsert, under
 * the student the existing row already names. It is how a renewal reaches the
 * server without the app posting anything.
 *
 * They differ only in where the student comes from — the session for one, the
 * existing row for the other — so the Apple lookup → plan check → upsert tail is
 * one private helper, keeping a single Apple→row mapping in the codebase.
 *
 * The Apple call runs inline on the request coroutine — the documented
 * `ASYNC_WORK.md` exception: the response's entire purpose is the verification
 * result, so the result is required for the response to be correct.
 *
 * Ordering: validation (no Apple call for garbage) → Apple lookup → plan check
 * (before writing — a row the gate would immediately fail closed on is never
 * created) → upsert. The account binding is the authenticated session:
 * whichever student first verifies an `originalTransactionId` owns it, and
 * [VerifyResult.OwnedByOtherAccount] guards every later rebind attempt.
 */
class SubscriptionService(
  private val database: Database,
  private val appStore: AppStoreServerApi,
  private val plans: SubscriptionPlans,
  private val appleJws: AppleJws = AppleJws(),
) {
  private val logger = LoggerFactory.getLogger(SubscriptionService::class.java)

  suspend fun verify(
    studentId: StudentId,
    signedTransaction: String,
  ): Result<VerifyResult> =
    runCatching {
      val transactionId =
        when (val validated = extractTransactionId(signedTransaction)) {
          is Validated.Failure -> return@runCatching VerifyResult.ValidationFailure(validated.fieldErrors)
          is Validated.TransactionId -> validated.value
        }

      // No expected key: this is the binding call, so whichever
      // originalTransactionId Apple answers with IS the key.
      when (val recorded = lookupAndRecord(studentId, transactionId, expectedOriginalTransactionId = null)) {
        is Recorded.Applied -> {
          VerifyResult.Verified(recorded.subscription)
        }

        is Recorded.UnknownTransaction -> {
          VerifyResult.UnknownTransaction
        }

        is Recorded.UnknownProduct -> {
          VerifyResult.UnknownProduct(recorded.productId)
        }

        is Recorded.AppStoreUnavailable -> {
          VerifyResult.AppStoreUnavailable(recorded.reason)
        }

        is Recorded.OwnedByOtherStudent -> {
          VerifyResult.OwnedByOtherAccount
        }

        // Unreachable: a mismatch needs a key to mismatch against, and the call
        // above passes none. Surfaced rather than folded into
        // UnknownTransaction, because reaching it means the tail compared
        // against a key this flow never supplied.
        is Recorded.MismatchedTransaction -> {
          throw IllegalStateException(
            "Verify of [$transactionId] compared against unrequested expected transaction [${recorded.expected}]; " +
              "App Store answered [${recorded.answered}]",
          )
        }
      }
    }

  /**
   * The webhook's flow (RFC 112): read the owning row, re-fetch authoritative
   * state from Apple, and upsert it under the student that row already names.
   *
   * Refreshes but never BINDS — a subscription no student has verified is
   * [RefreshResult.NotBound], because the notification carries no student
   * identity and inventing one would let an Apple-authenticated endpoint mint an
   * entitlement.
   *
   * The read and the write are separate transactions with the Apple call between
   * them, so no database connection is held across network I/O. Ownership cannot
   * drift in that gap: the write is keyed by the same transaction the read
   * matched, and a rebind is refused permanently.
   */
  suspend fun refresh(originalTransactionId: String): Result<RefreshResult> =
    runCatching {
      val existing =
        database.withConnection { session ->
          SubscriptionsDao.findByOriginalTransactionId(session, originalTransactionId).getOrThrow()
        }
      if (existing == null) {
        // No Apple call: an unbound notification costs nothing.
        logger.info("No subscription row owns App Store transaction [$originalTransactionId]; nothing to refresh")
        return@runCatching RefreshResult.NotBound
      }

      when (
        val recorded =
          lookupAndRecord(existing.studentId, originalTransactionId, expectedOriginalTransactionId = originalTransactionId)
      ) {
        is Recorded.Applied -> {
          RefreshResult.Refreshed(recorded.subscription)
        }

        is Recorded.UnknownTransaction -> {
          RefreshResult.UnknownTransaction
        }

        is Recorded.MismatchedTransaction -> {
          RefreshResult.MismatchedTransaction(expected = recorded.expected, answered = recorded.answered)
        }

        is Recorded.UnknownProduct -> {
          RefreshResult.UnknownProduct(recorded.productId)
        }

        is Recorded.AppStoreUnavailable -> {
          RefreshResult.AppStoreUnavailable(recorded.reason, recorded.cause)
        }

        // Unreachable: the upsert is keyed by the transaction the read above
        // matched, under that row's own student. Surfaced rather than folded
        // away, because reaching it means the read and the write disagreed about
        // who owns the row.
        is Recorded.OwnedByOtherStudent -> {
          throw IllegalStateException(
            "Refresh of [$originalTransactionId] read student [${existing.studentId.asString}] but the upsert found " +
              "student [${recorded.existing.studentId.asString}] owning it",
          )
        }
      }
    }

  /**
   * The tail both flows share: fetch authoritative state for [transactionId],
   * refuse a product no plan covers, and upsert under [studentId].
   *
   * [expectedOriginalTransactionId] is the key the caller already knows the row
   * is under, or null when the caller is binding for the first time. Holding
   * Apple's answer to it is load-bearing for [refresh]: Apple's *Get All
   * Subscription Statuses* answers for every auto-renewable subscription the
   * customer holds, and [AppStoreServerApi] reduces that to the entry with the
   * greatest `expiresDate`, so the returned `originalTransactionId` is not
   * guaranteed to be the one asked about.
   */
  private suspend fun lookupAndRecord(
    studentId: StudentId,
    transactionId: String,
    expectedOriginalTransactionId: String?,
  ): Recorded {
    val subscription =
      when (val lookup = appStore.subscriptionStatus(transactionId).getOrThrow()) {
        is AppStoreSubscriptionLookup.NotFound -> {
          return Recorded.UnknownTransaction
        }

        is AppStoreSubscriptionLookup.Unavailable -> {
          logger.warn(
            "App Store lookup unavailable for student [${studentId.asString}] " +
              "transaction [$transactionId]: [${lookup.reason}]",
            lookup.cause,
          )
          return Recorded.AppStoreUnavailable(lookup.reason, lookup.cause)
        }

        is AppStoreSubscriptionLookup.Found -> {
          lookup.subscription
        }
      }

    if (expectedOriginalTransactionId != null && subscription.originalTransactionId != expectedOriginalTransactionId) {
      logger.warn(
        "App Store answered for transaction [${subscription.originalTransactionId}] when asked about " +
          "[$expectedOriginalTransactionId]; nothing written",
      )
      return Recorded.MismatchedTransaction(
        expected = expectedOriginalTransactionId,
        answered = subscription.originalTransactionId,
      )
    }

    if (plans.periodBudget(subscription.productId) == null) {
      return Recorded.UnknownProduct(subscription.productId)
    }

    return record(studentId, subscription)
  }

  /**
   * The request-shape gate, before any Apple call: bound the JWS, decode its
   * payload, and hold the `transactionId` claim to
   * [AppStoreServerApi.TRANSACTION_ID_PATTERN] — it is embedded in the App
   * Store API path, so nothing else may pass. The sink refuses a violation
   * itself; this gate exists so a client-supplied one comes back as a
   * validation failure rather than a bug-grade one.
   */
  private fun extractTransactionId(signedTransaction: String): Validated {
    val bounded = jwsBoundsFailure(signedTransaction, MAX_JWS, "signedTransaction")
    if (bounded != null) {
      return Validated.failure(bounded)
    }
    val payload =
      appleJws.payload(signedTransaction).getOrElse { decodeFailure ->
        // The decode reason (segment count, base64url, non-object payload) is
        // operator-facing only: this is a public route fed a client-supplied
        // JWS, so the caller gets a generic message and the root cause goes to
        // the log.
        logger.warn("signedTransaction failed to decode as a JWS", decodeFailure)
        return Validated.failure("signedTransaction is not a decodable JWS")
      }
    val transactionId = payload[TRANSACTION_ID_CLAIM]?.jsonPrimitive?.content
    if (transactionId == null || !AppStoreServerApi.TRANSACTION_ID_PATTERN.matches(transactionId)) {
      return Validated.failure("signedTransaction carries no usable transactionId claim")
    }
    return Validated.TransactionId(transactionId)
  }

  private suspend fun record(
    studentId: StudentId,
    subscription: AppleSubscription,
  ): Recorded {
    val upsert =
      database.withConnection { session ->
        SubscriptionsDao
          .upsert(
            session,
            studentId = studentId,
            originalTransactionId = subscription.originalTransactionId,
            productId = subscription.productId,
            status = subscription.status.toRow(),
            periodStart = subscription.periodStart,
            periodEnd = subscription.periodEnd,
          ).getOrThrow()
      }
    return when (upsert) {
      // A write happened — either a fresh bind or the state-distinct guard let
      // the conflict arm's update through because Apple's state differs.
      is SubscriptionUpsert.Applied -> {
        Recorded.Applied(upsert.subscription)
      }

      // A no-op refresh is a successful record too: the row already says what
      // Apple says, so it maps to the same Recorded.Applied.
      is SubscriptionUpsert.Unchanged -> {
        Recorded.Applied(upsert.subscription)
      }

      // The owning student is named for the operator only: the verify response
      // says "another account" and never carries that id to the client.
      is SubscriptionUpsert.OwnedByOtherStudent -> {
        logger.warn(
          "Student [${studentId.asString}] attempted to record subscription " +
            "[${upsert.existing.originalTransactionId}] already bound to student [${upsert.existing.studentId.asString}]",
        )
        Recorded.OwnedByOtherStudent(upsert.existing)
      }
    }
  }

  /**
   * What the shared tail did, in terms neither caller's public vocabulary owns.
   * [verify] and [refresh] each map every arm, so an arm added here has to be
   * answered by both.
   */
  private sealed interface Recorded {
    /** The row now reflects Apple's state, whether it was written or already said so. */
    data class Applied(
      val subscription: Subscription,
    ) : Recorded

    /** Apple knows no such transaction. */
    data object UnknownTransaction : Recorded

    /**
     * Apple answered about a different transaction than [expected]; [answered]
     * is the one it replied with. Only reachable for a caller that passed an
     * expected key.
     */
    data class MismatchedTransaction(
      val expected: String,
      val answered: String,
    ) : Recorded

    data class UnknownProduct(
      val productId: String,
    ) : Recorded

    data class AppStoreUnavailable(
      val reason: String,
      val cause: Throwable? = null,
    ) : Recorded

    data class OwnedByOtherStudent(
      val existing: Subscription,
    ) : Recorded
  }

  private sealed interface Validated {
    data class TransactionId(
      val value: String,
    ) : Validated

    data class Failure(
      val fieldErrors: List<FieldError>,
    ) : Validated

    companion object {
      fun failure(message: String): Validated = Failure(listOf(FieldError(field = "signedTransaction", message = message)))
    }
  }

  companion object {
    /**
     * A StoreKit 2 signed transaction is single-digit KiB; 16 KiB bounds
     * hostile input. [jwsBoundsFailure] holds the string to it.
     */
    val MAX_JWS: DataSize = DataSize.ofKibibytes(16)

    const val TRANSACTION_ID_CLAIM = "transactionId"

    private fun AppleSubscriptionStatus.toRow(): SubscriptionStatus =
      when (this) {
        AppleSubscriptionStatus.ACTIVE -> SubscriptionStatus.ACTIVE
        AppleSubscriptionStatus.EXPIRED -> SubscriptionStatus.EXPIRED
        AppleSubscriptionStatus.BILLING_RETRY -> SubscriptionStatus.BILLING_RETRY
        AppleSubscriptionStatus.GRACE -> SubscriptionStatus.GRACE
        AppleSubscriptionStatus.REVOKED -> SubscriptionStatus.REVOKED
      }
  }
}
