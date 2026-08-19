package ed.unicoach.appstore

import com.typesafe.config.Config
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64

enum class AppStoreEnvironment {
  SANDBOX,
  PRODUCTION,
  ;

  companion object {
    /**
     * The one place the two environment names are spelled. Case-insensitive
     * because the same two values arrive in two casings — our own config's
     * lowercase `sandbox`/`production` and Apple's wire `Sandbox`/`Production`.
     * Null for anything else, so each caller can name its own source in the
     * error it raises.
     */
    fun parse(value: String): AppStoreEnvironment? =
      when (value.lowercase()) {
        "sandbox" -> SANDBOX
        "production" -> PRODUCTION
        else -> null
      }
  }
}

/** The three credential fields as a unit: all present and key-parseable, or none. */
class AppStoreCredentials(
  val issuerId: String,
  val keyId: String,
  // Parsed at load: base64 → PKCS8EncodedKeySpec → KeyFactory("EC").
  val privateKey: ECPrivateKey,
)

/**
 * Typed reader for the `appStore` block of appstore.conf (RFC 110), the third
 * instance of the provider-config shape (`AnthropicConfig`/`SesConfig`). The
 * environment is a required substitution; the credential trio is optional as a
 * UNIT — [credentials] is null when all three are absent (a valid, unconfigured
 * box whose verify calls answer 503), while a proper subset present is a
 * misconfiguration that fails boot naming the missing keys.
 */
class AppStoreConfig private constructor(
  val environment: AppStoreEnvironment,
  val credentials: AppStoreCredentials?,
  val bundleId: String,
  val baseUrl: String,
  val connectTimeout: Duration,
  val requestTimeout: Duration,
) {
  companion object {
    private const val ENVIRONMENT_KEY = "appStore.environment"
    private const val ISSUER_ID_KEY = "appStore.issuerId"
    private const val KEY_ID_KEY = "appStore.keyId"
    private const val PRIVATE_KEY_KEY = "appStore.privateKey"
    private const val BASE_URL_KEY = "appStore.baseUrl"

    private const val PRODUCTION_BASE_URL = "https://api.storekit.itunes.apple.com"
    private const val SANDBOX_BASE_URL = "https://api.storekit-sandbox.itunes.apple.com"

    /**
     * Failure on: unknown environment; a proper subset of the three credential
     * keys present (a partial credential is a misconfiguration, not an
     * unconfigured box); an un-decodable/non-EC private key. Each failure names
     * the offending key.
     */
    fun from(config: Config): Result<AppStoreConfig> =
      runCatching {
        val environment = parseEnvironment(config.getString(ENVIRONMENT_KEY))
        AppStoreConfig(
          environment = environment,
          credentials = parseCredentials(config),
          bundleId = config.getString("appStore.bundleId"),
          baseUrl = resolveBaseUrl(config, environment),
          connectTimeout = config.getDuration("appStore.connectTimeout"),
          requestTimeout = config.getDuration("appStore.requestTimeout"),
        )
      }

    /** An explicit `appStore.baseUrl` overrides the environment's default. */
    private fun resolveBaseUrl(
      config: Config,
      environment: AppStoreEnvironment,
    ): String =
      if (config.hasPath(BASE_URL_KEY)) {
        config.getString(BASE_URL_KEY)
      } else {
        defaultBaseUrl(environment)
      }

    private fun defaultBaseUrl(environment: AppStoreEnvironment): String =
      when (environment) {
        AppStoreEnvironment.PRODUCTION -> PRODUCTION_BASE_URL
        AppStoreEnvironment.SANDBOX -> SANDBOX_BASE_URL
      }

    private fun parseEnvironment(value: String): AppStoreEnvironment =
      AppStoreEnvironment.parse(value)
        ?: throw IllegalArgumentException(
          "[$ENVIRONMENT_KEY] = [$value] is not an App Store environment (expected sandbox | production)",
        )

    private fun parseCredentials(config: Config): AppStoreCredentials? {
      val keys = listOf(ISSUER_ID_KEY, KEY_ID_KEY, PRIVATE_KEY_KEY)
      val present = keys.filter { config.hasPath(it) }
      if (present.isEmpty()) return null
      val missing = keys - present.toSet()
      require(missing.isEmpty()) {
        "partial App Store credential: [${present.joinToString(", ")}] set but [${missing.joinToString(", ")}] missing " +
          "(set all three, or none)"
      }
      return AppStoreCredentials(
        issuerId = config.getString(ISSUER_ID_KEY),
        keyId = config.getString(KEY_ID_KEY),
        privateKey = parsePrivateKey(config.getString(PRIVATE_KEY_KEY)),
      )
    }

    /** One-line base64 PKCS#8 (PEM header/footer/newlines stripped) → [ECPrivateKey]. */
    private fun parsePrivateKey(base64: String): ECPrivateKey {
      val decoded =
        runCatching { Base64.getDecoder().decode(base64) }
          .getOrElse { throw IllegalArgumentException("[$PRIVATE_KEY_KEY] is not valid base64: [${it.message}]", it) }
      val key =
        runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decoded)) }
          .getOrElse { throw IllegalArgumentException("[$PRIVATE_KEY_KEY] is not a PKCS#8 EC private key: [${it.message}]", it) }
      return key as? ECPrivateKey
        ?: throw IllegalArgumentException("[$PRIVATE_KEY_KEY] parsed but is not an EC key: [${key.algorithm}]")
    }
  }
}
