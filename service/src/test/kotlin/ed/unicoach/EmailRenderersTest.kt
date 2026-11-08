package ed.unicoach

import com.typesafe.config.ConfigFactory
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.PasswordResetConfig
import ed.unicoach.email.EmailTemplate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EmailRenderersTest {
  @Test
  fun `every email template has exactly one renderer`() {
    val config =
      ConfigFactory.parseString(
        """
        emailVerification.tokenTtl = "1 hour"
        emailVerification.verifyUrlBase = "https://example.test/verify"
        passwordReset.tokenTtl = "1 hour"
        passwordReset.resetUrlBase = "https://example.test/reset"
        """.trimIndent(),
      )

    val templates =
      emailRenderers(
        EmailVerificationConfig.from(config).getOrThrow(),
        PasswordResetConfig.from(config).getOrThrow(),
      ).map { it.template }

    assertEquals(EmailTemplate.entries.sorted(), templates.sorted())
  }
}
