package ed.unicoach.college

import ed.unicoach.college.ScorecardDictionary.Dating
import ed.unicoach.college.ScorecardDictionary.ExpectedPeriod
import ed.unicoach.college.ScorecardDictionary.Refusal
import ed.unicoach.college.ScorecardDictionary.UnexpectedCohortShapeException
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.ProgramMoneyDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.StoredProgramCohort
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyDollars
import ed.unicoach.db.models.ProgramMoneyMeasure

/**
 * What the ingest refuses about a fetched Scorecard data dictionary before it
 * stamps a single row with a year from it (RFC 274 D7).
 *
 * Two halves, each listing EVERY offender rather than the first:
 *
 * - the FILE half: every column the fill dates has a dictionary row, and that
 *   row reads as the period kind the fill stamps, with the calendar year of its
 *   dollars stated exactly where the variable's measure carries one -- a
 *   dropped, renamed or re-worded variable is a located fault naming it, never
 *   a silent blank;
 * - the STORED half: no dated column moves to a period EARLIER than the newest
 *   one already stored for it -- a back-date is a re-dating nobody chose, and a
 *   newer release is the only direction a fetch is for. An empty table is no
 *   evidence and passes.
 *
 * Read once per fill, in the fill's own transaction, before its DELETE; and
 * once up front by the ingest, before the first phase commits, so a refusal
 * leaves every table as it was.
 */
internal object ScorecardYearGuard {
  /**
   * The year [dictionary] states for every dated money column, keyed by
   * column -- or a [DictionaryRefusesServedVariablesException] listing every
   * column it cannot date.
   */
  fun datedMoneyYears(dictionary: ScorecardDictionary): Map<String, AcademicYear> =
    readAll(ScorecardInstitutionColumns.DATED_MONEY_VARIABLES) { column -> dictionary.readAcademicYear(column) }

  /**
   * What [dictionary] states for every field-of-study money variable, ready to
   * stamp on its rows and keyed by variable -- or the same refusal.
   */
  fun fieldOfStudyCohorts(dictionary: ScorecardDictionary): Map<String, StampableCohort> {
    val measures = ScorecardFieldOfStudyColumns.VARIABLES.associate { it.sourceVariable to it.measure }
    return readAll(measures.keys) { variable -> stampableCohortOf(dictionary, variable, measures.getValue(variable)) }
  }

  /**
   * [variable]'s cohort, refused unless its cell states the calendar year of
   * the figure's dollars exactly where [measure] carries one: the row the fill
   * would stamp from it is one no [ProgramMoneyDollars] can hold, and this says
   * which variable and which cell before a row is written.
   */
  private fun stampableCohortOf(
    dictionary: ScorecardDictionary,
    variable: String,
    measure: ProgramMoneyMeasure,
  ): Dating<StampableCohort> =
    dictionary.readFieldOfStudyCohort(variable).flatMap { cohort ->
      val dollars = ProgramMoneyDollars.ofOrNull(measure, cohort.amountUsdYear)
      if (dollars != null) {
        Dating.Dated(StampableCohort(cohort.first, cohort.last, dollars))
      } else {
        Dating.Undatable(
          UnexpectedCohortShapeException(
            variable,
            cohort.cell,
            cohort,
            if (measure.statesAmountUsdYear) ExpectedPeriod.POOLED_WITH_AMOUNT_USD_YEAR else ExpectedPeriod.POOLED_WITHOUT_AMOUNT_USD_YEAR,
          ),
        )
      }
    }

  /**
   * Refuses a dated money column the dictionary dates EARLIER than the newest
   * year stored for it from the Scorecard.
   */
  fun requireNoMoneyBackdate(
    session: SqlSession,
    years: Map<String, AcademicYear>,
  ) {
    val stored = CanonicalMoneyDao.newestStoredYearBySourceVariable(session, MoneySource.SCORECARD).getOrThrow()
    val backdated =
      years.mapNotNull { (variable, year) ->
        stored[variable]
          ?.takeIf { newest -> year < newest }
          ?.let { newest -> Backdate(variable, stored = Backdate.Period.Year(newest), dictionary = Backdate.Period.Year(year)) }
      }
    if (backdated.isNotEmpty()) throw DictionaryBackdatesServedVariableException(backdated)
  }

  /**
   * Refuses a field-of-study variable the dictionary dates to a cohort EARLIER
   * than the newest one stored for it: ending earlier, or ending together and
   * starting earlier.
   */
  fun requireNoFieldOfStudyBackdate(
    session: SqlSession,
    cohorts: Map<String, StampableCohort>,
  ) {
    val stored =
      ProgramMoneyDao.newestStoredCohortBySourceVariable(session, ScorecardFieldOfStudyColumns.SOURCE).getOrThrow()
    val backdated =
      cohorts.mapNotNull { (variable, cohort) ->
        stored[variable]
          ?.takeIf { newest -> isEarlier(cohort, newest) }
          ?.let { newest ->
            Backdate(
              variable,
              stored = Backdate.Period.Span(newest.first, newest.last),
              dictionary = Backdate.Period.Span(cohort.first, cohort.last),
            )
          }
      }
    if (backdated.isNotEmpty()) throw DictionaryBackdatesServedVariableException(backdated)
  }

  private fun isEarlier(
    cohort: StampableCohort,
    newest: StoredProgramCohort,
  ): Boolean = cohort.last < newest.last || (cohort.last == newest.last && cohort.first < newest.first)

  private fun display(period: Backdate.Period): String =
    when (period) {
      is Backdate.Period.Year -> period.year.label
      is Backdate.Period.Span -> "${period.first.label}..${period.last.label}"
    }

  /** [read] over every one of [variables], collecting EVERY refusal before throwing one that lists them. */
  private fun <T> readAll(
    variables: Set<String>,
    read: (String) -> Dating<T>,
  ): Map<String, T> {
    val readings = variables.associateWith(read)
    val faults = readings.values.filterIsInstance<Dating.Undatable>().map { it.fault }
    if (faults.isNotEmpty()) throw DictionaryRefusesServedVariablesException(faults)
    return readings.mapValues { (_, reading) -> reading.orThrow() }
  }

  /** What a field-of-study row is stamped with: the pooled years, and the measure closed with its dollar year. */
  data class StampableCohort(
    val first: AcademicYear,
    val last: AcademicYear,
    val dollars: ProgramMoneyDollars,
  )

  /** One column the dictionary moves backwards: the period [stored] now, and the earlier one the [dictionary] states. */
  data class Backdate(
    val variable: String,
    val stored: Period,
    val dictionary: Period,
  ) {
    /** A money column is dated by one academic year, and a field-of-study variable by the span it pools. */
    sealed interface Period {
      data class Year(
        val year: AcademicYear,
      ) : Period

      data class Span(
        val first: AcademicYear,
        val last: AcademicYear,
      ) : Period
    }
  }

  /** The guard refused the dictionary; the subtype says which half and carries every offender. */
  sealed class RefusalException(
    message: String,
  ) : RuntimeException(message)

  /**
   * Columns this ingest serves that the dictionary drops, renames or dates in a
   * shape the fill cannot stamp -- each [faults] entry names its variable and,
   * where there is one, the publisher's cell.
   */
  class DictionaryRefusesServedVariablesException(
    val faults: List<Refusal>,
  ) : RefusalException(
      "the fetched Scorecard data dictionary cannot date [${faults.size}] column(s) this ingest serves, so no row " +
        "is stamped from it: " + faults.joinToString("; ") { it.message.orEmpty() },
    ) {
    /** The refused variables, in the order they were read. */
    val variables: List<String> get() = faults.mapNotNull { it.variable }
  }

  /** Columns the dictionary dates EARLIER than the newest period already stored for them. */
  class DictionaryBackdatesServedVariableException(
    val backdates: List<Backdate>,
  ) : RefusalException(
      "the fetched Scorecard data dictionary dates [${backdates.size}] column(s) EARLIER than the newest period " +
        "already stored for them -- a re-dating nobody chose: " +
        backdates.joinToString("; ") { "[${it.variable}] stored [${display(it.stored)}], dictionary [${display(it.dictionary)}]" },
    )
}
