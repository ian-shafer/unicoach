package ed.unicoach.db.models

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * Household income band backing `money_profiles.income_band` (RFC 134), with
 * self-describing labels naming the Scorecard NPT4 brackets (RFC 133). This
 * enum owns the band -> band-price selection ([getNetPrice]) so the mapping has
 * exactly one home.
 */
enum class IncomeBand(
  val value: String,
  /**
   * The band's dollar range as a coach would say it aloud — the one home for
   * display copy (the chat-tool description, and `income_band_label` on the
   * wire, RFC 142). Phrased to read inside a sentence ("for families earning
   * $110,000 or more"), never as a spreadsheet label and never as the source's
   * own bucket name.
   */
  val bracket: String,
) {
  /** $0-$30,000 (Scorecard NPT41). */
  UNDER_30K("under_30k", "\$0 to \$30,000"),

  /** $30,001-$48,000 (Scorecard NPT42). */
  K30_TO_48K("30k_to_48k", "\$30,001 to \$48,000"),

  /** $48,001-$75,000 (Scorecard NPT43). */
  K48_TO_75K("48k_to_75k", "\$48,001 to \$75,000"),

  /** $75,001-$110,000 (Scorecard NPT44). */
  K75_TO_110K("75k_to_110k", "\$75,001 to \$110,000"),

  /** $110,001+ (Scorecard NPT45). */
  OVER_110K("over_110k", "\$110,000 or more"),
  ;

  /**
   * The PUBLISHED band digit, 1..5 -- the `N` in the Scorecard's `NPT4N` and
   * in IPEDS SFA's `NPIS4N`/`NPT4N` series, which are the same five
   * published cut-points under two publishers' names.
   *
   * One home for both halves of the SFA path: the staging loader builds the
   * variable NAMES it reads from it, and the canonical fill looks the staged
   * cells back up by it. Two copies of 1..5 could disagree by a stem, and the
   * fill would then read a column the loader never staged.
   *
   * An exhaustive `when` by NAME, never `entries` position: a reorder of this
   * enum must not be able to file a net price under the wrong bracket.
   */
  val bandDigit: Int
    get() =
      when (this) {
        UNDER_30K -> 1
        K30_TO_48K -> 2
        K48_TO_75K -> 3
        K75_TO_110K -> 4
        OVER_110K -> 5
      }

  /**
   * The average annual net price, in whole US dollars (USD), a family in this
   * band pays at the school this search result row ([match]) stands for. Null
   * when no band price is served for that bracket.
   *
   * A LOOKUP, not a selection: [CollegeMatch.netPriceUsdByBand] is keyed by
   * the band the figure is about, so there is no band -> slot mapping left for
   * this enum to get wrong. It used to index five parallel fields through a
   * hand-written `when`, which could compile with two arms transposed and ship
   * a real net price under the wrong dollar-range label -- precisely the harm
   * RFC 142 exists to prevent.
   *
   * The values reach [CollegeMatch] from `cohort_money_stats` at the canonical
   * band address (RFC 176), never from a `colleges` column.
   */
  fun getNetPrice(match: CollegeMatch): Int? = match.netPriceUsdByBand[this]

  companion object {
    fun fromValue(value: String): IncomeBand? = entries.find { it.value == value }
  }
}

/**
 * Writes the band pair — `income_band` (the machine code) and
 * `income_band_label` ([IncomeBand.bracket], the dollar range a coach says
 * aloud) — into the object being built (RFC 142).
 *
 * The pair has exactly one emitter on purpose: every model-facing surface that
 * names a band names it in dollars too, and no site can half-fire by writing
 * the code and forgetting the label. Whenever a band reaches the wire, it
 * reaches it through here.
 */
fun JsonObjectBuilder.putIncomeBand(band: IncomeBand) {
  put("income_band", band.value)
  put("income_band_label", band.bracket)
}
