package ed.unicoach.util

import de.mkammerer.argon2.Argon2Factory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object Argon2Hasher {
    private const val ITERATIONS = 3
    private const val MEMORY = 65536
    private const val PARALLELISM = 1
    private const val TIMEOUT_MS = 2000L

    private val argon2 = Argon2Factory.create(
        Argon2Factory.Argon2Types.ARGON2id,
        16, // salt length
        32  // hash length
    )

    suspend fun hash(password: String): String = withContext(Dispatchers.IO) {
        withTimeout(TIMEOUT_MS) {
            val passwordChars = password.toCharArray()
            try {
                argon2.hash(ITERATIONS, MEMORY, PARALLELISM, passwordChars)
            } finally {
                argon2.wipeArray(passwordChars)
            }
        }
    }

    suspend fun verify(hash: String, password: String): Boolean = withContext(Dispatchers.IO) {
        withTimeout(TIMEOUT_MS) {
            val passwordChars = password.toCharArray()
            try {
                argon2.verify(hash, passwordChars)
            } finally {
                argon2.wipeArray(passwordChars)
            }
        }
    }
}
