package ed.unicoach.subscriptions

import ed.unicoach.db.models.Subscription

/** Outcome of [SubscriptionService.refresh] — the webhook's flow (RFC 112). */
sealed interface RefreshResult {
  /**
   * The row now reflects Apple's authoritative state. Non-entitling statuses
   * (expired/revoked/…) are still Refreshed, exactly as they are still
   * [VerifyResult.Verified]: recording Apple truth is this flow's job.
   */
  data class Refreshed(
    val subscription: Subscription,
  ) : RefreshResult

  /**
   * No local row owns this transaction: nothing to refresh, and no student to
   * bind one to. The normal state for a purchase whose app crashed before
   * posting `/verify` — the student's next verify binds and refreshes in one
   * call, so nothing is lost.
   */
  data object NotBound : RefreshResult

  /**
   * Apple knows no such transaction at all. Apple sent the notification, so its
   * own API should know the subscription — the job retries.
   */
  data object UnknownTransaction : RefreshResult

  /**
   * Apple answered for a different subscription than the notification named:
   * [expected] is the transaction asked about, [answered] is the one Apple
   * replied with. Distinct from [UnknownTransaction] because the two read the
   * same to a caller but not to an operator — collapsing them would report a
   * transaction Apple demonstrably knows as one it has never heard of.
   */
  data class MismatchedTransaction(
    val expected: String,
    val answered: String,
  ) : RefreshResult

  /** Apple answered, but no plan is configured for [productId] — config drift, not a client error. */
  data class UnknownProduct(
    val productId: String,
  ) : RefreshResult

  /**
   * Apple unreachable / erroring / credentials unconfigured — [reason] names
   * which, and [cause] carries the throwable behind an IO failure through to
   * the queue verdict's `cause` so the worker log gets the stack trace. Null
   * for the arms Apple answered rather than threw.
   */
  data class AppStoreUnavailable(
    val reason: String,
    val cause: Throwable? = null,
  ) : RefreshResult
}
