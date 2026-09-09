package ed.unicoach.coaching.costs

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.costs.CostsTestDb.answerBand
import ed.unicoach.coaching.costs.CostsTestDb.answerLivingPlan
import ed.unicoach.coaching.costs.CostsTestDb.answerResidency
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.costs.CostsTestDb.declineBand
import ed.unicoach.coaching.costs.CostsTestDb.declineLivingPlan
import ed.unicoach.coaching.costs.CostsTestDb.declineResidency
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.LoanType
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.ValueBearingStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeCostChatToolTest {
  @BeforeEach
  fun resetDatabase() {
    CostsTestDb.reset()
  }

  private val tool = CollegeCostChatTool(CollegeCostService(CostsTestDb.database))

  private fun seedListedCollege(
    student: StudentId,
    name: String,
    state: String = "CA",
    control: Int = 1,
    medianEarnings10yAfterEntryUsd: Int? = 55000,
    stickerCostOfAttendancePerYearUsd: Int? = 40000,
    tuitionAndFeesInStatePerYearUsd: Int? = 12000,
    tuitionAndFeesOutOfStatePerYearUsd: Int? = 30000,
    medianDebtAtCompletionUsd: Int? = 23000,
    bandPricing: Boolean = true,
    // The canonical-only figures (RFC 166 §4/§5), absent by default because most
    // institutions publish neither: an in-district row exists at the IC_AY
    // institutions alone, and the fees split only where a source separates fees
    // from tuition. A test that wants either asks for it by name.
    seedsInDistrictRow: Boolean = false,
    tuitionAndFeesInDistrictPerYearUsd: Int? = null,
    feesOnlyInStatePerYearUsd: Int? = null,
    feesOnlyOutOfStatePerYearUsd: Int? = null,
    feesOnlyInDistrictPerYearUsd: Int? = null,
  ): CollegeId {
    val id =
      CostsTestDb.seedCollege(
        name,
        state = state,
        control = control,
        costOfAttendancePerYearUsd = stickerCostOfAttendancePerYearUsd,
        tuitionAndFeesInStatePerYearUsd = tuitionAndFeesInStatePerYearUsd,
        tuitionAndFeesOutOfStatePerYearUsd = tuitionAndFeesOutOfStatePerYearUsd,
        seedsInDistrictRow = seedsInDistrictRow,
        tuitionAndFeesInDistrictPerYearUsd = tuitionAndFeesInDistrictPerYearUsd,
        feesOnlyInStatePerYearUsd = feesOnlyInStatePerYearUsd,
        feesOnlyOutOfStatePerYearUsd = feesOnlyOutOfStatePerYearUsd,
        feesOnlyInDistrictPerYearUsd = feesOnlyInDistrictPerYearUsd,
        medianDebtAtCompletionUsd = medianDebtAtCompletionUsd,
        medianEarnings10yAfterEntryUsd = medianEarnings10yAfterEntryUsd,
        netPricePerYearIncomeQ1Usd = if (bandPricing) CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q1_USD else null,
        netPricePerYearIncomeQ2Usd = if (bandPricing) CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q2_USD else null,
        netPricePerYearIncomeQ3Usd = if (bandPricing) CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q3_USD else null,
        netPricePerYearIncomeQ4Usd = if (bandPricing) CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q4_USD else null,
        netPricePerYearIncomeQ5Usd = if (bandPricing) CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q5_USD else null,
      )
    CostsTestDb.addToCollegeList(student, id)
    return id
  }

  private fun input(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

  private fun errorOf(result: JsonObject): String? = result["error"]?.jsonPrimitive?.content

  private fun collegesOf(result: JsonObject) = result.getValue("colleges").jsonArray.map { it.jsonObject }

  private fun execute(
    student: StudentId,
    raw: String = "{}",
  ): JsonObject = runBlocking { tool.execute(student, input(raw)) }

  /**
   * One college's precision_offer entries, in wire order -- the one place this
   * file writes the array's shape down, keyed off the emitter's own
   * [CollegeCostChatTool.PRECISION_OFFER_KEY] rather than a retyped literal.
   * An absent key reads as no offers, which is why the absent-vs-empty contract
   * is asserted on the raw key instead (see
   * `a college with nothing to offer carries no precision_offer key`).
   */
  private fun offersOf(college: JsonObject): List<JsonObject> =
    college[CollegeCostChatTool.PRECISION_OFFER_KEY]
      ?.jsonArray
      ?.map { it.jsonObject }
      ?: emptyList()

  /**
   * The `field` names of one college's offers, in wire order -- the ordering IS
   * the product decision (RFC 145), so it is asserted as a list, never a set.
   */
  private fun offerFieldsOf(college: JsonObject): List<String> = offersOf(college).map { it.getValue("field").jsonPrimitive.content }

  /** The offer sentence for one field, or null when that upgrade is not on offer here. */
  private fun offerCopyOf(
    college: JsonObject,
    field: String,
  ): String? =
    offersOf(college)
      .firstOrNull { it.getValue("field").jsonPrimitive.content == field }
      ?.getValue("offer")
      ?.jsonPrimitive
      ?.content

  @Test
  fun `income_band_label is emitted with every band-specific net price`() {
    val student = createStudent()
    seedListedCollege(student, "Label U")
    answerBand(student, IncomeBand.OVER_110K)

    val netPrice =
      collegesOf(execute(student)).single().getValue("net_price").jsonObject
    assertEquals("over_110k", netPrice["income_band"]!!.jsonPrimitive.content, "the code stays: it is the stable identifier")
    assertEquals(
      IncomeBand.OVER_110K.bracket,
      netPrice["income_band_label"]!!.jsonPrimitive.content,
      "the label is the band's own display copy, not a second home for it",
    )
    assertEquals(
      "\$110,000 or more",
      netPrice["income_band_label"]!!.jsonPrimitive.content,
      "and it is a phrase a coach can read into a sentence",
    )
  }

  @Test
  fun `an overall-average net price carries no band label`() {
    val student = createStudent()
    seedListedCollege(student, "NoBand U")

    val netPrice =
      collegesOf(execute(student)).single().getValue("net_price").jsonObject
    assertEquals("overall_average", netPrice["basis"]!!.jsonPrimitive.content)
    assertNull(netPrice["income_band"], "an overall average has no band")
    assertNull(netPrice["income_band_label"], "and so has nothing to label")
  }

  @Test
  fun `the money-profile echo labels an answered band and omits the label otherwise`() {
    val student = createStudent()

    val unanswered = execute(student).getValue("money_profile").jsonObject
    assertEquals("unanswered", unanswered["income_band_status"]!!.jsonPrimitive.content)
    assertNull(unanswered["income_band_label"], "an unanswered band has no label")

    answerBand(student, IncomeBand.K48_TO_75K)
    val answered = execute(student).getValue("money_profile").jsonObject
    assertEquals("48k_to_75k", answered["income_band"]!!.jsonPrimitive.content)
    assertEquals(IncomeBand.K48_TO_75K.bracket, answered["income_band_label"]!!.jsonPrimitive.content)

    declineBand(student)
    val declined = execute(student).getValue("money_profile").jsonObject
    assertEquals("declined", declined["income_band_status"]!!.jsonPrimitive.content)
    assertNull(declined["income_band"], "a decline clears the value")
    assertNull(declined["income_band_label"], "and with it the label")
  }

  @Test
  fun `no bare source code reaches a tool result`() {
    val student = createStudent()
    // Every optional cost field is populated on purpose: `collegeObject` renders
    // them with `cost.foo?.let { put(...) }`, so a field left null is simply
    // absent and the guard never sees it -- and that is exactly the shape the
    // NEXT coded field will take. A sparse fixture would let it sleep through.
    seedListedCollege(
      student,
      "Wire U",
      stickerCostOfAttendancePerYearUsd = 40000,
      tuitionAndFeesInStatePerYearUsd = 12000,
      tuitionAndFeesOutOfStatePerYearUsd = 30000,
      medianDebtAtCompletionUsd = 23000,
      // The RFC 166 keys too, for the same reason: the third tuition tier and
      // the three fees figures are absent at an ordinary college, so a fixture
      // without them would leave the newest keys unguarded.
      seedsInDistrictRow = true,
      tuitionAndFeesInDistrictPerYearUsd = IN_DISTRICT_TUITION_USD,
      feesOnlyInStatePerYearUsd = FEES_ONLY_IN_STATE_USD,
      feesOnlyOutOfStatePerYearUsd = FEES_ONLY_OUT_OF_STATE_USD,
      feesOnlyInDistrictPerYearUsd = FEES_ONLY_IN_DISTRICT_USD,
    )
    answerBand(student, IncomeBand.OVER_110K)
    // Residency too, so the arrangement objects render their tuition line AND
    // their total_per_year_usd: the guard must see the RFC 149 numbers, not a
    // breakdown that stopped short of emitting them.
    answerResidency(student, "CA")

    // The whole rendered payload, not one field: the leak this guards against
    // (RFC 142) is a FUTURE field quietly carrying the source's own codes into
    // the model's context, where it will happily say them aloud. RFC 143 makes
    // the assertion the general property rather than a grep for the two tokens
    // that leaked before -- `control` sat inside the old grep's own payload.
    val payload = execute(student)
    assertEquals(emptyList(), listViolations(payload), "the cost result must carry no source code")
    assertTrue(payload.toString().contains(IncomeBand.OVER_110K.bracket), "the dollar range is what goes instead")

    // ...and the clean verdict above is over a payload that actually renders
    // every cost field, so the allowlist is exercised rather than vacuously
    // satisfied by keys the fixture left null.
    val college = collegesOf(payload).single()
    assertEquals(
      emptySet(),
      CostField.entries.map { it.wireName }.toSet() - keysAnywhereIn(college),
      "every cost field the allowlist sanctions must be in the payload",
    )

    // Positive control: the guard must react to ALL THREE shapes it exists to
    // catch -- including `NPT4`, whose old direct `assertFalse(contains(...))`
    // this guard replaced -- or the assertions above prove nothing and an NPT4
    // typo in the helper would pass unnoticed.
    val doctored =
      JsonObject(
        payload +
          mapOf(
            "control" to JsonPrimitive(2),
            "net_price_per_year_income_q5_usd" to JsonPrimitive(31000),
            "source_column" to JsonPrimitive("NPT41"),
          ),
      )
    assertEquals(
      listOf(
        BareSourceCode.QuintileToken("q5"),
        BareSourceCode.Npt4ColumnFamily,
        BareSourceCode.BareNumberField("control"),
        BareSourceCode.BareNumberField("net_price_per_year_income_q5_usd"),
      ),
      listViolations(doctored),
    )

    // An error envelope is a model-facing tool result too, and a malformed-arg
    // retry is an ordinary path -- so it owes the same property (RFC 143).
    assertEquals(
      emptyList(),
      listViolations(execute(student, """{"college_ids":["not-a-uuid"]}""")),
      "the malformed-input error must carry no source code",
    )
  }

  @Test
  fun `the definition carries the name, the schema, and the ethos contract`() {
    assertEquals("college_cost_profile", tool.name)
    assertEquals("college_cost_profile", tool.definition["name"]!!.jsonPrimitive.content)
    val description = tool.definition["description"]!!.jsonPrimitive.content
    // NO publisher is named here any more (RFC 177). The description used to
    // tell the model to attribute every figure to the College Scorecard, while
    // the loader ranks both IPEDS surveys above it -- so the instruction was
    // wrong for most figures. It now points at the payload's own key.
    assertTrue(
      description.contains(CollegeCostChatTool.SOURCE_KEY),
      "the description must send the model to the key that names this payload's own publishers: [$description]",
    )
    MoneySource.entries.forEach { source ->
      assertFalse(
        description.contains(MoneySourceCopy.labelOf(source)),
        "the description may name no publisher: [${source.value}]",
      )
    }
    assertFalse(description.contains("College Scorecard"), "the hand-typed attribution is gone: [$description]")
    assertTrue(description.contains("never estimate"), "the no-invented-numbers rule must ride the description")
    assertTrue(description.contains("never re-raise"), "the decline etiquette must ride the description")
    assertTrue(description.contains(MoneyProfileChatTool.TOOL_NAME), "the offer must name the recording tool")
    assertTrue(
      description.contains("money_profile.residency_status is the authority on whether to raise residency"),
      "residency's decline authority must ride the description exactly as income's does (RFC 145)",
    )
    // Positionally, not by indexOf: `income_band` also occurs inside
    // `income_band_label` earlier in the description, so the order has to be
    // read off the sentence that states it.
    assertTrue(
      description.contains("${PrecisionOffer.RESIDENCY.field} sorts first"),
      "the description must state the offer order it renders: residency first",
    )
    assertTrue(
      description.contains("income_band_label"),
      "the model must be told the band's dollar range rides the result (RFC 142)",
    )
    assertTrue(description.contains("Read-only"))

    val properties =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    assertEquals("array", properties["college_ids"]!!.jsonObject["type"]!!.jsonPrimitive.content)
  }

  // ---------------------------------------------------------------------------
  // The residency offer (RFC 145)
  // ---------------------------------------------------------------------------

  @Test
  fun `a public college with residency unanswered offers residency first`() {
    val student = createStudent()
    seedListedCollege(student, "Public U", control = 1)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      listOf(PrecisionOffer.RESIDENCY.field, PrecisionOffer.INCOME_BAND.field),
      offerFieldsOf(college),
      "both upgrades are on offer, and residency is index 0 - the cheaper question, the bigger correction. " +
        "The living plan is NOT offered here: residency is unanswered, so this public school's tuition line " +
        "is null and no arrangement carries a total, which is nothing for the family to choose between (D4)",
    )
    val residency = offerCopyOf(college, PrecisionOffer.RESIDENCY.field)!!
    assertTrue(residency.contains(MoneyProfileChatTool.TOOL_NAME), "the offer must name the recording tool")
    assertTrue(
      residency.contains("in-state") && residency.contains("out-of-state"),
      "the offer must say what the answer unlocks: which published price this family would pay",
    )
  }

  @Test
  fun `an all-private list makes no residency offer`() {
    val student = createStudent()
    seedListedCollege(student, "Nonprofit U", control = 2)
    seedListedCollege(student, "ForProfit U", control = 3)

    collegesOf(execute(student)).forEach { college ->
      assertEquals(
        listOf(PrecisionOffer.INCOME_BAND.field, PrecisionOffer.LIVING_PLAN.field),
        offerFieldsOf(college),
        "a private school has one price, so residency buys nothing: [${college["name"]}]",
      )
    }
  }

  @Test
  fun `a declined residency is never re-offered`() {
    val student = createStudent()
    seedListedCollege(student, "Declined Residency U", control = 1)
    declineResidency(student)

    val result = execute(student)
    val college = collegesOf(result).single()
    assertEquals(
      listOf(PrecisionOffer.INCOME_BAND.field),
      offerFieldsOf(college),
      "a declined residency is accepted permanently - the coach is never cued to reopen it. The living plan " +
        "is not offered either: a declined residency leaves this public school's arrangements without totals",
    )
    assertEquals(
      "declined",
      result
        .getValue("money_profile")
        .jsonObject["residency_status"]!!
        .jsonPrimitive.content,
    )
    assertEquals(
      "unknown",
      college["tuition_applicable"]!!.jsonPrimitive.content,
      "a decline still leaves applicability unknown: the offer is keyed off the STATUS, never off unknown",
    )
  }

  @Test
  fun `an answered residency makes no residency offer`() {
    val student = createStudent()
    seedListedCollege(student, "Home State U", state = "CA", control = 1)
    seedListedCollege(student, "Away State U", state = "NY", control = 1)
    answerResidency(student, "CA")

    val byName = collegesOf(execute(student)).associateBy { it["name"]!!.jsonPrimitive.content }
    byName.values.forEach { college ->
      assertEquals(
        listOf(PrecisionOffer.INCOME_BAND.field, PrecisionOffer.LIVING_PLAN.field),
        offerFieldsOf(college),
        "residency is on file: [${college["name"]}]",
      )
    }
    assertEquals("in_state", byName.getValue("Home State U")["tuition_applicable"]!!.jsonPrimitive.content)
    assertEquals("out_of_state", byName.getValue("Away State U")["tuition_applicable"]!!.jsonPrimitive.content)
  }

  @Test
  fun `every offer field is a parameter update_money_profile accepts`() {
    // Read from the recording tool's OWN schema, never retyped here: the offer's
    // whole promise is that the coach can hand this field to that tool, so a
    // rename there must fail here rather than ship an invitation naming a
    // parameter nothing accepts (the two tools are otherwise unbound).
    val recordable =
      MoneyProfileChatTool(CostsTestDb.moneyProfiles)
        .definition
        .getValue("input_schema")
        .jsonObject
        .getValue("properties")
        .jsonObject
        .keys

    PrecisionOffer.entries.forEach { offer ->
      assertTrue(
        offer.field in recordable,
        "the coach is told to record [${offer.field}] with [${MoneyProfileChatTool.TOOL_NAME}], " +
          "which accepts only [$recordable]",
      )
    }
  }

  @Test
  fun `a student who says they will live at home gets that total, with the zero named as ours, in the same turn`() {
    // The slice's first-session test, end to end through the two tools the
    // coach actually calls: the write tool records the plan, and the very next
    // cost read leads with it. Nothing in between, because "in the same turn"
    // is the promise.
    val student = createStudent()
    seedListedCollege(student, "Same Turn U", control = 1)
    answerResidency(student, "CA")

    val offered = collegesOf(execute(student)).single()
    assertTrue(
      PrecisionOffer.LIVING_PLAN.field in offerFieldsOf(offered),
      "before the answer, the question is on offer",
    )
    assertNull(offered[CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY], "and nothing is led with yet")

    val write =
      runBlocking {
        MoneyProfileChatTool(CostsTestDb.moneyProfiles)
          .execute(student, input("""{"living_plan":"with_family"}"""))
      }
    assertNull(write["error"], "got $write")

    val after = collegesOf(execute(student)).single()
    val chosen = after.getValue(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY).jsonObject
    assertEquals(LivingArrangement.WITH_FAMILY.value, chosen.getValue("arrangement").jsonPrimitive.content)
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      chosen
        .getValue(CollegeCostChatTool.TOTAL_KEY)
        .jsonPrimitive.content
        .toInt(),
      "a real total, from this school's own in-state tuition and its at-home allowances",
    )
    val atHome =
      after
        .getValue(CollegeCostChatTool.BREAKDOWN_KEY)
        .jsonObject
        .getValue(LivingArrangement.WITH_FAMILY.value)
        .jsonObject
    assertNull(
      atHome[CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName],
      "never another arrangement's housing allowance: [$atHome]",
    )
    assertNull(atHome[CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD.wireName])
    // The at-home line is there, it is `$0`, and the arrangement says the zero
    // is ours (RFC 166 §7) -- so the total above is complete rather than a sum
    // with a part quietly left out of it.
    assertEquals(
      "0",
      atHome
        .getValue(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName)
        .jsonPrimitive.content,
    )
    assertEquals(
      listOf(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName),
      atHome
        .getValue(CollegeCostChatTool.ASSUMED_BY_UNICOACH_KEY)
        .jsonArray
        .map { it.jsonPrimitive.content },
    )
    assertTrue(
      PrecisionOffer.LIVING_PLAN.field !in offerFieldsOf(after),
      "and the question is not asked twice",
    )
  }

  @Test
  fun `a college with nothing to offer carries no precision_offer key`() {
    val student = createStudent()
    // Private, so residency buys nothing; band declined, so the income
    // invitation is closed for good. Nothing is left to offer.
    seedListedCollege(student, "Nothing To Offer U", control = 2)
    declineBand(student)
    // RFC 152: the third field must be closed too, or the school's two priced
    // ways of living keep an invitation alive. A decline closes it permanently.
    declineLivingPlan(student)

    // Asserted on the RAW key, deliberately not through offerFieldsOf: that
    // helper folds an absent key and an empty array into the same empty list,
    // so it cannot see the difference this test exists to pin. The contract is
    // an ABSENT key, never `"precision_offer": []` -- the key's mere presence
    // is what tells the model this result has an upgrade to offer, and an
    // empty array is an invitation with nothing in it.
    val college = collegesOf(execute(student)).single()
    assertNull(
      college[CollegeCostChatTool.PRECISION_OFFER_KEY],
      "no offers means no key at all, not an empty array: [$college]",
    )
  }

  @Test
  fun `a public college reporting no tuition figure makes no residency offer`() {
    val student = createStudent()
    seedListedCollege(
      student,
      "No Tuition U",
      control = 1,
      tuitionAndFeesInStatePerYearUsd = null,
      tuitionAndFeesOutOfStatePerYearUsd = null,
    )
    seedListedCollege(student, "In State Only U", control = 1, tuitionAndFeesOutOfStatePerYearUsd = null)

    val byName = collegesOf(execute(student)).associateBy { it["name"]!!.jsonPrimitive.content }
    assertEquals(
      listOf(PrecisionOffer.INCOME_BAND.field),
      offerFieldsOf(byName.getValue("No Tuition U")),
      "a college that publishes neither tuition figure has no residency upgrade to promise - and no " +
        "arrangement of its can carry a total, so it has no living-plan upgrade to promise either",
    )
    // One published figure still makes residency worth asking -- it decides
    // WHICH price applies -- and the offer copy promises no more than that,
    // saying plainly when the applicable one is the figure this school does
    // not report. That is the ordinary data_availability answer, not an
    // invented number.
    val inStateOnly = byName.getValue("In State Only U")
    assertEquals(
      listOf(PrecisionOffer.RESIDENCY.field, PrecisionOffer.INCOME_BAND.field),
      offerFieldsOf(inStateOnly),
      "one published figure is still a price residency selects - but until residency IS answered the " +
        "tuition line stays null, so no arrangement totals and the living plan is not yet worth asking",
    )
    assertTrue(
      offerCopyOf(inStateOnly, PrecisionOffer.RESIDENCY.field)!!.contains("does not report the one that applies"),
      "the copy must promise only what the data supports: no figure is guaranteed for either side",
    )
    assertTrue(
      "tuition_and_fees_out_of_state_per_year_usd" in dataAvailabilityOf(inStateOnly),
      "and the missing side is already reported as unavailable, which is what the coach then says",
    )
  }

  @Test
  fun `an empty list is a structured result with the money-profile block and the attribution`() {
    val student = createStudent()
    val result = execute(student)

    assertNull(errorOf(result), "got [$result]")
    assertTrue(collegesOf(result).isEmpty())
    assertEquals(
      0,
      result
        .getValue("count")
        .jsonPrimitive.content
        .toInt(),
    )
    val profile = result.getValue("money_profile").jsonObject
    assertEquals("unanswered", profile["income_band_status"]!!.jsonPrimitive.content)
    assertEquals("unanswered", profile["residency_status"]!!.jsonPrimitive.content)
    // An EMPTY read names no publisher (RFC 177): absent, never empty, exactly
    // like every other key here. The constant this replaced attributed a result
    // that carried no figure at all to the College Scorecard.
    assertNull(result[CollegeCostChatTool.SOURCE_KEY], "no figure, no publisher to name")
    assertNull(result["unknown_college_ids"], "no filter, no unknowns")
  }

  @Test
  fun `an explicit empty college_ids array is an empty subset, not the whole list`() {
    val student = createStudent()
    seedListedCollege(student, "Listed U")

    val result = execute(student, """{"college_ids":[]}""")

    assertNull(errorOf(result), "got [$result]")
    assertTrue(collegesOf(result).isEmpty(), "empty subset answers no colleges")
    assertEquals(
      0,
      result
        .getValue("count")
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(
      "unanswered",
      result
        .getValue("money_profile")
        .jsonObject["income_band_status"]!!
        .jsonPrimitive.content,
    )
  }

  @Test
  fun `the tool writes nothing`() {
    val student = createStudent()
    seedListedCollege(student, "ReadOnly U")

    val result = execute(student)
    assertNull(errorOf(result), "got [$result]")

    assertTrue(
      MoneyProfilesDao.findActiveByStudent(CostsTestDb.sqlSession, student).isFailure,
      "a cost read must not create a money-profile row",
    )
    CoachingTestDb.connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT count(*) FROM college_list_entries").use { rs ->
        rs.next()
        assertEquals(1, rs.getInt(1), "a cost read must not touch the list")
      }
    }
  }

  @Test
  fun `an answered band renders the family-specific net price without a precision offer`() {
    val student = createStudent()
    seedListedCollege(student, "Band U")
    answerBand(student, IncomeBand.K30_TO_48K)

    val result = execute(student)
    val college = collegesOf(result).single()
    val netPrice = college.getValue("net_price").jsonObject
    assertEquals("your_income_band", netPrice["basis"]!!.jsonPrimitive.content)
    assertEquals(
      11000,
      netPrice["amount_usd"]!!.jsonPrimitive.content.toInt(),
      "30k_to_48k must select net_price_per_year_income_q2_usd",
    )
    assertEquals("30k_to_48k", netPrice["income_band"]!!.jsonPrimitive.content)
    assertEquals(
      listOf(PrecisionOffer.RESIDENCY.field),
      offerFieldsOf(college),
      "an answered band needs no invitation, and the living plan waits on residency at a public school",
    )

    val profile = result.getValue("money_profile").jsonObject
    assertEquals("answered", profile["income_band_status"]!!.jsonPrimitive.content)
    assertEquals("30k_to_48k", profile["income_band"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an unanswered band renders the overall average WITH the precision offer`() {
    val student = createStudent()
    seedListedCollege(student, "Avg U")

    val college = collegesOf(execute(student)).single()
    val netPrice = college.getValue("net_price").jsonObject
    assertEquals("overall_average", netPrice["basis"]!!.jsonPrimitive.content)
    assertEquals(20000, netPrice["amount_usd"]!!.jsonPrimitive.content.toInt())
    assertNull(netPrice["income_band"])
    val offer = offerCopyOf(college, PrecisionOffer.INCOME_BAND.field)!!
    assertTrue(offer.contains(MoneyProfileChatTool.TOOL_NAME), "the offer must name the recording tool")
  }

  @Test
  fun `a college reporting no bracket columns carries no precision offer even with the band unanswered`() {
    val student = createStudent()
    seedListedCollege(student, "Full U")
    seedListedCollege(student, "NoBands U", bandPricing = false)

    val byName = collegesOf(execute(student)).associateBy { it["name"]!!.jsonPrimitive.content }
    assertTrue(
      PrecisionOffer.INCOME_BAND.field in offerFieldsOf(byName.getValue("Full U")),
      "a college with band data still carries the invitation",
    )
    assertTrue(
      PrecisionOffer.INCOME_BAND.field !in offerFieldsOf(byName.getValue("NoBands U")),
      "a college with no band data makes no upgrade promise",
    )
  }

  @Test
  fun `a declined band renders the overall average WITHOUT the precision offer`() {
    val student = createStudent()
    seedListedCollege(student, "Declined U")
    declineBand(student)

    val result = execute(student)
    val college = collegesOf(result).single()
    assertEquals(
      "overall_average",
      college
        .getValue("net_price")
        .jsonObject["basis"]!!
        .jsonPrimitive.content,
    )
    assertTrue(
      PrecisionOffer.INCOME_BAND.field !in offerFieldsOf(college),
      "the coach must never be cued to reopen a declined band",
    )
    assertEquals(
      "declined",
      result
        .getValue("money_profile")
        .jsonObject["income_band_status"]!!
        .jsonPrimitive.content,
    )
  }

  @Test
  fun `a band re-answered after a decline is family-specific on the next call`() {
    val student = createStudent()
    seedListedCollege(student, "Reopen U")
    declineBand(student)
    assertEquals(
      "overall_average",
      collegesOf(execute(student))
        .single()
        .getValue("net_price")
        .jsonObject["basis"]!!
        .jsonPrimitive.content,
    )

    answerBand(student, IncomeBand.UNDER_30K)
    val netPrice =
      collegesOf(execute(student)).single().getValue("net_price").jsonObject
    assertEquals("your_income_band", netPrice["basis"]!!.jsonPrimitive.content)
    assertEquals(9000, netPrice["amount_usd"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun `unknown ids are reported while known ones still answer`() {
    val student = createStudent()
    val mine = seedListedCollege(student, "Mine U")
    val nowhere = UUID.randomUUID()

    val result = execute(student, """{"college_ids":["${mine.value}","$nowhere"]}""")
    assertNull(errorOf(result), "got [$result]")
    assertEquals(listOf("Mine U"), collegesOf(result).map { it["name"]!!.jsonPrimitive.content })
    assertEquals(
      listOf(nowhere.toString()),
      result.getValue("unknown_college_ids").jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `data availability lists the fields a college does not report`() {
    val student = createStudent()
    seedListedCollege(student, "Sparse U", medianEarnings10yAfterEntryUsd = null)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      listOf("median_earnings_10y_after_entry_usd"),
      dataAvailabilityOf(college),
    )
    assertNull(college["median_earnings_10y_after_entry_usd"], "an unreported figure is absent, never invented")
  }

  @Test
  fun `a college with nothing missing carries no data_availability key at all`() {
    // Absent, never empty -- this payload's own convention, which
    // `withheld_figures` follows two lines below it in the writer. An empty
    // array is a sentinel the coach has to open to learn it says nothing.
    val student = createStudent()
    seedListedCollege(student, "Complete U")

    val college = collegesOf(execute(student)).single()
    assertEquals(emptyList(), dataAvailabilityOf(college), "this school reports every field the tool renders")
    assertNull(
      college[CollegeCostChatTool.DATA_AVAILABILITY_KEY],
      "and an empty list is not written at all: [$college]",
    )
  }

  @Test
  fun `a private college carries no tuition_applicable while a public one does`() {
    val student = createStudent()
    seedListedCollege(student, "Public U", control = 1)
    seedListedCollege(student, "Private U", control = 2)

    val byName = collegesOf(execute(student)).associateBy { it["name"]!!.jsonPrimitive.content }
    assertEquals("unknown", byName.getValue("Public U")["tuition_applicable"]!!.jsonPrimitive.content)
    assertEquals("public", byName.getValue("Public U")["control"]!!.jsonPrimitive.content)
    assertNull(byName.getValue("Private U")["tuition_applicable"])
    assertEquals("private_nonprofit", byName.getValue("Private U")["control"]!!.jsonPrimitive.content)
  }

  @Test
  fun `malformed input is a structured error naming the offending value and writes nothing`() {
    val student = createStudent()

    val notArray = execute(student, """{"college_ids":"abc"}""")
    assertEquals("""college_ids must be an array of uuid strings, got: ["abc"]""", errorOf(notArray))

    val notString = execute(student, """{"college_ids":["${UUID.randomUUID()}",42]}""")
    assertEquals("college_ids entry is not a uuid string: [42] at index [1]", errorOf(notString))

    val notUuid = execute(student, """{"college_ids":["not-a-uuid"]}""")
    assertEquals("college_ids entry is not a uuid: [not-a-uuid] at index [0]", errorOf(notUuid))

    val unknownField = execute(student, """{"college_id":"abc"}""")
    assertTrue(errorOf(unknownField)!!.contains("unknown field"), "got [$unknownField]")

    assertTrue(
      MoneyProfilesDao.findActiveByStudent(CostsTestDb.sqlSession, student).isFailure,
      "a malformed call must not create any row",
    )
  }

  @Test
  fun `an oversized college_ids array is a structured error naming the cap and the given size`() {
    val student = createStudent()
    val ids = (0..StudentScopedChatTool.MAX_COLLEGE_IDS).joinToString(",") { "\"${UUID.randomUUID()}\"" }

    val result = execute(student, """{"college_ids":[$ids]}""")
    assertEquals(
      "college_ids must contain at most [${StudentScopedChatTool.MAX_COLLEGE_IDS}] entries, " +
        "got [${StudentScopedChatTool.MAX_COLLEGE_IDS + 1}]",
      errorOf(result),
    )
  }

  @Test
  fun `duplicate college_ids are silently read once, as the schema documents`() {
    val student = createStudent()
    val mine = seedListedCollege(student, "Dup U")

    val result = execute(student, """{"college_ids":["${mine.value}","${mine.value}"]}""")
    assertNull(errorOf(result), "got [$result]")
    assertEquals(listOf("Dup U"), collegesOf(result).map { it["name"]!!.jsonPrimitive.content })
    assertNull(result["unknown_college_ids"], "a duplicate of a known id is not unknown")

    val description =
      tool.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject["college_ids"]!!
        .jsonObject["description"]!!
        .jsonPrimitive.content
    assertTrue(description.contains("duplicate ids are read once"), "the dedup tolerance must be documented: [$description]")
    assertTrue(
      description.contains("At most ${StudentScopedChatTool.MAX_COLLEGE_IDS}"),
      "the cap must be documented: [$description]",
    )
  }

  // ---------------------------------------------------------------------------
  // The merit-aid feed (RFC 148 D7) -- purely additive
  // ---------------------------------------------------------------------------

  @Test
  fun `a cost answer with no merit row is unchanged`() {
    val student = createStudent()
    seedListedCollege(student, "No Merit U")

    val college = collegesOf(execute(student)).single()
    assertNull(college[MeritAidWire.KEY], "an absent key, never an empty object")
    // "Unchanged" is the whole key vocabulary, not the one key the author had
    // in mind: a key the merit feed adds ANYWHERE in the college object fails
    // here. Derived from CostField, so a future cost field costs no test edit.
    assertEquals(
      emptySet(),
      college.keys - PRE_FEED_COLLEGE_KEYS,
      "a college with no merit row renders exactly the keys it rendered before the feed: [$college]",
    )
    // The silence belongs to the other source, so it must NOT join
    // data_availability, whose vocabulary is the Scorecard's cost fields.
    assertFalse(
      dataAvailabilityOf(college).contains(MeritAidWire.KEY),
      "merit silence is not a Scorecard silence: [$college]",
    )
  }

  @Test
  fun `a merit row carrying only the freshman total leaves the cost answer unchanged`() {
    // The 28-of-368 corpus shape (RFC 148 D4): a freshman total and neither
    // merit measure. Both tools read the same MeritPractice, so both must call
    // it silence -- a cost answer showing full_time_freshmen_headcount under a
    // Common Data Set citation with no merit fact beneath it is the same defect
    // here.
    val student = createStudent()
    val college = seedListedCollege(student, "Denominator Only U")
    CostsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2760,
      noNeedMeritRecipientsHeadcount = null,
      noNeedMeritAverageUsd = null,
    )

    val rendered = collegesOf(execute(student)).single()
    assertNull(rendered[MeritAidWire.KEY], "a denominator with no merit measure is not a merit section")
    assertEquals(
      emptySet(),
      rendered.keys - PRE_FEED_COLLEGE_KEYS,
      "the answer is exactly the one this college gave before the feed existed: [$rendered]",
    )
  }

  @Test
  fun `a merit sentence never requires the money profile`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Merit U")
    CostsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )

    // No income band, no residency -- the whole money profile unanswered.
    val merit = collegesOf(execute(student)).single()[MeritAidWire.KEY]!!.jsonObject
    assertEquals(
      25.0,
      merit
        .getValue(MeritAidWire.SHARE_KEY)
        .jsonPrimitive.content
        .toDouble(),
    )
    assertEquals(
      "25% of all full-time freshmen received non-need (merit) aid",
      merit.getValue("share_label").jsonPrimitive.content,
    )
  }

  // ---------------------------------------------------------------------------
  // The aid-policy section (RFC 170)
  // ---------------------------------------------------------------------------

  @Test
  fun `the aid policy section names its cohort in every sentence it emits`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Need Met U")
    CostsTestDb.seedAidPolicy(college)

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    assertEquals(
      94.3,
      policy
        .getValue(AidPolicyWire.NEED_MET_SHARE_KEY)
        .jsonPrimitive.content
        .toDouble(),
    )
    // 300 of 800 -- derived at read time from the two stored counts, and said
    // with the population it is over inside the sentence.
    assertEquals(
      37.5,
      policy
        .getValue(AidPolicyWire.FULLY_MET_SHARE_KEY)
        .jsonPrimitive.content
        .toDouble(),
    )
    // Both counts, and the cohort they are over, inside the sentence: the
    // fully-met count is reported against the freshmen who received ANY aid
    // (CDS H2 line d), which is neither "freshmen" nor the need-based-grant
    // recipients the two averages are over.
    assertEquals(
      "of the 800 first-time full-time freshmen who received any financial aid, 300 had their " +
        "full assessed need met -- 37.5%",
      policy.getValue(AidPolicyWire.FULLY_MET_LABEL_KEY).jsonPrimitive.content,
    )
    assertTrue(
      policy
        .getValue(AidPolicyWire.NEED_MET_LABEL_KEY)
        .jsonPrimitive.content
        .contains("for the freshmen who received need-based aid"),
    )
    assertEquals(
      800,
      policy
        .getValue(AidPolicyWire.AIDED_FRESHMEN_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(
      300,
      policy
        .getValue(AidPolicyWire.FULLY_MET_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(
      "Need Met U's 2024-25 Common Data Set",
      policy
        .getValue("source")
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content,
    )
    // D6: no stored yes/no about meeting full need, and none on the wire.
    assertFalse(policy.keys.any { it.contains("meets_full_need") }, "keys: ${policy.keys}")
  }

  @Test
  fun `the fully-met share is emitted only when both of its counts exist`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Half Reported U")
    CostsTestDb.seedAidPolicy(college, freshmenAwardedAnyAid = null)

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    // The average share of need met still stands -- it is an independent fact.
    assertEquals(
      94.3,
      policy
        .getValue(AidPolicyWire.NEED_MET_SHARE_KEY)
        .jsonPrimitive.content
        .toDouble(),
    )
    // The derived share and both counts go together or not at all: a share
    // without its denominator is the figure this codebase refuses to publish.
    assertNull(policy[AidPolicyWire.FULLY_MET_SHARE_KEY])
    assertNull(policy[AidPolicyWire.FULLY_MET_LABEL_KEY])
    assertNull(policy[AidPolicyWire.AIDED_FRESHMEN_KEY])
    assertNull(policy[AidPolicyWire.FULLY_MET_KEY])
    // The average grant is independent too, and its sentence names the
    // recipients it is averaged over -- the claim the sibling test makes about
    // every emitted figure, checked here for the one figure left standing.
    assertEquals(
      "the freshmen who received a need-based grant got \$18,007 in grant aid on average",
      policy.getValue(AidPolicyWire.AVERAGE_GRANT_LABEL_KEY).jsonPrimitive.content,
    )
  }

  @Test
  fun `a form the school's CDS does not list is absent, and the note says what that means`() {
    val student = createStudent()
    val college = seedListedCollege(student, "One Form U")
    CostsTestDb.seedAidPolicy(college, requiredForms = listOf(AidForm.FAFSA))

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    assertEquals(
      listOf("fafsa"),
      policy.getValue(AidPolicyWire.FORMS_KEY).jsonArray.map { it.jsonPrimitive.content },
    )
    // D5: the CSS Profile is simply not listed. Nothing on the wire says it is
    // not required, and the note says so in words.
    val note = policy.getValue(AidPolicyWire.FORMS_NOTE_KEY).jsonPrimitive.content
    assertTrue(note.contains("is not listed in that filing"), note)
    assertFalse(policy.toString().contains("not_required"), policy.toString())
  }

  @Test
  fun `a form required only of international applicants never reaches a domestic family`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Two Group U")
    // The D4 guarantee: applicant group is a domain axis, not a source shape.
    // This school requires the CSS Profile of international applicants only,
    // and a family in the other group must never be told to file it.
    CostsTestDb.seedAidPolicy(
      college,
      requiredForms = listOf(AidForm.FAFSA),
      nonresidentForms = listOf(AidForm.CSS_PROFILE),
    )

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    assertEquals(
      listOf("fafsa"),
      policy.getValue(AidPolicyWire.FORMS_KEY).jsonArray.map { it.jsonPrimitive.content },
      "the nonresident block is a different requirement, not this family's",
    )
  }

  @Test
  fun `a form the filing answers and we could not read is said as OUR gap, not as the school's silence`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Unread Form U")
    CostsTestDb.seedAidPolicy(
      college,
      requiredForms = listOf(AidForm.FAFSA),
      notCollectedForms = listOf(AidForm.CSS_PROFILE),
    )

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    // D7: the filing DOES answer the CSS Profile. Saying "not listed in that
    // filing" about it would report our own gap as the school's silence --
    // the inversion the status vocabulary exists to prevent.
    assertEquals(
      listOf("css_profile"),
      policy.getValue(AidPolicyWire.FORMS_NOT_COLLECTED_KEY).jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      listOf("fafsa"),
      policy.getValue(AidPolicyWire.FORMS_KEY).jsonArray.map { it.jsonPrimitive.content },
    )
    val note = policy.getValue(AidPolicyWire.FORMS_NOT_COLLECTED_NOTE_KEY).jsonPrimitive.content
    assertTrue(note.contains("we could not read"), note)
    assertTrue(note.contains("never that the school does not require them"), note)
  }

  @Test
  fun `a form the corpus failed to extract is not listed as required`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Our Gap U")
    // D7: the row exists and states OUR gap (not_collected_by_us, no value).
    // It is not the school saying the form is required, so the read must not
    // put it in front of a family as one.
    CostsTestDb.seedAidPolicy(
      college,
      requiredForms = listOf(AidForm.FAFSA),
      notCollectedForms = listOf(AidForm.NONCUSTODIAL_CSS_PROFILE),
    )

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    assertEquals(
      listOf("fafsa"),
      policy.getValue(AidPolicyWire.FORMS_KEY).jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `a school with no CDS filing says so, in our own voice`() {
    val student = createStudent()
    seedListedCollege(student, "No Filing U")

    val college = collegesOf(execute(student)).single()
    assertNull(college[AidPolicyWire.KEY])
    val line = college.getValue(CollegeCostChatTool.AID_POLICY_AVAILABILITY_KEY).jsonPrimitive.content
    assertTrue(line.contains("no Common Data Set filing"), line)
  }

  @Test
  fun `a filing that reports nothing we can read is a different sentence from no filing at all`() {
    val student = createStudent()
    val noFiling = seedListedCollege(student, "No Filing U")
    val emptyFiling = seedListedCollege(student, "Silent Filing U")
    // A real school in the committed seed: its Common Data Set is in the
    // corpus and reports one line-d headcount and nothing else, so there is
    // nothing renderable under the citation. Telling a family "we hold no
    // filing for this school" would be a false statement about OUR coverage.
    CostsTestDb.seedAidPolicy(
      emptyFiling,
      averageNeedMet = null,
      averageNeedBasedGrantUsd = null,
      freshmenNeedFullyMet = null,
      requiredForms = emptyList(),
    )

    val byName = collegesOf(execute(student)).associateBy { it.getValue("name").jsonPrimitive.content }
    val absent = byName.getValue("No Filing U")
    val silent = byName.getValue("Silent Filing U")
    assertNull(absent[AidPolicyWire.KEY])
    assertNull(silent[AidPolicyWire.KEY])
    assertEquals(
      CollegeCostChatTool.AID_POLICY_NO_FILING,
      absent.getValue(CollegeCostChatTool.AID_POLICY_AVAILABILITY_KEY).jsonPrimitive.content,
    )
    assertEquals(
      CollegeCostChatTool.AID_POLICY_NO_FACT_IN_FILING,
      silent.getValue(CollegeCostChatTool.AID_POLICY_AVAILABILITY_KEY).jsonPrimitive.content,
    )
    assertTrue(
      CollegeCostChatTool.AID_POLICY_NO_FACT_IN_FILING.contains("We hold this school's Common Data Set filing"),
      "the second silence must say we DO hold the filing",
    )
  }

  @Test
  fun `a filing whose only row is one we could not read still speaks, as our own gap`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Our Gap Only U")
    // D7 at the section level: a filing carrying nothing but
    // not_collected_by_us rows has no figure and no requirement -- but it does
    // have something true to say, which is WHICH form we could not read. That
    // is a fact about our coverage, and it is the section, not a silence.
    CostsTestDb.seedAidPolicy(
      college,
      averageNeedMet = null,
      averageNeedBasedGrantUsd = null,
      freshmenAwardedAnyAid = null,
      freshmenNeedFullyMet = null,
      requiredForms = emptyList(),
      notCollectedForms = listOf(AidForm.NONCUSTODIAL_CSS_PROFILE),
    )

    val rendered = collegesOf(execute(student)).single()
    val policy = rendered[AidPolicyWire.KEY]!!.jsonObject
    assertNull(rendered[CollegeCostChatTool.AID_POLICY_AVAILABILITY_KEY], "the filing exists, so no absence line")
    assertEquals(
      listOf("noncustodial_css_profile"),
      policy.getValue(AidPolicyWire.FORMS_NOT_COLLECTED_KEY).jsonArray.map { it.jsonPrimitive.content },
    )
    // Nothing is claimed as required, and no figure is invented.
    assertNull(policy[AidPolicyWire.FORMS_KEY])
    assertNull(policy[AidPolicyWire.NEED_MET_SHARE_KEY])
    // And no tier either: a tier states what kind of number a figure is, and
    // there is no figure here. Defaulting one to the softest because the section
    // is a school's own filing would be asserting it by position on the page
    // (RFC 179).
    assertNull(policy[AidPolicyWire.NEED_MET_ASSURANCE_KEY])
    assertNull(policy[AidPolicyWire.NEED_MET_ASSURANCE_STATEMENT_KEY])
    assertNull(policy[AidPolicyWire.AVERAGE_GRANT_ASSURANCE_KEY])
  }

  @Test
  fun `the two Common Data Set averages carry the softest tier, resolved from the row's own field id`() {
    // The slice's first-session test: "does Amherst meet full financial need?"
    // is answered from this section, and the answer now carries "the college
    // published this about itself; no one checks it" as part of the sentence
    // rather than as a warning box (RFC 179).
    val student = createStudent()
    val college = seedListedCollege(student, "Self Reported U")
    CostsTestDb.seedAidPolicy(college)

    val policy =
      collegesOf(execute(student))
        .single()[AidPolicyWire.KEY]!!
        .jsonObject
    // H.209 -- the average percent of need met -- and H.211, the average
    // need-based grant. Both are the school's own unaudited filing, and the
    // tier is read off each ROW's `(source, source_variable)` pair rather than
    // asserted from the section's name.
    listOf(
      AidPolicyWire.NEED_MET_ASSURANCE_KEY to AidPolicyWire.NEED_MET_ASSURANCE_STATEMENT_KEY,
      AidPolicyWire.AVERAGE_GRANT_ASSURANCE_KEY to AidPolicyWire.AVERAGE_GRANT_ASSURANCE_STATEMENT_KEY,
    ).forEach { (assuranceKey, statementKey) ->
      assertEquals(
        AssuranceTier.VOLUNTARY_SELF_REPORT.value,
        policy.getValue(assuranceKey).jsonPrimitive.content,
        "[$assuranceKey]",
      )
      assertEquals(
        AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT),
        policy.getValue(statementKey).jsonPrimitive.content,
        "the sentence is the domain's own, never re-worded here: [$statementKey]",
      )
    }
    // The OTHER half of the pair rule, which is a declared ABSENCE and so the
    // half that rots silently: a reported Common Data Set figure carries a TIER
    // and says NOTHING about its status. A status sentence added beside these
    // averages later must fail here.
    val statusWords =
      FigureStatus.entries.mapNotNull { FigureStatusCopy.statementOf(it, MoneySource.COMMON_DATA_SET) }
    assertTrue(
      policy.values.none { value -> statusWords.any { value.toString().contains(it) } },
      "a reported Common Data Set figure says nothing about its status: [$policy]",
    )
    // And no status-shaped KEY either, so a new one is caught by name rather
    // than only by the words it happens to carry.
    assertTrue(
      policy.keys.none { it.endsWith("_status") || it.endsWith("_status_statement") },
      "the aid-policy section carries no figure status, only a tier: keys=[${policy.keys}]",
    )
    // The headcounts get no tier: they reach the wire only through a derived
    // share, whose tier would be a claim about two rows at once.
    assertFalse(
      policy.keys.any { it.startsWith(AidPolicyWire.FULLY_MET_SHARE_KEY) && it.contains("assurance") },
      "keys=[${policy.keys}]",
    )
  }

  // ---------------------------------------------------------------------------
  // The borrowing section (RFC 175)
  // ---------------------------------------------------------------------------

  @Test
  fun `the borrowing section names its cohort, its year and the school claiming it`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Borrower U")
    CostsTestDb.seedBorrowing(college)

    val borrowing =
      collegesOf(execute(student))
        .single()[BorrowingWire.KEY]!!
        .jsonObject
    assertEquals(
      20747,
      borrowing
        .getValue(BorrowingWire.averageDebtKey(LoanType.FEDERAL))
        .jsonPrimitive.content
        .toInt(),
    )
    // 394 of 1000, derived at read time from two stored counts and said with
    // both of them inside the sentence.
    assertEquals(
      39.4,
      borrowing
        .getValue(BorrowingWire.shareKey(LoanType.FEDERAL))
        .jsonPrimitive.content
        .toDouble(),
    )
    assertEquals(
      "Borrower U reports that 394 of the 1000 students who graduated in 2024-25 borrowed federal loans " +
        "-- 39.4%",
      borrowing.getValue(BorrowingWire.shareLabelKey(LoanType.FEDERAL)).jsonPrimitive.content,
    )
    assertEquals(
      "Borrower U reports that the students who graduated in 2024-25 and borrowed federal loans owed " +
        "\$20,747 on average by the time they graduated",
      borrowing.getValue(BorrowingWire.averageDebtLabelKey(LoanType.FEDERAL)).jsonPrimitive.content,
    )
    // D8: the cohort is named in words, with its year, and it is neither this
    // year's freshmen nor every undergraduate.
    val cohort = borrowing.getValue(BorrowingWire.COHORT_LABEL_KEY).jsonPrimitive.content
    assertTrue(cohort.contains("graduated from it in 2024-25"), cohort)
    assertEquals(
      "Borrower U's 2024-25 Common Data Set",
      borrowing
        .getValue("source")
        .jsonObject
        .getValue("cited_as")
        .jsonPrimitive.content,
    )
  }

  @Test
  fun `every borrowing sentence names the school as the one claiming the figure`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Self Reported U")
    CostsTestDb.seedBorrowing(
      college,
      averageDebtUsdByLoanType = LoanType.entries.associateWith { 20000 },
      borrowersByLoanType = LoanType.entries.associateWith { 400 },
    )

    val borrowing =
      collegesOf(execute(student))
        .single()[BorrowingWire.KEY]!!
        .jsonObject
    // D10: these are self-reported survey answers with no audit, so no
    // sentence carrying a figure may be said in the flat voice an
    // administrative figure is said in. Asserted over EVERY string the section
    // can emit, not over the ones this test happened to think of.
    val spoken =
      borrowing
        .filterValues { it is JsonPrimitive && it.isString }
        .filterKeys { it != BorrowingWire.NOTE_KEY }
        .mapValues { (_, value) -> value.jsonPrimitive.content }
    assertTrue(spoken.size >= LoanType.entries.size, "expected a sentence per loan type: $spoken")
    val unattributed = spoken.filterValues { it.contains(Regex("""\d""")) && !it.contains("Self Reported U") }
    assertEquals(emptyMap(), unattributed, "a CDS figure stated without naming the school as the claimant")
  }

  @Test
  fun `the any-loan figure is the school's own, never a sum of the loan types`() {
    val student = createStudent()
    val college = seedListedCollege(student, "No Sums U")
    // D3: the four typed sets OVERLAP -- a student may borrow federally and
    // privately -- so the school's own any-loan figure is deliberately smaller
    // than the sum of the others, and a payload that added them would show it.
    CostsTestDb.seedBorrowing(
      college,
      averageDebtUsdByLoanType =
        mapOf(LoanType.ANY to 30000, LoanType.FEDERAL to 20000, LoanType.PRIVATE to 40000),
      borrowersByLoanType = mapOf(LoanType.ANY to 500, LoanType.FEDERAL to 450, LoanType.PRIVATE to 200),
    )

    val borrowing =
      collegesOf(execute(student))
        .single()[BorrowingWire.KEY]!!
        .jsonObject
    assertEquals(
      30000,
      borrowing
        .getValue(BorrowingWire.averageDebtKey(LoanType.ANY))
        .jsonPrimitive.content
        .toInt(),
      "the any-loan average is the school's own figure",
    )
    assertEquals(
      500,
      borrowing
        .getValue(BorrowingWire.borrowerCountKey(LoanType.ANY))
        .jsonPrimitive.content
        .toInt(),
      "the any-loan count is the school's own figure",
    )
    // No key anywhere holds a sum or a difference of two loan types, and the
    // note tells the model not to make one.
    val numbers =
      borrowing
        .filterValues { it is JsonPrimitive && !it.isString }
        .mapValues { (_, value) -> value.jsonPrimitive.content.toDouble() }
        .values
        .toSet()
    listOf(60000.0, 70000.0, 90000.0, 650.0, 950.0, 1150.0).forEach {
      assertFalse(it in numbers, "a summed loan figure reached the payload: $numbers")
    }
    assertTrue(
      borrowing
        .getValue(BorrowingWire.NOTE_KEY)
        .jsonPrimitive.content
        .contains("never add two of them"),
    )
  }

  @Test
  fun `a school that filed a federal figure and no private one says so`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Federal Only U")
    CostsTestDb.seedBorrowing(
      college,
      averageDebtUsdByLoanType = mapOf(LoanType.FEDERAL to 20747),
      borrowersByLoanType = mapOf(LoanType.FEDERAL to 394),
    )

    val borrowing =
      collegesOf(execute(student))
        .single()[BorrowingWire.KEY]!!
        .jsonObject
    assertEquals(
      20747,
      borrowing
        .getValue(BorrowingWire.averageDebtKey(LoanType.FEDERAL))
        .jsonPrimitive.content
        .toInt(),
    )
    // No private key at all -- absence is silence, never a zero.
    assertNull(borrowing[BorrowingWire.averageDebtKey(LoanType.PRIVATE)])
    assertNull(borrowing[BorrowingWire.borrowerCountKey(LoanType.PRIVATE)])
    assertNull(borrowing[BorrowingWire.shareKey(LoanType.PRIVATE)])
    // And the silence is said in words, so the federal figure is not left
    // standing as the whole of what this school's graduates borrowed.
    val line = borrowing.getValue(BorrowingWire.PRIVATE_NOT_FILED_KEY).jsonPrimitive.content
    assertTrue(line.contains("reports no average for private loans"), line)
    assertTrue(line.contains("Federal Only U"), line)
  }

  @Test
  fun `a readable federal average beside an unread private cell says OUR gap for private`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Half Read U")
    // The mixed filing: federal read, private ANSWERED by the school and
    // unreadable by us. The old filing-wide boolean was consulted only when
    // nothing was readable, so this school said "reports no average for
    // private loans" -- our failure in the school's mouth (D7).
    CostsTestDb.seedBorrowing(
      college,
      averageDebtUsdByLoanType = mapOf(LoanType.FEDERAL to 20747),
      borrowersByLoanType = mapOf(LoanType.FEDERAL to 394),
      unreadLoanTypes = setOf(LoanType.PRIVATE),
    )

    val result = collegesOf(execute(student)).single()
    val borrowing = result[BorrowingWire.KEY]!!.jsonObject
    // The readable half still renders: a partly unread filing is a filing with
    // figures, and a family is owed the ones we hold.
    assertEquals(
      20747,
      borrowing
        .getValue(BorrowingWire.averageDebtKey(LoanType.FEDERAL))
        .jsonPrimitive.content
        .toInt(),
    )
    // And the unread half is said as OURS, per loan type.
    val ours = borrowing.getValue(BorrowingWire.notReadKey(LoanType.PRIVATE)).jsonPrimitive.content
    assertTrue(ours.contains("WE could not read"), ours)
    assertTrue(ours.contains("Half Read U"), ours)
    assertTrue(ours.contains("never that this school does not report it"), ours)
    // The claim about the SCHOOL's filing is withheld: it is not true here.
    assertNull(
      borrowing[BorrowingWire.PRIVATE_NOT_FILED_KEY],
      "a cell we could not read must never be spoken as a cell the school did not file",
    )
    // No private FIGURE is invented, and no unread key is emitted for the
    // loan type we did read.
    assertNull(borrowing[BorrowingWire.averageDebtKey(LoanType.PRIVATE)])
    assertNull(borrowing[BorrowingWire.notReadKey(LoanType.FEDERAL)])
    // The section is present, so the whole-filing silences do not apply.
    assertNull(result[CollegeCostChatTool.BORROWING_AVAILABILITY_KEY])
  }

  @Test
  fun `a borrower count with no graduating class yields the average and no share`() {
    val student = createStudent()
    val college = seedListedCollege(student, "No Denominator U")
    CostsTestDb.seedBorrowing(college, graduatingClass = null)

    val borrowing =
      collegesOf(execute(student))
        .single()[BorrowingWire.KEY]!!
        .jsonObject
    // Acceptance criterion (c): the average is an independent fact and still
    // stands.
    assertEquals(
      20747,
      borrowing
        .getValue(BorrowingWire.averageDebtKey(LoanType.FEDERAL))
        .jsonPrimitive.content
        .toInt(),
    )
    // The share and both of its counts go together or not at all: a share
    // without its denominator is the figure this codebase refuses to publish.
    assertNull(borrowing[BorrowingWire.shareKey(LoanType.FEDERAL)])
    assertNull(borrowing[BorrowingWire.shareLabelKey(LoanType.FEDERAL)])
    assertNull(borrowing[BorrowingWire.borrowerCountKey(LoanType.FEDERAL)])
    assertNull(borrowing[BorrowingWire.GRADUATING_CLASS_KEY])
  }

  @Test
  fun `the four borrowing silences are four sentences, and none of them is a cost field`() {
    val student = createStudent()
    val noFiling = seedListedCollege(student, "No Filing U")
    val noBorrowing = seedListedCollege(student, "Need Only U")
    val unread = seedListedCollege(student, "Unread Cell U")
    val withheld = seedListedCollege(student, "Withheld Cell U")
    // A filing we hold that reports need figures and no borrowing block.
    CostsTestDb.seedAidPolicy(noBorrowing)
    // A filing whose borrowing cells we could not read: the rows ARE there,
    // under this school's document, carrying no value (`not_collected_by_us`).
    // Seeding no row at all would make this the no-block case under a
    // different name, and D7's guard would never fire.
    CostsTestDb.seedBorrowing(
      unread,
      graduatingClass = null,
      averageDebtUsdByLoanType = emptyMap(),
      borrowersByLoanType = emptyMap(),
      unreadLoanTypes = setOf(LoanType.FEDERAL, LoanType.PRIVATE),
    )
    CostsTestDb.seedAidPolicy(unread)
    // A filing whose borrowing cells the PUBLISHER withheld: value-less rows
    // again, and only the STATUS tells them from the unread ones above.
    CostsTestDb.seedBorrowing(
      withheld,
      graduatingClass = null,
      averageDebtUsdByLoanType = emptyMap(),
      borrowersByLoanType = emptyMap(),
      withheldLoanTypes = setOf(LoanType.FEDERAL, LoanType.PRIVATE),
    )
    CostsTestDb.seedAidPolicy(withheld)

    val byName = collegesOf(execute(student)).associateBy { it.getValue("name").jsonPrimitive.content }

    // A CODE beside the sentence, not the sentence alone: whose silence a
    // school's is (D7) is the fact this surface turns on, and read out of
    // English copy it is not a fact anyone can act on.
    fun availabilityOf(name: String): Pair<String, String> {
      val availability = byName.getValue(name).getValue(CollegeCostChatTool.BORROWING_AVAILABILITY_KEY).jsonObject
      return availability.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content to
        availability.getValue(CollegeCostChatTool.STATEMENT_KEY).jsonPrimitive.content
    }
    assertEquals(
      CollegeCostChatTool.BORROWING_NO_FILING_BASIS to CollegeCostChatTool.BORROWING_NO_FILING,
      availabilityOf("No Filing U"),
    )
    assertEquals(
      CollegeCostChatTool.BORROWING_NO_FACT_IN_FILING_BASIS to CollegeCostChatTool.BORROWING_NO_FACT_IN_FILING,
      availabilityOf("Need Only U"),
    )
    assertEquals(
      CollegeCostChatTool.BORROWING_NOT_READ_BY_US_BASIS to CollegeCostChatTool.BORROWING_NOT_READ_BY_US,
      availabilityOf("Unread Cell U"),
      "an unread cell is OUR gap and is never spoken as the school's silence (RFC 175 D7)",
    )
    // A filing whose borrowing cells the PUBLISHER withheld is neither of the
    // first two: told as the school's silence it says this school does not
    // report a figure it does report, and told as ours it blames us for a
    // suppression that is not our doing.
    assertEquals(
      CollegeCostChatTool.BORROWING_WITHHELD_BASIS to CollegeCostChatTool.BORROWING_WITHHELD_BY_PUBLISHER,
      availabilityOf("Withheld Cell U"),
    )
    // The sentences are as many as the silences, not one constant used four
    // times: a second silence wearing the first's words is the D7 defect
    // itself. The same for the codes.
    assertEquals(
      4,
      setOf(
        CollegeCostChatTool.BORROWING_NO_FILING,
        CollegeCostChatTool.BORROWING_NO_FACT_IN_FILING,
        CollegeCostChatTool.BORROWING_NOT_READ_BY_US,
        CollegeCostChatTool.BORROWING_WITHHELD_BY_PUBLISHER,
      ).size,
    )
    assertEquals(
      5,
      setOf(
        CollegeCostChatTool.BORROWING_NO_FILING_BASIS,
        CollegeCostChatTool.BORROWING_NO_FACT_IN_FILING_BASIS,
        CollegeCostChatTool.BORROWING_NOT_READ_BY_US_BASIS,
        CollegeCostChatTool.BORROWING_WITHHELD_BASIS,
        CollegeCostChatTool.BORROWING_NOT_APPLICABLE_BASIS,
      ).size,
    )
    // None of the three enters data_availability: that list speaks the
    // Scorecard-shaped CostField vocabulary, and folding a CDS silence into it
    // misattributes whose silence it is.
    byName.values.forEach { college ->
      assertFalse(
        dataAvailabilityOf(college).any { it.contains("borrow") || it.contains("loan") || it.contains("graduating") },
        "a CDS borrowing silence reached data_availability: ${dataAvailabilityOf(college)}",
      )
      assertNull(college[BorrowingWire.KEY])
    }
  }

  @Test
  fun `a filing whose only borrowing cells contradict each other is OUR withholding, not the school's silence`() {
    val student = createStudent()
    val contradicted = seedListedCollege(student, "Contradicted Count U")
    // The live shape D6 names: ipeds 166629 reports 39 graduates and 2588
    // federal borrowers. The pair is held and never spoken -- but the filing
    // ANSWERED two borrowing cells, so "it reports none of the borrowing
    // figures" is false about it.
    CostsTestDb.seedBorrowing(
      contradicted,
      graduatingClass = 39,
      averageDebtUsdByLoanType = emptyMap(),
      borrowersByLoanType = mapOf(LoanType.FEDERAL to 2588),
    )

    val college =
      collegesOf(execute(student)).single { it.getValue("name").jsonPrimitive.content == "Contradicted Count U" }
    val availability = college.getValue(CollegeCostChatTool.BORROWING_AVAILABILITY_KEY).jsonObject
    assertEquals(
      CollegeCostChatTool.BORROWING_NOT_RECONCILED_BY_US_BASIS,
      availability.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
      "a contradicted pair is OURS to withhold (D6), and it has its own basis",
    )
    assertEquals(
      CollegeCostChatTool.BORROWING_NOT_RECONCILED_BY_US,
      availability.getValue(CollegeCostChatTool.STATEMENT_KEY).jsonPrimitive.content,
    )
    // The defect this arm exists to prevent: the plain sentence tells a family
    // this school reports none of the borrowing figures, about a school that
    // reported two of them.
    assertNotEquals(
      CollegeCostChatTool.BORROWING_NO_FACT_IN_FILING,
      availability.getValue(CollegeCostChatTool.STATEMENT_KEY).jsonPrimitive.content,
    )
    // Six silences, six codes: a sixth wearing a fifth's words is the D7
    // defect with the reasons reversed.
    assertEquals(
      6,
      setOf(
        CollegeCostChatTool.BORROWING_NO_FILING_BASIS,
        CollegeCostChatTool.BORROWING_NO_FACT_IN_FILING_BASIS,
        CollegeCostChatTool.BORROWING_NOT_READ_BY_US_BASIS,
        CollegeCostChatTool.BORROWING_WITHHELD_BASIS,
        CollegeCostChatTool.BORROWING_NOT_APPLICABLE_BASIS,
        CollegeCostChatTool.BORROWING_NOT_RECONCILED_BY_US_BASIS,
      ).size,
    )
    // And neither cell of the pair is spoken: no section, and no count.
    assertNull(college[BorrowingWire.KEY])
    assertFalse(college.toString().contains("2588"), "a contradicted borrower count reached the payload")
  }

  @Test
  fun `the borrowing feed carries no bare source code into the cost result`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Guarded Borrowing U")
    CostsTestDb.seedBorrowing(college)

    val payload = execute(student)
    // The CDS field ids (H.401, H.511, ...) live in source_variable and must
    // never reach a tool result, and neither may a publisher's own measure slug.
    assertEquals(emptyList(), listViolations(payload))
    assertFalse(payload.toString().contains("H.4"), "a CDS field id reached the result: $payload")
    assertFalse(payload.toString().contains("H.5"), "a CDS field id reached the result: $payload")
    assertFalse(
      payload.toString().contains("GRAD_DEBT_MDN"),
      "a publisher's own variable name reached the result: $payload",
    )
  }

  @Test
  fun `the aid policy feed carries no bare source code into the cost result`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Guarded Policy U")
    CostsTestDb.seedAidPolicy(college)

    val payload = execute(student)
    // The CDS field ids (H.209, H.801, ...) live in source_variable and must
    // never reach a tool result; the guard also catches any bare number that
    // slipped in without a contract.
    assertEquals(emptyList(), listViolations(payload))
    assertFalse(payload.toString().contains("H.2"), "a CDS field id reached the result: $payload")
    assertFalse(payload.toString().contains("H.8"), "a CDS field id reached the result: $payload")
  }

  @Test
  fun `merit_aid rides its own citation, not the Scorecard source string`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Cited Merit U")
    CostsTestDb.seedMeritAid(college)

    val payload = execute(student)
    val source = payload.getValue("source").jsonPrimitive.content
    assertTrue(source.contains("College Scorecard"), "the payload attribution stays the Scorecard's: [$source]")
    assertFalse(source.contains("Common Data Set"), "and must not absorb a second source: [$source]")
    val citation =
      collegesOf(payload)
        .single()[MeritAidWire.KEY]!!
        .jsonObject
        .getValue("source")
        .jsonObject
    assertEquals("Cited Merit U's 2024-25 Common Data Set", citation.getValue("cited_as").jsonPrimitive.content)
  }

  @Test
  fun `every college on a multi-college list carries its own merit answer`() {
    val student = createStudent()
    val first = seedListedCollege(student, "Merit One")
    val second = seedListedCollege(student, "Merit Two")
    seedListedCollege(student, "Merit None")
    CostsTestDb.seedMeritAid(first, noNeedMeritAverageUsd = 1000)
    CostsTestDb.seedMeritAid(second, noNeedMeritAverageUsd = 2000)

    val byName = collegesOf(execute(student)).associateBy { it.getValue("name").jsonPrimitive.content }
    assertEquals(3, byName.size)
    assertEquals(
      1000,
      byName
        .getValue("Merit One")[MeritAidWire.KEY]!!
        .jsonObject
        .getValue(MeritAidWire.AVERAGE_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertEquals(
      2000,
      byName
        .getValue("Merit Two")[MeritAidWire.KEY]!!
        .jsonObject
        .getValue(MeritAidWire.AVERAGE_KEY)
        .jsonPrimitive.content
        .toInt(),
    )
    assertNull(byName.getValue("Merit None")[MeritAidWire.KEY])
  }

  @Test
  fun `the merit feed carries no bare source code into the cost result`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Guarded Merit U")
    CostsTestDb.seedMeritAid(
      college,
      firstTimeFullTimeFreshmenHeadcount = 2000,
      noNeedMeritRecipientsHeadcount = 500,
      noNeedMeritAverageUsd = 12500,
    )
    answerBand(student, IncomeBand.OVER_110K)

    val payload = execute(student)
    assertEquals(emptyList(), listViolations(payload), "the merit sub-object must carry no source code")
    assertFalse(payload.toString().contains("without need"), "never a share of freshmen without need")
  }

  // ---------------------------------------------------------------------------
  // The living-arrangement breakdown on the wire (RFC 149)
  // ---------------------------------------------------------------------------

  private fun breakdownOf(college: JsonObject): JsonObject? = college[CollegeCostChatTool.BREAKDOWN_KEY]?.jsonObject

  private fun arrangementOf(
    college: JsonObject,
    arrangement: LivingArrangement,
  ): JsonObject? = breakdownOf(college)?.get(arrangement.value)?.jsonObject

  /**
   * The dollar lines of one arrangement, by key.
   *
   * Non-scalar entries are skipped rather than parsed: an arrangement may carry
   * [CollegeCostChatTool.ASSUMED_BY_UNICOACH_KEY], an ARRAY of the keys whose
   * amount is ours (RFC 166 §7), and this helper is asked "what are the
   * numbers". The key itself is asserted where it belongs, on the at-home
   * arrangement, so skipping it here hides nothing.
   */
  private fun intsOf(arrangement: JsonObject): Map<String, Int> =
    arrangement
      .filterValues { it is JsonPrimitive }
      .mapValues {
        it.value.jsonPrimitive.content
          .toInt()
      }

  /**
   * One figure group's spoken academic year, read from the object the key now
   * carries (RFC 149 D-E).
   *
   * The group no longer knows the year -- it is DATA on the rows this school
   * served (RFC 166 §3), so the expectation is the FIXTURE's year
   * ([CostsTestDb.PRICE_ACADEMIC_YEAR]) rather than a constant on an enum.
   */
  private fun academicYearOf(
    college: JsonObject,
    vintage: FigureGroup,
  ): String? =
    college[vintage.wireName]
      ?.jsonObject
      ?.getValue(CollegeCostChatTool.ACADEMIC_YEAR_KEY)
      ?.jsonPrimitive
      ?.content

  /** The wire names one vintage says it dates -- the membership the payload now states in-band. */
  private fun datedFiguresOf(
    college: JsonObject,
    vintage: FigureGroup,
  ): List<String> =
    college[vintage.wireName]
      ?.jsonObject
      ?.getValue(CollegeCostChatTool.DATED_FIGURES_KEY)
      ?.jsonArray
      ?.map { it.jsonPrimitive.content }
      .orEmpty()

  @Test
  fun `cost_by_living_arrangement renders each arrangement with component keys and a total`() {
    val student = createStudent()
    seedListedCollege(student, "Arrangement U")
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    val breakdown = assertNotNull(breakdownOf(college))
    assertEquals(
      listOf(
        LivingArrangement.ON_CAMPUS.value,
        LivingArrangement.OFF_CAMPUS.value,
        LivingArrangement.WITH_FAMILY.value,
      ),
      breakdown.keys.toList(),
      "the arrangements ride in the order the coach reads them",
    )

    val onCampus = intsOf(assertNotNull(arrangementOf(college, LivingArrangement.ON_CAMPUS)))
    assertEquals(
      setOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName,
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD.wireName,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD.wireName,
        CollegeCostChatTool.TOTAL_KEY,
      ),
      onCampus.keys,
      "the component keys ARE the CostField wire names, so data_availability names keys the payload has",
    )
    assertEquals(
      onCampus.filterKeys { it != CollegeCostChatTool.TOTAL_KEY }.values.sum(),
      onCampus.getValue(CollegeCostChatTool.TOTAL_KEY),
      "the total is exactly the lines above it",
    )

    val withFamily = intsOf(assertNotNull(arrangementOf(college, LivingArrangement.WITH_FAMILY)))
    // Gate-2 D17 (RFC 166 §7) REVERSED the old rule here. The at-home
    // arrangement used to carry no food-and-housing line at all; it now carries
    // a labelled `$0`, because no source collects the figure and the absence is
    // not missing data about the school -- it is a cost that enrolling does not
    // create. The zero is OURS and the arrangement says so.
    assertEquals(
      0,
      withFamily[CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName],
      "the at-home food-and-housing line is a labelled zero: [$withFamily]",
    )
    assertTrue(
      withFamily.keys.none {
        it == CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName ||
          it == CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD.wireName
      },
      "and never another arrangement's housing allowance: [$withFamily]",
    )
    assertTrue(
      withFamily.getValue(CollegeCostChatTool.TOTAL_KEY) < onCampus.getValue(CollegeCostChatTool.TOTAL_KEY),
      "the one comparison this slice exists to make sayable",
    )
  }

  @Test
  fun `an incomplete arrangement renders its parts and no total`() {
    val student = createStudent()
    CostsTestDb.addToCollegeList(
      student,
      CostsTestDb.seedCollege("Gap U", otherExpensesOnCampusPerYearUsd = null),
    )
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    val onCampus = assertNotNull(arrangementOf(college, LivingArrangement.ON_CAMPUS))
    assertNull(onCampus[CollegeCostChatTool.TOTAL_KEY], "a partial sum must not be rendered as a total")
    assertNotNull(onCampus[CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName], "the reported parts still ride")
    assertTrue(
      dataAvailabilityOf(college).contains(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD.wireName),
      "and the missing part is named in the one vocabulary both keys share",
    )
  }

  @Test
  fun `unanswered residency renders the components with no tuition line and no total`() {
    val student = createStudent()
    seedListedCollege(student, "Unknown Residency U")

    val onCampus = intsOf(assertNotNull(arrangementOf(collegesOf(execute(student)).single(), LivingArrangement.ON_CAMPUS)))
    assertTrue(onCampus.keys.none { it.startsWith("tuition_and_fees") }, "a guessed residency is a lie: [$onCampus]")
    assertTrue(CollegeCostChatTool.TOTAL_KEY !in onCampus, "and there is nothing to total")
    assertEquals(
      CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      onCampus.getValue(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName),
      "the components are true whoever is reading them",
    )
  }

  @Test
  fun `a college reporting no component carries no breakdown key at all`() {
    val student = createStudent()
    CostsTestDb.addToCollegeList(
      student,
      CostsTestDb.seedCollege(
        "Componentless U",
        housingAndFoodOnCampusPerYearUsd = null,
        housingAndFoodOffCampusPerYearUsd = null,
        booksAndSuppliesPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        otherExpensesOffCampusPerYearUsd = null,
        otherExpensesWithFamilyPerYearUsd = null,
      ),
    )

    val college = collegesOf(execute(student)).single()
    assertNull(college[CollegeCostChatTool.BREAKDOWN_KEY], "an absent key, never an empty object")
  }

  @Test
  fun `a school with no residence halls renders the flag and no on_campus arrangement`() {
    val student = createStudent()
    CostsTestDb.addToCollegeList(
      student,
      CostsTestDb.seedCollege(
        "No Dorms Wire U",
        // The ordinary no-dorms shape: nothing on-campus is published either, so
        // the flag has nothing to contradict (RFC 149 D-B).
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ),
    )
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    assertEquals(
      false,
      college
        .getValue(CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY)
        .jsonPrimitive.content
        .toBoolean(),
    )
    assertNull(arrangementOf(college, LivingArrangement.ON_CAMPUS), "there is no on-campus option to price")
    assertNotNull(arrangementOf(college, LivingArrangement.OFF_CAMPUS))
    assertFalse(
      dataAvailabilityOf(college).contains(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName),
      "no dorms is an ANSWER, not a Scorecard silence: [$college]",
    )
  }

  @Test
  fun `a published on-campus price rides beside the no-dorms flag, and the coach is told both`() {
    // RFC 149 D-B, at the wire: the school's own published figures win, and the
    // flag is still there, so the coach can say the two sources disagree instead
    // of being handed one of them.
    val student = createStudent()
    CostsTestDb.addToCollegeList(
      student,
      CostsTestDb.seedCollege("Contradiction Wire U", ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER),
    )
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    assertEquals(
      false,
      college
        .getValue(CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY)
        .jsonPrimitive.content
        .toBoolean(),
      "the flag is a reported fact and does not disappear because the price disagrees with it",
    )
    val onCampus = intsOf(assertNotNull(arrangementOf(college, LivingArrangement.ON_CAMPUS), "the published figures are rendered"))
    assertEquals(
      CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
      onCampus.getValue(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName),
      "a figure the school published is never withheld: [$onCampus]",
    )
    assertFalse(
      dataAvailabilityOf(college).contains(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName),
      "and a reported figure is never also called unreported: [$college]",
    )
  }

  @Test
  fun `the housing flag is emitted whenever IPEDS knows it, true as well as false`() {
    // RFC 149 D-B: the flag is emitted whenever it is KNOWN. "IPEDS says this
    // school HAS dorms" is a reported fact, and rendering it as silence made it
    // indistinguishable from "IPEDS never said" -- with a rendered on_campus
    // arrangement standing in for the fact, which it is not: a school with
    // dorms that publishes no on-campus component carries no arrangement.
    val student = createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Dorms Wire U", ipedsHousing = CostsTestDb.IpedsHousing.OFFERS))

    val college = collegesOf(execute(student)).single()
    assertEquals(
      true,
      college
        .getValue(CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY)
        .jsonPrimitive.content
        .toBoolean(),
      "a known true is an answer, not a silence: [$college]",
    )
    assertNotNull(arrangementOf(college, LivingArrangement.ON_CAMPUS))
  }

  @Test
  fun `a school IPEDS is silent about carries the flag as an absent key, whether the row is missing or quiet`() {
    // The ONE state with nothing to say. Both silences render the same way here
    // -- no IPEDS row at all, and a row that does not report `IC.ROOM` -- but
    // they stay distinguishable in the DAO, which returns a null VALUE for the
    // quiet row and no key for the missing one.
    val student = createStudent()
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Silent IPEDS U", ipedsHousing = CostsTestDb.IpedsHousing.NO_ROW))
    CostsTestDb.addToCollegeList(student, CostsTestDb.seedCollege("Quiet Row U", ipedsHousing = CostsTestDb.IpedsHousing.UNREPORTED))

    val byName = collegesOf(execute(student)).associateBy { it.getValue("name").jsonPrimitive.content }
    for (name in listOf("Silent IPEDS U", "Quiet Row U")) {
      assertNull(
        byName.getValue(name)[CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY],
        "an absent IPEDS answer is not evidence either way: [$name]",
      )
      assertNotNull(arrangementOf(byName.getValue(name), LivingArrangement.ON_CAMPUS), "[$name]")
    }
  }

  @Test
  fun `each vintage label rides beside the figures it governs, and never as a bare year`() {
    val student = createStudent()
    seedListedCollege(student, "Vintage U")
    answerResidency(student, "CA")

    val payload = execute(student)
    val college = collegesOf(payload).single()
    assertEquals(CostsTestDb.PRICE_ACADEMIC_YEAR, academicYearOf(college, FigureGroup.PUBLISHED_PRICE))
    assertEquals(CostsTestDb.BLENDED_ACADEMIC_YEAR, academicYearOf(college, FigureGroup.BLENDED_AVERAGE))

    // The year NAMES the figures it dates (RFC 149 D-E): a bare year beside the
    // college object left membership to a convention the payload never states,
    // and the prompt tells the coach to quote the year beside a figure.
    val published = datedFiguresOf(college, FigureGroup.PUBLISHED_PRICE)
    assertTrue(
      published.containsAll(CostField.COMPONENTS.map { it.wireName }),
      "every component this college reports is dated by the published-price year: [$published]",
    )
    assertTrue(
      published.contains(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName),
      "and so is the tuition line that applies: [$published]",
    )
    assertEquals(
      listOf(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName, CostField.NET_PRICE.wireName),
      datedFiguresOf(college, FigureGroup.BLENDED_AVERAGE),
      "and the blended year dates ONLY the two blended figures, in CostField declaration order",
    )
    assertTrue(
      datedFiguresOf(college, FigureGroup.PUBLISHED_PRICE)
        .intersect(datedFiguresOf(college, FigureGroup.BLENDED_AVERAGE).toSet())
        .isEmpty(),
      "no figure is dated by both years, which is what makes 'never add across years' sayable",
    )
    assertTrue(
      datedFiguresOf(college, FigureGroup.PUBLISHED_PRICE).none {
        it == CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName || it == CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName
      } &&
        datedFiguresOf(college, FigureGroup.BLENDED_AVERAGE).none {
          it == CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName || it == CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName
        },
      "an undated figure is named by neither key: [$college]",
    )

    // The year is THIS SCHOOL's, read off the rows it served (RFC 166 §3) rather
    // than off a Kotlin constant on the group -- so the assertion is the
    // fixture's own year, in the academic-year form, and a Scorecard-only school
    // may honestly say a different one.
    assertEquals("2023-24", CostsTestDb.PRICE_ACADEMIC_YEAR, "the academic-year form, never a bare year")
    assertEquals("2022-23", CostsTestDb.BLENDED_ACADEMIC_YEAR)
    assertTrue(
      academicYearOf(college, FigureGroup.PUBLISHED_PRICE) != academicYearOf(college, FigureGroup.BLENDED_AVERAGE),
      "the components and the blended averages are different years; that is why they are never summed together",
    )

    // The payload attribution stops claiming an ingest year (RFC 149 D-E):
    // updated_at was WHEN WE LOADED THE FILE, never the year of the figures.
    val source = payload.getValue(CollegeCostChatTool.SOURCE_KEY).jsonPrimitive.content
    // DERIVED from the rows that won, never a constant (RFC 177). This fixture's
    // price rows are IPEDS IC_AY and its blended rows are the Scorecard, so the
    // payload names BOTH -- where the constant this replaced named only the
    // Scorecard, for figures IPEDS published.
    assertEquals(
      "${MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)} and ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}",
      source,
    )
    assertFalse(source.contains("ingested"), "the ingest year was never a vintage: [$source]")
  }

  @Test
  fun `a college with no blended figure carries no blended vintage label`() {
    val student = createStudent()
    CostsTestDb.addToCollegeList(
      student,
      CostsTestDb.seedCollege(
        "Published Only U",
        costOfAttendancePerYearUsd = null,
        netPricePerYearUsd = null,
        netPricePerYearIncomeQ1Usd = null,
        netPricePerYearIncomeQ2Usd = null,
        netPricePerYearIncomeQ3Usd = null,
        netPricePerYearIncomeQ4Usd = null,
        netPricePerYearIncomeQ5Usd = null,
        medianDebtAtCompletionUsd = null,
        medianEarnings10yAfterEntryUsd = null,
      ),
    )

    val college = collegesOf(execute(student)).single()
    assertNotNull(college[FigureGroup.PUBLISHED_PRICE.wireName], "it still publishes a price list")
    assertNull(
      college[FigureGroup.BLENDED_AVERAGE.wireName],
      "a label for a vintage no figure here carries would be a citation of nothing: [$college]",
    )
  }

  @Test
  fun `a college whose only blended-looking figures are debt and earnings carries NO vintage label`() {
    // RFC 149 D-E: a figure this RFC has not dated carries no vintage at all.
    // Median debt at completion and median earnings ten years after entry are on
    // neither cohort basis D-E dates, so labelling them AY2021-22 because
    // COSTT4_A happens to be that year would be a citation we cannot source.
    val student = createStudent()
    CostsTestDb.addToCollegeList(
      student,
      CostsTestDb.seedCollege(
        "Undated U",
        costOfAttendancePerYearUsd = null,
        netPricePerYearUsd = null,
        netPricePerYearIncomeQ1Usd = null,
        netPricePerYearIncomeQ2Usd = null,
        netPricePerYearIncomeQ3Usd = null,
        netPricePerYearIncomeQ4Usd = null,
        netPricePerYearIncomeQ5Usd = null,
        medianDebtAtCompletionUsd = 23000,
        medianEarnings10yAfterEntryUsd = 55000,
      ),
    )

    val college = collegesOf(execute(student)).single()
    assertEquals("23000", college.getValue(CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName).jsonPrimitive.content)
    assertEquals("55000", college.getValue(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName).jsonPrimitive.content)
    assertNull(
      college[FigureGroup.BLENDED_AVERAGE.wireName],
      "no COSTT4_A and no net price here, so the blended year describes nothing in this object: [$college]",
    )
  }

  // ---------------------------------------------------------------------------
  // The canonical store's own truths (RFC 166): three tuition tiers, the fees
  // split, the at-home zero that is OURS, and the six statuses spoken.
  // ---------------------------------------------------------------------------

  /**
   * RFC 161's Austin CC shape (`rfc/161...:16-24`): the community-college case,
   * and the ONLY fixture that exercises the in-district tier at all.
   *
   * A private or public four-year fixture publishes no in-district figure, so a
   * test written against one would pass while asserting nothing -- which is why
   * this shape is named once, here, rather than retyped per test.
   */
  private fun seedCommunityCollege(
    student: StudentId,
    name: String = "District CC",
  ): CollegeId =
    CostsTestDb
      .seedCollege(
        name,
        control = 1,
        seedsInDistrictRow = true,
        tuitionAndFeesInDistrictPerYearUsd = IN_DISTRICT_TUITION_USD,
        tuitionAndFeesInStatePerYearUsd = IN_STATE_TUITION_USD,
        tuitionAndFeesOutOfStatePerYearUsd = OUT_OF_STATE_TUITION_USD,
      ).also { CostsTestDb.addToCollegeList(student, it) }

  /** One college's `residency_tiers` object -- the code and the sentence, on every college. */
  private fun residencyTiersOf(college: JsonObject): JsonObject = college.getValue(CollegeCostChatTool.RESIDENCY_TIERS_KEY).jsonObject

  /** One college's `figure_statuses` entries by field, or an empty map when the key is absent. */
  private fun figureStatusesOf(college: JsonObject): Map<String, JsonObject> =
    college[CollegeCostChatTool.FIGURE_STATUSES_KEY]
      ?.jsonArray
      ?.map { it.jsonObject }
      ?.associateBy { it.getValue("field").jsonPrimitive.content }
      .orEmpty()

  @Test
  fun `a community college names three tuition tiers, and the district price is never anyone's tuition line`() {
    val student = createStudent()
    seedCommunityCollege(student)
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    assertEquals(
      IN_DISTRICT_TUITION_USD.toString(),
      college.getValue(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD.wireName).jsonPrimitive.content,
      "the third tier is on the wire for the first time: [$college]",
    )
    assertEquals(
      IN_STATE_TUITION_USD.toString(),
      college.getValue(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
    )
    assertEquals(
      OUT_OF_STATE_TUITION_USD.toString(),
      college.getValue(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
    )
    assertEquals(
      ResidencyTierBasis.THREE_TIERS_PUBLISHED.value,
      residencyTiersOf(college).getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    assertEquals(
      ResidencyTierBasis.THREE_TIERS_PUBLISHED.statement,
      statementOf(residencyTiersOf(college)),
      "the code never travels without the domain's own words",
    )

    // The applicable tuition for a CA family at a CA school is the IN-STATE one:
    // a state answer cannot select a district price, so the district figure is a
    // labelled tier and nothing more (RFC 166 s4).
    assertEquals("in_state", college.getValue("tuition_applicable").jsonPrimitive.content)
    LivingArrangement.entries.forEach { arrangement ->
      val lines = intsOf(assertNotNull(arrangementOf(college, arrangement), "[$arrangement]"))
      assertEquals(
        IN_STATE_TUITION_USD,
        lines[CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName],
        "the total is built from the tuition the family's own answer selected: [$lines]",
      )
      assertNull(
        lines[CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD.wireName],
        "the district price never enters a total: [$lines]",
      )
    }
    assertTrue(
      datedFiguresOf(college, FigureGroup.PUBLISHED_PRICE).contains(
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD.wireName,
      ),
      "and the new key is dated like every other published figure, never said with no year",
    )
  }

  @Test
  fun `a school whose publisher never separated a district price emits no in-district key and says so`() {
    // RFC 161's open item, handed to this slice in its own words: at the ~2,300
    // institutions with no in-district row, re-labelling the in-state figure
    // would trade one wrong label for another. So the figure keeps the label the
    // publisher gave it, and what we do not know is SAID.
    val student = createStudent()
    seedListedCollege(student, "Scorecard Only U")
    answerResidency(student, "CA")

    val payload = execute(student)
    val college = collegesOf(payload).single()
    assertEquals(
      ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.value,
      residencyTiersOf(college).getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    assertEquals(
      ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.statement,
      statementOf(residencyTiersOf(college)),
    )
    // The in-state figure is served AS IN-STATE, at its own key, and the word
    // "in-district" is attached to no figure anywhere in this payload -- not as
    // a key, not in `data_availability`, not as a dated figure.
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.toString(),
      college.getValue(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
    )
    val inDistrictKeys =
      setOf(
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD.wireName,
        CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD.wireName,
      )
    assertEquals(
      emptySet(),
      keysAnywhereIn(payload).intersect(inDistrictKeys),
      "no in-district key at a school that publishes no in-district figure: [$payload]",
    )
    assertEquals(
      emptyList(),
      dataAvailabilityOf(college).filter { it in inDistrictKeys },
      "and a tier the publisher never separated is not a field this school failed to report: [$college]",
    )
  }

  @Test
  fun `a school we hold no tuition price for is never said to publish one`() {
    // The zero-tier school used to reach `single_published_price` and this
    // payload shipped its sentence -- "This school publishes one tuition price"
    // -- to the coach, in the very object whose tuition keys are all absent and
    // whose `data_availability` says they are. The domain now names the empty
    // case itself, and the coach is told what is true instead of nothing.
    val student = createStudent()
    seedListedCollege(
      student,
      "Silent Tuition College",
      tuitionAndFeesInStatePerYearUsd = null,
      tuitionAndFeesOutOfStatePerYearUsd = null,
    )
    answerResidency(student, "CA")

    val payload = execute(student)
    val college = collegesOf(payload).single()
    assertEquals(
      emptySet(),
      keysAnywhereIn(payload).intersect(CostField.TUITION_FIELDS.map { it.wireName }.toSet()),
      "the premise of the case: no tuition key anywhere in the payload: [$payload]",
    )
    assertEquals(
      ResidencyTierBasis.NO_PUBLISHED_TUITION.value,
      residencyTiersOf(college).getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
      "the school we hold no price for says THAT, and the key is still present: [$college]",
    )
    assertEquals(
      ResidencyTierBasis.NO_PUBLISHED_TUITION.statement,
      statementOf(residencyTiersOf(college)),
      "the code never travels without the domain's own words",
    )
    // Asserted on the COLLEGE object rather than the whole payload: the
    // comparison basis has its own same-spelled `single_published_price` code
    // for a private school (`ComparedTuition.SinglePublishedPrice`), a different
    // vocabulary answering a different question, and folding the two would make
    // this assertion pass or fail for the wrong reason.
    assertFalse(
      college.toString().contains(ResidencyTierBasis.SINGLE_PUBLISHED_PRICE.value),
      "the one-price code may never ride on a school with no tuition key: [$college]",
    )
    assertFalse(
      payload.toString().contains("publishes one tuition price"),
      "and the words must reach the coach nowhere in this payload: [$payload]",
    )
  }

  @Test
  fun `the at-home total is complete, and the arrangement names the zero as ours`() {
    val student = createStudent()
    seedListedCollege(student, "At Home Total U")
    answerResidency(student, "CA")

    val atHome = intsOf(assertNotNull(arrangementOf(collegesOf(execute(student)).single(), LivingArrangement.WITH_FAMILY)))
    assertEquals(
      setOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName,
        CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD.wireName,
        CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD.wireName,
        CollegeCostChatTool.TOTAL_KEY,
      ),
      atHome.keys,
      "three components and the tuition line that applies: [$atHome]",
    )
    assertEquals(0, atHome.getValue(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName))
    assertEquals(
      atHome.filterKeys { it != CollegeCostChatTool.TOTAL_KEY }.values.sum(),
      atHome.getValue(CollegeCostChatTool.TOTAL_KEY),
      "the total is exactly the lines above it, and it EXISTS -- the zero is what completes it",
    )
  }

  @Test
  fun `the payload never says the school reported the at-home zero, nor that it failed to`() {
    // The second half of gate-2 D17: the number is ours, so every vocabulary
    // that speaks about the SCHOOL must stay silent about it. Nothing else in
    // the payload can carry that fact, which is why `assumed_by_unicoach` exists.
    val student = createStudent()
    seedListedCollege(student, "Whose Zero U")
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    val assumed = CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName
    assertEquals(
      listOf(assumed),
      assertNotNull(arrangementOf(college, LivingArrangement.WITH_FAMILY))
        .getValue(CollegeCostChatTool.ASSUMED_BY_UNICOACH_KEY)
        .jsonArray
        .map { it.jsonPrimitive.content },
      "exactly the one line whose amount is ours: [$college]",
    )
    assertFalse(
      dataAvailabilityOf(college).contains(assumed),
      "a figure no publisher collects is not a figure this school failed to report: [$college]",
    )
    assertNull(figureStatusesOf(college)[assumed], "and we hold no status about a row that does not exist: [$college]")
    LivingArrangement.entries
      .filter { it != LivingArrangement.WITH_FAMILY }
      .forEach { arrangement ->
        assertNull(
          assertNotNull(arrangementOf(college, arrangement))[CollegeCostChatTool.ASSUMED_BY_UNICOACH_KEY],
          "the key is absent, never empty, wherever nothing is ours: [$arrangement]",
        )
      }
  }

  @Test
  fun `the fees split rides beside the combined figure and is never summed into a total`() {
    val student = createStudent()
    CostsTestDb
      .seedCollege(
        "Split Fees U",
        control = 1,
        feesOnlyInStatePerYearUsd = FEES_ONLY_IN_STATE_USD,
        feesOnlyOutOfStatePerYearUsd = FEES_ONLY_OUT_OF_STATE_USD,
      ).also { CostsTestDb.addToCollegeList(student, it) }
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()
    assertEquals(
      FEES_ONLY_IN_STATE_USD.toString(),
      college.getValue(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
      "the fees part of the combined figure, beside it: [$college]",
    )
    assertEquals(
      FEES_ONLY_OUT_OF_STATE_USD.toString(),
      college.getValue(CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
    )

    // `fees_only` is a part OF `tuition_and_fees`, not a part BESIDE it: putting
    // it in an arrangement would count fees twice inside every total.
    val onCampus = intsOf(assertNotNull(arrangementOf(college, LivingArrangement.ON_CAMPUS)))
    assertNull(
      onCampus[CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName],
      "the fees figure is not a component of any arrangement: [$onCampus]",
    )
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      onCampus.getValue(CollegeCostChatTool.TOTAL_KEY),
      "the total is tuition-and-fees plus the components, with the fees figure counted once: [$onCampus]",
    )
    assertTrue(
      datedFiguresOf(college, FigureGroup.PUBLISHED_PRICE).containsAll(
        listOf(
          CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName,
          CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD.wireName,
        ),
      ),
      "and both new keys are dated by the published-price year",
    )
  }

  /**
   * THE LIVE DEFECT, pinned on the chat payload (RFC 177).
   *
   * `CanonicalMoneyLoader.ORDERED_SOURCES` ranks IPEDS SFA and IPEDS IC_AY ABOVE
   * the Scorecard, so a price cell IPEDS published wins. This payload attributed
   * every figure in it to the College Scorecard, from one hand-typed constant --
   * a false statement about a named publisher, and one no test could see,
   * because the constant only ever agreed with itself.
   *
   * Both halves are asserted: the payload-level attribution names the publisher
   * whose rows won, and the sentence beside an IPEDS figure names IPEDS. Fails
   * on the code as it stood before this slice.
   */
  @Test
  fun `a figure IPEDS published is never attributed to the College Scorecard`() {
    val student = createStudent()
    val collegeId = seedListedCollege(student, "IPEDS Priced U")
    // A cell the PUBLISHER suppressed, on the IPEDS side: the one status whose
    // sentence is about the publisher's own act and so must name it.
    CostsTestDb.seedPriceFigure(
      collegeId = collegeId,
      field = CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD,
      reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
      source = MoneySource.IPEDS_IC_AY,
    )

    val result = execute(student)
    val source = result.getValue(CollegeCostChatTool.SOURCE_KEY).jsonPrimitive.content

    assertTrue(
      source.contains(MoneySourceCopy.labelOf(MoneySource.IPEDS_IC_AY)),
      "the publisher whose price rows won must be named: [$source]",
    )
    val statuses = figureStatusesOf(collegesOf(result).single())
    val entry = assertNotNull(statuses[CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName], "[$statuses]")
    val statement = statementOf(entry)
    assertEquals(
      "The U.S. Department of Education's IPEDS survey of college costs withholds this figure to protect " +
        "students' privacy.",
      statement,
      "an IPEDS suppression is IPEDS', said by name",
    )
    assertFalse(
      statement.contains("College Scorecard"),
      "a figure IPEDS published may never be attributed to the Scorecard: [$statement]",
    )
  }

  @Test
  fun `the six figure statuses are spoken, and only the school's own silence joins data_availability`() {
    // One fixture, one cell per status. The cells chosen are the ones the
    // default seeder writes NO row for, because `price_figures` has a natural
    // key and a second row for the same cell is not writable -- so the statuses
    // ride on the in-district and fees-only addresses, plus one component the
    // fixture nulls.
    val student = createStudent()
    val college =
      CostsTestDb
        .seedCollege("Six Statuses U", control = 1, otherExpensesOnCampusPerYearUsd = null)
        .also { CostsTestDb.addToCollegeList(student, it) }
    // Value-bearing and SHOWN, with the publisher's-estimate sentence beside it:
    // the 263 `XFEE2` rows flagged `Z` are real zeros the publisher implied.
    CostsTestDb.seedPriceFigure(
      college,
      PriceConcept.FEES_ONLY,
      ResidencyBasis.IN_STATE,
      reading = FigureReading.Present(0, ValueBearingStatus.IMPUTED_BY_PUBLISHER),
    )
    CostsTestDb.seedPriceFigure(
      college,
      PriceConcept.FEES_ONLY,
      ResidencyBasis.OUT_OF_STATE,
      reading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
    )
    CostsTestDb.seedPriceFigure(
      college,
      PriceConcept.FEES_ONLY,
      ResidencyBasis.IN_DISTRICT,
      reading = FigureReading.Absent(AbsenceStatus.NOT_COLLECTED_BY_US),
    )
    CostsTestDb.seedPriceFigure(
      college,
      PriceConcept.TUITION_AND_FEES,
      ResidencyBasis.IN_DISTRICT,
      reading = FigureReading.Absent(AbsenceStatus.NOT_APPLICABLE),
    )
    answerResidency(student, "CA")

    val rendered = collegesOf(execute(student)).single()
    val statuses = figureStatusesOf(rendered)
    val expected =
      mapOf(
        CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName to FigureStatus.IMPUTED_BY_PUBLISHER,
        CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD.wireName to FigureStatus.SUPPRESSED_BY_PUBLISHER,
        CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD.wireName to FigureStatus.NOT_COLLECTED_BY_US,
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD.wireName to FigureStatus.NOT_APPLICABLE,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD.wireName to FigureStatus.NOT_REPORTED_BY_INSTITUTION,
      )
    // Every field here is a PRICE field, and this fixture's price rows are
    // IPEDS IC_AY -- so the sentence the wire carries is the DERIVED one, which
    // names that survey wherever the status is about the publisher's own act
    // (RFC 177 D3). Stated as the source rather than as five literals: the
    // sentence is still the domain's own and is still never re-worded here.
    val source = MoneySource.IPEDS_IC_AY
    expected.forEach { (field, status) ->
      val entry = assertNotNull(statuses[field], "[$field] must say why it has no number: [$statuses]")
      assertEquals(status.value, entry.getValue("status").jsonPrimitive.content, "[$field]")
      assertEquals(
        FigureStatusCopy.statementOf(status, source),
        statementOf(entry),
        "the sentence is the domain's own, never re-worded on the wire: [$field]",
      )
    }
    // The sixth status HAS an entry now (RFC 179 D6) and says nothing about its
    // status: the entry exists so the figure can carry what KIND of number it
    // is, which is the one thing a family could not learn beside a shown dollar
    // amount before. No status prose was invented for it -- the key is absent,
    // and absence has one representation on this payload.
    val reported = assertNotNull(statuses[CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName])
    assertEquals(FigureStatus.REPORTED.value, reported.getValue("status").jsonPrimitive.content)
    assertNull(
      reported[CollegeCostChatTool.STATEMENT_KEY],
      "a shown, plainly reported figure says nothing about its status: [$reported]",
    )
    // This fixture's price rows are IPEDS IC_AY: a compelled, edit-checked
    // survey, whichever variable the cell is.
    assertEquals(
      AssuranceTier.MANDATORY_SURVEY.value,
      reported.getValue(CollegeCostChatTool.ASSURANCE_KEY).jsonPrimitive.content,
    )
    assertEquals(
      AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY),
      reported.getValue(CollegeCostChatTool.ASSURANCE_STATEMENT_KEY).jsonPrimitive.content,
      "the tier's sentence is the domain's own, never re-worded on the wire",
    )
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.toString(),
      rendered.getValue(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
    )
    // An imputed figure is still a FIGURE: shown, labelled, never hidden.
    assertEquals(
      "0",
      rendered.getValue(CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD.wireName).jsonPrimitive.content,
      "an imputed zero is a real zero the publisher implied: [$rendered]",
    )
    // WHOSE gap it is decides whether `data_availability` -- the school's own
    // silence -- may name it at all.
    val availability = dataAvailabilityOf(rendered)
    assertTrue(
      availability.contains(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD.wireName),
      "the school reported nothing there, and that is the school's silence: [$availability]",
    )
    assertEquals(
      emptyList(),
      availability.filter {
        it == CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD.wireName ||
          it == CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD.wireName
      },
      "the publisher's suppression and our own gap are never the school's silence: [$availability]",
    )
  }

  @Test
  fun `a year-gap status carries the year we hold the figure for as data, and no other status carries the key`() {
    // RFC 166 §3. The two years used to live ONLY inside the English sentence,
    // so a reader that wanted the year we hold the figure for had to
    // substring-match our own prose about somebody's money -- while the `status`
    // beside it said `not_collected_by_us`, which reads as "we hold nothing".
    // The held year is now a FIELD on the wire.
    val student = createStudent()
    val college =
      CostsTestDb
        .seedCollege(
          "Wire Year Gap U",
          control = 1,
          // The school's own silence, at the SERVED year: the non-gap note this
          // case is contrasted against.
          otherExpensesOnCampusPerYearUsd = null,
          // No row at the served year for the off-campus figure, so the older
          // row below is the only one we hold for it.
          omittedPriceFields = setOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD),
        ).also { CostsTestDb.addToCollegeList(student, it) }
    CostsTestDb.seedPriceFigure(
      college,
      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      reading = FigureReading.Present(2400, ValueBearingStatus.REPORTED),
      academicYear = OLDER_YEAR,
    )
    answerResidency(student, "CA")

    val rendered = collegesOf(execute(student)).single()
    val statuses = figureStatusesOf(rendered)
    val gap =
      assertNotNull(
        statuses[CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD.wireName],
        "a figure held at another year is never dropped silently: [$statuses]",
      )
    assertEquals(FigureStatus.NOT_COLLECTED_BY_US.value, gap.getValue("status").jsonPrimitive.content)
    assertEquals(
      OLDER_ACADEMIC_YEAR,
      gap.getValue(CollegeCostChatTool.HELD_ACADEMIC_YEAR_KEY).jsonPrimitive.content,
      "the year we hold it for is readable as data, not by parsing the sentence: [$gap]",
    )
    // The SERVED year is not restated inside the entry: it is already on the
    // college object, under the vintage that dates the very figures this entry
    // is about, and a second copy is a second place for it to be wrong.
    assertNull(gap["served_academic_year"], "the served year is not duplicated into the entry: [$gap]")
    assertEquals(
      CostsTestDb.PRICE_ACADEMIC_YEAR,
      academicYearOf(rendered, FigureGroup.PUBLISHED_PRICE),
      "and the served year IS on the wire, where it always was",
    )
    // The key's PRESENCE is the fact -- `status` cannot carry it, because a year
    // gap and a cell we have genuinely never collected are both
    // `not_collected_by_us`. So it must be absent everywhere else.
    val plainSilence =
      assertNotNull(statuses[CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD.wireName], "[$statuses]")
    assertEquals(FigureStatus.NOT_REPORTED_BY_INSTITUTION.value, plainSilence.getValue("status").jsonPrimitive.content)
    assertNull(
      plainSilence[CollegeCostChatTool.HELD_ACADEMIC_YEAR_KEY],
      "a school's own silence holds no figure at any year: [$plainSilence]",
    )
    assertEquals(
      listOf(CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD.wireName),
      statuses
        .filterValues { it[CollegeCostChatTool.HELD_ACADEMIC_YEAR_KEY] != null }
        .keys
        .toList(),
      "exactly one entry carries the key, and it is the year gap: [$statuses]",
    )
  }

  @Test
  fun `a school with a district price and one state tier is never offered the three-tier invitation`() {
    // The contradiction this test exists against: `residency_tiers.statement`
    // said "a district price and one other tuition price; it does not publish
    // all three tiers" while the offer in the SAME object promised three prices
    // and a choice between two state-level ones. The second sentence named an
    // out-of-state price this school never published.
    val student = createStudent()
    val collegeId =
      CostsTestDb.seedCollege(
        "One Other Tier CC",
        control = 1,
        seedsInDistrictRow = true,
        tuitionAndFeesInDistrictPerYearUsd = IN_DISTRICT_TUITION_USD,
        tuitionAndFeesInStatePerYearUsd = IN_STATE_TUITION_USD,
        tuitionAndFeesOutOfStatePerYearUsd = null,
      )
    CostsTestDb.addToCollegeList(student, collegeId)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER.value,
      residencyTiersOf(college).getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    assertEquals(
      listOf(IN_STATE_TUITION_USD, IN_DISTRICT_TUITION_USD).map { it.toString() },
      listOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      ).map { college.getValue(it.wireName).jsonPrimitive.content },
    )
    assertNull(
      college[CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD.wireName],
      "the school publishes no out-of-state price, so the payload carries no key for one: [$college]",
    )

    val offer = assertNotNull(offerCopyOf(college, PrecisionOffer.RESIDENCY.field), "[$college]")
    assertEquals(CollegeCostChatTool.RESIDENCY_OFFER, offer)
    assertFalse(
      offer.contains("three tuition and fees prices"),
      "the offer may not promise a tier the same object says this school does not publish: [$offer]",
    )
  }

  @Test
  fun `the offer reads the tier decision the payload carries, never a second derivation of it`() {
    // The two sentences come from ONE fact. Asserting them together is what
    // catches a renderer that re-derives the tier shape from one amount: the
    // three-tier copy appears if and only if the basis says three tiers.
    val student = createStudent()
    seedCommunityCollege(student, name = "Agreeing Copy CC")

    val college = collegesOf(execute(student)).single()
    val basis = residencyTiersOf(college).getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content
    val offer = assertNotNull(offerCopyOf(college, PrecisionOffer.RESIDENCY.field), "[$college]")
    assertEquals(ResidencyTierBasis.THREE_TIERS_PUBLISHED.value, basis)
    assertEquals(CollegeCostChatTool.RESIDENCY_OFFER_THREE_TIERS, offer)
    assertEquals(
      basis == ResidencyTierBasis.THREE_TIERS_PUBLISHED.value,
      offer == CollegeCostChatTool.RESIDENCY_OFFER_THREE_TIERS,
      "one decision, two sentences: [$basis] vs [$offer]",
    )
  }

  @Test
  fun `a first session at a community college names three tiers, offers residency, and withholds no answer`() {
    // The RFC's first-session test, end to end: a brand-new student with NO
    // money profile at all. The `update_college_list` step is part of the
    // scenario because `college_cost_profile` answers only for colleges on the
    // active list, and this slice does not change that gate.
    val student = createStudent()
    val collegeId =
      CostsTestDb.seedCollege(
        "First Session CC",
        control = 1,
        seedsInDistrictRow = true,
        tuitionAndFeesInDistrictPerYearUsd = IN_DISTRICT_TUITION_USD,
        tuitionAndFeesInStatePerYearUsd = IN_STATE_TUITION_USD,
        tuitionAndFeesOutOfStatePerYearUsd = OUT_OF_STATE_TUITION_USD,
      )
    assertEquals(
      emptyList(),
      collegesOf(execute(student)).map { it.getValue("name").jsonPrimitive.content },
      "nothing is answered for a college the student has not listed",
    )
    CostsTestDb.addToCollegeList(student, collegeId)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      listOf(IN_STATE_TUITION_USD, OUT_OF_STATE_TUITION_USD, IN_DISTRICT_TUITION_USD).map { it.toString() },
      listOf(
        CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
        CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      ).map { college.getValue(it.wireName).jsonPrimitive.content },
      "all three tiers are named in the first answer, with nothing asked first: [$college]",
    )
    assertEquals(
      ResidencyTierBasis.THREE_TIERS_PUBLISHED.value,
      residencyTiersOf(college).getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )

    // The offer is OFFERED, never forced (brief 0001 D11): it carries the
    // three-tier copy this school actually needs, and every figure the school
    // publishes is already in the answer.
    val offer = assertNotNull(offerCopyOf(college, PrecisionOffer.RESIDENCY.field), "[$college]")
    assertEquals(CollegeCostChatTool.RESIDENCY_OFFER_THREE_TIERS, offer)
    assertTrue(offer.contains("three tuition and fees prices"), "the two-price copy is wrong here: [$offer]")
    assertTrue(offer.contains("A district is never asked about"), "no district question is ever asked: [$offer]")
    assertNull(college[CollegeCostChatTool.WITHHELD_FIGURES_KEY], "no answer is gated on the question: [$college]")
    assertNotNull(college[CostField.NET_PRICE.wireName], "the net price is answered on its stated basis: [$college]")
    LivingArrangement.entries.forEach { arrangement ->
      assertNotNull(arrangementOf(college, arrangement), "every way of living this school prices is shown: [$arrangement]")
    }
  }

  @Test
  fun `the description states the breakdown contract`() {
    val description =
      tool.definition
        .getValue("description")
        .jsonPrimitive.content

    assertTrue(description.contains(CollegeCostChatTool.BREAKDOWN_KEY), "the new key must be described")
    assertTrue(
      description.contains(LivingArrangement.WITH_FAMILY.value) &&
        description.contains(LivingArrangement.ON_CAMPUS.value) &&
        description.contains(LivingArrangement.OFF_CAMPUS.value),
      "the three arrangements must be named",
    )
    assertTrue(
      description.contains("never add up what is there and call it the total"),
      "a missing total is a rule, not an invitation to improvise one",
    )
    // Gate-2 D17 (RFC 166 §7) reversed this paragraph: the at-home line is a
    // labelled `$0` now, and the description's whole job here is to say WHOSE
    // zero it is -- ours, in one canonical sentence nobody re-words.
    assertTrue(
      description.contains(CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD.wireName) &&
        description.contains(AT_HOME_ASSUMPTION_STATEMENT),
      "the at-home zero is stated in the domain's own words, never re-worded here",
    )
    assertTrue(
      description.contains("never say this school reported a zero and never say it failed to report the figure"),
      "no publisher collects the figure, so neither claim about the school is available",
    )
    assertTrue(
      description.contains(CollegeCostChatTool.ASSUMED_BY_UNICOACH_KEY),
      "and the key that names our own amounts on the wire must be described",
    )
    assertTrue(
      description.contains("never compare it with an arrangement total"),
      "RFC 149 D-F rule 1: COSTT4_A is not the component sum",
    )
    assertTrue(
      description.contains("never subtract ${CostField.NET_PRICE.wireName} from tuition"),
      "RFC 149 D-F rule 2: aid applies to the blend, never to a component",
    )
    assertTrue(
      description.contains(CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY) &&
        description.contains("has no residence halls"),
      "the no-dorms answer must be readable as one",
    )
    // RFC 152's chosen-arrangement block, in the same description.
    assertTrue(
      description.contains(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY),
      "the chosen-arrangement key must be described",
    )
    assertTrue(
      description.contains("Lead with it, name it in the student's own words"),
      "a resolved plan is what the coach LEADS with, in the student's words",
    )
    assertTrue(
      description.contains("never assume living at home for a school silently"),
      "with_family is never inferred by us (D2a)",
    )
    assertTrue(
      description.contains("never quote a different arrangement in its place") &&
        description.contains("never carry a neighbour's figure across"),
      "a school not priced for the plan gets its reason, never a substitute",
    )
    // Every shape is named by its CODE, never by which sibling keys are absent
    // (RFC 152): the coach is told to read [CollegeCostChatTool.PRICING_KEY],
    // and the no-total case says whose gap it is.
    assertTrue(
      description.contains(CollegeCostChatTool.PRICING_KEY) &&
        LivingPlanPricing.entries
          .filterNot { it == LivingPlanPricing.NOT_CHOSEN }
          .all { description.contains("\"${it.value}\"") },
      "the three resolved shapes are told apart by a code the description names",
    )
    assertTrue(
      NoTotalReason.entries.all { description.contains("\"${it.value}\"") } &&
        description.contains("are OUR gaps, so never say this school published no price") &&
        description.contains("never add up what is there and call it the total"),
      "and the no-total case says whose gap it is, so our own open question never blames a school's price list",
    )
    // EXHAUSTIVE over both enums the description enumerates, mirroring the
    // [NoTotalReason] sweep above. A member added to either one ships a CODE to
    // the coach with no words explaining it, and the coach then improvises a
    // sentence about somebody's money. Not theoretical: a FIFTH
    // [ResidencyTierBasis] member was added to this very change, and only the
    // author's diligence put it in the description.
    assertTrue(
      FigureStatus.entries.all { description.contains("\"${it.value}\"") },
      "every figure status the payload can emit must be named in the description: [$description]",
    )
    assertTrue(
      ResidencyTierBasis.entries.all { description.contains("\"${it.value}\"") },
      "every residency-tier basis the payload can emit must be named in the description: [$description]",
    )
    assertTrue(
      description.contains("The other ways of living stay in ${CollegeCostChatTool.BREAKDOWN_KEY} and stay true"),
      "the breakdown is never filtered (D2): a what-if stays answerable from the same result",
    )
    assertTrue(
      description.contains("never add figures from the two different years together"),
      "RFC 149 D-F rule 3, said to the model as well as enforced in the types",
    )
    assertTrue(
      description.contains("quote a number with the year of the key that lists it") &&
        description.contains(CollegeCostChatTool.ACADEMIC_YEAR_KEY) &&
        description.contains(CollegeCostChatTool.DATED_FIGURES_KEY),
      "each vintage key names the figures it dates, and the description must say how to read that",
    )
    assertTrue(
      description.contains("\"${CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY}\": true"),
      "the known-true housing answer must be readable as one too",
    )
    // The year gap is a status the code alone cannot tell apart -- it and a cell
    // we have never collected are both `not_collected_by_us` -- so the key that
    // DOES tell them apart must be named, or the coach reads the narrower case
    // as the wider one.
    assertTrue(
      description.contains(CollegeCostChatTool.HELD_ACADEMIC_YEAR_KEY) &&
        description.contains("never carry that figure into a total"),
      "the held year must be named as a key to read, not left inside a sentence: [$description]",
    )
  }

  // ---------------------------------------------------------------------------
  // The comparison contract (RFC 151)
  // ---------------------------------------------------------------------------

  /** The per-call comparison object, or null when the payload carries none. */
  private fun comparisonBasisOf(result: JsonObject): JsonObject? = result[CollegeCostChatTool.COMPARISON_BASIS_KEY]?.jsonObject

  private fun statementOf(basis: JsonObject): String =
    basis
      .getValue(CollegeCostChatTool.STATEMENT_KEY)
      .jsonPrimitive.content

  /**
   * The strings under [key], or NULL when the key is absent -- the two are never
   * the same fact in this payload, whose whole convention is absent-never-empty.
   * Folding them together would let an assertion of `emptyList()` pass against a
   * key the tool must never emit empty.
   */
  private fun stringsOf(
    basis: JsonObject,
    key: String,
  ): List<String>? =
    basis[key]
      ?.jsonArray
      ?.map { it.jsonPrimitive.content }

  @Test
  fun `comparison_basis is absent for one college and present for two`() {
    val student = createStudent()
    val alone = seedListedCollege(student, "Alone U")
    val together = seedListedCollege(student, "Together U")
    answerResidency(student, "CA")

    assertNull(
      comparisonBasisOf(execute(student, """{"college_ids":["${alone.value}"]}""")),
      "an absent key, never an empty object: a one-school answer is not a comparison",
    )
    val basis = assertNotNull(comparisonBasisOf(execute(student, """{"college_ids":["${alone.value}","${together.value}"]}""")))
    assertEquals(
      setOf(
        "population",
        "residency",
        CollegeCostChatTool.BLENDED_FIGURE_BASIS_KEY,
        "living_arrangement",
        "academic_years",
        "aid",
      ),
      basis.keys,
      "the SIX facts that make a table honest, and nothing else: [$basis]",
    )
  }

  @Test
  fun `the sixth fact says which residency the blended figures are on, per school`() {
    // RFC 157 D-C. This assertion was `setOf(... five keys ...)` above, and it
    // failed the moment the sixth fact landed. That was the correct alarm: the
    // five-key set was a design decision -- residency means tuition -- and this
    // slice changed it deliberately. It is UPDATED rather than deleted, so a
    // SEVENTH fact still has to be argued for here.
    val student = createStudent()
    seedListedCollege(student, "Out Of State Public U", state = "CA", control = 1)
    seedListedCollege(student, "Home Private U", state = "WA", control = 2)
    answerResidency(student, "WA")

    val basis = assertNotNull(comparisonBasisOf(execute(student)))
    val blended = basis.getValue(CollegeCostChatTool.BLENDED_FIGURE_BASIS_KEY).jsonObject

    assertEquals(
      BlendedFigureScope.WITHHELD_AT_SOME_SCHOOLS.value,
      blended.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    assertEquals("answered", blended.getValue("status").jsonPrimitive.content)
    assertEquals("WA", blended.getValue("residency_state").jsonPrimitive.content)
    assertTrue(
      statementOf(blended).contains("Out Of State Public U"),
      "the sentence names the school these figures do not describe: [${statementOf(blended)}]",
    )

    val byCollege = blended.getValue("by_college").jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("Out Of State Public U", "Home Private U"),
      byCollege.map { it.getValue("name").jsonPrimitive.content },
    )
    assertEquals(
      listOf(false, true),
      byCollege.map { it.getValue(CollegeCostChatTool.APPLIES_KEY).jsonPrimitive.boolean },
      "the public school outside the family's state is not theirs; the private one is",
    )
    byCollege.forEach { entry ->
      assertTrue(statementOf(entry).isNotEmpty(), "a code on the wire rides with the sentence for it: [$entry]")
    }
  }

  @Test
  fun `an unanswered residency writes no applies flag and withholds nothing`() {
    // RFC 157 D-B: unknown is not false. Writing `false` here would tell the
    // coach these figures are somebody else's when all we know is we never asked.
    val student = createStudent()
    seedListedCollege(student, "Unknown One U", state = "CA", control = 1)
    seedListedCollege(student, "Unknown Two U", state = "NY", control = 1)

    val result = execute(student)
    val basis = assertNotNull(comparisonBasisOf(result))
    val blended = basis.getValue(CollegeCostChatTool.BLENDED_FIGURE_BASIS_KEY).jsonObject

    assertEquals(
      BlendedFigureScope.RESIDENCY_NOT_ON_FILE.value,
      blended.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    blended.getValue("by_college").jsonArray.map { it.jsonObject }.forEach { entry ->
      assertNull(entry[CollegeCostChatTool.APPLIES_KEY], "an open question is not an answer: [$entry]")
      // ...and the state itself is still SAID, so the third outcome does not
      // ride on a key that is not there (RFC 157 tier 2).
      assertEquals(
        BlendedFigureApplicability.BASIS_STATED.value,
        entry.getValue(CollegeCostChatTool.APPLIES_BASIS_KEY).jsonPrimitive.content,
        "the open question says itself rather than being inferred from an absence: [$entry]",
      )
    }
    collegesOf(result).forEach { college ->
      assertEquals(
        BlendedFigureApplicability.BASIS_STATED.value,
        college.getValue(CollegeCostChatTool.APPLIES_BASIS_KEY).jsonPrimitive.content,
        "and the per-college object says it too: [$college]",
      )
      assertNull(college[CollegeCostChatTool.WITHHELD_FIGURES_KEY], "nothing is withheld on an open question")
      assertNotNull(
        college[CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName],
        "the only price we hold is still shown, with its basis said: [$college]",
      )
    }
  }

  @Test
  fun `a non-matching public college emits neither blended figure, and says why`() {
    // The guard RFC 157 asks for, at the surface that speaks to the model.
    val student = createStudent()
    seedListedCollege(student, "Far Away Public U", state = "CA", control = 1)
    answerResidency(student, "WA")

    val college = collegesOf(execute(student)).single()

    assertNull(
      college[CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName],
      "an in-state published price must not reach an out-of-state family: [$college]",
    )
    assertNull(
      college
        .getValue(CostField.NET_PRICE.wireName)
        .jsonObject["amount_usd"],
      "and neither must the in-state net price: [$college]",
    )

    val withheld =
      college
        .getValue(CollegeCostChatTool.WITHHELD_FIGURES_KEY)
        .jsonArray
        .map { it.jsonObject }
    assertEquals(
      listOf(
        CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName,
        CostField.NET_PRICE.wireName,
      ),
      withheld.map { it.getValue("field").jsonPrimitive.content },
    )
    withheld.forEach { entry ->
      assertEquals(
        WithheldReason.IN_STATE_ONLY_FIGURE.value,
        entry.getValue(CollegeCostChatTool.WITHHELD_REASON_KEY).jsonPrimitive.content,
      )
      assertTrue(
        statementOf(entry).contains("out-of-state tuition and fees are the ones that apply"),
        "the blank points at the figures that ARE this family's: [$entry]",
      )
    }

    val availability = dataAvailabilityOf(college)
    assertTrue(
      availability.containsAll(withheld.map { it.getValue("field").jsonPrimitive.content }),
      "a figure with no number here must be named where the coach is told not to improvise: [$availability]",
    )
  }

  @Test
  fun `a withheld net price says so on its own object, and keeps the band the family answered`() {
    // Two conflations at once, both removed here (RFC 157 tier 2): an object
    // carrying a basis and no amount read exactly like the school's silence,
    // and a `your_income_band` basis shipped with no income_band_label breaks an
    // invariant the tool DESCRIPTION states.
    val student = createStudent()
    seedListedCollege(student, "Band Withheld U", state = "CA", control = 1)
    answerResidency(student, "WA")
    answerBand(student, IncomeBand.OVER_110K)

    val netPrice =
      collegesOf(execute(student))
        .single()
        .getValue(CostField.NET_PRICE.wireName)
        .jsonObject

    assertNull(netPrice["amount_usd"], "the in-state figure is not this family's")
    assertEquals("your_income_band", netPrice.getValue("basis").jsonPrimitive.content)
    assertEquals(
      IncomeBand.OVER_110K.value,
      netPrice.getValue("income_band").jsonPrimitive.content,
      "the family answered the income question, and that fact is theirs either way",
    )
    assertEquals(
      IncomeBand.OVER_110K.bracket,
      netPrice.getValue("income_band_label").jsonPrimitive.content,
      "a band-specific basis always carries its label, withheld or not",
    )
    assertEquals(
      WithheldReason.IN_STATE_ONLY_FIGURE.value,
      netPrice.getValue(CollegeCostChatTool.WITHHELD_REASON_KEY).jsonPrimitive.content,
      "our rule is never readable as the school's silence: [$netPrice]",
    )
    assertTrue(
      statementOf(netPrice).contains("out-of-state tuition and fees"),
      "and the blank points at the figures that ARE this family's: [$netPrice]",
    )
  }

  @Test
  fun `a ONE-college answer still states whether the blended figures are this family's`() {
    // RFC 157 D-B: comparison_basis needs two colleges, so a one-college result
    // is exactly where the basis would otherwise go unsaid. The per-college key
    // is written for the two known answers and absent for the open one.
    val student = createStudent()
    seedListedCollege(student, "Only Public U", state = "CA", control = 1)
    answerResidency(student, "WA")

    val result = execute(student)
    val college = collegesOf(result).single()

    assertNull(comparisonBasisOf(result), "one college is not a comparison")
    assertEquals(
      false,
      college.getValue(CollegeCostChatTool.APPLIES_KEY).jsonPrimitive.boolean,
      "the coach is told these two figures are not this family's here: [$college]",
    )
  }

  @Test
  fun `an in-state one-college answer says the figures ARE this family's`() {
    val student = createStudent()
    seedListedCollege(student, "Home Public U", state = "CA", control = 1)
    answerResidency(student, "CA")

    val college = collegesOf(execute(student)).single()

    assertEquals(
      true,
      college.getValue(CollegeCostChatTool.APPLIES_KEY).jsonPrimitive.boolean,
      "an in-state family is the family these figures describe: [$college]",
    )
  }

  @Test
  fun `every basis fact carries both a code and a non-empty statement`() {
    val student = createStudent()
    seedListedCollege(student, "Basis One U", state = "CA", control = 1)
    seedListedCollege(student, "Basis Two U", state = "NY", control = 2)
    answerResidency(student, "CA")

    val basis = assertNotNull(comparisonBasisOf(execute(student)))

    val population = basis.getValue("population").jsonObject
    assertEquals(PopulationBasis.CODE, population.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content)
    assertTrue(statementOf(population).isNotEmpty())

    val aid = basis.getValue("aid").jsonObject
    assertEquals(AidBasis.CODE, aid.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content)
    assertTrue(
      statementOf(aid).contains("Loans and work-study are never subtracted"),
      "the one fact the payload has never carried: aid here is grants and scholarships: [${statementOf(aid)}]",
    )

    val residency = basis.getValue("residency").jsonObject
    assertEquals("answered", residency.getValue("status").jsonPrimitive.content)
    assertEquals("CA", residency.getValue("residency_state").jsonPrimitive.content)
    assertTrue(statementOf(residency).isNotEmpty())
    val byCollege = residency.getValue("by_college").jsonArray.map { it.jsonObject }
    assertEquals(listOf("Basis One U", "Basis Two U"), byCollege.map { it.getValue("name").jsonPrimitive.content })
    assertEquals(
      listOf(TuitionApplicable.IN_STATE.value, ComparedTuition.SinglePublishedPrice.code),
      byCollege.map { it.getValue(CollegeCostChatTool.TUITION_BASIS_KEY).jsonPrimitive.content },
      "residency is the one per-school element inside the per-call object (RFC 151 D-A)",
    )
    byCollege.forEach { entry ->
      assertNull(
        entry["tuition_applicable"],
        "the per-college key's name is not reused for a different vocabulary here: [$entry]",
      )
    }
    assertEquals(
      ResidencyScope.MIXED.value,
      residency.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
      "the shape of the table rides as a code beside its sentence, like every other fact here",
    )
    byCollege.forEach { entry ->
      assertTrue(statementOf(entry).isNotEmpty(), "a code on the wire rides with the sentence for it: [$entry]")
      assertNotNull(entry["college_id"], "each entry names the school it is about: [$entry]")
    }

    val renderedIds = byCollege.map { it.getValue("college_id").jsonPrimitive.content }.toSet()

    val years = basis.getValue("academic_years").jsonArray.map { it.jsonObject }
    assertTrue(years.isNotEmpty())
    years.forEach { year ->
      assertTrue(
        year
          .getValue(CollegeCostChatTool.BASIS_KEY)
          .jsonPrimitive.content
          .isNotEmpty(),
      )
      assertTrue(
        year
          .getValue(CollegeCostChatTool.ACADEMIC_YEAR_KEY)
          .jsonPrimitive.content
          .contains("-"),
      )
      assertTrue(
        assertNotNull(stringsOf(year, CollegeCostChatTool.DATED_FIGURES_KEY)).isNotEmpty(),
        "a year names the figures it dates",
      )
      // The subject is matched on, not read: every other cross-reference in this
      // payload is by college_id, and names collide in the real corpus.
      val subjects = year.getValue(CollegeCostChatTool.DATED_COLLEGES_KEY).jsonArray.map { it.jsonObject }
      assertTrue(subjects.isNotEmpty(), "a year names the schools it is true of: [$year]")
      subjects.forEach { subject ->
        assertTrue(
          subject.getValue("college_id").jsonPrimitive.content in renderedIds,
          "a dated school is one of the schools this call rendered: [$subject]",
        )
        assertTrue(
          subject
            .getValue("name")
            .jsonPrimitive.content
            .isNotEmpty(),
        )
      }
      assertTrue(statementOf(year).isNotEmpty())
    }
  }

  @Test
  fun `the living arrangement names what is comparable and who is missing one, with the reason`() {
    val student = createStudent()
    seedListedCollege(student, "Every Way U")
    CostsTestDb
      .seedCollege(
        "No Dorms U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ).also { CostsTestDb.addToCollegeList(student, it) }
    answerResidency(student, "CA")

    val arrangement =
      assertNotNull(comparisonBasisOf(execute(student)))
        .getValue("living_arrangement")
        .jsonObject
    assertEquals(
      listOf(LivingArrangement.OFF_CAMPUS.value, LivingArrangement.WITH_FAMILY.value),
      stringsOf(arrangement, "comparable"),
      "only a way of living every school is priced for may be held constant",
    )
    val gap =
      arrangement
        .getValue("incomplete_by_college")
        .jsonArray
        .single()
        .jsonObject
    assertEquals("No Dorms U", gap.getValue("name").jsonPrimitive.content)
    assertEquals(listOf(LivingArrangement.ON_CAMPUS.value), stringsOf(gap, "missing"))
    assertEquals(
      ArrangementGap.NO_ON_CAMPUS_HOUSING.value,
      gap.getValue("reason").jsonPrimitive.content,
      "a school with no residence halls is stated as such, never as missing data",
    )
    assertTrue(statementOf(arrangement).isNotEmpty())
  }

  @Test
  fun `the chosen living arrangement is emitted after the breakdown and before the vintage labels`() {
    // The ordering is load-bearing and documented in place: the key re-keys a
    // figure the breakdown already emitted, so a reader that met it BELOW the
    // vintage labels would meet a dollar figure with no academic year beside
    // it, and nothing -- no type, no other test -- would fail for it.
    val student = createStudent()
    seedListedCollege(student, "Ordered U")
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val college = collegesOf(execute(student)).single()
    val keys = college.keys.toList()
    assertTrue(
      keys.indexOf(CollegeCostChatTool.BREAKDOWN_KEY) < keys.indexOf(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY),
      "the three arrangements are met before the one to lead with: [$keys]",
    )
    assertTrue(
      keys.indexOf(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY) < keys.indexOf(FigureGroup.PUBLISHED_PRICE.wireName),
      "and the chosen arrangement sits above the vintage labels: [$keys]",
    )
  }

  @Test
  fun `an assumed usual plan is rendered with its label, its total and the assumption named`() {
    val student = createStudent()
    seedListedCollege(student, "At Home U")
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val chosen =
      collegesOf(execute(student))
        .single()
        .getValue(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY)
        .jsonObject
    assertEquals(LivingArrangement.WITH_FAMILY.value, chosen.getValue("arrangement").jsonPrimitive.content)
    assertEquals(
      LivingArrangement.WITH_FAMILY.label,
      chosen.getValue("label").jsonPrimitive.content,
      "the wire name never travels without the words a student says it in",
    )
    assertEquals(LivingPlanSource.PROFILE_DEFAULT.value, chosen.getValue("source").jsonPrimitive.content)
    assertEquals(
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      chosen
        .getValue(CollegeCostChatTool.TOTAL_KEY)
        .jsonPrimitive.content
        .toInt(),
      "the total is the breakdown's own for that arrangement, never re-summed here",
    )
    assertEquals(
      LivingPlanPricing.PRICED.value,
      chosen.getValue(CollegeCostChatTool.PRICING_KEY).jsonPrimitive.content,
      "the priced case says so as a code, beside the number it leads with",
    )
    assertNull(chosen[CollegeCostChatTool.ARRANGEMENT_REASON_KEY], "a priced plan has no reason to give")
    assertTrue(
      statementOf(chosen).contains("usual plan, assumed"),
      "the assumption is named in the same breath: [${statementOf(chosen)}]",
    )
    // The at-home arrangement carries no housing and food line, and the tool
    // never renders one as a zero (brief 0003 D12).
    val atHome =
      collegesOf(execute(student))
        .single()
        .getValue(CollegeCostChatTool.BREAKDOWN_KEY)
        .jsonObject
        .getValue(LivingArrangement.WITH_FAMILY.value)
        .jsonObject
    assertNull(
      atHome[CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName],
      "living at home carries no housing and food line at all: [$atHome]",
    )
    assertNull(atHome[CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD.wireName])
  }

  @Test
  fun `a school's own plan renders as told-us-so, and the other arrangements stay in the payload`() {
    val student = createStudent()
    val college = seedListedCollege(student, "Override U")
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)

    val rendered = collegesOf(execute(student)).single()
    val chosen = rendered.getValue(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY).jsonObject
    assertEquals(LivingArrangement.ON_CAMPUS.value, chosen.getValue("arrangement").jsonPrimitive.content)
    assertEquals(LivingPlanSource.PER_COLLEGE.value, chosen.getValue("source").jsonPrimitive.content)
    assertTrue(
      statementOf(chosen).contains("said they plan on"),
      "a per-college plan is something the family SAID, not an assumption: [${statementOf(chosen)}]",
    )
    assertEquals(
      LivingArrangement.entries.map { it.value },
      rendered
        .getValue(CollegeCostChatTool.BREAKDOWN_KEY)
        .jsonObject.keys
        .toList(),
      "a chosen plan leads; it never removes a true fact from the payload (D2)",
    )
  }

  @Test
  fun `a school not priced for the chosen plan carries a reason and no total`() {
    val student = createStudent()
    CostsTestDb
      .seedCollege(
        "No Dorms U",
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
        ipedsHousing = CostsTestDb.IpedsHousing.DOES_NOT_OFFER,
      ).also { CostsTestDb.addToCollegeList(student, it) }
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.ON_CAMPUS)

    val chosen =
      collegesOf(execute(student))
        .single()
        .getValue(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY)
        .jsonObject
    assertEquals(LivingArrangement.ON_CAMPUS.value, chosen.getValue("arrangement").jsonPrimitive.content)
    assertEquals(
      LivingPlanPricing.NOT_PRICED_HERE.value,
      chosen.getValue(CollegeCostChatTool.PRICING_KEY).jsonPrimitive.content,
    )
    assertEquals(
      ArrangementGap.NO_ON_CAMPUS_HOUSING.value,
      chosen.getValue(CollegeCostChatTool.ARRANGEMENT_REASON_KEY).jsonPrimitive.content,
    )
    assertNull(chosen[CollegeCostChatTool.TOTAL_KEY], "there is no total to give, and none is invented")
    assertTrue(
      statementOf(chosen).contains("no residence halls") &&
        statementOf(chosen).contains("never quote another way of living in its place"),
      "the reason is said plainly and no arrangement is substituted: [${statementOf(chosen)}]",
    )
  }

  @Test
  fun `a chosen plan the school cannot total says so, and never ships a silent blank`() {
    // The third shape, stated. The school shows this way of living but has no
    // total for it, so the key carries no number -- and its own pricing code
    // plus a no-total reason are what make that readable. The reason rides
    // under its OWN key, never under the one that says what the SCHOOL
    // published, which is the misattribution RFC 149 D-B forbids.
    val student = createStudent()
    CostsTestDb
      .seedCollege("Silent Part U", housingAndFoodOffCampusPerYearUsd = null)
      .also { CostsTestDb.addToCollegeList(student, it) }
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val chosen =
      collegesOf(execute(student))
        .single()
        .getValue(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY)
        .jsonObject
    assertEquals(LivingArrangement.OFF_CAMPUS.value, chosen.getValue("arrangement").jsonPrimitive.content)
    assertNull(chosen[CollegeCostChatTool.TOTAL_KEY], "there is no total to give, and none is invented")
    assertEquals(
      LivingPlanPricing.NO_TOTAL_HERE.value,
      chosen.getValue(CollegeCostChatTool.PRICING_KEY).jsonPrimitive.content,
      "the case is a code, never three keys a reader has to notice the absence of",
    )
    assertEquals(
      NoTotalReason.PART_NOT_PUBLISHED.value,
      chosen.getValue(CollegeCostChatTool.NO_TOTAL_REASON_KEY).jsonPrimitive.content,
      "this school published parts of it, so the missing part is the school's own silence",
    )
    assertNull(
      chosen[CollegeCostChatTool.ARRANGEMENT_REASON_KEY],
      "and never the SCHOOL-gap key: that vocabulary states what a price list does not carry",
    )
    assertTrue(
      statementOf(chosen).contains("does not publish every part of what that way of living costs") &&
        statementOf(chosen).contains("never add up what is there and call it the total"),
      "the missing total is stated, and a partial sum is refused in the same breath: [${statementOf(chosen)}]",
    )
  }

  @Test
  fun `an open residency question names the gap as ours, and never as the school's price list`() {
    // The same no-total shape, the other cause. A public school with our
    // residency question still open publishes its prices in full, so the coach
    // must be told the gap is OURS and what closes it -- never that this school
    // publishes no price for that way of living (RFC 149 D-B).
    val student = createStudent()
    seedListedCollege(student, "Public Pending U")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val chosen =
      collegesOf(execute(student))
        .single()
        .getValue(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY)
        .jsonObject
    assertEquals(
      LivingPlanPricing.NO_TOTAL_HERE.value,
      chosen.getValue(CollegeCostChatTool.PRICING_KEY).jsonPrimitive.content,
    )
    assertEquals(
      NoTotalReason.AWAITING_RESIDENCY_ANSWER.value,
      chosen.getValue(CollegeCostChatTool.NO_TOTAL_REASON_KEY).jsonPrimitive.content,
      "our own open question, said as our own: the two causes of a missing total are two codes",
    )
    assertNull(chosen[CollegeCostChatTool.TOTAL_KEY], "and no total, because none applies until residency is answered")
    assertTrue(
      statementOf(chosen).contains("the gap is ours and not this school's") &&
        statementOf(chosen).contains("Ask where the student is a resident"),
      "the sentence says whose gap it is and what would close it: [${statementOf(chosen)}]",
    )
  }

  @Test
  fun `no plan and no override emits no chosen_living_arrangement key at all`() {
    val student = createStudent()
    seedListedCollege(student, "Unchosen U")

    val college = collegesOf(execute(student)).single()
    assertNull(
      college[CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY],
      "an absent key, never a null one: the family has said nothing, and the answer is what it always was",
    )
    assertEquals(
      LivingArrangement.entries.map { it.value },
      college
        .getValue(CollegeCostChatTool.BREAKDOWN_KEY)
        .jsonObject.keys
        .toList(),
    )
  }

  @Test
  fun `a declined plan emits no chosen key and no living-plan offer, and echoes the decline`() {
    val student = createStudent()
    seedListedCollege(student, "Declined Plan U")
    declineLivingPlan(student)

    val result = execute(student)
    val college = collegesOf(result).single()
    assertNull(college[CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY])
    assertTrue(
      PrecisionOffer.LIVING_PLAN.field !in offerFieldsOf(college),
      "a declined plan is accepted permanently - the coach is never cued to reopen it",
    )
    val profile = result.getValue("money_profile").jsonObject
    assertEquals("declined", profile["living_plan_status"]!!.jsonPrimitive.content)
    assertNull(profile["living_plan"], "a declined field carries no value")
    assertNull(profile["living_plan_label"])
  }

  @Test
  fun `the money_profile echo carries the plan with its spoken label, and the offer copy names the write tool`() {
    val student = createStudent()
    seedListedCollege(student, "Echo U")
    answerLivingPlan(student, LivingArrangement.OFF_CAMPUS)

    val result = execute(student)
    val profile = result.getValue("money_profile").jsonObject
    assertEquals("answered", profile.getValue("living_plan_status").jsonPrimitive.content)
    assertEquals(LivingArrangement.OFF_CAMPUS.value, profile.getValue("living_plan").jsonPrimitive.content)
    assertEquals(LivingArrangement.OFF_CAMPUS.label, profile.getValue("living_plan_label").jsonPrimitive.content)

    // And the offer's own copy, on a student who has NOT answered the plan.
    // Residency IS answered, because the offer rests on PRICED arrangements and
    // a public school with no residency on file totals none of them (D4).
    val other = createStudent()
    seedListedCollege(other, "Offer U")
    answerResidency(other, "CA")
    val offer = offerCopyOf(collegesOf(execute(other)).single(), PrecisionOffer.LIVING_PLAN.field)!!
    assertTrue(offer.contains(MoneyProfileChatTool.TOOL_NAME), "the offer must name the recording tool")
    assertTrue(
      offer.contains("one price picture instead of three"),
      "and say what the answer unlocks, promising a narrowing rather than a number: [$offer]",
    )
  }

  @Test
  fun `the arrangement basis renders its code, the family's answer, and the per-school plans when they differ`() {
    val student = createStudent()
    val near = seedListedCollege(student, "Nearby U")
    val far = seedListedCollege(student, "Faraway U")
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)
    CostsTestDb.setEntryLivingPlan(student, far, LivingArrangement.ON_CAMPUS)

    val arrangement =
      assertNotNull(comparisonBasisOf(execute(student)))
        .getValue("living_arrangement")
        .jsonObject
    assertEquals(
      ArrangementScope.PLAN_VARIES_BY_SCHOOL.value,
      arrangement.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
      "the one comparison fact that used to ship only lists now ships a code too (D5)",
    )
    assertEquals("answered", arrangement.getValue("living_plan_status").jsonPrimitive.content)
    assertEquals(LivingArrangement.WITH_FAMILY.value, arrangement.getValue("living_plan").jsonPrimitive.content)
    assertEquals(
      LivingArrangement.WITH_FAMILY.label,
      arrangement.getValue("living_plan_label").jsonPrimitive.content,
      "the family's usual plan never reaches the wire without the words they say it in",
    )
    val byCollege =
      arrangement
        .getValue("chosen_by_college")
        .jsonArray
        .map { it.jsonObject }
        .associateBy { it.getValue("name").jsonPrimitive.content }
    assertEquals(
      LivingArrangement.WITH_FAMILY.value,
      byCollege
        .getValue("Nearby U")
        .getValue("arrangement")
        .jsonPrimitive.content,
    )
    assertEquals(
      LivingPlanSource.PROFILE_DEFAULT.value,
      byCollege
        .getValue("Nearby U")
        .getValue("source")
        .jsonPrimitive.content,
    )
    assertEquals(
      LivingArrangement.ON_CAMPUS.value,
      byCollege
        .getValue("Faraway U")
        .getValue("arrangement")
        .jsonPrimitive.content,
    )
    assertEquals(
      LivingPlanSource.PER_COLLEGE.value,
      byCollege
        .getValue("Faraway U")
        .getValue("source")
        .jsonPrimitive.content,
    )
    assertEquals(
      LivingArrangement.entries.map { it.value },
      stringsOf(arrangement, "comparable"),
      "and the comparable list is not narrowed by any of it (D2)",
    )
    assertTrue(near != far, "two distinct schools, or the case is not the one under test")
  }

  @Test
  fun `with one plan everywhere the arrangement basis ships the code without a per-school list`() {
    val student = createStudent()
    seedListedCollege(student, "Home One U")
    seedListedCollege(student, "Home Two U")
    answerResidency(student, "CA")
    answerLivingPlan(student, LivingArrangement.WITH_FAMILY)

    val arrangement =
      assertNotNull(comparisonBasisOf(execute(student)))
        .getValue("living_arrangement")
        .jsonObject
    assertEquals(
      ArrangementScope.ONE_PLAN_EVERY_SCHOOL.value,
      arrangement.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    assertNull(
      arrangement["chosen_by_college"],
      "with one plan everywhere the statement already says it; a per-school list would invite the coach " +
        "to narrate a difference there is none of",
    )
  }

  @Test
  fun `with no plan on file the arrangement basis ships its code and no plan keys`() {
    val student = createStudent()
    seedListedCollege(student, "Quiet One U")
    seedListedCollege(student, "Quiet Two U")
    answerResidency(student, "CA")

    val arrangement =
      assertNotNull(comparisonBasisOf(execute(student)))
        .getValue("living_arrangement")
        .jsonObject
    assertEquals(
      ArrangementScope.NO_PLAN_COMPARABLE.value,
      arrangement.getValue(CollegeCostChatTool.BASIS_KEY).jsonPrimitive.content,
    )
    assertEquals("unanswered", arrangement.getValue("living_plan_status").jsonPrimitive.content)
    assertNull(arrangement["living_plan"], "an unanswered plan reaches the wire as a status and nothing more")
    assertNull(arrangement["living_plan_label"])
    assertNull(arrangement["chosen_by_college"])
  }

  @Test
  fun `a comparison with nothing missing carries no incomplete_by_college key`() {
    val student = createStudent()
    seedListedCollege(student, "Matched One U")
    seedListedCollege(student, "Matched Two U")
    answerResidency(student, "CA")

    val arrangement =
      assertNotNull(comparisonBasisOf(execute(student)))
        .getValue("living_arrangement")
        .jsonObject
    assertNull(arrangement["incomplete_by_college"], "an absent key, never an empty array")
    assertEquals(
      LivingArrangement.entries.map { it.value },
      stringsOf(arrangement, "comparable"),
    )
  }

  /**
   * Every figure this payload ACTUALLY dated, read off the per-college vintage
   * keys rather than from the domain -- so the binding below compares the wire
   * with the wire.
   */
  private fun datedFiguresOf(result: JsonObject): Set<String> =
    collegesOf(result)
      .flatMap { college ->
        FigureGroup.entries.flatMap { vintage ->
          college[vintage.wireName]?.jsonObject?.let { stringsOf(it, CollegeCostChatTool.DATED_FIGURES_KEY) }.orEmpty()
        }
      }.toSet()

  @Test
  fun `the academic years date exactly the figures the payload rendered`() {
    // The one binding that keeps the two sides honest: a field the comparison
    // dates but no college rendered is a year attached to nothing, and a
    // rendered figure the comparison forgets is a number said with no year.
    // Neither can be caught by reading either side alone.
    val student = createStudent()
    seedListedCollege(student, "Complete U")
    seedListedCollege(
      student,
      "Sparser U",
      stickerCostOfAttendancePerYearUsd = null,
      tuitionAndFeesOutOfStatePerYearUsd = null,
      medianDebtAtCompletionUsd = null,
    )
    answerResidency(student, "CA")

    val payload = execute(student)
    val basis = assertNotNull(comparisonBasisOf(payload))
    val dated =
      basis
        .getValue("academic_years")
        .jsonArray
        .flatMap { assertNotNull(stringsOf(it.jsonObject, CollegeCostChatTool.DATED_FIGURES_KEY)) }
        .toSet()

    assertTrue(dated.isNotEmpty(), "these fixtures carry both vintages, so the guard must have something to compare")
    assertEquals(
      datedFiguresOf(payload),
      dated,
      "the comparison may date no figure the payload did not render, and may forget none that it did: [$basis]",
    )
  }

  @Test
  fun `a comparison that dates nothing carries no academic_years key`() {
    // Reachable: two schools reporting only the undated cohort figures. An empty
    // array would be a year list nobody could name, and the object's own
    // convention is absent-never-empty.
    val student = createStudent()
    (1..2).forEach { n ->
      CostsTestDb
        .seedCollege(
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
        ).also { CostsTestDb.addToCollegeList(student, it) }
    }
    answerResidency(student, "CA")

    val basis = assertNotNull(comparisonBasisOf(execute(student)))
    assertNull(basis["academic_years"], "an absent key, never an empty array: [$basis]")
    assertEquals(emptySet(), datedFiguresOf(execute(student)), "and the payload really dates nothing")
    assertNotNull(basis["population"], "the facts that are still true are still stated: [$basis]")
    assertNotNull(basis["residency"], "[$basis]")
  }

  @Test
  fun `the comparison object carries no bare source code`() {
    val student = createStudent()
    seedListedCollege(student, "Guarded One U", state = "CA", control = 1)
    seedListedCollege(student, "Guarded Two U", state = "NY", control = 2)
    answerBand(student, IncomeBand.OVER_110K)
    answerResidency(student, "CA")

    val payload = execute(student)
    val basis = assertNotNull(comparisonBasisOf(payload), "the guard must run over a payload that actually carries it")
    assertEquals(emptyList(), listViolations(payload), "the comparison object must carry no source code")
    assertEquals(emptyList(), listViolations(basis), "and it must be clean read on its own too")
    // Positive control on the nested walk: the guard reaches INSIDE the new
    // object, so a coded number smuggled into it is caught rather than hidden
    // one level down.
    val doctored =
      JsonObject(payload + mapOf(CollegeCostChatTool.COMPARISON_BASIS_KEY to JsonObject(basis + mapOf("control" to JsonPrimitive(2)))))
    assertEquals(listOf(BareSourceCode.BareNumberField("control")), listViolations(doctored))
  }

  @Test
  fun `the description states the comparison contract`() {
    val description =
      tool.definition
        .getValue("description")
        .jsonPrimitive.content

    assertTrue(description.contains(CollegeCostChatTool.COMPARISON_BASIS_KEY), "the new key must be described")
    assertTrue(
      description.contains("two or more colleges"),
      "the model must be told when the object rides: a comparison, never a one-school answer",
    )
    assertTrue(
      description.contains("above the table"),
      "the basis is said as ordinary copy above the table, never as a footnote under it",
    )
    assertTrue(
      description.contains("never build a column from two different bases"),
      "one residency and one way of living per column - the contract this slice exists for",
    )
    assertTrue(
      description.contains("three columns"),
      "RFC 124's cap, restated where the comparison is actually rendered",
    )
    assertTrue(
      description.contains("leave that cell blank and label it as not reported"),
      "a missing part is a labelled blank, never a zero and never a neighbour's figure",
    )
    assertTrue(
      description.contains(CollegeCostChatTool.STATEMENT_KEY) && description.contains(CollegeCostChatTool.BASIS_KEY),
      "each fact carries a code and the sentence to say, and the model must be told to say the sentence",
    )
  }
}

/**
 * The `data_availability` entries of one college, or an EMPTY list when the key
 * is absent.
 *
 * Absent is the normal shape for a school with nothing missing (RFC 157 tier 2):
 * the payload's convention is absent-never-empty, so a reader that called
 * `getValue` would throw on exactly the healthiest college.
 */
private fun dataAvailabilityOf(college: JsonObject): List<String> =
  college[CollegeCostChatTool.DATA_AVAILABILITY_KEY]
    ?.jsonArray
    ?.map { it.jsonPrimitive.content }
    .orEmpty()

/**
 * RFC 161's Austin CC figures (`rfc/161...:16-24`), the measured shape this
 * slice's in-district work is written against: a district price materially
 * below the state one, and a third price for students from outside the state.
 *
 * Named rather than inlined for the reason every other fixture figure here is:
 * an expected tuition line is one of these constants, so a fixture that moves
 * cannot leave an assertion quietly describing a number nothing seeds.
 */
private const val IN_DISTRICT_TUITION_USD = 2550
private const val IN_STATE_TUITION_USD = 8580
private const val OUT_OF_STATE_TUITION_USD = 10590

/**
 * An academic year BEFORE the one the fixture serves prices at
 * (`CostsTestDb.PRICE_ACADEMIC_YEAR`), for the year-gap case: a figure this
 * school published, at a year that is not the one its price is quoted at.
 */
private val OLDER_YEAR = AcademicYear(2021)

/** [OLDER_YEAR] as words, derived rather than a second literal (RFC 170 D14). */
private val OLDER_ACADEMIC_YEAR = OLDER_YEAR.label

/** The fees split (RFC 166 §5): the fees PART of each combined tuition and fees figure above. */
private const val FEES_ONLY_IN_STATE_USD = 1200
private const val FEES_ONLY_OUT_OF_STATE_USD = 1800
private const val FEES_ONLY_IN_DISTRICT_USD = 900

/**
 * Every key `collegeObject` renders for a school with no Common Data Set facts
 * -- the whole key vocabulary of a college, so `a cost answer with no merit row is
 * unchanged` can assert the shape rather than one absent key. The cost measures
 * are read from [CostField], their one home, so a future cost field costs no
 * edit here; a key the merit feed adds anywhere else does.
 */
private val PRE_FEED_COLLEGE_KEYS: Set<String> =
  setOf(
    "college_id",
    "name",
    "city",
    "state",
    "control",
    "list_status",
    "tuition_applicable",
    CollegeCostChatTool.PRECISION_OFFER_KEY,
    CollegeCostChatTool.DATA_AVAILABILITY_KEY,
    // RFC 157: which of the three blended-figure outcomes this school is in,
    // ALWAYS written, so it is part of every college's key vocabulary.
    CollegeCostChatTool.APPLIES_BASIS_KEY,
    // RFC 149's additions: the per-arrangement split, the no-dorms answer, and
    // the two academic-year labels that say which year a figure describes.
    CollegeCostChatTool.BREAKDOWN_KEY,
    CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY,
    // RFC 166: which tuition tiers this school publishes (always written, so it
    // is part of every college's vocabulary) and, when a figure has no number,
    // the store's own reason for it.
    CollegeCostChatTool.RESIDENCY_TIERS_KEY,
    CollegeCostChatTool.FIGURE_STATUSES_KEY,
    // RFC 170: the sentence that says why a college carries no aid_policy
    // section. ALWAYS written when the section is absent, which is exactly the
    // case these "unchanged" assertions describe -- a school with no CDS
    // filing must not be silent about being silent.
    CollegeCostChatTool.AID_POLICY_AVAILABILITY_KEY,
    // RFC 175: the same, for the borrowing section. A school with no filing
    // carries no borrowing figures and says why, in one sentence.
    CollegeCostChatTool.BORROWING_AVAILABILITY_KEY,
  ) + CostField.entries.map { it.wireName } + FigureGroup.entries.map { it.wireName }

// ---------------------------------------------------------------------------
// The generalised source-code guard (RFC 143), hosted in :chat's test fixtures
// since RFC 148 D9 -- the walker is shared, the allowlist stays this tool's own.
// ---------------------------------------------------------------------------

/**
 * The field names whose value is a NUMBER by contract -- the cost measures this
 * tool renders (read from [CostField], their one home), the result count, the
 * net-price `amount_usd`, and the merit measures the RFC 148 feed adds (read from
 * [MeritAidWire], their one home) -- so a number under them is a fact, not a
 * code. Every other numeric field is a coded dimension until sanctioned here;
 * `control` was exactly that, and this list is the one place to admit the next
 * one, deliberately short enough to read in a review.
 *
 * The documented codes the model hands back (`income_band`, which
 * `update_money_profile` accepts, and `college_id`) ride as STRINGS and so
 * never reach this check.
 */
private fun listViolations(payload: JsonElement): List<BareSourceCode> =
  BareSourceCodeGuard.listViolations(payload, CollegeCostChatTool.NUMBERS_BY_CONTRACT)

/**
 * Every key in [element] at ANY depth.
 *
 * The coverage assertion above -- "every cost field the allowlist sanctions is
 * actually in this payload, so the allowlist is exercised rather than vacuously
 * satisfied" -- used to read `college.keys`, which was the whole vocabulary
 * while every cost field was a top-level scalar. RFC 149's six components render
 * INSIDE the arrangement objects, so a flat read would find them missing and,
 * worse, a future nested field would slip past the guard's exercise check
 * unnoticed. The walk mirrors [BareSourceCodeGuard.listNumericFields], which has
 * always been recursive.
 */
private fun keysAnywhereIn(element: JsonElement): Set<String> =
  when (element) {
    is JsonObject -> element.keys + element.values.flatMap { keysAnywhereIn(it) }
    is JsonArray -> element.flatMap { keysAnywhereIn(it) }.toSet()
    else -> emptySet()
  }
