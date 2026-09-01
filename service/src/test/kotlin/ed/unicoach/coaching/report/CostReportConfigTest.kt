package ed.unicoach.coaching.report

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.common.config.AppConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The packaged `costReport` block (RFC 155), read by the class `startServer`
 * actually calls — the precedent set by [ed.unicoach.coaching.CoachingConfigTest]
 * and [ed.unicoach.auth.EmailVerificationConfigTest].
 *
 * Both service-side tests of this feature build their config from a
 * hand-written string, so without this class nothing ever parses the block that
 * ships: a typo in `service.conf`'s `costReport` paths would fail production
 * boot with the whole suite green.
 *
 * The share-token secret is optional by design: an environment without it must
 * still boot, because only the two share tools need it and they decline with a
 * sentence the coach can say. That half is asserted OFFLINE, with the system
 * environment switched off, because this repo's own `.env.dev` sets
 * `COST_REPORT_SHARE_TOKEN_SECRET` for local development — so the running test
 * JVM is precisely the environment in which the absent-secret case cannot be
 * observed.
 */
class CostReportConfigTest {
  @Test
  fun `from reads the packaged defaults`() {
    val config = AppConfig.load("service.conf").getOrThrow()

    val costReport = CostReportConfig.from(config).getOrThrow()

    assertTrue(
      costReport.shareUrlBase.startsWith("http") && costReport.shareUrlBase.endsWith("/report"),
      "the link the coach speaks is the packaged one: [${costReport.shareUrlBase}]",
    )
  }

  @Test
  fun `an unset share-token secret reads as absent rather than failing the boot read`() {
    // service.conf's required substitutions, and nothing else: no
    // COST_REPORT_SHARE_TOKEN_SECRET, exactly as an environment that has never
    // seeded the SSM SecureString.
    val requiredEnv =
      ConfigFactory.parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = stub
        APPLE_AUTH_PROVIDER = stub
        """.trimIndent(),
      )
    val packaged =
      requiredEnv
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

    val costReport = CostReportConfig.from(packaged).getOrThrow()

    assertTrue(costReport.shareUrlBase.endsWith("/report"), "the link base still reads")
    assertNull(
      costReport.secret,
      "a missing secret must leave the server able to start; only the two share tools decline",
    )
  }

  @Test
  fun `a blank secret reads as unset rather than as a usable empty key`() {
    // An env var exported as "" leaves the HOCON key PRESENT and saying nothing.
    // It must mean "unset", not "a secret that happens to be empty" — the type
    // makes the second unrepresentable, and this pins the reading.
    val config =
      ConfigFactory.parseString(
        """
        costReport {
          shareUrlBase = "https://app.unicoach.test/report"
          shareTokenSecret = ""
        }
        """.trimIndent(),
      )

    assertNull(CostReportConfig.from(config).getOrThrow().secret, "an empty secret is no secret")
  }

  @Test
  fun `a configured secret is a long-enough ShareTokenSecret, never a bare string`() {
    val config = configWith(REAL_SECRET)

    val secret = CostReportConfig.from(config).getOrThrow().secret
    assertEquals(ShareTokenSecret.of(REAL_SECRET), secret, "the secret is carried as its own type")
    assertNull(ShareTokenSecret.of(""), "the type has no empty value to construct")
    assertNull(ShareTokenSecret.of(null), "absence is null, and only null")
  }

  /**
   * A present-but-too-short secret is a MISCONFIGURATION, not an unset
   * deployment: "not blank" would have let a one-character value type-check as
   * the key authorising every family's report link. It is refused through the
   * reader's own `Result.failure`, so the process fails to boot rather than
   * signing with it.
   */
  @Test
  fun `a secret shorter than the stated minimum is refused loudly rather than used`() {
    val short = "x".repeat(ShareTokenSecret.MIN_LENGTH - 1)

    val failure = CostReportConfig.from(configWith(short)).exceptionOrNull()

    assertTrue(
      failure?.message?.contains(CostReportConfig.SHARE_TOKEN_SECRET_PATH) == true,
      "the refusal must name the key an operator has to fix: [${failure?.message}]",
    )
  }

  @Test
  fun `a secret exactly at the stated minimum is accepted`() {
    val atFloor = "y".repeat(ShareTokenSecret.MIN_LENGTH)

    val secret = CostReportConfig.from(configWith(atFloor)).getOrThrow().secret

    assertEquals(atFloor, secret?.value, "the floor is inclusive, so the boundary is not a surprise")
  }

  private fun configWith(secret: String) =
    ConfigFactory.parseMap(
      mapOf(
        "costReport.shareUrlBase" to "https://app.unicoach.test/report",
        "costReport.shareTokenSecret" to secret,
      ),
    )

  private companion object {
    /** Long enough to be a key — the recipe is `openssl rand -base64 48` (infra/ssm.tf). */
    const val REAL_SECRET = "a-real-secret-long-enough-to-be-a-key"
  }
}
