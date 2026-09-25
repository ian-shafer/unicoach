package ed.unicoach.coaching.aid

import ed.unicoach.coaching.COACH_ZONE
import ed.unicoach.coaching.COACH_ZONE_LABEL
import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.Residency
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.noArgumentToolDefinition
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.PublishedDeadline
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId

/**
 * WHY this family's own state's rows are not in the answer: a statement about
 * OUR data, or a decision the family made, and NEVER a claim about a state -- a
 * state that publishes no deadline is a ROW of kind
 * [FafsaDeadlineChatTool.KIND_NONE_PUBLISHED] with the citation that proves we
 * checked.
 *
 * AN ENUM AND NOT FIVE LOOSE CONSTS, for the reason
 * [FafsaDeadlineChatTool.NO_DEADLINES_REASONS] already gives about the refusal
 * codes: interpolating consts into the legend survives a RENAME but not an
 * ADDITION. [value] is the wire code and [legend] is what the model is told it
 * means, so a sixth gap is ONE entry here -- it cannot reach a family with the
 * legend silently short, and it cannot be built as a bare string
 * ([FafsaProcessCopy.FafsaProcessTopic]'s shape, one file along).
 *
 * No `fromValue`: this vocabulary is EMITTED and never parsed back, so the
 * stored-enumeration convention's reader half would buy nothing here.
 */
enum class StateGap(
  /** The code the wire carries. */
  val value: String,
  /** What the legend tells the model this gap means, owned beside the code it explains. */
  val legend: String,
) {
  /** The family has not been asked which state they live in. */
  NOT_ASKED(
    "not_asked",
    "nobody has asked which state they live in -- give the federal dates, then ask, and record the " +
      "answer with ${MoneyProfileChatTool.TOOL_NAME}",
  ),

  /** They were asked and chose not to say; we do not ask again. */
  DECLINED("declined", "they chose not to say; never ask again"),

  /** Their profile could not be read. Never "they have not told us": we do not know that. */
  UNAVAILABLE(
    "unavailable",
    "their details could not be read -- never tell them their state is not on file, because we do " +
      "not know that",
  ),

  /**
   * We hold no deadline for that state that a human has verified against its
   * own agency's page (D9).
   *
   * SAID AS WHAT THE DATA OWNS. `missingJurisdictions` is a served
   * jurisdiction with no live row, and this code says exactly that and nothing
   * more. Since RFC 238 the value can ALSO carry the agency whose own page a
   * person opened and got no deadline from ([MissingJurisdiction.agency]), and
   * this door serves none of it -- the `/fafsa` page is where that citation is
   * printed, per jurisdiction, beside the gap. So this gap is still never "we
   * went to their page and were blocked", which for a jurisdiction that merely
   * lost its rows in a re-seed would be a claim about somebody else's page that
   * nothing in our data supports.
   */
  NOT_VERIFIED(
    "not_verified",
    "we hold no deadline for that state that a human has verified against its own agency's page",
  ),

  /** On file, and not one of the 50 states or DC (D13). */
  NOT_SERVED("not_served", "a jurisdiction this corpus does not cover"),
}

/**
 * The `fafsa_deadlines` chat tool (RFC 237): the coach's door onto
 * [FafsaDeadlineService], so a family asking when their FAFSA is due gets the
 * dates we actually hold for their own state, with the publisher, that
 * publisher's own page and the day a human read it -- and never a date from the
 * model's memory.
 *
 * ONE READ, RENDERED. The public `/fafsa` page will render the same
 * [FafsaDeadlineService] values (RFC 230 D4), so this class adds no read of its
 * own, re-derives nothing, and serves each row exactly as the service returned
 * it: that is what makes "the two doors cannot tell one family two different
 * stories" a property of the code rather than a promise about two renderers.
 *
 * EVERY LIVE ROW IS SERVED, AND NEVER THE EARLIEST ONE. A state that also
 * demands its own application publishes that as a SEPARATE ROW with its own
 * `program` and its own words (D14 -- Tennessee's Promise application, New
 * Jersey's NJFAMS record), so any `firstOrNull()` or "the deadline" collapse
 * here would tell a family half the work.
 *
 * EVERY DATE ON THE WIRE IS AN ISO `yyyy-mm-dd` STRING and no value is a bare
 * number at all ([NUMBERS_BY_CONTRACT] is empty): a cycle is its
 * [ed.unicoach.common.util.AcademicYear] label, and a date the model could do
 * its own arithmetic on is the one thing this tool exists to prevent.
 *
 * The zone is the COACH'S ([COACH_ZONE]), not the device's.
 * [FafsaDeadlineService.FALLBACK_ZONE] is the WEB door's answer to "the device
 * did not say"; the coach door has a real answer and passes it, and the payload
 * names it beside the date exactly as the admission-test timeline does.
 */
class FafsaDeadlineChatTool(
  service: FafsaDeadlineService,
  moneyProfiles: MoneyProfileService,
  /**
   * The deadline read, as the same SEAM [readProfile] is: a Postgres-backed test
   * cannot fault a read on demand, and this branch decides whether a database
   * fault reaches a family as an error or as a refusal claiming we hold no FAFSA
   * deadlines at all -- which would be a false claim about our corpus.
   */
  private val readDeadlines: suspend (ZoneId) -> Result<FafsaDeadlines> = { zone -> service.get(zone) },
  /**
   * The profile read, as the SEAM [ed.unicoach.coaching.ResidencyScopedChatTool]
   * already uses it: the one branch a Postgres-backed test cannot ask for on
   * demand is a read that FAULTS, and that branch decides whether a database
   * fault turns a working answer into an error or serves the federal dates with
   * a labelled gap.
   */
  private val readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult> = moneyProfiles::getForStudent,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject = noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)

  /**
   * IT TAKES NO ARGUMENT, and a `state` is the key that matters: the family's
   * state comes from `money_profiles` and must not be something the model can
   * contradict ([ed.unicoach.coaching.ResidencyScopedChatTool]'s standing rule).
   * There are exactly two ways a state gets into unicoach -- the family tells
   * the coach and the coach RECORDS it, or the family edits their own details --
   * and a state held only in a tool argument is a fact unicoach never learns.
   */
  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }

    val deadlines =
      readDeadlines(COACH_ZONE)
        .getOrElse { e ->
          logger.warn("tool [{}] {} for student=[{}]", TOOL_NAME, READ_FAILED_REASON, studentId.value, e)
          return errorObject(READ_FAILED_REASON)
        }

    return when (deadlines) {
      // The residency is read on the ANSWERING arm only. A refusal carries no
      // state at all, so a profile read on that path would buy a fact the
      // payload has nowhere to put.
      is FafsaDeadlines.NoDeadlines -> refusalObject(deadlines)

      is FafsaDeadlines.Resolved -> deadlinesObject(deadlines, Residency.forStudent(studentId, TOOL_NAME, readProfile))
    }
  }

  /**
   * The refusal: which of the three reasons it is, the cycles we hold, and the
   * sentence that says it.
   *
   * IT CARRIES NO DATE AT ALL -- not an expiry, not even today. The service
   * already withholds them (RFC 230: "a refusal exists to withhold dates"), and
   * this door keeps that: a refusal to name dates with a date in it invites the
   * model to compute from the one it was given, and the coach is told today's
   * date in its system prompt anyway. `held_award_years` stays, because a cycle
   * label is the publisher's name for what we hold and is not a day anybody can
   * act on.
   */
  private fun refusalObject(refused: FafsaDeadlines.NoDeadlines): JsonObject =
    buildJsonObject {
      put(NO_DEADLINES_KEY, true)
      put(REASON_KEY, reasonCodeOf(refused.reason))
      putJsonArray(HELD_AWARD_YEARS_KEY) { refused.heldAwardYears.forEach { add(JsonPrimitive(it.label)) } }
      put(REFUSAL_KEY, refusalFor(refused.reason))
    }

  /**
   * The answer: today, the cycle being filed, the federal rows every family is
   * bound by, this family's own state's rows where we hold them, and -- where we
   * do not -- which kind of gap that is.
   *
   * THE FEDERAL ROWS ARE SERVED BEFORE ANYTHING IS ASKED FOR. They bind every
   * family, so no arm of [stateAnswerOf] withholds them and no arm fails the tool:
   * the state half is guided, never gated.
   */
  private fun deadlinesObject(
    resolved: FafsaDeadlines.Resolved,
    residency: Residency,
  ): JsonObject {
    val stateBlock = stateBlockOf(stateAnswerOf(resolved, residency))
    val federalEventsAbsent = federalEventsAbsentFrom(resolved.federal)
    return buildJsonObject {
      put(AS_OF_KEY, resolved.asOf.toString())
      // The zone rides beside the date, from the one constant that owns it: a
      // date with no zone beside it is a date in whatever zone the reader assumes.
      put(AS_OF_ZONE_KEY, COACH_ZONE_LABEL)
      put(FILING_YEAR_KEY, resolved.filingYear.label)
      // Explicit nulls, never omitted keys: an absent key reads as a field the
      // tool forgot, and every one of these three is an answer.
      //
      // The CODE rides from the residency itself, not from the block below: a
      // state on file with a gap beside it is still a state we hold, and the
      // code identifies it either way.
      put(STATE_KEY, residency.familyResidencyState?.let(::JsonPrimitive) ?: JsonNull)
      // The state's own NAME is the service's, attached to a SERVED state and
      // nowhere else: naming a state we hold no rows for would need a second
      // read of the codebook, and a second read is a second answer waiting to
      // disagree with this one.
      //
      // The name, the gap and the rows are read off the ONE [StateBlock] above,
      // so a name without rows, or a gap beside rows, is not a payload this file
      // can build.
      put(STATE_NAME_KEY, stateBlock.name?.let(::JsonPrimitive) ?: JsonNull)
      put(STATE_GAP_KEY, stateBlock.gap?.value?.let(::JsonPrimitive) ?: JsonNull)
      putJsonArray(FEDERAL_DEADLINES_KEY) { resolved.federal.forEach { add(deadlineObject(it, resolved.filingYear)) } }
      // The federal absence, NAMED. The array is short whenever we hold no live
      // row for one of the events a family actually asks about, and a silently
      // short array is the one thing this tool cannot afford: "when is the FAFSA
      // due" answered by omission reads as an answer.
      putJsonArray(FEDERAL_EVENTS_ABSENT_KEY) { federalEventsAbsent.forEach { add(JsonPrimitive(it.value)) } }
      putJsonArray(STATE_DEADLINES_KEY) { stateBlock.deadlines.forEach { add(deadlineObject(it, resolved.filingYear)) } }
      put(SOURCE_NOTE_KEY, SOURCE_NOTE)
    }
  }

  /**
   * One row of the service's answer, field for field: its own cycle, the program
   * as its publisher names it, what kind of date it is, the day where there is
   * one, the publisher's own words for it, and the citation that proves a human
   * read it.
   *
   * `as_published` RIDES ON EVERY ROW, including a dated one: a `DATE` cannot
   * hold "11:59 p.m. Pacific", which is what the state actually enforces, and a
   * family shown only our parse would attribute OUR reading to the publisher.
   * The day is present ONLY on the arm that has one, so [KIND_KEY] is what the
   * model branches on and [DATE_KEY] is never a silent null.
   */
  private fun deadlineObject(
    deadline: FafsaDeadline,
    filingYear: AcademicYear,
  ): JsonObject =
    buildJsonObject {
      put(AWARD_YEAR_KEY, deadline.awardYear.label)
      // The cycle is not left as two labels for the model to compare: the live
      // set is the newest cycle PER JURISDICTION, so a row from another cycle is
      // the normal answer rather than the edge, and it rides with the code and
      // the sentence that say so (the [FederalAidPolicyChatTool] convention).
      // The statement is an explicit null on a current row rather than an
      // omitted key, because every other key on this row is.
      put(AWARD_YEAR_STATUS_KEY, if (deadline.awardYear == filingYear) CYCLE_CURRENT else CYCLE_OTHER_CYCLE)
      put(
        AWARD_YEAR_STATEMENT_KEY,
        if (deadline.awardYear == filingYear) JsonNull else JsonPrimitive(otherCycleStatement(deadline.awardYear, filingYear)),
      )
      put(PROGRAM_KEY, deadline.program)
      put(EVENT_KEY, deadline.event.value)
      put(KIND_KEY, kindCodeOf(deadline.deadline))
      put(DATE_KEY, dayOf(deadline.deadline)?.toString()?.let(::JsonPrimitive) ?: JsonNull)
      put(AS_PUBLISHED_KEY, deadline.deadline.deadlineAsPublished)
      // The citation is PER ROW and not per answer: fifty agencies publish fifty
      // pages, and a family checking one date needs the page that date came from.
      put(PUBLISHER_KEY, deadline.publisher)
      put(SOURCE_URL_KEY, deadline.sourceUrl)
      // AN ARCHIVED ADDRESS WE RECORDED AS A GRAVE IS NOT OFFERED, and the
      // question is ASKED and never re-spelled: the one home for "may this
      // address still be pointed at" is [SourceUrlStatus.rendersAsAnchor], so a
      // status added later is answered there rather than inheriting "yes" from a
      // `!= NOT_FOUND` written here. Handing the model a url we know answers 404
      // is the same harm as an anchor onto one -- the coach reads it aloud -- and
      // the key is already an explicit null for a row that was read through no
      // archive at all, so a withheld address needs no new vocabulary.
      put(
        ARCHIVE_URL_KEY,
        deadline.archive
          ?.takeIf { it.urlStatus.rendersAsAnchor }
          ?.url
          ?.let(::JsonPrimitive) ?: JsonNull,
      )
      put(CHECKED_ON_KEY, deadline.checkedOn.toString())
    }

  /**
   * What this family is told about their OWN state: the rows we hold for it, or
   * WHICH gap stands in their place. Never both and never neither.
   *
   * ONE VALUE, ONE DECISION. `state_name`, `state_gap` and `state_deadlines` are
   * three faces of the same answer, so a renderer that worked each of them out
   * for itself could name a gap beside the rows that fill it.
   */
  private sealed interface StateAnswer {
    /** That state's rows were served, and the name the service already read for it. */
    data class Served(
      val state: StateDeadlines,
    ) : StateAnswer

    /**
     * No rows, and WHY -- the gap as the typed vocabulary that owns its own wire
     * code, so a gap this file can build is a gap the legend explains.
     */
    data class Gap(
      val gap: StateGap,
    ) : StateAnswer
  }

  /**
   * The state half of the answer as the payload writes it: that state's name,
   * the gap standing in its rows' place, and the rows themselves.
   *
   * Resolved from [StateAnswer] in ONE exhaustive `when`, and never by a safe
   * cast at each key: a cast that misses folds into a null, and
   * `state_gap: null` beside an empty `state_deadlines` is the one payload the
   * legend cannot describe -- it tells the model that state's rows WERE served.
   * The three keys are not adjacent on the wire, so the builder reads them off
   * this one value rather than writing them together.
   */
  private data class StateBlock(
    val name: String?,
    val gap: StateGap?,
    val deadlines: List<FafsaDeadline>,
  )

  private fun stateBlockOf(answer: StateAnswer): StateBlock =
    when (answer) {
      is StateAnswer.Served -> StateBlock(answer.state.name, null, answer.state.deadlines)
      is StateAnswer.Gap -> StateBlock(null, answer.gap, emptyList())
    }

  /**
   * The federal events we hold NO live row for, out of the ones a federal
   * publisher issues at all.
   *
   * NAMED rather than derived inside the array it fills: "when is the FAFSA
   * due" answered by a silently short array reads as an answer, so which events
   * are missing is a decision with a name of its own -- the shape
   * [FafsaDeadlineService] already gives the jurisdiction half as
   * `missingJurisdictions`.
   */
  private fun federalEventsAbsentFrom(federal: List<FafsaDeadline>): List<AidDeadlineEvent> {
    val held = federal.mapTo(mutableSetOf()) { it.event }
    return FEDERAL_EVENTS.filter { it !in held }
  }

  /**
   * WHETHER this family's state was served, and when it was not, WHY.
   *
   * A ROUTER and nothing else: exhaustive over [Residency] with no `else`, so a
   * fourth residency arm has to decide what a family is told rather than
   * defaulting into one of these, and each branch is a NAME you open for its own
   * detail rather than a lookup inlined in a routing table.
   */
  private fun stateAnswerOf(
    resolved: FafsaDeadlines.Resolved,
    residency: Residency,
  ): StateAnswer =
    when (residency) {
      // No profile at all, or the question never put: the one arm the ask
      // belongs in, and the prompt is where the asking is stated.
      Residency.NotAsked -> StateAnswer.Gap(StateGap.NOT_ASKED)

      is Residency.Unavailable -> gapForUnusableResidency(residency.cause)

      is Residency.OnFile -> stateAnswerForStateOnFile(resolved, residency.familyResidencyState)
    }

  /**
   * A decision the family made, or a fault of ours -- NAMED apart, because "they
   * chose not to say" and "we could not read it" are different facts and only
   * one of them is theirs.
   */
  private fun gapForUnusableResidency(cause: Residency.Cause): StateAnswer.Gap =
    when (cause) {
      Residency.Cause.DECLINED -> StateAnswer.Gap(StateGap.DECLINED)
      Residency.Cause.READ_FAILED, Residency.Cause.CORRUPT_PROFILE -> StateAnswer.Gap(StateGap.UNAVAILABLE)
    }

  /**
   * A state we hold on file: its own rows where this read served them, and
   * otherwise WHICH gap stands in their place.
   *
   * The `not_verified` list is the service's own
   * [FafsaDeadlines.Resolved.missingJurisdictions] and is never re-derived here
   * (D9); what this door says about it is that we hold no verified deadline, and
   * never the agency that value may name ([StateGap.NOT_VERIFIED]), so it is
   * never "we went to their page and were blocked". [StateGap.NOT_SERVED] is a claim about the FAMILY's
   * jurisdiction rather than about our data, so it is the one arm that has to be
   * TESTED before it is said.
   */
  private fun stateAnswerForStateOnFile(
    resolved: FafsaDeadlines.Resolved,
    familyResidencyState: String,
  ): StateAnswer {
    resolved.states
      .firstOrNull { it.jurisdiction.uspsCode == familyResidencyState }
      ?.let { return StateAnswer.Served(it) }
    // WHAT THIS READ COVERS AT ALL (D13): the jurisdictions it served, plus the
    // served jurisdictions it holds nothing for. Both halves are the service's
    // own answer, and neither is a second list of ours waiting to disagree with
    // the codebook.
    val coveredJurisdictions =
      resolved.states.mapTo(mutableSetOf()) { it.jurisdiction.uspsCode } +
        resolved.missingJurisdictions.map { it.jurisdiction.uspsCode }
    return when {
      // Covered, and we hold no live row for it (D9): we hold no deadline for
      // that state that a human has verified. It is NOT a claim that anybody
      // went to that agency's page and was blocked; nothing in the data records
      // an attempt.
      familyResidencyState in coveredJurisdictions -> {
        StateAnswer.Gap(StateGap.NOT_VERIFIED)
      }

      // D13, TESTED and not inferred. The read names every served jurisdiction
      // exactly once across the two halves of its answer, so a code it names
      // nowhere is outside the corpus -- but ONLY if the read was COMPLETE,
      // which is the one thing an `else` cannot know. Reached by elimination,
      // this arm is also what a Californian would hear the day `us_states` lost
      // a row: that their state is not one of the fifty.
      coveredJurisdictions.size == SERVED_JURISDICTIONS -> {
        StateAnswer.Gap(StateGap.NOT_SERVED)
      }

      // The read did not account for the 50 states and DC, so we cannot say
      // where this family's jurisdiction stands. That is OUR gap and never a
      // claim about them, and it is logged with both counts because nothing else
      // records that a family was served a degraded answer.
      else -> {
        logger.error(
          "tool [{}]: residency [{}] is named neither among the [{}] served states nor the [{}] gaps, so " +
            "this read did not account for the 50 states and DC (D13) -- serving the federal rows with " +
            "gap [{}] rather than a claim about that jurisdiction",
          TOOL_NAME,
          familyResidencyState,
          resolved.states.size,
          resolved.missingJurisdictions.size,
          StateGap.NOT_VERIFIED.value,
        )
        StateAnswer.Gap(StateGap.NOT_VERIFIED)
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(FafsaDeadlineChatTool::class.java)

    const val TOOL_NAME = "fafsa_deadlines"

    // Wire keys as consts because the DESCRIPTION legend quotes them and the
    // test asserts through them: the legend the model reads, the payload it
    // receives and the suite that pins them cannot drift apart.
    const val AS_OF_KEY = "as_of"
    const val AS_OF_ZONE_KEY = "as_of_zone"
    const val FILING_YEAR_KEY = "filing_year"
    const val STATE_KEY = "state"
    const val STATE_NAME_KEY = "state_name"
    const val STATE_GAP_KEY = "state_gap"
    const val FEDERAL_DEADLINES_KEY = "federal_deadlines"
    const val FEDERAL_EVENTS_ABSENT_KEY = "federal_events_absent"
    const val STATE_DEADLINES_KEY = "state_deadlines"
    const val SOURCE_NOTE_KEY = "source_note"

    // One deadline row.
    const val AWARD_YEAR_KEY = "award_year"
    const val AWARD_YEAR_STATUS_KEY = "award_year_status"
    const val AWARD_YEAR_STATEMENT_KEY = "award_year_statement"
    const val PROGRAM_KEY = "program"
    const val EVENT_KEY = "event"
    const val KIND_KEY = "kind"
    const val DATE_KEY = "date"
    const val AS_PUBLISHED_KEY = "as_published"
    const val PUBLISHER_KEY = "publisher"
    const val SOURCE_URL_KEY = "source_url"
    const val ARCHIVE_URL_KEY = "archive_url"
    const val CHECKED_ON_KEY = "checked_on"

    // The refusal.
    const val NO_DEADLINES_KEY = "no_deadlines"
    const val REASON_KEY = "reason"
    const val HELD_AWARD_YEARS_KEY = "held_award_years"
    const val REFUSAL_KEY = "refusal"

    /**
     * EMPTY, and that is the contract: this tool emits no bare number anywhere.
     * Every date is an ISO string and every cycle is its published label, so the
     * RFC 143 guard has to be argued with before a numeric key can ship -- and a
     * date arriving as a number is the exact defect it is for.
     */
    val NUMBERS_BY_CONTRACT: Set<String> = emptySet()

    /**
     * What kind of thing the publisher published, as [KIND_KEY] says it.
     *
     * DERIVED FROM THE TYPED ARM in one exhaustive `when`, so a fourth
     * [PublishedDeadline] arm fails this file's compile instead of reaching a
     * family under one of these three names. The wire words are the arm's own
     * and not the stored `deadline_kind` code, for one reason: `"kind": "date"`
     * beside a `"date"` key reads as the day itself, and `"none"` reads as "we
     * hold nothing", which is the ONE thing this value must never be confused
     * with -- [KIND_NONE_PUBLISHED] is a verified absence WITH a citation.
     */
    fun kindCodeOf(deadline: PublishedDeadline): String =
      when (deadline) {
        is PublishedDeadline.OnDate -> KIND_ON_DATE
        is PublishedDeadline.InWords -> KIND_IN_WORDS
        is PublishedDeadline.NonePublished -> KIND_NONE_PUBLISHED
      }

    /**
     * The published DAY, or null where the publisher named none.
     *
     * DERIVED IN THE SAME EXHAUSTIVE `when` SHAPE as [kindCodeOf], and not
     * through a safe cast: a fourth [PublishedDeadline] arm carrying a day would
     * pass a cast and serve `"date": null` beside its new kind code, which is
     * precisely the silent answer [kindCodeOf]'s own claim promises it cannot.
     */
    fun dayOf(deadline: PublishedDeadline): LocalDate? =
      when (deadline) {
        is PublishedDeadline.OnDate -> deadline.date
        is PublishedDeadline.InWords -> null
        is PublishedDeadline.NonePublished -> null
      }

    /** The publisher names a day, and [DATE_KEY] carries it. */
    const val KIND_ON_DATE = "on_date"

    /** The publisher names a RULE and no day: the words are the fact, and [DATE_KEY] is null. */
    const val KIND_IN_WORDS = "in_words"

    /** We read the page and it publishes no deadline for this program: a verified absence, with its citation. */
    const val KIND_NONE_PUBLISHED = "none_published"

    /**
     * The jurisdictions a FAFSA deadline read serves (D13): the 50 states and
     * the District of Columbia, and never a territory or a freely-associated
     * state.
     *
     * A COUNT and not a list, because the list is the read's OWN answer --
     * [FafsaDeadlines.Resolved.states] plus
     * [FafsaDeadlines.Resolved.missingJurisdictions] -- and a second list here
     * would be a second vocabulary waiting to disagree with the codebook. What
     * this number buys is the one thing the two lists cannot say about
     * themselves: whether they are COMPLETE. Without it, [StateGap.NOT_SERVED]
     * is decided by elimination, and a `us_states` row that went missing would
     * reach a real family as "your state is not one of the fifty".
     */
    const val SERVED_JURISDICTIONS = 51

    /**
     * What a family is told when the deadline READ itself faulted -- never a
     * refusal, which would be a claim about our corpus that a database refusing
     * to answer is no evidence for.
     *
     * A const because the suite asserts through it: the sentence a family hears
     * is this value, never a second copy of it typed in a test.
     */
    const val READ_FAILED_REASON = "FAFSA deadline read failed"

    /** The stored reason as the wire says it -- exhaustive, so a fourth refusal arm cannot ship unnamed. */
    private fun reasonCodeOf(reason: NoDeadlinesReason): String =
      when (reason) {
        NoDeadlinesReason.EMPTY -> REASON_EMPTY
        NoDeadlinesReason.SUPERSEDED_CYCLE -> REASON_SUPERSEDED_CYCLE
        NoDeadlinesReason.EXPIRED -> REASON_EXPIRED
      }

    const val REASON_EMPTY = "empty"
    const val REASON_SUPERSEDED_CYCLE = "superseded_cycle"
    const val REASON_EXPIRED = "expired"

    /**
     * What the family should do INSTEAD, on all three refusals, because it is
     * the same thing in all three cases.
     *
     * IT NAMES NO AGENCY, and says why: with no rows there is no publisher to
     * name, and naming one anyway would be the invention the whole refusal
     * exists to withhold.
     */
    const val WHERE_TO_GO =
      "File at studentaid.gov, which is free, and ask your own state's student aid agency for the state " +
        "deadline -- with no rows in front of me I hold no publisher to name for you, so I will not name one."

    /**
     * The refusal, one sentence per reason, and the second sentence shared.
     *
     * THEY DIFFER IN THE FIRST CLAUSE, which is the whole point: "our deadlines
     * are out of date" is a true statement about rows we held and no longer
     * believe, a false one about a table with nothing in it, and beside the
     * point for rows that are healthy and belong to last cycle.
     */
    fun refusalFor(reason: NoDeadlinesReason): String =
      when (reason) {
        NoDeadlinesReason.EMPTY -> {
          "We do not hold FAFSA filing deadlines at all, so I will not name one from memory. $WHERE_TO_GO"
        }

        NoDeadlinesReason.SUPERSEDED_CYCLE -> {
          "Every FAFSA deadline we hold belongs to an earlier filing cycle, so it is not the one you would be " +
            "filing under, and I will not repeat last cycle's date as if it were this cycle's. $WHERE_TO_GO"
        }

        NoDeadlinesReason.EXPIRED -> {
          "Our FAFSA deadlines are out of date: too long has passed since a human last checked them against " +
            "the publisher, so I will not repeat them. $WHERE_TO_GO"
        }
      }

    /** This row's cycle IS the one a family filing today files under. */
    const val CYCLE_CURRENT = "current"

    /** It is NOT: an earlier cycle nobody has re-read, or a later one the publisher posted early. */
    const val CYCLE_OTHER_CYCLE = "other_cycle"

    /**
     * The sentence a row earns when its cycle is not the one being filed.
     *
     * NEVER SILENTLY SERVED, and never a comparison left to the model: the live
     * set is the newest cycle PER JURISDICTION, so one answer routinely carries
     * rows from two cycles, in either direction.
     */
    fun otherCycleStatement(
      awardYear: AcademicYear,
      filingYear: AcademicYear,
    ): String =
      "This date belongs to the ${awardYear.label} cycle, not the ${filingYear.label} cycle a family filing " +
        "today files under: it is the most recent one we have verified from that publisher, so say which " +
        "cycle it is and send them to that publisher for the ${filingYear.label} date."

    /**
     * Whether a FEDERAL publisher issues this kind of date at all, and therefore
     * whether our holding no live row for it is an absence a family must be told
     * about.
     *
     * AN EXHAUSTIVE `when` AND NOT A SET OF EXCLUSIONS, because
     * [AidDeadlineEvent] is `:db`'s vocabulary and a `filterNot` over somebody
     * else's enum ADMITS every future member by default: a fifth event would
     * default into the federal half and be announced on every single answer as a
     * federal date we do not hold and they should ask studentaid.gov for -- a
     * date no federal publisher issues at all. Here it fails THIS file's compile
     * instead, which is the shape [FafsaDeadlineService]'s `isServedJurisdiction`
     * already uses one layer down for the same problem.
     */
    private fun AidDeadlineEvent.isFederal(): Boolean =
      when (this) {
        AidDeadlineEvent.FORM_OPENS, AidDeadlineEvent.FINAL_DEADLINE, AidDeadlineEvent.CORRECTIONS_DEADLINE -> true

        // Being first in a queue some agency controls is a STATE instrument, so
        // the federal half holds none and its absence is not a gap.
        AidDeadlineEvent.PRIORITY_DEADLINE -> false
      }

    /**
     * The events a FEDERAL publisher issues, and therefore the ones whose
     * ABSENCE has to be stated. An ALLOWLIST, derived from the classifier above
     * and never hand-listed.
     */
    val FEDERAL_EVENTS: List<AidDeadlineEvent> = AidDeadlineEvent.entries.filter { it.isFederal() }

    /** Why every date here rides with a cycle, a publisher's page and the day we read it. */
    val SOURCE_NOTE =
      "Every row was read from the publisher's own page on the $CHECKED_ON_KEY date beside it: give the date " +
        "with the cycle its $AWARD_YEAR_KEY names and the day we checked it, speak $AS_PUBLISHED_KEY as the " +
        "publisher wrote it, and never name a FAFSA date this payload does not carry."

    /** The four event codes, quoted from the vocabulary the wire carries rather than retyped as words. */
    private val AID_DEADLINE_EVENTS: String = AidDeadlineEvent.entries.joinToString(" | ") { "\"${it.value}\"" }

    /**
     * The refusal codes, quoted from the enum the wire carries.
     *
     * DERIVED exactly as [AID_DEADLINE_EVENTS] is, because interpolating three
     * consts survives a RENAME but not an ADDITION: a fourth refusal arm would
     * fail [reasonCodeOf]'s compile and leave this sentence silently short.
     */
    private val NO_DEADLINES_REASONS: String = NoDeadlinesReason.entries.joinToString(" | ") { "\"${reasonCodeOf(it)}\"" }

    /** The federal events this payload can name as absent, quoted from the list it filters. */
    private val FEDERAL_EVENT_CODES: String = FEDERAL_EVENTS.joinToString(" | ") { "\"${it.value}\"" }

    /**
     * The gap codes and what each one means, quoted from the enum the wire
     * carries.
     *
     * DERIVED exactly as [NO_DEADLINES_REASONS] is, and for the same reason: a
     * sixth gap joins this sentence instead of reaching a family unexplained,
     * and an unexplained gap code is the site where the model supplies "that
     * state publishes no deadline" for itself.
     */
    private val STATE_GAP_CODES: String = StateGap.entries.joinToString(", ") { "\"${it.value}\" (${it.legend})" }

    /**
     * The payload legend the model is told (RFC 199): one entry per wire key,
     * what it means and what values it takes, built from the very consts the
     * payload is keyed by.
     *
     * Not `const`: the gap and kind vocabularies are rendered from the values the
     * payload actually emits, so the legend cannot quote a code the wire no
     * longer carries. The sentence about [STATE_GAP_KEY] never meaning "this
     * state publishes no deadline" is here because that is precisely the
     * sentence the model would otherwise supply for itself.
     */
    val DESCRIPTION =
      "The FAFSA filing dates we hold: when the form opens, the federal deadline, the last day to correct a " +
        "filed form, and the family's own state's deadlines. Use it whenever a family asks when the FAFSA is " +
        "due, when it opens, or how long they have to fix a form they have already filed, and never name a " +
        "FAFSA date from memory. It takes no input: the state is the family's own recorded one and is never " +
        "something you pass in. A legend of the payload's keys follows.\n" +
        "$AS_OF_KEY / $AS_OF_ZONE_KEY - the day this answer was worked out, and the zone it is that day in.\n" +
        "$FILING_YEAR_KEY - the cycle a family filing today would be filing under; it is not a claim that any " +
        "other cycle is shut.\n" +
        "$STATE_KEY / $STATE_NAME_KEY - the state this answer covers, from the family's own recorded " +
        "details. $STATE_KEY is null only where we hold no state at all; $STATE_NAME_KEY is null whenever " +
        "that state's own rows were not served, so a named $STATE_KEY with a null $STATE_NAME_KEY is a " +
        "state on file with a gap beside it, never a state we do not hold.\n" +
        "$STATE_GAP_KEY - null when the state's own rows are served, and otherwise WHICH gap it is: " +
        "$STATE_GAP_CODES. Every one of them is a gap in OUR data or a decision they " +
        "made, and NONE of them means that state publishes no deadline: say whose gap it is, and send them to " +
        "their own state's agency.\n" +
        "$FEDERAL_DEADLINES_KEY - the rows that bind every family, whatever state they live in.\n" +
        "$FEDERAL_EVENTS_ABSENT_KEY - the federal events we hold NO live row for, out of $FEDERAL_EVENT_CODES. " +
        "Empty is the normal answer; a code listed here is a date we do not hold, so say plainly that we do " +
        "not hold it, send them to studentaid.gov for it, and never supply it from memory.\n" +
        "$STATE_DEADLINES_KEY - that state's own rows, and EVERY one of them: a state that also demands its " +
        "own application publishes it as a row of its own, so read them all out rather than the earliest, or " +
        "a family does half the work.\n" +
        "$AWARD_YEAR_KEY - the cycle THIS row belongs to; say it with the date.\n" +
        "$AWARD_YEAR_STATUS_KEY - \"$CYCLE_CURRENT\" when that cycle IS the $FILING_YEAR_KEY a family filing " +
        "today files under, and \"$CYCLE_OTHER_CYCLE\" when it is not. On \"$CYCLE_OTHER_CYCLE\" the row " +
        "also carries $AWARD_YEAR_STATEMENT_KEY, the sentence to say: never give that date as their deadline " +
        "without it.\n" +
        "$AWARD_YEAR_STATEMENT_KEY - what to say about a row from another cycle, and null on a " +
        "\"$CYCLE_CURRENT\" row, which needs no such sentence.\n" +
        "$PROGRAM_KEY - the programme as its own publisher names it.\n" +
        "$EVENT_KEY - what kind of date it is: $AID_DEADLINE_EVENTS. A priority date is the end of being " +
        "first and not the end of filing, so never say one closes filing.\n" +
        "$KIND_KEY - what the publisher published: \"$KIND_ON_DATE\" (a day, in $DATE_KEY), \"$KIND_IN_WORDS\" " +
        "(a rule and no day -- speak $AS_PUBLISHED_KEY and turn it into no date of your own) or " +
        "\"$KIND_NONE_PUBLISHED\" (we read the page and it publishes no deadline for that programme, which is " +
        "a checked answer and not a gap).\n" +
        "$DATE_KEY - the published day, and null unless $KIND_KEY is \"$KIND_ON_DATE\".\n" +
        "$AS_PUBLISHED_KEY - the deadline in the publisher's own words, on every row, including the hour and " +
        "the time zone a date cannot hold.\n" +
        "$PUBLISHER_KEY / $SOURCE_URL_KEY / $ARCHIVE_URL_KEY - who published this row and the page it was read " +
        "from, per row; $ARCHIVE_URL_KEY is the archived address where that agency's page opens for no client " +
        "we have, and is null where none was needed or where that archived address is itself no longer " +
        "served.\n" +
        "$CHECKED_ON_KEY - the day WE read that page.\n" +
        "$NO_DEADLINES_KEY - present and true when there is no live answer at all. Then $REASON_KEY is one of " +
        "$NO_DEADLINES_REASONS, $HELD_AWARD_YEARS_KEY names the cycles we hold, $REFUSAL_KEY is what to say, " +
        "and the payload carries no date at all: say what the refusal says and name no date of your own.\n" +
        "$SOURCE_NOTE_KEY - why every date here rides with a cycle, a page and a date of reading.\n" +
        "Read-only: this tool changes nothing."
  }
}
