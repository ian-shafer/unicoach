package ed.unicoach.college

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.DEFAULT_UNIVERSE_SENTENCE
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewCollegeProgramsCensus
import ed.unicoach.db.models.NewSubject
import ed.unicoach.db.models.SimilarityAnchor
import ed.unicoach.db.models.SimilarityAxis
import ed.unicoach.db.models.SimilarityQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
 * `similar_colleges` over a REAL Postgres and the REAL committed codebook (RFC
 * 153), in [CollegeSearchToolTest]'s established shape: seed `colleges`,
 * rebuild `college_search_index`, and read the tool's own JSON back.
 *
 * The seeded universe is the acceptance criteria's: a Bowdoin-shaped anchor,
 * two small selective privates, an ASU-shaped large public and a large private.
 * Nothing writes `college_search_index` directly — only the ingest's rebuild
 * does, so a test that forgot it would silently rank an empty index.
 */
class SimilarCollegesToolTest {
  companion object {
    private lateinit var database: Database

    /** The anchor: small, extremely selective, rural, private nonprofit. */
    private const val ANCHOR = 15000

    /** Two schools shaped like it. */
    private const val PEER_A = 15001
    private const val PEER_B = 15002

    /** The large public the bare ask must NOT return. */
    private const val LARGE_PUBLIC = 15003

    /** A large private: same control as the anchor, nothing else like it. */
    private const val LARGE_PRIVATE = 15004

    /** This suite's whole id range, so the fixture cleans up after itself. */
    private const val FIRST_ID = 15000
    private const val LAST_ID = 15999

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
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs, subjects CASCADE").use { it.execute() }
        // `college_ipeds` is keyed by ipeds_unit_id and survives the CASCADE, so
        // this suite deletes ONLY its own id range rather than truncating a
        // table another suite may be seeding.
        session.prepareStatement("DELETE FROM college_ipeds WHERE ipeds_unit_id BETWEEN ? AND ?").use { stmt ->
          stmt.setInt(1, FIRST_ID)
          stmt.setInt(2, LAST_ID)
          stmt.execute()
        }
      }
      Unit
    }

  private val codebook = runBlocking { CodebookFixture.load(database) }

  private val tool = SimilarCollegesTool(CollegeSearchService(database), codebook)

  // ---------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------

  private fun newCollege(
    ipedsUnitId: Int,
    name: String = "Coastal College $ipedsUnitId",
    control: Int = 2,
    state: String = "ME",
    locale: Int? = 43,
    undergradEnrollmentHeadcount: Int? = 2000,
    admissionRateShare: Double? = 0.14,
    satAverageEquivalentScore: Int? = 1420,
    netPricePerYearUsd: Int? = 30000,
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
    city = "Seaside",
    state = state,
    region = 1,
    locale = locale,
    latitude = null,
    longitude = null,
    control = control,
    undergradEnrollmentHeadcount = undergradEnrollmentHeadcount,
    admissionRateShare = admissionRateShare,
    satAverageEquivalentScore = satAverageEquivalentScore,
    costOfAttendancePerYearUsd = null,
    netPricePerYearUsd = netPricePerYearUsd,
    netPricePerYearIncomeQ1Usd = null,
    netPricePerYearIncomeQ2Usd = null,
    netPricePerYearIncomeQ3Usd = null,
    netPricePerYearIncomeQ4Usd = null,
    netPricePerYearIncomeQ5Usd = null,
    tuitionAndFeesInStatePerYearUsd = null,
    tuitionAndFeesOutOfStatePerYearUsd = null,
    completionRate150pct4yrShare = 0.9,
    medianEarnings10yAfterEntryUsd = 55000,
    medianDebtAtCompletionUsd = 21000,
    pellShare = 0.2,
    website = null,
  )

  /**
   * Upserts one college and rebuilds BOTH derived tables. The name words are
   * what the anchor-by-name path matches on and the search index is what the
   * ranking reads; a test that rebuilt neither would resolve nothing and rank
   * nothing.
   */
  private fun insert(input: NewCollege): College =
    runBlocking {
      database.withConnection { session ->
        val college = CollegesDao.upsert(session, input).getOrThrow()
        CollegesDao.rebuildNameWords(session).getOrThrow()
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
        college
      }
    }

  /** A `college_ipeds` row, the only source of `is_active` on the index. */
  private fun insertIpeds(
    ipedsUnitId: Int,
    cyActive: Boolean,
  ) = runBlocking {
    database.withConnection { session ->
      CollegeIpedsDao
        .upsert(
          session,
          NewCollegeIpeds(
            ipedsUnitId = ipedsUnitId,
            surveyYear = 2023,
            cyActive = cyActive,
            deathYear = null,
            closedAt = null,
            newIpedsUnitId = null,
            instLevel = 1,
            ugOffer = true,
            sector = 2,
            carnegieBasic = null,
            carnegieSize = null,
            cbsa = null,
            relAffil = null,
            hasRotc = null,
            hasStudyAbroad = null,
            disabilityBand = null,
            registeredDisabilityPercent = null,
            offersHousing = null,
            housingCapacityHeadcount = null,
            applicationFeeUsd = null,
            athleticAssoc = emptyList(),
            footballConf = null,
            testPolicy = null,
          ),
        ).getOrThrow()
      CollegesDao.rebuildSearchIndex(session).getOrThrow()
    }
  }

  /** The acceptance criteria's universe, anchor first. */
  private fun seedUniverse(): College {
    val anchor =
      insert(
        newCollege(
          ANCHOR,
          name = "Bowdoin College",
          undergradEnrollmentHeadcount = 1800,
          admissionRateShare = 0.09,
          satAverageEquivalentScore = 1450,
          netPricePerYearUsd = 28400,
        ),
      )
    insert(
      newCollege(
        PEER_A,
        name = "Bates College",
        undergradEnrollmentHeadcount = 2000,
        admissionRateShare = 0.14,
        satAverageEquivalentScore = 1420,
        netPricePerYearUsd = 30000,
      ),
    )
    insert(
      newCollege(
        PEER_B,
        name = "Colby College",
        undergradEnrollmentHeadcount = 2200,
        admissionRateShare = 0.10,
        satAverageEquivalentScore = 1440,
        netPricePerYearUsd = 26000,
      ),
    )
    insert(
      newCollege(
        LARGE_PUBLIC,
        name = "Desert State University",
        control = 1,
        locale = 11,
        undergradEnrollmentHeadcount = 65000,
        admissionRateShare = 0.88,
        satAverageEquivalentScore = 1150,
        netPricePerYearUsd = 15000,
      ),
    )
    insert(
      newCollege(
        LARGE_PRIVATE,
        name = "Metro University",
        locale = 11,
        undergradEnrollmentHeadcount = 40000,
        admissionRateShare = 0.80,
        satAverageEquivalentScore = 1100,
        netPricePerYearUsd = 45000,
      ),
    )
    return anchor
  }

  /** The taxonomy row the index expands a program into a subject slug through. */
  private fun seedSubject() =
    runBlocking {
      database.withConnection { session ->
        CodebooksDao.upsertSubject(session, NewSubject("literature", "Literature", listOf("2301"))).getOrThrow()
        CodebooksDao.upsertSubject(session, NewSubject("biology", "Biology", listOf("26"))).getOrThrow()
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
      }
      Unit
    }

  /** One IPEDS-census program for a seeded college, then the rebuild that materialises its subject slugs. */
  private fun seedProgram(
    ipedsUnitId: Int,
    cipCode: String,
  ) = runBlocking {
    database.withConnection { session ->
      val college = checkNotNull(CollegesDao.findByIpedsUnitId(session, ipedsUnitId).getOrThrow())
      CollegeIpedsDao
        .upsertProgramsCensus(session, NewCollegeProgramsCensus(college.id, cipCode, 5, 12, 2023))
        .getOrThrow()
      CollegesDao.rebuildSearchIndex(session).getOrThrow()
    }
    Unit
  }

  private fun anchorInput(anchor: College) = buildJsonObject { put("college_id", anchor.id.value.toString()) }

  private fun namesOf(result: JsonObject): List<String> =
    (result["colleges"] as JsonArray).map { it.jsonObject["name"]!!.jsonPrimitive.content }

  private fun axesUsed(result: JsonObject): List<String> =
    (result["axes_used"] as JsonArray).map { it.jsonObject["axis"]!!.jsonPrimitive.content }

  private fun axesDropped(result: JsonObject): List<JsonObject> = (result["axes_dropped"] as JsonArray).map { it.jsonObject }

  private fun constraints(result: JsonObject): List<String> = (result["constraints_used"] as JsonArray).map { it.jsonPrimitive.content }

  private fun errorKind(result: JsonObject): String? = (result["error"] as? JsonObject)?.get("kind")?.jsonPrimitive?.content

  // ---------------------------------------------------------------------------
  // Definition
  // ---------------------------------------------------------------------------

  @Test
  fun `definition offers the shared vocabulary plus this tool's own fields, none required`() {
    val definition = tool.definition
    assertEquals(SimilarCollegesTool.TOOL_NAME, definition["name"]!!.jsonPrimitive.content)

    val schema = definition["input_schema"]!!.jsonObject
    assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    assertEquals(0, (schema["required"] as JsonArray).size, "nothing is required; the anchor rule is a sentence")

    val properties = schema["properties"]!!.jsonObject
    val own = setOf("college_id", "name", "axes", "weights", "cheaper_than_anchor", "easier_to_admit_than_anchor", "limit")
    assertEquals(emptySet(), own - properties.keys, "every own field must be offered")
    // D69: the SAME filter words `search_colleges` takes -- the vocabulary's own
    // FIELD_NAMES, never a count copied out of it. Only the codebook-backed
    // fields may be absent, because this database carries no value for them.
    assertEquals(
      emptySet(),
      CollegeQueryVocabulary.FIELD_NAMES - codebook.emptyVocabularies - properties.keys,
      "every shared filter word this database carries a vocabulary for must be offered",
    )

    // The axis words are the enum's own, so an axis added there is offerable
    // without a second list here.
    val axisWords = (properties["axes"]!!.jsonObject["items"]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content }
    assertEquals(SimilarityAxis.entries.map { it.word }, axisWords)

    val description = definition["description"]!!.jsonPrimitive.content
    assertTrue(description.contains("RANK AID"), "distance is never a percentage: [$description]")
    assertTrue(description.contains("not a percentage"), "[$description]")
    assertTrue(description.contains("axes_scored"), "[$description]")
  }

  // ---------------------------------------------------------------------------
  // The acceptance criteria, literally
  // ---------------------------------------------------------------------------

  @Test
  fun `the bare ask returns the small selective privates and not the large public`() =
    runBlocking {
      val anchor = seedUniverse()

      val result = tool.execute(anchorInput(anchor))
      assertNull(result["error"], "the bare ask is answerable: $result")

      val names = namesOf(result)
      assertTrue(names.contains("Bates College") && names.contains("Colby College"), "the peers: $names")
      assertFalse(names.contains("Desert State University"), "the large public is not like Bowdoin: $names")
      assertFalse(names.contains("Bowdoin College"), "the anchor is never listed among its own peers: $names")
      assertTrue(
        names.indexOf("Colby College") < names.indexOf("Metro University") &&
          names.indexOf("Bates College") < names.indexOf("Metro University"),
        "the small selective privates rank ahead of the large private: $names",
      )

      // Every response names its axes and its constraints (D70).
      assertEquals(SimilarityAxis.DEFAULTS.map { it.word }, axesUsed(result), "the bare ask's three axes")
      assertTrue(
        constraints(result).any { it.contains("private_nonprofit") },
        "the default same-control constraint is said in words: ${constraints(result)}",
      )
      assertTrue(constraints(result).any { it.contains("four-year") }, "${constraints(result)}")

      val anchorObject = result["anchor"]!!.jsonObject
      assertEquals("Bowdoin College", anchorObject["name"]!!.jsonPrimitive.content)
      assertEquals("private_nonprofit", anchorObject["control"]!!.jsonPrimitive.content, "the word, never a code")
      assertTrue(result["total_candidates"]!!.jsonPrimitive.intOrNull!! >= names.size, "the honest population")
    }

  @Test
  fun `cheaper_than_anchor shifts the set and reports the anchor's own price`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("cheaper_than_anchor", true)
          },
        )
      assertNull(result["error"], "$result")

      val names = namesOf(result)
      assertTrue(names.contains("Colby College"), "the only peer below Bowdoin's net price: $names")
      assertFalse(names.contains("Bates College"), "30000 is not below 28400: $names")
      assertFalse(names.contains("Metro University"), "$names")
      assertTrue(
        constraints(result).any { it.contains("28,400") && it.contains("Bowdoin College") },
        "the expanded constraint says the anchor's own figure: ${constraints(result)}",
      )
      // Every response names its AXES as well as its constraints (D70): a price
      // constraint is not an axis, and drops none.
      assertEquals(SimilarityAxis.DEFAULTS.map { it.word }, axesUsed(result), "a price constraint drops no axis: $result")
    }

  @Test
  fun `a candidate with no net price is excluded from a cheaper ask and counted`() =
    runBlocking {
      val anchor = seedUniverse()
      insert(newCollege(15012, name = "Priceless College", netPricePerYearUsd = null))

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("cheaper_than_anchor", true)
          },
        )
      assertNull(result["error"], "$result")
      // D68: an unreported net price is never kept as "maybe cheaper" -- it is
      // excluded, and the exclusion is COUNTED under the figure's own name.
      assertFalse(namesOf(result).contains("Priceless College"), "unreported is not cheaper: $result")
      assertEquals(
        1,
        result["excluded_unknown"]!!.jsonObject["net_price_per_year_usd"]!!.jsonPrimitive.intOrNull,
        "$result",
      )
    }

  @Test
  fun `easier_to_admit_than_anchor relaxes selectivity and says so`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("easier_to_admit_than_anchor", true)
          },
        )
      assertNull(result["error"], "$result")

      // Every returned school is genuinely easier to get into than the anchor.
      (result["colleges"] as JsonArray).forEach { college ->
        val rate = college.jsonObject["admission_rate_share"]!!.jsonPrimitive.doubleOrNull!!
        assertTrue(rate > 0.09, "every peer must admit more freely than Bowdoin: $college")
      }
      // ...and the axis that would have fought the constraint is DROPPED, named.
      assertFalse(axesUsed(result).contains(SimilarityAxis.SELECTIVITY.word), "${axesUsed(result)}")
      val drop = axesDropped(result).single { it["axis"]!!.jsonPrimitive.content == SimilarityAxis.SELECTIVITY.word }
      assertTrue(drop["reason"]!!.jsonPrimitive.content.contains("easier"), "$drop")
      assertTrue(
        constraints(result).any { it.contains("admission rate above") },
        "${constraints(result)}",
      )
    }

  // ---------------------------------------------------------------------------
  // The anchor (D63/D64)
  // ---------------------------------------------------------------------------

  @Test
  fun `the anchor resolves by id, by exact name and by one keystroke`() =
    runBlocking {
      seedUniverse()

      val byExactName = tool.execute(buildJsonObject { put("name", "Bowdoin College") })
      assertNull(byExactName["error"], "$byExactName")
      assertEquals("Bowdoin College", byExactName["anchor"]!!.jsonObject["name"]!!.jsonPrimitive.content)

      // One keystroke off, the same fuzzy path the picker uses (RFC 146).
      val byTypo = tool.execute(buildJsonObject { put("name", "Bowdon College") })
      assertNull(byTypo["error"], "$byTypo")
      assertEquals("Bowdoin College", byTypo["anchor"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

  @Test
  fun `an ambiguous name refuses and lists the candidates`() =
    runBlocking {
      seedUniverse()
      insert(newCollege(15010, name = "Washington College"))
      insert(newCollege(15011, name = "Washington University"))

      val result = tool.execute(buildJsonObject { put("name", "Washington") })
      assertEquals(SimilarCollegesTool.ANCHOR_AMBIGUOUS, errorKind(result), "$result")
      val candidates = (result["error"] as JsonObject)["candidates"]!!.jsonArray
      assertTrue(candidates.size >= 2, "$candidates")
      candidates.forEach { candidate ->
        assertNotNull(candidate.jsonObject["college_id"], "the coach must be able to call again with an id")
        assertNotNull(candidate.jsonObject["name"])
        assertNotNull(candidate.jsonObject["state"])
      }
    }

  @Test
  fun `a name that matches nothing refuses by naming the string`() =
    runBlocking {
      seedUniverse()

      val result = tool.execute(buildJsonObject { put("name", "Nonesuch Polytechnic") })
      assertTrue(
        result["error"]!!.jsonPrimitive.content.contains("Nonesuch Polytechnic"),
        "the refusal names the string that failed: $result",
      )
    }

  @Test
  fun `an anchor outside the default universe is a named refusal, not an empty page`() =
    runBlocking {
      val anchor = seedUniverse()
      insertIpeds(ANCHOR, cyActive = false)

      val result = tool.execute(anchorInput(anchor))
      assertEquals(SimilarCollegesTool.ANCHOR_NOT_RANKABLE, errorKind(result), "$result")
      val detail = (result["error"] as JsonObject)["detail"]!!.jsonPrimitive.content
      assertTrue(detail.contains("Bowdoin College") && detail.contains("four-year"), "the reason in words: $detail")
      assertNull(result["colleges"], "a refusal is never a page")
    }

  @Test
  fun `write the college_id or the name, never both and never neither`() =
    runBlocking {
      val anchor = seedUniverse()

      val both =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("name", "Bowdoin College")
          },
        )
      assertTrue(both["error"]!!.jsonPrimitive.content.contains("never both"), "$both")

      val neither = tool.execute(buildJsonObject {})
      assertTrue(neither["error"]!!.jsonPrimitive.content.contains("anchor"), "$neither")
    }

  // ---------------------------------------------------------------------------
  // Unknown is not no (D67)
  // ---------------------------------------------------------------------------

  @Test
  fun `an axis the anchor cannot be measured on is dropped for the whole query, with a reason`() =
    runBlocking {
      val anchor =
        insert(
          newCollege(
            ANCHOR,
            name = "Bowdoin College",
            admissionRateShare = null,
            satAverageEquivalentScore = null,
          ),
        )
      insert(newCollege(PEER_A, name = "Bates College"))

      val result = tool.execute(anchorInput(anchor))
      assertNull(result["error"], "$result")
      assertEquals(listOf("size", "setting"), axesUsed(result), "selectivity is gone for everyone")
      val drop = axesDropped(result).single()
      assertEquals(SimilarityAxis.SELECTIVITY.word, drop["axis"]!!.jsonPrimitive.content)
      assertTrue(
        drop["reason"]!!.jsonPrimitive.content.contains("Bowdoin College"),
        "the reason names the anchor and the missing figures: $drop",
      )
    }

  @Test
  fun `a candidate missing an axis is scored on the rest, and says which`() =
    runBlocking {
      val anchor = insert(newCollege(ANCHOR, name = "Bowdoin College"))
      insert(newCollege(PEER_A, name = "Bates College"))
      insert(newCollege(PEER_B, name = "Colby College", undergradEnrollmentHeadcount = null))

      val result = tool.execute(anchorInput(anchor))
      assertNull(result["error"], "$result")

      val colleges = (result["colleges"] as JsonArray).associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }
      val scoredOnAll = (colleges["Bates College"]!!["axes_scored"] as JsonArray).map { it.jsonPrimitive.content }
      val scoredOnSome = (colleges["Colby College"]!!["axes_scored"] as JsonArray).map { it.jsonPrimitive.content }
      assertEquals(SimilarityAxis.DEFAULTS.map { it.word }, scoredOnAll)
      assertEquals(listOf("selectivity", "setting"), scoredOnSome, "no size, and no zero substituted for it")
      // The unjudgeable count is reported under the axis name, exactly as
      // `search_colleges` reports a filter's.
      assertEquals(1, result["excluded_unknown"]!!.jsonObject["size"]!!.jsonPrimitive.intOrNull, "$result")
    }

  @Test
  fun `a candidate sharing no axis with the anchor is excluded and counted`() =
    runBlocking {
      val anchor = insert(newCollege(ANCHOR, name = "Bowdoin College"))
      insert(newCollege(PEER_A, name = "Bates College"))
      insert(
        newCollege(
          PEER_B,
          name = "Silent College",
          locale = null,
          undergradEnrollmentHeadcount = null,
          admissionRateShare = null,
          satAverageEquivalentScore = null,
        ),
      )

      val result = tool.execute(anchorInput(anchor))
      assertNull(result["error"], "$result")
      assertFalse(namesOf(result).contains("Silent College"), "nothing is known about it to rank on")
      assertEquals(1, result["total_candidates"]!!.jsonPrimitive.intOrNull, "only Bates is a candidate")
      val excluded = result["excluded_unknown"]!!.jsonObject
      SimilarityAxis.DEFAULTS.forEach { axis ->
        assertEquals(1, excluded[axis.word]!!.jsonPrimitive.intOrNull, "unjudgeable on ${axis.word}: $excluded")
      }
    }

  // ---------------------------------------------------------------------------
  // Axes and weights (D65/D66)
  // ---------------------------------------------------------------------------

  @Test
  fun `weights change the order`() =
    runBlocking {
      // One school shares the anchor's setting and nothing else; the other
      // shares its size and nothing else. Which one leads is the WEIGHTS'
      // answer, not the tool's.
      val anchor = insert(newCollege(ANCHOR, name = "Bowdoin College", undergradEnrollmentHeadcount = 1800, locale = 43))
      insert(newCollege(PEER_A, name = "Same Setting College", undergradEnrollmentHeadcount = 60000, locale = 43))
      insert(newCollege(PEER_B, name = "Same Size College", undergradEnrollmentHeadcount = 1810, locale = 11))

      val bySize =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") {
              add(JsonPrimitive("size"))
              add(JsonPrimitive("setting"))
            }
            put("weights", buildJsonObject { put("size", 10.0) })
          },
        )
      assertEquals("Same Size College", namesOf(bySize).first(), "$bySize")

      val bySetting =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") {
              add(JsonPrimitive("size"))
              add(JsonPrimitive("setting"))
            }
            put("weights", buildJsonObject { put("setting", 10.0) })
          },
        )
      assertEquals("Same Setting College", namesOf(bySetting).first(), "$bySetting")
    }

  @Test
  fun `a weight outside the range is clamped, and reported clamped`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put(
              "weights",
              buildJsonObject {
                put("size", 1000.0)
                put("setting", 0.0)
              },
            )
          },
        )
      assertNull(result["error"], "$result")
      val weights =
        (result["axes_used"] as JsonArray).associate {
          it.jsonObject["axis"]!!.jsonPrimitive.content to it.jsonObject["weight"]!!.jsonPrimitive.doubleOrNull
        }
      assertEquals(SimilarityAxis.MAX_WEIGHT, weights["size"])
      assertEquals(SimilarityAxis.MIN_WEIGHT, weights["setting"])
      assertEquals(SimilarityAxis.DEFAULT_WEIGHT, weights["selectivity"], "an unweighted axis keeps the default")
    }

  @Test
  fun `an unknown axis word is refused by name`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive("vibes")) }
          },
        )
      val reason = result["error"]!!.jsonPrimitive.content
      assertTrue(reason.contains("vibes"), "the refusal names the word: $reason")
      assertTrue(reason.contains("size"), "and the vocabulary it was refused against: $reason")
    }

  @Test
  fun `an unknown field is refused, and the limit clamps to ten`() =
    runBlocking {
      val anchor = seedUniverse()
      (15020..15035).forEach { id -> insert(newCollege(id, name = "Peer College $id")) }

      val unknownField =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("sort_by", "enrollment")
          },
        )
      assertTrue(unknownField["error"]!!.jsonPrimitive.content.contains("sort_by"), "$unknownField")
      assertEquals(emptyList(), listViolations(unknownField), "the malformed-input error must carry no source code")

      val capped =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("limit", 50)
          },
        )
      assertEquals(SimilarCollegesTool.MAX_LIMIT, namesOf(capped).size, "a peer list is read, not scrolled")
      assertTrue(
        capped["total_candidates"]!!.jsonPrimitive.intOrNull!! > SimilarCollegesTool.MAX_LIMIT,
        "and the honest population is still reported: $capped",
      )
    }

  @Test
  fun `a weight with no number is refused by name, never an opaque failure`() =
    runBlocking {
      val anchor = seedUniverse()

      // `{"size": null}` reads as ABSENT to the shared JSON readers, so the
      // parser used to `!!` it and throw out of a tool documented as total:
      // the coach got the opaque tool-failure marker with no field named, and
      // nothing it could rewrite.
      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            put("weights", buildJsonObject { put("size", JsonNull) })
          },
        )
      val reason = result["error"]!!.jsonPrimitive.content
      assertTrue(reason.contains("weights") && reason.contains("size"), "the refusal names the field: $reason")
      assertEquals(emptyList(), listViolations(result), "and carries no source code")
    }

  // ---------------------------------------------------------------------------
  // The response is the query, in words (D70)
  // ---------------------------------------------------------------------------

  @Test
  fun `constraints_used names the vocabulary filters the SQL actually applied`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("states") { add(JsonPrimitive("ME")) }
            put("maxNetPricePerYearUsd", 40000)
          },
        )
      assertNull(result["error"], "$result")

      val said = constraints(result)
      // D70's acceptance criterion is "every response names its axes and
      // constraints" -- a filter applied in SQL and unsaid is that criterion
      // failing, because the coach then states a narrower answer than it got.
      assertTrue(said.any { it.contains("ME") }, "the states filter is said: $said")
      // The money vocabulary a coach reads aloud: grouped, never the bare
      // `$40000` a `$$it` interpolation produced.
      assertTrue(said.any { it.contains("40,000") }, "and so is the price ceiling: $said")
      assertTrue(said.any { it.contains(DEFAULT_UNIVERSE_SENTENCE) }, "the universe, in its own words: $said")
      assertFalse(namesOf(result).contains("Metro University"), "45000 is above the ceiling: $result")
    }

  @Test
  fun `constraints_used reports the CALLER's control as the caller's, not as the anchor's`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("control") { add(JsonPrimitive("public")) }
          },
        )
      assertNull(result["error"], "$result")

      val said = constraints(result)
      assertTrue(
        said.any { it.contains("public") && it.contains("what you asked for") },
        "the caller asked for public against a private anchor: $said",
      )
      assertFalse(
        said.any { it.contains("the same way as the anchor") },
        "which is exactly what the peers are NOT run: $said",
      )
      // ...and the default path still says the anchor's own control as the
      // anchor's, so the honest version did not simply delete the sentence.
      val bare = tool.execute(anchorInput(anchor))
      assertTrue(
        constraints(bare).any { it.contains("the same way as the anchor") && it.contains("private_nonprofit") },
        "${constraints(bare)}",
      )
    }

  @Test
  fun `an anchor whose control this vocabulary does not define claims no same-control constraint`() {
    // `InstitutionControl.labelFor` is TOTAL: an extended source code renders as
    // `unknown (control [N])`, which names no control, so the D65 default
    // constraint cannot be built and the SQL never applies it. The response used
    // to claim it anyway.
    //
    // Called directly because `college_search_index.control` carries a CHECK
    // over this vocabulary's own labels: the state is unreachable through a
    // seeded database, which is why it went unnoticed rather than why it is
    // acceptable.
    val anchor =
      SimilarityAnchor(
        id = CollegeId(java.util.UUID.randomUUID()),
        name = "Extended College",
        state = "ME",
        control = null,
        controlLabel = InstitutionControl.unknownLabel(4),
        locale = "rural-fringe",
        subjectSlugs = null,
        netPricePerYearUsd = null,
        admissionRateShare = null,
        sizePercentile = 0.5,
        selectivityPercentile = null,
        pricePercentile = null,
        inDefaultUniverse = true,
      )
    val query =
      SimilarityQuery(
        anchor = anchor,
        axes = mapOf(checkNotNull(anchor.anchoredOn(SimilarityAxis.SIZE)) to 1.0),
        filters = CollegeQuery(limit = 5),
      )

    val sentence = tool.controlSentence(query)
    assertTrue(sentence.contains("no same-way-of-running constraint"), "the constraint is not claimed: $sentence")
    assertTrue(sentence.contains("Extended College"), "and the anchor is named: $sentence")
    // The offending LABEL is what an operator needs to fix the CHECK or the
    // enum, and it was the one value this sentence used to withhold.
    assertTrue(sentence.contains(InstitutionControl.unknownLabel(4)), "with the label that failed to resolve: $sentence")
  }

  @Test
  fun `an unanswerable anchor-relative flag is refused as itself, not as a dropped axis`() =
    runBlocking {
      val anchor =
        insert(
          newCollege(
            ANCHOR,
            name = "Bowdoin College",
            admissionRateShare = null,
            satAverageEquivalentScore = null,
          ),
        )
      insert(newCollege(PEER_A, name = "Bates College"))

      // The drop this flag causes was computed BEFORE the flag itself was
      // validated, so the caller was told selectivity could not be ranked on --
      // true, and not the reason -- and had to retry to learn that the
      // constraint had no figure to compare against at all.
      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.SELECTIVITY.word)) }
            put("easier_to_admit_than_anchor", true)
          },
        )
      val reason = result["error"]!!.jsonPrimitive.content
      assertTrue(reason.contains("easier_to_admit_than_anchor"), "the flag is named: $reason")
      assertTrue(reason.contains("Bowdoin College"), "with the anchor that cannot answer it: $reason")
      assertNull(errorKind(result), "and it is not reported as the no-axis-left refusal: $result")
    }

  @Test
  fun `excluded_unknown counts the colleges the caller's own constraints admit`() =
    runBlocking {
      val anchor = seedUniverse()
      // Three schools nobody asked about, unjudgeable on size. Counted over the
      // BARE universe they inflated `excluded_unknown` past `total_candidates`
      // -- and the prompt tells the coach to read both numbers aloud.
      (15040..15042).forEach { id ->
        insert(newCollege(id, name = "Empire College $id", state = "NY", undergradEnrollmentHeadcount = null))
      }

      val unfiltered = tool.execute(anchorInput(anchor))
      assertNull(unfiltered["error"], "$unfiltered")
      assertEquals(
        3,
        unfiltered["excluded_unknown"]!!.jsonObject["size"]!!.jsonPrimitive.intOrNull,
        "unconstrained, all three are candidates that could not be judged: $unfiltered",
      )

      val inMaine =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("states") { add(JsonPrimitive("ME")) }
          },
        )
      assertNull(inMaine["error"], "$inMaine")
      assertEquals(
        0,
        inMaine["excluded_unknown"]!!.jsonObject["size"]!!.jsonPrimitive.intOrNull,
        "a school the states filter excluded is not an unjudgeable candidate: $inMaine",
      )
      val considered = inMaine["total_candidates"]!!.jsonPrimitive.intOrNull!!
      inMaine["excluded_unknown"]!!.jsonObject.forEach { (axis, count) ->
        assertTrue(
          count.jsonPrimitive.intOrNull!! <= considered,
          "[$axis] cannot exceed the $considered candidates it is drawn from: $inMaine",
        )
      }
    }

  // ---------------------------------------------------------------------------
  // The source-code guard (RFC 143)
  // ---------------------------------------------------------------------------

  @Test
  fun `no bare source code reaches a result or a refusal`() =
    runBlocking {
      val anchor = seedUniverse()
      // Every axis at once, so the payload actually carries every key the
      // allowlist sanctions -- including the two categorical axes, whose SQL
      // (an equality test and a Jaccard distance over the slug arrays) is only
      // executed when they are asked for.
      seedSubject()
      seedProgram(ANCHOR, "230101")
      seedProgram(PEER_A, "230101")
      seedProgram(PEER_B, "231303")
      // ...and one IPEDS row, so BOTH vintages the payload can report are
      // present and the year keys are exercised too.
      insertIpeds(PEER_A, cyActive = true)

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") {
              SimilarityAxis.entries.forEach { axis -> add(JsonPrimitive(axis.word)) }
            }
          },
        )
      assertNull(result["error"], "all five axes are rankable here: $result")
      assertEquals(SimilarityAxis.entries.map { it.word }, axesUsed(result), "$result")
      assertEquals(emptyList(), listViolations(result), "the peer list must carry no source code")

      val rendered = BareSourceCodeGuard.listNumericFields(result).toSet()
      assertEquals(emptySet(), NUMBERS_BY_CONTRACT - rendered, "every field the allowlist sanctions must be in the payload")

      val ambiguous = tool.execute(buildJsonObject { put("name", "College") })
      // Pinned to the KIND, so the guard cannot go vacuous the day "College"
      // resolves to one school or to none.
      assertEquals(SimilarCollegesTool.ANCHOR_AMBIGUOUS, errorKind(ambiguous), "$ambiguous")
      assertEquals(emptyList(), listViolations(ambiguous), "nor the ambiguity refusal")

      val notFound = tool.execute(buildJsonObject { put("name", "Nonesuch Polytechnic") })
      assertEquals(emptyList(), listViolations(notFound), "nor the no-match refusal")

      // The no-axis-left refusal (D64's sibling kind): every requested axis was
      // dropped, and its detail is a GENERATED sentence about the anchor -- the
      // riskiest string in the tool, so it is guarded too.
      val noAxis =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.SELECTIVITY.word)) }
            put("easier_to_admit_than_anchor", true)
          },
        )
      assertEquals(SimilarCollegesTool.NO_RANKABLE_AXIS, errorKind(noAxis), "$noAxis")
      assertEquals(emptyList(), listViolations(noAxis), "nor the no-axis-left refusal")

      // ...and D64's own, which needs the anchor OUT of the default universe:
      // done last, because it changes what every other call above would see.
      insertIpeds(ANCHOR, cyActive = false)
      val notRankable = tool.execute(anchorInput(anchor))
      assertEquals(SimilarCollegesTool.ANCHOR_NOT_RANKABLE, errorKind(notRankable), "$notRankable")
      assertEquals(emptyList(), listViolations(notRankable), "nor the not-rankable refusal")

      val description = tool.definition["description"]!!.jsonPrimitive.content
      assertNull(QUINTILE_CODE.find(description), "nor the description the model reads first: [$description]")
      assertFalse(description.contains("NPT4"), "[$description]")

      // Positive control: the guard must still react.
      val doctored = JsonObject(result + mapOf("control" to JsonPrimitive(2)))
      assertEquals(listOf(BareSourceCode.BareNumberField("control")), listViolations(doctored))
    }

  @Test
  fun `the subjects axis scores the overlap in fields of study`() =
    runBlocking {
      val anchor = seedUniverse()
      seedSubject()
      seedProgram(ANCHOR, "230101")
      // Same subject as the anchor; the large private shares none.
      seedProgram(PEER_A, "230101")
      seedProgram(LARGE_PRIVATE, "260101")

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.SUBJECTS.word)) }
          },
        )
      assertNull(result["error"], "$result")
      assertEquals("Bates College", namesOf(result).first(), "the school offering the same subject: $result")
      // A college whose programs are unreported is unjudgeable on this axis --
      // never scored as offering nothing.
      assertTrue(
        result["excluded_unknown"]!!.jsonObject[SimilarityAxis.SUBJECTS.word]!!.jsonPrimitive.intOrNull!! > 0,
        "$result",
      )
    }

  // ---------------------------------------------------------------------------
  // Tier 2: the states a wrong answer used to be given for
  // ---------------------------------------------------------------------------

  @Test
  fun `an unbuilt index refuses the anchor by id, and never says no college has it`() =
    runBlocking {
      // `resetDatabase` leaves `colleges` empty, so the rebuild writes no index
      // row; the build-provenance row is deleted so the state is this test's
      // own and not another suite's leftovers. The id path used to read that
      // state as "this database holds no such college" -- the false zero RFC
      // 150 forbids, and the one the NAME path already refuses.
      database.withConnection { session ->
        session.prepareStatement("DELETE FROM college_index_build").use { it.execute() }
        CollegesDao.rebuildSearchIndex(session).getOrThrow()
      }

      val result =
        tool.execute(
          buildJsonObject {
            put(
              "college_id",
              java.util.UUID
                .randomUUID()
                .toString(),
            )
          },
        )
      val reason = result["error"]!!.jsonPrimitive.content
      assertTrue(reason.contains("search index has not been built"), "the deployment state is named: $reason")
      assertFalse(reason.contains("no college has"), "and never stated as a fact about the database: $reason")
    }

  @Test
  fun `net price is reported once, and an axis arm drops the filter over its own column`() =
    runBlocking {
      val anchor = seedUniverse()
      // Private, so the D65 default control constraint admits it, and priceless:
      // the `cheaper_than_anchor` filter removes it, and the `price` axis cannot
      // judge it. It is the college the axis arm exists to count.
      insert(newCollege(15050, name = "Priceless College", netPricePerYearUsd = null))

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.PRICE.word)) }
            put("cheaper_than_anchor", true)
          },
        )
      assertNull(result["error"], "$result")

      val excluded = result["excluded_unknown"]!!.jsonObject
      // ONE key for one fact: the axis WORD the caller asked in, never the same
      // column reported a second time under its schema identifier.
      assertNull(excluded["net_price_per_year_usd"], "net price is not reported twice, in two key styles: $excluded")
      assertEquals(
        1,
        excluded[SimilarityAxis.PRICE.word]!!.jsonPrimitive.intOrNull,
        "the arm drops the very filter that hid its unknowns, so the count is not 0 by construction: $excluded",
      )
      assertFalse(namesOf(result).contains("Priceless College"), "it is excluded and counted, never maybe-cheaper")
    }

  @Test
  fun `programs that name no taxonomy subject are unjudgeable at BOTH ends of the comparison`() =
    runBlocking {
      val anchor = insert(newCollege(ANCHOR, name = "Bowdoin College"))
      insert(newCollege(PEER_A, name = "Bates College"))
      val noSubject = insert(newCollege(PEER_B, name = "Trade College"))
      seedSubject()
      seedProgram(ANCHOR, "230101")
      seedProgram(PEER_A, "230101")
      // Programs REPORTED, none of them in the taxonomy: `subject_slugs = '{}'`,
      // which schema 0064 states is a different fact from NULL.
      seedProgram(PEER_B, "450101")

      val asCandidate =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.SUBJECTS.word)) }
          },
        )
      assertNull(asCandidate["error"], "$asCandidate")
      assertEquals(listOf("Bates College"), namesOf(asCandidate), "the `{}` college is not judged: $asCandidate")
      assertEquals(
        1,
        asCandidate["excluded_unknown"]!!.jsonObject[SimilarityAxis.SUBJECTS.word]!!.jsonPrimitive.intOrNull,
        "it is counted as unjudgeable, not charged a distance of 1: $asCandidate",
      )

      // The SAME college as the anchor: the same state, so the same treatment --
      // and the reason says which state it actually is.
      val asAnchor =
        tool.execute(
          buildJsonObject {
            put("college_id", noSubject.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.SUBJECTS.word)) }
          },
        )
      assertEquals(SimilarCollegesTool.NO_RANKABLE_AXIS, errorKind(asAnchor), "$asAnchor")
      val detail = asAnchor["error"]!!.jsonObject["detail"]!!.jsonPrimitive.content
      assertTrue(detail.contains("no subject in the taxonomy"), "the real reason: $detail")
      assertFalse(detail.contains("reports no programs"), "which is NOT that it reports no programs: $detail")
    }

  @Test
  fun `the no-axis-left refusal carries the drops as rows, not as a sentence to re-parse`() =
    runBlocking {
      val anchor = seedUniverse()

      val result =
        tool.execute(
          buildJsonObject {
            put("college_id", anchor.id.value.toString())
            putJsonArray("axes") { add(JsonPrimitive(SimilarityAxis.SELECTIVITY.word)) }
            put("easier_to_admit_than_anchor", true)
          },
        )
      assertEquals(SimilarCollegesTool.NO_RANKABLE_AXIS, errorKind(result), "$result")

      // The SAME `{axis, reason}` shape a successful response reports: the drops
      // are the whole answer here, so they must not be less readable than they
      // are when they are a footnote.
      val dropped = (result["error"]!!.jsonObject["axes_dropped"] as JsonArray).map { it.jsonObject }
      assertEquals(
        listOf(SimilarityAxis.SELECTIVITY.word),
        dropped.map { it["axis"]!!.jsonPrimitive.content },
        "$result",
      )
      assertTrue(
        dropped
          .single()["reason"]!!
          .jsonPrimitive.content
          .contains("easier to get into"),
        "with the reason beside it: $dropped",
      )
      assertEquals(emptyList(), listViolations(result), "and still no bare source code")
    }

  @Test
  fun `an input outside the stated domain is refused by name, never repaired`() =
    runBlocking {
      val anchor = seedUniverse()

      fun refusalFor(build: JsonObjectBuilder.() -> Unit): String =
        runBlocking {
          val result =
            tool.execute(
              buildJsonObject {
                put("college_id", anchor.id.value.toString())
                build()
              },
            )
          assertNotNull(result["error"], "$result")
          result["error"]!!.jsonPrimitive.content
        }

      // A NEGATIVE weight is the OPPOSITE instruction, not a small ratio: it was
      // coerced to the minimum and then reported back in `axes_used` as fact.
      val negative = refusalFor { put("weights", buildJsonObject { put("size", -5.0) }) }
      assertTrue(negative.contains("weights") && negative.contains("size"), "the field: $negative")
      assertTrue(negative.contains("negative"), "and what is wrong with it: $negative")

      // `{}` is the same caller mistake `[axes]: []` is refused for.
      val emptyWeights = refusalFor { put("weights", buildJsonObject { }) }
      assertTrue(emptyWeights.contains("weights"), "$emptyWeights")
      assertTrue(emptyWeights.contains("at least one axis"), "$emptyWeights")

      val repeated =
        refusalFor {
          putJsonArray("axes") {
            add(JsonPrimitive(SimilarityAxis.SIZE.word))
            add(JsonPrimitive(SimilarityAxis.SIZE.word))
          }
        }
      assertTrue(repeated.contains("more than once"), "a repeat is a mis-generated array, not a narrower ask: $repeated")

      // "Return nothing" used to be answered with ONE college.
      val zeroLimit = refusalFor { put("limit", 0) }
      assertTrue(zeroLimit.contains("limit") && zeroLimit.contains("0"), "$zeroLimit")

      // A blank name satisfied "exactly one of the two" and was then answered
      // with a false statement about the database.
      val blank = tool.execute(buildJsonObject { put("name", "   ") })
      val blankReason = blank["error"]!!.jsonPrimitive.content
      assertTrue(blankReason.contains("[name]"), "$blankReason")
      assertFalse(blankReason.contains("no college matches"), "the database is not what was wrong: $blankReason")
    }

  @Test
  fun `a caller's multi-control filter against an unresolvable anchor label never renders null`() {
    // Two nulls compared equal: `control.singleOrNull()` and the anchor's
    // unresolved control were both null, so the same-control arm fired and the
    // sentence printed the literal `null` in place of the controls the SQL had
    // actually applied.
    val anchor =
      SimilarityAnchor(
        id = CollegeId(java.util.UUID.randomUUID()),
        name = "Extended College",
        state = "ME",
        control = null,
        controlLabel = InstitutionControl.unknownLabel(4),
        locale = "rural-fringe",
        subjectSlugs = null,
        netPricePerYearUsd = null,
        admissionRateShare = null,
        sizePercentile = 0.5,
        selectivityPercentile = null,
        pricePercentile = null,
        inDefaultUniverse = true,
      )
    val query =
      SimilarityQuery(
        anchor = anchor,
        axes = mapOf(checkNotNull(anchor.anchoredOn(SimilarityAxis.SIZE)) to 1.0),
        filters =
          CollegeQuery(
            limit = 5,
            control = listOf(InstitutionControl.PUBLIC, InstitutionControl.PRIVATE_NONPROFIT),
          ),
      )

    val sentence = tool.controlSentence(query)
    assertFalse(sentence.contains("null"), "the constraint that ran is named, never a null: $sentence")
    assertTrue(sentence.contains("public") && sentence.contains("private_nonprofit"), "$sentence")
    assertTrue(sentence.contains(InstitutionControl.unknownLabel(4)), "and the anchor's own stored label: $sentence")
  }
}

// ---------------------------------------------------------------------------
// The generalised source-code guard (RFC 143), hosted in :chat's test fixtures
// since RFC 148 D9 -- the walker is shared, the allowlist stays this tool's own.
// ---------------------------------------------------------------------------

/**
 * The field names whose value is a NUMBER by contract for `similar_colleges`:
 * the measures the shared result row reports, plus this tool's own three —
 * `distance` (a rank aid), `weight` (a ratio the caller set) and
 * `total_candidates` (a count of colleges). The `excluded_unknown` keys are the
 * AXIS WORDS, and their values are counts of schools that could not be judged;
 * they are numbers by contract for the same reason `total_candidates` is, and
 * can never become a source's own code.
 */
private val NUMBERS_BY_CONTRACT =
  setOf(
    "distance",
    "weight",
    "total_candidates",
    "undergrad_enrollment_headcount",
    "admission_rate_share",
    "net_price_per_year_usd",
    "completion_rate_150pct_4yr_share",
    "median_earnings_10y_after_entry_usd",
    "median_debt_at_completion_usd",
    "pell_share",
    // `source_years`: an academic year is a fact about the figures, and the one
    // number in the payload that is a YEAR rather than a measure.
    "ipeds",
    "programs_census",
  ) + SimilarityAxis.entries.map { it.word }

private val QUINTILE_CODE = BareSourceCodeGuard.QUINTILE_CODE

private fun listViolations(payload: JsonElement): List<BareSourceCode> = BareSourceCodeGuard.listViolations(payload, NUMBERS_BY_CONTRACT)
