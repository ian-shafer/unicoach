package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.dao.AutomaticAwardFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `college_automatic_awards`' read (RFC 239): which of the five answers a named
 * school gets, where this student's own figures land in its grid, and the last
 * sitting that still makes the school's money.
 *
 * Every test pins an INSTANT and never a zone -- the service supplies
 * `COACH_ZONE` -- and every date asserted is either a row of the committed
 * calendar, a date the seeded award publishes, or a stated derivation. Nothing
 * here asserts a date this suite invented.
 *
 * The clock is [AutomaticAwardTestDb.NOON_EASTERN_2026_09_24] throughout unless
 * a test says otherwise, and the calendar is the committed one, so the sittings
 * these assertions name are the fifteen rows that ship.
 */
class AutomaticAwardServiceTest {
  @BeforeEach
  fun setUp() {
    AutomaticAwardTestDb.reset()
  }

  /**
   * THE ANSWER THE WHOLE SLICE EXISTS FOR. A school that pays on a score band,
   * a student who has one, and everything a family needs to act: which cell she
   * is in, what it pays, whether a score must be submitted at all, what the
   * school says about combining sittings, and the last Saturday that still
   * counts.
   */
  @Test
  fun `a score grid with a stored score answers the band, the dollars, the requirement, the rule and the last sitting`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Band College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      // THE SCHOOL'S ANSWER AND THE STUDENT'S STANDING ARE TWO VALUES NOW: the
      // grid arm carries nothing about her, and the standing rides beside it on
      // the student-scoped envelope, which the cost report's door cannot build.
      val grid = readFor(student).answers.single()
      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(grid.answer)

      assertEquals(ScoreRequirement.REQUIRED, answer.award.scoreRequirement)
      assertNotNull(answer.award.scoreRequirementAsPublished, "a stated requirement carries the school's own words")
      assertEquals(
        ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY,
        answer.award.superscoreRule,
        "the SCHOLARSHIP rule, which is a different fact from what this school does for admission",
      )
      val standing = grid.standings.single()
      assertEquals(AdmissionTest.SAT, standing.test, "she holds an SAT figure and no ACT one, so there is one standing")
      assertEquals(1240, standing.figureRead, "a single_sitting_only school reads the best total from one date")
      assertEquals(
        listOf(AutomaticAwardTestDb.FIRST_BAND_USD),
        standing.bandsReached.map { it.awardPerYearUsd },
        "1240 is inside the 1200-1250 cell, and that cell's money is the answer: ${standing.bandsReached}",
      )
      assertEquals(
        LocalDate.parse("2026-11-07"),
        assertNotNull(answer.lastUsableSitting).testDate,
        "the November 7 SAT releases scores on November 20, which is before this school's December 4 deadline",
      )
    }

  /**
   * THE SAME SCHOOL, NO SCORE, AND NOTHING GATED (brief 0001 D11). The grid, the
   * requirement and the deadline are all still answered; there is simply no
   * standing in it, because we hold no figure of hers.
   */
  @Test
  fun `the same grid with no stored score still answers its bands and its deadline, and gates nothing`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "No Score College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grid = readFor(student).answers.single()
      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(grid.answer)

      assertEquals(emptyList(), grid.standings, "we hold no figure for her, so there is no standing to state")
      assertEquals(
        AutomaticAwardTestDb.workedGrid().size,
        answer.award.bands.size,
        "every published cell still comes back: the school's grid is a fact about the school",
      )
      assertEquals(AutomaticAwardFixture.DEFAULT_SCORE_DEADLINE, answer.award.scoreDeadline)
      assertEquals(
        LocalDate.parse("2026-11-07"),
        assertNotNull(answer.lastUsableSitting).testDate,
        "the date arithmetic is about the school and the calendar, not about her score",
      )
    }

  /**
   * A REFUSAL WINS OVER ROWS (brief 0010 D24), and the answer is the same one
   * the no-score student gets. Her live rows stay hers; the coach does not name
   * them, does not stand a band on them, and nothing here re-asks.
   */
  @Test
  fun `a student who declined to share scores gets the whole grid, no standing, and is not re-asked`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Declined College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      // A score she DID give us, and then a refusal: the refusal is what the
      // answer must obey, and a test with no rows could not tell the two apart.
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.declineScores(student)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grid = readFor(student).answers.single()
      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(grid.answer)

      assertEquals(
        emptyList(),
        grid.standings,
        "a declined student has no score in the coach's context by design, live rows or not",
      )
      assertEquals(AutomaticAwardTestDb.workedGrid().size, answer.award.bands.size, "and the school's grid is still answered in full")
    }

  /**
   * FIVE ANSWERS, FIVE PLACES TO SEND A FAMILY, and the whole point is that no
   * two of them are the same sentence.
   *
   * "We never read this school", "we read it and it publishes nothing", "it
   * publishes a table a score cannot move", "our reading has gone stale" and "a
   * score is money here" are five different facts. Collapsing any pair -- most
   * temptingly [AutomaticAwardAnswer.Expired] into
   * [AutomaticAwardAnswer.NotCurated] -- would turn a refresh into a silence.
   */
  @Test
  fun `the five answers are five different places to send a family`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val grid = AutomaticAwardTestDb.seedListedCollege(student, "Score Grid College")
      val gpaOnly = AutomaticAwardTestDb.seedListedCollege(student, "GPA Only College")
      val nonePublished = AutomaticAwardTestDb.seedListedCollege(student, "Publishes Nothing College")
      val expired = AutomaticAwardTestDb.seedListedCollege(student, "Stale Reading College")
      val notCurated = AutomaticAwardTestDb.seedListedCollege(student, "Never Read College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(grid), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(gpaOnly, awardKind = AutomaticAwardKind.GPA_ONLY_GRID),
        listOf(
          AutomaticAwardFixture.band(
            awardName = "Trustee Scholarship",
            test = null,
            scoreLow = null,
            scoreHigh = null,
            gpaLow = BigDecimal("3.500"),
            awardPerYearUsd = 5000,
          ),
        ),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(nonePublished, awardKind = AutomaticAwardKind.NONE_PUBLISHED, scoreDeadline = null),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(expired, checkedOn = LocalDate.parse("2025-09-01"), expiresOn = LocalDate.parse("2026-07-31")),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val byCollege = readFor(student).answers.associateBy { it.collegeId }

      assertIs<AutomaticAwardAnswer.ScoreBands>(byCollege.getValue(grid).answer)
      assertIs<AutomaticAwardAnswer.NoScoreEffect>(byCollege.getValue(gpaOnly).answer)
      assertIs<AutomaticAwardAnswer.NonePublished>(byCollege.getValue(nonePublished).answer)
      val stale = assertIs<AutomaticAwardAnswer.Expired>(byCollege.getValue(expired).answer)
      assertIs<AutomaticAwardAnswer.NotCurated>(byCollege.getValue(notCurated).answer)
      assertEquals(
        LocalDate.parse("2026-07-31"),
        stale.expiredOn,
        "the stale arm names the day the reading stopped being believed, which is what makes it a refresh and not a silence",
      )
      assertEquals(
        5,
        byCollege.values
          .map { it.answer::class }
          .toSet()
          .size,
        "five schools, five KINDS of answer: [${byCollege.values.map { it.answer::class.simpleName }}]",
      )
    }

  /**
   * THE HEADLINE SENTENCE: how far away the next cell is, and what reaching it
   * is worth. It is measured against the best cell she already reaches, and it
   * is ABSENT in the top band -- where "one more band" would be a band the
   * school does not publish.
   */
  @Test
  fun `the next band carries the point gap and the dollar step, and is absent in the top band`() =
    runTest {
      val twentyShort = AutomaticAwardTestDb.createStudent()
      val alreadyTop = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(twentyShort, "Next Band College")
      AutomaticAwardTestDb.addToCollegeList(alreadyTop, college)
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.recordScore(twentyShort, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.recordScore(alreadyTop, AdmissionTestScoreComponent.SAT_TOTAL, 1400, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val short = readFor(twentyShort).answers.single()
      val top = readFor(alreadyTop).answers.single()
      assertIs<AutomaticAwardAnswer.ScoreBands>(short.answer)
      assertIs<AutomaticAwardAnswer.ScoreBands>(top.answer)

      val next = assertNotNull(short.standings.single().nextBand, "1240 is twenty points under the 1260 cell")
      assertEquals(20, next.pointsAway, "the gap is on the test's own scale, from the figure the school reads")
      // EVERY CELL AT THAT SCORE, NOT ONE OF THEM. This grid publishes a single
      // cell at 1260, so the list has one member -- and the assertion is on the
      // LIST, because a grid with two GPA rows at one score would put both here
      // and the old shape would silently have picked the better-paying one.
      assertEquals(
        listOf(AutomaticAwardTestDb.SECOND_BAND_USD),
        next.bands.map { it.awardPerYearUsd },
        "the cells at the next score up ride whole: [${next.bands.map { it.awardName }}]",
      )
      assertEquals(
        IntRange(
          AutomaticAwardTestDb.SECOND_BAND_USD - AutomaticAwardTestDb.FIRST_BAND_USD,
          AutomaticAwardTestDb.SECOND_BAND_USD - AutomaticAwardTestDb.FIRST_BAND_USD,
        ),
        next.dollarStepPerYearUsd,
        "the step is what she would GAIN, not what the next band pays in total -- a RANGE, which is one figure " +
          "wide where the school publishes one cell at that score",
      )
      assertNull(
        top.standings.single().nextBand,
        "1400 is in the top published cell, and there is no band above it to send her to",
      )
    }

  /**
   * A TWO-DIMENSIONAL GRID HAS ONE CELL PER GPA ROW, AND WE HOLD NO GPA. So the
   * headline sentence names EVERY cell at the next score up, exactly as
   * `bandsReached` already named every cell she reaches -- and the step is the
   * RANGE they pay, because which of them she would land in is a fact about a
   * grade point average nobody gave us.
   *
   * The landed code picked the best-paying cell at that score and rendered it as
   * "worth $6,000 more per year". 106 committed score cells have two or more GPA
   * rows paying different money, so that figure was routinely one her GPA cannot
   * earn.
   */
  @Test
  fun `the next band names every cell at that score and prices the step as the range they pay`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Matrix College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.matrixGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val next = assertNotNull(scoreBandStandings(readFor(student).answers.single()).single().nextBand)

      assertEquals(20, next.pointsAway, "1240 is twenty points under the 1260 score")
      assertEquals(
        listOf(AutomaticAwardTestDb.SECOND_BAND_USD, AutomaticAwardTestDb.TOP_BAND_USD),
        next.bands.map { it.awardPerYearUsd }.sortedBy { it },
        "BOTH cells at 1260 ride, because they differ by a GPA row we hold nothing about: " +
          "[${next.bands.map { "${'$'}{it.awardName} ${'$'}{it.gpaLow}" }}]",
      )
      assertEquals(
        IntRange(
          AutomaticAwardTestDb.SECOND_BAND_USD - AutomaticAwardTestDb.FIRST_BAND_USD,
          AutomaticAwardTestDb.TOP_BAND_USD - AutomaticAwardTestDb.FIRST_BAND_USD,
        ),
        next.dollarStepPerYearUsd,
        "the step spans what those cells pay over the cell she already reaches, and names neither end as THE figure",
      )
    }

  /**
   * A BAND PUBLISHED IN WORDS IS NOT A BAND THAT PAYS ZERO.
   *
   * Nineteen committed bands are priced in the school's own words -- "tuition
   * and fees", "Presidential Elite", "full cost of attendance" -- and they are
   * the TOP awards. Ranked at `awardPerYearUsd ?: 0`, the cash cell at the same
   * score always won the tie, so the coach named the school's SECOND-best award
   * as the next one up and computed a dollar step against nothing.
   */
  @Test
  fun `a band priced in the school's words is named beside the cash one, and no dollar step is invented`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Words College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.matrixGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1280, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val next = assertNotNull(scoreBandStandings(readFor(student).answers.single()).single().nextBand)

      assertEquals(
        setOf("Foundation in Excellence", "Presidential"),
        next.bands.map { it.awardName }.toSet(),
        "the word-priced award is named, not dropped for the cash one beside it: [${'$'}{next.bands}]",
      )
      assertEquals(
        AutomaticAwardTestDb.TUITION_AS_PUBLISHED,
        assertNotNull(next.bands.single { it.awardPerYearUsd == null }.awardAsPublished),
        "and it travels with the school's own words, which are what the coach reads out",
      )
      assertNull(
        next.dollarStepPerYearUsd,
        "a step measured against a band published in words is measured against nothing, so there is no gap to state",
      )
    }

  /**
   * THE SAME RULE ON THE OTHER SIDE OF THE STEP: a student already being paid a
   * band the school prices in words is not told the next band is worth its whole
   * amount more.
   *
   * No committed row reaches this today -- every unpriced cell's higher bands are
   * unpriced too -- and it is a money figure, so it does not wait for a school to
   * publish the arrangement that fires it.
   */
  @Test
  fun `a step measured from a band she already reaches that is priced in words states no figure`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Words Tier College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.wordPricedTierGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val standing = scoreBandStandings(readFor(student).answers.single()).single()
      val next = assertNotNull(standing.nextBand)

      assertEquals(
        listOf(AutomaticAwardTestDb.TUITION_AS_PUBLISHED),
        standing.bandsReached.map { it.awardAsPublished },
        "she is in the cell this school publishes in words",
      )
      assertEquals(listOf("Presidential Elite"), next.bands.map { it.awardName })
      assertNull(
        next.dollarStepPerYearUsd,
        "the next band's whole amount is not the STEP: part of it is money this school already pays her, and how " +
          "much is a figure the school never published",
      )
    }

  /**
   * ACT'S RULE IS CITED OR IT IS NOT OURS TO USE, AND THE ROW'S STATUS IS THE
   * CITATION.
   *
   * `findByUrl` was added so this read could see the status and then read only
   * whether a row existed, so a page the sweep had recorded as gone still
   * licensed our arithmetic under ACT's name. `unknown` is refused with it --
   * stricter than the link rule elsewhere, because that one decides whether to
   * show an anchor and this one decides whether to publish a number.
   */
  @Test
  fun `a swept-away or unanswered ACT rule page refuses the combination as loudly as an absent one`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Swept College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      seedActSitting(student, APRIL_2026, english = 30, math = 29, reading = 28)
      AutomaticAwardTestDb.seedCommittedCalendar()

      AutomaticAwardTestDb.citeActCompositeRule(SourceUrlStatus.NOT_FOUND)
      assertEquals(
        CombinationRefusal.ActRuleNotCited(
          AutomaticAwardService.ACT_COMPOSITE_RULE_URL,
          SourceUrlStatus.NOT_FOUND,
          AutomaticAwardFixture.DEFAULT_CHECKED_ON,
        ),
        refusalOf(standingOf(student, AdmissionTest.ACT)),
        "a row whose sweep says the page no longer resolves is not a citation we can stand behind -- and the " +
          "refusal carries WHICH verdict it was, because a page that has moved is a citation to repair",
      )

      AutomaticAwardTestDb.citeActCompositeRule(SourceUrlStatus.UNKNOWN)
      assertEquals(
        CombinationRefusal.ActRuleNotCited(
          AutomaticAwardService.ACT_COMPOSITE_RULE_URL,
          SourceUrlStatus.UNKNOWN,
          AutomaticAwardFixture.DEFAULT_CHECKED_ON,
        ),
        refusalOf(standingOf(student, AdmissionTest.ACT)),
        "and `unknown` is we never asked, or we asked and got no usable answer -- which must never read as a check " +
          "that passed, and is a different repair from a page that moved",
      )

      AutomaticAwardTestDb.citeActCompositeRule(SourceUrlStatus.FOUND)
      assertEquals(
        29,
        combinedOf(standingOf(student, AdmissionTest.ACT)).score,
        "the same sections, once the page is one we asked about and found",
      )
    }

  /**
   * "SHE REACHES NO BAND" IS NOT "A BAND PAYING $0", and the difference is the
   * whole headline figure.
   *
   * A student below every published score band reaches no cell on this test, and
   * the school's own NO-SCORE row may still be paying her: 15 committed grids
   * publish both a no-score row and score ranges, and unit 157085's James F.
   * Hardymon row pays $1,500-$6,500 on grade point average alone under a grid
   * whose lowest ACT range starts at 26. Measured from zero, the step became the
   * WHOLE next award rather than the gap -- the same family of defect as a
   * word-priced band ranked at $0.
   *
   * BOTH HALVES, because the fix is a state and not a constant: a school that
   * publishes no no-score row pays her nothing from this grid today, and there
   * the whole first band IS the step.
   */
  @Test
  fun `a student below every band is measured from the school's own no-score row, and from zero only where there is none`() =
    runTest {
      val underGrid = AutomaticAwardTestDb.createStudent()
      val underRowless = AutomaticAwardTestDb.createStudent()
      val withNoScoreRow = AutomaticAwardTestDb.seedListedCollege(underGrid, "No Score Row College")
      val withoutNoScoreRow = AutomaticAwardTestDb.seedListedCollege(underRowless, "Score Rows Only College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(withNoScoreRow), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(withoutNoScoreRow), AutomaticAwardTestDb.matrixGrid())
      AutomaticAwardTestDb.recordScore(underGrid, AdmissionTestScoreComponent.SAT_TOTAL, 1150, MARCH_2026)
      AutomaticAwardTestDb.recordScore(underRowless, AdmissionTestScoreComponent.SAT_TOTAL, 1150, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val under = scoreBandStandings(readFor(underGrid).answers.single()).single()
      assertEquals(emptyList(), under.bandsReached, "1150 is below every score cell this school publishes")
      val next = assertNotNull(under.nextBand, "the 1200 cell is still the next one up")
      assertEquals(50, next.pointsAway, "the gap is on the test's own scale")
      assertEquals(
        IntRange(
          AutomaticAwardTestDb.FIRST_BAND_USD - AutomaticAwardTestDb.NO_SCORE_AWARD_USD,
          AutomaticAwardTestDb.FIRST_BAND_USD - AutomaticAwardTestDb.NO_SCORE_AWARD_USD,
        ),
        next.dollarStepPerYearUsd,
        "the step is the GAP over the no-score row this school already pays her, and never the whole first band",
      )

      val rowless = scoreBandStandings(readFor(underRowless).answers.single()).single()
      val nextRowless = assertNotNull(rowless.nextBand)
      assertEquals(
        IntRange(AutomaticAwardTestDb.FIRST_BAND_USD, AutomaticAwardTestDb.FIRST_BAND_USD),
        nextRowless.dollarStepPerYearUsd,
        "this school publishes no no-score row, so its grid pays her nothing today and the whole first band IS the " +
          "step -- the school's own answer, not our default",
      )
    }

  /**
   * THE COMBINATION, BOTH WAYS ROUND, from the SAME two sittings (D-H).
   *
   * At a school whose page says it accepts a combined score, her best sections
   * cross a band and the higher cell is the answer; at a school that reads one
   * sitting, the very same history answers the LOWER cell and says why. One
   * student, one set of scores, two schools: the difference is the school's
   * stated rule and nothing else.
   */
  @Test
  fun `two sittings that cross a band are combined where the school accepts one, and are not where it reads a single sitting`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val combining = AutomaticAwardTestDb.seedListedCollege(student, "Combining College")
      val oneSitting = AutomaticAwardTestDb.seedListedCollege(student, "One Sitting College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(combining, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(oneSitting, superscoreRule = ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY),
        AutomaticAwardTestDb.workedGrid(),
      )
      seedTwoSatSittings(student)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answers = readFor(student).answers.associateBy { it.collegeId }
      val combined = scoreBandStandings(answers.getValue(combining)).single()
      val single = scoreBandStandings(answers.getValue(oneSitting)).single()

      assertEquals(1300, combinedOf(combined).score, "her best reading-and-writing plus her best math")
      assertEquals(
        listOf(MARCH_2026, JUNE_2026),
        combinedOf(combined).sittingsUsed,
        "the answer says WHICH sittings each winning section came from",
      )
      assertEquals(1300, combined.figureRead, "this school's own rule reads the combination, so that is the figure its grid is read with")
      assertEquals(listOf(AutomaticAwardTestDb.SECOND_BAND_USD), combined.bandsReached.map { it.awardPerYearUsd })
      // "NOTHING WAS REFUSED HERE" IS NOW THE ARM ITSELF: a standing offers a
      // figure or names why not, and cannot do both, so asserting the arm is the
      // whole of the old pair of assertions.
      assertIs<CombinationOutcome.Computed>(combined.combination, "nothing was refused here")

      assertIs<CombinationOutcome.Refused>(single.combination, "this school's page says its scholarship grid reads one sitting")
      assertEquals(
        CombinationRefusal.RuleDoesNotPermit(ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY),
        refusalOf(single),
        "and the refusal is NAMED, because it is the school's rule and not a gap in our data",
      )
      assertEquals(1250, single.figureRead, "her best single sitting is the June total")
      assertEquals(
        listOf(AutomaticAwardTestDb.FIRST_BAND_USD),
        single.bandsReached.map { it.awardPerYearUsd },
        "the same history is worth a band less here, and that difference is the whole point of storing the rule",
      )
    }

  /**
   * A SCHOOL THAT SAID NOTHING IS NOT A SCHOOL THAT SAID YES.
   *
   * `unstated` is the THIRD state this RFC argues for rather than a boolean, and
   * it is the majority of the committed corpus -- 35 rows, 5 of them score
   * grids. The gate is written against `superscore_accepted` and not against
   * `single_sitting_only`, and those two polarities differ ONLY here: a gate
   * that asked "is it single-sitting?" would hand these five schools a combined
   * figure they never promised to read, which is money the school will not pay.
   *
   * The SAME two sittings as the combination test above, so the difference
   * asserted is the school's silence and nothing else.
   */
  @Test
  fun `a school that publishes no superscore rule is read one sitting at a time`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Silent Rule College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.UNSTATED),
        AutomaticAwardTestDb.workedGrid(),
      )
      seedTwoSatSittings(student)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val standing = scoreBandStandings(readFor(student).answers.single()).single()

      assertIs<CombinationOutcome.Refused>(standing.combination, "this school's page never said it reads a combined score")
      assertEquals(
        CombinationRefusal.RuleDoesNotPermit(ScholarshipSuperscoreRule.UNSTATED),
        refusalOf(standing),
        "and the refusal names the school's SILENCE, which is a different sentence from a rule it stated",
      )
      assertEquals(1250, standing.figureRead, "her best single sitting is what this grid is read with")
      assertEquals(
        listOf(AutomaticAwardTestDb.FIRST_BAND_USD),
        standing.bandsReached.map { it.awardPerYearUsd },
        "1300 combined would have paid a band more, on a rule nobody published",
      )
    }

  /**
   * THE PREDICATE, AT ITS OWN EDGE. A row is believed THROUGH its `expires_on`:
   * on July 31 the school's grid is still the answer, and on August 1 it is a
   * reading we no longer stand behind.
   *
   * Every committed row expires on ONE day, so `>=` would take the whole corpus
   * off the coach a day early and take it off all at once -- and every other
   * case in this suite is weeks clear of the edge, so nothing else here would
   * notice. Both clocks in one case, because either half alone passes under
   * either predicate.
   */
  @Test
  fun `a row is believed through its expiry day and stale the morning after`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Edge Of Expiry College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val onTheDay =
        AutomaticAwardTestDb
          .serviceAt(NOON_EASTERN_2027_07_31)
          .getForStudent(student)
          .getOrThrow()
          .answers
          .single()
      assertIs<AutomaticAwardAnswer.ScoreBands>(
        onTheDay.answer,
        "expires_on is the LAST day we believe the reading, not the first day we do not: [$onTheDay]",
      )

      val theNextDay =
        AutomaticAwardTestDb
          .serviceAt(NOON_EASTERN_2027_08_01)
          .getForStudent(student)
          .getOrThrow()
          .answers
          .single()
      assertEquals(
        AutomaticAwardFixture.DEFAULT_EXPIRES_ON,
        assertIs<AutomaticAwardAnswer.Expired>(theNextDay.answer).expiredOn,
        "and the morning after, the same row is a refresh we name the date of: [$theNextDay]",
      )
    }

  /**
   * A SITTING ENTERED AS A BARE TOTAL CONTRIBUTES NO SECTIONS. The school
   * accepts a combination and we simply hold nothing to combine -- absent data
   * of ours, named as ours, and never the school's refusal.
   */
  @Test
  fun `a sitting stored as a bare total contributes no sections`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Totals Only College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1250, JUNE_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val standing = scoreBandStandings(readFor(student).answers.single()).single()

      assertEquals(CombinationRefusal.NoSectionsHeld, refusalOf(standing))
      assertEquals(1250, standing.figureRead, "her best single total still stands, and the grid is read with it")
    }

  /**
   * ACT'S OWN DIVISOR, AND NOT A NAIVE FOUR. Science left the Composite for
   * national testing between April and September 2025, so a three-section
   * history is averaged over three and a four-section one over four -- and
   * getting this wrong moves a student a whole band.
   */
  @Test
  fun `a three-section ACT history is averaged over three and a four-section one over four`() =
    runTest {
      val enhanced = AutomaticAwardTestDb.createStudent()
      val legacy = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(enhanced, "ACT Grid College")
      AutomaticAwardTestDb.addToCollegeList(legacy, college)
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.citeActCompositeRule()
      seedActSitting(enhanced, APRIL_2026, english = 30, math = 29, reading = 28)
      seedActSitting(legacy, APRIL_2026, english = 30, math = 29, reading = 28, science = 25)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val three = combinedOf(standingOf(enhanced, AdmissionTest.ACT))
      val four = combinedOf(standingOf(legacy, AdmissionTest.ACT))

      assertEquals(3, three.actSubjectsAveraged, "English, math and reading -- the current Composite")
      assertEquals(29, three.score, "87 over three")
      assertEquals(4, four.actSubjectsAveraged, "the same three plus science -- the legacy Composite")
      assertEquals(28, four.score, "112 over four, and the same three subjects average 29")
    }

  /**
   * HALF-UP, AND NEVER HALF-EVEN. ACT's own words: "fractions one-half or more
   * are rounded up". 28.5 must become 29, asserted directly -- and the second
   * student is what makes the cost visible: 30.5 half-even would answer 30 and
   * pay this school's middle band, where half-up answers 31 and pays its top.
   */
  @Test
  fun `an ACT composite of 28 and a half rounds up to 29, and a tie at a band edge costs the band`() =
    runTest {
      val half = AutomaticAwardTestDb.createStudent()
      val onTheEdge = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(half, "Rounding College")
      AutomaticAwardTestDb.addToCollegeList(onTheEdge, college)
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.citeActCompositeRule()
      seedActSitting(half, APRIL_2026, english = 29, math = 28, reading = 29, science = 28)
      seedActSitting(onTheEdge, APRIL_2026, english = 31, math = 31, reading = 30, science = 30)
      AutomaticAwardTestDb.seedCommittedCalendar()

      assertEquals(
        29,
        combinedOf(standingOf(half, AdmissionTest.ACT)).score,
        "114 over four is 28.5, and ACT rounds a half UP: half-even would answer 28",
      )
      val edge = standingOf(onTheEdge, AdmissionTest.ACT)
      assertEquals(31, combinedOf(edge).score, "122 over four is 30.5, and ACT rounds a half UP")
      assertEquals(
        listOf(AutomaticAwardTestDb.TOP_BAND_USD),
        edge.bandsReached.map { it.awardPerYearUsd },
        "half-even would have answered 30, paid the middle band, and been silently wrong by a band",
      )
    }

  /**
   * A MIXED HISTORY HAS NO PUBLISHED DIVISOR, so it gets a NAMED reason and NO
   * number. ACT states both rules and never says which wins for a student whose
   * sittings span the change; choosing one would be our interpretation wearing
   * ACT's authority.
   */
  @Test
  fun `a mixed ACT section history is refused by name and yields no combined number`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Mixed History College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.citeActCompositeRule()
      seedActSitting(student, APRIL_2026, english = 30, math = 29, reading = 28, science = 25)
      seedActSitting(student, JUNE_2026, english = 31, math = 30, reading = 29)
      // Her own reported composite, so the standing exists at all and the
      // refusal is visible on it: the answer still has her best single sitting.
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_COMPOSITE, 28, APRIL_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val standing = standingOf(student, AdmissionTest.ACT)

      // The arm IS the "no number at all": a refused combination cannot also
      // carry a figure, so naming the refusal names both halves.
      val refusal = assertIs<CombinationRefusal.MixedActSectionHistory>(refusalOf(standing))
      assertEquals(
        setOf(AutomaticAwardService.ENHANCED_ACT_SECTIONS, AutomaticAwardService.LEGACY_ACT_SECTIONS),
        refusal.sectionSets.toSet(),
        "the refusal carries the two section sets it could not choose between",
      )
      assertEquals(28, standing.figureRead, "her single best sitting still stands and the grid is read with it")
    }

  /**
   * THE STANDING THAT USED TO VANISH. A student who stored ACT SECTIONS and no
   * composite of her own, whose sittings span ACT's 2025 change, holds a figure
   * for nothing -- and the honest answer is not silence, it is "we hold your
   * sections and cannot combine them".
   *
   * A standing exists exactly when we hold SOMETHING of hers on that test, so
   * this one comes back with no figure, no cell, no next band, and the named
   * refusal. The rule it pins is not "no figure, no standing", which computed
   * this refusal and then dropped it on the floor.
   */
  @Test
  fun `a student who stored only mixed ACT sections still gets a standing, with no figure and the named refusal`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Sections Only College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.citeActCompositeRule()
      // Sections from both sides of ACT's 2025 change, and NO composite she
      // reported herself: nothing here can become a number.
      seedActSitting(student, APRIL_2026, english = 30, math = 29, reading = 28, science = 25)
      seedActSitting(student, JUNE_2026, english = 31, math = 30, reading = 29)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val standing = standingOf(student, AdmissionTest.ACT)

      assertNull(standing.bestSingleSitting, "she reported no composite of her own")
      assertNull(standing.figureRead, "so there is no figure this school's grid can be read with")
      assertEquals(emptyList(), standing.bandsReached, "no figure reaches no cell -- which is not the lowest cell")
      assertNull(standing.nextBand, "and there is no gap to measure from a figure that does not exist")
      assertIs<CombinationRefusal.MixedActSectionHistory>(
        refusalOf(standing),
        "the reason is what this standing is FOR: without it she is told nothing at all -- and ACT publishes no " +
          "divisor for a history like hers, so the outcome is a refusal and can carry no number beside it",
      )
    }

  /**
   * THE RULE IS A CITED CLAIM (RFC 220). ACT's composite arithmetic is ACT's
   * published rule, so no combination is computed while that page is not a row
   * of `external_documents` -- and the refusal NAMES the missing page rather
   * than leaving an absence for a reader to interpret.
   */
  @Test
  fun `the ACT combination is refused until ACT's own rounding rule is a cited document`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Uncited College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, superscoreRule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED),
        AutomaticAwardTestDb.workedGrid(),
      )
      seedActSitting(student, APRIL_2026, english = 30, math = 29, reading = 28)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val refused = standingOf(student, AdmissionTest.ACT)
      assertEquals(
        CombinationRefusal.ActRuleNotCited(AutomaticAwardService.ACT_COMPOSITE_RULE_URL, status = null, checkedOn = null),
        refusalOf(refused),
        "we do not hold ACT's page, so we do not do ACT's arithmetic -- and the refusal names the page it is " +
          "waiting on, with a null status saying we hold NO row at all rather than a swept verdict",
      )

      AutomaticAwardTestDb.citeActCompositeRule()

      val computed = standingOf(student, AdmissionTest.ACT)
      assertEquals(29, combinedOf(computed).score, "the same sections, once the citation exists")
    }

  /**
   * THE SUBTRACTION, AND THE BOUND THAT MADE IT. The December 5 SAT releases on
   * December 18, which is after this school's December 4 deadline, so the
   * November 7 sitting is the last one that counts -- and the answer says WHICH
   * of the school's two bounds excluded the next one.
   */
  @Test
  fun `the December sitting cannot make a December 4 deadline, and the receipt deadline is named as the bound`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "December College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(readFor(student).answers.single().answer)

      assertEquals(LocalDate.parse("2026-11-07"), assertNotNull(answer.lastUsableSitting).testDate)
      assertEquals(
        setOf(AwardDateBound.RECEIPT_DEADLINE),
        answer.boundBy,
        "the next sitting's scores would arrive on December 18, after the school's December 4 deadline",
      )
      assertEquals(
        emptyList(),
        answer.closedSittings.map { it.testDate }.filter { it == LocalDate.parse("2026-12-05") },
        "the December sitting is not CLOSED -- registration for it is open, and its scores are simply too late",
      )
    }

  /**
   * RESIDENCY CHANGES THE ANSWER, AND THIS SERVICE PICKS NEITHER (the measured
   * UAB case). One school, two published grids: the resident's stops at the
   * December ACT, the non-resident's runs to the February one. Which applies to
   * one student is a legal status the school determines, so both ride, labelled.
   */
  @Test
  fun `residency changes the last usable sitting at one school, and both grids are answered`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Two Grid University")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          residencyBasis = ResidencyBasis.IN_STATE,
          scoreDeadline = LocalDate.parse("2027-03-31"),
          lastSittingConsidered =
            LastSittingConsidered.Through(
              PartialDate.YearAndMonth(Year.of(2026), Month.DECEMBER),
              "Only scores from December 2026 and earlier will be considered.",
            ),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          residencyBasis = ResidencyBasis.OUT_OF_STATE,
          scoreDeadline = LocalDate.parse("2027-03-31"),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grids =
        readFor(student)
          .answers
          .map { assertIs<AutomaticAwardAnswer.ScoreBands>(it.answer) }
          .associateBy { it.award.residencyBasis }

      assertEquals(
        setOf(ResidencyBasis.IN_STATE, ResidencyBasis.OUT_OF_STATE),
        grids.keys,
        "both grids come back, because nothing here may choose one for her",
      )
      assertEquals(
        LocalDate.parse("2026-12-12"),
        assertNotNull(grids.getValue(ResidencyBasis.IN_STATE).lastUsableSitting).testDate,
        "the resident's grid stops counting after December 2026",
      )
      assertEquals(
        LocalDate.parse("2027-02-27"),
        assertNotNull(grids.getValue(ResidencyBasis.OUT_OF_STATE).lastUsableSitting).testDate,
        "the non-resident's grid has no such cut-off, so the February ACT still counts",
      )
    }

  /**
   * A CLOSED REGISTRATION IS A FACT WE HOLD, and it outranks everything about
   * scores. Both October sittings are still ahead on this clock and neither can
   * be registered for any more, so they are REPORTED and neither is the last
   * usable one.
   */
  @Test
  fun `a sitting whose registration has already closed is reported and is never the last usable one`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Closed Registration College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(readFor(student).answers.single().answer)

      assertEquals(
        listOf(LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-17")),
        answer.closedSittings.map { it.testDate },
        "the October SAT closed on September 18 and the October ACT on September 11: both are ahead and neither is a choice",
      )
      assertEquals(
        emptyList(),
        answer.closedSittings.filter { it.registrationOpen },
        "a closed sitting is closed, and the fact the verdict turns on rides with it",
      )
      assertTrue(
        assertNotNull(answer.lastUsableSitting).testDate.isAfter(LocalDate.parse("2026-10-17")),
        "no sitting nobody can register for may be offered as the last usable one",
      )
    }

  /**
   * THE PUBLISHER'S SILENCE IS NOT A YES. A sitting with no announced score
   * release cannot be shown to make a deadline, so it is never the last usable
   * one -- and it is not a BOUND either, because the school did not exclude it;
   * College Board simply has not published a date.
   */
  @Test
  fun `a sitting the publisher has announced no score release for is never the last usable one`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Unannounced College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(college, scoreDeadline = LocalDate.parse("2027-01-15")),
        AutomaticAwardTestDb.workedGrid(),
      )
      // This suite's own two rows, because the subject is a sitting with NO
      // announced release sitting LATER than one that has one.
      TestTimelineTestDb.seedCalendar(
        listOf(
          TestTimelineTestDb.administration(
            test = AdmissionTest.SAT,
            testDate = "2026-11-07",
            registrationDeadline = "2026-10-23",
            scoreReleaseDate = "2026-11-20",
            expiresOn = "2027-07-31",
          ),
          TestTimelineTestDb.administration(
            test = AdmissionTest.SAT,
            testDate = "2026-12-05",
            registrationDeadline = "2026-11-20",
            scoreReleaseDate = null,
            expiresOn = "2027-07-31",
          ),
        ),
      )

      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(readFor(student).answers.single().answer)

      assertEquals(
        LocalDate.parse("2026-11-07"),
        assertNotNull(answer.lastUsableSitting).testDate,
        "the December sitting might well make this deadline, and nobody has published the date that would say so",
      )
      assertEquals(
        emptySet(),
        answer.boundBy,
        "the school excluded nothing here: an unannounced release is the publisher's silence, not a bound of the school's",
      )
    }

  /** A school that publishes no receipt deadline has no last usable sitting to name, and claims no bound of its own. */
  @Test
  fun `a school that publishes no score deadline has no last usable sitting and claims no bound`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "No Deadline College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college, scoreDeadline = null), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(readFor(student).answers.single().answer)

      assertNull(answer.award.scoreDeadline)
      assertNull(answer.lastUsableSitting, "with no deadline to work back from, no sitting is the last usable one")
      assertEquals(emptySet(), answer.boundBy, "and no bound may be claimed on this school's behalf")
      assertEquals(
        AutomaticAwardTestDb.workedGrid().size,
        answer.award.bands.size,
        "the grid is still the school's published grid",
      )
    }

  /**
   * BOTH CONSTRAINTS, AND THE TIGHTER ONE WINS (the measured UT Knoxville case).
   * This school receives scores until May 15 and considers no sitting after
   * December 2026, so the February ACT -- whose scores WOULD arrive in time --
   * is not usable, and the bound that excluded it is named.
   *
   * The sibling school is what makes the claim mean anything: the same deadline
   * with no cut-off runs on to the April sitting, so it is the cut-off and not
   * the receipt deadline doing the work.
   */
  @Test
  fun `a sitting that makes the receipt deadline but falls after the last considered sitting is not usable`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val withCutOff = AutomaticAwardTestDb.seedListedCollege(student, "Cut Off College")
      val receiptOnly = AutomaticAwardTestDb.seedListedCollege(student, "Receipt Only College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          withCutOff,
          scoreDeadline = LocalDate.parse("2027-05-15"),
          lastSittingConsidered =
            LastSittingConsidered.Through(
              PartialDate.YearAndMonth(Year.of(2026), Month.DECEMBER),
              "Only scores through December 2026 will be considered.",
            ),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(receiptOnly, scoreDeadline = LocalDate.parse("2027-05-15")),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answers = readFor(student).answers.associateBy { it.collegeId }
      val bounded = assertIs<AutomaticAwardAnswer.ScoreBands>(answers.getValue(withCutOff).answer)
      val unbounded = assertIs<AutomaticAwardAnswer.ScoreBands>(answers.getValue(receiptOnly).answer)

      assertEquals(
        LocalDate.parse("2026-12-12"),
        assertNotNull(bounded.lastUsableSitting).testDate,
        "the cut-off stops this school at the December ACT",
      )
      assertEquals(
        setOf(AwardDateBound.LAST_SITTING_CONSIDERED),
        bounded.boundBy,
        "and the bound is NAMED: a retake in February is worth nothing here, however early the report arrives",
      )
      assertEquals(
        LocalDate.parse("2027-04-10"),
        assertNotNull(unbounded.lastUsableSitting).testDate,
        "the same May 15 deadline with no cut-off runs on to April, so it is the cut-off doing the work",
      )
    }

  /**
   * Both bounds can exclude the same Saturday, and BOTH are named: a family sent
   * to only one of them reads a school page that repeats the other.
   */
  @Test
  fun `both bounds can exclude the same sitting, and both are named`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Both Bounds College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          lastSittingConsidered =
            LastSittingConsidered.Through(
              PartialDate.YearAndMonth(Year.of(2026), Month.NOVEMBER),
              "Only scores from November 2026 and earlier will be considered.",
            ),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(readFor(student).answers.single().answer)

      assertEquals(LocalDate.parse("2026-11-07"), assertNotNull(answer.lastUsableSitting).testDate)
      assertEquals(
        setOf(AwardDateBound.RECEIPT_DEADLINE, AwardDateBound.LAST_SITTING_CONSIDERED),
        answer.boundBy,
        "the December 5 SAT is excluded by the December 4 deadline AND by the November cut-off, and both are said",
      )
    }

  /**
   * A CUT-OFF IN WORDS EXCLUDES NOTHING AND IS QUOTED INSTEAD (UAB's per-test
   * rule). We will not turn "February ACT or March SAT scores" into one date for
   * both tests, so no sitting is dropped by it and the school's own sentence is
   * what the family reads.
   */
  @Test
  fun `a cut-off published in words excludes no sitting and is quoted instead`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "In Words College")
      AutomaticAwardTestDb.seed(
        AutomaticAwardTestDb.award(
          college,
          scoreDeadline = LocalDate.parse("2027-05-15"),
          lastSittingConsidered = LastSittingConsidered.InWords(PER_TEST_CUT_OFF),
        ),
        AutomaticAwardTestDb.workedGrid(),
      )
      AutomaticAwardTestDb.seedCommittedCalendar()

      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(readFor(student).answers.single().answer)

      assertEquals(PER_TEST_CUT_OFF, answer.quotedCutOff, "the school's own sentence travels, because we will not parse it")
      assertEquals(
        LocalDate.parse("2027-04-10"),
        assertNotNull(answer.lastUsableSitting).testDate,
        "no sitting is excluded by words we did not parse: the receipt deadline alone decides here",
      )
    }

  /**
   * A CASE DELIBERATELY UNLIKE THE WORKED EXAMPLE (D22). A senior reading this
   * in July, past every date this school publishes: the grid still answers, no
   * sitting is left, and the one sitting still ahead is named as closed rather
   * than offered.
   */
  @Test
  fun `a senior past every deadline still gets the grid, with no sitting left and the closed one named`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Too Late College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.seedCommittedCalendar()

      val grid =
        AutomaticAwardTestDb
          .serviceAt(NOON_EASTERN_2027_07_01)
          .getForStudent(student)
          .getOrThrow()
          .answers
          .single()
      val answer = assertIs<AutomaticAwardAnswer.ScoreBands>(grid.answer)

      assertNull(answer.lastUsableSitting, "every sitting this school would count is behind her")
      assertEquals(
        listOf(LocalDate.parse("2027-07-10")),
        answer.closedSittings.map { it.testDate },
        "the one Saturday still ahead closed for registration on June 4, and saying so is the honest answer",
      )
      assertEquals(
        listOf(AutomaticAwardTestDb.FIRST_BAND_USD),
        grid.standings
          .single()
          .bandsReached
          .map { it.awardPerYearUsd },
        "her standing in the published grid is unchanged by the date: it is what the school pays her today",
      )
    }

  /** A student with nothing on her list is answered with nothing, and an id that is not on it is REPORTED rather than dropped. */
  @Test
  fun `a student with an empty list is answered with nothing, and an off-list id is reported`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val stranger = AutomaticAwardTestDb.createStudent()
      val notHers = AutomaticAwardTestDb.seedListedCollege(stranger, "Somebody Else's College")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(notHers), AutomaticAwardTestDb.workedGrid())
      AutomaticAwardTestDb.seedCommittedCalendar()

      val whole = readFor(student)
      assertEquals(emptyList(), whole.answers, "she has no schools, so there is no school to answer for")
      assertEquals(emptyList(), whole.unknownCollegeIds, "and she asked for none, so none is unknown")

      val asked = readFor(student, listOf(notHers))
      assertEquals(emptyList(), asked.answers, "another student's school is not read onto hers")
      assertEquals(
        listOf(notHers),
        asked.unknownCollegeIds,
        "it is REPORTED, so the coach can say the school is not on her list rather than that it has no award",
      )
    }

  /**
   * THE STATEMENT BUDGET, ABSOLUTE AND NOT MERELY RELATIVE (the
   * `TestTimelineServiceTest` precedent, and RFC 187's lesson that a count
   * stated only in prose is a claim no test can contradict).
   *
   * TEN COLLEGES COST WHAT ONE COLLEGE COSTS, and the absolute number is
   * asserted beside it: a relative assertion alone would let this read grow
   * silently past the number [AutomaticAwardService.readInSession]'s own KDoc
   * promises.
   */
  @Test
  fun `the whole read costs the stated number of statements, for one college and for ten`() =
    runTest {
      val one = AutomaticAwardTestDb.createStudent()
      val ten = AutomaticAwardTestDb.createStudent()
      seedGrids(one, count = 1)
      seedGrids(ten, count = 10)
      AutomaticAwardTestDb.recordScore(one, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.recordScore(ten, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
      AutomaticAwardTestDb.citeActCompositeRule()
      AutomaticAwardTestDb.seedCommittedCalendar()

      val forOne = CoachingTestDb.CountingSession()
      val forTen = CoachingTestDb.CountingSession()
      val service = AutomaticAwardTestDb.serviceAt(AutomaticAwardTestDb.NOON_EASTERN_2026_09_24)
      assertEquals(1, service.readInSession(forOne, one, null).answers.size)
      assertEquals(10, service.readInSession(forTen, ten, null).answers.size)

      assertEquals(
        AutomaticAwardService.READ_STATEMENTS,
        forOne.prepared.size,
        "the whole read is [${AutomaticAwardService.READ_STATEMENTS}] statements -- the selection's two, the awards " +
          "and their bands, the decline and the live scores, the calendar, and ACT's composite-rule citation. It " +
          "prepared [${forOne.prepared}]; if that is correct, say so in readInSession's KDoc and in " +
          "READ_STATEMENTS in the same commit",
      )
      assertEquals(
        forOne.prepared.size,
        forTen.prepared.size,
        "ten colleges must cost what one college costs: [${forTen.prepared}]",
      )
    }

  /**
   * THE STUDENT-FREE DOOR'S BUDGET, ASSERTED -- which
   * [AutomaticAwardService.GRID_READ_STATEMENTS]'s own KDoc claims ("ABSOLUTE,
   * and asserted") and nothing did.
   *
   * It is one statement fewer than it was, and the reason is not thrift: with no
   * score on this path there is no standing, so no combination is ever
   * attempted, so ACT's composite-rule citation is a read whose answer nothing
   * here can consume.
   */
  @Test
  fun `the student-free grid read costs the stated number of statements, for one college and for ten`() =
    runTest {
      val student = AutomaticAwardTestDb.createStudent()
      val colleges = (1..10).map { index -> AutomaticAwardTestDb.seedListedCollege(student, "Grid Budget College $index") }
      colleges.forEach { college -> AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid()) }
      AutomaticAwardTestDb.citeActCompositeRule()
      AutomaticAwardTestDb.seedCommittedCalendar()

      val forOne = CoachingTestDb.CountingSession()
      val forTen = CoachingTestDb.CountingSession()
      val service = AutomaticAwardTestDb.serviceAt(AutomaticAwardTestDb.NOON_EASTERN_2026_09_24)
      assertEquals(1, service.gridAnswersInSession(forOne, colleges.take(1)).size)
      assertEquals(10, service.gridAnswersInSession(forTen, colleges).size)

      assertEquals(
        AutomaticAwardService.GRID_READ_STATEMENTS,
        forOne.prepared.size,
        "the student-free read is [${AutomaticAwardService.GRID_READ_STATEMENTS}] statements -- the awards and " +
          "their bands, and the calendar. It prepared [${forOne.prepared}]; if that is correct, say so in " +
          "gridAnswersInSession's KDoc and in GRID_READ_STATEMENTS in the same commit",
      )
      assertEquals(
        forOne.prepared.size,
        forTen.prepared.size,
        "ten colleges must cost what one college costs here too: [${forTen.prepared}]",
      )
      // ACT'S CITATION LOOKUP, BY ITS OWN STATEMENT AND NOT BY THE TABLE NAME:
      // the awards read and the calendar read both JOIN `external_documents`
      // for the url each ROW cites, which is a different thing from asking
      // whether one named page is cited at all.
      assertTrue(
        forTen.prepared.none { it.contains("FROM external_documents WHERE url") },
        "and no statement on this path looks ACT's rule page up, because no combination can be attempted without " +
          "a score: [${forTen.prepared}]",
      )
    }

  /** The read, on this suite's standing clock. */
  private suspend fun readFor(
    student: StudentId,
    collegeIds: List<CollegeId>? = null,
  ): AutomaticAwardReading =
    AutomaticAwardTestDb
      .serviceAt(AutomaticAwardTestDb.NOON_EASTERN_2026_09_24)
      .getForStudent(student, collegeIds)
      .getOrThrow()

  /** This student's standing on one test at her single school -- the shape most of the combination tests want. */
  private suspend fun standingOf(
    student: StudentId,
    test: AdmissionTest,
  ): StudentStanding = scoreBandStandings(readFor(student).answers.single()).single { it.test == test }

  /**
   * The standings of one student-scoped answer, with the arm they belong to
   * asserted.
   *
   * A STANDING RIDES BESIDE THE SCHOOL'S ANSWER NOW ([StudentAwardAnswer]) and
   * no longer on it: the student-free door the family cost report reads answers
   * with [AutomaticAwardAnswer] alone, so that page cannot carry a score even by
   * mistake. This suite wants both halves, so it takes them from the envelope.
   */
  private fun scoreBandStandings(answer: StudentAwardAnswer): List<StudentStanding> {
    assertIs<AutomaticAwardAnswer.ScoreBands>(answer.answer)
    return answer.standings
  }

  /** OUR combined figure, asserted: a standing offers one or names why not, and never both. */
  private fun combinedOf(standing: StudentStanding): CombinedScore = assertIs<CombinationOutcome.Computed>(standing.combination).combined

  /** The named reason there is no combined figure, asserted the same way round. */
  private fun refusalOf(standing: StudentStanding): CombinationRefusal = assertIs<CombinationOutcome.Refused>(standing.combination).refusal

  /**
   * Two SAT sittings whose best sections cross a band: 640 and 600 in March, 590
   * and 660 in June.
   *
   * Her best SINGLE total is June's 1250, which is the top of the first cell;
   * her best sections are 640 + 660 = 1300, which is inside the second. That gap
   * is the whole subject of the combination tests, so it is built once here.
   */
  private fun seedTwoSatSittings(student: StudentId) {
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_READING_WRITING, 640, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_MATH, 600, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1240, MARCH_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_READING_WRITING, 590, JUNE_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_MATH, 660, JUNE_2026)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.SAT_TOTAL, 1250, JUNE_2026)
  }

  /**
   * One ACT sitting's sections. [science] absent is the ENHANCED set and present
   * is the LEGACY one, and which set it is decides the divisor.
   */
  private fun seedActSitting(
    student: StudentId,
    sitting: PartialDate,
    english: Int,
    math: Int,
    reading: Int,
    science: Int? = null,
  ) {
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_ENGLISH, english, sitting)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_MATH, math, sitting)
    AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_READING, reading, sitting)
    science?.let { AutomaticAwardTestDb.recordScore(student, AdmissionTestScoreComponent.ACT_SCIENCE, it, sitting) }
  }

  /** [count] listed schools, each with the worked grid -- the statement-budget test's input. */
  private fun seedGrids(
    student: StudentId,
    count: Int,
  ) {
    repeat(count) { index ->
      val college = AutomaticAwardTestDb.seedListedCollege(student, "Budget College $index")
      AutomaticAwardTestDb.seed(AutomaticAwardTestDb.award(college), AutomaticAwardTestDb.workedGrid())
    }
  }

  private companion object {
    /** Her first sitting, at the precision a student actually remembers (RFC 209). */
    val MARCH_2026: PartialDate = AutomaticAwardTestDb.sitting(2026, Month.MARCH)

    /** Her second, three months later -- a different row, because a sitting is a date. */
    val JUNE_2026: PartialDate = AutomaticAwardTestDb.sitting(2026, Month.JUNE)

    /** The ACT sitting the section tests use. */
    val APRIL_2026: PartialDate = AutomaticAwardTestDb.sitting(2026, Month.APRIL)

    /** Noon Eastern on 2027-07-01: past every deadline these schools publish, and inside the committed calendar's window. */
    const val NOON_EASTERN_2027_07_01 = "2027-07-01T16:00:00Z"

    /**
     * Noon Eastern on the day every committed row expires
     * ([AutomaticAwardFixture.DEFAULT_EXPIRES_ON]): the LAST day the corpus is
     * believed.
     */
    const val NOON_EASTERN_2027_07_31 = "2027-07-31T16:00:00Z"

    /** And noon Eastern the morning after, which is the first day it is not. */
    const val NOON_EASTERN_2027_08_01 = "2027-08-01T16:00:00Z"

    /** UAB's per-test cut-off, in the shape that cannot become one date for both tests. */
    const val PER_TEST_CUT_OFF = "February ACT or March SAT scores will be the final scores considered."
  }
}
