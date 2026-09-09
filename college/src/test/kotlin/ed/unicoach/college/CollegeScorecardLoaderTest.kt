package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.LockAcquisitionFailureException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
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
  fun `the REQUIRED column lists cover every column the mappers read`() =
    runBlocking {
      // Coverage guarantee (RFC 139): synthesize CSVs whose headers are EXACTLY
      // the REQUIRED_* lists. Every cell read goes through the loud isMapped
      // check in the loader, so a mapper reading any column missing from the
      // shared definition throws IllegalStateException and fails this test.
      // Two institution rows (control 1 and 2) drive both control-keyed
      // branches of the NPT4* reads.
      val institutionHeader = CollegeScorecardLoader.REQUIRED_INSTITUTION_COLUMNS
      val institutionValues =
        mapOf(
          "UNITID" to listOf("910001", "910002"),
          "INSTNM" to listOf("Coverage Public U", "Coverage Private U"),
          "CITY" to listOf("Townsville", "Cityburg"),
          "STABBR" to listOf("CA", "NY"),
          "CONTROL" to listOf("1", "2"),
        )
      val institutions = File.createTempFile("coverage-institutions", ".csv")
      institutions.deleteOnExit()
      institutions.writeText(
        buildString {
          appendLine(institutionHeader.joinToString(","))
          for (row in 0..1) {
            appendLine(institutionHeader.joinToString(",") { institutionValues[it]?.get(row) ?: "" })
          }
        },
      )

      val fieldsHeader = CollegeScorecardLoader.REQUIRED_FIELDS_COLUMNS
      val fieldsValues = mapOf("UNITID" to "910001", "CIPCODE" to "2601", "CIPDESC" to "Biology", "CREDLEV" to "3")
      val fields = File.createTempFile("coverage-fields", ".csv")
      fields.deleteOnExit()
      fields.writeText(
        buildString {
          appendLine(fieldsHeader.joinToString(","))
          appendLine(fieldsHeader.joinToString(",") { fieldsValues.getValue(it) })
        },
      )

      val result = loader.load(institutions, fields)
      assertEquals(2, result.collegesLoaded)
      assertEquals(1, result.programsLoaded)
    }

  @Test
  fun `loads institutions and programs from fixture CSVs`() =
    runBlocking {
      val result = loader.load(institutionCsv, fieldsCsv)

      // 5 valid institutions (the 6th row has an empty UNITID and is skipped).
      assertEquals(5, result.collegesLoaded)
      // 9 program rows, all referencing valid institutions; the last is a
      // 4-digit CIP ('0901') the old six-digit-only CHECK would have rejected.
      assertEquals(9, result.programsLoaded)

      // The institution phase writes NO money since RFC 176 -- the eighteen
      // columns left `colleges` -- so what it writes is identity, location,
      // codes and the non-money measures. The control-keyed net-price reads
      // this block used to assert are now `CanonicalMoneyLoader`'s, over the
      // same CSV, and `CanonicalMoneyLoaderTest` is where they are pinned.
      val public = withSession { CollegesDao.findByIpedsUnitId(it, 110100).getOrThrow() }
      assertNotNull(public)
      assertEquals(1, public.control)
      assertEquals(0.68, public.completionRate150pct4yrShare)

      val private = withSession { CollegesDao.findByIpedsUnitId(it, 220200).getOrThrow() }
      assertNotNull(private)
      assertEquals(2, private.control)
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
      // The changed file renames institution 110100; every other ipeds_unit_id is byte-identical.
      val changedCsv = fixture("scorecard-institutions-changed-fixture.csv")
      loader.load(changedCsv, fieldsCsv)

      val changed = withSession { CollegesDao.findByIpedsUnitId(it, 110100).getOrThrow() }
      assertNotNull(changed)
      assertEquals(2, changed.version)
      assertEquals("Coastal State University (Renamed)", changed.name)
      assertEquals(2, withSession { countHistory(it, changed.id) })

      // An untouched institution stays at version 1 with a single history row.
      val untouched = withSession { CollegesDao.findByIpedsUnitId(it, 220200).getOrThrow() }
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
        result.skipsByReason[SkipReason.MissingRequiredField(listOf("ipeds_unit_id"))],
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
          SkipReason.ConstraintViolation("colleges_control_valid_check"),
        ],
      )

      // Both good rows survived and are queryable (the bad row did not poison them).
      val leading = withSession { CollegesDao.findByIpedsUnitId(it, 700700).getOrThrow() }
      assertNotNull(leading)
      assertEquals("Good Lead University", leading.name)
      val trailing = withSession { CollegesDao.findByIpedsUnitId(it, 900900).getOrThrow() }
      assertNotNull(trailing)
      assertEquals("Good Tail University", trailing.name)

      // The bad row was skipped — not persisted.
      val bad = withSession { CollegesDao.findByIpedsUnitId(it, 800800).getOrThrow() }
      assertNull(bad)

      // The whole file did not roll back: exactly the two good rows are present.
      assertEquals(2, withSession { count(it, "colleges") })
    }

  @Test
  fun `a row naming an unpublished state is skipped under its FK name, not as an unnamed violation`() =
    runBlocking {
      // Migration 0067 made `colleges.state` a foreign key, which turns a
      // two-letter code that names nothing into a ROUTINE per-row rejection —
      // SQLSTATE 23503, i.e. a NotFoundException, not the 23505/23514 the skip
      // path used to be written for. Before the diagnostics were carried through
      // this skip tallied as ConstraintViolation(null) and logged
      // [constraint=null] [detail=null]: the row lost the name of what rejected
      // it and the value that did it.
      val unknownState = fixture("scorecard-institutions-unknown-state-fixture.csv")
      val emptyFields = fixture("scorecard-fields-empty-fixture.csv")

      val result = loader.load(unknownState, emptyFields)

      assertEquals(2, result.collegesLoaded)
      assertEquals(1, result.permanentSkips)
      assertEquals(
        1,
        result.skipsByReason[SkipReason.ConstraintViolation("colleges_state_codebook_fkey")],
        "the skip is bucketed by the FK that rejected it: ${result.skipsByReason}",
      )
      assertNull(withSession { CollegesDao.findByIpedsUnitId(it, 800800).getOrThrow() })
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
      assertEquals(1, result.fieldsCoercedToNull["admission_rate_share"])

      // The money cells of the same fixture row (GRAD_DEBT_MDN=-100,
      // BOOKSUPPLY=-50) are no longer this loader's to coerce: RFC 176 took
      // money off `colleges`, and the canonical fill applies its own domain to
      // the same cells. So mechanism A is asserted here on what this phase
      // still parses.
      assertEquals(setOf("admission_rate_share"), result.fieldsCoercedToNull.keys)

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 600600).getOrThrow() }
      assertNotNull(college)
      assertNull(college.admissionRateShare)
    }

  @Test
  fun `blank optional fields become null`() =
    runBlocking {
      loader.load(institutionCsv, fieldsCsv)
      // Row 330300 has an empty ADM_RATE cell.
      val college = withSession { CollegesDao.findByIpedsUnitId(it, 330300).getOrThrow() }
      assertNotNull(college)
      assertNull(college.admissionRateShare)
      // Row 550500 has an empty SAT_AVG cell.
      val cc = withSession { CollegesDao.findByIpedsUnitId(it, 550500).getOrThrow() }
      assertNotNull(cc)
      assertNull(cc.satAverageEquivalentScore)
    }

  @Test
  fun `classifyUpsertFailure buckets each failure shape distinctly`() {
    // Null and an unmappable Throwable both fall to UnknownFailure — never fused
    // into an unnamed ConstraintViolation.
    assertEquals(SkipReason.UnknownFailure, CsvIngestSupport.classifyUpsertFailure(null))
    assertEquals(
      SkipReason.UnknownFailure,
      CsvIngestSupport.classifyUpsertFailure(IllegalStateException("not a DaoException")),
    )
    // A retryable fault is Transient.
    assertEquals(
      SkipReason.Transient,
      CsvIngestSupport.classifyUpsertFailure(LockAcquisitionFailureException()),
    )
    // A named constraint violation keeps its name; an unnamed one carries null.
    assertEquals(
      SkipReason.ConstraintViolation("colleges_control_valid_check"),
      CsvIngestSupport.classifyUpsertFailure(
        ConstraintViolationException(SQLException("boom"), "colleges_control_valid_check"),
      ),
    )
    // A foreign-key rejection — a NotFoundException, the shape a `colleges`
    // row with an unpublished state now takes — keeps its constraint name too.
    assertEquals(
      SkipReason.ConstraintViolation("colleges_state_codebook_fkey"),
      CsvIngestSupport.classifyUpsertFailure(
        NotFoundException(
          message = "Referenced codebook row not found",
          cause = SQLException("boom"),
          constraint = "colleges_state_codebook_fkey",
          detail = "Key (state)=(ZZ) is not present in table \"us_states\".",
        ),
      ),
    )
    // A generic permanent DB error is an unkeyed ConstraintViolation, distinct
    // from UnknownFailure.
    assertEquals(
      SkipReason.ConstraintViolation(null),
      CsvIngestSupport.classifyUpsertFailure(DatabaseException(SQLException("boom"))),
    )
  }
}
