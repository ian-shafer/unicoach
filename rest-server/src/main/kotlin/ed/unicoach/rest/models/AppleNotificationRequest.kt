package ed.unicoach.rest.models

/**
 * `POST /api/v1/subscriptions/apple-notifications` request body (RFC 112): one
 * App Store Server Notification V2, as Apple posts it.
 *
 * The JWS inside is the request's ENTIRE authentication — there is no session
 * cookie, and nothing else about the request is trusted.
 */
data class AppleNotificationRequest(
  val signedPayload: String,
)
