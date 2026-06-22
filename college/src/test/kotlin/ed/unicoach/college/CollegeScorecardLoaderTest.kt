package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.SqlSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CollegeScorecardLoaderTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }

    private fun fixture(name: String): File {
      val url = requireNotNull(this::class.java.classLoader.getResource(name)) { "missing fixture [$name]" }
      return File(url.toURI())
    }
  }

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs CASCADE").use { it.execute() }
      }
      Unit
    }

  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  private fun <T> withSession(block: (SqlSession) -> T): T = runBlocking { database.withConnection(block) }

  @Test
  fun `loads institutions and programs from fixture CSVs`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)

      // 5 valid institutions (the 6th row has an empty UNITID and is skipped).
      assertEquals(5, result.collegesLoaded)
      // 8 program rows, all referencing valid institutions.
      assertEquals(8, result.programsLoaded)

      // Public row: net_price coalesced from NPT4_PUB.
      val public = withSession { CollegesDao.findByUnitId(it, 110100).getOrThrow() }
      assertNotNull(public)
      assertEquals(18000, public.netPrice)
      assertEquals(0.68, public.graduationRate)
      assertEquals(52000, public.medianEarnings)
      assertEquals(0.42, public.pctPell)

      // Private row: net_price coalesced from NPT4_PRIV (NPT4_PUB blank).
      val private = withSession { CollegesDao.findByUnitId(it, 220200).getOrThrow() }
      assertNotNull(private)
      assertEquals(2, private.control)
      assertEquals(41000, private.netPrice)
    }

  @Test
  fun `re-running the loader is idempotent`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      val second = loader.load(institutionCsv, fieldsCsv)
      assertEquals(5, second.collegesLoaded)
      assertEquals(8, second.programsLoaded)

      val collegeCount = withSession { count(it, "colleges") }
      val programCount = withSession { count(it, "college_programs") }
      assertEquals(5, collegeCount)
      assertEquals(8, programCount)
    }

  @Test
  fun `a row missing required fields is skipped, others load`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      // The malformed institution (empty UNITID) never lands.
      val total = withSession { count(it, "colleges") }
      assertEquals(5, total)
    }

  @Test
  fun `a row violating a DB CHECK is skipped and the surrounding good rows still load`() =
    runBlocking {
      // The middle row has admission_rate = 1.5, which is structurally valid (a
      // parseable double, so it passes mapInstitution) but violates
      // colleges_admission_rate_range_check at the DB. Without savepoint-per-row
      // isolation the failed statement would abort the transaction and the trailing
      // good row (and the leading one, at commit) would be silently lost.
      val checkViolation = fixture("scorecard-institutions-check-violation-fixture.csv")
      val emptyFields = fixture("scorecard-fields-empty-fixture.csv")

      val result = loader.load(checkViolation, emptyFields)

      // Only the two good rows are counted; the CHECK-violating row is a permanent skip.
      assertEquals(2, result.collegesLoaded)
      assertEquals(1, result.permanentSkips)
      assertEquals(0, result.transientSkips)

      // Both good rows survived and are queryable (the bad row did not poison them).
      val leading = withSession { CollegesDao.findByUnitId(it, 700700).getOrThrow() }
      assertNotNull(leading)
      assertEquals("Good Lead University", leading.name)
      val trailing = withSession { CollegesDao.findByUnitId(it, 900900).getOrThrow() }
      assertNotNull(trailing)
      assertEquals("Good Tail University", trailing.name)

      // The bad row was skipped — not persisted.
      val bad = withSession { CollegesDao.findByUnitId(it, 800800).getOrThrow() }
      assertNull(bad)

      // The whole file did not roll back: exactly the two good rows are present.
      assertEquals(2, withSession { count(it, "colleges") })
    }

  @Test
  fun `blank optional fields become null`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      // Row 330300 has an empty ADM_RATE cell.
      val college = withSession { CollegesDao.findByUnitId(it, 330300).getOrThrow() }
      assertNotNull(college)
      assertNull(college.admissionRate)
      // Row 550500 has an empty SAT_AVG cell.
      val cc = withSession { CollegesDao.findByUnitId(it, 550500).getOrThrow() }
      assertNotNull(cc)
      assertNull(cc.satAvg)
    }

  private fun count(
    session: SqlSession,
    table: String,
  ): Int =
    session.prepareStatement("SELECT count(*) FROM $table").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
