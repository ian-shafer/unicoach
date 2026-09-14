package ed.unicoach.coaching.testing

import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.coaching.coachToday
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.AdmissionTestStateProgrammeFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.SchoolDayParticipation
import ed.unicoach.db.models.SchoolDayTestChoice
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.TestChooser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SchoolDayTestingReader] (RFC 208 section 5): the student's stored residency
 * state, the newest row we hold for it, and the four different things this read
 * can honestly say.
 *
 * TODAY IS BUILT THE ONE WAY THE COACH BUILDS IT -- an [InstantSource] through
 * `coachToday()`, which applies the coach's own zone. `LocalDate.now(clock)`
 * appears nowhere: RFC 186 shipped a test written that way and it passed for the
 * wrong reason, because the zone it proved was the JVM's rather than ours.
 *
 * Every row here is this suite's own ([AdmissionTestStateProgrammeFixture]).
 * The committed corpus is migration-seeded and near-expiry rows in it fail the
 * build (D20), so the expiry arm can only be executed against rows a test chose.
 */
class SchoolDayTestingReaderTest {
  @BeforeEach
  fun setUp() {
    TestTimelineTestDb.reset()
  }

  /** The answer this whole slice exists for: a student whose state runs the sitting, inferred and never asked for. */
  @Test
  fun `a state we hold a current reading for is Held, and it is that state's row`() {
    val student = studentLivingIn("TN")
    TestTimelineTestDb.seedProgrammes(
      listOf(
        AdmissionTestStateProgrammeFixture.programme(state = "TN"),
        AdmissionTestStateProgrammeFixture.programme(state = "MA"),
      ),
    )

    val held = assertIs<SchoolDayProgramme.Held>(read(student, INSIDE_THE_READING))

    assertEquals("TN", held.row.state, "the row must be the student's own state's, never the first row of the corpus")
    assertEquals(SchoolDayParticipation.REQUIRED, held.row.participation)
  }

  /**
   * EXPIRY IS PER STATE AND IS DECIDED AGAINST TODAY, and the boundary is
   * inclusive: `expires_on` is the last day the reading is believed, which is the
   * same boundary the national calendar's own expiry uses.
   */
  @Test
  fun `a reading is Held on its expiry day and Expired the day after`() {
    val student = studentLivingIn("TN")
    TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

    assertIs<SchoolDayProgramme.Held>(read(student, ON_THE_EXPIRY))
    val expired = assertIs<SchoolDayProgramme.Expired>(read(student, PAST_THE_EXPIRY))

    assertEquals("TN", expired.state)
    assertEquals(AdmissionTestStateProgrammeFixture.DEFAULT_EXPIRES_ON, expired.expiresOn)
  }

  /**
   * ONE LAPSED STATE WITHHOLDS NOBODY ELSE'S ANSWER. The expiry is a fact about
   * one state's reading, so a corpus-wide refusal would throw away every current
   * record we hold -- which is what a minimum over the whole table would do.
   */
  @Test
  fun `a lapsed state does not expire another state's current reading`() {
    val lapsed = studentLivingIn("TN")
    val current = studentLivingIn("MA")
    TestTimelineTestDb.seedProgrammes(
      listOf(
        AdmissionTestStateProgrammeFixture.programme(state = "TN", expiresOn = LocalDate.parse("2026-10-31")),
        AdmissionTestStateProgrammeFixture.programme(state = "MA"),
      ),
    )

    assertIs<SchoolDayProgramme.Expired>(read(lapsed, PAST_THE_LAPSED_STATE))
    assertIs<SchoolDayProgramme.Held>(read(current, PAST_THE_LAPSED_STATE))
  }

  /** No row for that state is a gap in OUR data, and the typed value says exactly that and no more. */
  @Test
  fun `a state we hold no row for is NotHeld, and nothing about the state`() {
    val student = studentLivingIn("ND")
    TestTimelineTestDb.seedProgrammes(listOf(AdmissionTestStateProgrammeFixture.programme(state = "TN")))

    assertEquals(SchoolDayProgramme.NotHeld("ND"), read(student, INSIDE_THE_READING))
  }

  /** A student who has never touched a money topic has no profile row at all, and that is an ANSWER, not a failure. */
  @Test
  fun `a student with no money profile is StateUnknown NO_PROFILE`() {
    val student = TestTimelineTestDb.createStudent()

    assertEquals(
      SchoolDayProgramme.StateUnknown(ResidencyGap.NO_PROFILE),
      read(student, INSIDE_THE_READING),
    )
  }

  /** A profile exists and the residency question was never put: a different sentence from a decline. */
  @Test
  fun `a profile with the residency question unanswered is StateUnknown UNANSWERED`() {
    val student = TestTimelineTestDb.createStudent()
    TestTimelineTestDb.leaveResidencyUnanswered(student)

    assertEquals(
      SchoolDayProgramme.StateUnknown(ResidencyGap.UNANSWERED),
      read(student, INSIDE_THE_READING),
    )
  }

  /** They were asked and chose not to say. The tri-state survives to the reader rather than collapsing into "unknown". */
  @Test
  fun `a declined residency is StateUnknown DECLINED`() {
    val student = TestTimelineTestDb.createStudent()
    TestTimelineTestDb.declineResidency(student)

    assertEquals(
      SchoolDayProgramme.StateUnknown(ResidencyGap.DECLINED),
      read(student, INSIDE_THE_READING),
    )
  }

  /**
   * APPEND-ONLY HISTORY, SPOKEN AS THE NEWEST ROW. Illinois moved from the SAT to
   * the ACT and Kentucky the other way inside two cycles; last year's row stays
   * true about last year and is never the answer to "what does my state do".
   */
  @Test
  fun `a state with two school years is spoken as the newer one`() {
    val student = studentLivingIn("IL")
    TestTimelineTestDb.seedProgrammes(
      listOf(
        AdmissionTestStateProgrammeFixture.programme(
          state = "IL",
          schoolYear = AcademicYear(LAST_SCHOOL_YEAR),
          choice = AdmissionTestStateProgrammeFixture.stateChose(AdmissionTest.SAT),
        ),
        AdmissionTestStateProgrammeFixture.programme(
          state = "IL",
          choice = AdmissionTestStateProgrammeFixture.stateChose(AdmissionTest.ACT),
        ),
      ),
    )

    val held = assertIs<SchoolDayProgramme.Held>(read(student, INSIDE_THE_READING))

    assertEquals(
      AcademicYear(AdmissionTestStateProgrammeFixture.DEFAULT_SCHOOL_YEAR),
      held.row.schoolYear,
      "the newer school year is the state's answer today; the older row is still true about its own year",
    )
    assertEquals(SchoolDayTestChoice.StateChose(AdmissionTest.ACT), held.row.choice)
  }

  /**
   * `school_must_offer` TRUE IS THE STRONGEST SENTENCE IN THE CORPUS, and NULL is
   * silence -- not a denial.
   *
   * Asserted on the words the coach actually reads, because the defect this
   * guards is a renderer turning "we did not read it" into "the state does not
   * require it". The true sentence is still about what the state requires of
   * every school and names no school of this student's.
   */
  @Test
  fun `school_must_offer true is spoken, and null says nothing either way`() {
    val student = studentLivingIn("VA")
    TestTimelineTestDb.seedProgrammes(
      listOf(AdmissionTestStateProgrammeFixture.programme(state = "VA", schoolMustOffer = true)),
    )

    val spoken = AdmissionTestTimelineChatTool.whyOf(read(student, INSIDE_THE_READING))
    assertTrue(
      spoken.contains("requires every public high school to hold the sitting"),
      "a state that requires every school to hold it must say so: [$spoken]",
    )

    TestTimelineTestDb.seedProgrammes(
      listOf(AdmissionTestStateProgrammeFixture.programme(state = "VA", schoolMustOffer = null)),
    )
    val silent = AdmissionTestTimelineChatTool.whyOf(read(student, INSIDE_THE_READING))
    assertNull(SchoolDayCopy.schoolMustOfferClauseOf(null), "an unknown has no clause of its own")
    assertTrue(
      !silent.contains("every public high school"),
      "null is UNKNOWN: it may not be spoken as a requirement and may not be spoken as its absence: [$silent]",
    )
  }

  /**
   * THE CHOOSER SURVIVES THE READ, both ways round. A state that chose no test
   * carries none, which is the pairing the type makes unrepresentable -- and the
   * words for the three choosers are the copy's, not a `:db` enum's.
   */
  @Test
  fun `a locally-chosen state names no test, and a state-chosen one names its own`() {
    val districtStudent = studentLivingIn("TN")
    val studentChoiceStudent = studentLivingIn("SC")
    val stateChoiceStudent = studentLivingIn("DE")
    TestTimelineTestDb.seedProgrammes(
      listOf(
        AdmissionTestStateProgrammeFixture.programme(state = "TN"),
        AdmissionTestStateProgrammeFixture.programme(
          state = "SC",
          participation = SchoolDayParticipation.OFFERED,
          choice = SchoolDayTestChoice.ChosenLocally(TestChooser.STUDENT),
        ),
        AdmissionTestStateProgrammeFixture.programme(
          state = "DE",
          choice = AdmissionTestStateProgrammeFixture.stateChose(AdmissionTest.SAT),
        ),
      ),
    )

    assertEquals(
      SchoolDayTestChoice.ChosenLocally(TestChooser.DISTRICT),
      assertIs<SchoolDayProgramme.Held>(read(districtStudent, INSIDE_THE_READING)).row.choice,
    )
    assertEquals(
      SchoolDayTestChoice.ChosenLocally(TestChooser.STUDENT),
      assertIs<SchoolDayProgramme.Held>(read(studentChoiceStudent, INSIDE_THE_READING)).row.choice,
    )
    assertEquals(
      SchoolDayTestChoice.StateChose(AdmissionTest.SAT),
      assertIs<SchoolDayProgramme.Held>(read(stateChoiceStudent, INSIDE_THE_READING)).row.choice,
    )
  }

  /** A student living in [state], with the residency answer the cost conversation would have collected. */
  private fun studentLivingIn(state: String): StudentId =
    TestTimelineTestDb.createStudent().also { TestTimelineTestDb.answerResidency(it, state) }

  /**
   * The read, at the day [instant] falls on IN THE COACH'S ZONE.
   *
   * The date is never written by this suite: it comes from an [InstantSource]
   * through `coachToday()`, which is the one home for the rule that the zone is
   * ours.
   */
  private fun read(
    student: StudentId,
    instant: String,
  ): SchoolDayProgramme =
    SchoolDayTestingReader.read(
      AdmissionsTestDb.sqlSession,
      student,
      InstantSource.fixed(Instant.parse(instant)).coachToday(),
    )

  /** `kotlin.test.assertIs` without the reified-cast ceremony at every call site ([TestTimelineServiceTest]'s helper). */
  private inline fun <reified T> assertIs(value: Any): T {
    assertTrue(value is T, "expected a ${T::class.simpleName}, got [$value]")
    return value as T
  }

  private companion object {
    /** Noon Eastern inside the fixture's own reading window. */
    const val INSIDE_THE_READING = "2026-09-14T16:00:00Z"

    /** Noon Eastern ON the fixture's expiry day: the last day the reading is believed. */
    const val ON_THE_EXPIRY = "2027-07-31T16:00:00Z"

    /** Noon Eastern the day after it. */
    const val PAST_THE_EXPIRY = "2027-08-01T16:00:00Z"

    /** Noon Eastern past ONE state's chosen expiry and well inside every other state's. */
    const val PAST_THE_LAPSED_STATE = "2026-11-01T16:00:00Z"

    /** The school year before the fixture's default -- the older row of an append-only pair. */
    const val LAST_SCHOOL_YEAR = AdmissionTestStateProgrammeFixture.DEFAULT_SCHOOL_YEAR - 1
  }
}
