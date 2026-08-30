package ed.unicoach.college

import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FactorRating
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CDS seed loader suite (RFC 140), on the scorecard test scaffolding: colleges
 * are seeded from the Scorecard institutions fixture (unit ids 110100/220200/…)
 * so UNITID resolution runs against real `colleges` rows, and the per-test
 * TRUNCATE of `colleges` cascades through the CDS tables' FKs.
 */
class CdsSeedLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = CdsSeedLoader(database)
  private val meritCsv = fixture("cds-merit-aid-fixture.csv")
  private val factorsCsv = fixture("cds-admission-factors-fixture.csv")
  private val deadlinesCsv = fixture("cds-deadlines-fixture.csv")

  private fun seedColleges() =
    runBlocking {
      scorecardLoader.load(
        fixture("scorecard-institutions-fixture.csv"),
        fixture("scorecard-fields-empty-fixture.csv"),
      )
      Unit
    }

  /**
   * The repo's committed CDS seed directory, resolved by walking up from the
   * test's working directory (the module dir under Gradle) rather than assuming
   * a fixed depth.
   */
  private val committedSeedDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/cds") }
      .first { it.isDirectory }

  private fun collegeId(ipedsUnitId: Int): CollegeId =
    withSession {
      requireNotNull(CollegesDao.findByIpedsUnitId(it, ipedsUnitId).getOrThrow()).id
    }

  @Test
  fun `loads the three seed files, skipping and counting unknown UNITIDs`() =
    runBlocking {
      seedColleges()
      val result = loader.load(meritCsv, factorsCsv, deadlinesCsv)

      // merit-aid: 2 matched rows, the 999999 row has no college -> skipped.
      assertEquals(2, result.meritAid.upserted)
      assertEquals(0, result.meritAid.changed)
      assertEquals(0, result.meritAid.unchanged)
      assertEquals(1, result.meritAid.skipped)
      assertEquals(2, result.admissionFactors.upserted)
      assertEquals(0, result.admissionFactors.skipped)
      assertEquals(3, result.deadlines.upserted)
      assertEquals(1, result.deadlines.skipped)

      // Spot values: the H2A row lands typed, incl. the null average.
      val coastal = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(110100), 2024).getOrThrow() }
      assertNotNull(coastal)
      assertEquals(2760, coastal.firstTimeFullTimeFreshmenHeadcount)
      assertEquals(358, coastal.noNeedMeritRecipientsHeadcount)
      assertEquals(16112, coastal.noNeedMeritAverageUsd)
      assertEquals("https://coastal.example.edu/cds-2024-25.pdf", coastal.sourceUrl)
      assertEquals("https://www.collegedata.fyi/schools/coastal/2024-25", coastal.archiveUrl)
      val lakeside = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(220200), 2025).getOrThrow() }
      assertNotNull(lakeside)
      assertNull(lakeside.noNeedMeritAverageUsd)

      // Factor grid: rated cells land as enum codes, empty cells as NULL.
      val factors = withSession { CdsAdmissionsDao.findAdmissionFactors(it, collegeId(220200), 2025).getOrThrow() }
      assertNotNull(factors)
      assertEquals(FactorRating.VERY_IMPORTANT, factors.rigor)
      assertEquals(FactorRating.IMPORTANT, factors.testScores)
      assertNull(factors.classRank)
      assertNull(factors.applicantInterest)

      // Deadlines: two rounds for 110100, the rolling flags-only row for 220200.
      val rounds = withSession { CdsAdmissionsDao.listDeadlines(it, collegeId(110100), 2024).getOrThrow() }
      assertEquals(2, rounds.size)
      val ed1 = rounds.first { it.round == ApplicationRound.EARLY_DECISION_1 }
      assertEquals(CdsMonthDay(11, 1), ed1.closing)
      assertEquals(CdsMonthDay(12, 15), ed1.notification)
      val rolling = withSession { CdsAdmissionsDao.listDeadlines(it, collegeId(220200), 2025).getOrThrow() }
      assertEquals(listOf(ApplicationRound.ROLLING), rolling.map { it.round })
      assertTrue(rolling.single().offered)
      assertNull(rolling.single().closing)

      // Coverage is computed from the DB over the matched schools only.
      assertEquals(2, result.coverage.launchSetCount)
      assertEquals(2, result.coverage.meritAidCount)
      assertEquals(2, result.coverage.admissionFactorsCount)
      assertEquals(2, result.coverage.deadlinesFlagsCount)
      assertEquals(1, result.coverage.deadlinesWithDateCount)
      assertEquals(emptyList(), result.coverage.studentListedMissing)
    }

  @Test
  fun `re-running the load is idempotent -- every row unchanged`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv)
      val second = loader.load(meritCsv, factorsCsv, deadlinesCsv)

      assertEquals(0, second.meritAid.upserted)
      assertEquals(0, second.meritAid.changed)
      assertEquals(2, second.meritAid.unchanged)
      assertEquals(2, second.admissionFactors.unchanged)
      assertEquals(3, second.deadlines.unchanged)
      assertEquals(2, withSession { count(it, "college_merit_aid") })
      assertEquals(2, withSession { count(it, "college_admission_factors") })
      assertEquals(3, withSession { count(it, "college_deadlines") })
    }

  @Test
  fun `a changed value updates in place and advances updated_at`() =
    runBlocking {
      seedColleges()
      loader.load(meritCsv, factorsCsv, deadlinesCsv)
      val before = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(110100), 2024).getOrThrow() }
      assertNotNull(before)

      Thread.sleep(5)
      val result = loader.load(fixture("cds-merit-aid-changed-fixture.csv"), factorsCsv, deadlinesCsv)
      assertEquals(1, result.meritAid.changed)
      assertEquals(1, result.meritAid.unchanged)

      val after = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(110100), 2024).getOrThrow() }
      assertNotNull(after)
      assertEquals(before.id, after.id)
      assertEquals(17000, after.noNeedMeritAverageUsd)
      assertTrue(after.updatedAt.isAfter(before.updatedAt))
    }

  @Test
  fun `a missing or renamed header column is fatal and named`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(fixture("cds-merit-aid-bad-header-fixture.csv"), factorsCsv, deadlinesCsv) }
      }
    // Asserted on the structured defect, not the rendered sentence: the fields
    // are the payload, the message is one rendering of them.
    val defect = error.defect as CdsSeedLoader.Defect.HeaderMismatch
    // The RESOLVED path, not a basename: bin/ingest-colleges may hand the
    // loader a temp copy, and a basename cannot be resolved back to it.
    assertEquals(fixture("cds-merit-aid-bad-header-fixture.csv").path, defect.file)
    // `load` is handed bare Files, so the path IS the caller's argument here;
    // the ingest path below proves the operator's own spelling survives.
    assertEquals(defect.file, defect.sourceArg)
    assertEquals(listOf("freshmen_ft_total"), defect.missing)
    assertEquals(listOf("freshmen_ft"), defect.unexpected)
    assertEquals(CdsSeedLoader.MERIT_AID_COLUMNS, defect.expected)
    // Header assertion fires before any write: nothing landed from any file.
    assertEquals(0, withSession { count(it, "college_merit_aid") })
    assertEquals(0, withSession { count(it, "college_admission_factors") })
  }

  @Test
  fun `a rating outside the whitelist codes is fatal, not skipped`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(meritCsv, fixture("cds-admission-factors-junk-fixture.csv"), deadlinesCsv) }
      }
    val defect = error.defect as CdsSeedLoader.Defect.UnknownCode
    assertEquals(CdsSeedLoader.Table.ADMISSION_FACTORS, defect.table)
    assertEquals("rigor", defect.column)
    assertEquals("Very Important", defect.value)
    assertEquals(FactorRating.entries.map { it.value }, defect.allowed)
  }

  @Test
  fun `a month with no day loads, and does not count as a concrete date`() =
    runBlocking {
      seedColleges()
      // Real CDS reporting: "applications close in March". Stored raw (never
      // interpolated to a day), but the launch-set gate counts only complete
      // month+day dates, so it must not inflate deadlinesWithDateCount.
      val result = loader.load(meritCsv, factorsCsv, fixture("cds-deadlines-half-date-fixture.csv"))
      assertEquals(1, result.deadlines.upserted)

      val round = withSession { CdsAdmissionsDao.listDeadlines(it, collegeId(110100), 2024).getOrThrow() }.single()
      assertEquals(CdsMonthDay(3, null), round.closing)
      assertNull(round.notification)
      assertEquals(1, result.coverage.deadlinesFlagsCount)
      assertEquals(0, result.coverage.deadlinesWithDateCount)
    }

  @Test
  fun `a day with no month is a broken seed, not a half-date`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(meritCsv, factorsCsv, fixture("cds-deadlines-day-only-fixture.csv")) }
      }
    val defect = error.defect as CdsSeedLoader.Defect.DayWithoutMonth
    assertEquals(CdsSeedLoader.Table.DEADLINES, defect.table)
    assertEquals("closing_day", defect.dayColumn)
    assertEquals("closing_month", defect.monthColumn)
    assertEquals(15, defect.day)
  }

  @Test
  fun `an impossible calendar date is named by file and line, not left to the DB`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(meritCsv, factorsCsv, fixture("cds-deadlines-impossible-date-fixture.csv")) }
      }
    // Feb 30 is what a mangled extraction produces. Rejected HERE, with the
    // line and columns, rather than as an anonymous constraint violation.
    val defect = error.defect as CdsSeedLoader.Defect.NotACalendarDate
    assertEquals(CdsSeedLoader.Table.DEADLINES, defect.table)
    assertEquals(1L, defect.line)
    assertEquals("closing_month", defect.monthColumn)
    assertEquals("closing_day", defect.dayColumn)
    assertEquals(2, defect.month)
    assertEquals(30, defect.day)
    assertEquals(0, withSession { count(it, "college_deadlines") })
  }

  @Test
  fun `a ragged row fails as a located seed defect, not a raw parser error`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        runBlocking { loader.load(fixture("cds-merit-aid-short-row-fixture.csv"), factorsCsv, deadlinesCsv) }
      }
    // The header assertion proves the column NAMES; a truncated row would
    // otherwise escape as Commons CSV's unlocated IllegalArgumentException.
    val defect = error.defect as CdsSeedLoader.Defect.RowArity
    assertEquals(CdsSeedLoader.Table.MERIT_AID, defect.table)
    assertEquals(1L, defect.line)
    assertEquals(CdsSeedLoader.MERIT_AID_COLUMNS.size, defect.expectedCells)
    assertEquals(3, defect.cells)
  }

  @Test
  fun `a DB fault mid-load names the seed row that provoked it`() {
    seedColleges()
    val error =
      assertFailsWith<CdsSeedLoader.LoadException> {
        runBlocking { loader.load(fixture("cds-merit-aid-bad-year-fixture.csv"), factorsCsv, deadlinesCsv) }
      }
    // A source_year outside cds_source_year is refused by the DB, not by the
    // loader's own cell checks -- so this is the DB-fault path, and it must
    // still carry the row's seed coordinates rather than a bare
    // "Database constraint violation".
    assertEquals(CdsSeedLoader.Table.MERIT_AID, error.table)
    assertEquals(1L, error.line)
    assertEquals(110100, error.ipedsUnitId)
    assertTrue(error.cause is ed.unicoach.db.dao.ConstraintViolationException, "${error.cause}")
  }

  // ---------------------------------------------------------------------------
  // Provenance: the CDS load INSIDE the ingest run (RFC 148, D10)
  // ---------------------------------------------------------------------------

  private fun source(file: File) = SourceFile(file, file.path)

  private val cdsSources = CdsSources(source(meritCsv), source(factorsCsv), source(deadlinesCsv))

  /** One full ingest run over the Scorecard fixtures, with the CDS group
   * supplied or omitted — the only two shapes `bin/ingest-colleges` can produce. */
  private fun ingest(cds: CdsSources? = cdsSources): CollegeScorecardLoader.IngestReport =
    runBlocking {
      scorecardLoader.ingest(
        institution = source(fixture("scorecard-institutions-fixture.csv")),
        fields = source(fixture("scorecard-fields-empty-fixture.csv")),
        aliasesFile = source(fixture("college-aliases-fixture.json")),
        cds = cds,
      )
    }

  @Test
  fun `the build row records the CDS sources and row counts`() {
    val report = ingest()
    val load = assertNotNull(report.cds, "the run carries the CDS result it loaded")

    assertEquals(6, report.sources.size, "three Scorecard sources plus the three CDS seed files")
    // Digested the way every other source is: the recorded sha256 is the file's,
    // recomputed here independently rather than read back from the loader.
    val recorded = report.sources.first { it.fileName == meritCsv.name }
    val expectedDigest =
      MessageDigest
        .getInstance("SHA-256")
        .digest(meritCsv.readBytes())
        .joinToString("") { "%02x".format(it) }
    assertEquals(expectedDigest, recorded.sha256)
    assertEquals(meritCsv.length(), recorded.bytes)

    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    // 4, not RFC 148's prose "3": RFC 146 took 3 for the derived name-word
    // rebuild, so the CDS bump is the next number in the sequence.
    assertEquals(4, row.methodVersion)
    for (file in listOf(meritCsv, factorsCsv, deadlinesCsv)) {
      assertTrue(row.sources.contains(file.name), "sources names ${file.name}: ${row.sources}")
    }
    assertTrue(row.sources.contains(expectedDigest), "sources carries the CDS digest, not just the name: ${row.sources}")

    // The counts in the row ARE the counts the load reported.
    val rowsIngested = Json.parseToJsonElement(row.rowsIngested).jsonObject
    val cds = rowsIngested.getValue("cds").jsonObject

    fun upserted(table: String): Int =
      cds
        .getValue(table)
        .jsonObject
        .getValue("upserted")
        .jsonPrimitive.int
    // A SET, not a list: `sources`/`rows_ingested` are `jsonb`, which stores
    // object keys in its own normalised order, so key order is not a property
    // this row can carry and asserting it would only pin Postgres's ordering.
    assertEquals(
      setOf("merit_aid", "admission_factors", "deadlines"),
      cds.keys,
      "one block per CDS table",
    )
    val meritAid = cds.getValue("merit_aid").jsonObject
    assertEquals(load.meritAid.upserted, upserted("merit_aid"))
    assertEquals(2, upserted("merit_aid"))
    assertEquals(1, meritAid.getValue("skipped").jsonPrimitive.int)
    assertEquals(
      listOf(999999),
      meritAid.getValue("unmatched_ipeds_unit_ids").jsonArray.map { it.jsonPrimitive.int },
      "the seed schools our snapshot lacks are named, not merely counted",
    )
    assertEquals(2, upserted("admission_factors"))
    assertEquals(3, upserted("deadlines"))
    // The rows really landed: the build row describes a load that committed.
    assertEquals(2, withSession { count(it, "college_merit_aid") })
    assertEquals(3, withSession { count(it, "college_deadlines") })
  }

  @Test
  fun `a Scorecard-only run still writes a build row`() {
    val report = ingest(cds = null)
    assertNull(report.cds, "the whole CDS half is one absent value")
    assertEquals(3, report.sources.size)

    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    // Absent, never zero: a run that never read a seed file must not report
    // counts nobody measured.
    assertFalse(row.rowsIngested.contains("\"cds\""), "absent means absent: ${row.rowsIngested}")
    assertFalse(row.rowsIngested.contains("merit_aid"), row.rowsIngested)
    assertEquals(0, withSession { count(it, "college_merit_aid") })
  }

  @Test
  fun `the CDS phase commits before name-words and provenance`() {
    // The one way to observe phase ORDER from outside: hide the provenance
    // table so the last phase fails, and read what had committed. Restored in
    // the finally; the suite is sequential and bin/test recreates the test
    // database per run.
    renameBuildTable("college_index_build", "college_index_build_hidden")
    try {
      val thrown = assertFailsWith<PartialIngestException> { ingest() }
      assertEquals(listOf("institutions", "fields", "aliases", "cds", "name-words"), thrown.committedPhases)
      assertEquals("provenance", thrown.failedPhase)
      assertEquals(2, withSession { count(it, "college_merit_aid") }, "the cds phase committed before provenance ran")
    } finally {
      renameBuildTable("college_index_build_hidden", "college_index_build")
    }
  }

  /** The one DDL this suite issues, in one place: hiding `college_index_build`
   * is how phase ORDER is observed from outside the run. */
  private fun renameBuildTable(
    from: String,
    to: String,
  ) = withSession { session ->
    session.prepareStatement("ALTER TABLE $from RENAME TO $to").use { it.execute() }
  }

  @Test
  fun `a failed CDS load writes no build row at all`() {
    val buildRowsBefore = withSession { count(it, "college_index_build") }
    // A defect inside a ROW, not in a header: headers are asserted up front (see
    // below), so this is the failure that can only be found mid-load, and it is
    // the one that has to roll all three tables back as a unit.
    val thrown =
      assertFailsWith<PartialIngestException> {
        ingest(cdsSources.copy(admissionFactors = source(fixture("cds-admission-factors-junk-fixture.csv"))))
      }
    assertEquals("cds", thrown.failedPhase)
    assertTrue(thrown.cause is CdsSeedLoader.FormatException, "${thrown.cause}")
    // The CDS load is one transaction, so nothing from any of its three files
    // survives -- and, running before provenance, it left no build row to
    // describe a run that failed.
    assertEquals(0, withSession { count(it, "college_merit_aid") })
    assertEquals(0, withSession { count(it, "college_admission_factors") })
    assertEquals(0, withSession { count(it, "college_deadlines") })
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") }, "no build row describes a failed run")
  }

  @Test
  fun `a bad CDS header aborts the run before any phase commits`() {
    // The point of an up-front assertion: the CDS files are the last three of
    // ten, so if their headers were checked only when the cds phase ran, a
    // renamed column would be found AFTER institutions, fields, aliases and the
    // IPEDS phases had each committed their own transaction -- the half-written
    // snapshot the check exists to prevent. It must fail before phase one.
    val buildRowsBefore = withSession { count(it, "college_index_build") }
    val error =
      assertFailsWith<CdsSeedLoader.FormatException> {
        ingest(
          cdsSources.copy(
            meritAid =
              SourceFile(fixture("cds-merit-aid-bad-header-fixture.csv"), "s3://seed/merit-aid.csv"),
          ),
        )
      }
    val defect = error.defect as CdsSeedLoader.Defect.HeaderMismatch
    // The defect carries (path, sourceArg, missing) like the other seven files
    // of the same up-front check: an s3:// argument downloaded to a temp path
    // is only nameable through `sourceArg`.
    assertEquals(fixture("cds-merit-aid-bad-header-fixture.csv").path, defect.file)
    assertEquals("s3://seed/merit-aid.csv", defect.sourceArg)
    assertEquals(listOf("freshmen_ft_total"), defect.missing)
    // Not a PartialIngestException at all: nothing had committed to report.
    assertEquals(0, withSession { count(it, "colleges") }, "the institutions phase must not have run")
    assertEquals(0, withSession { count(it, "college_merit_aid") })
    assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") })
  }

  private data class BuildRow(
    val methodVersion: Int,
    val sources: String,
    val rowsIngested: String,
  )

  private fun buildRow(
    session: ed.unicoach.db.dao.SqlSession,
    id: java.util.UUID,
  ): BuildRow? =
    session
      .prepareStatement("SELECT method_version, sources::text, rows_ingested::text FROM college_index_build WHERE id = ?")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return null
          BuildRow(rs.getInt(1), rs.getString(2), rs.getString(3))
        }
      }

  @Test
  fun `the committed seed's headers are the headers this loader expects`() {
    // The generator (bin/fetch-cds-seed) owns the header, this loader restates
    // it, and until now the pairing that matters -- committed seed versus loader
    // constants -- was never exercised: a renamed column stayed green in CI and
    // fatalled only at operator ingest. Content-agnostic: whatever is committed
    // is read and compared.
    val expectedByFile =
      mapOf(
        "merit-aid.csv" to CdsSeedLoader.MERIT_AID_COLUMNS,
        "admission-factors.csv" to CdsSeedLoader.ADMISSION_FACTORS_COLUMNS,
        "deadlines.csv" to CdsSeedLoader.DEADLINES_COLUMNS,
      )
    for ((name, expected) in expectedByFile) {
      val header = File(committedSeedDir, name).useLines { it.first() }.split(",")
      assertEquals(expected, header, "db/seed/cds/$name header drifted from CdsSeedLoader")
    }
  }

  @Test
  fun `the committed seed matches its PROVENANCE manifest`() {
    // PROVENANCE.json exists so the seed is trustworthy and regenerable; its
    // hashes and row counts are a copy of what the CSVs own, so they are
    // recomputed and compared here. A hand edit or a half-regeneration fails.
    val manifest =
      Json.parseToJsonElement(File(committedSeedDir, "PROVENANCE.json").readText()).jsonObject
    val digests = manifest.getValue("sha256").jsonObject
    val rows = manifest.getValue("rows").jsonObject
    assertTrue(digests.isNotEmpty(), "PROVENANCE.json lists no sha256 entries")
    for ((name, digest) in digests) {
      val file = File(committedSeedDir, name)
      val actualDigest =
        MessageDigest
          .getInstance("SHA-256")
          .digest(file.readBytes())
          .joinToString("") { "%02x".format(it) }
      assertEquals(digest.jsonPrimitive.content, actualDigest, "$name: sha256 differs from PROVENANCE.json")
      // Data rows = every non-blank line but the header (the seed is CRLF, so
      // count lines rather than newline bytes).
      val dataRows = file.readLines().count { it.isNotBlank() } - 1
      assertEquals(rows.getValue(name).jsonPrimitive.int, dataRows, "$name: row count differs from PROVENANCE.json")
    }
  }
}
