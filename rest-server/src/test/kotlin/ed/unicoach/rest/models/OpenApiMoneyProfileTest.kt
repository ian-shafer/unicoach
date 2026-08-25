package ed.unicoach.rest.models

import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Holds the money-profile vocabulary in the published contract to the enums
 * the server actually speaks (RFC 134): [IncomeBand] and [AnswerStatus]. The
 * spec restates both rather than deriving them, and nothing else in the
 * default gate opens `api-specs/openapi.yaml` for these schemas, so without
 * this guard a member added to or removed from either enum leaves the
 * published contract silently stale.
 */
class OpenApiMoneyProfileTest {
  private fun publishedEnum(
    schema: String,
    property: String,
  ): List<String> = OpenApiSpec.get(schema, property).path("enum").map { it.asText() }

  @Test
  fun `spec income-band enums enumerate exactly IncomeBand's wire strings`() {
    val expected = IncomeBand.entries.map { it.value }
    assertEquals(
      expected,
      publishedEnum("UpdateMoneyProfileRequest", "incomeBand"),
      "UpdateMoneyProfileRequest.incomeBand must list exactly IncomeBand's wire strings, in declaration order",
    )
    assertEquals(
      expected,
      publishedEnum("PublicMoneyProfile", "incomeBand"),
      "PublicMoneyProfile.incomeBand must list exactly IncomeBand's wire strings, in declaration order",
    )
  }

  @Test
  fun `spec status enums enumerate exactly AnswerStatus's wire strings`() {
    val expected = AnswerStatus.entries.map { it.value }
    assertEquals(
      expected,
      publishedEnum("PublicMoneyProfile", "incomeBandStatus"),
      "PublicMoneyProfile.incomeBandStatus must list exactly AnswerStatus's wire strings, in declaration order",
    )
    assertEquals(
      expected,
      publishedEnum("PublicMoneyProfile", "residencyStatus"),
      "PublicMoneyProfile.residencyStatus must list exactly AnswerStatus's wire strings, in declaration order",
    )
  }
}
