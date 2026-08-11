package ed.unicoach.auth

import com.auth0.jwk.JwkProviderBuilder
import ed.unicoach.db.models.AuthProvider
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Selector mapping [SsoProviderConfig.provider] to exactly one
 * [IdTokenVerifier], mirroring ChatProviderFactory. An unknown selector is a
 * failure, never a silent fallback. Each entry point returns the verifier
 * already tagged with the provider it may verify ([GoogleIdTokenVerifier] /
 * [AppleIdTokenVerifier]), so the binding survives every hop to
 * [AuthService]'s constructor. Missing required configuration (empty
 * `clientIds` under a real provider) fails fast here, matching the
 * chat-provider boot contract.
 */
object IdTokenVerifierFactory {
  // JwkProviderBuilder's own constructor defaults, pinned explicitly rather
  // than adopted invisibly via `cached(true)` / `rateLimited(true)` (verified
  // against jwks-rsa 0.24.1). Behaviour is unchanged; the numbers governing
  // both providers' key rotation are now visible and reviewable here instead
  // of living only in a third-party constructor. The cache holds one entry per
  // signing key a provider currently publishes; the bucket caps cache-miss
  // fetches, which is what an unseen `kid` after a rotation costs.
  private const val JWKS_CACHED_KEYS = 5L
  private val JWKS_CACHE_TTL: Duration = Duration.ofHours(10)
  private const val JWKS_FETCH_BUCKET_SIZE = 10L
  private const val JWKS_FETCH_REFILL_PER_MINUTE = 1L

  fun googleFromConfig(config: SsoProviderConfig): Result<GoogleIdTokenVerifier> =
    verifierFor(config, AuthProvider.GOOGLE).map(::GoogleIdTokenVerifier)

  fun appleFromConfig(config: SsoProviderConfig): Result<AppleIdTokenVerifier> =
    verifierFor(config, AuthProvider.APPLE).map(::AppleIdTokenVerifier)

  private fun verifierFor(
    config: SsoProviderConfig,
    provider: AuthProvider,
  ): Result<IdTokenVerifier> =
    when (config.provider) {
      StubIdTokenVerifier.PROVIDER_ID -> Result.success(StubIdTokenVerifier())
      provider.wire -> jwksVerifier(config, provider)
      else -> Result.failure(createUnknownProviderError(config, provider))
    }

  private fun createUnknownProviderError(
    config: SsoProviderConfig,
    provider: AuthProvider,
  ) = IllegalArgumentException(
    "unknown auth.${provider.wire}.provider [${config.provider}] — expected " +
      "[${provider.wire}] or [${StubIdTokenVerifier.PROVIDER_ID}]",
  )

  private fun jwksVerifier(
    config: SsoProviderConfig,
    provider: AuthProvider,
  ): Result<IdTokenVerifier> {
    if (config.clientIds.isEmpty()) {
      // The env-var hint is reconstructed from the provider, not read from the
      // config: service.conf names every SSO override <PROVIDER>_<KEY>
      // (GOOGLE_CLIENT_IDS, APPLE_CLIENT_IDS), and Typesafe Config does not
      // expose the substitution name a value came from. Renaming those keys in
      // service.conf without touching this line makes the hint wrong.
      return Result.failure(
        IllegalArgumentException(
          "auth.${provider.wire}.provider [${provider.wire}] requires at least one " +
            "[auth.${provider.wire}.clientIds] (${provider.wire.uppercase()}_CLIENT_IDS)",
        ),
      )
    }
    return runCatching {
      val jwkProvider =
        JwkProviderBuilder(URI(config.jwksUri).toURL())
          .cached(JWKS_CACHED_KEYS, JWKS_CACHE_TTL)
          .rateLimited(JWKS_FETCH_BUCKET_SIZE, JWKS_FETCH_REFILL_PER_MINUTE, TimeUnit.MINUTES)
          .timeouts(config.connectTimeout.toMillis().toInt(), config.readTimeout.toMillis().toInt())
          .build()
      JwksIdTokenVerifier(
        jwkProvider = jwkProvider,
        issuers = config.issuers,
        clientIds = config.clientIds,
        clockSkew = config.clockSkew,
      )
    }
  }
}
