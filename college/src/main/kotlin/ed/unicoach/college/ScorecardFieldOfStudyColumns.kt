package ed.unicoach.college

import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.net.URI

/**
 * The Scorecard FIELD-OF-STUDY file's money columns, and what is true of each
 * one on every row that carries it (RFC 218): its measure, its publisher and the
 * kind of number it is.
 *
 * THE ROW CARRIES EVERY PERIOD. The two academic years a cohort pools and the
 * calendar year of the figure's dollars are columns on `program_money_stats`,
 * stamped at ingest from the fetched data dictionary (`ProgramMoneyLoader`, RFC
 * 274), and every reader takes them from the row. Descriptions and publishers
 * come from sheet `FieldOfStudy_Data_Dictionary` of
 * `CollegeScorecardDataDictionary.xlsx`.
 *
 * THREE THINGS THE PUBLISHER DOES NOT STATE, recorded so no later slice invents
 * them: no dollar year for either debt column (the earnings cell names its
 * adjustment, the debt cells name none); no interest rate tied to the release
 * (the 10-year payment's method is described only in
 * `FieldOfStudyDataDocumentation.pdf` -- so copy may say "the publisher's own
 * estimate of the monthly payment on a standard 10-year plan" and may NOT name
 * a rate); and that the earnings column and the debt columns are DIFFERENT
 * cohorts from DIFFERENT publishers, which is why a figure from one is never
 * averaged, combined or ratio'd with a figure from the other.
 */
object ScorecardFieldOfStudyColumns {
  const val EARN_MDN_4YR = "EARN_MDN_4YR"
  const val DEBT_ALL_STGP_EVAL_MDN = "DEBT_ALL_STGP_EVAL_MDN"
  const val DEBT_ALL_STGP_EVAL_MDN10YRPAY = "DEBT_ALL_STGP_EVAL_MDN10YRPAY"

  /**
   * WHOSE file this registry describes, as `program_money_stats.source` spells
   * it -- the one value a row must claim before anything here may attribute it.
   *
   * Here and not only in the loader, because it is a fact about the REGISTRY
   * and both sides need it: the ingest stamps it onto every row it writes, and
   * the read audits a row's own `source` against it before resolving a
   * `source_variable` through [ofOrNull]. The `money_source` domain admits four
   * publishers and no CHECK on `program_money_stats` narrows it, so "this row
   * is one of ours to attribute" is a question with an answer, and the answer
   * is stated once.
   */
  val SOURCE: MoneySource = MoneySource.SCORECARD

  /**
   * The College Scorecard's own data page, where a family can look up any
   * program's figures themselves (RFC 251, brief 0011 D34).
   *
   * A typed constant, not a stored row: it changes only when a human edits it,
   * so it is a fact of the code (brief 0011 D46). Checked to answer 200 on
   * 2026-10-11. The Family Cost Report links to it once, and only when it prints
   * a figure by field of study.
   *
   * Stated twice, on purpose: `bin/fetch-scorecard` holds the same URL as
   * `DATA_PAGE_URL`, the page each release is discovered from. A Python script
   * and the JVM cannot read each other's constant, and carrying one URL through
   * every committed environment file costs more than a disagreement would: a
   * stale link on the report, or a fetch that refuses loudly. No money moves.
   * Edit both.
   */
  val DATA_PAGE: URI = URI.create("https://collegescorecard.ed.gov/data/")

  /**
   * Who published a field-of-study figure: the agency that holds the records
   * the figure is computed from.
   *
   * An enumeration rather than free text, so the words a family is told for
   * each publisher are decided at one exhaustive `when` and a new publisher
   * cannot reach a page unnamed.
   */
  enum class FieldOfStudyPublisher(
    /** The publisher's own word for itself, as the data dictionary names it ("NSLDS") -- never spoken to a family. */
    val word: String,
  ) {
    TREASURY("Treasury"),
    NSLDS("NSLDS"),
  }

  /**
   * One field-of-study money variable, and everything true of EVERY row that
   * carries it.
   */
  data class FieldOfStudyVariable(
    /** The publisher's own variable name, as it appears in the CSV header. */
    val sourceVariable: String,
    /** Which stored measure the cell becomes. */
    val measure: ProgramMoneyMeasure,
    /** Who published the figure. */
    val publishedBy: FieldOfStudyPublisher,
    /**
     * What KIND of number the figure is (RFC 191). Every field-of-study figure
     * is computed from federal loan or tax records, so it covers only students
     * who received federal aid -- [AssuranceTier.ADMINISTRATIVE_RECORD], whose
     * own description names NSLDS and Treasury.
     */
    val assuranceTier: AssuranceTier,
  ) {
    /** Who published the figure, in the publisher's own word for itself. */
    val publisher: String get() = publishedBy.word
  }

  /**
   * Median earnings of graduates working and not enrolled four years after
   * completing, published by Treasury.
   */
  val EARNINGS =
    FieldOfStudyVariable(
      sourceVariable = EARN_MDN_4YR,
      measure = ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY,
      publishedBy = FieldOfStudyPublisher.TREASURY,
      assuranceTier = AssuranceTier.ADMINISTRATIVE_RECORD,
    )

  /** Median Stafford and Grad PLUS loan debt disbursed at this institution, published by NSLDS. */
  val DEBT =
    FieldOfStudyVariable(
      sourceVariable = DEBT_ALL_STGP_EVAL_MDN,
      measure = ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
      publishedBy = FieldOfStudyPublisher.NSLDS,
      assuranceTier = AssuranceTier.ADMINISTRATIVE_RECORD,
    )

  /**
   * The publisher's OWN median estimated monthly payment for that debt on a
   * standard ten-year plan: the same cohort as [DEBT], and the only one of the
   * three the publisher may state with cents.
   */
  val DEBT_MONTHLY_PAYMENT =
    FieldOfStudyVariable(
      sourceVariable = DEBT_ALL_STGP_EVAL_MDN10YRPAY,
      measure = ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT,
      publishedBy = FieldOfStudyPublisher.NSLDS,
      assuranceTier = AssuranceTier.ADMINISTRATIVE_RECORD,
    )

  /**
   * Every money variable this seam reads, in the order the loader writes them.
   * The ONE list; the two derived views below come from it rather than from a
   * second hand-kept copy.
   */
  val VARIABLES: List<FieldOfStudyVariable> = listOf(EARNINGS, DEBT, DEBT_MONTHLY_PAYMENT)

  /** The column names, for the loader's REQUIRED-column assertion. */
  val LOADED_VARIABLES: Set<String> = VARIABLES.map { it.sourceVariable }.toSet()

  private val BY_VARIABLE: Map<String, FieldOfStudyVariable> = VARIABLES.associateBy { it.sourceVariable }

  init {
    // A variable declared twice would silently lose one of the two entries, and
    // the derived map is where that loss would happen quietly.
    require(BY_VARIABLE.size == VARIABLES.size) {
      "a field-of-study variable is declared twice: ${VARIABLES.map { it.sourceVariable }}"
    }
  }

  /**
   * The registry entry for a stored `source_variable`, or null when the string
   * is not one this seam reads. The single lookup any reader of a
   * `program_money_stats` row uses to learn who published the figure before
   * saying it out loud.
   */
  fun ofOrNull(sourceVariable: String): FieldOfStudyVariable? = BY_VARIABLE[sourceVariable]
}
