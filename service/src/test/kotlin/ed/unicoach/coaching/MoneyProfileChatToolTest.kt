package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyProfileChatToolTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE money_profiles, students, users CASCADE")
    }
  }

  private val sqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val tool = MoneyProfileChatTool(MoneyProfileService(database))

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'mpt-$userId@test.com', 'MPT User', 'ahash')",
      )
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun profileOf(result: JsonObject): JsonObject = result.getValue("money_profile").jsonObject

  @Test
  fun `the definition carries the name and the ethos contract`() {
    assertEquals("update_money_profile", tool.name)
    assertEquals("update_money_profile", tool.definition["name"]!!.jsonPrimitive.content)
    val description = tool.definition["description"]!!.jsonPrimitive.content
    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    assertTrue(description.contains("only when cost comes up naturally"), "the ethos contract must ride the description")
    assertTrue(description.contains("without pushing"))
    assertTrue(description.contains("Never re-ask a declined field"))
    // RFC 152: the third field, and the sentence that keeps a per-school
    // correction off the global default.
    assertTrue(description.contains("where the student plans to live"), "the third field must ride the description")
    assertTrue(
      description.contains("carries its own plan on the college list"),
      "and the description must send a per-school correction to the college list, not to this tool",
    )
    // The plan's enum is the persisted vocabulary itself, with the spoken label
    // beside every wire name -- one vocabulary, and no surface inventing copy.
    val livingPlan = properties["living_plan"]!!.jsonObject
    assertEquals(
      LivingArrangement.entries.map { it.value },
      livingPlan["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
      "the schema publishes the arrangement wire names in declaration order",
    )
    LivingArrangement.entries.forEach {
      assertTrue(
        livingPlan["description"]!!.jsonPrimitive.content.contains(it.label),
        "every wire name must arrive with the words a student says it in: [${it.label}]",
      )
    }

    // The decline flags are literal-true in the schema itself, so a compliant
    // model can never emit false.
    for (flag in listOf("income_band_declined", "residency_declined", "living_plan_declined")) {
      assertEquals(
        "true",
        properties[flag]!!.jsonObject["const"]!!.jsonPrimitive.content,
        "$flag must carry const: true",
      )
    }
  }

  @Test
  fun `setting a value writes it and echoes the full post-write profile`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"income_band":"48k_to_75k"}"""))

      assertNull(errorOf(result), "got $result")
      val profile = profileOf(result)
      assertEquals("answered", profile["income_band_status"]!!.jsonPrimitive.content)
      assertEquals("48k_to_75k", profile["income_band"]!!.jsonPrimitive.content)
      // The echo is the moment right after the student stated their band, so
      // the code never travels without its spoken dollar range (RFC 142).
      assertEquals(
        IncomeBand.K48_TO_75K.bracket,
        profile["income_band_label"]!!.jsonPrimitive.content,
        "the echoed band must carry the range a coach says aloud",
      )
      assertEquals("unanswered", profile["residency_status"]!!.jsonPrimitive.content)
      assertNull(profile["residency_state"], "an unanswered field must carry no value in the echo")

      val persisted = MoneyProfilesDao.findActiveByStudent(sqlSession, student).getOrThrow()
      assertEquals(IncomeBand.K48_TO_75K, persisted.incomeBand)
      assertEquals(AnswerStatus.ANSWERED, persisted.incomeBandStatus)
    }

  @Test
  fun `declining a field records the decline and clears any prior value`() =
    runBlocking {
      val student = createStudent()
      tool.execute(student, input("""{"income_band":"under_30k","residency_state":"ca"}"""))

      val result = tool.execute(student, input("""{"income_band_declined":true}"""))
      assertNull(errorOf(result), "got $result")
      val profile = profileOf(result)
      assertEquals("declined", profile["income_band_status"]!!.jsonPrimitive.content)
      assertNull(profile["income_band"], "a declined field must carry no value")
      assertNull(profile["income_band_label"], "and so has nothing to label")
      assertEquals("answered", profile["residency_status"]!!.jsonPrimitive.content)
      assertEquals("CA", profile["residency_state"]!!.jsonPrimitive.content, "the untouched field survives, uppercased")
    }

  @Test
  fun `value and decline for the same field in one call is a structured error and writes nothing`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"income_band":"under_30k","income_band_declined":true}"""))
      assertTrue(errorOf(result)!!.contains("cannot both be set"), "got $result")
      assertTrue(
        MoneyProfilesDao.findActiveByStudent(sqlSession, student).isFailure,
        "a conflicting call must not create a profile row",
      )
    }

  @Test
  fun `a false decline flag is a structured error and writes nothing`() =
    runBlocking {
      val student = createStudent()

      val income = tool.execute(student, input("""{"income_band_declined":false}"""))
      assertEquals(
        "income_band_declined must be true when present; omit it to leave the field unchanged",
        errorOf(income),
        "got $income",
      )

      val residency = tool.execute(student, input("""{"residency_state":"CA","residency_declined":false}"""))
      assertEquals(
        "residency_declined must be true when present; omit it to leave the field unchanged",
        errorOf(residency),
        "got $residency",
      )

      assertTrue(
        MoneyProfilesDao.findActiveByStudent(sqlSession, student).isFailure,
        "a false-flag call must not create a profile row",
      )
    }

  @Test
  fun `a string-typed decline flag is a structured error naming the offending element, not a parsed boolean`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"income_band_declined":"true"}"""))
      assertEquals("""[income_band_declined] must be a [boolean], got: ["true"]""", errorOf(result), "got $result")
      assertTrue(
        MoneyProfilesDao.findActiveByStudent(sqlSession, student).isFailure,
        "a mistyped call must not create a profile row",
      )
    }

  @Test
  fun `a non-string income_band is a type-mismatch error, distinct from an absent field`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"income_band":42}"""))
      assertEquals("[income_band] must be a [string], got: [42]", errorOf(result), "got $result")
      assertTrue(
        MoneyProfilesDao.findActiveByStudent(sqlSession, student).isFailure,
        "a mistyped call must not create a profile row",
      )
    }

  @Test
  fun `an unknown field is a structured error`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"annual_income":50000}"""))
      assertTrue(errorOf(result)!!.contains("unknown field"), "got $result")
    }

  @Test
  fun `an unknown income band value is a structured error`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"income_band":"rich"}"""))
      assertTrue(errorOf(result)!!.contains("unknown income_band value"), "got $result")
    }

  @Test
  fun `a malformed residency state is a structured error`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"residency_state":"California"}"""))
      assertTrue(errorOf(result)!!.contains("two-letter"), "got $result")
      assertTrue(errorOf(result)!!.contains("California"), "the rejected value must be echoed, got $result")
    }

  @Test
  fun `a two-letter non-USPS code is a structured error, not an accepted state`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"residency_state":"ZZ"}"""))
      assertTrue(errorOf(result)!!.contains("ZZ"), "the rejected value must be echoed, got $result")
    }

  @Test
  fun `an empty call is a structured error`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{}"""))
      assertTrue(errorOf(result)!!.contains("nothing to update"), "got $result")
    }

  @Test
  fun `setting a living plan writes it and echoes it with the words a student says it in`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"living_plan":"with_family"}"""))

      assertNull(errorOf(result), "got $result")
      val profile = profileOf(result)
      assertEquals("answered", profile["living_plan_status"]!!.jsonPrimitive.content)
      assertEquals("with_family", profile["living_plan"]!!.jsonPrimitive.content)
      assertEquals(
        LivingArrangement.WITH_FAMILY.label,
        profile["living_plan_label"]!!.jsonPrimitive.content,
        "the wire name never travels alone: the coach reads the label aloud, never the key",
      )

      val persisted = MoneyProfilesDao.findActiveByStudent(sqlSession, student).getOrThrow()
      assertEquals(LivingArrangement.WITH_FAMILY, persisted.livingPlan)
      assertEquals(AnswerStatus.ANSWERED, persisted.livingPlanStatus)
    }

  @Test
  fun `a living plan can be set, changed, declined and re-answered across separate calls`() =
    runBlocking {
      // The slice's first acceptance criterion, entirely through the chat tool:
      // a field can be set, changed, declined and resumed, and the decline is
      // recorded rather than absorbed (brief 0001 D11).
      val student = createStudent()

      tool.execute(student, input("""{"living_plan":"on_campus"}"""))
      val changed = profileOf(tool.execute(student, input("""{"living_plan":"with_family"}""")))
      assertEquals("with_family", changed["living_plan"]!!.jsonPrimitive.content)

      val declined = profileOf(tool.execute(student, input("""{"living_plan_declined":true}""")))
      assertEquals("declined", declined["living_plan_status"]!!.jsonPrimitive.content)
      assertNull(declined["living_plan"], "a declined field must carry no value")
      assertNull(declined["living_plan_label"], "and so has nothing to label")

      val resumed = profileOf(tool.execute(student, input("""{"living_plan":"off_campus"}""")))
      assertEquals("answered", resumed["living_plan_status"]!!.jsonPrimitive.content)
      assertEquals("off_campus", resumed["living_plan"]!!.jsonPrimitive.content)

      // The whole trail survives: a decline is a fact in history, not an erasure.
      val id = MoneyProfilesDao.findActiveByStudent(sqlSession, student).getOrThrow().id
      assertEquals(
        listOf(
          AnswerStatus.ANSWERED,
          AnswerStatus.ANSWERED,
          AnswerStatus.DECLINED,
          AnswerStatus.ANSWERED,
        ),
        MoneyProfilesDao
          .listVersions(sqlSession, id)
          .getOrThrow()
          .map { it.entity.livingPlanStatus },
      )
    }

  @Test
  fun `a living plan and its decline in one call is a structured error and writes nothing`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"living_plan":"on_campus","living_plan_declined":true}"""))
      assertTrue(errorOf(result)!!.contains("cannot both be set"), "got $result")
      assertTrue(
        MoneyProfilesDao.findActiveByStudent(sqlSession, student).isFailure,
        "a conflicting call must not create a profile row",
      )
    }

  @Test
  fun `a false living_plan_declined flag is a structured error and writes nothing`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"living_plan_declined":false}"""))
      assertEquals(
        "living_plan_declined must be true when present; omit it to leave the field unchanged",
        errorOf(result),
        "got $result",
      )
      assertTrue(
        MoneyProfilesDao.findActiveByStudent(sqlSession, student).isFailure,
        "a false-flag call must not create a profile row",
      )
    }

  @Test
  fun `an unknown living plan value is a structured error`() =
    runBlocking {
      val student = createStudent()
      val result = tool.execute(student, input("""{"living_plan":"in_a_yurt"}"""))
      assertTrue(errorOf(result)!!.contains("unknown living_plan value"), "got $result")
      assertTrue(errorOf(result)!!.contains("in_a_yurt"), "the rejected value must be echoed, got $result")
    }

  @Test
  fun `an unanswered living plan echoes its status with no value, beside the other two fields`() =
    runBlocking {
      val student = createStudent()
      val profile = profileOf(tool.execute(student, input("""{"income_band":"under_30k"}""")))
      assertEquals("unanswered", profile["living_plan_status"]!!.jsonPrimitive.content)
      assertNull(profile["living_plan"], "an unanswered field must carry no value in the echo")
      assertNull(profile["living_plan_label"])
    }

  @Test
  fun `the unscoped ChatTool execute is a structured error, never a write`() =
    runBlocking {
      val result = (tool as ed.unicoach.chat.ChatTool).execute(input("""{"income_band":"under_30k"}"""))
      assertTrue(errorOf(result)!!.contains("student-scoped"), "got $result")
    }
}
