package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The RFC 139 ingest wrapper: fatal header assertion before any write, source
 * sha256 provenance, the inserted/changed/unchanged split, curated-alias
 * application, and the `college_index_build` row + human summary.
 */
class CollegeScorecardIngestTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val aliasesJson = fixture("college-aliases-fixture.json")

  private fun source(file: File): SourceFile = SourceFile(file, file.path)

  private fun ingest(): CollegeScorecardLoader.IngestReport =
    runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson)) }

  // ---------------------------------------------------------------------------
  // Header assertion (fatal, before any write)
  // ---------------------------------------------------------------------------

  @Test
  fun `a CSV missing a required column is fatal and writes nothing`() {
    val missingColumn = fixture("scorecard-institutions-missing-column-fixture.csv")

    val thrown =
      assertThrows<MissingSourceColumnsException> {
        runBlocking { loader.ingest(source(missingColumn), source(fieldsCsv), source(aliasesJson)) }
      }
    assertEquals(listOf("ADM_RATE"), thrown.missing)
    assertTrue(thrown.message!!.contains("ADM_RATE"), "the fatal message must name the missing column")

    // Nothing was written: no colleges, no programs, no build row.
    assertEquals(0, withSession { count(it, "colleges") })
    assertEquals(0, withSession { count(it, "college_programs") })
  }

  @Test
  fun `a snapshot missing a cost component column is fatal and writes nothing`() {
    // RFC 149: the six components are REQUIRED, so a release that stopped
    // publishing one must stop the ingest rather than load silent nulls over a
    // column the cost read now depends on. Triggered rather than inferred -- a
    // header assertion is silent on every run where the column is present, and
    // the shared missing-column fixture carries all six.
    //
    // A header-only CSV is enough: the assertion runs before any row is read,
    // which is the property the "writes nothing" half of the name asserts.
    val header = CollegeScorecardLoader.REQUIRED_INSTITUTION_COLUMNS.filterNot { it == "ROOMBOARD_ON" }
    val stripped = File.createTempFile("scorecard-without-roomboard-on", ".csv")
    stripped.deleteOnExit()
    stripped.writeText(header.joinToString(",") + "\n")

    val thrown =
      assertThrows<MissingSourceColumnsException> {
        runBlocking { loader.ingest(source(stripped), source(fieldsCsv), source(aliasesJson)) }
      }
    assertEquals(listOf("ROOMBOARD_ON"), thrown.missing)
    assertTrue(thrown.message!!.contains("ROOMBOARD_ON"), "the fatal message must name the missing column")
    assertEquals(0, withSession { count(it, "colleges") })
  }

  @Test
  fun `the legacy load path asserts headers too`() {
    val missingColumn = fixture("scorecard-institutions-missing-column-fixture.csv")
    assertThrows<MissingSourceColumnsException> {
      runBlocking { loader.load(missingColumn, fieldsCsv) }
    }
    assertEquals(0, withSession { count(it, "colleges") })
  }

  @Test
  fun `a duplicate ipeds_unit_id in the aliases file is fatal and writes nothing`() {
    val duplicated = File.createTempFile("college-aliases-duplicate", ".json")
    duplicated.deleteOnExit()
    duplicated.writeText(
      """
      [
        { "ipeds_unit_id": 110100, "aliases": ["Coastal"] },
        { "ipeds_unit_id": 110100, "aliases": ["CSU Seaside"] }
      ]
      """.trimIndent(),
    )

    val thrown =
      assertThrows<CollegeScorecardLoader.InvalidAliasFileException> {
        runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(duplicated)) }
      }
    assertEquals(listOf(110100), thrown.duplicateIpedsUnitIds)
    assertTrue(thrown.message!!.contains("110100"), "the fatal message must name the duplicate ipeds_unit_id: ${thrown.message}")
    assertTrue(thrown.message!!.contains(duplicated.path), "the fatal message must name the file: ${thrown.message}")

    // Fatal at parse, before any write: no colleges, no programs, no build row.
    assertEquals(0, withSession { count(it, "colleges") })
    assertEquals(0, withSession { count(it, "college_programs") })
  }

  @Test
  fun `the missing-column fatal names the source the caller gave, not a scratch basename`() {
    val missingColumn = fixture("scorecard-institutions-missing-column-fixture.csv")
    val callerArg = "s3://unicoach-scorecard/2024-25/institution.csv"

    val thrown =
      assertThrows<MissingSourceColumnsException> {
        runBlocking {
          loader.ingest(
            SourceFile(missingColumn, callerArg),
            source(fieldsCsv),
            source(aliasesJson),
          )
        }
      }
    assertEquals(callerArg, thrown.sourceArg)
    assertTrue(thrown.message!!.contains(callerArg), "the fatal names the caller's argument: ${thrown.message}")
  }

  // ---------------------------------------------------------------------------
  // Curated aliases: every shape violation is loud, typed, and located
  // ---------------------------------------------------------------------------

  private fun aliasesFile(json: String): File {
    val file = File.createTempFile("college-aliases-invalid", ".json")
    file.deleteOnExit()
    file.writeText(json)
    return file
  }

  private fun assertAliasFileRejected(
    json: String,
    vararg expectedInMessage: String,
  ): CollegeScorecardLoader.InvalidAliasFileException {
    val file = aliasesFile(json)
    val thrown =
      assertThrows<CollegeScorecardLoader.InvalidAliasFileException> {
        runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(file)) }
      }
    assertEquals(file.path, thrown.fileName, "every alias failure names the file")
    for (expected in expectedInMessage) {
      assertTrue(thrown.message!!.contains(expected), "message must contain [$expected]: ${thrown.message}")
    }
    // Fatal at parse, before any write.
    assertEquals(0, withSession { count(it, "colleges") })
    return thrown
  }

  @Test
  fun `an unknown key in an alias entry is fatal, naming the entry and the key`() {
    val thrown =
      assertAliasFileRejected(
        """[{ "ipeds_unit_id": 110100, "aliases": ["Coastal"], "alises": ["typo"] }]""",
        "alises",
        "entry [0]",
      )
    assertEquals(0, thrown.entryIndex)
  }

  @Test
  fun `a missing key in an alias entry is fatal, naming which key`() {
    assertAliasFileRejected("""[{ "ipeds_unit_id": 110100 }]""", "missing key(s) [aliases]", "entry [0]")
  }

  @Test
  fun `a non-integer ipeds_unit_id is fatal rather than a bare kotlinx cast failure`() {
    assertAliasFileRejected(
      """[{ "ipeds_unit_id": 110100, "aliases": ["ok"] }, { "ipeds_unit_id": "110200", "aliases": ["bad"] }]""",
      "ipeds_unit_id must be a JSON integer",
      "entry [1]",
    )
  }

  @Test
  fun `a non-string alias is fatal rather than silently coerced`() {
    assertAliasFileRejected("""[{ "ipeds_unit_id": 110100, "aliases": [42] }]""", "every alias must be a JSON string", "42")
  }

  @Test
  fun `a non-array aliases file is fatal, naming the file`() {
    assertAliasFileRejected("""{ "ipeds_unit_id": 110100, "aliases": ["Coastal"] }""", "top level must be a JSON array")
  }

  @Test
  fun `malformed JSON in the aliases file is fatal, naming the file`() {
    assertAliasFileRejected("""[{ "ipeds_unit_id": ]""", "not valid JSON")
  }

  // ---------------------------------------------------------------------------
  // Mid-run failure: no build row, but a loud partial-state report
  // ---------------------------------------------------------------------------

  @Test
  fun `a failure after a phase committed reports the committed phases and no provenance`() {
    // A NUL byte is valid JSON but cannot be stored as Postgres text, so the
    // alias phase fails at the DB — after institutions and fields committed.
    val hostile = aliasesFile("""[{ "ipeds_unit_id": 110100, "aliases": ["\u0000bad"] }]""")
    val buildRowsBefore = withSession { count(it, "college_index_build") }

    val thrown =
      assertThrows<PartialIngestException> {
        runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(hostile)) }
      }
    assertEquals(listOf("institutions", "fields"), thrown.committedPhases)
    assertEquals("aliases", thrown.failedPhase, "the report names the phase that threw, not just what landed")
    assertTrue(thrown.message!!.contains("in phase [aliases]"), "the operator-facing message names it too: ${thrown.message}")
    assertTrue(thrown.message!!.contains("provenance was NOT recorded"), "the report is explicit: ${thrown.message}")
    assertTrue(
      thrown.cause!!.message!!.contains("ipeds_unit_id=110100"),
      "the cause names the failing entry: ${thrown.cause?.message}",
    )

    // The partial state is real: earlier phases are committed, and — per the
    // success-only rule — no build row exists to describe them.
    assertEquals(5, withSession { count(it, "colleges") })
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") }, "no build row describes a failed run")
  }

  @Test
  fun `a failure in the provenance phase names the committed name-word rebuild`() {
    // The one phase that runs after name-words is provenance, so hiding the
    // table provenance writes fails there and nowhere earlier — the only way to
    // observe that the derived rebuild registers itself as a committed phase.
    // Restored in the finally; the suite is sequential and bin/test recreates
    // the test database per run, so a hard kill cannot leak the rename.
    withSession { it.prepareStatement("ALTER TABLE college_index_build RENAME TO college_index_build_hidden").use(::execute) }
    try {
      val thrown =
        assertThrows<PartialIngestException> {
          runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson)) }
        }
      assertEquals(
        listOf("institutions", "fields", "aliases", "name-words", "search-index"),
        thrown.committedPhases,
        "the derived search index registers itself as a committed phase too (RFC 150)",
      )
      assertEquals("provenance", thrown.failedPhase, "the report names the phase that threw, not just what landed")
      // Exactly the expected table, not merely non-empty: the independent
      // recomputation is in hand, so a rebuild that committed one college's
      // words — or the names without the aliases — must fail here.
      assertEquals(
        expectedNameWords(),
        storedNameWords(),
        "the phase the report names committed its rows in full before the later phase failed",
      )
    } finally {
      withSession { it.prepareStatement("ALTER TABLE college_index_build_hidden RENAME TO college_index_build").use(::execute) }
    }
  }

  /**
   * `execute()` as a function reference, so the DDL above reads
   * `.use(::execute)` rather than repeating a `{ it.execute() }` lambda at every
   * rename site. It exists for that call shape and nothing else.
   */
  private fun execute(statement: java.sql.PreparedStatement) {
    statement.execute()
  }

  // ---------------------------------------------------------------------------
  // Provenance: sources, counts, build row
  // ---------------------------------------------------------------------------

  @Test
  fun `the recorded sha256 and bytes match an independent digest`() {
    val report = ingest()
    val recorded = report.sources.first { it.fileName == institutionCsv.name }

    val md = MessageDigest.getInstance("SHA-256")
    val bytes = institutionCsv.readBytes()
    val expected = HexFormat.of().formatHex(md.digest(bytes))
    assertEquals(expected, recorded.sha256)
    assertEquals(bytes.size.toLong(), recorded.bytes)
    assertEquals(3, report.sources.size, "institution, fields, and aliases files are all recorded")
  }

  @Test
  fun `first ingest counts inserts, applies aliases, and writes a build row`() {
    val report = ingest()

    // 5 valid institutions inserted (the 6th row has an empty UNITID), 9 programs.
    assertEquals(6, report.colleges.seen)
    assertEquals(5, report.colleges.inserted)
    assertEquals(0, report.colleges.changed)
    assertEquals(0, report.colleges.unchanged)
    assertEquals(9, report.programs.upserted)

    // Aliases: two entries hit fixture institutions; the 999999 entry is
    // counted unknown, never fatal.
    assertEquals(3, report.aliases.entries)
    assertEquals(2, report.aliases.applied)
    assertEquals(0, report.aliases.unchanged)
    assertEquals(listOf(999999), report.aliases.unknownIpedsUnitIds, "the unmatched entry is named, not just counted")

    val coastal = withSession { CollegesDao.findByIpedsUnitId(it, 110100).getOrThrow() }
    assertNotNull(coastal)
    assertEquals(listOf("Coastal", "CSU Seaside"), coastal.aliases)
    assertEquals(2, coastal.version, "the alias application bumps the version once")

    // The build row exists and says what the report says.
    val row = withSession { buildRow(it, report.buildId) }
    assertNotNull(row)
    // Deliberately 5, not 1: RFC 144 added a second source family, RFC 146 the
    // derived name-word rebuild, RFC 148 the CDS seed load, and RFC 150 the
    // derived search index — each is exactly the derivation change
    // method_version exists to record.
    assertEquals(5, row.methodVersion)
    assertTrue(row.rowsIngested.contains("\"inserted\": 5"), "rows_ingested carries the insert count: ${row.rowsIngested}")
    assertTrue(row.sources.contains(institutionCsv.name), "sources carries the file name")
    assertTrue(row.changeSummary.contains("version_bumps"), "change_summary carries version bumps")
  }

  @Test
  fun `the build row records the skip taxonomy, not just a skip total`() {
    val report = ingest()

    // The 6th institution row has an empty UNITID, so exactly one row is
    // skipped -- and provenance must say WHY, not merely "1".
    val row = withSession { buildRow(it, report.buildId) }
    assertNotNull(row)
    assertTrue(
      row.rowsIngested.contains("\"skips_by_reason\""),
      "rows_ingested carries the taxonomy: ${row.rowsIngested}",
    )
    assertTrue(
      row.rowsIngested.contains("\"missing_required_field\": 1"),
      "the skip is attributed to its reason kind, not just counted: ${row.rowsIngested}",
    )
  }

  @Test
  fun `an unchanged re-ingest is a loudly visible no-op`() {
    ingest()
    val second = ingest()

    assertEquals(0, second.colleges.inserted)
    assertEquals(0, second.colleges.changed)
    assertEquals(5, second.colleges.unchanged)
    assertEquals(0, second.aliases.applied)
    assertEquals(2, second.aliases.unchanged)
    assertEquals(0, second.versionBumps)

    // The build row records the all-unchanged run.
    val row = withSession { buildRow(it, second.buildId) }
    assertNotNull(row)
    assertTrue(row.rowsIngested.contains("\"changed\": 0"), "rows_ingested says 0 changed: ${row.rowsIngested}")
    assertTrue(row.rowsIngested.contains("\"unchanged\": 5"), "rows_ingested says 5 unchanged: ${row.rowsIngested}")

    // The human summary prints the no-op loudly.
    val summary = second.humanSummary()
    assertTrue(summary.contains("0 inserted, 0 changed, 5 unchanged"), "summary must state the no-op: $summary")
    assertTrue(summary.contains("build row: ${second.buildId}"))
  }

  @Test
  fun `the change summary tracks per-column non-null counts before and after`() {
    val report = ingest()
    // The fixture has 4 non-blank ADM_RATE cells among the 5 loaded rows
    // (330300's is blank), starting from an empty table.
    assertEquals(0, report.nonNullBefore["admission_rate_share"])
    assertEquals(4, report.nonNullAfter["admission_rate_share"])
    val summary = report.humanSummary()
    assertTrue(summary.contains("admission_rate_share 0→4"), "summary carries the delta: $summary")
  }

  @Test
  fun `the change summary proves all six cost components loaded`() {
    // RFC 149: the six components are in NON_NULL_SUMMARY_COLUMNS precisely so
    // the run's own report proves they arrived. The fixture's five loaded rows
    // report 4 of each component except the two the fixture leaves NA:
    // 330300's ROOMBOARD_OFF and 550500's ROOMBOARD_ON, plus 440400's whole row.
    val report = ingest()
    val expectedAfter =
      mapOf(
        "books_and_supplies_per_year_usd" to 4,
        "housing_and_food_on_campus_per_year_usd" to 3,
        "housing_and_food_off_campus_per_year_usd" to 3,
        "other_expenses_on_campus_per_year_usd" to 4,
        "other_expenses_off_campus_per_year_usd" to 4,
        "other_expenses_with_family_per_year_usd" to 4,
      )
    for ((column, after) in expectedAfter) {
      assertEquals(0, report.nonNullBefore[column], "[$column] starts from an empty table")
      assertEquals(after, report.nonNullAfter[column], "[$column] must be counted by the change summary")
    }
    val summary = report.humanSummary()
    assertTrue(
      summary.contains("books_and_supplies_per_year_usd 0→4"),
      "the printed non-null deltas must carry the components: $summary",
    )
  }

  @Test
  fun `the unknown alias ipeds_unit_ids are named in the build row and the summary`() {
    val report = ingest()

    val row = withSession { buildRow(it, report.buildId) }
    assertNotNull(row)
    assertTrue(
      row.rowsIngested.contains("\"unknown_ipeds_unit_id\": [") && row.rowsIngested.contains("999999"),
      "the build row carries the unmatched ids, not just a count: ${row.rowsIngested}",
    )

    val summary = report.humanSummary()
    assertTrue(summary.contains("1 unknown ipeds_unit_id [999999]"), "the summary names the unmatched id: $summary")
  }

  @Test
  fun `the summary prints elapsed time with sub-second precision, never 0s`() {
    val summary = ingest().humanSummary()
    val elapsed = summary.lineSequence().first()
    assertTrue(
      Regex("""^ingest complete in \d+\.\ds$""").matches(elapsed),
      "elapsed must carry a decimal, so a fast run never reads as 0s: [$elapsed]",
    )
  }

  // ---------------------------------------------------------------------------
  // The derived name-words rebuild (RFC 146)
  // ---------------------------------------------------------------------------

  @Test
  fun `the ingest rebuilds the name-word table wholesale and records its row count`() {
    val report = ingest()

    // Every word of every college's search text, and nothing else: the table is
    // a pure function of the loaded rows, so an independent recomputation of it
    // must agree exactly.
    assertEquals(expectedNameWords(), storedNameWords())
    // The exact expected content is in hand, so the reported count is checked
    // against it rather than against a floor a wrong-but-non-empty rebuild
    // would clear.
    assertEquals(expectedNameWords().size, report.nameWords, "the phase reports exactly the rows it wrote")
    assertEquals(report.nameWords, withSession { count(it, "college_name_words") })

    // Curated aliases are part of the search text, so they are part of the
    // words: the phase runs AFTER the alias phase.
    assertTrue("csu" in storedNameWords().map { it.second }.toSet(), "alias words are indexed: ${storedNameWords()}")

    // The count reaches provenance (the column was NULL for every RFC 139 build
    // row) and the printed summary. 0064 renamed it `name_words_rows`, because
    // `index_rows` is now a name that describes a different table (D48).
    assertEquals(report.nameWords, withSession { nameWordsRows(it, report.buildId) })
    assertTrue(
      report.humanSummary().contains("name words: ${report.nameWords} rows"),
      "the summary carries the derived row count: ${report.humanSummary()}",
    )
  }

  @Test
  fun `an unchanged re-ingest leaves the name-word table identical`() {
    val first = ingest()
    val after = storedNameWords()
    val second = ingest()

    assertEquals(0, second.colleges.changed, "the premise: nothing changed")
    assertEquals(first.nameWords, second.nameWords)
    assertEquals(after, storedNameWords(), "a wholesale rebuild of unchanged rows is the same table")
  }

  // ---------------------------------------------------------------------------
  // Phase 2b: the derived search index (RFC 150)
  // ---------------------------------------------------------------------------

  @Test
  fun `the search-index phase writes one row per college and records the count`() {
    val report = ingest()

    assertEquals(report.colleges.inserted, report.searchIndex, "one index row per loaded college")
    assertEquals(report.searchIndex, withSession { count(it, "college_search_index") })
    // BOTH derived counts land, under their own names: that is the whole point
    // of D48's rename, and a shared column could not carry them.
    assertEquals(report.searchIndex, withSession { searchIndexRows(it, report.buildId) })
    assertEquals(report.nameWords, withSession { nameWordsRows(it, report.buildId) })
  }

  @Test
  fun `a Scorecard-only ingest still fills the index, with NULL attributes and is_active TRUE`() {
    ingest()
    // No IPEDS group, so no `college_ipeds` rows at all: every attribute column
    // is NULL and the row is still searchable. This is the LEFT JOIN discipline
    // at the source-join level, not just the codebook level.
    //
    // The three array columns are NULL here, not '{}' (RFC 150): nothing was
    // reported about this college's associations or programs, and the empty
    // array is reserved for the school that reported belonging to none.
    withSession { session ->
      session
        .prepareStatement(
          """
          SELECT count(*) AS n
          FROM college_search_index
          WHERE is_active AND sector IS NULL AND is_four_year IS NULL
            AND test_policy IS NULL AND religious_affiliation IS NULL
            AND carnegie_class IS NULL AND carnegie_size IS NULL
            AND athletic_associations IS NULL AND cip_codes IS NULL AND subject_slugs IS NULL
          """.trimIndent(),
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            assertEquals(5, rs.getInt("n"), "every Scorecard-only row is present and honestly empty")
          }
        }
    }
  }

  @Test
  fun `a failed later phase leaves no build row and so no search_index_rows`() {
    // The success-only provenance rule, restated for the new counts: a partial
    // run records nothing at all, so neither count can describe a run that did
    // not finish.
    val buildRowsBefore = withSession { count(it, "college_index_build") }
    val hostile = aliasesFile("""[{ "ipeds_unit_id": 110100, "aliases": ["\u0000bad"] }]""")
    assertThrows<PartialIngestException> {
      runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(hostile)) }
    }
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") })
  }

  @Test
  fun `an unchanged re-ingest reproduces the index column for column`() {
    ingest()
    val first = indexSnapshot()
    val second = ingest()
    assertEquals(0, second.colleges.changed, "the premise: nothing changed")
    // D59: every column, no exclusions. `build_id` was removed by D60, so there
    // is nothing build-specific left to exempt.
    assertEquals(first, indexSnapshot(), "the same snapshot at the same method_version reproduces the index")
  }

  /** Every column of every index row, as text, in a stable order. */
  private fun indexSnapshot(): List<String> =
    withSession { session ->
      session
        .prepareStatement("SELECT i::text AS whole_row FROM college_search_index i ORDER BY i.college_id")
        .use { stmt ->
          stmt.executeQuery().use { rs ->
            val rows = mutableListOf<String>()
            while (rs.next()) rows += rs.getString("whole_row")
            rows
          }
        }
    }

  /** The (ipeds_unit_id, word) pairs actually stored, alphabetical. */
  private fun storedNameWords(): List<Pair<Int, String>> =
    withSession { session ->
      session
        .prepareStatement(
          "SELECT c.ipeds_unit_id, nw.word FROM college_name_words nw JOIN colleges c ON c.id = nw.college_id " +
            "ORDER BY c.ipeds_unit_id, nw.word",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            val rows = mutableListOf<Pair<Int, String>>()
            while (rs.next()) rows += rs.getInt(1) to rs.getString(2)
            rows
          }
        }
    }

  /**
   * The same pairs recomputed independently, in Kotlin, from `colleges.name`
   * and `colleges.aliases` — the derived table's definition restated by a
   * second implementation rather than the SQL that wrote it.
   */
  private fun expectedNameWords(): List<Pair<Int, String>> =
    withSession { session ->
      session.prepareStatement("SELECT ipeds_unit_id, name, aliases FROM colleges").use { stmt ->
        stmt.executeQuery().use { rs ->
          val rows = mutableListOf<Pair<Int, String>>()
          while (rs.next()) {
            val ipedsUnitId = rs.getInt("ipeds_unit_id")
            val aliases =
              rs.getArray("aliases").let { arr ->
                try {
                  @Suppress("UNCHECKED_CAST")
                  (arr.array as Array<String?>).filterNotNull()
                } finally {
                  arr.free()
                }
              }
            val text = (listOf(rs.getString("name")) + aliases).joinToString(" ")
            text
              .lowercase()
              .split(Regex("[^a-z0-9]+"))
              .filter { it.isNotEmpty() }
              .distinct()
              .forEach { rows += ipedsUnitId to it }
          }
          rows.sortedWith(compareBy({ it.first }, { it.second }))
        }
      }
    }

  /**
   * `college_index_build.name_words_rows` for one build — the column 0064
   * renamed out of `index_rows` (RFC 150 D48). Nullable in the schema — it
   * was NULL for every RFC 139-era build row, which is exactly the regression
   * this helper's callers assert against — so it is read as `Int?`: `getInt`
   * alone would map SQL NULL onto the very same `0` a real zero count produces.
   * The lookup is by primary key, so exactly one row is the contract and both
   * ends of it are checked.
   */
  private fun nameWordsRows(
    session: SqlSession,
    id: UUID,
  ): Int? = buildCount(session, "name_words_rows", id)

  /** `college_index_build.search_index_rows` for one build (RFC 150). See [nameWordsRows]. */
  private fun searchIndexRows(
    session: SqlSession,
    id: UUID,
  ): Int? = buildCount(session, "search_index_rows", id)

  private fun buildCount(
    session: SqlSession,
    column: String,
    id: UUID,
  ): Int? =
    session.prepareStatement("SELECT $column FROM college_index_build WHERE id = ?").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "no college_index_build row for build [$id]")
        val rows = rs.getInt(1).takeUnless { rs.wasNull() }
        assertFalse(rs.next(), "more than one college_index_build row for build [$id]")
        rows
      }
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private data class BuildRow(
    val methodVersion: Int,
    val sources: String,
    val rowsIngested: String,
    val changeSummary: String,
  )

  private fun buildRow(
    session: SqlSession,
    id: UUID,
  ): BuildRow? =
    session
      .prepareStatement(
        "SELECT method_version, sources::text, rows_ingested::text, change_summary::text " +
          "FROM college_index_build WHERE id = ?",
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return null
          BuildRow(
            methodVersion = rs.getInt(1),
            sources = rs.getString(2),
            rowsIngested = rs.getString(3),
            changeSummary = rs.getString(4),
          )
        }
      }
}
