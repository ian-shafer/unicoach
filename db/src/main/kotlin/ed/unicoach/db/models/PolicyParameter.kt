package ed.unicoach.db.models

/**
 * The closed vocabulary of `policy_parameters.parameter` (RFC 159): the
 * federal Pell Grant and Direct Loan policy figures the coach may state, one
 * entry per stored parameter name. The schema's
 * `policy_parameters_parameter_check` CHECK lists exactly these strings, and
 * this enum is their only Kotlin home ([IncomeBand]/[AnswerStatus] precedent).
 *
 * The house measure_qualifier_unit naming rule lives in the parameter name
 * itself: dollars end `_usd`, percent points end `_pct`, and [SAI_FLOOR] is a
 * dimensionless index (the Student Aid Index may be negative; its floor is
 * the seeded row's fact, never this comment's). Loan names spell out the
 * federal loan-limit grid: annual vs
 * aggregate, dependent vs independent vs grad, year level, and total vs the
 * subsidized cap inside that total.
 */
enum class PolicyParameter(
  val value: String,
) {
  PELL_MAX_AWARD_USD("pell_max_award_usd"),
  PELL_MIN_AWARD_USD("pell_min_award_usd"),
  PELL_MAX_AGI_DEPENDENT_SINGLE_PARENT_PCT("pell_max_agi_dependent_single_parent_pct"),
  PELL_MAX_AGI_DEPENDENT_NON_SINGLE_PARENT_PCT("pell_max_agi_dependent_non_single_parent_pct"),
  SAI_FLOOR("sai_floor"),
  DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_TOTAL_USD("direct_loan_annual_dependent_y1_total_usd"),
  DIRECT_LOAN_ANNUAL_DEPENDENT_Y1_SUBSIDIZED_USD("direct_loan_annual_dependent_y1_subsidized_usd"),
  DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_TOTAL_USD("direct_loan_annual_dependent_y2_total_usd"),
  DIRECT_LOAN_ANNUAL_DEPENDENT_Y2_SUBSIDIZED_USD("direct_loan_annual_dependent_y2_subsidized_usd"),
  DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_TOTAL_USD("direct_loan_annual_dependent_y3_plus_total_usd"),
  DIRECT_LOAN_ANNUAL_DEPENDENT_Y3_PLUS_SUBSIDIZED_USD("direct_loan_annual_dependent_y3_plus_subsidized_usd"),
  DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_TOTAL_USD("direct_loan_annual_independent_y1_total_usd"),
  DIRECT_LOAN_ANNUAL_INDEPENDENT_Y1_SUBSIDIZED_USD("direct_loan_annual_independent_y1_subsidized_usd"),
  DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_TOTAL_USD("direct_loan_annual_independent_y2_total_usd"),
  DIRECT_LOAN_ANNUAL_INDEPENDENT_Y2_SUBSIDIZED_USD("direct_loan_annual_independent_y2_subsidized_usd"),
  DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_TOTAL_USD("direct_loan_annual_independent_y3_plus_total_usd"),
  DIRECT_LOAN_ANNUAL_INDEPENDENT_Y3_PLUS_SUBSIDIZED_USD("direct_loan_annual_independent_y3_plus_subsidized_usd"),
  DIRECT_LOAN_ANNUAL_GRAD_UNSUBSIDIZED_USD("direct_loan_annual_grad_unsubsidized_usd"),
  DIRECT_LOAN_AGGREGATE_DEPENDENT_TOTAL_USD("direct_loan_aggregate_dependent_total_usd"),
  DIRECT_LOAN_AGGREGATE_DEPENDENT_SUBSIDIZED_USD("direct_loan_aggregate_dependent_subsidized_usd"),
  DIRECT_LOAN_AGGREGATE_INDEPENDENT_TOTAL_USD("direct_loan_aggregate_independent_total_usd"),
  DIRECT_LOAN_AGGREGATE_INDEPENDENT_SUBSIDIZED_USD("direct_loan_aggregate_independent_subsidized_usd"),
  DIRECT_LOAN_AGGREGATE_GRAD_TOTAL_USD("direct_loan_aggregate_grad_total_usd"),
  DIRECT_LOAN_AGGREGATE_GRAD_SUBSIDIZED_USD("direct_loan_aggregate_grad_subsidized_usd"),
  ;

  companion object {
    fun fromValue(value: String): PolicyParameter? = entries.find { it.value == value }
  }
}
