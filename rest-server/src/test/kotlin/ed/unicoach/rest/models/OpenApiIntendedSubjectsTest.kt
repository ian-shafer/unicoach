package ed.unicoach.rest.models

import ed.unicoach.coaching.SubjectVocabularyService
import ed.unicoach.db.dao.IntendedSubjectsDao
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces the openapi.yaml <-> intended-subjects coupling (RFC 205), the
 * [OpenApiCollegeSearchTest] shape: the published `q` bound, the published
 * clamp range and the published subject cap must equal the constants the server
 * actually applies.
 *
 * Nothing else in the default gate opens openapi.yaml for these two operations
 * (`bin/test-fuzz` runs only when one of its inputs is staged, never for a
 * Kotlin-only change; see bin/gate-scope's FUZZ_INPUTS), so without this guard a
 * bump of [SubjectVocabularyService.MAX_QUERY_LENGTH] or
 * [IntendedSubjectsDao.MAX_SUBJECTS] leaves the published contract quietly
 * stale — a document that states a bound the server no longer enforces.
 *
 * No bound is written as a LITERAL here. A literal would move the duplication
 * into a third place and the guard would then pass while both copies were
 * wrong; every expectation below is read from the Kotlin constant.
 */
class OpenApiIntendedSubjectsTest {
  @Test
  fun `openapi's subjects q maxLength matches SubjectVocabularyService's MAX_QUERY_LENGTH`() {
    val published = OpenApiSpec.parameter("/api/v1/subjects", "get", "q").path("schema").path("maxLength")
    assertEquals(
      SubjectVocabularyService.MAX_QUERY_LENGTH,
      published.intValue(),
      "openapi.yaml's q.maxLength must equal SubjectVocabularyService.MAX_QUERY_LENGTH; found [$published]",
    )
  }

  @Test
  fun `openapi's subjects limit description states the SubjectVocabularyService clamp range`() {
    // The clamp has no `default` to pin — an absent `limit` serves the whole
    // list — so the range in the description IS the published contract, and it
    // is asserted from the constants rather than from a copy of the numbers.
    val range = "${SubjectVocabularyService.MIN_LIMIT}..${SubjectVocabularyService.MAX_LIMIT}"
    val description = OpenApiSpec.parameter("/api/v1/subjects", "get", "limit").path("description").asText()
    assertTrue(
      description.contains(range),
      "openapi.yaml's limit.description must state the clamp range [$range]; found [$description]",
    )
  }

  @Test
  fun `openapi's subjects maxItems matches IntendedSubjectsDao's MAX_SUBJECTS`() {
    val published = OpenApiSpec.get("UpdateIntendedSubjectsRequest", "subjects").path("maxItems")
    assertEquals(
      IntendedSubjectsDao.MAX_SUBJECTS,
      published.intValue(),
      "openapi.yaml's subjects.maxItems must equal IntendedSubjectsDao.MAX_SUBJECTS; found [$published]",
    )
  }

  @Test
  fun `openapi publishes declined and undecided as literal true, never a false default`() {
    // The route counts the keys that are PRESENT, so `false` is a 400. A
    // published `default: false` told a client the opposite, which is how the
    // document becomes a door that disagrees with the two real ones.
    listOf("declined", "undecided").forEach { field ->
      val property = OpenApiSpec.get("UpdateIntendedSubjectsRequest", field)
      assertTrue(
        property.path("default").isMissingNode,
        "openapi.yaml must not give [$field] a default; found [${property.path("default")}]",
      )
      assertEquals(
        listOf(true),
        property.path("enum").map { it.booleanValue() },
        "openapi.yaml must publish [$field] as literal true only; found [${property.path("enum")}]",
      )
    }
  }
}
