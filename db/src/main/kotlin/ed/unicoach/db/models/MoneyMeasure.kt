package ed.unicoach.db.models

/**
 * The unit a [MoneyMeasure] value is expressed in: whole US dollars per
 * academic year, or a 0-1 share of the measured population. Carried on the
 * measure so dollars and shares are distinguishable at the type, never by
 * guessing from a value's magnitude.
 *
 * There is no COUNT: a headcount is not money, which is exactly why
 * `cohort_population_counts` exists (RFC 162). A number of people is a row in
 * that table, with a [CohortPopulation] naming who was counted.
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

  /**
   * Average Pell grant per Pell recipient (IPEDS UPGRNTA). The Scorecard
   * publishes no such figure.
   *
   * The first of the IPEDS SFA measures (RFC 162), whose shared naming rule is
   * stated once here: a `_share` is the source's own published percentage
   * expressed as a 0-1 share; an `_average_award` / `_amount` is the source's
   * own published average PER RECIPIENT (IPEDS `_A` = `_T / _N`), which is why
   * the aid scope on the row is the cohort the row is drawn from and the
   * recipient restriction lives in the measure's own name.
   */
  PELL_AVERAGE_AWARD("pell_average_award", MeasureUnit.USD_PER_YEAR),

  /** Share of the cohort awarded federal grant aid (IPEDS FGRNT_P). */
  FEDERAL_GRANT_SHARE("federal_grant_share", MeasureUnit.SHARE),

  /** Average federal grant per recipient (IPEDS FGRNT_A). */
  FEDERAL_GRANT_AVERAGE_AWARD("federal_grant_average_award", MeasureUnit.USD_PER_YEAR),

  /** Share of the cohort awarded state/local grant aid (IPEDS SGRNT_P). */
  STATE_LOCAL_GRANT_SHARE("state_local_grant_share", MeasureUnit.SHARE),

  /** Average state/local grant per recipient (IPEDS SGRNT_A). */
  STATE_LOCAL_GRANT_AVERAGE_AWARD("state_local_grant_average_award", MeasureUnit.USD_PER_YEAR),

  /** Share of the cohort awarded institutional grant aid (IPEDS IGRNT_P). */
  INSTITUTIONAL_GRANT_SHARE("institutional_grant_share", MeasureUnit.SHARE),

  /** Average institutional grant per recipient (IPEDS IGRNT_A). */
  INSTITUTIONAL_GRANT_AVERAGE_AWARD("institutional_grant_average_award", MeasureUnit.USD_PER_YEAR),

  /** Share of the cohort taking any student loan (IPEDS LOAN_P: federal, institutional and private, PLUS excluded). */
  STUDENT_LOAN_SHARE("student_loan_share", MeasureUnit.SHARE),

  /** Average student loan per borrower (IPEDS LOAN_A: the same any-loan scope LOAN_P counts). */
  STUDENT_LOAN_AVERAGE_AMOUNT("student_loan_average_amount", MeasureUnit.USD_PER_YEAR),
  ;

  companion object {
    fun fromValue(value: String): MoneyMeasure? = entries.find { it.value == value }
  }
}
