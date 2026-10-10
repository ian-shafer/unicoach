package ed.unicoach.coaching.testing

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.ResidencyOffer
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `psat_national_merit` wire (RFC 243): what the model is actually handed.
 *
 * The BEHAVIOUR is asserted in [NationalMeritServiceTest]; this suite asserts the
 * things only the payload can be wrong about -- that a dollar figure cannot reach
 * the model without the base rate, that no key anywhere carries a qualifying
 * score, that nothing ranks or totals two schools' packages, that the numbers
 * this tool emits are the ones it declared, and that each package's citation is
 * stated once.
 */
class NationalMeritChatToolTest {
  @BeforeEach
  fun setUp() {
    NationalMeritTestDb.reset()
  }

  @Test
  fun `the tool name and the advertised schema are the shared college_ids contract`() {
    val tool = toolFor()
    assertEquals(NationalMeritChatTool.TOOL_NAME, tool.name)
    assertEquals("psat_national_merit", tool.name, "the name is the one the prompt and the registration both say")
    val schema = tool.definition.getValue("input_schema").jsonObject
    assertEquals(
      setOf(StudentScopedChatTool.COLLEGE_IDS_FIELD),
      schema
        .getValue("properties")
        .jsonObject.keys,
      "the input is college_ids ONLY -- no residency field the model could contradict the money profile with",
    )
    assertEquals(
      false,
      schema
        .getValue("additionalProperties")
        .jsonPrimitive.content
        .toBoolean(),
    )
  }

  /**
   * THE BASE-RATE CONTRACT, ON THE WIRE. Every dollar figure in the payload is
   * accompanied by the base rate, and the base rate names the competition year it
   * belongs to.
   *
   * PROVED BY DELETION: comment out `put("base_rate", ...)` in
   * [NationalMeritChatTool.execute], or the `base_rate_competition_year` line in
   * its `putPrice`, and this test fails by name. A payload naming $20,000 with no
   * roughly-one-in-eighty beside it is the one defect this whole corpus exists to
   * prevent.
   */
  @Test
  fun `no payload carrying a dollar figure lacks the base rate, and the base rate names its own competition year`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Six Figure University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      val payload = payloadFor(student)
      val money = packagesOf(payload).single()

      assertEquals(
        NationalMeritTestDb.AWARD_PER_YEAR_USD,
        money
          .getValue("award_per_year_usd")
          .jsonPrimitive.content
          .toInt(),
        "the school's own figure is on the wire: [$payload]",
      )
      assertTrue(
        payload.getValue("base_rate") != JsonNull,
        "a payload carrying a dollar figure states the base rate: delete the base_rate emission from the response " +
          "builder and this is the assertion that goes red: [$payload]",
      )
      val baseRate = payload.getValue("base_rate").jsonObject
      assertEquals(
        NationalMeritTestDb.FIXTURE_CYCLE_YEAR.toString(),
        baseRate.getValue("competition_year").jsonPrimitive.content,
        "and the base rate is there, naming the competition it belongs to",
      )
      assertEquals(
        NationalMeritTestDb.FIXTURE_CYCLE_YEAR.toString(),
        money.getValue("base_rate_competition_year").jsonPrimitive.content,
        "the package itself names that year too, so the figure cannot be quoted away from it",
      )
      assertTrue(
        baseRate
          .getValue("said_as")
          .jsonPrimitive.content
          .contains(NationalMeritTestDb.FIXTURE_CYCLE_YEAR.toString()),
        "said_as is how the year is SPOKEN, so the coach cannot state the figures as this student's own year: [$baseRate]",
      )
      assertEquals(
        assertNotNull(NationalMeritTestDb.datedCycle().funnel.finalists),
        baseRate
          .getValue("finalists")
          .jsonPrimitive.content
          .toInt(),
      )
      assertTrue(
        baseRate
          .getValue("finalist_share_said")
          .jsonPrimitive.content
          .contains('%'),
        "the derived share is also said in words, so the coach does not do the percentage itself: [$baseRate]",
      )
      assertTrue(
        baseRate
          .getValue("finalist_share_derived_from")
          .jsonPrimitive.content
          .contains("met_entry_requirements"),
        "and it says which two stages it is over, because it is derived at read time and stored nowhere",
      )
    }

  /**
   * THE SAME CONTRACT FROM THE OTHER SIDE, MECHANICALLY: sweep the whole payload
   * for a dollar figure and require the base rate wherever one is found. It is
   * the assertion a renderer growing a sixth money key cannot slip past.
   */
  @Test
  fun `a sweep of the whole payload finds no money key without the base rate beside it`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val priced = NationalMeritTestDb.seedListedCollege(student, "Priced University")
      val words = NationalMeritTestDb.seedListedCollege(student, "Words University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(priced))
      NationalMeritTestDb.seedAward(NationalMeritTestDb.wordsOnlyAward(words))

      val payload = payloadFor(student)

      assertTrue(moneyKeysIn(payload).isNotEmpty(), "this payload must actually carry money, or the sweep proves nothing: [$payload]")
      // getValue, NEVER `payload["k"] != JsonNull`: a MISSING key reads back as Kotlin null, and `null != JsonNull`
      // is true -- so the `!=` form was green for exactly the renderer this sweep exists to catch, the one that
      // emits money and no base rate at all. getValue throws on absence and assertNotEquals catches a null literal.
      assertNotEquals(JsonNull, payload.getValue("base_rate"), "and the base rate must be there with it: [$payload]")
      packagesOf(payload).forEach { pack ->
        val named = moneyKeysIn(pack)
        if (named.isNotEmpty()) {
          assertNotEquals(
            JsonNull,
            pack.getValue("base_rate_competition_year"),
            "package [$pack] names money in $named and no base-rate year",
          )
        }
      }
    }

  /**
   * NO QUALIFYING SCORE ANYWHERE, which is the refusal D9 and correction 4 make
   * together: the publisher sets the Semifinalist bar per STATE, publishes none in
   * advance, and says outright that it varies by year.
   *
   * The sweep covers the payload AND the tool's own description, because the
   * description is the other place the coach reads this from. The Selection Index
   * bounds ARE on the wire -- they are the scale a score is reported on -- so the
   * assertion is that no key presents a number as a requirement and that the scale
   * carries the sentence saying it is not one.
   */
  @Test
  fun `no key presents a qualifying score, and the Selection Index scale says it is not one`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Scale University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      val payload = payloadFor(student)
      val cycle = cycleOf(payload)

      assertEquals(
        emptyList(),
        BareSourceCodeGuard
          .listNumericFields(payload)
          .filter { key -> CUTOFF_SHAPED_KEY.containsMatchIn(key) },
        "no numeric key on this wire is shaped like a bar a student has to clear: [$payload]",
      )
      assertTrue(
        cycle
          .getValue("selection_index_why")
          .jsonPrimitive.content
          .contains("Neither of them is a qualifying score"),
        "the scale carries the sentence that says it is not a cutoff, in the same object: [$cycle]",
      )
      assertTrue(
        cycle
          .getValue("qualifying_score_as_published")
          .jsonPrimitive.content
          .isNotEmpty(),
        "and the publisher's own sentence is the answer to what she needs",
      )
      assertEquals(
        emptyList(),
        CUTOFF_CLAIMS.filter { (payload.toString() + toolFor().definition).contains(it, ignoreCase = true) },
        "no emitted string states a score to reach",
      )
    }

  /** NOTHING IS RANKED, SORTED, TOTALLED OR COMPARED, and the payload says so where a reader would look for it. */
  @Test
  fun `two schools priced in different terms are served in the corpus's own order, with no ranking key at all`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val figure = NationalMeritTestDb.seedListedCollege(student, "Figure University")
      val words = NationalMeritTestDb.seedListedCollege(student, "Words University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(figure))
      NationalMeritTestDb.seedAward(NationalMeritTestDb.wordsOnlyAward(words))

      val payload = payloadFor(student)

      assertEquals(
        emptyList(),
        RANKING_SHAPED_KEY.let { pattern -> allKeysIn(payload).filter { pattern.containsMatchIn(it) } },
        "there is no best, top, rank, total-across-schools or comparison key for a reader to find: [$payload]",
      )
      assertTrue(
        payload
          .getValue("nothing_is_ranked")
          .jsonPrimitive.content
          .contains("no best-award key"),
        "and the payload says so in words, because a school priced in words has no comparable number",
      )
      val wordsPackage =
        packagesOf(payload).single { pack ->
          pack.getValue("publisher").jsonPrimitive.content == NationalMeritTestDb.WORDS_ONLY_PUBLISHER
        }
      assertEquals(
        JsonNull,
        wordsPackage.getValue("award_per_year_usd"),
        "the words-only school publishes no figure, and no zero is invented for it: [$wordsPackage]",
      )
      assertEquals(
        NationalMeritTestDb.WORDS_ONLY_AS_PUBLISHED,
        wordsPackage.getValue("award_as_published").jsonPrimitive.content,
        "its own words are the price, and they are on the wire",
      )
      assertTrue(
        wordsPackage.getValue("base_rate_competition_year") != JsonNull,
        "a words-priced package is a PRICED package, so the base rate rides with it too",
      )
    }

  /**
   * EVERY ARM CARRIES THE SAME HEAD KEYS, as explicit nulls where we hold
   * nothing. An absent key is a silence the model fills, and the arm with least
   * to say -- a school we never read -- is exactly where a renderer is tempted to
   * omit.
   */
  @Test
  fun `all five per-school answers carry the same keys, and the three unpriced arms carry no money at all`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val automatic = NationalMeritTestDb.seedListedCollege(student, "Automatic University")
      val competitive = NationalMeritTestDb.seedListedCollege(student, "Competitive University")
      val absence = NationalMeritTestDb.seedListedCollege(student, "Silent University")
      val unread = NationalMeritTestDb.seedListedCollege(student, "Unread University")
      val stale = NationalMeritTestDb.seedListedCollege(student, "Stale University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(automatic))
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(competitive, awardKind = NationalMeritAwardKind.COMPETITIVE))
      NationalMeritTestDb.seedAward(NationalMeritTestDb.absence(absence))
      NationalMeritTestDb.seedAward(
        NationalMeritTestDb.award(
          stale,
          checkedOn = STALE_CHECKED_ON,
          expiresOn = STALE_EXPIRES_ON,
        ),
      )

      val packages = packagesOf(payloadFor(student))
      val byAnswer = packages.groupBy { it.getValue("answer").jsonPrimitive.content }

      assertEquals(
        setOf(
          PackageAnswer.AUTOMATIC.value,
          PackageAnswer.COMPETITIVE.value,
          PackageAnswer.NONE_PUBLISHED.value,
          PackageAnswer.EXPIRED.value,
          PackageAnswer.NOT_CURATED.value,
        ),
        byAnswer.keys,
        "all five answers, and no two of them alike: [$packages]",
      )
      val keySets = packages.map { it.keys }.distinct()
      assertEquals(1, keySets.size, "every arm carries the same keys, or an absent key is a silence: [$keySets]")
      listOf(PackageAnswer.NONE_PUBLISHED.value, PackageAnswer.EXPIRED.value, PackageAnswer.NOT_CURATED.value)
        .forEach { answer ->
          val pack = byAnswer.getValue(answer).single()
          assertEquals(
            emptyList(),
            moneyKeysIn(pack),
            "the [$answer] arm states no price at all -- not a figure and not the publisher's words, because the " +
              "words ARE the price: [$pack]",
          )
          assertEquals(JsonNull, pack.getValue("award_as_published"), "[$answer] quotes no price sentence: [$pack]")
        }
      assertEquals(
        NationalMeritTestDb.ABSENCE_AS_PUBLISHED,
        byAnswer
          .getValue(PackageAnswer.NONE_PUBLISHED.value)
          .single()
          .getValue("absence_as_published")
          .jsonPrimitive.content,
        "the absence arm carries the sentence that proves it, which is a different key from a price",
      )
      assertEquals(
        STALE_EXPIRES_ON.toString(),
        byAnswer
          .getValue(PackageAnswer.EXPIRED.value)
          .single()
          .getValue("expired_on")
          .jsonPrimitive.content,
      )
      assertEquals(
        JsonNull,
        byAnswer
          .getValue(PackageAnswer.NOT_CURATED.value)
          .single()
          .getValue("publisher"),
        "a school we never read cites nobody",
      )
      assertTrue(unread.value.toString() in payloadFor(student).toString(), "and it is still ON the wire, as a named gap")
    }

  /**
   * THE SIX TIMING ARMS REACH THE MODEL AS SIX DIFFERENT WORDS, and the two
   * arms that hold no believed cycle name no date.
   */
  @Test
  fun `each timing arm is its own word on the wire, and the two dateless arms carry no cycle at all`() =
    runTest {
      val junior = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      val uncurated = NationalMeritTestDb.createStudent(NationalMeritTestDb.UNCURATED_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      assertEquals(
        TimingAnswer.WINDOW_AHEAD.value,
        timingOf(payloadFor(junior)).getValue("answer").jsonPrimitive.content,
      )
      assertEquals(
        TimingAnswer.WINDOW_OPEN.value,
        timingOf(payloadFor(junior, NationalMeritTestDb.NOON_EASTERN_2028_10_15)).getValue("answer").jsonPrimitive.content,
      )
      assertEquals(
        TimingAnswer.ALTERNATE_ENTRY_OPEN.value,
        timingOf(payloadFor(junior, NationalMeritTestDb.NOON_EASTERN_2028_10_31)).getValue("answer").jsonPrimitive.content,
      )
      assertEquals(
        TimingAnswer.CYCLE_CLOSED.value,
        timingOf(payloadFor(junior, NationalMeritTestDb.NOON_EASTERN_2029_04_02)).getValue("answer").jsonPrimitive.content,
      )

      val expired = timingOf(payloadFor(junior, NationalMeritTestDb.NOON_EASTERN_2029_08_01))
      assertEquals(
        TimingAnswer.CYCLE_EXPIRED.value,
        expired.getValue("answer").jsonPrimitive.content,
        "a day past the cycle's own expires_on, our reading of it is the answer and not its dates",
      )
      assertEquals(
        JsonNull,
        expired.getValue("cycle"),
        "and NO cell of a dead row reaches the model: a window spoken from a stale reading is a date a family " +
          "would act on: [$expired]",
      )
      assertTrue(
        expired
          .getValue("why")
          .jsonPrimitive.content
          .contains("stopped believing"),
        "the sentence says it is OUR reading that went stale, and names whose pages to open: [$expired]",
      )

      val notCurated = timingOf(payloadFor(uncurated))
      assertEquals(TimingAnswer.NOT_CURATED.value, notCurated.getValue("answer").jsonPrimitive.content)
      assertEquals(JsonNull, notCurated.getValue("cycle"), "there is no cycle to describe, so there is no date to name")
      assertTrue(
        notCurated
          .getValue("why")
          .jsonPrimitive.content
          .contains(NationalMeritTestDb.UNCURATED_CLASS.toString()),
        "and it names the class we hold nothing for: [$notCurated]",
      )
    }

  /** An undated window reaches the model as two explicit nulls and its publisher's own words, never as a date we derived. */
  @Test
  fun `an undated window is two explicit nulls and the publisher's own description`() =
    runTest {
      val sophomore = NationalMeritTestDb.createStudent(NationalMeritTestDb.SOPHOMORE_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.describedCycle())

      val cycle = cycleOf(payloadFor(sophomore))

      assertEquals(JsonNull, cycle.getValue("window_opens_on"))
      assertEquals(JsonNull, cycle.getValue("window_closes_on"))
      assertEquals(
        false,
        cycle
          .getValue("window_dated")
          .jsonPrimitive.content
          .toBoolean(),
      )
      assertEquals(NationalMeritTestDb.WINDOW_AS_PUBLISHED, cycle.getValue("window_as_published").jsonPrimitive.content)
    }

  /**
   * THE DECLARED NUMBERS, AND NOTHING ELSE (RFC 143's guard, RFC 203's sweep),
   * asserted in both directions: no undeclared number reaches the model, and every
   * declared key is rendered by a real payload, so the allowlist cannot quietly
   * sanction a field that no longer exists.
   */
  @Test
  fun `the only bare numbers on the wire are the ones this tool declares, and every declared one is rendered`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Every Key University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      val payload = payloadFor(student)

      assertEquals(
        emptyList(),
        BareSourceCodeGuard
          .listViolations(payload, NationalMeritChatTool.NUMBERS_BY_CONTRACT)
          .map(BareSourceCodeGuard::mapMessage),
        "every bare number on this wire is one the tool declared: [$payload]",
      )
      assertEquals(
        emptySet(),
        NationalMeritChatTool.NUMBERS_BY_CONTRACT - BareSourceCodeGuard.listNumericFields(payload).toSet(),
        "every field the allowlist sanctions must be in the payload, or the allowlist is sanctioning nothing",
      )
      // The guard must be able to fire, or the assertion above is vacuous.
      assertTrue(
        BareSourceCodeGuard
          .listViolations(buildJsonObject { put("checked_on", 20280901) }, NationalMeritChatTool.NUMBERS_BY_CONTRACT)
          .isNotEmpty(),
        "a date arriving as a number must fail this guard",
      )
    }

  /**
   * THE RESIDENCY COMPARISON IS ON THE WIRE, AND THE ROW IS NOT DROPPED. An
   * Oregon family is told the Alabama-residents-only package exists and is not
   * hers; residency for tuition purposes stays the school's own determination.
   */
  @Test
  fun `an in-state-only package reaches an out-of-state family labelled as differing from the state on file`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Residents Only University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.IN_STATE))

      val pack = packagesOf(payloadFor(student, residencyState = "OR")).single()

      assertEquals(ResidencyScope.DIFFERS_FROM_STATE_ON_FILE.value, pack.getValue("residency_scope").jsonPrimitive.content)
      assertEquals("in_state", pack.getValue("residency_basis").jsonPrimitive.content)
      assertTrue(
        pack
          .getValue("residency_basis_label")
          .jsonPrimitive.content
          .contains("state resident"),
        "whose package it is, in words: [$pack]",
      )
      assertTrue(
        pack
          .getValue("residency_scope_why")
          .jsonPrimitive.content
          .contains("never as money she would be paid"),
        "and why it rides at all: [$pack]",
      )
    }

  /**
   * THE INVITATION, WHERE NOBODY HAS ASKED (RFC 169 D6's shape, as
   * `fafsa_deadlines` takes it): a family with no money profile is offered the
   * question once, and nothing here waits on the answer.
   */
  @Test
  fun `a family with no money profile is offered the residency question, and the whole answer still comes back`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Offer University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.OUT_OF_STATE))

      val payload = payloadFor(student, residencyState = null)

      assertTrue("residency_offer" in payload, "nobody has asked, so the invitation rides: [$payload]")
      assertEquals(
        ResidencyScope.NO_STATE_ON_FILE.value,
        packagesOf(payload)
          .single()
          .getValue("residency_scope")
          .jsonPrimitive.content,
        "and the comparison is a named absence rather than a claim about her",
      )
      assertEquals(
        TimingAnswer.WINDOW_AHEAD.value,
        timingOf(payload).getValue("answer").jsonPrimitive.content,
        "nothing waits on the answer: the whole cycle half is still here",
      )
    }

  /**
   * A MONEY-PROFILE READ THAT FAULTED IS NEVER SPOKEN AS A FACT ABOUT THE
   * FAMILY. "We could not read it" is not "you did not tell us", and residency
   * is what decides whether an in-state package is hers at all -- so the one
   * thing this payload may not do is state the absence as ours.
   *
   * Driven through the `readProfile` seam, which is the branch no migrated
   * database can be asked for on demand.
   *
   * PROVED BY REVERTING THE FIX: map
   * [ed.unicoach.coaching.Residency.Cause.READ_FAILED] back to
   * `NO_STATE_ON_FILE` in `ResidencyScope.scopeOfUnusable` and both the scope
   * and the sentence assertions go red by name.
   */
  @Test
  fun `a profile read that FAULTED is labelled unavailable, and no sentence says we hold no state`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Residents Only University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.IN_STATE))
      val tool =
        NationalMeritChatTool(
          service = NationalMeritTestDb.serviceAt(NationalMeritTestDb.NOON_EASTERN_2028_09_01),
          moneyProfiles = NationalMeritTestDb.moneyProfileService,
          readProfile = { Result.failure(IllegalStateException("the money profile read is on fire")) },
        )

      val payload = tool.execute(student, buildJsonObject {})
      val pack = packagesOf(payload).single()

      assertEquals(
        emptyList(),
        NO_STATE_CLAIMS.filter { payload.toString().contains(it, ignoreCase = true) },
        "nothing anywhere in this payload claims we hold no state for this family: [$payload]",
      )
      assertEquals(
        ResidencyScope.STATE_UNAVAILABLE.value,
        pack.getValue("residency_scope").jsonPrimitive.content,
        "a read that failed is its own answer, and not the one for a family nobody has asked: [$pack]",
      )
      assertTrue(
        pack
          .getValue("residency_scope_why")
          .jsonPrimitive.content
          .contains("could not be used"),
        "the sentence says what actually happened: [$pack]",
      )
      assertTrue(
        ResidencyOffer.UNAVAILABLE_KEY in payload,
        "and the cause is still at the top of the payload, where the offer would otherwise be: [$payload]",
      )
      assertTrue(ResidencyOffer.KEY !in payload, "a family we could not read is not a family to ask again")
      assertEquals(
        TimingAnswer.WINDOW_AHEAD.value,
        timingOf(payload).getValue("answer").jsonPrimitive.content,
        "and nothing else is lost to it: the whole cycle half is still here",
      )
    }

  /**
   * A LETTER ROUTE WITH NO PUBLISHED DEADLINE REACHES THE MODEL AS ITS OWN WORD,
   * and the sentence does not call the cycle closed.
   *
   * This is the recovery route the slice exists to name: brief 0010 correction 1
   * is why "miss it and it is gone forever" is false, and an undated route
   * served as `cycle_closed` makes it true again.
   */
  @Test
  fun `an undated letter route is its own answer on the wire, and no sentence says both routes closed`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(
        NationalMeritTestDb.datedCycle(alternateEntry = NationalMeritTestDb.undatedAlternateEntry()),
      )

      val timing = timingOf(payloadFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_31))
      val why = timing.getValue("why").jsonPrimitive.content

      assertEquals(TimingAnswer.ALTERNATE_ENTRY_UNDATED.value, timing.getValue("answer").jsonPrimitive.content)
      assertTrue(
        NationalMeritTestDb.UNDATED_ALTERNATE_ENTRY_AS_PUBLISHED in why,
        "the sentence quotes the route the publisher describes: [$why]",
      )
      assertTrue(why.contains("NOT a cycle to call closed"), "and it refuses to call the cycle shut: [$why]")
      assertEquals(
        JsonNull,
        timing
          .getValue("cycle")
          .jsonObject
          .getValue("alternate_entry")
          .jsonObject
          .getValue("deadline"),
        "we hold no day for it, and no date is invented for one: [$timing]",
      )
      assertTrue(
        TimingAnswer.ALTERNATE_ENTRY_UNDATED.value in NationalMeritChatTool.DESCRIPTION,
        "and the legend names the answer, because the model reads the payload through it",
      )
    }

  /**
   * THE BASE RATE IS SPOKEN AS EVERY OTHER SHARE IN THIS CODEBASE IS, because it
   * comes out of the one type that owns `ratio * 100` and the rounding rule
   * ([ed.unicoach.common.util.Share]) -- a hand-written `%.1f` here said "1.0%"
   * where every other coach-facing share says "1%". This is the figure that must
   * ride beside every dollar amount, so it is the last one to be hand-formatted.
   */
  @Test
  fun `a whole-percent base rate is spoken as a whole percent, and the wire figure is the same percent`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(
        NationalMeritTestDb.datedCycle(
          funnel =
            ed.unicoach.db.dao.NationalMeritFixture
              .funnel(
                metEntryRequirements = 1_200_000,
                finalists = 12_000,
                asPublished = "About 1.2 million entrants; some 12,000 reach Finalist.",
              ),
        ),
      )

      val baseRate = payloadFor(student).getValue("base_rate").jsonObject

      assertEquals(
        1.0,
        baseRate
          .getValue("finalist_share_of_entrants_percent")
          .jsonPrimitive.content
          .toDouble(),
        "the wire carries the PERCENT its key names, and not a 0-1 ratio beside a percent sentence",
      )
      assertEquals(
        "about 1% of entrants reach Finalist",
        baseRate.getValue("finalist_share_said").jsonPrimitive.content,
        "and the sentence is the type's number: a whole percent is said as a whole percent",
      )
    }

  /**
   * A READ THAT FAULTS IS A REFUSAL NAMING THE TOOL AND THE SCOPE, and nothing
   * about the student -- a branch no migrated database can be asked for on
   * demand, which is what the read seam is for.
   */
  @Test
  fun `a failing read refuses, naming the tool and the scope and no identifier`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      val tool =
        NationalMeritChatTool(
          service = NationalMeritTestDb.serviceAt(NationalMeritTestDb.NOON_EASTERN_2028_09_01),
          moneyProfiles = NationalMeritTestDb.moneyProfileService,
          read = { _, _, _ -> Result.failure(IllegalStateException("the corpus is on fire")) },
          readProfile = { Result.success(GetMoneyProfileResult.NotFound) },
        )

      val payload = tool.execute(student, buildJsonObject {})
      val error = payload.getValue(StudentScopedChatTool.ERROR_KEY).jsonPrimitive.content

      assertTrue(error.startsWith(NationalMeritChatTool.TOOL_NAME), "the refusal names which tool spoke: [$error]")
      assertTrue(error.contains("whole active list"), "and the scope it was asked for: [$error]")
      assertTrue(student.value.toString() !in payload.toString(), "and no identifier: [$payload]")
    }

  /**
   * WHERE THE BASE RATE IS UNAVAILABLE, THE PRICE IS WITHHELD -- figures and the
   * publisher's own words alike, with the reason named.
   *
   * Driven through the read seam, because Route A's migration seed makes a
   * database with no funnel anywhere unreachable: the cycles arrive by migration,
   * so `bin/test` cannot produce that state. The contract still has to hold on the
   * wire, and this is where it is held.
   */
  @Test
  fun `with no base rate the payload states no price at all, and says why`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Withheld University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))
      val priced =
        NationalMeritTestDb
          .serviceAt(NationalMeritTestDb.NOON_EASTERN_2028_09_01)
          .getForStudent(student)
          .getOrThrow()
      val withheld =
        priced.copy(
          baseRate = null,
          schools =
            priced.schools.map { answer ->
              val automatic = answer as NationalMeritSchoolAnswer.Automatic
              automatic.copy(published = PublishedPackage.of(automatic.claim, baseRate = null))
            },
        )
      val tool =
        NationalMeritChatTool(
          service = NationalMeritTestDb.serviceAt(NationalMeritTestDb.NOON_EASTERN_2028_09_01),
          moneyProfiles = NationalMeritTestDb.moneyProfileService,
          read = { _, _, _ -> Result.success(withheld) },
          readProfile = { Result.success(GetMoneyProfileResult.NotFound) },
        )

      val payload = tool.execute(student, buildJsonObject {})
      val pack = packagesOf(payload).single()

      assertEquals(JsonNull, payload.getValue("base_rate"), "no cycle publishes one, so there is none to state")
      assertEquals(emptyList(), moneyKeysIn(pack), "and no price reaches the family at all: [$pack]")
      assertTrue(
        pack
          .getValue("price_why")
          .jsonPrimitive.content
          .contains("withheld"),
        "the withholding is NAMED, so nobody reads it as a school that pays nothing: [$pack]",
      )
      assertTrue(
        payload
          .getValue("base_rate_why")
          .jsonPrimitive.content
          .contains("gap in OUR data"),
        "and the absence is said to be ours: [$payload]",
      )
    }

  /**
   * AN EMPTY LIST IS THE FIRST PAYLOAD THIS FEATURE EVER SENDS, and it states
   * the base rate.
   *
   * The proactive nudge fires for a junior in her lead window
   * ([ed.unicoach.coaching.synthesis.SynthesisService] `runPsatWindowNudgeStep`),
   * and a junior with no college list yet is exactly who that is. Her payload
   * still speaks NMSC's own $2,500 from the cycle object, so a head base rate
   * taken from her priced packages would state a figure with no counterweight
   * and a sentence saying our corpus holds no funnel -- while the 2028 row
   * publishes a complete one.
   *
   * PROVED BY REVERTING THE CARRY: derive [NationalMeritReading.baseRate] from
   * the packages again and this fails on the base rate, not on the money.
   */
  @Test
  fun `an empty college list still states the base rate, beside the sponsor's own award`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val payload = payloadFor(student)

      assertEquals(emptyList(), packagesOf(payload), "she has no schools at all, so there is no package to price")
      assertNotEquals(
        JsonNull,
        cycleOf(payload).getValue("nmsc_own_award_usd"),
        "the cycle object still speaks the sponsor's own figure: [$payload]",
      )
      assertNotEquals(
        JsonNull,
        payload.getValue("base_rate"),
        "so the counterweight to it must be stated with it, with no priced package anywhere: [$payload]",
      )
      assertEquals(
        NationalMeritChatTool.BASE_RATE_WHY,
        payload.getValue("base_rate_why").jsonPrimitive.content,
        "and the reason is the one that is TRUE of our corpus, not the no-funnel sentence",
      )
    }

  /** Her own year of school and her class ride as STRINGS: a bare year is a number the model would compute with. */
  @Test
  fun `the student's year of school and class ride as words and never as a number`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val payload = payloadFor(student)

      assertEquals(HighSchoolYear.JUNIOR.label, payload.getValue("year_of_school").jsonPrimitive.content)
      assertEquals(
        NationalMeritTestDb.JUNIOR_CLASS.toString(),
        payload.getValue("graduation_year").jsonPrimitive.content,
      )
      assertTrue(
        payload
          .getValue("graduation_year")
          .jsonPrimitive.isString,
        "a class year is a label and never an integer on this wire: [$payload]",
      )
    }

  /** The two publishers are cited separately, once each, and the citation note says what they cover (RFC 190). */
  @Test
  fun `the cycle cites both of its publishers once, and the package cites its own school once`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Cited University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      val payload = payloadFor(student)
      val cycle = cycleOf(payload)
      val pack = packagesOf(payload).single()

      assertTrue(
        cycle.getValue("sponsor_publisher").jsonPrimitive.content !=
          cycle.getValue("administrator_publisher").jsonPrimitive.content,
        "two publishers, and the row would have lost half its provenance if they were one: [$cycle]",
      )
      assertEquals(NationalMeritTestDb.CYCLE_CHECKED_ON.toString(), cycle.getValue("checked_on").jsonPrimitive.content)
      assertTrue(
        cycle
          .getValue("citation_note")
          .jsonPrimitive.content
          .contains("TWO publishers"),
      )
      assertEquals(NationalMeritTestDb.DEFAULT_PUBLISHER, pack.getValue("publisher").jsonPrimitive.content)
      assertEquals(NationalMeritTestDb.AWARD_CHECKED_ON.toString(), pack.getValue("checked_on").jsonPrimitive.content)
    }

  /**
   * THE DESCRIPTION IS A SCHEMA LEGEND (prompts/README.md, RFC 192): it names
   * keys and their values, and it never tells the coach what to say. Every
   * behavioural rule about this corpus lives in the coach prompt, which is its one
   * home.
   */
  @Test
  fun `the description names the keys it emits and instructs the coach in nothing`() {
    val description = NationalMeritChatTool.DESCRIPTION

    listOf(
      "cycle_answer",
      "base_rate",
      "finalist_share_of_entrants_percent",
      "residency_scope",
      ResidencyScope.STATE_UNAVAILABLE.value,
      TimingAnswer.ALTERNATE_ENTRY_UNDATED.value,
      "award_as_published",
      "qualifying_score_as_published",
      PackageAnswer.AUTOMATIC.value,
      PackageAnswer.COMPETITIVE.value,
      PackageAnswer.EXPIRED.value,
      PackageAnswer.NOT_CURATED.value,
    ).forEach { key ->
      assertTrue(key in description, "the legend must name [$key], because the model reads the payload through it")
    }
    assertEquals(
      emptyList(),
      BEHAVIOURAL_PHRASES.filter { description.contains(it, ignoreCase = true) },
      "a tool description is a schema legend and never copy: those sentences belong in prompts/coach-system-prompt.md",
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun toolFor(
    instant: String = NationalMeritTestDb.NOON_EASTERN_2028_09_01,
    residencyState: String? = null,
  ): NationalMeritChatTool =
    NationalMeritChatTool(
      service = NationalMeritTestDb.serviceAt(instant),
      moneyProfiles = NationalMeritTestDb.moneyProfileService,
      readProfile = { Result.success(profileResultFor(residencyState)) },
    )

  /**
   * The money profile a test hands the tool: a row that ANSWERED a state, or no
   * row at all.
   *
   * Through the tool's own [readProfile] seam rather than by writing
   * `money_profiles`, because what this suite is about is the PAYLOAD -- and the
   * seam is the one the two landed residency doors already use.
   */
  private fun profileResultFor(residencyState: String?): GetMoneyProfileResult =
    if (residencyState == null) {
      GetMoneyProfileResult.NotFound
    } else {
      GetMoneyProfileResult.Found(
        MoneyProfile(
          id = MoneyProfileId(java.util.UUID.randomUUID()),
          studentId = StudentId(java.util.UUID.randomUUID()),
          incomeBand = null,
          incomeBandStatus = AnswerStatus.UNANSWERED,
          residencyState = residencyState,
          residencyStatus = AnswerStatus.ANSWERED,
          livingPlan = null,
          livingPlanStatus = AnswerStatus.UNANSWERED,
          dependency = null,
          dependencyStatus = AnswerStatus.UNANSWERED,
          version = 1,
          createdAt = Instant.parse(NationalMeritTestDb.NOON_EASTERN_2028_09_01),
          updatedAt = Instant.parse(NationalMeritTestDb.NOON_EASTERN_2028_09_01),
          deletedAt = null,
        ),
      )
    }

  private suspend fun payloadFor(
    student: StudentId,
    instant: String = NationalMeritTestDb.NOON_EASTERN_2028_09_01,
    residencyState: String? = null,
  ): JsonObject = toolFor(instant, residencyState).execute(student, buildJsonObject {})

  private fun timingOf(payload: JsonObject): JsonObject = payload.getValue("cycle_answer").jsonObject

  private fun cycleOf(payload: JsonObject): JsonObject = timingOf(payload).getValue("cycle").jsonObject

  /** Every package object on the wire, across every college. */
  private fun packagesOf(payload: JsonObject): List<JsonObject> =
    payload
      .getValue("colleges")
      .jsonArray
      .flatMap { college ->
        college.jsonObject
          .getValue("packages")
          .jsonArray
          .map { it.jsonObject }
      }

  /** Every key in [element], at any depth -- what the no-ranking sweep reads. */
  private fun allKeysIn(element: JsonElement): List<String> =
    when (element) {
      is JsonObject -> element.keys.toList() + element.values.flatMap { allKeysIn(it) }
      is kotlinx.serialization.json.JsonArray -> element.flatMap { allKeysIn(it) }
      else -> emptyList()
    }

  /**
   * Every key in [element] that NAMES MONEY and holds something -- the five the
   * price is made of.
   *
   * `award_as_published` is one of them, and that is the point: half the paying
   * schools in this corpus publish no figure at all, so a sweep over integers
   * alone would let a stale or unpriced package quote its own six-figure sentence.
   */
  private fun moneyKeysIn(element: JsonElement): List<String> =
    when (element) {
      is JsonObject -> {
        element.entries.filter { (key, value) -> key in MONEY_KEYS && value != JsonNull }.map { it.key } +
          element.values.flatMap { moneyKeysIn(it) }
      }

      is kotlinx.serialization.json.JsonArray -> {
        element.flatMap { moneyKeysIn(it) }
      }

      else -> {
        emptyList()
      }
    }

  private companion object {
    /** The keys a price is made of, the publisher's own words included. */
    val MONEY_KEYS: Set<String> =
      setOf("award_per_year_usd", "award_total_usd", "award_years", "award_as_published")

    /** A key shaped like a bar a student has to clear. No numeric key on this wire may match it. */
    val CUTOFF_SHAPED_KEY: Regex = Regex("cutoff|qualifying_score|required_score|score_needed", RegexOption.IGNORE_CASE)

    /** A key shaped like a ranking, a total across schools, or a comparison. None may exist at all. */
    val RANKING_SHAPED_KEY: Regex =
      Regex("\\brank|best_|_best|highest|largest|top_|_total_across|compare|better", RegexOption.IGNORE_CASE)

    /** Sentences that would state a score to reach. None may appear in the payload or the description. */
    val CUTOFF_CLAIMS: List<String> =
      listOf(
        "you need a selection index of",
        "the cutoff is",
        "the qualifying score is",
        "she needs at least",
      )

    /** Copy, not legend: a description that says these is a second home for a behavioural rule (RFC 192). */
    val BEHAVIOURAL_PHRASES: List<String> =
      listOf(
        "tell the family",
        "tell them",
        "say to the family",
        "never say",
        "reassure",
        "encourage her",
      )

    /**
     * Sentences that state the ABSENCE of a residency as a fact we hold. None
     * may appear in a payload built from a profile read that FAULTED: we do not
     * know what that family told us.
     */
    val NO_STATE_CLAIMS: List<String> =
      listOf(
        "We hold no state of residency",
        "no state of residency for this family",
      )

    /** A reading read a year before this suite's clock, and believed only through the summer before it. */
    val STALE_CHECKED_ON: java.time.LocalDate = java.time.LocalDate.of(2027, 9, 1)
    val STALE_EXPIRES_ON: java.time.LocalDate = java.time.LocalDate.of(2028, 7, 31)
  }
}
