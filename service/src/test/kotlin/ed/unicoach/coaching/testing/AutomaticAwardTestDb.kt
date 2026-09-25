package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.db.dao.AdmissionTestCalendarFixture
import ed.unicoach.db.dao.AdmissionTestScoreDeclinesDao
import ed.unicoach.db.dao.AdmissionTestScoresDao
import ed.unicoach.db.dao.AutomaticAwardFixture
import ed.unicoach.db.dao.ExternalDocumentsDao
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.NewAdmissionTestScore
import ed.unicoach.db.models.NewAutomaticAward
import ed.unicoach.db.models.NewAutomaticAwardBand
import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.db.models.StudentId
import java.math.BigDecimal
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.Year

/**
 * The automatic-award suites' own fixture (RFC 239): the corpus rows, the
 * student's stored scores, ACT's composite-rule citation, and a service and a
 * tool with their clocks pinned.
 *
 * ONE HOME, on [TestTimelineTestDb]'s rule, because two suites need it today --
 * [AutomaticAwardServiceTest] and [AutomaticAwardChatToolTest] -- and a seeder
 * that lives inside one of them becomes that suite's private thing: the second
 * suite then either reaches across a test file or grows a copy, and a second
 * copy of this seeder is a second definition of what "a school whose grid pays
 * $8,000 at 1260" means.
 *
 * EVERY ROW GOES THROUGH THE REAL WRITE PATH -- [AutomaticAwardFixture]'s
 * builders over [ed.unicoach.db.dao.AutomaticAwardsDao]'s own upsert, and
 * [AdmissionTestScoresDao] for a score -- so a fixture cannot write a row the
 * read path could not have read, and every CHECK in the schema applies to this
 * suite exactly as it applies to the loader.
 *
 * The college, list-entry and student seeders are [AdmissionsTestDb]'s and are
 * reused rather than copied, and the calendar is [TestTimelineTestDb]'s own
 * committed one: the date half of this answer is `TestTimelineService`'s
 * arithmetic over that table, so both features read the same fifteen rows.
 */
internal object AutomaticAwardTestDb {
  val sqlSession get() = AdmissionsTestDb.sqlSession

  /**
   * Truncates what these suites touch, and every reset is NAMED by its caller
   * rather than hidden inside a `seed` verb ([AdmissionTestCalendarFixture]'s
   * stated rule).
   *
   * Four of them, and each one is a different reason:
   * [AdmissionsTestDb.reset] for the students and colleges this corpus hangs
   * off; [AutomaticAwardFixture.deleteAll] for the corpus itself, stated even
   * though the `colleges` truncate cascades to it, because a cascade is not a
   * statement of intent; the CALENDAR because it is migration-seeded and a
   * suite that chose its own sittings must not read a mixture of its rows and
   * the shipped ones; and [forgetActCompositeRule] because a test that PROVES
   * the ACT combination is refused without its citation would otherwise pass or
   * fail on whether a previous test in the same class had seeded it.
   */
  fun reset() {
    // The shared connection, in autocommit. Every write below goes through it
    // while the READ under test opens its own connection from the pool, so a
    // suite that inherited an open transaction from another class would seed
    // rows the service could not see (the `TestScoreServiceTest` precedent).
    CoachingTestDb.connection.autoCommit = true
    AdmissionsTestDb.reset()
    // `residency_basis` is a FOREIGN KEY into the money vocabulary (RFC 158 D4),
    // so a non-empty `residency_bases` is a WRITE PRECONDITION for this corpus
    // and not tidiness -- and another suite on this shared test database
    // truncates that table. Seeded here, idempotently, exactly as
    // `AdmissionsTestDb.reset` puts the codebook rows back one table along: the
    // alternative is a foreign-key failure that has nothing to do with what
    // these suites assert, in whichever run happens to order the suites wrongly.
    MoneyVocabularyFixture.seed(sqlSession)
    AutomaticAwardFixture.deleteAll(sqlSession)
    AdmissionTestCalendarFixture.deleteAll(sqlSession)
    deleteScores()
    forgetActCompositeRule()
  }

  /** The student's own score rows and her refusal, emptied. Named, and called from [reset]. */
  fun deleteScores() = CoachingTestDb.truncate("admission_test_scores", "admission_test_score_declines")

  /**
   * Forgets ACT's composite-rule page, by URL and by url alone.
   *
   * A DELETE and not a truncate: `external_documents` is the citation home for
   * every corpus in the tree, several of them migration-seeded, and truncating
   * it would cascade through every table that cites one. This suite's subject is
   * ONE row of it.
   */
  fun forgetActCompositeRule() {
    CoachingTestDb.connection
      .prepareStatement("DELETE FROM external_documents WHERE url = ?")
      .use { stmt ->
        stmt.setString(1, AutomaticAwardService.ACT_COMPOSITE_RULE_URL)
        stmt.executeUpdate()
      }
  }

  /**
   * ACT's own composite-rule page, cited -- the row the ACT combination is
   * refused without (RFC 220, D-H guardrail 2).
   *
   * Through [ExternalDocumentsDao.upsert], the sweep's entry point, because that
   * is what a swept corpus looks like: a url with a status and the day somebody
   * asked.
   */
  fun citeActCompositeRule(
    urlStatus: SourceUrlStatus = SourceUrlStatus.FOUND,
    checkedOn: LocalDate = AutomaticAwardFixture.DEFAULT_CHECKED_ON,
  ) {
    ExternalDocumentsDao
      .upsert(
        sqlSession,
        NewExternalDocument(AutomaticAwardService.ACT_COMPOSITE_RULE_URL, urlStatus, checkedOn),
      ).getOrThrow()
  }

  fun createStudent(): StudentId = AdmissionsTestDb.createStudent()

  fun seedListedCollege(
    student: StudentId,
    name: String,
  ): CollegeId = AdmissionsTestDb.seedListedCollege(student, name)

  /** One school on a SECOND student's list -- the seam a test needs when two students must read the same grid. */
  fun addToCollegeList(
    student: StudentId,
    collegeId: CollegeId,
  ) = AdmissionsTestDb.addToCollegeList(student, collegeId)

  /**
   * The real committed calendar -- the fifteen rows that ship, seeded through
   * [TestTimelineTestDb] so this feature and the timeline read one table one way.
   */
  fun seedCommittedCalendar() = TestTimelineTestDb.seedCommittedCalendar()

  /**
   * One award row, with the words the schema requires DERIVED from the coded
   * value beside them.
   *
   * It wraps [AutomaticAwardFixture.award] rather than replacing it, and the
   * reason is the `iff` CHECKs: a stated rule carries the school's words and an
   * unstated one carries none, both ways round. A suite that overrode
   * [superscoreRule] alone on the raw builder would write a row the database
   * refuses, and the refusal would read as a broken test rather than as the rule
   * it is.
   */
  fun award(
    collegeId: CollegeId,
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    awardKind: AutomaticAwardKind = AutomaticAwardKind.SCORE_GRID,
    scoreRequirement: ScoreRequirement = ScoreRequirement.REQUIRED,
    superscoreRule: ScholarshipSuperscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY,
    scoreDeadline: LocalDate? = AutomaticAwardFixture.DEFAULT_SCORE_DEADLINE,
    lastSittingConsidered: LastSittingConsidered? = null,
    publisher: String = DEFAULT_PUBLISHER,
    sourceUrl: String = AutomaticAwardFixture.DEFAULT_SOURCE_URL,
    archiveUrl: String? = null,
    checkedOn: LocalDate = AutomaticAwardFixture.DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = AutomaticAwardFixture.DEFAULT_EXPIRES_ON,
  ): NewAutomaticAward =
    AutomaticAwardFixture.award(
      collegeId = collegeId,
      residencyBasis = residencyBasis,
      awardKind = awardKind,
      scoreRequirement = scoreRequirement,
      scoreRequirementAsPublished = requirementWordsFor(scoreRequirement),
      superscoreRule = superscoreRule,
      superscoreAsPublished = superscoreWordsFor(superscoreRule),
      scoreDeadline = scoreDeadline,
      scoreDeadlineAsPublished = scoreDeadline?.let { "Official test scores must be received by ${it.monthValue}/${it.dayOfMonth}." },
      lastSittingConsidered = lastSittingConsidered,
      publisher = publisher,
      sourceUrl = sourceUrl,
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /** Writes one award and its cells through the DAO's own upsert. */
  fun seed(
    row: NewAutomaticAward,
    bands: List<NewAutomaticAwardBand> = emptyList(),
  ) = AutomaticAwardFixture.seed(sqlSession, row, bands)

  /**
   * THE WORKED GRID, and the one every date and standing test reads: three SAT
   * cells, three ACT cells at the same three prices, and the published NO-SCORE
   * row a test-optional applicant lands in.
   *
   * Both tests, at matched prices, because D13 says the whole test family and
   * the SAT is never the default noun: a grid with SAT cells alone would let an
   * ACT bug pass every test here. The no-score row is what makes "a score is
   * worth $4,000 more than no score" sayable without inventing a zero band.
   */
  fun workedGrid(): List<NewAutomaticAwardBand> =
    listOf(
      AutomaticAwardFixture.band(test = null, scoreLow = null, scoreHigh = null, awardPerYearUsd = NO_SCORE_AWARD_USD),
      AutomaticAwardFixture.band(test = AdmissionTest.SAT, scoreLow = 1200, scoreHigh = 1250, awardPerYearUsd = FIRST_BAND_USD),
      AutomaticAwardFixture.band(test = AdmissionTest.SAT, scoreLow = 1260, scoreHigh = 1310, awardPerYearUsd = SECOND_BAND_USD),
      AutomaticAwardFixture.band(test = AdmissionTest.SAT, scoreLow = 1320, scoreHigh = 1600, awardPerYearUsd = TOP_BAND_USD),
      AutomaticAwardFixture.band(test = AdmissionTest.ACT, scoreLow = 25, scoreHigh = 27, awardPerYearUsd = FIRST_BAND_USD),
      AutomaticAwardFixture.band(test = AdmissionTest.ACT, scoreLow = 28, scoreHigh = 30, awardPerYearUsd = SECOND_BAND_USD),
      AutomaticAwardFixture.band(test = AdmissionTest.ACT, scoreLow = 31, scoreHigh = 36, awardPerYearUsd = TOP_BAND_USD),
    )

  /**
   * A TWO-DIMENSIONAL grid, whose 1260 score has TWO cells -- one per GPA row --
   * and whose top score publishes a cash award BESIDE one priced in the school's
   * own words.
   *
   * Both shapes are measured, not invented. 106 committed score cells carry two
   * or more GPA rows paying different money (Oklahoma State, Northern State),
   * and unit 100751 publishes at SAT 1360 both `Foundation in Excellence` =
   * $9,000 and `Presidential` = "Tuition" -- so a headline sentence that picks
   * one cell picks a GPA nobody gave it, and one that ranks the word-priced cell
   * at $0 names the school's SECOND-best award as the next one up.
   */
  fun matrixGrid(): List<NewAutomaticAwardBand> =
    listOf(
      AutomaticAwardFixture.band(
        awardName = "Scholars",
        test = AdmissionTest.SAT,
        scoreLow = 1200,
        scoreHigh = 1250,
        gpaLow = BigDecimal("3.000"),
        awardPerYearUsd = FIRST_BAND_USD,
      ),
      AutomaticAwardFixture.band(
        awardName = "Scholars",
        test = AdmissionTest.SAT,
        scoreLow = 1260,
        scoreHigh = 1310,
        gpaLow = BigDecimal("3.000"),
        awardPerYearUsd = SECOND_BAND_USD,
      ),
      AutomaticAwardFixture.band(
        awardName = "Scholars",
        test = AdmissionTest.SAT,
        scoreLow = 1260,
        scoreHigh = 1310,
        gpaLow = BigDecimal("3.750"),
        awardPerYearUsd = TOP_BAND_USD,
      ),
      AutomaticAwardFixture.band(
        awardName = "Foundation in Excellence",
        test = AdmissionTest.SAT,
        scoreLow = 1320,
        scoreHigh = 1600,
        awardPerYearUsd = FOUNDATION_BAND_USD,
      ),
      AutomaticAwardFixture.band(
        awardName = "Presidential",
        test = AdmissionTest.SAT,
        scoreLow = 1320,
        scoreHigh = 1600,
        awardPerYearUsd = null,
        awardAsPublished = TUITION_AS_PUBLISHED,
      ),
    )

  /**
   * A grid whose LOWER tier is the one published in words: the mirror of
   * [matrixGrid]'s top, and the side the landed code got wrong in the other
   * direction -- a student already being paid "tuition and fees" was told the
   * next band was worth its whole amount more.
   */
  fun wordPricedTierGrid(): List<NewAutomaticAwardBand> =
    listOf(
      AutomaticAwardFixture.band(
        awardName = "Presidential",
        test = AdmissionTest.SAT,
        scoreLow = 1200,
        scoreHigh = 1250,
        awardPerYearUsd = null,
        awardAsPublished = TUITION_AS_PUBLISHED,
      ),
      AutomaticAwardFixture.band(
        awardName = "Presidential Elite",
        test = AdmissionTest.SAT,
        scoreLow = 1320,
        scoreHigh = 1600,
        awardPerYearUsd = TOP_BAND_USD,
      ),
    )

  /**
   * One stored score, through the DAO the write path itself uses: one row per
   * COMPONENT per sitting, which is what makes a combination possible at all.
   */
  fun recordScore(
    student: StudentId,
    component: AdmissionTestScoreComponent,
    score: Int,
    sitting: PartialDate,
  ) = AdmissionTestScoresDao
    .upsertForStudent(
      sqlSession,
      NewAdmissionTestScore(
        studentId = student,
        test = component.test,
        component = component,
        score = score,
        sitting = sitting,
      ),
    ).getOrThrow()

  /** The student chose not to share her scores at all. A refusal wins over rows (brief 0010 D24). */
  fun declineScores(student: StudentId) = AdmissionTestScoreDeclinesDao.insertDecline(sqlSession, student).getOrThrow()

  /** A month-precision sitting -- the precision a student actually remembers (RFC 209). */
  fun sitting(
    year: Int,
    month: Month,
  ): PartialDate = PartialDate.YearAndMonth(Year.of(year), month)

  /**
   * The service with today pinned. The pinned value is an INSTANT and carries no
   * zone at all (the `TestTimelineTestDb` precedent): the service applies
   * `COACH_ZONE` itself.
   */
  fun serviceAt(instant: String): AutomaticAwardService =
    AutomaticAwardService(AdmissionsTestDb.database, InstantSource.fixed(Instant.parse(instant)))

  /** The tool over that service -- the wire suite's entry point, so no test builds the pair by hand. */
  fun toolAt(instant: String): AutomaticAwardChatTool = AutomaticAwardChatTool(serviceAt(instant))

  /** The school's words for a stated submission requirement, and NONE for an unstated one -- the schema's `iff`. */
  private fun requirementWordsFor(requirement: ScoreRequirement): String? =
    when (requirement) {
      ScoreRequirement.REQUIRED -> "A test score is required for scholarship consideration."
      ScoreRequirement.NOT_REQUIRED -> "No test score is required for scholarship consideration."
      ScoreRequirement.UNSTATED -> null
    }

  /** The school's words for a stated scholarship superscore rule, and NONE for an unstated one. */
  private fun superscoreWordsFor(rule: ScholarshipSuperscoreRule): String? =
    when (rule) {
      ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY -> {
        "Scholarship awards are based on a single test administration."
      }

      ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED -> {
        "We will use your best section scores across test dates for scholarship consideration."
      }

      ScholarshipSuperscoreRule.UNSTATED -> {
        null
      }
    }

  /** The publisher these suites read, as a family would recognise it on the page. */
  const val DEFAULT_PUBLISHER = "Example State University"

  /** The published NO-SCORE tier: what a test-optional applicant is paid here. */
  const val NO_SCORE_AWARD_USD = 2000

  /** The three score bands of [workedGrid], in dollars per year. */
  const val FIRST_BAND_USD = 6000
  const val SECOND_BAND_USD = 8000
  const val TOP_BAND_USD = 12000

  /** The CASH award [matrixGrid] publishes at its top score, beside one published in words. */
  const val FOUNDATION_BAND_USD = 9000

  /** A school pricing a band in its own words rather than as a figure -- the shape nineteen committed bands take. */
  const val TUITION_AS_PUBLISHED = "tuition and fees"

  /**
   * Noon Eastern on the day this corpus's rows were read
   * ([AutomaticAwardFixture.DEFAULT_CHECKED_ON]), which is also inside the
   * committed calendar's own window.
   *
   * THE READING DAY AND NOT AN ARBITRARY ONE: a fixture row read on a day AFTER
   * the clock the test reads it back on would be a school page somebody opened
   * in the future, and every "we read this on" sentence built from it would say
   * so.
   */
  const val NOON_EASTERN_2026_09_24 = "2026-09-24T16:00:00Z"
}
