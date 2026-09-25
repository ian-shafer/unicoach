package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.AutomaticAwardBandId
import ed.unicoach.db.models.AutomaticAwardId
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.NewAutomaticAwardBand
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.LocalDate
import java.time.Month
import java.time.OffsetDateTime
import java.time.Year
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `college_automatic_awards` / `college_automatic_award_bands`, their DAO and
 * every CHECK on the pair (RFC 239, slice `test/07/a-score-is-money`), against
 * the real database.
 *
 * THE MIGRATION IS UNDER TEST HERE TOO. `bin/test` re-inits and migrates the
 * test database before Gradle runs, so a suite that connects at all has proved
 * `create-college-automatic-awards.sql` applies to a freshly recreated
 * database -- the D-J `admission_test` DOMAIN promotion included, which two
 * cases below then exercise on the two columns it was promoted for.
 *
 * EVERY CHECK IS PROVED BY A FAILED WRITE and never by reading the DDL, which is
 * why the two sweeps go around the models through literal SQL
 * ([AdmissionTestStateProgrammeDaoTest]'s idiom): the database is what a
 * migration, a generated seed and `psql` all write through, and none of those
 * typechecks. Where the Kotlin model ALSO refuses the state, both refusals are
 * asserted -- `the models refuse` below is that half, and it is a separate test
 * because a `require` in a data class and a CHECK in a table are two different
 * guarantees with two different blast radii.
 *
 * It does NOT save and restore the corpus, unlike
 * [AdmissionTestStateProgrammeDaoTest]: this corpus is loaded by the INGEST run
 * and not by a migration (D-C), so a freshly migrated
 * `college_automatic_awards` is empty and emptying it between cases leaves the
 * database as `db-reset` left it. `external_documents` is shared with every
 * other cited corpus and is never emptied here.
 */
class AutomaticAwardsDaoTest {
  companion object {
    private lateinit var connection: Connection

    /** The school every case writes about, keyed as IPEDS keys one. */
    private const val UNIT_ID = 100663

    /** A second school, for the batched read and for "a school with no award row". */
    private const val OTHER_UNIT_ID = 100706

    /** The page every literal-SQL row cites. The document is created in [setup], so the map below can name it by url. */
    private const val SOURCE_URL = AutomaticAwardFixture.DEFAULT_SOURCE_URL

    /** A second page, for the archive citation and for the swept-status case. */
    private const val ARCHIVE_URL = "https://web.archive.org/web/2026/example-state-scholarships"

    /**
     * A LEGAL award row as SQL literals, which every bad row in this suite is
     * one override away from: a case that had to restate eighteen columns would
     * hide which column it was actually about.
     *
     * Every value here is inside every CHECK: the deadline is in the Fall-2027
     * cycle window, the expiry is after the reading day, the last-sitting trio is
     * a complete pair at the start of its own period, and each of the three
     * claim columns carries the words the schema demands beside it.
     */
    private val AWARD_COLUMNS: Map<String, String> =
      linkedMapOf(
        "college_id" to "(SELECT id FROM colleges WHERE ipeds_unit_id = $UNIT_ID)",
        "entry_year" to "2027",
        "residency_basis" to "'not_applicable'",
        "award_kind" to "'score_grid'",
        "score_requirement" to "'required'",
        "score_requirement_as_published" to "'A test score is required for scholarship consideration.'",
        "superscore_rule" to "'single_sitting_only'",
        "superscore_as_published" to "'Scholarship awards are based on a single test administration.'",
        "score_deadline" to "DATE '2026-12-04'",
        "score_deadline_as_published" to "'Official test scores must be received by December 4.'",
        "last_sitting_through" to "DATE '2026-12-01'",
        "last_sitting_precision" to "'month'",
        "last_sitting_as_published" to "'Only scores through December 2026 will be considered.'",
        "publisher" to "'Example State University'",
        "source_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')",
        "archive_document_id" to "NULL",
        "checked_on" to "DATE '2026-09-24'",
        "expires_on" to "DATE '2027-07-31'",
      )

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.createStatement().use { stmt -> stmt.execute("DELETE FROM college_automatic_awards") }
        connection.close()
      }
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var college: CollegeId = CollegeId(UUID.randomUUID())
  private var otherCollege: CollegeId = CollegeId(UUID.randomUUID())

  @BeforeEach
  fun setup() {
    connection.autoCommit = true
    // The bands go with their award through ON DELETE CASCADE; the colleges go
    // because every award row foreign-keys one and this suite creates its own.
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE colleges, college_automatic_awards CASCADE")
    }
    // `colleges.state`/`locale` key into the codebooks (0067) and
    // `residency_basis` keys into the authored money vocabulary (RFC 158 D4):
    // both are preconditions of writing a row here, and an earlier suite in the
    // same Gradle invocation may have truncated either.
    CodebookReferenceFixture.seed(session)
    MoneyVocabularyFixture.seed(session)
    // The documents the literal-SQL rows cite. Created and never deleted:
    // external_documents is shared by every cited corpus, so a suite that
    // emptied it would take their citations with it. It states its evidence like
    // every other writer since RFC 220.
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO external_documents (url, url_status, url_checked_on) VALUES " +
          "('$SOURCE_URL', 'found', DATE '2026-09-24'), ('$ARCHIVE_URL', 'found', DATE '2026-09-24') " +
          "ON CONFLICT (url) DO NOTHING",
      )
    }
    college = seedCollege(UNIT_ID, "Example State University")
    otherCollege = seedCollege(OTHER_UNIT_ID, "Second State University")
  }

  // ---------------------------------------------------------------------------
  // Every CHECK, proved by a failed write
  // ---------------------------------------------------------------------------

  @Test
  fun `every CHECK on the award table refuses its own bad row, by name`() {
    val cases =
      listOf(
        // The three own enumerations (TEXT + CHECK IN, CLAUDE.md).
        "college_automatic_awards_kind_check" to mapOf("award_kind" to "'merit_grid'"),
        "college_automatic_awards_superscore_rule_check" to mapOf("superscore_rule" to "'sometimes'"),
        "college_automatic_awards_score_requirement_check" to mapOf("score_requirement" to "'maybe'"),
        // NO PARSED CLAIM WITHOUT THE PUBLISHER'S WORDS -- all four of them,
        // because each guards a different claim and a row worth four figures can
        // be asserted on our authority through any one of them.
        "college_automatic_awards_deadline_has_words_check" to mapOf("score_deadline_as_published" to "NULL"),
        "college_automatic_awards_superscore_has_words_check" to mapOf("superscore_as_published" to "NULL"),
        "college_automatic_awards_requirement_has_words_check" to mapOf("score_requirement_as_published" to "NULL"),
        "college_automatic_awards_last_sitting_has_words_check" to mapOf("last_sitting_as_published" to "NULL"),
        // AND THE MIRROR IMAGE, because those two CHECKs are an IFF and not an
        // implication: 'unstated' WITH a quote is a sentence with no rule
        // attached, and a renderer would print the school's words under a rule
        // the row denies having read. The models say the same thing with `==`,
        // and a one-way CHECK would admit a row whose READ then blew up in a
        // data class's `init` as an unlocated IllegalArgumentException.
        "college_automatic_awards_superscore_has_words_check" to mapOf("superscore_rule" to "'unstated'"),
        "college_automatic_awards_requirement_has_words_check" to mapOf("score_requirement" to "'unstated'"),
        // The last-sitting pair is a PAIR (D23), in both directions: half of it
        // is a cut-off nobody published or a date nobody can read.
        "college_automatic_awards_last_sitting_precision_check" to mapOf("last_sitting_precision" to "NULL"),
        "college_automatic_awards_last_sitting_precision_check" to
          mapOf("last_sitting_through" to "NULL", "last_sitting_as_published" to "NULL"),
        // A month-precision date that is not the 1st puts a day we never read
        // into the column, and the service takes the period END off it.
        "college_automatic_awards_last_sitting_period_start_check" to mapOf("last_sitting_through" to "DATE '2026-12-15'"),
        // in_district and unknown are LEGAL members of residency_bases -- the FK
        // resolves -- and are refused here: a community-college tier and an
        // IPEDS headcount category are not things a university publishes a
        // freshman award against.
        "college_automatic_awards_residency_basis_check" to mapOf("residency_basis" to "'in_district'"),
        "college_automatic_awards_residency_basis_check" to mapOf("residency_basis" to "'unknown'"),
        // Value-iff-kind: a school that publishes NO automatic award cannot
        // publish a deadline for one.
        "college_automatic_awards_none_has_no_deadline_check" to mapOf("award_kind" to "'none_published'"),
        // An empty as_published is a value-shaped absence, and a quote rendered
        // from one is a claim with no words behind it.
        "college_automatic_awards_publisher_nonempty_check" to mapOf("publisher" to "'   '"),
        "college_automatic_awards_deadline_words_nonempty_check" to mapOf("score_deadline_as_published" to "''"),
        "college_automatic_awards_requirement_words_nonempty_check" to mapOf("score_requirement_as_published" to "''"),
        "college_automatic_awards_superscore_words_nonempty_check" to mapOf("superscore_as_published" to "''"),
        "college_automatic_awards_last_sitting_words_nonempty_check" to mapOf("last_sitting_as_published" to "''"),
        // An archived address that IS the page is the same link twice, and a
        // coach told to offer both would print one url as two.
        "college_automatic_awards_archive_is_not_the_source_check" to
          mapOf("archive_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')"),
        // The boundary, not a comfortable failure: a row that expires the day it
        // was read is never live for a moment.
        "college_automatic_awards_expiry_after_checked_check" to mapOf("expires_on" to "DATE '2026-09-24'"),
        // A deadline belongs to its own cycle, and BOTH ENDS of the window are
        // executed here because the upper end was widened once and a later run
        // could narrow it back by accident.
        //
        // The LOWER bound is the half the CHECK earns its keep on: 2026-06-30
        // under a Fall-2027 key is LAST cycle's date, which is the one mistake
        // that makes this table worse than no table, because a family acts on it.
        "college_automatic_awards_deadline_in_cycle_check" to mapOf("score_deadline" to "DATE '2026-06-30'"),
        // The UPPER bound is 30 September of the entry year -- the term start --
        // so 1 October is past every freshman award. The legal end of that window
        // (West Virginia's published 1 August re-evaluation date) is asserted as
        // an ACCEPTED row in its own test below, because an accepted row lands
        // and would then occupy this sweep's natural key.
        "college_automatic_awards_deadline_in_cycle_check" to mapOf("score_deadline" to "DATE '2027-10-01'"),
      )

    // Every case is run and every mismatch reported, so one failing CHECK does
    // not hide the twenty behind it.
    val mismatches =
      cases.mapNotNull { (expected, overrides) ->
        val actual = violatedConstraint("college_automatic_awards", AWARD_COLUMNS + overrides)
        if (actual == expected) null else "overrides $overrides expected [$expected], got [$actual]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the row it exists to refuse")
    assertEquals(0, countRows("college_automatic_awards"), "no refused row may be left behind")
  }

  @Test
  fun `every CHECK on the band table refuses its own bad cell, by name`() {
    val award = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college)).id
    val legal = bandColumns(award)

    val cases =
      listOf(
        // A score with no test is a band on an unnamed scale; a test with no
        // score is a tier with no entry condition. One CHECK, both directions.
        "college_automatic_award_bands_score_iff_test_check" to mapOf("test" to "NULL"),
        "college_automatic_award_bands_score_iff_test_check" to mapOf("score_low" to "NULL", "score_high" to "NULL"),
        "college_automatic_award_bands_score_iff_test_check" to mapOf("score_high" to "NULL"),
        "college_automatic_award_bands_score_ordered_check" to mapOf("score_low" to "1260", "score_high" to "1250"),
        "college_automatic_award_bands_gpa_ordered_check" to mapOf("gpa_low" to "3.50", "gpa_high" to "3.00"),
        // A GPA outside 0..5 is not a GPA on any published scale.
        "college_automatic_award_bands_gpa_range_check" to mapOf("gpa_low" to "5.50"),
        "college_automatic_award_bands_gpa_range_check" to mapOf("gpa_high" to "9.99"),
        // Zero is not an award. Iowa State prints '--' in such a cell, and the
        // corpus drops the row rather than inventing a $0 band.
        "college_automatic_award_bands_award_positive_check" to mapOf("award_per_year_usd" to "0"),
        "college_automatic_award_bands_award_positive_check" to mapOf("award_per_year_usd" to "-1"),
        // A named band with neither an amount nor the school's words is a
        // heading over nothing.
        "college_automatic_award_bands_award_has_value_check" to
          mapOf("award_per_year_usd" to "NULL", "award_as_published" to "NULL"),
        "college_automatic_award_bands_award_words_nonempty_check" to mapOf("award_as_published" to "''"),
        "college_automatic_award_bands_award_name_nonempty_check" to mapOf("award_name" to "'  '"),
        // D-J: the column carries the `admission_test` DOMAIN, so a third test
        // value is refused by the domain's own constraint and not by a CHECK
        // this table declares.
        "admission_test_check" to mapOf("test" to "'psat'"),
      )

    val mismatches =
      cases.mapNotNull { (expected, overrides) ->
        val actual = violatedConstraint("college_automatic_award_bands", legal + overrides)
        if (actual == expected) null else "overrides $overrides expected [$expected], got [$actual]"
      }
    assertEquals(emptyList<String>(), mismatches, "a CHECK did not refuse the cell it exists to refuse")
    assertEquals(0, countRows("college_automatic_award_bands"), "no refused cell may be left behind")
  }

  /**
   * The states the models refuse one layer earlier, asserted BESIDE the
   * database's own refusal rather than instead of it.
   *
   * Two guarantees, not one: the CHECK is what a migration, a generated seed and
   * `psql` write through, and the `require` is what a renderer reading a
   * reconstructed row gets. A band built in a fixture never reaches a CHECK until
   * it is written, which is the case this half covers.
   *
   * The `New…` types carry NO `require` -- by design, because the loader's job is
   * to hand the database a row and let the located write error name the cell --
   * so every case here is the READ model, and every literal-SQL row in the two
   * sweeps above is a state Kotlin cannot hold at all.
   *
   * `..._score_iff_test_check` HAS NO CASE HERE ANY MORE, and that is a
   * strengthening and not a gap: the score axis is one [BandScoreAxis] value, so
   * a band with half a score range cannot be built to be refused. The test below
   * pins that shape, and the sweep above still asserts the CHECK itself.
   */
  @Test
  fun `the read models refuse the rows the schema refuses, one layer earlier`() {
    val refusals =
      listOf<Pair<String, () -> Any>>(
        // The ordering rule is the half of the score axis that a VALUE can still
        // get wrong, so it is the half [BandScoreAxis.Range] states.
        "college_automatic_award_bands_score_ordered_check" to
          { BandScoreAxis.Range(test = AdmissionTest.SAT, low = 1260, high = 1250) },
        "college_automatic_award_bands_award_has_value_check" to
          { readBand(awardPerYearUsd = null, awardAsPublished = null) },
        "college_automatic_awards_requirement_has_words_check" to
          { readAward(scoreRequirement = ScoreRequirement.REQUIRED, scoreRequirementAsPublished = null) },
        "college_automatic_awards_superscore_has_words_check" to
          { readAward(superscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY, superscoreAsPublished = null) },
        "college_automatic_awards_deadline_has_words_check" to
          { readAward(scoreDeadline = LocalDate.of(2026, 12, 4), scoreDeadlineAsPublished = null) },
        "college_automatic_awards_none_has_no_deadline_check" to
          {
            readAward(
              awardKind = AutomaticAwardKind.NONE_PUBLISHED,
              scoreDeadline = LocalDate.of(2026, 12, 4),
              scoreDeadlineAsPublished = "Official test scores must be received by December 4.",
            )
          },
      )

    // Each refusal NAMES the constraint it mirrors, so a reader who hits one in
    // a log can find the other half of the guarantee.
    val unnamed =
      refusals.mapNotNull { (constraint, build) ->
        val message = assertFailsWith<IllegalArgumentException> { build() }.message
        if (message?.contains(constraint) == true) null else "a refusal mirroring [$constraint] said [$message]"
      }
    assertEquals(emptyList<String>(), unnamed, "a model refusal must name the CHECK it mirrors")
  }

  /**
   * The score axis is a CLOSED PAIR, which is what replaced the score-iff-test
   * `require`: the states that `require` refused -- a score on no scale, a test
   * with no entry condition -- have no representation to be refused.
   *
   * The published NO-SCORE row is a MEMBER here and not an absence. It is the
   * tier a test-optional applicant lands in, and a reader that had to infer it
   * from a null test would be re-deriving the most meaningful cell in the grid.
   */
  @Test
  fun `the score axis is a closed pair, so half a score range has no representation`() {
    // ONE `when` WITH NO `else`, which is the assertion: it COMPILES only while
    // the axis has exactly these two members, and every arm reads fields the
    // type guarantees are there -- no `!!` and no null-guard in sight.
    val described =
      listOf(BandScoreAxis.NoScore, BandScoreAxis.Range(test = AdmissionTest.ACT, low = 30, high = 31)).map { axis ->
        when (axis) {
          is BandScoreAxis.NoScore -> "the published no-score row"
          is BandScoreAxis.Range -> "${axis.test.value} ${axis.low}-${axis.high}"
        }
      }
    assertEquals(listOf("the published no-score row", "act 30-31"), described)

    // A band on the no-score row has no range to contradict its test: there is
    // no field there to disagree with anything.
    assertEquals(BandScoreAxis.NoScore, readBand(score = BandScoreAxis.NoScore).score)

    // ONE SCORE IS A LEGAL CELL, and the boundary is asserted because the
    // `require` guards the other side of it: Louisiana Tech publishes two GPA
    // rows at the SAME ACT 32, so `low == high` is a cell of the committed
    // corpus and not a degenerate case.
    assertEquals(32, BandScoreAxis.Range(test = AdmissionTest.ACT, low = 32, high = 32).high)
  }

  // ---------------------------------------------------------------------------
  // The two natural keys
  // ---------------------------------------------------------------------------

  @Test
  fun `a second award row for one school, cycle and residency upserts instead of duplicating`() {
    val first = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college)).id
    val second =
      AutomaticAwardFixture
        .seed(
          session,
          AutomaticAwardFixture.award(college, publisher = "Example State University, Office of Admissions"),
        ).id

    // A corrected re-read of the same school's same cycle is a CORRECTION of
    // that row, never a second contradictory one -- two grids for one school and
    // one residency is two answers to one question.
    assertEquals(first, second, "the second write must be a correction of the same row")
    assertEquals(1, countRows("college_automatic_awards"))
    val stored = AutomaticAwardsDao.listByColleges(session, listOf(college)).getOrThrow().single()
    assertEquals("Example State University, Office of Admissions", stored.publisher)

    // The SAME school, cycle and grid for the OTHER residency is a different
    // row: Iowa State gates its in-state award on a score and pays the
    // out-of-state one on GPA alone, so residency is part of the key.
    AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college, residencyBasis = ResidencyBasis.IN_STATE))
    assertEquals(2, countRows("college_automatic_awards"), "a second residency is a second row")
  }

  @Test
  fun `a second band for one cell upserts, the NULL test and NULL gpa cases included`() {
    val award = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college)).id

    // The published NO-SCORE row (test NULL) and a band with no GPA axis
    // (gpa_low NULL). A plain unique index passes every other case in this file
    // and lets each of these land TWICE, because NULL <> NULL -- and the grid
    // would then print two no-score tiers paying different money.
    val cells =
      listOf(
        AutomaticAwardFixture.band(test = null, scoreLow = null, scoreHigh = null, awardPerYearUsd = 1000),
        AutomaticAwardFixture.band(test = AdmissionTest.SAT, scoreLow = 1200, scoreHigh = 1250, awardPerYearUsd = 6000),
      )
    val firstIds = cells.map { writtenBand(award, it).id }
    val secondIds = cells.map { cell -> writtenBand(award, cell.copy(awardPerYearUsd = cell.awardPerYearUsd!! + 500)).id }

    assertEquals(firstIds, secondIds, "NULLS NOT DISTINCT: each cell must collide with itself, not land twice")
    assertEquals(2, countRows("college_automatic_award_bands"))
    val stored =
      AutomaticAwardsDao
        .listByColleges(session, listOf(college))
        .getOrThrow()
        .single()
        .bands
    assertEquals(listOf(1500, 6500), stored.map { it.awardPerYearUsd }, "the second write corrects each cell in place")

    // A DIFFERENT cell of the same grid is a different row, and the award NAME
    // is part of the key because one school publishes several named awards with
    // overlapping bands.
    writtenBand(award, AutomaticAwardFixture.band(awardName = "Crimson Legends", scoreLow = 1200, scoreHigh = 1250))
    assertEquals(3, countRows("college_automatic_award_bands"), "a second named award over the same band is a second row")
  }

  // ---------------------------------------------------------------------------
  // The read
  // ---------------------------------------------------------------------------

  @Test
  fun `listByColleges answers in two statements whatever the number of colleges`() {
    AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college), listOf(AutomaticAwardFixture.band()))
    AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(otherCollege), listOf(AutomaticAwardFixture.band()))

    val counting = CountingSqlSession(session)
    val rows = AutomaticAwardsDao.listByColleges(counting, listOf(college, otherCollege)).getOrThrow()

    assertEquals(2, rows.size)
    // TWO statements: one for the awards and one for their bands. Nothing here
    // runs a statement per college or per award.
    assertEquals(
      2,
      counting.prepared.size,
      "the read must be two statements whatever the number of colleges: [${counting.prepared}]",
    )

    // An empty request asks NOTHING: `IN ()` is not valid SQL, and a query for
    // nothing has nothing to answer.
    val empty = CountingSqlSession(session)
    assertEquals(emptyList(), AutomaticAwardsDao.listByColleges(empty, emptyList()).getOrThrow())
    assertEquals(emptyList(), empty.prepared, "an empty request must not reach the database")
  }

  @Test
  fun `bands land under their own award, and the published no-score row leads the grid`() {
    AutomaticAwardFixture.seed(
      session,
      AutomaticAwardFixture.award(college),
      listOf(
        AutomaticAwardFixture.band(test = AdmissionTest.SAT, scoreLow = 1200, scoreHigh = 1250, awardPerYearUsd = 6000),
        AutomaticAwardFixture.band(test = AdmissionTest.ACT, scoreLow = 30, scoreHigh = 31, awardPerYearUsd = 7000),
        AutomaticAwardFixture.band(test = null, scoreLow = null, scoreHigh = null, awardPerYearUsd = 1000),
      ),
    )
    AutomaticAwardFixture.seed(
      session,
      AutomaticAwardFixture.award(otherCollege),
      listOf(AutomaticAwardFixture.band(test = AdmissionTest.SAT, scoreLow = 1400, scoreHigh = 1600, awardPerYearUsd = 12000)),
    )

    val rows = AutomaticAwardsDao.listByColleges(session, listOf(college, otherCollege)).getOrThrow()

    val mine = rows.single { it.collegeId == college }
    val theirs = rows.single { it.collegeId == otherCollege }
    assertEquals(3, mine.bands.size)
    assertEquals(1, theirs.bands.size, "a band must hang off its own award, never off another school's")
    assertTrue(mine.bands.all { it.awardId == mine.id }, "every band must carry its own award's id")
    // NULLS FIRST is explicit and load-bearing: the no-score row is the tier a
    // test-optional applicant lands in, and PostgreSQL's ascending default would
    // put it last -- after the bands it is the baseline for.
    assertEquals(
      listOf(null, AdmissionTest.ACT, AdmissionTest.SAT),
      mine.bands.map { (it.score as? BandScoreAxis.Range)?.test },
      "the published no-score row leads the grid",
    )
    assertTrue(
      mine.bands.first().score is BandScoreAxis.NoScore,
      "the leading row must come back as the no-score MEMBER, not as a cell whose test went missing",
    )
    assertEquals(1000, mine.bands.first().awardPerYearUsd, "the no-score tier is what makes 'a score is worth more' sayable")
  }

  @Test
  fun `a school with no award row is simply absent`() {
    AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college))

    val rows = AutomaticAwardsDao.listByColleges(session, listOf(college, otherCollege)).getOrThrow()

    // Absent, not an empty grid: "we never read this school" and "this school
    // publishes nothing" send a family to two different places, and only the
    // second is a row (`none_published`).
    assertEquals(listOf(college), rows.map { it.collegeId })
  }

  @Test
  fun `a none_published award that somehow has bands is refused by name`() {
    val award =
      AutomaticAwardFixture
        .seed(
          session,
          AutomaticAwardFixture.award(
            college,
            awardKind = AutomaticAwardKind.NONE_PUBLISHED,
            scoreDeadline = null,
            scoreDeadlineAsPublished = null,
          ),
        ).id
    // Planted past the DAO on purpose: [AutomaticAwardsDao.upsertBand] is the
    // only door that writes a band and it refuses this pair, so the read-side
    // half of the guarantee can only be reached from raw SQL. PostgreSQL cannot
    // express it as a cross-table CHECK without a trigger (D28).
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO college_automatic_award_bands (award_id, award_name, test, score_low, score_high, award_per_year_usd) " +
          "VALUES ('${award.value}', 'Presidential Scholarship', 'sat', 1200, 1250, 6000)",
      )
    }

    val failure =
      assertFailsWith<CorruptPersistedValueException> {
        AutomaticAwardsDao.listByColleges(session, listOf(college)).getOrThrow()
      }

    // A grid under a school we recorded as publishing nothing is a contradiction
    // a renderer would resolve by showing one side of it.
    assertTrue(
      failure.value.contains("none_published") && failure.value.contains("1"),
      "the refusal must say what it found: [${failure.value}]",
    )
    assertTrue(
      failure.location?.contains("college_automatic_awards") == true &&
        failure.location?.contains(award.value.toString()) == true,
      "the refusal must locate the row: [${failure.location}]",
    )
  }

  /**
   * The THIRD pair is deliberately NOT an iff, and this is the test that says so
   * out loud, so nobody "tidies" it into one.
   *
   * `college_automatic_awards_deadline_has_words_check` is a one-way implication
   * -- a stored date carries the sentence it was read from -- because WORDS WITH
   * NO DATE is a legal and meaningful state here: a school published a receipt
   * deadline we will not parse, and the coach quotes it instead of computing on
   * it. The other two pairs (`superscore`, `requirement`) ARE iffs, asserted in
   * both directions in the sweep above, because their parsed half is an
   * enumeration with an `unstated` member rather than a nullable date.
   */
  @Test
  fun `a deadline sentence with no parsed date is a legal row`() {
    val stored =
      AutomaticAwardFixture.seed(
        session,
        AutomaticAwardFixture.award(
          college,
          scoreDeadline = null,
          scoreDeadlineAsPublished = "Scores are due with the application; check with your admissions counsellor.",
        ),
      )

    assertNull(stored.scoreDeadline, "nothing may complete a published sentence with a date we did not read")
    assertEquals(
      "Scores are due with the application; check with your admissions counsellor.",
      stored.scoreDeadlineAsPublished,
      "the school's words are the answer when the date is unparseable",
    )
  }

  /**
   * The UPPER end of the deadline window, as a row that is ACCEPTED -- the half a
   * refusal sweep cannot state.
   *
   * West Virginia University publishes, for a Fall-2027 entrant: "After you're
   * admitted, you may submit an updated GPA or test score through August 1."
   * That is a real published post-admission re-evaluation window, and it is why
   * `college_automatic_awards_deadline_in_cycle_check` closes at 30 September of
   * the ENTRY year rather than the June before it. A June bound refused two
   * committed rows, and retyping them to June would have stored a date WVU never
   * published.
   *
   * The two REFUSALS that bound this on either side -- 2026-06-30 (last cycle's
   * date under this cycle's key) and 2027-10-01 (past term start) -- are in the
   * sweep above, so all three points of the window are executed.
   */
  @Test
  fun `a post-admission deadline in the August before term start is a legal row`() {
    val stored =
      AutomaticAwardFixture.seed(
        session,
        AutomaticAwardFixture.award(
          college,
          scoreDeadline = LocalDate.of(2027, 8, 1),
          scoreDeadlineAsPublished = "After you're admitted, you may submit an updated GPA or test score through August 1.",
        ),
      )

    assertEquals(LocalDate.of(2027, 8, 1), stored.scoreDeadline, "a published August re-evaluation date must be storable")
    assertEquals(
      "After you're admitted, you may submit an updated GPA or test score through August 1.",
      stored.scoreDeadlineAsPublished,
    )
  }

  @Test
  fun `a published GPA bound with three decimals round-trips exactly, never rounded into the next band`() {
    // Louisiana Tech publishes 3.0-3.749 at $8,000 and 3.75-4.0 at $8,500 for
    // the SAME 32 ACT. NUMERIC(3,2) would not REFUSE 3.749 -- it would round it
    // to 3.75, merging the two tiers so that a 3.75 student matches two cells
    // paying different money, with the answer decided by row order. The column
    // is NUMERIC(4,3) for exactly this row, and this assertion is the reason.
    val stored =
      AutomaticAwardFixture.seed(
        session,
        AutomaticAwardFixture.award(college),
        listOf(
          AutomaticAwardFixture.band(
            awardName = "Deans' Scholarship",
            test = AdmissionTest.ACT,
            scoreLow = 32,
            scoreHigh = 32,
            gpaLow = BigDecimal("3.0"),
            gpaHigh = BigDecimal("3.749"),
            awardPerYearUsd = 8000,
          ),
          AutomaticAwardFixture.band(
            awardName = "Deans' Scholarship",
            test = AdmissionTest.ACT,
            scoreLow = 32,
            scoreHigh = 32,
            gpaLow = BigDecimal("3.75"),
            gpaHigh = BigDecimal("4.0"),
            awardPerYearUsd = 8500,
          ),
        ),
      )

    val lower = stored.bands.single { it.awardPerYearUsd == 8000 }
    val upper = stored.bands.single { it.awardPerYearUsd == 8500 }
    // compareTo and not equals: 3.75 and the stored 3.750 are the SAME NUMBER at
    // two scales, and asserting `equals` here would be asserting the column's
    // scale rather than the school's bound.
    assertEquals(0, BigDecimal("3.749").compareTo(lower.gpaHigh!!), "the published bound came back as [${lower.gpaHigh}]")
    assertEquals(0, BigDecimal("3.75").compareTo(upper.gpaLow!!), "the next tier's floor came back as [${upper.gpaLow}]")
    assertTrue(
      lower.gpaHigh!! < upper.gpaLow!!,
      "the two tiers must not overlap: a 3.75 student would match both an [8000] and an [8500] cell",
    )
  }

  @Test
  fun `the last sitting comes back at its own precision, and its last DAY is derived`() {
    // The three precisions and the words-only arm, round-tripped: the column
    // stores the period START and the model derives the period END, because
    // "through December 2026" admits every December sitting.
    val cases =
      listOf<Triple<LastSittingConsidered?, LocalDate?, String>>(
        Triple(
          LastSittingConsidered.Through(PartialDate.YearAndMonth(Year.of(2026), Month.DECEMBER), "through December 2026"),
          LocalDate.of(2026, 12, 31),
          "a month reads through its last day",
        ),
        Triple(
          LastSittingConsidered.Through(PartialDate.YearOnly(Year.of(2026)), "scores from 2026 or earlier"),
          LocalDate.of(2026, 12, 31),
          "a year reads through 31 December",
        ),
        Triple(
          LastSittingConsidered.Through(PartialDate.FullDate(LocalDate.of(2027, 5, 15)), "scores taken on or before May 15, 2027"),
          LocalDate.of(2027, 5, 15),
          "a full date reads through itself",
        ),
        Triple(
          LastSittingConsidered.InWords("February ACT or March SAT scores will be the final scores considered."),
          null,
          "a words-only cut-off excludes no sitting and is quoted instead",
        ),
        Triple(null, null, "a school that published no cut-off has none"),
      )

    cases.forEach { (cutOff, expectedDay, why) ->
      val stored =
        AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college, lastSittingConsidered = cutOff))
      assertEquals(cutOff, stored.lastSittingConsidered, "the cut-off must come back as it went in: $why")
      assertEquals(expectedDay, stored.lastSittingThroughDay, why)
    }
  }

  // ---------------------------------------------------------------------------
  // The write surface the ingest loader uses
  // ---------------------------------------------------------------------------

  @Test
  fun `upsertAward reports inserted, then changed, then unchanged, and returns the id every time`() {
    val row = AutomaticAwardFixture.award(college)

    val inserted = AutomaticAwardsDao.upsertAward(session, row).getOrThrow()
    assertEquals(UpsertOutcome.INSERTED, inserted.outcome)

    val changed = AutomaticAwardsDao.upsertAward(session, row.copy(publisher = "Example State University Admissions")).getOrThrow()
    assertEquals(UpsertOutcome.CHANGED, changed.outcome)

    val writtenAt = updatedAtOf(changed.id)
    val unchanged = AutomaticAwardsDao.upsertAward(session, row.copy(publisher = "Example State University Admissions")).getOrThrow()
    assertEquals(UpsertOutcome.UNCHANGED, unchanged.outcome, "a re-ingest of an unchanged row must report that it wrote nothing")

    // THE ID COMES BACK WHATEVER THE OUTCOME. The bands hang off the award, so a
    // loader that learned only "unchanged" would have nothing to hang them on --
    // which is why this is not `upsertDetectingChange`.
    assertEquals(inserted.id, changed.id)
    assertEquals(inserted.id, unchanged.id, "the suppressed write must still answer with the row's id")
    // And the suppressed write really is suppressed: `updated_at` is what a
    // run summary and a staleness sweep both read.
    assertEquals(writtenAt, updatedAtOf(changed.id), "an unchanged re-write must not advance updated_at")
    assertEquals(1, countRows("college_automatic_awards"))
  }

  @Test
  fun `upsertBand reports the same three outcomes and returns the id every time`() {
    val award = AutomaticAwardsDao.upsertAward(session, AutomaticAwardFixture.award(college)).getOrThrow().id
    val cell = AutomaticAwardFixture.band()

    val inserted = writtenBand(award, cell)
    val changed = writtenBand(award, cell.copy(awardPerYearUsd = 8000))
    val unchanged = writtenBand(award, cell.copy(awardPerYearUsd = 8000))

    assertEquals(
      listOf(UpsertOutcome.INSERTED, UpsertOutcome.CHANGED, UpsertOutcome.UNCHANGED),
      listOf(inserted.outcome, changed.outcome, unchanged.outcome),
    )
    assertEquals(inserted.id, changed.id)
    assertEquals(inserted.id, unchanged.id, "the suppressed write must still answer with the cell's id, so a prune can keep it")
    assertEquals(1, countRows("college_automatic_award_bands"))
  }

  @Test
  fun `upsertBand answers NoGridPublished under a none_published award, and FAILS under an award that does not exist`() {
    val nonePublished =
      AutomaticAwardsDao
        .upsertAward(
          session,
          AutomaticAwardFixture.award(
            college,
            awardKind = AutomaticAwardKind.NONE_PUBLISHED,
            scoreDeadline = null,
            scoreDeadlineAsPublished = null,
          ),
        ).getOrThrow()
        .id

    // ZERO ROWS IS NOT "UNCHANGED" HERE, and the two reasons for it are not one
    // answer. "This school publishes no automatic award" is a state the corpus
    // is allowed to be in, so it arrives as an ARM a caller can route on -- and
    // the arm CARRIES THE AWARD ID, which is stronger than the sentence it
    // replaced: a caller reads the id instead of string-matching prose.
    val underNone =
      assertIs<BandWriteOutcome.NoGridPublished>(
        AutomaticAwardsDao.upsertBand(session, nonePublished, AutomaticAwardFixture.band()).getOrThrow(),
        "a band under a none_published award is a domain state, not a failed write",
      )
    assertEquals(nonePublished, underNone.awardId, "the arm must name the award that publishes no grid")

    // A silent skip is still how a school ends up quoted for a grid it does not
    // publish, so the COMPOSED door -- which was handed a whole grid -- refuses
    // rather than answering with the award and one cell quietly missing.
    val composed =
      assertFailsWith<NoGridForComposedBandException> {
        AutomaticAwardsDao
          .upsert(
            session,
            AutomaticAwardFixture.award(
              college,
              awardKind = AutomaticAwardKind.NONE_PUBLISHED,
              scoreDeadline = null,
              scoreDeadlineAsPublished = null,
            ),
            listOf(AutomaticAwardFixture.band()),
          ).getOrThrow()
      }
    assertTrue(
      composed.message!!.contains(AutomaticAwardFixture.DEFAULT_AWARD_NAME) &&
        composed.message!!.contains("none_published"),
      "the refusal must name the cell it would have dropped and the reason: [${composed.message}]",
    )
    // AND AS TYPED PROPERTIES, not only in the sentence: a caller that must tell
    // this refusal from a constraint fault switches on the type and reads the
    // outcome, never the message.
    assertEquals(
      AutomaticAwardFixture.DEFAULT_AWARD_NAME,
      composed.band.awardName,
      "the refusal carries the cell it would have dropped",
    )

    // AN AWARD THAT DOES NOT EXIST IS STILL A FAILURE: a referential fault of
    // the caller's own transaction, not a state of the corpus, and it is LOCATED
    // by the award id it could not find.
    val absent = AutomaticAwardId(UUID.randomUUID())
    val underAbsent =
      assertFailsWith<NotFoundException> { AutomaticAwardsDao.upsertBand(session, absent, AutomaticAwardFixture.band()).getOrThrow() }
    assertTrue(
      underAbsent.message!!.contains(absent.value.toString()),
      "the refusal must name the award id it could not find: [${underAbsent.message}]",
    )
    assertTrue(
      !underAbsent.message!!.contains("none_published"),
      "an absence must not be reported as a school that publishes nothing: [${underAbsent.message}]",
    )
    assertEquals(0, countRows("college_automatic_award_bands"))
  }

  @Test
  fun `deleteBandsExcept prunes exactly the bands not kept, and an empty keep drops all`() {
    val award = AutomaticAwardsDao.upsertAward(session, AutomaticAwardFixture.award(college)).getOrThrow().id
    val kept = writtenBand(award, AutomaticAwardFixture.band(scoreLow = 1200, scoreHigh = 1250)).id
    val dropped = writtenBand(award, AutomaticAwardFixture.band(scoreLow = 1260, scoreHigh = 1290)).id
    // Another school's grid, which no prune of THIS award may touch.
    val otherAward = AutomaticAwardsDao.upsertAward(session, AutomaticAwardFixture.award(otherCollege)).getOrThrow().id
    writtenBand(otherAward, AutomaticAwardFixture.band())

    assertEquals(1, AutomaticAwardsDao.deleteBandsExcept(session, award, listOf(kept)).getOrThrow())
    assertEquals(
      listOf(kept),
      AutomaticAwardsDao
        .listByColleges(session, listOf(college))
        .getOrThrow()
        .single()
        .bands
        .map { it.id },
    )
    assertEquals(
      1,
      AutomaticAwardsDao
        .listByColleges(session, listOf(otherCollege))
        .getOrThrow()
        .single()
        .bands.size,
    )

    // AN EMPTY `keep` DROPS EVERY BAND, deliberately: that is a grid emptied by
    // a re-read. It is not how a none_published school is loaded -- upsertBand
    // refuses a band under one before this is ever reached.
    assertEquals(1, AutomaticAwardsDao.deleteBandsExcept(session, award, emptyList()).getOrThrow())
    assertEquals(
      emptyList(),
      AutomaticAwardsDao
        .listByColleges(session, listOf(college))
        .getOrThrow()
        .single()
        .bands,
    )
    assertEquals(
      1,
      AutomaticAwardsDao
        .listByColleges(session, listOf(otherCollege))
        .getOrThrow()
        .single()
        .bands.size,
      "an empty keep drops THIS award's bands and nobody else's",
    )
    assertNotNull(dropped, "the pruned id is named so a failure above can be read")
  }

  @Test
  fun `a re-read that drops a band leaves no stale cell behind`() {
    val full =
      listOf(
        AutomaticAwardFixture.band(scoreLow = 1200, scoreHigh = 1250, awardPerYearUsd = 6000),
        AutomaticAwardFixture.band(scoreLow = 1260, scoreHigh = 1290, awardPerYearUsd = 8000),
        AutomaticAwardFixture.band(scoreLow = 1300, scoreHigh = 1600, awardPerYearUsd = 10000),
      )
    AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college), full)

    // The school stopped paying its top band. A natural-key upsert alone is
    // idempotent for an UNCHANGED corpus and NOT for a corrected one: the old
    // cell would stay, and the coach would quote a school paying money it
    // stopped paying.
    val reRead = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college), full.dropLast(1))

    assertEquals(
      listOf(6000, 8000),
      reRead.bands.map { it.awardPerYearUsd },
      "a re-read that drops a band must leave no stale cell behind",
    )
    assertEquals(2, countRows("college_automatic_award_bands"))
  }

  @Test
  fun `deleteAwardsExcept drops exactly the awards not kept, and their bands go with them`() {
    // Three award rows over two schools: the two residencies of one school are
    // two rows, so the prune has to drop ONE of them and leave the other.
    val dropped =
      AutomaticAwardFixture
        .seed(
          session,
          AutomaticAwardFixture.award(college),
          listOf(
            AutomaticAwardFixture.band(scoreLow = 1200, scoreHigh = 1250, awardPerYearUsd = 6000),
            AutomaticAwardFixture.band(scoreLow = 1260, scoreHigh = 1290, awardPerYearUsd = 8000),
          ),
        ).id
    val keptSameSchool =
      AutomaticAwardFixture
        .seed(
          session,
          AutomaticAwardFixture.award(college, residencyBasis = ResidencyBasis.IN_STATE),
          listOf(AutomaticAwardFixture.band(awardPerYearUsd = 9000)),
        ).id
    val keptOtherSchool =
      AutomaticAwardFixture
        .seed(session, AutomaticAwardFixture.award(otherCollege), listOf(AutomaticAwardFixture.band()))
        .id
    assertEquals(4, countRows("college_automatic_award_bands"), "the grid this prune is about must exist first")

    // A school (or a residency) the re-read no longer carries is a school we no
    // longer publish a grid for: a mis-keyed unit id CORRECTED leaves the old
    // row priced until expires_on, which is the defect this prune closes.
    assertEquals(
      1,
      AutomaticAwardsDao.deleteAwardsExcept(session, listOf(keptSameSchool, keptOtherSchool)).getOrThrow(),
      "exactly the awards not kept are dropped",
    )
    assertEquals(
      listOf(keptOtherSchool, keptSameSchool).sortedBy { it.value },
      AutomaticAwardsDao
        .listByColleges(session, listOf(college, otherCollege))
        .getOrThrow()
        .map { it.id }
        .sortedBy { it.value },
    )

    // THE BANDS GO WITH THE AWARD, through the bands table's ON DELETE CASCADE:
    // a cell that outlived its award would be money under no school at all.
    assertEquals(0, bandsUnder(dropped), "the dropped award's bands must go with it")
    assertEquals(1, bandsUnder(keptSameSchool), "a kept award keeps its whole grid")
    assertEquals(1, bandsUnder(keptOtherSchool), "a kept award keeps its whole grid")
    assertEquals(2, countRows("college_automatic_award_bands"))
  }

  @Test
  fun `deleteAwardsExcept REFUSES an empty keep instead of emptying the table`() {
    val award = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college), listOf(AutomaticAwardFixture.band())).id

    // The opposite of deleteBandsExcept, deliberately: an emptied GRID is a
    // school that stopped publishing cells, and an emptied TABLE is never a
    // thing a corpus says. A caller that read no award at all has a defect to
    // name, and obeying it here would drop every school at once.
    // A NAMED TYPE, because the ingest loader routes on it: it reports its own
    // located `EmptyAwardCorpus` defect for THIS refusal and must not report it
    // for the next reason this door learns to refuse.
    val refusal =
      assertFailsWith<EmptyAwardPruneException> {
        AutomaticAwardsDao.deleteAwardsExcept(session, emptyList()).getOrThrow()
      }
    assertTrue(
      refusal.message!!.contains("college_automatic_awards"),
      "the refusal must name the table it declined to empty: [${refusal.message}]",
    )

    // A REFUSAL AND NOT A SILENT NO-OP -- and nothing was written on the way to
    // it, so the run that provoked it can be fixed and re-run.
    assertEquals(1, countRows("college_automatic_awards"))
    assertEquals(1, bandsUnder(award))
  }

  // ---------------------------------------------------------------------------
  // The citation
  // ---------------------------------------------------------------------------

  @Test
  fun `both citation urls come back through the external_documents join, and an archive is optional`() {
    val cited = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college, archiveUrl = ARCHIVE_URL))
    val plain = AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(otherCollege))

    // The citation survives the move into external_documents (RFC 228): there is
    // exactly one url column in this schema and it is external_documents.url.
    assertEquals(AutomaticAwardFixture.DEFAULT_SOURCE_URL, cited.sourceUrl)
    assertEquals(ARCHIVE_URL, cited.archiveUrl)
    assertNull(plain.archiveUrl, "a row that needed no archive must read back as null, not as its own source")
    assertEquals(AutomaticAwardFixture.DEFAULT_SOURCE_URL, plain.sourceUrl)
  }

  @Test
  fun `citing a page does not overwrite a swept link status`() {
    // bin/check-link asked and got a 404. That verdict is the corpus's evidence,
    // and a writer that cites the page knows its address and knows NOTHING about
    // whether it is still served (RFC 182): the check that never ran must never
    // look like one that passed.
    val swept = "https://example-state.edu/admissions/scholarships-moved"
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO external_documents (url, url_status, url_checked_on) " +
          "VALUES ('$swept', 'not_found', DATE '2026-09-20') " +
          "ON CONFLICT (url) DO UPDATE SET url_status = 'not_found', url_checked_on = DATE '2026-09-20'",
      )
    }

    AutomaticAwardFixture.seed(session, AutomaticAwardFixture.award(college, sourceUrl = swept))

    assertEquals(
      "not_found" to LocalDate.of(2026, 9, 20),
      linkStatusOf(swept),
      "citing a page must not overwrite the sweep's verdict with 'we never asked'",
    )
  }

  // ---------------------------------------------------------------------------
  // D-J: the promotion is real
  // ---------------------------------------------------------------------------

  @Test
  fun `the admission_test domain refuses a third test value on both landed columns`() {
    // The two columns the migration promoted, each proved by a FAILED WRITE. The
    // per-table CHECKs are gone, so if the domain did not carry the vocabulary
    // these writes would succeed and the calendar and the stored scores would
    // admit a test we hold nothing about.
    assertEquals(
      "admission_test_check",
      violatedConstraint(
        "admission_test_administrations",
        linkedMapOf(
          "test" to "'psat'",
          "cycle" to "'2099-00'",
          "test_date" to "DATE '2099-10-03'",
          "registration_deadline" to "DATE '2099-09-05'",
          // The calendar cites a DOCUMENT and not a url (RFC 228): there is one
          // url column in this schema and it is external_documents.url.
          "dates_document_id" to "(SELECT id FROM external_documents WHERE url = '$SOURCE_URL')",
          "checked_on" to "DATE '2026-09-24'",
          "expires_on" to "DATE '2027-07-31'",
        ),
      ),
      "admission_test_administrations.test must be refused by the domain, not by a per-table CHECK",
    )

    // THE STUDENT ID IS DELIBERATELY ONE THAT DOES NOT EXIST, and it makes the
    // assertion STRONGER rather than weaker. A domain constraint is evaluated
    // when the value is coerced -- while the VALUES list is being evaluated,
    // before the tuple exists -- so it fires ahead of every table CHECK and
    // ahead of the foreign key, which is an AFTER-row trigger. If the domain did
    // NOT carry the vocabulary, this write would be refused by
    // `admission_test_scores_student_id_fkey` instead, and the assertion below
    // would fail just as loudly, naming that key.
    //
    // It is also why this suite writes no `users` and no `students` row for the
    // case: `students` carries `prevent_physical_delete`, so a student created
    // here could not be removed again, and TRUNCATEing those two tables would
    // reach outside this corpus for a fact about a domain.
    assertEquals(
      "admission_test_check",
      violatedConstraint(
        "admission_test_scores",
        linkedMapOf(
          "student_id" to "'${UUID.randomUUID()}'",
          "test" to "'psat'",
          "component" to "'sat_total'",
          "score" to "1200",
          "taken_on" to "DATE '2026-03-01'",
          "taken_precision" to "'month'",
        ),
      ),
      "admission_test_scores.test must be refused by the domain, not by a per-table CHECK and not by its foreign key",
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun seedCollege(
    ipedsUnitId: Int,
    name: String,
  ): CollegeId =
    CollegesDao
      .upsert(session, newCollegeFixture(ipedsUnitId, name))
      .getOrThrow()
      .id

  /**
   * A LEGAL band as SQL literals, under [award]: every bad cell in the band
   * sweep is one override away from it.
   */
  private fun bandColumns(award: AutomaticAwardId): Map<String, String> =
    linkedMapOf(
      "award_id" to "'${award.value}'",
      "award_name" to "'Presidential Scholarship'",
      "test" to "'sat'",
      "score_low" to "1200",
      "score_high" to "1250",
      "gpa_low" to "NULL",
      "gpa_high" to "NULL",
      "award_per_year_usd" to "6000",
      "award_as_published" to "NULL",
    )

  /**
   * Writes one row from literal SQL and answers with the constraint PostgreSQL
   * refused it with, or null if it was accepted.
   *
   * IT GOES AROUND THE MODELS ON PURPOSE. [LastSittingConsidered], the three
   * enums and the two data classes' own `require`s make most of these rows
   * unconstructable in Kotlin, which is the point of them -- and it is exactly
   * why the CHECKs still have to be EXECUTED: the database is what a migration,
   * a generated seed and `psql` all write through, and none of those typechecks.
   *
   * A DOMAIN constraint answers here too, under its own name: the refusal
   * carries `errdomainconstraint`, so `admission_test_check` is what comes back
   * for a third test value.
   */
  private fun violatedConstraint(
    table: String,
    columns: Map<String, String>,
  ): String? =
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("INSERT INTO $table (${columns.keys.joinToString(", ")}) VALUES (${columns.values.joinToString(", ")})")
      }
      null
    } catch (e: SQLException) {
      (e as? PSQLException)?.serverErrorMessage?.constraint
        ?: "a [${e.sqlState}] with no constraint name: ${e.message}"
    }

  /** One reconstructed band, with everything a case is not about already legal. */
  private fun readBand(
    awardName: String = AutomaticAwardFixture.DEFAULT_AWARD_NAME,
    score: BandScoreAxis = BandScoreAxis.Range(test = AdmissionTest.SAT, low = 1200, high = 1250),
    gpaLow: BigDecimal? = null,
    gpaHigh: BigDecimal? = null,
    awardPerYearUsd: Int? = 6000,
    awardAsPublished: String? = null,
  ): AutomaticAwardBand =
    AutomaticAwardBand(
      id = AutomaticAwardBandId(UUID.randomUUID()),
      awardId = AutomaticAwardId(UUID.randomUUID()),
      awardName = awardName,
      score = score,
      gpaLow = gpaLow,
      gpaHigh = gpaHigh,
      awardPerYearUsd = awardPerYearUsd,
      awardAsPublished = awardAsPublished,
    )

  /** One reconstructed award, with everything a case is not about already legal. */
  private fun readAward(
    awardKind: AutomaticAwardKind = AutomaticAwardKind.SCORE_GRID,
    scoreRequirement: ScoreRequirement = ScoreRequirement.REQUIRED,
    scoreRequirementAsPublished: String? = "A test score is required for scholarship consideration.",
    superscoreRule: ScholarshipSuperscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY,
    superscoreAsPublished: String? = "Scholarship awards are based on a single test administration.",
    scoreDeadline: LocalDate? = null,
    scoreDeadlineAsPublished: String? = null,
  ): AutomaticAward =
    AutomaticAward(
      id = AutomaticAwardId(UUID.randomUUID()),
      collegeId = college,
      entryYear = AcademicYear(AutomaticAwardFixture.DEFAULT_ENTRY_YEAR),
      residencyBasis = ResidencyBasis.NOT_APPLICABLE,
      awardKind = awardKind,
      scoreRequirement = scoreRequirement,
      scoreRequirementAsPublished = scoreRequirementAsPublished,
      superscoreRule = superscoreRule,
      superscoreAsPublished = superscoreAsPublished,
      scoreDeadline = scoreDeadline,
      scoreDeadlineAsPublished = scoreDeadlineAsPublished,
      lastSittingConsidered = null,
      publisher = "Example State University",
      sourceUrl = AutomaticAwardFixture.DEFAULT_SOURCE_URL,
      archiveUrl = null,
      checkedOn = AutomaticAwardFixture.DEFAULT_CHECKED_ON,
      expiresOn = AutomaticAwardFixture.DEFAULT_EXPIRES_ON,
      bands = emptyList(),
    )

  private fun updatedAtOf(id: AutomaticAwardId): OffsetDateTime =
    connection.prepareStatement("SELECT updated_at FROM college_automatic_awards WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getObject("updated_at", OffsetDateTime::class.java)
      }
    }

  private fun linkStatusOf(url: String): Pair<String, LocalDate?> =
    connection.prepareStatement("SELECT url_status, url_checked_on FROM external_documents WHERE url = ?").use { stmt ->
      stmt.setString(1, url)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getString("url_status") to rs.getDate("url_checked_on")?.toLocalDate()
      }
    }

  /**
   * The written cell, with the OUTCOME ARM asserted rather than assumed: a case
   * about the natural key or the prune must fail loudly if the write came back
   * [BandWriteOutcome.NoGridPublished], never pass on a cell that never landed.
   */
  private fun writtenBand(
    award: AutomaticAwardId,
    cell: NewAutomaticAwardBand,
  ): WrittenRow<AutomaticAwardBandId> =
    assertIs<BandWriteOutcome.Written>(
      AutomaticAwardsDao.upsertBand(session, award, cell).getOrThrow(),
      "the cell [${cell.awardName}] under award [${award.value}] was not written",
    ).row

  /** How many cells hang off [award], read past the DAO so a cascade can be observed without a read model. */
  private fun bandsUnder(award: AutomaticAwardId): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM college_automatic_award_bands WHERE award_id = ?").use { stmt ->
      stmt.setObject(1, award.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun countRows(table: String): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
