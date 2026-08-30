package ed.unicoach.coaching.costs

/**
 * The cost columns of the result (RFC 135): one entry per cost figure the
 * tool renders, each carrying its wire key. One vocabulary shared by
 * [CollegeCostChatTool]'s JSON keys and [CollegeCost.notReported], so the
 * domain traffics in the typed field while `data_availability` can never name
 * a field the tool does not render.
 */
enum class CostField(
  val wireName: String,
) {
  STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD("sticker_cost_of_attendance_per_year_usd"),
  TUITION_AND_FEES_IN_STATE_PER_YEAR_USD("tuition_and_fees_in_state_per_year_usd"),
  TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD("tuition_and_fees_out_of_state_per_year_usd"),

  // The one entry that is NOT a bare dollar figure: it keys an OBJECT
  // (amount_usd + basis + optional band), and a container carries no unit --
  // the same rule that leaves `net_price_by_income_band` unsuffixed. The
  // scalar inside it says its own unit.
  NET_PRICE("net_price"),
  MEDIAN_DEBT_AT_COMPLETION_USD("median_debt_at_completion_usd"),
  MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD("median_earnings_10y_after_entry_usd"),
}
