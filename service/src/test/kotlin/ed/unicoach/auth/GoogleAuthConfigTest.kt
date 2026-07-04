package ed.unicoach.auth

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleAuthConfigTest {
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

    val parsed = GoogleAuthConfig.from(config).getOrThrow()
    assertEquals("google", parsed.provider)
    assertEquals(listOf("c-1", "c-2"), parsed.clientIds)
    assertEquals(listOf("accounts.google.com", "https://accounts.google.com"), parsed.issuers)
    assertEquals("https://example.test/certs", parsed.jwksUri)
    assertEquals(Duration.ofSeconds(90), parsed.clockSkew)
    assertEquals(Duration.ofSeconds(5), parsed.connectTimeout)
    assertEquals(Duration.ofSeconds(6), parsed.readTimeout)
  }

  @Test
  fun `applies documented defaults from the packaged service conf`() {
    // Parse the packaged resource with env-var substitutions stripped so the test
    // observes the file's own defaults, not the harness environment. `provider` is
    // a REQUIRED substitution (no .conf default) after RFC 95, so supply
    // GOOGLE_AUTH_PROVIDER explicitly; every OTHER auth.google key still has a
    // committed default the test pins. allowUnresolved tolerates keys with no
    // file-own default at all (e.g. the env-derived emailVerification.verifyUrlBase).
    val config =
      ConfigFactory
        .parseString("GOOGLE_AUTH_PROVIDER = stub")
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(
          com.typesafe.config.ConfigResolveOptions
            .defaults()
            .setUseSystemEnvironment(false)
            .setAllowUnresolved(true),
        )
    val parsed = GoogleAuthConfig.from(config).getOrThrow()

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

    val parsed = GoogleAuthConfig.from(config).getOrThrow()
    assertEquals(listOf("a", "b", "c"), parsed.clientIds)
  }

  @Test
  fun `fails when the auth google section is absent`() {
    val config = ConfigFactory.parseString("coaching { model = x }")
    val result = GoogleAuthConfig.from(config)
    assertTrue(result.isFailure)
  }
}
