package ed.unicoach.coaching.costs

import ed.unicoach.chat.BareSourceCode
import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.costs.CostsTestDb.answerBand
import ed.unicoach.coaching.costs.CostsTestDb.answerResidency
import ed.unicoach.coaching.costs.CostsTestDb.createStudent
import ed.unicoach.coaching.costs.CostsTestDb.declineBand
import ed.unicoach.coaching.costs.CostsTestDb.declineResidency
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
      CostField.entries.map { it.wireName }.toSet() - college.keys,
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
      "both upgrades are on offer, and residency is index 0 - the cheaper question, the bigger correction",
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
        listOf(PrecisionOffer.INCOME_BAND.field),
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
      "a declined residency is accepted permanently - the coach is never cued to reopen it",
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
        listOf(PrecisionOffer.INCOME_BAND.field),
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
  fun `a college with nothing to offer carries no precision_offer key`() {
    val student = createStudent()
    // Private, so residency buys nothing; band declined, so the income
    // invitation is closed for good. Nothing is left to offer.
    seedListedCollege(student, "Nothing To Offer U", control = 2)
    declineBand(student)

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
      "a college that publishes neither tuition figure has no upgrade to promise",
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
      "one published figure is still a price residency selects",
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
      "an answered band needs no invitation; residency is still unanswered at this public school",
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
  ) + CostField.entries.map { it.wireName }

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
  CostField.entries.map { it.wireName }.toSet() + setOf("count", "amount_usd") + MeritAidWire.NUMERIC_KEYS

private fun listViolations(payload: JsonElement): List<BareSourceCode> = BareSourceCodeGuard.listViolations(payload, NUMBERS_BY_CONTRACT)
