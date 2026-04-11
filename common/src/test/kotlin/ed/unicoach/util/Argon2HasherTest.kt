package ed.unicoach.util

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Argon2HasherTest {
  @Test
  fun `test hashing and verifying`() =
    runBlocking {
      val hasher = Argon2Hasher()
      val password = "SuperSecretPassword123!"
      val hash = hasher.hash(password)

      assertTrue(hash.startsWith("\$argon2id\$"))
      assertTrue(hasher.verify(hash, password))
      assertFalse(hasher.verify(hash, "wrongpassword"))
    }
}
