package ed.unicoach.college

import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SourceUrlStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The National Merit award corpus loader (RFC 243), on the scorecard test
 * scaffolding: colleges are seeded from the Scorecard institutions fixture so
 * the `unit_id` resolution and the NAME AGREEMENT run against real `colleges`
 * rows, and the per-test TRUNCATE of `colleges` cascades through this corpus's
 * foreign key.
 *
 * EVERY TEST RUNS THE LOADER, and every corpus it reads is written into a temp
 * directory per test rather than read from the committed files -- what is under
 * test is a set of REFUSALS, and each one needs a corpus that differs from the
 * good one by ONE CELL. The committed corpus has its own suite
 * ([NationalMeritAwardCommittedCorpusTest]); the only thing this file asserts
 * about it is that its header is one this loader accepts.
 *
 * THE ROWS ARE BUILT FROM NAMED CELLS, which is the one way this differs from
 * [AutomaticAwardSeedLoaderTest]'s raw CSV literals. Twenty-two columns, twelve
 * of them legitimately empty on some row, make a literal a run of commas a
 * reader has to count -- and a test that meant to blank `award_as_published`
 * and blanked `absence_as_published` still fails, for the wrong reason, and
 * proves the wrong refusal. [row] also REFUSES a column name this corpus does
 * not have, so a mistyped override cannot silently leave the good row intact
 * and pass.
 */
class NationalMeritAwardSeedLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = NationalMeritAwardSeedLoader(database)

  private fun seedColleges() =
    runBlocking {
      scorecardLoader.load(
        fixture("scorecard-institutions-fixture.csv"),
        fixture("scorecard-fields-empty-fixture.csv"),
      )
      Unit
    }

  // The two seeded schools this corpus is written about, by the names
  // `colleges` actually holds. Named here so a test that means to type the
  // WRONG name has to say so.
  private val coastalUnitId = 110100
  private val coastalName = "Coastal State University"
  private val harborUnitId = 220200
  private val harborName = "Harbor Private College"

  private val coastalUrl = "https://coastalstate.edu/national-merit"
  private val harborUrl = "https://harborprivate.edu/scholarships"

  /**
   * A url no award row cites, and the reason the sidecar is written WHOLE: the
   * committed corpus's answers are a record of what `bin/check-link` said, and
   * a loader that wrote only the cited ones would drop the answer the next
   * re-read is meant to compare against.
   */
  private val uncitedUrl = "https://www.nationalmerit.org/s/1758/start.aspx"

  /**
   * The paying row this suite varies: Coastal pays Finalists automatically for
   * Fall 2027 entry, states its own words for every claim it makes, and
   * requires first choice with NMSC by a dated deadline inside its own cycle.
   */
  private val payingCells: Map<String, String> =
    mapOf(
      "unit_id" to "$coastalUnitId",
      "college_name" to coastalName,
      "entry_year" to "2027",
      "residency_basis" to "in_state",
      "award_kind" to "automatic",
      "qualifying_standing" to "finalist",
      "award_name" to "Coastal Merit Package",
      "award_per_year_usd" to "20000",
      "award_total_usd" to "80000",
      "award_years" to "4",
      "award_as_published" to "Finalists receive \$20,000 a year, for four years.",
      "first_choice_required" to "true",
      "first_choice_deadline" to "2027-05-01",
      "first_choice_as_published" to "List Coastal State University first with NMSC before May 1, 2027.",
      "apply_deadline" to "2026-12-01",
      "apply_deadline_as_published" to "Apply for admission by December 1.",
      "publisher" to coastalName,
      "source_url" to coastalUrl,
      "checked_on" to "2026-09-25",
      "expires_on" to "2027-07-31",
    )

  /**
   * THE VERIFIED ABSENCE: we read Harbor's page on `checked_on` and it
   * publishes no National Merit award at all. It states the sentence that
   * proves the absence and NOTHING else -- no standing, no name, no money, no
   * deadline -- which is what four of this table's CHECKs say between them.
   */
  private val absenceCells: Map<String, String> =
    mapOf(
      "unit_id" to "$harborUnitId",
      "college_name" to harborName,
      "entry_year" to "2027",
      "residency_basis" to "not_applicable",
      "award_kind" to "none_published",
      "absence_as_published" to "Harbor does not offer a National Merit scholarship.",
      "publisher" to harborName,
      "source_url" to harborUrl,
      "checked_on" to "2026-09-25",
      "expires_on" to "2027-07-31",
    )

  /**
   * One row as its NAMED cells, rendered in `AWARD_COLUMNS` order and quoted
   * where a cell carries a comma or a quote.
   *
   * A COLUMN THIS CORPUS DOES NOT HAVE IS A TEST BUG, and it is refused here:
   * an override spelled `award_published` would otherwise leave the good row
   * intact, load cleanly, and fail the `assertFailsWith` for a reason that has
   * nothing to do with the refusal under test.
   */
  private fun row(cells: Map<String, String>): String {
    val unknown = cells.keys - NationalMeritAwardSeedLoader.AWARD_COLUMNS.toSet()
    check(unknown.isEmpty()) { "this suite named $unknown, which awards.csv has no column for" }
    return NationalMeritAwardSeedLoader.AWARD_COLUMNS.joinToString(",") { column ->
      val cell = cells[column].orEmpty()
      if (cell.contains(',') || cell.contains('"')) "\"${cell.replace("\"", "\"\"")}\"" else cell
    }
  }

  /** The paying row, with [overrides] applied cell by name. */
  private fun paying(vararg overrides: Pair<String, String>): String = row(payingCells + overrides)

  /** The verified absence, with [overrides] applied cell by name. */
  private fun absence(vararg overrides: Pair<String, String>): String = row(absenceCells + overrides)

  private fun goodAwards(): List<String> = listOf(paying(), absence())

  private fun goodLinkStatuses(): List<String> =
    listOf(
      "$coastalUrl,found,2026-09-25",
      "$harborUrl,found,2026-09-25",
      "$uncitedUrl,found,2026-09-25",
    )

  /** One corpus on disk: the awards file, and the sidecar beside it. */
  private fun writeCorpus(
    dir: File,
    awards: List<String> = goodAwards(),
    linkStatuses: List<String>? = goodLinkStatuses(),
    header: List<String> = NationalMeritAwardSeedLoader.AWARD_COLUMNS,
  ): File {
    val awardsCsv = File(dir, "awards.csv")
    awardsCsv.writeText((listOf(header.joinToString(",")) + awards).joinToString("\n") + "\n")
    // `null` means NO sidecar at all, which is a different state from an empty
    // one and has its own refusal.
    if (linkStatuses != null) {
      File(dir, LINK_STATUS_FILE)
        .writeText(
          (listOf(NationalMeritAwardSeedLoader.LINK_STATUS_COLUMNS.joinToString(",")) + linkStatuses)
            .joinToString("\n") + "\n",
        )
    }
    return awardsCsv
  }

  private fun load(awardsCsv: File): NationalMeritAwardSeedLoader.LoadResult = runBlocking { loader.load(awardsCsv) }

  private fun refusalOf(
    dir: File,
    awards: List<String> = goodAwards(),
    linkStatuses: List<String>? = goodLinkStatuses(),
    header: List<String> = NationalMeritAwardSeedLoader.AWARD_COLUMNS,
  ): NationalMeritAwardSeedLoader.FormatException = assertFailsWith { load(writeCorpus(dir, awards, linkStatuses, header)) }

  /** Every stored row as `unit_id -> (award_kind, qualifying_standing)`, the two cells that decide what is said. */
  private fun storedRows(): List<Triple<Int, String, String?>> =
    query(
      "SELECT c.ipeds_unit_id, a.award_kind, a.qualifying_standing " +
        "FROM college_national_merit_awards a JOIN colleges c ON c.id = a.college_id " +
        "ORDER BY c.ipeds_unit_id, a.qualifying_standing",
    ) { rs -> Triple(rs.getInt(1), rs.getString(2), rs.getString(3)) }

  /**
   * The repo's committed corpus directory, resolved by walking up from the
   * test's working directory (the module dir under Gradle) rather than assuming
   * a fixed depth -- `CdsSeedLoaderTest`'s own way of finding its seed.
   */
  private val committedCorpusDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/national-merit-awards") }
      .first { it.isDirectory }

  @Test
  fun `the committed corpus is a corpus this loader can read`() {
    // It runs the loader's OWN assertion over the real files rather than
    // comparing two lists of strings: what must hold is that `load` will accept
    // this corpus, and the header is read here by the same parser `load` uses --
    // BOM stripping, trimming and all. The rows are
    // [NationalMeritAwardCommittedCorpusTest]'s subject, not this file's.
    val awards = File(committedCorpusDir, "awards.csv")
    runBlocking { loader.assertHeaders(source(awards)) }
  }

  @Test
  fun `the corpus loads its documents and its award rows`(
    @TempDir dir: File,
  ) {
    seedColleges()

    val result = load(writeCorpus(dir))

    assertEquals(3, result.documents, "every sidecar url is written, cited or not")
    assertEquals(2, result.awards.upserted)
    assertEquals(2, result.awards.rows)
    assertEquals(0, result.awardsPruned, "a first load of a corpus has nothing to prune")
    assertEquals(
      listOf(
        Triple(coastalUnitId, "automatic", "finalist"),
        Triple(harborUnitId, "none_published", null),
      ),
      storedRows(),
    )
  }

  @Test
  fun `a url no award row cites is still written, with the answer committed beside the corpus`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    val document =
      query(
        "SELECT url_status, url_checked_on FROM external_documents WHERE url = '$uncitedUrl'",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertEquals(listOf("found" to "2026-09-25"), document)
  }

  @Test
  fun `an unknown WITH a date is stored as it was committed, not refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // "We asked and got no usable answer" is a real state, and TWO urls in the
    // committed corpus are in it: Oklahoma State's host answers 403 to
    // bin/check-link on HEAD and GET, with our User-Agent and with curl's, and
    // 200 to a plain browser fetch. `unknown` with the day we asked is the true
    // reading, and it KEEPS the link -- so refusing it here would drop the
    // school with the best award in the corpus because its host dislikes our
    // client.
    val asked = goodLinkStatuses().map { it.replace("$harborUrl,found,", "$harborUrl,unknown,") }

    load(writeCorpus(dir, linkStatuses = asked))

    assertEquals(
      listOf("unknown" to "2026-09-25"),
      query("SELECT url_status, url_checked_on FROM external_documents WHERE url = '$harborUrl'") { rs ->
        rs.getString(1) to rs.getString(2)
      },
    )
  }

  @Test
  fun `a renamed column is refused, naming the file and the column`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val renamed = NationalMeritAwardSeedLoader.AWARD_COLUMNS.map { if (it == "award_kind") "kind" else it }

    val thrown = refusalOf(dir, header = renamed)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.HeaderMismatch>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(listOf("award_kind"), defect.missing)
    assertEquals(listOf("kind"), defect.unexpected)
    assertEquals(emptyList(), storedRows(), "a header refusal writes nothing at all")
  }

  @Test
  fun `a row with the wrong number of cells is refused by file and line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A cell dropped by a hand edit, and a cell added by one. Both are the same
    // fault -- this row is not this corpus's shape -- and both must name the
    // LINE, because the message's whole job is sending somebody to it.
    val short = refusalOf(dir, awards = listOf(paying().substringBeforeLast(","), absence()))
    val shortDefect = assertIs<NationalMeritAwardSeedLoader.Defect.RowArity>(short.defect)
    assertContains(shortDefect.file, "awards.csv")
    assertEquals(1, shortDefect.line)
    assertEquals(NationalMeritAwardSeedLoader.AWARD_COLUMNS.size - 1, shortDefect.cells)
    assertEquals(NationalMeritAwardSeedLoader.AWARD_COLUMNS.size, shortDefect.expectedCells)

    val long = refusalOf(dir, awards = listOf(paying(), absence() + ",spare"))
    val longDefect = assertIs<NationalMeritAwardSeedLoader.Defect.RowArity>(long.defect)
    assertEquals(2, longDefect.line)
    assertEquals(NationalMeritAwardSeedLoader.AWARD_COLUMNS.size + 1, longDefect.cells)
  }

  @Test
  fun `a unit_id that is not a whole number is refused, naming the cell it was typed in`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A letter O typed for a zero. `unit_id` is the cell every other check in
    // this corpus hangs off -- the college it resolves, the name it is compared
    // against -- so a cell that is not a number at all is named as itself
    // rather than surfacing as "no college carries this unit id".
    val typo = refusalOf(dir, awards = listOf(paying("unit_id" to "11O100"), absence()))
    val typoDefect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(typo.defect)
    assertEquals(SeedCellFault.NOT_AN_INTEGER, typoDefect.cell.fault)
    assertContains(typoDefect.file, "awards.csv")
    assertEquals(1, typoDefect.cell.line)
    assertEquals("unit_id", typoDefect.cell.column)
    assertEquals("11O100", typoDefect.cell.value)

    // And a figure typed the way a page prints it. The refusal names THAT
    // column, not the first integer cell of the row: a message naming the wrong
    // cell sends the curator to a line they already read correctly.
    val grouped = refusalOf(dir, awards = listOf(paying("award_per_year_usd" to "20,000"), absence()))
    val groupedDefect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(grouped.defect)
    assertEquals(SeedCellFault.NOT_AN_INTEGER, groupedDefect.cell.fault)
    assertEquals("award_per_year_usd", groupedDefect.cell.column)
    assertEquals("20,000", groupedDefect.cell.value)
  }

  @Test
  fun `a day that is not an ISO date is refused, naming the cell and what was typed`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The US spelling of the day the page was read. It parses as nothing, and
    // the alternative to refusing it is a row whose evidence is dated by
    // whatever the parser guessed.
    val thrown = refusalOf(dir, awards = listOf(paying("checked_on" to "09/25/2026"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(thrown.defect)
    assertEquals(SeedCellFault.NOT_A_DATE, defect.cell.fault)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.cell.line)
    assertEquals("checked_on", defect.cell.column)
    assertEquals("09/25/2026", defect.cell.value)
    // The parser's own reason rides underneath, because [2026-13-01] and
    // [2026-02-30] read identically without it.
    assertContains(thrown.cause?.message.orEmpty(), "09/25/2026")
  }

  @Test
  fun `a required cell left empty is refused, naming the column`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `publisher` is who we are quoting. An empty cell is not an absence this
    // corpus spells -- the optional cells are the ones allowed to be blank --
    // so it is refused by name rather than stored as a quote from nobody.
    val thrown = refusalOf(dir, awards = listOf(paying("publisher" to ""), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(thrown.defect)
    assertEquals(SeedCellFault.EMPTY_REQUIRED, defect.cell.fault)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.cell.line)
    assertEquals("publisher", defect.cell.column)
    assertEquals(emptyList(), storedRows(), "a refused corpus writes no row at all")
  }

  @Test
  fun `an entry_year outside the years the store admits is refused, naming the year`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A year typed from the page's copyright line rather than from the entering
    // class. `AcademicYear` carries the range its SQL domain states, so building
    // one from this cell would otherwise throw with no file, line or column.
    val thrown = refusalOf(dir, awards = listOf(paying("entry_year" to "1987"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(thrown.defect)
    assertEquals(SeedCellFault.NOT_AN_ACADEMIC_YEAR, defect.cell.fault)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.cell.line)
    assertEquals("entry_year", defect.cell.column)
    assertEquals(1987, defect.cell.year, "the NUMBER rides, already parsed, not the raw cell restringified")
  }

  @Test
  fun `a unit_id no college carries is refused, naming the row`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // 999999 is in no `colleges` row. The CDS loader SKIPS such a row because
    // its corpus is generated from a federal file; a hand-typed row has no
    // acceptable silent drop, and a dropped row reads downstream as "we have
    // not read this school" about a school somebody did read.
    val thrown = refusalOf(dir, awards = listOf(paying("unit_id" to "999999"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.UnknownCollege>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.line)
    assertEquals("unit_id", defect.column)
    assertEquals(999999, defect.ipedsUnitId)
    assertEquals(coastalName, defect.typedName)
  }

  @Test
  fun `a college_name that disagrees with the stored name is refused, naming both`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // THE MEASURED FAILURE MODE, with a REAL wrong pair: Harbor Private College
    // is a school in this table, typed under Coastal State University's unit
    // id. A fuzzy match of RFC 239's curated names returned eight of exactly
    // this shape -- Arizona State matched Arizona Christian, Michigan-Ann Arbor
    // matched Michigan-Flint -- and the row that results is another school's
    // money under this school's name.
    val thrown = refusalOf(dir, awards = listOf(paying("college_name" to harborName), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.CollegeNameDisagreement>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.line)
    assertEquals("college_name", defect.column)
    assertEquals(coastalUnitId, defect.ipedsUnitId)
    assertEquals(harborName, defect.typedName)
    assertEquals(coastalName, defect.storedName)
    // Both names in the message, because which one is wrong is the operator's
    // call and a message naming one cannot be acted on.
    assertContains(thrown.message.orEmpty(), harborName)
    assertContains(thrown.message.orEmpty(), coastalName)
  }

  @Test
  fun `a standing on a none_published row is refused, naming the cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A school we READ and verified publishes no National Merit award cannot
    // pay a standing: there is no award for the standing to qualify for.
    val thrown = refusalOf(dir, awards = listOf(paying(), absence("qualifying_standing" to "finalist")))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.StandingAndKindDisagree>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(2, defect.line)
    assertEquals("qualifying_standing", defect.column)
    assertEquals(NationalMeritAwardKind.NONE_PUBLISHED, defect.awardKind)
    assertEquals(QualifyingStanding.FINALIST, defect.qualifyingStanding)
  }

  @Test
  fun `a paying row that names no standing is refused, naming the same cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The other half of the same equality, and it is not the same mistake: a
    // school that pays must say WHICH standing it pays, because Maine pays
    // Semifinalists and Finalists at different rates and most schools pay one
    // and not the other.
    val thrown = refusalOf(dir, awards = listOf(paying("qualifying_standing" to ""), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.StandingAndKindDisagree>(thrown.defect)
    assertEquals(1, defect.line)
    assertEquals("qualifying_standing", defect.column)
    assertEquals(NationalMeritAwardKind.AUTOMATIC, defect.awardKind)
    assertEquals(null, defect.qualifyingStanding)
  }

  @Test
  fun `a paying row with no publisher's words is refused, naming the cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // THE RULE THE WHOLE CORPUS RESTS ON: the WORDS are required and the
    // figures are optional, never the other way round. Half the seeded schools
    // publish no number at all -- "full cost of tuition and required fees, for
    // four years" -- so a row with a figure and no sentence is a price nobody
    // can check us on, and RFC 239's review found the opposite arrangement
    // ranking a top award priced in words as $0.
    val thrown = refusalOf(dir, awards = listOf(paying("award_as_published" to ""), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.MissingWords>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.line)
    assertEquals("award_as_published", defect.column)
    assertEquals(NationalMeritAwardKind.AUTOMATIC, defect.awardKind)
    assertEquals(emptyList(), storedRows(), "a refused corpus writes no row at all")
  }

  @Test
  fun `an absence row with no absence words is refused, naming the cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A verified absence is an absence WITH a citation -- "we read this page on
    // this day and it publishes nothing" -- which is a different answer from
    // "we have not looked". Without the sentence it is only our silence, and
    // silence is not a citation.
    val thrown = refusalOf(dir, awards = listOf(paying(), absence("absence_as_published" to "")))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.MissingWords>(thrown.defect)
    assertEquals(2, defect.line)
    assertEquals("absence_as_published", defect.column)
    assertEquals(NationalMeritAwardKind.NONE_PUBLISHED, defect.awardKind)
  }

  @Test
  fun `each kind's words on the other kind's row are refused, naming the borrowed cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The two sentence columns are separate so neither kind can borrow the
    // other's. A paying row rendered from an absence quote tells a family the
    // school pays nothing, in the school's own voice.
    val onPaying =
      refusalOf(
        dir,
        awards = listOf(paying("absence_as_published" to "We do not offer this."), absence()),
      )
    val payingDefect = assertIs<NationalMeritAwardSeedLoader.Defect.BorrowedWords>(onPaying.defect)
    assertEquals(1, payingDefect.line)
    assertEquals("absence_as_published", payingDefect.column)
    assertEquals(NationalMeritAwardKind.AUTOMATIC, payingDefect.awardKind)

    val onAbsence =
      refusalOf(dir, awards = listOf(paying(), absence("award_as_published" to "Finalists receive tuition.")))
    val absenceDefect = assertIs<NationalMeritAwardSeedLoader.Defect.BorrowedWords>(onAbsence.defect)
    assertEquals(2, absenceDefect.line)
    assertEquals("award_as_published", absenceDefect.column)
    assertEquals(NationalMeritAwardKind.NONE_PUBLISHED, absenceDefect.awardKind)
  }

  @Test
  fun `money on a none_published row is refused, naming every cell that states one`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A school we verified publishes NOTHING has no award for a figure to be
    // the value of. Both cells are named, because a curator who crossed two
    // columns fixes both and a message naming one sends them back to the same
    // row twice.
    val thrown =
      refusalOf(
        dir,
        awards =
          listOf(
            paying(),
            absence("award_per_year_usd" to "20000", "award_name" to "Harbor Merit Package"),
          ),
      )

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.NonePublishedStatesAnAward>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(2, defect.line)
    assertEquals(listOf("award_name", "award_per_year_usd"), defect.columns)
    assertContains(thrown.message.orEmpty(), "award_per_year_usd")
  }

  @Test
  fun `a deadline outside its own cycle is refused, naming the cell and the window`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // THE MISTAKE A CURATOR ACTUALLY MAKES. Alabama publishes "before May 1,
    // 2027" and is the ONE row in the committed corpus that dates its
    // first-choice deadline at all; typed as 2026-05-01 it is a deadline that
    // passed fourteen months before this student applies, and nothing
    // downstream could tell it from one that had not.
    val thrown = refusalOf(dir, awards = listOf(paying("first_choice_deadline" to "2026-05-01"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.DeadlineOutsideCycle>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.line)
    assertEquals("first_choice_deadline", defect.column)
    assertEquals(LocalDate.of(2026, 5, 1), defect.deadline)
    assertEquals(LocalDate.of(2026, 7, 1), defect.cycle.start)
    assertEquals(LocalDate.of(2027, 9, 30), defect.cycle.endInclusive)

    // The apply deadline is bound by the SAME window, and the refusal names ITS
    // cell: a rule stated twice in one message names nothing.
    val applied = refusalOf(dir, awards = listOf(paying("apply_deadline" to "2027-12-01"), absence()))
    val appliedDefect = assertIs<NationalMeritAwardSeedLoader.Defect.DeadlineOutsideCycle>(applied.defect)
    assertEquals("apply_deadline", appliedDefect.column)
  }

  @Test
  fun `a first-choice deadline on a row that requires no first choice is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // Naming a college first choice with NMSC is an act with consequences
    // elsewhere, so the date and the requirement are one fact. A date left
    // behind after the requirement was corrected is a deadline for nothing.
    val thrown =
      refusalOf(
        dir,
        awards =
          listOf(
            paying("first_choice_required" to "false", "first_choice_as_published" to "We do not require first choice."),
            absence(),
          ),
      )

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.FirstChoiceDeadlineWithoutRequirement>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.line)
    assertEquals("first_choice_deadline", defect.column)
    assertEquals(LocalDate.of(2027, 5, 1), defect.deadline)
    assertEquals(false, defect.required)
  }

  @Test
  fun `a first-choice requirement with no words, and words with no requirement, are both refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A requirement we state without the school's sentence is our claim rather
    // than theirs, and a sentence with no coded rule is a quote nothing reads.
    val noWords =
      refusalOf(dir, awards = listOf(paying("first_choice_as_published" to "", "first_choice_deadline" to ""), absence()))
    val noWordsDefect = assertIs<NationalMeritAwardSeedLoader.Defect.FirstChoicePairBroken>(noWords.defect)
    assertEquals(1, noWordsDefect.line)
    assertEquals("first_choice_required", noWordsDefect.requiredColumn)
    assertEquals("first_choice_as_published", noWordsDefect.wordsColumn)
    assertEquals(true, noWordsDefect.required)
    assertEquals(null, noWordsDefect.asPublished)

    val noRule =
      refusalOf(dir, awards = listOf(paying("first_choice_required" to "", "first_choice_deadline" to ""), absence()))
    val noRuleDefect = assertIs<NationalMeritAwardSeedLoader.Defect.FirstChoicePairBroken>(noRule.defect)
    assertEquals(null, noRuleDefect.required)
    assertContains(noRuleDefect.asPublished.orEmpty(), "List Coastal State University first")
  }

  @Test
  fun `a stored apply deadline with no sentence beside it is refused, naming the date`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // ONE WAY, not an IFF, and the table says so too: a school may publish the
    // sentence with no date we could parse -- twenty rows of the committed
    // corpus do -- but a stored date with no sentence is a date nobody can
    // check us on.
    val thrown = refusalOf(dir, awards = listOf(paying("apply_deadline_as_published" to ""), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.DeadlineWithoutWords>(thrown.defect)
    assertEquals(1, defect.line)
    assertEquals("apply_deadline", defect.column)
    assertEquals(LocalDate.of(2026, 12, 1), defect.deadline)
    assertEquals("apply_deadline_as_published", defect.wordsColumn)
  }

  @Test
  fun `a cited url with no committed link answer is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // With RFC 220's strict `external_documents` the citing write would state
    // evidence nobody gathered: `citationIds` records the url as FOUND on the
    // reading day, by a loader that asked nobody. The corpus commits its rows
    // and its link answers together, so a url in one and not the other is a
    // corpus edited by halves.
    val withoutCoastal = goodLinkStatuses().filterNot { it.startsWith("$coastalUrl,") }

    val thrown = refusalOf(dir, linkStatuses = withoutCoastal)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.UncheckedUrl>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.line)
    assertEquals("source_url", defect.column)
    assertEquals(coastalUrl, defect.url)
    assertContains(defect.statusFile, LINK_STATUS_FILE)
    assertEquals(emptyList(), storedRows(), "a refused corpus writes no row at all")
  }

  @Test
  fun `an archive_url that repeats source_url is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A mirror is a SECOND address, read because the publisher's own page opens
    // for no client we have. One address cited twice states one piece of
    // evidence as two.
    val thrown = refusalOf(dir, awards = listOf(paying("archive_url" to coastalUrl), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.ArchiveRepeatsSource>(thrown.defect)
    assertEquals(1, defect.line)
    assertEquals("archive_url", defect.column)
    assertEquals(coastalUrl, defect.url)
  }

  @Test
  fun `an empty corpus is refused rather than emptying the table`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // An empty file would read as "drop every school we publish". It is a file
    // somebody truncated, so it is named and nothing is written -- the two rows
    // from the first load are still there afterwards.
    val thrown = refusalOf(dir, awards = emptyList())

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.EmptyAwardCorpus>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(2, storedRows().size, "a truncated corpus unpublishes nobody")
  }

  @Test
  fun `an absent link-status file is refused rather than read as we never asked`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The CDS loader tolerates an absent sidecar -- its corpus is swept by a
    // script and may legitimately run before the first sweep. This corpus's
    // answers are committed beside the rows, so absence is a corpus missing a
    // file, not a corpus nobody has checked yet.
    val thrown = refusalOf(dir, linkStatuses = null)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.MissingLinkStatusFile>(thrown.defect)
    assertContains(defect.file, LINK_STATUS_FILE)
  }

  @Test
  fun `a link answer with no day beside it is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A hand-committed file does not get to say "we asked and cannot say when":
    // the column is NOT NULL, so the alternative is this loader choosing a day.
    val dateless = goodLinkStatuses().map { it.replace("$harborUrl,found,2026-09-25", "$harborUrl,found,") }

    val thrown = refusalOf(dir, linkStatuses = dateless)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(thrown.defect)
    val fault = assertIs<LinkStatusFault.MissingDate>(defect.fault)
    assertContains(defect.file, LINK_STATUS_FILE)
    assertEquals(2, defect.line)
    assertEquals(harborUrl, fault.url)
  }

  @Test
  fun `a sidecar url that is not a web page is refused, by file and line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `external_documents` refuses this string by VALUE, with no idea which row
    // produced it -- and this corpus's whole discipline is telling the person
    // who typed it which row to fix.
    val notAPage = goodLinkStatuses() + "coastalstate.edu/national-merit,found,2026-09-25"

    val thrown = refusalOf(dir, linkStatuses = notAPage)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(thrown.defect)
    val fault = assertIs<LinkStatusFault.UrlNotAWebPage>(defect.fault)
    assertContains(defect.file, LINK_STATUS_FILE)
    assertEquals(4, defect.line)
    assertEquals("coastalstate.edu/national-merit", fault.url)
  }

  @Test
  fun `a sidecar row with the wrong number of cells is refused by file and line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A cell dropped by a hand edit, and a cell added by one. Commons CSV would
    // otherwise raise an unlocated IllegalArgumentException about a header name,
    // which is outside this loader's "every refusal names the row" contract.
    val dropped = goodLinkStatuses().map { it.replace("$harborUrl,found,2026-09-25", "$harborUrl,found") }
    val short = refusalOf(dir, linkStatuses = dropped)
    val shortDefect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(short.defect)
    val shortFault = assertIs<LinkStatusFault.RowArity>(shortDefect.fault)
    assertContains(shortDefect.file, LINK_STATUS_FILE)
    assertEquals(2, shortDefect.line)
    assertEquals(2, shortFault.cells)
    assertEquals(NationalMeritAwardSeedLoader.LINK_STATUS_COLUMNS.size, shortFault.expectedCells)

    val long = refusalOf(dir, linkStatuses = goodLinkStatuses() + "$uncitedUrl,found,2026-09-25,spare")
    val longDefect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(long.defect)
    assertEquals(4, longDefect.line)
    assertEquals(4, assertIs<LinkStatusFault.RowArity>(longDefect.fault).cells)
  }

  @Test
  fun `a sidecar row with no url is refused by file and line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A verdict about nothing: the row states that something was found, and
    // does not say what. There is no url for the message to name, so the LINE
    // is the whole address of the fault.
    val thrown = refusalOf(dir, linkStatuses = goodLinkStatuses() + ",found,2026-09-25")

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(thrown.defect)
    assertEquals(LinkStatusFault.EmptyUrl, defect.fault)
    assertContains(defect.file, LINK_STATUS_FILE)
    assertEquals(4, defect.line)
  }

  @Test
  fun `a link answer whose status is not a verdict we hold is refused, naming the url and what was allowed`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `probably` is not one of the three answers `external_documents` stores,
    // and the refusal names all three: a curator editing this file by hand is
    // the only reader of the message, and a closed set they cannot see is one
    // they guess at.
    val badStatus = goodLinkStatuses().map { it.replace("$harborUrl,found,", "$harborUrl,probably,") }

    val thrown = refusalOf(dir, linkStatuses = badStatus)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(thrown.defect)
    val fault = assertIs<LinkStatusFault.UnknownStatus>(defect.fault)
    assertContains(defect.file, LINK_STATUS_FILE)
    assertEquals(2, defect.line)
    assertEquals(harborUrl, fault.url)
    assertEquals("probably", fault.value)
    assertEquals(SourceUrlStatus.entries.map { it.value }, fault.allowed)
  }

  @Test
  fun `a sidecar day that is not an ISO date is refused, naming the url and the cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The day the verdict was reached, typed the way the curator's calendar
    // prints it. It is the cell that tells "we asked and got no usable answer"
    // from "we never asked", so a day nothing can parse is refused by row.
    val slashed = goodLinkStatuses().map { it.replace("$harborUrl,found,2026-09-25", "$harborUrl,found,25/09/2026") }

    val thrown = refusalOf(dir, linkStatuses = slashed)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(thrown.defect)
    val fault = assertIs<LinkStatusFault.NotADate>(defect.fault)
    assertContains(defect.file, LINK_STATUS_FILE)
    assertEquals(2, defect.line)
    assertEquals(harborUrl, fault.url)
    assertEquals("25/09/2026", fault.value)
    assertContains(thrown.cause?.message.orEmpty(), "25/09/2026")
  }

  @Test
  fun `two sidecar rows answering one url differently are refused, naming both lines and both answers`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // Resolved by row order it would be the LATER answer that decided whether a
    // family is shown this school's page -- silently, and only because of where
    // the second line happens to sit in the file.
    val conflicting = goodLinkStatuses() + "$harborUrl,unknown,2026-09-25"

    val thrown = refusalOf(dir, linkStatuses = conflicting)

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Sidecar>(thrown.defect)
    val fault = assertIs<LinkStatusFault.ConflictingStatus>(defect.fault)
    assertContains(defect.file, LINK_STATUS_FILE)
    assertEquals(2, fault.firstLine, "the operator is sent to BOTH rows, not only the second")
    assertEquals(4, defect.line)
    assertEquals(harborUrl, fault.url)
    assertEquals(RecordedLinkStatus(SourceUrlStatus.FOUND, LocalDate.of(2026, 9, 25)), fault.first)
    assertEquals(RecordedLinkStatus(SourceUrlStatus.UNKNOWN, LocalDate.of(2026, 9, 25)), fault.second)
  }

  @Test
  fun `a residency basis outside the three a freshman award is published for is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `in_district` is a legal member of the shared vocabulary and is refused
    // here, exactly as the table's own CHECK refuses it: a community-college
    // tuition tier is not something a university publishes a freshman award
    // against.
    val thrown = refusalOf(dir, awards = listOf(paying("residency_basis" to "in_district"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(thrown.defect)
    assertEquals(SeedCellFault.UNKNOWN_CODE, defect.cell.fault)
    assertEquals(1, defect.cell.line)
    assertEquals("residency_basis", defect.cell.column)
    assertEquals("in_district", defect.cell.value)
    assertEquals(
      NationalMeritAwardSeedLoader.AWARD_RESIDENCY_BASES.map { it.value },
      defect.cell.allowed,
      "the refusal names every basis that would have been accepted",
    )
  }

  @Test
  fun `a first_choice_required that is neither true nor false is refused, naming both spellings`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `yes` is a thing a curator types, and `String.toBoolean()` reads it as
    // FALSE -- which would silently record a school as NOT requiring first
    // choice, the one cell that decides whether a student must commit to it.
    val thrown = refusalOf(dir, awards = listOf(paying("first_choice_required" to "yes"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(thrown.defect)
    assertEquals(SeedCellFault.UNKNOWN_CODE, defect.cell.fault)
    assertEquals(1, defect.cell.line)
    assertEquals("first_choice_required", defect.cell.column)
    assertEquals("yes", defect.cell.value)
    assertEquals(listOf("true", "false"), defect.cell.allowed)
  }

  @Test
  fun `a figure outside the shape its column stores is refused by cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A typed ZERO is not a published price: this corpus prices half its
    // schools in WORDS, and the EMPTY cell is how it says so.
    val zero = refusalOf(dir, awards = listOf(paying("award_per_year_usd" to "0"), absence()))
    val zeroDefect = assertIs<NationalMeritAwardSeedLoader.Defect.MoneyOutsidePublishedShape>(zero.defect)
    assertEquals(1, zeroDefect.line)
    assertEquals("award_per_year_usd", zeroDefect.column)
    assertEquals(0, zeroDefect.value)

    // And a package length no school publishes: `award_years` is one to six.
    val years = refusalOf(dir, awards = listOf(paying("award_years" to "40"), absence()))
    val yearsDefect = assertIs<NationalMeritAwardSeedLoader.Defect.MoneyOutsidePublishedShape>(years.defect)
    assertEquals("award_years", yearsDefect.column)
    assertEquals(40, yearsDefect.value)
    assertEquals(NationalMeritAwardSeedLoader.AWARD_YEARS_RANGE, yearsDefect.allowed)
  }

  @Test
  fun `an expiry on or before the day the page was read is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `expires_on` is the day we stop believing the page, never a deadline the
    // row states -- so a row that expired before it was read is a column
    // somebody filled from the wrong sentence.
    val thrown = refusalOf(dir, awards = listOf(paying("expires_on" to "2026-09-25"), absence()))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.ExpiryNotAfterCheck>(thrown.defect)
    assertEquals(1, defect.line)
    assertEquals("expires_on", defect.column)
    assertEquals(LocalDate.of(2026, 9, 25), defect.checkedOn)
    assertEquals(LocalDate.of(2026, 9, 25), defect.expiresOn)
  }

  @Test
  fun `two rows on one natural key are refused rather than resolved by row order`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // Same school, same entry year, same residency, same standing, different
    // money. The natural-key upsert RESOLVES this last-wins and counts it as a
    // change; the corpus would carry two prices for one published award and
    // report a clean load.
    val thrown =
      refusalOf(dir, awards = listOf(paying(), absence(), paying("award_per_year_usd" to "30000")))

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.DuplicateAwardKey>(thrown.defect)
    assertEquals(3, defect.line)
    assertEquals(1, defect.firstLine, "the operator is sent to BOTH rows, not only the second")
    assertEquals(coastalUnitId, defect.ipedsUnitId)
    assertEquals(ResidencyBasis.IN_STATE, defect.residencyBasis)
    assertEquals(QualifyingStanding.FINALIST, defect.qualifyingStanding)
  }

  @Test
  fun `one school's two standings are two rows, not a duplicate`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // THE COLUMN THAT IS IN THE KEY, and Maine is why: it publishes a
    // Semifinalist row AND a Finalist row. A key without the standing would
    // make the second silently overwrite the first.
    val semifinalist =
      paying(
        "qualifying_standing" to "semifinalist",
        "award_kind" to "competitive",
        "award_name" to "Coastal Scholars",
        "award_per_year_usd" to "",
        "award_total_usd" to "",
        "award_years" to "",
        "award_as_published" to "Semifinalists are considered for the Coastal Scholars award.",
      )

    val result = load(writeCorpus(dir, awards = goodAwards() + semifinalist))

    assertEquals(3, result.awards.upserted)
    assertEquals(
      listOf(
        Triple(coastalUnitId, "automatic", "finalist"),
        Triple(coastalUnitId, "competitive", "semifinalist"),
        Triple(harborUnitId, "none_published", null),
      ),
      storedRows(),
    )
  }

  @Test
  fun `a refused row leaves NOTHING behind, not even the rows that read cleanly`(
    @TempDir dir: File,
  ) {
    seedColleges()

    refusalOf(dir, awards = goodAwards() + paying("unit_id" to "999999", "residency_basis" to "out_of_state"))

    // One transaction: the two good rows were written and rolled back. A
    // half-applied corpus is a school whose answer stops mid-file.
    assertEquals(emptyList(), storedRows())
  }

  @Test
  fun `a second run of the same corpus writes nothing and changes nothing`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val awardsCsv = writeCorpus(dir)
    load(awardsCsv)
    val before =
      query("SELECT id, updated_at FROM college_national_merit_awards ORDER BY id") { rs ->
        rs.getString(1) to rs.getString(2)
      }

    val second = load(awardsCsv)

    assertEquals(0, second.awards.upserted)
    assertEquals(0, second.awards.changed)
    assertEquals(2, second.awards.unchanged, "an unedited row is UNCHANGED, not CHANGED")
    assertEquals(0, second.awardsPruned, "a re-load of the same corpus drops no school")
    // The IDS AND THE TIMESTAMPS, not just the counts: a loader that deleted
    // and re-inserted every row would report the same three numbers.
    assertEquals(
      before,
      query("SELECT id, updated_at FROM college_national_merit_awards ORDER BY id") { rs ->
        rs.getString(1) to rs.getString(2)
      },
    )
  }

  @Test
  fun `a re-read that changes a figure changes the row and reports it`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    val raised = listOf(paying("award_per_year_usd" to "22000"), absence())
    val result = load(writeCorpus(dir, awards = raised))

    assertEquals(1, result.awards.changed)
    assertEquals(1, result.awards.unchanged)
    assertEquals(0, result.awardsPruned, "a changed figure is the SAME row: its natural key did not move")
    assertEquals(
      listOf(22000),
      query("SELECT award_per_year_usd FROM college_national_merit_awards WHERE award_per_year_usd IS NOT NULL") { rs ->
        rs.getInt(1)
      },
    )
  }

  @Test
  fun `a re-read that drops a school drops its row`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // The corpus no longer names Harbor -- the school was dropped, or its
    // unit_id was corrected and this row is the old one. Either way the row it
    // left behind is a school we would go on answering for from a line nobody
    // will look at again.
    val result = load(writeCorpus(dir, awards = listOf(paying())))

    assertEquals(1, result.awardsPruned)
    assertEquals(listOf(Triple(coastalUnitId, "automatic", "finalist")), storedRows())
  }

  @Test
  fun `a verified absence stores its citation and no claim`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    val row =
      query(
        "SELECT a.absence_as_published, a.award_as_published, a.award_per_year_usd, d.url " +
          "FROM college_national_merit_awards a JOIN colleges c ON c.id = a.college_id " +
          "JOIN external_documents d ON d.id = a.source_document_id WHERE c.ipeds_unit_id = $harborUnitId",
      ) { rs -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
    assertEquals(1, row.size)
    assertContains(row[0][0].orEmpty(), "does not offer a National Merit scholarship")
    assertEquals(null, row[0][1], "an absence row carries no award words")
    assertEquals(null, row[0][2], "an absence row carries no money")
    assertEquals(harborUrl, row[0][3], "the absence is CITED: we read this page, it is not our silence")
  }

  // ---------------------------------------------------------------------------
  // The refusal an operator actually reads: this corpus inside the ingest run
  // ---------------------------------------------------------------------------

  /** One full ingest run over the Scorecard fixtures with this corpus supplied, as `bin/ingest-colleges` produces it. */
  private fun ingest(awardsCsv: File): CollegeScorecardLoader.IngestReport =
    runBlocking {
      scorecardLoader.ingest(
        institution = source(fixture("scorecard-institutions-fixture.csv")),
        fields = source(fixture("scorecard-fields-empty-fixture.csv")),
        aliasesFile = source(fixture("college-aliases-fixture.json")),
        nationalMeritAwards = source(awardsCsv),
      )
    }

  /**
   * A HEADER REFUSAL REACHES THE INGEST'S ERROR FUNNEL AS THIS LOADER'S OWN
   * `FormatException`, which is the type `IngestApplication`'s arm for this
   * corpus catches and the arm's whole reason to exist: `assertHeaders` runs
   * before the first phase commits and outside any `phase(...)`, so this
   * refusal is wrapped in no `PartialIngestException` and matched no other arm
   * -- it escaped `main` as a bare stack trace while every sibling corpus's
   * refusal got a structured line, and the thirty-odd located defects this
   * loader carries existed to be read by an operator.
   */
  @Test
  fun `a header defect escapes the whole ingest as the type the error funnel logs`(
    @TempDir dir: File,
  ) {
    val renamed = NationalMeritAwardSeedLoader.AWARD_COLUMNS.map { if (it == "award_kind") "kind" else it }

    val thrown =
      assertFailsWith<NationalMeritAwardSeedLoader.FormatException> { ingest(writeCorpus(dir, header = renamed)) }

    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.HeaderMismatch>(thrown.defect)
    assertContains(defect.file, "awards.csv")
    // What the arm prints as the exception beside the payload: the operator's
    // one line names the corpus, the headers and that nothing was written.
    assertContains(thrown.message.orEmpty(), "awards.csv")
    assertContains(thrown.message.orEmpty(), "Nothing was written")
    // Not a PartialIngestException at all: nothing had committed to report.
    assertEquals(0, withSession { count(it, "colleges") }, "the institutions phase must not have run")
  }

  /**
   * AND A CELL REFUSAL REACHES IT WITH THE FILE, THE LINE AND THE CELL.
   *
   * This one is found inside the phase, after the institutions phase has
   * committed, so it arrives wrapped as the `PartialIngestException` the funnel
   * logs with its whole chain -- and the located sentence an operator acts on
   * is this loader's own, which is why the rendering is asserted here rather
   * than only the fields.
   */
  @Test
  fun `a cell refusal reaches the ingest's error funnel naming the file, the line and the cell`(
    @TempDir dir: File,
  ) {
    val awardsCsv = writeCorpus(dir, awards = listOf(paying("unit_id" to "11O100"), absence()))

    val thrown = assertFailsWith<PartialIngestException> { ingest(awardsCsv) }

    assertEquals("national-merit-awards", thrown.failedPhase)
    val refusal = assertIs<NationalMeritAwardSeedLoader.FormatException>(thrown.cause)
    val defect = assertIs<NationalMeritAwardSeedLoader.Defect.Cell>(refusal.defect)
    assertContains(defect.file, "awards.csv")
    assertEquals(1, defect.cell.line)
    assertEquals("unit_id", defect.cell.column)
    assertContains(refusal.message.orEmpty(), "awards.csv")
    assertContains(refusal.message.orEmpty(), "line [1]")
    assertContains(refusal.message.orEmpty(), "unit_id")
  }
}
