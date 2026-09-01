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
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  ): CollegeId {
    val id =
      CostsTestDb.seedCollege(
        name,
        state = state,
        control = control,
        costOfAttendancePerYearUsd = stickerCostOfAttendancePerYearUsd,
        tuitionAndFeesInStatePerYearUsd = tuitionAndFeesInStatePerYearUsd,
        tuitionAndFeesOutOfStatePerYearUsd = tuitionAndFeesOutOfStatePerYearUsd,
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
    assertTrue(description.contains("College Scorecard"), "the source must be named")
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
  fun `a student who says they will live at home gets that total, with no housing and food line, in the same turn`() {
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
      "no housing and food line at all - that is data, never a zero: [$atHome]",
    )
    assertNull(atHome[CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD.wireName])
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
      "tuition_and_fees_out_of_state_per_year_usd" in
        inStateOnly
          .getValue("data_availability")
          .jsonArray
          .map { it.jsonPrimitive.content },
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
    assertEquals("U.S. Department of Education College Scorecard", result.getValue("source").jsonPrimitive.content)
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
      college.getValue("data_availability").jsonArray.map { it.jsonPrimitive.content },
    )
    assertNull(college["median_earnings_10y_after_entry_usd"], "an unreported figure is absent, never invented")
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
      college
        .getValue("data_availability")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .contains(MeritAidWire.KEY),
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

  private fun intsOf(arrangement: JsonObject): Map<String, Int> =
    arrangement.mapValues {
      it.value.jsonPrimitive.content
        .toInt()
    }

  /** One vintage's spoken academic year, read from the object the key now carries (RFC 149 D-E). */
  private fun academicYearOf(
    college: JsonObject,
    vintage: ScorecardVintage,
  ): String? =
    college[vintage.wireName]
      ?.jsonObject
      ?.getValue(CollegeCostChatTool.ACADEMIC_YEAR_KEY)
      ?.jsonPrimitive
      ?.content

  /** The wire names one vintage says it dates -- the membership the payload now states in-band. */
  private fun datedFiguresOf(
    college: JsonObject,
    vintage: ScorecardVintage,
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
    assertTrue(
      withFamily.keys.none { it.startsWith("housing_and_food") },
      "no school publishes a housing allowance for a student living at home: [$withFamily]",
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
      college
        .getValue("data_availability")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .contains(CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD.wireName),
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
      college
        .getValue("data_availability")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .contains(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName),
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
      college
        .getValue("data_availability")
        .jsonArray
        .map { it.jsonPrimitive.content }
        .contains(CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD.wireName),
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
    assertEquals(ScorecardVintage.PUBLISHED_PRICE.label, academicYearOf(college, ScorecardVintage.PUBLISHED_PRICE))
    assertEquals(ScorecardVintage.BLENDED_AVERAGE.label, academicYearOf(college, ScorecardVintage.BLENDED_AVERAGE))

    // The year NAMES the figures it dates (RFC 149 D-E): a bare year beside the
    // college object left membership to a convention the payload never states,
    // and the prompt tells the coach to quote the year beside a figure.
    val published = datedFiguresOf(college, ScorecardVintage.PUBLISHED_PRICE)
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
      datedFiguresOf(college, ScorecardVintage.BLENDED_AVERAGE),
      "and the blended year dates ONLY the two blended figures, in CostField declaration order",
    )
    assertTrue(
      datedFiguresOf(college, ScorecardVintage.PUBLISHED_PRICE)
        .intersect(datedFiguresOf(college, ScorecardVintage.BLENDED_AVERAGE).toSet())
        .isEmpty(),
      "no figure is dated by both years, which is what makes 'never add across years' sayable",
    )
    assertTrue(
      datedFiguresOf(college, ScorecardVintage.PUBLISHED_PRICE).none {
        it == CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName || it == CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName
      } &&
        datedFiguresOf(college, ScorecardVintage.BLENDED_AVERAGE).none {
          it == CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName || it == CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName
        },
      "an undated figure is named by neither key: [$college]",
    )

    assertEquals("2022-23", ScorecardVintage.PUBLISHED_PRICE.label, "the academic-year form, rendered once in CdsCitation")
    assertEquals("2021-22", ScorecardVintage.BLENDED_AVERAGE.label)
    assertTrue(
      ScorecardVintage.PUBLISHED_PRICE.label != ScorecardVintage.BLENDED_AVERAGE.label,
      "the components and the blended averages are different years; that is why they are never summed together",
    )

    // The payload attribution stops claiming an ingest year (RFC 149 D-E):
    // updated_at was WHEN WE LOADED THE FILE, never the year of the figures.
    val source = payload.getValue("source").jsonPrimitive.content
    assertEquals(CollegeCostChatTool.SOURCE_ATTRIBUTION, source)
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
    assertNotNull(college[ScorecardVintage.PUBLISHED_PRICE.wireName], "it still publishes a price list")
    assertNull(
      college[ScorecardVintage.BLENDED_AVERAGE.wireName],
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
      college[ScorecardVintage.BLENDED_AVERAGE.wireName],
      "no COSTT4_A and no net price here, so the blended year describes nothing in this object: [$college]",
    )
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
    assertTrue(
      description.contains("never a housing cost of zero"),
      "with_family's missing housing line is absence, never a zero",
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
      setOf("population", "residency", "living_arrangement", "academic_years", "aid"),
      basis.keys,
      "the five facts that make a table honest, and nothing else: [$basis]",
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
      keys.indexOf(CollegeCostChatTool.CHOSEN_ARRANGEMENT_KEY) < keys.indexOf(ScorecardVintage.PUBLISHED_PRICE.wireName),
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
        ScorecardVintage.entries.flatMap { vintage ->
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
 * Every key `collegeObject` rendered BEFORE the RFC 148 merit feed -- the whole
 * key vocabulary of a college, so `a cost answer with no merit row is
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
    "data_availability",
    // RFC 149's additions: the per-arrangement split, the no-dorms answer, and
    // the two academic-year labels that say which year a figure describes.
    CollegeCostChatTool.BREAKDOWN_KEY,
    CollegeCostChatTool.OFFERS_ON_CAMPUS_HOUSING_KEY,
  ) + CostField.entries.map { it.wireName } + ScorecardVintage.entries.map { it.wireName }

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
private val NUMBERS_BY_CONTRACT =
  CostField.entries.map { it.wireName }.toSet() +
    setOf("count", "amount_usd", CollegeCostChatTool.TOTAL_KEY) +
    MeritAidWire.NUMERIC_KEYS

private fun listViolations(payload: JsonElement): List<BareSourceCode> = BareSourceCodeGuard.listViolations(payload, NUMBERS_BY_CONTRACT)

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
