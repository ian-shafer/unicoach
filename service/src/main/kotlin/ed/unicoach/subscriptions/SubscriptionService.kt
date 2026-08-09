package ed.unicoach.subscriptions

import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreSubscriptionLookup
import ed.unicoach.appstore.AppleJws
import ed.unicoach.appstore.AppleSubscription
import ed.unicoach.appstore.AppleSubscriptionStatus
import ed.unicoach.common.util.DataSize
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SubscriptionUpsert
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.error.FieldError
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * The one verify-and-record flow (RFC 110): bound + decode the posted StoreKit 2
 * signed transaction (a lookup key only — the client JWS is never trusted and
 * never signature-verified), fetch the authoritative subscription state from
 * Apple over TLS, and upsert the `subscriptions` row. Re-posting is the
 * idempotent refresh path (same row, updated state) — and, until the
 * Notifications-V2 webhook RFC lands, the only way a renewal reaches the server.
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

      val subscription =
        when (val lookup = appStore.subscriptionStatus(transactionId).getOrThrow()) {
          is AppStoreSubscriptionLookup.NotFound -> {
            return@runCatching VerifyResult.UnknownTransaction
          }

          is AppStoreSubscriptionLookup.Unavailable -> {
            logger.warn(
              "App Store verification unavailable for student [${studentId.asString}] " +
                "transaction [$transactionId]: [${lookup.reason}]",
            )
            return@runCatching VerifyResult.AppStoreUnavailable(lookup.reason)
          }

          is AppStoreSubscriptionLookup.Found -> {
            lookup.subscription
          }
        }

      if (plans.periodBudget(subscription.productId) == null) {
        return@runCatching VerifyResult.UnknownProduct(subscription.productId)
      }

      record(studentId, subscription)
    }

  /**
   * The request-shape gate, before any Apple call: bound the JWS, decode its
   * payload, and hold the `transactionId` claim to `^[0-9]{1,32}$` — it is
   * embedded in the App Store API path, so nothing else may pass.
   */
  private fun extractTransactionId(signedTransaction: String): Validated {
    if (signedTransaction.isBlank()) {
      return Validated.failure("signedTransaction must not be blank")
    }
    if (signedTransaction.length > MAX_JWS.bytes) {
      return Validated.failure("signedTransaction must be at most ${MAX_JWS.bytes} characters")
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
    if (transactionId == null || !TRANSACTION_ID_PATTERN.matches(transactionId)) {
      return Validated.failure("signedTransaction carries no usable transactionId claim")
    }
    return Validated.TransactionId(transactionId)
  }

  private suspend fun record(
    studentId: StudentId,
    subscription: AppleSubscription,
  ): VerifyResult {
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
      // A no-op refresh is a successful verification: the row already says
      // what Apple says.
      is SubscriptionUpsert.Applied -> {
        VerifyResult.Verified(upsert.subscription)
      }

      is SubscriptionUpsert.Unchanged -> {
        VerifyResult.Verified(upsert.subscription)
      }

      // The owning student is named for the operator only: the response below
      // says "another account" and never carries that id to the client.
      is SubscriptionUpsert.OwnedByOtherStudent -> {
        logger.warn(
          "Student [${studentId.asString}] attempted to verify subscription " +
            "[${upsert.existing.originalTransactionId}] already bound to student [${upsert.existing.studentId.asString}]",
        )
        VerifyResult.OwnedByOtherAccount
      }
    }
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
     * hostile input. A JWS is base64url — ASCII throughout — so its character
     * count and its byte count are the same number, and comparing a `length`
     * against this size is exact rather than an approximation.
     */
    val MAX_JWS: DataSize = DataSize.ofKibibytes(16)

    const val TRANSACTION_ID_CLAIM = "transactionId"
    private val TRANSACTION_ID_PATTERN = Regex("^[0-9]{1,32}$")

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
