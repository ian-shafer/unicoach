package ed.unicoach.appstore

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [AppleJws]'s decode-only contract (RFC 110): a structurally real JWS's
 * payload decodes to its JSON object, and each malformed shape is a failure —
 * never a signature check, by design.
 */
class AppleJwsTest {
  private val appleJws = AppleJws()

  @Test
  fun `decodes a fixture JWS payload`() {
    val jws = AppStoreTestFixtures.signedTransaction()

    val payload = appleJws.payload(jws).getOrThrow()

    assertEquals(AppStoreTestFixtures.PRODUCT_ID, payload["productId"]!!.jsonPrimitive.content)
    assertEquals(AppStoreTestFixtures.ORIGINAL_TRANSACTION_ID, payload["originalTransactionId"]!!.jsonPrimitive.content)
  }

  @Test
  fun `a two-segment input fails`() {
    assertTrue(appleJws.payload("abc.def").isFailure)
  }

  @Test
  fun `bad base64url in the payload segment fails`() {
    assertTrue(appleJws.payload("abc.###.ghi").isFailure)
  }

  @Test
  fun `a non-JSON payload fails`() {
    val notJson = Base64.getUrlEncoder().withoutPadding().encodeToString("hello".toByteArray())
    assertTrue(appleJws.payload("abc.$notJson.ghi").isFailure)
  }
}
