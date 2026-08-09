package ed.unicoach.common.money

import com.typesafe.config.Config
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

    /** USD → nano-dollars: 1e9 nano-dollars to the dollar. */
    private const val USD_SCALE_DIGITS = 9

    fun of(value: Long): Nanodollars = Nanodollars(value)

    /**
     * The largest amount a percentage-of-allowance can be computed for
     * (≈`$92.2M`). That arithmetic multiplies the SPEND by 100 on the branch
     * where the spend is still under the allowance, so bounding the allowance by
     * this is what keeps `spent × 100` inside a [Long].
     *
     * Every reader of a percentage-checked USD amount bounds against this one
     * value rather than re-deriving `Long.MAX_VALUE / 100`, so no two of them can
     * drift apart. The consumer of the bound — `Entitlement.usedPercent` in the
     * service's budget domain — restates the guard at the arithmetic itself and
     * is pinned to this ceiling by `BudgetConfigTest`; a change to that formula
     * has to change this constant with it.
     */
    val MAX_FOR_PERCENTAGE: Nanodollars = of(Long.MAX_VALUE / 100)

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

    /**
     * The exact USD [amount] as nano-dollars. The dollar scale lives here, so a
     * reader of a USD amount never restates it — the `$`/MTok reader and this one
     * differ in their unit alone, and each names it once.
     */
    fun fromExactUsd(amount: BigDecimal): Nanodollars = fromExactDecimal(amount, USD_SCALE_DIGITS)

    /**
     * Reads [key] from [config] as an exact USD decimal, converts it to
     * nano-dollars, and requires it at or under [MAX_FOR_PERCENTAGE] — the whole
     * of what a config-loaded, percentage-checked money amount has to satisfy,
     * in one place, so every such reader enforces the same thing.
     *
     * Reading the value's original text (not a lossy binary double) and every
     * check sit inside one catch, so EVERY way this can fail — text that is not a
     * decimal at all ([BigDecimal]'s own `NumberFormatException`, which names
     * neither), a negative or sub-nano-dollar amount, an over-ceiling one —
     * surfaces as an [IllegalArgumentException] naming [key], its raw text, and
     * the unit it was read as.
     *
     * An absent [key] is not this function's concern: [Config.getValue] throws
     * its own `ConfigException.Missing`, which already names the key.
     */
    fun fromConfigUsd(
      config: Config,
      key: String,
    ): Nanodollars {
      val raw = config.getValue(key).unwrapped().toString()
      return runCatching {
        val amount = fromExactUsd(BigDecimal(raw))
        require(amount.value <= MAX_FOR_PERCENTAGE.value) {
          "[${amount.toUsdString()}] is above the largest amount a usage percentage can be " +
            "computed for [${MAX_FOR_PERCENTAGE.toUsdString()}]"
        }
        amount
      }.getOrElse { throw IllegalArgumentException("[$key] = [$raw] (USD, no finer than a nano-dollar): [${it.message}]", it) }
    }
  }
}
