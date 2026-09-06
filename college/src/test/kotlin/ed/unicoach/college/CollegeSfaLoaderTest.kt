package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `sfa` staging phase (RFC 162): the file-relative year-suffix rule, the
 * X-flag reading, the wholesale rebuild, and the refusals.
 *
 * The fixture is a VERBATIM four-row subset of the real `sfa2223.csv` --
 * header and cells exactly as NCES published them -- so every number asserted
 * here is the publisher's, not a hand-typed stand-in.
 */
class CollegeSfaLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = CollegeSfaLoader(database)
  private val institutionCsv = fixture("scorecard-institutions-sfa-fixture.csv")
  private val fieldsCsv = fixture("scorecard-fields-fixture.csv")
  private val sfaCsv = fixture("ipeds-sfa2223-fixture.csv")

  private fun sources(
    file: File = sfaCsv,
    aidYearStart: Int = SFA_AID_YEAR_START,
  ) = SfaSources(SourceFile(file, file.path), aidYearStart)

  private fun stage(sources: SfaSources = sources()): SfaLoadResult =
    runBlocking {
      scorecardLoader.load(institutionCsv, fieldsCsv)
      loader.assertHeaders(sources)
      staged(loader.load(sources))
    }

  /**
   * The staged half of a load outcome. An [SfaLoadOutcome.UnknownImputationFlag]
   * is a real outcome the phase decides is fatal, so a test that did not
   * provoke one says so here rather than silently reading past it.
   */
  private fun staged(outcome: SfaLoadOutcome): SfaLoadResult =
    when (outcome) {
      is SfaLoadOutcome.Staged -> outcome.result
      is SfaLoadOutcome.UnknownImputationFlags -> error("unexpected unknown imputation flag(s): ${outcome.flags}")
    }

  @Test
  fun `every variable of every matched institution is staged, and the count is the arithmetic`() {
    val result = stage()
    // The fixture's seven institutions all exist as colleges, and each gets one
    // row per variable in the read set -- no partial institutions.
    assertEquals(7, result.seen)
    assertEquals(7, result.institutionsLoaded)
    assertEquals(7 * SfaVariables.ALL.size, result.cellsWritten)
    assertEquals(emptyMap(), result.skipsByReason)
    assertEquals(
      result.cellsWritten,
      query("SELECT count(*) FROM college_sfa") { it.getInt(1) }.single(),
    )
  }

  @Test
  fun `the year suffix is relative to the file, so one pin yields three aid years`() {
    stage()
    // Suffix 2 is the file's own aid year, 1 one earlier, 0 two earlier --
    // measured across two published files. Nothing hardcodes 2022-23.
    val years =
      query(
        "SELECT variable, aid_year FROM college_sfa WHERE ipeds_unit_id = 209807 AND variable LIKE 'npist%' ORDER BY variable",
      ) { rs -> rs.getString(1) to AcademicYear(rs.getInt(2)) }
    assertEquals(
      listOf("npist0" to AcademicYear(2020), "npist1" to AcademicYear(2021), "npist2" to AcademicYear(2022)),
      years,
    )
    // A single-year variable carries the file's own aid year, not a suffix.
    assertEquals(
      listOf(AcademicYear(2022)),
      query("SELECT aid_year FROM college_sfa WHERE ipeds_unit_id = 209807 AND variable = 'upgrnta'") {
        AcademicYear(it.getInt(1))
      },
    )
    assertEquals(
      AcademicYear(2020),
      SfaVariables.aidYear("npis412", 2020),
      "the rule is arithmetic on the FILE's year, so a 2020-21 file's suffix 2 is 2020-21",
    )
  }

  @Test
  fun `a published value lands with its raw flag, and a not-applicable cell lands with no value`() {
    stage()
    // The University of Alabama's average Pell award, exactly as published.
    val pell =
      query(
        "SELECT value, publisher_flag FROM college_sfa WHERE ipeds_unit_id = 100751 AND variable = 'upgrnta'",
      ) { rs -> rs.getBigDecimal(1).toInt() to rs.getString(2) }
    assertEquals(listOf(5202 to "R"), pell)
    // College of DuPage has no residence halls: the on-campus count is flagged
    // A and carries NO value, beside a reported with-family count that does.
    val dupage =
      query(
        "SELECT variable, value, publisher_flag FROM college_sfa WHERE ipeds_unit_id = 144865 " +
          "AND variable IN ('gis4on2', 'gis4wf2') ORDER BY variable",
      ) { rs -> Triple(rs.getString(1), rs.getBigDecimal(2)?.toInt(), rs.getString(3)) }
    assertEquals(
      listOf(Triple("gis4on2", null, "A"), Triple("gis4wf2", 501, "R")),
      dupage,
    )
  }

  @Test
  fun `a reported zero is staged as zero, never as an absent value`() {
    stage()
    // Portland State reports ZERO students paying the in-district rate, flag
    // R. A loader that read the blank-vs-zero distinction wrongly would make
    // "no one pays that rate" indistinguishable from "not reported".
    val inDistrict =
      query(
        "SELECT value, publisher_flag FROM college_sfa WHERE ipeds_unit_id = 209807 AND variable = 'scfa11n'",
      ) { rs -> rs.getBigDecimal(1)?.toInt() to rs.getString(2) }
    assertEquals(listOf(0 to "R"), inDistrict)
  }

  @Test
  fun `the two variable families are mutually exclusive in the source`() {
    stage()
    // Boston University (private) publishes NPGRN and flags NPIST not
    // applicable; the three publics do the reverse. Reading only the public
    // family would drop ~65% of the country's colleges.
    val flags =
      query(
        "SELECT ipeds_unit_id, variable, publisher_flag FROM college_sfa " +
          "WHERE variable IN ('npist2', 'npgrn2') ORDER BY ipeds_unit_id, variable",
      ) { rs -> Triple(rs.getInt(1), rs.getString(2), rs.getString(3)) }
    assertTrue(flags.contains(Triple(164988, "npgrn2", "R")), "the private publishes NPGRN")
    assertTrue(flags.contains(Triple(164988, "npist2", "A")), "and flags NPIST not applicable")
    assertTrue(flags.contains(Triple(209807, "npist2", "R")), "the public publishes NPIST")
    assertTrue(flags.contains(Triple(209807, "npgrn2", "A")), "and flags NPGRN not applicable")
  }

  @Test
  fun `an institution with no college is counted and staged for nobody`() {
    val result =
      runBlocking {
        // No Scorecard load at all: every SFA row names an institution this
        // snapshot does not have.
        staged(loader.load(sources()))
      }
    assertEquals(7, result.seen)
    assertEquals(0, result.institutionsLoaded)
    assertEquals(7, result.unmatchedIpedsUnitIds)
    assertEquals(0, query("SELECT count(*) FROM college_sfa") { it.getInt(1) }.single())
  }

  @Test
  fun `the load is a wholesale rebuild, so a second run leaves the same rows`() {
    val first = stage()
    val second = stage()
    assertEquals(first.cellsWritten, second.cellsWritten)
    assertEquals(
      first.cellsWritten,
      query("SELECT count(*) FROM college_sfa") { it.getInt(1) }.single(),
      "a rebuild replaces the staged cells rather than doubling them",
    )
  }

  @Test
  fun `an unknown imputation code is a RETURNED outcome, never a defaulted status`() {
    // The PUBLISHED column name, upper case, because that is what the header
    // of the verbatim fixture actually says.
    val edited = withCell("XUPGRNTA", "Q")
    val outcome =
      runBlocking {
        scorecardLoader.load(institutionCsv, fieldsCsv)
        loader.load(sources(edited))
      }
    // A statement about the FILE, returned like every SkipReason -- so a
    // caller can tell it from a JDBC fault. The PHASE decides it is fatal.
    val unknown = assertIs<SfaLoadOutcome.UnknownImputationFlags>(outcome)
    val flag = unknown.flags.single()
    assertEquals("XUPGRNTA", flag.column, "the outcome names the column")
    assertEquals("Q", flag.code, "and the letter the publisher used")
    assertEquals(7, flag.occurrences, "and how many cells carried it -- every row of the fixture")
    assertTrue(flag.ipedsUnitId > 0, "and the first institution it appeared at: ${flag.ipedsUnitId}")
    assertTrue(flag.line > 0, "and the CSV line, so the cell is findable: ${flag.line}")
    assertEquals(
      0,
      query("SELECT count(*) FROM college_sfa") { it.getInt(1) }.single(),
      "and nothing was staged: the read is refused before the delete and the insert",
    )
  }

  @Test
  fun `every unknown letter in the file is reported, not just the first`() {
    // Two different columns changed. Aborting on the first would cost an
    // operator one whole ingest per bad letter to learn the same thing.
    val edited = withCell("XUPGRNTA", "Q", withCell("XNPIST2", "Y"))
    val outcome =
      runBlocking {
        scorecardLoader.load(institutionCsv, fieldsCsv)
        loader.load(sources(edited))
      }
    val unknown = assertIs<SfaLoadOutcome.UnknownImputationFlags>(outcome)
    assertEquals(
      setOf("XNPIST2" to "Y", "XUPGRNTA" to "Q"),
      unknown.flags.map { it.column to it.code }.toSet(),
      "both vocabulary changes are named",
    )
  }

  @Test
  fun `an EMPTY flag cell is refused as itself, never as an empty-string code`() {
    // `""` is not a published IPEDS code. The outcome must say the cell was
    // blank, which is a different publisher change from a new letter.
    val outcome =
      runBlocking {
        scorecardLoader.load(institutionCsv, fieldsCsv)
        loader.load(sources(withCell("XUPGRNTA", "")))
      }
    val unknown = assertIs<SfaLoadOutcome.UnknownImputationFlags>(outcome)
    assertEquals(null, unknown.flags.single().code, "the absent flag cell arrives as null, not as an empty code")
  }

  @Test
  fun `a filled value cell this reader cannot parse is its own counted loss`() {
    // A published cell holding "NA" is neither a blank nor a number. Folded
    // into the blank it would be staged under a value-bearing flag and then
    // reported by the fill as a disagreement by the PUBLISHER -- our parse
    // loss, wearing their name, in the metric that exists to expose theirs.
    // (A thousands separator is the other real shape of this, but a comma
    // cannot be written through this fixture's own comma-split rewriter.)
    val result = stage(sources(withCell("UPGRNTA", "NA")))
    assertEquals(mapOf("upgrnta" to 7), result.valuesUnreadable, "counted by variable, once per institution")
    assertEquals(emptyMap(), result.valuesDroppedUnderNotApplicable, "and not confused with the A-flag drop")
    assertEquals(
      7,
      query("SELECT count(*) FROM college_sfa WHERE variable = 'upgrnta' AND value IS NULL") { it.getInt(1) }.single(),
      "the cell is still staged with no value: the flag the publisher wrote is not overwritten",
    )
  }

  @Test
  fun `a clean load counts no unreadable value`() {
    assertEquals(emptyMap(), stage().valuesUnreadable, "the verbatim fixture parses whole")
  }

  @Test
  fun `a value under a not-applicable flag cannot be stored, so it is counted`() {
    // The mirror of the shape the canonical fill tallies. NPGRN2 is flagged
    // `A` at every public in the fixture, so a number written into that column
    // is a value the DDL forbids storing -- and a published number must not
    // leave the system silently in EITHER direction.
    val result = stage(sources(withCell("NPGRN2", "1234")))
    assertTrue(
      (result.valuesDroppedUnderNotApplicable["npgrn2"] ?: 0) > 0,
      "the drop is counted by variable: ${result.valuesDroppedUnderNotApplicable}",
    )
    assertEquals(
      0,
      query("SELECT count(*) FROM college_sfa WHERE variable = 'npgrn2' AND publisher_flag = 'A' AND value IS NOT NULL") {
        it.getInt(1)
      }.single(),
      "and no such row is staged",
    )
  }

  @Test
  fun `a missing column is fatal before any write`() {
    val truncated = withoutColumn("XNPIST2")
    assertFailsWith<MissingSourceColumnsException> {
      runBlocking { loader.assertHeaders(sources(truncated)) }
    }
    // The computed X twin is asserted exactly like the variable itself: a
    // publisher rename must be a startup fatal, not a column of blanks. The
    // twin is named in the PUBLISHED case; `npist2` (lower) is the name the
    // staged row carries, and the two must not be confused.
    assertTrue(SfaVariables.REQUIRED_COLUMNS.contains("XNPIST2"))
    assertEquals("XNPIST2", SfaVariables.flagColumn("npist2"))
    assertEquals("NPIST2", SfaVariables.sourceColumn("npist2"))
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The fixture ([from], by default the verbatim one) with one column's cell rewritten in every data row. */
  private fun withCell(
    column: String,
    value: String,
    from: File = sfaCsv,
  ): File = rewrite(from) { header, cells -> cells.also { it[columnIndex(header, column)] = value } }

  /** The fixture with one column dropped entirely. */
  private fun withoutColumn(column: String): File =
    rewriteFile(sfaCsv) { lines ->
      val header = lines.first().split(",")
      val drop = columnIndex(header, column)
      lines.map { line -> line.split(",").filterIndexed { i, _ -> i != drop }.joinToString(",") }
    }

  /**
   * [column]'s position in the fixture header, REFUSING an absent name.
   * `indexOf` returning -1 is how an edit helper silently rewrites nothing (or
   * throws about an array bound) instead of saying which column it could not
   * find -- a test that edits no cell asserts nothing.
   */
  private fun columnIndex(
    header: List<String>,
    column: String,
  ): Int {
    val index = header.indexOf(column)
    assertTrue(index >= 0, "the fixture header has no column [$column]")
    return index
  }

  private fun rewrite(
    from: File = sfaCsv,
    edit: (List<String>, MutableList<String>) -> List<String>,
  ): File =
    rewriteFile(from) { lines ->
      val header = lines.first().split(",")
      listOf(lines.first()) + lines.drop(1).map { line -> edit(header, line.split(",").toMutableList()).joinToString(",") }
    }

  private fun rewriteFile(
    from: File = sfaCsv,
    edit: (List<String>) -> List<String>,
  ): File =
    File
      .createTempFile("ipeds-sfa2223-edited", ".csv")
      .apply {
        deleteOnExit()
        writeText(edit(from.readLines().filter { it.isNotBlank() }).joinToString("\n") + "\n")
      }

  private companion object {
    /** The START year of the fixture file's own aid year: SFA2223 is aid year 2022-23. */
    const val SFA_AID_YEAR_START = 2022
  }
}
