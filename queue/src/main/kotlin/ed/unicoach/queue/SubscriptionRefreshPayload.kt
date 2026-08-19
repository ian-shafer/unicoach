package ed.unicoach.queue

import kotlinx.serialization.Serializable

/**
 * Payload of a [JobType.REFRESH_SUBSCRIPTION] job (RFC 112): the Apple
 * subscription to re-read. [originalTransactionId] is the only field the handler
 * acts on; the notification identifiers ride along as log context so a
 * dead-lettered job can be traced back to the delivery that produced it, and
 * never select behaviour.
 *
 * The raw `signedPayload` is deliberately NOT carried: re-verifying in the
 * worker would make a job's success depend on certificate validity at execution
 * time rather than at receipt.
 */
@Serializable
data class SubscriptionRefreshPayload(
  val originalTransactionId: String,
  val notificationUuid: String,
  val notificationType: String,
  val subtype: String? = null,
)
