package ed.unicoach.auth

import com.auth0.jwk.JwkProviderBuilder
import java.net.URI

/**
 * Selector mapping [GoogleAuthConfig.provider] to exactly one [GoogleTokenVerifier],
 * mirroring ChatProviderFactory. An unknown selector is a failure, never a silent
 * fallback. The `google` branch fails fast on missing required configuration
 * (empty `clientIds`), matching the chat-provider boot contract.
 */
object GoogleTokenVerifierFactory {
  private const val PROVIDER_GOOGLE = "google"

  fun fromConfig(config: GoogleAuthConfig): Result<GoogleTokenVerifier> =
    when (config.provider) {
      StubGoogleTokenVerifier.PROVIDER_ID -> Result.success(StubGoogleTokenVerifier())
      PROVIDER_GOOGLE -> jwksVerifier(config)
      else -> Result.failure(IllegalArgumentException("unknown auth.google.provider [${config.provider}]"))
    }

  private fun jwksVerifier(config: GoogleAuthConfig): Result<GoogleTokenVerifier> {
    if (config.clientIds.isEmpty()) {
      return Result.failure(
        IllegalArgumentException(
          "auth.google.provider [google] requires at least one [auth.google.clientIds] (GOOGLE_CLIENT_IDS)",
        ),
      )
    }
    return runCatching {
      val jwkProvider =
        JwkProviderBuilder(URI(config.jwksUri).toURL())
          .cached(true)
          .rateLimited(true)
          .timeouts(config.connectTimeout.toMillis().toInt(), config.readTimeout.toMillis().toInt())
          .build()
      JwksGoogleTokenVerifier(
        jwkProvider = jwkProvider,
        issuers = config.issuers,
        clientIds = config.clientIds,
        clockSkew = config.clockSkew,
      )
    }
  }
}
