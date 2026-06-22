package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeSearchToolTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs CASCADE").use { it.execute() }
      }
      Unit
    }

  private val tool = CollegeSearchTool(CollegeSearchService(database))

  private fun newCollege(unitId: Int) =
    NewCollege(
      unitId = unitId,
      opeid = null,
      name = "Coastal College $unitId",
      city = "Seaside",
      state = "CA",
      region = 8,
      locale = 13,
      latitude = null,
      longitude = null,
      control = 1,
      undergradEnrollment = 2000,
      admissionRate = 0.4,
      satAvg = null,
      costAttendance = null,
      netPrice = 18000,
      tuitionInState = null,
      tuitionOutState = null,
      graduationRate = 0.7,
      medianEarnings = 55000,
      pctPell = 0.4,
      website = null,
    )

  private fun seedWithMarineBiology(unitId: Int) =
    runBlocking {
      database.withConnection { session ->
        val college = CollegesDao.upsert(session, newCollege(unitId)).getOrThrow()
        CollegesDao
          .upsertProgram(session, NewCollegeProgram(college.id, "260702", "Marine Biology", 3))
          .getOrThrow()
      }
    }

  // ---------------------------------------------------------------------------
  // Definition
  // ---------------------------------------------------------------------------

  @Test
  fun `definition exposes a valid input_schema with all CollegeQuery fields optional`() {
    val def = tool.definition
    assertEquals("search_colleges", def["name"]!!.jsonPrimitive.content)

    val schema = def["input_schema"]!!.jsonObject
    assertEquals("object", schema["type"]!!.jsonPrimitive.content)

    val properties = schema["properties"]!!.jsonObject
    val expected =
      setOf(
        "cipPrefix",
        "states",
        "region",
        "locales",
        "control",
        "minUndergradEnrollment",
        "maxUndergradEnrollment",
        "minAdmissionRate",
        "maxAdmissionRate",
        "maxNetPrice",
        "minGraduationRate",
        "limit",
      )
    assertEquals(expected, properties.keys)

    // No field is required (all optional).
    val required = schema["required"] as JsonArray
    assertTrue(required.isEmpty())
  }

  @Test
  fun `definition description states no geographic-distance capability`() {
    val description =
      tool.definition["description"]!!
        .jsonPrimitive.content
        .lowercase()
    assertTrue(description.contains("cannot"))
    assertTrue(description.contains("distance") || description.contains("coastline") || description.contains("proximity"))
  }

  // ---------------------------------------------------------------------------
  // execute
  // ---------------------------------------------------------------------------

  @Test
  fun `execute maps tool input to a CollegeQuery and returns the result object`() =
    runBlocking {
      seedWithMarineBiology(800)

      val input =
        buildJsonObject {
          put("cipPrefix", "2607")
          put("maxNetPrice", 25000)
        }
      val result = tool.execute(input)

      assertNull(result["error"])
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull)
      val colleges = result["colleges"] as JsonArray
      assertEquals(1, colleges.size)
      val first = colleges.single().jsonObject
      assertEquals("Coastal College 800", first["name"]!!.jsonPrimitive.content)
      val programs = first["programs"] as JsonArray
      assertEquals(listOf("Marine Biology"), programs.map { it.jsonPrimitive.content })
    }

  @Test
  fun `execute on malformed input returns an error object, not an exception`() =
    runBlocking {
      val nonDigitPrefix = tool.execute(buildJsonObject { put("cipPrefix", "bio") })
      assertTrue(nonDigitPrefix.containsKey("error"))

      val wrongTypedNetPrice = tool.execute(buildJsonObject { put("maxNetPrice", "cheap") })
      assertTrue(wrongTypedNetPrice.containsKey("error"))

      val unknownField = tool.execute(buildJsonObject { put("nearOcean", true) })
      assertTrue(unknownField.containsKey("error"))
    }

  @Test
  fun `execute rejects out-of-domain filter values with a structured error`() =
    runBlocking {
      // control: every element must be in {1, 2, 3}
      val badControl = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive(4)) }) })
      assertTrue(badControl.containsKey("error"))
      assertNull(badControl["count"])

      // region: must be in 0..9
      val badRegion = tool.execute(buildJsonObject { put("region", 10) })
      assertTrue(badRegion.containsKey("error"))
      assertNull(badRegion["count"])

      // locales: every element must be in 11..43
      val badLocaleLow = tool.execute(buildJsonObject { put("locales", buildJsonArray { add(JsonPrimitive(10)) }) })
      assertTrue(badLocaleLow.containsKey("error"))
      assertNull(badLocaleLow["count"])
      val badLocaleHigh = tool.execute(buildJsonObject { put("locales", buildJsonArray { add(JsonPrimitive(44)) }) })
      assertTrue(badLocaleHigh.containsKey("error"))
      assertNull(badLocaleHigh["count"])

      // minAdmissionRate / maxAdmissionRate / minGraduationRate: must be in 0.0..1.0
      val badMinAdmission = tool.execute(buildJsonObject { put("minAdmissionRate", 1.5) })
      assertTrue(badMinAdmission.containsKey("error"))
      assertNull(badMinAdmission["count"])
      val badMaxAdmission = tool.execute(buildJsonObject { put("maxAdmissionRate", -0.1) })
      assertTrue(badMaxAdmission.containsKey("error"))
      assertNull(badMaxAdmission["count"])
      val badGraduation = tool.execute(buildJsonObject { put("minGraduationRate", 2.0) })
      assertTrue(badGraduation.containsKey("error"))
      assertNull(badGraduation["count"])

      // maxNetPrice / enrollment bounds: must be >= 0
      val badNetPrice = tool.execute(buildJsonObject { put("maxNetPrice", -1) })
      assertTrue(badNetPrice.containsKey("error"))
      assertNull(badNetPrice["count"])
      val badMinEnrollment = tool.execute(buildJsonObject { put("minUndergradEnrollment", -1) })
      assertTrue(badMinEnrollment.containsKey("error"))
      assertNull(badMinEnrollment["count"])
      val badMaxEnrollment = tool.execute(buildJsonObject { put("maxUndergradEnrollment", -5) })
      assertTrue(badMaxEnrollment.containsKey("error"))
      assertNull(badMaxEnrollment["count"])

      // states: every element must be a 2-letter code
      val badStateLength = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("CAL")) }) })
      assertTrue(badStateLength.containsKey("error"))
      assertNull(badStateLength["count"])
      val badStateNonLetter = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("C1")) }) })
      assertTrue(badStateNonLetter.containsKey("error"))
      assertNull(badStateNonLetter["count"])
    }

  @Test
  fun `execute matches a lowercase state code the same as its uppercase form`() =
    runBlocking {
      // state is stored UPPERCASE (CA). An LLM emitting "ca" must match the same
      // rows as "CA" — the parser normalizes the code before the case-sensitive IN.
      seedWithMarineBiology(810)

      fun statesQuery(code: String) = buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive(code)) }) }

      val upper = tool.execute(statesQuery("CA"))
      val lower = tool.execute(statesQuery("ca"))

      assertNull(upper["error"])
      assertNull(lower["error"])
      assertEquals(1, upper["count"]!!.jsonPrimitive.intOrNull)
      // The lowercase form matches the identical row set, not a false zero.
      assertEquals(
        upper["count"]!!.jsonPrimitive.intOrNull,
        lower["count"]!!.jsonPrimitive.intOrNull,
      )
      val upperNames = (upper["colleges"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }
      val lowerNames = (lower["colleges"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }
      assertEquals(upperNames, lowerNames)
    }

  @Test
  fun `execute on a zero-match query returns count 0`() =
    runBlocking {
      seedWithMarineBiology(900)
      val result = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("ZZ")) }) })
      assertNull(result["error"])
      assertEquals(0, result["count"]!!.jsonPrimitive.intOrNull)
      assertTrue((result["colleges"] as JsonArray).isEmpty())
    }

  @Test
  fun `execute on a DAO failure returns a structured error carrying the failure category`() =
    runBlocking {
      // Drop a column the search SELECTs, on a committed raw connection, so the
      // next search fails with a permanent (non-transient) DatabaseException. The
      // structured error must preserve that category rather than flattening it to a
      // bare string, then we restore the column so the rest of the suite is unaffected.
      database.createRawConnection().use { conn ->
        conn.createStatement().use { it.execute("ALTER TABLE colleges DROP COLUMN pct_pell") }
      }
      try {
        val result = tool.execute(buildJsonObject {})

        val error = result["error"]
        assertNotNull(error)
        val errorObj = error.jsonObject
        assertEquals("search_failed", errorObj["kind"]!!.jsonPrimitive.content)
        // A missing-column fault is permanent, not retryable.
        assertEquals("permanent", errorObj["category"]!!.jsonPrimitive.content)
        assertEquals(false, errorObj["transient"]!!.jsonPrimitive.booleanOrNull)
        assertNull(result["count"])
      } finally {
        database.createRawConnection().use { conn ->
          conn.createStatement().use {
            it.execute(
              "ALTER TABLE colleges ADD COLUMN pct_pell DOUBLE PRECISION " +
                "CONSTRAINT colleges_pct_pell_range_check CHECK (pct_pell IS NULL OR pct_pell BETWEEN 0 AND 1)",
            )
          }
        }
      }
    }
}
