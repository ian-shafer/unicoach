package ed.unicoach.appstore

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.time.Clock

/**
 * Builds the production [AppStoreServerApi], mirroring `ChatProviderFactory` and
 * `EmailProviderFactory`: the `HttpClient(CIO)` + [HttpTimeout] construction and
 * the token wiring live beside the seam they serve, so the composition root
 * names the App Store client once instead of re-deriving how one is built.
 *
 * No `Result` wrapper, unlike the sibling factories: those reject an unknown
 * provider selector, whereas nothing here can be misconfigured at this point —
 * [AppStoreConfig.from] has already rejected a partial credential, and absent
 * credentials are a valid unconfigured box whose lookups answer
 * [AppStoreSubscriptionLookup.Unavailable].
 */
object AppStoreServerApiFactory {
  /**
   * The api and the [HttpClient] backing it. The client is handed back
   * explicitly rather than buried in the api because its lifetime belongs to the
   * caller: the composition root closes it when the application stops.
   */
  class Wiring(
    val api: AppStoreServerApi,
    val client: HttpClient,
  )

  fun fromConfig(config: AppStoreConfig): Wiring {
    val client =
      HttpClient(CIO) {
        install(HttpTimeout) {
          // Ktor's timeout plugin takes raw millis: unwrap the Duration here, at
          // the client boundary, and nowhere earlier.
          connectTimeoutMillis = config.connectTimeout.toMillis()
          requestTimeoutMillis = config.requestTimeout.toMillis()
        }
      }
    val api =
      AppStoreServerApi(
        KtorAppStoreTransport(client, config),
        config.credentials?.let { AppStoreAuthTokens(it, config.bundleId, Clock.systemUTC()) },
      )
    return Wiring(api, client)
  }
}
