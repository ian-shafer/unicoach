package ed.unicoach.rest.models

import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.JurisdictionKind
import ed.unicoach.vocabulary.VocabularyService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the published `GET /api/v1/vocabularies` contract to the Kotlin
 * declarations it restates (RFC 165), the [OpenApiMoneyProfileTest] idiom: the
 * spec names the income bands and the jurisdiction kinds a second time, and
 * nothing else in the default gate opens `api-specs/openapi.yaml` for them, so
 * without this guard a member added to either enum leaves the contract stale.
 */
class OpenApiVocabulariesTest {
  @Test
  fun `the published income-band vocabulary enumerates exactly IncomeBand's wire strings`() {
    assertEquals(
      IncomeBand.entries.map { it.value },
      OpenApiSpec.enumValues("IncomeBandVocabularyEntry", "value"),
      "IncomeBandVocabularyEntry.value must list exactly IncomeBand's wire strings, in declaration order",
    )
  }

  @Test
  fun `the published jurisdiction kinds are exactly JurisdictionKind's wire strings`() {
    assertEquals(
      JurisdictionKind.entries.map { it.value },
      OpenApiSpec.enumValues("ResidencyStateVocabularyEntry", VocabularyService.JURISDICTION_KIND),
      "ResidencyStateVocabularyEntry.jurisdictionKind must list exactly JurisdictionKind's wire strings, " +
        "in declaration order",
    )
  }

  @Test
  fun `the response schemas require the properties the route always sends`() {
    // value + label are universal and required: that requirement IS the shape
    // one client adapter is written against.
    assertEquals(listOf("value", "label"), OpenApiSpec.requiredProperties("VocabularyEntry"))
    assertEquals(listOf("version", "vocabularies"), OpenApiSpec.requiredProperties("VocabulariesResponse"))
    assertEquals(listOf("entries"), OpenApiSpec.requiredProperties("Vocabulary"))
    for (property in listOf("version", "vocabularies")) {
      assertTrue(
        !OpenApiSpec.get("VocabulariesResponse", property).isMissingNode,
        "VocabulariesResponse must publish [$property]",
      )
    }
    assertEquals(
      "#/components/schemas/Vocabulary",
      OpenApiSpec
        .get("VocabulariesResponse", "vocabularies")
        .path("additionalProperties")
        .path("\$ref")
        .asText(),
      "vocabularies is keyed by name, each value a Vocabulary",
    )
    // The two names served today are typed but NOT required: a client must read
    // the keys, and a vocabulary registered later needs no spec edit to validate.
    val vocabularies = OpenApiSpec.get("VocabulariesResponse", "vocabularies")
    assertTrue(vocabularies.path("required").isMissingNode, "no vocabulary name is a required key")
    assertEquals(
      mapOf(
        "income_bands" to "#/components/schemas/IncomeBandVocabulary",
        "residency_states" to "#/components/schemas/ResidencyStateVocabulary",
      ),
      vocabularies
        .path("properties")
        .fieldNames()
        .asSequence()
        .associateWith { name ->
          vocabularies
            .path("properties")
            .path(name)
            .path("\$ref")
            .asText()
        },
      "the served names are typed by their specializations",
    )
  }

  @Test
  fun `the vocabularies path requires a session and publishes its failure modes`() {
    val operation = OpenApiSpec.operation("/api/v1/vocabularies", "get")
    assertEquals(
      listOf("cookieAuth"),
      operation.path("security").flatMap { scheme -> scheme.fieldNames().asSequence().toList() },
      "the route is under the session umbrella, not public",
    )
    val responses =
      operation
        .path("responses")
        .fieldNames()
        .asSequence()
        .toList()
    assertEquals(listOf("200", "401", "500"), responses, "200 with a session, 401 without, 500 on a supplier fault")
  }
}
