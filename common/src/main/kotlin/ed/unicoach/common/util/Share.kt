package ed.unicoach.common.util

import java.util.Locale

/**
 * A part of a population, held as the PERCENT it is rendered as. The
 * ratio -> percent conversion, the one-decimal rounding rule and the spoken
 * form live here, on the [DataSize] precedent, so no caller writes `* 100` at
 * its own site and no reader has to learn from a field name whether a bare
 * `Double` holds a 0-1 fraction or a 0-100 percent. The repo runs both scales
 * today (`College.pctPell` is 0-1, the IPEDS disability figure is 0-100), and
 * a comment is the only thing that has kept them apart.
 *
 * The number and the sentence a coach reads aloud come from this one type, so
 * the payload figure and its spoken label cannot drift apart.
 */
@JvmInline
value class Share private constructor(
  val percent: Double,
) {
  /**
   * "5" rather than "5.0", and "5.2" as computed: the spoken form of the number
   * the payload carries.
   *
   * The exact `Double` compare is deliberate, not an oversight. [ofOrNull] is
   * the only constructor and it snaps every percent onto a `k / 10.0` grid,
   * whose integral members ARE exactly representable -- so
   * `percent == floor(percent)` is precisely "this share is a whole number of
   * percent". An epsilon compare would be the WRONG fix: it would speak a real
   * 5.04 as "5".
   */
  fun spoken(): String =
    if (percent == Math.floor(percent)) {
      "%.0f".format(Locale.US, percent)
    } else {
      "%.1f".format(Locale.US, percent)
    }

  companion object {
    /** A ratio is a percent times this; the conversion has one home rather than a literal per call site. */
    private const val PERCENT_PER_UNIT = 100.0

    /** One decimal place, expressed as the rounding grid the percent is snapped to. */
    private const val ROUNDING_STEPS_PER_PERCENT = 10.0

    /**
     * [part] as a percent of [whole], to one decimal place, or null when
     * [whole] is not a population -- a share of nobody is not a figure, and
     * this is the one place that rules on it.
     */
    fun ofOrNull(
      part: Int,
      whole: Int,
    ): Share? =
      if (whole > 0) {
        Share(Math.round(part * PERCENT_PER_UNIT * ROUNDING_STEPS_PER_PERCENT / whole) / ROUNDING_STEPS_PER_PERCENT)
      } else {
        null
      }
  }
}
