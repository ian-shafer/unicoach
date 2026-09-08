package ed.unicoach.college

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeProgramsCensus
import ed.unicoach.db.models.NewSubject
import ed.unicoach.db.models.PriceRuler
import ed.unicoach.db.models.RESIDENCY_TIERS_KEY
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ResidencyTierBasis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import java.time.Instant
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
        // `subjects` too (RFC 150): the taxonomy is loaded per test, and a row
        // left behind would advertise a subject the next test did not seed.
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs, subjects CASCADE").use { it.execute() }
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

  private fun seedWithMarineBiology(ipedsUnitId: Int) = seedWithProgram(ipedsUnitId, "260702")

  /**
   * Seeds one college with one IPEDS-census program. The census, not
   * `college_programs`, is what the search index derives from (RFC 150 D51),
   * and the TITLE is not passed in at all: it comes back from `cip_codes`, the
   * one home for a CIP title, joined over the returned page.
   */
  private fun seedWithProgram(
    ipedsUnitId: Int,
    cipCode: String,
  ) = runBlocking {
    database.withConnection { session ->
      val college = CollegesDao.upsert(session, newCollege(ipedsUnitId)).getOrThrow()
      CollegeIpedsDao
        .upsertProgramsCensus(session, NewCollegeProgramsCensus(college.id, cipCode, 5, 12, 2023))
        .getOrThrow()
      CollegesDao.rebuildSearchIndex(session).getOrThrow()
      college
    }
  }

  /**
   * Seeds one college and rebuilds `college_search_index` (RFC 150 D53). Both
   * search entry points read that table, and only the ingest's `search-index`
   * phase writes it — so a test that writes `colleges` directly must rebuild it
   * here, where a later test cannot forget to.
   */
  private fun insert(input: NewCollege) =
    runBlocking {
      database.withConnection { session ->
        val college = CollegesDao.upsert(session, input).getOrThrow()
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        college
      }
    }

  /**
   * The four canonical components a published on-campus total is summed from
   * (RFC 169), at both tuition tiers, plus the rebuild that materialises them
   * onto the index.
   *
   * Raw SQL on purpose: `CanonicalMoneyDao`'s write API is wholesale-only, and
   * the rebuild reads the TABLE. The vocabulary tables are a write precondition,
   * so they are seeded first from the committed vocabulary file.
   */
  private fun seedPublishedPrice(
    college: ed.unicoach.db.models.College,
    inStateTuition: Int,
    outOfStateTuition: Int,
    /**
     * The IN-DISTRICT tuition row, and the three states brief 0006 D19 turns on:
     * `null` seeds NO ROW (the Scorecard-only silence), an amount seeds a real
     * district price, and [inDistrictStatus] seeds a row that bears no value —
     * `not_applicable` being the publisher's own ANSWER that there is no
     * district tier here.
     */
    inDistrictTuition: Int? = null,
    inDistrictStatus: String? = null,
  ) = runBlocking {
    database.withConnection { session ->
      ed.unicoach.db.dao.MoneyVocabularyFixture
        .seed(session)
      if (inDistrictTuition != null || inDistrictStatus != null) {
        session
          .prepareStatement(
            "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
              "academic_year, amount_usd, status, source, source_variable) " +
              "VALUES (?, 'tuition_and_fees', 'in_district', 'not_applicable', 2023, ?, ?, " +
              "'ipeds_ic_ay', 'FIXTURE')",
          ).use { stmt ->
            stmt.setObject(1, college.id.value)
            if (inDistrictTuition == null) {
              stmt.setNull(2, java.sql.Types.INTEGER)
            } else {
              stmt.setInt(2, inDistrictTuition)
            }
            stmt.setString(3, inDistrictStatus ?: "reported")
            stmt.executeUpdate()
          }
      }
      val cells =
        listOf(
          Triple("tuition_and_fees", "in_state" to "not_applicable", inStateTuition),
          Triple("tuition_and_fees", "out_of_state" to "not_applicable", outOfStateTuition),
          Triple("housing_and_food", "not_applicable" to "on_campus", 12000),
          Triple("books_and_supplies", "not_applicable" to "not_applicable", 1200),
          Triple("other_expenses", "not_applicable" to "on_campus", 2800),
        )
      for ((concept, axes, amount) in cells) {
        session
          .prepareStatement(
            "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
              "academic_year, amount_usd, status, source, source_variable) " +
              "VALUES (?, ?, ?, ?, 2023, ?, 'reported', 'ipeds_ic_ay', 'FIXTURE')",
          ).use { stmt ->
            stmt.setObject(1, college.id.value)
            stmt.setString(2, concept)
            stmt.setString(3, axes.first)
            stmt.setString(4, axes.second)
            stmt.setInt(5, amount)
            stmt.executeUpdate()
          }
      }
      CollegesDao.rebuildSearchIndex(session).getOrThrow()
      Unit
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
    // `subject` is ABSENT here on purpose: this fixture loads the published
    // codebooks but no taxonomy, and a filter with no vocabulary is not
    // advertised at all (RFC 150). The test below loads one and sees it.
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
        // TWO price bounds since RFC 169, one per ruler, and exactly one is
        // honoured per call: the schema is built once at boot, before any
        // family is known, so both are advertised and naming the inactive one
        // is refused by name (the expand-and-refuse precedent). No residency
        // FIELD is added -- the state is read from the money profile.
        "maxInStateNetPricePerYearUsd",
        "maxPublishedPriceOnCampusPerYearUsd",
        "minCompletionRate150pct4yrShare",
        "test_policy",
        "religious_affiliation",
        "carnegie_class",
        "carnegie_size",
        "athletic_association",
        "has_rotc",
        "has_study_abroad",
        "has_housing",
        "is_active",
        "is_four_year",
        "sort_by",
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
          put("maxInStateNetPricePerYearUsd", 25000)
        }
      val result = tool.execute(input)

      assertNull(result["error"])
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull)
      val colleges = result["colleges"] as JsonArray
      assertEquals(1, colleges.size)
      val first = colleges.single().jsonObject
      assertEquals("Coastal College 800", first["name"]!!.jsonPrimitive.content)
      val programs = first["programs"] as JsonArray
      // The title comes from the loaded `cip_codes` vocabulary, not from a
      // string this test typed: there is exactly one home for a CIP title.
      assertEquals(listOf("Entomology"), programs.map { it.jsonPrimitive.content })
    }

  @Test
  fun `each search result carries the college_id update_college_list takes`() =
    runBlocking {
      val seeded = insert(newCollege(842))

      val result = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("CA")) }) })

      assertNull(result["error"])
      val first = (result["colleges"] as JsonArray).single().jsonObject
      assertEquals(seeded.id.value.toString(), first["college_id"]!!.jsonPrimitive.content)
      // First key by design: the model reads the id before anything it might
      // mistake for one (the name, the unit id).
      assertEquals("college_id", first.keys.first())
    }

  @Test
  fun `programs is OMITTED on a search that asked nothing about programs`() =
    runBlocking {
      seedWithMarineBiology(806)

      val unfiltered = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("CA")) }) })
      val row = (unfiltered["colleges"] as JsonArray).single().jsonObject
      // The key MEANS "what your program filter matched". With no filter there
      // is no answer, and `programs: []` on every search reads as "this college
      // offers nothing".
      assertFalse(row.containsKey("programs"), "expected no programs key, got: $row")

      val filtered = tool.execute(buildJsonObject { put("cipPrefix", "2607") })
      val matched = (filtered["colleges"] as JsonArray).single().jsonObject
      assertEquals(
        listOf("Entomology"),
        (matched["programs"] as JsonArray).map { it.jsonPrimitive.content },
      )
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

      val wrongTypedNetPrice = tool.execute(buildJsonObject { put("maxInStateNetPricePerYearUsd", "cheap") })
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
      seedWithProgram(801, "050103")

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

      // maxInStateNetPricePerYearUsd / enrollment bounds: must be >= 0
      val badNetPrice = tool.execute(buildJsonObject { put("maxInStateNetPricePerYearUsd", -1) })
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
      insert(
        newCollege(820).copy(netPricePerYearIncomeQ1Usd = -1200, netPricePerYearIncomeQ3Usd = 14500, medianDebtAtCompletionUsd = 21000),
      )

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
      // The band amounts carry the SAME basis-naming key the top-level net
      // price does (RFC 169 D7): one measure, one name, wherever it appears.
      assertEquals(listOf(-1200, 14500), bands.map { it["in_state_net_price_per_year_usd"]!!.jsonPrimitive.intOrNull })
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
      insert(newCollege(822).copy(control = 2))

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
      insert(
        newCollege(821).copy(
          netPricePerYearIncomeQ5Usd = 31000,
          control = 3,
          medianDebtAtCompletionUsd = 21000,
          satAverageEquivalentScore = 1200,
          costOfAttendancePerYearUsd = 40000,
          tuitionAndFeesInStatePerYearUsd = 12000,
          tuitionAndFeesOutOfStatePerYearUsd = 30000,
        ),
      )

      val result = tool.execute(buildJsonObject {})
      assertEquals(emptyList(), listViolations(result), "the search result must carry no source code")
      assertTrue(result.toString().contains(IncomeBand.OVER_110K.bracket), "the dollar range is what goes instead")

      // The SAME search on the published ruler (RFC 169), and one college per
      // tuition tier: a public in the family's own state renders the in-state
      // key, a public outside it the out-of-state one. Both are allowlisted, so
      // both have to be RENDERED here or the allowlist below is vacuous.
      seedPublishedPrice(insert(newCollege(823).copy(state = "NV", control = 1)), 9000, 26000)
      seedPublishedPrice(insert(newCollege(824).copy(state = "CA", control = 1)), 11000, 31000)
      val published = tool.execute(buildJsonObject {}, "NV")
      assertEquals(emptyList(), listViolations(published), "the published-ruler result must carry no source code either")

      // ...and the clean verdict above is over a payload that actually contains
      // every allowlisted field, so the allowlist is exercised rather than
      // vacuously satisfied by absent keys.
      val rendered =
        BareSourceCodeGuard.listNumericFields(result).toSet() +
          BareSourceCodeGuard.listNumericFields(published).toSet()
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
  fun `a school whose publisher does not separate a district price says so, and is ranked anyway`() =
    runBlocking {
      // Brief 0006 D19, the ~2,300-institution case: a Scorecard-only school has
      // NO in-district row at all, so the in-state figure we hold may already BE
      // the district price. It is RANKED exactly as every other school -- the
      // whole point is that it is neither dropped nor excluded -- and the gap is
      // said in the vocabulary's own words.
      val silent = insert(newCollege(840).copy(state = "NV", control = 1))
      seedPublishedPrice(silent, 11000, 31000)

      val result = tool.execute(buildJsonObject {}, "NV")
      val row = (result["colleges"] as JsonArray).single().jsonObject
      // Ranked, and its price reported, exactly as before the label existed.
      assertEquals(27000, row["published_price_in_state_on_campus_per_year_usd"]!!.jsonPrimitive.intOrNull)
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull, "$result")
      assertEquals(emptyMap(), result["excluded_unknown"]!!.jsonObject, "never dropped, never counted out")

      val tiers = row[RESIDENCY_TIERS_KEY]!!.jsonObject
      // The ENUM's own text, never a literal: the sentence has one home, and a
      // reword there must travel here rather than leaving two accounts of one
      // fact on two surfaces.
      assertEquals(
        ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.value,
        tiers["basis"]!!.jsonPrimitive.content,
      )
      assertEquals(
        ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.statement,
        tiers["statement"]!!.jsonPrimitive.content,
      )
    }

  @Test
  fun `a publisher that ANSWERED not_applicable carries no district sentence`() =
    runBlocking {
      // THE regression the first derivation would have shipped. An `in_district`
      // row at `not_applicable` is IPEDS flag A -- the publisher saying "there is
      // no district tier at this school" -- and it bears no value, so a rule that
      // looked only at the absence of an AMOUNT could not tell it from the
      // silence above. Measured on the 2023 IC_AY snapshot: 354 schools of the
      // 3,264-school default universe are in this state, and every one of them
      // would have been told we cannot say whether a lower district price exists.
      val answered = insert(newCollege(841).copy(state = "NV", control = 1))
      seedPublishedPrice(answered, 11000, 31000, inDistrictStatus = "not_applicable")

      val row = ((tool.execute(buildJsonObject {}, "NV"))["colleges"] as JsonArray).single().jsonObject
      assertEquals(27000, row["published_price_in_state_on_campus_per_year_usd"]!!.jsonPrimitive.intOrNull)
      assertNull(row[RESIDENCY_TIERS_KEY], "the publisher answered, so there is nothing we cannot say: $row")
    }

  @Test
  fun `a school that publishes a real district price carries no district sentence either`() =
    runBlocking {
      // The third bucket, and the largest: 2,269 of the 3,264. The district price
      // exists, so nothing is unknown and the sentence would be false.
      val threeTier = insert(newCollege(842).copy(state = "NV", control = 1))
      seedPublishedPrice(threeTier, 11000, 31000, inDistrictTuition = 5000)

      val row = ((tool.execute(buildJsonObject {}, "NV"))["colleges"] as JsonArray).single().jsonObject
      assertNull(row[RESIDENCY_TIERS_KEY], "three tiers published: $row")
    }

  @Test
  fun `the district sentence rides the tier it is about, and no net-ruler row`() =
    runBlocking {
      val silent = insert(newCollege(843).copy(state = "NV", control = 1))
      seedPublishedPrice(silent, 11000, 31000)

      // OUT-OF-STATE tier: the figure being reported is the out-of-state one,
      // which no district price could ever be, so the sentence does not belong.
      val outOfState = ((tool.execute(buildJsonObject {}, "CA"))["colleges"] as JsonArray).single().jsonObject
      assertEquals(47000, outOfState["published_price_out_of_state_on_campus_per_year_usd"]!!.jsonPrimitive.intOrNull)
      assertNull(outOfState[RESIDENCY_TIERS_KEY], "the out-of-state tier is not the tier in question: $outOfState")

      // NET ruler: no published price is reported at all, so there is no
      // published tier for a sentence to be about.
      val net = ((tool.execute(buildJsonObject {}))["colleges"] as JsonArray).single().jsonObject
      assertNull(net[RESIDENCY_TIERS_KEY], "no published price is on the wire here: $net")
    }

  @Test
  fun `the result names the ruler it ranked on, and says aid is not in it`() =
    runBlocking {
      val college = insert(newCollege(830).copy(state = "NV", control = 1))
      seedPublishedPrice(college, 11000, 31000)

      // The published ruler, for a family living in NV.
      val published = tool.execute(buildJsonObject {}, "NV")
      val ruler = published["price_ruler"]!!.jsonObject
      assertEquals("published_price_on_campus", ruler["metric"]!!.jsonPrimitive.content)
      val note = ruler["note"]!!.jsonPrimitive.content
      assertTrue(note.contains("NO financial aid"), note)
      assertTrue(note.contains("no out-of-state after-aid price"), note)
      assertTrue(note.contains("NV"), "the note names the state the tier was chosen against: $note")
      // The row carries the tier that was applied to it: NV school, NV family.
      val row = (published["colleges"] as JsonArray).single().jsonObject
      assertEquals(27000, row["published_price_in_state_on_campus_per_year_usd"]!!.jsonPrimitive.intOrNull)
      assertNull(row["published_price_out_of_state_on_campus_per_year_usd"], "one tier per row: $row")

      // ...and the same school for a family from another state, on the other
      // tier, under the other key -- never the same key with a different number.
      val outOfState = tool.execute(buildJsonObject {}, "CA")
      val far = (outOfState["colleges"] as JsonArray).single().jsonObject
      assertEquals(47000, far["published_price_out_of_state_on_campus_per_year_usd"]!!.jsonPrimitive.intOrNull)

      // The net ruler names itself too: a key that appears only when the answer
      // is unusual is a key a reader learns to ignore.
      val net = tool.execute(buildJsonObject {})
      assertEquals("in_state_net_price", net["price_ruler"]!!.jsonObject["metric"]!!.jsonPrimitive.content)
    }

  @Test
  fun `an explicit null on the inactive ruler's field is an absent field, not a refusal`() =
    runBlocking {
      // Every optional field in this vocabulary reads through `field`, which
      // treats an explicit JSON null as ABSENT. The inactive-ruler check read
      // `containsKey`, so a model writing the key with a null value -- a shape
      // models produce routinely -- had its ENTIRE search refused for naming a
      // bound it did not state.
      insert(newCollege(844))

      val result =
        tool.execute(
          buildJsonObject {
            put("maxPublishedPriceOnCampusPerYearUsd", JsonNull)
          },
        )
      assertNull(result["error"], "an explicit null states no bound: $result")
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull, "$result")

      // ...and the refusal still fires for a bound that IS stated.
      val stated = tool.execute(buildJsonObject { put("maxPublishedPriceOnCampusPerYearUsd", 30000) })
      // `assertTrue`, not `assertNotNull`: the latter RETURNS the value it
      // checked, which would give this expression-bodied test a non-Unit return
      // type and stop JUnit discovering it at all.
      assertTrue(stated["error"] != null, "a real bound on the inactive ruler is still refused: $stated")
    }

  @Test
  fun `naming the inactive ruler is refused by name, never silently ignored`() =
    runBlocking {
      insert(newCollege(831))

      // No residency on file: the published words are the inactive ones.
      val filter = tool.execute(buildJsonObject { put("maxPublishedPriceOnCampusPerYearUsd", 30000) })
      val filterError = filter["error"]!!.jsonPrimitive.content
      assertTrue(filterError.contains("maxPublishedPriceOnCampusPerYearUsd"), filterError)
      assertTrue(filterError.contains("maxInStateNetPricePerYearUsd"), "it says which field to use: $filterError")
      assertNull(filter["colleges"], "a refusal must not fall through to a search")

      val sort = tool.execute(buildJsonObject { put("sort_by", "published_price_on_campus") })
      val sortError = sort["error"]!!.jsonPrimitive.content
      assertTrue(sortError.contains("published_price_on_campus"), sortError)
      assertTrue(sortError.contains("in_state_net_price"), "it says which word to use: $sortError")

      // ...and with a residency on file the refusal points the other way.
      val onPublished = tool.execute(buildJsonObject { put("sort_by", "in_state_net_price") }, "NV")
      val flipped = onPublished["error"]!!.jsonPrimitive.content
      assertTrue(flipped.contains("in_state_net_price"), flipped)
      assertTrue(flipped.contains("published_price_on_campus"), flipped)
    }

  @Test
  fun `a published bound filters on the family's own tuition tier`() =
    runBlocking {
      val nevada = insert(newCollege(832).copy(state = "NV", control = 1))
      seedPublishedPrice(nevada, 11000, 31000)

      // 27,000 in state, 47,000 out of state: the SAME school and the same
      // ceiling, answered opposite ways for two families.
      val resident = tool.execute(buildJsonObject { put("maxPublishedPriceOnCampusPerYearUsd", 30000) }, "NV")
      assertEquals(1, resident["count"]!!.jsonPrimitive.intOrNull, "$resident")

      val visitor = tool.execute(buildJsonObject { put("maxPublishedPriceOnCampusPerYearUsd", 30000) }, "CA")
      assertEquals(0, visitor["count"]!!.jsonPrimitive.intOrNull, "$visitor")
    }

  @Test
  fun `the tool description states both rulers and never says subtract without never`() {
    // `NET_PRICE_BASIS_NOTE` had ZERO test coverage before RFC 169, and it
    // carried the very assumption this slice overturns.
    val description = tool.definition["description"]!!.jsonPrimitive.content
    // Against the CONSTANTS the row actually emits, never against literals: the
    // note types these keys into prose, so a rename that missed it would leave
    // the model taught keys the payload no longer carries -- with this suite and
    // the payload suite both green.
    assertTrue(description.contains(PriceRuler.NET_PRICE_RESULT_KEY), description)
    assertTrue(
      description.contains(PriceRuler.resultKey(PriceRuler.PublishedTier.IN_STATE)),
      description,
    )
    assertTrue(
      description.contains(PriceRuler.resultKey(PriceRuler.PublishedTier.OUT_OF_STATE)),
      description,
    )
    assertTrue(description.contains("no financial aid of any kind"), "the published ruler has no aid in it")
    assertTrue(description.contains("There is no out-of-state after-aid price"), description)
    // The forbidden arithmetic is only ever mentioned as forbidden.
    for (index in Regex("subtract").findAll(description).map { it.range.first }) {
      assertTrue(
        description.substring(0, index).trimEnd().endsWith("never") ||
          description.substring(0, index).contains("nobody may ever "),
        "[subtract] must never appear without a refusal before it: [$description]",
      )
    }
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
      // A REAL state with no college in it. "ZZ" used to stand here, and it was
      // a zero-match for the wrong reason (see the test below).
      val result = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("WY")) }) })
      assertNull(result["error"])
      assertEquals(0, result["count"]!!.jsonPrimitive.intOrNull)
      assertTrue((result["colleges"] as JsonArray).isEmpty())
    }

  @Test
  fun `a state code the published vocabulary does not carry is refused, not answered with zero`() =
    runBlocking {
      seedWithMarineBiology(901)
      // "ZZ" is not a US jurisdiction. Shape-checked but never resolved, it came
      // back as an ordinary zero-match -- which reads to a family as "there are
      // no colleges there" rather than "that is not a state". The refusal names
      // the field and echoes the offending code, exactly as `region` does.
      val result = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("ZZ")) }) })
      val error = result["error"]!!.jsonPrimitive.content
      assertTrue(error.startsWith("[states] must be one of ["), "the refusal must name the vocabulary, got [$error]")
      assertTrue(error.contains("got [ZZ]"), "the refusal must echo the offending code, got [$error]")
      assertNull(result["count"], "a refused query must not carry a count")
    }

  @Test
  fun `an empty OR-set array is refused rather than silently dropped`() =
    runBlocking {
      // Both arrays used to parse clean and then be skipped at the bind, so a
      // NARROWED question came back with an UNNARROWED answer: every college in
      // the country, with nothing to say the filter had gone.
      seedWithMarineBiology(902)
      seedWithMarineBiology(903)

      val emptyStates = tool.execute(buildJsonObject { put("states", buildJsonArray { }) })
      val statesError = emptyStates["error"]!!.jsonPrimitive.content
      assertTrue(statesError.contains("[states] must name at least one value"), "got [$statesError]")
      assertTrue(statesError.contains("got []"), "the refusal must echo the empty array, got [$statesError]")
      assertNull(emptyStates["count"], "a refused query must not answer with every college")

      val emptyControl = tool.execute(buildJsonObject { put("control", buildJsonArray { }) })
      val controlError = emptyControl["error"]!!.jsonPrimitive.content
      assertTrue(controlError.contains("[control] must name at least one value"), "got [$controlError]")
      assertNull(emptyControl["count"], "a refused query must not answer with every college")
    }

  @Test
  fun `a type refusal echoes the value that was written`() =
    runBlocking {
      // These strings are the model's only means of self-correction, and both
      // of these inputs used to produce the identical "has_rotc must be a
      // boolean" -- so the model could not see which of its two mistakes it had
      // made.
      val number = tool.execute(buildJsonObject { put("has_rotc", 1) })["error"]!!.jsonPrimitive.content
      val word = tool.execute(buildJsonObject { put("has_rotc", "yes") })["error"]!!.jsonPrimitive.content

      assertEquals("[has_rotc] must be a boolean; got [1]", number)
      assertEquals("[has_rotc] must be a boolean; got [\"yes\"]", word)
      assertTrue(number != word, "two different mistakes must not produce the same feedback")

      // The same helper for every reader: an int, a number, a string and an array.
      assertEquals(
        "[minUndergradEnrollmentHeadcount] must be an integer; got [\"lots\"]",
        tool.execute(buildJsonObject { put("minUndergradEnrollmentHeadcount", "lots") })["error"]!!.jsonPrimitive.content,
      )
      assertEquals(
        "[maxAdmissionRateShare] must be a number; got [\"half\"]",
        tool.execute(buildJsonObject { put("maxAdmissionRateShare", "half") })["error"]!!.jsonPrimitive.content,
      )
      assertEquals(
        "[region] must be a string; got [8]",
        tool.execute(buildJsonObject { put("region", 8) })["error"]!!.jsonPrimitive.content,
      )
      assertEquals(
        "[states] must be an array of strings; got [[1,2]]",
        tool
          .execute(
            buildJsonObject {
              put(
                "states",
                buildJsonArray {
                  add(JsonPrimitive(1))
                  add(JsonPrimitive(2))
                },
              )
            },
          )["error"]!!
          .jsonPrimitive.content,
      )
    }

  @Test
  fun `an unbuilt search index is a named refusal, never a zero result`() =
    runBlocking {
      // The migration creates `college_search_index` EMPTY and only the ingest's
      // `search-index` phase fills it. Between the two, a database full of
      // colleges answered "0 colleges match" to everything and warned nobody.
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges CASCADE").use { it.execute() }
        // The other half of the signal: no build row claims a search index either.
        session.prepareStatement("DELETE FROM college_index_build").use { it.execute() }
      }

      val result = tool.execute(buildJsonObject { })

      assertEquals(INDEX_NOT_BUILT, result["error"]?.jsonPrimitive?.content)
      assertNull(result["count"], "an unbuilt index must never answer with a count")
      assertNull(result["total_matches"], "an unbuilt index must never answer with a total")
    }

  @Test
  fun `execute on a DAO failure returns a structured error carrying the failure category`() =
    runBlocking {
      // A seeded college first, so the index is BUILT: an unbuilt index is
      // refused before the query runs, which is a different (and correct)
      // answer than the one this test is about.
      seedWithMarineBiology(960)
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
  fun `definition exposes sort_by as a word enum only`() {
    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject

    val sortWords = (properties["sort_by"]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content }
    assertEquals(
      listOf(
        "enrollment",
        "admission_rate_share",
        // Both price words are OFFERED; only the active ruler's is honoured
        // (RFC 169 D7), and the other is refused with a sentence naming it.
        "in_state_net_price",
        "published_price_on_campus",
        "completion_rate_150pct_4yr_share",
        "name",
      ),
      sortWords,
    )

    // Every attribute filter is a word enum too, drawn from the LOADED
    // reference tables (RFC 150 D54) — never a code, and never a list typed
    // here that the database might not carry.
    for (field in listOf("test_policy", "religious_affiliation", "carnegie_class", "carnegie_size", "athletic_association")) {
      val words = (properties[field]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content }
      assertTrue(words.isNotEmpty(), "[$field] advertises the loaded vocabulary")
      assertTrue(words.none { Regex("^[0-9]+$").matches(it) }, "[$field] offers words, not codes: $words")
    }
    for (field in listOf("has_rotc", "has_study_abroad", "has_housing", "is_active", "is_four_year")) {
      assertEquals("boolean", properties[field]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
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
  fun `credential_level is refused as an unknown field, not silently ignored`() =
    runBlocking {
      // RFC 150 D53 removed it: the census the index derives programs from is
      // bachelor's first majors only, so the filter was a tautology for
      // "bachelors" and a falsehood for anything else. A model that still
      // writes it must be TOLD, not quietly given a wider answer.
      val refused = tool.execute(buildJsonObject { put("credential_level", "bachelors") })
      assertTrue(refused.containsKey("error"))
      assertTrue(
        refused["error"]!!.toString().contains("credential_level"),
        "the rejection names the field: ${refused["error"]}",
      )
      assertNull(
        tool.definition["input_schema"]!!
          .jsonObject["properties"]!!
          .jsonObject["credential_level"],
        "and it is gone from the schema, so nothing advertises it",
      )
    }

  @Test
  fun `a program filter returns every college the census records the code for`() =
    runBlocking {
      seedWithProgram(920, "230101")
      seedWithProgram(921, "230101")

      val page = tool.execute(buildJsonObject { put("cipPrefix", "23") })
      assertNull(page["error"])
      assertEquals(2, (page["colleges"] as JsonArray).size)
      assertEquals(2, page["total_matches"]!!.jsonPrimitive.intOrNull)
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
      insert(newCollege(842).copy(name = "Rural Remote College", locale = 43))

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
      insert(newCollege(845).copy(control = 2))

      val hit = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive("private_nonprofit")) }) })
      assertEquals(1, hit["count"]!!.jsonPrimitive.intOrNull)
      val miss = tool.execute(buildJsonObject { put("control", buildJsonArray { add(JsonPrimitive("public")) }) })
      assertEquals(0, miss["count"]!!.jsonPrimitive.intOrNull)
    }

  @Test
  fun `an unpublished locale code cannot be stored, and an absent one renders as null`() =
    runBlocking {
      // This test used to store locale 14 — inside the old
      // `colleges_locale_range_check` (11..43) and outside the 12 codes IPEDS
      // publishes — and assert the boundary rendered it as null. Migration 0067
      // closed that gap at the source: `colleges.locale` is a foreign key onto
      // `nces_locales`, so the code can no longer reach the database, let alone
      // this boundary. Both halves of the new truth are asserted here.
      val refused =
        database.withConnection { session -> CollegesDao.upsert(session, newCollege(846).copy(locale = 14)) }
      assertTrue(refused.isFailure, "an unpublished locale code must not be storable at all")

      // And the absence that IS reachable — a college whose locale was never
      // reported — is still an explicit null, never a bare number and never a
      // missing key.
      insert(newCollege(846).copy(locale = null))

      val first = ((tool.execute(buildJsonObject {}))["colleges"] as JsonArray).single().jsonObject
      assertTrue(first.containsKey("locale_type"), "the key is present, explicitly null")
      assertEquals(JsonNull, first["locale_type"], "an unknown locale is an absence, never a bare number")
      assertEquals(emptyList(), listViolations(first), "and it still trips no source-code guard")
    }

  @Test
  fun `an empty codebook advertises nothing and refuses every word, both halves alike`() =
    runBlocking {
      // The state of every database before the `codebooks` ingest phase runs.
      // The rule is ONE rule: advertise what is LOADED, and nothing else.
      val bare = CollegeSearchTool(CollegeSearchService(database), Codebook.EMPTY)
      val properties =
        bare.definition["input_schema"]!!
          .jsonObject["properties"]!!
          .jsonObject

      // Not offered AT ALL, rather than offered as a bare string whose every
      // value is then refused: an advertised filter that cannot be used is
      // worse than one the model is never told about.
      for (field in listOf("region", "locale_type", "locale_detail", "subject", "test_policy")) {
        assertNull(properties[field], "[$field] must not be advertised when no word is loaded")
      }
      assertTrue(Codebook.EMPTY.isDegraded, "an empty codebook must not read as healthy")

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

  // ---------------------------------------------------------------------------
  // The honesty figures (RFC 150 D55)
  // ---------------------------------------------------------------------------

  @Test
  fun `the payload carries total_matches, excluded_unknown and source_years`() =
    runBlocking {
      insert(newCollege(950).copy(admissionRateShare = 0.4))
      insert(newCollege(951).copy(admissionRateShare = null))

      val result = tool.execute(buildJsonObject { put("maxAdmissionRateShare", 0.5) })
      assertNull(result["error"])
      assertEquals(1, result["count"]!!.jsonPrimitive.intOrNull)
      assertEquals(1, result["total_matches"]!!.jsonPrimitive.intOrNull)
      // Unknown is never silently "no": the one college that does not report an
      // admission rate is excluded AND counted, under the axis it failed.
      assertEquals(
        mapOf("admission_rate_share" to 1),
        result["excluded_unknown"]!!.jsonObject.mapValues { it.value.jsonPrimitive.intOrNull },
      )
      // No IPEDS row and no census row on this fixture, so the honest answer is
      // no vintages at all rather than an invented one.
      assertEquals(emptyMap(), result["source_years"]!!.jsonObject)
    }

  @Test
  fun `a filter that cannot exclude an unknown reports an empty excluded_unknown`() =
    runBlocking {
      insert(newCollege(952))
      val result = tool.execute(buildJsonObject { put("states", buildJsonArray { add(JsonPrimitive("CA")) }) })
      assertNull(result["error"])
      // `state` is NOT NULL on the index: there is nothing it could exclude, so
      // the key is present and empty rather than absent or invented.
      assertEquals(emptyMap(), result["excluded_unknown"]!!.jsonObject)
    }

  @Test
  fun `a BUILT index over no colleges returns an honest empty page, not an error`() =
    runBlocking {
      // The honest empty case, and the reason the unbuilt check reads a build
      // row as well as the rows: a database whose `colleges` table really is
      // empty has a BUILT index with nothing in it, and that search must answer
      // zero rather than refuse.
      database.withConnection { session ->
        session.prepareStatement("DELETE FROM college_index_build").use { it.execute() }
        CollegesDao
          .insertIndexBuild(
            session,
            NewCollegeIndexBuild(
              startedAt = Instant.now(),
              finishedAt = Instant.now(),
              sources = JsonArray(emptyList()),
              rowsIngested = JsonObject(emptyMap()),
              nameWordsRows = 0,
              searchIndexRows = 0,
              priceFigureRows = null,
              cohortMoneyStatRows = null,
              cohortPopulationCountRows = null,
              aidFormRequirementRows = null,
              canonicalMoneySummary = null,
              changeSummary = JsonObject(emptyMap()),
              methodVersion = 1,
            ),
          ).getOrThrow()
      }

      val result = tool.execute(buildJsonObject {})
      assertNull(result["error"])
      assertEquals(0, result["count"]!!.jsonPrimitive.intOrNull)
      assertEquals(0, result["total_matches"]!!.jsonPrimitive.intOrNull)
      assertEquals(emptyMap(), result["source_years"]!!.jsonObject)
    }

  // ---------------------------------------------------------------------------
  // subject (RFC 150 D54)
  // ---------------------------------------------------------------------------

  @Test
  fun `a subject word is advertised, filters, and an unknown one is a named error`() =
    runBlocking {
      val subjectTool =
        database
          .withConnection { session ->
            CodebooksDao
              .upsertSubject(session, NewSubject("literature", "Literature", listOf("2301")))
              .getOrThrow()
            Unit
          }.let { CollegeSearchTool(CollegeSearchService(database), Codebook.load(database).getOrThrow()) }

      // The taxonomy is inlined as the schema's own enum, so the model can SEE
      // the vocabulary rather than guess a federal code at it.
      val words =
        (
          subjectTool.definition["input_schema"]!!
            .jsonObject["properties"]!!
            .jsonObject["subject"]!!
            .jsonObject["enum"] as JsonArray
        ).map { it.jsonPrimitive.content }
      assertTrue(words.contains("literature"), "the loaded taxonomy is what is advertised: $words")

      seedWithProgram(953, "230101")
      val hit = subjectTool.execute(buildJsonObject { put("subject", "literature") })
      assertNull(hit["error"])
      assertEquals(1, hit["count"]!!.jsonPrimitive.intOrNull)

      val unknown = subjectTool.execute(buildJsonObject { put("subject", "underwater-basket-weaving") })
      assertTrue(unknown.containsKey("error"))
      assertTrue(
        unknown["error"]!!.toString().contains("underwater-basket-weaving"),
        "the refusal echoes the offending word: ${unknown["error"]}",
      )
      assertNull(unknown["count"], "an unresolvable word must not fall through to a search")
    }

  @Test
  fun `a filter with no loaded vocabulary is not advertised, and any word for it is refused`() =
    runBlocking {
      // This database has the published codebooks and NO taxonomy: the state an
      // ingest run without the `subjects` phase leaves.
      assertTrue(codebook.subjectSlugs.isEmpty())
      assertTrue(codebook.isDegraded, "a missing vocabulary must not read as healthy")
      assertTrue(codebook.emptyVocabularies.contains("subject"))

      // Not offered at all -- neither as an enum nor as a bare string, which
      // would invite a word every parse then refuses.
      val properties =
        tool.definition["input_schema"]!!
          .jsonObject["properties"]!!
          .jsonObject
      assertNull(properties["subject"])

      // And a model that writes it anyway is told why, not silently unfiltered.
      val refused = tool.execute(buildJsonObject { put("subject", "literature") })
      assertTrue(refused["error"]!!.toString().contains("not loaded in this database"), "${refused["error"]}")
      assertNull(refused["count"])
    }

  @Test
  fun `an unusable program word is a plain validation error, not a search failure`() =
    runBlocking {
      seedWithMarineBiology(957)

      // "5116" is a well-formed CIP prefix that the loaded vocabulary carries no
      // code under. The database did not fail, so the coach must not read the
      // retryable `search_failed` shape a DB fault gets -- it must read the same
      // plain refusal an unknown `region` word gets.
      val result = tool.execute(buildJsonObject { put("cipPrefix", "5116") })
      val error = result["error"]!!
      assertTrue(error is JsonPrimitive, "an unresolvable word is a validation string, not a fault object: $error")
      assertTrue(error.jsonPrimitive.content.contains("5116"))
      assertNull(result["count"], "a refusal must not fall through to a search")
    }

  private fun seedNamed(
    ipedsUnitId: Int,
    name: String,
  ) = insert(newCollege(ipedsUnitId).copy(name = name))
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
    // The price keys NAME THEIR BASIS since RFC 169: the after-federal-aid
    // blend (in-state at a public school), and the residency-correct published
    // on-campus total under each tuition tier. All three are numbers by
    // contract; none of them is or can become a Scorecard code.
    "in_state_net_price_per_year_usd",
    "published_price_in_state_on_campus_per_year_usd",
    "published_price_out_of_state_on_campus_per_year_usd",
    "completion_rate_150pct_4yr_share",
    "median_earnings_10y_after_entry_usd",
    "median_debt_at_completion_usd",
    "pell_share",
  )

private val QUINTILE_CODE = BareSourceCodeGuard.QUINTILE_CODE

private fun listViolations(payload: JsonElement): List<BareSourceCode> = BareSourceCodeGuard.listViolations(payload, NUMBERS_BY_CONTRACT)

/**
 * The NET ruler, stated once for this suite: `CollegeSearchTool.execute` takes the family's
 * residency with NO default (RFC 169 tier-1 review), because in production
 * "the caller forgot" and "this family has no state on file" must never be the
 * same thing. Most cases here are about the vocabulary rather than the
 * residency, so they call this one-argument form; the ruler cases pass a state
 * explicitly.
 */
private suspend fun CollegeSearchTool.execute(input: kotlinx.serialization.json.JsonObject) = execute(input, null)
