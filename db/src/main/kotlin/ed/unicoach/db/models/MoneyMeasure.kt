package ed.unicoach.db.models

/**
 * The unit a [MoneyMeasure] value is expressed in: whole US dollars per
 * academic year, or a 0-1 share of the measured population. Carried on the
 * measure so dollars and shares are distinguishable at the type, never by
 * guessing from a value's magnitude.
 */
enum class MeasureUnit {
  USD_PER_YEAR,
  SHARE,
}

/**
 * What a `cohort_money_stats` row measures (RFC 158, D2): a number about a
 * population, never a price anyone is quoted. Mirrors the
 * `cohort_money_stats_measure_check` CHECK list -- a measure attribute (house
 * enum pattern), not authored taxonomy, so no vocabulary table.
 */
enum class MoneyMeasure(
  val value: String,
  /** The unit the measure's value is expressed in. */
  val unit: MeasureUnit,
  /** TRUE for the one measure the NPT4 band series splits by household income. */
  val bandable: Boolean = false,
) {
  AVG_NET_PRICE("avg_net_price", MeasureUnit.USD_PER_YEAR, bandable = true),
  PUBLISHED_COST_BLEND("published_cost_blend", MeasureUnit.USD_PER_YEAR),
  PELL_SHARE("pell_share", MeasureUnit.SHARE),
  MEDIAN_DEBT_AT_COMPLETION("median_debt_at_completion", MeasureUnit.USD_PER_YEAR),
  MEDIAN_EARNINGS_10Y("median_earnings_10y", MeasureUnit.USD_PER_YEAR),
  ;

  companion object {
    fun fromValue(value: String): MoneyMeasure? = entries.find { it.value == value }
  }
}
