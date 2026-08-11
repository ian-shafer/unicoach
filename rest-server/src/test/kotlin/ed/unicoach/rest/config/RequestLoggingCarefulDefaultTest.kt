package ed.unicoach.rest.config

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.web.common.logging.RequestLoggingConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards **only** rest-server's request-log override: `rest-server.conf` adds
 * `X-Unicoach-Client-Key` to reference.conf's base secret set (RFC 105). The
 * shared careful defaults (`headers` / `detail` / the `Cookie,Authorization`
 * base) live in web-common's `reference.conf` and are asserted by
 * `RequestLoggingReferenceDefaultTest`; this guard asserts the delta.
 *
 * The resolution is deliberately **offline** (`setUseSystemEnvironment(false)`),
 * not via `AppConfig.load`: `bin/test` exports `ENV_FILES=".env.dev:.env.test"`,
 * and `.env.dev` sets `REQUEST_LOG_HEADERS=*` / `REQUEST_LOG_DETAIL=always`. A
 * live-env resolve would see those widened dev values. Turning the environment
 * off makes the optional `${?REQUEST_LOG_*}` subs vanish and fall to the shipped
 * literals — the defaults this guard asserts.
 *
 * `rest-server.conf` no longer carries `headers`/`detail` (they live only in
 * `reference.conf`), and the offline fallback chain does **not** auto-merge
 * `reference.conf` the way runtime `ConfigFactory.load` does. So this chain
 * **adds `reference.conf` last** (lowest priority, mirroring the runtime
 * layering); without it `RequestLoggingConfig.from` would fail on the absent
 * `headers`/`detail`. That `from` succeeds proves the inherited defaults
 * resolved; the resolved `x-unicoach-client-key` proves the restated override
 * merged.
 *
 * The conf chain carries **required** substitutions (`${VAR}`, no `.conf`
 * default); an offline `.resolve()` throws `UnresolvedSubstitution` on the first
 * one before `RequestLoggingConfig.from` is ever reached. So the chain's
 * required vars are fed dummy values via a `parseString(...)` prefix first. The
 * dummy values are irrelevant: `RequestLoggingConfig.from` reads only the
 * `requestLogging` subtree, which carries none of the fed subs.
 */
class RequestLoggingCarefulDefaultTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `rest-server conf override adds the client-key header to the secret set`() {
    val config =
      ConfigFactory
        .parseString(
          """
          SERVER_PORT = 0
          APP_DOMAIN = x
          SESSION_COOKIE_SECURE = false
          CHAT_PROVIDER = log
          POSTGRES_PORT = 0
          POSTGRES_DB = x
          PUBLIC_WEB_PORT = 0
          GOOGLE_AUTH_PROVIDER = stub
          APPLE_AUTH_PROVIDER = stub
          """.trimIndent(),
        ).withFallback(ConfigFactory.parseResourcesAnySyntax("rest-server.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("queue.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("chat.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("service.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("db.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("common.conf"))
        .withFallback(ConfigFactory.parseResourcesAnySyntax("reference.conf"))
        .resolve(offlineOptions)

    // `from` succeeding proves the inherited headers/detail resolved from
    // reference.conf; the secret set proves rest-server's restated override merged.
    val parsed = RequestLoggingConfig.from(config).getOrThrow()

    assertEquals(
      setOf("cookie", "authorization", "x-unicoach-client-key"),
      parsed.secretHeaders,
      "rest-server override must add x-unicoach-client-key to the base secret set",
    )
  }
}
