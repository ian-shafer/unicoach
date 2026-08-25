package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.IncomeBand
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeCostChatToolTest {
  @BeforeEach
  fun resetDatabase() {
    CostsTestDb.reset()
  }

  private val tool = CollegeCostChatTool(CollegeCostService(CostsTestDb.database))
  private val moneyProfiles = MoneyProfileService(CostsTestDb.database)

  private fun seedListedCollege(
    student: StudentId,
    name: String,
    state: String = "CA",
    control: Int = 1,
    medianEarnings: Int? = 55000,
    bandPricing: Boolean = true,
  ): CollegeId {
    val id =
      CostsTestDb.seedCollege(
        name,
        state = state,
        control = control,
        medianEarnings = medianEarnings,
        netPriceQ1 = if (bandPricing) CostsTestDb.NET_PRICE_Q1 else null,
        netPriceQ2 = if (bandPricing) CostsTestDb.NET_PRICE_Q2 else null,
        netPriceQ3 = if (bandPricing) CostsTestDb.NET_PRICE_Q3 else null,
        netPriceQ4 = if (bandPricing) CostsTestDb.NET_PRICE_Q4 else null,
        netPriceQ5 = if (bandPricing) CostsTestDb.NET_PRICE_Q5 else null,
      )
    CostsTestDb.addToCollegeList(student, id)
    return id
  }

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun collegesOf(result: JsonObject) = result.getValue("colleges").jsonArray.map { it.jsonObject }

  private fun execute(
    student: StudentId,
    raw: String = "{}",
  ): JsonObject = runBlocking { tool.execute(student, input(raw)) }

  @Test
  fun `the definition carries the name, the schema, and the ethos contract`() {
    assertEquals("college_cost_profile", tool.name)
    assertEquals("college_cost_profile", tool.definition["name"]!!.jsonPrimitive.content)
    val description = tool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("College Scorecard"), "the source must be named")
    assertTrue(description.contains("never estimate"), "the no-invented-numbers rule must ride the description")
    assertTrue(description.contains("never re-raise"), "the decline etiquette must ride the description")
    assertTrue(description.contains(MoneyProfileChatTool.TOOL_NAME), "the offer must name the recording tool")
    assertTrue(description.contains("Read-only"))

    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    assertEquals("array", properties["college_ids"]!!.jsonObject["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an empty list is a structured result with the money-profile block and the attribution`() {
    val student = createStudent()
    val result = execute(student)

    assertNull(errorOf(result), "got [$result]")
    assertTrue(collegesOf(result).isEmpty())
    assertEquals(
      0,
      result
        .getValue("count")
        .jsonPrimitive.content
        .toInt(),
    )
    val profile = result.getValue("money_profile").jsonObject
    assertEquals("unanswered", profile["income_band_status"]!!.jsonPrimitive.content)
    assertEquals("unanswered", profile["residency_status"]!!.jsonPrimitive.content)
    assertEquals("U.S. Department of Education College Scorecard", result.getValue("source").jsonPrimitive.content)
    assertNull(result["unknown_college_ids"], "no filter, no unknowns")
  }

  @Test
  fun `an explicit empty college_ids array is an empty subset, not the whole list`() {
    val student = createStudent()
    seedListedCollege(student, "Listed U")

    val result = execute(student, """{"college_ids":[]}""")

    assertNull(errorOf(result), "got [$result]")
    assertTrue(collegesOf(result).isEmpty(), "empty subset answers no colleges")
    assertEquals(
      0,
      result
        .getValue("count")
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(
      "unanswered",
      result
        .getValue("money_profile")
        .jsonObject["income_band_status"]!!
        .jsonPrimitive.content,
    )
  }

  @Test
  fun `the tool writes nothing`() {
    val student = createStudent()
    seedListedCollege(student, "ReadOnly U")

    val result = execute(student)
    assertNull(errorOf(result), "got [$result]")

    assertTrue(
      MoneyProfilesDao.findActiveByStudent(CostsTestDb.sqlSession, student).isFailure,
      "a cost read must not create a money-profile row",
    )
    CostsTestDb.connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM college_list_entries").use { rs ->
        rs.next()
        assertEquals(1, rs.getInt(1), "a cost read must not touch the list")
      }
    }
  }

  @Test
  fun `an answered band renders the family-specific net price without a precision offer`() {
    val student = createStudent()
    seedListedCollege(student, "Band U")
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(IncomeBand.K30_TO_48K))).getOrThrow()
    }

    val result = execute(student)
    val college = collegesOf(result).single()
    val netPrice = college.getValue("net_price").jsonObject
    assertEquals("your_income_band", netPrice["basis"]!!.jsonPrimitive.content)
    assertEquals(11000, netPrice["amount"]!!.jsonPrimitive.content.toInt(), "30k_to_48k must select net_price_q2")
    assertEquals("30k_to_48k", netPrice["income_band"]!!.jsonPrimitive.content)
    assertNull(college["precision_offer"], "an answered band needs no invitation")

    val profile = result.getValue("money_profile").jsonObject
    assertEquals("answered", profile["income_band_status"]!!.jsonPrimitive.content)
    assertEquals("30k_to_48k", profile["income_band"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an unanswered band renders the overall average WITH the precision offer`() {
    val student = createStudent()
    seedListedCollege(student, "Avg U")

    val college = collegesOf(execute(student)).single()
    val netPrice = college.getValue("net_price").jsonObject
    assertEquals("overall_average", netPrice["basis"]!!.jsonPrimitive.content)
    assertEquals(20000, netPrice["amount"]!!.jsonPrimitive.content.toInt())
    assertNull(netPrice["income_band"])
    val offer = college["precision_offer"]!!.jsonPrimitive.content
    assertTrue(offer.contains(MoneyProfileChatTool.TOOL_NAME), "the offer must name the recording tool")
  }

  @Test
  fun `a college reporting no bracket columns carries no precision offer even with the band unanswered`() {
    val student = createStudent()
    seedListedCollege(student, "Full U")
    seedListedCollege(student, "NoBands U", bandPricing = false)

    val byName = collegesOf(execute(student)).associateBy { it["name"]!!.jsonPrimitive.content }
    assertTrue(
      byName.getValue("Full U")["precision_offer"] != null,
      "a college with band data still carries the invitation",
    )
    assertNull(
      byName.getValue("NoBands U")["precision_offer"],
      "a college with no band data makes no upgrade promise",
    )
  }

  @Test
  fun `a declined band renders the overall average WITHOUT the precision offer`() {
    val student = createStudent()
    seedListedCollege(student, "Declined U")
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Decline)).getOrThrow()
    }

    val result = execute(student)
    val college = collegesOf(result).single()
    assertEquals(
      "overall_average",
      college
        .getValue("net_price")
        .jsonObject["basis"]!!
        .jsonPrimitive.content,
    )
    assertNull(college["precision_offer"], "the coach must never be cued to reopen a declined band")
    assertEquals(
      "declined",
      result
        .getValue("money_profile")
        .jsonObject["income_band_status"]!!
        .jsonPrimitive.content,
    )
  }

  @Test
  fun `a band re-answered after a decline is family-specific on the next call`() {
    val student = createStudent()
    seedListedCollege(student, "Reopen U")
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Decline)).getOrThrow()
    }
    assertEquals(
      "overall_average",
      collegesOf(execute(student))
        .single()
        .getValue("net_price")
        .jsonObject["basis"]!!
        .jsonPrimitive.content,
    )

    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(IncomeBand.UNDER_30K))).getOrThrow()
    }
    val netPrice =
      collegesOf(execute(student)).single().getValue("net_price").jsonObject
    assertEquals("your_income_band", netPrice["basis"]!!.jsonPrimitive.content)
    assertEquals(9000, netPrice["amount"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun `unknown ids are reported while known ones still answer`() {
    val student = createStudent()
    val mine = seedListedCollege(student, "Mine U")
    val nowhere = UUID.randomUUID()

    val result = execute(student, """{"college_ids":["${mine.value}","$nowhere"]}""")
    assertNull(errorOf(result), "got [$result]")
    assertEquals(listOf("Mine U"), collegesOf(result).map { it["name"]!!.jsonPrimitive.content })
    assertEquals(
      listOf(nowhere.toString()),
      result.getValue("unknown_college_ids").jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `data availability lists the fields a college does not report`() {
    val student = createStudent()
    seedListedCollege(student, "Sparse U", medianEarnings = null)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      listOf("median_earnings"),
      college.getValue("data_availability").jsonArray.map { it.jsonPrimitive.content },
    )
    assertNull(college["median_earnings"], "an unreported figure is absent, never invented")
  }

  @Test
  fun `a private college carries no tuition_applicable while a public one does`() {
    val student = createStudent()
    seedListedCollege(student, "Public U", control = 1)
    seedListedCollege(student, "Private U", control = 2)

    val byName = collegesOf(execute(student)).associateBy { it["name"]!!.jsonPrimitive.content }
    assertEquals("unknown", byName.getValue("Public U")["tuition_applicable"]!!.jsonPrimitive.content)
    assertEquals("public", byName.getValue("Public U")["control"]!!.jsonPrimitive.content)
    assertNull(byName.getValue("Private U")["tuition_applicable"])
    assertEquals("private_nonprofit", byName.getValue("Private U")["control"]!!.jsonPrimitive.content)
  }

  @Test
  fun `malformed input is a structured error naming the offending value and writes nothing`() {
    val student = createStudent()

    val notArray = execute(student, """{"college_ids":"abc"}""")
    assertEquals("""college_ids must be an array of uuid strings, got: ["abc"]""", errorOf(notArray))

    val notString = execute(student, """{"college_ids":["${UUID.randomUUID()}",42]}""")
    assertEquals("college_ids entry is not a uuid string: [42] at index [1]", errorOf(notString))

    val notUuid = execute(student, """{"college_ids":["not-a-uuid"]}""")
    assertEquals("college_ids entry is not a uuid: [not-a-uuid] at index [0]", errorOf(notUuid))

    val unknownField = execute(student, """{"college_id":"abc"}""")
    assertTrue(errorOf(unknownField)!!.contains("unknown field"), "got [$unknownField]")

    assertTrue(
      MoneyProfilesDao.findActiveByStudent(CostsTestDb.sqlSession, student).isFailure,
      "a malformed call must not create any row",
    )
  }

  @Test
  fun `an oversized college_ids array is a structured error naming the cap and the given size`() {
    val student = createStudent()
    val ids = (0..CollegeCostChatTool.MAX_COLLEGE_IDS).joinToString(",") { "\"${UUID.randomUUID()}\"" }

    val result = execute(student, """{"college_ids":[$ids]}""")
    assertEquals(
      "college_ids must contain at most [${CollegeCostChatTool.MAX_COLLEGE_IDS}] entries, " +
        "got [${CollegeCostChatTool.MAX_COLLEGE_IDS + 1}]",
      errorOf(result),
    )
  }

  @Test
  fun `duplicate college_ids are silently read once, as the schema documents`() {
    val student = createStudent()
    val mine = seedListedCollege(student, "Dup U")

    val result = execute(student, """{"college_ids":["${mine.value}","${mine.value}"]}""")
    assertNull(errorOf(result), "got [$result]")
    assertEquals(listOf("Dup U"), collegesOf(result).map { it["name"]!!.jsonPrimitive.content })
    assertNull(result["unknown_college_ids"], "a duplicate of a known id is not unknown")

    val description =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject["college_ids"]!!
        .jsonObject["description"]!!
        .jsonPrimitive.content
    assertTrue(description.contains("duplicate ids are read once"), "the dedup tolerance must be documented: [$description]")
    assertTrue(
      description.contains("At most ${CollegeCostChatTool.MAX_COLLEGE_IDS}"),
      "the cap must be documented: [$description]",
    )
  }
}
