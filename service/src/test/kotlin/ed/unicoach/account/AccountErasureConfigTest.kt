package ed.unicoach.account

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** The `accountErasure` block of the packaged service.conf (RFC 267 §4). */
class AccountErasureConfigTest {
  /** The packaged file, resolved with [env] standing in for the environment (plus service.conf's required keys). */
  private fun packaged(env: String = ""): Config =
    ConfigFactory
      .parseString(
        """
        APP_DOMAIN = localhost
        PUBLIC_WEB_PORT = 8082
        GOOGLE_AUTH_PROVIDER = stub
        APPLE_AUTH_PROVIDER = stub
        $env
        """.trimIndent(),
      ).withFallback(ConfigFactory.parseResources("service.conf"))
      .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

  @Test
  fun `the default grace is 7 days`() {
    assertEquals(7.days, AccountErasureConfig.from(packaged()).getOrThrow().grace)
  }

  @Test
  fun `the environment override is read`() {
    assertEquals(0.days, AccountErasureConfig.from(packaged("ACCOUNT_ERASURE_GRACE_DAYS = 0")).getOrThrow().grace)
    assertEquals(30.days, AccountErasureConfig.from(packaged("ACCOUNT_ERASURE_GRACE_DAYS = 30")).getOrThrow().grace)
  }

  @Test
  fun `a negative grace fails`() {
    val result = AccountErasureConfig.from(packaged("ACCOUNT_ERASURE_GRACE_DAYS = -1"))
    assertTrue(result.isFailure, "a negative grace would schedule an erasure before its close")
  }
}
