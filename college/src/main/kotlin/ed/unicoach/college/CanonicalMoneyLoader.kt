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
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.CollegeIpedsChargesDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegeSfaDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeIpedsCharge
import ed.unicoach.db.models.CollegeSfaCell
import ed.unicoach.db.models.FactTable
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.IpedsImputationFlag
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCohortMoneyStat
import ed.unicoach.db.models.NewCohortPopulationCount
import ed.unicoach.db.models.NewPriceFigure
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ValueBearingStatus
import ed.unicoach.db.models.reading
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * WHERE one cohort statistic lives: the measure, the POPULATION it is about
 * and the aid relationship that bounds it -- the three columns that
 * together say which students a number describes.
 *
 * A type rather than three arguments, because the three must travel as one:
 * `avg_net_price` is filed for TWO populations in this store (the Title
 * IV-aided undergraduates the Scorecard's NPT4 band series describes, and
 * the full-time first-time aid cohort IPEDS SFA publishes a grant-aided net
 * price for), and a reader that keyed on the measure alone served the wrong
 * cohort's number under the right label -- RFC 166 tier-0 blocker 1, with
 * every test green.
 */
data class CohortCoordinate(
  val measure: MoneyMeasure,
  val population: CohortPopulation,
  val aidScope: CohortAidScope,
)

/**
 * The `canonical-money` fill (RFC 158): re-reads the pinned Scorecard CSV, the
 * staged IC_AY charges and this run's staged SFA cells, and rebuilds
 * `price_figures`, `cohort_money_stats` and `cohort_population_counts`
 * wholesale -- DELETE + re-insert in the phase's one transaction (P12), so
 * idempotency is by construction.
 *
 * The fill writes rows only for cells in the active source mapping's domain
 * (P7): within that domain every matched college gets a row with a value or a
 * status (`PrivacySuppressed` -> `suppressed_by_publisher`, blank/`NULL` ->
 * `not_reported_by_institution`, domain-coerced -> `not_reported_by_institution`
 * with mechanism A's tally). Cells no ingested source carries get NO row, and
 * the v1 fill emits `not_collected_by_us` never.
 *
 * Upstream-wins (P8) is [ORDERED_SOURCES]: sources are iterated in priority
 * order and the FIRST write wins per natural key. The list is IPEDS SFA, then
 * IPEDS IC_AY, then the Scorecard: a publisher's own number displaces the
 * Scorecard's copy of it wherever the two share a natural key.
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
        "[charge_variable=${charge.chargeVariable}] [academic_year=${charge.academicYear.label}] carries the " +
        "imputation flag [$code], which is not one of the published codes " +
        "${IpedsImputationFlag.CODES}; college_ipeds_charges was written by something " +
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
    /** Rows the `cohort_population_counts` rebuild wrote (RFC 162). */
    val cohortPopulationCountRows: Int,
    /** Per-status row counts, typed all the way to the JSONB/log edge (risk 6.6: serialized as OUR status slugs, never column names). */
    val priceFigureStatusCounts: Map<FigureStatus, Int>,
    val cohortMoneyStatStatusCounts: Map<FigureStatus, Int>,
    val cohortPopulationCountStatusCounts: Map<FigureStatus, Int>,
    /**
     * Per-SOURCE row counts (RFC 161): the upstream-wins split, so an operator
     * sees at a glance how much of the price table IPEDS won and how much the
     * Scorecard filled behind it. Typed like the status counts.
     */
    val priceFigureSourceCounts: Map<MoneySource, Int>,
    /** Staged SFA cells this fill read; 0 when no `sfa` phase has ever run (RFC 162). */
    val sfaCellsRead: Int,
    /** Distinct colleges the SFA source wrote at least one row for. */
    val sfaCollegesMatched: Int,
    /** Staged institutions whose college disappeared between the `sfa` phase and this fill. */
    val sfaInstitutionsWithoutCollege: Int,
    /** Staged institutions publishing NEITHER net-price family: their family-gated cells wrote no row. */
    val sfaInstitutionsWithoutFamily: Int,
    /** Staged cells whose flag bears a value but which carried none, by variable. */
    val sfaCellsWithoutValue: Map<String, Int>,
    /**
     * Variables this fill looked up that the staging phase did not stage, by
     * name (RFC 162). Zero by construction: the loader stages every
     * [SfaVariables.ALL] cell of every loaded institution, so a non-zero here
     * is this file and [SfaVariables] disagreeing about a name -- a whole
     * measure writing no row for any college in the country, which without
     * this count is a silently shorter table.
     */
    val sfaCellsNotStaged: Map<String, Int>,
    /** Distinct colleges the fill wrote at least one row for. */
    val collegesMatched: Int,
    /** CSV rows too short to be well-formed, skipped and counted (a loss class like the two below). */
    val rowsMalformed: Int,
    /** Scorecard CSV rows with no matching college (the same rows `institutions` skipped). */
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
   * Rebuilds the three fact tables from [institutionCsv] and [sfa] in ONE
   * transaction it owns: resolve the college ids, map every row through
   * [ORDERED_SOURCES] first-write-wins, then DELETE + batch insert. A failure
   * anywhere rolls the whole rebuild back and leaves the previous fill
   * standing.
   *
   * [sfa] is THIS run's SFA group, null when the run did not supply one: every
   * input the fill reads is then passed to it. `college_sfa` is emptied only
   * by the `sfa` phase, so reading the table unconditionally would let a run
   * that named no SFA file emit `ipeds_sfa` rows out of an earlier run's
   * staged file.
   */
  suspend fun fill(
    institutionCsv: File,
    sfa: SfaSources?,
  ): FillResult =
    database.withConnection { session ->
      val collegeIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow()
      val ipedsChargesIgnored = mutableMapOf<ChargeDrift, MutableMap<String, Int>>()
      val prices = LinkedHashMap<PriceKey, NewPriceFigure>()
      val stats = LinkedHashMap<StatKey, NewCohortMoneyStat>()
      val counts = LinkedHashMap<CountKey, NewCohortPopulationCount>()
      val coercions = mutableMapOf<String, Int>()
      var sfaFill = SfaFill()
      val matched = mutableSetOf<UUID>()
      var scorecard = ScorecardLosses()
      // The `when` is EXHAUSTIVE over the enum, with no catch-all: a member
      // added without a branch is a COMPILE error here, which is a better
      // extension point than the runtime one an `else` could offer -- and the
      // companion's own init check covers what an `else` never could, a member
      // given a branch but never RANKED in ORDERED_SOURCES.
      for (source in ORDERED_SOURCES) {
        when (source) {
          // IC_AY runs ahead of the Scorecard (SFA runs ahead of both; arm
          // order here is not execution order -- ORDERED_SOURCES is), so its
          // three-tier published charges take every key it carries (P8).
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

          // The IPEDS SFA fill (RFC 162), FIRST in the ordered list: where SFA
          // and the Scorecard describe the SAME fact (the NPT4 band series is
          // literally the Scorecard's copy of NPIS4x/NPT4x), the upstream
          // publisher's own number wins the natural key and the Scorecard's
          // copy is never written -- never averaged with it. Where they
          // describe DIFFERENT populations (NPT4_PUB is Title IV-aided,
          // NPIST is grant-aided) the keys differ and both rows land, which
          // is the whole point of storing the basis.
          MoneySource.IPEDS_SFA -> {
            sfaFill = fillFromSfa(session, sfa, collegeIds, stats, counts)
            matched += sfaFill.colleges
          }

          // Unreachable: this loop walks ORDERED_SOURCES, which deliberately
          // does not rank the Common Data Set -- its canonical rows are
          // written by the `cds` phase, out of the committed seed (RFC 170).
          // Spelled as a refusal rather than covered by an `else`, so a source
          // added to ORDERED_SOURCES with no mapping is still a compile error.
          MoneySource.COMMON_DATA_SET -> {
            error(
              "canonical-money does not fill [${MoneySource.COMMON_DATA_SET.value}]: the cds phase writes " +
                "its rows from the committed seed, and ORDERED_SOURCES must not rank it",
            )
          }
        }
      }

      // Wholesale rebuild (P12), bounded to the sources THIS fill publishes:
      // the `cds` phase owns the Common Data Set rows in these same tables
      // (RFC 170) and rebuilt them earlier in this very run.
      CanonicalMoneyDao.deleteFactsOfSources(session, FactTable.PRICE_FIGURES, ORDERED_SOURCES).getOrThrow()
      CanonicalMoneyDao.deleteFactsOfSources(session, FactTable.COHORT_MONEY_STATS, ORDERED_SOURCES).getOrThrow()
      CanonicalMoneyDao
        .deleteFactsOfSources(session, FactTable.COHORT_POPULATION_COUNTS, ORDERED_SOURCES)
        .getOrThrow()
      val priceRows = CanonicalMoneyDao.insertPriceFigures(session, prices.values.toList()).getOrThrow()
      val statRows = CanonicalMoneyDao.insertCohortMoneyStats(session, stats.values.toList()).getOrThrow()
      val countRows = CanonicalMoneyDao.insertCohortPopulationCounts(session, counts.values.toList()).getOrThrow()
      val result =
        FillResult(
          priceFigureRows = priceRows,
          cohortMoneyStatRows = statRows,
          cohortPopulationCountRows = countRows,
          priceFigureStatusCounts = CanonicalMoneyDao.priceFigureCountsByStatus(session).getOrThrow(),
          cohortMoneyStatStatusCounts = CanonicalMoneyDao.cohortMoneyStatCountsByStatus(session).getOrThrow(),
          cohortPopulationCountStatusCounts = CanonicalMoneyDao.cohortPopulationCountCountsByStatus(session).getOrThrow(),
          priceFigureSourceCounts = CanonicalMoneyDao.priceFigureCountsBySource(session).getOrThrow(),
          sfaCellsRead = sfaFill.cellsRead,
          sfaCollegesMatched = sfaFill.colleges.size,
          sfaInstitutionsWithoutCollege = sfaFill.institutionsWithoutCollege,
          sfaInstitutionsWithoutFamily = sfaFill.institutionsWithoutFamily,
          sfaCellsWithoutValue = sfaFill.cellsWithoutValue.toMap(),
          sfaCellsNotStaged = sfaFill.cellsNotStaged.toMap(),
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
          "+ [{}] cohort_population_counts [{}] over [{}] college(s); " +
          "SFA: [{}] staged cell(s) over [{}] college(s), [{}] cell(s) whose flag bears a value but " +
          "which carried none, [{}] variable(s) this fill read that nothing staged, " +
          "[{}] institution(s) without a college, [{}] publishing neither net-price family; " +
          "[{}] malformed row(s); [{}] Scorecard row(s) without a college; " +
          "[{}] row(s) without a CONTROL (control-keyed cells skipped); " +
          "coercions [{}]; [{}] staged IC_AY row(s) passed over as stale [{}]",
        result.priceFigureRows,
        result.priceFigureStatusCounts.mapKeys { it.key.value },
        result.priceFigureSourceCounts.mapKeys { it.key.value },
        result.cohortMoneyStatRows,
        result.cohortMoneyStatStatusCounts.mapKeys { it.key.value },
        result.cohortPopulationCountRows,
        result.cohortPopulationCountStatusCounts.mapKeys { it.key.value },
        result.collegesMatched,
        result.sfaCellsRead,
        result.sfaCollegesMatched,
        result.sfaCellsWithoutValue,
        result.sfaCellsNotStaged,
        result.sfaInstitutionsWithoutCollege,
        result.sfaInstitutionsWithoutFamily,
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
  // The IPEDS SFA mapping (RFC 162's measure table)
  // ---------------------------------------------------------------------------

  /**
   * The whole `ipeds_sfa` arm of [fill]: THIS run's staged cells, grouped per
   * institution, mapped into the two cohort accumulators, with every loss it
   * can suffer counted in the [SfaFill] it returns.
   *
   * A function rather than twenty-five lines inside the source `when`: [fill]
   * decides SOURCE PRECEDENCE, and reading one source's grouping, college
   * resolution and loss counters there put a second level of abstraction into
   * the loop -- visible as the pile of `sfa*` accumulators the enclosing
   * function used to carry.
   */
  private fun fillFromSfa(
    session: SqlSession,
    sfa: SfaSources?,
    collegeIds: Map<Int, CollegeId>,
    stats: LinkedHashMap<StatKey, NewCohortMoneyStat>,
    counts: LinkedHashMap<CountKey, NewCohortPopulationCount>,
  ): SfaFill {
    // THIS run's staged cells, or none: the SFA branch reads the aid year the
    // run's own argv declared (RFC 162), never whatever an earlier run left
    // standing in the staging table.
    val cells = sfa?.let { CollegeSfaDao.allCells(session, it.aidYearStart).getOrThrow() } ?: emptyList()
    val fill = SfaFill(cellsRead = cells.size)
    for ((unitId, institutionCells) in cells.groupBy { it.ipedsUnitId }) {
      val collegeId = collegeIds[unitId]?.value
      if (collegeId == null) {
        // The staging phase already dropped unmatched institutions, so this
        // can only be a college deleted between the two phases -- counted like
        // every other loss, never silently skipped.
        fill.institutionsWithoutCollege++
        continue
      }
      fill.colleges += collegeId
      val byVariable = institutionCells.associateBy { it.variable }
      val family = byVariable.sfaFamily(unitId)
      // Neither family: a named, counted skip. The institution's net price and
      // arrangement counts contribute no row, and an operator can see that it
      // happened instead of reading a silently shorter table.
      if (family == null) fill.institutionsWithoutFamily++
      mapSfaInstitution(collegeId, byVariable, family, fill, stats, counts)
    }
    return fill
  }

  /**
   * Maps one institution's staged SFA cells into the cohort accumulators: the
   * variable [family] is resolved ONCE by the caller, then each TABLE is
   * filled by the function that owns it -- money rows and headcounts have
   * different keys, different units and different reasons to change, exactly
   * as the Scorecard half of this file splits prices from stats.
   */
  private fun mapSfaInstitution(
    collegeId: UUID,
    cells: Map<String, CollegeSfaCell>,
    family: SfaFamily?,
    fill: SfaFill,
    stats: LinkedHashMap<StatKey, NewCohortMoneyStat>,
    counts: LinkedHashMap<CountKey, NewCohortPopulationCount>,
  ) {
    mapSfaMoneyStats(collegeId, cells, family, fill, stats)
    mapSfaPopulationCounts(collegeId, cells, family, fill, counts)
  }

  /**
   * The `cohort_money_stats` half of one institution's SFA cells.
   *
   * The FAMILY gate is why [family] exists and the reason it exists is
   * measured: SFA splits publics and privates into two variable families for
   * the same concept (`NPIST`/`NPGRN`, `NPIS4x`/`NPT4x`, `GIS4*`/`GRN4*`) and
   * they are MUTUALLY EXCLUSIVE -- zero institutions carry both, and the
   * unused family is flagged `A` cell by cell. Emitting the unused family
   * would give every college a shadow set of `not_applicable` rows for a
   * concept it already answers under its own name, so a null family (the
   * institution publishes neither, which the caller counts) contributes no
   * family-gated row at all. Inside the family it DOES use, an `A` is a real
   * answer and keeps its row (College of DuPage has no residence halls:
   * `xgis4on2 = A` beside a reported `gis4wf2`).
   *
   * The measures with one shape at every institution -- Pell, the aid mix --
   * are not gated: they are the same variables everywhere.
   */
  private fun mapSfaMoneyStats(
    collegeId: UUID,
    cells: Map<String, CollegeSfaCell>,
    family: SfaFamily?,
    fill: SfaFill,
    stats: LinkedHashMap<StatKey, NewCohortMoneyStat>,
  ) {
    // Keyed by VARIABLE NAME, not by an already-resolved cell: a name the
    // staging phase never staged is this file disagreeing with
    // [SfaVariables], and the closure is the one place that can tell the two
    // apart from "this institution published nothing".
    fun stat(
      variable: String,
      address: CohortCoordinate,
      residencyScope: CohortResidencyScope,
      incomeBand: IncomeBand? = null,
    ) {
      val cell = cells[variable]
      if (cell == null) {
        fill.cellsNotStaged.merge(variable, 1, Int::plus)
        return
      }
      // A cell whose flag bears a value but which carried none yields no row
      // at all: there is nothing honest to write. The MAPPER stays pure and
      // this accumulator's own closure folds the tally (the `MapResult`
      // convention).
      val reading = cell.reading(address.measure.unit)
      if (reading == null) {
        fill.cellsWithoutValue.merge(cell.variable, 1, Int::plus)
        return
      }
      val row =
        cohortStat(
          collegeId = collegeId,
          measure = address.measure,
          population = address.population,
          residencyScope = residencyScope,
          aidScope = address.aidScope,
          incomeBand = incomeBand,
          vintage = cell.aidYear,
          reading = reading,
          source = MoneySource.IPEDS_SFA,
          sourceVariable = cell.variable,
          publisherFlag = cell.flag.code,
        )
      // Upstream-wins (P8): SFA is first in ORDERED_SOURCES, so its row keeps
      // the key and the Scorecard's copy of the same fact never lands.
      stats.putIfAbsent(
        StatKey(collegeId, address.measure, address.population, residencyScope, address.aidScope, incomeBand, cell.aidYear),
        row,
      )
    }

    if (family != null) {
      for (suffix in SfaVariables.YEAR_SUFFIXES) {
        // Net price, the OVERALL figure: a GRANT-AIDED population, which is not
        // the Title IV-aided one the band series below describes. The Scorecard's
        // NPT4_PUB has no SFA twin (it is the band-count-weighted mean of the
        // five bands), so these two never share a key and never overwrite each
        // other -- two rows, two aid scopes, which is the fact.
        stat(family.netPrice(suffix), SFA_GRANT_AIDED_NET_PRICE, family.residencyScope)
        // The income bands: the TITLE IV-aided population, keyed exactly as the
        // Scorecard's NPT4x rows are, because they are the same measured fact
        // (198 of 200 publics matched to the dollar). That shared key is what
        // makes upstream-wins bite: the publisher's own number displaces the
        // Scorecard's copy of it.
        for (band in IncomeBand.entries) {
          stat(family.bandNetPrice(band, suffix), AVG_NET_PRICE, family.residencyScope, incomeBand = band)
        }
      }
    }

    // Pell, at the all-undergraduate level. The share is the source's own
    // published INTEGER PERCENT (UPGRNTP = 18), converted to the 0-1 share
    // every MeasureUnit.SHARE row carries by MeasureUnit.storedValueOf --
    // read off the MEASURE's unit, so no call site can forget it. The Pell RECIPIENT COUNT
    // is not here: a headcount is not money, so it is a
    // `cohort_population_counts` row (RFC 162).
    stat(SfaVariables.PELL_SHARE, PELL_SHARE, CohortResidencyScope.ALL)
    stat(SfaVariables.PELL_AVERAGE_AWARD, PELL_AVERAGE_AWARD, CohortResidencyScope.ALL)
    // The aid mix, all of it about the full-time first-time financial-aid
    // cohort. The aid scope follows the DENOMINATOR: a `_P` share is a share of
    // that whole cohort, so `all`; an `_A` average is an average over the
    // RECIPIENTS of that aid, so it carries its own receiving scope -- the same
    // rule `pell_receiving` states for the average Pell award above.
    for (mix in AID_MIX) {
      stat(
        "${mix.stem}_p",
        CohortCoordinate(mix.shareMeasure, CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT, CohortAidScope.ALL),
        CohortResidencyScope.ALL,
      )
      stat(
        "${mix.stem}_a",
        CohortCoordinate(mix.averageMeasure, CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT, mix.averageScope),
        CohortResidencyScope.ALL,
      )
    }
  }

  /**
   * The `cohort_population_counts` half of one institution's SFA cells: the
   * living-arrangement weights (family-gated, [mapSfaMoneyStats]'s reason),
   * the Pell recipient headcount and the fall cohort's residency split.
   */
  private fun mapSfaPopulationCounts(
    collegeId: UUID,
    cells: Map<String, CollegeSfaCell>,
    family: SfaFamily?,
    fill: SfaFill,
    counts: LinkedHashMap<CountKey, NewCohortPopulationCount>,
  ) {
    fun count(
      variable: String,
      population: CohortPopulation,
      residency: ResidencyBasis,
      arrangement: FigureArrangement,
    ) {
      val cell = cells[variable]
      if (cell == null) {
        fill.cellsNotStaged.merge(variable, 1, Int::plus)
        return
      }
      val reading = cell.reading()
      if (reading == null) {
        fill.cellsWithoutValue.merge(cell.variable, 1, Int::plus)
        return
      }
      val row =
        NewCohortPopulationCount(
          collegeId = collegeId,
          population = population,
          residencyBasis = residency,
          arrangement = arrangement,
          vintage = cell.aidYear,
          reading = reading.toHeadcount(),
          source = MoneySource.IPEDS_SFA,
          sourceVariable = cell.variable,
          publisherFlag = cell.flag.code,
        )
      counts.putIfAbsent(CountKey(collegeId, population, residency, arrangement, cell.aidYear), row)
    }

    if (family != null) {
      // The living-arrangement headcounts: the enrolment weights the published
      // net price averages away, and the reason GIS4ON/WF/OF/UN exist at all.
      for (suffix in SfaVariables.YEAR_SUFFIXES) {
        count(
          family.arrangementTotal(suffix),
          CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
          family.arrangementResidency,
          FigureArrangement.NOT_APPLICABLE,
        )
        for ((code, arrangement) in SfaVariables.ARRANGEMENT_CODES) {
          count(
            family.arrangement(code, suffix),
            CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
            family.arrangementResidency,
            arrangement,
          )
        }
      }
    }

    // The Pell recipient headcount (UPGRNTN): a number of PEOPLE, so it lands
    // here with the population it counts rather than in the money table under
    // an invented "count" unit. Not family-gated: every institution reports it.
    count(
      SfaVariables.PELL_RECIPIENT_COUNT,
      CohortPopulation.PELL_RECEIVING_UNDERGRADUATES,
      ResidencyBasis.NOT_APPLICABLE,
      FigureArrangement.NOT_APPLICABLE,
    )
    // The fall cohort's residency split: the four parts sum exactly to the
    // total (checked over all 1,580 institutions that report them), which is
    // what makes "the in-district price applies to 71% of this class" a
    // queryable fact rather than a claim. Not family-gated: a private reports
    // the TOTAL and flags the split not-applicable, and that `A` is the
    // honest answer to "how many pay the in-district rate".
    count(
      "scfa1n",
      CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
      ResidencyBasis.NOT_APPLICABLE,
      FigureArrangement.NOT_APPLICABLE,
    )
    for ((variable, residency) in SfaVariables.RESIDENCY_SPLIT) {
      count(
        variable,
        CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
        residency,
        FigureArrangement.NOT_APPLICABLE,
      )
    }
  }

  // ---------------------------------------------------------------------------
  // The IPEDS IC_AY mapping (RFC 161)
  // ---------------------------------------------------------------------------

  /**
   * Maps the staged IC_AY charge rows into the price accumulator.
   *
   * Reads STAGING, not the CSV: the canonical phase runs after every ROW phase
   * -- `ipeds-charges` among them -- so the file it would re-parse has already
   * been loaded into `college_ipeds_charges` by that phase (its write
   * precondition). Since RFC 169 the canonical phase is no longer last of the
   * derived rebuilds: `search-index` now runs AFTER it, because the index sums
   * `price_figures` into its published-price columns. That reordering does not
   * touch this dependency, which is on a ROW phase. The staging TABLE is what this fill depends on, exactly as
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
        recordStaleCharge(charge, ChargeDrift.UNDECODABLE_ACADEMIC_YEAR, charge.academicYear.label, ignored)
        continue
      }
      // Each call site raises its own failure with the context it holds: here
      // the staged ROW, which is what a fixer would query on. An unreadable
      // code IS fatal -- it reached staging through a parse that refuses
      // unknown codes, so finding one in the table means the column was written
      // by something other than this loader.
      val flag =
        IpedsImputationFlag.fromCode(charge.imputationFlag)
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
      charge.academicYear.label,
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
          academicYear = PUBLISHED_PRICE_YEAR,
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
      address: CohortCoordinate,
      residencyScope: CohortResidencyScope,
      incomeBand: IncomeBand?,
      vintage: AcademicYear?,
      cell: StatusfulCell<Double>,
      sourceVariable: String,
    ) {
      val row =
        cohortStat(
          collegeId = collegeId,
          measure = address.measure,
          population = address.population,
          residencyScope = residencyScope,
          aidScope = address.aidScope,
          incomeBand = incomeBand,
          vintage = vintage,
          reading = cell.reading(),
          source = MoneySource.SCORECARD,
          sourceVariable = sourceVariable,
        )
      stats.putIfAbsent(
        StatKey(collegeId, address.measure, address.population, residencyScope, address.aidScope, incomeBand, vintage),
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
        address = PUBLISHED_COST_BLEND,
        residencyScope = blendScope,
        incomeBand = null,
        vintage = BLENDED_AVERAGE_VINTAGE,
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
          address = AVG_NET_PRICE,
          residencyScope = blendScope,
          incomeBand = band,
          vintage = BLENDED_AVERAGE_VINTAGE,
          cell = statusfulIntCell(record, column).toDouble(),
          sourceVariable = column,
        )
      }
      netPrice(NET_PRICE_BASE, null)
      for (band in IncomeBand.entries) netPrice("$NET_PRICE_BASE${band.bandDigit}", band)
    }

    // The three undated figures (P5): the source pools or does not date them,
    // and fabricating a year to satisfy the key is exactly what `undated`
    // exists to refuse.
    stat(
      address = PELL_SHARE,
      residencyScope = CohortResidencyScope.ALL,
      incomeBand = null,
      vintage = VINTAGE_UNDATED,
      cell = statusfulDoubleCellInDomain(record, PCTPELL, RATE_MIN, RATE_MAX, PCTPELL, coercions),
      sourceVariable = PCTPELL,
    )
    stat(
      address = MEDIAN_DEBT_AT_COMPLETION,
      residencyScope = CohortResidencyScope.ALL,
      incomeBand = null,
      vintage = VINTAGE_UNDATED,
      cell = grossCell(record, GRAD_DEBT_MDN, coercions).toDouble(),
      sourceVariable = GRAD_DEBT_MDN,
    )
    stat(
      address = MEDIAN_EARNINGS_10Y,
      residencyScope = CohortResidencyScope.ALL,
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
    val academicYear: AcademicYear,
  )

  /** The natural key of a `cohort_population_counts` row (RFC 162). */
  private data class CountKey(
    val collegeId: UUID,
    val population: CohortPopulation,
    val residency: ResidencyBasis,
    val arrangement: FigureArrangement,
    val vintage: AcademicYear,
  )

  /** The natural key of a `cohort_money_stats` row, NULL band included (P4). */
  private data class StatKey(
    val collegeId: UUID,
    val measure: MoneyMeasure,
    val population: CohortPopulation,
    val residencyScope: CohortResidencyScope,
    val aidScope: CohortAidScope,
    val incomeBand: IncomeBand?,
    val vintage: AcademicYear?,
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
     * natural key. IPEDS SFA is FIRST (RFC 162 D5): the Scorecard's NPT4 band
     * series is a copy of SFA's own NPIS4x/NPT4x, so the publisher's number
     * displaces the copy rather than being averaged with it. Only where the
     * keys are identical -- a fact the two sources describe about the SAME
     * population -- does anything get displaced.
     *
     * IPEDS IC_AY is next (RFC 161) because it carries the three residency
     * tiers as separate first-class variables, while the Scorecard collapses
     * in-district into "in" -- so for the 269 institutions where the two
     * disagree, the Scorecard's `TUITIONFEE_IN` is the in-DISTRICT price
     * wearing an in-state label. The Scorecard still fills every key the two
     * IPEDS surveys leave empty: the ~2,300 IC_PY institutions, and the cohort
     * statistics neither carries. Nothing is averaged.
     *
     * This list, not the [MoneySource] declaration order, is precedence.
     */
    internal val ORDERED_SOURCES = listOf(MoneySource.IPEDS_SFA, MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD)

    /**
     * The sources whose canonical rows another phase writes, and which
     * [ORDERED_SOURCES] therefore must NOT rank (RFC 170): the Common Data Set
     * facts are read out of the committed CDS seed by the `cds` phase, in its
     * own transaction, and this fill never sees them. Named here so the
     * unranked-source check below can tell "written elsewhere" from "silently
     * never written", which is the mistake that check exists to catch.
     */
    internal val SOURCES_FILLED_ELSEWHERE = setOf(MoneySource.COMMON_DATA_SET)

    init {
      // What a catch-all `else` in the dispatch could never catch: a member
      // that HAS a mapping branch but was never ranked here would simply never
      // be iterated, so its rows would silently stop being written and no
      // branch would run to complain. Checked at class-init, so the phase
      // cannot start with a half-declared precedence order.
      val unranked = MoneySource.entries - ORDERED_SOURCES.toSet() - SOURCES_FILLED_ELSEWHERE
      check(unranked.isEmpty()) {
        "ORDERED_SOURCES must rank every MoneySource this fill writes; ${unranked.map { it.value }} is/are " +
          "neither ranked nor declared SOURCES_FILLED_ELSEWHERE, so its rows would silently never be " +
          "written (RFC 161)"
      }
      check(ORDERED_SOURCES.size == ORDERED_SOURCES.toSet().size) {
        "ORDERED_SOURCES ranks a source twice: ${ORDERED_SOURCES.map { it.value }}"
      }
    }

    /**
     * The Scorecard published-price academic year, the stored twin of
     * `FigureGroup.PUBLISHED_PRICE` in the service cost domain:
     * the year is a property of the pinned snapshot and rides on every row
     * (P5), so a snapshot bump edits these two constants together.
     */
    internal val PUBLISHED_PRICE_YEAR = AcademicYear(2022)

    /** The blended-average year (`FigureGroup.BLENDED_AVERAGE`): COSTT4_A and the NPT4 family. */
    internal val BLENDED_AVERAGE_VINTAGE = AcademicYear(2021)

    /** COSTT4_A, the blended published price: Title IV-aided undergraduates, whole cohort. */
    val PUBLISHED_COST_BLEND: CohortCoordinate =
      CohortCoordinate(
        MoneyMeasure.PUBLISHED_COST_BLEND,
        CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
        CohortAidScope.ALL,
      )

    /** The NPT4 family (Scorecard) and SFA's NPIS4/NPT4 band twins: federal-aid-receiving Title IV undergraduates. */
    val AVG_NET_PRICE: CohortCoordinate =
      CohortCoordinate(
        MoneyMeasure.AVG_NET_PRICE,
        CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
        CohortAidScope.FEDERAL_AID_RECEIVING,
      )

    /**
     * SFA's OVERALL net price: a GRANT-AIDED, full-time first-time cohort --
     * the same measure as [AVG_NET_PRICE] about DIFFERENT students, filed once
     * per SFA aid year. Population and aid scope separate the two, never the
     * vintage. It is here so a reader can be tested against the fact that these
     * two are different addresses.
     */
    val SFA_GRANT_AIDED_NET_PRICE: CohortCoordinate =
      CohortCoordinate(
        MoneyMeasure.AVG_NET_PRICE,
        CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
        CohortAidScope.GRANT_AIDED,
      )

    /** GRAD_DEBT_MDN: the completers who borrowed federally. Undated (P5). */
    val MEDIAN_DEBT_AT_COMPLETION: CohortCoordinate =
      CohortCoordinate(
        MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
        CohortPopulation.FEDERAL_LOAN_BORROWING_COMPLETERS,
        CohortAidScope.FEDERAL_LOAN_BORROWING,
      )

    /** MD_EARN_WNE_P10: employed, not enrolled, ten years after entry. Undated (P5). */
    val MEDIAN_EARNINGS_10Y: CohortCoordinate =
      CohortCoordinate(
        MoneyMeasure.MEDIAN_EARNINGS_10Y,
        CohortPopulation.EMPLOYED_NOT_ENROLLED_10Y_AFTER_ENTRY,
        CohortAidScope.ALL,
      )

    /** PCTPELL / UPGRNTP: the Pell share of all undergraduates. */
    val PELL_SHARE: CohortCoordinate =
      CohortCoordinate(MoneyMeasure.PELL_SHARE, CohortPopulation.UNDERGRADUATES, CohortAidScope.ALL)

    /** UPGRNTA: the average Pell award, over its RECIPIENTS. */
    val PELL_AVERAGE_AWARD: CohortCoordinate =
      CohortCoordinate(
        MoneyMeasure.PELL_AVERAGE_AWARD,
        CohortPopulation.UNDERGRADUATES,
        CohortAidScope.PELL_RECEIVING,
      )

    /**
     * Every cohort address the NAMED fills write -- the write side of the
     * address grid, exported for the one consumer that has to agree with it.
     *
     * DERIVED, not a list beside the writes: each constant above is what the
     * `stat(...)` call site itself passes, so an address that moves moves here
     * too and this set cannot describe writes that no longer happen. The aid
     * MIX rows (`AID_MIX`, one pair per aid type) are deliberately absent: their
     * measure is data, not a constant, and no consumer surface reads them.
     *
     * `:service` states the same grid from the other end
     * (`CostField.figureAddress`) in this repo's own wire vocabulary; neither
     * side can be derived from the other, so `CanonicalAddressContractTest`
     * ENFORCES the agreement instead. Without it, a fill that moves serves a
     * different cohort's number under the same label and nothing fails -- the
     * defect RFC 166 tier-0 blocker 1 actually was.
     */
    val COHORT_ADDRESSES: Set<CohortCoordinate> =
      setOf(
        PUBLISHED_COST_BLEND,
        AVG_NET_PRICE,
        SFA_GRANT_AIDED_NET_PRICE,
        MEDIAN_DEBT_AT_COMPLETION,
        MEDIAN_EARNINGS_10Y,
        PELL_SHARE,
        PELL_AVERAGE_AWARD,
      )

    /**
     * The honest vintage for figures the source pools or does not date (P5):
     * an ABSENT year (RFC 170, D14), not a magic string a reader must know is
     * not a year.
     */
    internal val VINTAGE_UNDATED: AcademicYear? = null

    /**
     * The family this institution publishes, or NULL when it publishes
     * neither -- which the caller counts as a named skip rather than dropping
     * silently.
     *
     * Net price alone decides it: the families are mutually exclusive across
     * every measure, and one probe that every institution answers beats four
     * that could disagree. BOTH is measured at zero institutions over four aid
     * years, so it is a mis-read of the source (or a vocabulary change), never
     * a shape to emit two shadow row sets for -- a `check`, like every other
     * publisher-vocabulary fatal in this fill.
     */
    private fun Map<String, CollegeSfaCell>.sfaFamily(ipedsUnitId: Int): SfaFamily? {
      val used =
        SfaFamily.entries.filter { family ->
          SfaVariables.YEAR_SUFFIXES.any { suffix ->
            val cell = this["${family.netPriceStem}$suffix"]
            cell != null && cell.flag.status != FigureStatus.NOT_APPLICABLE
          }
        }
      check(used.size <= 1) {
        "SFA institution [ipeds_unit_id=$ipedsUnitId] publishes BOTH net-price families " +
          "${used.map { it.name }}; they name the same concepts and are mutually exclusive, so which " +
          "one states its price is a review (RFC 162)"
      }
      return used.singleOrNull()
    }

    /**
     * The aid mix: the SFA stem, the measure its `_P` share lands as, the
     * measure its `_A` per-recipient average lands as, and the aid scope that
     * average is an average OVER. Declared once, so the two halves of one
     * source can never be filed under different bases.
     *
     * The share half needs no column here because it is the same for all four:
     * a `_P` is a share of the whole cohort, i.e. [CohortAidScope.ALL]. The
     * average half differs per row, which is exactly why the scope is carried
     * beside the measure rather than passed at the call site.
     */
    private data class AidMix(
      val stem: String,
      val shareMeasure: MoneyMeasure,
      val averageMeasure: MoneyMeasure,
      val averageScope: CohortAidScope,
    )

    private val AID_MIX =
      listOf(
        AidMix(
          "fgrnt",
          MoneyMeasure.FEDERAL_GRANT_SHARE,
          MoneyMeasure.FEDERAL_GRANT_AVERAGE_AWARD,
          CohortAidScope.FEDERAL_GRANT_RECEIVING,
        ),
        AidMix(
          "sgrnt",
          MoneyMeasure.STATE_LOCAL_GRANT_SHARE,
          MoneyMeasure.STATE_LOCAL_GRANT_AVERAGE_AWARD,
          CohortAidScope.STATE_LOCAL_GRANT_RECEIVING,
        ),
        AidMix(
          "igrnt",
          MoneyMeasure.INSTITUTIONAL_GRANT_SHARE,
          MoneyMeasure.INSTITUTIONAL_GRANT_AVERAGE_AWARD,
          CohortAidScope.INSTITUTIONAL_GRANT_RECEIVING,
        ),
        // LOAN_P/LOAN_A count ANY loan -- federal, institutional or private --
        // so the average's scope is `loan_receiving`, never the Scorecard's
        // federal-only `federal_loan_borrowing`.
        AidMix(
          "loan",
          MoneyMeasure.STUDENT_LOAN_SHARE,
          MoneyMeasure.STUDENT_LOAN_AVERAGE_AMOUNT,
          CohortAidScope.LOAN_RECEIVING,
        ),
      )

    init {
      // The stems are the STAGING vocabulary's (SfaVariables.AID_MIX_STEMS);
      // what each pair MEANS is this table's. Pinned at class-init rather than
      // trusted: a stem here that nothing staged would map a whole measure to
      // no rows at all, and a stem staged but missing here would stage cells
      // nothing reads.
      check(AID_MIX.map { it.stem }.toSet() == SfaVariables.AID_MIX_STEMS.toSet()) {
        "the aid-mix measure table ${AID_MIX.map { it.stem }} and the staged aid-mix variables " +
          "${SfaVariables.AID_MIX_STEMS} name different stems"
      }
    }

    /**
     * A reading of a HEADCOUNT: the same status, the value narrowed to the
     * integer a number of people is. `cohort_population_counts.headcount` is
     * INTEGER, and rounding here rather than at the JDBC edge keeps the
     * narrowing visible.
     */
    private fun FigureReading<Double>.toHeadcount(): FigureReading<Int> =
      when (this) {
        is FigureReading.Present -> FigureReading.Present(Math.round(value).toInt(), bearing)
        is FigureReading.Absent -> this
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
      academicYear: AcademicYear,
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
      vintage: AcademicYear?,
      reading: FigureReading<Double>,
      /** The [ORDERED_SOURCES] publisher this row is attributed to: REQUIRED, because upstream-wins reads it. */
      source: MoneySource,
      sourceVariable: String,
      publisherFlag: String? = null,
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
        reading = reading,
        source = source,
        sourceVariable = sourceVariable,
        publisherFlag = publisherFlag,
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

/**
 * Every number the `ipeds_sfa` arm of [CanonicalMoneyLoader.fill] produced,
 * folded as it goes: what it read, which colleges it wrote for, and each loss
 * class it can suffer.
 *
 * One holder rather than six accumulators declared in `fill()` and handed down
 * three call levels -- the shape that made the enclosing function carry a
 * dozen locals belonging to one source.
 */
private class SfaFill(
  /** Staged cells this fill read; 0 when the run supplied no SFA group. */
  val cellsRead: Int = 0,
) {
  /** Colleges the SFA source wrote at least one row for. */
  val colleges = mutableSetOf<UUID>()

  /** Staged institutions whose college disappeared between the `sfa` phase and this fill. */
  var institutionsWithoutCollege = 0

  /** Staged institutions publishing NEITHER net-price family. */
  var institutionsWithoutFamily = 0

  /** Cells whose flag bears a value but which carried none, by variable. */
  val cellsWithoutValue = mutableMapOf<String, Int>()

  /** Variables this fill asked for that the staging phase never staged, by name. */
  val cellsNotStaged = mutableMapOf<String, Int>()
}
