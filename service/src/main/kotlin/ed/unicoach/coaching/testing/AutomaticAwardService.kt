package ed.unicoach.coaching.testing

import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.coachToday
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdmissionTestCalendarDao
import ed.unicoach.db.dao.AutomaticAwardsDao
import ed.unicoach.db.dao.ExternalDocumentsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ExternalDocument
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.InstantSource
import java.time.LocalDate

/**
 * Which of the school's two published date bounds excluded a sitting.
 *
 * TWO CONSTRAINTS AND NOT ONE, and naming which one bound the answer is the
 * point: "registration has closed", "your scores would arrive after this
 * school's deadline" and "this school stopped counting sittings in December"
 * send a student to three different decisions. UT Knoxville is the measured
 * case -- it receives scores until May 15 and considers no sitting after
 * December 2026 -- so a receipt-deadline-only model would send that student to
 * a February retake worth nothing.
 */
enum class AwardDateBound {
  /** The school's receipt deadline: the scores would not BE there in time. */
  RECEIPT_DEADLINE,

  /** The latest sitting the school will consider: the scores would arrive and still not count. */
  LAST_SITTING_CONSIDERED,
}

/** One stored total from ONE date, and the date it was sat -- never a figure assembled from several. */
data class SittingScore(
  val test: AdmissionTest,
  val score: Int,
  /** The sitting at the precision the student actually remembers (RFC 209); a year-only sitting has no month to leak. */
  val sitting: PartialDate,
)

/**
 * OUR arithmetic over the school's stated rule, and never a score the school
 * holds (D-H).
 *
 * WE NEVER CALL THIS AN "ACT SUPERSCORE". ACT's Superscore is ACT-computed and
 * ACT-reported; this is a combination WE computed from sections the student told
 * us, offered against a school that says in writing it accepts one. The name of
 * this type is the rule.
 */
data class CombinedScore(
  val test: AdmissionTest,
  val score: Int,
  /** Which sittings each winning section came from, so the answer can say what it used. */
  val sittingsUsed: List<PartialDate>,
  /** Always [ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED]: nothing else permits a combination. */
  val rule: ScholarshipSuperscoreRule,
  /** For the ACT, the divisor ACT's own page publishes -- three enhanced subjects or four legacy ones. Null for the SAT, whose scale makes the sum the total. */
  val actSubjectsAveraged: Int?,
)

/**
 * Why no combination was computed. A NAMED reason and never a silent absence:
 * "this school reads one sitting" and "we cannot divide your history" are
 * different sentences, and one of them is about the school's money while the
 * other is about our arithmetic.
 */
sealed interface CombinationRefusal {
  /** The school's page does not say it accepts a combined score, so offering one would be money it will not pay. */
  data class RuleDoesNotPermit(
    val rule: ScholarshipSuperscoreRule,
  ) : CombinationRefusal

  /** No sections are stored for this test -- a sitting entered as a bare total contributes none. Absent data, not a refusal of the school's. */
  data object NoSectionsHeld : CombinationRefusal

  /**
   * The student's ACT sittings span the 2025 change and ACT publishes no divisor
   * for a mixed history. Choosing one would be our interpretation wearing ACT's
   * authority, so there is a reason here and no number.
   */
  data class MixedActSectionHistory(
    val sectionSets: List<Set<AdmissionTestScoreComponent>>,
  ) : CombinationRefusal

  /** A sitting carries some ACT sections and not a whole published set, so averaging it would average a section we do not hold. */
  data class IncompleteActSections(
    val sectionSets: List<Set<AdmissionTestScoreComponent>>,
  ) : CombinationRefusal

  /**
   * ACT's composite-rounding rule is ACT's published arithmetic, so it carries a
   * cited page like every other claim (RFC 220), and the combination is not
   * computed until that page is a row of `external_documents` that RESOLVED.
   *
   * WHICH of the three it was rides with the refusal, because they are three
   * different facts and one of them is ours to fix: [status] is null where we
   * hold no row at all ("nobody ever committed this citation"), `not_found` is a
   * page that has MOVED, and `unknown` is a sweep that never reached an answer
   * (RFC 182). [checkedOn] is the day the sweep looked, and is null for the same
   * no-row case -- a stored row always carries one.
   */
  data class ActRuleNotCited(
    val url: String,
    val status: SourceUrlStatus?,
    val checkedOn: LocalDate?,
  ) : CombinationRefusal
}

/**
 * Whether we offer a combined figure against this school's grid, or say why not.
 *
 * ONE VALUE, because the two are exclusive: a figure beside a refusal tells the
 * coach to withhold a number it is also handed, and neither of them is the
 * fourth state a nullable pair can hold -- no figure and no reason at all. The
 * tool's own contract depends on it ("Where combination_refusal is present there
 * is NO combined number"), so it is stated where a compiler reads it.
 */
sealed interface CombinationOutcome {
  /** OUR arithmetic, under the school's own stated rule. */
  data class Computed(
    val combined: CombinedScore,
  ) : CombinationOutcome

  /** No combination, and the named reason there is none. */
  data class Refused(
    val refusal: CombinationRefusal,
  ) : CombinationOutcome
}

/**
 * The cells at the lowest score above the one the student's figure reaches:
 * the slice's headline sentence.
 *
 * EVERY CELL AT THAT SCORE, for [StudentStanding.bandsReached]'s reason: a
 * two-dimensional score x GPA grid (Oklahoma State, Northern State) has one cell
 * per GPA row at a given score and WE HOLD NO GPA, so naming one of them picks a
 * GPA she never gave us -- and picks it in the one sentence that carries a
 * dollar figure. 106 committed score cells have two or more GPA rows paying
 * different money.
 */
data class NextBand(
  val bands: List<AutomaticAwardBand>,
  /** The gap, on the test's own scale, measured from the figure THE SCHOOL reads. */
  val pointsAway: Int,
  /**
   * What the step is worth, as the RANGE those cells pay over what she already
   * reaches -- null where any cell on either side publishes a KIND of award
   * rather than an amount, in which case the school's own words are the answer
   * and there is no gap to state.
   *
   * A word-priced cell is not a cell paying nothing. NINETEEN committed bands
   * are priced in the school's own words ("tuition and fees", "Presidential
   * Elite"), and they are the TOP awards: ranking them at zero names a school's
   * SECOND-best award as the next one up and computes the step against nothing.
   */
  val dollarStepPerYearUsd: IntRange?,
) {
  init {
    require(bands.isNotEmpty()) {
      "a next band is the cells at ONE score above her figure, so there is at least one of them: a NextBand with " +
        "no cell is an absence, and an absence is a null NextBand"
    }
  }
}

/**
 * Where this student stands in one school's grid, FOR ONE TEST.
 *
 * PER TEST, because the two scales are not comparable. The RFC sketched one
 * `bestSingleSitting` as `maxByOrNull` over the SAT_TOTAL and ACT_COMPOSITE
 * rows together; a 36 and a 1450 have no ordering, so that maximum would always
 * be the SAT row and a student's ACT would silently never be read. D13 says the
 * whole test family and the SAT is never the default noun, so the standing is
 * computed once per test the school publishes bands for.
 */
data class StudentStanding(
  val test: AdmissionTest,
  /** The best stored total from ONE date, or null where the student stored sections and no total. */
  val bestSingleSitting: SittingScore?,
  /** Our combination, or the named reason there is none. ONE value, because the two are exclusive. */
  val combination: CombinationOutcome,
  /**
   * Every cell [figureRead] reaches.
   *
   * A LIST AND NOT ONE CELL, because a two-dimensional score x GPA grid
   * (Oklahoma State, Northern State) has one cell per GPA row at a given score
   * and WE HOLD NO GPA. Naming one of them would be picking a GPA the student
   * never gave us; naming all of them is what the school published.
   */
  val bandsReached: List<AutomaticAwardBand>,
  val nextBand: NextBand?,
) {
  /**
   * The figure the SCHOOL's rule reads: the combination where one was permitted
   * and computed, otherwise the best single sitting.
   *
   * DERIVED AND NEVER STORED (CLAUDE.md), so it cannot disagree with the two
   * fields it summarises. It was a constructor field, which made "a combined
   * score, a refusal, and a third number contradicting both" a state this type
   * could hold.
   */
  val figureRead: Int?
    get() = figureReadOf(combination, bestSingleSitting)
}

/**
 * The figure a school's own rule reads, from the two values it is read from --
 * the ONE home for that rule.
 *
 * A file-private function and not two copies: [StudentStanding.figureRead]
 * derives it for every reader, and the standing's own producer needs the same
 * number BEFORE it has a standing to ask, which is precisely how a stored copy
 * came to be able to contradict its own inputs.
 */
private fun figureReadOf(
  combination: CombinationOutcome,
  bestSingleSitting: SittingScore?,
): Int? =
  when (combination) {
    is CombinationOutcome.Computed -> combination.combined.score
    is CombinationOutcome.Refused -> bestSingleSitting?.score
  }

/**
 * What we can say about one school's automatic award, for one entry year and one
 * residency basis.
 *
 * FIVE ARMS, and the whole point is that a family is sent to five different
 * places. [Expired] is not [NotCurated]: "we read this school and our reading is
 * stale" and "we never read this school" are different facts, and collapsing
 * them would turn a refresh into a silence.
 */
sealed interface AutomaticAwardAnswer {
  val collegeId: CollegeId

  /** We have not read this school. The coach's standing sentence still holds: it does not know, and it never estimates. */
  data class NotCurated(
    override val collegeId: CollegeId,
  ) : AutomaticAwardAnswer

  /** We read the page on `checked_on` and this school publishes no automatic award at all. A verified absence with a citation. */
  data class NonePublished(
    override val collegeId: CollegeId,
    val award: AutomaticAward,
  ) : AutomaticAwardAnswer

  /**
   * The school publishes an automatic table and a score changes NOTHING in it.
   * A different TRUE answer, and saying "we do not know" here would be false.
   */
  data class NoScoreEffect(
    override val collegeId: CollegeId,
    val award: AutomaticAward,
  ) : AutomaticAwardAnswer

  /** The row exists and is no longer believed: `expires_on` has passed (D7/D20). */
  data class Expired(
    override val collegeId: CollegeId,
    val award: AutomaticAward,
    val expiredOn: LocalDate,
  ) : AutomaticAwardAnswer

  /** A score is money here. What the SCHOOL published, and nothing about any student. */
  data class ScoreBands(
    override val collegeId: CollegeId,
    val award: AutomaticAward,
    /** The last sitting that clears BOTH of the school's bounds, or null where none does or none is published. */
    val lastUsableSitting: TestSitting?,
    /** Which of the school's bounds excluded the next sitting after [lastUsableSitting]. Empty when nothing later was excluded by the school. */
    val boundBy: Set<AwardDateBound>,
    /** Sittings whose registration has already closed -- a fact we hold, and it outranks everything about scores. */
    val closedSittings: List<TestSitting>,
    /** The school's cut-off in words where we will not parse it (UAB's per-test rule). It excludes no sitting and is quoted instead. */
    val quotedCutOff: String?,
  ) : AutomaticAwardAnswer
}

/**
 * One school's published grid AND where this student stands in it -- the
 * student-scoped envelope, returned only by [AutomaticAwardService.readInSession].
 *
 * THE STANDING IS NOT ON THE ANSWER, and that is the privacy ruling made
 * structural. [AutomaticAwardAnswer] is what the SCHOOL published; the shared
 * family cost report reads that door and CANNOT carry a standing, rather than
 * being trusted to pass an empty list at every call site (RFC 155 D-F, D-E).
 *
 * [standings] is empty exactly when we hold no figure of hers on a test this
 * school publishes bands for -- one meaning, not two.
 */
data class StudentAwardAnswer(
  val answer: AutomaticAwardAnswer,
  /** One standing per test the school publishes bands for and the student holds something on. Empty is an answer (0001 D11). */
  val standings: List<StudentStanding>,
) {
  val collegeId: CollegeId get() = answer.collegeId
}

/**
 * The whole answer for one student: one or more answers per college on the list,
 * and the ids that are not on it.
 *
 * MORE THAN ONE ANSWER PER COLLEGE IS NORMAL, and it is residency. Iowa State
 * and Kansas State gate an in-state award on a score and pay the out-of-state
 * award on GPA alone; UAB's last usable sitting is December for a resident and
 * the February ACT for a non-resident. Each answer names its own
 * `residencyBasis`, and THIS SERVICE DOES NOT PICK ONE: a student's residency
 * for tuition purposes is a legal status a school determines, not something
 * derivable from the state on a money profile, and picking wrong here names
 * money the school will not pay. So both grids ride, labelled, and the coach
 * states which applies.
 */
data class AutomaticAwardReading(
  val answers: List<StudentAwardAnswer>,
  /** Requested ids that are not on this student's active list -- reported rather than failing the whole read. */
  val unknownCollegeIds: List<CollegeId>,
)

/**
 * The curated automatic-award read (RFC 239) as a SEAM, for
 * [ed.unicoach.coaching.costs.canonical.CanonicalCostReader]'s reason: the two
 * callers that only RENDER what comes back -- the chat tool and the family cost
 * report's cost service -- must be substitutable at the database boundary, and
 * Kotlin classes are final, so injecting the concrete service was a `new` with
 * a different spelling.
 *
 * BOTH DOORS RIDE, because the two callers use different ones and the arm rule
 * behind them is the same: the student-scoped [getForStudent] and the
 * student-free [gridAnswersInSession], which cannot carry a standing at all.
 *
 * The one implementation is [AutomaticAwardService], and every rule stays there.
 */
interface AutomaticAwardReader {
  /**
   * The grids for [collegeIds] with NO STUDENT IN THE ANSWER. The CALLER owns
   * the [SqlSession], exactly as `CanonicalCostReader.read` does: the Family
   * Cost Report resolves a share token and then reads the whole profile on one
   * connection, so one read is one snapshot.
   */
  fun gridAnswersInSession(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): List<AutomaticAwardAnswer>

  /** This student's list, her grids, and where her own figures land in them. */
  suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>? = null,
  ): Result<AutomaticAwardReading>
}

/**
 * The read behind `college_automatic_awards` (RFC 239): what a named school on
 * this student's list publishes about its automatic merit grid, where the
 * student's own score lands in it, and the last sitting that still makes the
 * school's money.
 *
 * It is a new read and not a sixth section of `college_admissions_profile`
 * because that profile is an always-on whole-list payload and curated coverage
 * is a narrow set of schools, and because the answer needs clock arithmetic the
 * profile service has no clock for (D-B).
 *
 * THE DATE HALF REUSES THE LANDED ENGINE AND ADDS NOTHING.
 * [TestTimelineService.sittingOf] takes an arbitrary anchor and mentions no
 * college, application or CDS concept, so the scholarship receipt deadline
 * passes straight in and [SittingVerdict]/[TestSitting] come back unchanged --
 * including their ARM ORDER, which is a stated product decision on
 * [SittingVerdict] and is reused here, not re-decided.
 * [TestTimeline.Resolved]'s own anchor is application-typed and is deliberately
 * NOT widened; this service holds its own anchor, which is what keeps
 * [TestTimelineService.READ_STATEMENTS] untouched.
 *
 * The clock is an [InstantSource] and not a [java.time.Clock], for the reason
 * [ed.unicoach.coaching.coachToday] states: this service supplies the ZONE, so a
 * type that carried one would advertise a zone it discards. `LocalDate.now`
 * appears nowhere in this file.
 *
 * IT RENDERS NOTHING. Every verdict, refusal and absence is a typed value; the
 * words the coach reads live in [AutomaticAwardChatTool], where a rendering test
 * can reach them.
 */
class AutomaticAwardService(
  private val database: Database,
  private val clock: InstantSource = InstantSource.system(),
) : AutomaticAwardReader {
  override suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
  ): Result<AutomaticAwardReading> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, studentId, collegeIds) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed
      // (CollegeAdmissionsService's rule).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The whole read on ONE session: the student's list, the grids for those
   * schools, the student's live scores, the national test calendar and ACT's
   * own composite-rule citation. [READ_STATEMENTS] statements, whatever the
   * length of the list.
   *
   * THE NUMBER IS ASSERTED against the constant this sentence names, for the
   * reason `TestTimelineService` states: a statement count stated only in prose
   * is a claim no test can contradict (RFC 187).
   *
   * The calendar and the ACT citation are read unconditionally rather than per
   * answer, so a list of ten schools costs exactly what a list of one costs.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
  ): AutomaticAwardReading {
    val today = clock.coachToday()
    val selection = StudentCollegeSelection.read(session, studentId, collegeIds)
    val awards = AutomaticAwardsDao.listByColleges(session, selection.selected).getOrThrow()
    val scores = readTestScoresInSession(session, studentId)
    val calendar = AdmissionTestCalendarDao.list(session).getOrThrow()
    // FOUND, AND NOT MERELY PRESENT. `findByUrl` exists so this caller can see
    // the row's STATUS, and the existence check threw exactly that away. THE ROW
    // ITSELF TRAVELS, not a Boolean made from it: "no row", `not_found` and
    // `unknown` are three different repairs, and a refusal built from a Boolean
    // cannot say which one the family is waiting on.
    //
    // WHAT EACH STATE MEANS HERE, decided and encoded rather than left to a
    // null test. `not_found` is a page that no longer serves ACT's rule, so the
    // combination is refused with [CombinationRefusal.ActRuleNotCited] naming
    // the url -- we would otherwise be doing arithmetic worth four figures
    // under an authority that has moved. `unknown` is refused too, and that is
    // deliberately STRICTER than the link rule elsewhere in this repo (only
    // `not_found` drops an anchor): dropping an anchor is a display choice,
    // while this licenses OUR number under ACT's name, and `unknown` is "we
    // never asked, or we asked and got no usable answer" -- the state RFC 182
    // says must never read as a check that passed. The refusal is a named
    // reason the coach reads out, not a silence, and her single best sitting
    // still stands.
    val actRuleCitation = ExternalDocumentsDao.findByUrl(session, ACT_COMPOSITE_RULE_URL).getOrThrow()

    // A REFUSAL WINS OVER ROWS (brief 0010 D24). A student who declined to share
    // scores has none in the coach's context by design, so the grid is answered
    // with no standing at all -- the school's bands, its requirement, its
    // deadline -- and nothing here re-asks.
    val usableScores = if (scores.status == AnswerStatus.DECLINED) emptyList() else scores.scores

    val byCollege = awards.groupBy { it.collegeId }
    val answers =
      selection.selected.flatMap { collegeId ->
        val rows = byCollege[collegeId].orEmpty()
        if (rows.isEmpty()) {
          listOf(StudentAwardAnswer(AutomaticAwardAnswer.NotCurated(collegeId), emptyList()))
        } else {
          // THE SCHOOL'S ANSWER FIRST, THE STUDENT'S STANDING SECOND, and they
          // are two values: the grid is the same one the student-free door
          // answers with, and the standing is what this door adds because this
          // door has a student.
          rows.map { award ->
            val answer = answerFor(award, today, calendar)
            StudentAwardAnswer(answer, standingsFor(answer, usableScores, actRuleCitation))
          }
        }
      }
    return AutomaticAwardReading(answers = answers, unknownCollegeIds = selection.unknown)
  }

  /**
   * The grids for [collegeIds] with NO STUDENT IN THE ANSWER -- the family cost
   * report's entry point (D-E).
   *
   * ONE HOME FOR THE ARM RULE. "Expired outranks the kind, a `none_published`
   * row is not a silence, a `gpa_only_grid` row is a true answer and not a
   * refusal" is a correctness rule, and a second `when` over `award_kind` in the
   * cost service would be a second place for it to be decided differently. So
   * the report calls this and renders what comes back.
   *
   * NO STANDING CAN RIDE HERE AT ALL, BY TYPE AND NOT BY CONVENTION. The shared
   * report page holds no test score and a logged-out parent reads it, so
   * "your student scored 1250, so this school pays $6,000" there would be a new
   * privacy ruling rather than an inherited one (RFC 155 D-F). This path never
   * reads a score, and [AutomaticAwardAnswer] has nowhere to put one: a standing
   * exists only inside [StudentAwardAnswer], which only [readInSession] builds.
   *
   * The DATE half is still computed and is still honest: the last usable sitting
   * is a fact about the SCHOOL's deadline against the national calendar and says
   * nothing about the student.
   *
   * [GRID_READ_STATEMENTS] statements, whatever the length of the list.
   */
  override fun gridAnswersInSession(
    session: SqlSession,
    collegeIds: Collection<CollegeId>,
  ): List<AutomaticAwardAnswer> {
    if (collegeIds.isEmpty()) return emptyList()
    val today = clock.coachToday()
    val awards = AutomaticAwardsDao.listByColleges(session, collegeIds).getOrThrow()
    if (awards.isEmpty()) return emptyList()
    val calendar = AdmissionTestCalendarDao.list(session).getOrThrow()
    // ACT'S CITATION IS NOT READ ON THIS PATH, and it cannot matter: no score
    // rides here, so no standing is built and no combination is ever attempted.
    // Asking would be a statement per cost report whose answer nothing can
    // consume.
    return awards.map { award -> answerFor(award, today, calendar) }
  }

  /**
   * WHAT THE SCHOOL PUBLISHED, for one award row, on this day. No student, no
   * score, and no standing -- both doors compute the answer here, and only the
   * student-scoped one adds a standing to it.
   */
  private fun answerFor(
    award: AutomaticAward,
    today: LocalDate,
    calendar: List<ed.unicoach.db.models.AdmissionTestAdministration>,
  ): AutomaticAwardAnswer {
    // `>` AND NOT `>=`: a row is believed THROUGH its expires_on, the predicate
    // the two landed corpora already share (TestTimelineService,
    // FafsaDeadlineService).
    if (today > award.expiresOn) {
      return AutomaticAwardAnswer.Expired(award.collegeId, award, award.expiresOn)
    }
    return when (award.awardKind) {
      AutomaticAwardKind.NONE_PUBLISHED -> AutomaticAwardAnswer.NonePublished(award.collegeId, award)
      AutomaticAwardKind.GPA_ONLY_GRID -> AutomaticAwardAnswer.NoScoreEffect(award.collegeId, award)
      AutomaticAwardKind.SCORE_GRID -> scoreBandsFor(award, today, calendar)
    }
  }

  /**
   * Where this student stands in the grid this answer carries -- one standing
   * per test the school publishes bands for and she holds something on.
   *
   * EMPTY ON EVERY ARM BUT [AutomaticAwardAnswer.ScoreBands], and that is not a
   * shortcut: a school whose table a score changes nothing in, a school that
   * publishes no award, a stale reading and a school we never read all have no
   * cell for a figure to land in, and inventing a standing there would be a
   * position in a grid that does not exist.
   */
  private fun standingsFor(
    answer: AutomaticAwardAnswer,
    scores: List<RenderedTestScore>,
    actRuleCitation: ExternalDocument?,
  ): List<StudentStanding> {
    if (answer !is AutomaticAwardAnswer.ScoreBands) return emptyList()
    val award = answer.award
    // THE TESTS THIS SCHOOL PUBLISHES A RANGE ON, which is what a standing can
    // be held against. The published no-score row is a cell of the grid and
    // names no test, so it contributes nothing here -- and now says so as a
    // member rather than as a null this filter had to drop.
    val bandTests = award.bands.mapNotNull { (it.score as? BandScoreAxis.Range)?.test }.toSet()
    return AdmissionTest.entries
      .filter { it in bandTests }
      .mapNotNull { test -> standingFor(test, award, scores, actRuleCitation) }
  }

  private fun scoreBandsFor(
    award: AutomaticAward,
    today: LocalDate,
    calendar: List<ed.unicoach.db.models.AdmissionTestAdministration>,
  ): AutomaticAwardAnswer.ScoreBands {
    val sittings =
      calendar
        .filter { !it.testDate.isBefore(today) && !it.testDate.isAfter(today.plus(TestTimelineService.SITTINGS_HORIZON)) }
        .map { TestTimelineService.sittingOf(it, today, award.scoreDeadline) }
        .sortedBy { it.testDate }

    val throughDay = award.lastSittingThroughDay
    // BOTH CONSTRAINTS, AND THE TIGHTER ONE WINS. A sitting is usable only if
    // its score release is not after the school's receipt deadline AND its test
    // date is not after the last sitting the school will consider. The second is
    // the one a receipt-deadline-only model gets wrong.
    val usable =
      sittings.filter { sitting ->
        sitting.usableForAnchor == true && (throughDay == null || !sitting.testDate.isAfter(throughDay))
      }
    val lastUsable = usable.maxByOrNull { it.testDate }

    val boundBy = boundsOn(sittings, lastUsable, award.scoreDeadline, throughDay)

    return AutomaticAwardAnswer.ScoreBands(
      collegeId = award.collegeId,
      award = award,
      lastUsableSitting = lastUsable,
      boundBy = boundBy,
      closedSittings = sittings.filter { !it.registrationOpen },
      quotedCutOff = (award.lastSittingConsidered as? LastSittingConsidered.InWords)?.asPublished,
    )
  }

  /**
   * Which of the school's bounds excluded the EARLIEST sitting later than
   * [lastUsable] -- the sitting a student would otherwise reach for next.
   *
   * It answers a `Set` because both bounds can exclude the same Saturday, and
   * naming only one of them would send a student to a school page that repeats
   * the other. It is empty when nothing later was excluded BY THE SCHOOL: the
   * horizon simply ran out, or the school published no bound at all, and
   * claiming a bound there would be our silence wearing the school's authority.
   */
  private fun boundsOn(
    sittings: List<TestSitting>,
    lastUsable: TestSitting?,
    deadline: LocalDate?,
    throughDay: LocalDate?,
  ): Set<AwardDateBound> {
    val later = sittings.filter { lastUsable == null || it.testDate.isAfter(lastUsable.testDate) }
    for (sitting in later) {
      val bounds = mutableSetOf<AwardDateBound>()
      val verdict = sitting.verdict
      if (deadline != null && verdict is SittingVerdict.ScoresCompared && verdict.release.isAfter(deadline)) {
        bounds += AwardDateBound.RECEIPT_DEADLINE
      }
      if (throughDay != null && sitting.testDate.isAfter(throughDay)) {
        bounds += AwardDateBound.LAST_SITTING_CONSIDERED
      }
      if (bounds.isNotEmpty()) return bounds
    }
    return emptySet()
  }

  /** This student's standing in one school's grid on ONE test, or null where they hold no figure on it. */
  private fun standingFor(
    test: AdmissionTest,
    award: AutomaticAward,
    scores: List<RenderedTestScore>,
    actRuleCitation: ExternalDocument?,
  ): StudentStanding? {
    // A STANDING EXISTS EXACTLY WHEN WE HOLD SOMETHING OF HERS ON THIS TEST, and
    // that is the test, not "we produced a figure". A student who stored ACT
    // SECTIONS and no composite, whose history spans the 2025 change, has no
    // figure at all -- and the honest answer there is
    // `MixedActSectionHistory`, which is precisely what a "no figure, no
    // standing" rule would compute and then drop on the floor. She would be told
    // nothing, when what we owe her is "we hold your sections and cannot combine
    // them, because ACT publishes no divisor for a history like yours".
    //
    // It cannot be "no figure AND no refusal" either: `RuleDoesNotPermit` is
    // returned for every single-sitting school whether or not the student stored
    // anything, so that rule would invent a standing for a student we hold no
    // score for at all.
    val onTest = scores.filter { it.component.test == test }
    if (onTest.isEmpty()) return null

    val best = bestSingleSittingOf(test, onTest)
    val combination = combinationFor(test, award, onTest, actRuleCitation)

    // THE BAND IS RESOLVED FROM THE FIGURE THE SCHOOL PERMITS: the combination
    // where one was computed, and the single sitting for single_sitting_only and
    // unstated. Measuring the gap from any other figure would send a student to
    // a retake they do not need, or hide one they do. The rule is stated ONCE,
    // on [StudentStanding.figureRead], and read from there rather than computed
    // a second time here.
    val figure = figureReadOf(combination, best)
    val bandsOnTest = rangesOn(test, award)
    val reached = cellsReached(bandsOnTest, figure)
    return StudentStanding(
      test = test,
      bestSingleSitting = best,
      combination = combination,
      bandsReached = reached,
      nextBand = if (figure == null) null else nextBandOf(bandsOnTest, noScoreCellsOf(award), reached, figure),
    )
  }

  /** The best total the student reported for ONE date on this test, or null where she stored sections only. */
  private fun bestSingleSittingOf(
    test: AdmissionTest,
    onTest: List<RenderedTestScore>,
  ): SittingScore? {
    val totalComponent = if (test == AdmissionTest.SAT) AdmissionTestScoreComponent.SAT_TOTAL else AdmissionTestScoreComponent.ACT_COMPOSITE
    return onTest
      .filter { it.component == totalComponent }
      .maxByOrNull { it.score.score }
      ?.let { SittingScore(test = test, score = it.score.score, sitting = it.sitting) }
  }

  /**
   * THE CELL AND ITS RANGE, CARRIED TOGETHER -- every cell of this grid that
   * names a range on THIS test.
   *
   * The published no-score row is a cell of every grid ([BandScoreAxis.NoScore])
   * and no student is measured against it: it is the tier reached by holding no
   * score at all, so a standing on a TEST reads the ranges on that test and
   * nothing else.
   */
  private fun rangesOn(
    test: AdmissionTest,
    award: AutomaticAward,
  ): List<Pair<AutomaticAwardBand, BandScoreAxis.Range>> =
    award.bands.mapNotNull { band ->
      (band.score as? BandScoreAxis.Range)?.takeIf { it.test == test }?.let { band to it }
    }

  /**
   * The school's published NO-SCORE row, where it publishes one: the tier a
   * student who submits nothing lands in.
   *
   * IT IS WHAT THIS SCHOOL ALREADY PAYS A STUDENT WHO REACHES NO BAND, which is
   * why [nextBandOf] needs it -- it is a cell of the same grid, not an absence.
   */
  private fun noScoreCellsOf(award: AutomaticAward): List<AutomaticAwardBand> = award.bands.filter { it.score is BandScoreAxis.NoScore }

  /** Every cell this figure lands in, and none at all where we hold no figure. */
  private fun cellsReached(
    bandsOnTest: List<Pair<AutomaticAwardBand, BandScoreAxis.Range>>,
    figure: Int?,
  ): List<AutomaticAwardBand> =
    if (figure == null) {
      emptyList()
    } else {
      bandsOnTest.filter { (_, range) -> figure in range.low..range.high }.map { it.first }
    }

  /**
   * The cells at the lowest score above the student's figure, with the point gap
   * and the dollar step -- the slice's headline sentence.
   *
   * ABSENT in the top band, absent with no stored figure, and absent where the
   * school is not a score grid (this is only reached from [scoreBandsFor]).
   *
   * EVERY CELL AT THAT SCORE RIDES, and the step is a RANGE, because a
   * two-dimensional grid has one cell per GPA row and we hold no GPA. The step
   * is measured against the best cell the figure already reaches -- the one she
   * could be earning today -- and is withheld entirely where any cell on either
   * side publishes a KIND of award rather than an amount, because those cells
   * are the school's TOP awards and pricing them at zero is how a second-best
   * award gets named as the next one up.
   *
   * WHAT SHE EARNS TODAY IS READ FROM THE WHOLE GRID, [noScoreCells] included.
   * A student below every published band reaches no cell on this test, and the
   * school's own no-score row may still be paying her: measuring the step from
   * zero there prices "she reaches no band" as "a band paying $0" and names the
   * whole next award as the gap.
   */
  private fun nextBandOf(
    onTest: List<Pair<AutomaticAwardBand, BandScoreAxis.Range>>,
    noScoreCells: List<AutomaticAwardBand>,
    reached: List<AutomaticAwardBand>,
    figure: Int,
  ): NextBand? {
    val nextScore = onTest.filter { it.second.low > figure }.minOfOrNull { it.second.low } ?: return null
    // THE CELLS AT THAT SCORE AND NEVER ONE OF THEM, which is the rule
    // `bandsReached` already states: several cells at one score means several GPA
    // rows, and we hold no GPA. The old tie-break picked the best-paying row and
    // rendered it as the step, which is a figure her GPA may not earn.
    val cells = onTest.filter { it.second.low == nextScore }.map { it.first }
    // A cell published in WORDS is not a cell paying nothing. Where either side
    // of the step is unpriced the STEP is unpriced, and the coach reads the
    // school's own words out instead of a gap we made up.
    val payable = cells.mapNotNull { it.awardPerYearUsd }.takeIf { it.size == cells.size }
    // THE CELLS SHE IS IN TODAY, and where her figure reaches none of them that
    // is the school's published no-score row: an undetermined "what does she
    // earn now" is not a zero, and this school may be paying that row on her
    // grade point average alone.
    val earnsToday = reached.ifEmpty { noScoreCells }
    val paidToday = earnsToday.map { it.awardPerYearUsd }
    val currentBest =
      when {
        // NO CELL OF THIS GRID AT ALL, no-score row included: this school pays
        // her nothing today, and that is the school's own answer rather than our
        // fallback.
        earnsToday.isEmpty() -> EARNS_NOTHING_YET_USD

        // A cell published in WORDS is not a cell paying nothing, on this side of
        // the step exactly as on the other.
        paidToday.any { it == null } -> null

        else -> paidToday.filterNotNull().max()
      }
    val step =
      if (payable == null || currentBest == null) {
        null
      } else {
        IntRange(payable.min() - currentBest, payable.max() - currentBest)
      }
    return NextBand(bands = cells, pointsAway = nextScore - figure, dollarStepPerYearUsd = step)
  }

  /**
   * Our combination for one test, or the named reason there is none (D-H).
   *
   * Three guardrails, and each one is a way to be confidently wrong:
   * only where the school's page says it accepts one; only sections we hold; and
   * for the ACT, only under ACT's own published divisor and rounding, cited.
   */
  private fun combinationFor(
    test: AdmissionTest,
    award: AutomaticAward,
    scores: List<RenderedTestScore>,
    actRuleCitation: ExternalDocument?,
  ): CombinationOutcome {
    if (award.superscoreRule != ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED) {
      return CombinationOutcome.Refused(CombinationRefusal.RuleDoesNotPermit(award.superscoreRule))
    }
    return when (test) {
      AdmissionTest.SAT -> satCombination(scores)
      AdmissionTest.ACT -> actCombination(scores, actRuleCitation)
    }
  }

  /**
   * The SAT combination: the highest `sat_reading_writing` plus the highest
   * `sat_math`, across sittings.
   *
   * IT IS ARITHMETIC AND NOTHING IS ESTIMATED. The scale makes that sum the
   * total by definition ([ed.unicoach.db.models.PublishedScoreScale]), so no
   * citation is needed beyond the scale itself. The objection that a stored
   * TOTAL may itself be a student-typed superscore is an objection to combining
   * totals; `admission_test_scores` stores one row per COMPONENT per sitting
   * (RFC 207), so the section-level combination does not touch it.
   */
  private fun satCombination(scores: List<RenderedTestScore>): CombinationOutcome {
    val readingWriting = scores.filter { it.component == AdmissionTestScoreComponent.SAT_READING_WRITING }.maxByOrNull { it.score.score }
    val math = scores.filter { it.component == AdmissionTestScoreComponent.SAT_MATH }.maxByOrNull { it.score.score }
    if (readingWriting == null || math == null) return CombinationOutcome.Refused(CombinationRefusal.NoSectionsHeld)
    return CombinationOutcome.Computed(
      CombinedScore(
        test = AdmissionTest.SAT,
        score = readingWriting.score.score + math.score.score,
        sittingsUsed = listOf(readingWriting.sitting, math.sitting).distinct(),
        rule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED,
        actSubjectsAveraged = null,
      ),
    )
  }

  /**
   * The ACT combination, under ACT's own published rule and not ours.
   *
   * THREE CONSEQUENCES OF THE 2025 CHANGE, and each one is a defect if missed:
   *
   * 1. Science LEFT the Composite between April and September 2025 for national
   *    testing, so the divisor is THREE for an enhanced sitting and FOUR for a
   *    legacy one.
   * 2. A MIXED history has no published divisor. ACT states both rules and never
   *    says which wins for a student whose sittings span the change, so a mixed
   *    set is refused with a named reason and NO number -- choosing a divisor
   *    there would be our interpretation wearing ACT's authority.
   * 3. Rounding is half-UP and never half-even: 28.5 must become 29. The wrong
   *    mode is silent and costs a band.
   *
   * And the rule itself is a CITED claim: the combination is not computed while
   * [ACT_COMPOSITE_RULE_URL] is absent from `external_documents`, so the
   * citation cannot silently go missing.
   */
  private fun actCombination(
    scores: List<RenderedTestScore>,
    actRuleCitation: ExternalDocument?,
  ): CombinationOutcome {
    val sections = scores.filter { it.component in ACT_SECTION_COMPONENTS }
    if (sections.isEmpty()) return CombinationOutcome.Refused(CombinationRefusal.NoSectionsHeld)
    // FOUND, AND NOT MERELY PRESENT -- and WHICH not-found it is rides with the
    // refusal, because a page that has MOVED is a citation to repair and a page
    // nobody swept is a sweep to run.
    if (actRuleCitation?.urlStatus != SourceUrlStatus.FOUND) {
      return CombinationOutcome.Refused(
        CombinationRefusal.ActRuleNotCited(
          url = ACT_COMPOSITE_RULE_URL,
          status = actRuleCitation?.urlStatus,
          checkedOn = actRuleCitation?.urlCheckedOn,
        ),
      )
    }

    val bySitting = sections.groupBy { it.sitting }
    val sets = bySitting.values.map { rows -> rows.map { it.component }.toSet() }
    val distinctSets = sets.distinct()
    if (distinctSets.size > 1) return CombinationOutcome.Refused(CombinationRefusal.MixedActSectionHistory(distinctSets))
    val set = distinctSets.single()
    if (set != ENHANCED_ACT_SECTIONS && set != LEGACY_ACT_SECTIONS) {
      return CombinationOutcome.Refused(CombinationRefusal.IncompleteActSections(distinctSets))
    }

    val winners = set.map { component -> sections.filter { it.component == component }.maxBy { it.score.score } }
    val total = winners.sumOf { it.score.score }
    // HALF-UP, ACT's own words: "Fractions less than one-half are rounded down;
    // fractions one-half or more are rounded up." BigDecimal with an explicit
    // RoundingMode and never Kotlin's `round` on a Double, whose tie behaviour
    // is not this one.
    val composite =
      BigDecimal(total)
        .divide(BigDecimal(set.size), ACT_COMPOSITE_DECIMALS, RoundingMode.HALF_UP)
        .toInt()
    return CombinationOutcome.Computed(
      CombinedScore(
        test = AdmissionTest.ACT,
        score = composite,
        sittingsUsed = winners.map { it.sitting }.distinct(),
        rule = ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED,
        actSubjectsAveraged = set.size,
      ),
    )
  }

  companion object {
    /**
     * ACT's own page for the composite rule and its rounding, read on
     * 2026-09-24 and committed in `db/seed/automatic-awards/link-status.csv`
     * with no award row citing it -- which is exactly why the loader upserts
     * EVERY url in that file and not only the cited ones.
     *
     * ACT's higher-education-professionals page still prints the stale
     * four-subject sentence with no qualifier and MUST NOT be cited for this
     * rule.
     */
    const val ACT_COMPOSITE_RULE_URL: String =
      "https://www.act.org/content/act/en/products-and-services/the-act/scores/understanding-your-scores.html"

    /** The current ("enhanced") Composite: English, math and reading, divided by three. */
    val ENHANCED_ACT_SECTIONS: Set<AdmissionTestScoreComponent> =
      setOf(
        AdmissionTestScoreComponent.ACT_ENGLISH,
        AdmissionTestScoreComponent.ACT_MATH,
        AdmissionTestScoreComponent.ACT_READING,
      )

    /** The legacy Composite: the same three plus science, divided by four. */
    val LEGACY_ACT_SECTIONS: Set<AdmissionTestScoreComponent> = ENHANCED_ACT_SECTIONS + AdmissionTestScoreComponent.ACT_SCIENCE

    private val ACT_SECTION_COMPONENTS: Set<AdmissionTestScoreComponent> = LEGACY_ACT_SECTIONS

    /**
     * ACT reports a whole-number Composite on a 1-36 scale, so the average is
     * taken to no decimals -- ACT's own scale, and not a rounding of ours. Named
     * because a positional `0` in a `divide` call is where that fact is least
     * visible, beside the rounding MODE the comment there already states.
     */
    private const val ACT_COMPOSITE_DECIMALS: Int = 0

    /**
     * What a student reaching NO cell of a school's grid -- not even its
     * published no-score row -- is already earning from it: nothing.
     *
     * NAMED, because it is the baseline every published dollar step is measured
     * from and it is the school's own answer, not a defensive fallback. The case
     * it is NOT is "she reaches no score band while the school's no-score row
     * pays her", which [nextBandOf] answers from that row instead.
     */
    private const val EARNS_NOTHING_YET_USD: Int = 0

    /**
     * What this read costs, in prepared statements: the two the college
     * selection takes, the two this corpus takes (awards and their bands), the
     * two the score read takes (the live decline and the live rows), the
     * calendar, and ACT's composite-rule citation.
     *
     * ABSOLUTE, and asserted. "Ten colleges cost what one college costs" stays
     * true when a ninth read is added, so a relative assertion alone would let
     * this read grow silently past the number [readInSession]'s own KDoc
     * promises.
     */
    const val READ_STATEMENTS: Int = 8

    /**
     * What [gridAnswersInSession] costs: the two this corpus takes (awards and
     * their bands), and the calendar. No student is read on that path at all --
     * the privacy ruling, not the budget -- and ACT's composite-rule citation is
     * not read either, because with no score there is no combination for it to
     * license.
     *
     * ABSOLUTE, and asserted, for [READ_STATEMENTS]'s reason.
     */
    const val GRID_READ_STATEMENTS: Int = 3
  }
}
