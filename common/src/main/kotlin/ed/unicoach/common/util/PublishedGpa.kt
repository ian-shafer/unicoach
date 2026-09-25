package ed.unicoach.common.util

import java.math.BigDecimal

/**
 * A stored grade point average as a school prints one: `3.50`, `3.75`, `3.875`.
 *
 * IN `:common` FOR [UsDate]'s REASON -- "one copy per module is how the same
 * date comes to be written four ways". Two surfaces now say a published grade
 * point average bound aloud: the family cost report in `:public-web` and the
 * coach's tool payload in `:service`. They had already diverged -- a school's
 * published `3.50` reached the page as `3.50` and the coach as `3.500`, because
 * only one of them knew the rule.
 *
 * THE COLUMN'S SCALE IS NOT THE SCHOOL'S TYPOGRAPHY. `gpa_low`/`gpa_high` are
 * `NUMERIC(4,3)`, so a row a school published as "3.50" comes back as `3.500`
 * and would print a third decimal no school ever typed.
 *
 * NOTHING HERE ROUNDS. Trailing zeros are dropped and two decimals restored,
 * which changes no value -- `3.875` keeps all three of its digits, and `3.00`
 * does not become a bare `3`. A published figure is never re-scaled into a
 * different number to make it pretty.
 */
object PublishedGpa {
  /** How many decimals a grade point average is printed to at least, because that is how schools print one. */
  private const val MIN_DECIMALS = 2

  /** `3.500` -> `"3.50"`, `3.875` -> `"3.875"`, `3.000` -> `"3.00"`. */
  fun of(gpa: BigDecimal): String {
    val bare = gpa.stripTrailingZeros()
    return (if (bare.scale() < MIN_DECIMALS) bare.setScale(MIN_DECIMALS) else bare).toPlainString()
  }
}
