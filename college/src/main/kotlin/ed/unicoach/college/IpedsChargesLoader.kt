package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.classifyUpsertFailure
import ed.unicoach.college.CsvIngestSupport.logUpsertSkip
import ed.unicoach.college.CsvIngestSupport.stringOrNull
import ed.unicoach.college.CsvIngestSupport.upsertWithSavepoint
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeIpedsChargesDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.ChargeKey
import ed.unicoach.db.models.CollegeIpedsChargeId
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.FlagMeaning
import ed.unicoach.db.models.IpedsImputationFlag
import ed.unicoach.db.models.NewCollegeIpedsCharge
import ed.unicoach.db.models.ValueBearingStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Per-file outcome for the `ipeds-charges` phase (RFC 161).
 *
 * [seen] counts IC_AY RECORDS — one per institution — while [inserted],
 * [changed] and [unchanged] count staged charge ROWS, of which one record
 * produces exactly 48 (twelve variable stems × four carried years). The two are
 * deliberately different units: a reader who conflates them reads a 3,825-row
 * file as a 180,000-row one.
 *
 * The two loss axes are separate for the same reason. [skipsByReason] counts
 * dropped RECORDS; [rowFailuresByReason] counts individual charge ROWS whose
 * upsert failed. Folding a row failure into the record axis would report one
 * bad institution as 48 skipped records.
 *
 * [pruned] counts rows DELETED because this file no longer carries them — a
 * departed institution, or the previous survey year's window after a bump.
 *
 * [cellsByFlag] is the imputation-code distribution over every LOADED cell —
 * the operator-visible evidence that no charge figure unicoach serves is
 * imputed today, and the number that moves the day one is. It is keyed by the
 * ENUM, not by the raw code string: the member is in hand where the tally is
 * taken, and the published code it renders to belongs at the JSON/log edge like
 * every other slug in this run.
 */
data class IpedsChargesLoadResult(
  val seen: Int,
  val inserted: Int,
  val changed: Int,
  val unchanged: Int,
  val pruned: Int,
  val skipsByReason: Map<SkipReason, Int>,
  val rowFailuresByReason: Map<SkipReason, Int>,
  val cellsByFlag: Map<IpedsImputationFlag, Int>,
) {
  /** Charge rows that reached the table, however they landed. */
  val loaded: Int get() = inserted + changed + unchanged

  /** Records dropped for any reason. */
  val skipped: Int get() = skipsByReason.values.sum()

  /** Charge ROWS whose own upsert failed — never a record skip. */
  val rowFailures: Int get() = rowFailuresByReason.values.sum()

  /** Records whose UNITID matches no college — the IC_AY-only institutions. */
  val unmatchedIpedsUnitIds: Int get() = skipsByReason[SkipReason.NoCollegeForIpedsUnitId] ?: 0

  /** Retryable failures on either axis, which are worth a WARN in the ingest summary. */
  val transientSkips: Int
    get() = (skipsByReason[SkipReason.Transient] ?: 0) + (rowFailuresByReason[SkipReason.Transient] ?: 0)
}

/**
 * Loads IPEDS `IC2023_AY.csv` — the published-charges survey — into the narrow
 * `college_ipeds_charges` staging table (RFC 161).
 *
 * IC_AY is a 235-column WIDE table whose value columns are a
 * year × tier × component cross-product: `CHG2AY3` is in-state tuition and fees
 * for 2023-24. This loader is the reshape: one staged row per
 * UNITID × variable stem × academic year, so a published year is DATA and not a
 * column identifier, and next year's file needs no migration.
 *
 * WHICH stems and WHICH years is not this loader's to decide: both come from
 * [IpedsChargeVocabulary], the one list the canonical fill reads too.
 *
 * It shares every generic mechanism with [IpedsLoader] and
 * [CollegeScorecardLoader] through [CsvIngestSupport] — header assertion, the
 * per-row savepoint, the skip taxonomy — and adds only what IC_AY forces:
 *
 * - **The missing-value token is a literal `.`**, not a blank and not `NULL`.
 *   A `.` read as text would store the string; read as a number it is a parse
 *   failure. It is named once, as [MISSING_TOKEN].
 * - **The last header is `CHG9AY3 `, with a trailing space.** Nothing here has
 *   to handle that: [parseCsv] already trims header names for `adm2023.csv`'s
 *   `ACTMT75   `, and the same defence covers this. Stated so a reader who
 *   greps the raw file for `CHG9AY3,` and finds nothing knows why.
 * - **Every loaded variable has an X-flag partner** naming why a cell is empty
 *   or how it was filled. For the `CHG*` family the partner is plain `X` + the
 *   column name (`CHG2AY3` → `XCHG2AY3`); the truncated-stem forms
 *   (`TUITION1` → `XTUIT1`) belong to variables this loader does not read.
 * - **A value with a valueless flag, or a valueless flag bearing a value, is
 *   FATAL.** That is the same value-IFF-status rule the `price_figures` CHECK
 *   and `college_ipeds_charges_value_iff_flag_check` enforce, caught at parse
 *   rather than 180,000 rows later.
 * - **The load is a SNAPSHOT, not an accumulation.** What this file no longer
 *   carries is deleted ([CollegeIpedsChargesDao.deleteNotIn]).
 *
 * **Coverage is 3,825 institutions, not the ~6,100 in `HD2023.csv`.**
 * Institutions that charge on a program-year calendar report into `IC_PY`, a
 * different survey file this repo does not ingest. An unmatched record is
 * counted, never invented, and the smaller row count is coverage rather than
 * data loss.
 */
class IpedsChargesLoader(
  private val database: Database,
  // Blocking file IO (the 235-column header parse) runs on this dispatcher,
  // never the caller's coroutine thread — the [IpedsLoader] pattern, injected
  // so a test can pin it.
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val logger = LoggerFactory.getLogger(IpedsChargesLoader::class.java)

  /**
   * Asserts every column this loader reads is present, and that the run's
   * survey year is the one the vocabulary decodes — both before any write.
   * Called from [CollegeScorecardLoader.ingest] with the other header
   * assertions, so a bad IC_AY header or a mis-dated window cannot corrupt a
   * run that has already written rows. Owns its own [ioDispatcher] hop, like
   * [IpedsLoader.assertHeaders]: a 235-column header parse must not block a
   * caller's thread.
   */
  suspend fun assertHeaders(
    source: SourceFile,
    surveyYear: Int,
  ) = withContext(ioDispatcher) {
    IpedsChargeVocabulary.assertSurveyYear(surveyYear)
    CsvIngestSupport.assertRequiredColumns(source, REQUIRED_COLUMNS)
  }

  /**
   * Stages every loaded charge cell of every IC_AY record whose `UNITID`
   * matches an existing `colleges.ipeds_unit_id`, then prunes whatever this
   * file no longer carries.
   *
   * One transaction for the phase, one savepoint per staged row: a CHECK
   * violation costs that row and is counted, not the file. [surveyYear] is the
   * run's own `--survey-year`, checked against the pinned window here as well
   * as at header time — this is the call that decodes the suffixes, so this is
   * where a mis-dated file must not get through.
   */
  suspend fun load(
    source: SourceFile,
    surveyYear: Int,
  ): IpedsChargesLoadResult {
    IpedsChargeVocabulary.assertSurveyYear(surveyYear)
    return database.withConnection { session ->
      val tally = Tally()
      val collegeIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow()
      // The UNITIDs this pass has already staged. IC_AY's key is unique in the
      // real file, but a repeated UNITID would otherwise overwrite the earlier
      // institution's 48 rows and report the loss as ordinary `changed`s.
      val staged = mutableSetOf<Int>()
      parseCsv(source.file).use { records ->
        for (record in records) {
          tally.count.seen++
          val ipedsUnitId =
            when (val mapped = mapRecord(record, records.headerMap.size, collegeIds.keys, staged)) {
              is MapResult.Skipped -> {
                tally.count.recordSkip(mapped.reason)
                continue
              }

              is MapResult.Mapped -> {
                mapped.value
              }
            }
          staged += ipedsUnitId
          stageRecord(session, record, collegeIds.getValue(ipedsUnitId).value, ipedsUnitId, tally)
        }
      }
      val pruned = deleteNotStaged(session, tally.stagedKeys)
      val cellsByFlag = tally.cellsByFlag.toSortedMap(compareBy { it.code })
      logger.info(
        "Load summary [ipeds-charges]: [records={}] [rows={}] [pruned={}] [skips={}] [row-failures={}] [flags={}]",
        tally.count.seen,
        tally.count.loaded,
        pruned,
        tally.count.skipsByReason,
        tally.rowFailuresByReason,
        // Codes only at the edge: the tally itself is typed.
        cellsByFlag.mapKeys { it.key.code },
      )
      IpedsChargesLoadResult(
        seen = tally.count.seen,
        inserted = tally.count.inserted,
        changed = tally.count.changed,
        unchanged = tally.count.unchanged,
        pruned = pruned,
        skipsByReason = tally.count.skipsByReason.toMap(),
        rowFailuresByReason = tally.rowFailuresByReason.toMap(),
        cellsByFlag = cellsByFlag,
      )
    }
  }

  /**
   * The four axes one `ipeds-charges` pass accumulates, held together so [load]
   * reads mapRecord -> stage -> next instead of carrying four loose collections
   * through two altitudes.
   *
   * They are four and not one because they count DIFFERENT things: [count]
   * counts records seen and charge rows written, [rowFailuresByReason] counts
   * charge ROWS whose own upsert failed (never a record skip), [cellsByFlag] is
   * the imputation-code distribution over LOADED cells, and [stagedKeys] is the
   * exact natural keys written — the prune's keep-set, which is why a failed
   * row must not reach it.
   */
  private class Tally {
    val count = LoadCount()
    val rowFailuresByReason = mutableMapOf<SkipReason, Int>()
    val cellsByFlag = mutableMapOf<IpedsImputationFlag, Int>()
    val stagedKeys = mutableSetOf<ChargeKey>()
  }

  /**
   * Stages one mapped record's 48 charge rows, each on its own savepoint:
   * a CHECK violation costs that row and is counted, not the file or the
   * record.
   *
   * A failure tallies on the ROW axis and reaches neither [Tally.stagedKeys]
   * nor [Tally.cellsByFlag] — the first because the prune must retire a
   * row this run failed to write rather than leave it holding the previous
   * file's amount, the second because the flag distribution is over cells that
   * LOADED, and counting a failed row there reports one cell twice.
   */
  private fun stageRecord(
    session: SqlSession,
    record: CSVRecord,
    collegeId: UUID,
    ipedsUnitId: Int,
    tally: Tally,
  ) {
    for ((row, flag) in mapRows(record, collegeId, ipedsUnitId)) {
      val result = upsertWithSavepoint(session) { CollegeIpedsChargesDao.upsert(session, row) }
      if (result.isFailure) {
        val error = result.exceptionOrNull()
        val reason = classifyUpsertFailure(error)
        tally.rowFailuresByReason.merge(reason, 1, Int::plus)
        // The WHOLE natural key, not only the institution: one record stages
        // 48 rows, so `ipeds_unit_id=222992` names the failure as one of 48
        // indistinguishable candidates and tells a fixer nothing.
        logUpsertSkip(
          reason,
          "ipeds-charges row",
          "ipeds_unit_id/charge_variable/academic_year",
          "$ipedsUnitId/${row.chargeVariable}/${row.academicYear}",
          record.recordNumber,
          error,
        )
      } else {
        tally.count.recordOutcome(result.getOrThrow())
        tally.cellsByFlag.merge(flag, 1, Int::plus)
        tally.stagedKeys += ChargeKey(collegeId, row.chargeVariable, row.academicYear)
      }
    }
  }

  /**
   * The per-RECORD mapper: well-formedness, a readable UNITID, a matching
   * college, and the first-record-wins duplicate rule.
   *
   * It returns the repo's own [MapResult] — the contract every other mapper in
   * [CsvIngestSupport]'s orbit states — rather than a nullable Int that also
   * tallies and mutates the caller's set on the way past. The decision is made
   * here; the FOLD is the loop's, which is what makes "one skip counted per
   * dropped record" a property of one place instead of five returns.
   *
   * [staged] is read, never written: the loop adds the accepted id.
   */
  private fun mapRecord(
    record: CSVRecord,
    headerSize: Int,
    knownIpedsUnitIds: Set<Int>,
    staged: Set<Int>,
  ): MapResult<Int> {
    if (!CsvIngestSupport.isWellFormed(record)) {
      logger.debug(
        "Skipping IC_AY row [line={}]: [{}] cells, header has [{}]",
        record.recordNumber,
        record.size(),
        headerSize,
      )
      return MapResult.Skipped(SkipReason.RowArityMismatch)
    }
    // Read as TEXT first, then parsed: `intOrNull` collapses "blank", "NA" and
    // "1006 54" into one null, and the three sibling branches below all say
    // what they saw. The cell and the line are what a fixer greps for --
    // [IpedsLoader.mapMissingIpedsUnitId]'s shape.
    val rawUnitId = stringOrNull(record, COL_UNITID)
    val ipedsUnitId = rawUnitId?.toIntOrNull()
    if (ipedsUnitId == null) {
      logger.debug(
        "Skipping IC_AY row [line={}]: [{}] is [{}], which is not a readable UNITID",
        record.recordNumber,
        COL_UNITID,
        rawUnitId ?: "<blank>",
      )
      return MapResult.Skipped(SkipReason.IpedsUnitIdNa)
    }
    if (ipedsUnitId !in knownIpedsUnitIds) {
      logger.debug(
        "Skipping IC_AY row [line={}]: no college for [ipeds_unit_id={}]",
        record.recordNumber,
        ipedsUnitId,
      )
      return MapResult.Skipped(SkipReason.NoCollegeForIpedsUnitId)
    }
    if (ipedsUnitId in staged) {
      logger.warn(
        "IC_AY file repeats [ipeds_unit_id={}] at [line={}]; keeping the first record",
        ipedsUnitId,
        record.recordNumber,
      )
      return MapResult.Skipped(SkipReason.DuplicateKeyInFile)
    }
    return MapResult.Mapped(ipedsUnitId, coercions = emptyMap())
  }

  /**
   * The RESHAPE itself: one IC_AY record's 235 columns read as EXACTLY 48
   * staged rows, each paired with the flag it was read under (the
   * `cells_by_flag` axis). Pure — it neither writes nor counts — so [load]
   * keeps the mapping and the write, exactly as [IpedsLoader.loadAttributes]
   * delegates to `mapHd`.
   *
   * Exactly 48, unconditionally: twelve stems × four carried years, with NO
   * branch that skips a cell. An ABSENT cell is still staged — a NULL
   * `amount_usd` under the X-flag that says why it is absent — and that is the
   * invariant, not an accident of the loop shape.
   *
   * Do not "optimise" this into `if (cell.amountUsd != null) add(...)`. It
   * would break two things at once. The flag is the only place unicoach
   * records WHY a price is missing, so dropping the row collapses
   * `not_applicable` (this institution has no such charge) and
   * `not_reported_by_institution` (it has one and did not report it) into the
   * same silence, and the canonical fill can no longer tell a family "this
   * college does not charge that" from "we do not know". And the skipped keys
   * would leave [Tally.stagedKeys], which is the prune's keep-set: a cell that
   * emptied upstream would stop being written AND stop being retired, so
   * [deleteNotStaged] would leave last year's amount in staging for the fill to
   * serve as current.
   */
  private fun mapRows(
    record: CSVRecord,
    collegeId: UUID,
    ipedsUnitId: Int,
  ): List<Pair<NewCollegeIpedsCharge, IpedsImputationFlag>> =
    buildList {
      for (stem in IpedsChargeVocabulary.STEMS) {
        for ((suffix, academicYear) in IpedsChargeVocabulary.ACADEMIC_YEAR_BY_SUFFIX) {
          val cell = readCell(record, stem, suffix, ipedsUnitId)
          add(
            NewCollegeIpedsCharge(
              collegeId = collegeId,
              chargeVariable = stem,
              academicYear = academicYear,
              amountUsd = cell.amountUsd,
              imputationFlag = cell.flag.code,
            ) to cell.flag,
          )
        }
      }
    }

  /**
   * Deletes what this file no longer carries — the departed institution, the
   * previous window's years after a survey-year bump, and the row this run
   * FAILED to write.
   *
   * The keep-set is [stagedKeys], the exact natural keys written, and that
   * third case is why: a keep-set built from three independent axes would spare
   * a failed row (its college, its variable and its year all appear elsewhere
   * in the run), leaving it holding the PREVIOUS file's amount for the
   * canonical fill to serve as current. A row this run could not write is not a
   * row this file carries.
   *
   * Skipped when the file staged NOTHING: an empty keep-set matches no row and
   * would delete the whole table, which is not what "this file carries nothing"
   * should mean.
   */
  private fun deleteNotStaged(
    session: SqlSession,
    stagedKeys: Set<ChargeKey>,
  ): Int {
    if (stagedKeys.isEmpty()) {
      logger.warn("ipeds-charges staged no rows; the prune is skipped and the previous staging stands")
      return 0
    }
    val pruned = CollegeIpedsChargesDao.deleteNotIn(session, stagedKeys).getOrThrow()
    if (pruned > 0) {
      logger.info(
        "ipeds-charges pruned [{}] staged row(s) this IC_AY file no longer carries " +
          "(a departed institution, or a superseded survey-year window)",
        pruned,
      )
    }
    return pruned
  }

  /**
   * One `(value, flag)` pair read as a unit, with the value-IFF-flag rule
   * applied ONCE, by [IpedsImputationFlag.mapReading]. Both directions are FATAL: an
   * unreadable flag, or a value that contradicts its own flag, is a changed
   * upstream format, and silently dropping either would put a number in front
   * of a family with no evidence behind it.
   */
  private fun readCell(
    record: CSVRecord,
    stem: String,
    suffix: String,
    ipedsUnitId: Int,
  ): Cell {
    val valueColumn = "$stem$suffix"
    val flagColumn = "$FLAG_PREFIX$valueColumn"
    val cellRef = CellRef.Published(valueColumn, ipedsUnitId, record.recordNumber)
    val rawFlag = stringOrNull(record, flagColumn)
    val flag =
      IpedsImputationFlag.fromCode(rawFlag)
        ?: error(
          "IC_AY [$flagColumn] for [ipeds_unit_id=$ipedsUnitId] [line=${record.recordNumber}] is " +
            "[${rawFlag ?: "<blank>"}], which is not one " +
            "of the published imputation codes ${IpedsImputationFlag.CODES} " +
            "(and [${IpedsImputationFlag.PROFESSIONAL_PRACTICE}] never occurs on a loaded variable)",
        )
    // IC_AY publishes exactly two cell shapes: unsigned whole dollars, or the
    // literal missing-value token. A BLANK is neither, and a blank read as
    // "missing" is a value column that emptied upstream arriving as ordinary
    // absence, with no number and no complaint. A SIGNED number is neither
    // either: `toIntOrNull` takes "-500", which then reaches the database as an
    // anonymous `amount_nonneg_check` row failure, long after the row identity
    // that would explain it went out of scope.
    val rawValue =
      stringOrNull(record, valueColumn)
        ?: error(
          "IC_AY [$valueColumn] for [ipeds_unit_id=$ipedsUnitId] [line=${record.recordNumber}] is blank; a " +
            "cell carries either a whole-dollar amount or the missing-value token [$MISSING_TOKEN]",
        )
    val amount =
      if (rawValue == MISSING_TOKEN) {
        null
      } else {
        rawValue.takeIf(WHOLE_DOLLARS::matches)?.toInt()
          ?: error(
            "IC_AY [$valueColumn] for [ipeds_unit_id=$ipedsUnitId] [line=${record.recordNumber}] is " +
              "[$rawValue], which is neither a whole-dollar " +
              "amount nor the missing-value token [$MISSING_TOKEN]",
          )
      }
    // The one home of the value-IFF-flag rule, shared with the canonical fill:
    // this call fatals when the pairing is broken in either direction.
    val reading = flag.mapReading(amount, cellRef)
    return Cell(
      amountUsd = (reading as? FigureReading.Present)?.value,
      flag = flag,
    )
  }

  private data class Cell(
    val amountUsd: Int?,
    val flag: IpedsImputationFlag,
  )

  /**
   * WHICH charge cell a reading is about, as row identity rather than as a
   * pre-formatted sentence fragment.
   *
   * It exists for two reasons at once. A cell is reached from two sides — the
   * published FILE and the staging TABLE — and their identities are genuinely
   * different (a CSV column plus a UNITID plus a line; a staged row's id, its
   * college and its stored key), so a single `String` parameter forced each
   * caller to invent its own prose and neither could be queried on. And the
   * message is needed almost never: both call sites build it ~180,000 times per
   * run for a fatal that fires when the upstream format has changed, so the
   * TEXT is built lazily, inside the failure lambda, from a value that is only
   * a few fields.
   */
  internal sealed interface CellRef {
    /** A cell of the published IC_AY file: its column, its record's UNITID, its line. */
    data class Published(
      val column: String,
      val ipedsUnitId: Int,
      val line: Long,
    ) : CellRef {
      override fun toString(): String = "$column (ipeds_unit_id=$ipedsUnitId, line=$line)"
    }

    /**
     * A row of `college_ipeds_charges`, named by what a fixer would query on:
     * the surrogate id, the owning college and the stored natural key. The
     * published column it came from is restored ([sourceVariable]) because that
     * is what a reader greps IPEDS for.
     */
    data class Staged(
      val id: CollegeIpedsChargeId,
      val collegeId: UUID,
      val chargeVariable: String,
      val academicYear: String,
      val sourceVariable: String,
    ) : CellRef {
      override fun toString(): String =
        "$sourceVariable (id=${id.value}, college_id=$collegeId, " +
          "charge_variable=$chargeVariable, academic_year=$academicYear)"
    }
  }

  companion object {
    private const val COL_UNITID = "UNITID"

    /** IC_AY's missing-value cell, byte for byte: one dot, never a blank. */
    internal const val MISSING_TOKEN = "."

    /**
     * The other shape IC_AY publishes in a value cell: UNSIGNED whole dollars.
     * A sign is a changed format, never a price — and `toIntOrNull` accepts
     * both `-500` and `+500`.
     */
    private val WHOLE_DOLLARS = Regex("^[0-9]+$")

    /** The X-flag partner of a `CHG*` column is plain `X` + the column name. */
    private const val FLAG_PREFIX = "X"

    /**
     * Every column read, BUILT from [IpedsChargeVocabulary] rather than
     * retyped: 97 names by hand is 97 chances to drop one, and a column missing
     * from this list loads as an absence indistinguishable from a real one.
     */
    internal val REQUIRED_COLUMNS: List<String> =
      buildList {
        add(COL_UNITID)
        for (stem in IpedsChargeVocabulary.STEMS) {
          for (suffix in IpedsChargeVocabulary.ACADEMIC_YEAR_BY_SUFFIX.keys) {
            add("$stem$suffix")
            add("$FLAG_PREFIX$stem$suffix")
          }
        }
      }
  }
}

/**
 * This code plus an amount as ONE [FigureReading]: a value exists exactly
 * when the code bears one, by construction. The ONE implementation of that
 * rule — the parse calls it on the way in and the canonical fill on the way
 * out, so neither can drift from the other or from the DB CHECK.
 *
 * An EXTENSION on [IpedsImputationFlag], not a member of it: the enum is the
 * shared vocabulary (RFC 162 owns it, and IC_AY and SFA read the same
 * thirteen codes), while REFUSING a broken pairing is this loader's policy --
 * SFA tallies the same shape instead of fataling on it.
 *
 * [cell] is the offending row's IDENTITY, not a sentence: it is rendered
 * only inside the failure lambdas, so the ~180,000 calls a run makes on the
 * happy path build no message at all, and the message a failure does build
 * names a row a fixer can query.
 */
internal fun IpedsImputationFlag.mapReading(
  amountUsd: Int?,
  cell: IpedsChargesLoader.CellRef,
): FigureReading<Int> =
  when (val reads = meaning) {
    is FlagMeaning.Bears -> {
      FigureReading.Present(
        requireNotNull(amountUsd) {
          "IC_AY [$cell] carries no amount under the value-bearing flag [$code]; " +
            "a flag that bears a value must have one"
        },
        reads.bearing,
      )
    }

    is FlagMeaning.Absent -> {
      require(amountUsd == null) {
        "IC_AY [$cell] carries [$amountUsd] under the valueless flag [$code]; " +
          "a value exists exactly when its flag bears one"
      }
      FigureReading.Absent(reads.absence)
    }
  }
