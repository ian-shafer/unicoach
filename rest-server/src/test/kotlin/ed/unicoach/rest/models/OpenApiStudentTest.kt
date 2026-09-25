package ed.unicoach.rest.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the published student contract to the Kotlin models the server actually
 * speaks (RFC 222). There is no student guard in the default gate today, so
 * without this one the 13+ age floor could ship with a spec that never asked
 * for a date of birth and never published the refusal.
 *
 * Three claims, and each is a thing a reviewer would otherwise have to check by
 * eye: the request's `required` set matches the non-nullable-by-contract fields,
 * the two date-of-birth properties carry the FULL-date pattern (not the
 * variable-precision graduation one), and `POST /api/v1/students` publishes the
 * 403 the floor answers with.
 */
class OpenApiStudentTest {
  private val fullDatePattern = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"

  @Test
  fun `spec CreateStudentRequest requires exactly the fields the Kotlin model demands`() {
    // `dateOfBirth` is nullable in Kotlin for one reason only -- an older client
    // that omits it must get a readable 400 rather than a serializer failure --
    // so the CONTRACT, not the type, is what says it is required. Reading the
    // field set from the model means a field added there fails here until the
    // spec publishes it.
    assertEquals(
      OpenApiSpec.declaredFieldNames(CreateStudentRequest::class),
      OpenApiSpec.requiredProperties("CreateStudentRequest").toSet(),
      "CreateStudentRequest must publish exactly the fields the Kotlin model declares, all required",
    )
  }

  @Test
  fun `spec date-of-birth properties carry the full-date pattern`() {
    // Deliberately NOT the graduation date's pattern: "year only" cannot decide
    // the floor in the year a student turns 13, so a variable-precision pattern
    // here would publish a contract the server refuses.
    for (schema in listOf("CreateStudentRequest", "PublicStudent")) {
      assertEquals(
        fullDatePattern,
        OpenApiSpec.get(schema, "dateOfBirth").path("pattern").asText(),
        "$schema.dateOfBirth must publish the full ISO date pattern",
      )
    }
  }

  @Test
  fun `spec PublicStudent requires exactly the fields the Kotlin model carries`() {
    // Every column behind this response is NOT NULL, so the model has no
    // optional field and the published required set is the whole model --
    // including the date of birth this slice adds.
    assertEquals(
      OpenApiSpec.declaredFieldNames(PublicStudent::class),
      OpenApiSpec.requiredProperties("PublicStudent").toSet(),
      "PublicStudent must publish exactly the fields the Kotlin model declares, all required",
    )
  }

  @Test
  fun `spec POST students publishes the 403 the age floor answers with`() {
    val responses = OpenApiSpec.operation("/api/v1/students", "post").path("responses")
    assertTrue(
      !responses.path("403").isMissingNode,
      "POST /api/v1/students must publish a 403: an undeclared status is what the contract fuzzer flags",
    )
  }
}
