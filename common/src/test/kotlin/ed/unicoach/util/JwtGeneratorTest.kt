package ed.unicoach.util

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtGeneratorTest {
  @Test
  fun `test token minting`() {
    val fixedClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"))
    val generator = JwtGenerator("mysecret", "http://issuer", fixedClock)

    val token = generator.mint("user-123", mapOf("role" to "admin"))

    assertNotNull(token)
    assertTrue(token.split(".").size == 3)
  }
}
