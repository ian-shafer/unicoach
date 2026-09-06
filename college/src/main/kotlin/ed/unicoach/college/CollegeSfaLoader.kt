package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.intOrNull
import ed.unicoach.college.CsvIngestSupport.stringOrNull
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegeSfaDao
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.IpedsImputationFlag
import ed.unicoach.db.models.NewCollegeSfaCell
import ed.unicoach.db.models.ResidencyBasis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory

/**
 * The IPEDS Student Financial Aid source file and the aid year it publishes,
 * supplied as an all-or-nothing group of its own (RFC 162) beside the IPEDS
 * institutional-characteristics group.
 *
 * [aidYearStart] is the START year of the FILE's own aid year -- `SFA2223` is
 * aid year 2022-23, so 2022. It is explicit rather than derived from a
 * filename for the reason [IpedsSources.surveyYear] is: a derived year is a
 * silent coercion, and every stored cell's own aid year is computed from it.
 */
data class SfaSources(
  val survey: SourceFile,
  val aidYearStart: Int,
)

/**
 * What one SFA staging load did. [seen] counts CSV rows; [cellsWritten] counts
 * the staged cells, which is rows-loaded x [SfaVariables.ALL]`.size` when
 * nothing is skipped.
 */
data class SfaLoadResult(
  val seen: Int,
  val institutionsLoaded: Int,
  val cellsWritten: Int,
  val skipsByReason: Map<SkipReason, Int>,
  /**
   * Cells flagged `A` that nevertheless carried a number, by variable: the
   * value cannot be stored (`college_sfa_not_applicable_has_no_value_check`),
   * so it is counted here. The exact MIRROR shape -- a value-bearing flag over
   * an empty cell -- is counted by the canonical fill, and one direction of a
   * publisher disagreement must not be visible while the other is silent.
   */
  val valuesDroppedUnderNotApplicable: Map<String, Int>,
  /**
   * Cells the publisher FILLED that this reader could not read as a number
   * (`"1,234"`, `"NA"`), by variable.
   *
   * Its own loss class, never folded into the empty cell beside it: an
   * unreadable cell is staged with no value, and the fill would then count it
   * as a cell whose flag bears a value but which carried none -- reporting OUR
   * parse loss as a disagreement by the PUBLISHER, in the very metric that
   * exists to expose one. Measured at zero over sfa2223.csv's 1.95M cells, so
   * a non-zero here is a column that changed shape.
   */
  val valuesUnreadable: Map<String, Int>,
) {
  val skipped: Int get() = skipsByReason.values.sum()
  val unmatchedIpedsUnitIds: Int get() = skipsByReason[SkipReason.NoCollegeForIpedsUnitId] ?: 0
}

/**
 * One SFA variable FAMILY, and the ONE place its published variable names are
 * spelled.
 *
 * Publics and privates publish the SAME concepts under DIFFERENT NAMES
 * (`NPIST`/`NPGRN`, `NPIS4x`/`NPT4x`, `GIS4*`/`GRN4*`) and are MUTUALLY
 * EXCLUSIVE -- zero institutions carry both over four measured aid years -- so
 * the family is resolved ONCE per institution into a value carrying every
 * per-family constant, rather than each mapping site re-deciding it (which is
 * how a stem ternary came to read the private stem when NEITHER family
 * applied).
 *
 * The name-building functions below are why this enum lives beside
 * [SfaVariables] rather than in the fill: the staging loader stages exactly
 * the names they return and the canonical fill looks exactly those names back
 * up, so the two halves cannot spell a stem differently.
 */
internal enum class SfaFamily(
  /** The overall grant-aided net price stem (NPIST/NPGRN). */
  val netPriceStem: String,
  /** The income-band net price stem (NPIS4x/NPT4x), completed by [IncomeBand.bandDigit] and the year suffix. */
  val bandStem: String,
  /** The living-arrangement headcount stem (`gis4`/`grn4`). */
  val arrangementStem: String,
  /** The scope this family's net prices are quoted on. */
  val residencyScope: CohortResidencyScope,
  /** The residency basis this family's arrangement headcounts are counted on. */
  val arrangementResidency: ResidencyBasis,
) {
  PUBLIC("npist", "npis4", "gis4", CohortResidencyScope.IN_STATE_RATE_PAYING, ResidencyBasis.IN_STATE),

  // A private charges one price, so there is no residency restriction to
  // record -- `all` is the honest scope, not a missing one, and its
  // arrangement counts are not a residency split at all.
  PRIVATE("npgrn", "npt4", "grn4", CohortResidencyScope.ALL, ResidencyBasis.NOT_APPLICABLE),
  ;

  /** The overall grant-aided net price for [suffix]'s aid year (`npist2`). */
  fun netPrice(suffix: String): String = "$netPriceStem$suffix"

  /** The net price for one household income [band] (`npis412`). */
  fun bandNetPrice(
    band: IncomeBand,
    suffix: String,
  ): String = "$bandStem${band.bandDigit}$suffix"

  /** The total living-arrangement headcount (`gis4n2`), the denominator of the four parts below. */
  fun arrangementTotal(suffix: String): String = "${arrangementStem}n$suffix"

  /** One living-arrangement part, [code] from [SfaVariables.ARRANGEMENT_CODES] (`gis4on2`). */
  fun arrangement(
    code: String,
    suffix: String,
  ): String = "$arrangementStem$code$suffix"

  /** Every variable this family publishes for one year suffix -- what the loader stages and the fill reads. */
  fun variables(suffix: String): List<String> =
    listOf(netPrice(suffix), arrangementTotal(suffix)) +
      IncomeBand.entries.map { band -> bandNetPrice(band, suffix) } +
      SfaVariables.ARRANGEMENT_CODES.map { (code, _) -> arrangement(code, suffix) }
}

/**
 * The variables this ingest reads out of the SFA file, and the ONE rule that
 * turns a variable name into the aid year it describes.
 *
 * **The year suffix is RELATIVE to the file** (measured across two files): in
 * `SFAyyzz`, suffix `2` is the file's own aid year, `1` one earlier, `0` two
 * earlier. So one `SFA2223` pin yields three vintages, and a loader that
 * hardcoded "suffix 2 means 2022-23" would be silently wrong on the next file.
 *
 * **Every variable has a mechanical `X` twin** (`npist2` -> `xnpist2`): 345 of
 * the file's 346 data variables carry one, the exception being `UNITID`. The
 * twin name is COMPUTED, never tabled, and the computed name is asserted
 * present in the header -- which turns a publisher rename into a startup
 * fatal instead of a column silently read as blank.
 *
 * Public and private institutions use DIFFERENT NAMES FOR THE SAME CONCEPT and
 * are mutually exclusive (zero institutions carry both): `NPIST`/`NPGRN`,
 * `NPIS4x`/`NPT4x`, `GIS4*`/`GRN4*`. Reading only the public half would drop
 * ~65% of the country's colleges, so both families are staged and the fill
 * takes whichever the institution actually published.
 */
internal object SfaVariables {
  /** The three file-relative year suffixes, newest last. */
  val YEAR_SUFFIXES = listOf("0", "1", "2")

  /**
   * The living-arrangement suffixes and the arrangement each names, completing
   * a family's arrangement stem (`gis4` + `on` + `2` = `gis4on2`).
   *
   * ONE table for both halves of the SFA path: the staging loader builds the
   * columns it READS from it and the canonical fill looks the staged cells
   * back UP by it, so a code added here reaches both or neither. Two hand-kept
   * lists is how the fill comes to read a column nothing staged.
   */
  val ARRANGEMENT_CODES: List<Pair<String, FigureArrangement>> =
    listOf(
      "on" to FigureArrangement.ON_CAMPUS,
      "wf" to FigureArrangement.WITH_FAMILY,
      "of" to FigureArrangement.OFF_CAMPUS,
      "un" to FigureArrangement.UNKNOWN,
    )

  /** The fall cohort's residency split (SCFA11N-SCFA14N) and the basis each counts; the same one-table rule. */
  val RESIDENCY_SPLIT: List<Pair<String, ResidencyBasis>> =
    listOf(
      "scfa11n" to ResidencyBasis.IN_DISTRICT,
      "scfa12n" to ResidencyBasis.IN_STATE,
      "scfa13n" to ResidencyBasis.OUT_OF_STATE,
      "scfa14n" to ResidencyBasis.UNKNOWN,
    )

  /**
   * The aid-mix stems, each published as a `_P` share of the cohort and a `_A`
   * average per recipient. WHICH measure and aid scope each pair lands as is
   * the canonical fill's business (`AID_MIX` there, pinned to this list); the
   * NAMES are this vocabulary's.
   */
  val AID_MIX_STEMS: List<String> = listOf("fgrnt", "sgrnt", "igrnt", "loan")

  /**
   * The three Pell variables at the all-undergraduate level: the RECIPIENT
   * HEADCOUNT, the SHARE receiving, and the AVERAGE AWARD per recipient.
   *
   * Named here, in the staging vocabulary, rather than spelled at each use:
   * the canonical fill looks these cells back up BY NAME
   * (`CanonicalMoneyLoader.mapSfaMoneyStats`/`mapSfaPopulationCounts`), and the
   * only thing that used to join the two copies was spelling. A rename on one
   * side alone lands in `fill.cellsNotStaged` and the Pell figures silently
   * stop being written -- a coverage loss, not an error. One constant, so a
   * rename reaches both sides or neither, the rule `ARRANGEMENT_CODES` already
   * states.
   */
  const val PELL_RECIPIENT_COUNT = "upgrntn"

  /** The share of all undergraduates receiving a Pell grant, published as an integer percent. */
  const val PELL_SHARE = "upgrntp"

  /** The average Pell award per RECIPIENT, published in whole dollars. */
  const val PELL_AVERAGE_AWARD = "upgrnta"

  /**
   * The single-year variables: the file's own aid year is the only year they
   * describe. Pell at the all-undergraduate level, the FTFT aid mix, and the
   * fall cohort's residency split.
   */
  val SINGLE_YEAR: List<String> =
    listOf(PELL_RECIPIENT_COUNT, PELL_SHARE, PELL_AVERAGE_AWARD) +
      AID_MIX_STEMS.flatMap { stem -> listOf("${stem}_p", "${stem}_a") } +
      listOf("scfa1n") + RESIDENCY_SPLIT.map { (variable, _) -> variable }

  /** Every variable staged, suffixed and single-year alike. */
  val ALL: List<String> =
    SfaFamily.entries.flatMap { family -> YEAR_SUFFIXES.flatMap { suffix -> family.variables(suffix) } } + SINGLE_YEAR

  /** The institution key, as this module names a staged variable: lower case. */
  const val UNIT_ID = "unitid"

  /**
   * [variable]'s name as it appears in the PUBLISHED header, which NCES writes
   * in upper case (`UNITID`, `NPIST2`, `XNPIST2`) exactly as the HD/IC/ADM
   * readers already assume.
   *
   * The two cases are deliberately distinct, not a sloppiness to be folded
   * away: the published name is what a header assertion and a record lookup
   * must use, and the lower-case name is what `college_sfa.variable` stores and
   * every canonical-fill mapping is keyed by. Naming the conversion once here
   * is what keeps a rename of either from silently becoming a column read as
   * blank.
   */
  fun sourceColumn(variable: String): String = variable.uppercase()

  /** The imputation twin of [variable] in the published header, from NCES's own mechanical rule. */
  fun flagColumn(variable: String): String = "X${variable.uppercase()}"

  /**
   * The aid year [variable] describes, given the START year of the FILE's own
   * aid year. Suffix `2` is that year, `1` is one earlier, `0` two earlier; a
   * single-year variable is the file's own year.
   */
  fun aidYear(
    variable: String,
    fileAidYearStart: Int,
  ): AcademicYear {
    if (variable in SINGLE_YEAR) return AcademicYear(fileAidYearStart)
    val suffix = YEAR_SUFFIXES.indexOf(variable.takeLast(1))
    // A suffixed variable whose last character is not one of the three
    // suffixes would otherwise get a year quietly computed from -1. The whole
    // point of resolving the suffix here is that nothing downstream can.
    check(suffix >= 0) {
      "SFA variable [$variable] is neither single-year nor suffixed 0/1/2; its aid year cannot be resolved"
    }
    val offset = suffix - (YEAR_SUFFIXES.size - 1)
    return AcademicYear(fileAidYearStart + offset)
  }

  /** Every column the loader reads, for the header assertion (RFC 139). */
  val REQUIRED_COLUMNS: List<String> =
    listOf(sourceColumn(UNIT_ID)) + ALL.flatMap { listOf(sourceColumn(it), flagColumn(it)) }
}

/**
 * Re-runnable staging loader for the IPEDS SFA survey (RFC 162): one
 * `college_sfa` row per measured cell of every institution that matches a
 * `colleges` row, with the cell's own aid year resolved and its raw X
 * imputation flag beside it.
 *
 * A WHOLESALE DELETE + batch re-insert in one transaction, not a per-row
 * upsert like [IpedsLoader]: the phase stages ~460k cells from ONE pinned
 * file, so a per-row savepoint would be 460k round trips, and the source
 * RESTATES prior years -- a cell the new file no longer publishes must
 * disappear rather than survive as an unupdated leftover. Idempotency is by
 * construction.
 *
 * The X flag is where this loader differs from every reader before it: the
 * value and its flag are TWO columns that must be read together, so absence
 * has a published reason rather than being inferred from a blank. An unknown
 * flag letter is FATAL -- the publisher changed a vocabulary and which of our
 * six statuses that means is a review, never a default.
 */
class CollegeSfaLoader(
  private val database: Database,
  // Blocking file IO (the header assertion) runs on this dispatcher, never a
  // caller's coroutine thread -- the [IpedsLoader] constructor-injection
  // pattern, overridable in tests.
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val logger = LoggerFactory.getLogger(CollegeSfaLoader::class.java)

  /**
   * Asserts every column this loader reads is present, before any write (RFC
   * 139). It owns its own [ioDispatcher] hop -- the header read opens and
   * parses a real file -- so the caller reads as a plain phase list and never
   * has to know this one blocks.
   */
  suspend fun assertHeaders(sources: SfaSources) =
    withContext(ioDispatcher) {
      CsvIngestSupport.assertRequiredColumns(sources.survey, SfaVariables.REQUIRED_COLUMNS)
    }

  /**
   * Stages [sources]' file: every matched institution's [SfaVariables.ALL]
   * cells, replacing whatever the previous run staged, in ONE transaction it
   * owns.
   *
   * Three steps, each its own function: READ the file into cells and tallies,
   * REPLACE the staged rows, REPORT. The transaction and the two writes are
   * all that is left here, so "what does this phase do to the database" is
   * answerable without reading the parse loop.
   *
   * Returns an OUTCOME, not a result: an unknown imputation letter is a
   * statement about the FILE, like every other bad-input state this loop
   * models with [SkipReason], so it is returned rather than thrown -- the
   * caller can then tell bad input from a JDBC fault, and decides (it still
   * does) that this one stops the run. Nothing is written on that path: the
   * delete and the insert are both after the read.
   */
  suspend fun load(sources: SfaSources): SfaLoadOutcome =
    database.withConnection { session ->
      // The match set, read once: a per-row lookup would be ~6k round trips to
      // answer a question one SELECT answers.
      val collegeIpedsUnitIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow().keys
      val read = readCells(sources, collegeIpedsUnitIds)
      if (read.unknownFlags.isNotEmpty()) {
        SfaLoadOutcome.UnknownImputationFlags(read.unknownFlags)
      } else {
        CollegeSfaDao.deleteAll(session).getOrThrow()
        val insertedCells = CollegeSfaDao.insertAll(session, read.cells).getOrThrow()
        SfaLoadOutcome.Staged(read.mapResult(insertedCells).also(::logSummary))
      }
    }

  /**
   * Parses [sources]' file into the cells to stage plus every counted loss.
   * Reads only: nothing here writes, which is what makes the unknown-flag
   * refusal below a decision taken before the delete.
   *
   * The whole file is read even once an unknown flag has been seen. The first
   * bad letter is not the fact an operator needs -- "the publisher started
   * using Q and Y, across these columns" is -- and one more pass over a file
   * already in the page cache is cheaper than a second aborted ingest.
   */
  private fun readCells(
    sources: SfaSources,
    collegeIpedsUnitIds: Set<Int>,
  ): SfaFileStaging {
    val staging = SfaFileStaging()
    val written = mutableSetOf<Int>()
    parseCsv(sources.survey.file).use { records ->
      for (record in records) {
        staging.count.seen++
        if (!CsvIngestSupport.isWellFormed(record)) {
          staging.count.recordSkip(SkipReason.RowArityMismatch)
          continue
        }
        val unitId = intOrNull(record, SfaVariables.sourceColumn(SfaVariables.UNIT_ID))
        if (unitId == null) {
          staging.count.recordSkip(SkipReason.MissingRequiredField(listOf(SfaVariables.UNIT_ID)))
          continue
        }
        if (unitId !in collegeIpedsUnitIds) {
          staging.count.recordSkip(SkipReason.NoCollegeForIpedsUnitId)
          continue
        }
        if (!written.add(unitId)) {
          // First row wins and the loser is COUNTED, exactly as the HD load
          // does: file order must never silently decide an institution's
          // values.
          staging.count.recordSkip(SkipReason.DuplicateKeyInFile)
          logger.warn("SFA file repeats [ipeds_unit_id={}] at [line={}]; keeping the first row", unitId, record.recordNumber)
          continue
        }
        staging.count.loaded++
        for (variable in SfaVariables.ALL) {
          readCell(record, unitId, variable, sources.aidYearStart, staging)
        }
      }
    }
    return staging
  }

  /**
   * Reads ONE (value, flag) column pair into [staging]: a staged cell, or a
   * counted loss, or -- for a flag letter no published vocabulary has -- one
   * more sighting of the change that will refuse the file.
   */
  private fun readCell(
    record: CSVRecord,
    unitId: Int,
    variable: String,
    aidYearStart: Int,
    staging: SfaFileStaging,
  ) {
    val column = SfaVariables.flagColumn(variable)
    // No `?: ""` sentinel: an absent flag cell is a state the nullable type
    // already carries, and `""` is not a code the publisher ever writes. Both
    // states refuse the file, and the outcome says WHICH one happened.
    val rawFlag = stringOrNull(record, column)
    val flag = rawFlag?.let(IpedsImputationFlag::fromCode)
    if (flag == null) {
      staging.recordUnknownFlag(column, rawFlag, unitId, record.recordNumber)
      return
    }
    // Once the file is going to be refused, the rest of this pass is a survey
    // for the report, not a staging run: no cell it built could be written.
    if (staging.unknownFlags.isNotEmpty()) return
    staging.cells += mapCell(record, unitId, variable, aidYearStart, flag, staging)
  }

  /**
   * One staged cell: the value, the flag that says why it is or is not there,
   * and the aid year the variable's own suffix resolves to. [flag] is read by
   * the caller, which is where an unknown letter becomes a returned outcome.
   *
   * A value-bearing flag over an EMPTY value cell is the one shape the source
   * should never publish (measured: `R`/`C`/`P`/`N` always carry a value,
   * `Z` always carries 0). It is staged as it stands -- flag raw, value NULL,
   * which the DDL allows. Staging does not judge it: the CANONICAL FILL is
   * where a cell becomes a status, so that is the one place the shape is
   * counted, and logged there. Tallying it here as well would be the
   * same fact measured twice, by two loaders, for one operator.
   *
   * The MIRROR shape -- flag `A` beside a real number -- is the one this
   * loader must count, because the DDL forbids storing it, so the fill will
   * never see it: [SfaLoadResult.valuesDroppedUnderNotApplicable] is where it
   * becomes visible.
   */
  private fun mapCell(
    record: CSVRecord,
    unitId: Int,
    variable: String,
    aidYearStart: Int,
    flag: IpedsImputationFlag,
    staging: SfaFileStaging,
  ): NewCollegeSfaCell {
    val raw = stringOrNull(record, SfaVariables.sourceColumn(variable))
    val value = raw?.toDoubleOrNull()
    // THREE input states, kept three. A cell the publisher FILLED that this
    // reader cannot read as a number is neither an empty cell nor a number:
    // folding it into the empty one stages it under a value-bearing flag, and
    // the fill then reports OUR parse loss as a disagreement by the PUBLISHER.
    if (raw != null && value == null) {
      staging.valuesUnreadable.merge(variable, 1, Int::plus)
      logger.warn(
        "SFA cell [{}] at [ipeds_unit_id={}] [line={}] holds [{}], which is not a number; staged with no value",
        variable,
        unitId,
        record.recordNumber,
        raw,
      )
    }
    // The DDL's own rule, restated at the write side: a not-applicable cell
    // has no value, so a stray one is dropped here rather than refused by a
    // CHECK four hundred thousand rows into a batch -- and COUNTED, because a
    // published number leaving the system silently is the loss this tally
    // exists to make visible.
    val stored =
      if (flag == IpedsImputationFlag.NOT_APPLICABLE && value != null) {
        staging.droppedUnderNotApplicable.merge(variable, 1, Int::plus)
        null
      } else {
        value
      }
    return NewCollegeSfaCell(
      ipedsUnitId = unitId,
      aidYearStart = aidYearStart,
      variable = variable,
      aidYear = SfaVariables.aidYear(variable, aidYearStart),
      value = stored,
      flag = flag,
    )
  }

  /** The one operator-facing line this phase leaves behind: every count it kept, none omitted. */
  private fun logSummary(result: SfaLoadResult) {
    logger.info(
      "Load summary [sfa]: [institutions={}] [cells={}] [skips={}] [values_dropped_under_not_applicable={}] " +
        "[values_unreadable={}]",
      result.institutionsLoaded,
      result.cellsWritten,
      result.skipsByReason,
      result.valuesDroppedUnderNotApplicable,
      result.valuesUnreadable,
    )
  }
}

/**
 * What one pass over the SFA file produced: the cells to stage and every
 * counted loss beside them. A mutable accumulator the read loop folds into,
 * exactly as [LoadCount] is for the row-level skips it wraps -- so the loop
 * threads ONE value instead of four out-parameters.
 */
private class SfaFileStaging {
  val count = LoadCount()
  val cells = mutableListOf<NewCollegeSfaCell>()
  val droppedUnderNotApplicable = mutableMapOf<String, Int>()
  val valuesUnreadable = mutableMapOf<String, Int>()

  /** Every unknown flag letter seen, by (column, letter): the file's whole vocabulary change, not its first sighting. */
  val unknownFlags: List<UnknownImputationFlag> get() = unknownByCode.values.toList()

  private val unknownByCode = linkedMapOf<Pair<String, String?>, UnknownImputationFlag>()

  fun recordUnknownFlag(
    column: String,
    rawFlag: String?,
    ipedsUnitId: Int,
    line: Long,
  ) {
    val seen = unknownByCode[column to rawFlag]
    unknownByCode[column to rawFlag] =
      seen?.copy(occurrences = seen.occurrences + 1)
        ?: UnknownImputationFlag(
          column = column,
          code = rawFlag,
          ipedsUnitId = ipedsUnitId,
          line = line,
          occurrences = 1,
        )
  }

  fun mapResult(insertedCells: Int): SfaLoadResult =
    SfaLoadResult(
      seen = count.seen,
      institutionsLoaded = count.loaded,
      cellsWritten = insertedCells,
      skipsByReason = count.skipsByReason.toMap(),
      valuesDroppedUnderNotApplicable = droppedUnderNotApplicable.toMap(),
      valuesUnreadable = valuesUnreadable.toMap(),
    )
}

/**
 * One flag letter the published vocabulary does not have, as the file
 * presented it: which column carried it, the letter itself (null when the cell
 * was EMPTY -- a different publisher change from a new letter), the first
 * institution and CSV line it appeared at, and how many cells carried it.
 *
 * The identity is kept because a fatal that names only a letter is
 * unactionable against a 691-column, 6,000-row file.
 */
data class UnknownImputationFlag(
  val column: String,
  val code: String?,
  val ipedsUnitId: Int,
  val line: Long,
  val occurrences: Int,
)

/**
 * A flag vocabulary change in the SFA file: one or more `X`-twin cells carry a
 * letter no published IPEDS imputation code has (or carry NOTHING). Which of
 * our six statuses such a letter means is a review, never a default, so the
 * phase refuses the file -- with the [flags] still TYPED, because a caller
 * that has to regex a sentence to learn which columns changed is a caller the
 * loader already did the work for.
 *
 * Nothing was staged: the refusal is decided from the read, before the delete.
 */
class UnknownImputationFlagException(
  val flags: List<UnknownImputationFlag>,
) : RuntimeException(
    "SFA imputation flag(s) outside the published vocabulary: " +
      flags.joinToString("; ") { flag ->
        "column [${flag.column}] carries [${flag.code}] in [${flag.occurrences}] cell(s), first at " +
          "[ipeds_unit_id=${flag.ipedsUnitId}] [line=${flag.line}]"
      } +
      ". The publisher changed the flag vocabulary; which status each letter means is a review, " +
      "never a default (RFC 162). Nothing was staged.",
  )

/**
 * What one SFA staging load produced: the staged result, or the one INPUT
 * state that must stop the run (RFC 162). An unknown imputation letter means
 * the publisher changed a vocabulary, and which of our six statuses that means
 * is a review, never a default -- but it is a fact about the file, so it is
 * RETURNED like every [SkipReason], not thrown out of band where a caller
 * cannot tell it from a JDBC fault.
 */
sealed interface SfaLoadOutcome {
  data class Staged(
    val result: SfaLoadResult,
  ) : SfaLoadOutcome

  /**
   * EVERY unknown letter the file carries, not the first: the whole file is
   * read before the refusal, so one aborted ingest tells the operator the
   * whole vocabulary change to review rather than one letter per re-run.
   */
  data class UnknownImputationFlags(
    val flags: List<UnknownImputationFlag>,
  ) : SfaLoadOutcome
}
