package ed.unicoach.family

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.costs.CollegeCostChatTool
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.CostsTestDb
import ed.unicoach.coaching.costs.LivingPlanPricing
import ed.unicoach.coaching.costs.SpokenAssurance
import ed.unicoach.coaching.costs.WithheldReason
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeLevel
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UnlinkOutcome
import ed.unicoach.db.models.UserId
import ed.unicoach.queue.QueueService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `family_money_plan` (RFC 261) over the real database: the all-years figures
 * are exactly the one-year figures times four, at four-year schools only, each
 * dated and attributed; every case with no total says why in the cost
 * picture's own words and carries no number; a severed link refuses.
 */
class FamilyMoneyPlanChatToolTest {
  private val database = CostsTestDb.database
  private val sqlSession = CostsTestDb.sqlSession
  private val sharingPermissions = SharingPermissionService(database)
  private val subjects = SubjectReadService(database, CollegeListService(database), CollegeCostService(database), sharingPermissions)
  private val supporters = SupporterService(database, LinkEndedNotice(QueueService(database)))

  private val moneyPlan = FamilyMoneyPlanChatTool(subjects, database)
  private val costPicture = FamilyCostPictureChatTool(subjects)

  @BeforeEach
  fun reset() {
    CostsTestDb.reset()
  }

  // ---------------------------------------------------------------------------
  // 1. A priced chosen plan and a band net price
  // ---------------------------------------------------------------------------

  @Test
  fun `a four-year school's totals are exactly four times its one-year figures, dated and attributed`() {
    val student = CostsTestDb.createStudent()
    val college = CostsTestDb.seedCollege("Four Year U", control = PRIVATE_NONPROFIT)
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)
    CostsTestDb.answerBand(student, IncomeBand.K48_TO_75K)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val entry = collegeEntry(FamilyTestDb.runTool(moneyPlan, adult, student), college)

    assertEquals(4, entry["years"]!!.jsonPrimitive.int)
    val published = entry["published_price"]!!.jsonArray.single().jsonObject
    assertEquals("on_campus", published["way_of_living"]!!.jsonPrimitive.content)
    assertTrue(published["chosen"]!!.jsonPrimitive.boolean)
    assertEquals(ON_CAMPUS_PER_YEAR_USD, published["per_year_usd"]!!.jsonPrimitive.int)
    assertEquals(4 * ON_CAMPUS_PER_YEAR_USD, published["all_years_usd"]!!.jsonPrimitive.int)
    assertEquals(CostsTestDb.PRICE_ACADEMIC_YEAR, published["academic_year"]!!.jsonPrimitive.content)
    assertProvenanceIs(MoneySource.IPEDS_IC_AY, published)

    val afterGrants = entry["price_after_grants"]!!.jsonObject
    assertEquals(CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q3_USD, afterGrants["per_year_usd"]!!.jsonPrimitive.int)
    assertEquals(4 * CostsTestDb.NET_PRICE_PER_YEAR_INCOME_Q3_USD, afterGrants["all_years_usd"]!!.jsonPrimitive.int)
    assertEquals(CostsTestDb.BLENDED_ACADEMIC_YEAR, afterGrants["academic_year"]!!.jsonPrimitive.content)
    assertEquals("your_income_band", afterGrants["basis"]!!.jsonPrimitive.content)
    assertEquals(IncomeBand.K48_TO_75K.bracket, afterGrants["income_band_label"]!!.jsonPrimitive.content)
    assertTrue("applies_to_this_family_basis" in afterGrants)
    assertProvenanceIs(MoneySource.SCORECARD, afterGrants)
  }

  @Test
  fun `no key sums schools, and every all-years figure is its own one-year figure times the years`() {
    val student = CostsTestDb.createStudent()
    listOf("First U", "Second U").forEach { name ->
      val college = CostsTestDb.seedCollege(name, control = PRIVATE_NONPROFIT)
      seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
      CostsTestDb.addToCollegeList(student, college)
    }
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val payload = FamilyTestDb.runTool(moneyPlan, adult, student)

    assertEquals(setOf("colleges", "all_years_statement", "source"), payload.keys, "nothing beside the schools adds them up")
    val totals = objectsCarrying("all_years_usd", payload)
    assertTrue(totals.size >= 2, "both schools carry totals: $payload")
    totals.forEach { total ->
      assertEquals(4 * total["per_year_usd"]!!.jsonPrimitive.int, total["all_years_usd"]!!.jsonPrimitive.int, "$total")
    }
  }

  // ---------------------------------------------------------------------------
  // 2. No living plan chosen
  // ---------------------------------------------------------------------------

  @Test
  fun `with no living plan chosen, every way of living the school prices gets its own total`() {
    val student = CostsTestDb.createStudent()
    val college = CostsTestDb.seedCollege("Undecided U", control = PRIVATE_NONPROFIT)
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val published =
      collegeEntry(
        FamilyTestDb.runTool(moneyPlan, adult, student),
        college,
      )["published_price"]!!.jsonArray.map { it.jsonObject }

    assertEquals(
      mapOf("on_campus" to ON_CAMPUS_PER_YEAR_USD, "off_campus" to OFF_CAMPUS_PER_YEAR_USD, "with_family" to WITH_FAMILY_PER_YEAR_USD),
      published.associate { it["way_of_living"]!!.jsonPrimitive.content to it["per_year_usd"]!!.jsonPrimitive.int },
    )
    published.forEach { entry ->
      assertFalse(entry["chosen"]!!.jsonPrimitive.boolean, "nothing was chosen: $entry")
      assertEquals(4 * entry["per_year_usd"]!!.jsonPrimitive.int, entry["all_years_usd"]!!.jsonPrimitive.int)
    }
    val atHome = published.single { it["way_of_living"]!!.jsonPrimitive.content == "with_family" }
    assertTrue("assumed_by_unicoach" in atHome, "the at-home zero is named as ours: $atHome")
  }

  // ---------------------------------------------------------------------------
  // 3. A chosen plan with no total
  // ---------------------------------------------------------------------------

  @Test
  fun `a chosen plan this school shows but cannot total gets the cost picture's own statement and no figure`() {
    val student = CostsTestDb.createStudent()
    val college = CostsTestDb.seedCollege("Part Published U", control = PRIVATE_NONPROFIT, housingAndFoodOnCampusPerYearUsd = null)
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    assertNoPublishedTotal(adult, student, college, LivingPlanPricing.NO_TOTAL_HERE)
  }

  @Test
  fun `a chosen plan this school does not price gets the cost picture's own statement and no figure`() {
    val student = CostsTestDb.createStudent()
    val college =
      CostsTestDb.seedCollege(
        "No Dorms U",
        control = PRIVATE_NONPROFIT,
        housingAndFoodOnCampusPerYearUsd = null,
        otherExpensesOnCampusPerYearUsd = null,
      )
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE, offersHousing = false)
    CostsTestDb.addToCollegeList(student, college)
    CostsTestDb.setEntryLivingPlan(student, college, LivingArrangement.ON_CAMPUS)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    assertNoPublishedTotal(adult, student, college, LivingPlanPricing.NOT_PRICED_HERE)
  }

  private fun assertNoPublishedTotal(
    adult: UserId,
    student: StudentId,
    college: CollegeId,
    pricing: LivingPlanPricing,
  ) {
    val entry = collegeEntry(FamilyTestDb.runTool(moneyPlan, adult, student), college)
    val picturesOwn = collegeEntry(FamilyTestDb.runTool(costPicture, adult, student), college)["chosen_living_arrangement"]!!.jsonObject

    assertFalse("published_price" in entry, "no published-price total: $entry")
    val availability = entry["published_price_availability"]!!.jsonObject
    assertEquals(pricing.value, availability["basis"]!!.jsonPrimitive.content)
    assertEquals(pricing.value, picturesOwn["pricing"]!!.jsonPrimitive.content, "the cost picture is in the same case")
    assertEquals(picturesOwn["statement"], availability["statement"], "the cost picture's own words for it")
  }

  // ---------------------------------------------------------------------------
  // 4. The price after grants withheld, or not reported
  // ---------------------------------------------------------------------------

  @Test
  fun `an out-of-state family at a public school gets no price after grants, and the reason`() {
    val student = CostsTestDb.createStudent()
    val college = CostsTestDb.seedCollege("Public U", state = "CA", control = CostsTestDb.CONTROL_PUBLIC)
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    CostsTestDb.answerResidency(student, "NY")
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val entry = collegeEntry(FamilyTestDb.runTool(moneyPlan, adult, student), college)

    assertFalse("price_after_grants" in entry, "$entry")
    val availability = entry["price_after_grants_availability"]!!.jsonObject
    assertEquals(WithheldReason.IN_STATE_ONLY_FIGURE.value, availability["basis"]!!.jsonPrimitive.content)
    assertEquals(WithheldReason.IN_STATE_ONLY_FIGURE.statement, availability["statement"]!!.jsonPrimitive.content)
    assertTrue("published_price" in entry, "the out-of-state published price still totals: $entry")
  }

  @Test
  fun `a school that reports no price after grants says so, and gives none`() {
    val student = CostsTestDb.createStudent()
    val college = CostsTestDb.seedCollege("Silent U", control = PRIVATE_NONPROFIT, netPricePerYearUsd = null)
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val entry = collegeEntry(FamilyTestDb.runTool(moneyPlan, adult, student), college)

    assertFalse("price_after_grants" in entry, "$entry")
    val availability = entry["price_after_grants_availability"]!!.jsonObject
    assertEquals(FigureStatus.NOT_REPORTED_BY_INSTITUTION.value, availability["basis"]!!.jsonPrimitive.content)
    assertEquals(
      FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_REPORTED_BY_INSTITUTION),
      availability["statement"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `a net price the publisher suppressed is said as that status, never as the school's silence`() {
    val student = CostsTestDb.createStudent()
    val college =
      CostsTestDb.seedCollege(
        "Suppressed U",
        control = PRIVATE_NONPROFIT,
        netPricePerYearUsd = null,
        netPriceReading = FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER),
      )
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val entry = collegeEntry(FamilyTestDb.runTool(moneyPlan, adult, student), college)
    val picturesOwn =
      collegeEntry(FamilyTestDb.runTool(costPicture, adult, student), college)["figure_statuses"]!!
        .jsonArray
        .map { it.jsonObject }
        .single { it["field"]!!.jsonPrimitive.content == CostField.NET_PRICE.wireName }

    assertFalse("price_after_grants" in entry, "$entry")
    val availability = entry["price_after_grants_availability"]!!.jsonObject
    assertEquals(FigureStatus.SUPPRESSED_BY_PUBLISHER.value, availability["basis"]!!.jsonPrimitive.content)
    assertEquals(picturesOwn["status"], availability["basis"], "the cost picture's own status for the cell")
    assertEquals(picturesOwn["statement"], availability["statement"], "the cost picture's own words for it")
  }

  // ---------------------------------------------------------------------------
  // 5. A school that is not four-year, and one with no level held
  // ---------------------------------------------------------------------------

  @Test
  fun `a two-year school and a school with no level held carry a reason and no dollar amount anywhere`() {
    val student = CostsTestDb.createStudent()
    val twoYear = CostsTestDb.seedCollege("Two Year College", control = PRIVATE_NONPROFIT)
    seedLevel(twoYear, instLevel = CollegeLevel.TWO_TO_FOUR_YEARS_CODE)
    val noLevel = CostsTestDb.seedCollege("Unknown Length College", control = PRIVATE_NONPROFIT)
    CostsTestDb.addToCollegeList(student, twoYear)
    CostsTestDb.addToCollegeList(student, noLevel)
    val (adult, _) = FamilyTestDb.linkedAdult(student)

    val payload = FamilyTestDb.runTool(moneyPlan, adult, student)

    mapOf(twoYear to NotTotalledReason.NOT_FOUR_YEAR, noLevel to NotTotalledReason.LEVEL_NOT_HELD).forEach { (college, reason) ->
      val entry = collegeEntry(payload, college)
      assertEquals(setOf("college_id", "name", "all_years_availability"), entry.keys, "$entry")
      val availability = entry["all_years_availability"]!!.jsonObject
      assertEquals(reason.value, availability["basis"]!!.jsonPrimitive.content)
      assertEquals(reason.statement, availability["statement"]!!.jsonPrimitive.content)
      assertEquals(emptyList(), numbersIn(entry), "no dollar amount anywhere in the entry: $entry")
    }
  }

  // ---------------------------------------------------------------------------
  // 6. A link that is gone
  // ---------------------------------------------------------------------------

  @Test
  fun `a viewer whose link is gone gets student not found`() {
    val student = CostsTestDb.createStudent()
    val college = CostsTestDb.seedCollege("Severed U", control = PRIVATE_NONPROFIT)
    seedLevel(college, instLevel = CollegeLevel.FOUR_OR_MORE_YEARS_CODE)
    CostsTestDb.addToCollegeList(student, college)
    val (adult, link) = FamilyTestDb.linkedAdult(student)
    assertNull(FamilyTestDb.runTool(moneyPlan, adult, student)["error"], "linked, it answers")

    assertIs<UnlinkOutcome.Unlinked>(runBlocking { supporters.disconnectAsStudent(student, link.id).getOrThrow() })

    assertEquals(
      buildJsonObject { put("error", JsonPrimitive(SubjectScopedChatTool.STUDENT_NOT_FOUND_REASON)) },
      FamilyTestDb.runTool(moneyPlan, adult, student),
    )
  }

  // ---------------------------------------------------------------------------
  // Fixtures and readers
  // ---------------------------------------------------------------------------

  /** The `college_ipeds` row for [collegeId], carrying its `HD.ICLEVEL` code. */
  private fun seedLevel(
    collegeId: CollegeId,
    instLevel: Int,
    offersHousing: Boolean? = null,
  ) {
    val ipedsUnitId = RawSql.single(sqlSession, "SELECT ipeds_unit_id FROM colleges WHERE id = ?", collegeId.value) { it.getInt(1) }
    CoachingTestDb.seedIpeds(ipedsUnitId, offersHousing = offersHousing, instLevel = instLevel)
  }

  private fun collegeEntry(
    payload: JsonObject,
    collegeId: CollegeId,
  ): JsonObject =
    payload["colleges"]!!
      .jsonArray
      .map { it.jsonObject }
      .single { it["college_id"]!!.jsonPrimitive.content == collegeId.value.toString() }

  /** One provenance entry, from [source], in the cost picture's own three keys. */
  private fun assertProvenanceIs(
    source: MoneySource,
    figure: JsonObject,
  ) {
    val provenance = figure["provenance"]!!.jsonArray.single().jsonObject
    assertEquals(source.value, provenance["source"]!!.jsonPrimitive.content, "$figure")
    val tier =
      ed.unicoach.db.models.AssuranceTier.entries
        .single { it.value == provenance["assurance"]!!.jsonPrimitive.content }
    assertEquals(
      CollegeCostChatTool.provenanceObjectOf(SpokenAssurance(tier), source),
      provenance,
      "rendered by the cost picture's own emitter",
    )
  }

  /** Every object anywhere in [element] that carries [key]. */
  private fun objectsCarrying(
    key: String,
    element: JsonElement,
  ): List<JsonObject> =
    when (element) {
      is JsonObject -> listOfNotNull(element.takeIf { key in it }) + element.values.flatMap { objectsCarrying(key, it) }
      is JsonArray -> element.flatMap { objectsCarrying(key, it) }
      else -> emptyList()
    }

  /** Every number anywhere in [element]. */
  private fun numbersIn(element: JsonElement): List<Int> =
    when (element) {
      is JsonObject -> element.values.flatMap(::numbersIn)
      is JsonArray -> element.flatMap(::numbersIn)
      is JsonPrimitive -> listOfNotNull(element.takeUnless { it.isString }?.intOrNull)
    }

  private companion object {
    /** `colleges.control` 2: one published price, so no residency answer is needed for a total. */
    const val PRIVATE_NONPROFIT = 2

    val ON_CAMPUS_PER_YEAR_USD =
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD

    val OFF_CAMPUS_PER_YEAR_USD =
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD +
        CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD + CostsTestDb.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD

    /** The at-home housing-and-food line is unicoach's own `$0`, so it adds nothing. */
    val WITH_FAMILY_PER_YEAR_USD =
      CostsTestDb.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD + CostsTestDb.BOOKS_AND_SUPPLIES_PER_YEAR_USD +
        CostsTestDb.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD
  }
}
