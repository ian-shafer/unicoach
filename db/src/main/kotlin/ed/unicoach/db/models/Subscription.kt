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
 * [value] is that single spelling.
 *
 * WHICH STATUSES ENTITLE is a property of this enum and is declared here
 * ([entitles]), not restated by its readers: `SubscriptionsDao`'s gate read
 * builds its `status IN (...)` from [ENTITLING], and the predicate of the index
 * that serves that read (`subscriptions_user_current_idx`) is pinned against
 * [ENTITLING] by `SubscriptionEntitlingStatusPinTest`. A sixth member cannot be
 * added without deciding, because the constructor demands the answer — and the
 * answer the constructor takes is the one the gate acts on, so a status that
 * should entitle cannot compile, migrate and read clean while coaching is
 * refused to somebody who paid for it.
 */
enum class SubscriptionStatus(
  val value: String,
  /** Whether a row in this status entitles its account while its period covers now. */
  val entitles: Boolean,
) {
  ACTIVE("active", entitles = true),
  EXPIRED("expired", entitles = false),
  BILLING_RETRY("billing_retry", entitles = false),
  GRACE("grace", entitles = true),
  REVOKED("revoked", entitles = false),
  ;

  companion object {
    /**
     * The statuses that entitle, in declaration order — what `SubscriptionsDao`'s
     * gate read and `subscriptions_user_current_idx`'s predicate are both built
     * on.
     */
    val ENTITLING: List<SubscriptionStatus> = entries.filter { it.entitles }

    /** The member whose [value] is [value], or throws naming the stray string. */
    fun from(value: String): SubscriptionStatus =
      entries.firstOrNull { it.value == value }
        ?: throw IllegalArgumentException("Not a subscription status: [$value]")
  }
}

/**
 * A row of the versioned `subscriptions` entity (RFC 110): the server-side
 * state of record for one Apple auto-renewable subscription, keyed by Apple's
 * [originalTransactionId] (stable across renewals) and owned by the ACCOUNT that
 * verified it. `[periodStart, periodEnd)` is both the entitlement window and
 * the subscription meter's windowed bounds; there is no `deleted_at` —
 * lifecycle is [status] (expired/revoked rows remain).
 *
 * [userId] and not a student (RFC 244): every account pays for itself, so a
 * supporter account holds its own row and a student's row hangs off their
 * account, one indexed hop from the student. A second account signed in to the
 * SAME Apple ID holds NO row — Apple will not sell the subscription twice — and
 * gets no coverage from this one either; that refusal is counted, not covered.
 */
class Subscription(
  val id: SubscriptionId,
  val version: Int,
  val userId: UserId,
  val originalTransactionId: String,
  val productId: String,
  val status: SubscriptionStatus,
  val periodStart: Instant,
  val periodEnd: Instant,
  val createdAt: Instant,
  val updatedAt: Instant,
)
