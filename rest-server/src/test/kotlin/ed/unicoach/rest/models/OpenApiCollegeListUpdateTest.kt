package ed.unicoach.rest.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the published `UpdateCollegeListEntryRequest` schema to the Kotlin
 * [UpdateCollegeListEntryRequest] the server actually deserializes (RFC 164
 * D5).
 *
 * The spec restates the DTO rather than deriving it, and the two disagreed for
 * as long as `livingPlan` claimed to be required: nothing enforced it, and the
 * one shipped client omits it on every call. This guard is what makes the
 * published `required` list a statement about the server instead of a wish.
 */
class OpenApiCollegeListUpdateTest {
  /** The DTO built with only the keys the spec calls required, so its own defaults answer for the published ones. */
  private val requiredKeysOnlyRequest = UpdateCollegeListEntryRequest(version = 1, status = "considering", reasons = null)

  @Test
  fun `spec publishes required as exactly version and status`() {
    // Neither livingPlan nor reasons belongs here. livingPlan is omittable by
    // design -- an omitted key KEEPS the stored override (RFC 164 D1). reasons
    // is omittable in fact -- the server has always read an omitted reasons as
    // null, and the shipped iOS client clears the note by dropping the key
    // (D4), so publishing it as required documented a rule nothing enforced.
    assertEquals(
      listOf("version", "status"),
      OpenApiSpec.requiredProperties("UpdateCollegeListEntryRequest"),
      "UpdateCollegeListEntryRequest.required must name exactly the keys the server refuses a body without",
    )
  }

  @Test
  fun `spec publishes livingPlanClear as a boolean defaulting to the DTO's own default`() {
    val livingPlanClearSchema = OpenApiSpec.get("UpdateCollegeListEntryRequest", "livingPlanClear")
    assertEquals("boolean", livingPlanClearSchema.path("type").asText(), "livingPlanClear is a flag, not a value")
    // Asserted present before it is compared: a missing default reads as false
    // through asBoolean(), which is the very value being asserted.
    assertTrue(livingPlanClearSchema.path("default").isBoolean, "livingPlanClear must publish a boolean default")
    assertEquals(
      requiredKeysOnlyRequest.livingPlanClear,
      livingPlanClearSchema.path("default").asBoolean(),
      "the published default must be the Kotlin default a client gets when it omits the key",
    )
  }
}
