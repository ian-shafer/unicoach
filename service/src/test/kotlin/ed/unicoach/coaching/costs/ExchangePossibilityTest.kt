package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.ServedFigures
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.servedAt
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeTuitionExchange
import ed.unicoach.db.models.CollegeTuitionExchangeId
import ed.unicoach.db.models.ExchangeSelection
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.PublishedCell
import ed.unicoach.db.models.SeparateApplication
import ed.unicoach.db.models.TuitionExchange
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Who is told about an exchange, and what the capped figure is (RFC 277). */
class ExchangePossibilityTest {
  private val today = LocalDate.now(ZoneOffset.UTC)
  private val servedYear = AcademicYear(today.year - 1)
  private val collegeId = CollegeId(UUID.randomUUID())

  private val inState = CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD
  private val outOfState = CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD
  private val inStateFees = CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD
  private val outOfStateFees = CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD

  private val allFigures =
    mapOf(inState to 13000, inStateFees to 2000, outOfState to 38000, outOfStateFees to 3000)

  private fun served(amounts: Map<CostField, Int> = allFigures): ServedFigures {
    val rows =
      amounts.map { (field, amount) ->
        val address = requireNotNull((field.figureAddress as? FigureAddress.Price)?.address) { "[$field] is not a price" }
        PriceFigure(
          collegeId = collegeId,
          priceConcept = address.concept,
          residencyBasis = address.residency,
          arrangement = address.arrangement,
          academicYear = servedYear,
          reading = FigureReading.Present(amount, ValueBearingStatus.REPORTED),
          cell = PublishedCell.Surveyed(PublishedCell.Survey.IC_AY, "FIXTURE"),
          publisherFlag = null,
        )
      }
    return CollegeFigures(collegeId, rows, emptyList()).servedAt(servedYear)
  }

  private fun row(
    applyBy: LocalDate? = today.plusDays(30),
    expiresOn: LocalDate = today.plusDays(200),
  ) = CollegeTuitionExchange(
    id = CollegeTuitionExchangeId(UUID.randomUUID()),
    collegeId = collegeId,
    exchange = TuitionExchange.WUE,
    memberStates = setOf("WA", "OR", "CA"),
    maxPercentOfResidentTuition = 150,
    selection = ExchangeSelection.SELECTIVE,
    separateApplication = SeparateApplication.NOT_REQUIRED,
    minimumUnweightedGpa = BigDecimal("3.25"),
    percentOfEligibleOffered = 30,
    applyBy = applyBy,
    sourceUrl = "https://example.edu/wue",
    checkedOn = today.minusDays(4),
    expiresOn = expiresOn,
  )

  private fun possibilities(
    rows: List<CollegeTuitionExchange> = listOf(row()),
    residencyState: String? = "WA",
    collegeState: String = "OR",
    figures: ServedFigures = served(),
    on: LocalDate = today,
  ): List<ExchangePossibility> = exchangePossibilitiesOf(rows, residencyState, collegeState, figures, on)

  @Test
  fun `a Washington student at an Oregon school gets the possibility`() {
    val possibility = possibilities().single()

    assertEquals(TuitionExchange.WUE, possibility.exchange)
    assertEquals(150, possibility.maxPercentOfResidentTuition)
    assertEquals(ExchangeSelection.SELECTIVE, possibility.selection)
    assertEquals(SeparateApplication.NOT_REQUIRED, possibility.separateApplication)
    assertEquals(BigDecimal("3.25"), possibility.minimumUnweightedGpa)
    assertEquals(30, possibility.percentOfEligibleOffered)
    assertEquals("https://example.edu/wue", possibility.sourceUrl)
    assertEquals(today.minusDays(4), possibility.checkedOn)
  }

  @Test
  fun `a student from a non-member state gets nothing`() {
    assertEquals(emptyList(), possibilities(residencyState = "TX"))
  }

  @Test
  fun `a resident of the school's own state gets nothing`() {
    assertEquals(emptyList(), possibilities(residencyState = "OR"))
  }

  @Test
  fun `a student with no state on file gets nothing`() {
    assertEquals(emptyList(), possibilities(residencyState = null))
  }

  @Test
  fun `no row gets nothing`() {
    assertEquals(emptyList(), possibilities(rows = emptyList()))
  }

  @Test
  fun `a row is current through its expiry day and gone the day after`() {
    assertEquals(1, possibilities(rows = listOf(row(expiresOn = today))).size)
    assertEquals(emptyList(), possibilities(rows = listOf(row(expiresOn = today.minusDays(1)))))
  }

  @Test
  fun `the cap is a percentage of resident tuition with the fees taken out, at the served year`() {
    val capped = possibilities().single().cappedTuition

    // (13000 - 2000) x 150%, never 13000 x 150%.
    assertEquals(CappedTuition(16500, servedYear), capped)
    assertEquals(16500, possibilities().single().cappedTuitionPerYearUsd)
  }

  @Test
  fun `the cap rounds to the nearest whole dollar`() {
    val figures = served(mapOf(inState to 3333, inStateFees to 1000))

    // 2333 x 150% = 3499.5.
    assertEquals(3500, possibilities(figures = figures).single().cappedTuitionPerYearUsd)
  }

  @Test
  fun `the cap is null when resident fees are unpublished`() {
    assertNull(possibilities(figures = served(allFigures - inStateFees)).single().cappedTuition)
  }

  @Test
  fun `the cap is null when resident tuition and fees are unpublished`() {
    assertNull(possibilities(figures = served(allFigures - inState)).single().cappedTuition)
  }

  @Test
  fun `a fees figure at or above the combined figure yields no cap, never a zero`() {
    assertNull(possibilities(figures = served(allFigures + (inStateFees to 13000))).single().cappedTuition)
  }

  @Test
  fun `the cap is null when the out-of-state tuition is not higher than it`() {
    // 19500 - 3000 = 16500, exactly the cap: the exchange saves nothing.
    assertNull(possibilities(figures = served(allFigures + (outOfState to 19500))).single().cappedTuition)
  }

  @Test
  fun `one dollar above the cap the out-of-state tuition binds`() {
    assertEquals(
      16500,
      possibilities(figures = served(allFigures + (outOfState to 19501))).single().cappedTuitionPerYearUsd,
    )
  }

  @Test
  fun `an unknown out-of-state tuition leaves the cap standing`() {
    assertEquals(16500, possibilities(figures = served(allFigures - outOfStateFees)).single().cappedTuitionPerYearUsd)
  }

  @Test
  fun `a deadline is upcoming on its day and passed the day after`() {
    val deadline = today.plusDays(30)
    val row = listOf(row(applyBy = deadline))

    assertEquals(ExchangeDeadline.Upcoming(deadline), possibilities(rows = row, on = deadline).single().deadline)
    assertEquals(ExchangeDeadline.Passed(deadline), possibilities(rows = row, on = deadline.plusDays(1)).single().deadline)
  }

  @Test
  fun `no deadline held is not a deadline passed`() {
    assertEquals(ExchangeDeadline.NotHeld, possibilities(rows = listOf(row(applyBy = null))).single().deadline)
  }
}
