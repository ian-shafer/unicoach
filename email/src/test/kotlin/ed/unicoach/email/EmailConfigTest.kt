package ed.unicoach.email

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EmailConfigTest {
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
    assertEquals("noreply@uni.coach", emailConfig.defaultFrom)
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
