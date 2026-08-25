package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.CostsTestDb.addToCollegeList
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.costs.CostsTestDb.seedCollege
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeCostServiceTest {
  @BeforeEach
  fun resetDatabase() {
    CostsTestDb.reset()
  }

  private val service = CollegeCostService(CostsTestDb.database)
  private val moneyProfiles = MoneyProfileService(CostsTestDb.database)

  private fun answerBand(
    student: StudentId,
    band: IncomeBand,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(band))).getOrThrow()
  }

  private fun declineBand(student: StudentId) =
    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Decline)).getOrThrow()
    }

  private fun answerResidency(
    student: StudentId,
    state: String,
  ) = runBlocking {
    moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Set(state))).getOrThrow()
  }

  private fun profileOf(
    student: StudentId,
    ids: List<CollegeId>? = null,
  ): CollegeCostProfile = runBlocking { service.getForStudent(student, ids).getOrThrow() }

  // ---------------------------------------------------------------------------
  // Net-price basis
  // ---------------------------------------------------------------------------

  @Test
  fun `an answered band selects that band's amount as a band-specific price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Band U"))
    answerBand(student, IncomeBand.K48_TO_75K)

    val netPrice = assertIs<NetPrice.BandSpecific>(profileOf(student).colleges.single().netPrice)
    assertEquals(14000, netPrice.amount, "48k_to_75k must select net_price_q3")
    assertEquals(IncomeBand.K48_TO_75K, netPrice.band)
  }

  @Test
  fun `an unanswered band falls back to the overall average and carries the precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Avg U"))
    answerResidency(student, "CA") // profile row exists; the band field is unanswered

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val netPrice = assertIs<NetPrice.OverallAverage>(cost.netPrice, "an overall average can carry no band")
    assertEquals(20000, netPrice.amount)
    assertTrue(profile.precisionOfferFor(cost), "an unanswered band must carry the in-answer invitation")
  }

  @Test
  fun `a declined band falls back to the overall average WITHOUT the precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Declined U"))
    declineBand(student)

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertIs<NetPrice.OverallAverage>(cost.netPrice)
    assertEquals(AnswerStatus.DECLINED, profile.moneyProfile.incomeBandStatus)
    assertTrue(!profile.precisionOfferFor(cost), "the coach must never be cued to reopen a declined band")
  }

  @Test
  fun `an answered band also carries no precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Answered U"))
    answerBand(student, IncomeBand.UNDER_30K)
    val profile = profileOf(student)
    assertTrue(!profile.precisionOfferFor(profile.colleges.single()))
  }

  @Test
  fun `a college reporting no bracket columns makes no precision offer even when the band is unanswered`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "NoBands U",
        netPriceQ1 = null,
        netPriceQ2 = null,
        netPriceQ3 = null,
        netPriceQ4 = null,
        netPriceQ5 = null,
      ),
    )

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
    assertTrue(!cost.reportsBandPricing)
    assertTrue(!profile.precisionOfferFor(cost), "a college with no band data makes no upgrade promise")
  }

  @Test
  fun `a band re-answered after a decline flows through on the next read`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Reopen U"))

    declineBand(student)
    assertIs<NetPrice.OverallAverage>(
      profileOf(student)
        .colleges
        .single()
        .netPrice,
    )

    // The student changes their mind: update_money_profile re-answers over
    // declined at any time (RFC 134); the very next read is family-specific.
    answerBand(student, IncomeBand.OVER_110K)
    val netPrice = assertIs<NetPrice.BandSpecific>(profileOf(student).colleges.single().netPrice)
    assertEquals(21000, netPrice.amount, "over_110k must select net_price_q5")
  }

  @Test
  fun `an absent money profile row reads as all-unanswered`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("NoProfile U"))

    val profile = profileOf(student)
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.residencyStatus)
    val cost = profile.colleges.single()
    assertIs<NetPrice.OverallAverage>(cost.netPrice)
    assertTrue(profile.precisionOfferFor(cost))
  }

  // ---------------------------------------------------------------------------
  // Tuition applicability
  // ---------------------------------------------------------------------------

  @Test
  fun `answered residency resolves applicable tuition for public colleges and leaves private ones without the field`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home State U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Away State U", state = "NY", control = 1))
    addToCollegeList(student, seedCollege("Private U", state = "NY", control = 2))
    answerResidency(student, "CA")

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(
      TuitionApplicable.IN_STATE,
      assertIs<CollegeControl.Public>(byName.getValue("Home State U").control).tuitionApplicable,
    )
    assertEquals(
      TuitionApplicable.OUT_OF_STATE,
      assertIs<CollegeControl.Public>(byName.getValue("Away State U").control).tuitionApplicable,
    )
    // A private college has one price; its case cannot carry applicability at all.
    assertIs<CollegeControl.PrivateNonprofit>(byName.getValue("Private U").control)
  }

  @Test
  fun `unanswered or declined residency leaves public tuition applicability unknown`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Public U", state = "CA", control = 1))
    assertEquals(
      TuitionApplicable.UNKNOWN,
      assertIs<CollegeControl.Public>(profileOf(student).colleges.single().control).tuitionApplicable,
    )

    runBlocking {
      moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Decline)).getOrThrow()
    }
    assertEquals(
      TuitionApplicable.UNKNOWN,
      assertIs<CollegeControl.Public>(profileOf(student).colleges.single().control).tuitionApplicable,
    )
  }

  @Test
  fun `an unrecognized control code keeps the raw value observable in the label`() {
    // colleges_control_valid_check keeps codes 1..3 in the DB; the case exists
    // for vocabulary drift, so the label contract is asserted directly.
    val control = CollegeControl.Unrecognized(9)
    assertEquals(9, control.code)
    assertEquals("unknown (control [9])", control.label, "the out-of-vocabulary code must ride the wire label")
  }

  // ---------------------------------------------------------------------------
  // Corrupt persisted values
  // ---------------------------------------------------------------------------

  @Test
  fun `an answered band with a corrupt null stored value fails the read, never folds into the overall average`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Corrupt U"))
    answerBand(student, IncomeBand.UNDER_30K)

    // The schema's iff-answered CHECK makes this state unreachable through any
    // write path; force it by dropping the constraint, then restore it.
    CostsTestDb.connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_income_band_value_iff_answered_check")
      stmt.execute("UPDATE money_profiles SET income_band = NULL, version = version + 1 WHERE student_id = '${student.value}'")
    }
    try {
      val result = runBlocking { service.getForStudent(student) }
      val error = result.exceptionOrNull()
      assertIs<CorruptPersistedValueException>(error, "got [$result]")
      assertTrue(
        error.message!!.contains("money_profiles.income_band"),
        "the error must name the corrupt column, got: [${error.message}]",
      )
      assertTrue(error.message!!.contains("(row ["), "the error must name the row, got: [${error.message}]")
    } finally {
      CostsTestDb.connection.createStatement().use { stmt ->
        stmt.execute("UPDATE money_profiles SET income_band = 'under_30k', version = version + 1 WHERE student_id = '${student.value}'")
        stmt.execute(
          "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_income_band_value_iff_answered_check " +
            "CHECK ((income_band IS NOT NULL) = (income_band_status = 'answered'))",
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Data availability
  // ---------------------------------------------------------------------------

  @Test
  fun `unreported columns surface in notReported`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege("Sparse U", costAttendance = null, netPrice = null, medianDebt = null, tuitionOutState = null),
    )

    val cost = profileOf(student).colleges.single()
    assertEquals(
      listOf(
        CostField.STICKER_COST_ATTENDANCE,
        CostField.TUITION_OUT_STATE,
        CostField.NET_PRICE,
        CostField.MEDIAN_DEBT,
      ),
      cost.notReported,
    )
    assertNull(cost.netPrice.amount)
  }

  @Test
  fun `an answered band whose bracket the college does not report keeps the basis and lists net_price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("NoBracket U", netPriceQ1 = null))
    answerBand(student, IncomeBand.UNDER_30K)

    val cost = profileOf(student).colleges.single()
    val netPrice = assertIs<NetPrice.BandSpecific>(cost.netPrice, "a missing bracket is not a silent fallback")
    assertNull(netPrice.amount)
    assertTrue(CostField.NET_PRICE in cost.notReported)
  }

  // ---------------------------------------------------------------------------
  // List shape: empty, subset, unknown ids
  // ---------------------------------------------------------------------------

  @Test
  fun `an empty college list is a valid result with the money profile intact`() {
    val student = createStudent()
    answerBand(student, IncomeBand.UNDER_30K)

    val profile = profileOf(student)
    assertTrue(profile.colleges.isEmpty())
    assertTrue(profile.unknownCollegeIds.isEmpty())
    assertEquals(AnswerStatus.ANSWERED, profile.moneyProfile.incomeBandStatus)
    assertNull(profile.ingestYear, "no colleges, no ingest year")
  }

  @Test
  fun `an explicit empty subset answers no colleges but keeps the money profile block`() {
    val student = createStudent()
    val a = seedCollege("A U")
    addToCollegeList(student, a)

    // [] is a literal empty subset — distinct from null (whole list).
    val profile = profileOf(student, emptyList())
    assertTrue(profile.colleges.isEmpty())
    assertTrue(profile.unknownCollegeIds.isEmpty())
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
  }

  @Test
  fun `a subset filter answers only the requested colleges in request order`() {
    val student = createStudent()
    val a = seedCollege("A U")
    val b = seedCollege("B U")
    val c = seedCollege("C U")
    addToCollegeList(student, a)
    addToCollegeList(student, b, CollegeListEntryStatus.APPLYING)
    addToCollegeList(student, c)

    val profile = profileOf(student, listOf(c, b))
    assertEquals(listOf("C U", "B U"), profile.colleges.map { it.name })
    assertEquals(CollegeListEntryStatus.APPLYING, profile.colleges[1].listStatus)
    assertTrue(profile.unknownCollegeIds.isEmpty())
  }

  @Test
  fun `unknown and foreign ids are reported while known ones still answer`() {
    val student = createStudent()
    val other = createStudent()
    val mine = seedCollege("Mine U")
    val theirs = seedCollege("Theirs U")
    addToCollegeList(student, mine)
    addToCollegeList(other, theirs)
    val nowhere = CollegeId(UUID.randomUUID())

    val profile = profileOf(student, listOf(mine, theirs, nowhere))
    assertEquals(listOf("Mine U"), profile.colleges.map { it.name })
    assertEquals(listOf(theirs, nowhere), profile.unknownCollegeIds, "another student's entry is as unknown as a random id")
  }

  @Test
  fun `the whole active list is returned in list order with statuses and the ingest year`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("First U"))
    addToCollegeList(student, seedCollege("Second U"), CollegeListEntryStatus.ADMITTED)

    val profile = profileOf(student)
    assertEquals(listOf("First U", "Second U"), profile.colleges.map { it.name })
    assertEquals(
      listOf(CollegeListEntryStatus.CONSIDERING, CollegeListEntryStatus.ADMITTED),
      profile.colleges.map { it.listStatus },
    )
    assertEquals(
      java.time.Year
        .now(java.time.ZoneOffset.UTC)
        .value,
      profile.ingestYear,
    )
  }
}
