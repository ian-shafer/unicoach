package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.costs.CostsTestDb.addToCollegeList
import ed.unicoach.coaching.costs.CostsTestDb.answerBand
import ed.unicoach.coaching.costs.CostsTestDb.answerResidency
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.costs.CostsTestDb.declineBand
import ed.unicoach.coaching.costs.CostsTestDb.declineResidency
import ed.unicoach.coaching.costs.CostsTestDb.seedCollege
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeCostServiceTest {
  @BeforeEach
  fun resetDatabase() {
    CostsTestDb.reset()
  }

  private val service = CollegeCostService(CostsTestDb.database)

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
    assertEquals(14000, netPrice.amount, "48k_to_75k must select net_price_per_year_income_q3_usd")
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
    assertEquals(
      listOf(PrecisionOffer.INCOME_BAND),
      profile.precisionOffersFor(cost),
      "an unanswered band must carry the in-answer invitation, and answered residency offers nothing more",
    )
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
    assertTrue(
      PrecisionOffer.INCOME_BAND !in profile.precisionOffersFor(cost),
      "the coach must never be cued to reopen a declined band",
    )
  }

  @Test
  fun `an answered band also carries no precision offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Answered U"))
    answerBand(student, IncomeBand.UNDER_30K)
    val profile = profileOf(student)
    assertTrue(PrecisionOffer.INCOME_BAND !in profile.precisionOffersFor(profile.colleges.single()))
  }

  @Test
  fun `a college reporting no bracket columns makes no precision offer even when the band is unanswered`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "NoBands U",
        netPricePerYearIncomeQ1Usd = null,
        netPricePerYearIncomeQ2Usd = null,
        netPricePerYearIncomeQ3Usd = null,
        netPricePerYearIncomeQ4Usd = null,
        netPricePerYearIncomeQ5Usd = null,
      ),
    )

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertEquals(AnswerStatus.UNANSWERED, profile.moneyProfile.incomeBandStatus)
    assertTrue(!cost.reportsBandPricing)
    assertTrue(
      PrecisionOffer.INCOME_BAND !in profile.precisionOffersFor(cost),
      "a college with no band data makes no upgrade promise",
    )
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
    assertEquals(21000, netPrice.amount, "over_110k must select net_price_per_year_income_q5_usd")
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
    assertEquals(
      listOf(PrecisionOffer.RESIDENCY, PrecisionOffer.INCOME_BAND),
      profile.precisionOffersFor(cost),
      "all-unanswered at a public college with published tuition offers both upgrades, residency first",
    )
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

    declineResidency(student)
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

    // The case means "outside the vocabulary" by construction, so it must read
    // as unknown even for a code the vocabulary DOES define -- otherwise the
    // state survives only because `controlOf` routes 1/2/3 elsewhere, and any
    // other caller could ship a confident "private_nonprofit" for a control
    // nothing ever recognised (RFC 143).
    assertEquals(
      "unknown (control [2])",
      CollegeControl.Unrecognized(2).label,
      "an Unrecognized case never renders a recognised label",
    )
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
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_income_band_value_iff_answered_check")
      stmt.execute("UPDATE money_profiles SET income_band = NULL, version = version + 1 WHERE student_id = '${student.value}'")
    }
    try {
      val result = runBlocking { service.getForStudent(student) }
      val error = result.exceptionOrNull()
      assertIs<CorruptPersistedValueException>(error, "got [$result]")
      assertTrue(
        error.message!!.contains("money_profiles.[income_band]"),
        "the error must name the corrupt column, got: [${error.message}]",
      )
      assertTrue(error.message!!.contains("(row ["), "the error must name the row, got: [${error.message}]")
    } finally {
      CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.execute("UPDATE money_profiles SET income_band = 'under_30k', version = version + 1 WHERE student_id = '${student.value}'")
        stmt.execute(
          "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_income_band_value_iff_answered_check " +
            "CHECK ((income_band IS NOT NULL) = (income_band_status = 'answered'))",
        )
      }
    }
  }

  @Test
  fun `an answered residency with a corrupt null stored state fails the read, never a silent withheld offer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Corrupt Residency U"))
    answerResidency(student, "CA")

    // Same shape as the band's twin above: the iff-answered CHECK makes this
    // state unreachable through any write path, so it is forced by dropping the
    // constraint and restored in the finally.
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_residency_value_iff_answered_check")
      stmt.execute(
        "UPDATE money_profiles SET residency_state = NULL, version = version + 1 WHERE student_id = '${student.value}'",
      )
    }
    try {
      // Unaudited, this row reads as answered-but-unusable: tuition_applicable
      // folds to unknown AND the residency offer is withheld, because the
      // status is not UNANSWERED -- the one state no coach reply can recover.
      val result = runBlocking { service.getForStudent(student) }
      val error = result.exceptionOrNull()
      assertIs<CorruptPersistedValueException>(error, "got [$result]")
      assertTrue(
        error.message!!.contains("money_profiles.[residency_state]"),
        "the error must name the corrupt column, got: [${error.message}]",
      )
      assertTrue(error.message!!.contains("(row ["), "the error must name the row, got: [${error.message}]")
    } finally {
      CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.execute("UPDATE money_profiles SET residency_state = 'CA', version = version + 1 WHERE student_id = '${student.value}'")
        stmt.execute(
          "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_residency_value_iff_answered_check " +
            "CHECK ((residency_state IS NOT NULL) = (residency_status = 'answered'))",
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
      seedCollege(
        "Sparse U",
        costOfAttendancePerYearUsd = null,
        netPricePerYearUsd = null,
        medianDebtAtCompletionUsd = null,
        tuitionAndFeesOutOfStatePerYearUsd = null,
      ),
    )

    val cost = profileOf(student).colleges.single()
    assertEquals(
      listOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
        CostField.NET_PRICE,
        CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
      ),
      cost.notReported,
    )
    assertNull(cost.netPrice.amount)
  }

  @Test
  fun `an answered band whose bracket the college does not report keeps the basis and lists net_price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("NoBracket U", netPricePerYearIncomeQ1Usd = null))
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

  // ---------------------------------------------------------------------------
  // The merit-aid read (RFC 148 D7) -- additive, batched, never gating
  // ---------------------------------------------------------------------------

  @Test
  fun `a college with no merit row degrades to exactly the cost answer it had before`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("No Merit U"))

    val cost = profileOf(student).colleges.single()
    assertNull(cost.meritAid, "a missing row is never a zero")
    // The Scorecard availability list is untouched by a second source's silence.
    assertTrue(CostField.entries.none { it.wireName == "merit_aid" })
    assertEquals(emptyList(), cost.notReported)
  }

  @Test
  fun `the merit read rides the same connection and answers every college at once`() {
    val student = createStudent()
    val first = seedCollege("Merit One").also { addToCollegeList(student, it) }
    val second = seedCollege("Merit Two").also { addToCollegeList(student, it) }
    seedCollege("Merit None").also { addToCollegeList(student, it) }
    CostsTestDb.seedMeritAid(first, noNeedMeritAverageUsd = 1000)
    CostsTestDb.seedMeritAid(second, noNeedMeritAverageUsd = 2000)

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(1000, byName.getValue("Merit One").meritAid?.averageNonNeedAid)
    assertEquals(2000, byName.getValue("Merit Two").meritAid?.averageNonNeedAid)
    assertNull(byName.getValue("Merit None").meritAid)
  }

  @Test
  fun `the merit read adds no query per college`() {
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Merit Batch $n").also {
          addToCollegeList(student, it)
          CostsTestDb.seedMeritAid(it, noNeedMeritAverageUsd = 1000 * n)
        }
      }

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
  fun `a cancelled read is rethrown, never reported as a read failure`() {
    val student = createStudent()
    seedCollege("Cancelled U").also { addToCollegeList(student, it) }

    // Same rule as the admissions read: a chat turn the caller abandoned is not
    // a database fault, and cancellation must keep propagating.
    val outcome =
      runBlocking {
        var thrown: Throwable? = null
        var completed: Result<CollegeCostProfile>? = null
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
  fun `the merit share is over all full-time freshmen, and is withheld without both counts`() {
    val student = createStudent()
    val shared = seedCollege("Share U").also { addToCollegeList(student, it) }
    val partial = seedCollege("Partial U").also { addToCollegeList(student, it) }
    CostsTestDb.seedMeritAid(
      shared,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )
    CostsTestDb.seedMeritAid(
      partial,
      firstTimeFullTimeFreshmenHeadcount = null,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(
      25.0,
      byName
        .getValue("Share U")
        .meritAid
        ?.shareOfAllFullTimeFreshmen
        ?.percent,
    )
    assertNull(
      byName
        .getValue("Partial U")
        .meritAid
        ?.shareOfAllFullTimeFreshmen
        ?.percent,
    )
    assertEquals(12500, byName.getValue("Partial U").meritAid?.averageNonNeedAid, "the average stands alone")
  }

  @Test
  fun `the latest merit cycle answers the cost read too`() {
    val student = createStudent()
    val college = seedCollege("Two Cycle U").also { addToCollegeList(student, it) }
    CostsTestDb.seedMeritAid(college, sourceYear = 2024, noNeedMeritAverageUsd = 1000)
    CostsTestDb.seedMeritAid(college, sourceYear = 2025, noNeedMeritAverageUsd = 2000)

    val merit = profileOf(student).colleges.single().meritAid
    assertEquals(2000, merit?.averageNonNeedAid)
    assertEquals("Two Cycle U's 2025-26 Common Data Set", merit?.source?.citedAs)
  }

  // ---------------------------------------------------------------------------
  // The living-arrangement breakdown (RFC 149)
  // ---------------------------------------------------------------------------

  /** One arrangement of a college's breakdown, or null when it was omitted. */
  private fun arrangementOf(
    cost: CollegeCost,
    arrangement: LivingArrangement,
  ): ArrangementCost? = cost.breakdown?.arrangements?.firstOrNull { it.arrangement == arrangement }

  private fun amountsOf(arrangement: ArrangementCost): Map<CostField, Int> = arrangement.lines.associate { it.field to it.amountUsd }

  @Test
  fun `all three arrangements are assembled, each totalling its own parts plus the applicable tuition`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Breakdown U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val breakdown = assertNotNull(cost.breakdown)
    assertEquals(
      listOf(LivingArrangement.ON_CAMPUS, LivingArrangement.OFF_CAMPUS, LivingArrangement.WITH_FAMILY),
      breakdown.arrangements.map { it.arrangement },
      "the arrangements ride in declaration order, which is the order the coach reads them",
    )

    val tuition = 12000
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS))
    assertEquals(
      mapOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD to tuition,
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD to CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD to CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD to CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      ),
      amountsOf(onCampus),
    )
    assertEquals(
      tuition +
        CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      onCampus.totalPerYearUsd,
    )

    val offCampus = assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertEquals(
      tuition +
        CostsTestDb.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      offCampus.totalPerYearUsd,
    )

    // The one sentence this whole slice exists to make sayable: living at home
    // costs the on-campus housing-and-food figure less, plus the difference in
    // everyday spending.
    val withFamily = assertNotNull(arrangementOf(cost, LivingArrangement.WITH_FAMILY))
    assertEquals(
      tuition + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      withFamily.totalPerYearUsd,
    )
    assertTrue(withFamily.totalPerYearUsd!! < onCampus.totalPerYearUsd!!)
  }

  @Test
  fun `with_family carries no housing and food line at all, never a zero`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home U"))
    answerResidency(student, "CA")

    val withFamily = assertNotNull(arrangementOf(profileOf(student).colleges.single(), LivingArrangement.WITH_FAMILY))
    val fields = withFamily.lines.map { it.field }
    assertTrue(
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD !in fields &&
        CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD !in fields,
      "the Scorecard publishes no ROOMBOARD_FAM, so this arrangement has one fewer part: [$fields]",
    )
    assertTrue(withFamily.lines.none { it.amountUsd == 0 }, "absence is never rendered as a zero")
  }

  @Test
  fun `an arrangement missing one part carries the parts it has and no total`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Partial U", otherExpensesOffCampusPerYearUsd = null))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    val offCampus = assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertNull(offCampus.totalPerYearUsd, "a partial sum is not a total")
    assertEquals(
      setOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
      ),
      amountsOf(offCampus).keys,
      "the reported parts still ride, they are true whatever is missing",
    )
    assertNotNull(
      arrangementOf(cost, LivingArrangement.ON_CAMPUS)?.totalPerYearUsd,
      "one arrangement's gap never costs another its total",
    )
    assertTrue(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD in cost.notReported)
  }

  @Test
  fun `an arrangement with no reported part at all is omitted entirely`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Off-Campus U",
        housingAndFoodOffCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        // Books is shared, so the arrangement is empty only once its own two
        // parts AND the shared one are gone.
        booksAndSuppliesPerYearUsd = null,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS), "an empty arrangement is absent, never an empty object")
    assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "the arrangements it can price still answer")
  }

  @Test
  fun `a college reporting no component at all carries no breakdown`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Silent U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNull(cost.breakdown)
    assertEquals(
      CostField.COMPONENTS,
      cost.notReported.filter { it in CostField.COMPONENTS },
      "every component it does not report is named, so the coach says so instead of estimating",
    )
  }

  @Test
  fun `unanswered residency at a public college drops the tuition line and every total`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Unknown Residency U", control = 1))

    val cost = profileOf(student).colleges.single()
    val breakdown = assertNotNull(cost.breakdown, "the components are true whoever is reading them")
    assertTrue(
      breakdown.arrangements.all { it.totalPerYearUsd == null },
      "a total that silently picked one residency would be a lie",
    )
    assertTrue(
      breakdown.arrangements.none { line -> line.lines.any { it.field.wireName.startsWith("tuition_and_fees") } },
      "and the tuition line it would have picked is absent, not guessed",
    )
  }

  @Test
  fun `out-of-state residency selects the out-of-state tuition line`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Away U", state = "NY", control = 1))
    answerResidency(student, "CA")

    val onCampus = assertNotNull(arrangementOf(profileOf(student).colleges.single(), LivingArrangement.ON_CAMPUS))
    assertEquals(
      30000,
      amountsOf(onCampus)[CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD],
      "the breakdown reuses the payload's own TuitionApplicable decision",
    )
    assertNull(amountsOf(onCampus)[CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD])
  }

  @Test
  fun `a private college is priced without a residency answer, because it has one price`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private Breakdown U", control = 2))

    val onCampus = assertNotNull(arrangementOf(profileOf(student).colleges.single(), LivingArrangement.ON_CAMPUS))
    assertEquals(12000, amountsOf(onCampus)[CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD])
    assertNotNull(onCampus.totalPerYearUsd, "there is no residency question here to leave unanswered")
  }

  @Test
  fun `an arrangement can never mix Scorecard vintages`() {
    // RFC 149 D-F rule 3, asserted on the type rather than on one payload: the
    // constructor refuses, so no future assembly site can quietly add COSTT4_A
    // or a net price into a published-price sum.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine = CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000),
          componentLines = listOf(CostLine(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, 40000)),
        )
      }
    assertTrue(error.message!!.contains("differing or unknown Scorecard vintages"), "got [${error.message}]")

    // ...and the two blended figures really are a different vintage from the
    // components, which is what makes the guard bite.
    assertEquals(ScorecardVintage.BLENDED_AVERAGE, CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.vintage)
    assertEquals(ScorecardVintage.BLENDED_AVERAGE, CostField.NET_PRICE.vintage)
    assertTrue(
      CostField.COMPONENTS.all { it.vintage == ScorecardVintage.PUBLISHED_PRICE },
      "the six components share one vintage, which is why they may be summed",
    )
  }

  @Test
  fun `an arrangement refuses an UNDATED figure, which can never be shown to share a year`() {
    // RFC 149 D-E: median debt and median earnings carry no vintage, because
    // this RFC dates neither. The require is what makes that safe rather than
    // merely quiet -- a null vintage is not a wildcard that matches everything,
    // it is a year nobody established, so it may not be summed with a dated one.
    assertNull(CostField.MEDIAN_DEBT_AT_COMPLETION_USD.vintage)
    assertNull(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.vintage)

    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine = null,
          componentLines =
            listOf(
              CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500),
              CostLine(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, 23000),
            ),
        )
      }
    assertTrue(error.message!!.contains("differing or unknown Scorecard vintages"), "got [${error.message}]")
  }

  @Test
  fun `an arrangement refuses a component that is not its own`() {
    // The total used to be gated by a line COUNT: any three published-price
    // lines satisfied ON_CAMPUS. An on-campus housing charge could therefore be
    // carried -- and summed -- under WITH_FAMILY, publishing an at-home total
    // containing a dorm charge. The check is on IDENTITY now.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine = CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000),
          componentLines =
            listOf(
              CostLine(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD, 15000),
              CostLine(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, 3000),
            ),
        )
      }
    assertTrue(error.message!!.contains("only its own components"), "got [${error.message}]")
  }

  @Test
  fun `an arrangement refuses the same component twice, however many lines that makes`() {
    // Three copies of the shared books allowance is three lines, which is
    // exactly ON_CAMPUS's component count -- and was therefore a complete
    // budget as far as the old size test could tell.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine = CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000),
          componentLines = List(3) { CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500) },
        )
      }
    assertTrue(error.message!!.contains("once each"), "got [${error.message}]")
  }

  @Test
  fun `the tuition slot refuses a figure that is not a published tuition figure`() {
    // The slot's type is CostLine, which admits all twelve fields. A component
    // there shares the components' vintage, so every other check passes and the
    // figure is summed a second time under tuition's name.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(
          arrangement = LivingArrangement.WITH_FAMILY,
          tuitionLine = CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500),
          componentLines =
            listOf(
              CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500),
              CostLine(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, 3000),
            ),
        )
      }
    assertTrue(error.message!!.contains("must be a published tuition figure"), "got [${error.message}]")
    assertEquals(
      setOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      ),
      CostField.TUITION_FIELDS,
      "the allowlist is the two published tuition figures, and nothing else",
    )
  }

  @Test
  fun `an arrangement with no line at all is refused, not silently totalled as nothing`() {
    // The zero case the old `vintages.size <= 1` tolerated: no line, no
    // vintage, every check satisfied, and a null total that reads like ordinary
    // partial reporting rather than like the empty value it is.
    val error =
      assertFailsWith<IllegalArgumentException> {
        ArrangementCost(arrangement = LivingArrangement.WITH_FAMILY, tuitionLine = null, componentLines = emptyList())
      }
    assertTrue(error.message!!.contains("absent arrangement, never an empty one"), "got [${error.message}]")
  }

  @Test
  fun `a complete arrangement carries exactly its own components, in render order, and totals them`() {
    // The positive case the refusals above bound: identity, not count, is what
    // admits a total.
    val arrangement =
      ArrangementCost(
        arrangement = LivingArrangement.WITH_FAMILY,
        tuitionLine = CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000),
        componentLines =
          listOf(
            CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500),
            CostLine(CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD, 3000),
          ),
      )
    assertEquals(LivingArrangement.WITH_FAMILY.components, arrangement.componentLines.map { it.field })
    assertEquals(16500, arrangement.totalPerYearUsd)

    // A partial one is still legal and still carries NO total.
    val partial =
      ArrangementCost(
        arrangement = LivingArrangement.WITH_FAMILY,
        tuitionLine = CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000),
        componentLines = listOf(CostLine(CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD, 1500)),
      )
    assertNull(partial.totalPerYearUsd, "a partial sum is not a total")
  }

  @Test
  fun `the mixed-vintage refusal carries the offending lines, not just a set of vintages`() {
    // This throw is caught into Result.failure by getForStudent, so its message
    // is the whole diagnostic an operator ever sees: it must name WHICH figure
    // came from another reporting year.
    val error =
      assertFailsWith<MixedVintageArrangementException> {
        ArrangementCost(
          arrangement = LivingArrangement.ON_CAMPUS,
          tuitionLine = CostLine(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, 12000),
          componentLines = listOf(CostLine(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, 40000)),
        )
      }
    assertEquals(LivingArrangement.ON_CAMPUS, error.arrangement)
    assertEquals(
      setOf(ScorecardVintage.PUBLISHED_PRICE, ScorecardVintage.BLENDED_AVERAGE),
      error.vintages,
      "the offending lines are carried, and the vintages derived from them",
    )
    assertTrue(
      error.message!!.contains(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName) &&
        error.message!!.contains("40000"),
      "the field and its amount are in the message: [${error.message}]",
    )
  }

  @Test
  fun `no arrangement total ever equals the sticker cost by construction`() {
    // RFC 149 D-F rule 1: COSTT4_A is a weighted blend across arrangements and a
    // year older, so it is never the on-campus sum -- it keeps its own key and
    // nothing substitutes one for the other.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Sticker U"))
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(40000, cost.stickerCostOfAttendancePerYearUsd)
    val breakdown = assertNotNull(cost.breakdown)

    // The STRUCTURAL claim, which is the rule itself: COSTT4_A is a blend ACROSS
    // arrangements, so it is never a line inside one. Asserted over CostField
    // membership rather than over values, because a comparison of seeded numbers
    // is a property of CostsTestDb and would still pass if the blend were summed
    // in under a different amount.
    assertTrue(
      breakdown.arrangements.none { arrangement ->
        arrangement.lines.any { it.field == CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD }
      },
      "COSTT4_A is a blend across arrangements, so it is never a line in one: [${breakdown.arrangements}]",
    )
    assertTrue(
      breakdown.arrangements.all { arrangement ->
        arrangement.lines.all { it.field.vintage == ScorecardVintage.PUBLISHED_PRICE }
      },
      "an arrangement is one published price list for one year: [${breakdown.arrangements}]",
    )

    // ...and, on these fixture numbers, it does not stand in for a total either.
    val totals = breakdown.arrangements.mapNotNull { it.totalPerYearUsd }
    assertTrue(totals.isNotEmpty())
    assertTrue(
      totals.none { it == cost.stickerCostOfAttendancePerYearUsd },
      "the fixture must keep the two figures distinguishable: [$totals]",
    )
  }

  // ---------------------------------------------------------------------------
  // The no-dorms case (RFC 149 D-B): IPEDS offers_housing, never inferred
  // ---------------------------------------------------------------------------

  @Test
  fun `a school with no residence halls drops on_campus and carries the flag instead`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        // The ORDINARY no-dorms shape: IPEDS says no residence halls and the
        // Scorecard publishes no on-campus figure either, so there is nothing
        // to show and nothing to contradict.
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(false, cost.offersOnCampusHousing)
    assertNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "there is no cheap on-campus option; there is none")
    assertNotNull(arrangementOf(cost, LivingArrangement.OFF_CAMPUS))
    assertNotNull(arrangementOf(cost, LivingArrangement.WITH_FAMILY))
  }

  @Test
  fun `published on-campus figures beat the no-dorms flag, and both facts still ride`() {
    // RFC 149 D-B: the two sources can disagree. Suppressing a figure the school
    // itself published would be the worse failure of the two, so the arrangement
    // is rendered from what was published and the flag is reported beside it.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege("Contradiction U", ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertEquals(false, cost.offersOnCampusHousing, "the flag is still reported; we do not pick one source and hide the other")
    val onCampus = assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "the school published these figures itself")
    assertEquals(
      CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      amountsOf(onCampus)[CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD],
      "the published figure is rendered, not dropped",
    )
    assertNotNull(onCampus.totalPerYearUsd, "every part is present, so the arrangement totals like any other")
  }

  @Test
  fun `a contradicting school still names the on-campus part it genuinely does not report`() {
    // The suppression follows the arrangement: once the on-campus arrangement is
    // rendered, a part missing FROM it is ordinary Scorecard silence and must be
    // named, or the coach would be told nothing about a gap it can see.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Half Contradiction U",
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val cost = profileOf(student).colleges.single()
    assertNotNull(arrangementOf(cost, LivingArrangement.ON_CAMPUS), "one published on-campus figure is enough to price it")
    assertTrue(
      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD in cost.notReported,
      "the arrangement is rendered, so its missing part is a silence and is named: [${cost.notReported}]",
    )
    assertNull(
      arrangementOf(cost, LivingArrangement.ON_CAMPUS)?.totalPerYearUsd,
      "and a partial arrangement still carries no total",
    )
  }

  @Test
  fun `the no-dorms case is not a data_availability silence`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Commuter Only U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )

    val cost = profileOf(student).colleges.single()
    assertTrue(
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD !in cost.notReported &&
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD !in cost.notReported,
      "a school with no dorms ANSWERED; it did not stay silent: [${cost.notReported}]",
    )
    assertTrue(
      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD !in cost.notReported,
      "books and supplies is shared by every arrangement and this college reports it",
    )
  }

  @Test
  fun `a school that offers housing keeps on_campus, and no IPEDS fact changes nothing`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Dorms U", ipedsHousing = CostsTestDb.IpedsHousing.OFFERS))
    addToCollegeList(student, seedCollege("No Row U", ipedsHousing = CostsTestDb.IpedsHousing.NO_ROW))
    addToCollegeList(student, seedCollege("Quiet Row U", ipedsHousing = CostsTestDb.IpedsHousing.UNREPORTED))
    answerResidency(student, "CA")

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertEquals(true, byName.getValue("Dorms U").offersOnCampusHousing)
    assertNotNull(arrangementOf(byName.getValue("Dorms U"), LivingArrangement.ON_CAMPUS))

    // Both silences fold to the same "not reported", and neither drops the
    // arrangement: absence of the IPEDS fact is not evidence of no dorms.
    for (name in listOf("No Row U", "Quiet Row U")) {
      assertNull(byName.getValue(name).offersOnCampusHousing, "[$name] must read as not reported")
      assertNotNull(arrangementOf(byName.getValue(name), LivingArrangement.ON_CAMPUS), "[$name]")
      assertTrue(
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD !in byName.getValue(name).notReported,
        "[$name] reports the figure, so it is not a silence",
      )
    }
  }

  @Test
  fun `the housing read adds no query per college`() {
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Housing Batch $n", ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER)
          .also { addToCollegeList(student, it) }
      }

    val one = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    assertEquals(1, service.readInSession(one, student, ids.take(1)).colleges.size)
    assertEquals(5, service.readInSession(five, student, ids).colleges.size)

    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "the IPEDS housing read must cost the same statements for five colleges as for one, " +
        "never one per college: one=[${one.prepared}] five=[${five.prepared}]",
    )
    assertEquals(
      5,
      service.readInSession(five, student, ids).colleges.count { it.offersOnCampusHousing == false },
      "and it must actually answer every one of them",
    )
  }
}
