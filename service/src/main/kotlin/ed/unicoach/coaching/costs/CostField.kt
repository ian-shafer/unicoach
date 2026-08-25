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
  STICKER_COST_ATTENDANCE("sticker_cost_attendance"),
  TUITION_IN_STATE("tuition_in_state"),
  TUITION_OUT_STATE("tuition_out_state"),
  NET_PRICE("net_price"),
  MEDIAN_DEBT("median_debt"),
  MEDIAN_EARNINGS("median_earnings"),
}
