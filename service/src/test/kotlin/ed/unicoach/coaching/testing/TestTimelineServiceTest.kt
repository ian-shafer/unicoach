package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.db.dao.AdmissionTestStateProgrammeFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `admission_test_timeline`'s read (RFC 197): the subtraction itself, the expiry
 * refusal, the publisher's unannounced score releases, and the two year-of-school
 * edges that have no application cycle to resolve a year into.
 *
 * Every test pins an INSTANT and never a zone -- the service supplies
 * `COACH_ZONE` -- and every date asserted is either a row of the seeded calendar,
 * a school's published month and day, or a stated derivation. Nothing here
 * asserts a date this suite invented.
 */
class TestTimelineServiceTest {
  @BeforeEach
  fun setUp() {
    TestTimelineTestDb.reset()
  }

  /**
   * THE TEST THE WHOLE SLICE EXISTS FOR. A senior graduating 2027 with one school
   * whose Early Action round closes on the 1st of November: from that deadline,
   * back through score release, to the day registration closes.
   *
   * Two registration dates are asserted by value, and both are the answer: the
   * earliest usable registration deadline of ALL is the ACT's 2026-09-11, and the
   * earliest usable SAT registration deadline is 2026-09-18. The SAT one is the
   * answer to the question as a family asks it ("when do I need to take the
   * SATs"); the overall one is the answer to the question they meant, and it
   * belongs to the other test -- which is exactly why both are said and the SAT is
   * never the default (D13).
   */
  @Test
  fun `the subtraction -- from a November 1 Early Action deadline back to the day registration closes`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Anchor College")
      AdmissionsTestDb.seedDeadline(
        collegeId = college,
        round = ApplicationRound.EARLY_ACTION,
        closing = CdsMonthDay(11, 1),
        notification = CdsMonthDay(12, 15),
      )
      TestTimelineTestDb.seedCommittedCalendar()

      val timeline = TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow()
      val resolved = assertIs<TestTimeline.Resolved>(timeline)

      val anchor = assertNotNull(resolved.anchor, "one dated Early Action round on the list must resolve to an anchor")
      assertEquals(LocalDate.parse("2026-11-01"), anchor.closingResolved)
      assertEquals("November 1", anchor.closingAsPublished, "the school's own words carry no year")
      assertEquals(Year.of(2027), anchor.graduationYear, "the input the resolved year came from rides with it")
      val basis = AdmissionTestTimelineChatTool.resolutionBasisOf(anchor)
      assertTrue(basis.contains("graduating in 2027"), "the basis must say whose year it is: [$basis]")

      assertEquals(true, usabilityOf(resolved, AdmissionTest.SAT, "2026-10-03"))
      assertEquals(true, usabilityOf(resolved, AdmissionTest.ACT, "2026-10-17"))

      val tooLate = sittingOf(resolved, AdmissionTest.SAT, "2026-11-07")
      assertEquals(false, tooLate.usableForAnchor)
      assertEquals(
        SittingVerdict.ScoresCompared(
          release = LocalDate.parse("2026-11-20"),
          anchor = LocalDate.parse("2026-11-01"),
        ),
        tooLate.verdict,
        "the verdict must BE the comparison, carrying both dates it was made from",
      )

      val usable = resolved.sittings.filter { it.usableForAnchor == true }
      assertEquals(
        LocalDate.parse("2026-09-18"),
        usable.filter { it.test == AdmissionTest.SAT }.minOf { it.registrationDeadline },
        "the day they must act to sit a usable SAT",
      )
      assertEquals(
        LocalDate.parse("2026-09-11"),
        usable.minOf { it.registrationDeadline },
        "the day they must act at all -- the ACT's, and it closes today",
      )
    }

  /**
   * D13 symmetry. Both tests have a usable sitting for this deadline, and both
   * come back -- the payload never privileges one publisher by omitting the other.
   */
  @Test
  fun `both tests come back when both have a usable sitting`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Symmetry College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertEquals(
        setOf(AdmissionTest.SAT, AdmissionTest.ACT),
        resolved.sittings
          .filter { it.usableForAnchor == true }
          .map { it.test }
          .toSet(),
        "both publishers have a sitting whose scores arrive in time, so both must be in the answer",
      )
      // And the order is the calendar's, by date -- not the SATs as a block first.
      assertEquals(
        resolved.sittings.map { it.testDate }.sorted(),
        resolved.sittings.map { it.testDate },
      )
    }

  /**
   * THE SPRING-2027 GAP. College Board has published score releases for fall 2026
   * only, so the three spring SAT sittings have none -- and a sitting with no
   * announced score release is `null`, never `true` and never `false`, and is
   * named in `data_availability` so the silence is reported rather than inferred.
   */
  @Test
  fun `a sitting with no announced score release is neither usable nor unusable, and is named`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Gap College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      val unannounced = SPRING_2027_SAT_SITTINGS.map { sittingOf(resolved, AdmissionTest.SAT, it) }
      assertEquals(
        SPRING_2027_SAT_SITTINGS.size,
        unannounced.size,
        "all three spring SAT sittings are still ahead on this clock and must be in the answer",
      )
      unannounced.forEach { sitting ->
        assertNull(
          sitting.usableForAnchor,
          "[${sitting.testDate}] has no published score release, so it is neither usable nor unusable",
        )
        assertEquals(
          SittingVerdict.ScoreReleaseUnannounced(AdmissionTest.SAT, sitting.testDate),
          sitting.verdict,
          "the verdict must BE the publisher's silence, and not a comparison that could not be made",
        )
        assertTrue(
          resolved.dataAvailability.any { it.test == AdmissionTest.SAT && it.testDate == sitting.testDate },
          "[${sitting.testDate}] must be named in data_availability: [${resolved.dataAvailability}]",
        )
      }
    }

  /** The fee-waiver lead date is exactly fourteen days before registration closes, on every sitting. */
  @Test
  fun `the fee-waiver date is the registration deadline minus fourteen days`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertTrue(resolved.sittings.isNotEmpty(), "the committed calendar must still have sittings ahead of this clock")
      assertEquals(
        emptyList(),
        resolved.sittings.filterNot { it.feeWaiverBy == it.registrationDeadline.minus(TestTimelineService.FEE_WAIVER_LEAD) },
        "every fee-waiver date is registration_deadline minus the one fee-waiver lead constant, and nothing else",
      )
      // The one worked example, by value: the sitting the subtraction test lands on.
      assertEquals(
        LocalDate.parse("2026-09-04"),
        sittingOf(resolved, AdmissionTest.SAT, "2026-10-03").feeWaiverBy,
      )
    }

  /**
   * D7, executed. One day past the calendar's expiry the read names no date at
   * all: no sittings, and a refusal naming both publishers.
   */
  @Test
  fun `past the calendar's expiry there are no sittings and a refusal naming both publishers`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCalendar(EXPIRING_CALENDAR)

      val timeline = TestTimelineTestDb.serviceAt(DAY_AFTER_THE_EXPIRY).getForStudent(student).getOrThrow()
      val refused = assertIs<TestTimeline.NoCalendar>(timeline)

      assertEquals(NoCalendarReason.EXPIRED, refused.reason, "we held this calendar and no longer believe it")
      assertEquals(listOf("2026-27"), refused.cycles, "the publishers' own label for what we held still travels")
      val refusal = AdmissionTestTimelineChatTool.refusalFor(refused.reason)
      assertTrue(refusal.contains("College Board"), "the refusal must name College Board: [$refusal]")
      assertTrue(refusal.contains("ACT"), "the refusal must name ACT: [$refusal]")
    }

  /**
   * AN EMPTY CALENDAR IS NOT AN OUT-OF-DATE ONE. Both refuse, and they refuse
   * with different sentences: "our calendar is out of date" is a claim about a
   * document we held, and it is simply untrue of a table with nothing in it.
   */
  @Test
  fun `an empty calendar refuses for a different reason than an expired one`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()

      val timeline = TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow()
      val refused = assertIs<TestTimeline.NoCalendar>(timeline)

      assertEquals(NoCalendarReason.EMPTY, refused.reason)
      assertEquals(emptyList(), refused.cycles, "there is no cycle label, because there is no calendar")
      assertTrue(
        !AdmissionTestTimelineChatTool.refusalFor(refused.reason).contains("out of date"),
        "an empty table is not a calendar that has gone out of date",
      )
    }

  /**
   * The sibling the test above needs to mean anything: ONE DAY EARLIER, with the
   * same rows, the sittings do come back. Without it, a read that returned nothing
   * for any reason at all would pass the refusal test.
   */
  @Test
  fun `one day before the expiry the same calendar still answers with sittings`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCalendar(EXPIRING_CALENDAR)

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(DAY_BEFORE_THE_EXPIRY).getForStudent(student).getOrThrow(),
        )

      assertEquals(LocalDate.parse("2026-10-31"), resolved.calendar.expiresOn)
      assertEquals(
        EXPIRING_CALENDAR.map { it.testDate },
        resolved.sittings.map { it.testDate },
        "every seeded sitting is still ahead of this clock, so every one must come back",
      )
    }

  /** A student not yet in high school has no application cycle to resolve a year into: no anchor, and the general sequence instead. */
  @Test
  fun `a student before high school gets no anchor and the general sequence`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2035)
      val college = AdmissionsTestDb.seedListedCollege(student, "Early College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertEquals(HighSchoolYear.BEFORE_HIGH_SCHOOL, resolved.yearOfSchool)
      assertNull(resolved.anchor, "there is no application cycle to attach a year to")
      assertEquals(
        emptyList(),
        resolved.anchorRejections,
        "the rounds are fine; it is the student who has no cycle, so no school is reported as having failed one",
      )
      assertEquals(
        emptyList(),
        resolved.sittings.filterNot { it.usableForAnchor == null },
        "with no anchor, no sitting can be usable or unusable for one",
      )
      assertNoFabricatedCycleYear(resolved, graduationYear = 2035)
    }

  /** And past the expected graduation date, likewise -- the cycle is over, so no year is supplied. */
  @Test
  fun `a student past graduation gets no anchor and the general sequence`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2024)
      val college = AdmissionsTestDb.seedListedCollege(student, "Late College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertEquals(HighSchoolYear.PAST_GRADUATION, resolved.yearOfSchool)
      assertNull(resolved.anchor)
      assertEquals(emptyList(), resolved.anchorRejections)
      assertNoFabricatedCycleYear(resolved, graduationYear = 2024)
    }

  /**
   * A CLOSED REGISTRATION IS A FACT WE HOLD, and it outranks the publisher's
   * silence about scores. On 2027-04-20 the SAT's 2027-05-01 sitting is past its
   * 2027-04-16 registration deadline, and College Board has announced no score
   * release for it. The anchor is 2027-11-01, months away, so the deadline is
   * NOT the reason -- the closed registration is.
   *
   * The verdict is therefore `false` and not `null`: reporting `null` would hide
   * a certainty behind an unknown. And the sentence that justifies it names the
   * registration deadline and today and NOTHING about a score release, so the
   * coach cannot read a registration fact as a guess about scores.
   */
  @Test
  fun `a sitting whose registration has closed is unusable, and the reason names only the registration deadline`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2028)
      val college = AdmissionsTestDb.seedListedCollege(student, "Closed Registration College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2027_04_20).getForStudent(student).getOrThrow(),
        )

      val anchor = assertNotNull(resolved.anchor, "a junior with a dated November round must have an anchor")
      assertEquals(
        LocalDate.parse("2027-11-01"),
        anchor.closingResolved,
        "the anchor must be far enough out that the deadline is not what decides this sitting",
      )

      val closed = sittingOf(resolved, AdmissionTest.SAT, "2027-05-01")
      assertNull(closed.scoreRelease, "this is the sitting College Board has announced no score release for")
      assertEquals(false, closed.usableForAnchor, "registration closed on 2027-04-16, and that is a fact we hold")
      assertEquals(
        SittingVerdict.RegistrationClosed(
          deadline = LocalDate.parse("2027-04-16"),
          today = LocalDate.parse("2027-04-20"),
        ),
        closed.verdict,
        "the verdict must BE the registration fact, carrying the two dates it turns on and nothing about scores",
      )
      // The SENTENCE is the tool's, and so is the assertion that it says nothing
      // about scores -- but the verdict above is what makes that checkable: a
      // RegistrationClosed carries no score-release date to leak.
      val why = AdmissionTestTimelineChatTool.whyOf(closed.verdict)
      assertTrue(why.contains("2027-04-16"), "the reason must name the registration deadline it turns on: [$why]")
      assertEquals(
        emptyList(),
        SCORE_RELEASE_WORDS.filter { why.contains(it, ignoreCase = true) },
        "a registration fact must never be worded as a guess about scores: [$why]",
      )
    }

  /**
   * 29 FEBRUARY, AND THE CRASH IT USED TO BE.
   *
   * `CdsMonthDay` validates month length in a LEAP year on purpose -- a CDS date
   * carries no year -- so a school that publishes "February 29" is stored
   * faithfully. Resolving it into a non-leap application cycle then threw
   * `DateTimeException: Invalid date 'February 29'` out of `LocalDate.of`, which
   * killed the ENTIRE timeline read for that student and named neither the
   * school nor the round.
   *
   * It is now the fourth way a round cannot be an anchor, it is LOCATED, and the
   * rest of the answer still comes back.
   */
  @Test
  fun `a February 29 deadline resolved into a non-leap year is a located rejection, not a crash`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Leap Day College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.REGULAR, closing = CdsMonthDay(2, 29))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertNull(resolved.anchor, "there is no real date to work back from, and none is invented")
      val rejection = resolved.anchorRejections.single()
      assertEquals(AnchorRejectionReason.DAY_NOT_IN_RESOLVED_YEAR, rejection.reason)
      assertEquals(college, rejection.collegeId)
      assertEquals(ApplicationRound.REGULAR, rejection.round)
      assertEquals("February 29", rejection.closingAsPublished, "the school's own words, unchanged")
      assertEquals(Year.of(2027), rejection.resolvedYear, "and the year OURS resolved to, which is not a leap year")
      assertTrue(resolved.sittings.isNotEmpty(), "the rest of the read survives: one bad round is not the read")
    }

  /**
   * A MONTH WITH NO DAY IS A FACT, NOT A SILENCE. "Applications close in
   * January" is legal CDS reporting and is never completed with a guessed day --
   * but the student's school DID publish something, so the read reports what and
   * why rather than answering as though the list were empty.
   */
  @Test
  fun `a month with no day is reported as a rejection, naming the school's own words`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Month Only College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.REGULAR, closing = CdsMonthDay(1, null))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertNull(resolved.anchor)
      val rejection = resolved.anchorRejections.single()
      assertEquals(AnchorRejectionReason.MONTH_WITHOUT_DAY, rejection.reason)
      assertEquals("January, day not reported", rejection.closingAsPublished)
      assertNull(rejection.resolvedYear, "no year is attached to a date we declined to complete")
    }

  /**
   * "EVERY DEADLINE ON YOUR LIST HAS ALREADY CLOSED" is the most
   * decision-relevant thing this read can tell a late student, and it used to be
   * thrown away by the `filter` that picked the anchor -- leaving a payload
   * indistinguishable from a student with no schools at all.
   */
  @Test
  fun `deadlines that have already closed are reported, not discarded`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Closed College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          // Past the 2026-11-01 the November round resolves to, and still inside
          // the committed calendar's own window.
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2027_04_20).getForStudent(student).getOrThrow(),
        )

      assertNull(resolved.anchor, "there is no deadline still ahead")
      assertEquals(
        listOf(LocalDate.parse("2026-11-01")),
        resolved.closedDeadlines.map { it.closingResolved },
        "the closed deadline is REPORTED, so the coach can say it has closed rather than that there is none",
      )
      assertEquals(emptyList(), resolved.anchorRejections, "nothing was wrong with the round; its date has passed")
    }

  /**
   * THE HORIZON. The design appends a cycle a year and never deletes one, so an
   * unbounded read grows 15 -> 30 -> 45 sittings, all of them into the model's
   * context. Only the sittings inside [TestTimelineService.SITTINGS_HORIZON]
   * come back.
   */
  @Test
  fun `sittings beyond the stated horizon are not reported`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCalendar(A_NEAR_AND_A_DISTANT_SITTING)

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertEquals(
        listOf(LocalDate.parse("2026-11-07")),
        resolved.sittings.map { it.testDate },
        "the 2030 sitting is real and is still in the table, but it is not an answer to when to sit a test",
      )
    }

  /**
   * THE ZONE. An instant at 02:30Z is still the PREVIOUS day in the US, and the
   * date the coach works from is ours and never the clock's. RFC 186's first
   * attempt read `LocalDate.now(clock)` and proved the opposite of this.
   */
  @Test
  fun `an instant after midnight UTC is still the previous US day`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt("2026-09-12T02:30:00Z").getForStudent(student).getOrThrow(),
        )

      assertEquals(LocalDate.parse("2026-09-11"), resolved.asOf)
    }

  /**
   * A January deadline belongs to the same application cycle as a November one
   * (RFC 197 section 5), and resolves to the graduation year rather than the year
   * before it. Asserted through the read, because the rule only matters where it
   * is applied.
   */
  @Test
  fun `a January round resolves to the graduation year, not the autumn before it`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Regular College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.REGULAR, closing = CdsMonthDay(1, 15))
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertEquals(LocalDate.parse("2027-01-15"), assertNotNull(resolved.anchor).closingResolved)
    }

  /** A round the school states it does not run carries no deadline, so it can never be the anchor. */
  @Test
  fun `a round the school does not run is never the anchor`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Not Offered College")
      AdmissionsTestDb.seedDeadline(
        collegeId = college,
        round = ApplicationRound.EARLY_ACTION,
        offered = false,
        closing = null,
        notification = null,
      )
      TestTimelineTestDb.seedCommittedCalendar()

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      assertNull(resolved.anchor)
      assertEquals(
        listOf(AnchorRejectionReason.ROUND_NOT_OFFERED),
        resolved.anchorRejections.map { it.reason },
        "the round is REPORTED as not offered, rather than dropped into a silence the general sequence would fill",
      )
    }

  /**
   * THE STATEMENT BUDGET, ABSOLUTE AND NOT MERELY RELATIVE (RFC 208 section 5,
   * and the `CollegeAdmissionsServiceTest` precedent).
   *
   * [TestTimelineService.readInSession]'s own KDoc states a number, and RFC 187
   * found a doc comment stating a count that no test could contradict -- this one
   * had already been wrong. The count is asserted against the constant that
   * sentence names, so a sixth read is a deliberate edit to the constant, the
   * sentence and this assertion together.
   *
   * The student is fully populated on purpose: a list with a college in it, a
   * dated round, a residency answer and a row for that state, so every one of the
   * seven statements actually fires.
   */
  @Test
  fun `the answering read costs the stated number of statements`() =
    runTest {
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Budget College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

      val counting = CoachingTestDb.CountingSession()
      val timeline =
        TestTimelineTestDb
          .serviceAt(NOON_EASTERN_2026_09_11)
          .readInSession(counting, student, null)

      assertIs<TestTimeline.Resolved>(timeline)
      assertEquals(
        TestTimelineService.READ_STATEMENTS,
        counting.prepared.size,
        "the whole answering read is [${TestTimelineService.READ_STATEMENTS}] statements -- the graduation date, " +
          "the selection's two, the deadlines, the calendar, and the state-programme reader's money profile and " +
          "DISTINCT ON. It prepared [${counting.prepared}]; if that is correct, say so in readInSession's KDoc " +
          "and in READ_STATEMENTS in the same commit",
      )
    }

  /**
   * THE REFUSAL PAYS FOR NOTHING IT CANNOT CARRY. A [TestTimeline.NoCalendar]
   * arm has no programme section at all (RFC 208 section 6), so the two
   * state-programme statements are not spent on it -- which is the claim
   * `readInSession`'s KDoc makes and the only thing that makes the number above
   * conditional.
   */
  @Test
  fun `a refusal does not pay for the state-programme read`() =
    runTest {
      // The SAME student shape as the budget test above: an empty list would
      // short-circuit two of the five reads, and the two counts would then
      // differ by something other than the state programme.
      val student = TestTimelineTestDb.createStudent(graduationYear = 2027)
      val college = AdmissionsTestDb.seedListedCollege(student, "Refusal College")
      AdmissionsTestDb.seedDeadline(college, ApplicationRound.EARLY_ACTION, closing = CdsMonthDay(11, 1))
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))
      TestTimelineTestDb.seedCalendar(EXPIRING_CALENDAR)

      val counting = CoachingTestDb.CountingSession()
      val timeline =
        TestTimelineTestDb
          .serviceAt(DAY_AFTER_THE_EXPIRY)
          .readInSession(counting, student, null)

      assertIs<TestTimeline.NoCalendar>(timeline)
      assertEquals(
        TestTimelineService.READ_STATEMENTS - STATE_PROGRAMME_STATEMENTS,
        counting.prepared.size,
        "a refusal carries no programme section, so it must not read one: [${counting.prepared}]",
      )
    }

  /**
   * THE STATE FACT RIDES ON THE ANSWER, and it is INFERRED: the student answered
   * the residency question in the cost conversation and was asked nothing here.
   */
  @Test
  fun `the resolved read carries the student's own state programme`() =
    runTest {
      val student = TestTimelineTestDb.createStudent()
      TestTimelineTestDb.seedCommittedCalendar()
      TestTimelineTestDb.answerResidency(student, "TN")
      TestTimelineTestDb.seedProgrammes(
        listOf(
          AdmissionTestStateProgrammeFixture.programme(state = "TN"),
          AdmissionTestStateProgrammeFixture.programme(state = "MA"),
        ),
      )

      val resolved =
        assertIs<TestTimeline.Resolved>(
          TestTimelineTestDb.serviceAt(NOON_EASTERN_2026_09_11).getForStudent(student).getOrThrow(),
        )

      val held = assertIs<SchoolDayProgramme.Held>(resolved.schoolDayProgramme)
      assertEquals("TN", held.row.state)
    }

  /**
   * THE CLAIM THE TWO EDGE TESTS ARE FOR, and the one `anchor == null` does not
   * make: no application-cycle year is INVENTED for a student who has no cycle.
   *
   * `anchor == null` is the mechanism; a resolved year leaking into the general
   * sequence or into a sitting's `why` is the defect. So this sweeps every
   * sentence the tool would render from this read, for the two years
   * [ed.unicoach.coaching.admissions.ApplicationCycle.yearOf] would have produced
   * from this student's graduation year -- G for a spring closing and G-1 for an
   * autumn one -- which are exactly the years that would have been fabricated.
   */
  private fun assertNoFabricatedCycleYear(
    resolved: TestTimeline.Resolved,
    graduationYear: Int,
  ) {
    val rendered =
      resolved.sittings.map { AdmissionTestTimelineChatTool.whyOf(it.verdict) } +
        resolved.anchorRejections.map { AdmissionTestTimelineChatTool.whyOf(it) } +
        listOf(AdmissionTestTimelineChatTool.GENERAL_SEQUENCE) +
        listOf(resolved.yearOfSchool.label, resolved.yearOfSchool.clause) +
        resolved.dataAvailability.map { "${it.test.value} ${it.testDate}" }
    assertTrue(rendered.isNotEmpty(), "there must be rendered text to sweep, or this assertion proves nothing")

    val wouldHaveBeenInvented = listOf(graduationYear, graduationYear - 1).map { it.toString() }
    assertEquals(
      emptyList(),
      rendered.filter { sentence -> wouldHaveBeenInvented.any { sentence.contains(it) } },
      "no application-cycle year may be invented for a student who has no cycle: looked for " +
        "$wouldHaveBeenInvented",
    )
  }

  private fun sittingOf(
    resolved: TestTimeline.Resolved,
    test: AdmissionTest,
    testDate: String,
  ): TestSitting = resolved.sittings.single { it.test == test && it.testDate == LocalDate.parse(testDate) }

  private fun usabilityOf(
    resolved: TestTimeline.Resolved,
    test: AdmissionTest,
    testDate: String,
  ): Boolean? = sittingOf(resolved, test, testDate).usableForAnchor

  /** `kotlin.test.assertIs` without the reified-cast ceremony at every call site. */
  private inline fun <reified T> assertIs(value: Any): T {
    assertTrue(value is T, "expected a ${T::class.simpleName}, got [$value]")
    return value as T
  }

  private companion object {
    /**
     * Noon Eastern on the day the calendar was verified. An INSTANT with no zone
     * on it: the service applies `COACH_ZONE`, and a test that supplied a zone
     * would be proving its own arithmetic.
     */
    const val NOON_EASTERN_2026_09_11 = "2026-09-11T16:00:00Z"

    /**
     * Noon Eastern on 2027-04-20: after the SAT 2027-05-01 sitting's 2027-04-16
     * registration deadline, and before the committed calendar's 2027-07-31
     * expiry, so the calendar still answers.
     */
    const val NOON_EASTERN_2027_04_20 = "2027-04-20T16:00:00Z"

    /**
     * Every way the `why` sentences in this service spell a score release. A
     * registration verdict must use none of them, and the list is checked
     * against the wording the service actually emits by the tests that DO expect
     * a score-release sentence.
     */
    val SCORE_RELEASE_WORDS = listOf("score", "release", "released", "announced")

    /** The three SAT sittings College Board has published no score release for. */
    val SPRING_2027_SAT_SITTINGS = listOf("2027-03-06", "2027-05-01", "2027-06-05")

    /**
     * A calendar whose expiry the test chose, which no committed row could ever
     * be: a committed row within 90 days of its expiry fails the build (D20), so
     * the expiry behaviour can only be executed against rows seeded here.
     */
    val EXPIRING_CALENDAR =
      listOf(
        TestTimelineTestDb.administration(
          test = AdmissionTest.SAT,
          testDate = "2026-11-07",
          registrationDeadline = "2026-10-23",
          lateRegistrationDeadline = "2026-10-27",
          scoreReleaseDate = "2026-11-20",
          expiresOn = "2026-10-31",
        ),
        TestTimelineTestDb.administration(
          test = AdmissionTest.ACT,
          testDate = "2026-12-12",
          registrationDeadline = "2026-11-06",
          lateRegistrationDeadline = "2026-11-29",
          scoreReleaseDate = "2026-12-22",
          expiresOn = "2026-10-31",
        ),
      )

    /**
     * One sitting inside [TestTimelineService.SITTINGS_HORIZON] and one far
     * beyond it, both live and both unexpired -- the shape a calendar takes after
     * a few years of appends.
     */
    val A_NEAR_AND_A_DISTANT_SITTING =
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
          testDate = "2030-11-02",
          registrationDeadline = "2030-10-18",
          scoreReleaseDate = "2030-11-15",
          expiresOn = "2031-07-31",
          cycle = "2030-31",
        ),
      )

    /** One day past [EXPIRING_CALENDAR]'s expiry, Eastern. */
    const val DAY_AFTER_THE_EXPIRY = "2026-11-01T16:00:00Z"

    /** One day before it, Eastern -- the same rows, and they still answer. */
    const val DAY_BEFORE_THE_EXPIRY = "2026-10-30T16:00:00Z"

    /**
     * What [SchoolDayTestingReader] costs on its own: the active money profile
     * and the newest row per state. Named, so the refusal assertion subtracts a
     * number that is written down rather than a literal two.
     */
    const val STATE_PROGRAMME_STATEMENTS = 2
  }
}
