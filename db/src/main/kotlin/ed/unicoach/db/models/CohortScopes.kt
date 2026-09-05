package ed.unicoach.db.models

/**
 * The structured population basis of a `cohort_money_stats` row (RFC 158, P4):
 * three columns, each TEXT + CHECK (house enum pattern). These are measure
 * attributes, not authored taxonomy, so no vocabulary table -- each enum
 * mirrors its column's CHECK list, pinned by test.
 */
enum class CohortPopulation(
  val value: String,
) {
  /** Title IV aid-receiving undergraduates (COSTT4_A / the NPT4 family). */
  TITLE_IV_AIDED_UNDERGRADUATES("title_iv_aided_undergraduates"),

  /** All undergraduates (PCTPELL). */
  UNDERGRADUATES("undergraduates"),

  /** Completers who borrowed federal loans (GRAD_DEBT_MDN). */
  FEDERAL_LOAN_BORROWING_COMPLETERS("federal_loan_borrowing_completers"),

  /** Students employed and not enrolled ten years after entry (MD_EARN_WNE_P10). */
  EMPLOYED_NOT_ENROLLED_10Y_AFTER_ENTRY("employed_not_enrolled_10y_after_entry"),
  ;

  companion object {
    fun fromValue(value: String): CohortPopulation? = entries.find { it.value == value }
  }
}

/**
 * Whose tuition rate the cohort was built on. `in_state_rate_paying` is the
 * RFC 157 fact as stored data: at a public school COSTT4_A and the NPT4 family
 * describe students paying the in-state rate, with no out-of-state
 * counterpart published anywhere.
 */
enum class CohortResidencyScope(
  val value: String,
) {
  IN_STATE_RATE_PAYING("in_state_rate_paying"),
  ALL("all"),
  ;

  companion object {
    fun fromValue(value: String): CohortResidencyScope? = entries.find { it.value == value }
  }
}

/** Which aid relationship bounds the cohort. */
enum class CohortAidScope(
  val value: String,
) {
  FEDERAL_AID_RECEIVING("federal_aid_receiving"),
  FEDERAL_LOAN_BORROWING("federal_loan_borrowing"),
  ALL("all"),
  ;

  companion object {
    fun fromValue(value: String): CohortAidScope? = entries.find { it.value == value }
  }
}
