package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.costs.CostsTestDb.addToCollegeList
import ed.unicoach.coaching.costs.CostsTestDb.answerBand
import ed.unicoach.coaching.costs.CostsTestDb.answerLivingPlan
import ed.unicoach.coaching.costs.CostsTestDb.answerResidency
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.costs.CostsTestDb.declineBand
import ed.unicoach.coaching.costs.CostsTestDb.declineLivingPlan
import ed.unicoach.coaching.costs.CostsTestDb.declineResidency
import ed.unicoach.coaching.costs.CostsTestDb.seedCollege
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.figureGroup
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.CohortAidScope
import ed.unicoach.db.models.CohortPopulation
import ed.unicoach.db.models.CohortResidencyScope
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneyMeasure
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.ValueBearingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeCostServiceTest {
  @BeforeEach
  fun resetDatabase() {
    CostsTestDb.reset()
  }

  private val service = CollegeCostService(CostsTestDb.database)

  /** One academic year NEWER than the fixture's own, for the year-selection cases (RFC 166 §3). */
  private val newerYear = AcademicYear(2024)

  /** [newerYear] as words, for the assertions that read the payload's own label. */
  private val newerAcademicYear = newerYear.label

  /** One academic year OLDER than the fixture's own -- a figure this school published, but not in the served year. */
  private val olderYear = AcademicYear(2021)

  /** [olderYear] as words, derived for the reason [newerAcademicYear] is. */
  private val olderAcademicYear = olderYear.label

  /**
   * The college the directly-constructed [ArrangementCost] fixtures below price.
   *
   * Fixed rather than random, so the refusal cases can assert that the id
   * reaches the message an operator reads.
   */
  private val arrangementCollegeId = CollegeId(UUID.fromString("00000000-0000-4000-8000-0000000000cc"))

  private fun profileOf(
    student: StudentId,
    ids: List<CollegeId>? = null,
  ): CollegeCostProfile = runBlocking { service.getForStudent(student, ids).getOrThrow() }

// ---------------------------------------------------------------------------
  // The canonical store: the year served, the statuses spoken, the third tier
  // (RFC 166).
  // ---------------------------------------------------------------------------

  @Test
  fun `the academic year is read off the rows and stated back, per college`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Dated U"))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(CostsTestDb.PRICE_ACADEMIC_YEAR, cost.publishedPriceAcademicYear)
    assertEquals(CostsTestDb.BLENDED_ACADEMIC_YEAR, cost.blendedAverageAcademicYear)
    assertEquals(CostsTestDb.PRICE_ACADEMIC_YEAR, cost.academicYearOf(FigureGroup.PUBLISHED_PRICE))
    assertEquals(CostsTestDb.BLENDED_ACADEMIC_YEAR, cost.academicYearOf(FigureGroup.BLENDED_AVERAGE))
  }

  @Test
  fun `a total is built from the latest COMPLETE year, not the latest year`() {
    // RFC 166 §3 rule 2. The newer year publishes a tuition figure and a housing
    // figure and nothing else; the older one is whole. A family quoted the newer
    // year would read an incomplete budget, and a total assembled across the two
    // would be a number no school ever published.
    val student = createStudent()
    val collegeId = seedCollege("Two Years U")
    // The fixture's own year is 2023-24 and is complete; add a NEWER, partial one.
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.TUITION_AND_FEES,
      ed.unicoach.db.models.ResidencyBasis.IN_STATE,
      FigureArrangement.NOT_APPLICABLE,
      academicYear = newerYear,
      reading = FigureReading.Present(13000, ValueBearingStatus.REPORTED),
    )
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.HOUSING_AND_FOOD,
      ed.unicoach.db.models.ResidencyBasis.NOT_APPLICABLE,
      FigureArrangement.ON_CAMPUS,
      academicYear = newerYear,
      reading = FigureReading.Present(9500, ValueBearingStatus.REPORTED),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(
      CostsTestDb.PRICE_ACADEMIC_YEAR,
      cost.publishedPriceAcademicYear,
      "the complete older year is served, and the year is stated",
    )
    assertEquals(CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, cost.tuitionAndFeesInStatePerYearUsd)
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS))
    assertNotNull(onCampus.totalPerYearUsd)
    assertEquals(
      setOf(CostsTestDb.PRICE_ACADEMIC_YEAR),
      onCampus.lines.map { it.academicYear }.toSet(),
      "every line of a served arrangement shares ONE academic year: [${onCampus.lines}]",
    )
  }

  @Test
  fun `with no complete year there is no total, the parts share one year, and the reason is given`() {
    val student = createStudent()
    val collegeId =
      seedCollege(
        "No Complete Year U",
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS))
    assertNull(onCampus.totalPerYearUsd, "a partial sum is not a total")
    assertEquals(
      setOf(CostsTestDb.PRICE_ACADEMIC_YEAR),
      onCampus.lines.map { it.academicYear }.toSet(),
      "the parts are shown at one year, each labelled with it",
    )
    assertTrue(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD in cost.notReported)
  }

  @Test
  fun `the six statuses are spoken, and a gap of ours is never called the school's silence`() {
    // RFC 166 §6. One college, one figure per absent status: the payload says
    // WHICH figure is blank (`data_availability`) and, beside it, WHY -- in the
    // store's own words and attributed to the right owner.
    val student = createStudent()
    val collegeId = seedCollege("Statuses U", booksAndSuppliesPerYearUsd = null)
    // The school's own silence is already there (books and supplies). Add a
    // figure the PUBLISHER suppressed and one WE have not collected.
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.FEES_ONLY,
      ed.unicoach.db.models.ResidencyBasis.IN_STATE,
      FigureArrangement.NOT_APPLICABLE,
      reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
    )
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.FEES_ONLY,
      ed.unicoach.db.models.ResidencyBasis.OUT_OF_STATE,
      FigureArrangement.NOT_APPLICABLE,
      reading = FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val byField = cost.figureStatuses.associateBy { it.field }

    val books = assertNotNull(byField[CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD])
    assertEquals(FigureStatus.NOT_REPORTED_BY_INSTITUTION, books.status)
    assertEquals("This school did not report this figure.", books.statement)
    assertTrue(
      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD in cost.notReported,
      "the SCHOOL's own silence is `data_availability`'s to name",
    )

    val suppressed = assertNotNull(byField[CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD])
    assertEquals(FigureStatus.SUPPRESSED_BY_PUBLISHER, suppressed.status)
    // The PUBLISHER is named, because the suppression is the publisher's own act
    // (RFC 177 D3). This college's price rows are IPEDS IC_AY, so "the
    // publisher" is that survey and the sentence says which.
    assertEquals(MoneySource.IPEDS_IC_AY, suppressed.source)
    assertEquals(
      "The U.S. Department of Education's IPEDS survey of college costs withholds this figure to protect " +
        "students' privacy.",
      suppressed.statement,
    )
    assertFalse(
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD in cost.notReported,
      "the PUBLISHER's suppression is not the school's silence, so `data_availability` may not claim it",
    )

    val ours = assertNotNull(byField[CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD])
    assertEquals(FigureStatus.NOT_COLLECTED_BY_US, ours.status)
    assertEquals("We have not collected this figure yet.", ours.statement)
    assertFalse(
      CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD in cost.notReported,
      "a gap of OURS may never be folded into a claim about the school's price list (RFC 149 D-B)",
    )
  }

  @Test
  fun `a net price the publisher suppressed speaks its own reason, and is never the school's silence`() {
    // RFC 166 §6 rule 1, on the COHORT side. `NPT4` is the Scorecard's most
    // suppressed series -- small cohorts -- and its status was read through a
    // price-only door that answers null for every cohort field, so the payload
    // told a family "this college does not report net price" about a figure the
    // publisher withheld for privacy, and dropped the sentence that says so.
    val student = createStudent()
    val collegeId =
      seedCollege(
        "Suppressed Net Price U",
        netPricePerYearUsd = null,
        netPriceReading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
      )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNull(cost.netPrice.amount, "a suppressed row bears no value")
    val note =
      assertNotNull(
        cost.figureStatuses.singleOrNull { it.field == CostField.NET_PRICE },
        "the store holds the reason, and the payload must carry it: [${cost.figureStatuses}]",
      )
    assertEquals(FigureStatus.SUPPRESSED_BY_PUBLISHER, note.status)
    // The net price is a COHORT row and this one is the Scorecard's, so the
    // sentence names the Scorecard where the price rows above name IPEDS -- one
    // college, two publishers, which is exactly what a single hand-typed
    // attribution could not say (RFC 177).
    assertEquals(MoneySource.SCORECARD, note.source)
    assertEquals(
      "The U.S. Department of Education College Scorecard withholds this figure to protect students' privacy.",
      note.statement,
    )
    assertFalse(
      CostField.NET_PRICE in cost.notReported,
      "the publisher's suppression may not be published as the school's silence: [${cost.notReported}]",
    )
  }

  @Test
  fun `a shown figure carries a tier and no status prose, and the note list cannot flood`() {
    // RFC 179 D6. The drop of every shown, non-imputed note is why RFC 177's
    // `reported` sentence was unreachable and why a tier would have been
    // invisible on every dollar amount a family reads. What replaces it is
    // bounded by construction: one note per CostField that HAS a row, so the
    // ceiling is the field vocabulary itself and nothing a college can grow.
    val student = createStudent()
    val collegeId = seedCollege("Fully Priced U")
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertTrue(
      cost.figureStatuses.size <= CostField.entries.size,
      "the ceiling is the field vocabulary, pinned as entries.size: [${cost.figureStatuses.size}]",
    )
    // Twelve: the eight published price cells this fixture writes, plus the
    // sticker cost, the net price, the median debt and the median earnings. The
    // fixture writes NO row for the fees-only and in-district fields unless a
    // test asks for them, and no row means no note.
    assertEquals(
      12,
      cost.figureStatuses.size,
      "one note per field this fixture seeds a row for: [${cost.figureStatuses.map { it.field.wireName }}]",
    )
    // No row, no note -- the rule the ceiling is made of. The fixture prices no
    // with-family food and housing, because no publisher does.
    assertTrue(
      cost.figureStatuses.none { it.field == CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD },
      "a field with no row still produces no entry",
    )

    // A shown, plainly reported figure: a tier, and NOTHING about its status.
    val tuition =
      assertNotNull(cost.statusNoteFor(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD))
    assertEquals(FigureStatus.REPORTED, tuition.status)
    assertNull(tuition.statement, "no status prose was invented for a figure whose status says nothing")
    assertEquals(AssuranceTier.MANDATORY_SURVEY, tuition.assurance)
    assertEquals(AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY), tuition.assuranceStatement)

    // And the tier is read from the row's own pair, so one college's two
    // publishers do not collapse: the median debt is the Scorecard relaying
    // NSLDS, which is a federal file about students and not a college's filing.
    val debt = assertNotNull(cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD))
    assertEquals(MoneySource.SCORECARD, debt.source)
    // The cell id is the resolver's INPUT and does not ride on the note; what
    // the note owes is the tier that pair resolved to.
    assertEquals(AssuranceTier.ADMINISTRATIVE_RECORD, debt.assurance)
    assertTrue(
      cost.figureStatuses
        .map { it.assuranceStatement }
        .toSet()
        .size > 1,
      "two publishers on one college produce two different tier sentences",
    )
  }

  @Test
  fun `an imputed figure emits its status AND its tier, and neither collapses into the other`() {
    // The PAIR rule (brief 0008 D3): `imputed_by_publisher` sits ON TOP of
    // whichever tier the cell is on. An imputed IPEDS cell is soft in a
    // different way from a school's own unaudited filing, and reading either
    // one alone loses half the fact.
    val student = createStudent()
    val collegeId = seedCollege("Imputed U")
    CostsTestDb.seedPriceFigure(
      collegeId,
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
      reading = FigureReading.Present(0, ValueBearingStatus.IMPUTED_BY_PUBLISHER),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val note = assertNotNull(cost.statusNoteFor(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD))
    assertEquals(FigureStatus.IMPUTED_BY_PUBLISHER, note.status)
    assertEquals(AssuranceTier.MANDATORY_SURVEY, note.assurance)
    assertNotNull(note.statement, "the imputed figure keeps its own sentence")
    assertEquals(AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY), note.assuranceStatement)
    assertFalse(
      note.statement.orEmpty().contains(note.assuranceStatement),
      "two sentences, composed beside each other and never concatenated into one",
    )
  }

  @Test
  fun `the blended-average year dates the row the family was served, never a newer row of another cohort`() {
    // RFC 162 files a SECOND `avg_net_price` series -- IPEDS SFA's grant-aided
    // figure for the full-time first-time aid cohort -- at a NEWER aid year. The
    // label is derived from the addresses this surface serves, so the year
    // printed beside the net price belongs to the number beside it.
    val student = createStudent()
    val collegeId = seedCollege("Two Net Price Series U")
    CostsTestDb.seedCohortStat(
      collegeId,
      MoneyMeasure.AVG_NET_PRICE,
      CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
      CohortResidencyScope.IN_STATE_RATE_PAYING,
      CohortAidScope.GRANT_AIDED,
      vintage = AcademicYear(2099),
      reading = FigureReading.Present(12345.0, ValueBearingStatus.REPORTED),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(20000, cost.netPrice.amount)
    assertEquals(
      CostsTestDb.BLENDED_ACADEMIC_YEAR,
      cost.blendedAverageAcademicYear,
      "the year is the SERVED row's, not the newest row sharing its measure",
    )
  }

  @Test
  fun `an imputed figure is shown, labelled, and never hidden`() {
    val student = createStudent()
    val collegeId = seedCollege("Imputed U")
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.FEES_ONLY,
      ed.unicoach.db.models.ResidencyBasis.IN_STATE,
      FigureArrangement.NOT_APPLICABLE,
      reading = FigureReading.Present(0, ValueBearingStatus.IMPUTED_BY_PUBLISHER),
      publisherFlag = "Z",
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(0, cost.publishedAmountOf(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD), "an implied zero is a real zero")
    assertFalse(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD in cost.notReported)
    val note = assertNotNull(cost.figureStatuses.singleOrNull { it.field == CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD })
    assertEquals(
      "The U.S. Department of Education's IPEDS survey of college costs estimated this for the school; the " +
        "school did not report it.",
      note.statement,
      "an imputed figure is still a figure -- shown, with the sentence that says WHICH publisher's estimate it is",
    )
  }

  @Test
  fun `a community college names three tiers, and the in-district figure never enters a total`() {
    // RFC 161's Austin CC shape. The tier is value-bearing for a minority, so a
    // fixture written against a private or a public four-year would not exercise
    // this feature at all.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "District CC",
        state = "TX",
        control = 1,
        seedsInDistrictRow = true,
        tuitionAndFeesInDistrictPerYearUsd = 2550,
        tuitionAndFeesInStatePerYearUsd = 8580,
        tuitionAndFeesOutOfStatePerYearUsd = 10590,
      ),
    )
    answerResidency(student, "TX")

    val cost = profileOf(student).colleges.single()
    assertEquals(ResidencyTierBasis.THREE_TIERS_PUBLISHED, cost.residencyTiers)
    assertEquals(2550, cost.publishedAmountOf(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD))

    // The applicable tuition for a Texas family is the IN-STATE one: a family's
    // answered STATE cannot select a DISTRICT price, and no new question is asked
    // to close that gap.
    val breakdown = assertNotNull(cost.breakdown)
    assertTrue(
      breakdown.arrangements.all { it.tuitionLine?.field == CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD },
      "the applicable tuition stays the state-selected one: [${breakdown.arrangements}]",
    )
    assertTrue(
      breakdown.arrangements.none { arrangement ->
        arrangement.lines.any { it.field == CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD }
      },
      "the in-district figure is a labelled tier and never a line in a total",
    )
  }

  @Test
  fun `a college with no in-district row says so, and its in-state figure keeps its own label`() {
    // RFC 161's open item: for the IC_PY remainder "re-labelling it in-district on
    // a guess would trade one wrong label for another". AN IN-STATE FIGURE IS
    // NEVER PRESENTED AS AN IN-DISTRICT PRICE.
    val student = createStudent()
    addToCollegeList(student, seedCollege("No District U"))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT, cost.residencyTiers)
    assertNull(cost.publishedAmountOf(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD))
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
      cost.tuitionAndFeesInStatePerYearUsd,
      "the figure keeps the label the publisher gave it",
    )
  }

  @Test
  fun `fees are reported beside the combined figure and are never summed into a total`() {
    // `fees_only` is a part OF `tuition_and_fees`, not a part BESIDE it, so adding
    // it to an arrangement would double-count fees inside every total.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Fees U", feesOnlyInStatePerYearUsd = 1500))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(1500, cost.publishedAmountOf(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD))
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS))
    assertTrue(
      onCampus.lines.none { it.field == CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD },
      "the split is reported, never added: [${onCampus.lines}]",
    )
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD +
        CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      onCampus.totalPerYearUsd,
    )
    // A fees tier with NO ROW AT ALL is in neither published list: `data_availability`
    // means "this college does not report this cost field", and a cell the fill has
    // never written for this college does not license that sentence. Most of the
    // corpus has no `in_district` row of any kind.
    assertTrue(
      CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD !in cost.notReported &&
        CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD !in cost.reported,
      "a cell we hold nothing for is not the school's silence, and not its figure either",
    )
  }

  // ---------------------------------------------------------------------------
  // Net-price basis
  // ---------------------------------------------------------------------------

  @Test
  fun `an answered band selects that band's amount as a band-specific price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Band U"))
    answerBand(student, IncomeBand.K48_TO_75K)

    val netPrice = assertIs<NetPrice.BandSpecific>(profileOf(student).colleges.single().netPrice)
    assertEquals(14000, netPrice.amount, "48k_to_75k must select net_price_per_year_income_q3_usd")
    assertEquals(IncomeBand.K48_TO_75K, netPrice.band)
  }

  @Test
  fun `an unanswered band falls back to the overall average and carries the precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Avg U"))
    answerResidency(student, "CA") // profile row exists; the band field is unanswered

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val netPrice = assertIs<NetPrice.OverallAverage>(cost.netPrice, "an overall average can carry no band")
    assertEquals(20000, netPrice.amount)
    assertEquals(
      listOf(PrecisionOffer.INCOME_BAND, PrecisionOffer.LIVING_PLAN),
      profile.precisionOffersFor(cost),
      "an unanswered band must carry the in-answer invitation; answered residency offers nothing more, " +
        "and the unanswered living plan is offered last (RFC 152 D4)",
    )
  }

  @Test
  fun `a declined band falls back to the overall average WITHOUT the precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Declined U"))
    declineBand(student)

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertIs<NetPrice.OverallAverage>(cost.netPrice)
    assertEquals(AnswerStatus.DECLINED, profile.moneyProfile.incomeBandStatus)
    assertTrue(
      PrecisionOffer.INCOME_BAND !in profile.precisionOffersFor(cost),
      "the coach must never be cued to reopen a declined band",
    )
  }

  @Test
  fun `an answered band also carries no precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Answered U"))
    answerBand(student, IncomeBand.UNDER_30K)
    val profile = profileOf(student)
    assertTrue(PrecisionOffer.INCOME_BAND !in profile.precisionOffersFor(profile.colleges.single()))
  }

  @Test
  fun `a college reporting no bracket columns makes no precision offer even when the band is unanswered`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "NoBands U",
        netPricePerYearIncomeQ1Usd = null,
        netPricePerYearIncomeQ2Usd = null,
        netPricePerYearIncomeQ3Usd = null,
        netPricePerYearIncomeQ4Usd = null,
        netPricePerYearIncomeQ5Usd = null,
      ),
    )

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
    assertTrue(!cost.reportsBandPricing)
    assertTrue(
      PrecisionOffer.INCOME_BAND !in profile.precisionOffersFor(cost),
      "a college with no band data makes no upgrade promise",
    )
  }

  @Test
  fun `a band re-answered after a decline flows through on the next read`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Reopen U"))

    declineBand(student)
    assertIs<NetPrice.OverallAverage>(
      profileOf(student)
        .colleges
        .single()
        .netPrice,
    )

    // The student changes their mind: update_money_profile re-answers over
    // declined at any time (RFC 134); the very next read is family-specific.
    answerBand(student, IncomeBand.OVER_110K)
    val netPrice = assertIs<NetPrice.BandSpecific>(profileOf(student).colleges.single().netPrice)
    assertEquals(21000, netPrice.amount, "over_110k must select net_price_per_year_income_q5_usd")
  }

  @Test
  fun `an absent money profile row reads as all-unanswered`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("NoProfile U"))

    val profile = profileOf(student)
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.residencyStatus)
    val cost = profile.colleges.single()
    assertIs<NetPrice.OverallAverage>(cost.netPrice)
    assertEquals(
      listOf(PrecisionOffer.RESIDENCY, PrecisionOffer.INCOME_BAND),
      profile.precisionOffersFor(cost),
      "all-unanswered at a public college offers the two upgrades that rest on nothing else, residency " +
        "first. The living plan is NOT among them: with residency unanswered this school's tuition line " +
        "is null, so no arrangement carries a total and there is nothing to choose between (D4)",
    )
  }

  // ---------------------------------------------------------------------------
  // Tuition applicability
  // ---------------------------------------------------------------------------

  @Test
  fun `answered residency resolves applicable tuition for public colleges and leaves private ones without the field`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home State U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Away State U", state = "NY", control = 1))
    addToCollegeList(student, seedCollege("Private U", state = "NY", control = 2))
    answerResidency(student, "CA")

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(
      TuitionApplicable.IN_STATE,
      assertIs<CollegeControl.Public>(byName.getValue("Home State U").control).tuitionApplicable,
    )
    assertEquals(
      TuitionApplicable.OUT_OF_STATE,
      assertIs<CollegeControl.Public>(byName.getValue("Away State U").control).tuitionApplicable,
    )
    // A private college has one price; its case cannot carry applicability at all.
    assertIs<CollegeControl.PrivateNonprofit>(byName.getValue("Private U").control)
  }

  @Test
  fun `unanswered or declined residency leaves public tuition applicability unknown`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Public U", state = "CA", control = 1))
    assertEquals(
      TuitionApplicable.UNKNOWN,
      assertIs<CollegeControl.Public>(profileOf(student).colleges.single().control).tuitionApplicable,
    )

    declineResidency(student)
    assertEquals(
      TuitionApplicable.UNKNOWN,
      assertIs<CollegeControl.Public>(profileOf(student).colleges.single().control).tuitionApplicable,
    )
  }

  @Test
  fun `an unrecognized control code keeps the raw value observable in the label`() {
    // colleges_control_valid_check keeps codes 1..3 in the DB; the case exists
    // for vocabulary drift, so the label contract is asserted directly.
    val control = CollegeControl.Unrecognized(9)
    assertEquals(9, control.code)
    assertEquals("unknown (control [9])", control.label, "the out-of-vocabulary code must ride the wire label")

    // The case means "outside the vocabulary" by construction, so it must read
    // as unknown even for a code the vocabulary DOES define -- otherwise the
    // state survives only because `controlOf` routes 1/2/3 elsewhere, and any
    // other caller could ship a confident "private_nonprofit" for a control
    // nothing ever recognised (RFC 143).
    assertEquals(
      "unknown (control [2])",
      CollegeControl.Unrecognized(2).label,
      "an Unrecognized case never renders a recognised label",
    )
  }

  // ---------------------------------------------------------------------------
  // Corrupt persisted values
  // ---------------------------------------------------------------------------

  @Test
  fun `an answered band with a corrupt null stored value fails the read, never folds into the overall average`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Corrupt U"))
    answerBand(student, IncomeBand.UNDER_30K)

    // The schema's iff-answered CHECK makes this state unreachable through any
    // write path; force it by dropping the constraint, then restore it.
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_income_band_value_iff_answered_check")
      stmt.execute("UPDATE money_profiles SET income_band = NULL, version = version + 1 WHERE student_id = '${student.value}'")
    }
    try {
      val result = runBlocking { service.getForStudent(student) }
      val error = result.exceptionOrNull()
      assertIs<CorruptPersistedValueException>(error, "got [$result]")
      assertTrue(
        error.message!!.contains("money_profiles.[income_band]"),
        "the error must name the corrupt column, got: [${error.message}]",
      )
      assertTrue(error.message!!.contains("(row ["), "the error must name the row, got: [${error.message}]")
    } finally {
      CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.execute("UPDATE money_profiles SET income_band = 'under_30k', version = version + 1 WHERE student_id = '${student.value}'")
        stmt.execute(
          "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_income_band_value_iff_answered_check " +
            "CHECK ((income_band IS NOT NULL) = (income_band_status = 'answered'))",
        )
      }
    }
  }

  @Test
  fun `an answered residency with a corrupt null stored state fails the read, never a silent withheld offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Corrupt Residency U"))
    answerResidency(student, "CA")

    // Same shape as the band's twin above: the iff-answered CHECK makes this
    // state unreachable through any write path, so it is forced by dropping the
    // constraint and restored in the finally.
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_residency_value_iff_answered_check")
      stmt.execute(
        "UPDATE money_profiles SET residency_state = NULL, version = version + 1 WHERE student_id = '${student.value}'",
      )
    }
    try {
      // Unaudited, this row reads as answered-but-unusable: tuition_applicable
      // folds to unknown AND the residency offer is withheld, because the
      // status is not UNANSWERED -- the one state no coach reply can recover.
      val result = runBlocking { service.getForStudent(student) }
      val error = result.exceptionOrNull()
      assertIs<CorruptPersistedValueException>(error, "got [$result]")
      assertTrue(
        error.message!!.contains("money_profiles.[residency_state]"),
        "the error must name the corrupt column, got: [${error.message}]",
      )
      assertTrue(error.message!!.contains("(row ["), "the error must name the row, got: [${error.message}]")
    } finally {
      CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.execute("UPDATE money_profiles SET residency_state = 'CA', version = version + 1 WHERE student_id = '${student.value}'")
        stmt.execute(
          "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_residency_value_iff_answered_check " +
            "CHECK ((residency_state IS NOT NULL) = (residency_status = 'answered'))",
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Data availability
  // ---------------------------------------------------------------------------

  @Test
  fun `unreported columns surface in notReported`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Sparse U",
        costOfAttendancePerYearUsd = null,
        netPricePerYearUsd = null,
        medianDebtAtCompletionUsd = null,
        tuitionAndFeesOutOfStatePerYearUsd = null,
      ),
    )

    val cost = profileOf(student).colleges.single()
    assertEquals(
      listOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
        CostField.NET_PRICE,
        CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      ),
      cost.notReported,
    )
    assertNull(cost.netPrice.amount)
  }

  @Test
  fun `an answered band whose bracket the college does not report keeps the basis and lists net_price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("NoBracket U", netPricePerYearIncomeQ1Usd = null))
    answerBand(student, IncomeBand.UNDER_30K)

    val cost = profileOf(student).colleges.single()
    val netPrice = assertIs<NetPrice.BandSpecific>(cost.netPrice, "a missing bracket is not a silent fallback")
    assertNull(netPrice.amount)
    assertTrue(CostField.NET_PRICE in cost.notReported)
  }

  // ---------------------------------------------------------------------------
  // List shape: empty, subset, unknown ids
  // ---------------------------------------------------------------------------

  @Test
  fun `an empty college list is a valid result with the money profile intact`() {
    val student = createStudent()
    answerBand(student, IncomeBand.UNDER_30K)

    val profile = profileOf(student)
    assertTrue(profile.colleges.isEmpty())
    assertTrue(profile.unknownCollegeIds.isEmpty())
    assertEquals(AnswerStatus.ANSWERED, profile.moneyProfile.incomeBandStatus)
  }

  @Test
  fun `an explicit empty subset answers no colleges but keeps the money profile block`() {
    val student = createStudent()
    val a = seedCollege("A U")
    addToCollegeList(student, a)

    // [] is a literal empty subset — distinct from null (whole list).
    val profile = profileOf(student, emptyList())
    assertTrue(profile.colleges.isEmpty())
    assertTrue(profile.unknownCollegeIds.isEmpty())
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
  }

  @Test
  fun `a subset filter answers only the requested colleges in request order`() {
    val student = createStudent()
    val a = seedCollege("A U")
    val b = seedCollege("B U")
    val c = seedCollege("C U")
    addToCollegeList(student, a)
    addToCollegeList(student, b, CollegeListEntryStatus.APPLYING)
    addToCollegeList(student, c)

    val profile = profileOf(student, listOf(c, b))
    assertEquals(listOf("C U", "B U"), profile.colleges.map { it.name })
    assertEquals(CollegeListEntryStatus.APPLYING, profile.colleges[1].listStatus)
    assertTrue(profile.unknownCollegeIds.isEmpty())
  }

  @Test
  fun `unknown and foreign ids are reported while known ones still answer`() {
    val student = createStudent()
    val other = createStudent()
    val mine = seedCollege("Mine U")
    val theirs = seedCollege("Theirs U")
    addToCollegeList(student, mine)
    addToCollegeList(other, theirs)
    val nowhere = CollegeId(UUID.randomUUID())

    val profile = profileOf(student, listOf(mine, theirs, nowhere))
    assertEquals(listOf("Mine U"), profile.colleges.map { it.name })
    assertEquals(listOf(theirs, nowhere), profile.unknownCollegeIds, "another student's entry is as unknown as a random id")
  }

  @Test
  fun `the whole active list is returned in list order with statuses and the ingest year`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("First U"))
    addToCollegeList(student, seedCollege("Second U"), CollegeListEntryStatus.ADMITTED)

    val profile = profileOf(student)
    assertEquals(listOf("First U", "Second U"), profile.colleges.map { it.name })
    assertEquals(
      listOf(CollegeListEntryStatus.CONSIDERING, CollegeListEntryStatus.ADMITTED),
      profile.colleges.map { it.listStatus },
    )
  }

  // ---------------------------------------------------------------------------
  // The merit-aid read (RFC 148 D7) -- additive, batched, never gating
  // ---------------------------------------------------------------------------

  @Test
  fun `a college with no merit row degrades to exactly the cost answer it had before`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("No Merit U"))

    val cost = profileOf(student).colleges.single()
    assertNull(cost.meritAid, "a missing row is never a zero")
    // The Scorecard availability list is untouched by a second source's silence.
    assertTrue(CostField.entries.none { it.wireName == "merit_aid" })
    assertEquals(emptyList(), cost.notReported)
  }

  @Test
  fun `the merit read rides the same connection and answers every college at once`() {
    val student = createStudent()
    val first = seedCollege("Merit One").also { addToCollegeList(student, it) }
    val second = seedCollege("Merit Two").also { addToCollegeList(student, it) }
    seedCollege("Merit None").also { addToCollegeList(student, it) }
    CostsTestDb.seedMeritAid(first, noNeedMeritAverageUsd = 1000)
    CostsTestDb.seedMeritAid(second, noNeedMeritAverageUsd = 2000)

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(1000, byName.getValue("Merit One").meritAid?.averageNonNeedAid)
    assertEquals(2000, byName.getValue("Merit Two").meritAid?.averageNonNeedAid)
    assertNull(byName.getValue("Merit None").meritAid)
  }

  @Test
  fun `the merit read adds no query per college`() {
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Merit Batch $n").also {
          addToCollegeList(student, it)
          CostsTestDb.seedMeritAid(it, noNeedMeritAverageUsd = 1000 * n)
        }
      }

    val one = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    assertEquals(1, service.readInSession(one, student, ids.take(1)).colleges.size)
    assertEquals(5, service.readInSession(five, student, ids).colleges.size)

    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "the read must cost the same statements for five colleges as for one, " +
        "never one per college: one=[${one.prepared}] five=[${five.prepared}]",
    )
  }

  @Test
  fun `a cancelled read is rethrown, never reported as a read failure`() {
    val student = createStudent()
    seedCollege("Cancelled U").also { addToCollegeList(student, it) }

    // Same rule as the admissions read: a chat turn the caller abandoned is not
    // a database fault, and cancellation must keep propagating.
    val outcome =
      runBlocking {
        var thrown: Throwable? = null
        var completed: Result<CollegeCostProfile>? = null
        val job =
          launch(start = CoroutineStart.UNDISPATCHED) {
            try {
              completed = service.getForStudent(student)
            } catch (e: Throwable) {
              thrown = e
            }
          }
        job.cancel()
        job.join()
        thrown to completed
      }
    assertTrue(outcome.first is CancellationException, "cancellation must propagate, got [${outcome.first}]")
    assertNull(outcome.second, "a cancelled read must not answer with a Result at all")
  }

  @Test
  fun `the merit share is over all full-time freshmen, and is withheld without both counts`() {
    val student = createStudent()
    val shared = seedCollege("Share U").also { addToCollegeList(student, it) }
    val partial = seedCollege("Partial U").also { addToCollegeList(student, it) }
    CostsTestDb.seedMeritAid(
      shared,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )
    CostsTestDb.seedMeritAid(
      partial,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(
      25.0,
      byName
        .getValue("Share U")
        .meritAid
        ?.shareOfAllFullTimeFreshmen
        ?.percent,
    )
    assertNull(
      byName
        .getValue("Partial U")
        .meritAid
        ?.shareOfAllFullTimeFreshmen
        ?.percent,
    )
    assertEquals(12500, byName.getValue("Partial U").meritAid?.averageNonNeedAid, "the average stands alone")
  }

  @Test
  fun `the latest merit cycle answers the cost read too`() {
    val student = createStudent()
    val college = seedCollege("Two Cycle U").also { addToCollegeList(student, it) }
    CostsTestDb.seedMeritAid(college, sourceYear = 2024, noNeedMeritAverageUsd = 1000)
    CostsTestDb.seedMeritAid(college, sourceYear = 2025, noNeedMeritAverageUsd = 2000)

    val merit = profileOf(student).colleges.single().meritAid
    assertEquals(2000, merit?.averageNonNeedAid)
    assertEquals("Two Cycle U's 2025-26 Common Data Set", merit?.source?.citedAs)
  }

  // ---------------------------------------------------------------------------
  // The living-arrangement breakdown (RFC 149)
  // ---------------------------------------------------------------------------

  /** One arrangement of a college's breakdown, or null when it was omitted. */
  private fun arrangementOf(
    cost: CollegeCost,
    arrangement: LivingArrangement,
  ): ArrangementCost? = cost.breakdown?.arrangements?.firstOrNull { it.arrangement == arrangement }

  private fun amountsOf(arrangement: ArrangementCost): Map<CostField, Int> = arrangement.lines.associate { it.field to it.amountUsd }

  @Test
  fun `all three arrangements are assembled, each totalling its own parts plus the applicable tuition`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Breakdown U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val breakdown = assertNotNull(cost.breakdown)
    assertEquals(
      listOf(LivingArrangement.ON_CAMPUS, LivingArrangement.OFF_CAMPUS, LivingArrangement.WITH_FAMILY),
      breakdown.arrangements.map { it.arrangement },
      "the arrangements ride in declaration order, which is the order the coach reads them",
    )

    val tuition = 12000
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS))
    assertEquals(
      mapOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD to tuition,
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD to CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD to CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      ),
      amountsOf(onCampus),
    )
    assertEquals(
      tuition +
        CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      onCampus.totalPerYearUsd,
    )

    val offCampus = assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertEquals(
      tuition +
        CostsTestDb.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      offCampus.totalPerYearUsd,
    )

    // The one sentence this whole slice exists to make sayable: living at home
    // costs the on-campus housing-and-food figure less, plus the difference in
    // everyday spending.
    val withFamily = assertNotNull(arrangementOf(cost, LivingArrangement.WITH_FAMILY))
    assertEquals(
      tuition + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD +
        ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD,
      withFamily.totalPerYearUsd,
    )
    assertTrue(withFamily.totalPerYearUsd!! < onCampus.totalPerYearUsd!!)
  }

  /**
   * THE REVERSAL (RFC 166 §7, gate-2 D17). This test was
   * `with_family carries no housing and food line at all, never a zero`, and the
   * behaviour it pinned is the one D17 overturns -- for `with_family` only.
   *
   * The old rule read the absence as missing data: no source publishes a
   * with-family food-and-housing figure, so a `$0` there would be a fabricated
   * fact and the arrangement carried one fewer part and never totalled. It is
   * not missing data. Eating at home is not free, but it is not a new cost that
   * ENROLLING creates, so the at-home total counts it as zero -- and the zero is
   * OURS, said in words, never attributed to the school.
   *
   * Every OTHER arrangement keeps both committed rules exactly, which the
   * sibling tests above and [a zero is unrepresentable under any other
   * arrangement or field] below still assert.
   */
  @Test
  fun `with_family carries a labelled zero housing and food line, and the zero is ours`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home U"))
    answerResidency(student, "CA")

    val withFamily = assertNotNull(arrangementOf(profileOf(student).colleges.single(), LivingArrangement.WITH_FAMILY))
    val housing =
      assertNotNull(
        withFamily.lines.singleOrNull { it.field == CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD },
        "the at-home arrangement carries a food-and-housing line: [${withFamily.lines}]",
      )
    assertEquals(ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD, housing.amountUsd)
    assertEquals(
      LineOrigin.ASSUMED_BY_UNICOACH,
      housing.origin,
      "the zero is ours, and the type says so rather than a comment beside a renderer",
    )
    // No canonical row is invented for it: the store holds what publishers said,
    // and no publisher said this.
    assertNull(
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.figureAddress as? FigureAddress.Price,
      "the assumed field has no address in a store of what publishers published",
    )
    // Every other line is still a published figure, so the reversal is exactly
    // one field wide.
    assertTrue(
      withFamily.lines
        .filter { it.field != CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD }
        .all { it.origin == LineOrigin.PUBLISHED && it.amountUsd != 0 },
      "the reversal is one field wide: [${withFamily.lines}]",
    )
    assertNotNull(withFamily.totalPerYearUsd, "the at-home total is complete, and is shown")
  }

  @Test
  fun `a zero we assume is unrepresentable under any other arrangement or field`() {
    // The rule is a PROPERTY, not a convention: `CostLine`'s own init admits
    // ASSUMED_BY_UNICOACH for exactly one field at exactly one amount and refuses
    // every other assumed line at construction (the ArrangementCost precedent).
    // Without this, the D17 reversal would be a habit, and a `$0` could appear
    // under any arrangement the next caller felt like.
    assertFailsWith<IllegalArgumentException>("no other field may carry an amount of ours") {
      CostLine(
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
        0,
        CostsTestDb.PRICE_ACADEMIC_YEAR,
        LineOrigin.ASSUMED_BY_UNICOACH,
      )
    }
    assertFailsWith<IllegalArgumentException>("the one assumed amount is zero, and only zero") {
      CostLine(
        CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD,
        1200,
        CostsTestDb.PRICE_ACADEMIC_YEAR,
        LineOrigin.ASSUMED_BY_UNICOACH,
      )
    }
    assertFailsWith<IllegalArgumentException>("no publisher publishes it, so no line for it is published") {
      CostLine(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD, 0, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED)
    }
  }

  @Test
  fun `the at-home assumption is stated in words, and only where an at-home total is shown`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Assumed One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Assumed Two U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val basis = assertNotNull(profileOf(student).comparisonBasis)
    assertNotNull(basis.atHomeAssumption, "an at-home total is quoted, so the assumption behind it is said")
    assertTrue(
      basis.statements.any { it == AT_HOME_ASSUMPTION_STATEMENT },
      "the statement rides in the ONE ordered list a renderer may not sub-select from: [${basis.statements}]",
    )

    // A family never quoted an at-home total is never told about an assumption
    // that moved nothing they were shown.
    val silent = createStudent()
    val noAtHome =
      (1..2).map { n ->
        seedCollege("Silent $n U", otherExpensesWithFamilyPerYearUsd = null, booksAndSuppliesPerYearUsd = null)
      }
    noAtHome.forEach { addToCollegeList(silent, it) }
    answerResidency(silent, "CA")
    val silentBasis = assertNotNull(profileOf(silent).comparisonBasis)
    assertNull(silentBasis.atHomeAssumption)
    assertTrue(silentBasis.statements.none { it == AT_HOME_ASSUMPTION_STATEMENT })
  }

  @Test
  fun `an arrangement missing one part carries the parts it has and no total`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Partial U", otherExpensesOffCampusPerYearUsd = null))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val offCampus = assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertNull(offCampus.totalPerYearUsd, "a partial sum is not a total")
    assertEquals(
      setOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
      ),
      amountsOf(offCampus).keys,
      "the reported parts still ride, they are true whatever is missing",
    )
    assertNotNull(
      arrangementOf(cost, LivingArrangement.ON_CAMPUS)?.totalPerYearUsd,
      "one arrangement's gap never costs another its total",
    )
    assertTrue(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD in cost.notReported)
  }

  @Test
  fun `the overall net price is the row at its own address, not a newer row that only shares its measure`() {
    // RFC 162 landed a SECOND `avg_net_price` series in this store: IPEDS SFA's
    // GRANT-AIDED net price for the full-time first-time aid cohort, written at
    // a newer vintage than the Scorecard's Title IV-aided figure. A selector
    // keyed on the measure and resolved by latest vintage serves the SFA row as
    // "the average net price" -- a different cohort, a materially different
    // number, and no label anywhere saying which.
    val student = createStudent()
    val collegeId = seedCollege("Two Cohorts U", netPricePerYearUsd = 20000)
    CostsTestDb.seedCohortStat(
      collegeId,
      MoneyMeasure.AVG_NET_PRICE,
      CohortPopulation.FIRST_TIME_FULL_TIME_AID_COHORT,
      CohortResidencyScope.IN_STATE_RATE_PAYING,
      CohortAidScope.GRANT_AIDED,
      vintage = newerYear,
      reading = FigureReading.Present(12345.0, ValueBearingStatus.REPORTED),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val netPrice = assertIs<NetPrice.OverallAverage>(cost.netPrice)
    assertEquals(
      20000,
      netPrice.amount,
      "the Scorecard-basis figure is the one served; the SFA grant-aided row is a different cohort's number",
    )
  }

  @Test
  fun `an arrangement may not sum two academic years, even inside one figure group`() {
    // `price_figures` holds four academic years, so two lines can share the
    // published-price group and still come from different years -- a mismatch
    // the closed vintage enum made unrepresentable and this store makes
    // ordinary. Constructed directly, because the composer picks one year before
    // it reads a line and the read path can no longer reach this throw.
    val error =
      assertFailsWith<MixedVintageArrangementException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine =
            CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
          componentLines =
            listOf(
              CostLine(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, 9500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
              // One year older, and the same figure group: a books allowance
              // from another reporting year is not part of this budget.
              CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, "2022-23", LineOrigin.PUBLISHED),
              CostLine(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD, 3000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            ),
        )
      }
    assertEquals(
      setOf(
        FigureGroup.PUBLISHED_PRICE to CostsTestDb.PRICE_ACADEMIC_YEAR,
        FigureGroup.PUBLISHED_PRICE to "2022-23",
      ),
      error.vintages,
      "one group, two years -- which a set of groups alone could not have seen",
    )
    assertTrue(
      error.message!!.contains(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD.wireName) &&
        error.message!!.contains("2022-23"),
      "the offending line and its year are in the message: [${error.message}]",
    )
  }

  @Test
  fun `a part WE have not collected leaves a total that is ours to explain, never the school's`() {
    // RFC 166 §6 rule 1, on the served path. `not_collected_by_us` is a gap of
    // OURS: the school may publish the figure perfectly well, so "this school
    // does not publish a part" is a claim about its price list we cannot make.
    val student = createStudent()
    val collegeId =
      seedCollege(
        "Uncollected Part U",
        omittedPriceFields = setOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD),
      )
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.OTHER_EXPENSES,
      ed.unicoach.db.models.ResidencyBasis.NOT_APPLICABLE,
      FigureArrangement.OFF_CAMPUS,
      reading = FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val cost = profileOf(student).colleges.single()
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(
      NoTotalReason.PART_NOT_COLLECTED_BY_US,
      noTotal.reason,
      "the routing is real: a gap of ours reaches its own reason, not the school's",
    )
    assertFalse(
      noTotal.reason.phrase.contains("published"),
      "and the words never say the part is unpublished: [${noTotal.reason.phrase}]",
    )
    assertFalse(
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD in cost.notReported,
      "a gap of OURS is never `data_availability`'s to claim",
    )
    val note =
      assertNotNull(cost.figureStatuses.singleOrNull { it.field == CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD })
    assertEquals(FigureStatus.NOT_COLLECTED_BY_US, note.status)
    assertEquals("We have not collected this figure yet.", note.statement)
  }

  @Test
  fun `a figure held only in another academic year is stated as ours, never dropped and never the school's silence`() {
    // The served year is chosen once per college, so a figure whose only row is
    // in another year is not shown. It was previously dropped with NO key and NO
    // status -- in neither published list, in `figure_statuses` nowhere -- which
    // is the one thing this surface may never do (RFC 166 §3).
    val student = createStudent()
    val collegeId =
      seedCollege(
        "Year Gap U",
        omittedPriceFields = setOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD),
      )
    CostsTestDb.seedPriceFigure(
      collegeId,
      PriceConcept.OTHER_EXPENSES,
      ed.unicoach.db.models.ResidencyBasis.NOT_APPLICABLE,
      FigureArrangement.OFF_CAMPUS,
      academicYear = olderYear,
      reading = FigureReading.Present(2400, ValueBearingStatus.REPORTED),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val cost = profileOf(student).colleges.single()
    assertEquals(
      CostsTestDb.PRICE_ACADEMIC_YEAR,
      cost.publishedPriceAcademicYear,
      "the college's own year is unchanged by a figure it holds only in an older one",
    )

    // 1. The arrangement is incomplete, and no total is composed across years.
    val offCampus = assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertNull(offCampus.totalPerYearUsd, "a part from another year is not a part of this budget")
    assertEquals(
      setOf(CostsTestDb.PRICE_ACADEMIC_YEAR),
      offCampus.lines.map { it.academicYear }.toSet(),
      "every line of a served arrangement still shares one year",
    )
    assertTrue(offCampus.lines.none { it.field == CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD })

    // 2. The gap is OURS, and it names the year.
    val note =
      assertNotNull(
        cost.figureStatuses.singleOrNull { it.field == CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD },
        "nothing is dropped silently: the figure has a key and a reason [${cost.figureStatuses}]",
      )
    assertEquals(FigureStatus.NOT_COLLECTED_BY_US, note.status)
    // Both years travel as DATA, not only inside the sentence: `status` alone
    // cannot tell a year gap from a cell we have never collected, so a renderer
    // or a coach that had to substring-match our English could not render the
    // case at all.
    assertTrue(note.isYearGap, "a figure held at another year is a year gap, and says so as data")
    assertEquals(CostsTestDb.PRICE_ACADEMIC_YEAR, note.servedAcademicYear)
    assertEquals(olderAcademicYear, note.heldAcademicYear)
    // A year gap always HAS a sentence -- only a shown, plainly reported figure
    // has none (RFC 179 D6) -- so this asserts the sentence exists as well as
    // what it says.
    val gapStatement = assertNotNull(note.statement, "a year gap states both years in words")
    assertTrue(
      gapStatement.contains(CostsTestDb.PRICE_ACADEMIC_YEAR) && gapStatement.contains(olderAcademicYear),
      "both years are named: [$gapStatement]",
    )
    assertFalse(
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD in cost.notReported,
      "the school DID publish it, so its silence is never claimed",
    )

    // 3. And the missing total is explained as ours.
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(NoTotalReason.PART_NOT_COLLECTED_BY_US, noTotal.reason)
  }

  @Test
  fun `a value-free row in another year speaks its own status, never a year-gap note about a figure we do not hold`() {
    // RFC 166 §3 and §6 together. A row at ANOTHER year that bears NO value is
    // not a figure we hold: calling it a year gap said "the most recent year we
    // hold this figure for is 2021-22" about a cell that holds nothing, and --
    // worse -- it preempted the row's own status, so a publisher's suppression
    // one year over was never spoken at all.
    val student = createStudent()
    val collegeId =
      seedCollege(
        "Suppressed Elsewhere U",
        omittedPriceFields = setOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD),
      )
    // The publisher's suppression, held only for an older year.
    CostsTestDb.seedPriceFigure(
      collegeId,
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      academicYear = olderYear,
      reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
    )
    // And the SCHOOL's own silence, likewise held only for an older year.
    CostsTestDb.seedPriceFigure(
      collegeId,
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
      academicYear = olderYear,
      reading = FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION),
    )
    addToCollegeList(student, collegeId)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val cost = profileOf(student).colleges.single()
    assertEquals(
      CostsTestDb.PRICE_ACADEMIC_YEAR,
      cost.publishedPriceAcademicYear,
      "the college is still served at its own year",
    )
    val byField = cost.figureStatuses.associateBy { it.field }

    // 1. The publisher's sentence is spoken, and it is NOT a year-gap note.
    val suppressed = assertNotNull(byField[CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD])
    assertEquals(FigureStatus.SUPPRESSED_BY_PUBLISHER, suppressed.status)
    assertEquals(
      "The U.S. Department of Education's IPEDS survey of college costs withholds this figure to protect " +
        "students' privacy.",
      suppressed.statement,
    )
    assertFalse(suppressed.isYearGap, "we hold no figure for that year either, so there is no gap of ours to name")
    assertNull(suppressed.heldAcademicYear)
    val suppressedStatement = assertNotNull(suppressed.statement, "a suppressed figure keeps its publisher's sentence")
    assertFalse(
      olderAcademicYear in suppressedStatement,
      "a year we hold nothing for may never be named as a year we hold the figure for: [$suppressedStatement]",
    )
    assertFalse(
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD in cost.notReported,
      "the PUBLISHER's suppression is not the school's silence: [${cost.notReported}]",
    )

    // 2. The school's own silence is spoken AND named in `data_availability`:
    //    a status we recovered from another year's row is not recovered at all
    //    if the lists that route it never see the field.
    val schools = assertNotNull(byField[CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD])
    assertEquals(FigureStatus.NOT_REPORTED_BY_INSTITUTION, schools.status)
    assertEquals("This school did not report this figure.", schools.statement)
    assertTrue(
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD in cost.notReported,
      "the school's own silence is `data_availability`'s to name, whichever year we hold the row at: [${cost.notReported}]",
    )
    assertFalse(
      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD in cost.reported,
      "and a cell with no value is never reported",
    )

    // 3. The missing off-campus part is not OURS: the publisher withheld it.
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(NoTotalReason.PART_NOT_PUBLISHED, noTotal.reason)
  }

  @Test
  fun `the at-home assumption is stated wherever the zero is shown, total or no total`() {
    // The `$0` and the sentence saying whose zero it is are one fact, and the
    // gate follows the LINE rather than the total: an at-home arrangement
    // missing one other part still PRINTS the zero, and shipping it with nothing
    // anywhere naming it as ours is exactly what D17 exists to prevent.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Half Priced Home U", booksAndSuppliesPerYearUsd = null))
    answerResidency(student, "CA")

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val withFamily = assertNotNull(arrangementOf(cost, LivingArrangement.WITH_FAMILY))
    assertNull(withFamily.totalPerYearUsd, "one part is missing, so there is no total")
    assertTrue(
      withFamily.lines.any {
        it.field == CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD &&
          it.origin == LineOrigin.ASSUMED_BY_UNICOACH
      },
      "and the zero is printed anyway: [${withFamily.lines}]",
    )
    assertTrue(cost.showsAtHomeArrangement)

    val residency =
      ResidencyBasis
        .of(profile.colleges, profile.moneyProfile)
        .byCollege
        .single()
    val basis = SingleSchoolBasis.of(cost, residency)
    assertNotNull(basis.atHomeAssumption, "the zero is shown, so the assumption behind it is said")
    assertTrue(
      AT_HOME_ASSUMPTION_STATEMENT in basis.statements,
      "in the ONE ordered list a renderer may not sub-select from: [${basis.statements}]",
    )
  }

  @Test
  fun `an arrangement with no reported part at all is omitted entirely`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Off-Campus U",
        housingAndFoodOffCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        // Books is shared, so the arrangement is empty only once its own two
        // parts AND the shared one are gone.
        booksAndSuppliesPerYearUsd = null,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS), "an empty arrangement is absent, never an empty object")
    assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "the arrangements it can price still answer")
  }

  @Test
  fun `a college reporting no component at all carries no breakdown`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Silent U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNull(cost.breakdown)
    // The PUBLISHED components, which is every component but the at-home
    // food-and-housing line (RFC 166 §7): that amount is ours, so the school has
    // neither reported nor failed to report it and it belongs in neither list --
    // the same third category the on-campus components fall into at a no-dorms
    // school (RFC 149 D-B).
    val published = CostField.COMPONENTS.filterNot { it == CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD }
    assertEquals(
      published,
      cost.notReported.filter { it in CostField.COMPONENTS },
      "every component it does not report is named, so the coach says so instead of estimating",
    )
    assertTrue(
      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD !in cost.notReported &&
        CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD !in cost.reported,
      "our own assumption is never attributed to the school, in either direction",
    )
  }

  @Test
  fun `unanswered residency at a public college drops the tuition line and every total`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Unknown Residency U", control = 1))

    val cost = profileOf(student).colleges.single()
    val breakdown = assertNotNull(cost.breakdown, "the components are true whoever is reading them")
    assertTrue(
      breakdown.arrangements.all { it.totalPerYearUsd == null },
      "a total that silently picked one residency would be a lie",
    )
    assertTrue(
      breakdown.arrangements.none { line -> line.lines.any { it.field.wireName.startsWith("tuition_and_fees") } },
      "and the tuition line it would have picked is absent, not guessed",
    )
  }

  @Test
  fun `out-of-state residency selects the out-of-state tuition line`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Away U", state = "NY", control = 1))
    answerResidency(student, "CA")

    val onCampus = assertNotNull(arrangementOf(profileOf(student).colleges.single(), LivingArrangement.ON_CAMPUS))
    assertEquals(
      30000,
      amountsOf(onCampus)[CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD],
      "the breakdown reuses the payload's own TuitionApplicable decision",
    )
    assertNull(amountsOf(onCampus)[CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD])
  }

  @Test
  fun `a private college is priced without a residency answer, because it has one price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private Breakdown U", control = 2))

    val onCampus = assertNotNull(arrangementOf(profileOf(student).colleges.single(), LivingArrangement.ON_CAMPUS))
    assertEquals(12000, amountsOf(onCampus)[CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD])
    assertNotNull(onCampus.totalPerYearUsd, "there is no residency question here to leave unanswered")
  }

  @Test
  fun `an arrangement can never mix Scorecard vintages`() {
    // RFC 149 D-F rule 3, asserted on the type rather than on one payload: the
    // constructor refuses, so no future assembly site can quietly add COSTT4_A
    // or a net price into a published-price sum.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine =
            CostLine(
              CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
              12000,
              CostsTestDb.PRICE_ACADEMIC_YEAR,
              LineOrigin.PUBLISHED,
            ),
          componentLines =
            listOf(
              CostLine(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, 40000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            ),
        )
      }
    assertTrue(error.message!!.contains("differing or unknown datings"), "got [${error.message}]")

    // ...and the two blended figures really are a different vintage from the
    // components, which is what makes the guard bite.
    assertEquals(FigureGroup.BLENDED_AVERAGE, CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.figureGroup)
    assertEquals(FigureGroup.BLENDED_AVERAGE, CostField.NET_PRICE.figureGroup)
    assertTrue(
      CostField.COMPONENTS.all { it.figureGroup == FigureGroup.PUBLISHED_PRICE },
      "the six components share one vintage, which is why they may be summed",
    )
  }

  @Test
  fun `an arrangement refuses an UNDATED figure, which can never be shown to share a year`() {
    // RFC 149 D-E: median debt and median earnings carry no vintage, because
    // this RFC dates neither. The require is what makes that safe rather than
    // merely quiet -- a null vintage is not a wildcard that matches everything,
    // it is a year nobody established, so it may not be summed with a dated one.
    assertNull(CostField.MEDIAN_DEBT_AT_COMPLETION_USD.figureGroup)
    assertNull(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.figureGroup)

    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine = null,
          componentLines =
            listOf(
              CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
              CostLine(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, 23000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            ),
        )
      }
    assertTrue(error.message!!.contains("differing or unknown datings"), "got [${error.message}]")
  }

  @Test
  fun `an arrangement refuses a component that is not its own`() {
    // The total used to be gated by a line COUNT: any three published-price
    // lines satisfied ON_CAMPUS. An on-campus housing charge could therefore be
    // carried -- and summed -- under WITH_FAMILY, publishing an at-home total
    // containing a dorm charge. The check is on IDENTITY now.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine =
            CostLine(
              CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
              12000,
              CostsTestDb.PRICE_ACADEMIC_YEAR,
              LineOrigin.PUBLISHED,
            ),
          componentLines =
            listOf(
              CostLine(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, 15000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
              CostLine(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, 3000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            ),
        )
      }
    assertTrue(error.message!!.contains("only its own components"), "got [${error.message}]")
  }

  @Test
  fun `an arrangement refuses the same component twice, however many lines that makes`() {
    // Three copies of the shared books allowance is three lines, which is
    // exactly ON_CAMPUS's component count -- and was therefore a complete
    // budget as far as the old size test could tell.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine =
            CostLine(
              CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
              12000,
              CostsTestDb.PRICE_ACADEMIC_YEAR,
              LineOrigin.PUBLISHED,
            ),
          componentLines =
            List(3) {
              CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED)
            },
        )
      }
    assertTrue(error.message!!.contains("once each"), "got [${error.message}]")
  }

  @Test
  fun `the tuition slot refuses a figure that is not a published tuition figure`() {
    // The slot's type is CostLine, which admits all twelve fields. A component
    // there shares the components' vintage, so every other check passes and the
    // figure is summed a second time under tuition's name.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine = CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
          componentLines =
            listOf(
              CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
              CostLine(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, 3000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            ),
        )
      }
    assertTrue(error.message!!.contains("must be a published tuition figure"), "got [${error.message}]")
    assertEquals(
      setOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
        // The third tier is admitted by the SLOT (RFC 166 §4) so the rule stays
        // "a published tuition figure" rather than "the two tiers we had in
        // 2026". It is never SELECTED into the slot: `applicableTuitionFor`
        // cannot return it, because a family's answered state does not answer a
        // district -- which the sibling test above asserts on a real breakdown.
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      ),
      CostField.TUITION_FIELDS,
      "the allowlist is the published tuition figures, and nothing else",
    )
  }

  @Test
  fun `an arrangement with no line at all is refused, not silently totalled as nothing`() {
    // The zero case the old `vintages.size <= 1` tolerated: no line, no
    // vintage, every check satisfied, and a null total that reads like ordinary
    // partial reporting rather than like the empty value it is.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine = null,
          componentLines = emptyList(),
        )
      }
    assertTrue(error.message!!.contains("absent arrangement, never an empty one"), "got [${error.message}]")
    // The read is batched over a student's whole list, so a refusal that names
    // no school leaves the operator to reproduce the list to find it.
    assertTrue(
      error.message!!.contains("college_id=[${arrangementCollegeId.value}]"),
      "the refusal names WHOSE arrangement failed: got [${error.message}]",
    )
  }

  @Test
  fun `a complete arrangement carries exactly its own components, in render order, and totals them`() {
    // The positive case the refusals above bound: identity, not count, is what
    // admits a total.
    val arrangement =
      ArrangementCost(
        collegeId = arrangementCollegeId,
        arrangement = LivingArrangement.WITH_FAMILY,
        tuitionLine =
          CostLine(
            CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
            12000,
            CostsTestDb.PRICE_ACADEMIC_YEAR,
            LineOrigin.PUBLISHED,
          ),
        componentLines =
          listOf(
            // The at-home arrangement's THIRD part (RFC 166 §7): a `$0` that is
            // ours, and without which this arrangement is not complete.
            CostLine(
              CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD,
              ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD,
              CostsTestDb.PRICE_ACADEMIC_YEAR,
              LineOrigin.ASSUMED_BY_UNICOACH,
            ),
            CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            CostLine(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, 3000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
          ),
      )
    assertEquals(LivingArrangement.WITH_FAMILY.components, arrangement.componentLines.map { it.field })
    assertEquals(16500, arrangement.totalPerYearUsd)

    // A partial one is still legal and still carries NO total.
    val partial =
      ArrangementCost(
        collegeId = arrangementCollegeId,
        arrangement = LivingArrangement.WITH_FAMILY,
        tuitionLine =
          CostLine(
            CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
            12000,
            CostsTestDb.PRICE_ACADEMIC_YEAR,
            LineOrigin.PUBLISHED,
          ),
        componentLines =
          listOf(
            CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
          ),
      )
    assertNull(partial.totalPerYearUsd, "a partial sum is not a total")
  }

  @Test
  fun `the mixed-vintage refusal carries the offending lines, not just a set of vintages`() {
    // This throw is caught into Result.failure by getForStudent, so its message
    // is the whole diagnostic an operator ever sees: it must name WHICH figure
    // came from another reporting year.
    val error =
      assertFailsWith<MixedVintageArrangementException> {
        ArrangementCost(
          collegeId = arrangementCollegeId,
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine =
            CostLine(
              CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
              12000,
              CostsTestDb.PRICE_ACADEMIC_YEAR,
              LineOrigin.PUBLISHED,
            ),
          componentLines =
            listOf(
              CostLine(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, 40000, CostsTestDb.PRICE_ACADEMIC_YEAR, LineOrigin.PUBLISHED),
            ),
        )
      }
    assertEquals(LivingArrangement.ON_CAMPUS, error.arrangement)
    assertEquals(
      setOf(
        FigureGroup.PUBLISHED_PRICE to CostsTestDb.PRICE_ACADEMIC_YEAR,
        FigureGroup.BLENDED_AVERAGE to CostsTestDb.PRICE_ACADEMIC_YEAR,
      ),
      error.vintages,
      "the offending lines are carried, and the (group, academic year) pairs derived from them",
    )
    assertTrue(
      error.message!!.contains(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName) &&
        error.message!!.contains("40000"),
      "the field and its amount are in the message: [${error.message}]",
    )
    assertEquals(arrangementCollegeId, error.collegeId)
    assertTrue(
      error.message!!.contains("college_id=[${arrangementCollegeId.value}]"),
      "and the school it happened to, because the read is batched over a whole list: [${error.message}]",
    )
  }

  @Test
  fun `no arrangement total ever equals the sticker cost by construction`() {
    // RFC 149 D-F rule 1: COSTT4_A is a weighted blend across arrangements and a
    // year older, so it is never the on-campus sum -- it keeps its own key and
    // nothing substitutes one for the other.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Sticker U"))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(40000, cost.stickerCostOfAttendancePerYearUsd)
    val breakdown = assertNotNull(cost.breakdown)

    // The STRUCTURAL claim, which is the rule itself: COSTT4_A is a blend ACROSS
    // arrangements, so it is never a line inside one. Asserted over CostField
    // membership rather than over values, because a comparison of seeded numbers
    // is a property of CostsTestDb and would still pass if the blend were summed
    // in under a different amount.
    assertTrue(
      breakdown.arrangements.none { arrangement ->
        arrangement.lines.any { it.field == CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD }
      },
      "COSTT4_A is a blend across arrangements, so it is never a line in one: [${breakdown.arrangements}]",
    )
    assertTrue(
      breakdown.arrangements.all { arrangement ->
        arrangement.lines.all { it.field.figureGroup == FigureGroup.PUBLISHED_PRICE }
      },
      "an arrangement is one published price list for one year: [${breakdown.arrangements}]",
    )

    // ...and, on these fixture numbers, it does not stand in for a total either.
    val totals = breakdown.arrangements.mapNotNull { it.totalPerYearUsd }
    assertTrue(totals.isNotEmpty())
    assertTrue(
      totals.none { it == cost.stickerCostOfAttendancePerYearUsd },
      "the fixture must keep the two figures distinguishable: [$totals]",
    )
  }

  // ---------------------------------------------------------------------------
  // The no-dorms case (RFC 149 D-B): IPEDS offers_housing, never inferred
  // ---------------------------------------------------------------------------

  @Test
  fun `a school with no residence halls drops on_campus and carries the flag instead`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        // The ORDINARY no-dorms shape: IPEDS says no residence halls and the
        // Scorecard publishes no on-campus figure either, so there is nothing
        // to show and nothing to contradict.
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(false, cost.offersOnCampusHousing)
    assertNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "there is no cheap on-campus option; there is none")
    assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertNotNull(arrangementOf(cost, LivingArrangement.WITH_FAMILY))
  }

  @Test
  fun `published on-campus figures beat the no-dorms flag, and both facts still ride`() {
    // RFC 149 D-B: the two sources can disagree. Suppressing a figure the school
    // itself published would be the worse failure of the two, so the arrangement
    // is rendered from what was published and the flag is reported beside it.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege("Contradiction U", ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(false, cost.offersOnCampusHousing, "the flag is still reported; we do not pick one source and hide the other")
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "the school published these figures itself")
    assertEquals(
      CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      amountsOf(onCampus)[CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD],
      "the published figure is rendered, not dropped",
    )
    assertNotNull(onCampus.totalPerYearUsd, "every part is present, so the arrangement totals like any other")
  }

  @Test
  fun `a contradicting school still names the on-campus part it genuinely does not report`() {
    // The suppression follows the arrangement: once the on-campus arrangement is
    // rendered, a part missing FROM it is ordinary Scorecard silence and must be
    // named, or the coach would be told nothing about a gap it can see.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Half Contradiction U",
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "one published on-campus figure is enough to price it")
    assertTrue(
      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD in cost.notReported,
      "the arrangement is rendered, so its missing part is a silence and is named: [${cost.notReported}]",
    )
    assertNull(
      arrangementOf(cost, LivingArrangement.ON_CAMPUS)?.totalPerYearUsd,
      "and a partial arrangement still carries no total",
    )
  }

  @Test
  fun `the no-dorms case is not a data_availability silence`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Commuter Only U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )

    val cost = profileOf(student).colleges.single()
    assertTrue(
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD !in cost.notReported &&
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD !in cost.notReported,
      "a school with no dorms ANSWERED; it did not stay silent: [${cost.notReported}]",
    )
    assertTrue(
      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD !in cost.notReported,
      "books and supplies is shared by every arrangement and this college reports it",
    )
  }

  @Test
  fun `a school that offers housing keeps on_campus, and no IPEDS fact changes nothing`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Dorms U", ipedsHousing = CostsTestDb.IpedsHousing.OFFERS))
    addToCollegeList(student, seedCollege("No Row U", ipedsHousing = CostsTestDb.IpedsHousing.NO_ROW))
    addToCollegeList(student, seedCollege("Quiet Row U", ipedsHousing = CostsTestDb.IpedsHousing.UNREPORTED))
    answerResidency(student, "CA")

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(true, byName.getValue("Dorms U").offersOnCampusHousing)
    assertNotNull(arrangementOf(byName.getValue("Dorms U"), LivingArrangement.ON_CAMPUS))

    // Both silences fold to the same "not reported", and neither drops the
    // arrangement: absence of the IPEDS fact is not evidence of no dorms.
    for (name in listOf("No Row U", "Quiet Row U")) {
      assertNull(byName.getValue(name).offersOnCampusHousing, "[$name] must read as not reported")
      assertNotNull(arrangementOf(byName.getValue(name), LivingArrangement.ON_CAMPUS), "[$name]")
      assertTrue(
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD !in byName.getValue(name).notReported,
        "[$name] reports the figure, so it is not a silence",
      )
    }
  }

  @Test
  fun `the housing read adds no query per college`() {
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Housing Batch $n", ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER)
          .also { addToCollegeList(student, it) }
      }

    val one = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    assertEquals(1, service.readInSession(one, student, ids.take(1)).colleges.size)
    assertEquals(5, service.readInSession(five, student, ids).colleges.size)

    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "the IPEDS housing read must cost the same statements for five colleges as for one, " +
        "never one per college: one=[${one.prepared}] five=[${five.prepared}]",
    )
    assertEquals(
      5,
      service.readInSession(five, student, ids).colleges.count { it.offersOnCampusHousing == false },
      "and it must actually answer every one of them",
    )
  }

  // ---------------------------------------------------------------------------
  // The comparison contract (RFC 151): per call, and only from two colleges up
  // ---------------------------------------------------------------------------

  /** One school's residency entry inside the comparison basis, by name. */
  private fun residencyEntryOf(
    basis: ComparisonBasis,
    name: String,
  ): CollegeResidencyBasis = basis.residency.byCollege.single { it.name == name }

  @Test
  fun `a one-college answer carries no comparison basis, and two colleges do`() {
    val student = createStudent()
    val first = seedCollege("Alone U").also { addToCollegeList(student, it) }
    val second = seedCollege("Together U").also { addToCollegeList(student, it) }
    answerResidency(student, "CA")

    assertNull(
      profileOf(student, listOf(first)).comparisonBasis,
      "one school is already fully labelled by its own keys; a comparison object would invite a narrated comparison",
    )
    val basis = assertNotNull(profileOf(student, listOf(first, second)).comparisonBasis)
    assertEquals(PopulationBasis.CODE, basis.population.code)
    assertEquals(AidBasis.CODE, basis.aid.code)
    assertTrue(basis.population.statement.isNotEmpty() && basis.aid.statement.isNotEmpty())
  }

  @Test
  fun `an empty list carries no comparison basis either`() {
    val student = createStudent()
    assertNull(profileOf(student).comparisonBasis, "no colleges, nothing held constant")
  }

  @Test
  fun `the residency entry states which published figure applies at each school`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home State U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Away State U", state = "NY", control = 1))
    addToCollegeList(student, seedCollege("Private U", state = "NY", control = 2))
    answerResidency(student, "CA")

    val basis = assertNotNull(profileOf(student).comparisonBasis)
    assertEquals(ComparedResidency.Answered("CA"), basis.residency.answer)
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.IN_STATE),
      residencyEntryOf(basis, "Home State U").tuition,
    )
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.OUT_OF_STATE),
      residencyEntryOf(basis, "Away State U").tuition,
    )
    assertEquals(
      ComparedTuition.SinglePublishedPrice,
      residencyEntryOf(basis, "Private U").tuition,
      "a private school has one published price, so residency is not a distinction it can carry",
    )
    // Every entry says its code aloud, and says it about the school it names.
    basis.residency.byCollege.forEach { entry ->
      assertTrue(entry.statement.contains(entry.name), "the sentence must name its own school: [$entry]")
    }
  }

  @Test
  fun `an unanswered or declined residency leaves every public entry unknown, and says so`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Quiet Public U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Quiet Private U", state = "CA", control = 2))

    val unanswered = assertNotNull(profileOf(student).comparisonBasis)
    assertEquals(
      ComparedResidency.Unanswered,
      unanswered.residency.answer,
      "no state is reachable on an unanswered residency: the case carries none",
    )
    assertEquals(AnswerStatus.UNANSWERED, unanswered.residency.answer.status, "and it echoes the money-profile status")
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.UNKNOWN),
      residencyEntryOf(unanswered, "Quiet Public U").tuition,
    )
    assertTrue(
      unanswered.residency.statement.contains("not on file"),
      "the missing answer is stated, never left for the coach to notice: [${unanswered.residency.statement}]",
    )

    declineResidency(student)
    val declined = assertNotNull(profileOf(student).comparisonBasis)
    assertEquals(ComparedResidency.Declined, declined.residency.answer)
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.UNKNOWN),
      residencyEntryOf(declined, "Quiet Public U").tuition,
    )
  }

  @Test
  fun `an all-private table gets no caveat about public tuition`() {
    // The basis line must be true of THIS table. A residency caveat here would
    // warn about a distinction no school in the comparison makes.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private One U", state = "CA", control = 2))
    addToCollegeList(student, seedCollege("Private Two U", state = "NY", control = 3))
    answerResidency(student, "CA")

    val statement = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(
      statement.contains("each publishes one price for everyone"),
      "the single-published-price case is stated as itself: [$statement]",
    )
    assertFalse(statement.contains("public"), "no school here is public, so nothing is said about public tuition: [$statement]")
    assertFalse(statement.contains("CA"), "the answered state selects nothing here, so it is not claimed to: [$statement]")
  }

  @Test
  fun `an all-public table states the residency it holds constant`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Public One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Public Two U", state = "NY", control = 1))
    answerResidency(student, "CA")

    val answered = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(answered.contains("Every school here is public"), "[$answered]")
    assertTrue(answered.contains("a family living in CA would be charged"), "[$answered]")

    declineResidency(student)
    val declined = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(
      declined.contains("not on file") && declined.contains("neither figure can be shown as theirs"),
      "a declined residency leaves both published figures unclaimed, and says so: [$declined]",
    )
  }

  @Test
  fun `a mixed table says which schools the residency is about, and what the others do`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Mixed Public U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Mixed Private U", state = "CA", control = 2))
    answerResidency(student, "CA")

    val statement = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(
      statement.contains("The public schools here - Mixed Public U -"),
      "the caveat names the schools it is about, never every column: [$statement]",
    )
    assertTrue(statement.contains("a family living in CA would be charged"), "[$statement]")
    assertTrue(
      statement.contains("the other schools here publish one price for everyone"),
      "the private school is not claimed to hold a residency basis: [$statement]",
    )
    assertFalse(
      statement.contains("Mixed Private U"),
      "the single-price school needs no residency caveat of its own; its by_college line says it: [$statement]",
    )
  }

  @Test
  fun `comparable is the intersection, and the school that lacks an arrangement is named with its reason`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Full U", state = "CA", control = 1))
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        state = "CA",
        control = 1,
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val arrangement = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(
      listOf(LivingArrangement.OFF_CAMPUS, LivingArrangement.WITH_FAMILY),
      arrangement.comparable,
      "on_campus is priced at one school only, so a column cannot hold it constant",
    )
    val gap = arrangement.incompleteByCollege.single()
    assertEquals("No Dorms U", gap.name)
    assertEquals(listOf(LivingArrangement.ON_CAMPUS), gap.missing)
    assertEquals(
      ArrangementGap.NO_ON_CAMPUS_HOUSING,
      gap.reason,
      "a school with no residence halls ANSWERED; it did not stay silent",
    )
    assertTrue(
      arrangement.statement.contains("renting off campus") && arrangement.statement.contains("living at home"),
      "the comparable arrangements are said in the words a student says: [${arrangement.statement}]",
    )
  }

  @Test
  fun `an unreported arrangement is a silence, never the no-dorms answer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Reports Everything U", state = "CA", control = 1))
    addToCollegeList(
      student,
      seedCollege(
        // No IPEDS row at all: absence of the fact is not evidence of no dorms,
        // so this gap is ordinary Scorecard silence. Every on-campus part is
        // null INCLUDING the shared books allowance -- one reported part is
        // enough to price an arrangement (RFC 149), so a school that keeps the
        // books figure still has an on-campus arrangement to compare.
        "Quiet U",
        state = "CA",
        control = 1,
        housingAndFoodOnCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
      ),
    )
    answerResidency(student, "CA")

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertNull(byName.getValue("Quiet U").offersOnCampusHousing, "no IPEDS row: we have no housing fact for it")

    val arrangement = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    val gap = arrangement.incompleteByCollege.single()
    assertEquals("Quiet U", gap.name)
    assertEquals(
      ArrangementGap.NOT_REPORTED,
      gap.reason,
      "an unknown housing fact is not the no-dorms answer; what IS true is that this school published no " +
        "on-campus figure, which is what not_reported says",
    )
  }

  @Test
  fun `every school priced the same way leaves the incomplete list empty`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Same One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Same Two U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val arrangement = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(LivingArrangement.entries, arrangement.comparable)
    assertEquals(emptyList(), arrangement.incompleteByCollege, "nothing is missing, so nobody is named")
  }

  @Test
  fun `the academic years name only the vintages the call actually carries`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Dated One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Dated Two U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val years = assertNotNull(profileOf(student).comparisonBasis).academicYears
    assertEquals(FigureGroup.entries, years.map { it.group }, "both figure groups are carried by these fixtures")
    val published = years.single { it.group == FigureGroup.PUBLISHED_PRICE }
    // The year is READ OFF THE ROWS this call served (RFC 166 §3 rule 4), not
    // off a Kotlin constant on the group: the fixture's own academic year is the
    // one the basis states back.
    assertEquals(CostsTestDb.PRICE_ACADEMIC_YEAR, published.academicYear)
    assertTrue(
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD in published.figures,
      "the year names the figures it dates: [${published.figures}]",
    )
    assertTrue(
      published.figures.all { it.figureGroup == FigureGroup.PUBLISHED_PRICE },
      "a year may never date a figure from the other one: [${published.figures}]",
    )
    assertTrue(published.statement.contains(published.academicYear), "the year is spoken, never left as a bare code")
  }

  @Test
  fun `a call whose colleges report only undated figures carries no academic year at all`() {
    val student = createStudent()
    val sparse =
      (1..2).map { n ->
        seedCollege(
          "Undated $n U",
          costOfAttendancePerYearUsd = null,
          netPricePerYearUsd = null,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = null,
          tuitionAndFeesOutOfStatePerYearUsd = null,
          housingAndFoodOnCampusPerYearUsd = null,
          housingAndFoodOffCampusPerYearUsd = null,
          booksAndSuppliesPerYearUsd = null,
          otherExpensesOnCampusPerYearUsd = null,
          otherExpensesOffCampusPerYearUsd = null,
          otherExpensesWithFamilyPerYearUsd = null,
        ).also { addToCollegeList(student, it) }
      }

    val basis = assertNotNull(profileOf(student, sparse).comparisonBasis)
    assertEquals(emptyList(), basis.academicYears, "median debt and median earnings are dated by neither vintage")
    assertEquals(emptyList(), basis.livingArrangement.comparable, "nothing is priced, so nothing is held constant")
    assertTrue(
      basis.livingArrangement.statement.contains("No one way of living is priced at every school here"),
      "the empty intersection is said in words: [${basis.livingArrangement.statement}]",
    )
  }

  @Test
  fun `the comparison basis adds no statement to the read`() {
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Basis Batch $n", state = "CA", control = 1).also { addToCollegeList(student, it) }
      }
    answerResidency(student, "CA")

    val one = CoachingTestDb.CountingSession()
    val two = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    assertNull(service.readInSession(one, student, ids.take(1)).comparisonBasis, "one college, no comparison")
    assertNotNull(service.readInSession(two, student, ids.take(2)).comparisonBasis)
    assertEquals(5, service.readInSession(five, student, ids).colleges.size)

    // RFC 151 D-C: the basis is assembled from facts the read already holds, so
    // the statement count is RFC 149's -- the same for a comparison as for the
    // single-college answer that carries none.
    assertEquals(
      one.prepared.size,
      two.prepared.size,
      "assembling the comparison basis must cost no statement: one=[${one.prepared}] two=[${two.prepared}]",
    )
    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "and five colleges must still cost what one does: one=[${one.prepared}] five=[${five.prepared}]",
    )
  }

  // ---------------------------------------------------------------------------
  // The resolved living plan (RFC 152)
  // ---------------------------------------------------------------------------

  @Test
  fun `no plan and no override leaves the answer exactly as it was - all three arrangements, nothing chosen`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Unanswered Plan U"))

    val cost = profileOf(student).colleges.single()
    assertIs<ChosenLivingPlan.NotChosen>(cost.chosen, "nothing said means nothing led with")
    assertEquals(
      LivingArrangement.entries,
      cost.breakdown!!.arrangements.map { it.arrangement },
      "and the breakdown is untouched: RFC 152 D3, today's behaviour exactly",
    )
  }

  @Test
  fun `a declined plan chooses nothing and is never re-offered`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Declined Plan U"))
    declineLivingPlan(student)

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertIs<ComparedLivingPlan.Declined>(profile.moneyProfile.living)
    assertIs<ChosenLivingPlan.NotChosen>(cost.chosen, "a decline leads with nothing, forever")
    assertEquals(
      LivingArrangement.entries,
      cost.breakdown!!.arrangements.map { it.arrangement },
      "and still shows every way of living the school publishes",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN !in profile.precisionOffersFor(cost),
      "the coach must never be cued to reopen a declined plan",
    )
  }

  @Test
  fun `the usual plan is assumed for a school with no override, and says it was assumed`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Default Plan U"))
    // A public school prices nothing until the residency question is answered,
    // so the plan is answered too: this test is about the resolution, not about
    // a tuition line that is still open.
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val cost = profileOf(student).colleges.single()
    val chosen = assertIs<ChosenLivingPlan.Priced>(cost.chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, chosen.plan)
    assertEquals(
      LivingPlanSource.PROFILE_DEFAULT,
      chosen.source,
      "with_family is never inferred by us: an assumed plan must stay nameable as an assumption",
    )
  }

  @Test
  fun `a school's own plan beats the usual plan, and a cleared override falls back to it`() {
    val student = createStudent()
    val college = seedCollege("Override U")
    addToCollegeList(student, college)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)
    val overridden = assertIs<ChosenLivingPlan.Priced>(profileOf(student).colleges.single().chosen)
    assertEquals(LivingArrangement.ON_CAMPUS, overridden.plan, "override beats default")
    assertEquals(
      LivingPlanSource.PER_COLLEGE,
      overridden.source,
      "and says so, because 'you told us this for this school' is a different sentence from an assumption",
    )

    CostsTestDb.setEntryLivingPlan(student, college, null)
    val cleared = assertIs<ChosenLivingPlan.Priced>(profileOf(student).colleges.single().chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, cleared.plan, "NULL is 'no override', so the usual plan returns")
    assertEquals(LivingPlanSource.PROFILE_DEFAULT, cleared.source)
  }

  @Test
  fun `an override alone resolves a school even with no usual plan on file`() {
    // Feasibility is a fact about the student-college pair, so a family can
    // state it about one school without ever stating a global preference.
    val student = createStudent()
    val overridden = seedCollege("Commutable U")
    val plain = seedCollege("Far Away U")
    addToCollegeList(student, overridden)
    addToCollegeList(student, plain)
    answerResidency(student, "CA")
    CostsTestDb.setEntryLivingPlan(student, overridden, LivingArrangement.WITH_FAMILY)

    val byName = profileOf(student).colleges.associateBy { it.name }
    val chosen = assertIs<ChosenLivingPlan.Priced>(byName.getValue("Commutable U").chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, chosen.plan)
    assertEquals(LivingPlanSource.PER_COLLEGE, chosen.source)
    assertIs<ChosenLivingPlan.NotChosen>(
      byName.getValue("Far Away U").chosen,
      "a school the family said nothing about stays unchosen; an override is never a global answer",
    )
  }

  @Test
  fun `a school with no residence halls says so rather than substituting another arrangement`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)

    val cost = profileOf(student).colleges.single()
    val notPriced = assertIs<ChosenLivingPlan.NotPricedHere>(cost.chosen)
    assertEquals(LivingArrangement.ON_CAMPUS, notPriced.plan)
    assertEquals(
      ArrangementGap.NO_ON_CAMPUS_HOUSING,
      notPriced.reason,
      "the school ANSWERED: it has no residence halls, which is not the same as silence",
    )
    assertFalse(
      cost.breakdown!!.arrangements.any { it.arrangement == LivingArrangement.ON_CAMPUS },
      "and no on-campus arrangement is invented to satisfy the plan",
    )
  }

  @Test
  fun `a school showing the chosen plan with no total says so, and is never blamed for our gap`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Silent Off Campus U", housingAndFoodOffCampusPerYearUsd = null))
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val cost = profileOf(student).colleges.single()
    // Three outcomes, not two. The arrangement still exists -- its other parts
    // are published -- but it carries no total, so the family gets no number to
    // lead with. That is NoTotalHere: stated, never a Priced with a silent
    // blank where its number should be, and never NotPricedHere, whose
    // ArrangementGap vocabulary would blame the school's price list for a
    // missing PART (RFC 149 D-B). A missing part is a labelled blank; a missing
    // ARRANGEMENT is a stated reason.
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(LivingArrangement.OFF_CAMPUS, noTotal.plan)
    assertNull(noTotal.cost.totalPerYearUsd, "a partial arrangement carries no total, and never a partial sum")
    assertEquals(
      NoTotalReason.PART_NOT_PUBLISHED,
      noTotal.reason,
      "and the cause is named: this one IS the school's silence about a part it does not publish",
    )
    assertTrue(
      cost.breakdown!!.arrangements.any { it.arrangement == LivingArrangement.OFF_CAMPUS },
      "and the way of living stays in the payload, each published part still labelled (D2)",
    )
  }

  @Test
  fun `an unanswered residency leaves a public school with no total, and the school is not blamed for it`() {
    // The regression this case exists to prevent. A public school with the
    // residency question still open has no tuition line, so no arrangement has
    // a total -- but the school published its prices in full. Reading that as
    // "this school publishes no price for that way of living" states OUR gap as
    // a fact about the school's price list, which is the one thing the
    // ArrangementGap vocabulary must never be used to say.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Public No Residency U"))
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val cost = profileOf(student).colleges.single()
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, noTotal.plan)
    assertEquals(
      LivingPlanSource.PROFILE_DEFAULT,
      noTotal.source,
      "the plan still resolved, and still says where it came from: only the number is missing",
    )
    assertEquals(
      NoTotalReason.AWAITING_RESIDENCY_ANSWER,
      noTotal.reason,
      "and the missing total is named as OUR open question, never as this school's silence",
    )
  }

  @Test
  fun `a school priced for nothing at all reports the chosen plan as not reported`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Components U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val cost = profileOf(student).colleges.single()
    assertNull(cost.breakdown, "a school that reports no component has no breakdown at all")
    val notPriced = assertIs<ChosenLivingPlan.NotPricedHere>(cost.chosen)
    assertEquals(
      ArrangementGap.NOT_REPORTED,
      notPriced.reason,
      "an absent IPEDS row is a gap in OUR data, never evidence a school has no residence halls",
    )
  }

  @Test
  fun `a with_family default and an on_campus override on the far school price both correctly in one comparison`() {
    // Ian's own case (brief 0003 D20): commutable in-state school, far
    // out-of-state one. One family, two plans, and both totals must be the
    // school's own -- the failure this override exists to prevent is a single
    // arrangement held across a table it is not true of.
    val student = createStudent()
    val nearby = seedCollege("Nearby State U", state = "CA", control = 1)
    val faraway = seedCollege("Faraway State U", state = "NY", control = 1)
    addToCollegeList(student, nearby)
    addToCollegeList(student, faraway)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, faraway, LivingArrangement.ON_CAMPUS)

    val profile = profileOf(student)
    val byName = profile.colleges.associateBy { it.name }

    val near = assertIs<ChosenLivingPlan.Priced>(byName.getValue("Nearby State U").chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, near.plan)
    assertEquals(LivingPlanSource.PROFILE_DEFAULT, near.source, "the usual plan, assumed for the commutable school")
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      near.cost.totalPerYearUsd,
      "in-state tuition and fees plus the at-home allowances, and NO housing and food line: that is data",
    )
    assertFalse(
      near.cost.lines.any { it.field == CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD },
      "living at home carries no housing and food line, and never a zero one",
    )

    val far = assertIs<ChosenLivingPlan.Priced>(byName.getValue("Faraway State U").chosen)
    assertEquals(LivingArrangement.ON_CAMPUS, far.plan)
    assertEquals(LivingPlanSource.PER_COLLEGE, far.source, "this school's own plan, stated by the family")
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD + CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      far.cost.totalPerYearUsd,
      "out-of-state tuition and fees plus the on-campus allowances - this school's own basis, not the other's",
    )

    // Neither answer narrowed: all three arrangements are still true facts on
    // both schools, so "what if he lived at home at the far school?" is still
    // answerable from the same result (D2).
    profile.colleges.forEach { cost ->
      assertEquals(
        LivingArrangement.entries,
        cost.breakdown!!.arrangements.map { it.arrangement },
        "a resolved plan decides what is LED with, never what exists: [${cost.name}]",
      )
    }
    val arrangement = assertNotNull(profile.comparisonBasis).livingArrangement
    assertEquals(
      LivingArrangement.entries,
      arrangement.comparable,
      "and ArrangementBasis.comparable is not narrowed by a resolved plan either",
    )
  }

  @Test
  fun `an answered plan with no stored value is refused, never relabelled as never asked`() {
    // The third field joins requireIntactAnswers. Reading a corrupt row as
    // "unanswered" would have the coach ASK a family a question they already
    // answered - the one thing the tri-state exists to prevent.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Corrupt Plan U"))
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)
    CoachingTestDb.sqlSession
      .prepareStatement(
        "ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_living_plan_value_iff_answered_check",
      ).use { it.execute() }
    CoachingTestDb.sqlSession
      .prepareStatement("UPDATE money_profiles SET living_plan = NULL, version = version + 1 WHERE student_id = ?")
      .use {
        it.setObject(1, student.value)
        it.executeUpdate()
      }

    val failure = runCatching { profileOf(student) }.exceptionOrNull()

    // Restored before the assertions: the suite shares one database, and a
    // dropped constraint left behind would weaken whichever test runs next
    // rather than failing this one.
    CoachingTestDb.sqlSession
      .prepareStatement(
        "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_living_plan_value_iff_answered_check " +
          "CHECK ((living_plan IS NOT NULL) = (living_plan_status = 'answered')) NOT VALID",
      ).use { it.execute() }

    assertIs<CorruptPersistedValueException>(failure, "got $failure")
    assertTrue(
      failure.message!!.contains("money_profiles.[living_plan]"),
      "the failure must name the corrupt column: [${failure.message}]",
    )
  }

  @Test
  fun `the living-plan offer rests only on a school with something to choose between`() {
    val student = createStudent()
    // Priced one way only: the school publishes just its at-home allowance, so
    // no on-campus or off-campus arrangement exists at all and the question
    // would buy this family nothing here. The SHARED books allowance is nulled
    // too, deliberately -- it belongs to every arrangement, so leaving it would
    // give this school three arrangements made of one line each.
    addToCollegeList(
      student,
      seedCollege(
        "One Way U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
      ),
    )
    addToCollegeList(student, seedCollege("Three Ways U"))
    // Residency answered, because the gate counts PRICED arrangements and a
    // public school with no residency on file totals none of them.
    answerResidency(student, "CA")

    val profile = profileOf(student)
    val byName = profile.colleges.associateBy { it.name }
    assertEquals(
      1,
      byName
        .getValue("One Way U")
        .breakdown!!
        .arrangements.size,
      "the fixture must actually price this school one way, or the test asserts nothing",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN !in profile.precisionOffersFor(byName.getValue("One Way U")),
      "an offer must never rest on a school with nothing to choose between",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN in profile.precisionOffersFor(byName.getValue("Three Ways U")),
      "and must be on offer where the plan actually moves the picture",
    )
  }

  @Test
  fun `arrangements present but not priced are nothing to choose between, so no living-plan offer`() {
    // The case that distinguishes "two arrangements" from "two PRICED
    // arrangements" (D4). This school publishes every component, so all three
    // arrangements exist -- but it publishes no tuition figure at all, so not
    // one of them carries a total. Asking the family where they plan to live
    // would move no number they can see, which is the definition of an offer
    // resting on nothing.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Priceless U",
        control = 2,
        tuitionAndFeesInStatePerYearUsd = null,
        tuitionAndFeesOutOfStatePerYearUsd = null,
      ),
    )

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val arrangements = assertNotNull(cost.breakdown).arrangements
    assertEquals(
      LivingArrangement.entries.size,
      arrangements.size,
      "the fixture must give this school every arrangement, or it tests the size gate instead",
    )
    assertEquals(
      0,
      arrangements.count { it.totalPerYearUsd != null },
      "and none of them may carry a total, which is the whole distinction under test",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN !in profile.precisionOffersFor(cost),
      "three total-less arrangements are nothing to choose between: the old size gate would have offered here",
    )
  }

  @Test
  fun `resolving the living plan adds no query per college`() {
    // The override is read from the list entry the selection ALREADY holds, and
    // the default from the money profile read once -- so the resolution costs
    // zero extra statements, whatever the list's size.
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Plan Batch $n").also { addToCollegeList(student, it) }
      }
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, ids.last(), LivingArrangement.ON_CAMPUS)

    val one = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    service.readInSession(one, student, ids.take(1))
    val all = service.readInSession(five, student, ids)
    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "one=[${one.prepared}] five=[${five.prepared}]",
    )
    assertEquals(
      LivingArrangement.ON_CAMPUS,
      assertIs<ChosenLivingPlan.Priced>(all.colleges.last().chosen).plan,
      "and the override still resolved inside that same statement budget",
    )
  }

  @Test
  fun `one plan priced at every school holds the comparison column and names the way of living`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home One U"))
    addToCollegeList(student, seedCollege("Home Two U"))
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.ONE_PLAN_EVERY_SCHOOL, basis.scope)
    assertEquals(ComparedLivingPlan.Answered(LivingArrangement.WITH_FAMILY), basis.answer)
    assertTrue(
      basis.statement.contains(LivingArrangement.WITH_FAMILY.label) &&
        basis.statement.contains("the column holds that one way of living"),
      "the plan is named in the student's words and the column is stated: [${basis.statement}]",
    )
  }

  @Test
  fun `different plans across the compared schools name the plan used for each`() {
    val student = createStudent()
    val near = seedCollege("Nearby U")
    val far = seedCollege("Faraway U")
    addToCollegeList(student, near)
    addToCollegeList(student, far)
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, far, LivingArrangement.ON_CAMPUS)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.PLAN_VARIES_BY_SCHOOL, basis.scope)
    assertTrue(
      basis.statement.contains("not all on the same plan") && basis.statement.contains("the family's actual situation"),
      "the column holds the situation rather than one arrangement: [${basis.statement}]",
    )
    assertTrue(
      basis.statement.contains("Nearby U: ${LivingArrangement.WITH_FAMILY.label}") &&
        basis.statement.contains("Faraway U: ${LivingArrangement.ON_CAMPUS.label}"),
      "and names the plan used for EACH school, before the numbers: [${basis.statement}]",
    )
    assertEquals(
      listOf(LivingArrangement.WITH_FAMILY, LivingArrangement.ON_CAMPUS),
      basis.byCollege.map { it.plan },
      "the per-school resolution is read off the per-college answer, never re-derived",
    )
    assertEquals(
      LivingArrangement.entries,
      basis.comparable,
      "and comparable is not narrowed by any of it (D2)",
    )
  }

  @Test
  fun `a school with a plan of its own beside a school with none is still varies-by-school`() {
    val student = createStudent()
    val commutable = seedCollege("Commutable U")
    addToCollegeList(student, commutable)
    addToCollegeList(student, seedCollege("Nothing Said U"))
    CostsTestDb.setEntryLivingPlan(student, commutable, LivingArrangement.WITH_FAMILY)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.PLAN_VARIES_BY_SCHOOL, basis.scope)
    assertTrue(
      basis.statement.contains("Nothing Said U: no plan on file"),
      "the school nobody said anything about is named as such, never given its neighbour's plan: [${basis.statement}]",
    )
    assertIs<ComparedLivingPlan.Unanswered>(basis.answer, "and the usual plan is still an open question")
  }

  @Test
  fun `a plan some school is not priced for names those schools with their reason`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Has Dorms U"))
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE, basis.scope)
    assertTrue(
      basis.statement.contains("No Dorms U: no residence halls"),
      "the school is named with its OWN reason, reusing the ArrangementGap split: [${basis.statement}]",
    )
    assertTrue(
      basis.statement.contains("never quote another way of living in their place"),
      "and no arrangement is substituted for the missing one: [${basis.statement}]",
    )
    assertFalse(
      basis.statement.contains("Has Dorms U"),
      "a school that IS priced for the plan is not named as a gap: [${basis.statement}]",
    )
  }

  @Test
  fun `every ArrangementScope code is reachable and labels its own statement`() {
    // The other half of ComparisonBasisTest's vocabulary check, and the half
    // that needs real fixtures: a code no arrangement of the facts produces is
    // a vocabulary entry nothing means, and two codes sharing a sentence would
    // make the code finer than the copy it labels. Both are the same defect in
    // opposite directions, so both are checked here, where a school is priced
    // by an actual `colleges` row.
    val bases =
      ArrangementScope.entries.associateWith { scope ->
        val student = createStudent()
        when (scope) {
          ArrangementScope.ONE_PLAN_EVERY_SCHOOL -> {
            addToCollegeList(student, seedCollege("Scope One A"))
            addToCollegeList(student, seedCollege("Scope One B"))
            // Residency too: a public school prices nothing while that question
            // is open, and "priced for it everywhere" is about a total.
            answerResidency(student, "CA")
            answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
          }

          ArrangementScope.PLAN_VARIES_BY_SCHOOL -> {
            addToCollegeList(student, seedCollege("Scope Varies A"))
            val far = seedCollege("Scope Varies B").also { addToCollegeList(student, it) }
            answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
            CostsTestDb.setEntryLivingPlan(student, far, LivingArrangement.ON_CAMPUS)
          }

          ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE -> {
            addToCollegeList(student, seedCollege("Scope Gap A"))
            addToCollegeList(
              student,
              seedCollege(
                "Scope Gap B",
                housingAndFoodOnCampusPerYearUsd = null,
                otherExpensesOnCampusPerYearUsd = null,
                ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
              ),
            )
            answerResidency(student, "CA")
            answerLivingPlan(student, LivingArrangement.ON_CAMPUS)
          }

          ArrangementScope.NO_PLAN_COMPARABLE -> {
            addToCollegeList(student, seedCollege("Scope Open A"))
            addToCollegeList(student, seedCollege("Scope Open B"))
          }

          ArrangementScope.NO_PLAN_NOTHING_COMPARABLE -> {
            addToCollegeList(
              student,
              seedCollege(
                "Scope Silent A",
                housingAndFoodOnCampusPerYearUsd = null,
                housingAndFoodOffCampusPerYearUsd = null,
                booksAndSuppliesPerYearUsd = null,
                otherExpensesOnCampusPerYearUsd = null,
                otherExpensesOffCampusPerYearUsd = null,
              ),
            )
            addToCollegeList(
              student,
              seedCollege(
                "Scope Silent B",
                housingAndFoodOffCampusPerYearUsd = null,
                booksAndSuppliesPerYearUsd = null,
                otherExpensesOffCampusPerYearUsd = null,
                otherExpensesWithFamilyPerYearUsd = null,
              ),
            )
          }
        }
        assertNotNull(profileOf(student).comparisonBasis).livingArrangement
      }

    bases.forEach { (expected, basis) ->
      assertEquals(expected, basis.scope, "the fixture built for [$expected] must actually produce it")
    }
    val statements = bases.values.map { it.statement }
    assertEquals(
      statements.size,
      statements.toSet().size,
      "each code must label its OWN sentence, or the code is finer than the copy it labels: $statements",
    )
    assertTrue(statements.none { it.isEmpty() }, "a code with no sentence beside it is half a fact")
  }

  @Test
  fun `no plan on file keeps RFC 151's two arrangement statements byte-for-byte`() {
    // RFC 152 D3, the whole backward-compatibility story: an unanswered or
    // declined plan renders exactly as it did, forever. The code is new; the
    // sentences are not.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Every Way One U"))
    addToCollegeList(student, seedCollege("Every Way Two U"))

    val comparable = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.NO_PLAN_COMPARABLE, comparable.scope)
    assertTrue(
      comparable.statement.startsWith("Every school here is priced for") &&
        comparable.statement.endsWith("name the one you are quoting."),
      "RFC 151's non-empty statement, unchanged: [${comparable.statement}]",
    )

    val other = createStudent()
    addToCollegeList(
      other,
      seedCollege(
        "Only At Home U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
      ),
    )
    addToCollegeList(
      other,
      seedCollege(
        "Only On Campus U",
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )
    declineLivingPlan(other)

    val nothingShared = assertNotNull(profileOf(other).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.NO_PLAN_NOTHING_COMPARABLE, nothingShared.scope)
    assertEquals(
      "No one way of living is priced at every school here, so a column cannot hold the living arrangement " +
        "constant: quote each school for the ways of living it does publish, and say which one you are quoting.",
      nothingShared.statement,
      "RFC 151's empty statement, byte-for-byte",
    )
    assertIs<ComparedLivingPlan.Declined>(
      nothingShared.answer,
      "a declined plan is reachable as itself, never as an absent value",
    )
  }

  // ---------------------------------------------------------------------------
  // RFC 157: the residency basis of the two blended figures
  // ---------------------------------------------------------------------------

  /**
   * UC San Diego (IPEDS 110680) as the Scorecard actually publishes it -- every
   * figure below is the live value recorded in RFC 157's evidence.
   *
   * A REAL row rather than a round-numbered one, because the whole defect is
   * arithmetic: `COSTT4_A` = 38,701 sits inside the in-state span and BELOW the
   * out-of-state minimum of 59,923, and a weighted average cannot fall below its
   * own smallest input. Fixture numbers chosen for readability would have proved
   * nothing about it.
   */
  private fun seedUcSanDiego(name: String = "UC San Diego"): CollegeId =
    seedCollege(
      name,
      state = UCSD_STATE,
      control = 1,
      costOfAttendancePerYearUsd = UCSD_COSTT4_A,
      netPricePerYearUsd = UCSD_NPT4_PUB,
      netPricePerYearIncomeQ1Usd = UcsdScorecardRow.NPT41_PUB,
      netPricePerYearIncomeQ2Usd = UcsdScorecardRow.NPT42_PUB,
      netPricePerYearIncomeQ3Usd = UcsdScorecardRow.NPT43_PUB,
      netPricePerYearIncomeQ4Usd = UcsdScorecardRow.NPT44_PUB,
      netPricePerYearIncomeQ5Usd = UCSD_NPT45_PUB,
      tuitionAndFeesInStatePerYearUsd = UcsdScorecardRow.TUITIONFEE_IN,
      tuitionAndFeesOutOfStatePerYearUsd = UcsdScorecardRow.TUITIONFEE_OUT,
      housingAndFoodOnCampusPerYearUsd = UcsdScorecardRow.ROOMBOARD_ON_CAMPUS,
      housingAndFoodOffCampusPerYearUsd = UcsdScorecardRow.ROOMBOARD_OFF_CAMPUS,
      booksAndSuppliesPerYearUsd = UcsdScorecardRow.BOOKSUPPLY,
      otherExpensesOnCampusPerYearUsd = UcsdScorecardRow.OTHEREXPENSE_ON_CAMPUS,
      otherExpensesOffCampusPerYearUsd = UcsdScorecardRow.OTHEREXPENSE_OFF_CAMPUS,
      otherExpensesWithFamilyPerYearUsd = UcsdScorecardRow.OTHEREXPENSE_WITH_FAMILY,
    )

  /** One arrangement's settled total, or null -- read off the breakdown the payload itself renders. */
  private fun totalOf(
    cost: CollegeCost,
    arrangement: LivingArrangement,
  ): Int? =
    cost.breakdown
      ?.arrangements
      ?.firstOrNull { it.arrangement == arrangement }
      ?.totalPerYearUsd

  @Test
  fun `a WA family at UC San Diego gets the out-of-state totals and NEITHER blended figure`() {
    // The report Ian read: correct 77K/77K/60K totals with a $39K "published
    // price" two rows below them, and nothing saying the two were on different
    // residency bases (RFC 157 D-A).
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()

    assertEquals(77102, totalOf(cost, LivingArrangement.ON_CAMPUS), "out-of-state tuition plus the parts")
    assertEquals(77659, totalOf(cost, LivingArrangement.OFF_CAMPUS))
    assertEquals(59923, totalOf(cost, LivingArrangement.WITH_FAMILY))

    assertNull(
      cost.stickerCostOfAttendancePerYearUsd,
      "COSTT4_A is an in-state figure, so a WA family is never shown it",
    )
    assertNull(cost.netPrice.amount, "NPT4_PUB is in-state too, and it is the more dangerous half")
    assertIs<NetPrice.Withheld>(cost.netPrice, "the blank says WHY, so no reader can mistake it for silence")
    assertEquals(BlendedFigureApplicability.WITHHELD, cost.blendedFiguresApply)
    assertEquals(
      listOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
        CostField.NET_PRICE,
      ),
      cost.withheld.map { it.field },
      "both in-state-only figures are withheld, in vocabulary order",
    )
    assertTrue(
      cost.withheld.all { it.reason == WithheldReason.IN_STATE_ONLY_FIGURE },
      "one reason, and it is ours rather than the school's silence",
    )
  }

  @Test
  fun `a withheld figure is in NEITHER the reported nor the not-reported list`() {
    // The school published both numbers, so calling them unreported would blame
    // its price list for our applicability rule -- the RFC 149 D-B split, reused.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()
    val withheld = cost.withheld.map { it.field }.toSet()

    assertTrue(
      withheld.none { it in cost.reported },
      "a withheld figure is not shown, so it is not among the figures this answer reports",
    )
    assertTrue(
      withheld.none { it in cost.notReported },
      "and it is not this school's silence either: it published both numbers",
    )
  }

  @Test
  fun `an in-state family at the same school is shown both blended figures`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, UCSD_STATE)

    val cost = profileOf(student).colleges.single()

    assertEquals(42902, totalOf(cost, LivingArrangement.ON_CAMPUS), "in-state tuition plus the parts")
    assertEquals(43459, totalOf(cost, LivingArrangement.OFF_CAMPUS))
    assertEquals(25723, totalOf(cost, LivingArrangement.WITH_FAMILY))
    assertEquals(UCSD_COSTT4_A, cost.stickerCostOfAttendancePerYearUsd)
    assertEquals(UCSD_NPT4_PUB, cost.netPrice.amount)
    assertEquals(BlendedFigureApplicability.APPLIES, cost.blendedFiguresApply)
    assertEquals(emptyList(), cost.withheld, "an in-state family is the family these figures describe")
  }

  @Test
  fun `an unanswered residency withholds NOTHING and shows both figures`() {
    // RFC 157 D-B. An unanswered question is not licence to hide the only price
    // we hold, and no answer of ours is gated on a completed profile (0001 D11).
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())

    val cost = profileOf(student).colleges.single()

    assertEquals(
      BlendedFigureApplicability.BASIS_STATED,
      cost.blendedFiguresApply,
      "an open question is not a withholding",
    )
    assertEquals(emptyList(), cost.withheld)
    assertEquals(UCSD_COSTT4_A, cost.stickerCostOfAttendancePerYearUsd)
    assertEquals(UCSD_NPT4_PUB, cost.netPrice.amount)
  }

  @Test
  fun `a declined residency also withholds nothing`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    declineResidency(student)

    val cost = profileOf(student).colleges.single()
    assertEquals(BlendedFigureApplicability.BASIS_STATED, cost.blendedFiguresApply)
    assertEquals(emptyList(), cost.withheld, "a closed topic must not cost the family the figures we hold")
    assertEquals(UCSD_COSTT4_A, cost.stickerCostOfAttendancePerYearUsd)
  }

  @Test
  fun `a private college is unchanged for an out-of-state family`() {
    // In-state and out-of-state do not exist at a private school (RFC 135), so
    // there is no residency basis for these figures to fail to match.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private U", state = "CA", control = 2))
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()
    assertEquals(BlendedFigureApplicability.APPLIES, cost.blendedFiguresApply)
    assertEquals(emptyList(), cost.withheld)
    assertEquals(40000, cost.stickerCostOfAttendancePerYearUsd)
    assertEquals(20000, cost.netPrice.amount)
  }

  @Test
  fun `a public school in another state that publishes NEITHER figure withholds nothing`() {
    // The school's own silence must stay the school's (RFC 157 D-A). Withholding
    // a figure that does not exist would tell a family "this school publishes
    // this figure" about a figure nobody published, and would delete the real
    // silence from `notReported`.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Silent Public U",
        state = UCSD_STATE,
        control = 1,
        costOfAttendancePerYearUsd = null,
        netPricePerYearUsd = null,
        netPricePerYearIncomeQ1Usd = null,
        netPricePerYearIncomeQ2Usd = null,
        netPricePerYearIncomeQ3Usd = null,
        netPricePerYearIncomeQ4Usd = null,
        netPricePerYearIncomeQ5Usd = null,
      ),
    )
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()

    assertEquals(emptyList(), cost.withheld, "there is nothing to hold back: the school published neither figure")
    assertTrue(
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD in cost.notReported &&
        CostField.NET_PRICE in cost.notReported,
      "a school that published nothing must still read as not reported by this school: " +
        "not_reported=[${cost.notReported}]",
    )
    assertIs<NetPrice.OverallAverage>(cost.netPrice, "a silence is not a withholding")
    assertEquals(
      BlendedFigureApplicability.WITHHELD,
      cost.blendedFiguresApply,
      "the basis still does not describe this family; there is simply no figure to hold back",
    )
  }

  @Test
  fun `the withheld band price keeps the band the family answered, and only loses the number`() {
    // The family answered the income question; that fact is theirs whether or
    // not this school's figure is one we can show them.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")
    answerBand(student, IncomeBand.OVER_110K)

    val netPrice = assertIs<NetPrice.Withheld>(profileOf(student).colleges.single().netPrice)
    val basis = assertIs<NetPriceBasis.YourIncomeBand>(netPrice.publishedBasis)
    assertEquals(IncomeBand.OVER_110K, basis.band, "the answered band survives the withholding")
    assertNull(netPrice.amount, "the top band is an in-state figure and is not this family's")
    assertEquals(WithheldReason.IN_STATE_ONLY_FIGURE, netPrice.reason, "the reason travels with the blank")
  }

  @Test
  fun `the same top-band family in California sees that band's price`() {
    // The other half of the case above: the withholding is about residency, and
    // nothing else about the band selection changed.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, UCSD_STATE)
    answerBand(student, IncomeBand.OVER_110K)

    val netPrice = assertIs<NetPrice.BandSpecific>(profileOf(student).colleges.single().netPrice)
    assertEquals(UCSD_NPT45_PUB, netPrice.amount)
  }

  @Test
  fun `the comparison basis carries SIX facts, and the sixth names the school it withholds at`() {
    // RFC 157 D-C: the object claimed for five facts that residency was one of
    // them, and for two of its columns it did not keep that promise.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    addToCollegeList(student, seedCollege("Home State U", state = "WA", control = 1))
    answerResidency(student, "WA")

    val basis = assertNotNull(profileOf(student).comparisonBasis)
    val blended = basis.blendedFigures

    assertEquals(BlendedFigureScope.WITHHELD_AT_SOME_SCHOOLS, blended.scope)
    assertTrue(
      blended.statement.contains("UC San Diego") && !blended.statement.contains("Home State U"),
      "the sentence names the schools these figures do NOT describe: [${blended.statement}]",
    )
    assertEquals(
      listOf(BlendedFigureApplicability.APPLIES, BlendedFigureApplicability.WITHHELD),
      blended.byCollege.sortedBy { it.name }.map { it.applies },
      "Home State U is theirs; UC San Diego is not",
    )
    assertEquals(
      basis.statements[basis.statements.indexOf(basis.residency.statement) + 1],
      blended.statement,
      "the sixth fact is said immediately after the residency line that makes it dangerous",
    )
  }

  @Test
  fun `an in-state family at every public school gets the in-state scope`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    addToCollegeList(student, seedCollege("Also California U", state = UCSD_STATE, control = 1))
    answerResidency(student, UCSD_STATE)

    val blended = assertNotNull(profileOf(student).comparisonBasis).blendedFigures
    assertEquals(BlendedFigureScope.IN_STATE_EVERYWHERE, blended.scope)
    assertTrue(
      blended.byCollege.all { it.applies == BlendedFigureApplicability.APPLIES },
      "both schools' figures are this family's",
    )
  }

  @Test
  fun `an all-private comparison says these figures are on no residency basis`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private One U", control = 2))
    addToCollegeList(student, seedCollege("Private Two U", control = 3))
    answerResidency(student, "WA")

    val blended = assertNotNull(profileOf(student).comparisonBasis).blendedFigures
    assertEquals(BlendedFigureScope.NO_RESIDENCY_BASIS_HERE, blended.scope)
    assertTrue(
      blended.statement.contains("not on any residency basis"),
      "an all-private table must not be given a caveat about public tuition: [${blended.statement}]",
    )
  }

  @Test
  fun `a public school with the residency unanswered states the in-state basis rather than withholding`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    addToCollegeList(student, seedCollege("Second Public U", control = 1))

    val blended = assertNotNull(profileOf(student).comparisonBasis).blendedFigures
    assertEquals(BlendedFigureScope.RESIDENCY_NOT_ON_FILE, blended.scope)
    assertTrue(
      blended.statement.contains("students paying in-state tuition") &&
        blended.statement.contains("rather than withheld"),
      "D-B: the basis is stated and nothing is hidden: [${blended.statement}]",
    )
  }

  @Test
  fun `the single-school basis states the blended-figure residency too`() {
    // A one-school answer builds no comparison (RFC 151 D-B), and the parent
    // reading it is owed the same sixth fact.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val residency =
      ResidencyBasis
        .of(profile.colleges, profile.moneyProfile)
        .byCollege
        .single()
    val basis = SingleSchoolBasis.of(cost, residency)

    assertEquals(BlendedFigureApplicability.WITHHELD, basis.blendedFigures.applies)
    assertTrue(
      basis.blendedFigures.statement in basis.statements,
      "the statement list is what every renderer prints, so a fact outside it reaches nobody",
    )
    assertTrue(
      basis.blendedFigures.statement.contains("out-of-state tuition and fees are the ones that apply"),
      "a blank with no destination is a number taken away: [${basis.blendedFigures.statement}]",
    )
  }

  @Test
  fun `no in-state-only figure survives for a family the in-state basis does not describe`() {
    // The guard RFC 157 asks for, stated over the vocabulary rather than over
    // one fixture: whatever [CostField.IN_STATE_ONLY_FIELDS] comes to hold, the
    // read must carry no amount for any of it at a non-matching public school.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()
    val amounts =
      mapOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD to cost.stickerCostOfAttendancePerYearUsd,
        CostField.NET_PRICE to cost.netPrice.amount,
      )

    assertEquals(
      CostField.IN_STATE_ONLY_FIELDS.toSet(),
      amounts.keys,
      "every in-state-only field must be checked here, or a third one ships unguarded",
    )
    assertTrue(
      amounts.values.all { it == null },
      "an in-state-only figure reached a family the in-state basis does not describe: amounts=[$amounts]",
    )
  }

  companion object {
    // The row itself lives in [UcsdScorecardRow], the one fixture home this
    // module and :public-web both read, so a corrected Scorecard figure is one
    // edit. The three names below are the figures these cases ASSERT on, kept
    // short at the assertion sites that read them.

    /** UC San Diego's own state, so "in-state" in these cases is a fact rather than a coincidence of literals. */
    private const val UCSD_STATE = UcsdScorecardRow.STATE

    /** `COSTT4_A`: below the out-of-state minimum of 59,923, which is what makes the in-state basis provable. */
    private const val UCSD_COSTT4_A = UcsdScorecardRow.COSTT4_A

    /** `NPT4_PUB`: the overall average net price, in-state only. */
    private const val UCSD_NPT4_PUB = UcsdScorecardRow.NPT4_PUB

    /** `NPT45_PUB`: the top band, and the figure a WA family read as theirs against a real ~77,102. */
    private const val UCSD_NPT45_PUB = UcsdScorecardRow.NPT45_PUB
  }
}
