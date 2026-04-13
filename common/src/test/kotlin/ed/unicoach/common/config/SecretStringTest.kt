package ed.unicoach.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecretStringTest {
  @Test
  fun `toString maps to masked sequence to prevent raw secret exposure`() {
    val secret = SecretString("my-super-secret-value")
    assertEquals("********", secret.toString())
    assertNotEquals("my-super-secret-value", secret.toString())
  }

  @Test
  fun `class correctly implements equals and hashcode`() {
    val secret1 = SecretString("secret")
    val secret2 = SecretString("secret")
    val secret3 = SecretString("other")

    assertEquals(secret1, secret2)
    assertEquals(secret1.hashCode(), secret2.hashCode())
    assertNotEquals(secret1, secret3)
  }
}
