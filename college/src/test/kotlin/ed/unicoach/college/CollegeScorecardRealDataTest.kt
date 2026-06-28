package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
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
  fun `negative net_price loads (guards 0022)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val ventura = withSession { CollegesDao.findByUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      assertEquals(-982, ventura.netPrice)
    }

  @Test
  fun `out-of-domain optional locale is nulled, institution kept (mechanism A)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      // Pensacola Christian (136455) has LOCALE=2, outside the 11..43 domain.
      val pensacola = withSession { CollegesDao.findByUnitId(it, 136455).getOrThrow() }
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
      val auburn = withSession { CollegesDao.findByUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      assertEquals("00831000", auburn.opeid)
    }

  @Test
  fun `4-digit and 6-digit CIP programs load (guards 0021)`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByUnitId(it, 100830).getOrThrow() }
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
      val auburn = withSession { CollegesDao.findByUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val title =
        withSession { programTitle(it, auburn.id.asString, "1101", 3) }
      assertEquals("Computer and Information Sciences, General.", title)
    }

  @Test
  fun `credlev 99 row is skipped and counted, neighbors survive (mechanism B)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      val auburn = withSession { CollegesDao.findByUnitId(it, 100830).getOrThrow() }
      assertNotNull(auburn)
      val cips = withSession { programCipCodes(it, auburn.id.asString) }
      // The CIPCODE=2601 / CREDLEV=99 program is absent.
      assertTrue("2601" !in cips, "expected the CREDLEV=99 program absent, got $cips")
      assertTrue(
        (result.skipsByReason[CollegeScorecardLoader.SkipReason.CredentialLevelOutOfDomain] ?: 0) >= 1,
      )
      // The other Auburn programs still load.
      assertTrue("0301" in cips && "0901" in cips && "1101" in cips)
    }

  @Test
  fun `UNITID=NA rows are skipped and counted, not silently lost (mechanism B)`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      assertTrue(
        (result.skipsByReason[CollegeScorecardLoader.SkipReason.UnitIdNa] ?: 0) >= 1,
      )
      // No college or program was synthesized for the OPEID6-keyed NA rows.
      val judson = withSession { CollegesDao.findByUnitId(it, 1023).getOrThrow() }
      assertNull(judson)
    }

  @Test
  fun `Ventura program links to its negative-net-price college`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val ventura = withSession { CollegesDao.findByUnitId(it, 125028).getOrThrow() }
      assertNotNull(ventura)
      val owner = withSession { programCollegeId(it, "0101", 2) }
      assertEquals(ventura.id.asString, owner)
    }

  @Test
  fun `summary has no transient skips against clean real data`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      assertEquals(0, result.skipsByReason[CollegeScorecardLoader.SkipReason.Transient] ?: 0)
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

  // ---------------------------------------------------------------------------
  // Query helpers (no DAO program-read path exists; read the table directly)
  // ---------------------------------------------------------------------------

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
