package ed.unicoach.coaching.aid

import ed.unicoach.chat.BareSourceCodeGuard
import ed.unicoach.chat.PayloadDateGuard
import ed.unicoach.coaching.COACH_ZONE
import ed.unicoach.coaching.COACH_ZONE_LABEL
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool.Companion.ERROR_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.ARCHIVE_URL_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.AS_OF_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.AS_OF_ZONE_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.AS_PUBLISHED_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.AWARD_YEAR_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.AWARD_YEAR_STATEMENT_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.AWARD_YEAR_STATUS_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.CHECKED_ON_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.CYCLE_CURRENT
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.CYCLE_OTHER_CYCLE
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.DATE_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.EVENT_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.FEDERAL_DEADLINES_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.FEDERAL_EVENTS_ABSENT_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.FILING_YEAR_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.HELD_AWARD_YEARS_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.KIND_IN_WORDS
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.KIND_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.KIND_NONE_PUBLISHED
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.KIND_ON_DATE
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.NO_DEADLINES_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.PROGRAM_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.PUBLISHER_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.REASON_EMPTY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.REASON_EXPIRED
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.REASON_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.REASON_SUPERSEDED_CYCLE
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.REFUSAL_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.SOURCE_NOTE_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.SOURCE_URL_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.STATE_DEADLINES_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.STATE_GAP_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.STATE_KEY
import ed.unicoach.coaching.aid.FafsaDeadlineChatTool.Companion.STATE_NAME_KEY
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.FafsaFilingDeadlineFixture
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfileUpsert
import ed.unicoach.db.models.NewFafsaFilingDeadline
import ed.unicoach.db.models.PublishedDeadline
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ed.unicoach.db.dao.FafsaFilingDeadlineFixture.deadline as row

/**
 * The rendered half of RFC 237: the `fafsa_deadlines` payload.
 *
 * The RULES are asserted in `FafsaDeadlineServiceTest` -- the three refusal
 * arms, the D23 live set, the region grouping and the expiry boundary all
 * belong to the service. This suite asserts what only the DOOR can be wrong
 * about: that the rows on the wire ARE the service's rows, that the family's
 * state comes from their money profile and from nowhere else, that every kind
 * of absence is named as OUR gap rather than as a claim about a state, and that
 * a refusal carries no date at all.
 *
 * Postgres-backed, because the answer is a read: the corpus is this suite's own
 * rows through [FafsaFilingDeadlineFixture], the state vocabulary is
 * [CodebookReferenceFixture]'s, and the clock is pinned per case so every cycle
 * and expiry boundary is asked for on purpose.
 */
class FafsaDeadlineChatToolTest {
  private val moneyProfiles = MoneyProfileService(CoachingTestDb.database)

  @BeforeEach
  fun setUp() {
    FafsaFilingDeadlineFixture.deleteAll(CoachingTestDb.sqlSession)
    // Every state row foreign-keys us_states, and the service joins us_states to
    // ipeds_regions for the region and the state's name -- so both vocabularies
    // must be present before any deadline row is written or read.
    CodebookReferenceFixture.seed(CoachingTestDb.sqlSession)
  }

  /**
   * THE ONE-CALL-PATH CLAIM: for one seeded corpus the tool's rows are
   * [FafsaDeadlineService]'s rows, field for field, `as_published` verbatim,
   * with nothing added and nothing dropped.
   *
   * It is the property the public `/fafsa` page will rest on (RFC 230 D4): the
   * page renders the same service values, so a coach that re-derived, re-sorted
   * or re-worded a row here is how the two doors would come to tell one family
   * two different stories. The page-side half of the equivalence arrives with
   * the page; this is the half that exists today.
   */
  @Test
  fun `the rows the coach serves are the service's own rows, field for field`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val payload = execute(student)
    val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(AFTER_OCTOBER_2026)))

    assertEquals(resolved.asOf.toString(), payload.getValue(AS_OF_KEY).jsonPrimitive.content)
    assertEquals(COACH_ZONE_LABEL, payload.getValue(AS_OF_ZONE_KEY).jsonPrimitive.content, "the coach's zone, not the device's")
    assertEquals(resolved.filingYear.label, payload.getValue(FILING_YEAR_KEY).jsonPrimitive.content)
    assertRowsAreTheServicesOwn(resolved.federal, payload.getValue(FEDERAL_DEADLINES_KEY).jsonArray, "federal")
    assertRowsAreTheServicesOwn(
      resolved.states.single { it.jurisdiction.uspsCode == "CA" }.deadlines,
      payload.getValue(STATE_DEADLINES_KEY).jsonArray,
      "California",
    )
  }

  /** A state on file gets the federal rows AND its own, each row carrying the publisher, the page and the day we read it. */
  @Test
  fun `a state on file is served its own rows beside the federal ones, cited per row`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val payload = execute(student)

    assertEquals(LEGEND_KEYS, payload.keys, "the answer carries exactly the keys the legend explains")
    assertEquals("CA", payload.getValue(STATE_KEY).jsonPrimitive.content)
    assertEquals(nameOf("CA"), payload.getValue(STATE_NAME_KEY).jsonPrimitive.content, "the state's own name, from the codebook")
    assertEquals(JsonNull, payload.getValue(STATE_GAP_KEY), "we served that state's rows, so there is no gap to name")
    assertTrue(payload.getValue(FEDERAL_DEADLINES_KEY).jsonArray.isNotEmpty(), "the federal rows bind every family")
    val california = payload.getValue(STATE_DEADLINES_KEY).jsonArray
    assertEquals(
      listOf(CAL_GRANT, CAL_GRANT_IN_WORDS),
      california.map {
        it.jsonObject
          .getValue(PROGRAM_KEY)
          .jsonPrimitive.content
      },
      "both of that state's live rows, in the order the read served them",
    )
    california.forEach { served ->
      val deadline = served.jsonObject
      assertEquals(FafsaFilingDeadlineFixture.DEFAULT_PUBLISHER, deadline.getValue(PUBLISHER_KEY).jsonPrimitive.content)
      assertTrue(
        deadline
          .getValue(SOURCE_URL_KEY)
          .jsonPrimitive.content
          .startsWith("https://"),
        "every row cites the publisher's own page: [$deadline]",
      )
      assertEquals(
        FafsaFilingDeadlineFixture.DEFAULT_CHECKED_ON.toString(),
        deadline.getValue(CHECKED_ON_KEY).jsonPrimitive.content,
        "the day a human read that page rides on the row",
      )
    }
  }

  /**
   * THE MODEL CANNOT NAME A STATE. Any input key is refused -- `state` above
   * all, which is the point of a closed no-argument schema here: a state held
   * in a tool argument is a fact unicoach never learns, so there is no argument
   * for the model to contradict the profile with.
   */
  @Test
  fun `a state in the input is refused, and the answer still comes from the profile`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val refused = execute(student, input = buildJsonObject { put(STATE_KEY, "TX") })
    assertTrue(
      refused
        .getValue(ERROR_KEY)
        .jsonPrimitive.content
        .contains(STATE_KEY),
      "got $refused",
    )
    assertNull(refused[STATE_DEADLINES_KEY], "an error is not also a payload")

    // And the state the model tried to pass changed nothing: the answer is the
    // profile's state, on the very next call.
    assertEquals("CA", execute(student).getValue(STATE_KEY).jsonPrimitive.content)
  }

  /**
   * NOBODY HAS ASKED: the federal dates are served anyway, with the gap named.
   *
   * Both ways that happens are asserted, because the tool must not tell them
   * apart: a family with no money profile at all, and a family whose profile
   * exists with the residency question never put.
   */
  @Test
  fun `a family nobody has asked is served the federal dates with the not_asked gap`() {
    seed(theCorpus())

    listOf(student(), student().also { leaveResidencyUnanswered(it) }).forEach { student ->
      val payload = execute(student)

      assertFederalOnly(payload, StateGap.NOT_ASKED)
      assertEquals(JsonNull, payload.getValue(STATE_KEY), "we hold no state, so the key is an explicit null")
      assertEquals(JsonNull, payload.getValue(STATE_NAME_KEY))
    }
  }

  /** THEY CHOSE NOT TO SAY -- a decision of theirs, named apart from a fault of ours, and the federal dates still land. */
  @Test
  fun `a family that declined is served the federal dates with the declined gap`() {
    val student = student()
    declineResidency(student)
    seed(theCorpus())

    val payload = execute(student)

    assertFederalOnly(payload, StateGap.DECLINED)
  }

  /**
   * A PROFILE READ THAT FAULTED still answers. This is the branch the
   * `readProfile` seam exists for: a Postgres-backed test cannot ask for a
   * fault on demand, and it is the branch that decides whether a database fault
   * turns a working answer into an error.
   *
   * `unavailable` and not `not_asked`: we do not know what the family said, and
   * the payload must not claim they have said nothing.
   */
  @Test
  fun `a profile that could not be read still answers, with the unavailable gap`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val tool =
      FafsaDeadlineChatTool(
        serviceAt(AFTER_OCTOBER_2026),
        moneyProfiles,
        readProfile = { Result.failure(IllegalStateException("the money-profile read faulted")) },
      )
    val payload = runBlocking { tool.execute(student, buildJsonObject {}) }

    assertFederalOnly(payload, StateGap.UNAVAILABLE)
    assertEquals(JsonNull, payload.getValue(STATE_KEY), "a faulted read holds no state to name")
  }

  /**
   * A DEADLINE READ THAT FAULTED IS AN ERROR, AND NEVER A REFUSAL. "We hold no
   * FAFSA deadlines" is a claim about our corpus, and a database that would not
   * answer is no evidence for it -- so the fault must not reach a family
   * wearing the refusal vocabulary this slice built to be truthful.
   *
   * This is what the `readDeadlines` seam exists for: [FafsaDeadlineService] is
   * concrete and reads a real database, so no Postgres-backed case can fault it
   * on demand, and an inverted or mis-worded guard here would be silent on
   * every green run.
   */
  @Test
  fun `a deadline read that faulted is a structured error, never a refusal`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val tool =
      FafsaDeadlineChatTool(
        serviceAt(AFTER_OCTOBER_2026),
        moneyProfiles,
        readDeadlines = { Result.failure(IllegalStateException("the deadline read faulted")) },
      )
    val payload = runBlocking { tool.execute(student, buildJsonObject {}) }

    assertEquals(
      FafsaDeadlineChatTool.READ_FAILED_REASON,
      payload.getValue(ERROR_KEY).jsonPrimitive.content,
      "the reason the family hears is the const, never a retyped copy",
    )
    assertNull(payload[NO_DEADLINES_KEY], "a fault must never reach a family as 'we hold none'")
    assertNull(payload[REFUSAL_KEY], "and never wearing a refusal sentence of ours")
    assertNull(payload[FEDERAL_DEADLINES_KEY], "a failed read serves no rows at all")
  }

  /** A residency outside the 50 states and DC (D13) is said to be outside the corpus, and is never a gap in it. */
  @Test
  fun `a residency this corpus does not cover is labelled not_served`() {
    val student = student()
    answerResidency(student, "PR")
    seed(theCorpus())

    val payload = execute(student)

    assertFederalOnly(payload, StateGap.NOT_SERVED)
    assertEquals("PR", payload.getValue(STATE_KEY).jsonPrimitive.content, "the state we hold is still named")
  }

  /**
   * A RULE IN WORDS IS SERVED AS WORDS. The publisher named no day, so the row
   * carries none: `kind` says so, `date` is an explicit null, and the sweep
   * proves no date of ours appears anywhere in that row.
   */
  @Test
  fun `a rule in words is served as words with no date of ours`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val served =
      execute(student)
        .getValue(STATE_DEADLINES_KEY)
        .jsonArray
        .map { it.jsonObject }
        .single { it.getValue(PROGRAM_KEY).jsonPrimitive.content == CAL_GRANT_IN_WORDS }

    assertEquals(KIND_IN_WORDS, served.getValue(KIND_KEY).jsonPrimitive.content)
    assertEquals(JsonNull, served.getValue(DATE_KEY), "a rule has no day, and the key is an explicit null")
    assertEquals(IN_WORDS, served.getValue(AS_PUBLISHED_KEY).jsonPrimitive.content, "the publisher's wording, verbatim")

    assertTrue(PayloadDateGuard.isoDatePatternFires(), "the date pattern must be able to fire, or this sweep proves nothing")
    assertEquals(
      listOf(FafsaFilingDeadlineFixture.DEFAULT_CHECKED_ON.toString()),
      PayloadDateGuard.datesIn(served),
      "the only date on a rule-in-words row is the day WE read the page",
    )
  }

  /**
   * D14: a state that also demands its own application serves BOTH rows.
   *
   * The extra application has no typed flag anywhere -- it is a separate row
   * with its own `program` and the publisher's own words -- so the regression
   * this case guards is a renderer that collapses a state to "the" deadline and
   * makes a family miss half the work.
   */
  @Test
  fun `a state that also demands its own application serves both of its rows`() {
    val student = student()
    answerResidency(student, "TN")
    seed(theCorpus())

    val served = execute(student).getValue(STATE_DEADLINES_KEY).jsonArray.map { it.jsonObject }

    assertEquals(
      listOf(TENNESSEE_PROMISE, TENNESSEE_STATE_APPLICATION),
      served.map { it.getValue(PROGRAM_KEY).jsonPrimitive.content },
      "both programmes, because a single date per state is half the work",
    )
    assertEquals(
      listOf(PROMISE_FAFSA_DAY.toString(), PROMISE_APPLICATION_DAY.toString()),
      served.map { it.getValue(DATE_KEY).jsonPrimitive.content },
      "and both published days, neither collapsed into the other",
    )
  }

  /**
   * A STATE WE COULD NOT READ IS A GAP OF OURS (D9). It is the answer this
   * vocabulary exists to keep apart from a verified absence: nowhere may the
   * payload read as though that state publishes no deadline.
   */
  @Test
  fun `a state we could not read is a gap of ours, never a state that publishes none`() {
    val student = student()
    answerResidency(student, "AR")
    seed(theCorpus())

    val payload = execute(student)

    assertFederalOnly(payload, StateGap.NOT_VERIFIED)
    assertEquals("AR", payload.getValue(STATE_KEY).jsonPrimitive.content)
    assertEquals(JsonNull, payload.getValue(STATE_NAME_KEY), "a state we hold no rows for is named by its code alone")
    assertFalse(
      payload.toString().contains(KIND_NONE_PUBLISHED),
      "a jurisdiction nobody could verify must not reach a family as a published absence: $payload",
    )
  }

  /**
   * A VERIFIED ABSENCE IS A ROW, not a gap: we read the page on `checked_on`
   * and it publishes no deadline for that programme. `state_gap` is null,
   * because that state's rows WERE served -- the two answers are not the same
   * answer, and a family must be able to tell them apart.
   */
  @Test
  fun `a verified absence is a row with its citation, and not a gap`() {
    val student = student()
    answerResidency(student, "NV")
    seed(theCorpus())

    val payload = execute(student)

    assertEquals(JsonNull, payload.getValue(STATE_GAP_KEY), "we hold and served that state's answer")
    val served =
      payload
        .getValue(STATE_DEADLINES_KEY)
        .jsonArray
        .single()
        .jsonObject
    assertEquals(KIND_NONE_PUBLISHED, served.getValue(KIND_KEY).jsonPrimitive.content)
    assertEquals(JsonNull, served.getValue(DATE_KEY))
    assertEquals(NONE_PUBLISHED, served.getValue(AS_PUBLISHED_KEY).jsonPrimitive.content)
    assertTrue(
      served
        .getValue(SOURCE_URL_KEY)
        .jsonPrimitive.content
        .startsWith("https://"),
      "the citation that proves we checked",
    )
    assertEquals(FafsaFilingDeadlineFixture.DEFAULT_CHECKED_ON.toString(), served.getValue(CHECKED_ON_KEY).jsonPrimitive.content)
  }

  /** An empty corpus refuses with the sentence for an empty corpus, and with no date anywhere in the payload. */
  @Test
  fun `an empty corpus refuses as empty, with no date at all`() {
    val payload = execute(student())

    assertRefusal(payload, REASON_EMPTY, NoDeadlinesReason.EMPTY)
    assertEquals(
      emptyList(),
      payload.getValue(HELD_AWARD_YEARS_KEY).jsonArray.toList(),
      "there is no cycle label, because there are no rows",
    )
  }

  /**
   * THE NAMED FAILURE THIS SLICE EXISTS TO PREVENT: the filing cycle has moved
   * past the corpus, so last cycle's dates are refused rather than repeated as
   * this cycle's. Every row is healthy and unexpired, and the refusal names the
   * cycle we hold instead of serving it.
   */
  @Test
  fun `a corpus one cycle behind the clock refuses as superseded_cycle`() {
    seed(listOf(row(awardYear = LAST_CYCLE), row(jurisdiction = state("CA"), awardYear = LAST_CYCLE)))

    val payload = execute(student())

    assertRefusal(payload, REASON_SUPERSEDED_CYCLE, NoDeadlinesReason.SUPERSEDED_CYCLE)
    assertEquals(
      listOf(LAST_CYCLE.label),
      payload.getValue(HELD_AWARD_YEARS_KEY).jsonArray.map { it.jsonPrimitive.content },
      "the cycle we hold is NAMED, never served",
    )
  }

  /** Rows nobody has re-checked since `expires_on` refuse with the out-of-date sentence, which is true only of them. */
  @Test
  fun `a corpus past its expiry refuses as expired`() {
    seed(listOf(row(expiresOn = EXPIRY), row(jurisdiction = state("CA"), expiresOn = EXPIRY)))

    val payload = execute(student(), instant = AFTER_EXPIRY)

    assertRefusal(payload, REASON_EXPIRED, NoDeadlinesReason.EXPIRED)
    assertEquals(
      listOf(FafsaFilingDeadlineFixture.DEFAULT_AWARD_YEAR.label),
      payload.getValue(HELD_AWARD_YEARS_KEY).jsonArray.map { it.jsonPrimitive.content },
    )
  }

  /** The three refusals are three different sentences, because the three facts are different facts. */
  @Test
  fun `the three refusals say three different things`() {
    val refusals = NoDeadlinesReason.entries.map { FafsaDeadlineChatTool.refusalFor(it) }

    assertEquals(refusals.size, refusals.distinct().size, "one sentence per reason: $refusals")
    refusals.forEach { refusal ->
      assertTrue(refusal.contains("studentaid.gov"), "every refusal says where to file instead: [$refusal]")
      assertTrue(
        refusal.contains("state's student aid agency"),
        "and sends them to their own state's agency for the state deadline: [$refusal]",
      )
      assertEquals(emptyList(), PayloadDateGuard.datesIn(refusal), "a refusal to name dates carries none: [$refusal]")
    }
  }

  @Test
  fun `the definition advertises a closed no-argument schema and a key legend`() {
    val tool = toolAt(AFTER_OCTOBER_2026)
    assertEquals(FafsaDeadlineChatTool.TOOL_NAME, tool.name)

    val definition = tool.definition
    assertEquals(FafsaDeadlineChatTool.TOOL_NAME, definition.getValue("name").jsonPrimitive.content)
    val schema = definition.getValue("input_schema").jsonObject
    assertEquals(0, schema.getValue("properties").jsonObject.size, "the tool takes no argument at all")
    assertFalse(
      schema.getValue("additionalProperties").jsonPrimitive.boolean,
      "and refuses any other key",
    )

    // RFC 199: the description is a LEGEND -- one entry per wire key, read from
    // the very consts the payload is keyed by, so a renamed key breaks the
    // legend that explains it instead of leaving the model reading a dead name.
    val description = definition.getValue("description").jsonPrimitive.content
    assertTrue(description.contains("A legend of the payload's keys follows"), "the legend announces itself")
    (LEGEND_KEYS + ROW_KEYS + REFUSAL_KEYS).forEach {
      assertTrue(description.contains(it), "wire key [$it] has no legend entry")
    }
    StateGap.entries.forEach {
      assertTrue(
        description.contains("\"${it.value}\""),
        "the legend must quote the gap code [${it.value}] the wire carries",
      )
      assertTrue(description.contains(it.legend), "the legend must say what gap [${it.value}] means")
    }
    listOf(KIND_ON_DATE, KIND_IN_WORDS, KIND_NONE_PUBLISHED).forEach {
      assertTrue(description.contains("\"$it\""), "the legend must quote the kind code [$it] the wire carries")
    }
    AidDeadlineEvent.entries.forEach {
      assertTrue(description.contains("\"${it.value}\""), "the legend must quote the event code [${it.value}] the wire carries")
    }
    assertTrue(
      description.contains("NONE of them means that state publishes no deadline"),
      "the sentence the model would otherwise supply for itself is refused in the legend: [$description]",
    )
    assertTrue(
      description.contains(MoneyProfileChatTool.TOOL_NAME),
      "the legend names the tool that RECORDS a state, because a state said in chat and not recorded is not held",
    )
  }

  @Test
  fun `the unscoped ChatTool execute is a structured error`() {
    val result = runBlocking { (toolAt(AFTER_OCTOBER_2026) as ed.unicoach.chat.ChatTool).execute(buildJsonObject {}) }
    assertTrue(
      result
        .getValue(ERROR_KEY)
        .jsonPrimitive.content
        .contains("student-scoped"),
      "got $result",
    )
  }

  @Test
  fun `no bare source code reaches the payload`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    // NOTHING here is a number by contract: every date is an ISO string and
    // every cycle is its published label, so the allowlist is empty on purpose
    // and a numeric field appearing later has to be argued for.
    assertEquals(emptySet(), FafsaDeadlineChatTool.NUMBERS_BY_CONTRACT, "a number by contract must be argued for, not inherited")
    assertTrue(BareSourceCodeGuard.codeToWordPatternFires(), "the guard pattern must be able to fire")
    val payload = execute(student)
    assertEquals(
      emptyList(),
      BareSourceCodeGuard.listViolations(payload, FafsaDeadlineChatTool.NUMBERS_BY_CONTRACT).map(BareSourceCodeGuard::mapMessage),
      "a bare source code reached the FAFSA deadline answer",
    )
    assertFalse(BareSourceCodeGuard.CODE_EQUALS_WORD.containsMatchIn(payload.toString()), "the payload transcribes a source codebook")
    // The FAFSA is Common Data Set H.801; a family is told the form's name. The
    // pattern and its positive control are the shared guard's, for the reason it
    // states: a hand-typed copy that can never fire reads as covered.
    assertTrue(BareSourceCodeGuard.cdsFieldIdPatternFires(), "the CDS pattern must be able to fire, or this sweep proves nothing")
    assertFalse(
      BareSourceCodeGuard.CDS_FIELD_ID.containsMatchIn(payload.toString()),
      "a CDS field id must never reach a family: $payload",
    )
  }

  /**
   * A FEDERAL DATE WE DO NOT HOLD IS NAMED, never left as a short array.
   *
   * The D23 live set is the newest cycle per jurisdiction, so the federal half
   * of a real answer can easily be missing one of the three events this tool is
   * routed for -- and "when is the FAFSA due" answered by omission reads to the
   * model as an answer.
   */
  @Test
  fun `a federal event we hold no live row for is named as absent`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus())

    val payload = execute(student)

    assertEquals(
      listOf(AidDeadlineEvent.CORRECTIONS_DEADLINE.value),
      payload.getValue(FEDERAL_EVENTS_ABSENT_KEY).jsonArray.map { it.jsonPrimitive.content },
      "the corpus holds the opening day and the federal deadline, so the corrections date is the absence to name",
    )
  }

  /**
   * EMPTY IS THE NORMAL ANSWER, and a priority deadline is never in it: being
   * first in a queue an agency controls is a STATE instrument, so the federal
   * half holds none and its absence is not a gap.
   */
  @Test
  fun `a complete federal set names no absent event, and never a priority deadline`() {
    val student = student()
    answerResidency(student, "CA")
    seed(theCorpus() + row(program = FEDERAL_AID, event = AidDeadlineEvent.CORRECTIONS_DEADLINE))

    val payload = execute(student)

    assertEquals(
      emptyList(),
      payload.getValue(FEDERAL_EVENTS_ABSENT_KEY).jsonArray.toList(),
      "every federal event a family asks about is held, and no federal priority row exists to be missed",
    )
  }

  /**
   * A ROW FROM ANOTHER FILING CYCLE IS MARKED, and carries the sentence that
   * says so.
   *
   * The live set is the newest cycle PER JURISDICTION, so one answer routinely
   * mixes cycles: here the federal rows are this cycle's and California's is
   * last cycle's. Two bare cycle labels would leave the model to compare them
   * and decide for itself whether the date is this family's deadline.
   */
  @Test
  fun `a row from another filing cycle is marked as such and carries the sentence to say`() {
    val student = student()
    answerResidency(student, "CA")
    seed(
      listOf(
        row(program = FEDERAL_AID, event = AidDeadlineEvent.FINAL_DEADLINE),
        row(jurisdiction = state("CA"), program = CAL_GRANT, awardYear = LAST_CYCLE),
      ),
    )

    val payload = execute(student)
    val resolved = assertIs<FafsaDeadlines.Resolved>(read(serviceAt(AFTER_OCTOBER_2026)))
    val federal =
      payload
        .getValue(FEDERAL_DEADLINES_KEY)
        .jsonArray
        .single()
        .jsonObject
    val california =
      payload
        .getValue(STATE_DEADLINES_KEY)
        .jsonArray
        .single()
        .jsonObject

    assertEquals(resolved.filingYear.label, federal.getValue(AWARD_YEAR_KEY).jsonPrimitive.content, "the federal row IS this cycle")
    assertEquals(CYCLE_CURRENT, federal.getValue(AWARD_YEAR_STATUS_KEY).jsonPrimitive.content)
    assertEquals(JsonNull, federal.getValue(AWARD_YEAR_STATEMENT_KEY), "a row from the cycle being filed earns no sentence")

    assertEquals(LAST_CYCLE.label, california.getValue(AWARD_YEAR_KEY).jsonPrimitive.content, "and California's is the cycle before")
    assertEquals(
      CYCLE_OTHER_CYCLE,
      california.getValue(AWARD_YEAR_STATUS_KEY).jsonPrimitive.content,
      "a row from another cycle is marked, never served bare beside a current one",
    )
    assertEquals(
      FafsaDeadlineChatTool.otherCycleStatement(LAST_CYCLE, resolved.filingYear),
      california.getValue(AWARD_YEAR_STATEMENT_KEY).jsonPrimitive.content,
      "the sentence the family hears is the const, never a retyped copy",
    )
  }

  // ---------------------------------------------------------------------------
  // The corpus, the clock, and the plumbing
  // ---------------------------------------------------------------------------

  /**
   * The corpus every answering case reads: the federal rows a family is bound
   * by wherever they live, a state with a dated row AND a rule in words, a
   * state whose own application is a row of its own (D14), and a state that
   * publishes nothing and says so.
   *
   * ONE corpus rather than one per case, because the claim under test is mostly
   * about which SLICE of it a family is served: a per-case store would let a
   * case pass by holding nothing to get wrong.
   */
  private fun theCorpus(): List<NewFafsaFilingDeadline> =
    listOf(
      row(
        program = FEDERAL_AID,
        event = AidDeadlineEvent.FORM_OPENS,
        deadline = PublishedDeadline.OnDate(FORM_OPENS_DAY, "October 1, 2026"),
      ),
      row(program = FEDERAL_AID, event = AidDeadlineEvent.FINAL_DEADLINE),
      row(jurisdiction = state("CA"), program = CAL_GRANT),
      row(jurisdiction = state("CA"), program = CAL_GRANT_IN_WORDS, deadline = PublishedDeadline.InWords(IN_WORDS)),
      row(
        jurisdiction = state("TN"),
        program = TENNESSEE_PROMISE,
        event = AidDeadlineEvent.FINAL_DEADLINE,
        deadline = PublishedDeadline.OnDate(PROMISE_FAFSA_DAY, "April 1, 2027"),
      ),
      row(
        jurisdiction = state("TN"),
        program = TENNESSEE_STATE_APPLICATION,
        event = AidDeadlineEvent.FINAL_DEADLINE,
        deadline = PublishedDeadline.OnDate(PROMISE_APPLICATION_DAY, "November 2, 2026"),
      ),
      row(jurisdiction = state("NV"), deadline = PublishedDeadline.NonePublished(NONE_PUBLISHED)),
    )

  /** The tool with its clock pinned -- an INSTANT with no zone on it, because the tool applies the coach's own. */
  private fun toolAt(instant: String): FafsaDeadlineChatTool = FafsaDeadlineChatTool(serviceAt(instant), moneyProfiles)

  private fun serviceAt(instant: String): FafsaDeadlineService =
    FafsaDeadlineService(CoachingTestDb.database, InstantSource.fixed(Instant.parse(instant)))

  private fun execute(
    student: StudentId,
    instant: String = AFTER_OCTOBER_2026,
    input: JsonObject = buildJsonObject {},
  ): JsonObject = runBlocking { toolAt(instant).execute(student, input) }

  /** The service's own answer, through the very zone the tool passes it. */
  private fun read(service: FafsaDeadlineService): FafsaDeadlines = runBlocking { service.get(COACH_ZONE).getOrThrow() }

  private fun seed(rows: List<NewFafsaFilingDeadline>) = FafsaFilingDeadlineFixture.seed(CoachingTestDb.sqlSession, rows)

  private fun student(): StudentId = CoachingTestDb.createStudent("fafsa-deadlines")

  /**
   * The three residency writers are separate calls and not one nullable
   * parameter, because ANSWERED, UNANSWERED and DECLINED are the tri-state the
   * whole gap vocabulary turns on: a case says which of the three it means by
   * which one it calls (the `TestTimelineTestDb` rule).
   */
  private fun answerResidency(
    student: StudentId,
    state: String,
  ) = upsertProfile(MoneyProfileUpsert(studentId = student, residency = MoneyProfileUpsert.FieldWrite.Answer(state)))

  /** A profile that EXISTS with the residency question never put: the income field is written so the row is real. */
  private fun leaveResidencyUnanswered(student: StudentId) =
    upsertProfile(MoneyProfileUpsert(studentId = student, income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.K48_TO_75K)))

  private fun declineResidency(student: StudentId) =
    upsertProfile(MoneyProfileUpsert(studentId = student, residency = MoneyProfileUpsert.FieldWrite.Declined))

  private fun upsertProfile(input: MoneyProfileUpsert) {
    MoneyProfilesDao.upsertForStudent(CoachingTestDb.sqlSession, input).getOrThrow()
  }

  /** The typed USPS code, so a case names a state in two words -- the `FafsaDeadlineServiceTest` helper, same job. */
  private fun state(code: String): DeadlineJurisdiction.State =
    requireNotNull(DeadlineJurisdiction.State.of(code)) { "[$code] is not a USPS code DeadlineJurisdiction.State admits" }

  /** That jurisdiction's own name, from the codebook the service reads it from rather than as a literal here. */
  private fun nameOf(code: String): String = CodebookReferenceFixture.STATES.first { it.uspsCode == code }.name

  /**
   * A FEDERAL-ONLY answer with [gap] named: the rows that bind every family
   * landed, no state's rows were invented, and no arm of the gap vocabulary
   * failed the tool (guided, never gated).
   *
   * One helper rather than five copies, so a sixth gap arm is one line and
   * cannot be given a weaker version of the same claim.
   */
  private fun assertFederalOnly(
    payload: JsonObject,
    gap: StateGap,
  ) {
    assertEquals(gap.value, payload.getValue(STATE_GAP_KEY).jsonPrimitive.content)
    assertFederalRowsServed(payload)
    assertEquals(emptyList(), payload.getValue(STATE_DEADLINES_KEY).jsonArray.toList(), "no state's rows are invented")
    assertNull(payload[ERROR_KEY], "no arm of the gap vocabulary fails the tool")
  }

  /** The federal rows reached the family, which every arm of the gap vocabulary owes them. */
  private fun assertFederalRowsServed(payload: JsonObject) {
    assertEquals(
      listOf(FEDERAL_AID, FEDERAL_AID),
      payload.getValue(FEDERAL_DEADLINES_KEY).jsonArray.map {
        it.jsonObject
          .getValue(PROGRAM_KEY)
          .jsonPrimitive.content
      },
      "the federal dates bind every family and are served before anything is asked for",
    )
  }

  /**
   * One refusal: the flag, the code, the sentence the tool owns, and NO DATE
   * ANYWHERE -- swept, with a positive control, because a refusal that carried
   * today would invite the model to compute from it.
   */
  private fun assertRefusal(
    payload: JsonObject,
    code: String,
    reason: NoDeadlinesReason,
  ) {
    assertTrue(
      payload.getValue(NO_DEADLINES_KEY).jsonPrimitive.boolean,
      "a refusal says so in the one key the legend names",
    )
    assertEquals(code, payload.getValue(REASON_KEY).jsonPrimitive.content)
    assertEquals(
      FafsaDeadlineChatTool.refusalFor(reason),
      payload.getValue(REFUSAL_KEY).jsonPrimitive.content,
      "the sentence the family hears is the const, never a retyped copy",
    )
    assertNull(payload[FEDERAL_DEADLINES_KEY], "a refusal carries no rows at all")
    assertNull(payload[AS_OF_KEY], "not even today: a refusal exists to withhold dates")
    assertEquals(REFUSAL_KEYS, payload.keys, "a refusal carries exactly the keys the legend explains")
    assertTrue(PayloadDateGuard.isoDatePatternFires(), "the date pattern must be able to fire, or this sweep proves nothing")
    assertEquals(emptyList(), PayloadDateGuard.datesIn(payload), "a refusal names no date: $payload")
  }

  /**
   * [served] IS [expected], row for row and field for field.
   *
   * The key SET is asserted too, so "nothing added and nothing dropped" is a
   * claim about the whole row rather than about the fields this helper happens
   * to name.
   */
  private fun assertRowsAreTheServicesOwn(
    expected: List<FafsaDeadline>,
    served: JsonArray,
    label: String,
  ) {
    assertTrue(expected.isNotEmpty(), "[$label] must have rows to compare, or this case proves nothing")
    assertEquals(expected.size, served.size, "[$label]: the coach served a different number of rows than the service returned")
    expected.zip(served).forEach { (deadline, element) ->
      val json = element.jsonObject
      assertEquals(ROW_KEYS, json.keys, "[$label]: the row the coach serves is the service's row, no key added and none dropped")
      assertEquals(deadline.awardYear.label, json.getValue(AWARD_YEAR_KEY).jsonPrimitive.content, "[$label] award year")
      assertEquals(deadline.program, json.getValue(PROGRAM_KEY).jsonPrimitive.content, "[$label] program")
      assertEquals(deadline.event.value, json.getValue(EVENT_KEY).jsonPrimitive.content, "[$label] event")
      assertEquals(FafsaDeadlineChatTool.kindCodeOf(deadline.deadline), json.getValue(KIND_KEY).jsonPrimitive.content, "[$label] kind")
      assertEquals(
        (deadline.deadline as? PublishedDeadline.OnDate)?.date?.toString(),
        stringOrNull(json.getValue(DATE_KEY)),
        "[$label]: the day is present exactly when the publisher named one",
      )
      assertEquals(
        deadline.deadline.deadlineAsPublished,
        json.getValue(AS_PUBLISHED_KEY).jsonPrimitive.content,
        "[$label]: the publisher's own words, verbatim",
      )
      assertEquals(deadline.publisher, json.getValue(PUBLISHER_KEY).jsonPrimitive.content, "[$label] publisher")
      assertEquals(deadline.sourceUrl, json.getValue(SOURCE_URL_KEY).jsonPrimitive.content, "[$label] source url")
      assertEquals(deadline.archiveUrl, stringOrNull(json.getValue(ARCHIVE_URL_KEY)), "[$label] archive url")
      assertEquals(deadline.checkedOn.toString(), json.getValue(CHECKED_ON_KEY).jsonPrimitive.content, "[$label] checked on")
    }
  }

  /** A wire value that may be an explicit null, as the Kotlin value it stands for. */
  private fun stringOrNull(element: JsonElement): String? = if (element is JsonNull) null else element.jsonPrimitive.content

  private companion object {
    /** Noon UTC on 2 October 2026 -- past the October turnover, so the cycle being filed is 2027-28 in the coach's zone. */
    const val AFTER_OCTOBER_2026 = "2026-10-02T12:00:00Z"

    /** The day the expiry case's rows stop being believed -- THE ONE HOME for that boundary; the instant below is derived from it. */
    val EXPIRY: LocalDate = LocalDate.of(2026, 10, 5)

    /** Noon UTC on the day after [EXPIRY]: the first day those rows are refused. */
    val AFTER_EXPIRY: String = "${EXPIRY.plusDays(1)}T12:00:00Z"

    /** The cycle before the one [AFTER_OCTOBER_2026] names: healthy rows that are nonetheless not this family's cycle. */
    val LAST_CYCLE: AcademicYear = AcademicYear(FafsaFilingDeadlineFixture.DEFAULT_AWARD_YEAR.firstCalendarYear - 1)

    /** The federal programme name, on the rows every family is bound by. */
    const val FEDERAL_AID = "Federal student aid"

    const val CAL_GRANT = "Cal Grant"

    /** The same state, and a programme whose publisher named a RULE and no day. */
    const val CAL_GRANT_IN_WORDS = "Cal Grant (community college entitlement)"

    const val IN_WORDS = "as soon as possible after the form opens, until funds are exhausted"

    /** The D14 pair: the FAFSA leg, and the state's own application as a row of its own. */
    const val TENNESSEE_PROMISE = "Tennessee Promise"

    const val TENNESSEE_STATE_APPLICATION = "Tennessee Promise (state application)"

    val PROMISE_FAFSA_DAY: LocalDate = LocalDate.of(2027, 4, 1)

    val PROMISE_APPLICATION_DAY: LocalDate = LocalDate.of(2026, 11, 2)

    /** The day the form opens for the cycle this suite's rows belong to. */
    val FORM_OPENS_DAY: LocalDate = LocalDate.of(2026, 10, 1)

    /** A publisher that was read and publishes no deadline at all -- a verified absence, in its own words. */
    const val NONE_PUBLISHED = "no state deadline is published"

    /** Every key one deadline row carries, and exactly those. */
    val ROW_KEYS =
      setOf(
        AWARD_YEAR_KEY,
        AWARD_YEAR_STATUS_KEY,
        AWARD_YEAR_STATEMENT_KEY,
        PROGRAM_KEY,
        EVENT_KEY,
        KIND_KEY,
        DATE_KEY,
        AS_PUBLISHED_KEY,
        PUBLISHER_KEY,
        SOURCE_URL_KEY,
        ARCHIVE_URL_KEY,
        CHECKED_ON_KEY,
      )

    /** Every key the answer itself carries. */
    val LEGEND_KEYS =
      setOf(
        AS_OF_KEY,
        AS_OF_ZONE_KEY,
        FILING_YEAR_KEY,
        STATE_KEY,
        STATE_NAME_KEY,
        STATE_GAP_KEY,
        FEDERAL_DEADLINES_KEY,
        FEDERAL_EVENTS_ABSENT_KEY,
        STATE_DEADLINES_KEY,
        SOURCE_NOTE_KEY,
      )

    /** Every key a refusal carries. */
    val REFUSAL_KEYS = setOf(NO_DEADLINES_KEY, REASON_KEY, HELD_AWARD_YEARS_KEY, REFUSAL_KEY)
  }
}
