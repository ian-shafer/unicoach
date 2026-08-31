package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.NewCollege
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The IPEDS half of one ingest run, end to end (RFC 144): the four extra header
 * assertions up front, the HD-driven load with IC/ADM left-joined, the census's
 * bachelor's-first-major filter, the unmatched-`ipeds_unit_id` count, and the two new
 * provenance blocks — which are ABSENT, not zero, when the group was not passed.
 */
class IpedsIngestTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val aliasesJson = fixture("college-aliases-fixture.json")

  /** Every UNITID in the joined HD fixture, so a test can choose which ones exist as colleges. */
  private val hdIpedsUnitIds =
    listOf(161280, 115728, 498979, 447971, 128577, 186131, 102234, 219338, 100690, 100663, 166027, 168342)

  private fun source(file: File): SourceFile = SourceFile(file, file.path)

  private fun ipedsSources(
    hd: File = fixture("ipeds-hd-joined-fixture.csv"),
    ic: File = fixture("ipeds-ic-fixture.csv"),
    adm: File = fixture("ipeds-adm-fixture.csv"),
    completions: File = fixture("ipeds-ca-fixture.csv"),
    surveyYear: Int = 2023,
  ) = IpedsSources(source(hd), source(ic), source(adm), source(completions), surveyYear)

  private fun seedColleges(ipedsUnitIds: List<Int>) =
    withSession { session ->
      for (ipedsUnitId in ipedsUnitIds) {
        CollegesDao
          .upsert(
            session,
            NewCollege(
              housingAndFoodOnCampusPerYearUsd = null,
              housingAndFoodOffCampusPerYearUsd = null,
              booksAndSuppliesPerYearUsd = null,
              otherExpensesOnCampusPerYearUsd = null,
              otherExpensesOffCampusPerYearUsd = null,
              otherExpensesWithFamilyPerYearUsd = null,
              ipedsUnitId = ipedsUnitId,
              opeid = null,
              name = "IPEDS U $ipedsUnitId",
              city = "Townsville",
              state = "ME",
              region = null,
              locale = null,
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
    }

  private fun ingest(ipeds: IpedsSources? = ipedsSources()): CollegeScorecardLoader.IngestReport =
    runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson), ipeds) }

  // ---------------------------------------------------------------------------
  // The attribute phase
  // ---------------------------------------------------------------------------

  @Test
  fun `every HD row with a matching college is loaded, and the rest are counted as unmatched`() {
    // 11 of the 12 HD institutions exist as colleges; 168342 deliberately does not.
    seedColleges(hdIpedsUnitIds - 168342)
    val report = ingest()
    val ipeds = assertNotNull(report.ipeds).attributes

    assertEquals(12, ipeds.seen)
    assertEquals(11, ipeds.inserted)
    assertEquals(0, ipeds.changed)
    assertEquals(0, ipeds.unchanged)
    assertEquals(1, ipeds.unmatchedIpedsUnitIds, "an unmatched ipeds_unit_id is counted and skipped, never invented")
    assertEquals(1, ipeds.skipsByReason[SkipReason.NoCollegeForIpedsUnitId])
    assertEquals(11, withSession { count(it, "college_ipeds") })
  }

  @Test
  fun `the IC and ADM halves are left-joined, and an ADM-less college keeps a NULL test policy`() {
    seedColleges(hdIpedsUnitIds)
    ingest()

    // 161280 (UMaine System Central Office): in HD and IC, absent from ADM.
    val umaine = assertNotNull(row(161280))
    assertNull(umaine.testPolicy, "no ADM row means unknown, never a fabricated policy code")
    assertEquals(0, umaine.sector, "D23: SECTOR = 0 flags the administrative unit")
    assertEquals(true, umaine.ugOffer, "it is loadable and inside the four-year universe")
    assertEquals(-2, umaine.relAffil, "the IC half joined: -2 = explicitly not religious")
    assertEquals(false, umaine.hasRotc, "IC's -2 is a real no")

    // 168342 (Williams) has an ADM row saying test-optional.
    assertEquals(5, assertNotNull(row(168342)).testPolicy)
    // 102234 (Spring Hill) has a free application and a denomination.
    val springHill = assertNotNull(row(102234))
    assertEquals(0, springHill.applicationFeeUsd, "0 is a REAL free application")
    assertEquals(30, springHill.relAffil)
    // 100690 (Amridge) reports no housing with ROOM=2.
    assertEquals(false, assertNotNull(row(100690)).offersHousing)
    // 219338 (Avera Sacred Heart) reported none of it: -1 is unknown.
    val avera = assertNotNull(row(219338))
    assertNull(avera.hasRotc)
    assertNull(avera.offersHousing)
    assertEquals("{}", avera.athleticAssoc)
    // 186131 (Princeton) is NCAA.
    assertEquals("{1}", assertNotNull(row(186131)).athleticAssoc)
  }

  @Test
  fun `an unmappable IC row is COUNTED, not silently dropped from the side-file pass`() {
    // A side file's losses are the phase's losses: an IC row with no UNITID
    // removes one institution's IC half, so it must land in the skip taxonomy
    // rather than in a DEBUG line nobody tallies.
    seedColleges(hdIpedsUnitIds)
    val icLines = fixture("ipeds-ic-fixture.csv").readLines()
    val blankIpedsUnitId = icLines[1].split(",").drop(1).joinToString(",", prefix = ",")
    val ic = File.createTempFile("ipeds-ic-blank-unitid", ".csv")
    ic.deleteOnExit()
    ic.writeText((icLines + blankIpedsUnitId).joinToString("\n") + "\n")

    val ipeds = assertNotNull(ingest(ipedsSources(ic = ic)).ipeds).attributes
    assertEquals(
      1,
      ipeds.skipsByReason[SkipReason.MissingRequiredField(listOf("ipeds_unit_id"))],
      "the IC row without a UNITID is counted: ${ipeds.skipsByReason}",
    )
    // The HD rows themselves are unaffected: 12 seen, 12 loaded.
    assertEquals(12, ipeds.seen)
    assertEquals(12, ipeds.loaded)
  }

  @Test
  fun `HD rows with no college at all leave the table empty rather than inventing institutions`() {
    // Not one HD ipeds_unit_id is seeded: the Scorecard fixture's colleges are other
    // institutions entirely.
    val report = ingest()
    val ipeds = assertNotNull(report.ipeds).attributes
    assertEquals(12, ipeds.unmatchedIpedsUnitIds)
    assertEquals(0, ipeds.inserted)
    assertEquals(0, withSession { count(it, "college_ipeds") })
  }

  @Test
  fun `re-ingesting the same files is a loudly visible no-op`() {
    seedColleges(hdIpedsUnitIds)
    ingest()
    // The other half of the no-op: an IPEDS-bearing run must not touch
    // `colleges`, so no new version row may appear across the second run.
    val versionsBefore = withSession { count(it, "colleges_versions") }
    val second = ingest()
    assertEquals(
      versionsBefore,
      withSession { count(it, "colleges_versions") },
      "an unchanged re-ingest bumps no college version",
    )
    val ipedsReport = assertNotNull(second.ipeds)
    val ipeds = ipedsReport.attributes
    val census = ipedsReport.census

    assertEquals(0, ipeds.inserted)
    assertEquals(0, ipeds.changed)
    assertEquals(12, ipeds.unchanged)
    assertEquals(0, census.inserted)
    assertEquals(0, census.changed)
    assertEquals(5, census.unchanged)

    val summary = second.humanSummary()
    assertTrue(summary.contains("0 inserted, 0 changed, 12 unchanged"), "the IPEDS no-op is printed: $summary")
  }

  @Test
  fun `a changed source value is reported as changed, not silently rewritten`() {
    seedColleges(hdIpedsUnitIds)
    ingest()
    // The same run against a survey year one later: every row's survey_year
    // differs, so all 12 must report CHANGED.
    val second = ingest(ipedsSources(surveyYear = 2024))
    assertEquals(12, assertNotNull(second.ipeds).attributes.changed)
    assertEquals(0, assertNotNull(second.ipeds).attributes.inserted)
  }

  // ---------------------------------------------------------------------------
  // The census phase
  // ---------------------------------------------------------------------------

  @Test
  fun `the census keeps bachelor's first majors only, so the natural key never collides`() {
    seedColleges(hdIpedsUnitIds)
    val report = ingest()
    val census = assertNotNull(report.ipeds).census

    assertEquals(9, census.seen, "every C_A row is read")
    assertEquals(5, census.selected, "AWLEVEL=5, MAJORNUM=1, CIPCODE<>'99'")
    assertEquals(5, census.inserted)
    assertEquals(0, census.skipped, "the excluded rows are FILTERED, not skipped: nothing failed")
    assertEquals(5, withSession { count(it, "college_programs_census") })

    // The duplicate-key trap: 166027 / 05.0104 / AWLEVEL 5 exists twice in the
    // fixture (MAJORNUM 1 and 2). Exactly one row lands, with the first major's
    // count -- not a doubled one, and not a unique violation.
    val awards =
      withSession { session ->
        session
          .prepareStatement(
            "SELECT awards_count FROM college_programs_census c " +
              "JOIN colleges g ON g.id = c.college_id " +
              "WHERE g.ipeds_unit_id = 166027 AND c.cip_code = '050104'",
          ).use { stmt ->
            stmt.executeQuery().use { rs ->
              val values = mutableListOf<Int>()
              while (rs.next()) values += rs.getInt(1)
              values
            }
          }
      }
    assertEquals(listOf(8), awards)

    // Princeton's CIPCODE="99" grand total (1284) is excluded, so the table
    // never carries the sum of its own rows.
    assertEquals(
      0,
      withSession { session ->
        session
          .prepareStatement("SELECT count(*) FROM college_programs_census WHERE awards_count = 1284")
          .use { stmt ->
            stmt.executeQuery().use { rs ->
              rs.next()
              rs.getInt(1)
            }
          }
      },
    )
  }

  @Test
  fun `a census row whose college is absent is counted and skipped`() {
    // 186131 exists; 166027 does not, so its two selected rows are unmatched.
    seedColleges(listOf(186131))
    val census = assertNotNull(ingest().ipeds).census
    assertEquals(5, census.selected)
    assertEquals(3, census.inserted)
    assertEquals(2, census.unmatchedIpedsUnitIds)
  }

  // ---------------------------------------------------------------------------
  // Malformed rows: counted skips, never a dead phase and never a silent loss
  // ---------------------------------------------------------------------------

  /** [name]'s fixture with [extraRows] appended verbatim, as a temp file. */
  private fun withExtraRows(
    name: String,
    vararg extraRows: String,
  ): File {
    val file = File.createTempFile(name.removeSuffix(".csv") + "-extra", ".csv")
    file.deleteOnExit()
    file.writeText((fixture(name).readLines() + extraRows).joinToString("\n") + "\n")
    return file
  }

  @Test
  fun `a row whose field count differs from the header is a counted skip, not a dead phase`() {
    // A short row makes CSVRecord.get(name) throw from whichever cell the
    // mapper reads first, which would abort the phase and turn the run into
    // PARTIAL INGEST; a long row's surplus cells are read by nobody. Both are
    // malformed rows, so both are counted like every other malformed row.
    seedColleges(hdIpedsUnitIds)
    val short = "910010,1"
    val long = fixture("ipeds-hd-joined-fixture.csv").readLines()[1] + ",surplus,cells"
    val hd = withExtraRows("ipeds-hd-joined-fixture.csv", short, long)

    val ipeds = assertNotNull(ingest(ipedsSources(hd = hd)).ipeds).attributes
    assertEquals(14, ipeds.seen, "both ragged rows were read")
    assertEquals(2, ipeds.skipsByReason[SkipReason.RowArityMismatch], "counted: ${ipeds.skipsByReason}")
    assertEquals("row_arity_mismatch", SkipReason.RowArityMismatch.kind)
    assertEquals(12, ipeds.loaded, "the 12 well-formed rows still load")
  }

  @Test
  fun `a repeated ipeds_unit_id keeps the first row and counts the loser, in HD and in a side file`() {
    // Silent last-wins would let file order decide an institution's attributes,
    // and a WARN nobody tallies leaves the run reporting 0 skipped while half
    // an institution was thrown away.
    seedColleges(hdIpedsUnitIds)
    val hdLines = fixture("ipeds-hd-joined-fixture.csv").readLines()
    val hd = withExtraRows("ipeds-hd-joined-fixture.csv", hdLines[1])
    val icLines = fixture("ipeds-ic-fixture.csv").readLines()
    val ic = withExtraRows("ipeds-ic-fixture.csv", icLines[1])

    val ipeds = assertNotNull(ingest(ipedsSources(hd = hd, ic = ic)).ipeds).attributes
    assertEquals(2, ipeds.skipsByReason[SkipReason.DuplicateKeyInFile], "both twins counted: ${ipeds.skipsByReason}")
    assertEquals("duplicate_key_in_file", SkipReason.DuplicateKeyInFile.kind)
    assertEquals(13, ipeds.seen)
    assertEquals(12, ipeds.loaded, "the repeat is dropped, not written a second time")
  }

  @Test
  fun `a census row with an unreadable filter cell is skipped and counted, not silently excluded`() {
    // "Not a bachelor's first major" and "we could not tell" are different
    // answers: the second is a malformed row and belongs in the skip taxonomy,
    // not in the deliberate seen - selected exclusion.
    seedColleges(hdIpedsUnitIds)
    val caLines = fixture("ipeds-ca-fixture.csv").readLines()
    val header = caLines[0].removePrefix("\uFEFF").split(",")
    val blankAwLevel =
      header.joinToString(",") { column ->
        when (column.trim()) {
          "UNITID" -> "186131"
          "CIPCODE" -> "\"11.0701\""
          "MAJORNUM" -> "1"
          "AWLEVEL" -> ""
          "CTOTALT" -> "4"
          else -> ""
        }
      }
    val completions = withExtraRows("ipeds-ca-fixture.csv", blankAwLevel)

    val census = assertNotNull(ingest(ipedsSources(completions = completions)).ipeds).census
    assertEquals(10, census.seen)
    assertEquals(5, census.selected, "the unjudgeable row is NOT selected")
    assertEquals(
      1,
      census.skipsByReason[SkipReason.MissingRequiredField(listOf("award_level"))],
      "it is counted: ${census.skipsByReason}",
    )
    assertEquals(5, census.inserted)
  }

  @Test
  fun `a repeated census key keeps the first row and counts the loser`() {
    seedColleges(hdIpedsUnitIds)
    val caLines = fixture("ipeds-ca-fixture.csv").readLines()
    // Princeton's 04.0201 bachelor's first-major row, repeated verbatim.
    val selectedRow = caLines.first { it.startsWith("186131,\"04.0201\"") }
    val completions = withExtraRows("ipeds-ca-fixture.csv", selectedRow)

    val census = assertNotNull(ingest(ipedsSources(completions = completions)).ipeds).census
    assertEquals(6, census.selected, "both copies pass the filter")
    assertEquals(1, census.skipsByReason[SkipReason.DuplicateKeyInFile], "${census.skipsByReason}")
    assertEquals(5, census.inserted, "the twin is dropped rather than upserted over the first")
  }

  // ---------------------------------------------------------------------------
  // Header assertions: fatal, before any write, for all four files
  // ---------------------------------------------------------------------------

  private fun headerOnly(
    columns: List<String>,
    drop: String,
  ): File {
    val file = File.createTempFile("ipeds-missing-column", ".csv")
    file.deleteOnExit()
    file.writeText((columns - drop).joinToString(",") + "\n")
    return file
  }

  @Test
  fun `a missing required column in any IPEDS file is fatal, writing nothing at all`() {
    seedColleges(hdIpedsUnitIds)
    val cases =
      listOf(
        "CYACTIVE" to
          ipedsSources(hd = headerOnly(IpedsLoader.REQUIRED_HD_COLUMNS, "CYACTIVE")),
        "SLO5" to
          ipedsSources(ic = headerOnly(IpedsLoader.REQUIRED_IC_COLUMNS, "SLO5")),
        "ADMCON7" to
          ipedsSources(adm = headerOnly(IpedsLoader.REQUIRED_ADM_COLUMNS, "ADMCON7")),
        "CTOTALT" to
          ipedsSources(completions = headerOnly(IpedsLoader.REQUIRED_COMPLETIONS_COLUMNS, "CTOTALT")),
      )
    val buildRowsBefore = withSession { count(it, "college_index_build") }
    for ((column, sources) in cases) {
      val thrown = assertThrows<MissingSourceColumnsException> { ingest(sources) }
      assertEquals(listOf(column), thrown.missing)
      // Fatal BEFORE phase 1: the Scorecard institutions are not written
      // either — only the 12 colleges this test seeded exist.
      assertEquals(hdIpedsUnitIds.size, withSession { count(it, "colleges") }, "[$column] must abort before any write")
      assertNull(
        withSession { CollegesDao.findByIpedsUnitId(it, 110100).getOrThrow() },
        "[$column] must abort before the Scorecard institutions phase",
      )
      assertEquals(0, withSession { count(it, "college_ipeds") })
      assertEquals(0, withSession { count(it, "college_programs_census") })
      assertEquals(buildRowsBefore, withSession { count(it, "college_index_build") })
      seedColleges(hdIpedsUnitIds)
    }
  }

  // ---------------------------------------------------------------------------
  // Provenance
  // ---------------------------------------------------------------------------

  @Test
  fun `the build row gains the IPEDS blocks, the four digests, and the current method_version`() {
    seedColleges(hdIpedsUnitIds)
    val report = ingest()
    assertEquals(7, report.sources.size, "three Scorecard sources plus the four IPEDS files")

    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    // 4 since RFC 146 added the derived name-word rebuild and RFC 148 the CDS
    // seed load; 2 was RFC 144's own bump for this IPEDS source family.
    assertEquals(4, row.methodVersion)
    assertTrue(row.sources.contains("ipeds-hd-joined-fixture.csv"), "sources names the HD file: ${row.sources}")
    assertTrue(row.rowsIngested.contains("\"ipeds\""), "rows_ingested carries the ipeds block: ${row.rowsIngested}")
    assertTrue(row.rowsIngested.contains("\"programs_census\""), row.rowsIngested)
    assertTrue(row.rowsIngested.contains("\"survey_year\": 2023"), row.rowsIngested)
    assertTrue(row.rowsIngested.contains("\"unmatched_ipeds_unit_ids\": 0"), row.rowsIngested)
    assertTrue(row.rowsIngested.contains("\"selected\": 5"), row.rowsIngested)
    assertTrue(
      row.changeSummary.contains("\"college_ipeds\"") && row.changeSummary.contains("\"test_policy\""),
      "change_summary carries the per-column IPEDS axis: ${row.changeSummary}",
    )
  }

  @Test
  fun `a run without the IPEDS group OMITS the keys entirely, never writing them as zeros`() {
    val report = ingest(ipeds = null)
    assertNull(report.ipeds, "the whole IPEDS half is one absent value, not five nullable slots")
    assertEquals(3, report.sources.size)

    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    assertFalse(row.rowsIngested.contains("\"ipeds\""), "absent means absent: ${row.rowsIngested}")
    assertFalse(row.rowsIngested.contains("\"programs_census\""), row.rowsIngested)
    assertFalse(row.changeSummary.contains("college_ipeds"), row.changeSummary)
    // The summary stays the RFC 139 one, with no fabricated IPEDS lines.
    assertFalse(report.humanSummary().contains("ipeds:"), report.humanSummary())
  }

  @Test
  fun `the non-null change summary measures college_ipeds before and after`() {
    seedColleges(hdIpedsUnitIds)
    val report = ingest()
    assertEquals(0, assertNotNull(report.ipeds).nonNullBefore["test_policy"])
    // Only 3 of the 8 ADM fixture rows name an institution in the HD fixture
    // (186131, 166027, 168342); the other five are outside it, and the
    // remaining HD institutions have no ADM row at all.
    assertEquals(3, assertNotNull(report.ipeds).nonNullAfter["test_policy"])
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private data class IpedsRow(
    val sector: Int?,
    val ugOffer: Boolean?,
    val relAffil: Int?,
    val hasRotc: Boolean?,
    val offersHousing: Boolean?,
    val applicationFeeUsd: Int?,
    val testPolicy: Int?,
    val athleticAssoc: String,
  )

  private fun row(ipedsUnitId: Int): IpedsRow? =
    withSession { session ->
      session
        .prepareStatement(
          "SELECT sector, ug_offer, rel_affil, has_rotc, offers_housing, application_fee_usd, test_policy, " +
            "athletic_assoc::text FROM college_ipeds WHERE ipeds_unit_id = ?",
        ).use { stmt ->
          stmt.setInt(1, ipedsUnitId)
          stmt.executeQuery().use { rs ->
            if (!rs.next()) {
              null
            } else {
              IpedsRow(
                sector = rs.getInt(1).takeUnless { rs.wasNull() },
                ugOffer = rs.getBoolean(2).takeUnless { rs.wasNull() },
                relAffil = rs.getInt(3).takeUnless { rs.wasNull() },
                hasRotc = rs.getBoolean(4).takeUnless { rs.wasNull() },
                offersHousing = rs.getBoolean(5).takeUnless { rs.wasNull() },
                applicationFeeUsd = rs.getInt(6).takeUnless { rs.wasNull() },
                testPolicy = rs.getInt(7).takeUnless { rs.wasNull() },
                athleticAssoc = rs.getString(8),
              )
            }
          }
        }
    }

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
