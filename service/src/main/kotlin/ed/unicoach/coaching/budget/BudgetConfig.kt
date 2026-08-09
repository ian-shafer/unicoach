package ed.unicoach.coaching.budget

import com.typesafe.config.Config
import ed.unicoach.common.money.Nanodollars

/**
 * Typed reader for the `budget` block of service.conf (RFC 109), mirroring
 * [CoachingConfig][ed.unicoach.coaching.CoachingConfig]'s shape: `from` returns
 * [Result.failure] when the key is absent or unreadable.
 *
 * [freeAllowance] is the lifetime free allowance per student, spent once and
 * cumulatively (no reset). Config states it in human-readable USD;
 * [Nanodollars.fromConfigUsd] converts it to the ledger's integer nano-dollars
 * once at load, so no gate ever re-parses money.
 *
 * A zero allowance is deliberately valid: it is the kill switch that blocks
 * every student at once. An absurdly large one is not — the shared reader holds
 * the value under [Nanodollars.MAX_FOR_PERCENTAGE], so a mistyped
 * `FREE_ALLOWANCE_USD` fails boot with the key named rather than silently
 * wrapping an [Entitlement.usedPercent] later.
 */
class BudgetConfig private constructor(
  val freeAllowance: Nanodollars,
) {
  companion object {
    private const val ALLOWANCE_KEY = "budget.freeAllowanceUsd"

    fun from(config: Config): Result<BudgetConfig> = runCatching { BudgetConfig(Nanodollars.fromConfigUsd(config, ALLOWANCE_KEY)) }
  }
}
