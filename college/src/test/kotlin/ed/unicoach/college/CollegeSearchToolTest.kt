package ed.unicoach.college

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

  /**
   * The REAL committed codebook, loaded into the test database once (RFC 147
   * D45): the region and locale words this tool accepts and renders are the
   * published ones, not a pair invented here, so a word that only works in the
   * test cannot exist.
   */
  private val codebook = runBlocking { CodebookFixture.load(database) }

  private val tool = CollegeSearchTool(CollegeSearchService(database), codebook)

  private fun newCollege(ipedsUnitId: Int) =
    NewCollege(
      housingAndFoodOnCampusPerYearUsd = null,
      housingAndFoodOffCampusPerYearUsd = null,
      booksAndSuppliesPerYearUsd = null,
      otherExpensesOnCampusPerYearUsd = null,
      otherExpensesOffCampusPerYearUsd = null,
      otherExpensesWithFamilyPerYearUsd = null,
      ipedsUnitId = ipedsUnitId,
      opeid = null,
      name = "Coastal College $ipedsUnitId",
      city = "Seaside",
      state = "CA",
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

  private fun seedWithMarineBiology(ipedsUnitId: Int) = seedWithProgram(ipedsUnitId, "260702", "Marine Biology")

  private fun seedWithProgram(
    ipedsUnitId: Int,
    cipCode: String,
    title: String,
  ) = runBlocking {
    database.withConnection { session ->
      val college = CollegesDao.upsert(session, newCollege(ipedsUnitId)).getOrThrow()
      CollegesDao
        .upsertProgram(session, NewCollegeProgram(college.id, cipCode, title, 3))
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
        "locale_type",
        "locale_detail",
        "control",
        "minUndergradEnrollmentHeadcount",
        "maxUndergradEnrollmentHeadcount",
        "minAdmissionRateShare",
        "maxAdmissionRateShare",
        "maxNetPricePerYearUsd",
        "minCompletionRate150pct4yrShare",
        "sort_by",
        "credential_level",
        "limit",
      )
    assertEquals(expected, properties.keys)

    // No field is required (all optional).
    val required = schema["required"] as JsonArray
    assertTrue(required.isEmpty())
  }

  @Test
  fun `definition documents that dotted CIP notation is accepted`() {
    val cip =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject["cipPrefix"]!!
        .jsonObject["description"]!!
        .jsonPrimitive.content
    assertTrue(cip.contains("26.0702"), "the dotted form should be shown")
    assertTrue(cip.contains("canonical"), "the canonical form should be named")
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
          put("maxNetPricePerYearUsd", 25000)
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
  fun `each search result carries the college_id update_college_list takes`() =
    runBlocking {
      val seeded = database.withConnection { session -> CollegesDao.upsert(session, newCollege(842)).getOrThrow() }

      val result = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("CA")) }) })

      assertNull(result["error"])
      val first = (result["colleges"] as JsonArray).single().jsonObject
      assertEquals(seeded.id.value.toString(), first["college_id"]!!.jsonPrimitive.content)
      // First key by design: the model reads the id before anything it might
      // mistake for one (the name, the unit id).
      assertEquals("college_id", first.keys.first())
    }

  @Test
  fun `definition description tells the model to copy college_id verbatim`() {
    val description = tool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("college_id"))
    assertTrue(description.contains("verbatim"))
  }

  @Test
  fun `execute on malformed input returns an error object, not an exception`() =
    runBlocking {
      val nonDigitPrefix = tool.execute(buildJsonObject { put("cipPrefix", "bio") })
      assertTrue(nonDigitPrefix.containsKey("error"))

      val wrongTypedNetPrice = tool.execute(buildJsonObject { put("maxNetPricePerYearUsd", "cheap") })
      assertTrue(wrongTypedNetPrice.containsKey("error"))

      val unknownField = tool.execute(buildJsonObject { put("nearOcean", true) })
      assertTrue(unknownField.containsKey("error"))

      // An error envelope is a model-facing tool result too, and a malformed-arg
      // retry is an ordinary path -- so it owes the same no-bare-source-code
      // property as the success payload (RFC 143).
      assertEquals(emptyList(), listViolations(unknownField), "the malformed-input error must carry no source code")
    }

  @Test
  fun `execute accepts CIP prefixes in the conventional dotted notation`() =
    runBlocking {
      seedWithMarineBiology(800)

      // The dotted form a model naturally writes, and the digits-only canonical
      // form, are the same query.
      for (prefix in listOf("2607", "26.07", "260702", "26.0702", " 26.0702 ", "26")) {
        val result = tool.execute(buildJsonObject { put("cipPrefix", prefix) })
        assertNull(result["error"], "cipPrefix [$prefix] should be accepted")
        assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull, "cipPrefix [$prefix] should match")
      }
    }

  @Test
  fun `execute reads a CIP prefix the model wrote unquoted`() =
    runBlocking {
      seedWithMarineBiology(802)

      // The schema says string, but a model writing 26.07 often omits the quotes;
      // the number's literal text is still a readable prefix.
      val result = tool.execute(buildJsonObject { put("cipPrefix", JsonPrimitive(26.07)) })
      assertNull(result["error"])
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `execute reads a dotted CIP prefix whose family lost its leading zero`() =
    runBlocking {
      seedWithProgram(801, "050103", "Asian Studies")

      // "5.0103" is 05.0103 with the leading zero elided -- splitting on the dot
      // and padding the family recovers it, where deleting the dot would not.
      for (prefix in listOf("050103", "5.0103", "05.0103")) {
        val result = tool.execute(buildJsonObject { put("cipPrefix", prefix) })
        assertNull(result["error"], "cipPrefix [$prefix] should be accepted")
        assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull, "cipPrefix [$prefix] should match")
      }
    }

  @Test
  fun `execute still rejects ambiguous or malformed CIP prefixes`() =
    runBlocking {
      // "5.138" is ambiguous (05.138? 51.38?) -- refuse rather than guess, since
      // guessing "5138" would silently answer about 51.38 Nursing instead.
      for (prefix in listOf("5.138", "26.07.02", ".2607", "26.070", "bio", "26.b7", "2.6.0.7")) {
        val result = tool.execute(buildJsonObject { put("cipPrefix", prefix) })
        val error = result["error"]
        assertNotNull(error, "cipPrefix [$prefix] should be rejected")
        val message = error.jsonPrimitive.content
        assertTrue(message.contains("26.07"), "error should show the accepted dotted form")
        assertTrue(message.contains(prefix), "error should echo the rejected input [$prefix]")
        assertNull(result["count"])
      }
    }

  @Test
  fun `execute rejects out-of-domain filter values with a structured error`() =
    runBlocking {
      // control: every element must be a control word, never a code (RFC 147).
      val badControl = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive("state-run")) }) })
      assertTrue(badControl.containsKey("error"))
      assertNull(badControl["count"])

      // ...and the CODE the column stores is no longer an accepted input: a
      // model that remembers the old contract is corrected, not obeyed.
      val codedControl = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive(1)) }) })
      assertTrue(codedControl.containsKey("error"))
      assertNull(codedControl["count"])

      // region: must be a published region word.
      val badRegion = tool.execute(buildJsonObject { put("region", "narnia") })
      assertTrue(badRegion.containsKey("error"))
      assertNull(badRegion["count"])
      val codedRegion = tool.execute(buildJsonObject { put("region", 1) })
      assertTrue(codedRegion.containsKey("error"))
      assertNull(codedRegion["count"])

      // minAdmissionRateShare / maxAdmissionRateShare / minCompletionRate150pct4yrShare: must be in 0.0..1.0
      val badMinAdmission = tool.execute(buildJsonObject { put("minAdmissionRateShare", 1.5) })
      assertTrue(badMinAdmission.containsKey("error"))
      assertNull(badMinAdmission["count"])
      val badMaxAdmission = tool.execute(buildJsonObject { put("maxAdmissionRateShare", -0.1) })
      assertTrue(badMaxAdmission.containsKey("error"))
      assertNull(badMaxAdmission["count"])
      val badGraduation = tool.execute(buildJsonObject { put("minCompletionRate150pct4yrShare", 2.0) })
      assertTrue(badGraduation.containsKey("error"))
      assertNull(badGraduation["count"])

      // maxNetPricePerYearUsd / enrollment bounds: must be >= 0
      val badNetPrice = tool.execute(buildJsonObject { put("maxNetPricePerYearUsd", -1) })
      assertTrue(badNetPrice.containsKey("error"))
      assertNull(badNetPrice["count"])
      val badMinEnrollment = tool.execute(buildJsonObject { put("minUndergradEnrollmentHeadcount", -1) })
      assertTrue(badMinEnrollment.containsKey("error"))
      assertNull(badMinEnrollment["count"])
      val badMaxEnrollment = tool.execute(buildJsonObject { put("maxUndergradEnrollmentHeadcount", -5) })
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
  fun `result objects carry the reported income bands, each labeled, and median debt`() =
    runBlocking {
      // RFC 133: seed a college with a negative low band (valid, 0022 precedent)
      // and some bands absent. RFC 142: the five opaque net_price_per_year_income_qN_usd keys are
      // gone -- what serializes is one entry per REPORTED band, each carrying
      // the band code, the dollar range a coach says aloud, and the amount.
      database.withConnection { session ->
        CollegesDao
          .upsert(
            session,
            newCollege(820).copy(netPricePerYearIncomeQ1Usd = -1200, netPricePerYearIncomeQ3Usd = 14500, medianDebtAtCompletionUsd = 21000),
          ).getOrThrow()
      }

      val result = tool.execute(buildJsonObject {})
      assertNull(result["error"])
      val first = (result["colleges"] as JsonArray).single().jsonObject
      assertEquals(21000, first["median_debt_at_completion_usd"]!!.jsonPrimitive.intOrNull)

      val bands = (first["net_price_by_income_band"] as JsonArray).map { it.jsonObject }
      // Only the two reported bands: an unreported bracket is absent, never a
      // labeled null a model could read as a price.
      assertEquals(
        listOf(IncomeBand.UNDER_30K.value, IncomeBand.K48_TO_75K.value),
        bands.map { it["income_band"]!!.jsonPrimitive.content },
      )
      assertEquals(listOf(-1200, 14500), bands.map { it["net_price_per_year_usd"]!!.jsonPrimitive.intOrNull })
      // The label is the band's own bracket, from the one home for that copy --
      // so a wire label can never drift from what the prompt teaches.
      assertEquals(
        listOf(IncomeBand.UNDER_30K.bracket, IncomeBand.K48_TO_75K.bracket),
        bands.map { it["income_band_label"]!!.jsonPrimitive.content },
      )
      assertNull(first["net_price_per_year_income_q1_usd"], "the opaque quintile keys are gone (RFC 142)")
      assertNull(first["net_price_per_year_income_q5_usd"], "including the one a real user was read back as \"Q5\"")
    }

  @Test
  fun `search results name the control in words`() =
    runBlocking {
      // The gap RFC 143 closes: the sibling cost tool already said "public"
      // while search shipped the raw IPEDS integer beside it.
      database.withConnection { session ->
        CollegesDao.upsert(session, newCollege(822).copy(control = 2)).getOrThrow()
      }

      val first = ((tool.execute(buildJsonObject {}))["colleges"] as JsonArray).single().jsonObject
      val control = first["control"]!!.jsonPrimitive
      assertEquals("private_nonprofit", control.content, "the label, from InstitutionControl's one home")
      assertNull(control.intOrNull, "never the bare code the model would have to translate")
    }

  @Test
  fun `no bare source code reaches a tool result`() =
    runBlocking {
      // The leak RFC 142 was actually about: search is what puts net prices in
      // front of the model on the ordinary path, so BOTH surfaces it sees --
      // the rendered result and the tool description it reads first -- must be
      // free of the source's own codes. The assertion is the general property
      // (RFC 143), not a grep for the two tokens that leaked before.
      // Every optional measure is populated on purpose: `matchObject` renders
      // them with `putOrNull`, so a field left null is simply absent and the
      // guard never sees it -- and `foo?.let { put("foo", code) }` is exactly
      // the shape the NEXT coded field will take. A sparse fixture would let it
      // sleep through.
      database.withConnection { session ->
        CollegesDao
          .upsert(
            session,
            newCollege(821).copy(
              netPricePerYearIncomeQ5Usd = 31000,
              control = 3,
              medianDebtAtCompletionUsd = 21000,
              satAverageEquivalentScore = 1200,
              costOfAttendancePerYearUsd = 40000,
              tuitionAndFeesInStatePerYearUsd = 12000,
              tuitionAndFeesOutOfStatePerYearUsd = 30000,
            ),
          ).getOrThrow()
      }

      val result = tool.execute(buildJsonObject {})
      assertEquals(emptyList(), listViolations(result), "the search result must carry no source code")
      assertTrue(result.toString().contains(IncomeBand.OVER_110K.bracket), "the dollar range is what goes instead")

      // ...and the clean verdict above is over a payload that actually contains
      // every allowlisted field, so the allowlist is exercised rather than
      // vacuously satisfied by absent keys.
      val rendered = BareSourceCodeGuard.listNumericFields(result).toSet()
      assertEquals(emptySet(), NUMBERS_BY_CONTRACT - rendered, "every field the allowlist sanctions must be in the payload")

      // The description is prose the model reads before any result, so only the
      // token half applies to it -- its `control` filter documents the CODES on
      // purpose: there the code is the input contract.
      val description = tool.definition["description"]!!.jsonPrimitive.content
      assertNull(QUINTILE_CODE.find(description), "nor the description the model reads first: [$description]")
      assertFalse(description.contains("NPT4"), "nor in the description: [$description]")

      // Positive control: the guard must react to ALL THREE shapes it exists to
      // catch -- including `NPT4`, whose old direct `assertFalse(contains(...))`
      // this guard replaced -- or the assertions above prove nothing and an
      // NPT4 typo in the helper would pass unnoticed.
      val doctored =
        JsonObject(
          result +
            mapOf(
              "control" to JsonPrimitive(2),
              "net_price_per_year_income_q5_usd" to JsonPrimitive(31000),
              "source_column" to JsonPrimitive("NPT41"),
            ),
        )
      assertEquals(
        listOf(
          BareSourceCode.QuintileToken("q5"),
          BareSourceCode.Npt4ColumnFamily,
          BareSourceCode.BareNumberField("control"),
          BareSourceCode.BareNumberField("net_price_per_year_income_q5_usd"),
        ),
        listViolations(doctored),
      )
    }

  @Test
  fun `definition description names the five income brackets in dollars`() {
    // The coach must be able to pick the right band conversationally, so the
    // description spells out each band's range -- rendered from IncomeBand, the
    // one home for that copy, never hand-typed here or there.
    val description = tool.definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("net_price_by_income_band"))
    assertTrue(description.contains("median_debt_at_completion_usd"))
    IncomeBand.entries.forEach { band ->
      assertTrue(description.contains(band.bracket), "the description must name [${band.bracket}]")
    }
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
        conn.createStatement().use { it.execute("ALTER TABLE colleges DROP COLUMN pell_share") }
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
        // The failure envelope interpolates an UPSTREAM message, so it is the
        // error shape most able to leak a source column name to the model; it
        // goes through the same guard as the success payload (RFC 143).
        assertEquals(emptyList(), listViolations(result), "the search-failure error must carry no source code")
      } finally {
        database.createRawConnection().use { conn ->
          conn.createStatement().use {
            it.execute(
              "ALTER TABLE colleges ADD COLUMN pell_share DOUBLE PRECISION " +
                "CONSTRAINT colleges_pell_share_range_check CHECK (pell_share IS NULL OR pell_share BETWEEN 0 AND 1)",
            )
          }
        }
      }
    }

  // ---------------------------------------------------------------------------
  // sort_by / credential_level / total_matches (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `definition exposes sort_by and credential_level as word enums only`() {
    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject

    val sortWords = (properties["sort_by"]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content }
    assertEquals(
      listOf("enrollment", "admission_rate_share", "net_price_per_year_usd", "completion_rate_150pct_4yr_share", "name"),
      sortWords,
    )

    val credentialWords =
      (properties["credential_level"]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content }
    assertEquals(listOf("certificate", "associate", "bachelors", "masters", "doctoral"), credentialWords)
    // Raw CREDLEV codes never reach the LLM (brief 0004 amendment): the schema
    // text for credential_level carries words, not numeric codes.
    val description = properties["credential_level"]!!.jsonObject["description"]!!.jsonPrimitive.content
    assertTrue(Regex("[0-9]").containsMatchIn(description).not(), "no raw code may appear: $description")
  }

  @Test
  fun `execute sorts by name when sort_by is name`() =
    runBlocking {
      seedNamed(910, "Zebra College")
      seedNamed(911, "Aardvark College")

      val result = tool.execute(buildJsonObject { put("sort_by", "name") })
      assertNull(result["error"])
      val names = (result["colleges"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }
      assertEquals(listOf("Aardvark College", "Zebra College"), names)
    }

  @Test
  fun `execute rejects an unknown sort_by word`() =
    runBlocking {
      val result = tool.execute(buildJsonObject { put("sort_by", "biggest") })
      assertTrue(result.containsKey("error"))
      val detail = result["error"]!!.toString()
      assertTrue(detail.contains("biggest"), "the rejection echoes the offending word: $detail")
      assertTrue(detail.contains("enrollment"), "the rejection lists the vocabulary: $detail")
    }

  @Test
  fun `execute maps credential_level words to the program join at the boundary`() =
    runBlocking {
      seedWithProgram(920, "230101", "English")
      // A master's-only sibling: same CIP, credential level 5.
      runBlocking {
        database.withConnection { session ->
          val college = CollegesDao.upsert(session, newCollege(921)).getOrThrow()
          CollegesDao
            .upsertProgram(
              session,
              ed.unicoach.db.models
                .NewCollegeProgram(college.id, "230101", "English", 5),
            ).getOrThrow()
        }
      }

      val bachelors =
        tool.execute(
          buildJsonObject {
            put("cipPrefix", "23")
            put("credential_level", "bachelors")
          },
        )
      assertNull(bachelors["error"])
      assertEquals(1, (bachelors["colleges"] as JsonArray).size)
      assertEquals(
        "Coastal College 920",
        (bachelors["colleges"] as JsonArray)
          .single()
          .jsonObject["name"]!!
          .jsonPrimitive.content,
      )

      val masters =
        tool.execute(
          buildJsonObject {
            put("cipPrefix", "23")
            put("credential_level", "masters")
          },
        )
      assertNull(masters["error"])
      assertEquals(
        "Coastal College 921",
        (masters["colleges"] as JsonArray)
          .single()
          .jsonObject["name"]!!
          .jsonPrimitive.content,
      )
    }

  @Test
  fun `execute rejects credential_level without cipPrefix and unknown words`() =
    runBlocking {
      val alone = tool.execute(buildJsonObject { put("credential_level", "bachelors") })
      assertTrue(alone.containsKey("error"))

      val unknown =
        tool.execute(
          buildJsonObject {
            put("cipPrefix", "23")
            put("credential_level", "bachelor's degree")
          },
        )
      assertTrue(unknown.containsKey("error"))
      assertTrue(
        unknown["error"]!!.toString().contains("bachelor's degree"),
        "the rejection echoes the offending word: ${unknown["error"]}",
      )
    }

  @Test
  fun `total_matches is unclamped while count is the returned slice`() =
    runBlocking {
      for (u in 930..934) seedNamed(u, "Count College $u")

      val result = tool.execute(buildJsonObject { put("limit", 2) })
      assertNull(result["error"])
      assertEquals(2, result["count"]!!.jsonPrimitive.intOrNull)
      assertEquals(5, result["total_matches"]!!.jsonPrimitive.intOrNull)
      assertEquals(2, (result["colleges"] as JsonArray).size)
    }

  // ---------------------------------------------------------------------------
  // The published vocabulary, in and out (RFC 147 D45)
  // ---------------------------------------------------------------------------

  @Test
  fun `the region enum offered is the loaded codebook's own word list`() {
    val region =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject["region"]!!
        .jsonObject

    assertEquals("string", region["type"]!!.jsonPrimitive.content)
    // Read from the loaded table, not retyped: the assertion is that the schema
    // and the reference table are THE SAME LIST, which a literal could not say.
    assertEquals(
      codebook.regionSlugs,
      (region["enum"] as JsonArray).map { it.jsonPrimitive.content },
    )
    assertTrue(codebook.regionSlugs.contains("new-england"), "the published codebook must carry the region words")
    // Not a single bare code anywhere in what the model reads about the field,
    // proved with the shared pattern's own positive control first.
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    assertNull(
      BareSourceCodeGuard.CODE_EQUALS_WORD.find(region.toString()),
      "the region property must name no code: $region",
    )

    val localeType =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject["locale_type"]!!
        .jsonObject
    assertEquals(
      NcesLocaleType.WORDS,
      (localeType["enum"] as JsonArray).map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `a region word filters on the code it names`() =
    runBlocking {
      // newCollege() is region 8 (far-west) / locale 13 (city: small).
      seedNamed(840, "Far West College")

      val hit = tool.execute(buildJsonObject { put("region", "far-west") })
      assertNull(hit["error"])
      assertEquals(1, hit["count"]!!.jsonPrimitive.intOrNull)

      val miss = tool.execute(buildJsonObject { put("region", "new-england") })
      assertNull(miss["error"])
      assertEquals(0, miss["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `a locale type matches every published size and locale detail narrows it`() =
    runBlocking {
      seedNamed(841, "City Small College") // locale 13 = city: small
      database.withConnection { session ->
        CollegesDao.upsert(session, newCollege(842).copy(name = "Rural Remote College", locale = 43)).getOrThrow()
      }

      // The type alone is the OR-set over its published sizes -- one word where
      // the old contract took [11, 12, 13].
      val city = tool.execute(buildJsonObject { put("locale_type", "city") })
      assertEquals(1, city["count"]!!.jsonPrimitive.intOrNull)
      val rural = tool.execute(buildJsonObject { put("locale_type", "rural") })
      assertEquals(1, rural["count"]!!.jsonPrimitive.intOrNull)

      val citySmall =
        tool.execute(
          buildJsonObject {
            put("locale_type", "city")
            put("locale_detail", "small")
          },
        )
      assertEquals(1, citySmall["count"]!!.jsonPrimitive.intOrNull)
      val cityLarge =
        tool.execute(
          buildJsonObject {
            put("locale_type", "city")
            put("locale_detail", "large")
          },
        )
      assertNull(cityLarge["error"])
      assertEquals(0, cityLarge["count"]!!.jsonPrimitive.intOrNull, "a real pairing with no rows is an empty result, not an error")
    }

  @Test
  fun `an unknown word is a listed error, never a silent no-op`() =
    runBlocking {
      seedNamed(843, "Listed Error College")

      // The failure mode this replaces: an unresolvable filter that is dropped
      // returns EVERY college, answering a narrower question with a wider answer.
      val region = tool.execute(buildJsonObject { put("region", "new-englund") })
      val regionError = region["error"]!!.jsonPrimitive.content
      assertNull(region["count"], "an unknown word must not fall through to a search")
      assertTrue(regionError.contains("new-englund"), "the error echoes the offending word: $regionError")
      codebook.regionSlugs.forEach {
        assertTrue(regionError.contains(it), "the error lists the whole vocabulary; [$it] is missing: $regionError")
      }

      val localeType = tool.execute(buildJsonObject { put("locale_type", "metropolis") })
      val typeError = localeType["error"]!!.jsonPrimitive.content
      assertNull(localeType["count"])
      NcesLocaleType.WORDS.forEach { assertTrue(typeError.contains(it), "locale_type error must list [$it]: $typeError") }

      // A pairing the publisher does not define is an error too -- "city: fringe"
      // is not a locale, and matching zero colleges would read as "none exist".
      val impossible =
        tool.execute(
          buildJsonObject {
            put("locale_type", "city")
            put("locale_detail", "fringe")
          },
        )
      val pairError = impossible["error"]!!.jsonPrimitive.content
      assertNull(impossible["count"])
      assertTrue(pairError.contains("large"), "the error lists what the type DOES publish: $pairError")

      // ...and a detail with no type is refused rather than quietly ignored.
      val orphan = tool.execute(buildJsonObject { put("locale_detail", "large") })
      assertTrue(orphan.containsKey("error"))
      assertNull(orphan["count"])
    }

  @Test
  fun `search results name the region and locale in words`() =
    runBlocking {
      seedNamed(844, "Worded College")

      val first = ((tool.execute(buildJsonObject {}))["colleges"] as JsonArray).single().jsonObject
      assertEquals("far-west", first["region"]!!.jsonPrimitive.content, "the same word the region filter takes")
      assertEquals("city", first["locale_type"]!!.jsonPrimitive.content)
      assertEquals("small", first["locale_detail"]!!.jsonPrimitive.content)
      assertNull(first["region"]!!.jsonPrimitive.intOrNull, "never the bare OBEREG code")
      assertNull(first["locale_type"]!!.jsonPrimitive.intOrNull, "never the bare LOCALE code")

      // The round trip: what the result says is what the filter accepts.
      val echoed =
        tool.execute(
          buildJsonObject {
            put("region", first["region"]!!.jsonPrimitive.content)
            put("locale_type", first["locale_type"]!!.jsonPrimitive.content)
            put("locale_detail", first["locale_detail"]!!.jsonPrimitive.content)
          },
        )
      assertNull(echoed["error"])
      assertEquals(1, echoed["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `a control word filters on the code the column stores`() =
    runBlocking {
      database.withConnection { session ->
        CollegesDao.upsert(session, newCollege(845).copy(control = 2)).getOrThrow()
      }

      val hit = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive("private_nonprofit")) }) })
      assertEquals(1, hit["count"]!!.jsonPrimitive.intOrNull)
      val miss = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive("public")) }) })
      assertEquals(0, miss["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `a stored code with no codebook row is named unknown, never a bare number`() =
    runBlocking {
      // colleges.locale's CHECK still admits 11..43, which is wider than the 12
      // codes IPEDS publishes (D46). A code inside the range and outside the
      // codebook must still leave as a word.
      database.withConnection { session ->
        CollegesDao.upsert(session, newCollege(846).copy(locale = 14)).getOrThrow()
      }

      val first = ((tool.execute(buildJsonObject {}))["colleges"] as JsonArray).single().jsonObject
      val localeType = first["locale_type"]!!.jsonPrimitive
      assertNull(localeType.intOrNull, "an unmapped code must not fall back to the raw number")
      assertTrue(localeType.content.startsWith("unknown"), "it is named as unknown: ${localeType.content}")
      assertEquals(emptyList(), listViolations(first), "and it still trips no source-code guard")
    }

  @Test
  fun `an empty codebook advertises nothing and refuses every word, both halves alike`() =
    runBlocking {
      // The state of every database before the `codebooks` ingest phase runs.
      // The rule is ONE rule: advertise what is LOADED. `region` already did;
      // `locale_type`/`locale_detail` used to advertise their Kotlin enums and
      // then refuse every word of them.
      val bare = CollegeSearchTool(CollegeSearchService(database), Codebook.EMPTY)
      val properties =
        bare.definition["input_schema"]!!
          .jsonObject["properties"]!!
          .jsonObject

      for (field in listOf("region", "locale_type", "locale_detail")) {
        val property = properties[field]!!.jsonObject
        assertNull(property["enum"], "[$field] must advertise no words when none are loaded")
        assertTrue(
          property["description"]!!.jsonPrimitive.content.contains("none are loaded"),
          "[$field] must say so: ${property["description"]}",
        )
      }

      seedNamed(850, "Unvocabularied College")
      // And every word is a clean, explained refusal — never a dropped filter
      // that would answer with the whole corpus.
      for (
      input in
      listOf(
        buildJsonObject { put("region", "far-west") },
        buildJsonObject { put("locale_type", "city") },
      )
      ) {
        val result = bare.execute(input)
        val error = result["error"]!!.jsonPrimitive.content
        assertNull(result["count"], "an unresolvable word must not fall through to a search")
        assertTrue(error.contains(Codebook.UNAVAILABLE), "the refusal must say why: $error")
      }
    }

  private fun seedNamed(
    ipedsUnitId: Int,
    name: String,
  ) = runBlocking {
    database.withConnection { session ->
      CollegesDao.upsert(session, newCollege(ipedsUnitId).copy(name = name)).getOrThrow()
    }
  }
}

// ---------------------------------------------------------------------------
// The generalised source-code guard (RFC 143), hosted in :chat's test fixtures
// since RFC 148 D9 -- the walker is shared, the allowlist stays this tool's own.
// ---------------------------------------------------------------------------

/**
 * The field names whose value is a NUMBER by contract -- the measures this tool
 * reports, plus the result count -- so a number under them is a fact, not a
 * code. Every other numeric field is a coded dimension until sanctioned here;
 * `control` was exactly that, and this list is the one place to admit the next
 * one, deliberately short enough to read in a review.
 *
 * The documented codes the model hands back (`income_band`, which
 * `update_money_profile` accepts, and `college_id`) ride as STRINGS and so
 * never reach this check.
 */
private val NUMBERS_BY_CONTRACT =
  setOf(
    "count",
    // RFC 139: the honest, unclamped match total beside `count`'s returned
    // slice. A count of colleges is a number by contract in exactly the way
    // `count` is -- it is not, and can never become, a Scorecard code.
    "total_matches",
    "undergrad_enrollment_headcount",
    "admission_rate_share",
    "net_price_per_year_usd",
    "completion_rate_150pct_4yr_share",
    "median_earnings_10y_after_entry_usd",
    "median_debt_at_completion_usd",
    "pell_share",
  )

private val QUINTILE_CODE = BareSourceCodeGuard.QUINTILE_CODE

private fun listViolations(payload: JsonElement): List<BareSourceCode> = BareSourceCodeGuard.listViolations(payload, NUMBERS_BY_CONTRACT)
