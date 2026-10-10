package ed.unicoach.rest.models

import ed.unicoach.rest.routing.SUPPORTER_COACHING_USAGE_PATH
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Holds the published `GET /api/v1/supporters/me/coaching-usage` contract
 * (RFC 244 §6) to the Kotlin declarations it restates, the
 * [OpenApiSubscriptionStatusTest] / [OpenApiSupporterTest] idiom: the spec
 * retypes the coverage vocabulary and the body's fields, and nothing else in the
 * default gate opens `api-specs/openapi.yaml`, so without this guard a member
 * added to [CoachingCoverageKind] or a field added to
 * [SupporterCoachingUsageResponse] leaves the published contract silently stale.
 *
 * The first guard is the load-bearing one. The pair of coverage kinds IS the
 * product decision — an account either paid for itself or is on the free
 * allowance, and no account rides another's subscription — so a third published
 * word would be a client rendering coverage this slice deliberately did not
 * build.
 */
class OpenApiSupporterCoachingUsageTest {
  @Test
  fun `the published coverage kinds are exactly CoachingCoverageKind's wire strings`() {
    assertEquals(
      CoachingCoverageKind.entries.map { it.wire },
      OpenApiSpec.enumValues("CoachingCoverage", "kind"),
      "api-specs/openapi.yaml's CoachingCoverage.kind must list exactly CoachingCoverageKind's wire strings, in declaration order",
    )
  }

  @Test
  fun `the published body is exactly the fields the Kotlin models carry`() {
    // Read from the data classes Jackson serialises, never retyped here: a guard
    // that restates them is green precisely when the spec and the model
    // disagree, which is the only disagreement it exists to find.
    assertEquals(
      OpenApiSpec.declaredFieldNames(SupporterCoachingUsageResponse::class),
      OpenApiSpec.requiredProperties("SupporterCoachingUsageResponse").toSet(),
      "a field added to SupporterCoachingUsageResponse must be published before a client can be sent it",
    )
    assertEquals(
      OpenApiSpec.declaredFieldNames(SupporterCoachingUsageResponse::class),
      OpenApiSpec
        .schema("SupporterCoachingUsageResponse")
        .path("properties")
        .fieldNames()
        .asSequence()
        .toSet(),
      "and published as PROPERTIES, not merely named in required",
    )
    assertEquals(
      OpenApiSpec.declaredFieldNames(CoachingCoverage::class),
      OpenApiSpec.requiredProperties("CoachingCoverage").toSet(),
    )
    assertEquals(
      OpenApiSpec.declaredFieldNames(CoachingCoverage::class),
      OpenApiSpec
        .schema("CoachingCoverage")
        .path("properties")
        .fieldNames()
        .asSequence()
        .toSet(),
    )
  }

  /**
   * `required` plus `properties` is an underflow check only: it says what must
   * be present and nothing about what else may be. The closure is what makes a
   * fifth property an edit a reviewer sees, so it is asserted here exactly as
   * [OpenApiSupporterTest] asserts it for the home surface these two schemas
   * sit on.
   */
  @Test
  fun `the published body is a closed schema`() {
    assertEquals(
      false,
      OpenApiSpec.schema("CoachingCoverage").path("additionalProperties").asBoolean(true),
      "the coverage object is CLOSED: a second property is a decision, not an addition",
    )
    assertEquals(
      false,
      OpenApiSpec.schema("SupporterCoachingUsageResponse").path("additionalProperties").asBoolean(true),
      "and so is the envelope: the closure claim is about the whole body a client validates",
    )
  }

  /**
   * The meter is the SHARED `CoachingUsage`, published by reference rather than
   * restated: the parent's bar and the student's bar report one
   * [ed.unicoach.coaching.budget.Entitlement] through one shape, and a second
   * copy of it in the spec is how the two come to disagree about what 100% means
   * — the reason [SupporterCoachingUsageResponse] reuses the Kotlin type too.
   */
  @Test
  fun `the parent's meter is published as the shared CoachingUsage, not a second copy`() {
    assertEquals(
      "#/components/schemas/CoachingUsage",
      OpenApiSpec
        .get("SupporterCoachingUsageResponse", "usage")
        .path("\$ref")
        .asText(),
    )
  }

  @Test
  fun `the route requires a session and publishes its answers`() {
    val get = OpenApiSpec.operation(SUPPORTER_COACHING_USAGE_PATH, "get")
    assertEquals(
      listOf("cookieAuth"),
      get.path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
    )
    assertEquals(
      listOf("200", "401", "403", "404", "500"),
      get
        .path("responses")
        .fieldNames()
        .asSequence()
        .toList(),
      "404 is the mirror of GET /api/v1/supporters/me's supporter_not_found; there is no 409 on this door",
    )
  }
}
