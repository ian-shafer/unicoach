package ed.unicoach.common.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object AppConfig {
  /**
   * Loads and merges multiple HOCON configuration resources with right-to-left precedence.
   *
   * Resources provided later in the arguments array will override identical keys defined
   * in earlier resources. For example: `load("base.conf", "override.conf")` will cause
   * values in `override.conf` to take precedence over `base.conf`.
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

      ConfigFactory.load(mergedConfig)
    }
}

/**
 * Ensures strict runtime evaluation of HOCON structural config bindings natively
 * guaranteeing strings are present and refuse empty/blank permutations cleanly.
 */
fun Config.getNonBlankString(path: String): String = this.getString(path).also { require(it.isNotBlank()) { "[$path] cannot be blank" } }
