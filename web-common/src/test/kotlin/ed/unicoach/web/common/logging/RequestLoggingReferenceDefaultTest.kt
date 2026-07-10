package ed.unicoach.web.common.logging

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards that web-common's shipped `reference.conf` — the single source of the
 * shared careful request-log defaults auto-merged onto all three web services
 * (RFC 105) — parses to careful when no `REQUEST_LOG_*` override is present.
 * Because admin-web and public-web carry no `requestLogging` block, this test
 * covers every value they resolve; rest-server adds only its client-key override
 * on top (guarded separately).
 *
 * The resolution is deliberately **offline** (`setUseSystemEnvironment(false)`):
 * `bin/test` exports `ENV_FILES=".env.dev:.env.test"`, and `.env.dev` sets
 * `REQUEST_LOG_HEADERS=*` / `REQUEST_LOG_DETAIL=always`. A live-env resolve would
 * see those widened dev values and the guard would read `All` / `ALWAYS` and
 * fail. Turning the environment off makes the optional `${?REQUEST_LOG_*}` subs
 * vanish and fall to the careful literals — the shipped defaults this guard
 * asserts.
 *
 * There is **no** `parseString(...)` dummy feed: `reference.conf` carries no
 * required `${VAR}` subs, only optional ones. This same bare resolve is the
 * guard against a future edit adding a required sub here (which would break every
 * web service's boot): an offline `.resolve()` would throw
 * `UnresolvedSubstitution` before reaching the assertions.
 */
class RequestLoggingReferenceDefaultTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `shipped reference conf parses to careful request-log defaults`() {
    val config =
      ConfigFactory
        .parseResourcesAnySyntax("reference.conf")
        .resolve(offlineOptions)

    val parsed = RequestLoggingConfig.from(config).getOrThrow()

    assertEquals(Detail.FAILURE, parsed.detail)
    assertTrue(parsed.headers is HeaderSelection.Allowlist, "careful default must be an allowlist, not All")
    val allowlist = (parsed.headers as HeaderSelection.Allowlist).names
    assertEquals(
      setOf("Accept", "Content-Type", "User-Agent", "Expect", "Content-Length"),
      allowlist,
      "careful allowlist must be the shared default set",
    )
    assertEquals(
      setOf("cookie", "authorization"),
      parsed.secretHeaders,
      "shared secret set must be exactly cookie + authorization",
    )
  }
}
