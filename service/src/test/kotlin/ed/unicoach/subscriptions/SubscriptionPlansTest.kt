package ed.unicoach.subscriptions

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [SubscriptionPlans.from] (RFC 110): the packaged default block's exact
 * budget, the (0, 1) ratio band, the reject-rather-than-round money policy on
 * the `y × price` product, the percentage-arithmetic ceiling, and the null
 * answer for a product this box does not know.
 */
class SubscriptionPlansTest {
  private fun plans(
    budgetRatio: String = "0.5",
    plansBlock: String = """"coach.uni.UnicoachiOS.monthly10" { priceUsd = 9.99 }""",
  ) = SubscriptionPlans.from(
    ConfigFactory.parseString(
      """
      subscriptions.budgetRatio = $budgetRatio
      subscriptions.plans { $plansBlock }
      """.trimIndent(),
    ),
  )

  @Test
  fun `the packaged default block parses to the monthly10 budget`() {
    // The shipped service.conf itself (resolved offline with its required env,
    // ServiceConfTest's incantation), so a plan-block edit shows up here.
    val packaged =
      ConfigFactory
        .parseString("APP_DOMAIN = localhost\nPUBLIC_WEB_PORT = 8082\nGOOGLE_AUTH_PROVIDER = stub")
        .withFallback(ConfigFactory.parseResources("service.conf"))
        .resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))

    val parsed = SubscriptionPlans.from(packaged).getOrThrow()

    assertEquals(
      4_995_000_000L,
      parsed.periodBudget("coach.uni.UnicoachiOS.monthly10")!!.value,
      "y = 0.5 of the \$9.99 plan funds \$4.995 of provider spend per period",
    )
  }

  @Test
  fun `an unknown productId answers null — the caller names the failure`() {
    assertNull(plans().getOrThrow().periodBudget("coach.uni.UnicoachiOS.yearly100"))
  }

  @Test
  fun `a ratio of 0, 1, or negative is a failure`() {
    for (ratio in listOf("0", "1", "-0.5")) {
      assertTrue(plans(budgetRatio = ratio).isFailure, "ratio [$ratio] is outside (0, 1)")
    }
  }

  @Test
  fun `an inexact ratio-price product is a failure naming the plan`() {
    // 0.3333333333 × 9.99 has no exact nano-dollar form; rejecting beats rounding.
    val failure = plans(budgetRatio = "0.3333333333").exceptionOrNull()
    assertNotNull(failure)
    assertTrue(failure.message!!.contains("coach.uni.UnicoachiOS.monthly10"), failure.message)
  }

  @Test
  fun `an over-ceiling budget is a failure`() {
    val failure =
      plans(plansBlock = """"coach.uni.UnicoachiOS.monthly10" { priceUsd = 300000000.00 }""")
        .exceptionOrNull()
    assertNotNull(failure, "\$150M per period is past the percentage-arithmetic ceiling")
    assertTrue(failure.message!!.contains("coach.uni.UnicoachiOS.monthly10"), failure.message)
  }

  @Test
  fun `a non-positive price is a failure naming the plan`() {
    val failure =
      plans(plansBlock = """"coach.uni.UnicoachiOS.monthly10" { priceUsd = 0 }""")
        .exceptionOrNull()
    assertNotNull(failure)
    assertTrue(failure.message!!.contains("coach.uni.UnicoachiOS.monthly10"), failure.message)
  }

  @Test
  fun `a plan that is not a nested object is a failure naming the plan`() {
    val failure =
      plans(plansBlock = """"coach.uni.UnicoachiOS.monthly10" = 5""")
        .exceptionOrNull()
    assertNotNull(failure, "a scalar plan entry has no priceUsd to read")
    assertTrue(failure.message!!.contains("coach.uni.UnicoachiOS.monthly10"), failure.message)
  }

  @Test
  fun `empty plans are a failure — a subscribed product must map somewhere`() {
    assertTrue(plans(plansBlock = "").isFailure)
  }
}
