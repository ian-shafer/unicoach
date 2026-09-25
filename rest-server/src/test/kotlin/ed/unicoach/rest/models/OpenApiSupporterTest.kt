package ed.unicoach.rest.models

import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.rest.routing.SUPPORTERS_PATH
import ed.unicoach.rest.routing.SUPPORTER_ME_PATH
import org.junit.jupiter.api.Test
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
      "the roster entry is a CLOSED schema: a fifth property is a decision, not an addition",
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
      "a supporter reads the link, the student it points at, a display name and the relationship — and nothing else",
    )
  }
}
