package ed.unicoach.coaching.admissions

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.college.AdmissionTestPolicy
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.ApplicationRound
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.FactorRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat-free half of RFC 148: the composition rules that decide what may be
 * said at all. The honesty rules live here rather than in the renderer, so
 * every one of them is provable without a JSON payload in the way.
 */
class CollegeAdmissionsServiceTest {
  companion object {
    /**
     * Every statement one whole admissions read prepares, whatever the list's
     * length: the student's college selection (its list entries and then the
     * college rows), the three CDS reference tables, and the IPEDS test policy
     * RFC 187 added.
     *
     * The same number [CollegeAdmissionsService.readInSession]'s KDoc states in
     * words, so the two move together or the batching test fails.
     */
    private const val BATCHED_STATEMENTS = 6
  }

  @BeforeEach
  fun resetDatabase() {
    AdmissionsTestDb.reset()
  }

  private val service = CollegeAdmissionsService(AdmissionsTestDb.database)

  private fun read(
    student: ed.unicoach.db.models.StudentId,
    collegeIds: List<CollegeId>? = null,
  ): CollegeAdmissionsProfile = runBlocking { service.getForStudent(student, collegeIds).getOrThrow() }

  // ---------------------------------------------------------------------------
  // Selection
  // ---------------------------------------------------------------------------

  @Test
  fun `an absent college_ids reads the whole active list and an empty list reads nothing`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "First College")
    AdmissionsTestDb.seedListedCollege(student, "Second College")

    assertEquals(listOf("First College", "Second College"), read(student).colleges.map { it.name }.sorted())
    // `[]` is a literal empty subset and never means "all of them".
    assertEquals(emptyList(), read(student, emptyList()).colleges.map { it.name })
  }

  @Test
  fun `an id outside the student's list is reported while the known ones still answer`() {
    val student = AdmissionsTestDb.createStudent()
    val mine = AdmissionsTestDb.seedListedCollege(student, "Mine University")
    val theirs = AdmissionsTestDb.seedCollege("Not On My List College")

    val profile = read(student, listOf(mine, theirs))
    assertEquals(listOf("Mine University"), profile.colleges.map { it.name })
    assertEquals(listOf(theirs), profile.unknownCollegeIds)
  }

  @Test
  fun `the list status rides with every college`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "Applying U", status = CollegeListEntryStatus.APPLYING)

    assertEquals(CollegeListEntryStatus.APPLYING, read(student).colleges.single().listStatus)
  }

  // ---------------------------------------------------------------------------
  // The honest denominator (D4)
  // ---------------------------------------------------------------------------

  @Test
  fun `the share is emitted only when both counts are present`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "No Total College")
    // A quarter of the corpus reports no freshman total. The average is an
    // independent fact and still answers; the share is simply missing.
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = 358,
      noNeedMeritAverageUsd = 16112,
    )

    val merit = read(student).colleges.single().meritAid
    assertNotNull(merit)
    assertNull(merit.shareOfAllFullTimeFreshmen, "a share with no denominator must not be computed")
    assertEquals(16112, merit.averageNonNeedAid)
  }

  @Test
  fun `a missing recipient count also withholds the share`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "No Count College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = 16112,
    )

    assertNull(
      read(student)
        .colleges
        .single()
        .meritAid
        ?.shareOfAllFullTimeFreshmen,
    )
  }

  @Test
  fun `zero merit recipients is a reported fact, not a missing one`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Amherst-shaped College")
    // The real shape: 480 freshmen, 0 given non-need aid, $0 average. Every one
    // of those zeros is the school's own statement.
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 480,
      noNeedMeritRecipientsHeadcount = 0,
      noNeedMeritAverageUsd = 0,
    )

    val merit = read(student).colleges.single().meritAid
    assertNotNull(merit)
    assertEquals(0.0, merit.shareOfAllFullTimeFreshmen?.percent)
    assertEquals(0, merit.averageNonNeedAid)
    assertTrue(
      read(student)
        .colleges
        .single()
        .notReported
        .none { it == AdmissionsField.MERIT_AID },
    )
  }

  @Test
  fun `a zero freshman total is silence, not a section of bare zeroes`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Zero Total College")
    // `first_time_full_time_freshmen_headcount >= 0` is in domain and the corpus
    // CHECK then forces 0
    // recipients, so a mangled extraction can report a school with no freshman
    // class at all. Rendering it would put two bare zeroes and a citation with
    // no computable share in front of the coach, and -- worse -- keep the
    // school's merit silence OUT of data_availability, where the coach reads
    // it. It is ruled on exactly like the denominator-only row.
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 0,
      noNeedMeritRecipientsHeadcount = 0,
      noNeedMeritAverageUsd = null,
    )

    val admissions = read(student).colleges.single()
    assertNull(admissions.meritAid, "a school with no freshman class reported no merit fact")
    assertTrue(
      admissions.notReported.contains(AdmissionsField.MERIT_AID),
      "the silence must be named where the coach reads it",
    )
  }

  @Test
  fun `a cancelled read is rethrown, never reported as a read failure`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "Cancelled College")

    // A chat turn the caller abandoned is not a database fault. Folding
    // CancellationException into Result.failure would log the cancellation as a
    // failure, tell the model "college admissions read failed", and stop the
    // cancellation propagating.
    val outcome =
      runBlocking {
        var thrown: Throwable? = null
        var completed: Result<CollegeAdmissionsProfile>? = null
        // UNDISPATCHED runs the body on this thread up to the first real
        // suspension -- the connection hand-off -- and the cancel below then
        // lands while the read is in flight, with no sleep and no race.
        val job =
          launch(start = CoroutineStart.UNDISPATCHED) {
            try {
              completed = service.getForStudent(student)
            } catch (e: Throwable) {
              thrown = e
            }
          }
        job.cancel()
        job.join()
        thrown to completed
      }
    assertTrue(outcome.first is CancellationException, "cancellation must propagate, got [${outcome.first}]")
    assertNull(outcome.second, "a cancelled read must not answer with a Result at all")
  }

  @Test
  fun `the share is over all full-time freshmen`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Share College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )

    val merit = read(student).colleges.single().meritAid
    assertNotNull(merit)
    assertEquals(25.0, merit.shareOfAllFullTimeFreshmen?.percent)
    assertEquals(2000, merit.fullTimeFreshmen)
    assertEquals(500, merit.nonNeedMeritRecipients)
  }

  @Test
  fun `a college with no merit row still appears and says so`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "Silent College")

    val college = read(student).colleges.single()
    assertNull(college.meritAid, "a missing row is never a zero")
    assertTrue(AdmissionsField.MERIT_AID in college.notReported)
  }

  @Test
  fun `a merit row with no measures at all reads as unreported`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Empty Row College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = null,
    )

    val admissions = read(student).colleges.single()
    assertNull(admissions.meritAid, "a citation with no facts under it is not data")
    assertTrue(AdmissionsField.MERIT_AID in admissions.notReported)
  }

  @Test
  fun `a merit row with only the freshman total reads as unreported`() {
    // 28 of the seed's 368 merit rows are exactly this shape: a freshman total
    // and neither merit measure. The total is the share's DENOMINATOR, not a
    // merit fact, so the section is silence -- and the silence has to reach
    // notReported, or the school's "we do not report this" becomes unsayable.
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Denominator Only College")
    AdmissionsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2760,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = null,
    )

    val admissions = read(student).colleges.single()
    assertNull(admissions.meritAid, "a denominator alone is not a merit fact")
    assertTrue(AdmissionsField.MERIT_AID in admissions.notReported)
  }

  @Test
  fun `either merit measure alone is still a reported section`() {
    val student = AdmissionsTestDb.createStudent()
    val countOnly = AdmissionsTestDb.seedListedCollege(student, "Count Only College")
    AdmissionsTestDb.seedMeritAid(
      countOnly,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = 358,
      noNeedMeritAverageUsd = null,
    )
    val averageOnly = AdmissionsTestDb.seedListedCollege(student, "Average Only College")
    AdmissionsTestDb.seedMeritAid(
      averageOnly,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = 16112,
    )

    val byId = read(student).colleges.associateBy { it.collegeId }
    assertEquals(358, assertNotNull(byId.getValue(countOnly).meritAid).nonNeedMeritRecipients)
    assertEquals(16112, assertNotNull(byId.getValue(averageOnly).meritAid).averageNonNeedAid)
    assertTrue(byId.getValue(countOnly).notReported.none { it == AdmissionsField.MERIT_AID })
  }

  // ---------------------------------------------------------------------------
  // Factors (D6)
  // ---------------------------------------------------------------------------

  @Test
  fun `a null factor is omitted while an explicit not considered is kept`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Factors College")
    AdmissionsTestDb.seedFactors(college, interview = null, alumniRelation = FactorRating.NOT_CONSIDERED)

    val weights =
      read(student)
        .colleges
        .single()
        .factors
        ?.weights
        .orEmpty()
    assertTrue(
      weights.none { it.factor == ed.unicoach.db.models.AdmissionFactor.INTERVIEW },
      "an unreported factor must be absent, not rendered as anything",
    )
    assertEquals(
      FactorRating.NOT_CONSIDERED,
      weights.single { it.factor == ed.unicoach.db.models.AdmissionFactor.ALUMNI_RELATION }.rating,
      "the school's own 'not considered' is a reported fact and stays",
    )
  }

  // ---------------------------------------------------------------------------
  // Deadlines (D5)
  // ---------------------------------------------------------------------------

  @Test
  fun `an unoffered round is kept as a reported fact`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Rounds College")
    AdmissionsTestDb.seedDeadline(college, round = ApplicationRound.EARLY_DECISION_2, offered = false, closing = null, notification = null)
    AdmissionsTestDb.seedDeadline(college, round = ApplicationRound.REGULAR, offered = true, closing = CdsMonthDay(1, 15))

    val rounds =
      read(student)
        .colleges
        .single()
        .deadlines
        ?.rounds
        .orEmpty()
    assertEquals(2, rounds.size)
    assertEquals(false, rounds.single { it.round == ApplicationRound.EARLY_DECISION_2 }.offered)
  }

  @Test
  fun `a college with no rounds says deadlines are unreported`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "No Rounds College")

    val college = read(student).colleges.single()
    assertNull(college.deadlines)
    assertEquals(
      listOf(
        AdmissionsField.MERIT_AID,
        AdmissionsField.ADMISSION_FACTORS,
        AdmissionsField.TEST_POLICY,
        AdmissionsField.DEADLINES,
      ),
      college.notReported,
    )
  }

  @Test
  fun `the rounds of one school come from one cycle, so the citation is true of all of them`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Two Cycle College")
    AdmissionsTestDb.seedDeadline(college, round = ApplicationRound.EARLY_ACTION, sourceYear = 2024)
    AdmissionsTestDb.seedDeadline(college, round = ApplicationRound.REGULAR, sourceYear = 2025)

    val schedule = read(student).colleges.single().deadlines
    assertNotNull(schedule)
    assertEquals(listOf(ApplicationRound.REGULAR), schedule.rounds.map { it.round })
    assertEquals("Two Cycle College's 2025-26 Common Data Set", schedule.source.citedAs)
  }

  // ---------------------------------------------------------------------------
  // Cycles and citations (D2, D3)
  // ---------------------------------------------------------------------------

  @Test
  fun `the latest cycle is resolved per table, in one result`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Mixed Cycle University")
    AdmissionsTestDb.seedFactors(college, sourceYear = 2024)
    AdmissionsTestDb.seedMeritAid(college, sourceYear = 2024, noNeedMeritAverageUsd = 1000)
    AdmissionsTestDb.seedMeritAid(college, sourceYear = 2025, noNeedMeritAverageUsd = 2000)

    val admissions = read(student).colleges.single()
    assertEquals("Mixed Cycle University's 2025-26 Common Data Set", admissions.meritAid?.source?.citedAs)
    assertEquals(2000, admissions.meritAid?.averageNonNeedAid)
    assertEquals("Mixed Cycle University's 2024-25 Common Data Set", admissions.factors?.source?.citedAs)
  }

  @Test
  fun `each section carries its own citation, and a null archive copy is simply absent`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Cited College")
    // ONE filing backs all three sections (RFC 170, D13), so the mirror is a
    // property of that filing: a school whose CDS we have not archived shows
    // no archive copy anywhere, rather than showing one under merit aid and
    // not under deadlines. The two urls cannot disagree between sections
    // because there is only one copy of them.
    AdmissionsTestDb.seedMeritAid(college, archiveUrl = null)
    AdmissionsTestDb.seedFactors(college, archiveUrl = null)
    AdmissionsTestDb.seedDeadline(college, archiveUrl = null)

    val admissions = read(student).colleges.single()
    assertEquals(AdmissionsTestDb.SOURCE_URL, admissions.meritAid?.source?.url)
    assertNull(admissions.meritAid?.source?.archiveUrl)
    assertNull(admissions.factors?.source?.archiveUrl)
    assertNull(admissions.deadlines?.source?.archiveUrl)
    assertEquals("Cited College's 2024-25 Common Data Set", admissions.deadlines?.source?.citedAs)

    val mirrored = AdmissionsTestDb.seedListedCollege(student, "Mirrored College")
    AdmissionsTestDb.seedMeritAid(mirrored)
    AdmissionsTestDb.seedFactors(mirrored)
    val second = read(student, listOf(mirrored)).colleges.single()
    assertEquals(AdmissionsTestDb.ARCHIVE_URL, second.meritAid?.source?.archiveUrl)
    assertEquals(AdmissionsTestDb.ARCHIVE_URL, second.factors?.source?.archiveUrl)
  }

  @Test
  fun `the cycle label spans the two academic years`() {
    assertEquals("2024-25", CdsCitation.cycleLabel(2024))
    assertEquals("2025-26", CdsCitation.cycleLabel(2025))
    // The century roll is written down once, here, so it cannot be rediscovered wrongly in 2099.
    assertEquals("2099-00", CdsCitation.cycleLabel(2099))
  }

  // ---------------------------------------------------------------------------
  // The test policy (RFC 187)
  // ---------------------------------------------------------------------------

  @Test
  fun `each published policy code becomes its own member, with the survey year off the row`() {
    val student = AdmissionsTestDb.createStudent()
    val required = AdmissionsTestDb.seedListedCollege(student, "Required College")
    val optional = AdmissionsTestDb.seedListedCollege(student, "Optional College")
    val blind = AdmissionsTestDb.seedListedCollege(student, "Blind College")
    // 2024, deliberately: the citation must follow the ROW, so a fixture
    // collected in a year other than 2023 is what proves no literal 2023 is
    // hiding in the sentence a family hears.
    AdmissionsTestDb.seedIpedsTestPolicy(required, testPolicy = 1, surveyYear = 2024)
    AdmissionsTestDb.seedIpedsTestPolicy(optional, testPolicy = 5, surveyYear = 2024)
    AdmissionsTestDb.seedIpedsTestPolicy(blind, testPolicy = 3, surveyYear = 2024)

    val byName = read(student).colleges.associateBy { it.name }
    assertEquals(AdmissionTestPolicy.REQUIRED, byName.getValue("Required College").testPolicy?.policy)
    assertEquals(AdmissionTestPolicy.TEST_OPTIONAL, byName.getValue("Optional College").testPolicy?.policy)
    assertEquals(AdmissionTestPolicy.TEST_BLIND, byName.getValue("Blind College").testPolicy?.policy)
    assertEquals(
      2024,
      byName
        .getValue("Required College")
        .testPolicy
        ?.source
        ?.surveyYear,
    )
    assertTrue(
      byName
        .getValue("Required College")
        .testPolicy
        ?.source
        ?.citedAs
        ?.contains("2024") == true,
      "the spoken citation carries the row's year: [${byName.getValue("Required College").testPolicy?.source?.citedAs}]",
    )
  }

  @Test
  fun `the statement is computed from the policy, never carried beside it`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Optional College")
    AdmissionsTestDb.seedIpedsTestPolicy(college, testPolicy = 5)

    val section = read(student).colleges.single().testPolicy
    assertNotNull(section)
    // Asserted against the copy object rather than a retyped sentence: the
    // point of computing it is that the words and the code cannot drift, and a
    // literal here would be a second home for the words.
    assertEquals(TestPolicyCopy.statementOf(AdmissionTestPolicy.TEST_OPTIONAL), section.statement)
    assertTrue(section.statement.contains("did not require"), "the test-optional sentence says what the school did")
  }

  @Test
  fun `no IPEDS row and a row reporting no policy are both silence the coach can state`() {
    val student = AdmissionsTestDb.createStudent()
    val noRow = AdmissionsTestDb.seedListedCollege(student, "No Row College")
    val noPolicy = AdmissionsTestDb.seedListedCollege(student, "No Policy College")
    // The row exists -- IPEDS has this school -- it simply does not answer
    // ADMCON7. Different from the school above in the DAO, identical here.
    AdmissionsTestDb.seedIpedsTestPolicy(noPolicy, testPolicy = null)

    val byName = read(student).colleges.associateBy { it.name }
    listOf("No Row College", "No Policy College").forEach { name ->
      assertNull(byName.getValue(name).testPolicy, "[$name] reports no policy we can state")
      assertTrue(
        AdmissionsField.TEST_POLICY in byName.getValue(name).notReported,
        "[$name] must name the section in data_availability rather than leaving a silence",
      )
    }
  }

  @Test
  fun `a code outside the published set maps to nothing, so the section can only ever be suppressed`() {
    // `testPolicyOf` suppresses the section for a code that maps to no
    // [AdmissionTestPolicy] member, and that arm cannot be driven through the
    // database: `college_ipeds_test_policy_domain_check` admits only 1, 3 and 5
    // and fires on UPDATE as well as INSERT, so a fixture storing a 2 is
    // refused before the service ever reads it. The guard is therefore pinned
    // where it is decidable -- at the mapping itself -- so relaxing the CHECK
    // cannot quietly turn an unknown code into some other school's sentence.
    assertNull(AdmissionTestPolicy.fromCode(2), "2 is not a published ADMCON7 code")
    assertNull(AdmissionTestPolicy.fromCode(0), "and neither is the zero a SQL NULL reads as")
    assertEquals(
      setOf(1, 3, 5),
      AdmissionTestPolicy.entries.map { it.code }.toSet(),
      "the published set is 1, 3 and 5 -- the domain the column's own CHECK enforces",
    )
  }

  @Test
  fun `a school that reports every other section still reports its policy silence`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Full CDS College")
    AdmissionsTestDb.seedMeritAid(college)
    AdmissionsTestDb.seedFactors(college)
    AdmissionsTestDb.seedDeadline(college)

    val admissions = read(student).colleges.single()
    assertNotNull(admissions.meritAid)
    assertNotNull(admissions.factors)
    assertNotNull(admissions.deadlines)
    // The four sections are independent: a school's own Common Data Set says
    // nothing about whether the federal survey has its admissions row.
    assertEquals(listOf(AdmissionsField.TEST_POLICY), admissions.notReported)
  }

  @Test
  fun `a policy is reported independently of every CDS section`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "IPEDS Only College")
    AdmissionsTestDb.seedIpedsTestPolicy(college, testPolicy = 1)

    val admissions = read(student).colleges.single()
    assertEquals(AdmissionTestPolicy.REQUIRED, admissions.testPolicy?.policy)
    assertEquals(
      listOf(AdmissionsField.MERIT_AID, AdmissionsField.ADMISSION_FACTORS, AdmissionsField.DEADLINES),
      admissions.notReported,
      "the three CDS sections are silent and say so; the policy is unaffected",
    )
  }

  // ---------------------------------------------------------------------------
  // Score bands (RFC 195)
  // ---------------------------------------------------------------------------

  @Test
  fun `a college with published bands reports them, and says nothing about an average`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(
      student,
      "Banded College",
      satReadingBand = 620 to 700,
      satMathBand = 640 to 750,
      actBand = 28 to 33,
    )

    val section = read(student).colleges.single().scoreBands
    assertNotNull(section)
    val byTest = section.bands.associateBy { it.test }
    assertEquals(620, byTest.getValue(ScoredTest.SAT_READING).lower)
    assertEquals(700, byTest.getValue(ScoredTest.SAT_READING).upper)
    assertEquals(640, byTest.getValue(ScoredTest.SAT_MATH).lower)
    assertEquals(750, byTest.getValue(ScoredTest.SAT_MATH).upper)
    assertEquals(28, byTest.getValue(ScoredTest.ACT_COMPOSITE).lower)
    assertEquals(33, byTest.getValue(ScoredTest.ACT_COMPOSITE).upper)
    // D16, the direction the failure mode runs: a school with a band gets NO
    // average, even though this fixture publishes one. Showing both is how a
    // family comes to read the average as the bar.
    assertNull(section.average, "a band and an average for the same test is what D16 forbids")
    assertEquals(
      ScoredTest.entries,
      section.bands.map { it.test },
      "the bands are rendered in ScoredTest declaration order -- the two SAT sections, then the ACT",
    )
  }

  @Test
  fun `a college with no score at all renders no section and says so`() {
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "No Score College", satAverageEquivalentScore = null)

    val admissions = read(student).colleges.single()
    assertNull(admissions.scoreBands, "no band and no average is not a section")
    assertTrue(
      AdmissionsField.SCORE_BANDS in admissions.notReported,
      "our silence must be sayable: ${admissions.notReported}",
    )
  }

  @Test
  fun `the admitted average stands in for a missing SAT band, per test family`() {
    // D-A, and the measured shape it exists for: 88 colleges publish an SAT
    // average and no SAT band, and ALL 88 publish an ACT band. A rule of "an
    // average only when the school has no band of any kind" would therefore
    // never fire once, and those 88 students would be told nothing about the
    // SAT at the very school they asked about.
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(
      student,
      "ACT Only College",
      satAverageEquivalentScore = 1310,
      actBand = 28 to 33,
    )

    val section = read(student).colleges.single().scoreBands
    assertNotNull(section)
    assertEquals(
      listOf(ScoredTest.ACT_COMPOSITE),
      section.bands.map { it.test },
      "the school publishes no SAT band, and none is fabricated from the average",
    )
    assertEquals(28, section.bands.single().lower, "the ACT band it DOES publish is unaffected")
    assertEquals(1310, section.average?.score, "the average stands in for the missing SAT band, beside the ACT band")
    assertTrue(
      section.bands.none { it.test.family == ScoreFamily.SAT },
      "D-A is decided on the FAMILY: an ACT band does not suppress an SAT-equivalent average",
    )
  }

  @Test
  fun `an average alone is a section, and a band alone is a section`() {
    val student = AdmissionsTestDb.createStudent()
    val averageOnly = AdmissionsTestDb.seedListedCollege(student, "Average Only College")
    val bandOnly =
      AdmissionsTestDb.seedListedCollege(
        student,
        "Band Only College",
        satAverageEquivalentScore = null,
        satReadingBand = 600 to 690,
        satMathBand = 610 to 710,
      )

    val byId = read(student).colleges.associateBy { it.collegeId }
    val average = byId.getValue(averageOnly).scoreBands
    assertNotNull(average)
    assertEquals(emptyList(), average.bands, "an average-only section carries no band at all")
    assertEquals(1200, average.average?.score)

    // The complement: two colleges publish a band and no average at all, so a
    // section built only from the average would drop them.
    val band = byId.getValue(bandOnly).scoreBands
    assertNotNull(band)
    assertNull(band.average)
    assertEquals(600, band.bands.first { it.test == ScoredTest.SAT_READING }.lower)
  }

  @Test
  fun `a stored band that violates the CHECKs is logged, and never becomes an average`() {
    // The four states `ScoreBand.of` used to fold into one null. Three of them
    // are stored rows the `colleges` CHECKs forbid -- half a pair, an inverted
    // pair, a score outside the published scale -- so they cannot be SEEDED at
    // all, which is the point: such a row means the CHECK was dropped or
    // something wrote past it. The service is handed the row directly, which
    // is the only honest way to exercise a state a correct database refuses.
    val student = AdmissionsTestDb.createStudent()
    val id = AdmissionsTestDb.seedListedCollege(student, "Broken Band College", satAverageEquivalentScore = 1310)
    val stored = CollegesDao.listByIds(AdmissionsTestDb.sqlSession, listOf(id)).getOrThrow().single()

    // Half an SAT reading band, and an out-of-scale SAT math band.
    val malformed =
      stored.copy(
        satReading25thPercentileScore = 620,
        satReading75thPercentileScore = null,
        satMath25thPercentileScore = 640,
        satMath75thPercentileScore = 1500,
        actComposite25thPercentileScore = 28,
        actComposite75thPercentileScore = 33,
      )

    val warnings = captureWarns { assertNotNull(service.scoreBandsOf(malformed)) }
    val section = assertNotNull(service.scoreBandsOf(malformed))

    // 1. The refusal is not silent. One line per malformed band, naming the
    //    college, the test and BOTH stored values -- an operator cannot find a
    //    row it is not told about.
    assertEquals(2, warnings.size, "one warning per malformed band: $warnings")
    val reading = assertNotNull(warnings.firstOrNull { it.contains("sat_reading") }, "$warnings")
    assertTrue(reading.contains(id.value.toString()), "the college must be named: [$reading]")
    assertTrue(reading.contains("lower=[620]") && reading.contains("upper=[null]"), "with its figures: [$reading]")
    assertTrue(reading.contains(MalformedBand.HALF_A_PAIR.value), "and why it was refused: [$reading]")
    val math = assertNotNull(warnings.firstOrNull { it.contains("sat_math") }, "$warnings")
    assertTrue(math.contains(MalformedBand.OUT_OF_SCALE.value), "[$math]")

    // 2. The refusal does not quietly change what the family is told. This
    //    school HAS a stored SAT range; we refused to read it, so the answer
    //    is our gap and not a different figure on a different scale.
    assertNull(
      section.average,
      "a refused SAT band must never promote the school into the average path: the row holds a range",
    )
    assertEquals(listOf(ScoredTest.ACT_COMPOSITE), section.bands.map { it.test }, "the readable band still speaks")
  }

  @Test
  fun `a school whose only SAT band is malformed is a named silence, not an average`() {
    // The same rule with nothing else to say: no section at all, and the
    // school named in `data_availability`. Our gap, said plainly.
    val student = AdmissionsTestDb.createStudent()
    val id = AdmissionsTestDb.seedListedCollege(student, "Only Broken College", satAverageEquivalentScore = 1310)
    val stored = CollegesDao.listByIds(AdmissionsTestDb.sqlSession, listOf(id)).getOrThrow().single()
    val malformed = stored.copy(satReading25thPercentileScore = 700, satReading75thPercentileScore = 620)

    val warnings = captureWarns { assertNull(service.scoreBandsOf(malformed)) }
    assertEquals(1, warnings.size, "the inverted band is reported: $warnings")
    assertTrue(warnings.single().contains(MalformedBand.INVERTED.value), warnings.single())
  }

  @Test
  fun `ordinary absence is not logged`() {
    // 5,234 of the snapshot's 6,273 colleges publish no score. A line for each
    // would be 83% of the corpus restating what the federal file is, and the
    // three lines that matter would be unfindable inside it.
    val student = AdmissionsTestDb.createStudent()
    val id = AdmissionsTestDb.seedListedCollege(student, "Silent College", satAverageEquivalentScore = null)
    val stored = CollegesDao.listByIds(AdmissionsTestDb.sqlSession, listOf(id)).getOrThrow().single()

    assertEquals(emptyList(), captureWarns { assertNull(service.scoreBandsOf(stored)) }, "no score published is not a defect")
  }

  @Test
  fun `an average outside the published composite scale is refused, not spoken`() {
    // The only rendered figure in this section with no scale gate:
    // `sat_average_equivalent_score` is loaded 0..Int.MAX_VALUE and its DB
    // guard is merely non-negative, so a stored 0 reached the wire as
    // "0 was the average score of students admitted for autumn 2024".
    val student = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(student, "Zero Average College", satAverageEquivalentScore = 0)

    val zero = read(student).colleges.single()
    assertNull(zero.scoreBands, "0 is not an average, so there is no figure and no section")
    assertTrue(AdmissionsField.SCORE_BANDS in zero.notReported, "and the silence is sayable: ${zero.notReported}")

    // The complement, so the refusal is a scale and not a blanket: a real
    // average on the published scale still speaks.
    val other = AdmissionsTestDb.createStudent()
    AdmissionsTestDb.seedListedCollege(other, "Real Average College", satAverageEquivalentScore = 1310)
    assertEquals(
      1310,
      read(other)
        .colleges
        .single()
        .scoreBands
        ?.average
        ?.score,
    )
  }

  /** The WARNs an exercise emits, captured off the root logger -- [ed.unicoach.coaching.LlmCallLogTest]'s idiom. */
  private fun captureWarns(exercise: () -> Unit): List<String> {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
      exercise()
    } finally {
      root.detachAppender(appender)
    }
    return appender.list.filter { it.level.levelStr == "WARN" }.map { it.formattedMessage }
  }

  // ---------------------------------------------------------------------------
  // Batching
  // ---------------------------------------------------------------------------

  @Test
  fun `many colleges are answered without a read per college`() {
    val student = AdmissionsTestDb.createStudent()
    val ids =
      (1..5).map { n ->
        // Every section populated, INCLUDING the score bands: a section left
        // absent is a section the statement count never sees.
        AdmissionsTestDb
          .seedListedCollege(
            student,
            "Batch College $n",
            satReadingBand = 620 to 700,
            satMathBand = 640 to 750,
            actBand = 28 to 33,
          ).also {
            AdmissionsTestDb.seedMeritAid(it)
            AdmissionsTestDb.seedFactors(it)
            AdmissionsTestDb.seedDeadline(it)
            AdmissionsTestDb.seedIpedsTestPolicy(it)
          }
      }

    val profile = read(student, ids)
    assertEquals(5, profile.colleges.size)
    assertTrue(
      profile.colleges.all {
        it.meritAid != null && it.factors != null && it.testPolicy != null && it.deadlines != null
      },
    )
    // RFC 195's section rides the `colleges` row the selection already read,
    // so it is present for every one of the five and costs nothing below.
    assertTrue(profile.colleges.all { it.scoreBands != null }, "the fifth section must be populated for all five")
    // The batch reads are keyed by college id; a duplicated id is read once.
    assertEquals(1, read(student, listOf(ids.first(), ids.first())).colleges.size)

    // The claim above is a statement count, so count statements: one college
    // and five colleges must prepare the SAME statements. A per-college loop in
    // any of the three CDS reads -- or in the IPEDS test-policy read RFC 187
    // added, which costs one more statement for the whole answer and not one
    // per college -- fails here and nowhere else.
    val one = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    assertEquals(1, service.readInSession(one, student, ids.take(1)).colleges.size)
    assertEquals(5, service.readInSession(five, student, ids).colleges.size)
    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "the read must cost the same statements for five colleges as for one, " +
        "never one per college: one=[${one.prepared}] five=[${five.prepared}]",
    )
    // And the count is ABSOLUTE, not merely equal to itself. "Five colleges
    // cost what one college costs" stays true when a sixth read is added, so on
    // its own it would let the read grow silently past the number
    // [CollegeAdmissionsService.readInSession]'s own KDoc promises -- the list
    // selection plus one statement per reference table. A new read is then a
    // deliberate edit here and to that sentence, together.
    assertEquals(
      BATCHED_STATEMENTS,
      five.prepared.size,
      "the whole admissions read is [$BATCHED_STATEMENTS] statements -- the selection's two, the three CDS " +
        "tables and the IPEDS test policy, and NOT the score bands, which ride the `colleges` row (RFC 195). " +
        "It prepared [${five.prepared}]; if that is correct, say so in readInSession's KDoc in the same commit",
    )
  }

  @Test
  fun `a student with no list reads nothing and fails at nothing`() {
    val student = AdmissionsTestDb.createStudent()
    val profile = read(student)
    assertEquals(emptyList(), profile.colleges.map { it.name })
    assertEquals(emptyList(), profile.unknownCollegeIds)
    // An id that is not any college at all is unknown, not an error.
    assertEquals(1, read(student, listOf(CollegeId(UUID.randomUUID()))).unknownCollegeIds.size)
  }
}
