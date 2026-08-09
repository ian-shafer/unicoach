package ed.unicoach.rest.config

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.subscriptions.SubscriptionService
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enforces the rest-server.conf <-> SubscriptionService.MAX_JWS coupling
 * (RFC 110), the sibling of [ed.unicoach.rest.models.OpenApiSubscriptionVerifyLimitTest]:
 * HOCON cannot reference a Kotlin constant, so the verify route's request-size
 * cap restates a bound the service owns, and a bump of either side would
 * otherwise drift silently — the route rejecting bodies the service would have
 * accepted, or the derivation comment quietly becoming false.
 *
 * The cap is not equal to the bound: the JWS travels as a JSON string inside a
 * `SubscriptionVerifyRequest` envelope, so the cap must exceed it. That
 * headroom is the assertion — the guard fires the moment the cap stops covering
 * the bound, without pinning either number.
 *
 * `rest-server.conf` is a `:rest-server` resource, so Gradle already treats an
 * edit to it as an input change to this test task; unlike the openapi.yaml
 * guard, no explicit build wiring is needed to keep the check from going stale.
 */
class RequestSizeSubscriptionVerifyLimitTest {
  private val verifyRoute = "/api/v1/subscriptions/verify"

  /**
   * Resolved offline, like [RequestLoggingCarefulDefaultTest], rather than
   * through `AppConfig.load`: the guard is about the cap rest-server *ships*,
   * and a live resolve would fold in the `bin/test` environment and any
   * developer's `~/.config/unicoach/local.conf` overlay. The conf's required
   * `${VAR}` substitutions are fed dummies so `.resolve` completes — their
   * values are irrelevant, since [RequestSizeConfig.from] reads only the
   * `server.requestSize` subtree, which carries none of them.
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
  fun `the verify route's size cap covers SubscriptionService's JWS bound`() {
    // Addressed by route key through the production parser rather than by
    // matching the literal in the file: a cap that happens to appear elsewhere
    // in the conf must not let a stale verify entry pass.
    val routeOverrides = RequestSizeConfig.from(shippedConfig).getOrThrow().routeOverrides
    val cap =
      requireNotNull(routeOverrides[verifyRoute]) {
        "rest-server.conf has no server.requestSize.routeOverrides entry for [$verifyRoute]; found ${routeOverrides.keys}"
      }

    assertTrue(
      cap.bytes > SubscriptionService.MAX_JWS.bytes,
      "the [$verifyRoute] request-size cap [${cap.bytes}] must exceed " +
        "SubscriptionService.MAX_JWS [${SubscriptionService.MAX_JWS.bytes}], " +
        "or a maximum-length JWS cannot fit inside the JSON envelope",
    )
  }
}
