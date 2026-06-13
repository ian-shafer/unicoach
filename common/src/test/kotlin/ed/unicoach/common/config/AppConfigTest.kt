package ed.unicoach.common.config

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppConfigTest {
  private var configDir: File? = null

  /**
   * Redirects the overlay base directory to a fresh JVM temp dir via the `unicoach.config.dir`
   * system property and writes `<temp>/unicoach/local.conf` with the given contents. Returns the
   * temp base dir. When [contents] is null, no `local.conf` is written (absent-overlay case).
   */
  private fun redirectOverlay(contents: String?): File {
    val base = Files.createTempDirectory("unicoach-config").toFile()
    configDir = base
    System.setProperty("unicoach.config.dir", base.path)
    if (contents != null) {
      val unicoachDir = File(base, "unicoach").also { it.mkdirs() }
      File(unicoachDir, "local.conf").writeText(contents)
    }
    ConfigFactory.invalidateCaches()
    return base
  }

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

  @Test
  fun `overlay overrides a classpath resource key at highest precedence`() {
    redirectOverlay("app.name = \"from-local\"")

    val config = AppConfig.load("merge-base.conf").getOrThrow()

    // The overlay key wins over the classpath `merge-base.conf` (which defines `base`).
    assertEquals("from-local", config.getString("app.name"))
    // An untouched classpath key survives the overlay fold.
    assertEquals("base-region", config.getString("app.region"))
  }

  @Test
  fun `absent overlay file is a non-fatal no-op`() {
    redirectOverlay(null)

    val config = AppConfig.load("merge-base.conf").getOrThrow()

    assertEquals("base", config.getString("app.name"))
  }

  @Test
  fun `malformed overlay surfaces as failure carrying ConfigException`() {
    redirectOverlay("app.name = \"unterminated")

    val result = AppConfig.load("merge-base.conf")

    assertTrue(result.isFailure)
    assertIs<ConfigException>(result.exceptionOrNull())
  }

  @Test
  fun `overlay literal wins over a classpath env substitution`() {
    // `APP_CONFIG_TEST_OVERRIDE` is unset (the @AfterTest clears it), so `env-substitution.conf`
    // would default `app.value` to "default" absent the overlay.
    redirectOverlay("app.value = \"local\"")

    val config = AppConfig.load("env-substitution.conf").getOrThrow()

    assertEquals("local", config.getString("app.value"))
  }

  @Test
  fun `classpath env substitution still resolves when overlay omits the key`() {
    redirectOverlay("app.other = \"unrelated\"")
    System.setProperty("APP_CONFIG_TEST_OVERRIDE", "from-environment")
    ConfigFactory.invalidateCaches()

    val config = AppConfig.load("env-substitution.conf").getOrThrow()

    // The overlay does not set `app.value`, so the substitution resolves normally.
    assertEquals("from-environment", config.getString("app.value"))
  }

  @AfterTest
  fun clearOverride() {
    System.clearProperty("APP_CONFIG_TEST_OVERRIDE")
    System.clearProperty("unicoach.config.dir")
    configDir?.deleteRecursively()
    configDir = null
    ConfigFactory.invalidateCaches()
  }
}
