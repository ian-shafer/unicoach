/*
 * The one place rest-server suites build their App Store wiring (RFC 110).
 * Suites that are not ABOUT subscriptions wire [offlineAppStoreServerApi] — an
 * unconfigured client whose transport fails loudly if anything ever calls it —
 * so appModule's two new parameters stay a mechanical ripple.
 */
package ed.unicoach.rest

import com.typesafe.config.Config
import ed.unicoach.appstore.AppStoreServerApi
import ed.unicoach.appstore.AppStoreTestFixtures
import ed.unicoach.appstore.AppleJwsVerifier
import ed.unicoach.appstore.AppleNotificationVerifier
import ed.unicoach.subscriptions.SubscriptionPlans
import java.security.cert.X509Certificate

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

/**
 * A minted root nothing in the suite holds the signing key for — the anchor a
 * suite with no interest in notifications gets, so a notification it never meant
 * to send is refused rather than silently accepted.
 */
private val unreachableTrustAnchor: X509Certificate by lazy { AppStoreTestFixtures.certificateChain().root }

/**
 * The suites' one way to build the notifications endpoint's verifier (RFC 112),
 * the third part of the mechanical `appModule` ripple. [trustAnchor] is the
 * locally minted root a suite signs its own fixtures under.
 *
 * Never the real Apple root: no test can produce a certificate Apple issued, so
 * pinning it would make every fixture unverifiable for the wrong reason. What
 * that costs is stated in RFC 112 — the marker OID is asserted only against
 * certificates this repo generated, and one real sandbox notification must
 * verify end to end before the production URL is entered in App Store Connect.
 */
fun testAppleNotificationVerifier(trustAnchor: X509Certificate = unreachableTrustAnchor): AppleNotificationVerifier =
  AppleNotificationVerifier(
    AppleJwsVerifier(setOf(trustAnchor)),
    AppStoreTestFixtures.appStoreConfig(),
  )
