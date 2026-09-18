package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `update_test_scores` tool's contract (RFC 207): every malformed call is a
 * structured `{ "error": ... }` that names the field, never a throw, and a
 * successful write echoes the full post-write state. Parse failures are tested
 * against the real service so the cases that must never reach a write are shown
 * not to.
 */
class UpdateTestScoresChatToolTest {
  private val today = Instant.parse("2026-09-14T15:00:00Z")

  private val tool = UpdateTestScoresChatTool(TestScoreService(CoachingTestDb.database, InstantSource.fixed(today)))

  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.connection.autoCommit = true
    CoachingTestDb.truncate("students", "users", "admission_test_scores", "admission_test_score_declines")
  }

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String = result["error"]!!.jsonPrimitive.content

  @Test
  fun `a score and a remove in one call is refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"component":"sat_total","score":1340,"taken_on":"2026-03","remove":true}"""))
      assertTrue(errorOf(result).contains("remove cannot be set in the same call as score"), errorOf(result))
    }

  @Test
  fun `a decline and a score in one call is refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"component":"sat_total","score":1340,"taken_on":"2026-03","declined":true}"""))
      assertTrue(errorOf(result).contains("declined cannot be set"), errorOf(result))
    }

  @Test
  fun `a false decline flag is refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"declined":false}"""))
      assertTrue(errorOf(result).contains("declined must be true"), errorOf(result))
    }

  @Test
  fun `an unknown field is refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"percentile":99}"""))
      assertTrue(errorOf(result).contains("unknown field"), errorOf(result))
    }

  @Test
  fun `an off-scale score returns the rejection, naming the published scale`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"component":"sat_total","score":1337,"taken_on":"2026-03"}"""))
      assertTrue(errorOf(result).contains("not a possible SAT total score"), errorOf(result))
    }

  @Test
  fun `a malformed taken_on is refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"component":"sat_total","score":1340,"taken_on":"03/14/2026"}"""))
      assertTrue(errorOf(result).contains("taken_on must be"), errorOf(result))
    }

  @Test
  fun `a remove without a component and a date is refused`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"remove":true}"""))
      assertTrue(errorOf(result).contains("remove needs component and taken_on"), errorOf(result))
    }

  @Test
  fun `a successful record echoes the full post-write state`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"component":"sat_total","score":1340,"taken_on":"2026-03"}"""))
      val section = result["test_scores"]!!.jsonObject
      assertEquals("answered", section["status"]!!.jsonPrimitive.content)
      val scores = section["scores"]!!.jsonArray
      assertEquals(1, scores.size)
      val score = scores.single().jsonObject
      assertEquals("sat_total", score["component"]!!.jsonPrimitive.content)
      assertEquals("sat", score["test"]!!.jsonPrimitive.content)
      assertEquals(1340, score["score"]!!.jsonPrimitive.content.toInt())
      assertEquals("2026-03", score["taken_on"]!!.jsonPrimitive.content)
      assertEquals("true", score["self_reported"]!!.jsonPrimitive.content)
    }

  @Test
  fun `a decline echoes declined and no scores`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores-tool")
      val result = tool.execute(student, input("""{"declined":true}"""))
      val section = result["test_scores"]!!.jsonObject
      assertEquals("declined", section["status"]!!.jsonPrimitive.content)
      assertTrue(section["scores"]!!.jsonArray.isEmpty())
    }
}
