package ed.unicoach.auth

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordResetConfigTest {
  @Test
  fun `from reads tokenTtl and resetUrlBase, and strips a trailing slash`() {
    val config =
      ConfigFactory.parseString(
        """
        passwordReset.tokenTtl = "1 hour"
        passwordReset.resetUrlBase = "https://example.test/reset-password/"
        """.trimIndent(),
      )

    val parsed = PasswordResetConfig.from(config).getOrThrow()

    assertEquals(Duration.ofHours(1), parsed.tokenTtl)
    assertEquals("https://example.test/reset-password", parsed.resetUrlBase)
  }

  @Test
  fun `from fails when a key is missing`() {
    val config = ConfigFactory.parseString("""passwordReset.tokenTtl = "1 hour"""")

    assertTrue(PasswordResetConfig.from(config).isFailure, "Missing resetUrlBase must yield Result.failure")
  }
}
