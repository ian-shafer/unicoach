package ed.unicoach.subscriptions

import ed.unicoach.db.models.Subscription
import ed.unicoach.error.FieldError

/** Outcome of [SubscriptionService.verify]. */
sealed interface VerifyResult {
  /**
   * The row now reflects Apple's authoritative state. Non-entitling statuses
   * (expired/revoked/…) are still Verified — recording Apple truth is this
   * flow's job; entitlement is the gate's.
   */
  data class Verified(
    val subscription: Subscription,
  ) : VerifyResult

  /** Blank, over 16 KiB, undecodable JWS, or a transactionId claim not matching `^[0-9]{1,32}$` (it is embedded in the API path). */
  data class ValidationFailure(
    val fieldErrors: List<FieldError>,
  ) : VerifyResult

  /** Apple knows no such transaction in this environment (e.g. a sandbox receipt against the production API). */
  data object UnknownTransaction : VerifyResult

  /** Apple verified it, but no plan is configured for [productId] — config drift, not a client error. */
  data class UnknownProduct(
    val productId: String,
  ) : VerifyResult

  /** Another student verified this `originalTransactionId` first; the binding never moves. */
  data object OwnedByOtherAccount : VerifyResult

  /**
   * Apple unreachable / erroring / credentials unconfigured — [reason] names
   * which, carried through from the lookup. Server-side diagnosis only: the
   * route answers a fixed 503 body and never puts [reason] on the wire.
   */
  data class AppStoreUnavailable(
    val reason: String,
  ) : VerifyResult
}
