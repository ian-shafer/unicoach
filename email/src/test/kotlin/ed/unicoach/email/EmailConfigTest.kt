package ed.unicoach.email

import com.typesafe.config.ConfigFactory
import ed.unicoach.common.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class EmailConfigTest {
  @Test
  fun `from reads email defaultFrom verbatim`() {
    val config = ConfigFactory.parseString("email.defaultFrom = \"x@y.io\"")
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("x@y.io", emailConfig.defaultFrom)
  }

  @Test
  fun `email conf is on the classpath and merges with the packaged default`() {
    val config = AppConfig.load("common.conf", "db.conf", "email.conf").getOrThrow()
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("noreply@unicoach.app", emailConfig.defaultFrom)
  }

  @Test
  fun `from does not validate the address`() {
    val config = ConfigFactory.parseString("email.defaultFrom = \"not-an-email\"")
    val emailConfig = EmailConfig.from(config).getOrThrow()
    assertEquals("not-an-email", emailConfig.defaultFrom)
  }
}
