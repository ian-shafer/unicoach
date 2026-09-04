package ed.unicoach.coaching.aid

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rendered half of RFC 159: the `federal_aid_policy` payload shape over
 * the real service and the real migration seed, on a clock fixed inside award
 * year 2026-27 -- the run's real season, in which the launch data itself
 * exercises the prior-year mechanism.
 */
class FederalAidPolicyChatToolTest {
  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.truncate("money_profiles", "students", "users")
  }

  private val clock = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC)
  private val tool = FederalAidPolicyChatTool(FederalAidPolicyService(CoachingTestDb.database, clock))

  private fun createStudent(): StudentId = CoachingTestDb.createStudent("aid-tool")

  private fun execute(student: StudentId): JsonObject = runBlocking { tool.execute(student, buildJsonObject {}) }

  @Test
  fun `the first-session shape answers fully with no profile at all`() {
    val result = execute(createStudent())

    assertEquals("2026-27", result["current_award_year"]!!.jsonPrimitive.content)

    // The dependency tri-state: unanswered, and NO value key -- a silence is
    // stated as a status, never left to be inferred.
    val moneyProfile = result["money_profile"]!!.jsonObject
    assertEquals("unanswered", moneyProfile["dependency_status"]!!.jsonPrimitive.content)
    assertNull(moneyProfile["dependency"], "an unanswered field must carry no value")

    // Full Pell.
    val pell = result["pell"]!!.jsonObject
    assertEquals("2026-27", pell["award_year"]!!.jsonPrimitive.content)
    assertEquals("current", pell["award_year_status"]!!.jsonPrimitive.content)
    assertNull(pell["award_year_statement"], "a current-year topic carries no prior-year sentence")
    assertEquals(7395, pell["max_award_usd"]!!.jsonPrimitive.int)
    assertEquals(740, pell["min_award_usd"]!!.jsonPrimitive.int)
    assertEquals(-1500, pell["sai_floor"]!!.jsonPrimitive.int)
    val agiTests = pell["max_pell_agi_tests"]!!.jsonObject
    assertEquals(225, agiTests["dependent_single_parent_pct_of_poverty_guideline"]!!.jsonPrimitive.int)
    assertEquals(175, agiTests["dependent_non_single_parent_pct_of_poverty_guideline"]!!.jsonPrimitive.int)
    // The sentence is DERIVED from the served percents, so a corrected seed
    // row can never contradict its own gloss.
    assertEquals(FederalAidPolicyChatTool.maxPellAgiTestStatement(225, 175), agiTests["statement"]!!.jsonPrimitive.content)
    assertTrue(agiTests["statement"]!!.jsonPrimitive.content.contains("225%"))
    assertTrue(agiTests["statement"]!!.jsonPrimitive.content.contains("175%"))
    assertEquals(FederalAidPolicyChatTool.PELL_FRAMING_STATEMENT, pell["framing_statement"]!!.jsonPrimitive.content)

    // Both loan tables, prior-year status SAID.
    val undergrad = result["undergrad_loans"]!!.jsonObject
    assertEquals("2025-26", undergrad["award_year"]!!.jsonPrimitive.content)
    assertEquals("prior_year", undergrad["award_year_status"]!!.jsonPrimitive.content)
    val statement = assertNotNull(undergrad["award_year_statement"]).jsonPrimitive.content
    assertTrue(statement.contains("2025-26") && statement.contains("2026-27"), "the sentence names both years: [$statement]")
    val dependent = undergrad["dependent"]!!.jsonObject
    val independent = undergrad["independent"]!!.jsonObject
    assertEquals(3, dependent["annual"]!!.jsonArray.size)
    assertEquals(3, independent["annual"]!!.jsonArray.size)
    val firstYear = dependent["annual"]!!.jsonArray.first().jsonObject
    assertEquals("first_year", firstYear["year_level"]!!.jsonPrimitive.content)
    assertEquals(5500, firstYear["total_usd"]!!.jsonPrimitive.int)
    assertEquals(3500, firstYear["subsidized_max_usd"]!!.jsonPrimitive.int)
    assertEquals(31000, dependent["aggregate_total_usd"]!!.jsonPrimitive.int)
    assertEquals(57500, independent["aggregate_total_usd"]!!.jsonPrimitive.int)
    assertEquals(FederalAidPolicyChatTool.LOAN_FRAMING_STATEMENT, undergrad["loan_framing_statement"]!!.jsonPrimitive.content)

    val grad = result["grad_loans"]!!.jsonObject
    assertEquals("2025-26", grad["award_year"]!!.jsonPrimitive.content)
    assertEquals("prior_year", grad["award_year_status"]!!.jsonPrimitive.content)
    assertNotNull(grad["award_year_statement"])
    assertEquals(20500, grad["annual_unsubsidized_usd"]!!.jsonPrimitive.int)
    assertEquals(138500, grad["aggregate_total_usd"]!!.jsonPrimitive.int)
    assertEquals(65500, grad["aggregate_subsidized_max_usd"]!!.jsonPrimitive.int)

    assertEquals(FederalAidPolicyChatTool.SOURCE_NOTE, result["source_note"]!!.jsonPrimitive.content)
  }

  @Test
  fun `every topic carries its award year and a citation with a primary FSA url`() {
    val result = execute(createStudent())
    listOf("pell", "undergrad_loans", "grad_loans").forEach { topic ->
      val group = assertNotNull(result[topic], "topic [$topic] must be served").jsonObject
      assertNotNull(group["award_year"], "topic [$topic] must carry its award year")
      val sources = assertNotNull(group["sources"], "topic [$topic] must carry its citations").jsonArray
      assertTrue(sources.isNotEmpty(), "citations are absent-never-empty")
      sources.forEach { source ->
        val cited = source.jsonObject
        assertTrue(cited["cited_as"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(cited["url"]!!.jsonPrimitive.content.startsWith("https://fsapartners.ed.gov/"))
      }
    }
  }

  @Test
  fun `no array anywhere in the payload is empty`() {
    fun sweep(
      element: kotlinx.serialization.json.JsonElement,
      path: String,
    ) {
      when (element) {
        is JsonObject -> {
          element.forEach { (key, value) -> sweep(value, "$path.$key") }
        }

        is JsonArray -> {
          assertTrue(element.isNotEmpty(), "empty list at [$path]: an absent fact omits its key instead")
          element.forEachIndexed { i, item -> sweep(item, "$path[$i]") }
        }

        is JsonPrimitive -> {}
      }
    }
    sweep(execute(createStudent()), "$")
  }

  @Test
  fun `an answered dependency echoes its value with the spoken label, and a declined one only its status`() {
    val student = createStudent()
    val profiles = MoneyProfileService(CoachingTestDb.database)
    runBlocking {
      profiles.upsert(student, MoneyProfileUpdate(dependency = FieldUpdate.Set(DependencyStatus.INDEPENDENT))).getOrThrow()
    }

    val answered = execute(student)["money_profile"]!!.jsonObject
    assertEquals("answered", answered["dependency_status"]!!.jsonPrimitive.content)
    assertEquals("independent", answered["dependency"]!!.jsonPrimitive.content)
    assertEquals(DependencyStatus.INDEPENDENT.label, answered["dependency_label"]!!.jsonPrimitive.content)

    runBlocking { profiles.upsert(student, MoneyProfileUpdate(dependency = FieldUpdate.Decline)).getOrThrow() }
    val declined = execute(student)["money_profile"]!!.jsonObject
    assertEquals("declined", declined["dependency_status"]!!.jsonPrimitive.content)
    assertNull(declined["dependency"], "a declined field must carry no value")
  }

  @Test
  fun `the definition advertises a closed no-argument schema and the framing contract`() {
    assertEquals("federal_aid_policy", tool.name)
    val definition = tool.definition
    assertEquals("federal_aid_policy", definition["name"]!!.jsonPrimitive.content)
    val schema = definition["input_schema"]!!.jsonObject
    assertEquals(0, schema["properties"]!!.jsonObject.size)
    assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.content.toBoolean())
    val description = definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("never a promised amount"), "Pell is a range, never a promise")
    assertTrue(description.contains("never take a loan or a loan limit off any price"))
    assertTrue(description.contains("most students applying from high school are dependent"), "the invitation names its value")
    assertTrue(description.contains("MUST still answer fully"), "the answer never waits on the dependency question")
  }

  @Test
  fun `a call with any input key is a structured error, not a bigger read`() =
    runBlocking {
      val result = tool.execute(createStudent(), buildJsonObject { put("college_id", "nope") })
      assertTrue(result["error"]!!.jsonPrimitive.content.contains("college_id"), "got $result")
    }

  @Test
  fun `the unscoped ChatTool execute is a structured error`() =
    runBlocking {
      val result = (tool as ed.unicoach.chat.ChatTool).execute(buildJsonObject {})
      assertTrue(result["error"]!!.jsonPrimitive.content.contains("student-scoped"), "got $result")
    }
}
