package ed.unicoach.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class Argon2HasherTest {
  @Test
  fun `hash executes on the injected dispatcher`() =
    runTest {
      val hasher = Argon2Hasher(dispatcher = StandardTestDispatcher(testScheduler))
      val hash = hasher.hash("SuperSecretPassword123!")

      assertTrue(hash.startsWith("\$argon2id\$"))
    }

  @Test
  fun `verify executes on the injected dispatcher`() =
    runTest {
      val hasher = Argon2Hasher(dispatcher = StandardTestDispatcher(testScheduler))
      val password = "SuperSecretPassword123!"
      val hash = hasher.hash(password)

      assertTrue(hasher.verify(hash, password))
      assertFalse(hasher.verify(hash, "wrongpassword"))
    }

  @Test
  fun `hash default dispatcher is Dispatchers Crypto`() =
    runTest {
      val hasher = Argon2Hasher()
      val hash = hasher.hash("SuperSecretPassword123!")

      assertTrue(hash.startsWith("\$argon2id\$"))
    }
}
