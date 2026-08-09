package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

@JvmInline
value class SubscriptionId(
  val value: UUID,
)

/**
 * The closed status set of the `subscriptions` entity (RFC 110) — Apple's
 * subscription status integers snake_cased (1=active, 2=expired,
 * 3=billing_retry, 4=grace, 5=revoked). Wire and DB share the lowercase string;
 * [value] is that single spelling. Entitling statuses are ACTIVE and GRACE.
 */
enum class SubscriptionStatus(
  val value: String,
) {
  ACTIVE("active"),
  EXPIRED("expired"),
  BILLING_RETRY("billing_retry"),
  GRACE("grace"),
  REVOKED("revoked"),
  ;

  companion object {
    /** The member whose [value] is [value], or throws naming the stray string. */
    fun from(value: String): SubscriptionStatus =
      entries.firstOrNull { it.value == value }
        ?: throw IllegalArgumentException("Not a subscription status: [$value]")
  }
}

/**
 * A row of the versioned `subscriptions` entity (RFC 110): the server-side
 * state of record for one Apple auto-renewable subscription, keyed by Apple's
 * [originalTransactionId] (stable across renewals) and bound to the student who
 * verified it. `[periodStart, periodEnd)` is both the entitlement window and
 * the subscription meter's `windowedCost` bounds; there is no `deleted_at` —
 * lifecycle is [status] (expired/revoked rows remain).
 */
class Subscription(
  val id: SubscriptionId,
  val version: Int,
  val studentId: StudentId,
  val originalTransactionId: String,
  val productId: String,
  val status: SubscriptionStatus,
  val periodStart: Instant,
  val periodEnd: Instant,
  val createdAt: Instant,
  val updatedAt: Instant,
)
