package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.StatusfulCell
import ed.unicoach.college.CsvIngestSupport.intOrNull
import ed.unicoach.college.CsvIngestSupport.statusfulDoubleCellInDomain
import ed.unicoach.college.CsvIngestSupport.statusfulIntCell
import ed.unicoach.college.CsvIngestSupport.statusfulIntCellInDomain
import ed.unicoach.college.ScorecardInstitutionColumns.BOOKSUPPLY
import ed.unicoach.college.ScorecardInstitutionColumns.CONTROL
import ed.unicoach.college.ScorecardInstitutionColumns.COSTT4_A
import ed.unicoach.college.ScorecardInstitutionColumns.GRAD_DEBT_MDN
import ed.unicoach.college.ScorecardInstitutionColumns.GROSS_USD_MAX
import ed.unicoach.college.ScorecardInstitutionColumns.GROSS_USD_MIN
import ed.unicoach.college.ScorecardInstitutionColumns.MD_EARN_WNE_P10
import ed.unicoach.college.ScorecardInstitutionColumns.NET_PRICE_BASE
import ed.unicoach.college.ScorecardInstitutionColumns.OTHEREXPENSE_FAM
import ed.unicoach.college.ScorecardInstitutionColumns.OTHEREXPENSE_OFF
import ed.unicoach.college.ScorecardInstitutionColumns.OTHEREXPENSE_ON
import ed.unicoach.college.ScorecardInstitutionColumns.PCTPELL
import ed.unicoach.college.ScorecardInstitutionColumns.RATE_MAX
import ed.unicoach.college.ScorecardInstitutionColumns.RATE_MIN
import ed.unicoach.college.ScorecardInstitutionColumns.ROOMBOARD_OFF
import ed.unicoach.college.ScorecardInstitutionColumns.ROOMBOARD_ON
import ed.unicoach.college.ScorecardInstitutionColumns.SUFFIX_PRIVATE
import ed.unicoach.college.ScorecardInstitutionColumns.SUFFIX_PUBLIC
import ed.unicoach.college.ScorecardInstitutionColumns.TUITIONFEE_IN
import ed.unicoach.college.ScorecardInstitutionColumns.TUITIONFEE_OUT
import ed.unicoach.college.ScorecardInstitutionColumns.UNITID
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.CollegeIpedsChargesDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeIpedsCharge
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ValueBearingStatus
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * The `canonical-money` fill (RFC 158): re-parses the pinned Scorecard
 * institution CSV with the status-preserving readers and rebuilds
 * `price_figures` and `cohort_money_stats` wholesale -- DELETE + re-insert in
 * the phase's one transaction (P12), so idempotency is by construction.
 *
 * The fill writes rows only for cells in the active source mapping's domain
 * (P7): within that domain every matched college gets a row with a value or a
 * status (`PrivacySuppressed` -> `suppressed_by_publisher`, blank/`NULL` ->
 * `not_reported_by_institution`, domain-coerced -> `not_reported_by_institution`
 * with mechanism A's tally). Cells no ingested source carries get NO row, and
 * the v1 fill emits `not_collected_by_us` never.
 *
 * Upstream-wins (P8) is [ORDERED_SOURCES]: sources are iterated in priority
 * order and the FIRST write wins per natural key. The v1 list is
 * `[scorecard]` only, but the rule is load-bearing before shape/02 adds IPEDS
 * sources ahead of it.
 *
 * Three mis-mappings are loader FATALS, not data: an arrangement-invariant
 * concept paired with a living arrangement (or vice versa, P3 -- a CHECK
 * cannot span tables), a blend variable routed at `price_figures` (P6's
 * loader half), and a banded row for a measure that does not band.
 *
 * Lives in the ingest module, not `service/.../costs` (P9): the fill maps and
 * copies, it never does money arithmetic.
 */
class CanonicalMoneyLoader internal constructor(
  private val database: Database,
  // The fill's SECOND input, declared rather than hidden in a DAO call: half
  // the price table comes from `college_ipeds_charges`, and `fill(csv)` read as
  // though the CSV were everything it consumed. It is a CONSTRUCTOR
  // collaborator and `internal`, not a public parameter of [fill], because the
  // thing it is handed is the fill's own live transaction: an internal seam a
  // test substitutes is a testable input, a public one is this module's pooled
  // connection escaping to any caller.
  private val stagedCharges: StagedChargesReader,
) {
  /** The production wiring: the fill reads its own transaction's staging snapshot. */
  constructor(database: Database) : this(database, DEFAULT_STAGED_CHARGES)

  /**
   * Reads the staged IC_AY rows one [fill] maps.
   *
   * LIFETIME CONTRACT, and it is not decoration: [read] is called exactly ONCE,
   * from inside the fill's own transaction, with a [SqlSession] valid only for
   * the duration of that call. The fill owns the session; an implementation
   * must not retain it, hand it on, or use it after returning, because the
   * connection under it is committed and returned to the pool when the
   * transaction ends, and every statement it prepares must be closed before it
   * returns. An exception it throws rolls the whole rebuild back.
   */
  internal fun interface StagedChargesReader {
    fun read(session: SqlSession): List<CollegeIpedsCharge>
  }

  /**
   * A staged `college_ipeds_charges` row carrying an imputation flag no
   * published code names (RFC 161).
   *
   * It is TYPED, and it carries the [charge] rather than a formatted sentence,
   * because it is not a data-quality event: a row reaches staging through a
   * parse that refuses unknown codes and a CHECK that admits only the 13
   * published ones, so finding one here means the column was written by
   * something other than this loader. The row is what a fixer queries on.
   */
  class CorruptStagedChargeException(
    val charge: CollegeIpedsCharge,
    val code: String,
  ) : RuntimeException(
      "staged IC_AY row [id=${charge.id.value}] [college_id=${charge.collegeId}] " +
        "[charge_variable=${charge.chargeVariable}] [academic_year=${charge.academicYear}] carries the " +
        "imputation flag [$code], which is not one of the published codes " +
        "${IpedsChargesLoader.ImputationFlag.CODES}; college_ipeds_charges was written by something " +
        "other than IpedsChargesLoader",
    )

  /**
   * WHY a staged IC_AY row could not be mapped — the axis, not the value.
   *
   * The two are separate facts a fixer acts on differently: a variable this
   * mapping does not name is a superseded or never-mapped stem, while an
   * academic year it cannot decode is a survey-year window that moved. The
   * offending VALUE is the nested key under each, so a consumer reads
   * `{"charge_variable": {"CHG2AT": 1}}` rather than having to split a packed
   * `"charge_variable=CHG2AT"` string on `=`.
   */
  enum class ChargeDrift(
    /** The JSON/log key for this axis; rendered at the edge, never stored typed-as-text. */
    val slug: String,
  ) {
    UNMAPPED_CHARGE_VARIABLE("charge_variable"),
    UNDECODABLE_ACADEMIC_YEAR("academic_year"),
  }

  /** What one [fill] did, for provenance (P11) and the stderr summary. */
  data class FillResult(
    val priceFigureRows: Int,
    val cohortMoneyStatRows: Int,
    /** Per-status row counts, typed all the way to the JSONB/log edge (risk 6.6: serialized as OUR status slugs, never column names). */
    val priceFigureStatusCounts: Map<FigureStatus, Int>,
    val cohortMoneyStatStatusCounts: Map<FigureStatus, Int>,
    /**
     * Per-SOURCE row counts (RFC 161): the upstream-wins split, so an operator
     * sees at a glance how much of the price table IPEDS won and how much the
     * Scorecard filled behind it. Typed like the status counts.
     */
    val priceFigureSourceCounts: Map<MoneySource, Int>,
    /** Distinct colleges the fill wrote at least one row for. */
    val collegesMatched: Int,
    /** CSV rows too short to be well-formed, skipped and counted (a loss class like the two below). */
    val rowsMalformed: Int,
    /** CSV rows with no matching college (the same rows `institutions` skipped). */
    val rowsWithoutCollege: Int,
    /** Matched rows whose CONTROL was missing or unparseable: their control-keyed cohort cells were skipped, never guessed private. */
    val rowsWithoutControl: Int,
    /** Mechanism A's tally over the status-preserving reads, by cell name. */
    val fieldsCoercedToNull: Map<String, Int>,
    /**
     * Staged IC_AY rows this fill could not map and PASSED OVER, by the drift
     * that made them unmappable (RFC 161; brief 0006 D6).
     *
     * Stale staging is a real state, not a defect: the prune runs in the
     * `ipeds-charges` phase, so a Scorecard-only run (no `--ic-ay`) never
     * prunes, and this fill must survive whatever the last IC_AY run left. It
     * is COUNTED rather than fatal so one superseded variable cannot take the
     * whole money rebuild down -- and counted rather than ignored so the drift
     * stays loud instead of becoming a silently missing price.
     *
     * NESTED and TYPED, like [priceFigureStatusCounts] and
     * [priceFigureSourceCounts] beside it: the [ChargeDrift] axis, then the
     * offending value under it. A flat `{"charge_variable=CHG2AT": 1}` is a key
     * no consumer can read without splitting on `=`.
     */
    val ipedsChargesIgnored: Map<ChargeDrift, Map<String, Int>>,
  ) {
    /** Staged IC_AY rows passed over, however they drifted. */
    val ipedsChargesIgnoredRows: Int get() = ipedsChargesIgnored.values.sumOf { it.values.sum() }
  }

  /**
   * Rebuilds both fact tables from [institutionCsv] in ONE transaction it
   * owns: resolve the college ids, map every row through [ORDERED_SOURCES]
   * first-write-wins, then DELETE + batch insert. A failure anywhere rolls
   * the whole rebuild back and leaves the previous fill standing.
   */
  suspend fun fill(institutionCsv: File): FillResult =
    database.withConnection { session ->
      val collegeIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow()
      val ipedsChargesIgnored = mutableMapOf<ChargeDrift, MutableMap<String, Int>>()
      val prices = LinkedHashMap<PriceKey, NewPriceFigure>()
      val stats = LinkedHashMap<StatKey, NewCohortMoneyStat>()
      val coercions = mutableMapOf<String, Int>()
      val matched = mutableSetOf<UUID>()
      var scorecard = ScorecardLosses()
      // The `when` is EXHAUSTIVE over the enum, with no catch-all: a member
      // added without a branch is a COMPILE error here, which is a better
      // extension point than the runtime one an `else` could offer -- and the
      // companion's own init check covers what an `else` never could, a member
      // given a branch but never RANKED in ORDERED_SOURCES.
      for (source in ORDERED_SOURCES) {
        when (source) {
          // IPEDS runs FIRST, so its three-tier published charges take every
          // key it carries and the Scorecard fills only what is left (P8).
          MoneySource.IPEDS_IC_AY -> {
            val mapping = mapIpedsCharges(stagedCharges.read(session))
            // Upstream-wins (P8) is folded in HERE, at the level that decides
            // precedence, instead of inside a callee handed three of this
            // block's own collections to mutate.
            for ((key, figure) in mapping.prices) prices.putIfAbsent(key, figure)
            matched += mapping.matched
            for ((drift, counts) in mapping.ignored) {
              val axis = ipedsChargesIgnored.getOrPut(drift) { mutableMapOf() }
              for ((value, n) in counts) axis.merge(value, n, Int::plus)
            }
          }

          MoneySource.SCORECARD -> {
            scorecard = mapScorecardCsv(institutionCsv, collegeIds, coercions, prices, stats, matched)
          }
        }
      }

      CanonicalMoneyDao.deleteAllPriceFigures(session).getOrThrow()
      CanonicalMoneyDao.deleteAllCohortMoneyStats(session).getOrThrow()
      val priceRows = CanonicalMoneyDao.insertPriceFigures(session, prices.values.toList()).getOrThrow()
      val statRows = CanonicalMoneyDao.insertCohortMoneyStats(session, stats.values.toList()).getOrThrow()
      val result =
        FillResult(
          priceFigureRows = priceRows,
          cohortMoneyStatRows = statRows,
          priceFigureStatusCounts = CanonicalMoneyDao.priceFigureCountsByStatus(session).getOrThrow(),
          cohortMoneyStatStatusCounts = CanonicalMoneyDao.cohortMoneyStatCountsByStatus(session).getOrThrow(),
          priceFigureSourceCounts = CanonicalMoneyDao.priceFigureCountsBySource(session).getOrThrow(),
          collegesMatched = matched.size,
          rowsMalformed = scorecard.rowsMalformed,
          rowsWithoutCollege = scorecard.rowsWithoutCollege,
          rowsWithoutControl = scorecard.rowsWithoutControl,
          fieldsCoercedToNull = coercions.toMap(),
          ipedsChargesIgnored =
            ipedsChargesIgnored.entries
              .sortedBy { it.key.slug }
              .associate { (drift, counts) -> drift to counts.toSortedMap().toMap() },
        )
      // The per-status breakdown is the operator-visible fact this phase
      // exists to keep (suppression must SURVIVE the fill), said where every
      // ingest diagnostic goes: the log, on stderr. Statuses flatten to their
      // slugs only here, at the edge.
      logger.info(
        "Canonical money fill: [{}] price_figures [{}] by source [{}] + [{}] cohort_money_stats [{}] " +
          "over [{}] college(s); " +
          "[{}] malformed row(s); [{}] row(s) without a college; [{}] row(s) without a CONTROL (control-keyed cells skipped); " +
          "coercions [{}]; [{}] staged IC_AY row(s) passed over as stale [{}]",
        result.priceFigureRows,
        result.priceFigureStatusCounts.mapKeys { it.key.value },
        result.priceFigureSourceCounts.mapKeys { it.key.value },
        result.cohortMoneyStatRows,
        result.cohortMoneyStatStatusCounts.mapKeys { it.key.value },
        result.collegesMatched,
        result.rowsMalformed,
        result.rowsWithoutCollege,
        result.rowsWithoutControl,
        result.fieldsCoercedToNull,
        result.ipedsChargesIgnoredRows,
        // Slugs at the edge, like the status and source maps above it.
        result.ipedsChargesIgnored.mapKeys { it.key.slug },
      )
      result
    }

  // ---------------------------------------------------------------------------
  // The IPEDS IC_AY mapping (RFC 161)
  // ---------------------------------------------------------------------------

  /**
   * Maps the staged IC_AY charge rows into the price accumulator.
   *
   * Reads STAGING, not the CSV: the canonical phase runs last, after
   * `search-index`, so the file it would re-parse has already been loaded into
   * `college_ipeds_charges` by the `ipeds-charges` phase (its write
   * precondition). The staging TABLE is what this fill depends on, exactly as
   * the money-vocabulary TABLES are (RFC 158 P2) -- so a run that supplies no
   * IC_AY file still serves whatever the last one loaded, rather than silently
   * dropping every IPEDS price for one Scorecard-only ingest.
   *
   * A staged variable or academic year this mapping does not name is COUNTED
   * and PASSED OVER, never fatal. It is the direct consequence of the sentence
   * above: the prune that retires superseded staging lives in the
   * `ipeds-charges` phase, so a Scorecard-only run cannot have run it, and a
   * fill that refused the leftovers would take the whole money rebuild down
   * over rows nothing asked it to serve. The count reaches the ingest summary
   * ([FillResult.ipedsChargesIgnored]), so drift is loud rather than invisible.
   */
  private fun mapIpedsCharges(charges: List<CollegeIpedsCharge>): MappedIpedsCharges {
    val prices = LinkedHashMap<PriceKey, NewPriceFigure>()
    val matched = mutableSetOf<UUID>()
    val ignored = mutableMapOf<ChargeDrift, MutableMap<String, Int>>()
    for (charge in charges) {
      val cell = IpedsChargeVocabulary.CELLS[charge.chargeVariable]
      if (cell == null) {
        recordStaleCharge(charge, ChargeDrift.UNMAPPED_CHARGE_VARIABLE, charge.chargeVariable, ignored)
        continue
      }
      val suffix = IpedsChargeVocabulary.SUFFIX_BY_ACADEMIC_YEAR[charge.academicYear]
      if (suffix == null) {
        recordStaleCharge(charge, ChargeDrift.UNDECODABLE_ACADEMIC_YEAR, charge.academicYear, ignored)
        continue
      }
      // Each call site raises its own failure with the context it holds: here
      // the staged ROW, which is what a fixer would query on. An unreadable
      // code IS fatal -- it reached staging through a parse that refuses
      // unknown codes, so finding one in the table means the column was written
      // by something other than this loader.
      val flag =
        IpedsChargesLoader.ImputationFlag.fromCode(charge.imputationFlag)
          ?: throw CorruptStagedChargeException(charge, charge.imputationFlag)
      matched += charge.collegeId
      // The REAL published column, year suffix restored: a reader who greps
      // IPEDS for `CHG2AY` finds a stem, `CHG2AY3` finds the figure.
      val sourceVariable = "${charge.chargeVariable}$suffix"
      val figure =
        priceFigure(
          collegeId = charge.collegeId,
          concept = cell.concept,
          residency = cell.residency,
          arrangement = cell.arrangement,
          academicYear = charge.academicYear,
          reading =
            flag.mapReading(
              charge.amountUsd,
              IpedsChargesLoader.CellRef.Staged(
                id = charge.id,
                collegeId = charge.collegeId,
                chargeVariable = charge.chargeVariable,
                academicYear = charge.academicYear,
                sourceVariable = sourceVariable,
              ),
            ),
          source = MoneySource.IPEDS_IC_AY,
          sourceVariable = sourceVariable,
          // The raw X-code rides on the row (the source-defined-codes rule):
          // our four-way status reading is a lossy summary of every published
          // code, and the code itself is what a later question is asked against.
          publisherFlag = charge.imputationFlag,
        )
      // `putIfAbsent`, not `put` — and it is DEFENCE, not an intra-source
      // precedence rule. Two staged rows cannot legitimately collide on this
      // key: staging is unique on
      // `(college_id, charge_variable, academic_year)`, and CELLS maps every
      // stem to a DISTINCT concept/residency/arrangement triple, so the stem
      // is recoverable from the key and two rows would have to share it.
      // First-write-wins is therefore never exercised here; it is what keeps a
      // corrupt duplicate from silently overwriting a good figure rather than a
      // decision about which IC_AY row outranks which. Cross-SOURCE precedence
      // (IPEDS ahead of the Scorecard) is decided in [fill], not here.
      prices.putIfAbsent(
        PriceKey(charge.collegeId, cell.concept, cell.residency, cell.arrangement, charge.academicYear),
        figure,
      )
    }
    return MappedIpedsCharges(prices, matched, ignored)
  }

  /**
   * What one IC_AY staging pass produced: the price rows it mapped, the
   * colleges it touched, and the drift it passed over — a VALUE the caller
   * folds in, not three of the caller's own collections mutated behind its
   * back. Upstream-wins then happens where precedence is decided.
   */
  private data class MappedIpedsCharges(
    val prices: Map<PriceKey, NewPriceFigure>,
    val matched: Set<UUID>,
    val ignored: Map<ChargeDrift, Map<String, Int>>,
  )

  /**
   * Passes over one unmappable staged row: tallies it on its drift axis AND
   * says which row it was.
   *
   * Both halves, in one place, because either alone is useless. The COUNT
   * reaches the ingest summary and the provenance row, so the drift is loud;
   * without the LOG, "1 staged IC_AY row passed over" names one row out of
   * ~180,000 and no college, which is not something anyone can act on.
   */
  private fun recordStaleCharge(
    charge: CollegeIpedsCharge,
    drift: ChargeDrift,
    value: String,
    ignored: MutableMap<ChargeDrift, MutableMap<String, Int>>,
  ) {
    ignored.getOrPut(drift) { mutableMapOf() }.merge(value, 1, Int::plus)
    logger.debug(
      "canonical-money passed over stale staged IC_AY row [id={}] [college_id={}] " +
        "[charge_variable={}] [academic_year={}]: [{}] [{}] is not one this fill maps",
      charge.id.value,
      charge.collegeId,
      charge.chargeVariable,
      charge.academicYear,
      drift.slug,
      value,
    )
  }

  // ---------------------------------------------------------------------------
  // The Scorecard mapping (the RFC 158 mapping table, verbatim)
  // ---------------------------------------------------------------------------

  /**
   * Maps the whole Scorecard institution CSV into both accumulators, returning
   * the three LOSSES the pass counted.
   *
   * Extracted so both branches of [ORDERED_SOURCES]' `when` are a single
   * delegating call at the same altitude: the branch used to inline an
   * 18-line CSV pass beside a one-line call, so "which sources fill this table,
   * in what order" could not be read without also reading how one of them
   * parses a file.
   */
  private fun mapScorecardCsv(
    institutionCsv: File,
    collegeIds: Map<Int, CollegeId>,
    coercions: MutableMap<String, Int>,
    prices: LinkedHashMap<PriceKey, NewPriceFigure>,
    stats: LinkedHashMap<StatKey, NewCohortMoneyStat>,
    matched: MutableSet<UUID>,
  ): ScorecardLosses {
    var rowsMalformed = 0
    var rowsWithoutCollege = 0
    var rowsWithoutControl = 0
    parseCsv(institutionCsv).use { records ->
      for (record in records) {
        if (!CsvIngestSupport.isWellFormed(record)) {
          // A short row is a LOSS this fill must count, like every other skip
          // class -- never a silent `continue`.
          rowsMalformed++
          continue
        }
        val collegeId = intOrNull(record, UNITID)?.let { collegeIds[it]?.value }
        if (collegeId == null) {
          rowsWithoutCollege++
          continue
        }
        matched += collegeId
        val controlMissing = mapScorecardRow(record, collegeId, coercions, prices, stats)
        if (controlMissing) rowsWithoutControl++
      }
    }
    return ScorecardLosses(rowsMalformed, rowsWithoutCollege, rowsWithoutControl)
  }

  /**
   * The Scorecard pass's three loss classes, as one value. Zero for all three
   * is also the honest answer when the pass did not run at all.
   */
  private data class ScorecardLosses(
    val rowsMalformed: Int = 0,
    val rowsWithoutCollege: Int = 0,
    val rowsWithoutControl: Int = 0,
  )

  /**
   * Maps one Scorecard row into both accumulators, returning TRUE when the
   * row's CONTROL was missing or unparseable. CONTROL resolution happens
   * exactly here, once: it keys the `*_PUB`/`*_PRIV` column choice and the
   * blended figures' residency scope, and a row without one must never be
   * silently classified private (the sibling loader refuses such a row) --
   * its control-keyed cohort cells are skipped and tallied instead, while
   * the control-independent cells still write.
   */
  private fun mapScorecardRow(
    record: CSVRecord,
    collegeId: UUID,
    coercions: MutableMap<String, Int>,
    prices: LinkedHashMap<PriceKey, NewPriceFigure>,
    stats: LinkedHashMap<StatKey, NewCohortMoneyStat>,
  ): Boolean {
    mapScorecardPrices(record, collegeId, coercions, prices)
    val control = intOrNull(record, CONTROL)
    mapScorecardStats(record, collegeId, public = control?.let { it == CONTROL_PUBLIC }, coercions, stats)
    return control == null
  }

  /** The eight `price_figures` cells: none of them is control-keyed. */
  private fun mapScorecardPrices(
    record: CSVRecord,
    collegeId: UUID,
    coercions: MutableMap<String, Int>,
    prices: LinkedHashMap<PriceKey, NewPriceFigure>,
  ) {
    fun price(
      concept: PriceConcept,
      residency: ResidencyBasis,
      arrangement: FigureArrangement,
      column: String,
    ) {
      val figure =
        priceFigure(
          collegeId = collegeId,
          concept = concept,
          residency = residency,
          arrangement = arrangement,
          academicYear = PUBLISHED_PRICE_ACADEMIC_YEAR,
          reading = grossCell(record, column, coercions).reading(),
          source = MoneySource.SCORECARD,
          sourceVariable = column,
          // The Scorecard publishes no per-cell imputation code; its one
          // sentinel (`PrivacySuppressed`) is already carried by the status.
          publisherFlag = null,
        )
      // Upstream-wins (P8): the first source to write a key keeps it.
      prices.putIfAbsent(PriceKey(collegeId, concept, residency, arrangement, figure.academicYear), figure)
    }

    // The published tuition pair lands as TWO rows: residency is a key axis,
    // never a pair of sibling columns.
    price(PriceConcept.TUITION_AND_FEES, ResidencyBasis.IN_STATE, FigureArrangement.NOT_APPLICABLE, TUITIONFEE_IN)
    price(PriceConcept.TUITION_AND_FEES, ResidencyBasis.OUT_OF_STATE, FigureArrangement.NOT_APPLICABLE, TUITIONFEE_OUT)
    price(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS, ROOMBOARD_ON)
    price(PriceConcept.HOUSING_AND_FOOD, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.OFF_CAMPUS, ROOMBOARD_OFF)
    price(PriceConcept.BOOKS_AND_SUPPLIES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.NOT_APPLICABLE, BOOKSUPPLY)
    price(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.ON_CAMPUS, OTHEREXPENSE_ON)
    price(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.OFF_CAMPUS, OTHEREXPENSE_OFF)
    price(PriceConcept.OTHER_EXPENSES, ResidencyBasis.NOT_APPLICABLE, FigureArrangement.WITH_FAMILY, OTHEREXPENSE_FAM)
  }

  /**
   * The ten `cohort_money_stats` cells. [public] null means the row carried
   * no usable CONTROL: the seven control-keyed cells (COSTT4_A and the NPT4
   * family, whose column choice AND residency scope both hang on it) are
   * skipped -- the caller tallies the loss -- and only the three
   * control-independent pooled figures write.
   */
  private fun mapScorecardStats(
    record: CSVRecord,
    collegeId: UUID,
    public: Boolean?,
    coercions: MutableMap<String, Int>,
    stats: LinkedHashMap<StatKey, NewCohortMoneyStat>,
  ) {
    fun stat(
      measure: MoneyMeasure,
      population: CohortPopulation,
      residencyScope: CohortResidencyScope,
      aidScope: CohortAidScope,
      incomeBand: IncomeBand?,
      vintage: String,
      cell: StatusfulCell<Double>,
      sourceVariable: String,
    ) {
      val row =
        cohortStat(
          collegeId = collegeId,
          measure = measure,
          population = population,
          residencyScope = residencyScope,
          aidScope = aidScope,
          incomeBand = incomeBand,
          vintage = vintage,
          cell = cell,
          source = MoneySource.SCORECARD,
          sourceVariable = sourceVariable,
        )
      stats.putIfAbsent(
        StatKey(collegeId, measure, population, residencyScope, aidScope, incomeBand, vintage),
        row,
      )
    }

    if (public != null) {
      // Control keys the *_PUB/*_PRIV column choice and the residency scope
      // of the blended figures: public reads _PUB and its cohorts are
      // in-state-rate-paying (RFC 157); all else reads _PRIV, scope `all`.
      val blendScope = if (public) CohortResidencyScope.IN_STATE_RATE_PAYING else CohortResidencyScope.ALL
      val netPriceSuffix = if (public) SUFFIX_PUBLIC else SUFFIX_PRIVATE

      // COSTT4_A: the blended average -- a cohort statistic with its true
      // population basis, structurally unable to enter price_figures (P6).
      stat(
        measure = MoneyMeasure.PUBLISHED_COST_BLEND,
        population = CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
        residencyScope = blendScope,
        aidScope = CohortAidScope.ALL,
        incomeBand = null,
        vintage = BLENDED_AVERAGE_ACADEMIC_YEAR,
        cell = grossCell(record, COSTT4_A, coercions).toDouble(),
        sourceVariable = COSTT4_A,
      )

      // NPT4 + the five bands, control-keyed as today. NO domain coercion:
      // negative net prices are valid (aid exceeding cost, 0022), and the
      // low-income bands go negative most often.
      fun netPrice(
        base: String,
        band: IncomeBand?,
      ) {
        val column = "$base$netPriceSuffix"
        stat(
          measure = MoneyMeasure.AVG_NET_PRICE,
          population = CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
          residencyScope = blendScope,
          aidScope = CohortAidScope.FEDERAL_AID_RECEIVING,
          incomeBand = band,
          vintage = BLENDED_AVERAGE_ACADEMIC_YEAR,
          cell = statusfulIntCell(record, column).toDouble(),
          sourceVariable = column,
        )
      }
      netPrice(NET_PRICE_BASE, null)
      for (band in IncomeBand.entries) netPrice("$NET_PRICE_BASE${band.npt4Digit}", band)
    }

    // The three undated figures (P5): the source pools or does not date them,
    // and fabricating a year to satisfy the key is exactly what `undated`
    // exists to refuse.
    stat(
      measure = MoneyMeasure.PELL_SHARE,
      population = CohortPopulation.UNDERGRADUATES,
      residencyScope = CohortResidencyScope.ALL,
      aidScope = CohortAidScope.ALL,
      incomeBand = null,
      vintage = VINTAGE_UNDATED,
      cell = statusfulDoubleCellInDomain(record, PCTPELL, RATE_MIN, RATE_MAX, PCTPELL, coercions),
      sourceVariable = PCTPELL,
    )
    stat(
      measure = MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
      population = CohortPopulation.FEDERAL_LOAN_BORROWING_COMPLETERS,
      residencyScope = CohortResidencyScope.ALL,
      aidScope = CohortAidScope.FEDERAL_LOAN_BORROWING,
      incomeBand = null,
      vintage = VINTAGE_UNDATED,
      cell = grossCell(record, GRAD_DEBT_MDN, coercions).toDouble(),
      sourceVariable = GRAD_DEBT_MDN,
    )
    stat(
      measure = MoneyMeasure.MEDIAN_EARNINGS_10Y,
      population = CohortPopulation.EMPLOYED_NOT_ENROLLED_10Y_AFTER_ENTRY,
      residencyScope = CohortResidencyScope.ALL,
      aidScope = CohortAidScope.ALL,
      incomeBand = null,
      vintage = VINTAGE_UNDATED,
      cell = grossCell(record, MD_EARN_WNE_P10, coercions).toDouble(),
      sourceVariable = MD_EARN_WNE_P10,
    )
  }

  /** A gross USD cell: non-negative by definition (mechanism A; the DB CHECK is the backstop). */
  private fun grossCell(
    record: CSVRecord,
    column: String,
    coercions: MutableMap<String, Int>,
  ): StatusfulCell<Int> = statusfulIntCellInDomain(record, column, GROSS_USD_MIN, GROSS_USD_MAX, column, coercions)

  /** The natural key of a `price_figures` row, for first-write-wins (P8). */
  private data class PriceKey(
    val collegeId: UUID,
    val concept: PriceConcept,
    val residency: ResidencyBasis,
    val arrangement: FigureArrangement,
    val academicYear: String,
  )

  /** The natural key of a `cohort_money_stats` row, NULL band included (P4). */
  private data class StatKey(
    val collegeId: UUID,
    val measure: MoneyMeasure,
    val population: CohortPopulation,
    val residencyScope: CohortResidencyScope,
    val aidScope: CohortAidScope,
    val incomeBand: IncomeBand?,
    val vintage: String,
  )

  companion object {
    private val logger = LoggerFactory.getLogger(CanonicalMoneyLoader::class.java)

    /**
     * How [fill] reads the staged IC_AY charges it maps: the whole table, in
     * the fill's OWN transaction, so the rebuild sees one consistent staging
     * snapshot.
     *
     * It is a parameter rather than a hidden DAO call because the fill's second
     * input deserves to appear in its signature -- `fill(csv)` read as though
     * the CSV were everything it consumed, while half the price table came from
     * a table it never named. The semantics are unchanged: canonical money
     * stays DERIVED from the database, so a run that supplies no `--ic-ay`
     * still rebuilds from the last IC_AY load rather than dropping every IPEDS
     * price.
     */
    internal val DEFAULT_STAGED_CHARGES: StagedChargesReader =
      StagedChargesReader { session -> CollegeIpedsChargesDao.list(session).getOrThrow() }

    /** The Scorecard CONTROL value meaning "public"; 2 and 3 are the private families. */
    private const val CONTROL_PUBLIC = 1

    /**
     * Upstream-wins (P8): sources in priority order, first write wins per
     * natural key. IPEDS IC_AY is FIRST (RFC 161) because it carries the three
     * residency tiers as separate first-class variables, while the Scorecard
     * collapses in-district into "in" -- so for the 269 institutions where the
     * two disagree, the Scorecard's `TUITIONFEE_IN` is the in-DISTRICT price
     * wearing an in-state label. The Scorecard still fills every key IC_AY
     * leaves empty: the ~2,300 IC_PY institutions, and every cohort statistic,
     * which IC_AY does not carry at all. Nothing is averaged.
     *
     * This list, not the [MoneySource] declaration order, is precedence.
     */
    internal val ORDERED_SOURCES = listOf(MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD)

    init {
      // What a catch-all `else` in the dispatch could never catch: a member
      // that HAS a mapping branch but was never ranked here would simply never
      // be iterated, so its rows would silently stop being written and no
      // branch would run to complain. Checked at class-init, so the phase
      // cannot start with a half-declared precedence order.
      val unranked = MoneySource.entries - ORDERED_SOURCES.toSet()
      check(unranked.isEmpty()) {
        "ORDERED_SOURCES must rank every MoneySource; ${unranked.map { it.value }} is/are unranked, so its " +
          "rows would silently never be written (RFC 161)"
      }
      check(ORDERED_SOURCES.size == ORDERED_SOURCES.toSet().size) {
        "ORDERED_SOURCES ranks a source twice: ${ORDERED_SOURCES.map { it.value }}"
      }
    }

    /**
     * The Scorecard published-price academic year ('YYYY-YY'), the stored
     * twin of `ScorecardVintage.PUBLISHED_PRICE` in the service cost domain:
     * the year is a property of the pinned snapshot and rides on every row
     * (P5), so a snapshot bump edits these two constants together.
     */
    internal const val PUBLISHED_PRICE_ACADEMIC_YEAR = "2022-23"

    /** The blended-average year (`ScorecardVintage.BLENDED_AVERAGE`): COSTT4_A and the NPT4 family. */
    internal const val BLENDED_AVERAGE_ACADEMIC_YEAR = "2021-22"

    /** The honest vintage for figures the source pools or does not date (P5). */
    internal const val VINTAGE_UNDATED = "undated"

    /**
     * The Scorecard NPT4 band digit per income band -- the correspondence
     * each [IncomeBand] member's own doc names ("Scorecard NPT41"). An
     * exhaustive `when` by NAME, never `entries` position: a reorder of the
     * enum must not be able to file a net price under the wrong bracket.
     */
    private val IncomeBand.npt4Digit: Int
      get() =
        when (this) {
          IncomeBand.UNDER_30K -> 1
          IncomeBand.K30_TO_48K -> 2
          IncomeBand.K48_TO_75K -> 3
          IncomeBand.K75_TO_110K -> 4
          IncomeBand.OVER_110K -> 5
        }

    /**
     * The blend variables (P6's loader half): the FK onto `price_concepts`
     * already makes a blend unrepresentable in `price_figures`; this list is
     * the belt to that brace, checked on every price row built.
     */
    internal val BLEND_VARIABLES: Set<String> =
      setOf("COSTT4_A", "NPT4_PUB", "NPT4_PRIV") +
        (1..5).flatMap { listOf("NPT4${it}_PUB", "NPT4${it}_PRIV") }

    /**
     * Builds one price row, enforcing the two price-side fatals: the P3
     * pairing rule (`not_applicable` arrangement IFF the concept is
     * arrangement-invariant -- a CHECK cannot span tables, so the loader is
     * the sanctioned second mechanism) and the P6 blend guard. Internal so
     * the tests provoke the fatals directly.
     */
    internal fun priceFigure(
      collegeId: UUID,
      concept: PriceConcept,
      residency: ResidencyBasis,
      arrangement: FigureArrangement,
      academicYear: String,
      reading: FigureReading<Int>,
      source: MoneySource,
      sourceVariable: String,
      /**
       * The publisher's own code for this cell, or null for a source that has
       * none. NOT defaulted: IC_AY carries an X-code on every figure, and a
       * default is exactly how a mapping drops one silently -- every caller
       * says which it is.
       */
      publisherFlag: String?,
    ): NewPriceFigure {
      check(sourceVariable !in BLEND_VARIABLES) {
        "blend variable [$sourceVariable] may not be routed at price_figures: a blend is a cohort " +
          "statistic and belongs in cohort_money_stats (RFC 158 P6)"
      }
      check((arrangement == FigureArrangement.NOT_APPLICABLE) == !concept.arrangementVaries) {
        "concept [${concept.value}] (arrangement_varies=${concept.arrangementVaries}) may not pair with " +
          "arrangement [${arrangement.value}]: an arrangement-invariant concept keys on not_applicable " +
          "and a varying one on a living arrangement (RFC 158 P3)"
      }
      return NewPriceFigure(
        collegeId = collegeId,
        priceConcept = concept,
        residencyBasis = residency,
        arrangement = arrangement,
        academicYear = academicYear,
        reading = reading,
        source = source,
        sourceVariable = sourceVariable,
        publisherFlag = publisherFlag,
      )
    }

    /** Builds one cohort row, enforcing the banded-measure fatal. Internal for the same reason as [priceFigure]. */
    internal fun cohortStat(
      collegeId: UUID,
      measure: MoneyMeasure,
      population: CohortPopulation,
      residencyScope: CohortResidencyScope,
      aidScope: CohortAidScope,
      incomeBand: IncomeBand?,
      vintage: String,
      cell: StatusfulCell<Double>,
      source: MoneySource,
      sourceVariable: String,
    ): NewCohortMoneyStat {
      check(incomeBand == null || measure.bandable) {
        "measure [${measure.value}] does not band by household income; a row banded [${incomeBand?.value}] " +
          "is a mis-mapping (RFC 158)"
      }
      return NewCohortMoneyStat(
        collegeId = collegeId,
        measure = measure,
        population = population,
        residencyScope = residencyScope,
        aidScope = aidScope,
        incomeBand = incomeBand,
        vintage = vintage,
        reading = cell.reading(),
        source = source,
        sourceVariable = sourceVariable,
      )
    }

    /** The one cell-reading -> [FigureReading] mapping (D3), written once: a value exists exactly when its status bears one, by construction. */
    private fun <T> StatusfulCell<T>.reading(): FigureReading<T> =
      when (this) {
        is StatusfulCell.Reported -> FigureReading.Present(value, ValueBearingStatus.REPORTED)
        StatusfulCell.SuppressedByPublisher -> FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER)
        StatusfulCell.NotReported -> FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)
      }

    /** An int reading widened to the NUMERIC stat column's shape, status preserved. */
    private fun StatusfulCell<Int>.toDouble(): StatusfulCell<Double> =
      when (this) {
        is StatusfulCell.Reported -> StatusfulCell.Reported(value.toDouble())
        StatusfulCell.SuppressedByPublisher -> StatusfulCell.SuppressedByPublisher
        StatusfulCell.NotReported -> StatusfulCell.NotReported
      }
  }
}
