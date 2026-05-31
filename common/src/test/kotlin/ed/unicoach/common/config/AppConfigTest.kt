package ed.unicoach.common.config

import com.typesafe.config.ConfigFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppConfigTest {
  @Test
  fun `load merges resources right-to-left so the right-most resource overrides earlier keys`() {
    val config = AppConfig.load("merge-base.conf", "merge-override.conf").getOrThrow()

    // `app.name` is defined in both; the right-most resource wins.
    assertEquals("override", config.getString("app.name"))
    // `app.region` is only in the left-most resource and survives the merge.
    assertEquals("base-region", config.getString("app.region"))
  }

  @Test
  fun `load resolves optional substitutions so external overrides win over in-file defaults`() {
    System.setProperty("APP_CONFIG_TEST_OVERRIDE", "from-environment")
    ConfigFactory.invalidateCaches()

    val config = AppConfig.load("env-substitution.conf").getOrThrow()

    // `value = ${?APP_CONFIG_TEST_OVERRIDE}` overrides the literal default once the
    // substitution source is present at load time.
    assertEquals("from-environment", config.getString("app.value"))
  }

  @AfterTest
  fun clearOverride() {
    System.clearProperty("APP_CONFIG_TEST_OVERRIDE")
    ConfigFactory.invalidateCaches()
  }
}
