package ed.unicoach.college

import ed.unicoach.db.dao.AutomaticAwardFixture
import ed.unicoach.db.dao.AutomaticAwardsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.newCollegeFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE REAL COMMITTED CORPUS, THROUGH THE REAL LOADER, INTO A REAL DATABASE
 * (RFC 239).
 *
 * [AutomaticAwardSeedLoaderTest] is about the loader's REFUSALS, so every corpus
 * it reads is two rows written per test; it asserts the committed files' HEADER
 * and nothing else about them. That leaves the thing the corpus is: 60 award rows
 * and 571 published cells over 40 real IPEDS unit ids, hand-typed, every one of
 * which has to satisfy every CHECK in
 * `db/schema/create-college-automatic-awards.sql` and then map back through
 * `AutomaticAward`'s four `init` requires. A constraint that refuses a REAL row
 * is the class of defect this suite exists to catch, and one already happened
 * during authoring: West Virginia publishes "you may submit an updated GPA or
 * test score through August 1", which a deadline-window CHECK closing on 30 June
 * refused. That was found by reading rows, not by running them -- so now the
 * build runs them.
 *
 * WHICH MAKES THIS THE GATE ON A CSV EDIT. A future re-read that types a date
 * outside its cycle, a GPA bound with four decimals, a band under a school we
 * verified publishes nothing, or a url the sidecar does not answer for, fails
 * HERE -- at `bin/test`, on the machine of whoever edited it -- instead of at an
 * operator's `bin/load-external-data` run.
 *
 * `colleges` IS SEEDED FROM `awards.csv` ITSELF, never from a typed list: the
 * loader keys on `(unit_id, college_name)` and refuses a disagreement, so a
 * hand-typed college list in this file would be a second authoring of the corpus
 * that could drift from it and would then be testing itself. Reading the pairs
 * out of the corpus means the 40 schools this suite creates ARE the 40 schools
 * the corpus names, whatever a later re-read does to them.
 */
class AutomaticAwardCommittedCorpusTest : CollegeScorecardTestBase() {
  private val loader = AutomaticAwardSeedLoader(database)

  private val committedAwards = AutomaticAwardFixture.committedAwards()
  private val committedBands = AutomaticAwardFixture.committedBands()
  private val committedLinkStatuses = AutomaticAwardFixture.committedLinkStatuses()

  /**
   * Every school the corpus keys on, `unit_id -> name AS TYPED` -- read out of
   * `awards.csv`, which is the only place that pairing exists.
   */
  private val corpusColleges: Map<Int, String> =
    committedAwards.associate { award -> award.key.unitId.toInt() to award.collegeName }

  /**
   * The 40 schools as `colleges` rows, under the names the corpus types. Every
   * other column is [newCollegeFixture]'s filler: this suite is about the award
   * corpus, and the only `colleges` columns the loader reads are
   * `ipeds_unit_id`, `name` and `id`.
   */
  private fun seedCorpusColleges(): Map<Int, CollegeId> =
    withSession { session ->
      corpusColleges.mapValues { (unitId, name) ->
        CollegesDao.upsert(session, newCollegeFixture(unitId, name)).getOrThrow().id
      }
    }

  private fun load(): AutomaticAwardSeedLoader.LoadResult =
    runBlocking {
      loader.load(AutomaticAwardFixture.AWARDS_CSV, AutomaticAwardFixture.BANDS_CSV)
    }

  private fun countOf(table: String): Int = withSession { session -> count(session, table) }

  /** The stored answer for every url the sidecar carries, in the sidecar's own shape. */
  private fun storedLinkAnswers(): Map<String, Pair<String, LocalDate?>> =
    query(
      "SELECT url, url_status, url_checked_on FROM external_documents",
    ) { rs -> rs.getString(1) to Pair(rs.getString(2), rs.getDate(3)?.toLocalDate()) }
      .toMap()
      .filterKeys { url -> url in committedLinkStatuses }

  private fun awardsOf(ids: Collection<CollegeId>): List<AutomaticAward> =
    withSession { session -> AutomaticAwardsDao.listByColleges(session, ids).getOrThrow() }

  private fun awardOf(
    awards: List<AutomaticAward>,
    collegeId: CollegeId,
    residencyBasis: ResidencyBasis,
  ): AutomaticAward = awards.single { award -> award.collegeId == collegeId && award.residencyBasis == residencyBasis }

  @Test
  fun `the committed corpus loads into a real database`() {
    seedCorpusColleges()

    val result = load()

    // EVERY ROW WAS AN INSERT, which is the assertion that no two rows collapsed
    // onto one natural key: a duplicated (unit_id, entry_year, residency_basis)
    // is refused by name, but a duplicated band CELL would upsert quietly and
    // land as CHANGED or UNCHANGED rather than INSERTED.
    assertEquals(committedAwards.size, result.awards.upserted, "every committed award row is an INSERT")
    assertEquals(committedAwards.size, result.awards.rows, "every committed award row was read")
    assertEquals(committedBands.size, result.bands.upserted, "every committed band row is an INSERT")
    assertEquals(committedBands.size, result.bands.rows, "every committed band row was read")
    assertEquals(committedLinkStatuses.size, result.documents, "every url the sidecar answers for was written")
    assertEquals(0, result.bandsPruned, "a first load of a corpus has nothing to prune")

    // The tables, not the run report: a count the loader computed and a count
    // the database holds are two different claims.
    assertEquals(committedAwards.size, countOf("college_automatic_awards"))
    assertEquals(committedBands.size, countOf("college_automatic_award_bands"))
    // The sidecar's answers, as committed -- including the two urls no award row
    // cites, which is why this is the sidecar's whole content and not the cited
    // subset.
    assertEquals(committedLinkStatuses, storedLinkAnswers())
  }

  @Test
  fun `a second load of the unchanged corpus writes nothing and prunes nothing`() {
    seedCorpusColleges()
    load()

    val again = load()

    // UNCHANGED and not CHANGED: `updated_at` must not advance for a corpus
    // nobody edited, and a re-ingest must not report writes it did not make.
    assertEquals(committedAwards.size, again.awards.unchanged, "an unedited award row is UNCHANGED")
    assertEquals(committedBands.size, again.bands.unchanged, "an unedited band row is UNCHANGED")
    assertEquals(0, again.awards.upserted + again.awards.changed)
    assertEquals(0, again.bands.upserted + again.bands.changed)
    // THE PRUNE IS THE ONE THAT COULD BE DESTRUCTIVE: it deletes every band an
    // award ends the run without, so a re-run that failed to recognise its own
    // rows would drop the grid and re-insert it, churning ids for nothing.
    assertEquals(0, again.bandsPruned, "a re-load of the same corpus drops no cell")
    assertEquals(committedBands.size, countOf("college_automatic_award_bands"))
  }

  @Test
  fun `every loaded row reads back through the model layer`() {
    val collegeIds = seedCorpusColleges()
    load()

    val awards = awardsOf(collegeIds.values)

    // The read is what the `init` requires of `AutomaticAward` and
    // `AutomaticAwardBand` run on -- the value-iff-kind pairings, and
    // `withBands`' refusal of a band under a verified absence. A row legal in
    // SQL and illegal in Kotlin fails HERE and nowhere earlier.
    assertEquals(committedAwards.size, awards.size, "every stored award row maps back")
    assertEquals(committedBands.size, awards.sumOf { it.bands.size }, "every stored band maps back under its award")
    assertTrue(
      awards.filter { it.awardKind == AutomaticAwardKind.NONE_PUBLISHED }.all { it.bands.isEmpty() },
      "a verified absence publishes no cell",
    )
  }

  /**
   * ALABAMA, THE SLICE'S WORKED EXAMPLE: a score grid with a published
   * RECEIPT DEADLINE and no sitting bound, whose out-of-state cells are the
   * RFC's headline figures -- $6,000/yr at SAT 1200-1250 and $8,000/yr at
   * 1260-1290, one sitting only.
   */
  @Test
  fun `Alabama's score grid reads back with its deadline and its headline cells`() {
    val collegeIds = seedCorpusColleges()
    load()

    val alabama = collegeIds.getValue(ALABAMA_UNIT_ID)
    val awards = awardsOf(listOf(alabama))

    assertEquals(
      setOf(ResidencyBasis.IN_STATE, ResidencyBasis.OUT_OF_STATE),
      awards.map { it.residencyBasis }.toSet(),
      "Alabama publishes a resident grid and a non-resident grid, and they pay differently",
    )

    val outOfState = awardOf(awards, alabama, ResidencyBasis.OUT_OF_STATE)
    assertEquals(AutomaticAwardKind.SCORE_GRID, outOfState.awardKind)
    assertEquals(ScoreRequirement.REQUIRED, outOfState.scoreRequirement)
    assertEquals(ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY, outOfState.superscoreRule)
    assertEquals(LocalDate.of(2026, 12, 4), outOfState.scoreDeadline)
    assertTrue(
      outOfState.scoreDeadlineAsPublished!!.contains("official score reports due by December 4"),
      "the deadline carries the sentence it was read from",
    )
    // A RECEIPT DEADLINE IS NOT A SITTING BOUND, and Alabama publishes only the
    // first: no sitting is excluded here, and the answer is the deadline plus
    // the reporting lag, which the service computes.
    assertNull(outOfState.lastSittingConsidered)
    assertNull(outOfState.lastSittingThroughDay)
    assertEquals(2027, outOfState.entryYear.firstCalendarYear)
    assertEquals("https://afford.ua.edu/scholarships/oos-international-freshman/", outOfState.sourceUrl)

    assertEquals(
      6000,
      outOfState.bands.single { it.score == BandScoreAxis.Range(AdmissionTest.SAT, 1200, 1250) }.awardPerYearUsd,
      "SAT 1200-1250 pays \$6,000/yr out of state",
    )
    assertEquals(
      8000,
      outOfState.bands.single { it.score == BandScoreAxis.Range(AdmissionTest.SAT, 1260, 1290) }.awardPerYearUsd,
      "ten SAT points more pays \$8,000/yr -- the slice's whole point",
    )
    // The top band publishes a KIND of award and not a figure, which is why
    // `award_per_year_usd` is nullable and `award_as_published` exists.
    val elite =
      outOfState.bands.single { it.awardName == "Presidential Elite" && (it.score as? BandScoreAxis.Range)?.test == AdmissionTest.SAT }
    assertNull(elite.awardPerYearUsd)
    assertTrue(elite.awardAsPublished!!.startsWith("Tuition+"))
  }

  /**
   * UAB, THE ROW WHOSE CUT-OFF IS WORDS: it publishes a last-considered sitting
   * that differs BY TEST and BY RESIDENCY, which no single stored period can
   * hold. Words with no date is the legal state, and the two residency rows
   * carry DIFFERENT words -- which is the corpus's proof that residency is part
   * of the key and not a display detail.
   */
  @Test
  fun `UAB's cut-off reads back as the school's words with no date`() {
    val collegeIds = seedCorpusColleges()
    load()

    val uab = collegeIds.getValue(UAB_UNIT_ID)
    val awards = awardsOf(listOf(uab))

    val inState = awardOf(awards, uab, ResidencyBasis.IN_STATE)
    val outOfState = awardOf(awards, uab, ResidencyBasis.OUT_OF_STATE)

    for (award in listOf(inState, outOfState)) {
      assertEquals(AutomaticAwardKind.SCORE_GRID, award.awardKind)
      // NOT the same fact as Alabama's: UAB publishes no receipt deadline at all.
      assertNull(award.scoreDeadline)
      assertNull(award.scoreDeadlineAsPublished)
      val cutOff = assertIs<LastSittingConsidered.InWords>(award.lastSittingConsidered)
      assertTrue(cutOff.asPublished.endsWith("will be the final scores considered for automatic merit scholarship review."))
      // NO SITTING IS EXCLUDED BY A CUT-OFF WE WILL NOT PARSE. A null here and a
      // null from an award with no cut-off at all read the same to the service,
      // and both are correct: the words are the answer.
      assertNull(award.lastSittingThroughDay)
    }

    assertTrue(
      inState.lastSittingConsidered!!.asPublished.startsWith("December ACT or SAT scores"),
      "a resident's last usable sitting is December",
    )
    assertTrue(
      outOfState.lastSittingConsidered!!.asPublished.startsWith("February ACT or March SAT scores"),
      "a non-resident's is the February ACT -- the same school, a different answer",
    )
  }

  /**
   * AUBURN, THE VERIFIED ABSENCE: we read the page on `checked_on` and it
   * publishes no automatic award. A cited absence is not our silence, and the
   * `NotCurated` answer for a school nobody read is a different answer.
   */
  @Test
  fun `Auburn reads back as a verified absence with a citation and no cells`() {
    val collegeIds = seedCorpusColleges()
    load()

    val auburn = collegeIds.getValue(AUBURN_UNIT_ID)
    val award = awardsOf(listOf(auburn)).single()

    assertEquals(ResidencyBasis.NOT_APPLICABLE, award.residencyBasis)
    assertEquals(AutomaticAwardKind.NONE_PUBLISHED, award.awardKind)
    assertTrue(award.bands.isEmpty(), "a school that publishes no automatic award publishes no cell of one")
    // A verified absence cannot publish a deadline for the award it does not
    // publish (`..._none_has_no_deadline_check`).
    assertNull(award.scoreDeadline)
    assertEquals("https://www.auburn.edu/administration/finaid/scholarship/", award.sourceUrl)
    assertEquals(LocalDate.of(2026, 9, 24), award.checkedOn)
    // D-G: July 31 of the entry year, and the staleness guard in `:db` is what
    // fails the build 90 days out from it.
    assertEquals(LocalDate.of(2027, 7, 31), award.expiresOn)
    assertEquals(
      "found",
      storedLinkAnswers().getValue(award.sourceUrl).first,
      "the citation states the evidence committed beside the row",
    )
  }

  private companion object {
    /** The slice's worked example: a score grid with a receipt deadline. */
    const val ALABAMA_UNIT_ID = 100751

    /** The row whose last-considered sitting is words with no date, and differs by residency. */
    const val UAB_UNIT_ID = 100663

    /** The verified absence. */
    const val AUBURN_UNIT_ID = 100858
  }
}
