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

  /**
   * The full-time first-time degree/certificate-seeking financial-aid cohort
   * (IPEDS SCUGFFN): who the SFA aid-mix figures, the grant-aided net price
   * and the fall residency headcounts are all about. Distinct from
   * [TITLE_IV_AIDED_UNDERGRADUATES], which is the Title IV-aided subset the
   * NPT4 band series describes -- the bands do not roll up to the overall
   * figure, and saying so is the point of storing the basis.
   */
  FIRST_TIME_FULL_TIME_AID_COHORT("first_time_full_time_aid_cohort"),

  /**
   * Undergraduates awarded a Federal Pell grant (IPEDS UPGRNTN): who a Pell
   * recipient HEADCOUNT counts. A number of people is not money, so it is a
   * `cohort_population_counts` row with this population -- never a
   * [MoneyMeasure] with an invented "count" unit (RFC 162).
   */
  PELL_RECEIVING_UNDERGRADUATES("pell_receiving_undergraduates"),
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

  /**
   * Awarded grant or scholarship aid from federal, state/local or
   * institutional sources (RFC 162): the population IPEDS NPIST/NPGRN net
   * price describes. NOT the Title IV population [FEDERAL_AID_RECEIVING]
   * names -- different cohort, materially different number, and the two land
   * as two rows rather than one overwriting the other.
   */
  GRANT_AIDED("grant_aided"),

  /** Awarded a Federal Pell grant (RFC 162): the denominator of an average Pell award. */
  PELL_RECEIVING("pell_receiving"),

  /**
   * The receiving scopes the SFA aid mix needs (RFC 162). The rule is the
   * DENOMINATOR, applied symmetrically: a `_share` measure is a share OF THE
   * COHORT and so carries [ALL], while an `_average_award`/`_average_amount`
   * is an average OVER RECIPIENTS and so carries its own receiving scope --
   * exactly as [PELL_RECEIVING] already bounds the average Pell award. A
   * per-recipient average filed under [ALL] would read as "the cohort's
   * average", which is a materially smaller number the publisher never states.
   */
  FEDERAL_GRANT_RECEIVING("federal_grant_receiving"),

  /** Awarded state or local government grant aid (RFC 162); the [FEDERAL_GRANT_RECEIVING] rule. */
  STATE_LOCAL_GRANT_RECEIVING("state_local_grant_receiving"),

  /** Awarded institutional grant aid (RFC 162); the [FEDERAL_GRANT_RECEIVING] rule. */
  INSTITUTIONAL_GRANT_RECEIVING("institutional_grant_receiving"),

  /**
   * Borrowed a student loan of ANY kind -- federal, institutional or private
   * (IPEDS LOAN_A/LOAN_P), which is why this is not [FEDERAL_LOAN_BORROWING]:
   * the Scorecard's completer-debt figure counts federal borrowing only, and
   * the two must not share a scope slug. The [FEDERAL_GRANT_RECEIVING] rule.
   */
  LOAN_RECEIVING("loan_receiving"),
  ;

  companion object {
    fun fromValue(value: String): CohortAidScope? = entries.find { it.value == value }
  }
}
