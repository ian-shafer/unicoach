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
  fun `the legacy load path asserts headers too`() {
    val missingColumn = fixture("scorecard-institutions-missing-column-fixture.csv")
    assertThrows<MissingSourceColumnsException> {
      runBlocking { loader.load(missingColumn, fieldsCsv) }
    }
    assertEquals(0, withSession { count(it, "colleges") })
  }

  @Test
  fun `a duplicate unit_id in the aliases file is fatal and writes nothing`() {
    val duplicated = File.createTempFile("college-aliases-duplicate", ".json")
    duplicated.deleteOnExit()
    duplicated.writeText(
      """
      [
        { "unit_id": 110100, "aliases": ["Coastal"] },
        { "unit_id": 110100, "aliases": ["CSU Seaside"] }
      ]
      """.trimIndent(),
    )

    val thrown =
      assertThrows<CollegeScorecardLoader.InvalidAliasFileException> {
        runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(duplicated)) }
      }
    assertEquals(listOf(110100), thrown.duplicateUnitIds)
    assertTrue(thrown.message!!.contains("110100"), "the fatal message must name the duplicate unit_id: ${thrown.message}")
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
        """[{ "unit_id": 110100, "aliases": ["Coastal"], "alises": ["typo"] }]""",
        "alises",
        "entry [0]",
      )
    assertEquals(0, thrown.entryIndex)
  }

  @Test
  fun `a missing key in an alias entry is fatal, naming which key`() {
    assertAliasFileRejected("""[{ "unit_id": 110100 }]""", "missing key(s) [aliases]", "entry [0]")
  }

  @Test
  fun `a non-integer unit_id is fatal rather than a bare kotlinx cast failure`() {
    assertAliasFileRejected(
      """[{ "unit_id": 110100, "aliases": ["ok"] }, { "unit_id": "110200", "aliases": ["bad"] }]""",
      "unit_id must be a JSON integer",
      "entry [1]",
    )
  }

  @Test
  fun `a non-string alias is fatal rather than silently coerced`() {
    assertAliasFileRejected("""[{ "unit_id": 110100, "aliases": [42] }]""", "every alias must be a JSON string", "42")
  }

  @Test
  fun `a non-array aliases file is fatal, naming the file`() {
    assertAliasFileRejected("""{ "unit_id": 110100, "aliases": ["Coastal"] }""", "top level must be a JSON array")
  }

  @Test
  fun `malformed JSON in the aliases file is fatal, naming the file`() {
    assertAliasFileRejected("""[{ "unit_id": ]""", "not valid JSON")
  }

  // ---------------------------------------------------------------------------
  // Mid-run failure: no build row, but a loud partial-state report
  // ---------------------------------------------------------------------------

  @Test
  fun `a failure after a phase committed reports the committed phases and no provenance`() {
    // A NUL byte is valid JSON but cannot be stored as Postgres text, so the
    // alias phase fails at the DB — after institutions and fields committed.
    val hostile = aliasesFile("""[{ "unit_id": 110100, "aliases": ["\u0000bad"] }]""")
    val buildRowsBefore = withSession { count(it, "college_index_build") }

    val thrown =
      assertThrows<PartialIngestException> {
        runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(hostile)) }
      }
    assertEquals(listOf("institutions", "fields"), thrown.committedPhases)
    assertTrue(thrown.message!!.contains("provenance was NOT recorded"), "the report is explicit: ${thrown.message}")
    assertTrue(
      thrown.cause!!.message!!.contains("unit_id=110100"),
      "the cause names the failing entry: ${thrown.cause?.message}",
    )

    // The partial state is real: earlier phases are committed, and — per the
    // success-only rule — no build row exists to describe them.
    assertEquals(5, withSession { count(it, "colleges") })
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") }, "no build row describes a failed run")
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
    assertEquals(listOf(999999), report.aliases.unknownUnitIds, "the unmatched entry is named, not just counted")

    val coastal = withSession { CollegesDao.findByUnitId(it, 110100).getOrThrow() }
    assertNotNull(coastal)
    assertEquals(listOf("Coastal", "CSU Seaside"), coastal.aliases)
    assertEquals(2, coastal.version, "the alias application bumps the version once")

    // The build row exists and says what the report says.
    val row = withSession { buildRow(it, report.buildId) }
    assertNotNull(row)
    // Deliberately 2, not 1: RFC 144 added a second source family, which is
    // exactly the derivation change method_version exists to record.
    assertEquals(2, row.methodVersion)
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
    assertEquals(0, report.nonNullBefore["admission_rate"])
    assertEquals(4, report.nonNullAfter["admission_rate"])
    val summary = report.humanSummary()
    assertTrue(summary.contains("admission_rate 0→4"), "summary carries the delta: $summary")
  }

  @Test
  fun `the unknown alias unit_ids are named in the build row and the summary`() {
    val report = ingest()

    val row = withSession { buildRow(it, report.buildId) }
    assertNotNull(row)
    assertTrue(
      row.rowsIngested.contains("\"unknown_unit_id\": [") && row.rowsIngested.contains("999999"),
      "the build row carries the unmatched ids, not just a count: ${row.rowsIngested}",
    )

    val summary = report.humanSummary()
    assertTrue(summary.contains("1 unknown unit_id [999999]"), "the summary names the unmatched id: $summary")
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
