package ed.unicoach.coaching.budget

import com.typesafe.config.Config
import ed.unicoach.common.money.Nanodollars
import java.math.BigDecimal

/**
 * Typed reader for the `budget` block of service.conf (RFC 109), mirroring
 * [CoachingConfig][ed.unicoach.coaching.CoachingConfig]'s shape: `from` returns
 * [Result.failure] when the key is absent or unreadable.
 *
 * [freeAllowance] is the lifetime free allowance per student, spent once and
 * cumulatively (no reset). Config states it in human-readable USD; the
 * conversion to the ledger's integer nano-dollars happens here, once at load, so
 * no gate ever re-parses money.
 *
 * A zero allowance is deliberately valid: it is the kill switch that blocks
 * every student at once. An absurdly large one is not — see [MAX_ALLOWANCE].
 */
class BudgetConfig private constructor(
  val freeAllowance: Nanodollars,
) {
  companion object {
    /** USD → nano-dollars: 1e9 nano-dollars to the dollar. */
    private const val USD_SCALE_DIGITS = 9

    private const val ALLOWANCE_KEY = "budget.freeAllowanceUsd"

    /**
     * The largest allowance [Entitlement.usedPercent] can be computed for
     * (≈`$92.2M`). That percentage multiplies the SPEND by 100, not the
     * allowance — but it only does so on the branch where `spent < allowance`,
     * so bounding the allowance here is what keeps `spent × 100` inside a
     * [Long]. Enforcing it at load, the one boundary an operator's value
     * crosses, makes a mistyped `FREE_ALLOWANCE_USD` fail boot with the key
     * named rather than silently wrap a percentage later.
     */
    private val MAX_ALLOWANCE = Nanodollars.of(Long.MAX_VALUE / 100)

    fun from(config: Config): Result<BudgetConfig> =
      runCatching {
        val freeAllowance = parseNanodollars(config, ALLOWANCE_KEY)
        require(freeAllowance.value <= MAX_ALLOWANCE.value) {
          "budget amount [$ALLOWANCE_KEY] (USD) is [${freeAllowance.toUsdString()}], " +
            "above the largest allowance a usage percentage can be computed for [${MAX_ALLOWANCE.toUsdString()}]"
        }
        BudgetConfig(freeAllowance)
      }

    /**
     * Converts the USD amount at [key] to nano-dollars. The exact decimal is
     * taken from the value's original text (not a lossy binary double), and
     * [Nanodollars.fromExactDecimal] applies the shared reject-rather-than-round
     * policy to it.
     *
     * Both steps sit inside the same catch, so EVERY way this can fail — text
     * that is not a decimal at all ([BigDecimal]'s own
     * `NumberFormatException`, which names neither), as much as a negative or
     * sub-nano-dollar amount — surfaces out of [from] as a [Result.failure]
     * naming the offending [key], its raw text, and this reader's unit.
     */
    private fun parseNanodollars(
      config: Config,
      key: String,
    ): Nanodollars {
      val raw = config.getValue(key).unwrapped().toString()
      return runCatching { Nanodollars.fromExactDecimal(BigDecimal(raw), USD_SCALE_DIGITS) }
        .getOrElse {
          throw IllegalArgumentException(
            "budget amount [$key] = [$raw] (USD, no finer than a nano-dollar): [${it.message}]",
            it,
          )
        }
    }
  }
}
