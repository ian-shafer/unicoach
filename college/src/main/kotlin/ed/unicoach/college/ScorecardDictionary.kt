package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.CalendarYear
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The College Scorecard's own data dictionary, as `bin/fetch-scorecard` fetched
 * it with the data (RFC 274): the ONE home of the period every Scorecard figure
 * describes.
 *
 * The publisher dates every variable itself, in the `Most_Recent_Inst_Cohort_Map`
 * and `FieldOfStudy_Cohort_Map` sheets of `CollegeScorecardDataDictionary.xlsx`,
 * and names its source in `Institution_Data_Dictionary`. The fetcher writes those
 * three sheets as CSV, cells verbatim ([ScorecardDictionarySources]); this reads
 * them. Nothing here parses a spreadsheet, and no year is typed into Kotlin: a
 * newer release re-dates a figure by being fetched and ingested.
 *
 * Cells are kept VERBATIM and parsed ON DEMAND, per variable. A column the
 * ingest stamps undated is never asked for its period, so a pooled
 * administrative cell needs no grammar until something serves its year; a cell
 * that IS asked for and cannot be read is a refusal naming the variable and the
 * cell ([UnreadableCohortCellException]), never a silent blank.
 *
 * The publisher's cell grammar is [ScorecardCohortGrammar]. Every question comes
 * in two forms: `read…` answers a [Dating] for a caller that collects refusals
 * ([ScorecardYearGuard]), and `…Of` throws the refusal for one that wants a
 * single answer.
 */
class ScorecardDictionary private constructor(
  /** variable -> `Cohort/Measure in "Most Recent" Datafile`, verbatim. */
  private val institutionCells: Map<String, String>,
  /** variable -> `SOURCE`, verbatim. */
  private val institutionSources: Map<String, String>,
  /** variable -> `FieldOfStudyMostRecent datafile`, verbatim. */
  private val fieldOfStudyCells: Map<String, String>,
  /** The three CSVs as read, for the ingest's provenance row. */
  val digests: List<SourceDigest>,
) {
  /** The institution cohort cell for [variable], verbatim. */
  fun institutionCellOf(variable: String): String = readInstitutionCell(variable).orThrow()

  /** The publisher's `SOURCE` for [variable], verbatim. */
  fun sourceOf(variable: String): String =
    cellIn(institutionSources, variable, ScorecardDictionarySources.INSTITUTION_SOURCES_FILE).orThrow()

  /** The field-of-study cohort cell for [variable] in the most-recent datafile, verbatim. */
  fun fieldOfStudyCellOf(variable: String): String = readFieldOfStudyCell(variable).orThrow()

  /** The institution cell for [variable], read. */
  fun institutionCohortOf(variable: String): Cohort = readInstitutionCohort(variable).orThrow()

  /** [institutionCohortOf], answering rather than throwing. */
  fun readInstitutionCohort(variable: String): Dating<Cohort> =
    readInstitutionCell(variable).flatMap { cell -> ScorecardCohortGrammar.read(variable, cell) }

  /**
   * The academic year a MONEY figure was collected for (`AcadYr 2025-26`): the
   * year the ingest stamps on [variable]'s rows. Any other readable shape is
   * refused, because a term, an entering cohort and a pooled span are not the
   * year a price list is for.
   */
  fun academicYearOf(variable: String): AcademicYear = readAcademicYear(variable).orThrow()

  /** [academicYearOf], answering rather than throwing. */
  fun readAcademicYear(variable: String): Dating<AcademicYear> =
    readInstitutionCohort(variable).flatMap { cohort ->
      when (cohort) {
        is Cohort.StatedAcademicYear -> Dating.Dated(cohort.year)

        is Cohort.StatedTerm,
        is Cohort.StatedEnteringAcademicYear,
        is Cohort.PooledAcademicYears,
        Cohort.NoAcademicYear,
        -> Dating.Undatable(UnexpectedCohortShapeException(variable, institutionCellOf(variable), cohort, ExpectedPeriod.ACADEMIC_YEAR))
      }
    }

  /**
   * The period an OUTCOME figure's cohort entered in: a fall term or a full
   * entering academic year, and nothing else.
   */
  fun enteringPeriodOf(variable: String): Cohort =
    when (val cohort = institutionCohortOf(variable)) {
      is Cohort.StatedTerm, is Cohort.StatedEnteringAcademicYear -> cohort

      is Cohort.StatedAcademicYear,
      is Cohort.PooledAcademicYears,
      Cohort.NoAcademicYear,
      -> throw UnexpectedCohortShapeException(variable, institutionCellOf(variable), cohort, ExpectedPeriod.ENTERING_PERIOD)
    }

  /** The pooled academic years a FIELD-OF-STUDY figure describes. */
  fun fieldOfStudyCohortOf(variable: String): Cohort.PooledAcademicYears = readFieldOfStudyCohort(variable).orThrow()

  /** [fieldOfStudyCohortOf], answering rather than throwing. */
  fun readFieldOfStudyCohort(variable: String): Dating<Cohort.PooledAcademicYears> =
    readFieldOfStudyCell(variable).flatMap { cell -> ScorecardCohortGrammar.readPooled(variable, cell) }

  private fun readInstitutionCell(variable: String): Dating<String> =
    cellIn(institutionCells, variable, ScorecardDictionarySources.INSTITUTION_COHORTS_FILE)

  private fun readFieldOfStudyCell(variable: String): Dating<String> =
    cellIn(fieldOfStudyCells, variable, ScorecardDictionarySources.FIELD_OF_STUDY_COHORTS_FILE)

  private fun cellIn(
    cells: Map<String, String>,
    variable: String,
    file: String,
  ): Dating<String> = cells[variable]?.let { Dating.Dated(it) } ?: Dating.Undatable(VariableNotInDictionaryException(variable, file))

  /**
   * What one question about the dictionary answered: the value, or the refusal
   * as a value, so a caller collecting EVERY offender needs no catch.
   */
  sealed interface Dating<out T> {
    data class Dated<T>(
      val value: T,
    ) : Dating<T>

    data class Undatable(
      val fault: Refusal,
    ) : Dating<Nothing>

    /** The next question, asked only of a value. */
    fun <R> flatMap(next: (T) -> Dating<R>): Dating<R> =
      when (this) {
        is Dated -> next(value)
        is Undatable -> this
      }

    /** The value, or the refusal thrown. */
    fun orThrow(): T =
      when (this) {
        is Dated -> value
        is Undatable -> throw fault
      }
  }

  /**
   * How the publisher dates one variable -- a CLOSED type, so a fall term is
   * carried as a term rather than parsed into a year it is not, an entering
   * cohort is never read as a collection year, and an empty cell is a state
   * with a name rather than the empty string.
   */
  sealed interface Cohort {
    /** `AcadYr 2025-26`: the academic year a money figure was collected for. */
    data class StatedAcademicYear(
      val year: AcademicYear,
    ) : Cohort

    /**
     * `Fall 2024`: an entering term. [term] is the leading clause VERBATIM;
     * [autumnYear] is the calendar year it names, parsed once here.
     */
    data class StatedTerm(
      val term: String,
      val autumnYear: Int,
    ) : Cohort

    /**
     * `AY 2017-18 cohort`: the academic year a group of students ENTERED in,
     * across a July-June year. Distinct from [StatedAcademicYear] although both
     * carry an [AcademicYear]: one is when a figure was collected, the other
     * when a cohort arrived.
     */
    data class StatedEnteringAcademicYear(
      val cell: String,
      val year: AcademicYear,
    ) : Cohort

    /**
     * A cohort POOLED across academic years (`NSLDS pooled AY2022-23, AY2023-24
     * cohort`), with the dollar year the figure is expressed in where the
     * publisher states one ([amountUsdYear], a [CalendarYear], never an
     * academic year). [cell] is the publisher's cell verbatim.
     */
    data class PooledAcademicYears(
      val first: AcademicYear,
      val last: AcademicYear,
      val amountUsdYear: CalendarYear?,
      val cell: String,
    ) : Cohort {
      init {
        require(last >= first) { "[$cell] pools [${first.label}]..[${last.label}]: a cohort cannot end before it starts" }
      }
    }

    /** The cell is EMPTY: the publisher states no period at all. */
    data object NoAcademicYear : Cohort
  }

  /** WHY a cell names no period this reader can read: a closed set, so a further cause is a compile error. */
  enum class Cause {
    /** An `AY …` cell that is not exactly `AY <label> cohort`. */
    WRONG_CELL_SHAPE,

    /** A `Fall …` cell that is not `Fall <yyyy>` with an optional trailing clause. */
    NOT_AN_ENTERING_TERM,

    /** A `… pooled …` cell in neither pooled shape the publisher writes. */
    NOT_A_POOLED_COHORT,

    /** No shape this reader knows at all. */
    UNKNOWN_SHAPE,

    /** The label does not begin with a calendar year. */
    LABEL_HAS_NO_YEAR,

    /** [AcademicYear] or [CalendarYear] itself refused the year the label names. */
    YEAR_REJECTED,

    /** The label does not spell its academic year the way the publisher's grammar does. */
    LABEL_NOT_SPOKEN_THAT_WAY,
  }

  /**
   * The NAMED reason a cell was refused, as evidence: [cause] is what a caller
   * routes on, and [ScorecardCohortGrammar.describe] says it to a human.
   */
  data class Unreadable(
    val cause: Cause,
    /** The text refused: the cell's whole leading clause, or the one year label inside it. */
    val clause: String,
    /** For [Cause.LABEL_NOT_SPOKEN_THAT_WAY], how the grammar would read [clause]. */
    val spellings: List<String> = emptyList(),
    /** For [Cause.YEAR_REJECTED], what the year type threw. */
    val rejection: IllegalArgumentException? = null,
  )

  /** The period kind a caller asked a variable for: a closed set, rendered only in the refusal's message. */
  enum class ExpectedPeriod {
    ACADEMIC_YEAR,
    ENTERING_PERIOD,
    POOLED,
    POOLED_WITH_AMOUNT_USD_YEAR,
    POOLED_WITHOUT_AMOUNT_USD_YEAR,
  }

  /**
   * The dictionary refused something. A subtype names the [variable] it was
   * asked about wherever the fault is about one, and carries the evidence as
   * properties, so a caller routes on the type and a fixer reads the cell,
   * never a sentence.
   */
  sealed class Refusal(
    message: String,
    cause: Throwable? = null,
  ) : RuntimeException(message, cause) {
    abstract val variable: String?
  }

  /** [variable] has no row in [file]: the publisher dropped or renamed it. */
  class VariableNotInDictionaryException(
    override val variable: String,
    val file: String,
  ) : Refusal(
      "the Scorecard data dictionary [$file] has no row for [$variable]: the release dropped or renamed it, " +
        "so the period it describes cannot be read",
    )

  /** [cell], stated for [variable], is no shape the grammar reads; [read] says why. */
  class UnreadableCohortCellException(
    override val variable: String,
    val cell: String,
    val read: Unreadable,
  ) : Refusal(
      "the Scorecard data dictionary dates [$variable] as [$cell], which this reader cannot read: " +
        ScorecardCohortGrammar.describe(read),
      read.rejection,
    )

  /** [cell] reads as [cohort], which is not the period kind a caller asked [variable] for. */
  class UnexpectedCohortShapeException(
    override val variable: String,
    val cell: String,
    val cohort: Cohort,
    val expected: ExpectedPeriod,
  ) : Refusal(
      "the Scorecard data dictionary dates [$variable] as [$cell], which reads as [$cohort], not ${describe(expected)}",
    )

  /**
   * One of the three CSVs is not the file the fetcher writes: [detail] says
   * how, [file] says which, and [variable] is the row it stopped on, where
   * that row names one. A missing column is about no row, and a blank variable
   * cell names nothing.
   */
  class MalformedDictionaryFileException(
    val file: String,
    override val variable: String? = null,
    val detail: String,
  ) : Refusal("the Scorecard data dictionary [$file] is malformed: $detail")

  companion object {
    private fun describe(expected: ExpectedPeriod): String =
      when (expected) {
        ExpectedPeriod.ACADEMIC_YEAR -> "an academic year ([AcadYr <label>])"
        ExpectedPeriod.ENTERING_PERIOD -> "an entering term ([Fall <yyyy>]) or entering academic year ([AY <label> cohort])"
        ExpectedPeriod.POOLED -> "a cohort pooled across academic years"
        ExpectedPeriod.POOLED_WITH_AMOUNT_USD_YEAR -> "a pooled cohort that states the calendar year of its dollars"
        ExpectedPeriod.POOLED_WITHOUT_AMOUNT_USD_YEAR -> "a pooled cohort that states no calendar year for its dollars"
      }

    /** The header names the fetcher writes, each the publisher's own. */
    internal const val INSTITUTION_VARIABLE_COLUMN = "variable"
    internal const val INSTITUTION_COHORT_COLUMN = "Cohort/Measure in \"Most Recent\" Datafile"
    internal const val SOURCES_VARIABLE_COLUMN = "VARIABLE NAME"
    internal const val SOURCES_SOURCE_COLUMN = "SOURCE"
    internal const val FIELD_OF_STUDY_VARIABLE_COLUMN = "Variable Name"
    internal const val FIELD_OF_STUDY_COHORT_COLUMN = "FieldOfStudyMostRecent datafile"

    /**
     * The dictionary, read from [sources] on [ioDispatcher]. Refuses a file
     * missing a column it reads, a row with no variable name, and a variable
     * stated twice -- the last would let an arbitrary one of two contradictory
     * cells date a figure.
     */
    suspend fun fromFiles(
      sources: ScorecardDictionarySources,
      ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ScorecardDictionary = withContext(ioDispatcher) { readFiles(sources) }

    private fun readFiles(sources: ScorecardDictionarySources): ScorecardDictionary =
      ScorecardDictionary(
        institutionCells =
          readColumn(sources.institutionCohorts.file, INSTITUTION_VARIABLE_COLUMN, INSTITUTION_COHORT_COLUMN),
        institutionSources =
          readColumn(sources.institutionSources.file, SOURCES_VARIABLE_COLUMN, SOURCES_SOURCE_COLUMN),
        fieldOfStudyCells =
          readColumn(sources.fieldOfStudyCohorts.file, FIELD_OF_STUDY_VARIABLE_COLUMN, FIELD_OF_STUDY_COHORT_COLUMN),
        digests = sources.files.map(CsvIngestSupport::digest),
      )

    /** `variable -> cell` from one CSV, refusing what [fromFiles] says it refuses. */
    internal fun readColumn(
      file: File,
      variableColumn: String,
      valueColumn: String,
    ): Map<String, String> =
      parseCsv(file).use { records ->
        for (column in listOf(variableColumn, valueColumn)) {
          if (column !in records.headerMap) {
            throw MalformedDictionaryFileException(file.name, detail = "it has no [$column] column")
          }
        }
        records.fold(LinkedHashMap<String, String>()) { cells, record ->
          val variable = record.get(variableColumn).trim()
          if (variable.isEmpty()) {
            throw MalformedDictionaryFileException(file.name, detail = "line [${record.recordNumber + 1}] names no variable")
          }
          if (cells.put(variable, record.get(valueColumn).trim()) != null) {
            throw MalformedDictionaryFileException(file.name, variable, "it states [$variable] twice")
          }
          cells
        }
      }
  }
}

/**
 * The three CSVs `bin/fetch-scorecard` writes from the data dictionary, as the
 * ingest's one input group. Each is named by its own argument (`bin/ingest-colleges
 * -D/-O/-F`): `bin/remote` uploads every file under its own prefix, so three
 * files that must sit together never arrive together on the host.
 */
data class ScorecardDictionarySources(
  val institutionCohorts: SourceFile,
  val institutionSources: SourceFile,
  val fieldOfStudyCohorts: SourceFile,
) {
  /** The three, in the order they are digested. */
  val files: List<SourceFile> get() = listOf(institutionCohorts, institutionSources, fieldOfStudyCohorts)

  companion object {
    /** The fetcher's names for the three sheets it writes (`bin/fetch-scorecard` DICTIONARY_SHEETS). */
    const val INSTITUTION_COHORTS_FILE = "dictionary-institution-cohorts.csv"
    const val INSTITUTION_SOURCES_FILE = "dictionary-institution-sources.csv"
    const val FIELD_OF_STUDY_COHORTS_FILE = "dictionary-field-of-study-cohorts.csv"
  }
}
