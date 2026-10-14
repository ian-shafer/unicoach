package ed.unicoach.rest.models

import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.rest.routing.STUDENT_SUPPORTERS_PATH
import ed.unicoach.rest.routing.STUDENT_SUPPORTER_LINK_PATH
import ed.unicoach.rest.routing.STUDENT_SUPPORTER_REQUEST_APPROVE_PATH
import ed.unicoach.rest.routing.STUDENT_SUPPORTER_REQUEST_DECLINE_PATH
import ed.unicoach.rest.routing.SUPPORTERS_PATH
import ed.unicoach.rest.routing.SUPPORTER_ME_PATH
import ed.unicoach.rest.routing.SUPPORTER_STUDENT_LINK_PATH
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the published supporter contract (RFC 241) to the Kotlin declarations it
 * restates, the [OpenApiVocabulariesTest] idiom.
 *
 * The load-bearing guard is the LAST one. `PublicLinkedStudent` is the whole
 * `family/05/the-subject-seam` boundary written down: a date of birth or an
 * expected graduation date appearing there would publish a read this slice
 * deliberately does not make, and the schema is closed so that adding one is an
 * edit a reviewer sees rather than a field that arrives with a client.
 */
class OpenApiSupporterTest {
  @Test
  fun `the published relationship words are exactly SupporterRelationship's`() {
    assertEquals(
      SupporterRelationship.entries.map { it.value },
      OpenApiSpec.enumValues("PublicLinkedStudent", "relationship"),
      "PublicLinkedStudent.relationship must list exactly the vocabulary's wire strings, in declaration order",
    )
  }

  @Test
  fun `both doors require a session and publish their answers`() {
    val post = OpenApiSpec.operation(SUPPORTERS_PATH, "post")
    assertEquals(
      listOf("cookieAuth"),
      post.path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
    )
    assertEquals(
      listOf("200", "201", "401", "403", "409", "500"),
      post
        .path("responses")
        .fieldNames()
        .asSequence()
        .toList(),
      "201 on the mint, 200 when it was already there, 403 behind the email gate, 409 for a CLOSED row",
    )
    assertTrue(post.path("requestBody").isMissingNode, "the mint door reads no request body")

    val get = OpenApiSpec.operation(SUPPORTER_ME_PATH, "get")
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
      "404 is the mirror of GET /api/v1/students/me's student_not_found",
    )
  }

  @Test
  fun `the roster publishes exactly the fields the Kotlin models carry, and no student profile data`() {
    // The field names are READ from the data classes Jackson serialises, not
    // retyped here: a guard that restates them is green precisely when the spec
    // and the model disagree, which is the only disagreement it exists to find.
    assertEquals(
      OpenApiSpec.declaredFieldNames(PublicLinkedStudent::class),
      OpenApiSpec.requiredProperties("PublicLinkedStudent").toSet(),
      "a field added to PublicLinkedStudent must be published here before it can reach a supporter",
    )
    assertEquals(
      OpenApiSpec.declaredFieldNames(SupporterHomeResponse::class),
      OpenApiSpec.requiredProperties("SupporterHomeResponse").toSet(),
    )

    val schema = OpenApiSpec.schema("PublicLinkedStudent")
    assertEquals(
      false,
      schema.path("additionalProperties").asBoolean(true),
      "the roster entry is a CLOSED schema: a new property is a decision, not an addition",
    )
    assertEquals(
      false,
      OpenApiSpec.schema("SupporterHomeResponse").path("additionalProperties").asBoolean(true),
      "and so is the envelope that carries it: the closure claim is about the whole body a client validates",
    )
    assertEquals(
      OpenApiSpec.declaredFieldNames(PublicLinkedStudent::class),
      schema
        .path("properties")
        .fieldNames()
        .asSequence()
        .toSet(),
      "a supporter reads exactly what PublicLinkedStudent declares — and nothing else",
    )
  }

  private fun responseCodes(
    path: String,
    method: String,
  ): List<String> =
    OpenApiSpec
      .operation(path, method)
      .path("responses")
      .fieldNames()
      .asSequence()
      .toList()

  @Test
  fun `the link doors require a session and publish their answers`() {
    for ((path, method) in listOf(
      STUDENT_SUPPORTERS_PATH to "get",
      STUDENT_SUPPORTER_LINK_PATH to "delete",
      SUPPORTER_STUDENT_LINK_PATH to "delete",
    )) {
      assertEquals(
        listOf("cookieAuth"),
        OpenApiSpec.operation(path, method).path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
        "[$method $path] requires a session",
      )
    }
    assertEquals(listOf("200", "401", "403", "409", "500"), responseCodes(STUDENT_SUPPORTERS_PATH, "get"))
    assertEquals(
      listOf("204", "400", "401", "403", "404", "409", "500"),
      responseCodes(STUDENT_SUPPORTER_LINK_PATH, "delete"),
      "the student door keeps student_profile_required",
    )
    assertEquals(
      listOf("204", "400", "401", "403", "404", "500"),
      responseCodes(SUPPORTER_STUDENT_LINK_PATH, "delete"),
      "the supporter door never sends student_profile_required",
    )
  }

  /**
   * The student's list, pinned the way the roster is: each schema publishes
   * exactly the fields its Kotlin model carries, and is closed — so an adult's
   * email cannot reach a student by arriving with a client.
   */
  @Test
  fun `the student's list publishes exactly the fields the Kotlin models carry, closed`() {
    val pairs: List<Pair<KClass<*>, String>> =
      listOf(
        PublicLinkedSupporter::class to "PublicLinkedSupporter",
        SupporterCeilingResponse::class to "SupporterCeiling",
        StudentSupportersResponse::class to "StudentSupportersResponse",
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

  /** The student's request doors (RFC 254): a session, the uniform 404, and the student-only 409. */
  @Test
  fun `the request doors require a session and publish their answers`() {
    for (path in listOf(STUDENT_SUPPORTER_REQUEST_APPROVE_PATH, STUDENT_SUPPORTER_REQUEST_DECLINE_PATH)) {
      assertEquals(
        listOf("cookieAuth"),
        OpenApiSpec.operation(path, "post").path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
        "[post $path] requires a session",
      )
    }
    assertEquals(listOf("200", "400", "401", "403", "404", "409", "500"), responseCodes(STUDENT_SUPPORTER_REQUEST_APPROVE_PATH, "post"))
    assertEquals(listOf("204", "400", "401", "403", "404", "409", "500"), responseCodes(STUDENT_SUPPORTER_REQUEST_DECLINE_PATH, "post"))
  }

  @Test
  fun `a pending request publishes exactly the fields its Kotlin model carries, closed`() {
    val schema = OpenApiSpec.schema("PublicSupporterRequest")
    assertEquals(
      OpenApiSpec.declaredFieldNames(PublicSupporterRequest::class),
      OpenApiSpec.requiredProperties("PublicSupporterRequest").toSet(),
    )
    assertEquals(
      OpenApiSpec.declaredFieldNames(PublicSupporterRequest::class),
      schema
        .path("properties")
        .fieldNames()
        .asSequence()
        .toSet(),
    )
    assertEquals(false, schema.path("additionalProperties").asBoolean(true), "a CLOSED schema")
  }
}
