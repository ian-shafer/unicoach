package ed.unicoach.auth

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailVerificationConfigTest {
  @Test
  fun `from reads tokenTtl and verifyUrlBase`() {
    val config =
      ConfigFactory.parseString(
        """
        emailVerification.tokenTtl = "24 hours"
        emailVerification.verifyUrlBase = "https://example.test/verify"
        """.trimIndent(),
      )

    val result = EmailVerificationConfig.from(config)
    assertTrue(result.isSuccess)
    val parsed = result.getOrThrow()
    assertEquals(Duration.ofHours(24), parsed.tokenTtl)
    assertEquals("https://example.test/verify", parsed.verifyUrlBase)
  }

  @Test
  fun `from fails when a key is missing`() {
    val config =
      ConfigFactory.parseString(
        """
        emailVerification.tokenTtl = "1 hour"
        """.trimIndent(),
      )

    val result = EmailVerificationConfig.from(config)
    assertTrue(result.isFailure, "Missing verifyUrlBase must yield Result.failure")
  }
}
