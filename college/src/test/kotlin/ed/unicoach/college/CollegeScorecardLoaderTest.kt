package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.LockAcquisitionFailureException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeScorecardLoaderTest : CollegeScorecardTestBase() {
  private val loader = CollegeScorecardLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  @Test
  fun `loads institutions and programs from fixture CSVs`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)

      // 5 valid institutions (the 6th row has an empty UNITID and is skipped).
      assertEquals(5, result.collegesLoaded)
      // 9 program rows, all referencing valid institutions; the last is a
      // 4-digit CIP ('0901') the old six-digit-only CHECK would have rejected.
      assertEquals(9, result.programsLoaded)

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
      assertEquals(9, second.programsLoaded)

      val collegeCount = withSession { count(it, "colleges") }
      val programCount = withSession { count(it, "college_programs") }
      assertEquals(5, collegeCount)
      assertEquals(9, programCount)

      // RFC 82: the second identical load is a no-op per college — no version
      // bump, no extra history row. Every college stays at version 1 and
      // colleges_versions holds exactly one row per college.
      val versions = withSession { listVersions(it) }
      assertTrue(versions.all { v -> v == 1 }, "every college must stay at version 1, got $versions")
      assertEquals(5, withSession { count(it, "colleges_versions") })
    }

  @Test
  fun `re-ingesting a changed institution bumps version and logs history`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      // The changed file renames institution 110100; every other unit_id is byte-identical.
      val changedCsv = fixture("scorecard-institutions-changed-fixture.csv")
      loader.load(changedCsv, fieldsCsv)

      val changed = withSession { CollegesDao.findByUnitId(it, 110100).getOrThrow() }
      assertNotNull(changed)
      assertEquals(2, changed.version)
      assertEquals("Coastal State University (Renamed)", changed.name)
      assertEquals(2, withSession { countHistory(it, changed.id) })

      // An untouched institution stays at version 1 with a single history row.
      val untouched = withSession { CollegesDao.findByUnitId(it, 220200).getOrThrow() }
      assertNotNull(untouched)
      assertEquals(1, untouched.version)
      assertEquals(1, withSession { countHistory(it, untouched.id) })
    }

  /** Every college's current `version`, for the all-version-1 idempotency assertion. */
  private fun listVersions(session: SqlSession): List<Int> =
    session.prepareStatement("SELECT version FROM colleges").use { stmt ->
      stmt.executeQuery().use { rs ->
        val out = mutableListOf<Int>()
        while (rs.next()) out.add(rs.getInt(1))
        out
      }
    }

  /** Count of `colleges_versions` rows for one college id. */
  private fun countHistory(
    session: SqlSession,
    id: CollegeId,
  ): Int =
    session.prepareStatement("SELECT count(*) FROM colleges_versions WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  @Test
  fun `a row missing required fields is skipped, others load`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)
      // The malformed institution (empty UNITID) never lands.
      val total = withSession { count(it, "colleges") }
      assertEquals(5, total)
      // The empty-UNITID row is counted, not just logged, and the missing column
      // is carried in the structured reason.
      assertEquals(
        1,
        result.skipsByReason[CollegeScorecardLoader.SkipReason.MissingRequiredField(listOf("unit_id"))],
      )
    }

  @Test
  fun `a row violating a DB CHECK is skipped and the surrounding good rows still load`() =
    runBlocking {
      // The middle row has CONTROL=4, an out-of-domain *required* field (CONTROL
      // is never coerced by mechanism A), so it is rejected by
      // colleges_control_valid_check at the DB. Without savepoint-per-row
      // isolation the failed statement would abort the transaction and the trailing
      // good row (and the leading one, at commit) would be silently lost.
      val checkViolation = fixture("scorecard-institutions-check-violation-fixture.csv")
      val emptyFields = fixture("scorecard-fields-empty-fixture.csv")

      val result = loader.load(checkViolation, emptyFields)

      // Only the two good rows are counted; the CHECK-violating row is a permanent skip
      // bucketed by the violated constraint name.
      assertEquals(2, result.collegesLoaded)
      assertEquals(1, result.permanentSkips)
      assertEquals(0, result.transientSkips)
      assertEquals(
        1,
        result.skipsByReason[
          CollegeScorecardLoader.SkipReason.ConstraintViolation("colleges_control_valid_check"),
        ],
      )

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
  fun `an out-of-domain optional field is coerced to null, not rejected`() =
    runBlocking {
      // ADM_RATE=1.5 is an out-of-domain *optional* metric (mechanism A): it is
      // nulled and the institution still loads, rather than dropping the row.
      val coercion = fixture("scorecard-institutions-coercion-fixture.csv")
      val emptyFields = fixture("scorecard-fields-empty-fixture.csv")

      val result = loader.load(coercion, emptyFields)

      assertEquals(1, result.collegesLoaded)
      assertEquals(1, result.fieldsCoercedToNull["admission_rate"])

      val college = withSession { CollegesDao.findByUnitId(it, 600600).getOrThrow() }
      assertNotNull(college)
      assertNull(college.admissionRate)
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

  @Test
  fun `classifyUpsertFailure buckets each failure shape distinctly`() {
    // Null and an unmappable Throwable both fall to UnknownFailure — never fused
    // into an unnamed ConstraintViolation.
    assertEquals(CollegeScorecardLoader.SkipReason.UnknownFailure, loader.classifyUpsertFailure(null))
    assertEquals(
      CollegeScorecardLoader.SkipReason.UnknownFailure,
      loader.classifyUpsertFailure(IllegalStateException("not a DaoException")),
    )
    // A retryable fault is Transient.
    assertEquals(
      CollegeScorecardLoader.SkipReason.Transient,
      loader.classifyUpsertFailure(LockAcquisitionFailureException()),
    )
    // A named constraint violation keeps its name; an unnamed one carries null.
    assertEquals(
      CollegeScorecardLoader.SkipReason.ConstraintViolation("colleges_control_valid_check"),
      loader.classifyUpsertFailure(
        ConstraintViolationException(SQLException("boom"), "colleges_control_valid_check"),
      ),
    )
    // A generic permanent DB error is an unkeyed ConstraintViolation, distinct
    // from UnknownFailure.
    assertEquals(
      CollegeScorecardLoader.SkipReason.ConstraintViolation(null),
      loader.classifyUpsertFailure(DatabaseException(SQLException("boom"))),
    )
  }
}
