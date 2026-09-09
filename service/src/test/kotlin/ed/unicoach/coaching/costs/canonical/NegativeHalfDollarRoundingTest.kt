package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * ONE school may not quote TWO integers for one figure.
 *
 * `cohort_money_stats.value` is `NUMERIC` and may be negative by design -- aid
 * exceeding cost at the lowest income bands, which is why that column carries
 * no non-negative CHECK -- so a negative half dollar is a shape the store
 * admits. The two readers of that column round in two languages: search rounds
 * in SQL (`CollegesDao.createWholeDollarsHalfUpSql`) and the cost surface rounds in
 * Kotlin (`CollegeFigures.toWholeDollars`, `roundToInt`). SQL `round()` breaks
 * a tie AWAY FROM ZERO and `roundToInt` breaks it UPWARD, so while the search
 * lateral said `round()` a -1234.5 net price read -1235 on a search result and
 * -1234 on the cost page.
 *
 * Both of the search-side laterals are pinned, because they are two
 * expressions: the OVERALL net price is materialised onto
 * `college_search_index` by the rebuild's own lateral, and the five BAND prices
 * are read live by the payload lateral.
 *
 * Written in `:service` because it is the only module that can see both
 * surfaces at once. Pinning each side in its own module would let the two
 * expectations drift apart, which is precisely the defect.
 */
class NegativeHalfDollarRoundingTest {
  @BeforeEach
  fun setUp() {
    CostsTestDb.reset()
  }

  @Test
  fun `a negative half-dollar net price reads the same whole dollars on search and on the cost surface`() {
    val collegeId =
      CostsTestDb.seedCollege(
        name = "Half Dollar University",
        // PRIVATE, so both fills quote this college on the `all` residency
        // scope and the preference in `createCohortRowOrderSql` has one candidate to
        // choose. The rounding is what is under test, not the scope rule.
        control = 2,
        netPriceReading = reading(OVERALL_HALF_DOLLAR),
        netPriceReadingByIncomeBand = mapOf(IncomeBand.UNDER_30K to reading(BAND_HALF_DOLLAR)),
      )

    val outcome = CollegesDao.search(CostsTestDb.sqlSession, CollegeQuery(limit = 25)).getOrThrow()
    val match = assertIs<CollegeSearchOutcome.Page>(outcome).page.matches.single { it.id == collegeId }

    val figures =
      DbCanonicalCostReader(CostsTestDb.database)
        .readCohortStats(CostsTestDb.sqlSession, listOf(collegeId))
        .getValue(collegeId)

    // HALF UP, in both languages: SQL `round()` would answer -1235 here, and
    // that difference between the two surfaces is the whole finding.
    assertEquals(-1234, match.netPricePerYearUsd, "the search index's overall net price rounds half UP")
    assertEquals(
      -1234,
      presentAmountOf(figures, band = null),
      "and the cost surface reads the same integer out of the same row",
    )
    assertEquals(
      -567,
      match.netPriceUsdByBand[IncomeBand.UNDER_30K],
      "the payload lateral's band cell rounds half UP too",
    )
    assertEquals(
      -567,
      presentAmountOf(figures, band = IncomeBand.UNDER_30K),
      "and the cost surface agrees with it, band for band",
    )
  }

  /** The whole-dollar amount the cost surface serves for [band], which must be a value-bearing row here. */
  private fun presentAmountOf(
    figures: CollegeFigures,
    band: IncomeBand?,
  ): Int {
    val stat = requireNotNull(figures.cohortOf(CostField.NET_PRICE, band)) { "the fixture seeded this row" }
    return assertIs<FigureReading.Present<Int>>(stat.reading).value
  }

  private fun reading(value: Double): FigureReading<Double> = FigureReading.Present(value, ValueBearingStatus.REPORTED)

  private companion object {
    /** Negative, and exactly half a dollar: the one shape the two rounding rules disagree on. */
    const val OVERALL_HALF_DOLLAR = -1234.5
    const val BAND_HALF_DOLLAR = -567.5
  }
}
