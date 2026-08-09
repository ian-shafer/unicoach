package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonValue
import ed.unicoach.db.models.SubscriptionStatus
import java.time.Instant

/** `POST /api/v1/subscriptions/verify` 200 body (RFC 110). */
data class SubscriptionVerifyResponse(
  val subscription: SubscriptionView,
)

/**
 * The closed wire vocabulary of [SubscriptionView.status] (RFC 110), one member
 * per [SubscriptionStatus] — Jackson serializes [wire] via its `@JsonValue`,
 * exactly as [ErrorCode] does. Declaring the set here rather than typing the
 * field `String` is what makes a stray or mis-cased status fail to compile, and
 * what lets `OpenApiSubscriptionStatusTest` hold the published spec to a
 * declaration instead of to a comment. [from] is exhaustive, so a status added
 * to or removed from [SubscriptionStatus] breaks the build here.
 */
enum class SubscriptionStatusView(
  @get:JsonValue val wire: String,
) {
  ACTIVE("active"),
  EXPIRED("expired"),
  BILLING_RETRY("billing_retry"),
  GRACE("grace"),
  REVOKED("revoked"),
  ;

  companion object {
    /** The member that renders the recorded [status]. */
    fun from(status: SubscriptionStatus): SubscriptionStatusView =
      when (status) {
        SubscriptionStatus.ACTIVE -> ACTIVE
        SubscriptionStatus.EXPIRED -> EXPIRED
        SubscriptionStatus.BILLING_RETRY -> BILLING_RETRY
        SubscriptionStatus.GRACE -> GRACE
        SubscriptionStatus.REVOKED -> REVOKED
      }
  }
}

/**
 * The client's view of the recorded subscription: its status, plan, and reset
 * point — no Apple identifiers, no dollars.
 */
data class SubscriptionView(
  val status: SubscriptionStatusView,
  val productId: String,
  /** ISO-8601 on the wire. */
  val currentPeriodEnd: Instant,
)
