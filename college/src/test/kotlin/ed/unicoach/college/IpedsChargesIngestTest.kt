package ed.unicoach.college

import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.IpedsImputationFlag
import ed.unicoach.db.models.MoneySource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `ipeds-charges` phase inside one full ingest run (RFC 161): its position
 * ahead of `canonical-money` (an ingest-loaded staging table is that fill's
 * write precondition), the provenance block it adds, and the operator-visible
 * summary line — which names RECORDS and ROWS as the different units they are.
 */
class IpedsChargesIngestTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-ic-ay-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val aliasesJson = fixture("college-aliases-fixture.json")

  private fun ipedsSources() =
    IpedsSources(
      source(fixture("ipeds-hd2023-fixture.csv")),
      source(fixture("ipeds-ic2023-fixture.csv")),
      source(fixture("ipeds-adm2023-fixture.csv")),
      source(fixture("ipeds-c2023-a-fixture.csv")),
      source(fixture("ipeds-ic2023-ay-fixture.csv")),
      2023,
    )

  private fun ingest(ipeds: IpedsSources? = ipedsSources()): CollegeScorecardLoader.IngestReport =
    runBlocking { loader.ingest(source(institutionCsv), source(fieldsCsv), source(aliasesJson), ipeds) }

  // ---------------------------------------------------------------------------
  // The phase
  // ---------------------------------------------------------------------------

  @Test
  fun `the charges phase stages one row per variable per year for every matched record`() {
    val report = ingest()
    val charges = assertNotNull(report.ipeds).charges
    // The IC_AY fixture carries four records; the Scorecard fixture gives all
    // four a college. 4 records x 12 stems x 4 years.
    assertEquals(IC_AY_FIXTURE_RECORDS, charges.seen)
    assertEquals(0, charges.unmatchedIpedsUnitIds)
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, charges.loaded)
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, withSession { count(it, "college_ipeds_charges") })
  }

  @Test
  fun `re-ingesting the same snapshot is a loudly visible no-op`() {
    ingest()
    val charges = assertNotNull(ingest().ipeds).charges
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, charges.unchanged)
    assertEquals(0, charges.inserted)
    assertEquals(0, charges.changed)
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, withSession { count(it, "college_ipeds_charges") })
  }

  @Test
  fun `the staged rows are in place before the canonical-money fill reads them`() {
    // Ordering is a write PRECONDITION, not a preference: `canonical-money` is
    // the last derived phase and reads this table, so an ingest that finished
    // must have both.
    val report = ingest()
    assertTrue(withSession { count(it, "college_ipeds_charges") } > 0)
    assertTrue(report.canonicalMoney.priceFigureRows > 0)
  }

  // ---------------------------------------------------------------------------
  // Provenance and the summary
  // ---------------------------------------------------------------------------

  @Test
  fun `the build row gains the ipeds_charges block, with records and rows named apart`() {
    val report = ingest()
    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    assertTrue(row.rowsIngested.contains("\"ipeds_charges\""), row.rowsIngested)
    assertTrue(row.rowsIngested.contains("\"rows\": ${IC_AY_FIXTURE_RECORDS * 12 * 4}"), row.rowsIngested)
    assertTrue(row.rowsIngested.contains("\"cells_by_flag\""), row.rowsIngested)
    assertTrue(row.sources.contains("ipeds-ic2023-ay-fixture.csv"), row.sources)
  }

  @Test
  fun `the flag distribution omits a code with no cells, never writes it as zero`() {
    val report = ingest()
    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    val charges = assertNotNull(report.ipeds).charges
    // Only R and A occur in the real fixture rows, so no other code appears.
    assertEquals(
      setOf(
        IpedsImputationFlag.REPORTED,
        IpedsImputationFlag.NOT_APPLICABLE,
      ),
      charges.cellsByFlag.keys,
    )
    // The published code is what the build row carries -- rendered at the JSON
    // edge from the typed tally.
    assertTrue(row.rowsIngested.contains("\"R\": "), row.rowsIngested)
    assertFalse(row.rowsIngested.contains("\"L\": 0"), row.rowsIngested)
    assertFalse(row.rowsIngested.contains("\"Z\": 0"), row.rowsIngested)
  }

  @Test
  fun `the persisted canonical summary breaks price rows down by source`() {
    // The FillResult map is the input to the serialiser, not its output. This
    // is the only ingest test whose fixture carries IC_AY, so it is the one
    // place the PERSISTED operator artifact can be read back.
    val report = ingest()
    val summary = assertNotNull(withSession { canonicalMoneySummary(it, report.buildId) })
    val counts = report.canonicalMoney.priceFigureSourceCounts
    assertTrue(summary.contains("\"price_figures_by_source\""), summary)
    assertEquals(setOf(MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD), counts.keys, "$counts")
    for ((source, rows) in counts) {
      assertTrue(summary.contains("\"${source.value}\": $rows"), "[${source.value}] must carry $rows: $summary")
    }
  }

  @Test
  fun `a source that won no price row is omitted from the block, never written as zero`() {
    // "won nothing" and "was not consulted" are different facts, so a source
    // with no rows is ABSENT rather than 0 -- asserted on the run that has no
    // IPEDS group at all, where IC_AY is exactly that source.
    val report = ingest(ipeds = null)
    val summary = assertNotNull(withSession { canonicalMoneySummary(it, report.buildId) })
    assertTrue(summary.contains("\"price_figures_by_source\""), summary)
    assertFalse(summary.contains(MoneySource.IPEDS_IC_AY.value), summary)
    assertEquals(setOf(MoneySource.SCORECARD), report.canonicalMoney.priceFigureSourceCounts.keys)
  }

  @Test
  fun `a run without the IPEDS group omits the ipeds_charges key entirely`() {
    val report = ingest(ipeds = null)
    val row = assertNotNull(withSession { buildRow(it, report.buildId) })
    assertFalse(row.rowsIngested.contains("ipeds_charges"), "absent means absent: ${row.rowsIngested}")
    assertEquals(0, withSession { count(it, "college_ipeds_charges") })
    assertFalse(report.humanSummary().contains("ipeds-charges:"), report.humanSummary())
  }

  @Test
  fun `the summary line names records and rows as different units`() {
    val summary = ingest().humanSummary()
    assertTrue(summary.contains("ipeds-charges: [$IC_AY_FIXTURE_RECORDS] records seen"), summary)
    assertTrue(summary.contains("[${IC_AY_FIXTURE_RECORDS * 12 * 4}] rows"), summary)
    assertTrue(summary.contains("academic-year universe"), summary)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private data class BuildRow(
    val sources: String,
    val rowsIngested: String,
  )

  private fun canonicalMoneySummary(
    session: SqlSession,
    id: UUID,
  ): String? =
    session
      .prepareStatement("SELECT canonical_money_summary::text FROM college_index_build WHERE id = ?")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
      }

  private fun buildRow(
    session: SqlSession,
    id: UUID,
  ): BuildRow? =
    session
      .prepareStatement("SELECT sources::text, rows_ingested::text FROM college_index_build WHERE id = ?")
      .use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return null
          BuildRow(sources = rs.getString(1), rowsIngested = rs.getString(2))
        }
      }
}
