package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.StatusfulCell
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ValueBearingStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [CanonicalMoneyLoader] (RFC 158): the mapping table over the shared fixture
 * CSV (which carries `PrivacySuppressed`, `NA` and blank cells on purpose),
 * the three loader fatals (P3 pairing, P6 blend routing, banding a
 * non-banding measure), the no-blend and one-year-per-row stored shapes, and
 * rebuild idempotency (P12).
 */
class CanonicalMoneyLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = CanonicalMoneyLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  private fun fill(): CanonicalMoneyLoader.FillResult =
    runBlocking {
      scorecardLoader.load(institutionCsv, fieldsCsv)
      loader.fill(institutionCsv, sfa = null)
    }

  // ---------------------------------------------------------------------------
  // The fill's stored shape
  // ---------------------------------------------------------------------------

  @Test
  fun `the tuition pair lands as exactly two rows per college, residency as a key axis`() {
    fill()
    // Every loaded college gets BOTH tuition rows -- one per residency basis
    // -- because a pair of sibling columns became a key axis (D2). The
    // fixture's 5 loaded colleges each report both cells.
    val rows =
      query(
        "SELECT residency_basis, count(*) FROM price_figures WHERE price_concept = 'tuition_and_fees' " +
          "GROUP BY residency_basis ORDER BY residency_basis",
      ) { rs -> rs.getString(1) to rs.getInt(2) }
    assertEquals(listOf("in_state" to 5, "out_of_state" to 5), rows)
  }

  @Test
  fun `every mapped cell writes a row with a value or a status, and only mapped cells write rows`() {
    val result = fill()
    // 8 price cells x 5 colleges; 10 cohort cells x 5 colleges (P7's domain:
    // rows for every cell in the mapping, value-bearing or not; NO rows for
    // in_district, fees_only, with-family housing -- no source carries them).
    assertEquals(40, result.priceFigureRows)
    assertEquals(50, result.cohortMoneyStatRows)
    assertEquals(5, result.collegesMatched)
    assertEquals(1, result.rowsWithoutCollege, "the broken fixture row (empty UNITID) writes nothing")
    assertEquals(40, result.priceFigureStatusCounts.values.sum())
    assertEquals(50, result.cohortMoneyStatStatusCounts.values.sum())
  }

  @Test
  fun `PrivacySuppressed survives the fill as suppressed_by_publisher, never a silent null`() {
    val result = fill()
    // 330300 (public) has NPT43_PUB = PrivacySuppressed; 440400 has
    // GRAD_DEBT_MDN = PrivacySuppressed. The whole point of D3: these are
    // DIFFERENT facts from "not reported", and today's toIntOrNull collapse
    // loses them.
    assertEquals(2, result.cohortMoneyStatStatusCounts[FigureStatus.SUPPRESSED_BY_PUBLISHER])
    val suppressed =
      query(
        "SELECT source_variable FROM cohort_money_stats WHERE status = 'suppressed_by_publisher' " +
          "ORDER BY source_variable",
      ) { rs -> rs.getString(1) }
    assertEquals(listOf("GRAD_DEBT_MDN", "NPT43_PUB"), suppressed)
    // A suppressed row carries no value: the D3 CHECK enforced it, the loader
    // honored it.
    assertEquals(
      0,
      query("SELECT count(*) FROM cohort_money_stats WHERE status = 'suppressed_by_publisher' AND value IS NOT NULL") {
        it.getInt(1)
      }.single(),
    )
  }

  @Test
  fun `a blank or NA cell lands as not_reported_by_institution`() {
    fill()
    // 330300's ROOMBOARD_OFF is "NA", 550500's ROOMBOARD_ON is "NA", and
    // 440400's six components are all NA -- each still writes a ROW, with the
    // reason (P7): absence of a row is reserved for cells no source carries.
    val notReported =
      query(
        "SELECT c.ipeds_unit_id FROM price_figures pf JOIN colleges c ON c.id = pf.college_id " +
          "WHERE pf.status = 'not_reported_by_institution' AND pf.source_variable = 'ROOMBOARD_OFF' " +
          "ORDER BY c.ipeds_unit_id",
      ) { rs -> rs.getInt(1) }
    assertEquals(listOf(330300, 440400), notReported)
  }

  @Test
  fun `a negative net price is a real reported value, never coerced`() {
    fill()
    // 550500's NPT41_PUB is -1500: aid exceeding cost, valid since 0022. The
    // net-price reads carry NO gross-domain coercion, and NUMERIC stores it.
    val value =
      query(
        "SELECT value FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 550500 AND cms.measure = 'avg_net_price' AND cms.income_band = 'under_30k'",
      ) { rs -> rs.getDouble(1) }
    assertEquals(listOf(-1500.0), value)
  }

  @Test
  fun `no blend variable ever reaches price_figures, and no published_price row exists`() {
    fill()
    // P6, both halves: the blends land in cohort_money_stats under their true
    // population basis, and the price table holds no COSTT4_A/NPT4* row and
    // no published_price concept at all.
    val blends =
      query(
        "SELECT count(*) FROM price_figures WHERE source_variable = 'COSTT4_A' OR source_variable LIKE 'NPT4%'",
      ) { it.getInt(1) }
    assertEquals(listOf(0), blends)
    assertEquals(
      listOf(0),
      query("SELECT count(*) FROM price_figures WHERE price_concept = 'published_price'") { it.getInt(1) },
    )
  }

  @Test
  fun `every stored row carries exactly one year in its key, undated included honestly`() {
    fill()
    // The stored-shape successor of CostBreakdown's mixed-vintage rule: every
    // price row is one real year; the blended stats carry THEIR year; the
    // pooled/undated figures say `undated` rather than borrowing one.
    assertEquals(
      listOf("2022-23"),
      query("SELECT DISTINCT academic_year FROM price_figures") { it.getString(1) },
    )
    val vintages =
      query(
        "SELECT DISTINCT measure, vintage FROM cohort_money_stats ORDER BY measure, vintage",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertEquals(
      listOf(
        "avg_net_price" to "2021-22",
        "median_debt_at_completion" to "undated",
        "median_earnings_10y" to "undated",
        "pell_share" to "undated",
        "published_cost_blend" to "2021-22",
      ),
      vintages,
    )
  }

  @Test
  fun `the pooled figures carry their true population bases (debt, earnings, pell)`() {
    fill()
    // The mapping table's population-basis columns for the three undated
    // figures, pinned in full (measure, population, residency_scope,
    // aid_scope, vintage) so a wrong basis fails by name.
    val bases =
      query(
        "SELECT DISTINCT measure, population, residency_scope, aid_scope, vintage FROM cohort_money_stats " +
          "WHERE measure IN ('median_debt_at_completion', 'median_earnings_10y', 'pell_share') ORDER BY measure",
      ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }
    assertEquals(
      listOf(
        listOf("median_debt_at_completion", "federal_loan_borrowing_completers", "all", "federal_loan_borrowing", "undated"),
        listOf("median_earnings_10y", "employed_not_enrolled_10y_after_entry", "all", "all", "undated"),
        listOf("pell_share", "undergraduates", "all", "all", "undated"),
      ),
      bases,
    )
  }

  @Test
  fun `the blended figures carry their true population basis, control-keyed`() {
    fill()
    // 110100 is public: in_state_rate_paying (RFC 157 as stored data).
    // 220200 is private: scope `all`. Both read their own control's column.
    val scopes =
      query(
        "SELECT c.ipeds_unit_id, cms.residency_scope, cms.source_variable FROM cohort_money_stats cms " +
          "JOIN colleges c ON c.id = cms.college_id " +
          "WHERE cms.measure = 'avg_net_price' AND cms.income_band IS NULL AND c.ipeds_unit_id IN (110100, 220200) " +
          "ORDER BY c.ipeds_unit_id",
      ) { rs -> Triple(rs.getInt(1), rs.getString(2), rs.getString(3)) }
    assertEquals(
      listOf(
        Triple(110100, "in_state_rate_paying", "NPT4_PUB"),
        Triple(220200, "all", "NPT4_PRIV"),
      ),
      scopes,
    )
  }

  @Test
  fun `a second fill of the same snapshot reproduces the same counts (P12)`() {
    val first = fill()
    val second = runBlocking { loader.fill(institutionCsv, sfa = null) }
    assertEquals(first.priceFigureRows, second.priceFigureRows)
    assertEquals(first.cohortMoneyStatRows, second.cohortMoneyStatRows)
    assertEquals(first.priceFigureStatusCounts, second.priceFigureStatusCounts)
    assertEquals(first.cohortMoneyStatStatusCounts, second.cohortMoneyStatStatusCounts)
    assertEquals(40, query("SELECT count(*) FROM price_figures") { it.getInt(1) }.single())
  }

  @Test
  fun `the v1 fill never emits not_collected_by_us (P7)`() {
    val result = fill()
    assertEquals(null, result.priceFigureStatusCounts[FigureStatus.NOT_COLLECTED_BY_US])
    assertEquals(null, result.cohortMoneyStatStatusCounts[FigureStatus.NOT_COLLECTED_BY_US])
  }

  @Test
  fun `a row without a parseable CONTROL skips only its control-keyed cohort cells, counted`() {
    // A college that exists from an earlier ingest whose CURRENT snapshot row
    // lost CONTROL must not be silently classified private (wrong scope key,
    // wrong _PRIV column family): the seven control-keyed cohort cells
    // (COSTT4_A + the NPT4 family) are skipped and tallied, while the eight
    // control-independent price cells and the three pooled figures still
    // write.
    runBlocking { scorecardLoader.load(institutionCsv, fieldsCsv) }
    val result = runBlocking { loader.fill(withBlankControl(110100), sfa = null) }

    assertEquals(1, result.rowsWithoutControl)
    assertEquals(40, result.priceFigureRows, "the price cells are not control-keyed and still write")
    assertEquals(50 - 7, result.cohortMoneyStatRows, "exactly the seven control-keyed cells are skipped")
    val remaining =
      query(
        "SELECT cms.measure, count(*) FROM cohort_money_stats cms JOIN colleges c ON c.id = cms.college_id " +
          "WHERE c.ipeds_unit_id = 110100 GROUP BY cms.measure ORDER BY cms.measure",
      ) { rs -> rs.getString(1) to rs.getInt(2) }
    assertEquals(
      listOf("median_debt_at_completion" to 1, "median_earnings_10y" to 1, "pell_share" to 1),
      remaining,
      "no avg_net_price or published_cost_blend row was fabricated under a guessed control",
    )
  }

  // ---------------------------------------------------------------------------
  // The loader fatals
  // ---------------------------------------------------------------------------

  @Test
  fun `an arrangement-invariant concept paired with a living arrangement is fatal (P3)`() {
    val thrown =
      assertFailsWith<IllegalStateException> {
        CanonicalMoneyLoader.priceFigure(
          collegeId = UUID.randomUUID(),
          concept = PriceConcept.TUITION_AND_FEES,
          residency = ResidencyBasis.IN_STATE,
          arrangement = FigureArrangement.ON_CAMPUS,
          academicYear = "2022-23",
          reading = FigureReading.Present(11000, ValueBearingStatus.REPORTED),
          source = MoneySource.SCORECARD,
          sourceVariable = "TUITIONFEE_IN",
          publisherFlag = null,
        )
      }
    assertContains(thrown.message!!, "tuition_and_fees")
    assertContains(thrown.message!!, "P3")
  }

  @Test
  fun `a varying concept keyed not_applicable is the other half of the pairing fatal`() {
    assertFailsWith<IllegalStateException> {
      CanonicalMoneyLoader.priceFigure(
        collegeId = UUID.randomUUID(),
        concept = PriceConcept.HOUSING_AND_FOOD,
        residency = ResidencyBasis.NOT_APPLICABLE,
        arrangement = FigureArrangement.NOT_APPLICABLE,
        academicYear = "2022-23",
        reading = FigureReading.Present(9000, ValueBearingStatus.REPORTED),
        source = MoneySource.SCORECARD,
        sourceVariable = "ROOMBOARD_ON",
        publisherFlag = null,
      )
    }
  }

  @Test
  fun `a blend variable routed at price_figures is fatal (P6)`() {
    val thrown =
      assertFailsWith<IllegalStateException> {
        CanonicalMoneyLoader.priceFigure(
          collegeId = UUID.randomUUID(),
          concept = PriceConcept.PUBLISHED_PRICE,
          residency = ResidencyBasis.IN_STATE,
          arrangement = FigureArrangement.ON_CAMPUS,
          academicYear = "2021-22",
          reading = FigureReading.Present(32000, ValueBearingStatus.REPORTED),
          source = MoneySource.SCORECARD,
          sourceVariable = "COSTT4_A",
          publisherFlag = null,
        )
      }
    assertContains(thrown.message!!, "COSTT4_A")
    assertContains(thrown.message!!, "P6")
  }

  @Test
  fun `a banded row for a measure that does not band is fatal`() {
    val thrown =
      assertFailsWith<IllegalStateException> {
        CanonicalMoneyLoader.cohortStat(
          collegeId = UUID.randomUUID(),
          measure = MoneyMeasure.PELL_SHARE,
          population = CohortPopulation.UNDERGRADUATES,
          residencyScope = CohortResidencyScope.ALL,
          aidScope = CohortAidScope.ALL,
          incomeBand = IncomeBand.UNDER_30K,
          vintage = "undated",
          reading = FigureReading.Present(0.4, ValueBearingStatus.REPORTED),
          source = MoneySource.SCORECARD,
          sourceVariable = "PCTPELL",
        )
      }
    assertContains(thrown.message!!, "pell_share")
    assertTrue(MoneyMeasure.AVG_NET_PRICE.bandable, "the one banding measure stays bandable")
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * The fixture CSV with one row's CONTROL cell blanked (the fixture quotes
   * no cells, so a plain comma split is faithful). Models a current snapshot
   * that lost CONTROL for a college an earlier ingest already loaded.
   */
  private fun withBlankControl(unitId: Int): java.io.File {
    val lines = institutionCsv.readLines()
    val header = lines.first().split(",")
    val unitIdx = header.indexOf("UNITID")
    val controlIdx = header.indexOf("CONTROL")
    val edited =
      lines.map { line ->
        val cells = line.split(",").toMutableList()
        if (cells.getOrNull(unitIdx) != unitId.toString()) return@map line
        cells[controlIdx] = ""
        cells.joinToString(",")
      }
    return java.io.File
      .createTempFile("scorecard-institutions-no-control", ".csv")
      .apply {
        deleteOnExit()
        writeText(edited.joinToString("\n") + "\n")
      }
  }
}
