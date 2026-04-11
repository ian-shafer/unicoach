package ed.unicoach.util

import de.mkammerer.argon2.Argon2Factory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class Argon2Hasher(
  private val iterations: Int = 3,
  private val memory: Int = 65536,
  private val parallelism: Int = 1,
  private val timeoutMs: Long = 2000L,
) {
  private val argon2 =
    Argon2Factory.create(
      Argon2Factory.Argon2Types.ARGON2id,
      16, // salt length
      32, // hash length
    )

  suspend fun hash(password: String): String =
    withContext(Dispatchers.IO) {
      withTimeout(timeoutMs) {
        val passwordChars = password.toCharArray()
        try {
          argon2.hash(iterations, memory, parallelism, passwordChars)
        } finally {
          argon2.wipeArray(passwordChars)
        }
      }
    }

  suspend fun verify(
    hash: String,
    password: String,
  ): Boolean =
    withContext(Dispatchers.IO) {
      withTimeout(timeoutMs) {
        val passwordChars = password.toCharArray()
        try {
          argon2.verify(hash, passwordChars)
        } finally {
          argon2.wipeArray(passwordChars)
        }
      }
    }
}
