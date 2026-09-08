package ed.unicoach.coaching

import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.college.CollegeSearchTool
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.PriceRuler
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The adapter is a verbatim delegate in everything but ONE thing: it resolves
 * the family's state of residency and hands it down (RFC 169 D5/D6).
 *
 * Name and definition still come from the wrapped tool unchanged -- no residency
 * INPUT field is added, because the state comes from `money_profiles` and must
 * not be something the model can contradict.
 */
class CollegeChatToolTest {
  private val database = CoachingTestDb.database

  // The delegation, not the vocabulary, is under test: an empty codebook is the
  // honest snapshot of a database whose `codebooks` ingest phase never ran.
  private val wrapped = CollegeSearchTool(CollegeSearchService(database), ed.unicoach.college.Codebook.EMPTY)
  private val moneyProfiles = MoneyProfileService(database)
  private val adapter = CollegeChatTool(wrapped, moneyProfiles)

  /**
   * A built index with one college in it. `search_colleges` refuses an UNBUILT
   * index outright (RFC 150's "never a page of zero"), and every assertion below
   * is about what a real PAGE carries -- the ruler it names, and the invitation
   * beside it -- so the page has to exist.
   */
  @BeforeEach
  fun buildSearchIndex() {
    CollegesDao
      .upsert(CoachingTestDb.sqlSession, newCollege())
      .getOrThrow()
    CollegesDao.rebuildSearchIndex(CoachingTestDb.sqlSession).getOrThrow()
  }

  @Test
  fun `name and definition come from the wrapped tool`() {
    assertEquals(CollegeSearchTool.TOOL_NAME, adapter.name)
    assertEquals(wrapped.definition, adapter.definition)
  }

  @Test
  fun `the definition offers no residency input field`() {
    // The state is READ, never asked for: a model-supplied residency could
    // contradict what the family recorded, and there would be no way to tell
    // which one the ranking used.
    val properties =
      adapter.definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject
    assertTrue(properties.keys.none { "residency" in it }, properties.keys.toString())
  }

  @Test
  fun `an unscoped dispatch is refused, not answered on the net ruler`() {
    // The tool is student-scoped now, so a dispatcher that forgot to scope it
    // must SAY so rather than quietly answer every family the same way.
    //
    // A BLOCK body, not `= runBlocking { ... }`: this test ends on
    // `assertNotNull`, which RETURNS the value it checked, so an expression body
    // gives the method a non-Unit return type and JUnit never discovers it. It
    // compiled, formatted and read as covered while never running once.
    runBlocking {
      val result = (adapter as ChatToolLike).execute(buildJsonObject { })
      assertNotNull(result["error"], result.toString())
    }
  }

  @Test
  fun `a malformed input is refused with the wrapped tool's own words`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("search-bad-input")
      val badInput = buildJsonObject { put("not_a_field", "x") }
      assertEquals(wrapped.execute(badInput, null), adapter.execute(student, badInput))
    }

  @Test
  fun `an answered residency puts the search on the published ruler`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("search-answered")
      answerResidency(student, "WA")
      val result = adapter.execute(student, buildJsonObject { })
      assertEquals(
        PriceRuler.PUBLISHED_PRICE_SORT_WORD,
        result["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )
      // Answered is not offerable: the topic is closed by an answer.
      assertNull(result[ResidencyOffer.KEY])
    }

  @Test
  fun `a residency offer never gates a result`() =
    runBlocking {
      // UNANSWERED: the full page comes back AND the invitation rides beside it.
      val unanswered = CoachingTestDb.createStudent("search-unanswered")
      val offered = adapter.execute(unanswered, buildJsonObject { })
      assertNotNull(offered["colleges"], offered.toString())
      assertNotNull(offered["total_matches"], offered.toString())
      assertEquals(
        ResidencyOffer.FIELD,
        offered[ResidencyOffer.KEY]!!
          .jsonObject["field"]!!
          .jsonPrimitive.content,
      )
      assertEquals(
        PriceRuler.NET_PRICE_SORT_WORD,
        offered["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )

      // DECLINED: still the net ruler, still a full page -- and the key is
      // ABSENT, because the offer is keyed on UNANSWERED and not on a missing
      // value. A decline is permanent; a coach is never cued to reopen it.
      val declined = CoachingTestDb.createStudent("search-declined")
      declineResidency(declined)
      val quiet = adapter.execute(declined, buildJsonObject { })
      assertNotNull(quiet["colleges"], quiet.toString())
      assertNull(quiet[ResidencyOffer.KEY])
      assertEquals(
        PriceRuler.NET_PRICE_SORT_WORD,
        quiet["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )
    }

  @Test
  fun `an UNANSWERED profile row is offered, exactly as a missing profile is`() =
    runBlocking {
      // A3. These are DIFFERENT states and both must reach the offer: a student
      // with no `money_profiles` row at all has never been asked, and a student
      // WITH a row whose residency is still `unanswered` has been asked about
      // other things and not this one. The test above covers the first; without
      // this one the `AnswerStatus.UNANSWERED` branch was never executed, so
      // deleting it would have stayed green.
      val student = CoachingTestDb.createStudent("search-unanswered-row")
      moneyProfiles.upsert(student, MoneyProfileUpdate(income = FieldUpdate.Set(IncomeBand.UNDER_30K))).getOrThrow()

      val profile = moneyProfiles.getForStudent(student).getOrThrow()
      assertTrue(profile is GetMoneyProfileResult.Found, "the premise: a row EXISTS for this student")
      assertEquals(
        AnswerStatus.UNANSWERED,
        (profile as GetMoneyProfileResult.Found).profile.residencyStatus,
        "and its residency is unanswered, which is the branch under test",
      )

      val result = adapter.execute(student, buildJsonObject { })
      assertNotNull(result["colleges"], "the page is returned in full: $result")
      assertEquals(
        ResidencyOffer.FIELD,
        result[ResidencyOffer.KEY]!!
          .jsonObject["field"]!!
          .jsonPrimitive.content,
        "a row that has not answered is still a family nobody has asked",
      )
      assertEquals(
        PriceRuler.NET_PRICE_SORT_WORD,
        result["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )
    }

  @Test
  fun `a failed profile read leaves the search on the net ruler, not in an error`() =
    runBlocking {
      // The documented fallback, and the one branch a Postgres-backed test cannot
      // ask for on demand — which is why the read is a seam. A database fault on
      // a SIDE read must never turn a working search into an error: the family
      // gets the ranking search had before a residency-correct one existed.
      val student = CoachingTestDb.createStudent("search-read-failed")
      val faulting =
        CollegeChatTool(wrapped, moneyProfiles) { Result.failure(IllegalStateException("the profile read failed")) }

      val result = faulting.execute(student, buildJsonObject { })
      assertNull(result["error"], "a side read that failed is not a failed search: $result")
      assertNotNull(result["colleges"], "$result")
      assertEquals(
        PriceRuler.NET_PRICE_SORT_WORD,
        result["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )
      // And NO offer: a fault is not "nobody has asked", so it must not put a
      // question in front of a family on the strength of a broken read.
      assertNull(result[ResidencyOffer.KEY], "a fault never invites the question: $result")
    }

  @Test
  fun `a failed read says we do not know, and never that the state is not on file`() {
    // The copy defect. `price_ruler.note` says what the ranking IS and what would
    // change it, and asserts nothing about this family -- `:college` cannot tell
    // a fault from a silence. The layer that CAN says so here. A page that told
    // a family their state was "not on file" after a read that FAULTED was
    // stating something it did not know.
    runBlocking {
      val student = CoachingTestDb.createStudent("search-read-failed-copy")
      val faulting =
        CollegeChatTool(wrapped, moneyProfiles) { Result.failure(IllegalStateException("the profile read failed")) }

      val result = faulting.execute(student, buildJsonObject { })
      val note = result[ResidencyOffer.UNAVAILABLE_KEY]!!.jsonPrimitive.content
      assertTrue(note.contains("could not be read"), note)
      assertTrue(note.contains("we do not know"), "it says what is TRUE for a fault: $note")
      assertNull(result[ResidencyOffer.KEY], "a fault never invites the question")

      val rulerNote =
        result["price_ruler"]!!
          .jsonObject["note"]!!
          .jsonPrimitive.content
      assertFalse(rulerNote.contains("not on file"), "the ruler note asserts nothing about this family: $rulerNote")
    }
  }

  @Test
  fun `a stored state outside the published vocabulary degrades to the net ruler, never a failed search`() {
    // A `money_profiles` row that says ANSWERED while holding a state the USPS
    // vocabulary does not contain violates that table's own CHECK. It reaches
    // this read only if a row was edited around the write boundary -- and it used
    // to reach `PriceRuler.Published`, whose `init` throws, turning EVERY search
    // by that family into a tool failure instead of the documented fall back.
    runBlocking {
      val student = CoachingTestDb.createStudent("search-corrupt-state")
      val corrupt =
        CollegeChatTool(wrapped, moneyProfiles) {
          Result.success(
            GetMoneyProfileResult.Found(
              moneyProfileWith(residencyState = "ZZ", residencyStatus = AnswerStatus.ANSWERED),
            ),
          )
        }

      val result = corrupt.execute(student, buildJsonObject { })
      assertNull(result["error"], "corruption on a side read is not a failed search: $result")
      assertEquals(
        PriceRuler.NET_PRICE_SORT_WORD,
        result["price_ruler"]!!
          .jsonObject["metric"]!!
          .jsonPrimitive.content,
      )
      assertTrue(
        result[ResidencyOffer.UNAVAILABLE_KEY]!!
          .jsonPrimitive.content
          .contains("could not be used"),
        "$result",
      )
    }
  }

  @Test
  fun `every offer field is a parameter update_money_profile accepts`() {
    // The twin of the cost tool's binding test: an invitation naming a
    // parameter nothing accepts is an invitation a family cannot act on.
    val accepted =
      MoneyProfileChatTool(moneyProfiles)
        .definition["input_schema"]!!
        .jsonObject["properties"]!!
        .jsonObject.keys
    assertTrue(ResidencyOffer.FIELD in accepted, accepted.toString())
  }

  @Test
  fun `a residency offer is never attached to a refusal`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("search-refusal")
      val refusal = adapter.execute(student, buildJsonObject { put("not_a_field", "x") })
      assertNotNull(refusal["error"], refusal.toString())
      assertNull(refusal[ResidencyOffer.KEY])
    }

  /**
   * A [MoneyProfile] shaped by hand, for the two states no supported write path
   * can produce: a row that says ANSWERED while holding a state the published
   * USPS vocabulary does not contain. `money_profiles` has a CHECK against it,
   * so the only way to test the read's behaviour is to hand it the row directly.
   */
  private fun moneyProfileWith(
    residencyState: String?,
    residencyStatus: AnswerStatus,
  ): MoneyProfile {
    val now = java.time.Instant.now()
    return MoneyProfile(
      id = MoneyProfileId(java.util.UUID.randomUUID()),
      studentId = StudentId(java.util.UUID.randomUUID()),
      incomeBand = null,
      incomeBandStatus = AnswerStatus.UNANSWERED,
      residencyState = residencyState,
      residencyStatus = residencyStatus,
      livingPlan = null,
      livingPlanStatus = AnswerStatus.UNANSWERED,
      dependency = null,
      dependencyStatus = AnswerStatus.UNANSWERED,
      version = 1,
      createdAt = now,
      updatedAt = now,
      deletedAt = null,
    )
  }

  private fun newCollege() =
    NewCollege(
      ipedsUnitId = 909091,
      opeid = null,
      name = "Residency Ruler University",
      city = "Testville",
      state = "NV",
      region = null,
      locale = null,
      latitude = null,
      longitude = null,
      control = 1,
      undergradEnrollmentHeadcount = 5000,
      admissionRateShare = 0.5,
      satAverageEquivalentScore = 1200,
      costOfAttendancePerYearUsd = null,
      netPricePerYearUsd = 20000,
      netPricePerYearIncomeQ1Usd = null,
      netPricePerYearIncomeQ2Usd = null,
      netPricePerYearIncomeQ3Usd = null,
      netPricePerYearIncomeQ4Usd = null,
      netPricePerYearIncomeQ5Usd = null,
      tuitionAndFeesInStatePerYearUsd = null,
      tuitionAndFeesOutOfStatePerYearUsd = null,
      completionRate150pct4yrShare = 0.7,
      medianEarnings10yAfterEntryUsd = null,
      medianDebtAtCompletionUsd = null,
      housingAndFoodOnCampusPerYearUsd = null,
      housingAndFoodOffCampusPerYearUsd = null,
      booksAndSuppliesPerYearUsd = null,
      otherExpensesOnCampusPerYearUsd = null,
      otherExpensesOffCampusPerYearUsd = null,
      otherExpensesWithFamilyPerYearUsd = null,
      pellShare = null,
      website = null,
    )

  private suspend fun answerResidency(
    student: StudentId,
    state: String,
  ) {
    moneyProfiles
      .upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Set(state)))
      .getOrThrow()
  }

  private suspend fun declineResidency(student: StudentId) {
    moneyProfiles.upsert(student, MoneyProfileUpdate(residency = FieldUpdate.Decline)).getOrThrow()
  }
}

/** The bare [ed.unicoach.chat.ChatTool] view of the adapter: the unscoped `execute` the misroute guard owns. */
private typealias ChatToolLike = ed.unicoach.chat.ChatTool
