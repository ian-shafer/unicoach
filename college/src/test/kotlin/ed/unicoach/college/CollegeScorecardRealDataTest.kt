package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.InstitutionControl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the real loader against verbatim, machine-extracted real Scorecard rows
 * (the full real headers plus the institution/field-of-study quirk rows RFC 78
 * hardens against). Each observed quirk is an executable assertion: negative
 * net price, out-of-domain optional metrics, the real OPEID column, the 2/4/6
 * CIP grammar, quoted embedded commas, and the `CREDLEV=99` / `UNITID=NA`
 * sentinels.
 */
class CollegeScorecardRealDataTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-real-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-real-fixture.csv")

  @Test
  fun `negative net_price_per_year_usd loads (guards 0022)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val ventura = withSession { CollegesDao.findByIpedsUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      assertEquals(-982, ventura.netPricePerYearUsd)
    }

  @Test
  fun `income-band net prices and median debt load from real rows (RFC 133)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)

      // Ventura (public): the low-income bands are genuinely negative in the
      // published data and must load un-coerced.
      val ventura = withSession { CollegesDao.findByIpedsUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      assertEquals(-1913, ventura.netPricePerYearIncomeQ1Usd)
      assertEquals(-2393, ventura.netPricePerYearIncomeQ2Usd)
      assertEquals(524, ventura.netPricePerYearIncomeQ3Usd)
      assertEquals(4165, ventura.netPricePerYearIncomeQ4Usd)
      assertEquals(6577, ventura.netPricePerYearIncomeQ5Usd)
      assertEquals(13876, ventura.medianDebtAtCompletionUsd)

      // Auburn Montgomery (public): plain positive bands from the _PUB columns.
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      assertEquals(11706, auburn.netPricePerYearIncomeQ1Usd)
      assertEquals(16117, auburn.netPricePerYearIncomeQ5Usd)
      assertEquals(25000, auburn.medianDebtAtCompletionUsd)

      // Pensacola Christian (private): every band cell is the NA sentinel.
      val pensacola = withSession { CollegesDao.findByIpedsUnitId(it, 136455).getOrThrow() }
      assertNotNull(pensacola)
      assertNull(pensacola.netPricePerYearIncomeQ1Usd)
      assertNull(pensacola.netPricePerYearIncomeQ5Usd)
      assertNull(pensacola.medianDebtAtCompletionUsd)
    }

  @Test
  fun `the six cost components load from real rows, NA is not reported (RFC 149)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)

      // Auburn Montgomery: a school that reports every one of the six, so all
      // three living arrangements are answerable from this row.
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      assertEquals(1500, auburn.booksAndSuppliesPerYearUsd)
      assertEquals(7368, auburn.housingAndFoodOnCampusPerYearUsd)
      assertEquals(12762, auburn.housingAndFoodOffCampusPerYearUsd)
      assertEquals(4545, auburn.otherExpensesOnCampusPerYearUsd)
      assertEquals(4545, auburn.otherExpensesOffCampusPerYearUsd)
      assertEquals(4545, auburn.otherExpensesWithFamilyPerYearUsd)

      // Ventura: a community college. The ON-CAMPUS pair is the NA sentinel and
      // the off-campus pair is published -- the real shape that makes "not
      // reported" a per-arrangement fact rather than a per-school one.
      val ventura = withSession { CollegesDao.findByIpedsUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      assertNull(ventura.housingAndFoodOnCampusPerYearUsd, "NA is not reported, never 0")
      assertNull(ventura.otherExpensesOnCampusPerYearUsd)
      assertEquals(22086, ventura.housingAndFoodOffCampusPerYearUsd)
      assertEquals(4968, ventura.otherExpensesOffCampusPerYearUsd)
      assertEquals(4059, ventura.otherExpensesWithFamilyPerYearUsd)
      assertEquals(1062, ventura.booksAndSuppliesPerYearUsd)

      // Pensacola Christian: all six are NA -- a school that reports no
      // components at all, and so gets no breakdown rather than a zeroed one.
      val pensacola = withSession { CollegesDao.findByIpedsUnitId(it, 136455).getOrThrow() }
      assertNotNull(pensacola)
      assertNull(pensacola.booksAndSuppliesPerYearUsd)
      assertNull(pensacola.housingAndFoodOnCampusPerYearUsd)
      assertNull(pensacola.housingAndFoodOffCampusPerYearUsd)
      assertNull(pensacola.otherExpensesOnCampusPerYearUsd)
      assertNull(pensacola.otherExpensesOffCampusPerYearUsd)
      assertNull(pensacola.otherExpensesWithFamilyPerYearUsd)
    }

  @Test
  fun `out-of-domain optional locale is nulled, institution kept (mechanism A)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      // Pensacola Christian (136455) has LOCALE=2, outside the 11..43 domain.
      val pensacola = withSession { CollegesDao.findByIpedsUnitId(it, 136455).getOrThrow() }
      assertNotNull(pensacola)
      assertNull(pensacola.locale)
      // A valid required field is retained — the row was kept, not dropped.
      assertEquals(2, pensacola.control)
      assertEquals(1, result.fieldsCoercedToNull["locale"])
    }

  @Test
  fun `opeid loaded from real OPEID column (item 3)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      assertEquals("00831000", auburn.opeid)
    }

  @Test
  fun `4-digit and 6-digit CIP programs load (guards 0021)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val cips = withSession { programCipCodes(it, auburn.id.asString) }
      // 4-digit family codes the old six-only CHECK would have rejected.
      assertTrue("0301" in cips, "expected 4-digit CIP 0301, got $cips")
      assertTrue("0901" in cips, "expected 4-digit CIP 0901, got $cips")
    }

  @Test
  fun `quoted embedded comma in CIPDESC parses intact`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val title =
        withSession { programTitle(it, auburn.id.asString, "1101", 3) }
      assertEquals("Computer and Information Sciences, General.", title)
    }

  @Test
  fun `credlev 99 row is skipped and counted, neighbors survive (mechanism B)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByIpedsUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val cips = withSession { programCipCodes(it, auburn.id.asString) }
      // The CIPCODE=2601 / CREDLEV=99 program is absent.
      assertTrue("2601" !in cips, "expected the CREDLEV=99 program absent, got $cips")
      assertTrue(
        (result.skipsByReason[SkipReason.CredentialLevelOutOfDomain] ?: 0) >= 1,
      )
      // The other Auburn programs still load.
      assertTrue("0301" in cips && "0901" in cips && "1101" in cips)
    }

  @Test
  fun `UNITID=NA rows are skipped and counted, not silently lost (mechanism B)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      assertTrue(
        (result.skipsByReason[SkipReason.IpedsUnitIdNa] ?: 0) >= 1,
      )
      // No college or program was synthesized for the OPEID6-keyed NA rows.
      val judson = withSession { CollegesDao.findByIpedsUnitId(it, 1023).getOrThrow() }
      assertNull(judson)
    }

  @Test
  fun `Ventura program links to its negative-net-price college`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val ventura = withSession { CollegesDao.findByIpedsUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      val owner = withSession { programCollegeId(it, "0101", 2) }
      assertEquals(ventura.id.asString, owner)
    }

  @Test
  fun `summary has no transient skips against clean real data`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      assertEquals(0, result.skipsByReason[SkipReason.Transient] ?: 0)
    }

  @Test
  fun `re-running the real-data load is idempotent`() =
    runBlocking {
      val first = loader.load(institutionCsv, fieldsCsv)
      val second = loader.load(institutionCsv, fieldsCsv)
      assertEquals(first.collegesLoaded, second.collegesLoaded)
      assertEquals(first.programsLoaded, second.programsLoaded)
      assertEquals(first.collegesLoaded, withSession { count(it, "colleges") })
      assertEquals(first.programsLoaded, withSession { count(it, "college_programs") })
    }

  @Test
  fun `COSTT4_A sits inside the IN-STATE arrangement span at every public row we hold (RFC 157)`() =
    runBlocking {
      // The falsifier for RFC 157, run over the real rows this repo actually
      // commits rather than over numbers a fixture chose.
      //
      // COSTT4_A is a weighted average of the three living-arrangement totals,
      // and the Scorecard builds it for students paying the IN-STATE rate. A
      // weighted average cannot fall outside the span of its own inputs, so at
      // every CONTROL=1 row that publishes all seven parts, COSTT4_A must lie
      // between the smallest and the largest IN-STATE total. One row outside
      // that span would break the arithmetic argument the whole RFC rests on.
      //
      // Only rows with all seven parts qualify: a row missing an arrangement
      // (Ventura College publishes no on-campus figures) has no span to test.
      loader.load(institutionCsv, fieldsCsv)

      val unitIds = withSession { CollegesDao.currentVersionsByIpedsUnitId(it).getOrThrow() }.keys
      assertTrue(unitIds.isNotEmpty(), "the real fixture must have loaded some rows for this scan to mean anything")

      val checked = mutableListOf<String>()
      unitIds.forEach { unitId ->
        val college = withSession { CollegesDao.findByIpedsUnitId(it, unitId).getOrThrow() }
        assertNotNull(college)
        val costt4a = college.costOfAttendancePerYearUsd
        val inStateTuition = college.tuitionAndFeesInStatePerYearUsd
        val books = college.booksAndSuppliesPerYearUsd
        val housingOn = college.housingAndFoodOnCampusPerYearUsd
        val housingOff = college.housingAndFoodOffCampusPerYearUsd
        val otherOn = college.otherExpensesOnCampusPerYearUsd
        val otherOff = college.otherExpensesOffCampusPerYearUsd
        val otherFamily = college.otherExpensesWithFamilyPerYearUsd
        if (college.control != PUBLIC_CONTROL_CODE ||
          costt4a == null ||
          inStateTuition == null ||
          books == null ||
          housingOn == null ||
          housingOff == null ||
          otherOn == null ||
          otherOff == null ||
          otherFamily == null
        ) {
          return@forEach
        }
        val totals =
          listOf(
            inStateTuition + books + housingOn + otherOn,
            inStateTuition + books + housingOff + otherOff,
            inStateTuition + books + otherFamily,
          )
        checked.add("name=[${college.name}] COSTT4_A=[$costt4a] in_state_totals=[$totals]")
        assertTrue(
          costt4a >= totals.min() && costt4a <= totals.max(),
          "COSTT4_A must lie inside the in-state span, or it is not built on the in-state rate: " +
            "name=[${college.name}] COSTT4_A=[$costt4a] totals=[$totals]",
        )
      }

      // TWO qualifying public rows, not one: Auburn University at Montgomery
      // and UC San Diego (110680), the institution RFC 157 argues from. Both are
      // verbatim Scorecard rows, and the scan widens by itself the day another
      // real row is committed. A one-row falsifier is an anecdote.
      assertTrue(
        checked.size >= MIN_QUALIFYING_PUBLIC_ROWS && checked.any { it.startsWith("name=[$UCSD_NAME]") },
        "the scan must test at least the two qualifying public rows this repo commits, UC San Diego among " +
          "them, or it is an anecdote: checked=[$checked] loaded_units=[$unitIds]",
      )
    }

  // ---------------------------------------------------------------------------
  // Query helpers (no DAO program-read path exists; read the table directly)
  // ---------------------------------------------------------------------------

  private companion object {
    /**
     * Scorecard `CONTROL` for a public institution -- the only control for which
     * the in-state basis is a distinction at all -- read from the vocabulary's
     * one home ([InstitutionControl]) and never restated here.
     */
    val PUBLIC_CONTROL_CODE = InstitutionControl.PUBLIC.code

    /**
     * The qualifying public rows this repo commits today: Auburn University at
     * Montgomery and UC San Diego. A FLOOR, not an equality -- the scan widens
     * by itself the day another real public row lands.
     */
    const val MIN_QUALIFYING_PUBLIC_ROWS = 2

    /** The institution RFC 157 argues from, which must be one of the rows the scan reaches. */
    const val UCSD_NAME = "University of California-San Diego"
  }

  private fun programCipCodes(
    session: SqlSession,
    collegeId: String,
  ): Set<String> =
    session.prepareStatement("SELECT cip_code FROM college_programs WHERE college_id = ?::uuid").use { stmt ->
      stmt.setString(1, collegeId)
      stmt.executeQuery().use { rs ->
        val out = mutableSetOf<String>()
        while (rs.next()) out.add(rs.getString(1))
        out
      }
    }

  private fun programTitle(
    session: SqlSession,
    collegeId: String,
    cipCode: String,
    credentialLevel: Int,
  ): String? =
    session
      .prepareStatement(
        "SELECT cip_title FROM college_programs " +
          "WHERE college_id = ?::uuid AND cip_code = ? AND credential_level = ?",
      ).use { stmt ->
        stmt.setString(1, collegeId)
        stmt.setString(2, cipCode)
        stmt.setInt(3, credentialLevel)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
      }

  private fun programCollegeId(
    session: SqlSession,
    cipCode: String,
    credentialLevel: Int,
  ): String? =
    session
      .prepareStatement(
        "SELECT college_id FROM college_programs WHERE cip_code = ? AND credential_level = ?",
      ).use { stmt ->
        stmt.setString(1, cipCode)
        stmt.setInt(2, credentialLevel)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
      }
}
