package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CanonicalMoneyReadDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.MoneySource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The year a family is told, driven by the FETCHED data dictionary (RFC 274):
 * a newer release re-dates the money with no code or seed edit, and a release
 * that drops or back-dates a served column is refused, naming it, with the
 * previous fill standing.
 *
 * Every year here is READ from the committed dictionary or derived from one
 * read there (`+ 1`, `- 1`); none is typed.
 */
class CanonicalMoneyDictionaryTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = CanonicalMoneyLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  /** The committed charge year, and the columns the dictionary dates to it. */
  private val chargeYear: AcademicYear = SeedScorecardYears.PUBLISHED_CHARGE
  private val chargeColumns: List<String> = EditedScorecardDictionary.publishedChargeColumns

  private fun fill(dictionary: ScorecardDictionary): CanonicalMoneyLoader.FillResult =
    runBlocking {
      scorecardLoader.load(institutionCsv, fieldsCsv)
      loader.fill(institutionCsv, sfa = null, dictionary = dictionary)
    }

  /** Every Scorecard price row's (source_variable, academic_year), sorted. */
  private fun scorecardPriceYears(): List<Pair<String, Int>> =
    query(
      "SELECT source_variable, academic_year FROM price_figures WHERE source = 'scorecard' " +
        "ORDER BY source_variable, academic_year, college_id",
    ) { rs -> rs.getString(1) to rs.getInt(2) }

  @Test
  fun `a dictionary dating the charges a year later stamps every Scorecard charge row with that year, and the read serves it`() {
    // The acceptance criterion: fetching and ingesting a newer release changes
    // the year a family is told with no code or seed edit.
    assertTrue(chargeColumns.size == 8, "the eight charges share one committed year: $chargeColumns")
    val newer = AcademicYear(chargeYear.firstCalendarYear + 1)
    fill(EditedScorecardDictionary.withChargesDated(newer))

    val years = scorecardPriceYears()
    assertTrue(years.isNotEmpty(), "the fill wrote no Scorecard price row, so this proves nothing")
    assertEquals(setOf(newer.firstCalendarYear), years.map { it.second }.toSet(), "every Scorecard price row: $years")

    // The read seam the cost service answers from returns the row's own year.
    val collegeIds = query("SELECT DISTINCT college_id FROM price_figures") { rs -> CollegeId(rs.getObject(1, UUID::class.java)) }
    val read = withSession { session -> CanonicalMoneyReadDao.listPriceFigures(session, collegeIds).getOrThrow() }
    val served =
      read.byCollege.values
        .flatten()
        .filter { it.source == MoneySource.SCORECARD }
        .map { it.academicYear }
        .toSet()
    assertEquals(setOf(newer), served, "the canonical read serves the year the dictionary states")

    // The blended cohort is a separate cell, re-dated by nobody here, and so unmoved.
    val blendVintages =
      query("SELECT DISTINCT vintage FROM cohort_money_stats WHERE source = 'scorecard' AND source_variable = 'COSTT4_A'") { rs ->
        rs.getInt(1)
      }
    assertEquals(listOf(SeedScorecardYears.BLENDED_AVERAGE.firstCalendarYear), blendVintages)
  }

  @Test
  fun `a dictionary that drops a served column is refused naming it, and the previous fill stands`() {
    fill(SeedScorecardDictionary.dictionary)
    val before = scorecardPriceYears()

    val refusal =
      assertFailsWith<ScorecardYearGuard.DictionaryRefusesServedVariablesException> {
        fill(EditedScorecardDictionary.of(institutionCells = mapOf(ScorecardInstitutionColumns.TUITIONFEE_IN to null)))
      }
    assertEquals(listOf(ScorecardInstitutionColumns.TUITIONFEE_IN), refusal.variables)
    assertTrue(refusal.message.orEmpty().contains("[${ScorecardInstitutionColumns.TUITIONFEE_IN}]"), refusal.message)
    assertEquals(before, scorecardPriceYears(), "nothing was rewritten")
  }

  @Test
  fun `a dictionary that back-dates a served column is refused naming the variable and both years, and the previous fill stands`() {
    fill(SeedScorecardDictionary.dictionary)
    val before = scorecardPriceYears()
    val older = AcademicYear(chargeYear.firstCalendarYear - 1)

    val refusal =
      assertFailsWith<ScorecardYearGuard.DictionaryBackdatesServedVariableException> {
        fill(
          EditedScorecardDictionary.of(
            institutionCells =
              mapOf(ScorecardInstitutionColumns.TUITIONFEE_OUT to EditedScorecardDictionary.collectionCell(older)),
          ),
        )
      }
    assertEquals(
      listOf(
        ScorecardYearGuard.Backdate(
          ScorecardInstitutionColumns.TUITIONFEE_OUT,
          ScorecardYearGuard.Backdate.Period.Year(chargeYear),
          ScorecardYearGuard.Backdate.Period.Year(older),
        ),
      ),
      refusal.backdates,
    )
    assertEquals(before, scorecardPriceYears(), "nothing was rewritten")
  }

  @Test
  fun `a served column re-worded into a shape the fill cannot stamp is refused naming the variable and the cell`() {
    val reworded = "Fall ${chargeYear.firstCalendarYear}, reported in IPEDS DCY${chargeYear.label}"
    val refusal =
      assertFailsWith<ScorecardYearGuard.DictionaryRefusesServedVariablesException> {
        fill(EditedScorecardDictionary.of(institutionCells = mapOf(ScorecardInstitutionColumns.BOOKSUPPLY to reworded)))
      }
    val fault = refusal.faults.single()
    assertEquals(ScorecardInstitutionColumns.BOOKSUPPLY, fault.variable)
    assertTrue(fault is ScorecardDictionary.UnexpectedCohortShapeException && fault.cell == reworded, "$fault")
    assertEquals(0, withSession { count(it, "price_figures") }, "the guard runs before the fill writes anything")
  }

  @Test
  fun `the ingest refuses a bad dictionary before its first phase commits`() {
    // The up-front half of the guard: a refused dictionary leaves every table
    // as it was, not just the money tables -- no college row is written either.
    assertFailsWith<ScorecardYearGuard.DictionaryRefusesServedVariablesException> {
      runBlocking {
        scorecardLoader.ingest(
          source(institutionCsv),
          source(fieldsCsv),
          source(fixture("college-aliases-fixture.json")),
          dictionary = EditedScorecardDictionary.sourcesOf(institutionCells = mapOf(ScorecardInstitutionColumns.COSTT4_A to null)),
        )
      }
    }
    assertEquals(0, withSession { count(it, "colleges") })
  }
}
