package ed.unicoach.rest.models

import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.rest.routing.SUBJECT_COLLEGE_LIST_PATH
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals

/**
 * Holds the published `GET /api/v1/students/{studentId}/college-list` contract
 * (RFC 250) to the Kotlin models the server serialises, the
 * [OpenApiSupporterTest] idiom.
 *
 * The load-bearing guard is the closure of `PublicSharedCollegeListEntry`: the
 * student's reasons and conversation quotes are withheld from a linked adult,
 * so a field arriving there has to be an edit a reviewer sees.
 */
class OpenApiSubjectCollegeListTest {
  @Test
  fun `the route requires a session and publishes its answers`() {
    val get = OpenApiSpec.operation(SUBJECT_COLLEGE_LIST_PATH, "get")
    assertEquals(
      listOf("cookieAuth"),
      get.path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
    )
    assertEquals(
      listOf("200", "400", "401", "403", "404", "500"),
      get
        .path("responses")
        .fieldNames()
        .asSequence()
        .toList(),
      "never 409 student_profile_required: a linked adult has no student profile",
    )
    assertEquals("path", OpenApiSpec.parameter(SUBJECT_COLLEGE_LIST_PATH, "get", "studentId").path("in").asText())
  }

  @Test
  fun `each schema publishes exactly the fields its Kotlin model carries, closed`() {
    val pairs: List<Pair<KClass<*>, String>> =
      listOf(
        PublicSharedCollegeListEntry::class to "PublicSharedCollegeListEntry",
        SharedCollegeListResponse::class to "SharedCollegeListResponse",
      )
    for ((model, schemaName) in pairs) {
      val schema = OpenApiSpec.schema(schemaName)
      assertEquals(OpenApiSpec.declaredFieldNames(model), OpenApiSpec.requiredProperties(schemaName).toSet(), "[$schemaName] required")
      assertEquals(
        OpenApiSpec.declaredFieldNames(model),
        schema
          .path("properties")
          .fieldNames()
          .asSequence()
          .toSet(),
        "[$schemaName] publishes exactly its model's fields",
      )
      assertEquals(false, schema.path("additionalProperties").asBoolean(true), "[$schemaName] is a CLOSED schema")
    }
  }

  @Test
  fun `the published status and living plan words are the enums' own`() {
    assertEquals(CollegeListEntryStatus.entries.map { it.value }, OpenApiSpec.enumValues("PublicSharedCollegeListEntry", "status"))
    assertEquals(LivingArrangement.entries.map { it.value }, OpenApiSpec.enumValues("PublicSharedCollegeListEntry", "livingPlan"))
  }
}
