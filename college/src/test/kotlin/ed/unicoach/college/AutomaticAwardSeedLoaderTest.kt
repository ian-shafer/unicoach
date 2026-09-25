package ed.unicoach.college

import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.PublishedScoreScale
import ed.unicoach.db.models.ResidencyBasis
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The automatic-award corpus loader (RFC 239), on the scorecard test
 * scaffolding: colleges are seeded from the Scorecard institutions fixture so
 * the `unit_id` resolution and the NAME AGREEMENT run against real `colleges`
 * rows, and the per-test TRUNCATE of `colleges` cascades through this corpus's
 * foreign keys.
 *
 * EVERY TEST RUNS THE LOADER. The corpus is written into a temp directory per
 * test rather than read from committed fixtures, because what is under test is a
 * set of REFUSALS and each one needs a corpus that differs from the good one by
 * one cell -- a wrong name, a missing link answer, a band under a verified
 * absence. Committed fixtures for each would be eight near-identical files whose
 * differences a reader has to diff for.
 */
class AutomaticAwardSeedLoaderTest : CollegeScorecardTestBase() {
  private val scorecardLoader = CollegeScorecardLoader(database)
  private val loader = AutomaticAwardSeedLoader(database)

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

  private val coastalUrl = "https://coastalstate.edu/scholarships"
  private val harborUrl = "https://harborprivate.edu/aid"

  /**
   * A url no award row cites, and the reason phase 1 writes the sidecar WHOLE:
   * the real corpus commits the ACT composite-rounding page, which D-H's
   * arithmetic rests on and no school's grid links to. A loader that wrote only
   * the cited urls would leave it uncitable.
   */
  private val uncitedUrl = "https://www.act.org/content/act/en/products-and-services/the-act/scores.html"

  private fun goodAwards(): List<String> =
    listOf(
      "$coastalUnitId,$coastalName,2027,in_state,score_grid,required,single_sitting_only,2026-12-04," +
        "\"Official scores must be received by December 4.\",\"A submitted score is required to qualify.\"," +
        "\"We do not superscore for scholarship purposes.\",2026-12-01,month," +
        "\"Only scores through December 2026 will be considered.\",Coastal State University,$coastalUrl,," +
        "2026-09-24,2027-07-31",
      // A VERIFIED ABSENCE: we read this page and it publishes no automatic
      // award. It carries a citation and no claim, so no words and no deadline.
      "$harborUnitId,$harborName,2027,not_applicable,none_published,unstated,unstated,,,,,,,," +
        "Harbor Private College,$harborUrl,,2026-09-24,2027-07-31",
    )

  private fun goodBands(): List<String> =
    listOf(
      "$coastalUnitId,2027,in_state,Coastal Scholars,sat,1200,1250,3.50,,6000,",
      "$coastalUnitId,2027,in_state,Coastal Scholars,sat,1260,1290,3.50,,8000,",
      // The published NO-SCORE row -- the tier a test-optional applicant lands
      // in, and what makes "a score is worth $2,000 more than no score" sayable.
      "$coastalUnitId,2027,in_state,Coastal Scholars,,,,3.50,,4000,",
    )

  private fun goodLinkStatuses(): List<String> =
    listOf(
      "$coastalUrl,found,2026-09-24",
      "$harborUrl,found,2026-09-24",
      "$uncitedUrl,found,2026-09-24",
    )

  /** One corpus on disk: the three files, each written from its header plus the rows given. */
  private fun writeCorpus(
    dir: File,
    awards: List<String> = goodAwards(),
    bands: List<String> = goodBands(),
    linkStatuses: List<String>? = goodLinkStatuses(),
  ): Pair<File, File> {
    val awardsCsv = File(dir, "awards.csv")
    val bandsCsv = File(dir, "bands.csv")
    awardsCsv.writeText((listOf(AutomaticAwardSeedLoader.AWARD_COLUMNS.joinToString(",")) + awards).joinToString("\n") + "\n")
    bandsCsv.writeText((listOf(AutomaticAwardSeedLoader.BAND_COLUMNS.joinToString(",")) + bands).joinToString("\n") + "\n")
    // `null` means NO sidecar at all, which is a different state from an empty
    // one and has its own refusal.
    if (linkStatuses != null) {
      File(dir, LINK_STATUS_FILE)
        .writeText(
          (listOf(AutomaticAwardSeedLoader.LINK_STATUS_COLUMNS.joinToString(",")) + linkStatuses).joinToString("\n") + "\n",
        )
    }
    return awardsCsv to bandsCsv
  }

  private fun load(files: Pair<File, File>): AutomaticAwardSeedLoader.LoadResult = runBlocking { loader.load(files.first, files.second) }

  private fun awardKindsByUnitId(): Map<Int, String> =
    query(
      "SELECT c.ipeds_unit_id, a.award_kind FROM college_automatic_awards a " +
        "JOIN colleges c ON c.id = a.college_id",
    ) { rs -> rs.getInt(1) to rs.getString(2) }.toMap()

  private fun bandAmounts(): List<Int?> =
    query("SELECT award_per_year_usd FROM college_automatic_award_bands ORDER BY award_per_year_usd") { rs ->
      rs.getInt(1).takeUnless { rs.wasNull() }
    }

  /**
   * The repo's committed corpus directory, resolved by walking up from the
   * test's working directory (the module dir under Gradle) rather than assuming
   * a fixed depth -- `CdsSeedLoaderTest`'s own way of finding its seed.
   */
  private val committedCorpusDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/automatic-awards") }
      .first { it.isDirectory }

  @Test
  fun `the committed corpus is a corpus this loader can read`() {
    // THE CONTRACT IS AUTHORED TWICE and cannot be authored once: `:college`
    // cannot see `:db`'s test fixtures, where AutomaticAwardFixture names the
    // same three headers for the seed test. So the two authorings are checked
    // against the SAME committed files, from both sides, and a column renamed
    // in the corpus fails both.
    //
    // It runs the loader's own assertion over the real files rather than
    // comparing two lists of strings: what must hold is that `load` will accept
    // this corpus, and the header is read here by the same parser `load` uses --
    // BOM stripping, trimming and all.
    val awards = File(committedCorpusDir, "awards.csv")
    val bands = File(committedCorpusDir, "bands.csv")
    runBlocking { loader.assertHeaders(AutomaticAwardSources(source(awards), source(bands))) }
  }

  @Test
  fun `the corpus loads its documents, its awards and its bands`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val result = load(writeCorpus(dir))

    assertEquals(3, result.documents, "every sidecar url is written, cited or not")
    assertEquals(2, result.awards.upserted)
    assertEquals(3, result.bands.upserted)
    assertEquals(0, result.bandsPruned)
    assertEquals(mapOf(coastalUnitId to "score_grid", harborUnitId to "none_published"), awardKindsByUnitId())
    assertEquals(listOf(4000, 6000, 8000), bandAmounts())
  }

  @Test
  fun `a url no award row cites is still written, with the answer committed beside the corpus`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // THE POINT OF PHASE 1. The ACT rounding page is cited by no grid and is the
    // evidence D-H's arithmetic rests on; the status and the day are the
    // committed ones, not a "we never asked" this loader invented.
    val document =
      query(
        "SELECT url_status, url_checked_on FROM external_documents WHERE url = '$uncitedUrl'",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertEquals(listOf("found" to "2026-09-24"), document)
  }

  @Test
  fun `an unknown WITH a date is stored as it was committed, not refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // "We asked and got no usable answer" is a real state, and three urls in the
    // committed corpus are in it (a 403 in both passes). It KEEPS the link, so
    // refusing it here would drop a school whose page merely dislikes our
    // client. What is refused is a verdict with no day beside it.
    val asked = goodLinkStatuses().map { it.replace("$harborUrl,found,", "$harborUrl,unknown,") }
    load(writeCorpus(dir, linkStatuses = asked))

    assertEquals(
      listOf("unknown" to "2026-09-24"),
      query("SELECT url_status, url_checked_on FROM external_documents WHERE url = '$harborUrl'") { rs ->
        rs.getString(1) to rs.getString(2)
      },
    )
  }

  @Test
  fun `a link answer with no day beside it is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A hand-committed file does not get to say "we asked and cannot say when":
    // the column is NOT NULL, so the alternative is this loader choosing a day.
    val dateless = goodLinkStatuses().map { it.replace("$harborUrl,found,2026-09-24", "$harborUrl,found,") }
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, linkStatuses = dateless)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.SidecarMissingDate>(thrown.defect)
    assertEquals(harborUrl, defect.url)
  }

  @Test
  fun `a unit_id no college carries is refused, naming the row`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // 999999 is in no `colleges` row. The CDS loader SKIPS such a row because
    // its corpus is generated from a federal file; forty hand-typed rows have no
    // acceptable silent drop, and a dropped row reads downstream as "we have not
    // read this school" about a school somebody did read.
    val awards = goodAwards() + listOf(goodAwards()[0].replaceFirst("$coastalUnitId,", "999999,"))
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = awards)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.UnknownCollege>(thrown.defect)
    assertEquals(999999, defect.ipedsUnitId)
    assertEquals(coastalName, defect.typedName)
  }

  @Test
  fun `a college_name that disagrees with the stored name is refused, naming both`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // THE MEASURED FAILURE MODE, with a REAL wrong pair: Harbor Private College
    // is a school in this table, typed under Coastal State University's unit id.
    // A fuzzy match of the 48 curated names returned eight of exactly this shape
    // -- Arizona State matched Arizona Christian, Ohio State matched Shawnee
    // State -- and the row that results is another school's money under this
    // school's name.
    val misKeyed = goodAwards()[0].replaceFirst("$coastalUnitId,$coastalName,", "$coastalUnitId,$harborName,")
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> {
        load(writeCorpus(dir, awards = listOf(misKeyed)))
      }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.CollegeNameDisagreement>(thrown.defect)
    assertEquals(coastalUnitId, defect.ipedsUnitId)
    assertEquals(harborName, defect.typedName)
    assertEquals(coastalName, defect.storedName)
    // Both names in the message, because which one is wrong is the operator's
    // call and a message naming one cannot be acted on.
    assertContains(thrown.message.orEmpty(), harborName)
    assertContains(thrown.message.orEmpty(), coastalName)
  }

  @Test
  fun `a refused row leaves NOTHING behind, not even the rows that read cleanly`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val awards = goodAwards() + listOf(goodAwards()[0].replaceFirst("$coastalUnitId,", "999999,"))
    assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = awards)) }

    // One transaction: the two good rows were written and rolled back. A
    // half-applied grid is a school whose published bands stop mid-table.
    assertEquals(emptyMap(), awardKindsByUnitId())
    assertEquals(emptyList(), bandAmounts())
  }

  @Test
  fun `a band under a none_published row is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A school we READ and verified publishes no automatic award cannot publish
    // a cell of one. A cross-table CHECK would need a trigger, so this refusal
    // is the enforcement the schema comment names.
    val bands = goodBands() + listOf("$harborUnitId,2027,not_applicable,Harbor Merit,sat,1300,1400,3.50,,5000,")
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = bands)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.BandUnderNonePublished>(thrown.defect)
    assertEquals(harborUnitId, defect.ipedsUnitId)
    assertEquals(ResidencyBasis.NOT_APPLICABLE, defect.residencyBasis)
  }

  @Test
  fun `a band whose key names no award row is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // Not the same fault as the one above: this key has no award row AT ALL, so
    // "the school publishes none" is not what is wrong -- the two files
    // disagree about which grids exist.
    val bands = goodBands() + listOf("$coastalUnitId,2027,out_of_state,Coastal Scholars,sat,1200,1250,3.50,,6000,")
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = bands)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.BandWithNoAward>(thrown.defect)
    assertEquals(ResidencyBasis.OUT_OF_STATE, defect.residencyBasis)
  }

  @Test
  fun `a cited url with no committed link answer is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // With RFC 220's strict `external_documents` the citing write would state
    // evidence nobody gathered: the document would be recorded FOUND on the
    // reading day, by a loader that asked nobody. The corpus commits its rows
    // and its link answers together, so a url in one and not the other is a
    // corpus edited by halves.
    val withoutCoastal = goodLinkStatuses().filterNot { it.startsWith("$coastalUrl,") }
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> {
        load(writeCorpus(dir, linkStatuses = withoutCoastal))
      }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.UncheckedUrl>(thrown.defect)
    assertEquals(coastalUrl, defect.url)
    assertEquals("source_url", defect.column)
  }

  @Test
  fun `an absent link-status file is refused rather than read as we never asked`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The CDS loader tolerates an absent sidecar -- its corpus is swept by a
    // script and may legitimately run before the first sweep. This corpus's
    // answers are committed beside the rows, so absence is a corpus that is
    // missing a file, not a corpus nobody has checked yet.
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, linkStatuses = null)) }

    assertIs<AutomaticAwardSeedLoader.Defect.MissingLinkStatusFile>(thrown.defect)
  }

  @Test
  fun `two award rows on one key are refused rather than resolved by row order`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val awards = goodAwards() + listOf(goodAwards()[0].replaceFirst(",6000,", ",9999,"))
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = awards)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.DuplicateAwardKey>(thrown.defect)
    assertEquals(coastalUnitId, defect.ipedsUnitId)
  }

  @Test
  fun `a second run of the same corpus writes nothing and changes nothing`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val files = writeCorpus(dir)
    load(files)
    val before =
      query("SELECT id, updated_at FROM college_automatic_awards ORDER BY id") { rs ->
        rs.getString(1) to rs.getString(2)
      }

    val second = load(files)

    assertEquals(0, second.awards.upserted)
    assertEquals(0, second.awards.changed)
    assertEquals(2, second.awards.unchanged)
    assertEquals(0, second.bands.upserted)
    assertEquals(0, second.bands.changed)
    assertEquals(3, second.bands.unchanged)
    assertEquals(0, second.bandsPruned)
    // The IDS AND THE TIMESTAMPS, not just the counts: a loader that deleted and
    // re-inserted every row would report the same three numbers.
    assertEquals(
      before,
      query("SELECT id, updated_at FROM college_automatic_awards ORDER BY id") { rs ->
        rs.getString(1) to rs.getString(2)
      },
    )
    assertEquals(listOf(4000, 6000, 8000), bandAmounts())
  }

  @Test
  fun `a re-read that drops a band drops it from the table too`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // The corpus is re-read and the school no longer publishes its top cell. A
    // natural-key upsert alone would leave that row standing, and the coach
    // would quote money the school stopped paying.
    val corrected = writeCorpus(dir, bands = goodBands().filterNot { it.contains(",8000,") })
    val result = load(corrected)

    assertEquals(1, result.bandsPruned)
    assertEquals(listOf(4000, 6000), bandAmounts())
  }

  @Test
  fun `a re-read that changes a figure changes the row and reports it`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    val raised = goodBands().map { it.replace(",6000,", ",6500,") }
    val result = load(writeCorpus(dir, bands = raised))

    assertEquals(1, result.bands.changed)
    assertEquals(2, result.bands.unchanged)
    assertEquals(0, result.bandsPruned, "a changed cell is the SAME cell: its natural key did not move")
    assertEquals(listOf(4000, 6500, 8000), bandAmounts())
  }

  @Test
  fun `the last sitting is stored at the precision the page published`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    val stored =
      query(
        "SELECT last_sitting_through::text, last_sitting_precision, last_sitting_as_published " +
          "FROM college_automatic_awards a JOIN colleges c ON c.id = a.college_id " +
          "WHERE c.ipeds_unit_id = $coastalUnitId",
      ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
    assertEquals(1, stored.size)
    // The period's START is stored; the service takes its end, because "through
    // December 2026" admits every December sitting.
    assertEquals("2026-12-01", stored[0].first)
    assertEquals("month", stored[0].second)
    assertContains(stored[0].third, "through December 2026")
  }

  @Test
  fun `a last sitting date that is not its period's start is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val midMonth = goodAwards().map { it.replace(",2026-12-01,month,", ",2026-12-15,month,") }
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = midMonth)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.LastSittingNotAPeriodStart>(thrown.defect)
    assertEquals("2026-12-15", defect.through)
  }

  @Test
  fun `a last sitting date with no precision beside it is refused`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val halfPair = goodAwards().map { it.replace(",2026-12-01,month,", ",2026-12-01,,") }
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = halfPair)) }

    assertIs<AutomaticAwardSeedLoader.Defect.LastSittingPairBroken>(thrown.defect)
  }

  @Test
  fun `a verified absence stores its citation and no claim`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    val row =
      query(
        "SELECT a.score_deadline::text, d.url FROM college_automatic_awards a " +
          "JOIN colleges c ON c.id = a.college_id JOIN external_documents d ON d.id = a.source_document_id " +
          "WHERE c.ipeds_unit_id = $harborUnitId",
      ) { rs -> rs.getString(1) to rs.getString(2) }
    assertEquals(1, row.size)
    assertNull(row[0].first, "a school that publishes no automatic award publishes no deadline for one")
    assertEquals(harborUrl, row[0].second, "the absence is CITED: we read this page, it is not our silence")
  }

  @Test
  fun `a re-read that drops an award drops the school's whole grid`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // The corpus no longer names Harbor -- the school was dropped, or its
    // unit_id was corrected and this row is the old one. Either way the row it
    // left behind is a school we would go on publishing from a line nobody will
    // look at again, which is the band prune's argument one table up.
    val corrected =
      writeCorpus(
        dir,
        awards = goodAwards().filterNot { it.startsWith("$harborUnitId,") },
      )
    val result = load(corrected)

    assertEquals(1, result.awardsPruned)
    assertEquals(mapOf(coastalUnitId to "score_grid"), awardKindsByUnitId())
  }

  @Test
  fun `a dropped award takes its bands with it`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // Coastal is the school with the grid, so dropping IT is the case where the
    // cascade matters: three cells priced under a row this corpus no longer
    // names.
    val corrected =
      writeCorpus(
        dir,
        awards = goodAwards().filterNot { it.startsWith("$coastalUnitId,") },
        bands = emptyList(),
      )
    val result = load(corrected)

    assertEquals(1, result.awardsPruned)
    assertEquals(mapOf(harborUnitId to "none_published"), awardKindsByUnitId())
    assertEquals(emptyList(), bandAmounts(), "the cells go with the award they hang off")
  }

  @Test
  fun `an awards file with no row is refused rather than emptying the table`(
    @TempDir dir: File,
  ) {
    seedColleges()
    load(writeCorpus(dir))

    // An empty corpus would read as "drop every school we publish". It is a
    // file somebody truncated, so it is named and nothing is written -- the two
    // awards from the first load are still there afterwards.
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> {
        load(writeCorpus(dir, awards = emptyList(), bands = emptyList()))
      }

    assertIs<AutomaticAwardSeedLoader.Defect.EmptyAwardCorpus>(thrown.defect)
    assertEquals(mapOf(coastalUnitId to "score_grid", harborUnitId to "none_published"), awardKindsByUnitId())
  }

  @Test
  fun `a second band row on one natural key is refused, not resolved last-wins`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // Same award, same name, same test, same score floor and same GPA floor,
    // different money. The upsert would resolve this by row order and count it
    // as a change; the corpus would carry two prices for one published cell.
    val repeated = goodBands() + "$coastalUnitId,2027,in_state,Coastal Scholars,sat,1200,1250,3.50,,7000,"
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = repeated)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.DuplicateBandKey>(thrown.defect)
    assertEquals("Coastal Scholars", defect.awardName)
    assertEquals(1200, defect.scoreLow)
    assertEquals(1, defect.firstLine, "the operator is sent to BOTH rows, not only the second")
    assertEquals(4, defect.line)
  }

  @Test
  fun `the same cell typed with a trailing zero is still the same cell`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `3.50` and `3.5` are one GPA and one row in a NUMERIC index, so a
    // duplicate typed the other way round must be refused too.
    val repeated = goodBands() + "$coastalUnitId,2027,in_state,Coastal Scholars,sat,1200,1250,3.5,,7000,"
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = repeated)) }

    assertIs<AutomaticAwardSeedLoader.Defect.DuplicateBandKey>(thrown.defect)
  }

  @Test
  fun `a GPA bound the column would ROUND is refused, not stored rounded`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `NUMERIC(4,3)` rounds a fourth decimal on the way IN, before any CHECK
    // sees it, so `3.7499` would land as `3.750` -- a bound no school
    // published, colliding with a real `3.75` tier on the natural key and
    // replacing that cell's money under a clean `changed` count. The loader is
    // the last reader that still holds the cell as it was typed.
    val rounded = goodBands() + "$coastalUnitId,2027,in_state,Coastal Scholars,sat,1300,1400,3.7499,,9000,"
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = rounded)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.GpaOutsidePublishedShape>(thrown.defect)
    assertEquals("gpa_low", defect.column)
    assertEquals("3.7499", defect.value)
    assertEquals(4, defect.line)
    assertEquals(0, bandAmounts().size, "a refused corpus writes no cell at all")
  }

  @Test
  fun `a GPA bound outside the published range is refused by line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The same range the table's own ..._gpa_range_check admits, asked where the
    // line is still in hand.
    val outOfRange = goodBands() + "$coastalUnitId,2027,in_state,Coastal Scholars,sat,1300,1400,,6.5,9000,"
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = outOfRange)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.GpaOutsidePublishedShape>(thrown.defect)
    assertEquals("gpa_high", defect.column)
    assertEquals("6.5", defect.value)
  }

  @Test
  fun `a band edge off its own test's published scale is refused by line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `126` typed for `1260`: an integer, ordered, and not an SAT score. It
    // would load green and match no student ever, which reads downstream
    // exactly like a school that pays nothing in that band.
    val misKeyed = goodBands() + "$coastalUnitId,2027,in_state,Coastal Scholars,sat,126,129,3.60,,9000,"
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = misKeyed)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.ScoreOutsidePublishedScale>(thrown.defect)
    assertEquals("score_low", defect.column)
    assertEquals(AdmissionTest.SAT, defect.test)
    assertEquals(126, defect.value)
    assertEquals(PublishedScoreScale.SAT_COMPOSITE, defect.scale)
    assertEquals(0, bandAmounts().size, "a refused corpus writes no cell at all")
  }

  @Test
  fun `an ACT band edge on the SAT's scale is refused too`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The scale is the TEST'S, not one number for both: 1200 is a real SAT
    // total and no ACT composite at all.
    val satOnAct = goodBands() + "$coastalUnitId,2027,in_state,Coastal Scholars,act,1200,1250,3.60,,9000,"
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = satOnAct)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.ScoreOutsidePublishedScale>(thrown.defect)
    assertEquals(AdmissionTest.ACT, defect.test)
    assertEquals(PublishedScoreScale.ACT_COMPOSITE, defect.scale)
  }

  @Test
  fun `a sidecar url that is not a web page is refused, by file and line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // `external_documents` refuses this string by VALUE, with no idea which row
    // produced it -- and this corpus's whole discipline is telling the person
    // who typed it which row to fix.
    val notAPage = goodLinkStatuses() + "coastal.example.edu/scholarships,found,2026-09-24"
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> {
        load(writeCorpus(dir, linkStatuses = notAPage))
      }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.SidecarUrlNotAWebPage>(thrown.defect)
    assertEquals("coastal.example.edu/scholarships", defect.url)
    assertEquals(4, defect.line)
    assertContains(defect.file, LINK_STATUS_FILE)
  }

  @Test
  fun `a stored score deadline with no sentence beside it is refused, naming the date`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // ONE WAY, not an IFF: a school may publish the sentence with no date we can
    // parse, but a stored date with no sentence is a date nobody can check us
    // on. The DATE rides on the defect, not a rendering of it.
    val noWords = goodAwards().map { it.replace("\"Official scores must be received by December 4.\",", ",") }
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = noWords)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.DeadlineWithoutWords>(thrown.defect)
    assertEquals(LocalDate.of(2026, 12, 4), defect.deadline)
    assertEquals("score_deadline", defect.deadlineColumn)
    assertEquals("score_deadline_as_published", defect.wordsColumn)
  }

  @Test
  fun `a stated rule with no words, and words with no rule, are both refused by line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The schema's ..._has_words_check is an IFF and the read model requires
    // it; neither can say which LINE somebody typed.
    val noWords =
      goodAwards().map {
        it.replace("\"A submitted score is required to qualify.\",", ",")
      }
    val missing = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = noWords)) }
    val missingDefect = assertIs<AutomaticAwardSeedLoader.Defect.ClaimAndWordsDisagree>(missing.defect)
    assertEquals("score_requirement", missingDefect.ruleColumn)
    assertEquals("required", missingDefect.rule)

    // And the other way: the verified absence claims nothing, so a sentence
    // beside it is a quote under a rule the row denies having read.
    val wordsUnderUnstated =
      goodAwards().map {
        it.replace(
          "$harborUnitId,$harborName,2027,not_applicable,none_published,unstated,unstated,,,,,",
          "$harborUnitId,$harborName,2027,not_applicable,none_published,unstated,unstated,,," +
            "\"Scores are not required.\",,",
        )
      }
    val spurious =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, awards = wordsUnderUnstated)) }
    val spuriousDefect = assertIs<AutomaticAwardSeedLoader.Defect.ClaimAndWordsDisagree>(spurious.defect)
    assertEquals("score_requirement", spuriousDefect.ruleColumn)
    assertEquals("unstated", spuriousDefect.rule)
  }

  @Test
  fun `a band that states no value at all is refused by line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    val valueless = goodBands().map { it.replace(",3.50,,6000,", ",3.50,,,") }
    val thrown = assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = valueless)) }

    val defect = assertIs<AutomaticAwardSeedLoader.Defect.BandStatesNoValue>(thrown.defect)
    assertEquals("Coastal Scholars", defect.awardName)
  }

  @Test
  fun `a score band with no test, and a test with no score band, are both refused by line`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // A score range is a range ON a test: the published no-score row names
    // neither, and anything in between is half a cell.
    val scoreWithoutTest =
      goodBands().map { it.replace("Coastal Scholars,sat,1200,1250,", "Coastal Scholars,,1200,1250,") }
    val thrown =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = scoreWithoutTest)) }
    val defect = assertIs<AutomaticAwardSeedLoader.Defect.BandScoreAndTestDisagree>(thrown.defect)
    assertNull(defect.test)
    assertEquals(1200, defect.scoreLow)

    val testWithoutScore =
      goodBands().map { it.replace("Coastal Scholars,sat,1200,1250,", "Coastal Scholars,sat,,,") }
    val second =
      assertFailsWith<AutomaticAwardSeedLoader.FormatException> { load(writeCorpus(dir, bands = testWithoutScore)) }
    assertIs<AutomaticAwardSeedLoader.Defect.BandScoreAndTestDisagree>(second.defect)
  }

  @Test
  fun `the outcome of every written row is reported, never inferred`(
    @TempDir dir: File,
  ) {
    seedColleges()
    // The three dispositions are the run summary's whole content, so a loader
    // that reported INSERTED for an untouched row would make a no-op re-ingest
    // read like a rewrite.
    val first = load(writeCorpus(dir))
    assertEquals(
      UpsertOutcome.entries.size,
      3,
      "this suite asserts against all three dispositions by name; a fourth needs a case here",
    )
    assertEquals(first.awards.rows, 2)
    assertEquals(first.bands.rows, 3)
  }
}
