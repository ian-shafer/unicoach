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
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure
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
class CanonicalMoneyLoader(
  private val database: Database,
) {
  /** What one [fill] did, for provenance (P11) and the stderr summary. */
  data class FillResult(
    val priceFigureRows: Int,
    val cohortMoneyStatRows: Int,
    /** Per-status row counts, typed all the way to the JSONB/log edge (risk 6.6: serialized as OUR status slugs, never column names). */
    val priceFigureStatusCounts: Map<FigureStatus, Int>,
    val cohortMoneyStatStatusCounts: Map<FigureStatus, Int>,
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
  )

  /**
   * Rebuilds both fact tables from [institutionCsv] in ONE transaction it
   * owns: resolve the college ids, map every row through [ORDERED_SOURCES]
   * first-write-wins, then DELETE + batch insert. A failure anywhere rolls
   * the whole rebuild back and leaves the previous fill standing.
   */
  suspend fun fill(institutionCsv: File): FillResult =
    database.withConnection { session ->
      val collegeIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow()
      val prices = LinkedHashMap<PriceKey, NewPriceFigure>()
      val stats = LinkedHashMap<StatKey, NewCohortMoneyStat>()
      val coercions = mutableMapOf<String, Int>()
      var rowsMalformed = 0
      var rowsWithoutCollege = 0
      var rowsWithoutControl = 0
      val matched = mutableSetOf<UUID>()
      for (source in ORDERED_SOURCES) {
        when (source) {
          SOURCE_SCORECARD -> {
            parseCsv(institutionCsv).use { records ->
              for (record in records) {
                if (!CsvIngestSupport.isWellFormed(record)) {
                  // A short row is a LOSS this fill must count, like every
                  // other skip class -- never a silent `continue`.
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
          }

          // A source in the ordered list with no mapping branch must fail the
          // phase loudly, not contribute nothing: this is exactly the path
          // shape/02/03 extend (P8).
          else -> {
            error("unmapped canonical-money source [$source]")
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
          collegesMatched = matched.size,
          rowsMalformed = rowsMalformed,
          rowsWithoutCollege = rowsWithoutCollege,
          rowsWithoutControl = rowsWithoutControl,
          fieldsCoercedToNull = coercions.toMap(),
        )
      // The per-status breakdown is the operator-visible fact this phase
      // exists to keep (suppression must SURVIVE the fill), said where every
      // ingest diagnostic goes: the log, on stderr. Statuses flatten to their
      // slugs only here, at the edge.
      logger.info(
        "Canonical money fill: [{}] price_figures [{}] + [{}] cohort_money_stats [{}] over [{}] college(s); " +
          "[{}] malformed row(s); [{}] row(s) without a college; [{}] row(s) without a CONTROL (control-keyed cells skipped); coercions [{}]",
        result.priceFigureRows,
        result.priceFigureStatusCounts.mapKeys { it.key.value },
        result.cohortMoneyStatRows,
        result.cohortMoneyStatStatusCounts.mapKeys { it.key.value },
        result.collegesMatched,
        result.rowsMalformed,
        result.rowsWithoutCollege,
        result.rowsWithoutControl,
        result.fieldsCoercedToNull,
      )
      result
    }

  // ---------------------------------------------------------------------------
  // The Scorecard mapping (the RFC 158 mapping table, verbatim)
  // ---------------------------------------------------------------------------

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
          cell = grossCell(record, column, coercions),
          sourceVariable = column,
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

    /** The one source name every row of the v1 fill records. */
    const val SOURCE_SCORECARD = "scorecard"

    /** The Scorecard CONTROL value meaning "public"; 2 and 3 are the private families. */
    private const val CONTROL_PUBLIC = 1

    /**
     * Upstream-wins (P8): sources in priority order, first write wins per
     * natural key. v1 is the Scorecard alone; shape/02/03 prepend IPEDS
     * sources here and the rule is already load-bearing.
     */
    internal val ORDERED_SOURCES = listOf(SOURCE_SCORECARD)

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
      cell: StatusfulCell<Int>,
      sourceVariable: String,
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
        reading = cell.reading(),
        source = SOURCE_SCORECARD,
        sourceVariable = sourceVariable,
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
        source = SOURCE_SCORECARD,
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
