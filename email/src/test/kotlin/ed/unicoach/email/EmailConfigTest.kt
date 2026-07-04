package ed.unicoach.email

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.common.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EmailConfigTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `required EMAIL_PROVIDER fails to resolve when unset`() {
    // provider = ${EMAIL_PROVIDER} is a required substitution (no .conf default):
    // resolving the packaged conf offline, with no EMAIL_PROVIDER supplied, must
    // fail rather than silently routing prod email to the `log` sink (RFC 95).
    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      ConfigFactory.parseResources("email.conf").resolve(offlineOptions)
    }
  }

  @Test
  fun `EMAIL_PROVIDER resolves to the set value`() {
    val config =
      ConfigFactory
        .parseString("EMAIL_PROVIDER = ses")
        .withFallback(ConfigFactory.parseResources("email.conf"))
        .resolve(offlineOptions)

    assertEquals("ses", EmailConfig.from(config).getOrThrow().provider)
  }

  @Test
  fun `from reads email defaultFrom verbatim`() {
    val config =
      ConfigFactory.parseString(
        """
        email.defaultFrom = "x@y.io"
        email.provider = "log"
        email.ses.region = "us-east-1"
        """.trimIndent(),
      )
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("x@y.io", emailConfig.defaultFrom)
  }

  @Test
  fun `email conf is on the classpath and merges with the packaged default`() {
    val config = AppConfig.load("common.conf", "db.conf", "email.conf").getOrThrow()
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("noreply@localhost", emailConfig.defaultFrom)
    assertEquals("log", emailConfig.provider)
    assertEquals("us-east-1", emailConfig.ses.region)
    assertNull(emailConfig.ses.accessKeyId)
    assertNull(emailConfig.ses.secretAccessKey)
  }

  @Test
  fun `from does not validate the address`() {
    val config =
      ConfigFactory.parseString(
        """
        email.defaultFrom = "not-an-email"
        email.provider = "log"
        email.ses.region = "us-east-1"
        """.trimIndent(),
      )
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("not-an-email", emailConfig.defaultFrom)
  }

  @Test
  fun `from reads provider and ses region from a parsed config`() {
    val config =
      ConfigFactory.parseString(
        """
        email.defaultFrom = "x@y.io"
        email.provider = "ses"
        email.ses.region = "eu-west-1"
        """.trimIndent(),
      )
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("ses", emailConfig.provider)
    assertEquals("eu-west-1", emailConfig.ses.region)
  }

  @Test
  fun `static credentials present in a parsed config surface on SesConfig`() {
    val config =
      ConfigFactory.parseString(
        """
        email.defaultFrom = "x@y.io"
        email.provider = "ses"
        email.ses.region = "us-east-1"
        email.ses.accessKeyId = "AKIA-test"
        email.ses.secretAccessKey = "secret-test"
        """.trimIndent(),
      )
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("AKIA-test", emailConfig.ses.accessKeyId)
    assertEquals("secret-test", emailConfig.ses.secretAccessKey)
  }

  @Test
  fun `absent static credentials surface as null`() {
    val config =
      ConfigFactory.parseString(
        """
        email.defaultFrom = "x@y.io"
        email.provider = "ses"
        email.ses.region = "us-east-1"
        """.trimIndent(),
      )
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertNull(emailConfig.ses.accessKeyId)
    assertNull(emailConfig.ses.secretAccessKey)
  }
}
