package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.models.NewCollegeIpeds
import org.apache.commons.csv.CSVRecord
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The IPEDS row mappers, over the BYTE-VERBATIM 2023 fixtures (original BOM,
 * CRLF, space-padded sentinels, quoted dotted CIP codes, the duplicate-key
 * trap). The mappers are pure, so this suite needs no database: every case
 * below is one of the three missing-value conventions the four files use, and
 * getting any of them wrong is a silent data lie rather than a crash.
 */
class IpedsLoaderTest {
  // The loader's mappers touch neither the DB nor the dispatcher; the instance
  // exists only to reach them.
  private val loader =
    IpedsLoader(
      Database(
        DatabaseConfig
          .from(
            ed.unicoach.common.config.AppConfig
              .load("common.conf", "db.conf")
              .getOrThrow(),
          ).getOrThrow(),
      ),
    )

  private fun fixture(name: String): File {
    val url = requireNotNull(this::class.java.classLoader.getResource(name)) { "missing fixture [$name]" }
    return File(url.toURI())
  }

  private fun rows(name: String): List<CSVRecord> = parseCsv(fixture(name)).use { it.toList() }

  private fun hdRows(): Map<Int, NewCollegeIpeds> =
    rows("ipeds-hd-fixture.csv").associate { record ->
      val mapped = loader.mapHd(record)
      assertTrue(mapped is MapResult.Mapped, "HD row [${record.recordNumber}] should map: $mapped")
      mapped.value.ipedsUnitId to mapped.value.toRow(2023, null, null)
    }

  private fun icRows(): Map<Int, IpedsLoader.IcAttributes> =
    rows("ipeds-ic-fixture.csv").associate { record ->
      val mapped = loader.mapIc(record)
      assertTrue(mapped is MapResult.Mapped, "IC row [${record.recordNumber}] should map: $mapped")
      mapped.value.ipedsUnitId to mapped.value
    }

  private fun admRows(): Map<Int, IpedsLoader.AdmAttributes> =
    rows("ipeds-adm-fixture.csv").associate { record ->
      val mapped = loader.mapAdm(record)
      assertTrue(mapped is MapResult.Mapped, "ADM row [${record.recordNumber}] should map: $mapped")
      mapped.value.ipedsUnitId to mapped.value
    }

  // ---------------------------------------------------------------------------
  // Reader: BOM, CRLF, trailing header spaces
  // ---------------------------------------------------------------------------

  @Test
  fun `the UTF-8 BOM is stripped, so the first column is UNITID and not a BOM-prefixed name`() {
    // HD, IC and C_A all start with EF BB BF. Read as plain UTF-8, the first
    // header name would be "\uFEFFUNITID" and every header assertion would fail.
    for (name in listOf("ipeds-hd-fixture.csv", "ipeds-ic-fixture.csv", "ipeds-ca-fixture.csv")) {
      val header = parseCsv(fixture(name)).use { it.headerMap.keys.toList() }
      assertEquals("UNITID", header.first(), "[$name] must not carry the BOM into its first column name")
    }
  }

  @Test
  fun `header names are trimmed, so adm2023's trailing-space last column is reachable`() {
    val header = parseCsv(fixture("ipeds-adm-fixture.csv")).use { it.headerMap.keys.toList() }
    assertEquals("ACTMT75", header.last(), "the raw ADM header line ends in spaces: [${header.last()}]")
  }

  @Test
  fun `every required column list is satisfied by the real headers`() {
    CsvIngestSupport.assertRequiredColumns(source("ipeds-hd-fixture.csv"), IpedsLoader.REQUIRED_HD_COLUMNS)
    CsvIngestSupport.assertRequiredColumns(source("ipeds-ic-fixture.csv"), IpedsLoader.REQUIRED_IC_COLUMNS)
    CsvIngestSupport.assertRequiredColumns(source("ipeds-adm-fixture.csv"), IpedsLoader.REQUIRED_ADM_COLUMNS)
    CsvIngestSupport.assertRequiredColumns(
      source("ipeds-ca-fixture.csv"),
      IpedsLoader.REQUIRED_COMPLETIONS_COLUMNS,
    )
  }

  private fun source(name: String): SourceFile = SourceFile(fixture(name), fixture(name).path)

  // ---------------------------------------------------------------------------
  // HD sentinels
  // ---------------------------------------------------------------------------

  @Test
  fun `CLOSEDAT's space-padded -2 sentinel and 00-00-0000 both become NULL, a real date parses`() {
    val hd = hdRows()
    // 161280 and 128577 carry the literal 10-char sentinel '-2        '.
    assertNull(hd.getValue(161280).closedAt, "'-2        ' is the sentinel, not a date")
    assertNull(hd.getValue(128577).closedAt)
    // 447971 carries 00/00/0000, which is not a date and must not crash a parse.
    assertNull(hd.getValue(447971).closedAt, "00/00/0000 is not a date")
    // 115728 (Holy Names University) really did close.
    assertEquals(LocalDate.of(2023, 5, 15), hd.getValue(115728).closedAt)
  }

  @Test
  fun `the 00-00-0000 row is tallied as a coercion, not silently dropped`() {
    val record = rows("ipeds-hd-fixture.csv").single { CsvIngestSupport.intOrNull(it, "UNITID") == 447971 }
    val mapped = loader.mapHd(record)
    assertTrue(mapped is MapResult.Mapped, "$mapped")
    assertEquals(1, mapped.coercions["closed_at"], "an unparseable date must be counted: ${mapped.coercions}")
  }

  @Test
  fun `DEATHYR and NEWID treat -2 as still-alive and not-merged, never as year -2`() {
    val hd = hdRows()
    assertNull(hd.getValue(161280).deathYear)
    assertNull(hd.getValue(161280).newIpedsUnitId)
    assertEquals(2023, hd.getValue(115728).deathYear)
    // 128577 (Asnuntuck CC) merged into 129367.
    assertEquals(129367, hd.getValue(128577).newIpedsUnitId)
  }

  @Test
  fun `CYACTIVE maps 1 to true and 3 to false, with no third state invented`() {
    val hd = hdRows()
    assertTrue(hd.getValue(161280).cyActive)
    assertFalse(hd.getValue(115728).cyActive, "CYACTIVE=3 means NOT active")
  }

  @Test
  fun `ICLEVEL and UGOFFER -3 are unknown, not a level and not a no`() {
    // 498979 (Jersey College-Cleveland) has ICLEVEL=-3 and UGOFFER=-3.
    val row = hdRows().getValue(498979)
    assertNull(row.instLevel)
    assertNull(row.ugOffer, "-3 is 'not available', which is not the same claim as 'offers no undergraduate'")
    // 128577 offers undergraduate study; 161280 does too.
    assertEquals(true, hdRows().getValue(128577).ugOffer)
  }

  @Test
  fun `the Carnegie codes keep -2, because it is a real exclusion and not a gap`() {
    val row = hdRows().getValue(161280)
    assertEquals(-2, row.carnegieBasic, "-2 = not in the Carnegie universe, which is an assertion, not a gap")
    assertEquals(-2, row.carnegieSize)
    assertEquals(12620, row.cbsa)
  }

  @Test
  fun `SECTOR is kept raw, so an administrative unit is flagged rather than hidden`() {
    // D23: UNITID 161280 is the UMaine System Central Office. It passes the
    // ICLEVEL/UGOFFER/CYACTIVE/PSET4FLG quartet, so SECTOR=0 is the ONLY
    // discriminator the index can use to keep a system office out of results.
    val row = hdRows().getValue(161280)
    assertEquals(0, row.sector)
    assertEquals(1, row.instLevel)
    assertEquals(true, row.ugOffer)
  }

  // ---------------------------------------------------------------------------
  // IC sentinels
  // ---------------------------------------------------------------------------

  @Test
  fun `SLO5 and SLO6 -2 mean a real no, while -1 means unknown`() {
    val ic = icRows()
    // 161280's IC row is all -2 (not applicable) — a real "no ROTC here".
    assertEquals(false, ic.getValue(161280).hasRotc)
    assertEquals(false, ic.getValue(161280).hasStudyAbroad)
    // 219338 (Avera Sacred Heart) reported nothing: -1 is genuinely unknown.
    assertNull(ic.getValue(219338).hasRotc)
    assertNull(ic.getValue(219338).hasStudyAbroad)
    // 100690 (Amridge) left the checkbox unticked: 0 is an implied no.
    assertEquals(false, ic.getValue(100690).hasRotc)
    assertEquals(true, ic.getValue(186131).hasRotc)
  }

  @Test
  fun `RELAFFIL keeps -2, which is the claim 'explicitly not religious'`() {
    val ic = icRows()
    assertEquals(-2, ic.getValue(186131).relAffil)
    assertEquals(30, ic.getValue(102234).relAffil, "30 = Roman Catholic")
    assertEquals(74, ic.getValue(100690).relAffil)
  }

  @Test
  fun `CONFNO1 keeps -2 (no football conference) and nulls -1 (unreported)`() {
    val ic = icRows()
    assertEquals(-2, ic.getValue(100690).footballConf)
    assertEquals(117, ic.getValue(186131).footballConf)
    assertNull(ic.getValue(219338).footballConf)
  }

  @Test
  fun `the dot sentinel nulls DISABPCT and ROOMCAP without touching a real value`() {
    val ic = icRows()
    assertNull(ic.getValue(161280).disabilityPct, "'.' is not a number")
    assertNull(ic.getValue(161280).housingCapacity)
    assertEquals(17.0, ic.getValue(166027).disabilityPct)
    assertEquals(14344, ic.getValue(166027).housingCapacity)
  }

  @Test
  fun `DISAB is a band code, and both -1 and -2 are unknown for it`() {
    val ic = icRows()
    assertEquals(2, ic.getValue(166027).disabilityBand, "2 = more than 3% of undergraduates registered")
    assertEquals(1, ic.getValue(100690).disabilityBand)
    assertNull(ic.getValue(161280).disabilityBand, "-2 tells us nothing about the band")
    assertNull(ic.getValue(219338).disabilityBand)
  }

  @Test
  fun `APPLFEEU zero is a real free application, distinct from the dot sentinel`() {
    val ic = icRows()
    assertEquals(0, ic.getValue(102234).applicationFee, "0 is a FREE application, not a missing one")
    assertNull(ic.getValue(161280).applicationFee, "'.' is not reported")
    assertEquals(70, ic.getValue(186131).applicationFee)
  }

  @Test
  fun `ROOM 2 means no housing, not a zero-valued yes`() {
    val ic = icRows()
    assertEquals(false, ic.getValue(100690).hasHousing, "ROOM=2 is No")
    assertEquals(true, ic.getValue(166027).hasHousing)
    assertNull(ic.getValue(161280).hasHousing)
    assertNull(ic.getValue(219338).hasHousing)
  }

  @Test
  fun `athletic_assoc carries the ordinals whose flag is 1, and is empty when all are -1`() {
    val ic = icRows()
    assertEquals(listOf(1), ic.getValue(186131).athleticAssoc, "Princeton is NCAA (ASSOC1) only")
    assertEquals(emptyList(), ic.getValue(161280).athleticAssoc)
    // The documented tri-state gap: all six flags are -1 (unreported) and the
    // NOT NULL column cannot say so.
    assertEquals(emptyList(), ic.getValue(219338).athleticAssoc)
  }

  @Test
  fun `each ASSOC flag contributes its own ordinal, so 2 to 6 are not read as ASSOC1`() {
    // Every real IC fixture row is ASSOC1-only or empty, so the 2..6 half of the
    // ordinal mapping would go unverified. The fixtures are byte-verbatim and
    // must not be edited, so this pins the correspondence on a synthetic row:
    // an ASSOC1-only mapper, or one off by one, fails here.
    val columns = IpedsLoader.REQUIRED_IC_COLUMNS
    val values = mapOf("UNITID" to "910002", "ASSOC2" to "1", "ASSOC3" to "1", "ASSOC6" to "1")
    val file = File.createTempFile("ipeds-ic-assoc", ".csv")
    file.deleteOnExit()
    file.writeText(columns.joinToString(",") + "\n" + columns.joinToString(",") { values[it] ?: "0" } + "\n")
    val record = parseCsv(file).use { it.toList() }.single()
    val mapped = loader.mapIc(record)
    assertTrue(mapped is MapResult.Mapped, "$mapped")
    assertEquals(listOf(2, 3, 6), mapped.value.athleticAssoc, "ASSOCi must contribute ordinal i, in order")
  }

  // ---------------------------------------------------------------------------
  // ADM
  // ---------------------------------------------------------------------------

  @Test
  fun `ADMCON7 keeps the raw 1-3-5 policy codes`() {
    val adm = admRows()
    assertEquals(5, adm.getValue(186131).testPolicy, "5 = test optional")
    assertEquals(3, adm.getValue(101365).testPolicy, "3 = test blind")
    assertEquals(1, adm.getValue(101453).testPolicy, "1 = required")
  }

  @Test
  fun `an institution absent from ADM gets a NULL test policy, never a fabricated code`() {
    // 161280 is in HD and IC but has no ADM row at all — as 749 of the 2,488
    // four-year universe institutions do not.
    assertNull(admRows()[161280])
    val row = hdRows().getValue(161280)
    val joined =
      IpedsLoader
        .HdAttributes(
          ipedsUnitId = row.ipedsUnitId,
          cyActive = row.cyActive,
          deathYear = row.deathYear,
          closedAt = row.closedAt,
          newIpedsUnitId = row.newIpedsUnitId,
          instLevel = row.instLevel,
          ugOffer = row.ugOffer,
          sector = row.sector,
          carnegieBasic = row.carnegieBasic,
          carnegieSize = row.carnegieSize,
          cbsa = row.cbsa,
        ).toRow(2023, icRows()[161280], admRows()[161280])
    assertNull(joined.testPolicy)
    assertEquals(-2, joined.relAffil, "the IC half still joined; only ADM is missing")
  }

  // ---------------------------------------------------------------------------
  // Completions (C_A)
  // ---------------------------------------------------------------------------

  @Test
  fun `the bachelor's first-major filter drops second majors, grand totals and other levels`() {
    val selected =
      rows("ipeds-ca-fixture.csv")
        .filter { loader.selectBachelorsFirstMajor(it) == IpedsLoader.CensusSelection.Selected }
        .map { CsvIngestSupport.intOrNull(it, "UNITID") to CsvIngestSupport.stringOrNull(it, "CIPCODE") }
    assertEquals(
      listOf(
        186131 to "04.0201",
        186131 to "05.0104",
        186131 to "05.0108",
        166027 to "03.0103",
        166027 to "05.0104",
      ),
      selected,
    )
    // The fixture deliberately holds the duplicate-key trap: 166027 / 05.0104 /
    // AWLEVEL 5 appears with MAJORNUM 1 AND 2. Only one survives the filter, so
    // the (college, cip, level) key never collides.
    assertEquals(1, selected.count { it == 166027 to "05.0104" })
  }

  @Test
  fun `CIPCODE loses its dot and becomes the six digits the schema stores`() {
    val record =
      rows("ipeds-ca-fixture.csv").first {
        loader.selectBachelorsFirstMajor(it) == IpedsLoader.CensusSelection.Selected &&
          CsvIngestSupport.stringOrNull(it, "CIPCODE") == "04.0201"
      }
    val mapped = loader.mapCompletion(record)
    assertTrue(mapped is MapResult.Mapped, "$mapped")
    assertEquals("040201", mapped.value.cipCode)
    assertEquals(5, mapped.value.awardLevel)
    assertEquals(6, mapped.value.awardsTotal)
  }

  @Test
  fun `a CIPCODE that is not six digits is skipped under its own named bucket`() {
    val record =
      rows("ipeds-ca-fixture.csv").first { CsvIngestSupport.stringOrNull(it, "CIPCODE") == "99" }
    val mapped = loader.mapCompletion(record)
    assertTrue(mapped is MapResult.Skipped, "$mapped")
    assertEquals(SkipReason.CipCodeMalformed, mapped.reason)
    assertEquals("cip_code_malformed", mapped.reason.kind)
  }

  @Test
  fun `the grand-total row is excluded by the filter before it ever reaches the mapper`() {
    val totals = rows("ipeds-ca-fixture.csv").filter { CsvIngestSupport.stringOrNull(it, "CIPCODE") == "99" }
    assertEquals(1, totals.size, "the fixture carries Princeton's 1284 grand total")
    assertTrue(totals.none { loader.selectBachelorsFirstMajor(it) == IpedsLoader.CensusSelection.Selected })
  }

  @Test
  fun `an HD row with no UNITID is skipped with its missing field named`() {
    val header = IpedsLoader.REQUIRED_HD_COLUMNS.joinToString(",")
    val blank = IpedsLoader.REQUIRED_HD_COLUMNS.joinToString(",") { if (it == "CYACTIVE") "1" else "" }
    val file = File.createTempFile("ipeds-hd-blank", ".csv")
    file.deleteOnExit()
    file.writeText("$header\n$blank\n")
    val record = parseCsv(file).use { it.toList() }.single()
    val mapped = loader.mapHd(record)
    assertTrue(mapped is MapResult.Skipped, "$mapped")
    assertEquals(SkipReason.MissingRequiredField(listOf("ipeds_unit_id")), mapped.reason)
  }

  @Test
  fun `an unreadable AWLEVEL or MAJORNUM is a counted skip, not a silent exclusion`() {
    // "No" and "we could not tell" are different answers: a blank filter cell
    // must land in the skip taxonomy, where MissingRequiredField already is,
    // rather than vanishing into seen - selected as a deliberate exclusion.
    val columns = IpedsLoader.REQUIRED_COMPLETIONS_COLUMNS
    val row =
      mapOf("UNITID" to "910003", "CIPCODE" to "11.0701", "MAJORNUM" to "1", "AWLEVEL" to "", "CTOTALT" to "3")
    val file = File.createTempFile("ipeds-ca-blank-awlevel", ".csv")
    file.deleteOnExit()
    file.writeText(columns.joinToString(",") + "\n" + columns.joinToString(",") { row.getValue(it) } + "\n")
    val record = parseCsv(file).use { it.toList() }.single()
    val selection = loader.selectBachelorsFirstMajor(record)
    assertTrue(selection is IpedsLoader.CensusSelection.Skipped, "$selection")
    assertEquals(SkipReason.MissingRequiredField(listOf("award_level")), selection.reason)
  }

  @Test
  fun `SECTOR accepts only the published code set, coercing an unpublished code and tallying it`() {
    // 0..9 plus 99 is what IPEDS publishes; a shifted-column read landing 50
    // would otherwise be stored as an authoritative sector.
    val columns = IpedsLoader.REQUIRED_HD_COLUMNS

    fun hdWithSector(sector: String): MapResult<IpedsLoader.HdAttributes> {
      val values = mapOf("UNITID" to "910004", "CYACTIVE" to "1", "SECTOR" to sector)
      val file = File.createTempFile("ipeds-hd-sector", ".csv")
      file.deleteOnExit()
      file.writeText(columns.joinToString(",") + "\n" + columns.joinToString(",") { values[it] ?: "-2" } + "\n")
      return loader.mapHd(parseCsv(file).use { it.toList() }.single())
    }
    val junk = hdWithSector("50")
    assertTrue(junk is MapResult.Mapped, "$junk")
    assertNull(junk.value.sector, "50 is not a published SECTOR code")
    assertEquals(1, junk.coercions["sector"], "the coercion is tallied, never silently dropped: ${junk.coercions}")
    for (code in listOf("0", "9", "99")) {
      val ok = hdWithSector(code)
      assertTrue(ok is MapResult.Mapped, "$ok")
      assertEquals(code.toInt(), ok.value.sector, "[$code] is a published SECTOR code")
      assertNull(ok.coercions["sector"])
    }
  }

  @Test
  fun `an out-of-set ADMCON7 is coerced and tallied under its own column name`() {
    // The domain is the SET {1, 3, 5}: 4 is out of it even though it sits
    // inside [1, 5], which is what the range-formatted log used to claim.
    val columns = IpedsLoader.REQUIRED_ADM_COLUMNS
    val file = File.createTempFile("ipeds-adm-testpolicy", ".csv")
    file.deleteOnExit()
    file.writeText(columns.joinToString(",") + "\n" + "910005,4\n")
    val mapped = loader.mapAdm(parseCsv(file).use { it.toList() }.single())
    assertTrue(mapped is MapResult.Mapped, "$mapped")
    assertNull(mapped.value.testPolicy)
    assertEquals(1, mapped.coercions["test_policy"], "${mapped.coercions}")
  }

  @Test
  fun `the REQUIRED column lists cover every column the mappers read`() {
    // Coverage guarantee (RFC 139): synthesize CSVs whose headers are EXACTLY
    // the REQUIRED_* lists. Every cell read goes through the loud isMapped
    // check, so a mapper reading a column missing from the shared list throws.
    fun oneRow(
      columns: List<String>,
      values: Map<String, String>,
    ): CSVRecord {
      val file = File.createTempFile("ipeds-coverage", ".csv")
      file.deleteOnExit()
      file.writeText(
        columns.joinToString(",") + "\n" + columns.joinToString(",") { values[it] ?: "-2" } + "\n",
      )
      return parseCsv(file).use { it.toList() }.single()
    }
    assertNotNull(
      loader.mapHd(oneRow(IpedsLoader.REQUIRED_HD_COLUMNS, mapOf("UNITID" to "910001", "CYACTIVE" to "1"))),
    )
    assertNotNull(loader.mapIc(oneRow(IpedsLoader.REQUIRED_IC_COLUMNS, mapOf("UNITID" to "910001"))))
    assertNotNull(loader.mapAdm(oneRow(IpedsLoader.REQUIRED_ADM_COLUMNS, mapOf("UNITID" to "910001"))))
    val completion =
      oneRow(
        IpedsLoader.REQUIRED_COMPLETIONS_COLUMNS,
        mapOf("UNITID" to "910001", "CIPCODE" to "11.0701", "MAJORNUM" to "1", "AWLEVEL" to "5", "CTOTALT" to "3"),
      )
    assertEquals(IpedsLoader.CensusSelection.Selected, loader.selectBachelorsFirstMajor(completion))
    assertNotNull(loader.mapCompletion(completion))
  }
}
