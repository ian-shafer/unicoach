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

  /**
   * CDS H2 **line d**: first-time full-time freshmen "in line c who were
   * awarded any financial aid" -- of those determined to have need (line c),
   * the ones who got something (RFC 170).
   *
   * NOT "the freshmen with need": that is line c, which this seed does not
   * fetch. The distinction is the whole reason this member is named after the
   * line's own words -- line d is the denominator of line h (need fully met)
   * and of NOTHING else. Lines i and k are reported against line e, which is
   * [FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_NEED_BASED_GRANT].
   *
   * The freshmen block, not the full-time-undergraduate or
   * less-than-full-time one: the CDS repeats the same thirteen lines for three
   * cohorts and labels none of them, and this is the block the merit-aid seed
   * already reads.
   */
  FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID("first_time_full_time_freshmen_awarded_any_aid"),

  /**
   * CDS H2 **line e**: freshmen "in line d who were awarded any need-based
   * scholarship or grant aid" (RFC 170).
   *
   * A SUBSET of line d, and the population the two H2 averages are actually
   * reported over: line i is "the percentage of need that was met of students
   * who were awarded any need-based aid" and line k is "average need-based
   * scholarship and grant award of those in line e". Filing either of them
   * under line d would state an average over a larger population than the one
   * the school measured.
   */
  FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_NEED_BASED_GRANT("first_time_full_time_freshmen_awarded_need_based_grant"),

  /**
   * CDS H2 **line h**: freshmen "in line d whose need was fully met" --
   * excluding any aid above the need the school assessed (RFC 170).
   *
   * Its denominator is [FIRST_TIME_FULL_TIME_FRESHMEN_AWARDED_ANY_AID] (line d)
   * and no other member here. The read enforces exactly that pairing: a
   * fully-met count larger than the line-d count it is reported against is a
   * corrupt stored value, not a share to serve, because a school cannot have
   * met the full need of more students than it aided at all.
   */
  FIRST_TIME_FULL_TIME_FRESHMEN_NEED_FULLY_MET("first_time_full_time_freshmen_need_fully_met"),

  /**
   * CDS **H.401**: the students who were awarded a bachelor's degree by this
   * school in the named year -- the graduating class every H4/H5 borrowing
   * figure is reported over (RFC 175).
   *
   * It is the DENOMINATOR of the derived borrowing share, and of nothing else
   * here. Not "undergraduates" and not an entering cohort: the borrowing
   * figures are about people who finished, in one named year, which is why the
   * read layer says the year out loud.
   */
  GRADUATING_CLASS("graduating_class"),

  /**
   * CDS **H.501**: members of the graduating class who borrowed a loan of ANY
   * kind -- federal, institutional, state or private (RFC 175).
   *
   * The shared rule for the five borrower cohorts is stated once here. The
   * loan type rides in the SLUG because `cohort_population_counts`' natural
   * key is (college, population, residency basis, arrangement, vintage) with
   * no measure column, so five borrower counts for one school-year would
   * collide under one slug (D1). This any-loan count is always the school's
   * own H.501 and never our sum of the four typed counts (D3); the four are
   * not disjoint, so summing them would double-count every student who
   * borrowed twice.
   */
  GRADUATING_CLASS_BORROWERS_ANY_LOAN("graduating_class_borrowers_any_loan"),

  /** CDS **H.502**: members of the graduating class who borrowed federal loans (RFC 175). */
  GRADUATING_CLASS_BORROWERS_FEDERAL_LOAN("graduating_class_borrowers_federal_loan"),

  /** CDS **H.503**: members of the graduating class who took an institutional loan (RFC 175). */
  GRADUATING_CLASS_BORROWERS_INSTITUTIONAL_LOAN("graduating_class_borrowers_institutional_loan"),

  /** CDS **H.504**: members of the graduating class who took a state loan (RFC 175). */
  GRADUATING_CLASS_BORROWERS_STATE_LOAN("graduating_class_borrowers_state_loan"),

  /** CDS **H.505**: members of the graduating class who took a private loan (RFC 175). */
  GRADUATING_CLASS_BORROWERS_PRIVATE_LOAN("graduating_class_borrowers_private_loan"),
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

  /**
   * Awarded need-based scholarship or grant aid (CDS H2 **line e**, RFC 170):
   * the denominator both CDS need figures are published against. Line d, one
   * line above it, is a WIDER group -- the freshmen awarded any financial aid
   * at all -- so this scope is line e alone and never the pair. Neither
   * [ALL] nor any federal scope -- "the percentage of need that was met" is an
   * average over the students who got need-based aid, and filing it under
   * [ALL] would read as a claim about every freshman.
   */
  NEED_BASED_AID_RECEIVING("need_based_aid_receiving"),

  /**
   * Took an institutional loan by graduation (CDS **H.503**, the denominator
   * of [MoneyMeasure.INSTITUTIONAL_LOAN_DEBT_AVERAGE], RFC 175).
   *
   * The rule for the three new borrowing scopes is the DENOMINATOR rule again:
   * an average cumulative principal is an average over the borrowers OF THAT
   * LOAN TYPE, so it carries that type's own receiving scope. The other two of
   * the five already exist and mean exactly the right set --
   * [LOAN_RECEIVING] is H.501's "borrowed a loan of any kind" and
   * [FEDERAL_LOAN_BORROWING] is H.502's "borrowed federal loans" -- so they
   * are reused rather than twinned under a publisher-shaped name (D4).
   */
  INSTITUTIONAL_LOAN_BORROWING("institutional_loan_borrowing"),

  /** Took a state loan by graduation (CDS **H.504**, RFC 175); the [INSTITUTIONAL_LOAN_BORROWING] rule. */
  STATE_LOAN_BORROWING("state_loan_borrowing"),

  /** Took a private loan by graduation (CDS **H.505**, RFC 175); the [INSTITUTIONAL_LOAN_BORROWING] rule. */
  PRIVATE_LOAN_BORROWING("private_loan_borrowing"),
  ;

  companion object {
    fun fromValue(value: String): CohortAidScope? = entries.find { it.value == value }
  }
}
