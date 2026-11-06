package ed.unicoach.auth

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsoProviderConfigTest {
  @Test
  fun `parses a full auth google block`() {
    val config =
      ConfigFactory.parseString(
        """
        auth {
          google {
            provider = "google"
            clientIds = ["c-1", "c-2"]
            issuers = ["accounts.google.com", "https://accounts.google.com"]
            jwksUri = "https://example.test/certs"
            clockSkew = "90s"
            connectTimeout = "5s"
            readTimeout = "6s"
          }
        }
        """.trimIndent(),
      )

    val parsed = SsoProviderConfig.from(config, "auth.google").getOrThrow()
    assertEquals("google", parsed.provider)
    assertEquals(listOf("c-1", "c-2"), parsed.clientIds)
    assertEquals(listOf("accounts.google.com", "https://accounts.google.com"), parsed.issuers)
    assertEquals("https://example.test/certs", parsed.jwksUri)
    assertEquals(Duration.ofSeconds(90), parsed.clockSkew)
    assertEquals(Duration.ofSeconds(5), parsed.connectTimeout)
    assertEquals(Duration.ofSeconds(6), parsed.readTimeout)
  }

  @Test
  fun `applies documented defaults from the packaged service conf for auth google`() {
    // Parse the packaged resource with env-var substitutions stripped so the test
    // observes the file's own defaults, not the harness environment. `provider` is
    // a REQUIRED substitution (no .conf default) after RFC 95, so supply
    // GOOGLE_AUTH_PROVIDER explicitly; every OTHER auth.google key still has a
    // committed default the test pins. allowUnresolved tolerates keys with no
    // file-own default at all (e.g. the env-derived emailVerification.verifyUrlBase).
    val config =
      ConfigFactory
        .parseString("GOOGLE_AUTH_PROVIDER = stub\nAPPLE_AUTH_PROVIDER = stub")
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(
          com.typesafe.config.ConfigResolveOptions
            .defaults()
            .setUseSystemEnvironment(false)
            .setAllowUnresolved(true),
        )
    val parsed = SsoProviderConfig.from(config, "auth.google").getOrThrow()

    // provider has NO committed default — it is required and set per-env in the
    // dotenv (.env.dev=stub, .env.<env>=google), so a forgotten prod override
    // fails the JVM at boot rather than silently dropping to a stub-auth bypass
    // (RFC 95). Here it reflects the value supplied above.
    assertEquals("stub", parsed.provider)
    assertEquals(listOf("accounts.google.com", "https://accounts.google.com"), parsed.issuers)
    assertEquals("https://www.googleapis.com/oauth2/v3/certs", parsed.jwksUri)
    assertEquals(Duration.ofSeconds(60), parsed.clockSkew)
    assertEquals(Duration.ofSeconds(10), parsed.connectTimeout)
    assertEquals(Duration.ofSeconds(10), parsed.readTimeout)
  }

  @Test
  fun `applies documented defaults from the packaged service conf for auth apple`() {
    // APPLE_CLIENT_IDS is supplied in the env-override shape prod actually uses — a
    // single comma-separated string flowing through the packaged conf's
    // `clientIds = ${?APPLE_CLIENT_IDS}` — so this case covers both the committed
    // defaults and the substitution that decides which audiences prod accepts.
    val config =
      ConfigFactory
        .parseString(
          """
          GOOGLE_AUTH_PROVIDER = stub
          APPLE_AUTH_PROVIDER = stub
          APPLE_CLIENT_IDS = " coach.uni.UnicoachiOS , coach.uni.UnicoachiOS.debug "
          """.trimIndent(),
        ).withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(
          com.typesafe.config.ConfigResolveOptions
            .defaults()
            .setUseSystemEnvironment(false)
            .setAllowUnresolved(true),
        )
    val parsed = SsoProviderConfig.from(config, "auth.apple").getOrThrow()

    assertEquals("stub", parsed.provider)
    assertEquals(listOf("coach.uni.UnicoachiOS", "coach.uni.UnicoachiOS.debug"), parsed.clientIds)
    assertEquals(listOf("https://appleid.apple.com"), parsed.issuers)
    assertEquals("https://appleid.apple.com/auth/keys", parsed.jwksUri)
    assertEquals(Duration.ofSeconds(60), parsed.clockSkew)
    assertEquals(Duration.ofSeconds(10), parsed.connectTimeout)
    assertEquals(Duration.ofSeconds(10), parsed.readTimeout)
  }

  @Test
  fun `splits a comma-separated clientIds string`() {
    val config =
      ConfigFactory.parseString(
        """
        auth {
          google {
            provider = "google"
            clientIds = " a , b ,c "
            issuers = ["accounts.google.com"]
            jwksUri = "https://example.test/certs"
            clockSkew = "60s"
            connectTimeout = "1s"
            readTimeout = "1s"
          }
        }
        """.trimIndent(),
      )

    val parsed = SsoProviderConfig.from(config, "auth.google").getOrThrow()
    assertEquals(listOf("a", "b", "c"), parsed.clientIds)
  }

  @Test
  fun `fails when the auth google section is absent`() {
    val config = ConfigFactory.parseString("coaching { model = x }")
    val result = SsoProviderConfig.from(config, "auth.google")
    assertTrue(result.isFailure)
  }

  @Test
  fun `fails when the auth apple section is absent`() {
    val config = ConfigFactory.parseString("coaching { model = x }")
    val result = SsoProviderConfig.from(config, "auth.apple")
    assertTrue(result.isFailure)
  }

  /**
   * The web client joins the accepted audiences by derivation (RFC 275 D-C),
   * listed once even when an operator also put it in `clientIds`.
   */
  @Test
  fun `acceptedAudiences is clientIds plus the web client id, de-duplicated`() {
    val parsed =
      SsoProviderConfig
        .from(
          googleBlock("""clientIds = ["ios-1", "web-1"]""", """webClientId = " web-1 """"),
          "auth.google",
        ).getOrThrow()
    assertEquals("web-1", parsed.webClientId)
    assertEquals(listOf("ios-1", "web-1"), parsed.acceptedAudiences)

    val added = SsoProviderConfig.from(googleBlock("""clientIds = ["ios-1"]""", """webClientId = "web-2""""), "auth.google").getOrThrow()
    assertEquals(listOf("ios-1", "web-2"), added.acceptedAudiences)
  }

  @Test
  fun `a blank or absent web client id reads as absent`() {
    val blank = SsoProviderConfig.from(googleBlock("""clientIds = ["ios-1"]""", """webClientId = "  """"), "auth.google").getOrThrow()
    assertNull(blank.webClientId)
    assertEquals(listOf("ios-1"), blank.acceptedAudiences)

    val absent = SsoProviderConfig.from(googleBlock("""clientIds = ["ios-1"]"""), "auth.google").getOrThrow()
    assertNull(absent.webClientId)
    assertEquals(listOf("ios-1"), absent.acceptedAudiences)
  }

  /**
   * The env shape prod actually sets, through the packaged conf: the
   * comma-separated `GOOGLE_CLIENT_IDS` string still splits, and
   * `GOOGLE_WEB_CLIENT_ID` lands in `webClientId` and the accepted set.
   */
  @Test
  fun `the packaged conf reads the web client ids from their env keys`() {
    val config =
      ConfigFactory
        .parseString(
          """
          GOOGLE_AUTH_PROVIDER = stub
          APPLE_AUTH_PROVIDER = stub
          GOOGLE_CLIENT_IDS = " ios-a , ios-b "
          GOOGLE_WEB_CLIENT_ID = "web-g"
          APPLE_WEB_SERVICES_ID = "coach.uni.web"
          """.trimIndent(),
        ).withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(
          com.typesafe.config.ConfigResolveOptions
            .defaults()
            .setUseSystemEnvironment(false)
            .setAllowUnresolved(true),
        )

    val google = SsoProviderConfig.from(config, "auth.google").getOrThrow()
    assertEquals(listOf("ios-a", "ios-b"), google.clientIds)
    assertEquals(listOf("ios-a", "ios-b", "web-g"), google.acceptedAudiences)
    val apple = SsoProviderConfig.from(config, "auth.apple").getOrThrow()
    assertEquals("coach.uni.web", apple.webClientId)
    assertEquals(listOf("coach.uni.web"), apple.acceptedAudiences)
  }

  private fun googleBlock(vararg lines: String) =
    ConfigFactory.parseString(
      """
      auth {
        google {
          provider = "google"
          ${lines.joinToString("\n          ")}
          issuers = ["accounts.google.com"]
          jwksUri = "https://example.test/certs"
          clockSkew = "60s"
          connectTimeout = "1s"
          readTimeout = "1s"
        }
      }
      """.trimIndent(),
    )
}
