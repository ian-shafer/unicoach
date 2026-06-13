package ed.unicoach.common.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import java.io.File

object AppConfig {
  /**
   * Loads and merges multiple HOCON configuration resources with right-to-left precedence.
   *
   * Resources provided later in the arguments array will override identical keys defined
   * in earlier resources. For example: `load("base.conf", "override.conf")` will cause
   * values in `override.conf` to take precedence over `base.conf`.
   *
   * On top of the classpath merge, an optional out-of-source-control local overlay file is
   * folded at highest precedence (above every classpath resource, below JVM system properties).
   * The overlay is a single HOCON file at `<base>/unicoach/local.conf`, where `<base>` is the
   * first of: the `unicoach.config.dir` system property (when set and non-blank), the
   * `XDG_CONFIG_HOME` environment variable (when set and non-blank), or `${user.home}/.config`.
   * An absent overlay is a non-fatal no-op; a present-but-malformed overlay surfaces as a
   * [Result.failure] carrying the underlying typesafe `ConfigException`. The overlay is a
   * sanctioned on-host home for local secrets and key overrides; its path and contents are
   * never logged.
   *
   * @param resources The classpath layer filenames (e.g., "common.conf") to parse.
   * @return A [Result] encapsulating the merged [Config], or the failure if parsing fails.
   */
  fun load(vararg resources: String): Result<Config> =
    runCatching {
      var mergedConfig = ConfigFactory.empty()

      for (i in resources.indices.reversed()) {
        mergedConfig = mergedConfig.withFallback(ConfigFactory.parseResourcesAnySyntax(resources[i]))
      }

      val overlay = ConfigFactory.parseFile(overlayFile())

      ConfigFactory.load(overlay.withFallback(mergedConfig))
    }

  /**
   * Resolves the local overlay file path `<base>/unicoach/local.conf`. Performs no IO beyond
   * constructing the path. `<base>` is the first non-blank of the `unicoach.config.dir` system
   * property, the `XDG_CONFIG_HOME` environment variable, or `${user.home}/.config`.
   */
  private fun overlayFile(): File {
    val base =
      System.getProperty("unicoach.config.dir")?.takeIf { it.isNotBlank() }
        ?: System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".config").path

    return File(File(base, "unicoach"), "local.conf")
  }
}

/**
 * Ensures strict runtime evaluation of HOCON structural config bindings natively
 * guaranteeing strings are present and refuse empty/blank permutations cleanly.
 */
fun Config.getNonBlankString(path: String): String = this.getString(path).also { require(it.isNotBlank()) { "[$path] cannot be blank" } }
