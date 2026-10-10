package ed.unicoach.college

import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.NationalMeritAwardsDao
import ed.unicoach.db.dao.newCollegeFixture
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.HandTypedReferenceData
import ed.unicoach.db.models.HandTypedReferenceData.REFRESH_LEAD
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NationalMeritClaim
import kotlinx.coroutines.runBlocking
import org.apache.commons.csv.CSVFormat
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE REAL COMMITTED CORPUS, THROUGH THE REAL LOADER, INTO A REAL DATABASE
 * (RFC 243), and the staleness guard over the same files.
 *
 * [NationalMeritAwardSeedLoaderTest] is about the loader's REFUSALS, so every
 * corpus it reads is two rows written per test; it asserts the committed file's
 * HEADER and nothing else about it. That leaves the thing the corpus IS:
 * twenty-six hand-typed rows over seventeen real IPEDS unit ids, every one of
 * which has to satisfy every CHECK in
 * `db/schema/create-college-national-merit-awards.sql` and then map back
 * through `NationalMeritAward`'s six `init` requires. A constraint that refuses
 * a REAL row is the class of defect this suite exists to catch -- RFC 239's own
 * corpus met one during authoring, where West Virginia's "through August 1"
 * fell outside a deadline window that closed on 30 June.
 *
 * WHICH MAKES THIS THE GATE ON A CSV EDIT. A future re-read that types a date
 * outside its cycle, money on a school we verified publishes nothing, or a url
 * the sidecar does not answer for, fails HERE -- at `bin/test`, on the machine
 * of whoever edited it -- instead of at an operator's `bin/load-external-data`
 * run.
 *
 * `colleges` IS SEEDED FROM `awards.csv` ITSELF, never from a typed list: the
 * loader keys on `(unit_id, college_name)` and refuses a disagreement, so a
 * hand-typed college list in this file would be a second authoring of the
 * corpus that could drift from it and would then be testing itself. Reading the
 * pairs out of the corpus means the schools this suite creates ARE the schools
 * the corpus names, whatever a later re-read does to them.
 *
 * THE STALENESS GUARD LIVES HERE, and not in `:db` beside
 * `AutomaticAwardSeedTest`, because [NationalMeritFixture] deliberately reads
 * no committed corpus: the two corpora this slice commits reach the database by
 * two different routes, each route's own guard reads its own file, and a reader
 * in the shared fixture would be a third opinion about what is on disk. The
 * NUMBER it uses is still the shared one -- [REFRESH_LEAD], never a private
 * ninety days.
 */
class NationalMeritAwardCommittedCorpusTest : CollegeScorecardTestBase() {
  private val loader = NationalMeritAwardSeedLoader(database)

  /**
   * The committed corpus directory, found by walking up from the test's working
   * directory -- [NationalMeritAwardSeedLoaderTest]'s rule, which is
   * `CdsSeedLoaderTest`'s.
   */
  private val committedDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/seed/national-merit-awards") }
      .first { it.isDirectory }

  private val awardsCsv = File(committedDir, "awards.csv")

  /**
   * The committed rows as their raw cells, read with a REAL CSV parser.
   *
   * IT READS CELLS AND NOT A MODEL. Everything this suite asserts about the
   * corpus's CONTENT is read back out of the DATABASE the loader wrote; what
   * the file is needed for is the `(unit_id, college_name)` pairs, which only
   * it holds, and the expiry days the staleness guard reads. A second decoder
   * here would be a second opinion about what the corpus says, and the loader
   * is the one that has to be right.
   */
  private val committedRows: List<Map<String, String>> =
    CSVFormat.DEFAULT
      .builder()
      .setHeader()
      .setSkipHeaderRecord(true)
      .get()
      .parse(awardsCsv.bufferedReader())
      .use { parser ->
        require(parser.headerNames == NationalMeritAwardSeedLoader.AWARD_COLUMNS) {
          "[${awardsCsv.path}] header is [${parser.headerNames}], which is not the header this loader reads"
        }
        parser.records.map { record -> record.toMap() }
      }

  /** Every school the corpus keys on, `unit_id -> name AS TYPED` -- read out of `awards.csv`, the only home of that pairing. */
  private val corpusColleges: Map<Int, String> =
    committedRows.associate { row -> row.getValue("unit_id").toInt() to row.getValue("college_name") }

  /**
   * The corpus's schools as `colleges` rows, under the names the corpus types.
   * Every other column is [newCollegeFixture]'s filler: this suite is about the
   * award corpus, and the only `colleges` columns the loader reads are
   * `ipeds_unit_id`, `name` and `id`.
   */
  private fun seedCorpusColleges(): Map<Int, CollegeId> =
    withSession { session ->
      corpusColleges.mapValues { (unitId, name) ->
        CollegesDao.upsert(session, newCollegeFixture(unitId, name)).getOrThrow().id
      }
    }

  private fun load(): NationalMeritAwardSeedLoader.LoadResult = runBlocking { loader.load(awardsCsv) }

  private fun countOf(sql: String): Int = query(sql) { rs -> rs.getInt(1) }.single()

  @Test
  fun `the committed corpus loads into a real database with no refusal`() {
    seedCorpusColleges()

    val result = load()

    // EVERY ROW WAS AN INSERT, which is the assertion that no two rows
    // collapsed onto one natural key: a duplicated key is refused by name, but
    // only because this loader refuses it -- the upsert underneath would
    // resolve it last-wins and report a clean CHANGED.
    assertEquals(committedRows.size, result.awards.upserted, "every committed row is an INSERT")
    assertEquals(committedRows.size, result.awards.rows, "every committed row was read")
    assertEquals(0, result.awardsPruned, "a first load of a corpus has nothing to prune")
    // The TABLE, not the run report: a count the loader computed and a count
    // the database holds are two different claims.
    assertEquals(committedRows.size, countOf("SELECT count(*) FROM college_national_merit_awards"))
  }

  @Test
  fun `the corpus meets the size the curation pass was measured at`() {
    seedCorpusColleges()
    load()

    // THE FLOORS ARE NOT THE MEASURED COUNTS, and that is deliberate
    // ([AutomaticAwardSeedTest]'s argument): `PROVENANCE.json` pins the exact
    // numbers, and what these catch is the other failure -- a file truncated by
    // a half-finished edit, a merge that kept one side -- which an exact count
    // cannot tell from an honest re-read. They sit JUST under what the
    // 2026-09-25 pass measured, because D8's escape clause made the SIZE of
    // this corpus the thing that decided the slice ships at all.
    assertAtLeast(
      MINIMUM_PAYING_SCHOOLS,
      countOf(
        "SELECT count(DISTINCT college_id) FROM college_national_merit_awards WHERE award_kind <> 'none_published'",
      ),
      "schools publishing a National Merit award",
    )
    assertAtLeast(MINIMUM_AWARD_ROWS, countOf("SELECT count(*) FROM college_national_merit_awards"), "award rows")
    assertAtLeast(
      MINIMUM_COLLEGES,
      countOf("SELECT count(DISTINCT college_id) FROM college_national_merit_awards"),
      "colleges seeded",
    )
    // A VERIFIED ABSENCE IS ITS OWN ANSWER, and the hardest row in the corpus
    // to source: a page that simply never mentions National Merit is silence,
    // and silence is not a citation. Losing the last one would leave the coach
    // with no way to say "we read this school and it publishes nothing".
    assertAtLeast(
      MINIMUM_VERIFIED_ABSENCES,
      countOf("SELECT count(*) FROM college_national_merit_awards WHERE award_kind = 'none_published'"),
      "verified absences",
    )
  }

  @Test
  fun `every loaded row reads back through the model layer`() {
    val collegeIds = seedCorpusColleges()
    load()

    val awards =
      withSession { session -> NationalMeritAwardsDao.listByColleges(session, collegeIds.values).getOrThrow() }

    // The read is what the model layer's refusals run on: `NationalMeritClaim`'s
    // two arms (a standing, a name, the figures, a rule and a deadline exist on
    // the paying arm and nowhere else), `FirstChoiceRule`'s two arms,
    // `ApplyDeadline`'s pairing and `NationalMeritAward`'s expiry require. A row
    // legal in SQL and illegal in Kotlin fails HERE and nowhere earlier -- the
    // mapper refuses it with the row's own id and column.
    assertEquals(committedRows.size, awards.size, "every stored row maps back")
    assertTrue(
      awards.all { award ->
        when (val claim = award.claim) {
          is NationalMeritClaim.Paying -> claim.awardKind != NationalMeritAwardKind.NONE_PUBLISHED
          is NationalMeritClaim.NonePublished -> true
        }
      },
      "a verified absence reads back as the absence arm, which has no award to name",
    )
  }

  @Test
  fun `a second load of the unchanged corpus writes nothing and prunes nothing`() {
    seedCorpusColleges()
    load()

    val again = load()

    // UNCHANGED and not CHANGED: `updated_at` must not advance for a corpus
    // nobody edited, and a re-ingest must not report writes it did not make.
    assertEquals(committedRows.size, again.awards.unchanged, "an unedited row is UNCHANGED")
    assertEquals(0, again.awards.upserted + again.awards.changed)
    // THE PRUNE IS THE ONE THAT COULD BE DESTRUCTIVE: it deletes every row this
    // run did not write, so a re-run that failed to recognise its own rows
    // would drop the corpus and re-insert it, churning ids for nothing.
    assertEquals(0, again.awardsPruned, "a re-load of the same corpus drops no school")
    assertEquals(committedRows.size, countOf("SELECT count(*) FROM college_national_merit_awards"))
  }

  @Test
  fun `every url the corpus cites has a committed link answer, and every answer says when we asked`() {
    seedCorpusColleges()

    val result = load()

    // The sidecar's own row count, not the cited subset: every url it answers
    // for is written. A cited url MISSING from it is a refusal the loader makes
    // by name, so this passing is the statement that none is missing.
    assertTrue(result.documents > 0, "the sidecar answers for at least one url")
    assertEquals(
      0,
      countOf(
        "SELECT count(*) FROM external_documents d " +
          "JOIN college_national_merit_awards a ON a.source_document_id = d.id " +
          "WHERE d.url_checked_on IS NULL",
      ),
      "a citation states the day we asked, or it is not evidence",
    )
  }

  /**
   * STALENESS IS A BUILD FAILURE, and that is this case's whole point
   * (brief 0010 D20). Every row is hand-typed from a school's own scholarship
   * page because no machine-readable feed exists for any of them, and at these
   * schools a National Merit standing IS a five-figure package -- so the only
   * thing standing between a family and a wrong number is somebody noticing the
   * cycle turned over. Auburn is the worked case: it cut a $44,000 award to
   * Alabama residents only, and a row with no read date would still be telling
   * a family in Oregon it was theirs.
   *
   * IT READS EVERY COMMITTED ROW, not a live subset, which is
   * [AutomaticAwardSeedTest]'s reading one corpus along and for its reason:
   * this corpus is RE-READ IN PLACE for the next entry year rather than
   * appended to, so every committed row is a current claim about a school. A
   * row already past its `expires_on` fails this too, which is correct -- it is
   * staler still.
   */
  @Test
  fun `no committed row is within the shared refresh lead of expiring`() {
    val today = today()

    val stale =
      committedRows
        .map { row -> row to LocalDate.parse(row.getValue("expires_on")) }
        .filter { (_, expiresOn) -> ChronoUnit.DAYS.between(today, expiresOn) < REFRESH_LEAD.days }
        .map { (row, expiresOn) -> "[${row.getValue("college_name")}] expires_on=[$expiresOn]" }

    assertEquals(
      emptyList(),
      stale,
      "the committed National Merit award corpus has rotted, so the coach is about to quote a superseded " +
        "package; re-read every school's own page for the next entry year, re-run bin/check-link over " +
        "[${awardsCsv.parentFile.path}], and commit the new rows with their new checked_on and expires_on",
    )
  }

  /**
   * Today, in the ONE zone every hand-typed corpus's staleness guard reads it in
   * ([HandTypedReferenceData.GUARD_ZONE]).
   *
   * `LocalDate.now()` would read `ZoneId.systemDefault()` -- itself the process
   * `TZ` -- so the refresh bar would fire a day apart on a developer's machine
   * and on CI. `:college` cannot see `COACH_ZONE` in `:service`, which is why
   * the zone is named in `:db` beside [REFRESH_LEAD] and taken from there.
   */
  private fun today(): LocalDate = LocalDate.ofInstant(Instant.now(), HandTypedReferenceData.GUARD_ZONE)

  private fun assertAtLeast(
    floor: Int,
    actual: Int,
    what: String,
  ) = assertTrue(
    actual >= floor,
    "[${awardsCsv.path}] loaded [$actual] $what, fewer than the [$floor] the 2026-09-25 curation pass was " +
      "measured at: this corpus is half-written, not merely smaller",
  )

  private companion object {
    /**
     * The floors, JUST under the 2026-09-25 measurement (16 paying schools, 26
     * rows, 17 colleges, 1 verified absence) -- see the size case for why they
     * are floors and not the numbers themselves.
     */
    const val MINIMUM_PAYING_SCHOOLS = 16
    const val MINIMUM_AWARD_ROWS = 26
    const val MINIMUM_COLLEGES = 17
    const val MINIMUM_VERIFIED_ABSENCES = 1
  }
}
