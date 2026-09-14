package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.LockAcquisitionFailureException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import kotlinx.coroutines.runBlocking
import org.apache.commons.csv.CSVFormat
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
      assertEquals(
        setOf("admission_rate_share", "act_composite_25th_percentile_score", "act_composite_75th_percentile_score"),
        result.fieldsCoercedToNull.keys,
        "and on the two score-band cells RFC 195 added, whose fixture values 0 and 41 are outside the ACT scale",
      )

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 600600).getOrThrow() }
      assertNotNull(college)
      assertNull(college.admissionRateShare)
    }

  @Test
  fun `the six percentile score bands load as published`() =
    runBlocking {
      // Row 110100 carries all six cells (RFC 195), with three DIFFERENT pairs
      // so a mapper that crossed two columns shows up as a wrong number.
      loader.load(institutionCsv, fieldsCsv)

      val banded = withSession { CollegesDao.findByIpedsUnitId(it, 110100).getOrThrow() }
      assertNotNull(banded)
      assertEquals(560, banded.satReading25thPercentileScore, "SATVR25")
      assertEquals(660, banded.satReading75thPercentileScore, "SATVR75")
      assertEquals(570, banded.satMath25thPercentileScore, "SATMT25")
      assertEquals(670, banded.satMath75thPercentileScore, "SATMT75")
      assertEquals(22, banded.actComposite25thPercentileScore, "ACTCM25")
      assertEquals(28, banded.actComposite75thPercentileScore, "ACTCM75")

      // Row 330300 leaves all six cells blank -- the 5,234-of-6,273 case. Six
      // nulls, and the college still loads: a school with no published score
      // is not a broken row.
      val silent = withSession { CollegesDao.findByIpedsUnitId(it, 330300).getOrThrow() }
      assertNotNull(silent)
      assertNull(silent.satReading25thPercentileScore)
      assertNull(silent.satReading75thPercentileScore)
      assertNull(silent.satMath25thPercentileScore)
      assertNull(silent.satMath75thPercentileScore)
      assertNull(silent.actComposite25thPercentileScore)
      assertNull(silent.actComposite75thPercentileScore)
    }

  @Test
  fun `an out-of-domain score band is coerced to null and counted, and the run does not fail`() =
    runBlocking {
      // The coercion fixture's ACT cells are 0 and 41 -- both outside the
      // published 1-36 scale (RFC 195). Mechanism A applies to them exactly as
      // it does to ADM_RATE: nulled, counted, and the institution still loads.
      val coercion = fixture("scorecard-institutions-coercion-fixture.csv")
      val result = loader.load(coercion, fixture("scorecard-fields-empty-fixture.csv"))

      assertEquals(1, result.collegesLoaded, "an unusable band must never cost the whole college")
      assertEquals(1, result.fieldsCoercedToNull["act_composite_25th_percentile_score"])
      assertEquals(1, result.fieldsCoercedToNull["act_composite_75th_percentile_score"])

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 600600).getOrThrow() }
      assertNotNull(college)
      assertNull(college.actComposite25thPercentileScore)
      assertNull(college.actComposite75thPercentileScore)
      // The SAT cells of the same row are in domain and are kept: a coercion
      // is per cell, not per row.
      assertEquals(560, college.satReading25thPercentileScore)
      assertEquals(670, college.satMath75thPercentileScore)
    }

  @Test
  fun `half a published band is dropped whole, so the college still loads`() =
    runBlocking {
      // `colleges` CHECKs that a band is a pair, so a row carrying one half
      // would be REJECTED AT THE INSERT and the ingest would lose the whole
      // school over one unusable score. The loader drops the band instead, and
      // counts the drop under the band's own name so the run says a band was
      // lost. The pinned snapshot has no such row -- 0 mismatches over 6,273
      // -- which is exactly why the guard has to be tested by hand.
      val institutions =
        institutionCsv(
          "half-band",
          "UNITID" to "930001",
          "INSTNM" to "Half Band College",
          // A 25th with no 75th, and a 75th with no 25th: both directions.
          "SATVR25" to "600",
          "SATMT75" to "700",
          "ACTCM25" to "25",
          "ACTCM75" to "31",
        )

      val result = loader.load(institutions, fixture("scorecard-fields-empty-fixture.csv"))
      assertEquals(1, result.collegesLoaded, "the college must survive a half band")
      // The drops are read from their OWN tally, keyed by the band and the
      // reason. No suffix, and so no test that has to parse a key to learn
      // what it counts -- which is what reading them out of
      // `fieldsCoercedToNull` cost.
      assertEquals(
        mapOf(
          PairDrop("sat_reading", PairDropReason.HALF_A_PAIR) to 1,
          PairDrop("sat_math", PairDropReason.HALF_A_PAIR) to 1,
        ),
        result.pairsDropped,
        "both dropped bands are counted, by band and by reason",
      )
      assertTrue(
        result.fieldsCoercedToNull.keys.none { it.contains("band_dropped") },
        "a dropped band is not a coerced column: ${result.fieldsCoercedToNull.keys}",
      )

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 930001).getOrThrow() }
      assertNotNull(college)
      assertNull(college.satReading25thPercentileScore, "the surviving half is dropped with its partner")
      assertNull(college.satMath75thPercentileScore)
      // The COMPLETE band on the same row is untouched: the rule is per band.
      assertEquals(25, college.actComposite25thPercentileScore)
      assertEquals(31, college.actComposite75thPercentileScore)
    }

  @Test
  fun `an inverted published band is dropped whole, so the college still loads`() =
    runBlocking {
      // The failure this guards is NOT a missing band: `colleges` CHECKs that a
      // band is ORDERED, so an inverted pair passes both domain reads, is mapped,
      // and is then rejected AT THE UPSERT -- and the run loses the whole COLLEGE,
      // its name, its location and its enrolment, over one unusable score.
      // The pinned snapshot has no such row (0 mismatches over 6,273), so this
      // case exists only because it is built here.
      val institutions =
        institutionCsv(
          "inverted-band",
          "UNITID" to "930002",
          "INSTNM" to "Inverted Band College",
          // Both in domain, both present -- and the wrong way round.
          "SATVR25" to "700",
          "SATVR75" to "600",
          "ACTCM25" to "25",
          "ACTCM75" to "31",
        )

      val result = loader.load(institutions, fixture("scorecard-fields-empty-fixture.csv"))
      assertEquals(1, result.collegesLoaded, "an inverted band must never cost the whole college")
      assertEquals(emptyMap(), result.skipsByReason, "and must not be a skip: ${result.skipsByReason}")
      assertEquals(
        mapOf(PairDrop("sat_reading", PairDropReason.INVERTED) to 1),
        result.pairsDropped,
        // The reason is kept DISTINCT from the half-pair case: they are two
        // different faults in the published file and an operator acts on them
        // differently.
        "an inverted pair is counted under its own reason",
      )

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 930002).getOrThrow() }
      assertNotNull(college)
      assertNull(college.satReading25thPercentileScore, "an inverted pair is not a range, so neither half is kept")
      assertNull(college.satReading75thPercentileScore)
      // The band that IS a range on the same row is untouched: the rule is per band.
      assertEquals(25, college.actComposite25thPercentileScore)
      assertEquals(31, college.actComposite75thPercentileScore)
    }

  @Test
  fun `a band whose two cells are both unreadable is counted, not silently absent`() =
    runBlocking {
      // The case no per-cell reader can see. `PrivacySuppressed`, `NA` and a
      // thousands-separated number are all PRESENT and all unparseable, so
      // each cell is nulled by the blank-cell idiom with no tally anywhere --
      // and the row reaches `colleges` as six nulls, indistinguishable from
      // the 5,234 schools that publish no score at all. A published band we
      // could not read is exactly what the drop counter exists to show.
      val institutions =
        institutionCsv(
          "junk-band",
          "UNITID" to "930003",
          "INSTNM" to "Junk Band College",
          // The literal, not a constant: RFC 206 replaced the single
          // `PRIVACY_SUPPRESSED` with a SET of spellings, and this test does
          // not care which sentinel it is. What it needs is a cell that is
          // PRESENT and unparseable as an Int -- the pair readers below have
          // no suppression status to keep, so every such cell is one thing to
          // them: a published band we could not read.
          "SATVR25" to "PrivacySuppressed",
          "SATVR75" to "PrivacySuppressed",
          "SATMT25" to "NA",
          "SATMT75" to "1,200",
          "ACTCM25" to "25",
          "ACTCM75" to "31",
        )

      val result = loader.load(institutions, fixture("scorecard-fields-empty-fixture.csv"))
      assertEquals(1, result.collegesLoaded, "an unreadable band must never cost the whole college")
      assertEquals(
        mapOf(
          PairDrop("sat_reading", PairDropReason.UNREADABLE) to 1,
          PairDrop("sat_math", PairDropReason.UNREADABLE) to 1,
        ),
        result.pairsDropped,
        "both corrupt bands are counted, and neither is confused with a school that publishes none",
      )
      // The BLANK band is NOT counted: a school that publishes no score is
      // ordinary, and counting it would bury the two rows above.
      val blank =
        loader.load(
          institutionCsv("blank-band", "UNITID" to "930004", "INSTNM" to "Blank Band College"),
          fixture("scorecard-fields-empty-fixture.csv"),
        )
      assertEquals(emptyMap(), blank.pairsDropped, "no published score is not a dropped band")

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 930003).getOrThrow() }
      assertNotNull(college)
      assertNull(college.satReading25thPercentileScore)
      assertNull(college.satMath75thPercentileScore)
      assertEquals(25, college.actComposite25thPercentileScore, "the readable band on the same row is untouched")
    }

  @Test
  fun `a band with one blank cell and one unreadable cell is counted, not silently absent`() =
    runBlocking {
      // The MIXED pair, one cell over from the both-junk case above. A blank
      // beside a `PrivacySuppressed` resolves to the same two nulls, no earlier
      // reason matches -- "half a pair" needs one cell to SURVIVE -- and under
      // an `&&` on both cells being unreadable the row reached `colleges` as
      // two nulls nothing counted anywhere. This school DID publish something
      // for this band and we could not read it, which is the whole point of the
      // tally.
      val institutions =
        institutionCsv(
          "mixed-band",
          "UNITID" to "930005",
          "INSTNM" to "Mixed Band College",
          // Blank lower, unreadable upper (the literal, per the case above)...
          "SATVR75" to "PrivacySuppressed",
          // ...and the other way round, so neither ordering is the tested one.
          "SATMT25" to "NA",
          "ACTCM25" to "25",
          "ACTCM75" to "31",
        )

      val result = loader.load(institutions, fixture("scorecard-fields-empty-fixture.csv"))
      assertEquals(1, result.collegesLoaded, "a mixed unreadable band must never cost the whole college")
      assertEquals(
        mapOf(
          PairDrop("sat_reading", PairDropReason.UNREADABLE) to 1,
          PairDrop("sat_math", PairDropReason.UNREADABLE) to 1,
        ),
        result.pairsDropped,
        "one present-but-unreadable cell is enough: the band was published and is lost",
      )

      val college = withSession { CollegesDao.findByIpedsUnitId(it, 930005).getOrThrow() }
      assertNotNull(college)
      assertNull(college.satReading75thPercentileScore)
      assertNull(college.satMath25thPercentileScore)
      assertEquals(25, college.actComposite25thPercentileScore, "the readable band on the same row is untouched")
    }

  /**
   * One institution CSV holding a single row, written through the module's own
   * CSV writer.
   *
   * `joinToString(",")` quotes NOTHING: one cell containing a comma shifts
   * every later column, and the test then asserts confidently about the wrong
   * variable. `CSVFormat.DEFAULT` is already on this classpath and already
   * used by sibling tests for exactly this reason.
   *
   * The five required identity cells are defaulted here so each caller writes
   * only the cells its own case is about.
   */
  private fun institutionCsv(
    name: String,
    vararg cells: Pair<String, String>,
  ): File {
    val row =
      mapOf(
        "CITY" to "Townsville",
        "STABBR" to "CA",
        "CONTROL" to "1",
      ) + cells.toMap()
    val file = File.createTempFile("$name-institutions", ".csv")
    file.deleteOnExit()
    CSVFormat.DEFAULT.print(file, Charsets.UTF_8).use { printer ->
      printer.printRecord(CollegeScorecardLoader.REQUIRED_INSTITUTION_COLUMNS)
      printer.printRecord(CollegeScorecardLoader.REQUIRED_INSTITUTION_COLUMNS.map { row[it] ?: "" })
    }
    return file
  }

  @Test
  fun `the required institution columns name all six score-band variables`() {
    // A snapshot that drops one of them must fail LOUDLY, before any write,
    // rather than loading 6,273 colleges with a silently empty band (RFC 195).
    // The coverage test above proves the mappers read nothing outside this
    // list; this one proves the list names these six by their published
    // variable names.
    assertEquals(
      emptySet(),
      setOf("SATVR25", "SATVR75", "SATMT25", "SATMT75", "ACTCM25", "ACTCM75") -
        CollegeScorecardLoader.REQUIRED_INSTITUTION_COLUMNS.toSet(),
      "every score-band variable must be asserted in the header: ${CollegeScorecardLoader.REQUIRED_INSTITUTION_COLUMNS}",
    )
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
