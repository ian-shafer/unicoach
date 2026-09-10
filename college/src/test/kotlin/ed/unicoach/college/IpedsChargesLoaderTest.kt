package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CollegeIpedsChargesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IpedsImputationFlag
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [IpedsChargesLoader] (RFC 161): the narrow reshape of IC_AY's wide
 * year × tier × component cross-product, the X-flag enum and its two fatals,
 * and the coverage the file really has.
 *
 * Every fixture row is a VERBATIM subset of the published `ic2023_ay.csv`
 * (Austin Community College District 222992, UC San Diego 110680, Harvard
 * 166027, Florida State College at Jacksonville 133702) except the `*-flags-*`, `*-unknown-flag-*`, `*-value-without-status-*`,
 * `*-status-without-value-*` and `*-professional-practice-flag-*` files, which
 * are those same rows with named flag or value cells edited to reach codes and
 * shapes the 2023 file does not happen to contain.
 */
class IpedsChargesLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = IpedsChargesLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-ic-ay-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")

  private fun source(name: String) = SourceFile(fixture(name), sourceArg = name)

  private fun load(name: String = "ipeds-ic2023-ay-fixture.csv"): IpedsChargesLoadResult =
    runBlocking {
      scorecardLoader.load(institutionCsv, fieldsCsv)
      loader.load(source(name), IpedsChargeVocabulary.SURVEY_YEAR)
    }

  private fun amount(
    ipedsUnitId: Int,
    variable: String,
    academicYear: AcademicYear = AcademicYear(2023),
  ): Int? =
    query(
      "SELECT c.amount_usd FROM college_ipeds_charges c JOIN colleges g ON g.id = c.college_id " +
        "WHERE g.ipeds_unit_id = $ipedsUnitId AND c.charge_variable = '$variable' " +
        "AND c.academic_year = ${academicYear.firstCalendarYear}",
    ) { rs -> rs.getInt(1).takeUnless { rs.wasNull() } }.single()

  private fun flag(
    ipedsUnitId: Int,
    variable: String,
    academicYear: AcademicYear = AcademicYear(2023),
  ): String =
    query(
      "SELECT c.imputation_flag FROM college_ipeds_charges c JOIN colleges g ON g.id = c.college_id " +
        "WHERE g.ipeds_unit_id = $ipedsUnitId AND c.charge_variable = '$variable' " +
        "AND c.academic_year = ${academicYear.firstCalendarYear}",
    ) { rs -> rs.getString(1) }.single()

  // ---------------------------------------------------------------------------
  // The reshape
  // ---------------------------------------------------------------------------

  @Test
  fun `each record becomes twelve variables times four years of staged rows`() {
    // The whole point of the narrow table: a published year is DATA, so next
    // year's file adds rows and needs no migration.
    val result = load()
    assertEquals(IC_AY_FIXTURE_RECORDS, result.seen)
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, result.loaded)
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, result.inserted)
    assertEquals(0, result.skipped)
    assertEquals(12, IpedsChargeVocabulary.STEMS.size)
    assertEquals(4, IpedsChargeVocabulary.ACADEMIC_YEAR_BY_SUFFIX.size)
  }

  @Test
  fun `the year suffix decodes to the academic year it means, all four landing distinctly`() {
    load()
    assertEquals(
      listOf(2020, 2021, 2022, 2023).map(::AcademicYear),
      query("SELECT DISTINCT academic_year FROM college_ipeds_charges ORDER BY academic_year") {
        AcademicYear(it.getInt(1))
      },
    )
    assertEquals(
      listOf(2020, 2021, 2022, 2023).map { AcademicYear(it) to IC_AY_FIXTURE_RECORDS * 12 },
      query(
        "SELECT academic_year, count(*) FROM college_ipeds_charges GROUP BY academic_year ORDER BY academic_year",
      ) { rs -> AcademicYear(rs.getInt(1)) to rs.getInt(2) },
    )
  }

  @Test
  fun `the charge_variable stored is the stem without its year suffix`() {
    load()
    assertEquals(
      IpedsChargeVocabulary.STEMS.sorted(),
      query("SELECT DISTINCT charge_variable FROM college_ipeds_charges ORDER BY charge_variable") {
        it.getString(1)
      },
    )
  }

  // ---------------------------------------------------------------------------
  // The values IC_AY actually publishes
  // ---------------------------------------------------------------------------

  @Test
  fun `Austin CC's three residency tiers are three different published prices`() {
    // The slice's whole reason to exist. IC_AY carries in-district, in-state and
    // out-of-state as separate first-class variables; the Scorecard collapses
    // the first two into one.
    load()
    assertEquals(2550, amount(222992, "CHG1AY"))
    assertEquals(8580, amount(222992, "CHG2AY"))
    assertEquals(10590, amount(222992, "CHG3AY"))
  }

  @Test
  fun `the in-district and in-state gap lives in the FEE component, not the tuition one`() {
    // 12 of the 269 real mismatches differ ONLY in fees, so a design that tests
    // in-district-ness on tuition alone misses them. Austin CC is the shape:
    // its fees differ 540 vs 6,570 while its tuition-only figure does not.
    load()
    assertEquals(540, amount(222992, "CHG1AF"))
    assertEquals(6570, amount(222992, "CHG2AF"))
    assertEquals(540, amount(222992, "CHG3AF"))
  }

  @Test
  fun `a private reports one price for all three tiers, and no off-campus figures at all`() {
    load()
    assertEquals(59076, amount(166027, "CHG1AY"))
    assertEquals(59076, amount(166027, "CHG2AY"))
    assertEquals(59076, amount(166027, "CHG3AY"))
    assertNull(amount(166027, "CHG7AY"))
    assertEquals("A", flag(166027, "CHG7AY"))
  }

  @Test
  fun `a commuter college reports no on-campus figures, and that absence is not a zero`() {
    load()
    assertNull(amount(222992, "CHG5AY"))
    assertEquals("A", flag(222992, "CHG5AY"))
    assertEquals(18240, amount(222992, "CHG7AY"))
    assertEquals(5408, amount(222992, "CHG9AY"))
  }

  @Test
  fun `earlier years carry their own values, not a copy of the latest`() {
    load()
    assertEquals(15265, amount(110680, "CHG2AY", AcademicYear(2023)))
    assertEquals(14906, amount(110680, "CHG2AY", AcademicYear(2022)))
    assertEquals(17198, amount(110680, "CHG5AY", AcademicYear(2023)))
    assertEquals(16710, amount(110680, "CHG5AY", AcademicYear(2022)))
  }

  // ---------------------------------------------------------------------------
  // The X-flag mapping
  // ---------------------------------------------------------------------------

  @Test
  fun `the flag distribution over the real fixture is reported by raw code`() {
    val result = load()
    // 4 records x 12 stems x 4 years = 192 cells. The SPLIT is the operator's
    // evidence, so it is pinned as counts: a run that swapped every `R` for an
    // `A` would still satisfy a key set and a total. The fixture is fixed
    // bytes, so these numbers are stable.
    assertEquals(
      mapOf(
        IpedsImputationFlag.NOT_APPLICABLE to 19,
        // 173, not 125: RFC 183's fourth institution (133702) publishes all 48
        // of its cells, so every one of them is an `R`.
        IpedsImputationFlag.REPORTED to 173,
      ),
      result.cellsByFlag,
    )
    // The tally is keyed by the ENUM, and the published code is what the JSON
    // and log edges render -- so the operator-visible axis is still the raw
    // X-code, and nothing splits a string to read it.
    assertEquals(mapOf("A" to 19, "R" to 173), result.cellsByFlag.mapKeys { it.key.code })
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, result.cellsByFlag.values.sum())
  }

  @Test
  fun `an imputed code stores its value under imputed_by_publisher, never reported`() {
    load("ipeds-ic2023-ay-flags-fixture.csv")
    assertEquals("L", flag(110680, "CHG2AY"))
    assertEquals(15265, amount(110680, "CHG2AY"))
    assertEquals(
      FigureStatus.IMPUTED_BY_PUBLISHER,
      IpedsImputationFlag.fromCode("L")!!.status,
    )
  }

  @Test
  fun `an implied zero keeps its real zero, which is not an absence`() {
    load("ipeds-ic2023-ay-flags-fixture.csv")
    assertEquals("Z", flag(110680, "CHG4AY"))
    assertEquals(0, amount(110680, "CHG4AY"))
  }

  @Test
  fun `a blank, do-not-know or unusable cell stores no value`() {
    load("ipeds-ic2023-ay-flags-fixture.csv")
    assertEquals("B", flag(166027, "CHG2AY"))
    assertNull(amount(166027, "CHG2AY"))
    assertEquals("D", flag(166027, "CHG4AY"))
    assertNull(amount(166027, "CHG4AY"))
    assertEquals("H", flag(222992, "CHG9AY"))
    assertNull(amount(222992, "CHG9AY"))
  }

  @Test
  fun `an analyst-corrected value reads as reported, because it is a real reported number`() {
    load("ipeds-ic2023-ay-flags-fixture.csv")
    assertEquals("C", flag(222992, "CHG3AY"))
    assertEquals(10590, amount(222992, "CHG3AY"))
    assertEquals(
      FigureStatus.REPORTED,
      IpedsImputationFlag.fromCode("C")!!.status,
    )
  }

  @Test
  fun `every published imputation code has a reading, and the enum is closed`() {
    // The codebook ships the fourteen codes as a COMMENT block with no
    // `label define`, so this enum is their one declaration. Thirteen are
    // readable; `Y` is published but never occurs on a loaded variable.
    val codes =
      IpedsImputationFlag.entries
        .map { it.code }
        .toSet()
    assertEquals(
      setOf("A", "B", "C", "D", "G", "H", "J", "K", "L", "N", "P", "R", "Z"),
      codes,
    )
    assertTrue(IpedsImputationFlag.PROFESSIONAL_PRACTICE !in codes)
  }

  @Test
  fun `a value bears a value exactly when its status does, on every code`() {
    // The value-IFF-flag rule has ONE implementation, `mapReading()`, which both
    // the parse and the canonical fill call. This drives it in both directions
    // for every published code, so an inverted branch cannot pass by being
    // exercised only on the codes the fixture happens to carry.
    for (entry in IpedsImputationFlag.entries) {
      val cell = IpedsChargesLoader.CellRef.Published("CHG2AY3", ipedsUnitId = 222992, line = 2)
      if (entry.status.valueBearing) {
        assertEquals(1234, (entry.mapReading(1234, cell) as FigureReading.Present).value, entry.code)
        assertFailsWith<IllegalArgumentException>(entry.code) { entry.mapReading(null, cell) }
      } else {
        assertEquals(entry.status, entry.mapReading(null, cell).status, entry.code)
        assertFailsWith<IllegalArgumentException>(entry.code) { entry.mapReading(1234, cell) }
      }
    }
  }

  @Test
  fun `a broken pairing names the ROW, from the file side and from the staging side alike`() {
    // The identity is STRUCTURED, so both readers of a charge cell name what
    // they actually hold -- a CSV column plus a UNITID plus a line, or a staged
    // row's id plus its college and stored key -- instead of each inventing its
    // own sentence fragment for one shared `String` parameter.
    val published = IpedsChargesLoader.CellRef.Published("CHG2AY3", ipedsUnitId = 222992, line = 7)
    val fromFile =
      assertFailsWith<IllegalArgumentException> {
        IpedsImputationFlag.REPORTED.mapReading(null, published)
      }
    assertContains(fromFile.message!!, "CHG2AY3")
    assertContains(fromFile.message!!, "ipeds_unit_id=222992")
    assertContains(fromFile.message!!, "line=7")

    val chargeId =
      ed.unicoach.db.models
        .CollegeIpedsChargeId(java.util.UUID.randomUUID())
    val collegeId = java.util.UUID.randomUUID()
    val staged =
      IpedsChargesLoader.CellRef.Staged(
        id = chargeId,
        collegeId = collegeId,
        chargeVariable = "CHG5AY",
        academicYear = AcademicYear(2023),
        sourceVariable = "CHG5AY3",
      )
    val fromStaging =
      assertFailsWith<IllegalArgumentException> {
        IpedsImputationFlag.NOT_APPLICABLE.mapReading(9999, staged)
      }
    assertContains(fromStaging.message!!, "id=${chargeId.value}")
    assertContains(fromStaging.message!!, "college_id=$collegeId")
    assertContains(fromStaging.message!!, "charge_variable=CHG5AY")
    assertContains(fromStaging.message!!, "academic_year=2023-24")
  }

  // ---------------------------------------------------------------------------
  // The fatals
  // ---------------------------------------------------------------------------

  @Test
  fun `an unknown imputation code is fatal, never read as reported`() {
    val error = assertFailsWith<IllegalStateException> { load("ipeds-ic2023-ay-unknown-flag-fixture.csv") }
    assertContains(error.message!!, "XCHG2AY3")
    assertContains(error.message!!, "[Q]")
  }

  @Test
  fun `a value-bearing flag over an empty cell is fatal, not read as an absence`() {
    // The OTHER direction of the value-IFF-status rule: the fixture leaves
    // Austin CC's published `R` on CHG2AY3 and empties the cell. Without this
    // the guard has never once executed, so an inverted branch there would
    // pass the whole suite.
    // IllegalArgumentException, not IllegalStateException: the rule has one
    // implementation now (`IpedsImputationFlag.mapReading`, a `require`), shared with
    // the canonical fill instead of re-written at the parse.
    val error = assertFailsWith<IllegalArgumentException> { load("ipeds-ic2023-ay-status-without-value-fixture.csv") }
    assertContains(error.message!!, "CHG2AY3")
    assertContains(error.message!!, "a flag that bears a value must have one")
  }

  @Test
  fun `the published Y code is fatal on a loaded variable, exactly like an unknown one`() {
    // `Y` is a REAL published code, so a file carrying it would not look wrong
    // -- which is why "never occurs on a loaded variable" needs a cell, not an
    // inference from "the enum omits Y" plus "Q fatals".
    val error =
      assertFailsWith<IllegalStateException> { load("ipeds-ic2023-ay-professional-practice-flag-fixture.csv") }
    assertContains(error.message!!, "XCHG2AY3")
    assertContains(error.message!!, "[${IpedsImputationFlag.PROFESSIONAL_PRACTICE}]")
    assertContains(error.message!!, "never occurs on a loaded variable")
  }

  @Test
  fun `a value under a valueless flag is fatal, not silently kept or dropped`() {
    val error = assertFailsWith<IllegalArgumentException> { load("ipeds-ic2023-ay-value-without-status-fixture.csv") }
    assertContains(error.message!!, "CHG5AY3")
    assertContains(error.message!!, "a value exists exactly when its flag bears one")
  }

  @Test
  fun `a blank value cell is fatal, never read as the missing-value token`() {
    // The boundary IC_AY publishes is exactly two shapes: a whole-dollar amount
    // or the literal `.`. A blank is neither -- and a blank folded to "missing"
    // is a value column that emptied upstream arriving as ordinary absence,
    // with no number and nothing said.
    val error = assertFailsWith<IllegalStateException> { load("ipeds-ic2023-ay-blank-value-fixture.csv") }
    assertContains(error.message!!, "CHG2AY3")
    assertContains(error.message!!, "is blank")
    withSession { session -> assertEquals(0, CollegeIpedsChargesDao.rowCount(session).getOrThrow()) }
  }

  @Test
  fun `a signed amount is refused at the parse, where the row identity is still in hand`() {
    // `toIntOrNull` takes "-500" and "+500". Left to the database, a negative
    // arrives as an anonymous `amount_nonneg_check` ROW FAILURE, counted with
    // no cell and no line -- so it is refused here, naming both.
    val error = assertFailsWith<IllegalStateException> { load("ipeds-ic2023-ay-signed-value-fixture.csv") }
    assertContains(error.message!!, "CHG2AY3")
    assertContains(error.message!!, "[-500]")
    assertContains(error.message!!, "neither a whole-dollar")
  }

  @Test
  fun `a row this run FAILED to write is retired by the prune, not left holding the last file's amount`() {
    // The prune's keep-set is the keys actually WRITTEN. Built from three
    // independent axes instead, it would spare exactly this row -- its college,
    // its variable and its year all appear elsewhere in the same run -- and the
    // canonical fill, which takes the first write per key, would then serve the
    // PREVIOUS file's amount as though IC_AY had just published it.
    load()
    assertEquals(15265, amount(110680, "CHG2AY"))
    // NOT VALID, so the rows already staged stand: what is under test is the
    // SECOND pass, in which that one natural key can no longer be written.
    withSession { session ->
      session
        .prepareStatement(
          "ALTER TABLE college_ipeds_charges ADD CONSTRAINT tmp_reject_one_key " +
            "CHECK (NOT (charge_variable = 'CHG2AY' AND academic_year = 2023)) NOT VALID",
        ).use { it.execute() }
    }
    try {
      val again = runBlocking { loader.load(source("ipeds-ic2023-ay-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR) }
      // One row per institution failed; not one RECORD was skipped.
      assertEquals(IC_AY_FIXTURE_RECORDS, again.rowFailures)
      assertEquals(0, again.skipped)
      assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4 - IC_AY_FIXTURE_RECORDS, again.loaded)
      // ...and the rows the run could not write -- one per institution -- are
      // gone, rather than surviving with the amount the first load left.
      assertEquals(IC_AY_FIXTURE_RECORDS, again.pruned)
      assertEquals(
        emptyList(),
        query(
          "SELECT amount_usd FROM college_ipeds_charges " +
            "WHERE charge_variable = 'CHG2AY' AND academic_year = 2023",
        ) { it.getInt(1) },
      )
      assertEquals(
        IC_AY_FIXTURE_RECORDS * 12 * 4 - IC_AY_FIXTURE_RECORDS,
        withSession { count(it, "college_ipeds_charges") },
      )
    } finally {
      withSession { session ->
        session.prepareStatement("ALTER TABLE college_ipeds_charges DROP CONSTRAINT tmp_reject_one_key").use {
          it.execute()
        }
      }
    }
  }

  @Test
  fun `a missing required column is refused before any row is written`() {
    val error =
      assertFailsWith<MissingSourceColumnsException> {
        runBlocking {
          loader.assertHeaders(source("ipeds-ic2023-ay-missing-column-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR)
        }
      }
    assertContains(error.message!!, "CHG2AY3")
    withSession { session -> assertEquals(0, CollegeIpedsChargesDao.rowCount(session).getOrThrow()) }
  }

  @Test
  fun `the required column list covers every value column and its X partner`() {
    // 1 key + 12 stems x 4 years x 2 columns. Built, never retyped: a column
    // missing from the list loads as an absence indistinguishable from a real
    // one.
    assertEquals(1 + 12 * 4 * 2, IpedsChargesLoader.REQUIRED_COLUMNS.size)
    assertEquals(IpedsChargesLoader.REQUIRED_COLUMNS.size, IpedsChargesLoader.REQUIRED_COLUMNS.distinct().size)
    assertContains(IpedsChargesLoader.REQUIRED_COLUMNS, "CHG9AY3")
    assertContains(IpedsChargesLoader.REQUIRED_COLUMNS, "XCHG9AY3")
    // CHG*AT is derivable and is deliberately absent, as is the guaranteed-increase pair.
    assertTrue(IpedsChargesLoader.REQUIRED_COLUMNS.none { it.contains("AT") || it.endsWith("GTD") })
  }

  @Test
  fun `the real header's trailing space on CHG9AY3 does not hide the column`() {
    // The published header line ends `...,XCHG9AY3,CHG9AY3 ` -- with a space.
    // parseCsv already trims header names for adm2023.csv's ACTMT75; this pins
    // that the same defence covers IC_AY, against the REAL fixture bytes.
    val header = fixture("ipeds-ic2023-ay-fixture.csv").readLines().first()
    assertTrue(header.endsWith("CHG9AY3 "), "the fixture must keep the published trailing space: [$header]")
    load()
    assertEquals(5408, amount(222992, "CHG9AY"))
  }

  // ---------------------------------------------------------------------------
  // Coverage and re-ingest
  // ---------------------------------------------------------------------------

  @Test
  fun `a Scorecard institution with no IC_AY row is simply absent, never invented`() {
    // IC_AY covers academic-year charge reporters only; program-year reporters
    // are in IC_PY, which this repo does not ingest. The fixture's fourth
    // college (10236801) has a Scorecard row and no IC_AY row.
    load()
    assertEquals(
      emptyList(),
      query(
        "SELECT c.id FROM college_ipeds_charges c JOIN colleges g ON g.id = c.college_id " +
          "WHERE g.ipeds_unit_id = 10236801",
      ) { it.getString(1) },
    )
  }

  @Test
  fun `an institution that leaves IC_AY loses its staged rows, and does not serve last year's price forever`() {
    // Staging is upsert-only, so without the prune a departed institution's
    // rows survive every later ingest -- and, because the canonical fill takes
    // the FIRST write per key, those stale rows keep beating the Scorecard.
    // The family would be shown last year's price indefinitely, with nothing
    // saying so.
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, load().loaded)
    val second = runBlocking { loader.load(source("ipeds-ic2023-ay-departed-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR) }
    assertEquals(2, second.seen)
    assertEquals(2 * 12 * 4, second.loaded)
    // TWO institutions are missing from the departed file now (222992 and the
    // 133702 RFC 183 added), so a whole institution's rows are pruned twice.
    assertEquals(2 * 12 * 4, second.pruned)
    assertEquals(
      emptyList(),
      query(
        "SELECT c.id FROM college_ipeds_charges c JOIN colleges g ON g.id = c.college_id " +
          "WHERE g.ipeds_unit_id = 222992",
      ) { it.getString(1) },
    )
    assertEquals(2 * 12 * 4, withSession { count(it, "college_ipeds_charges") })
  }

  @Test
  fun `a superseded survey-year window is pruned, so the canonical fill never meets an undecodable year`() {
    // A year bump moves the four-year window: the new file stages 2021-22 to
    // 2024-25 and the old 2020-21 rows are left behind, orphaned. The fill
    // cannot map them, so they would be reported as drift forever. Simulated
    // with a hand-written row rather than a second pinned file, since the
    // window is a compile-time constant.
    load()
    val collegeId =
      query("SELECT id FROM colleges WHERE ipeds_unit_id = 222992") { it.getString(1) }.single()
    withSession { session ->
      session
        .prepareStatement(
          "INSERT INTO college_ipeds_charges (college_id, charge_variable, academic_year, amount_usd, " +
            "imputation_flag) VALUES (?::uuid, 'CHG2AY', 2019, 7350, 'R')",
        ).use { stmt ->
          stmt.setString(1, collegeId)
          stmt.executeUpdate()
        }
    }
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4 + 1, withSession { count(it, "college_ipeds_charges") })
    val again = runBlocking { loader.load(source("ipeds-ic2023-ay-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR) }
    assertEquals(1, again.pruned)
    assertEquals(
      IpedsChargeVocabulary.ACADEMIC_YEAR_BY_SUFFIX.values.toList(),
      query("SELECT DISTINCT academic_year FROM college_ipeds_charges ORDER BY academic_year") {
        AcademicYear(it.getInt(1))
      },
    )
  }

  @Test
  fun `a survey year the window does not decode is fatal, never stamped one year stale`() {
    // The suffix is a POSITION in the file's own window, so an IC2024_AY file
    // decoded against the 2023 window would date every staged row -- and every
    // price derived from it -- one year wrong, and exit green.
    val error =
      assertFailsWith<IllegalStateException> {
        runBlocking {
          scorecardLoader.load(institutionCsv, fieldsCsv)
          loader.load(source("ipeds-ic2023-ay-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR + 1)
        }
      }
    assertContains(error.message!!, "${IpedsChargeVocabulary.SURVEY_YEAR + 1}")
    assertContains(error.message!!, "IpedsChargeVocabulary.SURVEY_YEAR")
    assertEquals(0, withSession { count(it, "college_ipeds_charges") })
    // The header assertion carries the same guard, so the refusal lands before
    // ANY phase of the run commits rather than three phases in.
    assertFailsWith<IllegalStateException> {
      runBlocking {
        loader.assertHeaders(source("ipeds-ic2023-ay-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR + 1)
      }
    }
  }

  @Test
  fun `the staged stems and the canonical cells are one list, agreeing in both directions`() {
    // They used to be two hand-typed lists policed by a mid-run error(). The
    // stems are now DERIVED from the cell map, and this asserts the derivation
    // BOTH ways -- a subset check in one direction would pass a list that had
    // silently lost a stem.
    assertEquals(IpedsChargeVocabulary.CELLS.keys, IpedsChargeVocabulary.STEMS.toSet())
    assertEquals(IpedsChargeVocabulary.STEMS.toSet(), IpedsChargeVocabulary.CELLS.keys)
    // And the columns the loader actually reads name exactly those stems: the
    // vocabulary is the single source for the reshape as well as the mapping.
    val stemsRead =
      IpedsChargesLoader.REQUIRED_COLUMNS
        .filterNot { it == "UNITID" || it.startsWith("X") }
        .map { it.dropLast(1) }
        .toSet()
    assertEquals(IpedsChargeVocabulary.CELLS.keys, stemsRead)
    assertEquals(stemsRead, IpedsChargeVocabulary.CELLS.keys)
  }

  @Test
  fun `re-staging the same file is a loudly visible no-op`() {
    load()
    val again = runBlocking { loader.load(source("ipeds-ic2023-ay-fixture.csv"), IpedsChargeVocabulary.SURVEY_YEAR) }
    assertEquals(IC_AY_FIXTURE_RECORDS * 12 * 4, again.unchanged)
    assertEquals(0, again.inserted)
    assertEquals(0, again.changed)
    assertEquals(0, again.pruned)
  }
}
