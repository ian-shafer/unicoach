package ed.unicoach.coaching.budget

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.common.money.Nanodollars
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [BudgetConfig] (RFC 109): the human-readable-USD → nano-dollar
 * conversion, the kill-switch zero, and the load-time guards that reject an
 * allowance the rest of the budget domain could not carry — one finer than a
 * nano-dollar, and one above the usage-percentage ceiling.
 *
 * The packaged service.conf's own value is asserted too, so the shipped default
 * allowance is pinned to a number, not just to "whatever the file says".
 */
class BudgetConfigTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  private fun configFrom(hocon: String) = BudgetConfig.from(ConfigFactory.parseString(hocon))

  @Test
  fun `parses human-readable USD into nano-dollars`() {
    val config = configFrom("budget { freeAllowanceUsd = 5.00 }").getOrThrow()
    assertEquals(5_000_000_000L, config.freeAllowance.value)
  }

  @Test
  fun `a zero allowance is valid — the kill switch is representable`() {
    val config = configFrom("budget { freeAllowanceUsd = 0.00 }").getOrThrow()
    assertEquals(0L, config.freeAllowance.value)
  }

  @Test
  fun `a negative allowance is rejected`() {
    val failure = configFrom("budget { freeAllowanceUsd = -1.00 }").exceptionOrNull()
    assertTrue(failure is IllegalArgumentException, "got [$failure]")
    assertTrue(
      failure.message!!.contains("budget.freeAllowanceUsd"),
      "the failure names the offending key, got [${failure.message}]",
    )
  }

  @Test
  fun `an allowance finer than a nano-dollar is rejected, never silently rounded`() {
    val failure = configFrom("budget { freeAllowanceUsd = 0.0000000001 }").exceptionOrNull()
    assertTrue(failure is IllegalArgumentException, "got [$failure]")
    assertTrue(
      failure.message!!.contains("no exact nano-dollar form"),
      "the failure says why, got [${failure.message}]",
    )
  }

  @Test
  fun `an allowance that is not a number names the key and the raw text`() {
    // A typo, a currency symbol, an unresolved substitution: the text never
    // reaches Nanodollars, so only the reader's own wrapper can say what broke.
    val failure = configFrom("""budget { freeAllowanceUsd = "5..00" }""").exceptionOrNull()
    assertTrue(failure is IllegalArgumentException, "got [$failure]")
    assertTrue(
      failure.message!!.contains("budget.freeAllowanceUsd") && failure.message!!.contains("5..00"),
      "the failure names the offending key and its raw value, got [${failure.message}]",
    )
  }

  @Test
  fun `an allowance above the usage-percentage ceiling is rejected at load`() {
    // Just past Long.MAX_VALUE / 100 nano-dollars, the point where usedPercent's
    // `spent × 100` would stop fitting in a Long.
    val failure = configFrom("budget { freeAllowanceUsd = 92233721.00 }").exceptionOrNull()
    assertTrue(failure is IllegalArgumentException, "got [$failure]")
    assertTrue(
      failure.message!!.contains("budget.freeAllowanceUsd"),
      "the failure names the offending key, got [${failure.message}]",
    )
  }

  @Test
  fun `an allowance just under the ceiling still parses`() {
    val config = configFrom("budget { freeAllowanceUsd = 92233720.00 }").getOrThrow()
    assertEquals(92_233_720_000_000_000L, config.freeAllowance.value)
    assertTrue(
      Entitlement(
        spent = Nanodollars.of(config.freeAllowance.value - 1),
        allowance = config.freeAllowance,
        basis = EntitlementBasis.FreeAllowance,
      ).usedPercent
        in 0..100,
      "the largest allowance config accepts must still compute a percentage",
    )
  }

  @Test
  fun `a missing key is a failure, never a zero allowance`() {
    assertTrue(configFrom("budget { }").isFailure, "an absent allowance must not read as the kill-switch zero")
  }

  @Test
  fun `the packaged service_conf allowance is five dollars`() {
    val resolved =
      ConfigFactory
        .parseString(
          """
          APP_DOMAIN = localhost
          PUBLIC_WEB_PORT = 8082
          GOOGLE_AUTH_PROVIDER = stub
          """.trimIndent(),
        ).withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(offlineOptions)

    assertEquals(
      5_000_000_000L,
      BudgetConfig
        .from(resolved)
        .getOrThrow()
        .freeAllowance.value,
    )
  }
}
