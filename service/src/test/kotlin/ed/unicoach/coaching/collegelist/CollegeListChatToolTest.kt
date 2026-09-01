package ed.unicoach.coaching.collegelist

import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeListChatToolTest {
  // The one shared DB fixture (CostsTestDb): connection/session plumbing, the
  // per-test truncation, and the student/college seeders — nothing re-rolled
  // here, so a `colleges` schema change breaks exactly one seeder.
  @BeforeEach
  fun resetDatabase() = CostsTestDb.reset()

  private val tool = CollegeListChatTool(CollegeListService(CostsTestDb.database))

  private fun createStudent(): StudentId = CostsTestDb.createStudent()

  private fun seedCollege(name: String): CollegeId = CostsTestDb.seedCollege(name)

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun collegeListOf(result: JsonObject) = result.getValue("college_list").jsonArray.map { it.jsonObject }

  private fun countOf(result: JsonObject): Int =
    result
      .getValue("count")
      .jsonPrimitive.content
      .toInt()

  private fun execute(
    student: StudentId,
    raw: String,
  ): JsonObject = runBlocking { tool.execute(student, input(raw)) }

  private fun activeEntries(student: StudentId) = CollegeListEntriesDao.listActiveByStudent(CostsTestDb.sqlSession, student).getOrThrow()

  @Test
  fun `the definition carries the name, the enums, and the ethos contract`() {
    assertEquals("update_college_list", tool.name)
    assertEquals("update_college_list", tool.definition["name"]!!.jsonPrimitive.content)
    val description = tool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("offer to add"), "the value-before-ask offer must ride the description")
    assertTrue(
      description.contains("never add, change, or remove an entry without the student's say-so"),
      "the say-so rule must ride the description",
    )
    assertTrue(
      description.contains("change a school's status or remove it at any time"),
      "reversibility must ride the description",
    )

    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    assertEquals(
      listOf("add", "update", "remove"),
      properties["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      listOf("considering", "applying", "admitted", "rejected"),
      properties["status"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    // RFC 152 D2a: the per-college living-plan override, in the ONE arrangement
    // vocabulary, each wire name arriving with the words a student says it in.
    assertEquals(
      LivingArrangement.entries.map { it.value },
      properties["living_plan"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    LivingArrangement.entries.forEach {
      assertTrue(
        properties["living_plan"]!!
          .jsonObject["description"]!!
          .jsonPrimitive.content
          .contains(it.label),
        "every wire name must arrive with its spoken label: [${it.label}]",
      )
    }
    assertEquals(
      "true",
      properties["living_plan_clear"]!!.jsonObject["const"]!!.jsonPrimitive.content,
      "living_plan_clear must carry const: true, so a compliant model can never emit false",
    )
    assertTrue(
      description.contains("clear it to go back to their usual plan"),
      "the override's reversibility must ride the description",
    )
  }

  @Test
  fun `add without status creates the entry as considering and echoes the named list`() {
    val student = createStudent()
    val college = seedCollege("Brown University")

    val result = execute(student, """{"action":"add","college_id":"${college.value}"}""")

    assertNull(errorOf(result), "got $result")
    val row = collegeListOf(result).single()
    assertEquals(college.value.toString(), row["college_id"]!!.jsonPrimitive.content)
    assertEquals("Brown University", row["name"]!!.jsonPrimitive.content)
    assertEquals("considering", row["status"]!!.jsonPrimitive.content)
    assertNull(row["reasons"], "unset reasons must carry no value in the echo")
    assertEquals(1, countOf(result))

    val persisted = activeEntries(student).single()
    assertEquals(CollegeListEntryStatus.CONSIDERING, persisted.status)
    assertNull(persisted.reasons)
  }

  // The round trip the tools are actually used through: the model never types a
  // uuid, it copies `college_id` out of a search result. Driving the REAL
  // CollegeSearchTool here is the point — a hand-written uuid would pass even if
  // search never emitted the id at all.
  @Test
  fun `a college_id copied out of a real search result drives an add end to end`() {
    val student = createStudent()
    val seeded = seedCollege("Round Trip University")

    val searchTool = CollegeSearchTool(CollegeSearchService(CostsTestDb.database), ed.unicoach.college.Codebook.EMPTY)
    val searchResult = runBlocking { searchTool.execute(input("""{"states":["CA"]}""")) }
    val match =
      searchResult
        .getValue("colleges")
        .jsonArray
        .map { it.jsonObject }
        .single { it["name"]!!.jsonPrimitive.content == "Round Trip University" }
    val collegeId = match["college_id"]!!.jsonPrimitive.content
    // Throws if the search emitted anything but a uuid string.
    assertEquals(seeded.value, UUID.fromString(collegeId))

    val result = execute(student, """{"action":"add","college_id":"$collegeId"}""")

    assertNull(errorOf(result), "got $result")
    val row = collegeListOf(result).single()
    assertEquals(collegeId, row["college_id"]!!.jsonPrimitive.content)
    assertEquals("Round Trip University", row["name"]!!.jsonPrimitive.content)
    assertEquals(1, countOf(result))
  }

  @Test
  fun `add with status and reasons writes and echoes both`() {
    val student = createStudent()
    val college = seedCollege("Brown University")

    val result =
      execute(
        student,
        """{"action":"add","college_id":"${college.value}","status":"applying","reasons":"loves the open curriculum"}""",
      )

    assertNull(errorOf(result), "got $result")
    val row = collegeListOf(result).single()
    assertEquals("applying", row["status"]!!.jsonPrimitive.content)
    assertEquals("loves the open curriculum", row["reasons"]!!.jsonPrimitive.content)

    val persisted = activeEntries(student).single()
    assertEquals(CollegeListEntryStatus.APPLYING, persisted.status)
    assertEquals("loves the open curriculum", persisted.reasons)
  }

  @Test
  fun `a second add of the same college is an already-listed error naming the school, not internals`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}"}""")

    val result = execute(student, """{"action":"add","college_id":"${college.value}"}""")

    val error = errorOf(result)!!
    assertTrue(error.contains("Brown University"), "the error must name the school, got $result")
    assertTrue(error.contains("already on the list"), "got $result")
    assertTrue(error.contains("update"), "the error must steer the model to update, got $result")
    val entry = activeEntries(student).single()
    assertFalse(error.contains(entry.id.value.toString()), "entry ids never cross the model boundary")
  }

  @Test
  fun `add with an unknown college id is a structured error`() {
    val student = createStudent()
    val unknown = UUID.randomUUID()
    val result = execute(student, """{"action":"add","college_id":"$unknown"}""")
    assertTrue(errorOf(result)!!.contains("no college with id [$unknown]"), "got $result")
    assertTrue(activeEntries(student).isEmpty(), "a failed add must write nothing")
  }

  @Test
  fun `a malformed college_id uuid is a structured error`() {
    val student = createStudent()
    val result = execute(student, """{"action":"add","college_id":"not-a-uuid"}""")
    assertEquals("college_id is not a uuid: [not-a-uuid]", errorOf(result), "got $result")
  }

  @Test
  fun `an unknown action value is a structured error`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    val result = execute(student, """{"action":"upsert","college_id":"${college.value}"}""")
    assertEquals("unknown action value: [upsert]", errorOf(result), "got $result")
  }

  @Test
  fun `an unknown status value is a structured error`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    val result = execute(student, """{"action":"add","college_id":"${college.value}","status":"waitlisted"}""")
    assertEquals("unknown status value: [waitlisted]", errorOf(result), "got $result")
    assertTrue(activeEntries(student).isEmpty(), "a malformed call must write nothing")
  }

  @Test
  fun `an unknown field is a structured error`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    val result = execute(student, """{"action":"add","college_id":"${college.value}","entry_id":"x"}""")
    assertTrue(errorOf(result)!!.contains("unknown field"), "got $result")
  }

  @Test
  fun `update restatuses and preserves the reasons when omitted`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}","reasons":"loves the open curriculum"}""")

    val result = execute(student, """{"action":"update","college_id":"${college.value}","status":"admitted"}""")

    assertNull(errorOf(result), "got $result")
    val row = collegeListOf(result).single()
    assertEquals("admitted", row["status"]!!.jsonPrimitive.content)
    assertEquals("loves the open curriculum", row["reasons"]!!.jsonPrimitive.content, "omitted reasons must survive")

    val persisted = activeEntries(student).single()
    assertEquals(CollegeListEntryStatus.ADMITTED, persisted.status)
    assertEquals("loves the open curriculum", persisted.reasons)
  }

  @Test
  fun `update with reasons only preserves the status`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}","status":"applying"}""")

    val result = execute(student, """{"action":"update","college_id":"${college.value}","reasons":"great financial aid"}""")

    assertNull(errorOf(result), "got $result")
    val row = collegeListOf(result).single()
    assertEquals("applying", row["status"]!!.jsonPrimitive.content, "omitted status must survive")
    assertEquals("great financial aid", row["reasons"]!!.jsonPrimitive.content)
  }

  @Test
  fun `update with neither status nor reasons is a structured error`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}"}""")

    val result = execute(student, """{"action":"update","college_id":"${college.value}"}""")
    assertTrue(errorOf(result)!!.contains("nothing to update"), "got $result")
  }

  @Test
  fun `update of an unlisted college is a structured error steering to add`() {
    val student = createStudent()
    val college = seedCollege("Brown University")

    val result = execute(student, """{"action":"update","college_id":"${college.value}","status":"applying"}""")

    val error = errorOf(result)!!
    assertTrue(error.contains("not on the list"), "got $result")
    assertTrue(error.contains("add"), "the error must steer the model to add, got $result")
  }

  @Test
  fun `remove soft-deletes the entry and echoes the list without the school`() {
    val student = createStudent()
    val kept = seedCollege("Kept University")
    val removed = seedCollege("Removed University")
    execute(student, """{"action":"add","college_id":"${kept.value}"}""")
    execute(student, """{"action":"add","college_id":"${removed.value}"}""")

    val result = execute(student, """{"action":"remove","college_id":"${removed.value}"}""")

    assertNull(errorOf(result), "got $result")
    val row = collegeListOf(result).single()
    assertEquals("Kept University", row["name"]!!.jsonPrimitive.content)
    assertEquals(1, countOf(result))
    assertEquals(kept, activeEntries(student).single().collegeId)
  }

  @Test
  fun `removing the last entry echoes an empty list`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}"}""")

    val result = execute(student, """{"action":"remove","college_id":"${college.value}"}""")

    assertNull(errorOf(result), "got $result")
    assertTrue(collegeListOf(result).isEmpty())
    assertEquals(0, countOf(result))
  }

  @Test
  fun `remove of an unlisted college is a structured error`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    val result = execute(student, """{"action":"remove","college_id":"${college.value}"}""")
    assertTrue(errorOf(result)!!.contains("not on the list"), "got $result")
  }

  @Test
  fun `status or reasons on a remove is a structured error and writes nothing`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}"}""")

    val withStatus = execute(student, """{"action":"remove","college_id":"${college.value}","status":"rejected"}""")
    assertEquals("status cannot be set on a remove", errorOf(withStatus), "got $withStatus")

    val withReasons = execute(student, """{"action":"remove","college_id":"${college.value}","reasons":"changed my mind"}""")
    assertEquals("reasons cannot be set on a remove", errorOf(withReasons), "got $withReasons")

    assertEquals(1, activeEntries(student).size, "a malformed remove must not touch the entry")
  }

  @Test
  fun `a per-college living plan round-trips through the tool and reaches the version history`() {
    // RFC 152 D2a. NULL is "no override, use the usual plan", so the column has
    // three reachable states through this tool -- unset, set, and cleared back
    // to unset -- and all three must be legible in the entry's own history.
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}"}""")
    assertNull(activeEntries(student).single().livingPlan, "an add with no plan leaves the override unset")

    val set =
      execute(student, """{"action":"update","college_id":"${college.value}","living_plan":"on_campus"}""")
    assertNull(errorOf(set), "got $set")
    assertEquals(LivingArrangement.ON_CAMPUS, activeEntries(student).single().livingPlan)
    val echoed = collegeListOf(set).single()
    assertEquals("on_campus", echoed["living_plan"]!!.jsonPrimitive.content)
    assertEquals(
      LivingArrangement.ON_CAMPUS.label,
      echoed["living_plan_label"]!!.jsonPrimitive.content,
      "the wire name never travels alone in the echo",
    )
    assertEquals(
      CollegeListEntryStatus.CONSIDERING.value,
      echoed["status"]!!.jsonPrimitive.content,
      "a plan-only update leaves the status alone",
    )

    val changed =
      execute(student, """{"action":"update","college_id":"${college.value}","living_plan":"with_family"}""")
    assertEquals(LivingArrangement.WITH_FAMILY, activeEntries(student).single().livingPlan)
    assertNull(errorOf(changed), "got $changed")

    val cleared = execute(student, """{"action":"update","college_id":"${college.value}","living_plan_clear":true}""")
    assertNull(errorOf(cleared), "got $cleared")
    assertNull(activeEntries(student).single().livingPlan, "a clear returns the school to the usual plan")
    assertNull(
      collegeListOf(cleared).single()["living_plan"],
      "and the echo says nothing rather than saying null: absent IS no override",
    )

    val history =
      CollegeListEntriesDao
        .listVersions(CostsTestDb.sqlSession, activeEntries(student).single().id)
        .getOrThrow()
    assertEquals(
      listOf(null, LivingArrangement.ON_CAMPUS, LivingArrangement.WITH_FAMILY, null),
      history.map { it.entity.livingPlan },
      "log_college_list_entry_version must carry the new column, or the trail is lost silently",
    )
  }

  @Test
  fun `a living plan and its clear in one call is a structured error, and a plan on a remove is refused`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    execute(student, """{"action":"add","college_id":"${college.value}"}""")

    val both =
      execute(
        student,
        """{"action":"update","college_id":"${college.value}","living_plan":"on_campus","living_plan_clear":true}""",
      )
    assertEquals("living_plan and living_plan_clear cannot both be set in one call", errorOf(both), "got $both")

    val falseFlag =
      execute(student, """{"action":"update","college_id":"${college.value}","living_plan_clear":false}""")
    assertEquals(
      "living_plan_clear must be true when present; omit it to leave the field unchanged",
      errorOf(falseFlag),
      "got $falseFlag",
    )

    val unknown = execute(student, """{"action":"update","college_id":"${college.value}","living_plan":"in_a_yurt"}""")
    assertEquals("unknown living_plan value: [in_a_yurt]", errorOf(unknown), "got $unknown")

    val onRemove = execute(student, """{"action":"remove","college_id":"${college.value}","living_plan":"on_campus"}""")
    assertEquals("living_plan cannot be set on a remove", errorOf(onRemove), "got $onRemove")

    // One refusal per KEY: a call carrying the clear flag must not be told the
    // other key is the problem, or the caller retries the same call.
    val clearOnRemove =
      execute(student, """{"action":"remove","college_id":"${college.value}","living_plan_clear":true}""")
    assertEquals("living_plan_clear cannot be set on a remove", errorOf(clearOnRemove), "got $clearOnRemove")

    assertEquals(1, activeEntries(student).size, "no malformed call touched the entry")
    assertNull(activeEntries(student).single().livingPlan)
  }

  @Test
  fun `a clear on an add is refused by name, never accepted and dropped`() {
    val student = createStudent()
    val college = seedCollege("Brown University")

    val result =
      execute(student, """{"action":"add","college_id":"${college.value}","living_plan_clear":true}""")
    assertEquals(
      "living_plan_clear cannot be set on an add; there is no plan to clear yet",
      errorOf(result),
      "got $result",
    )
    assertTrue(activeEntries(student).isEmpty(), "and the refused call wrote nothing at all")
  }

  @Test
  fun `an add can carry the school's own living plan in the same call`() {
    val student = createStudent()
    val college = seedCollege("Brown University")

    val result =
      execute(student, """{"action":"add","college_id":"${college.value}","living_plan":"with_family"}""")
    assertNull(errorOf(result), "got $result")
    assertEquals(LivingArrangement.WITH_FAMILY, activeEntries(student).single().livingPlan)
    assertEquals("with_family", collegeListOf(result).single()["living_plan"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the unscoped ChatTool execute is a structured error, never a write`() {
    val student = createStudent()
    val college = seedCollege("Brown University")
    val result =
      runBlocking {
        (tool as ed.unicoach.chat.ChatTool).execute(input("""{"action":"add","college_id":"${college.value}"}"""))
      }
    assertTrue(errorOf(result)!!.contains("student-scoped"), "got $result")
    assertTrue(activeEntries(student).isEmpty(), "a misrouted call must write nothing")
  }

  @Test
  fun `a student's tool call cannot see or mutate another student's entry for the same college`() {
    val studentA = createStudent()
    val studentB = createStudent()
    val college = seedCollege("Brown University")
    execute(studentA, """{"action":"add","college_id":"${college.value}","status":"applying"}""")

    val update = execute(studentB, """{"action":"update","college_id":"${college.value}","status":"rejected"}""")
    assertTrue(errorOf(update)!!.contains("not on the list"), "B must not see A's entry, got $update")

    val remove = execute(studentB, """{"action":"remove","college_id":"${college.value}"}""")
    assertTrue(errorOf(remove)!!.contains("not on the list"), "B must not remove A's entry, got $remove")

    val persisted = activeEntries(studentA).single()
    assertEquals(CollegeListEntryStatus.APPLYING, persisted.status, "A's entry must be untouched")
  }

  @Test
  fun `reasons at the 2048-char CHECK bound writes, one past it is the invalid-reasons error`() {
    val student = createStudent()
    val college = seedCollege("Bound University")

    val atBound = execute(student, """{"action":"add","college_id":"${college.value}","reasons":"${"r".repeat(2048)}"}""")
    assertNull(errorOf(atBound), "got $atBound")

    val overBound = execute(student, """{"action":"update","college_id":"${college.value}","reasons":"${"r".repeat(2049)}"}""")
    assertEquals("reasons must be non-empty and at most [2048] characters", errorOf(overBound), "got $overBound")
  }
}
