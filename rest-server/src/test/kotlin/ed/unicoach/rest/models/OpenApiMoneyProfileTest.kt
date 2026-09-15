package ed.unicoach.rest.models

import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Holds the money-profile vocabulary in the published contract to the enums
 * the server actually speaks (RFC 134, RFC 152): [IncomeBand], [AnswerStatus]
 * and [LivingArrangement]. The
 * spec restates both rather than deriving them, and nothing else in the
 * default gate opens `api-specs/openapi.yaml` for these schemas, so without
 * this guard a member added to or removed from either enum leaves the
 * published contract silently stale.
 */
class OpenApiMoneyProfileTest {
  @Test
  fun `spec income-band enums enumerate exactly IncomeBand's wire strings`() {
    val expected = IncomeBand.entries.map { it.value }
    assertEquals(
      expected,
      OpenApiSpec.enumValues("UpdateMoneyProfileRequest", "incomeBand"),
      "UpdateMoneyProfileRequest.incomeBand must list exactly IncomeBand's wire strings, in declaration order",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "incomeBand"),
      "PublicMoneyProfile.incomeBand must list exactly IncomeBand's wire strings, in declaration order",
    )
  }

  /**
   * The money statuses are [AnswerStatus] MINUS [AnswerStatus.UNDECIDED], and
   * the subtraction is the assertion (RFC 205).
   *
   * A plain `entries == spec` check used to hold. RFC 205 gave [AnswerStatus] a
   * fourth value for a question where "I do not know yet" is a real answer, and
   * the three `money_profiles` status CHECKs were deliberately left at
   * `('unanswered','answered','declined')` — the database REFUSES `undecided` on
   * every money column, and `MoneyProfileUndecidedRefusedTest` proves it. So
   * keeping the old assertion would have forced `undecided` into these three
   * published lists, where it would name a value the server cannot store: a spec
   * that lies.
   *
   * Declaration order is still asserted, with the excluded member removed rather
   * than the list sorted, so a reorder of [AnswerStatus] is still a failure here.
   */
  @Test
  fun `spec money status enums enumerate AnswerStatus minus UNDECIDED`() {
    // Read from `moneyCases`, not recomputed as `entries - UNDECIDED`. That
    // subtraction is the RULE about which statuses money may hold, and the rule
    // has one owner now; recomputing it here would make this guard agree with a
    // second, independent copy rather than with the thing production uses.
    val expected = AnswerStatus.moneyCases.map { it.value }
    val reason =
      "must list exactly AnswerStatus's wire strings MINUS [${AnswerStatus.UNDECIDED.value}], in " +
        "declaration order: the money_profiles CHECKs refuse that value, so a money column can never " +
        "hold it and the published contract must not offer it"
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "incomeBandStatus"),
      "PublicMoneyProfile.incomeBandStatus $reason",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "residencyStatus"),
      "PublicMoneyProfile.residencyStatus $reason",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "livingPlanStatus"),
      "PublicMoneyProfile.livingPlanStatus $reason",
    )
  }

  /**
   * The other half of the split: the INTENDED-SUBJECT status lists all four.
   *
   * Asserted in the same file as its money twin on purpose. The two lists differ
   * by exactly one member, and the only thing that keeps that difference
   * deliberate is a reader seeing both claims side by side.
   */
  @Test
  fun `spec intended-subject status enum enumerates every AnswerStatus wire string`() {
    assertEquals(
      AnswerStatus.entries.map { it.value }.sorted(),
      OpenApiSpec.enumValues("PublicIntendedSubjects", "status").sorted(),
      "PublicIntendedSubjects.status must list every AnswerStatus wire string, [${AnswerStatus.UNDECIDED.value}] " +
        "included: it is the one surface where that answer is storable",
    )
  }

  @Test
  fun `spec living-plan enums enumerate exactly LivingArrangement's wire strings`() {
    // RFC 152's third tri-state field. Declaration order is asserted, not just
    // membership: the published contract is a list, and a reordered list is a
    // different document even when it names the same three values.
    val expected = LivingArrangement.entries.map { it.value }
    assertEquals(
      expected,
      OpenApiSpec.enumValues("UpdateMoneyProfileRequest", "livingPlan"),
      "UpdateMoneyProfileRequest.livingPlan must list exactly LivingArrangement's wire strings, in declaration order",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "livingPlan"),
      "PublicMoneyProfile.livingPlan must list exactly LivingArrangement's wire strings, in declaration order",
    )
    // The same vocabulary reaches the college list, where it is the per-college
    // override rather than the family's usual plan (D2a) -- one enum, two homes.
    assertEquals(
      expected,
      OpenApiSpec.enumValues("CreateCollegeListEntryRequest", "livingPlan"),
      "CreateCollegeListEntryRequest.livingPlan must speak the same vocabulary",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("UpdateCollegeListEntryRequest", "livingPlan"),
      "UpdateCollegeListEntryRequest.livingPlan must speak the same vocabulary",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicCollegeListEntry", "livingPlan"),
      "PublicCollegeListEntry.livingPlan must speak the same vocabulary",
    )
  }
}
