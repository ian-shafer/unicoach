package ed.unicoach.appstore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Instant

/** Outcome of one authoritative subscription-state lookup against Apple. */
sealed interface AppStoreSubscriptionLookup {
  data class Found(
    val subscription: AppleSubscription,
  ) : AppStoreSubscriptionLookup

  /** Apple 404 (unknown transaction in this environment), or a response with no auto-renewable entry. */
  data object NotFound : AppStoreSubscriptionLookup

  /**
   * Unconfigured credentials, 401, 429, 5xx, or IO failure. [reason] is the
   * same text this lookup WARN/ERROR-logged — which of those it was, with the
   * status and body or the exception type and message — so a caller mapping
   * this outcome onward does not have to send the reader to the logs.
   *
   * [cause] is the throwable behind an IO failure, carried so a caller logging
   * this outcome logs the stack trace rather than the message alone. The
   * outcomes Apple answered rather than threw (unconfigured credentials, a
   * non-200 status) have none.
   */
  data class Unavailable(
    val reason: String,
    val cause: Throwable? = null,
  ) : AppStoreSubscriptionLookup
}

/** Apple's subscription status integers, named: 1=ACTIVE, 2=EXPIRED, 3=BILLING_RETRY, 4=GRACE, 5=REVOKED. */
enum class AppleSubscriptionStatus {
  ACTIVE,
  EXPIRED,
  BILLING_RETRY,
  GRACE,
  REVOKED,
}

/** The authoritative state of one Apple auto-renewable subscription, as this lookup decoded it. */
class AppleSubscription(
  val originalTransactionId: String,
  val productId: String,
  val status: AppleSubscriptionStatus,
  // The latest transaction's purchaseDate.
  val periodStart: Instant,
  // Its expiresDate; GRACE: renewal info's gracePeriodExpiresDate, required there.
  val periodEnd: Instant,
)

/**
 * The App Store Server API client (RFC 110): one Apple read — Get All
 * Subscription Statuses — decoded via [AppleJws] (TLS-trusted, so no x5c chain
 * verification on this path) and mapped to [AppleSubscription].
 *
 * Failure taxonomy: infrastructure trouble (unconfigured credentials, 401, 429,
 * 5xx, IO) is [AppStoreSubscriptionLookup.Unavailable]; an unknown transaction
 * or a response with no auto-renewable entry is
 * [AppStoreSubscriptionLookup.NotFound]; a 200 body that fails to parse, an
 * unknown status integer, or a GRACE entry carrying no
 * `gracePeriodExpiresDate`, is a [Result.failure] (bug-grade — a new Apple
 * state or response shape must be looked at, not guessed at). A transaction id
 * failing [TRANSACTION_ID_PATTERN] is that same bug-grade [Result.failure],
 * raised before any call is made.
 */
class AppStoreServerApi(
  private val transport: AppStoreTransport,
  // Null = credentials unconfigured; every lookup answers Unavailable.
  private val tokens: AppStoreAuthTokens?,
  private val appleJws: AppleJws = AppleJws(),
) {
  private val logger = LoggerFactory.getLogger(AppStoreServerApi::class.java)

  /** GET /inApps/v1/subscriptions/{transactionId} (Get All Subscription Statuses). */
  suspend fun subscriptionStatus(transactionId: String): Result<AppStoreSubscriptionLookup> {
    if (!TRANSACTION_ID_PATTERN.matches(transactionId)) {
      return Result.failure(
        IllegalArgumentException(
          "App Store subscription lookup refused transaction [$transactionId]: it does not match " +
            "[${TRANSACTION_ID_PATTERN.pattern}], the only shape this client embeds in [$SUBSCRIPTIONS_PATH]",
        ),
      )
    }
    if (tokens == null) {
      val reason = "App Store credentials not configured"
      logger.error(reason)
      return Result.success(AppStoreSubscriptionLookup.Unavailable(reason))
    }

    val response =
      try {
        transport.get("$SUBSCRIPTIONS_PATH/$transactionId", tokens.mint())
      } catch (e: Exception) {
        val reason =
          "App Store subscription lookup for [$transactionId] failed with [${e::class.simpleName}]: [${e.message}]"
        logger.warn(reason, e)
        return Result.success(AppStoreSubscriptionLookup.Unavailable(reason, e))
      }

    return when {
      response.status == 200 -> {
        runCatching { mapStatusResponse(response.body) }
      }

      response.status == 404 -> {
        logger.info("App Store knows no transaction [$transactionId] in this environment (404)")
        Result.success(AppStoreSubscriptionLookup.NotFound)
      }

      else -> {
        val reason =
          "App Store subscription lookup for [$transactionId] answered [${response.status}]: [${response.body}]"
        logger.warn(reason)
        Result.success(AppStoreSubscriptionLookup.Unavailable(reason))
      }
    }
  }

  /**
   * Maps the 200 response JSON (`{environment, bundleId, data:
   * [{subscriptionGroupIdentifier, lastTransactions: [{originalTransactionId,
   * status, signedTransactionInfo, signedRenewalInfo}]}]}`) to one
   * [AppStoreSubscriptionLookup]. A body carrying no auto-renewable entry is
   * [AppStoreSubscriptionLookup.NotFound].
   */
  private fun mapStatusResponse(body: String): AppStoreSubscriptionLookup {
    val root =
      Json.parseToJsonElement(body) as? JsonObject
        ?: throw IllegalArgumentException("App Store 200 body is not a JSON object")
    val current = currentEntry(decodeEntries(root))
    if (current == null) {
      logger.info("App Store response carries no auto-renewable entry (no expiresDate); treating as not found")
      return AppStoreSubscriptionLookup.NotFound
    }

    val status = mapStatus(current)
    return AppStoreSubscriptionLookup.Found(
      AppleSubscription(
        originalTransactionId = current.transaction.requiredText("originalTransactionId", SUBJECT),
        productId = current.transaction.requiredText("productId", SUBJECT),
        status = status,
        periodStart = Instant.ofEpochMilli(current.transaction.requiredNumber("purchaseDate", SUBJECT)),
        periodEnd = periodEnd(current, status),
      ),
    )
  }

  /**
   * Flattens `data[].lastTransactions[]` and decodes each entry's
   * `signedTransactionInfo` (TLS-trusted, so no x5c chain verification).
   */
  private fun decodeEntries(root: JsonObject): List<DecodedEntry> =
    (root["data"] ?: throw IllegalArgumentException("App Store 200 body has no [data] array"))
      .jsonArray
      .flatMap { group -> group.jsonObject["lastTransactions"]?.jsonArray ?: emptyList() }
      .map { entry ->
        val entryObject = entry.jsonObject
        val signedTransactionInfo =
          entryObject["signedTransactionInfo"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("lastTransactions entry has no [signedTransactionInfo]")
        DecodedEntry(
          entry = entryObject,
          transaction = appleJws.payload(signedTransactionInfo).getOrThrow(),
        )
      }

  /**
   * The current transaction is the entry with the greatest `expiresDate`.
   * Multi-group/multi-plan selection beyond that is out of scope with one
   * product. An entry lacking `expiresDate` is not an auto-renewable
   * subscription, so none of them being one yields null.
   */
  private fun currentEntry(entries: List<DecodedEntry>): DecodedEntry? =
    entries
      .filter { it.expiresDate != null }
      .maxByOrNull { it.expiresDate!! }

  private fun mapStatus(entry: DecodedEntry): AppleSubscriptionStatus {
    val raw =
      entry.entry["status"]?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("lastTransactions entry has no integer [status]")
    return when (raw) {
      1L -> AppleSubscriptionStatus.ACTIVE

      2L -> AppleSubscriptionStatus.EXPIRED

      3L -> AppleSubscriptionStatus.BILLING_RETRY

      4L -> AppleSubscriptionStatus.GRACE

      5L -> AppleSubscriptionStatus.REVOKED

      // A new Apple state must be looked at, not guessed at.
      else -> throw IllegalArgumentException("Unknown App Store subscription status integer [$raw]")
    }
  }

  /**
   * The entitlement window's end: `expiresDate`, except a GRACE entry reads the
   * renewal info's `gracePeriodExpiresDate`, which it requires. Grace runs
   * *past* `expiresDate` — already elapsed whenever the status is GRACE — so
   * falling back to it would set `periodEnd` in the past and un-entitle the
   * very subscriber grace protects; a GRACE entry without the field is instead
   * refused the way an unknown status integer is. Timestamps are Apple
   * epoch-milliseconds.
   */
  private fun periodEnd(
    entry: DecodedEntry,
    status: AppleSubscriptionStatus,
  ): Instant {
    val expires = Instant.ofEpochMilli(entry.expiresDate!!)
    if (status != AppleSubscriptionStatus.GRACE) return expires
    val graceExpiresMillis =
      entry.entry["signedRenewalInfo"]
        ?.jsonPrimitive
        ?.content
        ?.let { appleJws.payload(it).getOrThrow() }
        ?.get("gracePeriodExpiresDate")
        ?.jsonPrimitive
        ?.longOrNull
        ?: throw IllegalArgumentException(
          "GRACE entry [${entry.transaction.requiredText("originalTransactionId", SUBJECT)}] expiring [$expires] carries no " +
            "gracePeriodExpiresDate — a new Apple response shape must be looked at, not guessed at",
        )
    return Instant.ofEpochMilli(graceExpiresMillis)
  }

  /** One `lastTransactions` entry alongside its decoded `signedTransactionInfo` payload. */
  private class DecodedEntry(
    val entry: JsonObject,
    val transaction: JsonObject,
  ) {
    val expiresDate: Long? get() = transaction["expiresDate"]?.jsonPrimitive?.longOrNull
  }

  companion object {
    const val SUBSCRIPTIONS_PATH = "/inApps/v1/subscriptions"

    /**
     * The only transaction-id shape this client will interpolate into
     * [SUBSCRIPTIONS_PATH]; anything else is refused before any Apple call.
     * Enforced at this one sink rather than per caller, so no caller — present
     * or future — can reach the path by forgetting to check. A caller running
     * its own request-shape gate (to answer a client with a validation failure
     * rather than a bug-grade one) matches against this same pattern.
     */
    val TRANSACTION_ID_PATTERN = Regex("^[0-9]{1,32}$")

    /** Names this reader's JSON in every [requiredText] refusal. */
    private const val SUBJECT = "Transaction payload"
  }
}
