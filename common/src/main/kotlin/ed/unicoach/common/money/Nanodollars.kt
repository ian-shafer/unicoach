package ed.unicoach.common.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of money in nano-dollars (1e-9 USD), the integer unit RFC 108
 * established for LLM cost so a call's price is a pure integer dot-product —
 * no division, no rounding — and so a ledger sum never drifts through binary
 * floats. Never negative: every producer (a config-loaded per-token rate, or a
 * dot-product of non-negative rates and non-negative token counts) guarantees
 * that, so the type enforces it once rather than every call site re-checking.
 */
@JvmInline
value class Nanodollars private constructor(
  val value: Long,
) {
  init {
    require(value >= 0) { "Nanodollars must be non-negative, got [$value]" }
  }

  /**
   * Renders as human-readable USD at [USD_DISPLAY_SCALE] decimal places, e.g.
   * `3_000_000` → `"0.003000"`. Exact via [BigDecimal] — no binary-float
   * rounding on the way to the display.
   */
  fun toUsdString(): String =
    BigDecimal
      .valueOf(value)
      .movePointLeft(9)
      .setScale(USD_DISPLAY_SCALE, RoundingMode.HALF_UP)
      .toPlainString()

  companion object {
    private const val USD_DISPLAY_SCALE = 6

    fun of(value: Long): Nanodollars = Nanodollars(value)
  }
}
