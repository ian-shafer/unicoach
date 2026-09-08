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
  ;

  /**
   * [published] as the store holds it.
   *
   * Every publisher in the corpus states a share as a percent (IPEDS
   * `UPGRNTP = 18`, the CDS's "87.5") and money as whole dollars, while the
   * store holds a 0-1 share. The CONVERSION is exposed, not the factor: a bare
   * multiplier hands a caller the chance to apply it, forget it, or apply it to
   * the wrong unit, which is the same 100x hazard two copies of the constant
   * had. Exhaustive, so a third unit is a compile error here.
   */
  fun storedValueOf(published: Double): Double =
    when (this) {
      USD_PER_YEAR -> published
      SHARE -> published * PERCENT_TO_STORED_SHARE
    }

  private companion object {
    /** The published percent -> the stored 0-1 share; one home for the literal. */
    const val PERCENT_TO_STORED_SHARE = 0.01
  }
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

  /**
   * Average need-based scholarship and grant award per recipient, as the
   * school reports it in its own Common Data Set (H2 line k, RFC 170).
   * Per-recipient, so the row's aid scope is the recipients it is averaged
   * over -- the naming rule stated at [PELL_AVERAGE_AWARD].
   */
  AVG_NEED_BASED_GRANT("avg_need_based_grant", MeasureUnit.USD_PER_YEAR),

  /**
   * The average SHARE of assessed financial need that was met, over the
   * freshmen awarded need-based aid (CDS H2 line i, RFC 170). Stored as a 0-1
   * share like every other `_share`, though the CDS publishes "87.5%": one
   * rule for shares, not one per publisher.
   *
   * It is emphatically NOT "does this school meet full need" -- no source
   * publishes that, and D6 refuses to store a derived boolean. The read layer
   * answers that question from this average and the fully-met headcount over
   * its denominator, both cited, both naming their cohort.
   */
  AVG_NEED_MET_SHARE("avg_need_met_share", MeasureUnit.SHARE),

  /**
   * Average cumulative principal borrowed, over the graduating class members
   * who took a loan of ANY kind -- federal, institutional, state or private
   * (CDS H.511, RFC 175). Its denominator is the school's own H.501 borrower
   * count, so the row carries [CohortAidScope.LOAN_RECEIVING].
   *
   * The shared naming rule for the five borrowing measures is stated once
   * here. Loan type is part of the MEASURE, not an axis (D1): the grant-mix
   * precedent, applied to borrowing. Each is an average PER BORROWER OF ITS
   * OWN LOAN TYPE, so its aid scope is that type's receiving scope and never
   * `all`. `debt` is the CUMULATIVE principal a student had borrowed by
   * graduation -- not the annual [STUDENT_LOAN_AVERAGE_AMOUNT], and not the
   * Scorecard's [MEDIAN_DEBT_AT_COMPLETION] (a median, federal only, over
   * completers rather than a graduating class). [MeasureUnit.USD_PER_YEAR] is
   * nonetheless the unit of all five, as it already is of
   * [MEDIAN_DEBT_AT_COMPLETION]: that enum distinguishes DOLLARS from shares
   * and names no period, so it reads "whole dollars" here. Changing it is not a
   * rename -- `BorrowingDao.wholeDollars` refuses any borrowing average stored
   * under another unit, so the "fix" is a production corrupt-value fault.
   * Two loan types are never summed: this any-loan figure is always the school's own H.511, never our
   * addition of the other four (D3).
   */
  ANY_LOAN_DEBT_AVERAGE("any_loan_debt_average", MeasureUnit.USD_PER_YEAR),

  /**
   * Average cumulative federal loan principal borrowed, over the graduating
   * class members who borrowed federally (CDS H.512, denominator H.502, RFC
   * 175). Carries [CohortAidScope.FEDERAL_LOAN_BORROWING] -- the same
   * denominator the Scorecard's completer debt is drawn over, a different
   * measure over a different cohort (D4).
   */
  FEDERAL_LOAN_DEBT_AVERAGE("federal_loan_debt_average", MeasureUnit.USD_PER_YEAR),

  /**
   * Average cumulative institutional loan principal borrowed, over the
   * graduating class members who took an institutional loan (CDS H.513,
   * denominator H.503, RFC 175). Carries
   * [CohortAidScope.INSTITUTIONAL_LOAN_BORROWING].
   */
  INSTITUTIONAL_LOAN_DEBT_AVERAGE("institutional_loan_debt_average", MeasureUnit.USD_PER_YEAR),

  /**
   * Average cumulative state loan principal borrowed, over the graduating
   * class members who took a state loan (CDS H.514, denominator H.504, RFC
   * 175). Carries [CohortAidScope.STATE_LOAN_BORROWING].
   */
  STATE_LOAN_DEBT_AVERAGE("state_loan_debt_average", MeasureUnit.USD_PER_YEAR),

  /**
   * Average cumulative private loan principal borrowed, over the graduating
   * class members who took a private loan (CDS H.515, denominator H.505, RFC
   * 175). Carries [CohortAidScope.PRIVATE_LOAN_BORROWING]. This is the half of
   * the debt picture the Scorecard's federal-only median cannot see.
   */
  PRIVATE_LOAN_DEBT_AVERAGE("private_loan_debt_average", MeasureUnit.USD_PER_YEAR),
  ;

  companion object {
    fun fromValue(value: String): MoneyMeasure? = entries.find { it.value == value }
  }
}
