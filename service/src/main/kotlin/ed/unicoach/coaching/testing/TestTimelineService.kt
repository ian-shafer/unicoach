package ed.unicoach.coaching.testing

import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.StudentCollegeSelection
import ed.unicoach.coaching.admissions.ApplicationCycle
import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.coachToday
import ed.unicoach.coaching.graduationDateOf
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdmissionTestCalendarDao
import ed.unicoach.db.dao.CdsAdmissionsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestAdministration
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeDeadline
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ScoreRelease
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.Period
import java.time.Year
import java.time.YearMonth

/**
 * The window a LIVE calendar is inside: which cycles it names, and the EARLIEST
 * expiry any still-ahead row carries, because the answer is only as current as
 * its least current row.
 *
 * IT DESCRIBES A LIVE CALENDAR AND NOTHING ELSE, which is why [expiresOn] is not
 * nullable and there is no `expired` flag. The previous shape was
 * `(cycle: String?, expiresOn: LocalDate?, expired: Boolean)`, and it let a
 * stale date sit beside `expired = false` and compile -- three fields that had
 * to agree, checked by nobody. An expired or empty calendar is the OTHER arm of
 * [TestTimeline], so a window that exists at all is a window we are inside.
 *
 * [cycles] is a LIST because several cycles can be present at once: the refresh
 * appends the next one beside the last, so both are in the table for a year. It
 * used to be a `", "`-joined `String?` -- three different meanings ("no rows",
 * "one cycle", "several") in one nullable string, which the renderer then had to
 * hand the model as a sentence fragment.
 */
data class CalendarWindow(
  /** The academic years the publishers label these sittings with. Several present are all named, rather than one chosen. */
  val cycles: List<String>,
  val expiresOn: LocalDate,
)

/** Why a read can name no date at all (D7). Two genuinely different facts, and the coach says a different sentence for each. */
enum class NoCalendarReason {
  /** We held a calendar and no longer believe it. "Our calendar is out of date" is TRUE here. */
  EXPIRED,

  /**
   * There is no calendar at all. "Our calendar is out of date" would be a
   * FALSE statement about a table with nothing in it -- a claim about a
   * document that does not exist -- so this arm says what is actually so.
   */
  EMPTY,
}

/** One stored round, once we have tried to give it a year: either a dated deadline or a located refusal. Never a null. */
sealed interface DeadlineCandidate

/**
 * One school's round, resolved to a real date: the month and day the school
 * published, with the year WE supplied written down beside it (RFC 197 section
 * 5).
 *
 * Both halves ride, and so does [graduationYear]: the school published no year,
 * so the resolved date is ours, and a wire that carried only the resolved one
 * would let the coach attribute our arithmetic to the school. The SENTENCE that
 * says so is not built here -- this is data, and the words are the renderer's.
 */
data class ResolvedDeadline(
  val collegeId: CollegeId,
  val collegeName: String,
  val round: ApplicationRound,
  /** Exactly what the school published: "November 1". No year, because it publishes none. */
  val closingAsPublished: String,
  /** The date we resolved it to, for this student's application cycle. */
  val closingResolved: LocalDate,
  /** The student's own graduation year -- the input the resolved year came from, so the renderer can say whose year it is. */
  val graduationYear: Year,
  val citation: CdsCitation,
) : DeadlineCandidate

/** Why one stored round could not become a dated deadline. Four cases, none of them "nothing was there". */
enum class AnchorRejectionReason {
  /** The school states it does not run the round. Its `offered` flag is the reliable bit; a date under it is not a deadline. */
  ROUND_NOT_OFFERED,

  /** The school reported no closing month at all for the round. */
  NO_CLOSING_MONTH,

  /** The school published a month with NO day -- legal CDS reporting, and not completed with a guessed day. */
  MONTH_WITHOUT_DAY,

  /**
   * The published month and day are a real calendar pair but not a real day of
   * the year WE resolved them into: 29 February in a non-leap year.
   * [CdsMonthDay] validates against a LEAP year on purpose, because a CDS date
   * carries no year, so this pair is storable and this case is reachable in
   * three years out of four.
   */
  DAY_NOT_IN_RESOLVED_YEAR,
}

/**
 * One round that could not be an anchor, and WHY -- located, by school and by
 * round.
 *
 * It exists because the alternative is a lie. The rejections used to be a bare
 * `null` from a `mapNotNull`, and the general sequence then asserted "there is
 * none on this student's list to work back from" -- which is false for a student
 * whose school published "January" with no day. That is an invented claim, in
 * the read built to prevent invented claims.
 */
data class AnchorRejection(
  val collegeId: CollegeId,
  val collegeName: String,
  val round: ApplicationRound,
  val reason: AnchorRejectionReason,
  /** What the school did publish, where it published anything: "January, day not reported". Null when it published no month. */
  val closingAsPublished: String?,
  /** The year we resolved into, on the one reason that turns on it ([AnchorRejectionReason.DAY_NOT_IN_RESOLVED_YEAR]). */
  val resolvedYear: Year?,
) : DeadlineCandidate

/**
 * What a sitting IS, for this student's anchor. ONE value, and every verdict and
 * every sentence about the sitting is derived from it.
 *
 * IT REPLACED TWO PARALLEL `when` CHAINS, and that is the whole reason it
 * exists. The service used to walk the same four conditions twice -- once for a
 * `Boolean?` and once for the sentence -- and the fourth sentence arm read the
 * BOOLEAN, so the second chain silently depended on the first. Reordering one
 * and not the other produced a sitting whose sentence contradicted its own
 * verdict, and the safety property this feature turns on (a closed-registration
 * sentence never mentions scores) lived exactly in that pairing. Here there is
 * one decision, made once, and both the verdict and the words are functions OF
 * it.
 *
 * THE ARM ORDER IS DECIDED AND IS NOT AN IMPLEMENTATION DETAIL. A closed
 * registration answers BEFORE an unannounced score release, because a closed
 * registration is a fact we HOLD: reporting "we cannot say" there would hide a
 * certainty behind an unknown, which is the opposite of what this read is for.
 * The publisher's silence still travels on its own -- `score_release_date` is an
 * explicit null and the sitting is named in `data_availability` -- so the two
 * facts are independent and both survive.
 */
sealed interface SittingVerdict {
  /**
   * TRUE, FALSE, or "we cannot say" -- DERIVED, in one `when` with no `else`, so
   * a fifth verdict cannot ship without deciding what it means.
   *
   * `null` is not a convenience. It is the honest answer both when there is no
   * anchor to compare against and when the publisher has announced no score
   * release. Collapsing either into `false` would tell a family a sitting is too
   * late when nobody knows that, and into `true` would promise a date nobody
   * published.
   */
  val usableForAnchor: Boolean?
    get() =
      when (this) {
        is NoAnchor -> null
        is RegistrationClosed -> false
        is ScoreReleaseUnannounced -> null
        is ScoresCompared -> !release.isAfter(anchor)
      }

  /** There is no resolved deadline to compare against, so this sitting is neither usable nor unusable. */
  data object NoAnchor : SittingVerdict

  /** Registration closed before today. A fact we hold, and it outranks everything below it. */
  data class RegistrationClosed(
    val deadline: LocalDate,
    val today: LocalDate,
  ) : SittingVerdict

  /** The publisher has announced no score release for this sitting, so whether the scores would arrive is not ours to say. */
  data class ScoreReleaseUnannounced(
    val test: AdmissionTest,
    val testDate: LocalDate,
  ) : SittingVerdict

  /** The comparison this whole read exists for: the announced release against the resolved deadline. */
  data class ScoresCompared(
    val release: LocalDate,
    val anchor: LocalDate,
  ) : SittingVerdict
}

/** One future sitting, with the subtraction already done for it. */
data class TestSitting(
  val test: AdmissionTest,
  val testDate: LocalDate,
  val registrationDeadline: LocalDate,
  val lateRegistrationDeadline: LocalDate?,
  /** The announced release and its citation, or null when the publisher has announced none. */
  val scoreRelease: ScoreRelease?,
  /** Whether registration is still open: the deadline is today or later. The day a family has to act on. */
  val registrationOpen: Boolean,
  /** What this sitting is, for this student's anchor -- and the ONE thing every sentence about it is built from. */
  val verdict: SittingVerdict,
  /**
   * DERIVED: [registrationDeadline] minus [TestTimelineService.FEE_WAIVER_LEAD],
   * College Board's own "ideally 1-2 weeks before the registration deadline".
   * Flagged as derived on the wire, because it is our guidance and not a
   * published deadline -- but it is still a deadline of its own, and an earlier
   * one.
   */
  val feeWaiverBy: LocalDate,
) {
  /** [SittingVerdict.usableForAnchor], forwarded so no caller has to reach through the verdict for the answer. */
  val usableForAnchor: Boolean? get() = verdict.usableForAnchor
}

/** A sitting the publisher has announced no score release for, named so the silence is reported rather than inferred. */
data class UnannouncedScoreRelease(
  val test: AdmissionTest,
  val testDate: LocalDate,
)

/** The answer. Two cases, because with no live calendar there are no dates to answer with at all (D7). */
sealed interface TestTimeline {
  /**
   * There is no calendar to answer from, so this read names no date of its own
   * (D7).
   *
   * IT CARRIES NO `asOf` AND NO WINDOW, deliberately. The refusal exists to
   * withhold dates, and a shape that forced today's date onto it left only the
   * renderer's discipline between that date and the wire. There is nothing here
   * to leak: the cycles the publishers labelled what we held, and which of the
   * two things went wrong.
   */
  data class NoCalendar(
    val cycles: List<String>,
    val reason: NoCalendarReason,
  ) : TestTimeline

  /** The calendar is current, so the subtraction is real. */
  data class Resolved(
    val asOf: LocalDate,
    val calendar: CalendarWindow,
    val yearOfSchool: HighSchoolYear,
    /** The earliest deadline still ahead, or null when there is none to work back from. */
    val anchor: ResolvedDeadline?,
    /** Every round on the list that could not become one, and why. Never a silent drop. */
    val anchorRejections: List<AnchorRejection>,
    /**
     * The deadlines on this list that have ALREADY closed. The single most
     * decision-relevant fact this read computes for a late student, and it used
     * to be discarded by the `filter` that found the anchor.
     */
    val closedDeadlines: List<ResolvedDeadline>,
    val sittings: List<TestSitting>,
    /** The sittings whose score release the publisher has not announced. */
    val dataAvailability: List<UnannouncedScoreRelease>,
    val unknownCollegeIds: List<CollegeId>,
    /**
     * What this student's own STATE does about the school-day SAT or ACT (RFC
     * 208) -- one typed value, never a nullable row plus a reason.
     *
     * It is NOT nullable, because there is no case in which this read has
     * nothing to say: a student with no money profile, a declined residency
     * question and a state we hold no row for are three different ANSWERS
     * ([SchoolDayProgramme]), and a null here would collapse them into the one
     * silence the model fills for itself.
     */
    val schoolDayProgramme: SchoolDayProgramme,
  ) : TestTimeline
}

/**
 * The read behind `admission_test_timeline` (RFC 197): from the earliest real
 * deadline on the student's own list, backwards through score release and
 * registration, to the date they have to act.
 *
 * It is a new read and not a fifth section of `college_admissions_profile`
 * because the two answer different questions -- "what does this school want"
 * versus "what must I do, and by when" -- and because that service's statement
 * budget is asserted absolutely at six.
 *
 * The clock is an [InstantSource] and not a [java.time.Clock], for the reason
 * [ed.unicoach.coaching.coachToday] states: this service supplies the ZONE, so a
 * type that carries one would advertise a zone it discards. `LocalDate.now`
 * appears nowhere in this file.
 *
 * IT RENDERS NOTHING, and that is now true rather than aspirational. Every
 * verdict, every rejection and every absence is a typed value; not one English
 * sentence is authored in this file. The words the coach reads are in
 * [AdmissionTestTimelineChatTool] and [AdmissionTestCopy], where a rendering
 * test can reach them.
 */
class TestTimelineService(
  private val database: Database,
  private val clock: InstantSource = InstantSource.system(),
) {
  suspend fun getForStudent(
    studentId: StudentId,
    collegeIds: List<CollegeId>? = null,
  ): Result<TestTimeline> =
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
   * The whole read on ONE session: today, the student's graduation date, their
   * list, those schools' deadline rounds, the calendar, and -- on the answering
   * path -- the student's residency state and the state-programme corpus.
   * [READ_STATEMENTS] statements, whatever the length of the list.
   *
   * THE NUMBER IS ASSERTED, in `TestTimelineServiceTest`, against the constant
   * this sentence names. A statement count stated only in prose is a claim no
   * test can contradict (RFC 187), and this one had already been wrong once.
   *
   * The two state-programme statements are spent only where there is an answer
   * to attach them to. A [TestTimeline.NoCalendar] refusal carries no programme
   * section at all (RFC 208 section 6), so it returns before them and costs
   * [READ_STATEMENTS] minus [SchoolDayTestingReader]'s two.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
  ): TestTimeline {
    val today = clock.coachToday()
    val graduation = graduationDateOf(session, studentId, "building the admission-test timeline")
    val selection = StudentCollegeSelection.read(session, studentId, collegeIds)
    val deadlines = CdsAdmissionsDao.listLatestDeadlines(session, selection.selected).getOrThrow()
    val calendar = AdmissionTestCalendarDao.list(session).getOrThrow()

    if (calendar.isEmpty()) return TestTimeline.NoCalendar(cycles = emptyList(), reason = NoCalendarReason.EMPTY)

    // THE LIVE ROWS, and every date below is computed from them. Expiry is the
    // minimum over the rows still AHEAD of today, never over all of them: the
    // documented refresh appends the next cycle and never edits a landed seed,
    // so the rows of a spent cycle keep their own expiry forever. Taking the
    // minimum over all of them would refuse dates we hold, and no append could
    // ever clear it -- the fix would be editing a landed migration, which the
    // generator's own header forbids.
    val live = calendar.filter { !it.testDate.isBefore(today) }
    val expiry = live.minOfOrNull { it.expiresOn }
    if (expiry == null || today > expiry) {
      return TestTimeline.NoCalendar(cycles = cyclesOf(calendar), reason = NoCalendarReason.EXPIRED)
    }

    val yearOfSchool = HighSchoolYear.of(today, graduation)
    val candidates = deadlineCandidates(yearOfSchool, graduation.year, selection, deadlines)
    val (ahead, closed) = candidates.resolved.partition { !it.closingResolved.isBefore(today) }
    val anchor = ahead.minByOrNull { it.closingResolved }
    val sittings =
      live
        .filter { !it.testDate.isAfter(today.plus(SITTINGS_HORIZON)) }
        .map { sittingOf(it, today, anchor?.closingResolved) }

    return TestTimeline.Resolved(
      asOf = today,
      calendar = CalendarWindow(cycles = cyclesOf(live), expiresOn = expiry),
      yearOfSchool = yearOfSchool,
      anchor = anchor,
      anchorRejections = candidates.rejected,
      closedDeadlines = closed.sortedBy { it.closingResolved },
      sittings = sittings,
      dataAvailability =
        sittings
          .filter { it.scoreRelease == null }
          .map { UnannouncedScoreRelease(it.test, it.testDate) },
      unknownCollegeIds = selection.unknown,
      // Read HERE and not beside the reads above, because a refusal carries no
      // programme section: two statements spent on a value the refusal arm
      // cannot hold would be two statements spent on nothing.
      schoolDayProgramme = SchoolDayTestingReader.read(session, studentId, today),
    )
  }

  /** The cycles [rows] are labelled with, distinct and in order -- several present are all named, never one chosen. */
  private fun cyclesOf(rows: List<AdmissionTestAdministration>): List<String> = rows.map { it.cycle }.distinct().sorted()

  /** Every round on the student's list, split into the ones that resolved to a date and the ones that could not. */
  private data class DeadlineCandidates(
    val resolved: List<ResolvedDeadline>,
    val rejected: List<AnchorRejection>,
  )

  /**
   * The student's rounds, resolved.
   *
   * No grade, no application cycle to resolve a year into (RFC 197 section 5):
   * BEFORE_HIGH_SCHOOL and PAST_GRADUATION resolve nothing, and the general
   * sequence answers instead. Asked of the enum's own `grade` rather than by
   * listing the four members, so a new year member cannot be forgotten here.
   * NOTHING is rejected in that case either: the rounds are perfectly good, and
   * it is the student who has no cycle -- reporting them as rejections would
   * blame the schools for it.
   */
  private fun deadlineCandidates(
    yearOfSchool: HighSchoolYear,
    graduationYear: Year,
    selection: StudentCollegeSelection,
    deadlines: List<CollegeDeadline>,
  ): DeadlineCandidates {
    if (yearOfSchool.grade == null) return DeadlineCandidates(emptyList(), emptyList())

    val deadlinesByCollege = deadlines.groupBy { it.collegeId }
    val resolved = mutableListOf<ResolvedDeadline>()
    val rejected = mutableListOf<AnchorRejection>()
    selection
      .map { college, _ -> college }
      .forEach { college ->
        deadlinesByCollege[college.id].orEmpty().forEach { row ->
          when (val candidate = candidateOf(college.name, row, graduationYear)) {
            is ResolvedDeadline -> resolved.add(candidate)
            is AnchorRejection -> rejected.add(candidate)
          }
        }
      }
    return DeadlineCandidates(resolved.toList(), rejected.toList())
  }

  /**
   * One stored round, either resolved to a real date or REJECTED with the reason
   * named. The return type is deliberately one or the other and never a null:
   * "there is no deadline here" and "the school published January with no day"
   * are different facts, and a family is entitled to the second one.
   */
  private fun candidateOf(
    collegeName: String,
    row: CollegeDeadline,
    graduationYear: Year,
  ): DeadlineCandidate {
    fun rejection(
      reason: AnchorRejectionReason,
      closingAsPublished: String? = row.closing?.phrase,
      resolvedYear: Year? = null,
    ) = AnchorRejection(row.collegeId, collegeName, row.round, reason, closingAsPublished, resolvedYear)

    if (!row.offered) return rejection(AnchorRejectionReason.ROUND_NOT_OFFERED)
    val closing = row.closing ?: return rejection(AnchorRejectionReason.NO_CLOSING_MONTH, closingAsPublished = null)
    val day = closing.day ?: return rejection(AnchorRejectionReason.MONTH_WITHOUT_DAY)

    val year = ApplicationCycle.yearOf(Month.of(closing.month), graduationYear)
    // 29 FEBRUARY IS STORABLE AND IS NOT ALWAYS A DAY. `CdsMonthDay` validates
    // month length in a LEAP year on purpose -- a CDS date carries no year -- so
    // the pair is valid, and `LocalDate.of` on the year we resolved it into
    // throws in three years out of four. Asked before the construction, so this
    // is a located refusal about one round of one school rather than a
    // `DateTimeException` that kills the whole read and names nobody.
    if (!YearMonth.of(year.value, closing.month).isValidDay(day)) {
      return rejection(AnchorRejectionReason.DAY_NOT_IN_RESOLVED_YEAR, resolvedYear = year)
    }

    return ResolvedDeadline(
      collegeId = row.collegeId,
      collegeName = collegeName,
      round = row.round,
      closingAsPublished = closing.phrase,
      closingResolved = LocalDate.of(year.value, closing.month, day),
      graduationYear = graduationYear,
      citation = CdsCitation(collegeName, row.sourceYear, row.sourceUrl, row.archiveUrl, row.sourceUrlStatus),
    )
  }

  companion object {
    /**
     * College Board's own "ideally 1-2 weeks before the registration deadline",
     * taken at the far end of its range.
     *
     * ONE named home. The number used to be a raw `14` in the service, in the
     * tool's model-facing DESCRIPTION as the word "fourteen", and in two tests --
     * so moving it would have left both tests green while the coach was told the
     * old number. Everything that says it now reads it from here.
     *
     * A [Period] and not a `Long`, so the UNIT is in the type: `FEE_WAIVER_LEAD`
     * needed the suffix `_DAYS` to be usable, which is a name doing a type's job.
     */
    val FEE_WAIVER_LEAD: Period = Period.ofDays(14)

    /**
     * What the answering path costs, in prepared statements: the graduation
     * date, the two the college selection takes, the schools' deadline rounds,
     * the calendar, and [SchoolDayTestingReader]'s two -- the active money
     * profile and the newest state-programme row per state.
     *
     * ABSOLUTE, and asserted. "Five colleges cost what one college costs" stays
     * true when a sixth read is added, so a relative assertion alone would let
     * this read grow silently past the number [readInSession]'s own KDoc
     * promises (the `CollegeAdmissionsServiceTest` precedent). A new read is a
     * deliberate edit here, to that sentence, and to the test, together.
     */
    const val READ_STATEMENTS: Int = 7

    /**
     * How far ahead sittings are reported. Fifteen months: one whole application
     * cycle plus the overlap where the next one has landed, which is every date
     * a student deciding when to sit could act on.
     *
     * A STATED BOUND, because the table only grows. The design appends a cycle a
     * year and never deletes one, so an unbounded read returns 15 sittings, then
     * 30, then 45 -- all of them into the model's context, most of them years
     * away and none of them relevant. The read is one statement either way; the
     * cost of the missing bound is paid in tokens and in a coach with three
     * years of Saturdays to choose between.
     */
    val SITTINGS_HORIZON: Period = Period.ofMonths(15)

    /**
     * One future sitting, with the comparison made once and its result attached.
     *
     * The arm order is the order of the facts and is stated on [SittingVerdict],
     * which is the only place it is written down now: registration first,
     * because a sitting nobody can register for any more is unusable whatever
     * the scores would have done; then the publisher's silence, which is an
     * absence and not a verdict; and only then the comparison the whole tool
     * exists for.
     */
    internal fun sittingOf(
      row: AdmissionTestAdministration,
      today: LocalDate,
      anchorDate: LocalDate?,
    ): TestSitting {
      val registrationOpen = !row.registrationDeadline.isBefore(today)
      // Bound once: `row.scoreRelease` is a property of another module, so every
      // read of it is a fresh one and no smart cast survives the null check.
      val scoreRelease = row.scoreRelease
      val verdict =
        when {
          anchorDate == null -> SittingVerdict.NoAnchor
          !registrationOpen -> SittingVerdict.RegistrationClosed(row.registrationDeadline, today)
          scoreRelease == null -> SittingVerdict.ScoreReleaseUnannounced(row.test, row.testDate)
          else -> SittingVerdict.ScoresCompared(scoreRelease.date, anchorDate)
        }
      return TestSitting(
        test = row.test,
        testDate = row.testDate,
        registrationDeadline = row.registrationDeadline,
        lateRegistrationDeadline = row.lateRegistrationDeadline,
        scoreRelease = scoreRelease,
        registrationOpen = registrationOpen,
        verdict = verdict,
        feeWaiverBy = row.registrationDeadline.minus(FEE_WAIVER_LEAD),
      )
    }
  }
}
