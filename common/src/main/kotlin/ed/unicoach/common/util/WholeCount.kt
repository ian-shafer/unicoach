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

  /**
   * A count as a sentence's words say it: zero through nine spelled out
   * (`4` -> `"four"`), and every other count as [spoken] -- the usual rule for
   * a small number in running prose, so a sentence built around a constant
   * reads the same as one typed by hand and still follows the constant when
   * it changes.
   */
  fun inWords(count: Int): String = SMALL_COUNT_WORDS.getOrNull(count) ?: spoken(count)

  private val SMALL_COUNT_WORDS =
    listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
}
