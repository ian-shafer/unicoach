/*
 * The one place rest-server suites build their App Store wiring (RFC 110).
 * Suites that are not ABOUT subscriptions wire [offlineAppStoreServerApi] — an
 * unconfigured client whose transport fails loudly if anything ever calls it —
 * so appModule's two new parameters stay a mechanical ripple.
 */
package ed.unicoach.rest

import com.typesafe.config.Config
import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.subscriptions.SubscriptionPlans

/**
 * An [AppStoreServerApi] with unconfigured credentials (every lookup answers
 * Unavailable before the transport) over a transport that throws — an
 * unexpected Apple call fails the test loudly rather than silently answering.
 */
fun offlineAppStoreServerApi(): AppStoreServerApi =
  AppStoreServerApi(
    { path, _ -> throw IllegalStateException("Unexpected App Store call to [$path] from a suite with no App Store wiring") },
    tokens = null,
  )

/**
 * The suites' one way to load the [SubscriptionPlans] table — the second half
 * of the mechanical ripple [offlineAppStoreServerApi] covers. A malformed
 * plans block is a broken test fixture, so it throws rather than returning a
 * [Result] every suite would unwrap identically.
 */
fun subscriptionPlansFrom(config: Config): SubscriptionPlans = SubscriptionPlans.from(config).getOrThrow()
