package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
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
    medianEarnings: Int? = 55000,
    stickerCostAttendance: Int? = 40000,
    tuitionInState: Int? = 12000,
    tuitionOutState: Int? = 30000,
    medianDebt: Int? = 23000,
    bandPricing: Boolean = true,
  ): CollegeId {
    val id =
      CostsTestDb.seedCollege(
        name,
        state = state,
        control = control,
        costAttendance = stickerCostAttendance,
        tuitionInState = tuitionInState,
        tuitionOutState = tuitionOutState,
        medianDebt = medianDebt,
        medianEarnings = medianEarnings,
        netPriceQ1 = if (bandPricing) CostsTestDb.NET_PRICE_Q1 else null,
        netPriceQ2 = if (bandPricing) CostsTestDb.NET_PRICE_Q2 else null,
        netPriceQ3 = if (bandPricing) CostsTestDb.NET_PRICE_Q3 else null,
        netPriceQ4 = if (bandPricing) CostsTestDb.NET_PRICE_Q4 else null,
        netPriceQ5 = if (bandPricing) CostsTestDb.NET_PRICE_Q5 else null,
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
      stickerCostAttendance = 40000,
      tuitionInState = 12000,
      tuitionOutState = 30000,
      medianDebt = 23000,
    )
    answerBand(student, IncomeBand.OVER_110K)

    // The whole rendered payload, not one field: the leak this guards against
    // (RFC 142) is a FUTURE field quietly carrying the source's own codes into
    // the model's context, where it will happily say them aloud. RFC 143 makes
    // the assertion the general property rather than a grep for the two tokens
    // that leaked before -- `control` sat inside the old grep's own payload.
    val payload = execute(student)
    assertEquals(emptyList(), bareSourceCodes(payload), "the cost result must carry no source code")
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
            "net_price_q5" to JsonPrimitive(31000),
            "source_column" to JsonPrimitive("NPT41"),
          ),
      )
    assertEquals(
      listOf(
        "quintile code [q5]",
        "source column family [NPT4]",
        "bare code in field [control]",
        "bare code in field [net_price_q5]",
      ),
      bareSourceCodes(doctored),
    )

    // An error envelope is a model-facing tool result too, and a malformed-arg
    // retry is an ordinary path -- so it owes the same property (RFC 143).
    assertEquals(
      emptyList(),
      bareSourceCodes(execute(student, """{"college_ids":["not-a-uuid"]}""")),
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
    seedListedCollege(student, "No Tuition U", control = 1, tuitionInState = null, tuitionOutState = null)
    seedListedCollege(student, "In State Only U", control = 1, tuitionOutState = null)

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
      "tuition_out_state" in
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
    CostsTestDb.connection.createStatement().use { stmt ->
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
    assertEquals(11000, netPrice["amount"]!!.jsonPrimitive.content.toInt(), "30k_to_48k must select net_price_q2")
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
    assertEquals(20000, netPrice["amount"]!!.jsonPrimitive.content.toInt())
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
    assertEquals(9000, netPrice["amount"]!!.jsonPrimitive.content.toInt())
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
    seedListedCollege(student, "Sparse U", medianEarnings = null)

    val college = collegesOf(execute(student)).single()
    assertEquals(
      listOf("median_earnings"),
      college.getValue("data_availability").jsonArray.map { it.jsonPrimitive.content },
    )
    assertNull(college["median_earnings"], "an unreported figure is absent, never invented")
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
    val ids = (0..CollegeCostChatTool.MAX_COLLEGE_IDS).joinToString(",") { "\"${UUID.randomUUID()}\"" }

    val result = execute(student, """{"college_ids":[$ids]}""")
    assertEquals(
      "college_ids must contain at most [${CollegeCostChatTool.MAX_COLLEGE_IDS}] entries, " +
        "got [${CollegeCostChatTool.MAX_COLLEGE_IDS + 1}]",
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
      description.contains("At most ${CollegeCostChatTool.MAX_COLLEGE_IDS}"),
      "the cap must be documented: [$description]",
    )
  }
}

// ---------------------------------------------------------------------------
// The generalised source-code guard (RFC 143)
// ---------------------------------------------------------------------------

/**
 * The field names whose value is a NUMBER by contract -- the cost measures this
 * tool renders (read from [CostField], their one home), the result count, and
 * the net-price `amount` -- so a number under them is a fact, not a code. Every
 * other numeric field is a coded dimension until sanctioned here; `control` was
 * exactly that, and this list is the one place to admit the next one,
 * deliberately short enough to read in a review.
 *
 * The documented codes the model hands back (`income_band`, which
 * `update_money_profile` accepts, and `college_id`) ride as STRINGS and so
 * never reach this check.
 */
private val NUMBERS_BY_CONTRACT = CostField.entries.map { it.wireName }.toSet() + setOf("count", "amount")

// No leading \b, deliberately: `_` is a word character, so `\bq[1-5]\b` does
// NOT match `net_price_q5` -- the very key this guard has to catch.
private val QUINTILE_CODE = Regex("""q[1-5]\b""", RegexOption.IGNORE_CASE)

/**
 * Every way [payload] carries a bare source code, in the general form RFC 143
 * put in place of RFC 142's string-specific grep: a `qN` bucket token, the
 * `NPT4` column family, or any field carrying a bare number that is not a
 * number by contract. Read over the WHOLE payload, so a field added later is
 * covered without anyone remembering to extend the test.
 *
 * Returns the reasons rather than asserting them, so a test can also drive it
 * with a doctored payload and prove it still reacts.
 */
private fun bareSourceCodes(payload: JsonElement): List<String> =
  buildList {
    val text = payload.toString()
    QUINTILE_CODE.find(text)?.let { add("quintile code [${it.value}]") }
    if (text.contains("NPT4")) add("source column family [NPT4]")
    addAll(
      numericFields(payload)
        .filterNot { it in NUMBERS_BY_CONTRACT }
        .map { "bare code in field [$it]" },
    )
  }

/** Every field name in [element], at any depth, whose value is a bare number. */
private fun numericFields(
  element: JsonElement,
  key: String? = null,
): List<String> =
  when (element) {
    is JsonObject -> element.flatMap { (name, value) -> numericFields(value, name) }
    is JsonArray -> element.flatMap { numericFields(it, key) }
    is JsonPrimitive -> if (key != null && !element.isString && element.doubleOrNull != null) listOf(key) else emptyList()
  }
