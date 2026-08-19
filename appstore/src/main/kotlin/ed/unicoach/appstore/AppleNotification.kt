package ed.unicoach.appstore

/** One verified App Store Server Notification V2, reduced to what we act on and log. */
class AppleNotification(
  val notificationUuid: String,
  // Carried verbatim, never matched against a known set: an unrecognised type
  // still refreshes state correctly, so recognising types is not this design's job.
  val notificationType: String,
  val subtype: String?,
  val environment: AppStoreEnvironment,
  val bundleId: String,
  /**
   * Null when the notification names no transaction — Apple's TEST
   * notification carries a `data` block with no `signedTransactionInfo`, and
   * the summary-bearing types (`RENEWAL_EXTENSION` with subtype `SUMMARY`)
   * carry a `summary` block and no `data` at all. Nothing to refresh.
   */
  val originalTransactionId: String?,
)

/** Outcome of reading one inbound `signedPayload`. */
sealed interface AppleNotificationOutcome {
  data class Verified(
    val notification: AppleNotification,
  ) : AppleNotificationOutcome

  /**
   * Nothing here is proof the bytes came from Apple: an oversized body, a
   * malformed JWS, or any [AppleJwsVerifier] failure on the outer payload or
   * the nested transaction. [reason] is the operator-facing detail; the client
   * is told only that it is unauthenticated.
   */
  data class Untrusted(
    val reason: String,
  ) : AppleNotificationOutcome

  /**
   * Apple-signed, but for another bundle or another App Store environment than
   * this box serves. Split by axis, and carrying the two values that disagree
   * rather than a sentence about them, because the two crossings mean different
   * things to an operator: a foreign bundle is App Store Connect pointed at the
   * wrong app, a foreign environment is routine Sandbox/Production cross-talk.
   */
  sealed interface ForeignTarget : AppleNotificationOutcome {
    data class WrongBundle(
      val targeted: String,
      val served: String,
    ) : ForeignTarget

    data class WrongEnvironment(
      val targeted: AppStoreEnvironment,
      val served: AppStoreEnvironment,
    ) : ForeignTarget
  }
}
