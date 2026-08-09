package ed.unicoach.rest.models

/**
 * `POST /api/v1/subscriptions/verify` request body (RFC 110): the StoreKit 2
 * signed transaction (JWS) as the app received it from `Product.purchase()`.
 * Never trusted — the backend uses it only as a lookup key and fetches the
 * authoritative state from Apple.
 */
data class SubscriptionVerifyRequest(
  val signedTransaction: String,
)
