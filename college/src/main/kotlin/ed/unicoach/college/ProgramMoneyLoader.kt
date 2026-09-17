package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.statusfulDoubleCellInDomain
import ed.unicoach.db.Database
import ed.unicoach.db.dao.NewProgramMoneyStat
import ed.unicoach.db.dao.ProgramKey
import ed.unicoach.db.dao.ProgramMoneyDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The `program-money` fill (RFC 218): re-reads the pinned Scorecard
 * field-of-study CSV and rebuilds `program_money_stats` wholesale -- DELETE
 * this source's rows + batch re-insert in the phase's one transaction -- so
 * idempotency is by construction and the table is always a projection of the
 * snapshot in hand.
 *
 * IT IS NOT AN ARM OF [CanonicalMoneyLoader.fill]. That function is a
 * source-precedence loop over `ORDERED_SOURCES` resolving competing publishers
 * for one INSTITUTION-level key. These figures have exactly one publisher and a
 * different grain, so an arm inside that loop would deform it (brief 0011 D37).
 *
 * A SECOND PASS OVER THE SAME FILE, on purpose. The `fields` phase already
 * streams it once to write `college_programs`; holding ~684,000 staged money
 * rows in memory to save one pass is the wrong trade, and the rows this phase
 * writes point at program ids that phase created.
 *
 * THREE SILENCES, KEPT APART (RFC 206). Every cell is read through
 * [CsvIngestSupport]'s `statusful*` seam and NOTHING about sentinel recognition
 * is rebuilt here: a numeric cell is `reported`, `PS` is
 * `suppressed_by_publisher`, `NA` is `not_reported_by_institution`. Absence
 * rows are WRITTEN, not dropped -- "the publisher withheld this" is a fact a
 * family is owed.
 *
 * IT COMPUTES NOTHING. It maps and copies. There is no debt-to-earnings ratio
 * here or anywhere: the earnings column and the debt columns are different
 * cohorts from different publishers in different dollar bases, so a ratio over
 * them is a figure nobody published. The one arithmetic in this file is
 * [wholeDollars], which discards cents.
 */
class ProgramMoneyLoader(
  private val database: Database,
) {
  private val logger = LoggerFactory.getLogger(ProgramMoneyLoader::class.java)

  /** What one [fill] did: the operator's whole view of the phase. */
  data class FillResult(
    /** Data rows read from the CSV. */
    val seen: Int,
    /** Rows whose three cells were mapped -- each one writes one row per [ScorecardFieldOfStudyColumns.VARIABLES] member. */
    val mapped: Int,
    /** Rows written to `program_money_stats`. */
    val rowsWritten: Int,
    /** Every dropped row, under its own named reason -- never a silent loss. */
    val skipsByReason: Map<SkipReason, Int>,
    /** Per-status row counts after the fill: two thirds of this table is absence, and this is what says so. */
    val statusCounts: Map<FigureStatus, Int>,
    /** Mechanism A's tally over the status-preserving reads, by source column. */
    val fieldsCoercedToNull: Map<String, Int>,
    /** Cells this fill read as the publisher's own suppression, by column (RFC 206). */
    val cellsSuppressedByPublisher: Map<String, Int>,
    /** Present cells whose token this seam has never been taught, by column (RFC 218, D45). */
    val unrecognisedTokens: Map<String, Int>,
  ) {
    /** Rows dropped, however they were dropped. */
    val skipped: Int get() = skipsByReason.values.sum()
  }

  /**
   * Rebuilds `program_money_stats` from [fieldsCsv] in ONE transaction it owns.
   * A failure anywhere rolls the whole rebuild back and leaves the previous
   * fill standing.
   */
  suspend fun fill(fieldsCsv: File): FillResult =
    database.withConnection { session ->
      val programIds = ProgramMoneyDao.programIdsByKey(session).getOrThrow()

      // The rebuild's own first step, before a single row is staged: this
      // source's rows go, and the fill writes them back. Inside the same
      // transaction, so a failure below leaves the old fill standing.
      ProgramMoneyDao.deleteFactsOfSource(session, SOURCE).getOrThrow()

      val pass = streamAndStage(fieldsCsv, session, programIds)
      val result =
        FillResult(
          seen = pass.seen,
          mapped = pass.mapped,
          rowsWritten = pass.rowsWritten,
          skipsByReason = pass.skips,
          statusCounts = ProgramMoneyDao.countsByStatus(session).getOrThrow(),
          fieldsCoercedToNull = pass.absences.coercedToNull,
          cellsSuppressedByPublisher = pass.absences.suppressedByPublisher,
          unrecognisedTokens = pass.absences.unrecognisedTokens,
        )
      logFill(result)
      result
    }

  /** What one [streamAndStage] pass counted, kept together so [fill] reads as an orchestrator. */
  private data class StreamPass(
    val seen: Int,
    val mapped: Int,
    val rowsWritten: Int,
    val skips: Map<SkipReason, Int>,
    val absences: CellAbsenceTally,
  )

  /**
   * One pass over [fieldsCsv]: map each row with the shared `mapField`, resolve
   * the program id, stage one stat per measure, and flush in batches. The
   * per-row detail lives here so [fill] reads read-ids -> delete -> stage ->
   * count -> report.
   */
  private fun streamAndStage(
    fieldsCsv: File,
    session: SqlSession,
    programIds: Map<ProgramKey, java.util.UUID>,
  ): StreamPass {
    val absences = CellAbsenceTally()
    val skips = mutableMapOf<SkipReason, Int>()
    var seen = 0
    var mapped = 0
    var rowsWritten = 0
    val pending = mutableListOf<NewProgramMoneyStat>()

    fun flush() {
      if (pending.isEmpty()) return
      rowsWritten += ProgramMoneyDao.batchInsert(session, pending.toList()).getOrThrow()
      pending.clear()
    }

    parseCsv(fieldsCsv).use { records ->
      for (record in records) {
        seen++
        // The SAME skip rules the `fields` phase applied to this same file --
        // its own function, not a second copy of the reasoning.
        val fields =
          when (val mapResult = CollegeScorecardLoader.mapField(record)) {
            is MapResult.Skipped -> {
              skips.merge(mapResult.reason, 1, Int::plus)
              continue
            }

            is MapResult.Mapped -> {
              mapResult.value
            }
          }
        val programId =
          programIds[ProgramKey(fields.ipedsUnitId, fields.cipCode, fields.credentialLevel)]
        if (programId == null) {
          // Counted and named rather than assumed away; see
          // [SkipReason.NoProgramForKey].
          logger.debug(
            "Skipping program money row [line={}]: no college_programs row " +
              "[ipeds_unit_id={}] [cip_code={}] [credential_level={}]",
            record.recordNumber,
            fields.ipedsUnitId,
            fields.cipCode,
            fields.credentialLevel,
          )
          skips.merge(SkipReason.NoProgramForKey, 1, Int::plus)
          continue
        }
        mapped++
        for (variable in ScorecardFieldOfStudyColumns.VARIABLES) {
          pending += statRow(record, variable, programId, absences)
        }
        if (pending.size >= FLUSH_ROWS) flush()
      }
    }
    flush()
    return StreamPass(seen, mapped, rowsWritten, skips.toMap(), absences)
  }

  /**
   * One stored row: the publisher's cell, read through the status-preserving
   * seam and paired with its status by construction.
   *
   * The DOMAIN is `[0, Int.MAX_VALUE]` -- the loader-side twin of
   * `program_money_stats_amount_nonnegative_check` and of the column's own
   * INTEGER width. It is a coercion and not a fatal because one impossible cell
   * must not take a 684,000-row rebuild down; the coercion tally is what says
   * it happened, by column.
   */
  private fun statRow(
    record: CSVRecord,
    variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable,
    programId: java.util.UUID,
    absences: CellAbsenceTally,
  ): NewProgramMoneyStat {
    val cell =
      statusfulDoubleCellInDomain(
        record,
        variable.sourceVariable,
        AMOUNT_USD_MIN,
        AMOUNT_USD_MAX,
        absences,
      )
    return NewProgramMoneyStat(
      collegeProgramId = programId,
      measure = variable.measure,
      cohortFirstYear = variable.cohortFirstYear,
      cohortLastYear = variable.cohortLastYear,
      reading = cell.reading().toWholeDollars(),
      source = SOURCE,
      sourceVariable = variable.sourceVariable,
    )
  }

  /**
   * Whole US dollars from the publisher's cell, half up: the ONE place cents
   * are discarded. The absence arms pass through untouched -- an absence has no
   * value to round.
   */
  private fun FigureReading<Double>.toWholeDollars(): FigureReading<Int> =
    when (this) {
      is FigureReading.Present -> FigureReading.Present(wholeDollars(value), bearing)
      is FigureReading.Absent -> this
    }

  private fun logFill(result: FillResult) {
    logger.info(
      "Program money fill: [{}] row(s) seen -- [{}] mapped, [{}] program_money_stats row(s) written [{}], " +
        "[{}] row(s) skipped [{}]; coercions [{}]; publisher-suppressed cell(s) READ [{}]; " +
        "unrecognised token(s) READ [{}]",
      result.seen,
      result.mapped,
      result.rowsWritten,
      result.statusCounts.mapKeys { it.key.value },
      result.skipped,
      result.skipsByReason.mapKeys { it.key.kind },
      result.fieldsCoercedToNull,
      result.cellsSuppressedByPublisher,
      result.unrecognisedTokens,
    )
  }

  companion object {
    /**
     * The ONE place cents are discarded, and they are ROUNDED, never truncated
     * (Ian, 2026-09-16: "ignore the cents. they are not material.").
     *
     * HALF UP: `123.49` stores `123` and `123.50` stores `124`. The publisher's
     * ten-year monthly payment is the only one of the three that can arrive
     * with a fraction, and reading every column through this one function is
     * what makes "where did the cents go?" a question with one answer.
     *
     * [BigDecimal] rather than `(value + 0.5).toInt()`: the addition is a
     * second rounding, in binary, before the one the caller asked for -- and
     * `BigDecimal.valueOf` takes the decimal the publisher actually wrote
     * rather than the binary double nearest to it.
     */
    internal fun wholeDollars(value: Double): Int =
      BigDecimal
        .valueOf(value)
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact()

    /** The one publisher of every row this loader writes. */
    internal val SOURCE = MoneySource.SCORECARD

    /**
     * Dollars, never negative -- the loader-side twin of
     * `program_money_stats_amount_nonnegative_check`. The upper end is the
     * column's own INTEGER width and not a business bound: the publisher
     * states no cap, and inventing one would silently drop a real figure.
     */
    private const val AMOUNT_USD_MIN = 0.0
    private val AMOUNT_USD_MAX = Int.MAX_VALUE.toDouble()

    /**
     * Rows staged before a batch goes to the database. The fill writes ~684,000
     * rows, and holding them all to send one batch is a heap the phase does not
     * need; the batch writer chunks below this again.
     */
    private const val FLUSH_ROWS = 10_000
  }
}
