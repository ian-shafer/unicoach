package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.NewCollege
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The name-lookup tool (RFC 154), tested the way [CollegeSearchToolTest] tests
 * the structured one: the definition is a contract, `execute` is total, and an
 * unbuilt index is never rendered as a zero result.
 */
class FindCollegeToolTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  private val tool = FindCollegeTool(CollegeSearchService(database))

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges CASCADE").use { it.execute() }
      }
      Unit
    }

  private fun newCollege(
    ipedsUnitId: Int,
    name: String,
    city: String = "Seaside",
    state: String = "CA",
  ) = NewCollege(
    housingAndFoodOnCampusPerYearUsd = null,
    housingAndFoodOffCampusPerYearUsd = null,
    booksAndSuppliesPerYearUsd = null,
    otherExpensesOnCampusPerYearUsd = null,
    otherExpensesOffCampusPerYearUsd = null,
    otherExpensesWithFamilyPerYearUsd = null,
    ipedsUnitId = ipedsUnitId,
    opeid = null,
    name = name,
    city = city,
    state = state,
    region = 8,
    locale = 13,
    latitude = null,
    longitude = null,
    control = 1,
    undergradEnrollmentHeadcount = 2000,
    admissionRateShare = 0.4,
    satAverageEquivalentScore = null,
    costOfAttendancePerYearUsd = null,
    netPricePerYearUsd = 18000,
    netPricePerYearIncomeQ1Usd = null,
    netPricePerYearIncomeQ2Usd = null,
    netPricePerYearIncomeQ3Usd = null,
    netPricePerYearIncomeQ4Usd = null,
    netPricePerYearIncomeQ5Usd = null,
    tuitionAndFeesInStatePerYearUsd = null,
    tuitionAndFeesOutOfStatePerYearUsd = null,
    completionRate150pct4yrShare = 0.7,
    medianEarnings10yAfterEntryUsd = 55000,
    medianDebtAtCompletionUsd = null,
    pellShare = 0.4,
    website = null,
  )

  /**
   * Seeds one college and rebuilds BOTH derived tables the name search reads:
   * `college_search_index` (the substring arm and the build gate) and
   * `college_name_words` (the one-keystroke arm). Only the ingest writes them,
   * so a test that writes `colleges` directly rebuilds them here.
   */
  private fun insert(input: NewCollege) =
    runBlocking {
      database.withConnection { session ->
        val college = CollegesDao.upsert(session, input).getOrThrow()
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        CollegesDao.rebuildNameWords(session).getOrThrow()
        college
      }
    }

  // ---------------------------------------------------------------------------
  // Definition
  // ---------------------------------------------------------------------------

  @Test
  fun `definition names find_college, requires name, and advertises exactly name and limit`() {
    val def = tool.definition
    assertEquals("find_college", def["name"]!!.jsonPrimitive.content)

    val schema = def["input_schema"]!!.jsonObject
    assertEquals("object", schema["type"]!!.jsonPrimitive.content)

    val properties = schema["properties"]!!.jsonObject
    // Exactly two fields: this is a lookup, not a second search surface.
    assertEquals(setOf("name", "limit"), properties.keys)
    assertEquals("string", properties["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("integer", properties["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)

    val required = schema["required"] as JsonArray
    assertEquals(listOf("name"), required.map { it.jsonPrimitive.content })
  }

  @Test
  fun `the description names search_colleges as the other tool and college_id as what the caller carries onward`() {
    val description = tool.definition["description"]!!.jsonPrimitive.content
    // The division of labour lives in the tool contract, not only in the prompt.
    assertTrue(
      description.contains(CollegeSearchTool.TOOL_NAME),
      "the other tool must be named -- by its own constant, so a rename fails here: [$description]",
    )
    assertTrue(description.contains("college_id"))
    assertTrue(description.contains("verbatim"))
  }

  // ---------------------------------------------------------------------------
  // execute
  // ---------------------------------------------------------------------------

  @Test
  fun `a match is rendered as college_id, name, city and state with a count`() =
    runBlocking {
      val seeded = insert(newCollege(910, "Amherst College", city = "Amherst", state = "MA"))

      val result = tool.execute(buildJsonObject { put("name", "Amherst College") })

      assertNull(result["error"])
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull)
      val first = (result["colleges"] as JsonArray).single().jsonObject
      assertEquals(
        listOf("college_id", "name", "city", "state"),
        first.keys.toList(),
        "the id comes first, and the projection is the picker's four fields",
      )
      assertEquals(seeded.id.value.toString(), first["college_id"]!!.jsonPrimitive.content)
      assertEquals("Amherst College", first["name"]!!.jsonPrimitive.content)
      assertEquals("Amherst", first["city"]!!.jsonPrimitive.content)
      assertEquals("MA", first["state"]!!.jsonPrimitive.content)
    }

  @Test
  fun `a misspelling one keystroke away still finds the college`() =
    runBlocking {
      // The reason the tool exists: the words the student said, not a name the
      // model corrected for them.
      insert(newCollege(911, "Amherst College", city = "Amherst", state = "MA"))

      val result = tool.execute(buildJsonObject { put("name", "Amhurst College") })

      assertNull(result["error"])
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `a zero-match name is an empty list, not an error`() =
    runBlocking {
      insert(newCollege(912, "Amherst College"))

      val result = tool.execute(buildJsonObject { put("name", "Nowhere Polytechnic Institute") })

      assertNull(result["error"], "a name nothing matches is a valid outcome")
      assertEquals(0, result["count"]!!.jsonPrimitive.intOrNull)
      assertTrue((result["colleges"] as JsonArray).isEmpty())
    }

  @Test
  fun `limit caps the matches and is clamped by the service`() =
    runBlocking {
      repeat(4) { i -> insert(newCollege(920 + i, "Coastal College $i")) }

      val capped =
        tool.execute(
          buildJsonObject {
            put("name", "Coastal College")
            put("limit", 2)
          },
        )
      assertEquals(2, capped["count"]!!.jsonPrimitive.intOrNull)

      // Above the service's MAX_LIMIT is clamped, not refused.
      val clamped =
        tool.execute(
          buildJsonObject {
            put("name", "Coastal College")
            put("limit", 5000)
          },
        )
      assertNull(clamped["error"])
      assertEquals(4, clamped["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `an unknown field is refused by name rather than ignored`() =
    runBlocking {
      insert(newCollege(930, "Amherst College"))

      val result =
        tool.execute(
          buildJsonObject {
            put("name", "Amherst College")
            put("states", buildJsonArray { add(JsonPrimitive("MA")) })
          },
        )

      val error = result["error"]!!.jsonPrimitive.content
      // Named, so a model that wrote a `search_colleges` filter here can correct
      // itself instead of silently getting a bare lookup.
      assertTrue(error.contains("states"), "the offending field must be named: [$error]")
      assertNull(result["colleges"])
    }

  @Test
  fun `a non-string name is a validation error, not a throw`() =
    runBlocking {
      val numeric = tool.execute(buildJsonObject { put("name", 42) })
      val error = numeric["error"]!!.jsonPrimitive.content
      assertTrue(error.contains("name"), error)
      assertTrue(error.contains("42"), "the refusal echoes what was written: [$error]")

      val missing = tool.execute(buildJsonObject { })
      assertTrue(missing["error"]!!.jsonPrimitive.content.contains("name"))

      val badLimit =
        tool.execute(
          buildJsonObject {
            put("name", "Amherst")
            put("limit", "a few")
          },
        )
      assertTrue(badLimit["error"]!!.jsonPrimitive.content.contains("limit"))
    }

  @Test
  fun `a blank name is refused by name, never answered with an empty list`() =
    runBlocking {
      insert(newCollege(940, "Amherst College"))

      // The service answers a blank query with an empty SUCCESS, which is right
      // for the picker: its user can see their own empty box. The coach cannot,
      // and would read `{"colleges": [], "count": 0}` as "no school by that
      // name exists" -- a claim about the world, for an input that named
      // nothing. So the TOOL refuses it, by name, and the service's rule is
      // untouched (RFC 154 D-C).
      val blank = tool.execute(buildJsonObject { put("name", "   ") })
      val error = assertNotNull(blank["error"]).jsonPrimitive.content
      assertTrue(error.contains("name"), "the offending field must be named: [$error]")
      assertNull(blank["colleges"], "a blank name must never answer with a list")
      assertNull(blank["count"], "a blank name must never answer with a count")
    }

  @Test
  fun `an over-long name is a rejected input, worded for this tool's own field`() =
    runBlocking {
      insert(newCollege(941, "Amherst College"))

      // The service still owns the length rule -- no second constant is written
      // here -- but it hands the rejection up as NUMBERS and the tool words it,
      // because the service's own sentence says "query" and this tool has no
      // field by that name. It is a rejected INPUT, so it takes the flat shape a
      // malformed field gets: telling the coach the search FAILED would read as
      // an outage when the only thing wrong is the words it wrote.
      val tooLong = tool.execute(buildJsonObject { put("name", "a".repeat(CollegeSearchService.MAX_QUERY_LENGTH + 1)) })
      val error = assertNotNull(tooLong["error"]).jsonPrimitive.content
      assertTrue(
        error.contains(CollegeSearchService.MAX_QUERY_LENGTH.toString()),
        "the bound must survive: [$error]",
      )
      assertTrue(
        error.contains((CollegeSearchService.MAX_QUERY_LENGTH + 1).toString()),
        "and so must the observed length: [$error]",
      )
      assertTrue(error.contains("[name]"), "the refusal names the field the tool actually has: [$error]")
      assertFalse(error.contains("query"), "no field of this tool is called `query`: [$error]")
      assertFalse(error.contains("search_failed"), "a rejected input is not a failed search: [$error]")
      assertNull(tooLong["colleges"])
    }

  @Test
  fun `an unbuilt search index is a named refusal, never an empty list`() =
    runBlocking {
      // The migration creates `college_search_index` EMPTY and only the ingest's
      // `search-index` phase fills it. An empty answer out of a full database is
      // indistinguishable from a real zero, so it is refused instead.
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges CASCADE").use { it.execute() }
        session.prepareStatement("DELETE FROM college_index_build").use { it.execute() }
      }

      val result = tool.execute(buildJsonObject { put("name", "Amherst College") })

      assertEquals(CollegeSearchTool.INDEX_NOT_BUILT, result["error"]?.jsonPrimitive?.content)
      assertNull(result["colleges"], "an unbuilt index must never answer with a list")
      assertNull(result["count"], "an unbuilt index must never answer with a count")
    }

  @Test
  fun `a database failure returns the structured search_failed error`() =
    runBlocking {
      // Seed first, so the index IS built: an unbuilt index is refused before
      // the query runs, which is a different (and correct) answer.
      insert(newCollege(950, "Amherst College"))
      // Rename a column the name search reads, on a committed raw connection, so
      // the next lookup fails with a permanent DatabaseException; the structured
      // error must preserve that category rather than flattening it to a string.
      database.createRawConnection().use { conn ->
        conn.createStatement().use { it.execute("ALTER TABLE college_search_index RENAME COLUMN search_text TO search_text_gone") }
      }
      try {
        val result = tool.execute(buildJsonObject { put("name", "Amherst College") })

        val error = assertNotNull(result["error"]).jsonObject
        assertEquals("search_failed", error["kind"]!!.jsonPrimitive.content)
        assertEquals("permanent", error["category"]!!.jsonPrimitive.content)
        assertEquals(false, error["transient"]!!.jsonPrimitive.booleanOrNull)
        assertNull(result["count"])
        assertNull(result["colleges"])
      } finally {
        database.createRawConnection().use { conn ->
          conn.createStatement().use { it.execute("ALTER TABLE college_search_index RENAME COLUMN search_text_gone TO search_text") }
        }
      }
    }

  @Test
  fun `the tool refuses nothing the service already answers as a domain outcome`() =
    runBlocking {
      insert(newCollege(960, "Amherst College"))

      // A fragment and a nickname-shaped fragment are both ordinary successes:
      // this tool holds no matching rule of its own.
      val fragment = tool.execute(buildJsonObject { put("name", "Amh") })
      assertNull(fragment["error"])
      assertEquals(1, fragment["count"]!!.jsonPrimitive.intOrNull)
      assertFalse((fragment["colleges"] as JsonArray).isEmpty())
    }

  @Test
  fun `both college tools render the same failure as the same error object`() =
    runBlocking {
      // The RFC says the two tools "fail identically". That used to be a comment
      // beside a copied envelope; after the tier-1 review both tools build the
      // object from ONE internal builder in this module, so it is asserted
      // STRUCTURALLY -- the two objects are equal -- rather than against a
      // literal copied from one of them, which would drift with the copy.
      //
      // A closed pool is the one failure both tools can be made to hit with the
      // SAME throwable: it is raised before either query is sent, so nothing
      // query-shaped can differ between the two messages.
      val closedDatabase =
        Database(DatabaseConfig.from(AppConfig.load("common.conf", "db.conf").getOrThrow()).getOrThrow())
      closedDatabase.close()
      val service = CollegeSearchService(closedDatabase)

      val find = FindCollegeTool(service).execute(buildJsonObject { put("name", "Amherst College") })
      // An empty query: no filter word, so the codebook vocabulary is not read.
      val search = CollegeSearchTool(service, Codebook(emptyList(), emptyList())).execute(buildJsonObject { })

      assertEquals(
        "search_failed",
        assertNotNull(find["error"]).jsonObject["kind"]!!.jsonPrimitive.content,
        "the setup must actually have failed the search",
      )
      assertEquals(search["error"], find["error"], "the two tools must fail identically")
    }
}
