package ed.unicoach.college

import ed.unicoach.db.dao.CodeColumn
import ed.unicoach.db.dao.CodebookTable
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpeds
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RFC 147 codebook load, exercised against the REAL committed
 * `db/data/codebooks.json` rather than a miniature fixture: the file is
 * generated repo data that ships with the code, so the row counts asserted here
 * are the ones a production ingest writes, and a regeneration that changes them
 * has to change this test too.
 *
 * The failure paths (a sentinel published as a code, a referenced row being
 * dropped, digest drift) are exercised by MUTATING a copy of that same file, so
 * each one is a one-line difference from a codebook that is known to load.
 */
class CodebookLoaderTest : CollegeScorecardTestBase() {
  private val loader = CodebookLoader(database)

  /**
   * The committed codebook, found by walking up from the test's working
   * directory (the module dir under Gradle) rather than assuming a fixed depth
   * — the [CdsSeedLoaderTest] precedent.
   */
  private val committedCodebook: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/data/codebooks.json") }
      .first { it.isFile }

  /**
   * The per-domain row counts of the committed file (`bin/fetch-codebooks`
   * against the 2023 artifacts). Spelled out rather than derived from the file:
   * a generator change that silently halves a domain must fail here, which a
   * self-referential count could not do.
   */
  private val expectedRows =
    mapOf(
      "ipeds_region" to 10,
      "us_states" to 59,
      "nces_locale" to 12,
      "carnegie_2021_basic" to 34,
      "carnegie_2021_size_setting" to 19,
      "religious_affiliation" to 63,
      "athletic_association" to 6,
      "football_conference" to 99,
      "admission_test_policy" to 3,
      "cip_code" to 1710,
    )

  private fun source(file: File) = SourceFile(file, file.path)

  /** The committed codebook as mutable JSON, for the one-line-difference cases. */
  private fun codebookJson(): MutableMap<String, JsonElement> =
    (Json.parseToJsonElement(committedCodebook.readText()) as JsonObject).toMutableMap()

  private fun writeCodebook(domains: Map<String, JsonElement>): SourceFile {
    val file = File.createTempFile("codebooks", ".json")
    file.deleteOnExit()
    file.writeText(JsonObject(domains).toString())
    return source(file)
  }

  /** One domain of the committed file, as a mutable object. */
  private fun domain(
    json: Map<String, JsonElement>,
    key: String,
  ): MutableMap<String, JsonElement> = (json.getValue(key) as JsonObject).toMutableMap()

  private fun codes(
    json: Map<String, JsonElement>,
    key: String,
  ): List<JsonObject> = (domain(json, key).getValue("codes") as JsonArray).map { it as JsonObject }

  private fun withCodes(
    json: MutableMap<String, JsonElement>,
    key: String,
    codes: List<JsonElement>,
  ) {
    val replaced = domain(json, key)
    replaced["codes"] = JsonArray(codes)
    json[key] = JsonObject(replaced)
  }

  private fun seedCollege(
    ipedsUnitId: Int,
    state: String = "CA",
    region: Int? = null,
    locale: Int? = null,
  ) = withSession { session ->
    CollegesDao
      .upsert(
        session,
        NewCollege(
          ipedsUnitId = ipedsUnitId,
          opeid = null,
          name = "Test U $ipedsUnitId",
          city = "Townsville",
          state = state,
          region = region,
          locale = locale,
          latitude = null,
          longitude = null,
          control = 1,
          undergradEnrollmentHeadcount = null,
          admissionRateShare = null,
          satAverageEquivalentScore = null,
          costOfAttendancePerYearUsd = null,
          netPricePerYearUsd = null,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = null,
          tuitionAndFeesOutOfStatePerYearUsd = null,
          completionRate150pct4yrShare = null,
          medianEarnings10yAfterEntryUsd = null,
          medianDebtAtCompletionUsd = null,
          pellShare = null,
          website = null,
        ),
      ).getOrThrow()
  }

  private fun seedIpeds(
    ipedsUnitId: Int,
    relAffil: Int? = null,
    footballConf: Int? = null,
    athleticAssoc: List<Int> = emptyList(),
  ) = withSession { session ->
    CollegeIpedsDao
      .upsert(
        session,
        NewCollegeIpeds(
          ipedsUnitId = ipedsUnitId,
          surveyYear = 2023,
          cyActive = true,
          deathYear = null,
          closedAt = null,
          newIpedsUnitId = null,
          instLevel = null,
          ugOffer = null,
          sector = null,
          carnegieBasic = null,
          carnegieSize = null,
          cbsa = null,
          relAffil = relAffil,
          hasRotc = null,
          hasStudyAbroad = null,
          disabilityBand = null,
          registeredDisabilityPercent = null,
          offersHousing = null,
          housingCapacityHeadcount = null,
          applicationFeeUsd = null,
          athleticAssoc = athleticAssoc,
          footballConf = footballConf,
          testPolicy = null,
        ),
      ).getOrThrow()
  }

  // ---------------------------------------------------------------------------
  // The happy path
  // ---------------------------------------------------------------------------

  @Test
  fun `every domain of the committed codebook loads with its published row count`() =
    runBlocking {
      val result = loader.load(source(committedCodebook))

      assertEquals(expectedRows.keys, result.domains.map { it.domain }.toSet())
      for (summary in result.domains) {
        assertEquals(expectedRows.getValue(summary.domain), summary.rows, "rows for ${summary.domain}")
        assertEquals(summary.rows, summary.inserted, "every row is an insert on a first load: ${summary.domain}")
        assertEquals(0, summary.changed)
        assertEquals(0, summary.deleted)
      }
      assertEquals(expectedRows.values.sum(), result.totalRows)

      // Spot-check the parsed structure the whole slice exists for: the label is
      // stored verbatim, the parsed columns beside it.
      val locale =
        withSession { session ->
          session.prepareStatement("SELECT type, detail, name, label_raw FROM nces_locales WHERE code = 41").use { stmt ->
            stmt.executeQuery().use { rs ->
              rs.next()
              listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))
            }
          }
        }
      assertEquals(listOf("rural", "fringe", "Rural: Fringe", "Rural: Fringe"), locale)

      // The FK half: every state points at a real region.
      val states = withSession { CodebooksDao.storedRows(it, CodebookTable.US_STATES).getOrThrow() }
      assertEquals(59, states.size)
      assertTrue(states.any { it.key == "CA" })
    }

  @Test
  fun `a first load reports every domain as new provenance, a re-load reports no drift and no change`() =
    runBlocking {
      val first = loader.load(source(committedCodebook))
      // A domain loaded for the first time is reported with no stored digest —
      // stated, not silently treated as "unchanged".
      assertEquals(expectedRows.size, first.drift.size)
      assertTrue(first.drift.all { it.stored == null })

      val second = loader.load(source(committedCodebook))
      assertEquals(emptyList(), second.drift)
      for (summary in second.domains) {
        assertEquals(expectedRows.getValue(summary.domain), summary.unchanged, "unchanged for ${summary.domain}")
        assertEquals(0, summary.inserted)
        assertEquals(0, summary.changed)
        assertEquals(0, summary.deleted)
      }
    }

  @Test
  fun `a changed source digest is reported as drift, loudly, without failing the load`() =
    runBlocking {
      loader.load(source(committedCodebook))

      val json = codebookJson()
      val region = domain(json, "ipeds_region")
      region["source_sha256"] = JsonPrimitive("0".repeat(64))
      json["ipeds_region"] = JsonObject(region)
      val result = loader.load(writeCodebook(json))

      assertEquals(1, result.drift.size)
      val drift = result.drift.single()
      assertEquals("ipeds_region", drift.domain)
      assertEquals("0".repeat(64), drift.incoming)
      assertNotNull(drift.stored)
      assertTrue(result.render().contains("SOURCE DIGEST DRIFT"))
      // The rows still loaded: drift is a report, not a refusal.
      assertEquals(10, result.domains.single { it.domain == "ipeds_region" }.rows)
    }

  // ---------------------------------------------------------------------------
  // The refusals
  // ---------------------------------------------------------------------------

  @Test
  fun `a declared null sentinel published as a code is fatal (D41)`() =
    runBlocking {
      val json = codebookJson()
      val sentinelRow =
        buildJsonObject {
          put("code", JsonPrimitive(-1))
          put("slug", JsonPrimitive("not-reported"))
          put("name", JsonPrimitive("Not reported"))
          put("label_raw", JsonPrimitive("Not reported"))
        }
      withCodes(json, "religious_affiliation", codes(json, "religious_affiliation") + sentinelRow)
      val bad = writeCodebook(json)

      val failure = assertFailsWith<CodebookLoader.SentinelAsCodeException> { loader.load(bad) }
      assertEquals("religious_affiliation", failure.domain)
      assertEquals(listOf(-1), failure.sentinels)
      // Nothing was written: the parse fails before the transaction opens.
      assertEquals(0, withSession { count(it, "religious_affiliations") })
    }

  @Test
  fun `dropping a row that stored data still references is refused, and nothing is deleted`() =
    runBlocking {
      loader.load(source(committedCodebook))
      seedCollege(100001)
      // 71 = Roman Catholic, the code college_ipeds.rel_affil stores.
      seedIpeds(100001, relAffil = 71)

      val json = codebookJson()
      val kept = codes(json, "religious_affiliation").filter { (it.getValue("code") as JsonPrimitive).content != "71" }
      assertEquals(62, kept.size)
      withCodes(json, "religious_affiliation", kept)

      val failure =
        assertFailsWith<CodebookLoader.ReferencedCodebookRowException> { loader.load(writeCodebook(json)) }
      assertEquals("religious_affiliation", failure.domain)
      assertEquals(71, failure.code)
      assertTrue(failure.references.single().startsWith("college_ipeds.rel_affil"))
      assertTrue(failure.references.single().contains("1 row"))
      // The whole load rolled back: the dropped row is still there, and so are
      // the other 62.
      assertEquals(63, withSession { count(it, "religious_affiliations") })
    }

  @Test
  fun `dropping an unreferenced row deletes exactly it`() =
    runBlocking {
      loader.load(source(committedCodebook))

      val json = codebookJson()
      val kept = codes(json, "religious_affiliation").filter { (it.getValue("code") as JsonPrimitive).content != "71" }
      withCodes(json, "religious_affiliation", kept)

      val result = loader.load(writeCodebook(json))
      val summary = result.domains.single { it.domain == "religious_affiliation" }
      assertEquals(1, summary.deleted)
      assertEquals(62, summary.rows)
      assertEquals(62, withSession { count(it, "religious_affiliations") })
    }

  @Test
  fun `a malformed codebook is fatal before any write`() =
    runBlocking {
      val file = File.createTempFile("codebooks-bad", ".json")
      file.deleteOnExit()
      file.writeText("""{"not_a_domain": {}}""")

      val failure = assertFailsWith<CodebookLoader.InvalidCodebookFileException> { loader.load(source(file)) }
      assertTrue(failure.detail.contains("not_a_domain"), failure.detail)
      assertEquals(0, withSession { count(it, "codebook_sources") })
    }

  @Test
  fun `a region membership disagreeing with us_states is fatal`() =
    runBlocking {
      val json = codebookJson()
      val states =
        codes(json, "us_states").map { state ->
          if ((state.getValue("code") as JsonPrimitive).content != "CA") {
            state
          } else {
            JsonObject(state.toMutableMap().apply { put("ipeds_region", JsonPrimitive("new-england")) })
          }
        }
      withCodes(json, "us_states", states)

      val failure =
        assertFailsWith<CodebookLoader.InvalidCodebookFileException> { loader.load(writeCodebook(json)) }
      assertTrue(failure.detail.contains("[CA]"), failure.detail)
    }

  // ---------------------------------------------------------------------------
  // D46 — the unknown-code report
  // ---------------------------------------------------------------------------

  @Test
  fun `the unknown-code report counts every stored code with no codebook row, per column`() =
    runBlocking {
      // A codebook missing one football conference and five of the six athletic
      // associations, so the stored codes below have nothing to resolve against.
      // Built by REMOVING rows rather than by inventing codes, so the report is
      // measured against real published values.
      val json = codebookJson()
      withCodes(
        json,
        "football_conference",
        codes(json, "football_conference").filter { (it.getValue("code") as JsonPrimitive).content != "102" },
      )
      withCodes(
        json,
        "athletic_association",
        codes(json, "athletic_association").filter { (it.getValue("code") as JsonPrimitive).content == "1" },
      )
      loader.load(writeCodebook(json))

      seedCollege(100001)
      seedCollege(100002)
      seedIpeds(100001, footballConf = 102, athleticAssoc = listOf(1, 2, 3))
      seedIpeds(100002, footballConf = 102, athleticAssoc = listOf(2))

      val report = loader.reportUnknownCodes()

      val football = report.columns.single { it.column == CodeColumn("college_ipeds", "football_conf") }
      assertEquals(mapOf("102" to 2), football.counts)
      assertEquals(2, football.rows)

      // The array column is unnested: two institutions carry code 2, one carries 3.
      val assoc = report.columns.single { it.column.column == "athletic_assoc" }
      assertEquals(mapOf("2" to 2, "3" to 1), assoc.counts)
      assertEquals(3, assoc.rows)
      assertEquals(2, assoc.distinctCodes)

      // Every other column resolves cleanly — including colleges.state, whose
      // codes are the us_states keys rather than a numeric code.
      val state = report.columns.single { it.column == CodeColumn("colleges", "state") }
      assertEquals(emptyMap(), state.counts)
      assertTrue(report.render().contains("college_ipeds.football_conf"))
    }

  @Test
  fun `a fully-loaded codebook explains every stored code`() =
    runBlocking {
      loader.load(source(committedCodebook))
      seedCollege(100001, state = "CA", region = 8, locale = 11)
      // 102 = Atlantic Coast Conference, a real published CONFNO1 code (the set
      // starts at 102; there is no conference 1).
      seedIpeds(100001, relAffil = 71, footballConf = 102, athleticAssoc = listOf(1, 2))

      val report = loader.reportUnknownCodes()

      assertEquals(emptyList(), report.withUnknowns)
      assertTrue(report.render().contains("every stored code"))
      // Every code column of every domain is reported on, empty or not — the
      // SETS, not their sizes. Sizes would pass while `colleges.state` sat in
      // the SQL allowlist and no domain referenced it: a column with no delete
      // refusal and no D46 report, which is the silent orphaning this loader
      // exists to prevent.
      assertEquals(
        CodebooksDao.CODE_COLUMNS,
        report.columns.map { it.column }.toSet(),
        "every allowlisted code column must be referenced by exactly one codebook domain",
      )
      assertEquals(CodebooksDao.CODE_COLUMNS.size, report.columns.size, "and referenced exactly once")
      assertNull(report.columns.firstOrNull { it.counts.isNotEmpty() })
    }

  // ---------------------------------------------------------------------------
  // The `codebooks` INGEST PHASE (RFC 147) — the loader is injected into
  // CollegeScorecardLoader for exactly this, and nothing was exercising it: the
  // phase's name, its position, and the coverage report's placement after the
  // row phases could all have been deleted with every test still green.
  // ---------------------------------------------------------------------------

  private fun ingest(codebooks: File? = committedCodebook) =
    runBlocking {
      CollegeScorecardLoader(database).ingest(
        institution = source(fixture("scorecard-institutions-fixture.csv")),
        fields = source(fixture("scorecard-fields-empty-fixture.csv")),
        aliasesFile = source(fixture("college-aliases-fixture.json")),
        codebooks = codebooks?.let { source(it) },
      )
    }

  @Test
  fun `a slug re-key that keeps the code is refused by name, not as a raw constraint`() =
    runBlocking {
      // What a D38 caret repair or any label rewording produces: same published
      // code, new slug. The upsert's conflict target is the slug, so the insert
      // collides on <table>_code_key instead — a constraint the arbiter does not
      // cover. The transaction rolls back either way; the point is that the
      // operator is told what to do about it.
      loader.load(source(committedCodebook))

      // nces_locale, not ipeds_region: a region re-key would trip the
      // OBEREG-membership cross-check first, which is a different (earlier,
      // also correct) refusal and would hide the one under test.
      val json = codebookJson()
      val rekeyed =
        codes(json, "nces_locale").map { code ->
          if ((code["slug"] as? JsonPrimitive)?.content != "city-large") {
            code
          } else {
            JsonObject(code + mapOf("slug" to JsonPrimitive("city-large-relabelled")))
          }
        }
      withCodes(json, "nces_locale", rekeyed)

      val thrown =
        assertFailsWith<CodebookLoader.CodebookSlugRekeyException> { loader.load(writeCodebook(json)) }
      assertEquals("nces_locale", thrown.domain)
      assertEquals("city-large-relabelled", thrown.key)
      assertEquals(11, thrown.code)
      assertTrue(thrown.constraint.endsWith("_code_key"), thrown.constraint)
      assertTrue(thrown.message!!.contains("two steps"), thrown.message!!)
      // Nothing was written: the original slug is still the one stored.
      assertEquals(
        12,
        withSession { CodebooksDao.rowCount(it, CodebookTable.NCES_LOCALES).getOrThrow() },
      )
    }

  @Test
  fun `an artifact whose bytes disagree with the declared digest is fatal`() =
    runBlocking {
      // The guard the drift check could never be: drift compares a declaration
      // to a declaration, so a hand-edited codebook that left its digest strings
      // alone passed it. This hashes the archive on disk.
      val dir = committedCodebook.parentFile.parentFile.resolve("seed/codebooks")
      assertTrue(dir.isDirectory, "the committed artifacts must be beside the codebook: $dir")

      val json = codebookJson()
      val tamperedDomain = domain(json, "ipeds_region")
      tamperedDomain["source_sha256"] = JsonPrimitive("0".repeat(64))
      json["ipeds_region"] = JsonObject(tamperedDomain)
      // Written into db/data, so the artifact directory resolves exactly as it
      // does for the committed file; removed in the finally.
      val tampered = File(committedCodebook.parentFile, "codebooks-tampered-test.json")
      try {
        tampered.writeText(JsonObject(json).toString())
        val thrown =
          assertFailsWith<CodebookLoader.ArtifactDigestMismatchException> {
            loader.load(SourceFile(tampered, tampered.path))
          }
        assertEquals(1, thrown.mismatches.size)
        assertEquals("ipeds_region", thrown.mismatches.single().domain)
        assertEquals("0".repeat(64), thrown.mismatches.single().declared)
        // Nothing was written: the load never reached the database.
        assertEquals(0, withSession { CodebooksDao.rowCount(it, CodebookTable.IPEDS_REGIONS).getOrThrow() })
      } finally {
        tampered.delete()
      }
    }

  @Test
  fun `a first load is reported as a first load, never as drift`() =
    runBlocking {
      val result = loader.load(source(committedCodebook))

      assertEquals(emptyList(), result.changed, "a database that never held the domain cannot have drifted")
      assertEquals(expectedRows.size, result.firstLoads.size)
      val rendered = result.render()
      assertTrue(!rendered.contains("SOURCE DIGEST DRIFT"), rendered)
      assertTrue(rendered.contains("first load for ${expectedRows.size} domain(s)"), rendered)
      // ...and the artifact verdict is stated, verified, on the same run.
      assertTrue(rendered.contains("${expectedRows.size} verified"), rendered)
    }

  @Test
  fun `the ingest loads the codebook and reports coverage over the rows it just wrote`() {
    val report = ingest()

    val codebooks = assertNotNull(report.codebooks, "the run carries the codebook load it performed")
    assertEquals(expectedRows.size, codebooks.domains.size)
    assertEquals(expectedRows.values.sum(), codebooks.totalRows)
    // Reported AFTER the row phases: the institutions fixture has just written
    // colleges, so the report is over this run's snapshot, not the last one's.
    val coverage = assertNotNull(report.unknownCodes, "D46's report runs whenever a codebook was loaded")
    assertEquals(CodebooksDao.CODE_COLUMNS.size, coverage.columns.size)
    assertTrue(withSession { count(it, "ipeds_regions") } > 0, "the phase really committed rows")
    // Every artifact the codebook names is beside it and hashes to what it
    // declares — the guard that a hand-edited codebook cannot pass.
    assertTrue(
      codebooks.artifacts.all { it.status == CodebookLoader.ArtifactStatus.VERIFIED },
      "the committed artifacts must verify: ${codebooks.artifacts}",
    )
    assertTrue(codebooks.render().contains("verified"), codebooks.render())
    // The operator is told the running services will not see this until they
    // restart; the vocabulary is a boot-time snapshot.
    assertTrue(report.humanSummary().contains("restart them"), report.humanSummary())
  }

  @Test
  fun `the codebooks phase commits FIRST, before the row phases`() {
    // Phase order observed the only way it can be from outside: hide the
    // provenance table so the last phase fails, and read what had committed.
    // Restored in the finally; the suite is sequential.
    renameTable("college_index_build", "college_index_build_hidden")
    try {
      val thrown = assertFailsWith<PartialIngestException> { ingest() }
      assertEquals(
        listOf("codebooks", "institutions", "fields", "aliases", "name-words"),
        thrown.committedPhases,
        "the reference vocabulary lands before the columns that are read through it",
      )
      assertEquals("provenance", thrown.failedPhase)
      assertTrue(withSession { count(it, "ipeds_regions") } > 0, "the codebooks phase committed")
    } finally {
      renameTable("college_index_build_hidden", "college_index_build")
    }
  }

  @Test
  fun `a run with no codebook source has no codebooks phase at all`() {
    // Omit-vs-zero (the IPEDS rule): a run that was never given a codebook must
    // not report "0 domains", and must not run a coverage report over nothing.
    val report = ingest(codebooks = null)

    assertNull(report.codebooks)
    assertNull(report.unknownCodes)
    assertEquals(0, withSession { count(it, "ipeds_regions") })
    assertTrue(!report.humanSummary().contains("codebooks:"), report.humanSummary())
    assertTrue(!report.humanSummary().contains("codebook coverage"), report.humanSummary())

    renameTable("college_index_build", "college_index_build_hidden")
    try {
      val thrown = assertFailsWith<PartialIngestException> { ingest(codebooks = null) }
      assertEquals(listOf("institutions", "fields", "aliases", "name-words"), thrown.committedPhases)
    } finally {
      renameTable("college_index_build_hidden", "college_index_build")
    }
  }

  /** Hiding `college_index_build` is how phase ORDER is observed from outside. */
  private fun renameTable(
    from: String,
    to: String,
  ) = withSession { session ->
    session.prepareStatement("ALTER TABLE $from RENAME TO $to").use { it.execute() }
  }
}
