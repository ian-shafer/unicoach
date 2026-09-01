package ed.unicoach

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.coaching.report.CostReportConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the packaged service.conf's required-env contract: the file resolves with
 * exactly APP_DOMAIN, PUBLIC_WEB_PORT, GOOGLE_AUTH_PROVIDER, and
 * APPLE_AUTH_PROVIDER supplied, and fails fast without them. Every env that
 * boots a service.conf consumer must provide all four (.env.dev locally, the
 * .env.<env>/SSM env on the deploy host); a new required substitution added to
 * service.conf breaks the first test until its variable is documented in that
 * set. GOOGLE_AUTH_PROVIDER/APPLE_AUTH_PROVIDER are required substitutions (no
 * .conf default) so a forgotten prod override fails the JVM at boot instead of
 * silently running the offline `stub` verifier (RFC 95, generalized by RFC 111).
 *
 * It also pins the one-public-web-origin shape (RFC 155 D-J): `publicWeb.urlBase`
 * is stated once and both page links derive from it, an env-supplied origin
 * reaches both, and each per-link escape hatch still wins when set.
 */
class ServiceConfTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  @Test
  fun `resolves offline with exactly APP_DOMAIN, PUBLIC_WEB_PORT, GOOGLE_AUTH_PROVIDER, and APPLE_AUTH_PROVIDER`() {
    val requiredEnv =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = stub
        APPLE_AUTH_PROVIDER = stub
        """.trimIndent(),
      )

    val resolved =
      requiredEnv
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)

    assertEquals(
      "http://localhost:8082/verify-email",
      resolved.getString("emailVerification.verifyUrlBase"),
    )
    assertEquals("stub", resolved.getString("auth.google.provider"))
    assertEquals("stub", resolved.getString("auth.apple.provider"))
  }

  @Test
  fun `fails to resolve without the required environment`() {
    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      ConfigFactory.parseResources("service.conf").resolve(offlineOptions)
    }
  }

  @Test
  fun `required GOOGLE_AUTH_PROVIDER fails to resolve when unset`() {
    // Supply every OTHER required substitution but omit GOOGLE_AUTH_PROVIDER, so
    // the failure isolates to that toggle: an unset provider must fail the JVM at
    // boot, never fall back to the offline stub verifier.
    val withoutToggle =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        APPLE_AUTH_PROVIDER = stub
        """.trimIndent(),
      )

    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      withoutToggle
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)
    }
  }

  @Test
  fun `required APPLE_AUTH_PROVIDER fails to resolve when unset`() {
    // Mirrors the GOOGLE_AUTH_PROVIDER isolation case above, for the Apple toggle
    // RFC 111 adds.
    val withoutToggle =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = stub
        """.trimIndent(),
      )

    assertFailsWith<ConfigException.UnresolvedSubstitution> {
      withoutToggle
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)
    }
  }

  @Test
  fun `GOOGLE_AUTH_PROVIDER resolves to the set value`() {
    val withToggle =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = google
        APPLE_AUTH_PROVIDER = stub
        """.trimIndent(),
      )

    val resolved =
      withToggle
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)

    assertEquals("google", resolved.getString("auth.google.provider"))
  }

  // ── One public-web origin, paths derived (RFC 155 D-J) ────────────────────
  //
  // service.conf states the public-web origin ONCE (publicWeb.urlBase) and each
  // page link appends only its own path. HOCON resolves substitutions AFTER the
  // merge, so an env-supplied origin must reach BOTH derived links; and both
  // per-link escape hatches must keep WINNING when set, because email
  // verification is a shipped surface a deployed environment may already
  // override. These pin all three of those, through the typed readers the
  // servers actually call — so the single trailing-slash rule is in scope too.

  private fun resolvePackaged(envLines: String) =
    ConfigFactory
      .parseString(envLines.trimIndent())
      .withFallback(ConfigFactory.parseResources("service.conf"))
      .resolve(offlineOptions)

  private val requiredEnvLines =
    """
    APP_DOMAIN = localhost
    PUBLIC_WEB_PORT = 8082
    GOOGLE_AUTH_PROVIDER = stub
    APPLE_AUTH_PROVIDER = stub
    """

  private fun verifyBase(config: Config): String = EmailVerificationConfig.from(config).getOrThrow().verifyUrlBase

  private fun shareBase(config: Config): String = CostReportConfig.from(config).getOrThrow().shareUrlBase

  @Test
  fun `the packaged default derives BOTH links from the one local origin`() {
    val resolved = resolvePackaged(requiredEnvLines)

    assertEquals("http://localhost:8082", resolved.getString("publicWeb.urlBase"))
    assertEquals("http://localhost:8082/verify-email", verifyBase(resolved))
    assertEquals("http://localhost:8082/report", shareBase(resolved))
  }

  @Test
  fun `PUBLIC_WEB_URL_BASE propagates into BOTH derived links`() {
    // The whole point of the unification: one env var moves both pages. HOCON
    // resolves after the merge, so the override set here is what the derived
    // values read — assert it rather than assume it.
    val resolved = resolvePackaged(requiredEnvLines + "\nPUBLIC_WEB_URL_BASE = \"https://app.uni.coach\"")

    assertEquals("https://app.uni.coach/verify-email", verifyBase(resolved))
    assertEquals("https://app.uni.coach/report", shareBase(resolved))
  }

  @Test
  fun `a trailing slash on PUBLIC_WEB_URL_BASE does not double the separator on either link`() {
    val resolved = resolvePackaged(requiredEnvLines + "\nPUBLIC_WEB_URL_BASE = \"https://app.uni.coach/\"")

    assertEquals("https://app.uni.coach/verify-email", verifyBase(resolved))
    assertEquals("https://app.uni.coach/report", shareBase(resolved))
  }

  @Test
  fun `EMAIL_VERIFICATION_VERIFY_URL_BASE still wins over the derived default`() {
    // Email verification is SHIPPED. An environment already exporting this must
    // keep the link it exported; only the DEFAULT moved under it.
    val resolved =
      resolvePackaged(
        requiredEnvLines + "\nEMAIL_VERIFICATION_VERIFY_URL_BASE = \"https://legacy.uni.coach/confirm\"",
      )

    assertEquals("https://legacy.uni.coach/confirm", verifyBase(resolved))
    assertEquals("http://localhost:8082/report", shareBase(resolved))
  }

  @Test
  fun `COST_REPORT_SHARE_URL_BASE still wins over the derived default`() {
    val resolved =
      resolvePackaged(
        requiredEnvLines + "\nCOST_REPORT_SHARE_URL_BASE = \"https://legacy.uni.coach/family\"",
      )

    assertEquals("https://legacy.uni.coach/family", shareBase(resolved))
    assertEquals("http://localhost:8082/verify-email", verifyBase(resolved))
  }

  @Test
  fun `a per-link override beats the origin when BOTH are set`() {
    // The migration state a deployed environment can actually be in: the new
    // origin adopted while a legacy per-link value is still exported. The
    // per-link value is the more specific statement, so it wins; the link
    // without one follows the origin.
    val resolved =
      resolvePackaged(
        requiredEnvLines +
          "\nPUBLIC_WEB_URL_BASE = \"https://app.uni.coach\"" +
          "\nEMAIL_VERIFICATION_VERIFY_URL_BASE = \"https://legacy.uni.coach/confirm\"",
      )

    assertEquals("https://legacy.uni.coach/confirm", verifyBase(resolved))
    assertEquals("https://app.uni.coach/report", shareBase(resolved))
  }

  @Test
  fun `a trailing slash on a per-link override is normalized by the same one rule`() {
    val resolved =
      resolvePackaged(
        requiredEnvLines + "\nCOST_REPORT_SHARE_URL_BASE = \"https://legacy.uni.coach/family/\"",
      )

    assertEquals("https://legacy.uni.coach/family", shareBase(resolved))
  }

  @Test
  fun `APPLE_AUTH_PROVIDER resolves to the set value`() {
    val withToggle =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = stub
        APPLE_AUTH_PROVIDER = apple
        """.trimIndent(),
      )

    val resolved =
      withToggle
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)

    assertEquals("apple", resolved.getString("auth.apple.provider"))
  }
}
