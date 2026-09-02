package ed.unicoach.coaching.costs

import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
  ): CollegeCost =
    CollegeCost(
      collegeId = CollegeId(UUID.randomUUID()),
      name = name,
      city = "Springfield",
      state = "CA",
      control = control,
      listStatus = CollegeListEntryStatus.CONSIDERING,
      publishedStickerCostOfAttendancePerYearUsd = null,
      tuitionAndFeesInStatePerYearUsd = null,
      tuitionAndFeesOutOfStatePerYearUsd = null,
      publishedNetPrice = NetPrice.OverallAverage(null),
      medianDebtAtCompletionUsd = null,
      medianEarnings10yAfterEntryUsd = null,
      reportsBandPricing = false,
      reportsPublishedTuition = false,
      // The figures AS PUBLISHED. RFC 157's withholding is [CollegeCost]'s own,
      // derived from the control this fixture chooses, so no test file re-states
      // what that control means for the two blended figures.
      publishedNotReported = emptyList(),
      publishedReported = emptySet(),
      breakdown = null,
      offersOnCampusHousing = null,
      meritAid = null,
      chosen = chosen,
    )

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
