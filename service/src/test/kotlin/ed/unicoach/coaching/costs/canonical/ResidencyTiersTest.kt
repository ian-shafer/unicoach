package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHICH tuition tiers a school publishes, and the sentence that goes with the
 * code (RFC 166 SS4) -- driven with no database at all, like the projection it
 * reads.
 *
 * [CollegeFiguresTest] walks the value-bearing combinations. These are the two
 * cases the combination walk could not see, because both turn on the ABSENCE
 * a row carries rather than on the amount it does not:
 *
 * - a school we hold NO tuition price for, which used to be announced as
 *   publishing one price for everybody;
 * - an `in_district` row that is absent for a reason OTHER than
 *   `not_applicable`, which used to read as a two-tier price list and silenced
 *   the one sentence a community-college family needs.
 */
class ResidencyTiersTest {
  private val collegeId = CollegeId(UUID.randomUUID())

  // ---------------------------------------------------------------------------
  // No tuition price at all is said as itself (B2).
  // ---------------------------------------------------------------------------

  @Test
  fun `a school we hold no tuition price for is never said to publish one`() {
    // No price row at all: the college has no served year
    // ([ServedFigures.servesNoPublishedPrice]) and still has to answer.
    val served = figuresOf().servedAt(null)

    val basis = residencyTiersOf(served)

    assertEquals(ResidencyTierBasis.NO_PUBLISHED_TUITION, basis)
    assertTrue(publishedTuitionTiersOf(served).isEmpty(), "no tuition key rides beside it")
    assertFalse(
      basis.statement.contains("publishes one tuition price"),
      "the school is not made to publish a price we do not hold: [${basis.statement}]",
    )
  }

  @Test
  fun `tuition rows the school never reported are no tuition price either`() {
    // The other shape of the same fact: the rows EXIST and bear no value, so
    // the college is served at a year and every tier is still blank.
    val served =
      figuresOf(
        price(
          CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
          "2023-24",
          reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
        ),
        price(
          CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
          "2023-24",
          reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        ),
      ).servedAt("2023-24")

    assertEquals(ResidencyTierBasis.NO_PUBLISHED_TUITION, residencyTiersOf(served))
    assertTrue(publishedTuitionTiersOf(served).isEmpty())
  }

  @Test
  fun `one tuition price is stated as the one we hold, not as one price for everybody`() {
    // A public school whose out-of-state price is suppressed keeps a
    // residency-SPECIFIC in-state figure, so the words may not claim the price
    // applies whoever the student is.
    val served =
      figuresOf(
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, "2023-24", 8580),
        price(
          CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
          "2023-24",
          reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        ),
      ).servedAt("2023-24")

    val basis = residencyTiersOf(served)

    assertEquals(ResidencyTierBasis.SINGLE_PUBLISHED_PRICE, basis)
    assertEquals(1, publishedTuitionTiersOf(served).size, "the basis says one price, and one key is emitted")
    assertFalse(
      basis.statement.contains("whoever the student is"),
      "one tier we hold is not a promise about every student: [${basis.statement}]",
    )
  }

  @Test
  fun `every basis but the empty one rides beside at least one tuition key`() {
    // The invariant the catch-all broke: a sentence naming a price, and a
    // payload carrying none. Only [NO_PUBLISHED_TUITION] may be spoken with an
    // empty tuition key set.
    val tiers =
      listOf(
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      )
    (0 until 8).forEach { mask ->
      val present = tiers.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
      val served =
        figuresOf(*present.map { price(it, "2023-24", 9000) }.toTypedArray())
          .servedAt("2023-24".takeIf { present.isNotEmpty() })
      val basis = residencyTiersOf(served)
      val emitted = publishedTuitionTiersOf(served)

      if (emitted.isEmpty()) {
        assertEquals(ResidencyTierBasis.NO_PUBLISHED_TUITION, basis, "no key, so no price may be named")
      } else {
        assertFalse(
          basis == ResidencyTierBasis.NO_PUBLISHED_TUITION,
          "[$emitted] is a published tuition price, so the empty basis is false of it",
        )
      }
      if (basis == ResidencyTierBasis.SINGLE_PUBLISHED_PRICE) {
        assertEquals(1, emitted.size, "[${basis.value}] says ONE price, so it may never ride beside [$emitted]")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // A suppressed in-district price is not "two tiers" (H2).
  // ---------------------------------------------------------------------------

  @Test
  fun `an in-district price the school did not report is not a two-tier price list`() {
    // `not_reported_by_institution` is a SILENCE, and it is the same state as no
    // row at all: we cannot say whether a lower district price exists, and the
    // family at a community college is the one who needs to be told so.
    val served =
      figuresOf(
        price(
          CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
          "2023-24",
          reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
        ),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, "2023-24", 8580),
        price(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, "2023-24", 10590),
      ).servedAt("2023-24")

    val basis = residencyTiersOf(served)

    assertEquals(ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT, basis)
    assertTrue(
      basis.statement.contains("cannot say whether a lower district price exists"),
      "the honest sentence is the one spoken: [${basis.statement}]",
    )
  }

  @Test
  fun `an in-district price the publisher suppressed is not a two-tier price list either`() {
    val served =
      figuresOf(
        price(
          CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
          "2023-24",
          reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        ),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, "2023-24", 8580),
        price(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, "2023-24", 10590),
      ).servedAt("2023-24")

    assertEquals(ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT, residencyTiersOf(served))
  }

  @Test
  fun `only not_applicable is the publisher answering, and it is the two-tier case`() {
    // The other half of the rule: `not_applicable` is the publisher SAYING there
    // is no district tier here -- the typical public four-year and every private
    // -- and that school really does publish two prices.
    val served =
      figuresOf(
        price(
          CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
          "2023-24",
          reading = FigureReading.Absent(AbsenceStatus.NOT_APPLICABLE),
        ),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, "2023-24", 12000),
        price(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, "2023-24", 30000),
      ).servedAt("2023-24")

    assertEquals(ResidencyTierBasis.TWO_TIERS_PUBLISHED, residencyTiersOf(served))
    assertTrue(
      CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD !in publishedTuitionTiersOf(served),
      "an answered non-tier is not emitted as a key",
    )
  }

  private fun figuresOf(vararg rows: PriceFigure): CollegeFigures =
    CollegeFigures(collegeId = collegeId, priceFigures = rows.toList(), cohortStats = emptyList())

  private fun price(
    field: CostField,
    academicYear: String,
    amountUsd: Int? = null,
    reading: FigureReading<Int>? = null,
  ): PriceFigure {
    val address =
      requireNotNull((field.figureAddress as? FigureAddress.Price)?.address) {
        "[${field.wireName}] is not a price_figures cell, so this fixture cannot address it"
      }
    return PriceFigure(
      collegeId = collegeId,
      priceConcept = address.concept,
      residencyBasis = address.residency,
      arrangement = address.arrangement,
      academicYear = academicYear,
      reading =
        reading
          ?: amountUsd?.let { FigureReading.Present(it, ValueBearingStatus.REPORTED) }
          ?: FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
      source = MoneySource.IPEDS_IC_AY,
      sourceVariable = "FIXTURE",
      publisherFlag = null,
    )
  }
}
