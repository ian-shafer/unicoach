package ed.unicoach.common.money

import java.util.Locale

/**
 * The one spoken form of a whole-US-dollar figure exactly as a source publishes
 * it: `$18,400`. The CDS and the Scorecard report no cents, so nothing here
 * rounds and nothing divides -- this is display only.
 *
 * It lives beside [Nanodollars] because `common/money` is this repo's home for
 * money display: a renderer that invents its own `"$%,d"` is a second money
 * format waiting to disagree with this one. [Locale.US] is pinned so the
 * grouping a coach reads is the grouping the payload number carries, whatever
 * the JVM's ambient default locale happens to be.
 */
object WholeDollars {
  /** `18400` -> `"$18,400"`. */
  fun spoken(dollars: Int): String = "\$%,d".format(Locale.US, dollars)
}
