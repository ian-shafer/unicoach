package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.CalendarYear
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyMeasure

/**
 * The pinned Scorecard FIELD-OF-STUDY file's money columns, and everything the
 * publisher says about each one that is NOT a fact about a single row (RFC 218).
 *
 * WHY THIS IS NOT A ROW IN `dictionary-variable-sources.csv`. That file is the
 * INSTITUTION money seam's pin, and `ScorecardDictionaryPinTest` asserts its key
 * set EQUALS `ScorecardInstitutionColumns.LOADED_VARIABLES` -- an equality, both
 * ways -- so a field-of-study variable cannot be added to it without failing a
 * test that is right to fail. Two further walls stand behind that one: every
 * `ScorecardInstitutionColumns.VintageGroup` member is an institution cohort, so
 * joining one would stamp `EARN_MDN_4YR` with a tuition year; and that file's
 * `Cohort` type parses `AcadYr <label>` or empty, while these cohorts are POOLED
 * two-year spans -- the one thing they must state is the one thing that column
 * cannot hold. RFC 195's six score-band columns hit the identical wall and were
 * deliberately kept out of that file, with their date in one named constant, a
 * release pin and a test asserting the two agree. This object is that shipped
 * shape, applied a second time.
 *
 * THE ROW STORES THE YEARS; THIS OBJECT STORES THE SENTENCE. The two academic
 * years a cohort pools are a fact about the ROW -- they are what the figure is
 * about -- so they are typed columns on `program_money_stats`. Everything else
 * in the publisher's cell (which calendar years earnings were measured in, and
 * that they are adjusted to 2024 dollars) is a fact about the VARIABLE,
 * identical on every one of the ~684,000 rows. Repeating it per row would be
 * storing one fact 684,000 times and inviting 684,000 versions of it.
 *
 * WHERE THE CELLS WERE READ. `CollegeScorecardDataDictionary.xlsx`
 * (727,278 bytes,
 * <https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx>,
 * read 2026-09-15), sheet `FieldOfStudy_Cohort_Map`, column
 * `FieldOfStudyMostRecent datafile` -- the column for the file we ingest.
 * Descriptions and publishers come from sheet `FieldOfStudy_Data_Dictionary`.
 * The workbook's `README` says "Released June 10, 2026" and its `ChangeLog`
 * dates the latest field-of-study cohort refresh to the same day, which is the
 * release in `db/seed/scorecard/PROVENANCE.json` (`06102026`). That is a DATE
 * MATCH and not a stamped filename, and this KDoc says so rather than claiming
 * more. `db/seed/scorecard/FETCH-NOTES.md` carries the same citation.
 *
 * THREE THINGS THE PUBLISHER DOES NOT STATE, recorded so no later slice invents
 * them: no dollar year for either debt column (the earnings cell names its
 * adjustment, the debt cells name none); no interest rate tied to this release
 * (the 10-year payment's method is described only in
 * `FieldOfStudyDataDocumentation.pdf`, whose own version line reads
 * "Version: June 2024", two years older than our snapshot -- so copy may say
 * "the publisher's own estimate of the monthly payment on a standard 10-year
 * plan" and may NOT name a rate); and that the earnings column and the debt
 * columns are DIFFERENT cohorts from DIFFERENT publishers, which is why a
 * figure from one is never averaged, combined or ratio'd with a figure from the
 * other.
 */
object ScorecardFieldOfStudyColumns {
  const val EARN_MDN_4YR = "EARN_MDN_4YR"
  const val DEBT_ALL_STGP_EVAL_MDN = "DEBT_ALL_STGP_EVAL_MDN"
  const val DEBT_ALL_STGP_EVAL_MDN10YRPAY = "DEBT_ALL_STGP_EVAL_MDN10YRPAY"

  /**
   * The Scorecard release these cohort cells were transcribed from.
   *
   * The publisher's own release id, in the publisher's own `MMDDYYYY` form:
   * `06102026` is 10 June 2026, not 6 October. It is compared as an opaque
   * STRING to `release` in `db/seed/scorecard/PROVENANCE.json` and is never
   * parsed as a date.
   *
   * `bin/fetch-scorecard` discovers the CURRENT release, so the seed directory
   * can move under this object without a line of Kotlin changing: the data would
   * be a later cohort while these years still said 2017-2018, which is a dated
   * claim about a cohort nobody re-read. `ScorecardFieldOfStudyPinTest` asserts
   * this equals that file's `release`. WHEN THAT TEST FAILS neither the fetch
   * nor this object is wrong -- they simply describe different releases. Re-read
   * the workbook's `FieldOfStudy_Cohort_Map` sheet for these three variables,
   * then update the years, this release and
   * `db/seed/scorecard/FETCH-NOTES.md` together.
   */
  const val COHORT_SNAPSHOT_RELEASE: String = "06102026"

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
   * One field-of-study money variable, and everything true of EVERY row that
   * carries it.
   *
   * [publisherCohortCell] is the publisher's own cell, VERBATIM and unedited:
   * it is the evidence for [cohortFirstYear] / [cohortLastYear]
   * and for [inflationBaseYear], and a paraphrase would be us restating a
   * source in our own words in the one place a reader goes to check us.
   */
  data class FieldOfStudyVariable(
    /** The publisher's own variable name, as it appears in the CSV header. */
    val sourceVariable: String,
    /** Which stored measure the cell becomes. */
    val measure: ProgramMoneyMeasure,
    /** Who published the figure, in the publisher's own word for itself. */
    val publisher: String,
    /** The FIRST academic year the cohort pools, as its start year. */
    val cohortFirstYear: AcademicYear,
    /** The LAST academic year the cohort pools, as its start year; never earlier than the first. */
    val cohortLastYear: AcademicYear,
    /** The publisher's cohort cell, verbatim. */
    val publisherCohortCell: String,
    /**
     * The calendar year whose dollars the figure is expressed in, where the
     * publisher STATES one -- and null where it does not.
     *
     * A named field and not prose inside [publisherCohortCell], because a
     * sentence that speaks an earnings figure without its dollar base is a
     * defect, and the reader that must not commit it needs a value it can read.
     * NULL on both debt columns is a finding, not an omission: the publisher
     * names no dollar year for debt anywhere in the workbook or the
     * documentation, so we say nothing about debt in today's dollars.
     *
     * A [CalendarYear] and never an [AcademicYear]: it names the dollars the
     * figure is expressed in, not a year of school, and the two are not
     * interchangeable in either direction.
     */
    val inflationBaseYear: CalendarYear?,
  ) {
    init {
      require(cohortLastYear >= cohortFirstYear) {
        "[$sourceVariable] pools [${cohortFirstYear.label}]..[${cohortLastYear.label}]: a cohort cannot end before it starts"
      }
    }
  }

  /**
   * Median earnings of graduates working and not enrolled four years after
   * completing, published by Treasury.
   *
   * The ONE place the 2024 dollar base is written down. Any reader speaking this
   * figure out loud reaches it from the row through `source_variable`; the
   * `COMMENT ON COLUMN program_money_stats.source_variable` names this object so
   * somebody reading the schema alone is sent here too.
   */
  val EARNINGS =
    FieldOfStudyVariable(
      sourceVariable = EARN_MDN_4YR,
      measure = ProgramMoneyMeasure.MEDIAN_EARNINGS_4YR_AFTER_ENTRY,
      publisher = "Treasury",
      cohortFirstYear = AcademicYear(2017),
      cohortLastYear = AcademicYear(2018),
      publisherCohortCell =
        "Treasury AY2017-2018, AY2018-2019 pooled earnings cohort measured in CY2022, CY2023, " +
          "inflation adjusted to 2024 dollars",
      inflationBaseYear = CalendarYear(2024),
    )

  /** Median Stafford and Grad PLUS loan debt disbursed at this institution, published by NSLDS. */
  val DEBT =
    FieldOfStudyVariable(
      sourceVariable = DEBT_ALL_STGP_EVAL_MDN,
      measure = ProgramMoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
      publisher = "NSLDS",
      cohortFirstYear = AcademicYear(2018),
      cohortLastYear = AcademicYear(2019),
      publisherCohortCell = "NSLDS pooled AY2018-19, AY2019-20 cohort",
      // The publisher states no adjustment and names no dollar year for debt.
      inflationBaseYear = null,
    )

  /**
   * The publisher's OWN median estimated monthly payment for that debt on a
   * standard ten-year plan. The same cohort cell as [DEBT], because it is the
   * same cohort -- and the only one of the three the publisher may state with
   * cents.
   */
  val DEBT_MONTHLY_PAYMENT =
    FieldOfStudyVariable(
      sourceVariable = DEBT_ALL_STGP_EVAL_MDN10YRPAY,
      measure = ProgramMoneyMeasure.MEDIAN_DEBT_10YR_MONTHLY_PAYMENT,
      publisher = "NSLDS",
      cohortFirstYear = AcademicYear(2018),
      cohortLastYear = AcademicYear(2019),
      publisherCohortCell = "NSLDS pooled AY2018-19, AY2019-20 cohort",
      inflationBaseYear = null,
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
    // A variable declared twice would silently lose one of the two cohorts, and
    // the derived map is where that loss would happen quietly.
    require(BY_VARIABLE.size == VARIABLES.size) {
      "a field-of-study variable is declared twice: ${VARIABLES.map { it.sourceVariable }}"
    }
  }

  /**
   * The registry entry for a stored `source_variable`, or null when the string
   * is not one this seam reads. The single lookup any reader of a
   * `program_money_stats` row uses to learn the cohort, the publisher and the
   * dollar base before saying the figure out loud.
   */
  fun ofOrNull(sourceVariable: String): FieldOfStudyVariable? = BY_VARIABLE[sourceVariable]
}
