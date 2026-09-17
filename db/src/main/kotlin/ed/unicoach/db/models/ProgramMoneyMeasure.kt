package ed.unicoach.db.models

/**
 * What a `program_money_stats` row measures (RFC 218): one of the three College
 * Scorecard field-of-study figures, about a PROGRAM rather than about an
 * institution.
 *
 * Mirrors the `program_money_stats_measure_check` CHECK list -- the house rule
 * for an own enumeration is TEXT + CHECK in the schema plus exactly ONE Kotlin
 * enum with a `fromValue` companion, and a test pins the two by set equality in
 * both directions.
 *
 * Separate from [MoneyMeasure] on purpose, and not a widening of it. That enum
 * names the measures of `cohort_money_stats`, whose rows are institution-level
 * and carry a population, a residency scope, an aid scope and an income band;
 * these three are program-level and carry none of those. Two of the names look
 * alike -- `median_debt_at_completion` exists in both lists -- and that is the
 * point: the same publisher concept measured at a different grain is a
 * different row in a different table, never the same row widened.
 *
 * Every member is whole US dollars, which is why the column can be
 * `amount_usd INTEGER` and needs no per-measure unit: there is no share here to
 * mix with the dollars (contrast [MeasureUnit] on [MoneyMeasure]).
 *
 * NOTHING HERE IS A RATIO, AND NOTHING MAY BE DERIVED FROM TWO OF THEM (brief
 * 0011 D27). The earnings figure and the two debt figures are different
 * cohorts, different publishers and different dollar bases, so a
 * debt-to-earnings ratio over them is a figure nobody published. The ten-year
 * monthly payment is a stored MEASURE rather than a derivation because the
 * PUBLISHER computed it.
 */
enum class ProgramMoneyMeasure(
  val value: String,
) {
  /** Treasury median earnings four years after entry (`EARN_MDN_4YR`), in the publisher's stated dollar base. */
  MEDIAN_EARNINGS_4YR_AFTER_ENTRY("median_earnings_4yr_after_entry"),

  /** NSLDS median Stafford and Grad PLUS debt of completers (`DEBT_ALL_STGP_EVAL_MDN`). */
  MEDIAN_DEBT_AT_COMPLETION("median_debt_at_completion"),

  /**
   * The publisher's OWN estimate of the monthly payment on a standard ten-year
   * plan (`DEBT_ALL_STGP_EVAL_MDN10YRPAY`). Stored because the publisher
   * computed it, never because we could compute one: a payment derived here
   * from a debt figure would be a stored derived figure.
   *
   * The only one of the three the publisher may state with cents. They are
   * discarded, half up, in ONE place (`ProgramMoneyLoader`).
   */
  MEDIAN_DEBT_10YR_MONTHLY_PAYMENT("median_debt_10yr_monthly_payment"),
  ;

  companion object {
    /** The stored slug back to the member, or null -- the house `fromValue` shape. */
    fun fromValue(value: String): ProgramMoneyMeasure? = entries.find { it.value == value }
  }
}
