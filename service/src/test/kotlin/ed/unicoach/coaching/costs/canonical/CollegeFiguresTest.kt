package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.FigureGroup
import ed.unicoach.coaching.costs.LineOrigin
import ed.unicoach.coaching.costs.NoTotalReason
import ed.unicoach.coaching.costs.ScorecardVariableNames
import ed.unicoach.coaching.costs.components
import ed.unicoach.coaching.costs.isAssumedByUnicoach
import ed.unicoach.coaching.costs.publishedPriceOf
import ed.unicoach.coaching.costs.reportedComponentsOf
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortMoneyStat
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MeasureUnit
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.PublishedCell
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The projection, driven with NO DATABASE AT ALL (RFC 166 §2).
 *
 * That is the point of the year selection being a Kotlin fold rather than a
 * window function: a college carries ~48 price rows and ~10 cohort rows, so the
 * whole of "store history, serve latest" is a small pure function, and the cases
 * that matter -- four academic years, a suppressed cell, a latest year that is
 * incomplete -- can be written as data rather than seeded, migrated and read
 * back.
 *
 * The DB-backed half of the same behaviour is asserted in
 * [ed.unicoach.coaching.costs.CollegeCostServiceTest]; these are the cases that
 * would be expensive to reach through a fixture and cheap to get wrong.
 */
class CollegeFiguresTest {
  private val collegeId = CollegeId(UUID.randomUUID())

  /** Where a refused fixture cell says it came from -- one home, so both fixture rows name the same place. */
  private val fixtureLocation = "the CollegeFigures fixture"

  // ---------------------------------------------------------------------------
  // Latest year, per key, and no cross-year total (§3).
  // ---------------------------------------------------------------------------

  @Test
  fun `a figure quoted alone is quoted at its own latest year`() {
    val figures =
      figuresOf(
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2020), 9000),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 12000),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2021), 10000),
      )
    val latest = assertNotNull(figures.latestPriceOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD))
    assertEquals(AcademicYear(2023), latest.academicYear)
    assertEquals(12000, latest.amountUsd)
  }

  @Test
  fun `the served year is the latest year that prices a whole way of living, not merely the latest year`() {
    // The newest year has a tuition figure and a housing figure and nothing else;
    // the year before it is complete. A family quoted the newest year would be
    // quoted an incomplete budget, or -- worse -- a total assembled from two
    // reporting years. RFC 166 §3 rule 2.
    val figures =
      figuresOf(
        *completeYear(AcademicYear(2022)),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 13000),
        price(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, AcademicYear(2023), 9500),
      )
    assertEquals(AcademicYear(2022), chosenYearOf(figures))
  }

  @Test
  fun `with no complete year anywhere, one year is still served and it is the latest that bears anything`() {
    // No total is possible, so the parts are shown at ONE year and labelled with
    // it. Not "each at its own latest year": every line of a served arrangement
    // shares one academic year, which is what makes the mixed-vintage refusal
    // unreachable from the read path by construction.
    val figures =
      figuresOf(
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2021), 11000),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 13000),
        price(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, AcademicYear(2023), 9500),
      )
    val year = assertNotNull(chosenYearOf(figures))
    assertEquals(AcademicYear(2023), year)
    assertFalse(
      figures.hasValuesAt(
        setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD) + LivingArrangement.ON_CAMPUS.components,
        year,
      ),
      "the year is served, and it is still incomplete -- so there is no total",
    )
  }

  @Test
  fun `every line of a served arrangement shares one academic year`() {
    val figures = figuresOf(*completeYear(AcademicYear(2022)), *completeYear(AcademicYear(2023)))
    val year = assertNotNull(chosenYearOf(figures))
    val lines = LivingArrangement.ON_CAMPUS.reportedComponentsOf(figures.servedAt(year))
    assertTrue(lines.isNotEmpty())
    assertEquals(setOf(year.label), lines.map { it.academicYear }.toSet())
  }

  @Test
  fun `a suppressed cell is not a value, so it cannot complete a year`() {
    val figures =
      figuresOf(
        *completeYear(AcademicYear(2022)),
        *completeYear(AcademicYear(2023)),
        price(
          CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
          AcademicYear(2023),
          reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        ),
      )
    assertEquals(
      AcademicYear(2022),
      chosenYearOf(figures),
      "a withheld figure is a figure with no value, whoever withheld it",
    )
  }

  @Test
  fun `a year whose in-state tuition was refused cannot be served to an in-state family`() {
    // RFC 183 D1's READ-SIDE half. The write seam refuses the Scorecard's
    // `TUITIONFEE_IN` at a college whose in-district and in-state prices really
    // differ, so the Scorecard's 2024-25 cells are all present EXCEPT in-state
    // tuition. Without this test the refusal is proved only at the STORE: the
    // second arm of `publishedPriceYearOf` -- "the latest year bearing ANY
    // value" -- would still hand an in-state family 2024-25 off the housing and
    // books cells, and the stored-row assertions in
    // `ScorecardResidencyCollapseTest` would never see it. What must break if this test is absent: a year that
    // prices no in-state arrangement being spoken and priced as the family's.
    val figures =
      figuresOf(
        *completeYear(AcademicYear(2023)),
        // `publisher = null` is this fixture's way of saying THE SCORECARD
        // (RFC 184): the survey publishers are named, and the Scorecard is the
        // absence of one. These are its 2024-25 cells, complete EXCEPT in-state
        // tuition -- exactly the shape RFC 183 D1's write-seam refusal leaves.
        price(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, AcademicYear(2024), 18240, publisher = null),
        price(CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2024), 18240, publisher = null),
        price(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, AcademicYear(2024), 1200, publisher = null),
        price(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD, AcademicYear(2024), 3000, publisher = null),
        price(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2024), 3500, publisher = null),
        price(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, AcademicYear(2024), 2500, publisher = null),
      )
    val year = assertNotNull(chosenYearOf(figures))
    assertEquals(AcademicYear(2023), year, "a year with no in-state tuition cannot price an in-state family")
    // And the figure that reaches the family is the coherent IPEDS one, at that
    // year -- the served value, not merely the stored row.
    assertEquals(
      12000,
      figures.servedAt(year).amountOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
      "the family is served the in-state price of the year that actually prices them",
    )
  }

  // ---------------------------------------------------------------------------
  // The cohort side (§8).
  // ---------------------------------------------------------------------------

  @Test
  fun `a cohort value is rounded to whole dollars and never clamped`() {
    // `cohort_money_stats.value` is NUMERIC and may be NEGATIVE by design -- aid
    // exceeding cost, which the lowest income bands do most often -- and the
    // column carries no non-negative CHECK for exactly that reason.
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2022), -1499.6),
      )
    assertEquals(-1500, assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).amountUsd)
  }

  @Test
  fun `an undated cohort row carries no year at all, rather than borrowing one`() {
    val figures =
      figuresOf(
        cohort(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, null, 23000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
      )
    assertNull(assertNotNull(figures.cohortOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, band = null)).vintage)
    assertEquals(AcademicYear(2022), figures.blendedAverageVintage(band = null))
  }

  @Test
  fun `the latest vintage per measure wins`() {
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2021), 18000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
      )
    assertEquals(20000, assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).amountUsd)
  }

  @Test
  fun `the projection routes on a closed set of measures it names, never exhaustively over the enum`() {
    // `pipeline/rfc-162` appends nine members to MoneyMeasure, so an exhaustive
    // `when (measure)` written against `main` compiles today and breaks on the
    // rebase. A measure this surface does not serve must simply not be served.
    val figures =
      figuresOf(
        rawCohort(
          MoneyMeasure.PELL_SHARE,
          CohortPopulation.UNDERGRADUATES,
          CohortAidScope.ALL,
          null,
          0.4,
        ),
      )
    assertNull(figures.cohortOf(CostField.NET_PRICE, band = null))
    assertTrue(CostField.entries.none { (it.figureAddress as? FigureAddress.Cohort)?.address?.measure == MoneyMeasure.PELL_SHARE })
  }

  @Test
  fun `the overall net price is the row at the served address, not the newest row sharing its measure`() {
    // RFC 162 landed a SECOND `avg_net_price` series: IPEDS SFA's grant-aided
    // net price for the full-time first-time aid cohort, at a NEWER vintage than
    // the Scorecard's Title IV-aided figure. Keyed on the measure and taken by
    // latest vintage, the SFA row would be served as "the average net price" --
    // a different cohort's number, materially different, and unlabelled.
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
        rawCohort(
          MoneyMeasure.AVG_NET_PRICE,
          CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
          CohortAidScope.GRANT_AIDED,
          AcademicYear(2023),
          12345.0,
        ),
      )
    assertEquals(
      20000,
      assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).amountUsd,
      "the served figure is the one at the address RFC 166 §8 pins, whatever else shares its measure",
    )
    assertEquals(AcademicYear(2022), assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).vintage)
  }

  @Test
  fun `a band figure is the band's row, and never the band-less row of another population`() {
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 6000.0, band = IncomeBand.UNDER_30K),
        rawCohort(
          MoneyMeasure.AVG_NET_PRICE,
          CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
          CohortAidScope.GRANT_AIDED,
          AcademicYear(2023),
          12345.0,
        ),
      )
    assertEquals(6000, assertNotNull(figures.cohortOf(CostField.NET_PRICE, IncomeBand.UNDER_30K)).amountUsd)
    assertEquals(20000, assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).amountUsd)
  }

  @Test
  fun `the blended-average year labels the rows this surface serves, never the newest row in the table`() {
    // The label twin of the address rule above. RFC 162 files an SFA
    // `grant_aided` net price at a NEWER vintage than the Scorecard blend this
    // surface serves, so a max over every cohort row dated the SERVED figure --
    // 20000, from 2021-22 -- as 2023-24. The number shown and the year printed
    // beside it must come from one row.
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2021), 20000.0),
        cohort(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AcademicYear(2021), 41000.0),
        rawCohort(
          MoneyMeasure.AVG_NET_PRICE,
          CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
          CohortAidScope.GRANT_AIDED,
          AcademicYear(2023),
          12345.0,
        ),
      )
    assertEquals(AcademicYear(2021), figures.blendedAverageVintage(band = null))
    assertEquals(20000, assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).amountUsd)
  }

  @Test
  fun `the blended-average year follows the band-selected row the family was served`() {
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2021), 20000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 6000.0, band = IncomeBand.UNDER_30K),
        cohort(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AcademicYear(2022), 41000.0),
      )
    assertEquals(AcademicYear(2022), figures.blendedAverageVintage(IncomeBand.UNDER_30K))
    assertEquals(
      null,
      figures.blendedAverageVintage(band = null),
      "the band-less net price is 2021-22 and the sticker is 2022-23, so no ONE year dates the group",
    )
  }

  @Test
  fun `two blended figures at two different years take no label at all, rather than the newer one`() {
    // The RESIDUE of the tier-1 fix that narrowed the fold to the served set:
    // the two served blended addresses can legitimately disagree, because the
    // net price is band-selected and the band series has an upstream (SFA)
    // writer the Scorecard blend does not. `max` labelled BOTH with the newer
    // year, so a published cost of attendance was quoted -- to the coach and in
    // the comparison basis -- under an academic year no row of it carries.
    val figures =
      figuresOf(
        cohort(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AcademicYear(2021), 41000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 6000.0, band = IncomeBand.UNDER_30K),
      )
    assertEquals(41000, assertNotNull(figures.cohortOf(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, band = null)).amountUsd)
    assertEquals(6000, assertNotNull(figures.cohortOf(CostField.NET_PRICE, IncomeBand.UNDER_30K)).amountUsd)
    assertNull(
      figures.blendedAverageVintage(IncomeBand.UNDER_30K),
      "one label cannot date two years, and no label is better than the wrong one",
    )

    // One year across both, and the label is that year.
    val agreed =
      figuresOf(
        cohort(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AcademicYear(2021), 41000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2021), 6000.0, band = IncomeBand.UNDER_30K),
      )
    assertEquals(AcademicYear(2021), agreed.blendedAverageVintage(IncomeBand.UNDER_30K))
  }

  @Test
  fun `a dated row beside an undated one at the same address is the answer, and keeps its year`() {
    // `'undated'` is not a year and is never ordered as one: by char code
    // "undated" > "2023-24", so ordering the raw column preferred the UNDATED
    // row and then erased the year of the dated one it discarded. The loader
    // really does write one measure at one key both ways.
    val figures =
      figuresOf(
        cohort(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, null, 23000.0),
        cohort(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, AcademicYear(2022), 19500.0),
      )
    val debt = assertNotNull(figures.cohortOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, band = null))
    assertEquals(19500, debt.amountUsd, "the dated row is the better answer")
    assertEquals(AcademicYear(2022), debt.vintage, "and it keeps its own year")
  }

  @Test
  fun `a dated series may not be served from an undated row`() {
    // A blended address declares a FigureGroup, so it is dated by definition: an
    // undated row at it would ship an amount with NO year on either surface --
    // the tool emits no `blended_average` key and the comparison prints no year
    // sentence -- and nothing would say the year was unknown.
    val error =
      assertFailsWith<IllegalStateException> {
        figuresOf(cohort(CostField.NET_PRICE, null, 20000.0))
      }
    assertTrue(
      error.message.orEmpty().contains("avg_net_price") && error.message.orEmpty().contains(collegeId.value.toString()),
      "the refusal names the college and the series: [${error.message}]",
    )
    // The two measures the store writes `undated` declare no group, so they are
    // untouched by the rule.
    assertNull(
      assertNotNull(
        figuresOf(cohort(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, null, 23000.0))
          .cohortOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, band = null),
      ).vintage,
    )
  }

  @Test
  fun `a share is not dollars, so a share row is not carried as a figure at all`() {
    // `cohort_money_stats.value` is one NUMERIC column holding two units. Rounded
    // like dollars, `pell_share = 0.183` reads as `amountUsd = 0` -- a share
    // served as money to a family. Nothing serves a share address TODAY, which
    // is a coincidence of the current vocabulary and not a property of the type.
    val figures =
      figuresOf(
        rawCohort(MoneyMeasure.PELL_SHARE, CohortPopulation.UNDERGRADUATES, CohortAidScope.ALL, AcademicYear(2022), 0.183),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
      )
    assertEquals(MeasureUnit.SHARE, MoneyMeasure.PELL_SHARE.unit)
    assertTrue(
      CostField.entries.none {
        ((it.figureAddress as? FigureAddress.Cohort)?.address?.measure?.unit ?: MeasureUnit.USD_PER_YEAR) != MeasureUnit.USD_PER_YEAR
      },
      "every cohort figure this surface names is in whole dollars",
    )
    assertNull(
      figures.cohortOf(
        CohortAddress(MoneyMeasure.PELL_SHARE, CohortPopulation.UNDERGRADUATES, CohortAidScope.ALL, group = null),
        band = null,
      ),
      "the share row is not held at all, so no figure can ever read it as zero dollars",
    )
    assertEquals(20000, assertNotNull(figures.cohortOf(CostField.NET_PRICE, band = null)).amountUsd)
  }

  @Test
  fun `a cohort value with no whole-dollar form is refused, never clamped to the largest Int`() {
    // The column is unconstrained NUMERIC and Postgres admits the literal 'NaN'.
    // `roundToInt` answers Int.MAX_VALUE above the range, so the clamp would show
    // a family $2,147,483,647 with full confidence and no status.
    listOf(Double.NaN, 4_000_000_000.0, -4_000_000_000.0).forEach { value ->
      val error =
        assertFailsWith<IllegalArgumentException>("[$value] has no whole-dollar form") {
          figuresOf(cohort(CostField.NET_PRICE, AcademicYear(2022), value))
        }
      assertTrue(error.message.orEmpty().contains("whole-dollar"), "[${error.message}]")
    }
  }

  @Test
  fun `one cohort address is one population basis, and two are refused rather than resolved`() {
    // `residency_scope` is part of the natural key, so the store admits both
    // rows; the resolution this replaces was the DECLARATION ORDER of an enum in
    // another module, so reordering it silently changed which cohort's net price
    // a public school quoted -- and the surfaces carry no key saying which.
    val figures =
      figuresOf(
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 17000.0, residencyScope = CohortResidencyScope.ALL),
      )
    val error =
      assertFailsWith<IllegalArgumentException> {
        figures.cohortOf(CostField.NET_PRICE, band = null)
      }
    assertTrue(
      error.message.orEmpty().contains("in_state_rate_paying") && error.message.orEmpty().contains("all"),
      "the refusal names both scopes it found: [${error.message}]",
    )
    assertTrue(error.message.orEmpty().contains(collegeId.value.toString()), "and the college: [${error.message}]")
  }

  @Test
  fun `a field read through the wrong door is refused, never answered as a school with no figure`() {
    val figures = figuresOf(*completeYear(AcademicYear(2023)))
    val error =
      assertFailsWith<IllegalStateException> {
        figures.cohortOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, band = null)
      }
    assertTrue(
      error.message.orEmpty().contains("not a cohort statistic"),
      "a mis-addressed field is a mistaken call, not a coverage claim: [${error.message}]",
    )
  }

  // ---------------------------------------------------------------------------
  // A cohort figure speaks its own status (§6).
  // ---------------------------------------------------------------------------

  @Test
  fun `a suppressed cohort figure states its status, where the price-only door said nothing at all`() {
    val figures =
      figuresOf(
        cohort(
          CostField.NET_PRICE,
          AcademicYear(2022),
          0.0,
          reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
        ),
      )
    assertEquals(
      FigureStatus.SUPPRESSED_BY_PUBLISHER,
      figures.statusOf(CostField.NET_PRICE, AcademicYear(2023), band = null)?.status,
    )
    assertNull(
      figures.figureOf(CostField.NET_PRICE, AcademicYear(2023)),
      "the price door still answers nothing for a cohort figure -- a statistic takes no published-price year",
    )
  }

  @Test
  fun `every cost field is dispatched on its own address, so no status is invisible`() {
    // The five cohort-addressed fields are the ones a price-only door could not
    // answer for. Walking the whole vocabulary is the assertion: a field added
    // tomorrow must be answerable through the same door.
    val figures =
      figuresOf(
        *completeYear(AcademicYear(2023)),
        cohort(CostField.NET_PRICE, AcademicYear(2022), 20000.0),
        cohort(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, AcademicYear(2022), 41000.0),
        cohort(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, null, 23000.0),
        cohort(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD, null, 45000.0),
      )
    val answered = CostField.entries.filter { figures.statusOf(it, AcademicYear(2023), band = null) != null }
    assertTrue(
      CostField.entries.filter { it.figureAddress is FigureAddress.Cohort }.all { it in answered },
      "a cohort field with a row must carry a status: [${answered.map { it.wireName }}]",
    )
    assertNull(
      figures.statusOf(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD, AcademicYear(2023), band = null),
      "our own assumption has no publisher status, and must not borrow one",
    )
  }

  @Test
  fun `an income band on a measure that files no band series is refused, never answered with null`() {
    val figures = figuresOf(cohort(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, null, 23000.0))
    val error =
      assertFailsWith<IllegalArgumentException> {
        figures.cohortOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, IncomeBand.UNDER_30K)
      }
    assertTrue(
      error.message.orEmpty().contains("not band-selected"),
      "the refusal names the rule it enforces: [${error.message}]",
    )
  }

  // ---------------------------------------------------------------------------
  // Nothing is dropped silently (§3).
  // ---------------------------------------------------------------------------

  @Test
  fun `a figure held only in another academic year is a year gap, never a silence`() {
    // The newest year prices ON-CAMPUS completely, so it is the served year --
    // and this school's off-campus travel allowance exists only two years back.
    val figures =
      figuresOf(
        *completeYear(AcademicYear(2023)).filterNot { it.arrangement == FigureArrangement.OFF_CAMPUS }.toTypedArray(),
        price(CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2023), 11000),
        price(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2021), 2400),
      )
    val year = assertNotNull(chosenYearOf(figures))
    assertEquals(AcademicYear(2023), year)

    val gap = assertNotNull(figures.yearGapOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, year))
    assertEquals(AcademicYear(2021), gap.academicYear, "the year we DO hold it for is named")
    assertEquals(2400, gap.amountUsd)

    // A figure that IS at the served year is no gap, and neither is one this
    // college carries no row for anywhere.
    assertNull(figures.yearGapOf(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, year))
    assertNull(figures.yearGapOf(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD, year))
  }

  @Test
  fun `a value-free row in another year is not a figure we hold, and its own status is what is spoken`() {
    // "The most recent year we hold this figure for is 2021-22" is a claim about
    // a named school's price list. A row that says the school reported NOTHING,
    // or that the publisher suppressed it, is not a figure we hold -- and
    // speaking it as OUR year gap also buried the row's real status, so the
    // publisher's suppression one year over never reached the family.
    listOf(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION, AbsenceStatus.SUPPRESSED_BY_PUBLISHER, AbsenceStatus.NOT_APPLICABLE)
      .forEach { absence ->
        val figures =
          figuresOf(
            *completeYear(AcademicYear(2023)).filterNot { it.arrangement == FigureArrangement.OFF_CAMPUS }.toTypedArray(),
            price(CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2023), 11000),
            price(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2021), reading = FigureReading.Absent(absence)),
          )
        val year = assertNotNull(chosenYearOf(figures))
        assertEquals(AcademicYear(2023), year)
        assertNull(
          figures.yearGapOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, year),
          "[${absence.status.value}] at another year is that year's own answer, not a figure we hold",
        )
        assertEquals(
          absence.status,
          figures.statusOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, year, band = null)?.status,
          "and the row's own status is what the field says instead: [${absence.status.value}]",
        )
      }
  }

  @Test
  fun `a value-bearing row in another year is the year gap, and the served year's own row still wins`() {
    // The two doors partition the other-year row between them: exactly one of
    // [yearGapOf] and the [statusOf] fall-through answers for it, never both and
    // never neither.
    val figures =
      figuresOf(
        *completeYear(AcademicYear(2023)).filterNot { it.arrangement == FigureArrangement.OFF_CAMPUS }.toTypedArray(),
        price(CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2023), 11000),
        price(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2021), 2400),
      )
    assertEquals(2400, assertNotNull(figures.yearGapOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2023))).amountUsd)
    assertNull(
      figures.statusOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, AcademicYear(2023), band = null),
      "the year gap speaks for a value-bearing other-year row, so the status door stays silent and neither doubles the other",
    )
    assertNull(
      figures.statusOf(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD, AcademicYear(2023), band = null),
      "a field with no row in any year still has no status at all",
    )
  }

  @Test
  fun `the year gap is spoken as ours, naming both years, and never as the school's silence`() {
    val statement = FigureStatusCopy.yearGapStatementOf("2023-24", "2021-22")
    assertTrue(statement.contains("2023-24") && statement.contains("2021-22"), "both years: [$statement]")
    assertTrue(statement.startsWith("We "), "the sentence is ours from its first word: [$statement]")
    assertFalse(
      statement.contains("did not report") || statement.contains("does not publish"),
      "a year of OURS is never the school's silence: [$statement]",
    )
    assertEquals(
      FigureGapOwner.UNICOACH,
      FigureStatusCopy.ownerOf(FigureStatusCopy.YEAR_GAP_STATUS),
      "and the status it is spoken under is one of ours",
    )
  }

  @Test
  fun `a gap of ours never produces the school's no-total reason`() {
    FigureStatus.entries.forEach { status ->
      val reason = FigureStatusCopy.noTotalReasonOf(status)
      when (FigureStatusCopy.ownerOf(status)) {
        FigureGapOwner.UNICOACH -> {
          assertEquals(
            NoTotalReason.PART_NOT_COLLECTED_BY_US,
            reason,
            "[${status.value}] is ours, so it may never say a part is not published",
          )
        }

        FigureGapOwner.SCHOOL, FigureGapOwner.PUBLISHER -> {
          assertEquals(NoTotalReason.PART_NOT_PUBLISHED, reason, "[${status.value}]")
        }
      }
      assertFalse(reason.aboutTuition, "a missing PART is never a tuition reason: [${status.value}]")
    }
  }

  // ---------------------------------------------------------------------------
  // The six statuses, spoken (§6).
  // ---------------------------------------------------------------------------

  @Test
  fun `every status but reported ships with words`() {
    FigureStatus.entries.forEach { status ->
      val statement = FigureStatusCopy.agentlessStatementOf(status)
      if (status == FigureStatus.REPORTED) {
        assertNull(statement, "a plainly reported figure is shown plainly")
      } else {
        assertNotNull(statement, "a status with no words is a code a family cannot be told: [$status]")
        assertTrue(statement.isNotBlank())
      }
    }
  }

  @Test
  fun `a gap of ours may never be claimed as the school's silence`() {
    // `ArrangementGap` and `data_availability` both make a claim about what the
    // SCHOOL published, so `not_collected_by_us` must never reach either -- and
    // neither may the publisher's own suppression.
    assertEquals(FigureGapOwner.UNICOACH, FigureStatusCopy.ownerOf(FigureStatus.NOT_COLLECTED_BY_US))
    assertFalse(FigureStatusCopy.isSchoolsOwnSilence(FigureStatus.NOT_COLLECTED_BY_US))
    assertFalse(FigureStatusCopy.isSchoolsOwnSilence(FigureStatus.SUPPRESSED_BY_PUBLISHER))
    assertTrue(FigureStatusCopy.isSchoolsOwnSilence(FigureStatus.NOT_REPORTED_BY_INSTITUTION))
    assertTrue(FigureStatusCopy.isSchoolsOwnSilence(FigureStatus.NOT_APPLICABLE))
  }

  @Test
  fun `an imputed figure is still a figure`() {
    // The 263 XFEE2 rows flagged `Z` (implied zero) are the only imputed figures
    // in the corpus today, and they are real zeros the publisher implied. They
    // are SHOWN, with the publisher's-estimate sentence beside them, never hidden.
    assertTrue(FigureStatus.IMPUTED_BY_PUBLISHER.valueBearing)
    val figures =
      figuresOf(
        price(
          CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
          AcademicYear(2023),
          reading = FigureReading.Present(0, ValueBearingStatus.IMPUTED_BY_PUBLISHER),
        ),
      )
    val fees = assertNotNull(figures.figureOf(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD, AcademicYear(2023)))
    assertEquals(0, fees.amountUsd)
    assertEquals(
      "This is the publisher's own estimate for this school, not a figure the school reported.",
      FigureStatusCopy.agentlessStatementOf(fees.status),
    )
  }

  // ---------------------------------------------------------------------------
  // Three tiers, and the in-district mislabel (§4).
  // ---------------------------------------------------------------------------

  @Test
  fun `three published tiers are named as three`() {
    // RFC 161's Austin CC shape: the tier is value-bearing for a minority, and a
    // fixture written against a private or public four-year would not exercise it
    // at all.
    val figures =
      figuresOf(
        price(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD, AcademicYear(2023), 2550),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 8580),
        price(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, AcademicYear(2023), 10590),
      )
    assertEquals(ResidencyTierBasis.THREE_TIERS_PUBLISHED, residencyTiersOf(figures.servedAt(AcademicYear(2023))))
    assertEquals(
      listOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      ),
      publishedTuitionTiersOf(figures.servedAt(AcademicYear(2023))),
    )
  }

  @Test
  fun `an in-district row the publisher says does not apply is not a tier, and is not mentioned`() {
    // An `in_district` cell flagged `not_applicable` is the publisher ANSWERING.
    // The typical public four-year and every private is in this case.
    val figures =
      figuresOf(
        price(
          CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
          AcademicYear(2023),
          reading = FigureReading.Absent(AbsenceStatus.NOT_APPLICABLE),
        ),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 12000),
        price(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, AcademicYear(2023), 30000),
      )
    assertEquals(ResidencyTierBasis.TWO_TIERS_PUBLISHED, residencyTiersOf(figures.servedAt(AcademicYear(2023))))
    assertTrue(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD !in publishedTuitionTiersOf(figures.servedAt(AcademicYear(2023))))
  }

  @Test
  fun `no in-district row at all is said in words, and the in-state figure keeps its own label`() {
    // RFC 161's open item, handed here in its own words: for the ~2,300 IC_PY
    // institutions "re-labelling it in-district on a guess would trade one wrong
    // label for another". AN IN-STATE FIGURE IS NEVER PRESENTED AS AN IN-DISTRICT
    // PRICE.
    // `publisher = null` is the SCORECARD, the publisher that does not separate
    // the in-district tier at all -- which is the case under test.
    val figures =
      figuresOf(
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2022), 12000, publisher = null),
        price(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, AcademicYear(2022), 30000, publisher = null),
      )
    val basis = residencyTiersOf(figures.servedAt(AcademicYear(2022)))
    assertEquals(ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT, basis)
    assertTrue(basis.statement.contains("district"), "the code ships with the words: [${basis.statement}]")
    assertTrue(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD !in publishedTuitionTiersOf(figures.servedAt(AcademicYear(2022))))
    assertNull(figures.figureOf(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD, AcademicYear(2022)))
  }

  @Test
  fun `the tier sentence can never say one price while more than one tuition key is emitted`() {
    // The words and the keys are two renderings of one fact, so a combination
    // that makes them disagree is money copy that is false about the school's
    // own price list. Every combination of the three tiers is walked, because
    // the one that used to fall through -- a district price with only one state
    // tier beside it -- is exactly the one no table row named.
    val tiers =
      listOf(
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      )
    (0 until 8).forEach { mask ->
      val present = tiers.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
      val figures = figuresOf(*present.map { price(it, AcademicYear(2023), 9000) }.toTypedArray())
      // The empty combination publishes no price row at all, so it has no year
      // to be served at ([ServedFigures.servesNoPublishedPrice]) -- and it still
      // has to answer, with one price named and no key emitted.
      val served = figures.servedAt(AcademicYear(2023).takeIf { present.isNotEmpty() })
      val basis = residencyTiersOf(served)
      val emitted = publishedTuitionTiersOf(served)
      assertEquals(present.toSet(), emitted.toSet(), "every value-bearing tier is emitted: [$present]")
      if (basis == ResidencyTierBasis.SINGLE_PUBLISHED_PRICE) {
        assertTrue(
          emitted.size <= 1,
          "[${basis.value}] says one price, so it may never ride beside [$emitted]",
        )
      }
      if (emitted.size == 3) assertEquals(ResidencyTierBasis.THREE_TIERS_PUBLISHED, basis)
    }
  }

  @Test
  fun `a district price beside one state tier is named as such, and never as a single price`() {
    val figures =
      figuresOf(
        price(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD, AcademicYear(2023), 2550),
        price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 8580),
      )
    val basis = residencyTiersOf(figures.servedAt(AcademicYear(2023)))
    assertEquals(ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER, basis)
    assertEquals(2, publishedTuitionTiersOf(figures.servedAt(AcademicYear(2023))).size)
    assertTrue(basis.statement.contains("district"), "the code ships with the words: [${basis.statement}]")
  }

  // ---------------------------------------------------------------------------
  // The assumed at-home line (§7).
  // ---------------------------------------------------------------------------

  @Test
  fun `the assumed at-home line has no address in the store, and is available in the served year`() {
    assertEquals(FigureAddress.AssumedByUnicoach, CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.figureAddress)
    val figures = figuresOf(*completeYear(AcademicYear(2023)))
    val lines = LivingArrangement.WITH_FAMILY.reportedComponentsOf(figures.servedAt(AcademicYear(2023)))
    val assumed = assertNotNull(lines.singleOrNull { it.origin == LineOrigin.ASSUMED_BY_UNICOACH })
    assertEquals(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD, assumed.field)
    assertEquals(ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD, assumed.amountUsd)
    assertEquals(AcademicYear(2023).label, assumed.academicYear, "our line dates with the budget it belongs to")
  }

  @Test
  fun `an assumed line alone cannot make a year look complete`() {
    // A school that publishes nothing at home is not priced at home. If the
    // assumption counted toward completeness, every school in the corpus would
    // appear to price an at-home budget it never published a part of.
    val figures = figuresOf(price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, AcademicYear(2023), 12000))
    assertFalse(
      figures.hasAnyValueAt(
        LivingArrangement.WITH_FAMILY.components.filter { it != CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD },
        AcademicYear(2023),
      ),
    )
  }

  @Test
  fun `whose number a field carries is read off its address, so a second assumed field needs no second edit`() {
    // The predicate four load-bearing sites consult -- the line builder below,
    // both of CostLine's requires, and the year choice -- is DERIVED from the
    // canonical address rather than naming one field. Asserted over `entries`
    // and driven through the builder, so a SECOND field the compiler forces an
    // AssumedByUnicoach address for is attributed to us with no edit in
    // CostBreakdown. The hardcoded `==` answered false for such a field: its
    // amount would have been looked for in `price_figures`, its `$0` unguarded,
    // and our number rendered as the school's.
    val addressedToUs = CostField.entries.filter { it.figureAddress == FigureAddress.AssumedByUnicoach }
    assertTrue(addressedToUs.isNotEmpty(), "the vocabulary carries at least one figure of ours")
    assertEquals(
      addressedToUs,
      CostField.entries.filter { it.isAssumedByUnicoach },
      "a field is ours exactly when its address says no publisher fills it",
    )

    val figures = figuresOf(*completeYear(AcademicYear(2023)))
    LivingArrangement.entries.forEach { arrangement ->
      val lines = arrangement.reportedComponentsOf(figures.servedAt(AcademicYear(2023)))
      arrangement.components.filter { it in addressedToUs }.forEach { field ->
        val line = assertNotNull(lines.singleOrNull { it.field == field }, "[${field.wireName}] is built as our line")
        assertEquals(LineOrigin.ASSUMED_BY_UNICOACH, line.origin, "[${field.wireName}] is named as ours")
        assertEquals(ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD, line.amountUsd)
      }
      lines.filterNot { it.field in addressedToUs }.forEach { line ->
        assertEquals(LineOrigin.PUBLISHED, line.origin, "[${line.field.wireName}] is the school's own figure")
      }
    }
  }

  @Test
  fun `a field's figure group is read off its address, so the classifier is never stated twice`() {
    // The group used to be a hand-typed column on the CostField member beside
    // this address, free to disagree with it: a price-backed field declared
    // BLENDED_AVERAGE would have shipped a published tuition price under
    // `blended_average_academic_year`, with nothing failing to compile.
    CostField.entries.forEach { field ->
      val fromAddress =
        when (val address = field.figureAddress) {
          is FigureAddress.Price -> FigureGroup.PUBLISHED_PRICE
          is FigureAddress.Cohort -> address.address.group
          FigureAddress.AssumedByUnicoach -> FigureGroup.PUBLISHED_PRICE
        }
      assertEquals(fromAddress, field.figureGroup, "[${field.wireName}] is grouped by where it lives")
    }
    assertEquals(
      listOf(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD),
      CostField.entries.filter { it.figureGroup == null },
      "exactly the two measures the store writes `undated` carry no group, and so no spoken year",
    )
    assertEquals(
      FigureGroup.PUBLISHED_PRICE,
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.figureGroup,
      "our own zero is a line inside a published-price budget, or the at-home total would be refused as mixed",
    )
  }

  // ---------------------------------------------------------------------------
  // The withholding chain, unchanged by the new axis (§4).
  // ---------------------------------------------------------------------------

  @Test
  fun `the new residency axis adds nothing to the in-state-only withholding chain`() {
    // IN_STATE_ONLY_FIELDS is DERIVED from the axis, so an in-district figure
    // filed under the wrong member would be silently withheld from every family
    // whose state does not match -- the opposite of what adding the tier is for.
    assertEquals(
      listOf(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, CostField.NET_PRICE),
      CostField.IN_STATE_ONLY_FIELDS,
    )
  }

  // ---------------------------------------------------------------------------
  // The served year is a TYPE, not an argument (§3).
  // ---------------------------------------------------------------------------

  @Test
  fun `a college cannot be served at a year it does not publish`() {
    // The pair (figures, academicYear) used to travel as two parameters through
    // ~21 signatures, so ANY string type-checked as "this college's served
    // year". A wrong one did not throw: `priceAt` answers null for a year the
    // college does not carry, so every read degraded to "this school reports
    // nothing" and a real price list was rendered as a blank. Bound into
    // [ServedFigures], the wrong year has no constructor.
    val figures = figuresOf(*completeYear(AcademicYear(2023)))
    val served = figures.servedAt(AcademicYear(2023))
    assertEquals(AcademicYear(2023), served.academicYear)
    assertNotNull(served.amountOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD))
    assertFailsWith<IllegalArgumentException>("a year this college publishes nothing in is not a served year") {
      figures.servedAt(AcademicYear(2019))
    }
  }

  @Test
  fun `no served year is a NAMED state about the school, and only a school with no price list may take it`() {
    // The null year is the one degrade that stays reachable, so it is pinned to
    // the fact that licenses it: a college with NO price row at all. A college
    // that does publish a price can no longer be served at no year -- which is
    // what a dropped year looked like, and it looked exactly like a school that
    // publishes nothing.
    val silent = figuresOf().servedAt(null)
    assertTrue(silent.servesNoPublishedPrice, "a school with no price row at all has no year to be served at")
    assertNull(silent.amountOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD))
    assertEquals(
      emptyList(),
      LivingArrangement.WITH_FAMILY.reportedComponentsOf(silent),
      "and no arrangement is built for it -- not even out of our own assumption",
    )
    assertFailsWith<IllegalArgumentException>("a school with a price list is served at one of its years") {
      figuresOf(*completeYear(AcademicYear(2023))).servedAt(null)
    }
    assertFalse(figuresOf(*completeYear(AcademicYear(2023))).servedAt(AcademicYear(2023)).servesNoPublishedPrice)
  }

  // ---------------------------------------------------------------------------
  // Fixtures: rows as data, no database.
  // ---------------------------------------------------------------------------

  private fun figuresOf(vararg rows: Any): CollegeFigures =
    CollegeFigures(
      collegeId = collegeId,
      priceFigures = rows.filterIsInstance<PriceFigure>(),
      cohortStats = rows.filterIsInstance<CohortMoneyStat>(),
    )

  /** The chosen published-price year for an ordinary in-state family, through the composer's own rule. */
  private fun chosenYearOf(figures: CollegeFigures): AcademicYear? =
    figures.publishedPriceYearOf(
      LivingArrangement.entries.map { arrangement ->
        (
          setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD) +
            arrangement.components.filter { it != CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD }
        ).toSet()
      },
    )

  private fun price(
    field: CostField,
    academicYear: AcademicYear,
    amountUsd: Int? = null,
    reading: FigureReading<Int>? = null,
    /**
     * WHICH survey published this row, or null for the SCORECARD -- the one
     * fixture parameter whose publisher is genuinely a choice. IC_AY by
     * default, because the loader ranks the surveys above the Scorecard and
     * most real price rows are IC_AY's.
     */
    publisher: PublishedCell.Survey? = PublishedCell.Survey.IC_AY,
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
      // The published cell (RFC 184), named for THIS field by THIS row's
      // publisher, from the ONE home that dispatches between the two naming
      // vocabularies -- the same one `:public-web`'s report-page fixture uses.
      // This suite used to type `FIXTURE` on its IPEDS rows, which is a name no
      // survey publishes; the tier answers for the whole source, so nothing
      // refused it, and the copy of the rule here drifted from the copy there.
      cell = publishedPriceOf(field, publisher, fixtureLocation),
      publisherFlag = null,
    )
  }

  /**
   * A cohort row at the FULL canonical address of [field] -- population and aid
   * scope read off [CostField.figureAddress] rather than typed here, so a
   * fixture can never file a figure under a cohort the served address does not
   * ask for.
   */
  private fun cohort(
    field: CostField,
    vintage: AcademicYear?,
    value: Double,
    band: IncomeBand? = null,
    residencyScope: CohortResidencyScope = CohortResidencyScope.IN_STATE_RATE_PAYING,
    reading: FigureReading<Double>? = null,
  ): CohortMoneyStat {
    val address =
      requireNotNull((field.figureAddress as? FigureAddress.Cohort)?.address) {
        "[${field.wireName}] is not a cohort_money_stats row, so this fixture cannot address it"
      }
    return rawCohort(
      address.measure,
      address.population,
      address.aidScope,
      vintage,
      value,
      band,
      residencyScope = residencyScope,
      reading = reading,
    )
  }

  /** A cohort row at an address of the caller's choosing -- for the rows this surface must NOT serve. */
  private fun rawCohort(
    measure: MoneyMeasure,
    population: CohortPopulation,
    aidScope: CohortAidScope,
    vintage: AcademicYear?,
    value: Double,
    band: IncomeBand? = null,
    residencyScope: CohortResidencyScope = CohortResidencyScope.IN_STATE_RATE_PAYING,
    reading: FigureReading<Double>? = null,
  ): CohortMoneyStat =
    CohortMoneyStat(
      collegeId = collegeId,
      measure = measure,
      population = population,
      residencyScope = residencyScope,
      aidScope = aidScope,
      incomeBand = band,
      vintage = vintage,
      reading = reading ?: FigureReading.Present(value, ValueBearingStatus.REPORTED),
      // A real Scorecard column for the measure, from the one home that names
      // them: this arm is keyed on the variable (RFC 179/184). Built AS the
      // Scorecard arm, because this fixture names no other publisher -- there
      // is no source to decode, so nothing decodes one.
      cell =
        PublishedCell.ScorecardCell.of(
          ScorecardVariableNames.cohortOf(measure, band),
          fixtureLocation,
        ),
      publisherFlag = null,
    )

  /** Every published component and the in-state tuition, at one year -- a year that prices all three ways of living. */
  private fun completeYear(academicYear: AcademicYear): Array<PriceFigure> =
    arrayOf(
      price(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, academicYear, 12000),
      price(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, academicYear, 9000),
      price(CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD, academicYear, 11000),
      price(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, academicYear, 1200),
      price(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD, academicYear, 3000),
      price(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD, academicYear, 3500),
      price(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, academicYear, 2500),
    )
}
