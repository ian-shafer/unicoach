package ed.unicoach.db.models

/**
 * Household income band backing `money_profiles.income_band` (RFC 134), with
 * self-describing labels naming the Scorecard NPT4 brackets (RFC 133). This
 * enum owns the band -> `net_price_qN` selection ([netPriceFor]) so the mapping
 * has exactly one home.
 */
enum class IncomeBand(
  val value: String,
  /** Human-readable dollar bracket — the one home for display copy (e.g. the chat-tool description). */
  val bracket: String,
) {
  /** $0-$30,000 (Scorecard NPT41). */
  UNDER_30K("under_30k", "\$0-30k"),

  /** $30,001-$48,000 (Scorecard NPT42). */
  K30_TO_48K("30k_to_48k", "\$30,001-48k"),

  /** $48,001-$75,000 (Scorecard NPT43). */
  K48_TO_75K("48k_to_75k", "\$48,001-75k"),

  /** $75,001-$110,000 (Scorecard NPT44). */
  K75_TO_110K("75k_to_110k", "\$75,001-110k"),

  /** $110,001+ (Scorecard NPT45). */
  OVER_110K("over_110k", "\$110k+"),
  ;

  /**
   * The average annual net price, in whole US dollars (USD), a family in this
   * band pays at [college]: the matching `net_price_qN` column (RFC 133). Null
   * when the college did not report that bracket.
   */
  fun netPriceFor(college: College): Int? =
    when (this) {
      UNDER_30K -> college.netPriceQ1
      K30_TO_48K -> college.netPriceQ2
      K48_TO_75K -> college.netPriceQ3
      K75_TO_110K -> college.netPriceQ4
      OVER_110K -> college.netPriceQ5
    }

  companion object {
    fun fromValue(value: String): IncomeBand? = entries.find { it.value == value }
  }
}
