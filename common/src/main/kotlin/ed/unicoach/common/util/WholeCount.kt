package ed.unicoach.common.util

import java.util.Locale

/**
 * The one spoken form of a whole COUNT of people or things: `1,000`. Nothing
 * here rounds and nothing divides -- this is display only, for a figure a
 * source publishes as an integer (a graduating class, a borrower count).
 *
 * It lives beside [ed.unicoach.common.money.WholeDollars] and for its reason
 * (RFC 260 D-D): a renderer that types its own `"%,d"` is a second thousands
 * separator waiting to disagree with the first. [Locale.US] is pinned so the
 * grouping a family reads is the grouping the payload number carries,
 * whatever the JVM's ambient default locale happens to be.
 */
object WholeCount {
  /** `1000` -> `"1,000"`. */
  fun spoken(count: Int): String = "%,d".format(Locale.US, count)
}
