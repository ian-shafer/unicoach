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

  @Test
  fun `spec status enums enumerate exactly AnswerStatus's wire strings`() {
    val expected = AnswerStatus.entries.map { it.value }
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "incomeBandStatus"),
      "PublicMoneyProfile.incomeBandStatus must list exactly AnswerStatus's wire strings, in declaration order",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "residencyStatus"),
      "PublicMoneyProfile.residencyStatus must list exactly AnswerStatus's wire strings, in declaration order",
    )
    assertEquals(
      expected,
      OpenApiSpec.enumValues("PublicMoneyProfile", "livingPlanStatus"),
      "PublicMoneyProfile.livingPlanStatus must list exactly AnswerStatus's wire strings, in declaration order",
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
