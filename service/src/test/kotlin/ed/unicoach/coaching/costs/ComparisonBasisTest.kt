package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.ResidencyTierBasis
import ed.unicoach.coaching.costs.canonical.ServedFigures
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.servedAt
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceFigure
import ed.unicoach.db.models.ValueBearingStatus
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one comparison-basis case the DB-backed suite cannot reach (RFC 151).
 *
 * `colleges_control_valid_check` (0015) keeps the stored control in 1..3, so
 * [CollegeControl.Unrecognized] -- and with it
 * [ComparedTuition.PublishedPriceUnknown] -- exists for vocabulary drift and can
 * only be stated on the type. Its siblings (`in_state`, `out_of_state`,
 * `unknown`, `single_published_price`) are exercised through the real read in
 * [CollegeCostServiceTest]; this file exists so the fifth code and its sentence
 * do not ship unexercised.
 *
 * It touches no database on purpose: the suite's DB fixtures are shared, and a
 * seed-free test in a seeding class would leave the shared tables in a state its
 * neighbours do not expect. The RFC 157 cases below are here for the same
 * reason: they are facts about the VOCABULARY -- which reason may describe which
 * field, and what a drifted control withholds -- and need no row to be true.
 */
class ComparisonBasisTest {
  @Test
  fun `a control outside the vocabulary says plainly that no published price can be selected`() {
    // Answering a drifted control with `unknown` would state the WRONG missing
    // fact -- `unknown` means the family's state is not on file -- and a
    // residency-specific code would state a fact nobody has.
    val entry =
      CollegeResidencyBasis(
        collegeId = CollegeId(UUID.randomUUID()),
        name = "Drifted U",
        tuition = ComparedTuition.PublishedPriceUnknown(CollegeControl.Unrecognized(9)),
      )

    assertEquals("published_price_unknown", entry.tuition.code)
    val unknown = assertIs<ComparedTuition.PublishedPriceUnknown>(entry.tuition)
    assertEquals(
      9,
      unknown.sourceControl.code,
      "the value that defeated the residency line stays recoverable, as it does everywhere else in this payload",
    )
    assertEquals(
      "unknown (control [9])",
      unknown.sourceControl.label,
      "and it goes on the wire in the labelled form the per-college control key uses, never as a bare code",
    )
    assertFalse(
      entry.tuition.publishesOnePriceForEveryone,
      "a control nobody recognised is not a school we know charges one price",
    )
    assertTrue(entry.statement.startsWith("Drifted U"), "the sentence names its own school: [${entry.statement}]")
    assertTrue(
      entry.statement.contains("we cannot say which of its published prices applies"),
      "the unknown control is stated, never resolved to a residency: [${entry.statement}]",
    )
    assertTrue(
      TuitionApplicable.entries.none { it.value == entry.tuition.code },
      "the fifth code is its own fact, never one of the residency codes: [${entry.tuition.code}]",
    )
  }

  @Test
  fun `a drifted control states the blended-figure basis and withholds nothing`() {
    // RFC 157 D-B, on the one control the DB cannot store: an unrecognised
    // school is not an out-of-state one, so nothing is held back from it.
    val control = CollegeControl.Unrecognized(9)

    assertEquals(BlendedFigureApplicability.BASIS_STATED, blendedFigureApplicabilityOf(control))
    assertEquals(
      emptyList(),
      withheldFiguresFor(control, CostField.IN_STATE_ONLY_FIELDS.toSet()),
      "a control nobody recognised is not licence to hide the only price we hold",
    )
  }

  @Test
  fun `a withheld figure can only be paired with a reason its own axis carries`() {
    // The pair is built from the field, so a sentence about in-state tuition can
    // never be attached to a figure that is on no residency basis at all.
    assertNull(
      WithheldFigure.of(CostField.MEDIAN_DEBT_AT_COMPLETION_USD),
      "median debt is not a price, so no reason's axis describes it",
    )
    assertEquals(
      WithheldReason.IN_STATE_ONLY_FIGURE,
      assertNotNull(WithheldFigure.of(CostField.NET_PRICE)).reason,
    )
    assertFailsWith<IllegalArgumentException>("a mismatched pair has no constructor") {
      WithheldFigure(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, WithheldReason.IN_STATE_ONLY_FIGURE)
    }
  }

  /**
   * A [CollegeCost] with nothing on it but the facts a comparison basis reads:
   * the id, the name, and the control. Built here rather than through the DB
   * because these cases are about the assembly, not about a read.
   */
  private fun college(
    name: String,
    control: CollegeControl,
    chosen: ChosenLivingPlan = ChosenLivingPlan.NotChosen,
    publishedPriceAcademicYear: String? = null,
    publishedReported: Set<CostField> = emptySet(),
  ): CollegeCost =
    CollegeCost(
      collegeId = CollegeId(UUID.randomUUID()),
      name = name,
      city = "Springfield",
      state = "CA",
      control = control,
      listStatus = CollegeListEntryStatus.CONSIDERING,
      publishedStickerCostOfAttendancePerYearUsd = null,
      // A school whose canonical rows are exactly the served year and nothing
      // else: this fixture is about the ASSEMBLY of a basis, and the assembly
      // reads the control, the chosen plan and the served year, never a price
      // (RFC 166). [ServedFigures] refuses a year the college does not publish,
      // so a fixture that states a year states the row it is read from.
      served = servedAt(publishedPriceAcademicYear),
      blendedAverageAcademicYear = null,
      residencyTiers = ResidencyTierBasis.SINGLE_PUBLISHED_PRICE,
      figureStatuses = emptyList(),
      publishedNetPrice = NetPrice.OverallAverage(null),
      medianDebtAtCompletionUsd = null,
      medianEarnings10yAfterEntryUsd = null,
      reportsBandPricing = false,
      reportsPublishedTuition = false,
      // The figures AS PUBLISHED. RFC 157's withholding is [CollegeCost]'s own,
      // derived from the control this fixture chooses, so no test file re-states
      // what that control means for the two blended figures.
      publishedNotReported = CostField.entries.filterNot { it in publishedReported },
      publishedReported = publishedReported,
      breakdown = null,
      offersOnCampusHousing = null,
      meritAid = null,
      chosen = chosen,
    )

  /**
   * The figures behind [college], bound to the year it is served at.
   *
   * A college with no year carries no price row, and a college with a year
   * carries one row in it -- the two shapes [ServedFigures] admits. Nothing
   * here reads the AMOUNT; the row exists so the served year is a year this
   * school really publishes.
   */
  private fun servedAt(academicYear: String?): ServedFigures {
    val collegeId = CollegeId(UUID.randomUUID())
    val address =
      requireNotNull((CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.figureAddress as? FigureAddress.Price)?.address)
    val rows =
      academicYear
        ?.let {
          listOf(
            PriceFigure(
              collegeId = collegeId,
              priceConcept = address.concept,
              residencyBasis = address.residency,
              arrangement = address.arrangement,
              academicYear = it,
              reading = FigureReading.Present(1, ValueBearingStatus.REPORTED),
              source = MoneySource.IPEDS_IC_AY,
              sourceVariable = "FIXTURE",
              publisherFlag = null,
            ),
          )
        }.orEmpty()
    return CollegeFigures(collegeId, rows, emptyList()).servedAt(academicYear)
  }

  private fun moneyProfile(
    residencyStatus: AnswerStatus,
    residencyState: String?,
    living: ComparedLivingPlan = ComparedLivingPlan.Unanswered,
  ): MoneyProfileStatuses =
    MoneyProfileStatuses(
      incomeBandStatus = AnswerStatus.UNANSWERED,
      incomeBand = null,
      residencyStatus = residencyStatus,
      residencyState = residencyState,
      living = living,
    )

  @Test
  fun `an answered residency with no stored state is refused, never relabelled unanswered`() {
    // The one shape [CollegeCostService.requireIntactAnswers] already refuses.
    // Reading it as "unanswered" would tell a family that ANSWERED that their
    // state is not on file - and would say it on every public school's line.
    val colleges =
      listOf(
        college("Corrupt One U", CollegeControl.Public(TuitionApplicable.UNKNOWN)),
        college("Corrupt Two U", CollegeControl.PrivateNonprofit),
      )

    val failure =
      assertFailsWith<CorruptPersistedValueException> {
        ComparisonBasis.of(colleges, moneyProfile(AnswerStatus.ANSWERED, residencyState = null))
      }

    assertTrue(
      failure.message.orEmpty().contains("money_profiles.[residency_state]"),
      "the refusal names the corrupt column, exactly as the service's does: [${failure.message}]",
    )
    assertEquals(
      ComparedResidency.Unanswered,
      assertNotNull(ComparisonBasis.of(colleges, moneyProfile(AnswerStatus.UNANSWERED, null))).residency.answer,
      "an actually-unanswered residency still reads as unanswered: only the corrupt pair is refused",
    )
  }

  @Test
  fun `the residency basis ships the shape of the table as a code, not only as a sentence`() {
    val publicOne = college("Scope Public One U", CollegeControl.Public(TuitionApplicable.IN_STATE))
    val publicTwo = college("Scope Public Two U", CollegeControl.Public(TuitionApplicable.OUT_OF_STATE))
    val privateOne = college("Scope Private One U", CollegeControl.PrivateNonprofit)
    val privateTwo = college("Scope Private Two U", CollegeControl.PrivateForProfit)
    val answered = moneyProfile(AnswerStatus.ANSWERED, "CA")

    assertEquals(
      ResidencyScope.ALL_PUBLIC,
      assertNotNull(ComparisonBasis.of(listOf(publicOne, publicTwo), answered)).residency.scope,
    )
    assertEquals(
      ResidencyScope.NO_PUBLIC,
      assertNotNull(ComparisonBasis.of(listOf(privateOne, privateTwo), answered)).residency.scope,
    )
    assertEquals(
      ResidencyScope.MIXED,
      assertNotNull(ComparisonBasis.of(listOf(publicOne, privateOne), answered)).residency.scope,
    )
    // The code and the sentence are the same decision, so they can never
    // disagree: each shape gets its own words.
    val statements =
      listOf(listOf(publicOne, publicTwo), listOf(privateOne, privateTwo), listOf(publicOne, privateOne))
        .map { assertNotNull(ComparisonBasis.of(it, answered)).residency.statement }
    assertEquals(statements.size, statements.toSet().size, "one sentence per shape: [$statements]")
  }

  @Test
  fun `an unrecognised control is named by the blended-figure scope, never spoken for`() {
    // The quantifiers range over the schools that CHARGE by residency, and a
    // school whose control we did not recognise is not one of them -- so before
    // this scope existed, {in-state public, unrecognised} claimed "both figures
    // are theirs in this table" while that school's own line said we cannot say
    // which residency its figures are for (RFC 157 tier 2).
    val inState = college("Scope In State U", CollegeControl.Public(TuitionApplicable.IN_STATE))
    val drifted = college("Scope Drifted U", CollegeControl.Unrecognized(9))
    val privateOne = college("Scope Private U", CollegeControl.PrivateNonprofit)
    val answered = moneyProfile(AnswerStatus.ANSWERED, "CA")

    val mixed = assertNotNull(ComparisonBasis.of(listOf(inState, drifted), answered)).blendedFigures
    assertEquals(BlendedFigureScope.BASIS_UNKNOWN_AT_SOME_SCHOOLS, mixed.scope)
    assertTrue(
      mixed.statement.contains("Scope Drifted U") && mixed.statement.contains("read that school's own line"),
      "the sentence names the school no claim is true of, and sends the reader to its line: [${mixed.statement}]",
    )
    assertEquals(
      BlendedFigureScope.BASIS_UNKNOWN_AT_SOME_SCHOOLS,
      assertNotNull(ComparisonBasis.of(listOf(privateOne, drifted), answered)).blendedFigures.scope,
      "and a table of one-price schools plus a drifted one is not 'no residency basis here' either",
    )
    assertEquals(
      BlendedFigureScope.IN_STATE_EVERYWHERE,
      assertNotNull(ComparisonBasis.of(listOf(inState, privateOne), answered)).blendedFigures.scope,
      "while a table with no drifted school still answers for itself",
    )
  }

  @Test
  fun `a blended-figure basis that compares fewer than two colleges is refused by the type`() {
    // The stated boundary is TWO: a comparison fact about one school holds
    // nothing constant across anything, and the one-school answer is
    // [CollegeBlendedFigureBasis]. [ComparisonBasis.of] already refuses below
    // two; the type refuses it too (RFC 157 tier 2).
    val one =
      CollegeBlendedFigureBasis(
        collegeId = CollegeId(UUID.randomUUID()),
        name = "Lonely U",
        tuition = ComparedTuition.SinglePublishedPrice,
      )
    val failure =
      assertFailsWith<IllegalArgumentException> {
        BlendedFigureBasis(answer = ComparedResidency.Unanswered, byCollege = listOf(one))
      }

    assertTrue(
      failure.message.orEmpty().contains("colleges=[1]"),
      "the refusal prints what it refused: [${failure.message}]",
    )
  }

  @Test
  fun `a residency basis with no college is refused by the type`() {
    // Every quantifier in this type answers for the empty set - `others.all`
    // would assert that every school here publishes one price - so a basis
    // stating a residency held constant across no table at all is refused.
    val failure =
      assertFailsWith<IllegalArgumentException> {
        ResidencyBasis(answer = ComparedResidency.Unanswered, byCollege = emptyList())
      }

    assertTrue(
      failure.message.orEmpty().contains("no college"),
      "the refusal says what it refused: [${failure.message}]",
    )
  }

  @Test
  fun `an incomplete entry with no missing arrangement is refused by the type`() {
    // "Absent, never empty" was enforced only by one private factory. An entry
    // that names a school as incomplete and then lists nothing it lacks is
    // nonsense whoever builds it, so the type refuses it.
    val failure =
      assertFailsWith<IllegalArgumentException> {
        IncompleteArrangement(
          collegeId = CollegeId(UUID.randomUUID()),
          name = "Empty Gap U",
          missing = emptyList(),
          reason = ArrangementGap.NOT_REPORTED,
        )
      }

    assertTrue(
      failure.message.orEmpty().contains("never an empty one"),
      "the refusal says what it refused: [${failure.message}]",
    )
    assertTrue(
      failure.message.orEmpty().contains(ArrangementGap.NOT_REPORTED.value),
      "and which entry it was: [${failure.message}]",
    )
    assertTrue(
      failure.message.orEmpty().contains("Empty Gap U"),
      "and which school, in the words an operator reads elsewhere: [${failure.message}]",
    )
  }

  // ---------------------------------------------------------------------------
  // ArrangementBasis: the code beside the sentence (RFC 152 D5)
  // ---------------------------------------------------------------------------

  @Test
  fun `no two ArrangementScope cases share a code`() {
    // The D-D invariant this fact went without until RFC 152: ArrangementBasis
    // was the ONE comparison fact with no code, only lists. Two cases sharing a
    // code would make the code unreadable, which is worse than having none.
    //
    // Only the vocabulary is stated here, because this file is the one that
    // touches no database and [CostBreakdown]'s constructor is private -- a
    // basis in the `no_plan_comparable` state needs a real priced breakdown,
    // which needs a real `colleges` row. That every code is REACHABLE and
    // carries its own sentence is earned in `CollegeCostServiceTest`'s
    // `every ArrangementScope code is reachable and labels its own statement`,
    // against actual fixtures.
    val codes = ArrangementScope.entries.map { it.value }
    assertEquals(codes.size, codes.toSet().size, "two cases sharing a code: $codes")
    assertTrue(codes.none { it.isEmpty() }, "a fact with an empty code ships no code at all")
  }

  // ---------------------------------------------------------------------------
  // DatedFigures: the year is a fact about ONE SCHOOL (RFC 166 §3)
  // ---------------------------------------------------------------------------

  @Test
  fun `two schools served at two academic years each get their own year, their own figures and their own name`() {
    // The case RFC 166 created and no DB fixture can reach: every seeded college
    // shares one PRICE_ACADEMIC_YEAR, while the store really holds a
    // Scorecard-only school at 2022-23 beside an IC_AY school at 2023-24. The
    // old cross product dated ONE call-wide field list with EVERY school's year,
    // so both sentences claimed both schools' figures and named neither school
    // -- rendered verbatim to a parent on the report page.
    val scorecard =
      college(
        "Scorecard Only U",
        CollegeControl.PrivateNonprofit,
        publishedPriceAcademicYear = "2022-23",
        publishedReported = setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD),
      )
    val ipeds =
      college(
        "Ipeds Year U",
        CollegeControl.PrivateNonprofit,
        publishedPriceAcademicYear = "2023-24",
        publishedReported =
          setOf(
            CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
            CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
          ),
      )

    val basis = assertNotNull(ComparisonBasis.of(listOf(scorecard, ipeds), moneyProfile(AnswerStatus.ANSWERED, "CA")))
    val years = basis.academicYears

    assertEquals(listOf("2022-23", "2023-24"), years.map { it.academicYear }, "one entry per year served: [$years]")
    val older = years.single { it.academicYear == "2022-23" }
    val newer = years.single { it.academicYear == "2023-24" }
    // BY ID, not by name: the id is what a reader cross-references this subject
    // on, and two schools in one comparison can share a display name.
    assertEquals(
      listOf(DatedCollege(scorecard.collegeId, "Scorecard Only U")),
      older.colleges,
      "a year names the schools it is true of, and no others",
    )
    assertEquals(listOf(DatedCollege(ipeds.collegeId, "Ipeds Year U")), newer.colleges)
    assertTrue(
      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD in older.figures &&
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD !in newer.figures,
      "a year dates only the figures the schools it names really carry: [$years]",
    )
    assertTrue(
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD in newer.figures &&
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD !in older.figures,
      "and the other school's figure is not dated to this one's year: [$years]",
    )
    assertTrue(older.statement.contains("Scorecard Only U"), "the sentence names its subject: [${older.statement}]")
    assertFalse(older.statement.contains("Ipeds Year U"), "and only its subject: [${older.statement}]")
    assertTrue(newer.statement.contains("Ipeds Year U"), "[${newer.statement}]")
    assertFalse(newer.statement.contains("Scorecard Only U"), "[${newer.statement}]")
    // The page and the coach both render this list and nothing else, so a
    // sentence that never reaches it is a year a parent never reads.
    assertTrue(basis.statements.containsAll(listOf(older.statement, newer.statement)), "[${basis.statements}]")
  }

  @Test
  fun `one school served at one year reads exactly as it always did, with that school named`() {
    val one =
      college(
        "Single Year One U",
        CollegeControl.PrivateNonprofit,
        publishedPriceAcademicYear = "2023-24",
        publishedReported = setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
      )
    val two =
      college(
        "Single Year Two U",
        CollegeControl.PrivateNonprofit,
        publishedPriceAcademicYear = "2023-24",
        publishedReported = setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
      )

    val basis = assertNotNull(ComparisonBasis.of(listOf(one, two), moneyProfile(AnswerStatus.ANSWERED, "CA")))
    val year = basis.academicYears.single()

    assertEquals(
      listOf(DatedCollege(one.collegeId, "Single Year One U"), DatedCollege(two.collegeId, "Single Year Two U")),
      year.colleges,
      "declaration order, not set order",
    )
    assertTrue(
      year.statement.contains("Single Year One U and Single Year Two U"),
      "both schools are named in one sentence, in the domain's own listing grammar: [${year.statement}]",
    )
  }

  @Test
  fun `two schools sharing one display name stay distinguishable, because the subject carries the id`() {
    // "Columbia College" and "Saint Mary's College" both exist several times in
    // the real corpus, and a family's list may legitimately hold two of them.
    // The name alone identifies neither, so a reader matching the year onto a
    // school by name would attach the WRONG year to a column of dollars -- the
    // harm the per-school year exists to prevent.
    val older =
      college(
        "Columbia College",
        CollegeControl.PrivateNonprofit,
        publishedPriceAcademicYear = "2022-23",
        publishedReported = setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
      )
    val newer =
      college(
        "Columbia College",
        CollegeControl.PrivateNonprofit,
        publishedPriceAcademicYear = "2023-24",
        publishedReported = setOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
      )

    val basis = assertNotNull(ComparisonBasis.of(listOf(older, newer), moneyProfile(AnswerStatus.ANSWERED, "CA")))
    val years = basis.academicYears

    assertEquals(listOf("2022-23", "2023-24"), years.map { it.academicYear })
    assertEquals(
      listOf(listOf(older.collegeId), listOf(newer.collegeId)),
      years.map { year -> year.colleges.map { it.collegeId } },
      "each year names its own school by id, even though the two share a name: [$years]",
    )
    assertNotEquals(
      years.first().colleges,
      years.last().colleges,
      "and the two subjects are not the same value, which name-only subjects would have been",
    )
  }

  @Test
  fun `a dated year with no school names the bucket that went subject-less`() {
    // The message is the whole of what an operator sees, and this type is built
    // by a per-year fan-out: without the group and the year, nothing says WHICH
    // bucket produced no subject.
    val failure =
      assertFailsWith<IllegalArgumentException> {
        DatedFigures(
          group = FigureGroup.PUBLISHED_PRICE,
          academicYear = "2023-24",
          colleges = emptyList(),
          figures = listOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
        )
      }

    val message = failure.message.orEmpty()
    assertTrue(message.contains("basis=[${FigureGroup.PUBLISHED_PRICE.wireName}]"), "got [$message]")
    assertTrue(message.contains("academic_year=[2023-24]"), "got [$message]")
    assertTrue(
      message.contains(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName),
      "and the figures the yearless bucket would have dated: got [$message]",
    )
  }

  @Test
  fun `a living plan is reachable only through the answered case`() {
    // [ComparedLivingPlan] is sealed for [ComparedResidency]'s reason: a status
    // plus a nullable plan re-encodes a disjoint fact, and a reader could then
    // state a plan nobody gave. Asserted as a property of the type.
    assertEquals(AnswerStatus.ANSWERED, ComparedLivingPlan.Answered(LivingArrangement.WITH_FAMILY).status)
    assertEquals(AnswerStatus.UNANSWERED, ComparedLivingPlan.Unanswered.status)
    assertEquals(AnswerStatus.DECLINED, ComparedLivingPlan.Declined.status)
  }
}
