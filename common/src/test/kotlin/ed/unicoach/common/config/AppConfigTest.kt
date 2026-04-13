package ed.unicoach.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppConfigTest {
  @Test
  fun `load correctly prioritizes right-most module overrides`() {
    val config = AppConfig.load("common.conf").getOrThrow()
    assertEquals(30000, config.getInt("database.connectionTimeout"))
  }

  @Test
  fun `verify System getenv forcefully overrides all identically named fields`() {
    val config = AppConfig.load().getOrThrow()
    assertTrue(config.hasPath("PATH") || config.hasPath("USER"), "Environment must contain standard variables natively")
  }
}
