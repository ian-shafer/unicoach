package ed.unicoach.rest.config

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.appstore.AppleNotificationVerifier
import ed.unicoach.rest.routing.AppleNotificationRouteHandler
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enforces the rest-server.conf <-> [AppleNotificationVerifier.MAX_JWS] coupling
 * (RFC 112), the sibling of [RequestSizeSubscriptionVerifyLimitTest].
 *
 * The cap must EXCEED the bound, and that headroom is the whole point: a body
 * between the two reaches the service and is refused as unauthenticated with a
 * legible reason, rather than being shadowed by a bare 413 that says nothing
 * about why. Were the cap to fall to or below the bound, the verifier's own
 * refusal would become unreachable and nothing else would notice.
 *
 * The client-key allowlist entry is asserted here too, from the same shipped
 * conf: the endpoint's two config-side preconditions are one decision, and Apple
 * sends no client key, so without the entry the route answers 403 in every
 * environment where `UNICOACH_CLIENT_KEYS` is set.
 */
class RequestSizeAppleNotificationLimitTest {
  /**
   * Resolved offline, like [RequestSizeSubscriptionVerifyLimitTest]: the guard is
   * about the caps rest-server *ships*, and a live resolve would fold in the
   * `bin/test` environment and any developer's local overlay.
   */
  private val shippedConfig =
    ConfigFactory
      .parseString(
        """
        SERVER_PORT = 0
        APP_DOMAIN = x
        SESSION_COOKIE_SECURE = false
        """.trimIndent(),
      ).withFallback(ConfigFactory.parseResourcesAnySyntax("rest-server.conf"))
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  @Test
  fun `the notifications route's size cap exceeds AppleNotificationVerifier's JWS bound`() {
    val routeOverrides = RequestSizeConfig.from(shippedConfig).getOrThrow().routeOverrides
    val cap =
      requireNotNull(routeOverrides[AppleNotificationRouteHandler.PATH]) {
        "rest-server.conf has no server.requestSize.routeOverrides entry for " +
          "[${AppleNotificationRouteHandler.PATH}]; found ${routeOverrides.keys}"
      }

    assertTrue(
      cap.bytes > AppleNotificationVerifier.MAX_JWS.bytes,
      "the [${AppleNotificationRouteHandler.PATH}] request-size cap [${cap.bytes}] must exceed " +
        "AppleNotificationVerifier.MAX_JWS [${AppleNotificationVerifier.MAX_JWS.bytes}], or the service's " +
        "legible refusal is shadowed by a bare 413",
    )
  }

  @Test
  fun `the notifications route is exempt from the client-key gate`() {
    val allowlist = ClientKeyGateConfig.from(shippedConfig).getOrThrow().allowlistPaths

    assertTrue(
      AppleNotificationRouteHandler.PATH in allowlist,
      "Apple sends no client key, so [${AppleNotificationRouteHandler.PATH}] must be allowlisted; found $allowlist",
    )
  }
}
