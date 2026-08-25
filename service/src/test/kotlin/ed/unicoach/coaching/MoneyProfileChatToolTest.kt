package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    assertTrue(description.contains("only when cost comes up naturally"), "the ethos contract must ride the description")
    assertTrue(description.contains("without pushing"))
    assertTrue(description.contains("Never re-ask a declined field"))

    // The decline flags are literal-true in the schema itself, so a compliant
    // model can never emit false.
    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    for (flag in listOf("income_band_declined", "residency_declined")) {
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
  fun `the unscoped ChatTool execute is a structured error, never a write`() =
    runBlocking {
      val result = (tool as ed.unicoach.chat.ChatTool).execute(input("""{"income_band":"under_30k"}"""))
      assertTrue(errorOf(result)!!.contains("student-scoped"), "got $result")
    }
}
