package ed.unicoach.auth

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.JwtGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AuthServiceTest {
    // Tests domain validation boundary correctly
    @Test
    fun `test validation rejection for weak passwords`() = runBlocking {
        // We can pass a dummy db and jwt config because it should fail validation before hitting them
        val dummyDb = Database(DatabaseConfig("jdbc:h2:mem:test", "user", ""))
        val jwtGenerator = JwtGenerator("secret", "issuer")
        
        val argon2Hasher = Argon2Hasher()
        val service = AuthService(dummyDb, jwtGenerator, argon2Hasher)

        val res1 = service.register("email@test.com", "Name", "short")
        assertTrue(res1 is AuthResult.ValidationFailure)

        val res2 = service.register("email@test.com", "Name", "nouppercasenonumber")
        assertTrue(res2 is AuthResult.ValidationFailure)

        val res3 = service.register("email@test.com", "Name", "UPPERCASENONUMBER")
        assertTrue(res3 is AuthResult.ValidationFailure)
        
        dummyDb.close()
    }
}
