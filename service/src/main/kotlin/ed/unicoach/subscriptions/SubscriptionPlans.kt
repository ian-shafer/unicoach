package ed.unicoach.subscriptions

import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import ed.unicoach.common.money.Nanodollars
import java.math.BigDecimal

/**
 * The typed productId → period-budget table (RFC 110), read from the
 * `subscriptions` block of service.conf. Budgets are computed once, at load:
 * `y × priceUsd` exact via [BigDecimal] into [Nanodollars.fromExactUsd] — the
 * reject-rather-than-round money policy, so a ratio/price pair with no exact
 * nano-dollar product fails boot with the key named. Each budget is also held
 * under [Nanodollars.MAX_FOR_PERCENTAGE], the one ceiling every
 * percentage-checked money amount shares.
 */
class SubscriptionPlans private constructor(
  private val periodBudgets: Map<String, Nanodollars>,
) {
  /** Null for a product this box does not know — the caller names the failure. */
  fun periodBudget(productId: String): Nanodollars? = periodBudgets[productId]

  companion object {
    private const val RATIO_KEY = "subscriptions.budgetRatio"
    private const val PLANS_KEY = "subscriptions.plans"
    private const val PRICE_KEY = "priceUsd"

    /**
     * Failure on: ratio outside (0, 1); a plan that is not a nested object;
     * non-positive price; inexact or over-ceiling budget; empty plans.
     */
    fun from(config: Config): Result<SubscriptionPlans> =
      runCatching {
        val ratio = parseRatio(config)
        val plansObject = config.getConfig(PLANS_KEY).root()
        require(plansObject.isNotEmpty()) { "[$PLANS_KEY] is empty: at least one plan must be configured" }
        SubscriptionPlans(
          plansObject.entries.associate { (productId, value) ->
            productId to periodBudget(productId, planConfig(productId, value), ratio)
          },
        )
      }

    /**
     * A plan entry is an object or it is nothing: a scalar (`plans { id = 5 }`)
     * fails naming the plan, the way every other parse failure here does,
     * rather than reaching [Config] through a cast that throws anonymously.
     */
    private fun planConfig(
      productId: String,
      value: ConfigValue,
    ): Config =
      (value as? ConfigObject)?.toConfig()
        ?: throw IllegalArgumentException(
          "[$PLANS_KEY.\"$productId\"] must be a nested object (e.g. { $PRICE_KEY = 9.99 }), got [${value.valueType()}]",
        )

    private fun parseRatio(config: Config): BigDecimal {
      val raw = config.getValue(RATIO_KEY).unwrapped().toString()
      val ratio =
        runCatching { BigDecimal(raw) }
          .getOrElse { throw IllegalArgumentException("[$RATIO_KEY] = [$raw] is not a decimal", it) }
      require(ratio > BigDecimal.ZERO && ratio < BigDecimal.ONE) {
        "[$RATIO_KEY] = [$raw] must be strictly between 0 and 1 (the fraction of a plan's price funding provider spend)"
      }
      return ratio
    }

    /**
     * One plan's period budget: the exact decimal product `ratio × priceUsd`
     * into nano-dollars. Every way this can fail — a price that is not a
     * decimal, non-positive, an inexact product, an over-ceiling budget —
     * surfaces out of [from] naming the plan.
     */
    private fun periodBudget(
      productId: String,
      plan: Config,
      ratio: BigDecimal,
    ): Nanodollars {
      val raw = plan.getValue(PRICE_KEY).unwrapped().toString()
      return runCatching {
        val price = BigDecimal(raw)
        require(price.signum() > 0) { "price must be positive" }
        val budget = Nanodollars.fromExactUsd(ratio.multiply(price))
        require(budget.value <= Nanodollars.MAX_FOR_PERCENTAGE.value) {
          "budget [${budget.toUsdString()}] is above the largest amount a usage percentage " +
            "can be computed for [${Nanodollars.MAX_FOR_PERCENTAGE.toUsdString()}]"
        }
        budget
      }.getOrElse {
        throw IllegalArgumentException(
          "subscription plan [$PLANS_KEY.\"$productId\".$PRICE_KEY] = [$raw] (USD) with ratio [$ratio]: [${it.message}]",
          it,
        )
      }
    }
  }
}
