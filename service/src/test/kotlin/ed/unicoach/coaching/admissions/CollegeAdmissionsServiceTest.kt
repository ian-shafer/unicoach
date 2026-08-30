package ed.unicoach.coaching.admissions

import ed.unicoach.coaching.CoachingTestDb
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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = null, noNeedMeritCount = 358, noNeedMeritAvg = 16112)

    val merit = read(student).colleges.single().meritAid
    assertNotNull(merit)
    assertNull(merit.shareOfAllFullTimeFreshmen, "a share with no denominator must not be computed")
    assertEquals(16112, merit.averageNonNeedAid)
  }

  @Test
  fun `a missing recipient count also withholds the share`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "No Count College")
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 2000, noNeedMeritCount = null, noNeedMeritAvg = 16112)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 480, noNeedMeritCount = 0, noNeedMeritAvg = 0)

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
    // `freshmen_ft_total >= 0` is in domain and the corpus CHECK then forces 0
    // recipients, so a mangled extraction can report a school with no freshman
    // class at all. Rendering it would put two bare zeroes and a citation with
    // no computable share in front of the coach, and -- worse -- keep the
    // school's merit silence OUT of data_availability, where the coach reads
    // it. It is ruled on exactly like the denominator-only row.
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 0, noNeedMeritCount = 0, noNeedMeritAvg = null)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 2000, noNeedMeritCount = 500, noNeedMeritAvg = 12500)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = null, noNeedMeritCount = null, noNeedMeritAvg = null)

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
    AdmissionsTestDb.seedMeritAid(college, freshmenFtTotal = 2760, noNeedMeritCount = null, noNeedMeritAvg = null)

    val admissions = read(student).colleges.single()
    assertNull(admissions.meritAid, "a denominator alone is not a merit fact")
    assertTrue(AdmissionsField.MERIT_AID in admissions.notReported)
  }

  @Test
  fun `either merit measure alone is still a reported section`() {
    val student = AdmissionsTestDb.createStudent()
    val countOnly = AdmissionsTestDb.seedListedCollege(student, "Count Only College")
    AdmissionsTestDb.seedMeritAid(countOnly, freshmenFtTotal = null, noNeedMeritCount = 358, noNeedMeritAvg = null)
    val averageOnly = AdmissionsTestDb.seedListedCollege(student, "Average Only College")
    AdmissionsTestDb.seedMeritAid(averageOnly, freshmenFtTotal = null, noNeedMeritCount = null, noNeedMeritAvg = 16112)

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
      listOf(AdmissionsField.MERIT_AID, AdmissionsField.ADMISSION_FACTORS, AdmissionsField.DEADLINES),
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
    AdmissionsTestDb.seedMeritAid(college, sourceYear = 2024, noNeedMeritAvg = 1000)
    AdmissionsTestDb.seedMeritAid(college, sourceYear = 2025, noNeedMeritAvg = 2000)

    val admissions = read(student).colleges.single()
    assertEquals("Mixed Cycle University's 2025-26 Common Data Set", admissions.meritAid?.source?.citedAs)
    assertEquals(2000, admissions.meritAid?.averageNonNeedAid)
    assertEquals("Mixed Cycle University's 2024-25 Common Data Set", admissions.factors?.source?.citedAs)
  }

  @Test
  fun `each section carries its own citation, and a null archive copy is simply absent`() {
    val student = AdmissionsTestDb.createStudent()
    val college = AdmissionsTestDb.seedListedCollege(student, "Cited College")
    AdmissionsTestDb.seedMeritAid(college, archiveUrl = null)
    AdmissionsTestDb.seedFactors(college)
    AdmissionsTestDb.seedDeadline(college)

    val admissions = read(student).colleges.single()
    assertEquals(AdmissionsTestDb.SOURCE_URL, admissions.meritAid?.source?.url)
    assertNull(admissions.meritAid?.source?.archiveUrl)
    assertEquals(AdmissionsTestDb.ARCHIVE_URL, admissions.factors?.source?.archiveUrl)
    assertEquals("Cited College's 2024-25 Common Data Set", admissions.deadlines?.source?.citedAs)
  }

  @Test
  fun `the cycle label spans the two academic years`() {
    assertEquals("2024-25", CdsCitation.cycleLabel(2024))
    assertEquals("2025-26", CdsCitation.cycleLabel(2025))
    // The century roll is written down once, here, so it cannot be rediscovered wrongly in 2099.
    assertEquals("2099-00", CdsCitation.cycleLabel(2099))
  }

  // ---------------------------------------------------------------------------
  // Batching
  // ---------------------------------------------------------------------------

  @Test
  fun `many colleges are answered without a read per college`() {
    val student = AdmissionsTestDb.createStudent()
    val ids =
      (1..5).map { n ->
        AdmissionsTestDb.seedListedCollege(student, "Batch College $n").also {
          AdmissionsTestDb.seedMeritAid(it)
          AdmissionsTestDb.seedFactors(it)
          AdmissionsTestDb.seedDeadline(it)
        }
      }

    val profile = read(student, ids)
    assertEquals(5, profile.colleges.size)
    assertTrue(profile.colleges.all { it.meritAid != null && it.factors != null && it.deadlines != null })
    // The batch reads are keyed by college id; a duplicated id is read once.
    assertEquals(1, read(student, listOf(ids.first(), ids.first())).colleges.size)

    // The claim above is a statement count, so count statements: one college
    // and five colleges must prepare the SAME statements. A per-college loop in
    // any of the three CDS reads fails here and nowhere else.
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
