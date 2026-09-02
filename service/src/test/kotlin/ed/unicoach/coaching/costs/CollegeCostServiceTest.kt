package ed.unicoach.coaching.costs

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.costs.CostsTestDb.addToCollegeList
import ed.unicoach.coaching.costs.CostsTestDb.answerBand
import ed.unicoach.coaching.costs.CostsTestDb.answerLivingPlan
import ed.unicoach.coaching.costs.CostsTestDb.answerResidency
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.costs.CostsTestDb.declineBand
import ed.unicoach.coaching.costs.CostsTestDb.declineLivingPlan
import ed.unicoach.coaching.costs.CostsTestDb.declineResidency
import ed.unicoach.coaching.costs.CostsTestDb.seedCollege
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
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
import kotlin.test.assertFalse
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
      listOf(PrecisionOffer.INCOME_BAND, PrecisionOffer.LIVING_PLAN),
      profile.precisionOffersFor(cost),
      "an unanswered band must carry the in-answer invitation; answered residency offers nothing more, " +
        "and the unanswered living plan is offered last (RFC 152 D4)",
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
      "all-unanswered at a public college offers the two upgrades that rest on nothing else, residency " +
        "first. The living plan is NOT among them: with residency unanswered this school's tuition line " +
        "is null, so no arrangement carries a total and there is nothing to choose between (D4)",
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

  // ---------------------------------------------------------------------------
  // The comparison contract (RFC 151): per call, and only from two colleges up
  // ---------------------------------------------------------------------------

  /** One school's residency entry inside the comparison basis, by name. */
  private fun residencyEntryOf(
    basis: ComparisonBasis,
    name: String,
  ): CollegeResidencyBasis = basis.residency.byCollege.single { it.name == name }

  @Test
  fun `a one-college answer carries no comparison basis, and two colleges do`() {
    val student = createStudent()
    val first = seedCollege("Alone U").also { addToCollegeList(student, it) }
    val second = seedCollege("Together U").also { addToCollegeList(student, it) }
    answerResidency(student, "CA")

    assertNull(
      profileOf(student, listOf(first)).comparisonBasis,
      "one school is already fully labelled by its own keys; a comparison object would invite a narrated comparison",
    )
    val basis = assertNotNull(profileOf(student, listOf(first, second)).comparisonBasis)
    assertEquals(PopulationBasis.CODE, basis.population.code)
    assertEquals(AidBasis.CODE, basis.aid.code)
    assertTrue(basis.population.statement.isNotEmpty() && basis.aid.statement.isNotEmpty())
  }

  @Test
  fun `an empty list carries no comparison basis either`() {
    val student = createStudent()
    assertNull(profileOf(student).comparisonBasis, "no colleges, nothing held constant")
  }

  @Test
  fun `the residency entry states which published figure applies at each school`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home State U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Away State U", state = "NY", control = 1))
    addToCollegeList(student, seedCollege("Private U", state = "NY", control = 2))
    answerResidency(student, "CA")

    val basis = assertNotNull(profileOf(student).comparisonBasis)
    assertEquals(ComparedResidency.Answered("CA"), basis.residency.answer)
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.IN_STATE),
      residencyEntryOf(basis, "Home State U").tuition,
    )
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.OUT_OF_STATE),
      residencyEntryOf(basis, "Away State U").tuition,
    )
    assertEquals(
      ComparedTuition.SinglePublishedPrice,
      residencyEntryOf(basis, "Private U").tuition,
      "a private school has one published price, so residency is not a distinction it can carry",
    )
    // Every entry says its code aloud, and says it about the school it names.
    basis.residency.byCollege.forEach { entry ->
      assertTrue(entry.statement.contains(entry.name), "the sentence must name its own school: [$entry]")
    }
  }

  @Test
  fun `an unanswered or declined residency leaves every public entry unknown, and says so`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Quiet Public U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Quiet Private U", state = "CA", control = 2))

    val unanswered = assertNotNull(profileOf(student).comparisonBasis)
    assertEquals(
      ComparedResidency.Unanswered,
      unanswered.residency.answer,
      "no state is reachable on an unanswered residency: the case carries none",
    )
    assertEquals(AnswerStatus.UNANSWERED, unanswered.residency.answer.status, "and it echoes the money-profile status")
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.UNKNOWN),
      residencyEntryOf(unanswered, "Quiet Public U").tuition,
    )
    assertTrue(
      unanswered.residency.statement.contains("not on file"),
      "the missing answer is stated, never left for the coach to notice: [${unanswered.residency.statement}]",
    )

    declineResidency(student)
    val declined = assertNotNull(profileOf(student).comparisonBasis)
    assertEquals(ComparedResidency.Declined, declined.residency.answer)
    assertEquals(
      ComparedTuition.Public(TuitionApplicable.UNKNOWN),
      residencyEntryOf(declined, "Quiet Public U").tuition,
    )
  }

  @Test
  fun `an all-private table gets no caveat about public tuition`() {
    // The basis line must be true of THIS table. A residency caveat here would
    // warn about a distinction no school in the comparison makes.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private One U", state = "CA", control = 2))
    addToCollegeList(student, seedCollege("Private Two U", state = "NY", control = 3))
    answerResidency(student, "CA")

    val statement = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(
      statement.contains("each publishes one price for everyone"),
      "the single-published-price case is stated as itself: [$statement]",
    )
    assertFalse(statement.contains("public"), "no school here is public, so nothing is said about public tuition: [$statement]")
    assertFalse(statement.contains("CA"), "the answered state selects nothing here, so it is not claimed to: [$statement]")
  }

  @Test
  fun `an all-public table states the residency it holds constant`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Public One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Public Two U", state = "NY", control = 1))
    answerResidency(student, "CA")

    val answered = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(answered.contains("Every school here is public"), "[$answered]")
    assertTrue(answered.contains("a family living in CA would be charged"), "[$answered]")

    declineResidency(student)
    val declined = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(
      declined.contains("not on file") && declined.contains("neither figure can be shown as theirs"),
      "a declined residency leaves both published figures unclaimed, and says so: [$declined]",
    )
  }

  @Test
  fun `a mixed table says which schools the residency is about, and what the others do`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Mixed Public U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Mixed Private U", state = "CA", control = 2))
    answerResidency(student, "CA")

    val statement = assertNotNull(profileOf(student).comparisonBasis).residency.statement
    assertTrue(
      statement.contains("The public schools here - Mixed Public U -"),
      "the caveat names the schools it is about, never every column: [$statement]",
    )
    assertTrue(statement.contains("a family living in CA would be charged"), "[$statement]")
    assertTrue(
      statement.contains("the other schools here publish one price for everyone"),
      "the private school is not claimed to hold a residency basis: [$statement]",
    )
    assertFalse(
      statement.contains("Mixed Private U"),
      "the single-price school needs no residency caveat of its own; its by_college line says it: [$statement]",
    )
  }

  @Test
  fun `comparable is the intersection, and the school that lacks an arrangement is named with its reason`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Full U", state = "CA", control = 1))
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        state = "CA",
        control = 1,
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val arrangement = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(
      listOf(LivingArrangement.OFF_CAMPUS, LivingArrangement.WITH_FAMILY),
      arrangement.comparable,
      "on_campus is priced at one school only, so a column cannot hold it constant",
    )
    val gap = arrangement.incompleteByCollege.single()
    assertEquals("No Dorms U", gap.name)
    assertEquals(listOf(LivingArrangement.ON_CAMPUS), gap.missing)
    assertEquals(
      ArrangementGap.NO_ON_CAMPUS_HOUSING,
      gap.reason,
      "a school with no residence halls ANSWERED; it did not stay silent",
    )
    assertTrue(
      arrangement.statement.contains("renting off campus") && arrangement.statement.contains("living at home"),
      "the comparable arrangements are said in the words a student says: [${arrangement.statement}]",
    )
  }

  @Test
  fun `an unreported arrangement is a silence, never the no-dorms answer`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Reports Everything U", state = "CA", control = 1))
    addToCollegeList(
      student,
      seedCollege(
        // No IPEDS row at all: absence of the fact is not evidence of no dorms,
        // so this gap is ordinary Scorecard silence. Every on-campus part is
        // null INCLUDING the shared books allowance -- one reported part is
        // enough to price an arrangement (RFC 149), so a school that keeps the
        // books figure still has an on-campus arrangement to compare.
        "Quiet U",
        state = "CA",
        control = 1,
        housingAndFoodOnCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
      ),
    )
    answerResidency(student, "CA")

    val byName = profileOf(student).colleges.associateBy { it.name }
    assertNull(byName.getValue("Quiet U").offersOnCampusHousing, "no IPEDS row: we have no housing fact for it")

    val arrangement = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    val gap = arrangement.incompleteByCollege.single()
    assertEquals("Quiet U", gap.name)
    assertEquals(
      ArrangementGap.NOT_REPORTED,
      gap.reason,
      "an unknown housing fact is not the no-dorms answer; what IS true is that this school published no " +
        "on-campus figure, which is what not_reported says",
    )
  }

  @Test
  fun `every school priced the same way leaves the incomplete list empty`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Same One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Same Two U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val arrangement = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(LivingArrangement.entries, arrangement.comparable)
    assertEquals(emptyList(), arrangement.incompleteByCollege, "nothing is missing, so nobody is named")
  }

  @Test
  fun `the academic years name only the vintages the call actually carries`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Dated One U", state = "CA", control = 1))
    addToCollegeList(student, seedCollege("Dated Two U", state = "CA", control = 1))
    answerResidency(student, "CA")

    val years = assertNotNull(profileOf(student).comparisonBasis).academicYears
    assertEquals(ScorecardVintage.entries, years.map { it.vintage }, "both vintages are carried by these fixtures")
    val published = years.single { it.vintage == ScorecardVintage.PUBLISHED_PRICE }
    assertEquals(ScorecardVintage.PUBLISHED_PRICE.label, published.academicYear)
    assertTrue(
      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD in published.figures,
      "the year names the figures it dates: [${published.figures}]",
    )
    assertTrue(
      published.figures.all { it.vintage == ScorecardVintage.PUBLISHED_PRICE },
      "a year may never date a figure from the other one: [${published.figures}]",
    )
    assertTrue(published.statement.contains(published.academicYear), "the year is spoken, never left as a bare code")
  }

  @Test
  fun `a call whose colleges report only undated figures carries no academic year at all`() {
    val student = createStudent()
    val sparse =
      (1..2).map { n ->
        seedCollege(
          "Undated $n U",
          costOfAttendancePerYearUsd = null,
          netPricePerYearUsd = null,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = null,
          tuitionAndFeesOutOfStatePerYearUsd = null,
          housingAndFoodOnCampusPerYearUsd = null,
          housingAndFoodOffCampusPerYearUsd = null,
          booksAndSuppliesPerYearUsd = null,
          otherExpensesOnCampusPerYearUsd = null,
          otherExpensesOffCampusPerYearUsd = null,
          otherExpensesWithFamilyPerYearUsd = null,
        ).also { addToCollegeList(student, it) }
      }

    val basis = assertNotNull(profileOf(student, sparse).comparisonBasis)
    assertEquals(emptyList(), basis.academicYears, "median debt and median earnings are dated by neither vintage")
    assertEquals(emptyList(), basis.livingArrangement.comparable, "nothing is priced, so nothing is held constant")
    assertTrue(
      basis.livingArrangement.statement.contains("No one way of living is priced at every school here"),
      "the empty intersection is said in words: [${basis.livingArrangement.statement}]",
    )
  }

  @Test
  fun `the comparison basis adds no statement to the read`() {
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Basis Batch $n", state = "CA", control = 1).also { addToCollegeList(student, it) }
      }
    answerResidency(student, "CA")

    val one = CoachingTestDb.CountingSession()
    val two = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    assertNull(service.readInSession(one, student, ids.take(1)).comparisonBasis, "one college, no comparison")
    assertNotNull(service.readInSession(two, student, ids.take(2)).comparisonBasis)
    assertEquals(5, service.readInSession(five, student, ids).colleges.size)

    // RFC 151 D-C: the basis is assembled from facts the read already holds, so
    // the statement count is RFC 149's -- the same for a comparison as for the
    // single-college answer that carries none.
    assertEquals(
      one.prepared.size,
      two.prepared.size,
      "assembling the comparison basis must cost no statement: one=[${one.prepared}] two=[${two.prepared}]",
    )
    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "and five colleges must still cost what one does: one=[${one.prepared}] five=[${five.prepared}]",
    )
  }

  // ---------------------------------------------------------------------------
  // The resolved living plan (RFC 152)
  // ---------------------------------------------------------------------------

  @Test
  fun `no plan and no override leaves the answer exactly as it was - all three arrangements, nothing chosen`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Unanswered Plan U"))

    val cost = profileOf(student).colleges.single()
    assertIs<ChosenLivingPlan.NotChosen>(cost.chosen, "nothing said means nothing led with")
    assertEquals(
      LivingArrangement.entries,
      cost.breakdown!!.arrangements.map { it.arrangement },
      "and the breakdown is untouched: RFC 152 D3, today's behaviour exactly",
    )
  }

  @Test
  fun `a declined plan chooses nothing and is never re-offered`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Declined Plan U"))
    declineLivingPlan(student)

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    assertIs<ComparedLivingPlan.Declined>(profile.moneyProfile.living)
    assertIs<ChosenLivingPlan.NotChosen>(cost.chosen, "a decline leads with nothing, forever")
    assertEquals(
      LivingArrangement.entries,
      cost.breakdown!!.arrangements.map { it.arrangement },
      "and still shows every way of living the school publishes",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN !in profile.precisionOffersFor(cost),
      "the coach must never be cued to reopen a declined plan",
    )
  }

  @Test
  fun `the usual plan is assumed for a school with no override, and says it was assumed`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Default Plan U"))
    // A public school prices nothing until the residency question is answered,
    // so the plan is answered too: this test is about the resolution, not about
    // a tuition line that is still open.
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val cost = profileOf(student).colleges.single()
    val chosen = assertIs<ChosenLivingPlan.Priced>(cost.chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, chosen.plan)
    assertEquals(
      LivingPlanSource.PROFILE_DEFAULT,
      chosen.source,
      "with_family is never inferred by us: an assumed plan must stay nameable as an assumption",
    )
  }

  @Test
  fun `a school's own plan beats the usual plan, and a cleared override falls back to it`() {
    val student = createStudent()
    val college = seedCollege("Override U")
    addToCollegeList(student, college)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)
    val overridden = assertIs<ChosenLivingPlan.Priced>(profileOf(student).colleges.single().chosen)
    assertEquals(LivingArrangement.ON_CAMPUS, overridden.plan, "override beats default")
    assertEquals(
      LivingPlanSource.PER_COLLEGE,
      overridden.source,
      "and says so, because 'you told us this for this school' is a different sentence from an assumption",
    )

    CostsTestDb.setEntryLivingPlan(student, college, null)
    val cleared = assertIs<ChosenLivingPlan.Priced>(profileOf(student).colleges.single().chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, cleared.plan, "NULL is 'no override', so the usual plan returns")
    assertEquals(LivingPlanSource.PROFILE_DEFAULT, cleared.source)
  }

  @Test
  fun `an override alone resolves a school even with no usual plan on file`() {
    // Feasibility is a fact about the student-college pair, so a family can
    // state it about one school without ever stating a global preference.
    val student = createStudent()
    val overridden = seedCollege("Commutable U")
    val plain = seedCollege("Far Away U")
    addToCollegeList(student, overridden)
    addToCollegeList(student, plain)
    answerResidency(student, "CA")
    CostsTestDb.setEntryLivingPlan(student, overridden, LivingArrangement.WITH_FAMILY)

    val byName = profileOf(student).colleges.associateBy { it.name }
    val chosen = assertIs<ChosenLivingPlan.Priced>(byName.getValue("Commutable U").chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, chosen.plan)
    assertEquals(LivingPlanSource.PER_COLLEGE, chosen.source)
    assertIs<ChosenLivingPlan.NotChosen>(
      byName.getValue("Far Away U").chosen,
      "a school the family said nothing about stays unchosen; an override is never a global answer",
    )
  }

  @Test
  fun `a school with no residence halls says so rather than substituting another arrangement`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)

    val cost = profileOf(student).colleges.single()
    val notPriced = assertIs<ChosenLivingPlan.NotPricedHere>(cost.chosen)
    assertEquals(LivingArrangement.ON_CAMPUS, notPriced.plan)
    assertEquals(
      ArrangementGap.NO_ON_CAMPUS_HOUSING,
      notPriced.reason,
      "the school ANSWERED: it has no residence halls, which is not the same as silence",
    )
    assertFalse(
      cost.breakdown!!.arrangements.any { it.arrangement == LivingArrangement.ON_CAMPUS },
      "and no on-campus arrangement is invented to satisfy the plan",
    )
  }

  @Test
  fun `a school showing the chosen plan with no total says so, and is never blamed for our gap`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Silent Off Campus U", housingAndFoodOffCampusPerYearUsd = null))
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val cost = profileOf(student).colleges.single()
    // Three outcomes, not two. The arrangement still exists -- its other parts
    // are published -- but it carries no total, so the family gets no number to
    // lead with. That is NoTotalHere: stated, never a Priced with a silent
    // blank where its number should be, and never NotPricedHere, whose
    // ArrangementGap vocabulary would blame the school's price list for a
    // missing PART (RFC 149 D-B). A missing part is a labelled blank; a missing
    // ARRANGEMENT is a stated reason.
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(LivingArrangement.OFF_CAMPUS, noTotal.plan)
    assertNull(noTotal.cost.totalPerYearUsd, "a partial arrangement carries no total, and never a partial sum")
    assertEquals(
      NoTotalReason.PART_NOT_PUBLISHED,
      noTotal.reason,
      "and the cause is named: this one IS the school's silence about a part it does not publish",
    )
    assertTrue(
      cost.breakdown!!.arrangements.any { it.arrangement == LivingArrangement.OFF_CAMPUS },
      "and the way of living stays in the payload, each published part still labelled (D2)",
    )
  }

  @Test
  fun `an unanswered residency leaves a public school with no total, and the school is not blamed for it`() {
    // The regression this case exists to prevent. A public school with the
    // residency question still open has no tuition line, so no arrangement has
    // a total -- but the school published its prices in full. Reading that as
    // "this school publishes no price for that way of living" states OUR gap as
    // a fact about the school's price list, which is the one thing the
    // ArrangementGap vocabulary must never be used to say.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Public No Residency U"))
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val cost = profileOf(student).colleges.single()
    val noTotal = assertIs<ChosenLivingPlan.NoTotalHere>(cost.chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, noTotal.plan)
    assertEquals(
      LivingPlanSource.PROFILE_DEFAULT,
      noTotal.source,
      "the plan still resolved, and still says where it came from: only the number is missing",
    )
    assertEquals(
      NoTotalReason.AWAITING_RESIDENCY_ANSWER,
      noTotal.reason,
      "and the missing total is named as OUR open question, never as this school's silence",
    )
  }

  @Test
  fun `a school priced for nothing at all reports the chosen plan as not reported`() {
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "No Components U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val cost = profileOf(student).colleges.single()
    assertNull(cost.breakdown, "a school that reports no component has no breakdown at all")
    val notPriced = assertIs<ChosenLivingPlan.NotPricedHere>(cost.chosen)
    assertEquals(
      ArrangementGap.NOT_REPORTED,
      notPriced.reason,
      "an absent IPEDS row is a gap in OUR data, never evidence a school has no residence halls",
    )
  }

  @Test
  fun `a with_family default and an on_campus override on the far school price both correctly in one comparison`() {
    // Ian's own case (brief 0003 D20): commutable in-state school, far
    // out-of-state one. One family, two plans, and both totals must be the
    // school's own -- the failure this override exists to prevent is a single
    // arrangement held across a table it is not true of.
    val student = createStudent()
    val nearby = seedCollege("Nearby State U", state = "CA", control = 1)
    val faraway = seedCollege("Faraway State U", state = "NY", control = 1)
    addToCollegeList(student, nearby)
    addToCollegeList(student, faraway)
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, faraway, LivingArrangement.ON_CAMPUS)

    val profile = profileOf(student)
    val byName = profile.colleges.associateBy { it.name }

    val near = assertIs<ChosenLivingPlan.Priced>(byName.getValue("Nearby State U").chosen)
    assertEquals(LivingArrangement.WITH_FAMILY, near.plan)
    assertEquals(LivingPlanSource.PROFILE_DEFAULT, near.source, "the usual plan, assumed for the commutable school")
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      near.cost.totalPerYearUsd,
      "in-state tuition and fees plus the at-home allowances, and NO housing and food line: that is data",
    )
    assertFalse(
      near.cost.lines.any { it.field == CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD },
      "living at home carries no housing and food line, and never a zero one",
    )

    val far = assertIs<ChosenLivingPlan.Priced>(byName.getValue("Faraway State U").chosen)
    assertEquals(LivingArrangement.ON_CAMPUS, far.plan)
    assertEquals(LivingPlanSource.PER_COLLEGE, far.source, "this school's own plan, stated by the family")
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD + CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      far.cost.totalPerYearUsd,
      "out-of-state tuition and fees plus the on-campus allowances - this school's own basis, not the other's",
    )

    // Neither answer narrowed: all three arrangements are still true facts on
    // both schools, so "what if he lived at home at the far school?" is still
    // answerable from the same result (D2).
    profile.colleges.forEach { cost ->
      assertEquals(
        LivingArrangement.entries,
        cost.breakdown!!.arrangements.map { it.arrangement },
        "a resolved plan decides what is LED with, never what exists: [${cost.name}]",
      )
    }
    val arrangement = assertNotNull(profile.comparisonBasis).livingArrangement
    assertEquals(
      LivingArrangement.entries,
      arrangement.comparable,
      "and ArrangementBasis.comparable is not narrowed by a resolved plan either",
    )
  }

  @Test
  fun `an answered plan with no stored value is refused, never relabelled as never asked`() {
    // The third field joins requireIntactAnswers. Reading a corrupt row as
    // "unanswered" would have the coach ASK a family a question they already
    // answered - the one thing the tri-state exists to prevent.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Corrupt Plan U"))
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)
    CoachingTestDb.sqlSession
      .prepareStatement(
        "ALTER TABLE money_profiles DROP CONSTRAINT money_profiles_living_plan_value_iff_answered_check",
      ).use { it.execute() }
    CoachingTestDb.sqlSession
      .prepareStatement("UPDATE money_profiles SET living_plan = NULL, version = version + 1 WHERE student_id = ?")
      .use {
        it.setObject(1, student.value)
        it.executeUpdate()
      }

    val failure = runCatching { profileOf(student) }.exceptionOrNull()

    // Restored before the assertions: the suite shares one database, and a
    // dropped constraint left behind would weaken whichever test runs next
    // rather than failing this one.
    CoachingTestDb.sqlSession
      .prepareStatement(
        "ALTER TABLE money_profiles ADD CONSTRAINT money_profiles_living_plan_value_iff_answered_check " +
          "CHECK ((living_plan IS NOT NULL) = (living_plan_status = 'answered')) NOT VALID",
      ).use { it.execute() }

    assertIs<CorruptPersistedValueException>(failure, "got $failure")
    assertTrue(
      failure.message!!.contains("money_profiles.[living_plan]"),
      "the failure must name the corrupt column: [${failure.message}]",
    )
  }

  @Test
  fun `the living-plan offer rests only on a school with something to choose between`() {
    val student = createStudent()
    // Priced one way only: the school publishes just its at-home allowance, so
    // no on-campus or off-campus arrangement exists at all and the question
    // would buy this family nothing here. The SHARED books allowance is nulled
    // too, deliberately -- it belongs to every arrangement, so leaving it would
    // give this school three arrangements made of one line each.
    addToCollegeList(
      student,
      seedCollege(
        "One Way U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
      ),
    )
    addToCollegeList(student, seedCollege("Three Ways U"))
    // Residency answered, because the gate counts PRICED arrangements and a
    // public school with no residency on file totals none of them.
    answerResidency(student, "CA")

    val profile = profileOf(student)
    val byName = profile.colleges.associateBy { it.name }
    assertEquals(
      1,
      byName
        .getValue("One Way U")
        .breakdown!!
        .arrangements.size,
      "the fixture must actually price this school one way, or the test asserts nothing",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN !in profile.precisionOffersFor(byName.getValue("One Way U")),
      "an offer must never rest on a school with nothing to choose between",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN in profile.precisionOffersFor(byName.getValue("Three Ways U")),
      "and must be on offer where the plan actually moves the picture",
    )
  }

  @Test
  fun `arrangements present but not priced are nothing to choose between, so no living-plan offer`() {
    // The case that distinguishes "two arrangements" from "two PRICED
    // arrangements" (D4). This school publishes every component, so all three
    // arrangements exist -- but it publishes no tuition figure at all, so not
    // one of them carries a total. Asking the family where they plan to live
    // would move no number they can see, which is the definition of an offer
    // resting on nothing.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Priceless U",
        control = 2,
        tuitionAndFeesInStatePerYearUsd = null,
        tuitionAndFeesOutOfStatePerYearUsd = null,
      ),
    )

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val arrangements = assertNotNull(cost.breakdown).arrangements
    assertEquals(
      LivingArrangement.entries.size,
      arrangements.size,
      "the fixture must give this school every arrangement, or it tests the size gate instead",
    )
    assertEquals(
      0,
      arrangements.count { it.totalPerYearUsd != null },
      "and none of them may carry a total, which is the whole distinction under test",
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN !in profile.precisionOffersFor(cost),
      "three total-less arrangements are nothing to choose between: the old size gate would have offered here",
    )
  }

  @Test
  fun `resolving the living plan adds no query per college`() {
    // The override is read from the list entry the selection ALREADY holds, and
    // the default from the money profile read once -- so the resolution costs
    // zero extra statements, whatever the list's size.
    val student = createStudent()
    val ids =
      (1..5).map { n ->
        seedCollege("Plan Batch $n").also { addToCollegeList(student, it) }
      }
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, ids.last(), LivingArrangement.ON_CAMPUS)

    val one = CoachingTestDb.CountingSession()
    val five = CoachingTestDb.CountingSession()
    service.readInSession(one, student, ids.take(1))
    val all = service.readInSession(five, student, ids)
    assertEquals(
      one.prepared.size,
      five.prepared.size,
      "one=[${one.prepared}] five=[${five.prepared}]",
    )
    assertEquals(
      LivingArrangement.ON_CAMPUS,
      assertIs<ChosenLivingPlan.Priced>(all.colleges.last().chosen).plan,
      "and the override still resolved inside that same statement budget",
    )
  }

  @Test
  fun `one plan priced at every school holds the comparison column and names the way of living`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Home One U"))
    addToCollegeList(student, seedCollege("Home Two U"))
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.ONE_PLAN_EVERY_SCHOOL, basis.scope)
    assertEquals(ComparedLivingPlan.Answered(LivingArrangement.WITH_FAMILY), basis.answer)
    assertTrue(
      basis.statement.contains(LivingArrangement.WITH_FAMILY.label) &&
        basis.statement.contains("the column holds that one way of living"),
      "the plan is named in the student's words and the column is stated: [${basis.statement}]",
    )
  }

  @Test
  fun `different plans across the compared schools name the plan used for each`() {
    val student = createStudent()
    val near = seedCollege("Nearby U")
    val far = seedCollege("Faraway U")
    addToCollegeList(student, near)
    addToCollegeList(student, far)
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, far, LivingArrangement.ON_CAMPUS)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.PLAN_VARIES_BY_SCHOOL, basis.scope)
    assertTrue(
      basis.statement.contains("not all on the same plan") && basis.statement.contains("the family's actual situation"),
      "the column holds the situation rather than one arrangement: [${basis.statement}]",
    )
    assertTrue(
      basis.statement.contains("Nearby U: ${LivingArrangement.WITH_FAMILY.label}") &&
        basis.statement.contains("Faraway U: ${LivingArrangement.ON_CAMPUS.label}"),
      "and names the plan used for EACH school, before the numbers: [${basis.statement}]",
    )
    assertEquals(
      listOf(LivingArrangement.WITH_FAMILY, LivingArrangement.ON_CAMPUS),
      basis.byCollege.map { it.plan },
      "the per-school resolution is read off the per-college answer, never re-derived",
    )
    assertEquals(
      LivingArrangement.entries,
      basis.comparable,
      "and comparable is not narrowed by any of it (D2)",
    )
  }

  @Test
  fun `a school with a plan of its own beside a school with none is still varies-by-school`() {
    val student = createStudent()
    val commutable = seedCollege("Commutable U")
    addToCollegeList(student, commutable)
    addToCollegeList(student, seedCollege("Nothing Said U"))
    CostsTestDb.setEntryLivingPlan(student, commutable, LivingArrangement.WITH_FAMILY)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.PLAN_VARIES_BY_SCHOOL, basis.scope)
    assertTrue(
      basis.statement.contains("Nothing Said U: no plan on file"),
      "the school nobody said anything about is named as such, never given its neighbour's plan: [${basis.statement}]",
    )
    assertIs<ComparedLivingPlan.Unanswered>(basis.answer, "and the usual plan is still an open question")
  }

  @Test
  fun `a plan some school is not priced for names those schools with their reason`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Has Dorms U"))
    addToCollegeList(
      student,
      seedCollege(
        "No Dorms U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)

    val basis = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE, basis.scope)
    assertTrue(
      basis.statement.contains("No Dorms U: no residence halls"),
      "the school is named with its OWN reason, reusing the ArrangementGap split: [${basis.statement}]",
    )
    assertTrue(
      basis.statement.contains("never quote another way of living in their place"),
      "and no arrangement is substituted for the missing one: [${basis.statement}]",
    )
    assertFalse(
      basis.statement.contains("Has Dorms U"),
      "a school that IS priced for the plan is not named as a gap: [${basis.statement}]",
    )
  }

  @Test
  fun `every ArrangementScope code is reachable and labels its own statement`() {
    // The other half of ComparisonBasisTest's vocabulary check, and the half
    // that needs real fixtures: a code no arrangement of the facts produces is
    // a vocabulary entry nothing means, and two codes sharing a sentence would
    // make the code finer than the copy it labels. Both are the same defect in
    // opposite directions, so both are checked here, where a school is priced
    // by an actual `colleges` row.
    val bases =
      ArrangementScope.entries.associateWith { scope ->
        val student = createStudent()
        when (scope) {
          ArrangementScope.ONE_PLAN_EVERY_SCHOOL -> {
            addToCollegeList(student, seedCollege("Scope One A"))
            addToCollegeList(student, seedCollege("Scope One B"))
            // Residency too: a public school prices nothing while that question
            // is open, and "priced for it everywhere" is about a total.
            answerResidency(student, "CA")
            answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
          }

          ArrangementScope.PLAN_VARIES_BY_SCHOOL -> {
            addToCollegeList(student, seedCollege("Scope Varies A"))
            val far = seedCollege("Scope Varies B").also { addToCollegeList(student, it) }
            answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
            CostsTestDb.setEntryLivingPlan(student, far, LivingArrangement.ON_CAMPUS)
          }

          ArrangementScope.PLAN_NOT_PRICED_EVERYWHERE -> {
            addToCollegeList(student, seedCollege("Scope Gap A"))
            addToCollegeList(
              student,
              seedCollege(
                "Scope Gap B",
                housingAndFoodOnCampusPerYearUsd = null,
                otherExpensesOnCampusPerYearUsd = null,
                ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
              ),
            )
            answerResidency(student, "CA")
            answerLivingPlan(student, LivingArrangement.ON_CAMPUS)
          }

          ArrangementScope.NO_PLAN_COMPARABLE -> {
            addToCollegeList(student, seedCollege("Scope Open A"))
            addToCollegeList(student, seedCollege("Scope Open B"))
          }

          ArrangementScope.NO_PLAN_NOTHING_COMPARABLE -> {
            addToCollegeList(
              student,
              seedCollege(
                "Scope Silent A",
                housingAndFoodOnCampusPerYearUsd = null,
                housingAndFoodOffCampusPerYearUsd = null,
                booksAndSuppliesPerYearUsd = null,
                otherExpensesOnCampusPerYearUsd = null,
                otherExpensesOffCampusPerYearUsd = null,
              ),
            )
            addToCollegeList(
              student,
              seedCollege(
                "Scope Silent B",
                housingAndFoodOffCampusPerYearUsd = null,
                booksAndSuppliesPerYearUsd = null,
                otherExpensesOffCampusPerYearUsd = null,
                otherExpensesWithFamilyPerYearUsd = null,
              ),
            )
          }
        }
        assertNotNull(profileOf(student).comparisonBasis).livingArrangement
      }

    bases.forEach { (expected, basis) ->
      assertEquals(expected, basis.scope, "the fixture built for [$expected] must actually produce it")
    }
    val statements = bases.values.map { it.statement }
    assertEquals(
      statements.size,
      statements.toSet().size,
      "each code must label its OWN sentence, or the code is finer than the copy it labels: $statements",
    )
    assertTrue(statements.none { it.isEmpty() }, "a code with no sentence beside it is half a fact")
  }

  @Test
  fun `no plan on file keeps RFC 151's two arrangement statements byte-for-byte`() {
    // RFC 152 D3, the whole backward-compatibility story: an unanswered or
    // declined plan renders exactly as it did, forever. The code is new; the
    // sentences are not.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Every Way One U"))
    addToCollegeList(student, seedCollege("Every Way Two U"))

    val comparable = assertNotNull(profileOf(student).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.NO_PLAN_COMPARABLE, comparable.scope)
    assertTrue(
      comparable.statement.startsWith("Every school here is priced for") &&
        comparable.statement.endsWith("name the one you are quoting."),
      "RFC 151's non-empty statement, unchanged: [${comparable.statement}]",
    )

    val other = createStudent()
    addToCollegeList(
      other,
      seedCollege(
        "Only At Home U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
      ),
    )
    addToCollegeList(
      other,
      seedCollege(
        "Only On Campus U",
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )
    declineLivingPlan(other)

    val nothingShared = assertNotNull(profileOf(other).comparisonBasis).livingArrangement
    assertEquals(ArrangementScope.NO_PLAN_NOTHING_COMPARABLE, nothingShared.scope)
    assertEquals(
      "No one way of living is priced at every school here, so a column cannot hold the living arrangement " +
        "constant: quote each school for the ways of living it does publish, and say which one you are quoting.",
      nothingShared.statement,
      "RFC 151's empty statement, byte-for-byte",
    )
    assertIs<ComparedLivingPlan.Declined>(
      nothingShared.answer,
      "a declined plan is reachable as itself, never as an absent value",
    )
  }

  // ---------------------------------------------------------------------------
  // RFC 157: the residency basis of the two blended figures
  // ---------------------------------------------------------------------------

  /**
   * UC San Diego (IPEDS 110680) as the Scorecard actually publishes it -- every
   * figure below is the live value recorded in RFC 157's evidence.
   *
   * A REAL row rather than a round-numbered one, because the whole defect is
   * arithmetic: `COSTT4_A` = 38,701 sits inside the in-state span and BELOW the
   * out-of-state minimum of 59,923, and a weighted average cannot fall below its
   * own smallest input. Fixture numbers chosen for readability would have proved
   * nothing about it.
   */
  private fun seedUcSanDiego(name: String = "UC San Diego"): CollegeId =
    seedCollege(
      name,
      state = UCSD_STATE,
      control = 1,
      costOfAttendancePerYearUsd = UCSD_COSTT4_A,
      netPricePerYearUsd = UCSD_NPT4_PUB,
      netPricePerYearIncomeQ1Usd = UcsdScorecardRow.NPT41_PUB,
      netPricePerYearIncomeQ2Usd = UcsdScorecardRow.NPT42_PUB,
      netPricePerYearIncomeQ3Usd = UcsdScorecardRow.NPT43_PUB,
      netPricePerYearIncomeQ4Usd = UcsdScorecardRow.NPT44_PUB,
      netPricePerYearIncomeQ5Usd = UCSD_NPT45_PUB,
      tuitionAndFeesInStatePerYearUsd = UcsdScorecardRow.TUITIONFEE_IN,
      tuitionAndFeesOutOfStatePerYearUsd = UcsdScorecardRow.TUITIONFEE_OUT,
      housingAndFoodOnCampusPerYearUsd = UcsdScorecardRow.ROOMBOARD_ON_CAMPUS,
      housingAndFoodOffCampusPerYearUsd = UcsdScorecardRow.ROOMBOARD_OFF_CAMPUS,
      booksAndSuppliesPerYearUsd = UcsdScorecardRow.BOOKSUPPLY,
      otherExpensesOnCampusPerYearUsd = UcsdScorecardRow.OTHEREXPENSE_ON_CAMPUS,
      otherExpensesOffCampusPerYearUsd = UcsdScorecardRow.OTHEREXPENSE_OFF_CAMPUS,
      otherExpensesWithFamilyPerYearUsd = UcsdScorecardRow.OTHEREXPENSE_WITH_FAMILY,
    )

  /** One arrangement's settled total, or null -- read off the breakdown the payload itself renders. */
  private fun totalOf(
    cost: CollegeCost,
    arrangement: LivingArrangement,
  ): Int? =
    cost.breakdown
      ?.arrangements
      ?.firstOrNull { it.arrangement == arrangement }
      ?.totalPerYearUsd

  @Test
  fun `a WA family at UC San Diego gets the out-of-state totals and NEITHER blended figure`() {
    // The report Ian read: correct 77K/77K/60K totals with a $39K "published
    // price" two rows below them, and nothing saying the two were on different
    // residency bases (RFC 157 D-A).
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()

    assertEquals(77102, totalOf(cost, LivingArrangement.ON_CAMPUS), "out-of-state tuition plus the parts")
    assertEquals(77659, totalOf(cost, LivingArrangement.OFF_CAMPUS))
    assertEquals(59923, totalOf(cost, LivingArrangement.WITH_FAMILY))

    assertNull(
      cost.stickerCostOfAttendancePerYearUsd,
      "COSTT4_A is an in-state figure, so a WA family is never shown it",
    )
    assertNull(cost.netPrice.amount, "NPT4_PUB is in-state too, and it is the more dangerous half")
    assertIs<NetPrice.Withheld>(cost.netPrice, "the blank says WHY, so no reader can mistake it for silence")
    assertEquals(BlendedFigureApplicability.WITHHELD, cost.blendedFiguresApply)
    assertEquals(
      listOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD,
        CostField.NET_PRICE,
      ),
      cost.withheld.map { it.field },
      "both in-state-only figures are withheld, in vocabulary order",
    )
    assertTrue(
      cost.withheld.all { it.reason == WithheldReason.IN_STATE_ONLY_FIGURE },
      "one reason, and it is ours rather than the school's silence",
    )
  }

  @Test
  fun `a withheld figure is in NEITHER the reported nor the not-reported list`() {
    // The school published both numbers, so calling them unreported would blame
    // its price list for our applicability rule -- the RFC 149 D-B split, reused.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()
    val withheld = cost.withheld.map { it.field }.toSet()

    assertTrue(
      withheld.none { it in cost.reported },
      "a withheld figure is not shown, so it is not among the figures this answer reports",
    )
    assertTrue(
      withheld.none { it in cost.notReported },
      "and it is not this school's silence either: it published both numbers",
    )
  }

  @Test
  fun `an in-state family at the same school is shown both blended figures`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, UCSD_STATE)

    val cost = profileOf(student).colleges.single()

    assertEquals(42902, totalOf(cost, LivingArrangement.ON_CAMPUS), "in-state tuition plus the parts")
    assertEquals(43459, totalOf(cost, LivingArrangement.OFF_CAMPUS))
    assertEquals(25723, totalOf(cost, LivingArrangement.WITH_FAMILY))
    assertEquals(UCSD_COSTT4_A, cost.stickerCostOfAttendancePerYearUsd)
    assertEquals(UCSD_NPT4_PUB, cost.netPrice.amount)
    assertEquals(BlendedFigureApplicability.APPLIES, cost.blendedFiguresApply)
    assertEquals(emptyList(), cost.withheld, "an in-state family is the family these figures describe")
  }

  @Test
  fun `an unanswered residency withholds NOTHING and shows both figures`() {
    // RFC 157 D-B. An unanswered question is not licence to hide the only price
    // we hold, and no answer of ours is gated on a completed profile (0001 D11).
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())

    val cost = profileOf(student).colleges.single()

    assertEquals(
      BlendedFigureApplicability.BASIS_STATED,
      cost.blendedFiguresApply,
      "an open question is not a withholding",
    )
    assertEquals(emptyList(), cost.withheld)
    assertEquals(UCSD_COSTT4_A, cost.stickerCostOfAttendancePerYearUsd)
    assertEquals(UCSD_NPT4_PUB, cost.netPrice.amount)
  }

  @Test
  fun `a declined residency also withholds nothing`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    declineResidency(student)

    val cost = profileOf(student).colleges.single()
    assertEquals(BlendedFigureApplicability.BASIS_STATED, cost.blendedFiguresApply)
    assertEquals(emptyList(), cost.withheld, "a closed topic must not cost the family the figures we hold")
    assertEquals(UCSD_COSTT4_A, cost.stickerCostOfAttendancePerYearUsd)
  }

  @Test
  fun `a private college is unchanged for an out-of-state family`() {
    // In-state and out-of-state do not exist at a private school (RFC 135), so
    // there is no residency basis for these figures to fail to match.
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private U", state = "CA", control = 2))
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()
    assertEquals(BlendedFigureApplicability.APPLIES, cost.blendedFiguresApply)
    assertEquals(emptyList(), cost.withheld)
    assertEquals(40000, cost.stickerCostOfAttendancePerYearUsd)
    assertEquals(20000, cost.netPrice.amount)
  }

  @Test
  fun `a public school in another state that publishes NEITHER figure withholds nothing`() {
    // The school's own silence must stay the school's (RFC 157 D-A). Withholding
    // a figure that does not exist would tell a family "this school publishes
    // this figure" about a figure nobody published, and would delete the real
    // silence from `notReported`.
    val student = createStudent()
    addToCollegeList(
      student,
      seedCollege(
        "Silent Public U",
        state = UCSD_STATE,
        control = 1,
        costOfAttendancePerYearUsd = null,
        netPricePerYearUsd = null,
        netPricePerYearIncomeQ1Usd = null,
        netPricePerYearIncomeQ2Usd = null,
        netPricePerYearIncomeQ3Usd = null,
        netPricePerYearIncomeQ4Usd = null,
        netPricePerYearIncomeQ5Usd = null,
      ),
    )
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()

    assertEquals(emptyList(), cost.withheld, "there is nothing to hold back: the school published neither figure")
    assertTrue(
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD in cost.notReported &&
        CostField.NET_PRICE in cost.notReported,
      "a school that published nothing must still read as not reported by this school: " +
        "not_reported=[${cost.notReported}]",
    )
    assertIs<NetPrice.OverallAverage>(cost.netPrice, "a silence is not a withholding")
    assertEquals(
      BlendedFigureApplicability.WITHHELD,
      cost.blendedFiguresApply,
      "the basis still does not describe this family; there is simply no figure to hold back",
    )
  }

  @Test
  fun `the withheld band price keeps the band the family answered, and only loses the number`() {
    // The family answered the income question; that fact is theirs whether or
    // not this school's figure is one we can show them.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")
    answerBand(student, IncomeBand.OVER_110K)

    val netPrice = assertIs<NetPrice.Withheld>(profileOf(student).colleges.single().netPrice)
    val basis = assertIs<NetPriceBasis.YourIncomeBand>(netPrice.publishedBasis)
    assertEquals(IncomeBand.OVER_110K, basis.band, "the answered band survives the withholding")
    assertNull(netPrice.amount, "the top band is an in-state figure and is not this family's")
    assertEquals(WithheldReason.IN_STATE_ONLY_FIGURE, netPrice.reason, "the reason travels with the blank")
  }

  @Test
  fun `the same top-band family in California sees that band's price`() {
    // The other half of the case above: the withholding is about residency, and
    // nothing else about the band selection changed.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, UCSD_STATE)
    answerBand(student, IncomeBand.OVER_110K)

    val netPrice = assertIs<NetPrice.BandSpecific>(profileOf(student).colleges.single().netPrice)
    assertEquals(UCSD_NPT45_PUB, netPrice.amount)
  }

  @Test
  fun `the comparison basis carries SIX facts, and the sixth names the school it withholds at`() {
    // RFC 157 D-C: the object claimed for five facts that residency was one of
    // them, and for two of its columns it did not keep that promise.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    addToCollegeList(student, seedCollege("Home State U", state = "WA", control = 1))
    answerResidency(student, "WA")

    val basis = assertNotNull(profileOf(student).comparisonBasis)
    val blended = basis.blendedFigures

    assertEquals(BlendedFigureScope.WITHHELD_AT_SOME_SCHOOLS, blended.scope)
    assertTrue(
      blended.statement.contains("UC San Diego") && !blended.statement.contains("Home State U"),
      "the sentence names the schools these figures do NOT describe: [${blended.statement}]",
    )
    assertEquals(
      listOf(BlendedFigureApplicability.APPLIES, BlendedFigureApplicability.WITHHELD),
      blended.byCollege.sortedBy { it.name }.map { it.applies },
      "Home State U is theirs; UC San Diego is not",
    )
    assertEquals(
      basis.statements[basis.statements.indexOf(basis.residency.statement) + 1],
      blended.statement,
      "the sixth fact is said immediately after the residency line that makes it dangerous",
    )
  }

  @Test
  fun `an in-state family at every public school gets the in-state scope`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    addToCollegeList(student, seedCollege("Also California U", state = UCSD_STATE, control = 1))
    answerResidency(student, UCSD_STATE)

    val blended = assertNotNull(profileOf(student).comparisonBasis).blendedFigures
    assertEquals(BlendedFigureScope.IN_STATE_EVERYWHERE, blended.scope)
    assertTrue(
      blended.byCollege.all { it.applies == BlendedFigureApplicability.APPLIES },
      "both schools' figures are this family's",
    )
  }

  @Test
  fun `an all-private comparison says these figures are on no residency basis`() {
    val student = createStudent()
    addToCollegeList(student, seedCollege("Private One U", control = 2))
    addToCollegeList(student, seedCollege("Private Two U", control = 3))
    answerResidency(student, "WA")

    val blended = assertNotNull(profileOf(student).comparisonBasis).blendedFigures
    assertEquals(BlendedFigureScope.NO_RESIDENCY_BASIS_HERE, blended.scope)
    assertTrue(
      blended.statement.contains("not on any residency basis"),
      "an all-private table must not be given a caveat about public tuition: [${blended.statement}]",
    )
  }

  @Test
  fun `a public school with the residency unanswered states the in-state basis rather than withholding`() {
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    addToCollegeList(student, seedCollege("Second Public U", control = 1))

    val blended = assertNotNull(profileOf(student).comparisonBasis).blendedFigures
    assertEquals(BlendedFigureScope.RESIDENCY_NOT_ON_FILE, blended.scope)
    assertTrue(
      blended.statement.contains("students paying in-state tuition") &&
        blended.statement.contains("rather than withheld"),
      "D-B: the basis is stated and nothing is hidden: [${blended.statement}]",
    )
  }

  @Test
  fun `the single-school basis states the blended-figure residency too`() {
    // A one-school answer builds no comparison (RFC 151 D-B), and the parent
    // reading it is owed the same sixth fact.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val profile = profileOf(student)
    val cost = profile.colleges.single()
    val residency =
      ResidencyBasis
        .of(profile.colleges, profile.moneyProfile)
        .byCollege
        .single()
    val basis = SingleSchoolBasis.of(cost, residency)

    assertEquals(BlendedFigureApplicability.WITHHELD, basis.blendedFigures.applies)
    assertTrue(
      basis.blendedFigures.statement in basis.statements,
      "the statement list is what every renderer prints, so a fact outside it reaches nobody",
    )
    assertTrue(
      basis.blendedFigures.statement.contains("out-of-state tuition and fees are the ones that apply"),
      "a blank with no destination is a number taken away: [${basis.blendedFigures.statement}]",
    )
  }

  @Test
  fun `no in-state-only figure survives for a family the in-state basis does not describe`() {
    // The guard RFC 157 asks for, stated over the vocabulary rather than over
    // one fixture: whatever [CostField.IN_STATE_ONLY_FIELDS] comes to hold, the
    // read must carry no amount for any of it at a non-matching public school.
    val student = createStudent()
    addToCollegeList(student, seedUcSanDiego())
    answerResidency(student, "WA")

    val cost = profileOf(student).colleges.single()
    val amounts =
      mapOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD to cost.stickerCostOfAttendancePerYearUsd,
        CostField.NET_PRICE to cost.netPrice.amount,
      )

    assertEquals(
      CostField.IN_STATE_ONLY_FIELDS.toSet(),
      amounts.keys,
      "every in-state-only field must be checked here, or a third one ships unguarded",
    )
    assertTrue(
      amounts.values.all { it == null },
      "an in-state-only figure reached a family the in-state basis does not describe: amounts=[$amounts]",
    )
  }

  companion object {
    // The row itself lives in [UcsdScorecardRow], the one fixture home this
    // module and :public-web both read, so a corrected Scorecard figure is one
    // edit. The three names below are the figures these cases ASSERT on, kept
    // short at the assertion sites that read them.

    /** UC San Diego's own state, so "in-state" in these cases is a fact rather than a coincidence of literals. */
    private const val UCSD_STATE = UcsdScorecardRow.STATE

    /** `COSTT4_A`: below the out-of-state minimum of 59,923, which is what makes the in-state basis provable. */
    private const val UCSD_COSTT4_A = UcsdScorecardRow.COSTT4_A

    /** `NPT4_PUB`: the overall average net price, in-state only. */
    private const val UCSD_NPT4_PUB = UcsdScorecardRow.NPT4_PUB

    /** `NPT45_PUB`: the top band, and the figure a WA family read as theirs against a real ~77,102. */
    private const val UCSD_NPT45_PUB = UcsdScorecardRow.NPT45_PUB
  }
}
