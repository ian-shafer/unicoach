package ed.unicoach.rest.models

import ed.unicoach.subscriptions.SubscriptionService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces the openapi.yaml <-> SubscriptionService.MAX_JWS coupling (RFC 110):
 * the published signedTransaction.maxLength must equal the bound the service
 * actually enforces, or the contract silently understates or overstates what
 * the server accepts. Nothing else in `bin/test` opens openapi.yaml, so without
 * this guard a bump of either side leaves the other quietly stale.
 *
 * `maxLength` counts characters and [SubscriptionService.MAX_JWS] counts bytes;
 * they are the same number because a JWS is ASCII base64url, which is exactly
 * why the service compares a `length` against that size.
 */
class OpenApiSubscriptionVerifyLimitTest {
  @Test
  fun `openapi's signedTransaction maxLength matches SubscriptionService's MAX_JWS`() {
    // Addressed by schema path rather than by searching the file for the
    // number: a bound that happens to match some unrelated property elsewhere
    // in the spec must not let a stale signedTransaction bound pass.
    val published = OpenApiSpec.get("SubscriptionVerifyRequest", "signedTransaction").path("maxLength")
    assertTrue(
      published.isInt,
      "openapi.yaml's SubscriptionVerifyRequest.signedTransaction has no integer maxLength; found [$published]",
    )
    assertEquals(
      SubscriptionService.MAX_JWS.bytes,
      published.longValue(),
      "openapi.yaml's SubscriptionVerifyRequest.signedTransaction.maxLength must equal SubscriptionService.MAX_JWS",
    )
  }
}
