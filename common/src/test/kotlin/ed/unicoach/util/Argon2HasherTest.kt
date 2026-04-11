package ed.unicoach.util

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class Argon2HasherTest {
    @Test
    fun `test hashing and verifying`() = runBlocking {
        val password = "SuperSecretPassword123!"
        val hash = Argon2Hasher.hash(password)
        
        assertTrue(hash.startsWith("\$argon2id\$"))
        assertTrue(Argon2Hasher.verify(hash, password))
        assertFalse(Argon2Hasher.verify(hash, "wrongpassword"))
    }
}
