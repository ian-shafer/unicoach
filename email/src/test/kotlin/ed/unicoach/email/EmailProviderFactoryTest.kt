package ed.unicoach.email

import com.typesafe.config.ConfigFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailProviderFactoryTest {
  @Test
  fun `provider log yields a LogOnlyEmailProvider`() {
    val provider = EmailProviderFactory.fromConfig(emailConfig("log")).getOrThrow()

    assertTrue(provider is LogOnlyEmailProvider)
    assertEquals("log", provider.id)
  }

  @Test
  fun `provider ses yields a SesEmailProvider with the default credential chain`() {
    val provider = EmailProviderFactory.fromConfig(emailConfig("ses")).getOrThrow()

    assertTrue(provider is SesEmailProvider)
    assertEquals("ses", provider.id)
    // Releases the real SesV2Client constructed offline with the default region.
    provider.close()
  }

  @Test
  fun `provider ses with only one static credential key falls back to the default chain without failing`() {
    val config =
      EmailConfig
        .from(
          ConfigFactory.parseString(
            """
            email.defaultFrom = "noreply@unicoach.app"
            email.provider = "ses"
            email.ses.region = "us-east-1"
            email.ses.accessKeyId = "AKIA-only"
            """.trimIndent(),
          ),
        ).getOrThrow()

    val provider = EmailProviderFactory.fromConfig(config).getOrThrow()

    assertTrue(provider is SesEmailProvider)
    provider.close()
  }

  @Test
  fun `an unknown provider yields a failure`() {
    val result = EmailProviderFactory.fromConfig(emailConfig("smtp"))

    assertTrue(result.isFailure)
  }

  private fun emailConfig(provider: String): EmailConfig =
    EmailConfig
      .from(
        ConfigFactory.parseString(
          """
          email.defaultFrom = "noreply@unicoach.app"
          email.provider = "$provider"
          email.ses.region = "us-east-1"
          """.trimIndent(),
        ),
      ).getOrThrow()
}
