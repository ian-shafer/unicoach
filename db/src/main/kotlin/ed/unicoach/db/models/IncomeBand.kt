package ed.unicoach.db.models

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * Household income band backing `money_profiles.income_band` (RFC 134), with
 * self-describing labels naming the Scorecard NPT4 brackets (RFC 133). This
 * enum owns the band -> `net_price_per_year_income_qN_usd` selection ([netPriceFor]) so the mapping
 * has exactly one home.
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
   * The average annual net price, in whole US dollars (USD), a family in this
   * band pays at [college]: the matching `net_price_per_year_income_qN_usd` column (RFC 133). Null
   * when the college did not report that bracket.
   */
  fun netPriceFor(college: College): Int? =
    netPriceOf(
      college.netPricePerYearIncomeQ1Usd,
      college.netPricePerYearIncomeQ2Usd,
      college.netPricePerYearIncomeQ3Usd,
      college.netPricePerYearIncomeQ4Usd,
      college.netPricePerYearIncomeQ5Usd,
    )

  /**
   * The same band -> column selection for a search result row ([match]), so
   * search and the cost tools read the mapping from this one home rather than
   * each hand-indexing the five `net_price_per_year_income_qN_usd` fields.
   */
  fun netPriceFor(match: CollegeMatch): Int? =
    netPriceOf(
      match.netPricePerYearIncomeQ1Usd,
      match.netPricePerYearIncomeQ2Usd,
      match.netPricePerYearIncomeQ3Usd,
      match.netPricePerYearIncomeQ4Usd,
      match.netPricePerYearIncomeQ5Usd,
    )

  /**
   * The band -> bracket-column selection itself, written ONCE over the five
   * values rather than once per row type. `College` and `CollegeMatch` declare
   * the same five identically-named, identically-typed properties, so two
   * parallel `when` blocks would compile with two arms transposed and ship a
   * real net price under the wrong dollar-range label — precisely the harm
   * RFC 142 exists to prevent. One mapping, two thin adapters.
   */
  private fun netPriceOf(
    q1: Int?,
    q2: Int?,
    q3: Int?,
    q4: Int?,
    q5: Int?,
  ): Int? =
    when (this) {
      UNDER_30K -> q1
      K30_TO_48K -> q2
      K48_TO_75K -> q3
      K75_TO_110K -> q4
      OVER_110K -> q5
    }

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
