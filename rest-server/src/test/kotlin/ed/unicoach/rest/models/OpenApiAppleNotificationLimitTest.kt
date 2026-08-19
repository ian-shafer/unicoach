package ed.unicoach.rest.models

import ed.unicoach.appstore.AppleNotificationVerifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces the openapi.yaml <-> [AppleNotificationVerifier.MAX_JWS] coupling
 * (RFC 112), the sibling of [OpenApiSubscriptionVerifyLimitTest]: the published
 * signedPayload.maxLength must equal the bound the verifier actually enforces,
 * or the contract silently understates or overstates what the server accepts.
 * Nothing else in `bin/test` opens openapi.yaml, so without this guard a bump of
 * either side leaves the other quietly stale.
 *
 * `maxLength` counts characters and [AppleNotificationVerifier.MAX_JWS] counts
 * bytes; they are the same number because a JWS is ASCII base64url, which is
 * exactly why the verifier compares a `length` against that size.
 */
class OpenApiAppleNotificationLimitTest {
  @Test
  fun `openapi's signedPayload maxLength matches AppleNotificationVerifier's MAX_JWS`() {
    val published = OpenApiSpec.get("AppleNotificationRequest", "signedPayload").path("maxLength")
    assertTrue(
      published.isInt,
      "openapi.yaml's AppleNotificationRequest.signedPayload has no integer maxLength; found [$published]",
    )
    assertEquals(
      AppleNotificationVerifier.MAX_JWS.bytes,
      published.longValue(),
      "openapi.yaml's AppleNotificationRequest.signedPayload.maxLength must equal AppleNotificationVerifier.MAX_JWS",
    )
  }
}
