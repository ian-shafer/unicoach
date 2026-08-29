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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

  private fun collegeId(unitId: Int): CollegeId = withSession { requireNotNull(CollegesDao.findByUnitId(it, unitId).getOrThrow()).id }

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
      assertEquals(2760, coastal.freshmenFtTotal)
      assertEquals(358, coastal.noNeedMeritCount)
      assertEquals(16112, coastal.noNeedMeritAvg)
      assertEquals("https://coastal.example.edu/cds-2024-25.pdf", coastal.sourceUrl)
      assertEquals("https://www.collegedata.fyi/schools/coastal/2024-25", coastal.archiveUrl)
      val lakeside = withSession { CdsAdmissionsDao.findMeritAid(it, collegeId(220200), 2025).getOrThrow() }
      assertNotNull(lakeside)
      assertNull(lakeside.noNeedMeritAvg)

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
      assertEquals(17000, after.noNeedMeritAvg)
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
    assertEquals("cds-merit-aid-bad-header-fixture.csv", defect.file)
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
    assertEquals(110100, error.unitId)
    assertTrue(error.cause is ed.unicoach.db.dao.ConstraintViolationException, "${error.cause}")
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
