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

    /**
     * The exact value of [amount] × 10^[scaleDigits], as nano-dollars. This is
     * the one place the "reject rather than round" money policy lives: readers
     * of a decimal money amount differ only in the scale that carries their
     * unit to nano-dollars (`$` → 9, `$`/MTok → 3), never in the policy itself.
     *
     * Throws [IllegalArgumentException] when [amount] is negative, or when the
     * scaled amount is not a whole number of nano-dollars (or overflows the
     * [Long] ledger unit) — an imprecise amount is never silently rounded. The
     * message names only what this function knows, the amount and the scale;
     * naming what the amount WAS — a config key, a field — is the caller's
     * concept, so callers wrap this to add that context.
     */
    fun fromExactDecimal(
      amount: BigDecimal,
      scaleDigits: Int,
    ): Nanodollars {
      require(amount.signum() >= 0) { "must be non-negative, got [$amount]" }
      return try {
        of(amount.movePointRight(scaleDigits).toBigIntegerExact().longValueExact())
      } catch (e: ArithmeticException) {
        throw IllegalArgumentException("[$amount] scaled by 10^[$scaleDigits] has no exact nano-dollar form", e)
      }
    }
  }
}
