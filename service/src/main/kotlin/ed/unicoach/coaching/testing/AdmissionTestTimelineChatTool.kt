package ed.unicoach.coaching.testing

import ed.unicoach.coaching.COACH_ZONE_LABEL
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.putCitation
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.SchoolDayTestChoice
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * The `admission_test_timeline` chat tool (RFC 197): the one read that answers
 * "when do I need to take the SATs" with a date the student can act on, worked
 * back from the earliest real deadline on their own college list.
 *
 * A thin adapter, the house shape: [execute] parses, reads, and renders. Every
 * RULE -- which sitting is usable, which year a school's month-and-day resolves
 * to, what happens with no live calendar -- lives in [TestTimelineService], and
 * every WORD the coach reads is authored here or in [AdmissionTestCopy]. That
 * split is the RFC 197 review's, and it is the point: the service returns typed
 * verdicts and this class turns each of them into exactly one sentence, so a
 * sentence can never disagree with the verdict it belongs to.
 *
 * This class does no arithmetic and holds no date of its own.
 *
 * EVERY DATE ON THE WIRE IS AN ISO `yyyy-mm-dd` STRING, and no value on the wire
 * is a bare number at all ([NUMBERS_BY_CONTRACT] is empty). That is deliberate
 * rather than incidental: a date rendered as a number would be a month and a day
 * the model could do its own arithmetic on, and the whole point of this tool is
 * that the arithmetic is ours and is shown.
 */
class AdmissionTestTimelineChatTool(
  private val service: TestTimelineService,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") { putCollegeIdsSchema() }
    }

  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val collegeIds =
      when (val parsed = readCollegeIds(input)) {
        is CollegeIdsInput.Ok -> parsed.collegeIds
        is CollegeIdsInput.Invalid -> return errorObject(parsed.reason)
      }

    val timeline =
      service
        .getForStudent(studentId, collegeIds)
        .getOrElse { e ->
          // The subset is the only caller-supplied state in the call, so the log
          // is where a failing read has to be reproducible from: null means the
          // whole active list, which is a different call from a three-school one.
          logger.warn(
            "tool [{}] timeline read failed for student=[{}] college_ids=[{}]",
            TOOL_NAME,
            studentId.value,
            collegeIds?.map { it.value } ?: "whole active list",
            e,
          )
          return errorObject("admission test timeline read failed")
        }

    return when (timeline) {
      is TestTimeline.NoCalendar -> refusalObject(timeline)
      is TestTimeline.Resolved -> timelineObject(timeline)
    }
  }

  /**
   * With no live calendar the tool returns dates for NOTHING (D7), and that is
   * read literally here: this payload carries no date at all -- not a sitting,
   * not the calendar's own expiry, not even today.
   *
   * Today is withheld on purpose. It is a date, and a refusal to name dates with
   * a date in it invites the model to compute from the one it was given; the
   * coach is already told today's date in its system prompt, so nothing is lost.
   * `cycles` stays, because those are the publishers' own labels for what we
   * held and are not dates anybody can act on.
   */
  private fun refusalObject(timeline: TestTimeline.NoCalendar): JsonObject =
    buildJsonObject {
      putJsonObject("calendar") {
        putJsonArray("cycles") { timeline.cycles.forEach { add(JsonPrimitive(it)) } }
        // TRUE only when it IS true. An empty table is not an out-of-date
        // calendar, and the sentence in `refusal` says which of the two happened.
        put("expired", timeline.reason == NoCalendarReason.EXPIRED)
      }
      putJsonArray("sittings") {}
      put("refusal", refusalFor(timeline.reason))
    }

  /** The full answer: when it is, what school deadline it is worked back from, and every sitting that is still ahead. */
  private fun timelineObject(timeline: TestTimeline.Resolved): JsonObject =
    buildJsonObject {
      put("as_of", timeline.asOf.toString())
      // The zone rides beside the date, from the one constant that owns it: a
      // date with no zone beside it is a date in whatever zone the reader
      // assumes, and the two publishers do not even agree with each other.
      put("as_of_zone", COACH_ZONE_LABEL)
      put("year_of_school", timeline.yearOfSchool.label)
      putJsonObject("calendar") {
        putJsonArray("cycles") { timeline.calendar.cycles.forEach { add(JsonPrimitive(it)) } }
        put("expires_on", timeline.calendar.expiresOn.toString())
        put("expired", false)
      }
      // An explicit null, never an omitted key: "there is no deadline to work
      // back from" is an answer, and the general sequence below is what answers
      // instead. An absent key reads as a field the tool forgot.
      put("anchor", timeline.anchor?.let(::deadlineObject) ?: JsonNull)
      // WHY there is no anchor, and WHAT has already closed. Both used to be
      // dropped on the floor -- the first by a `mapNotNull`, the second by the
      // `filter` that found the anchor -- while the general sequence asserted
      // the student had no deadline at all. Both are always present, so an empty
      // array is a statement and not a missing key.
      putJsonArray("anchor_rejections") { timeline.anchorRejections.forEach { add(rejectionObject(it)) } }
      putJsonArray("closed_deadlines") { timeline.closedDeadlines.forEach { add(deadlineObject(it)) } }
      putJsonArray("sittings") { timeline.sittings.forEach { add(sittingJson(it)) } }
      putJsonArray("data_availability") { timeline.dataAvailability.forEach { add(unannouncedObject(it)) } }
      put("general_sequence", if (timeline.anchor == null) JsonPrimitive(GENERAL_SEQUENCE) else JsonNull)
      // LAST, and that is the order the RFC decided: the national calendar is
      // the picture, and a state's in-school sitting is an exception to it
      // rather than the way into the explanation.
      put("school_day_programme", schoolDayObject(timeline.schoolDayProgramme))
      if (timeline.unknownCollegeIds.isNotEmpty()) {
        putJsonArray("unknown_college_ids") {
          timeline.unknownCollegeIds.forEach { add(JsonPrimitive(it.value.toString())) }
        }
      }
    }

  /**
   * One resolved deadline -- the anchor, or one that has already closed -- with
   * BOTH dates and the basis.
   *
   * `closing_as_published` is the school's own words and carries no year, because
   * the school publishes none; `closing_resolved` is ours; `resolution_basis`
   * says in words whose year it is and how it was worked out. All three, or the
   * coach cannot tell the family which part of the date the school stands behind.
   */
  private fun deadlineObject(deadline: ResolvedDeadline): JsonObject =
    buildJsonObject {
      put("college_id", deadline.collegeId.value.toString())
      put("name", deadline.collegeName)
      put("round", deadline.round.value)
      put("round_label", deadline.round.label)
      put("closing_as_published", deadline.closingAsPublished)
      put("closing_resolved", deadline.closingResolved.toString())
      put("resolution_basis", resolutionBasisOf(deadline))
      putJsonObject("citation") { putCitation(deadline.citation) }
    }

  /** One round that could not be worked back from, named -- the school, the round, and the sentence that says why. */
  private fun rejectionObject(rejection: AnchorRejection): JsonObject =
    buildJsonObject {
      put("college_id", rejection.collegeId.value.toString())
      put("name", rejection.collegeName)
      put("round", rejection.round.value)
      put("round_label", rejection.round.label)
      put("closing_as_published", rejection.closingAsPublished?.let(::JsonPrimitive) ?: JsonNull)
      // A STRING and not a number, like every other value here: the year is ours
      // and is part of a sentence, not a quantity to compute with.
      put("resolved_year", rejection.resolvedYear?.toString()?.let(::JsonPrimitive) ?: JsonNull)
      put("why", whyOf(rejection))
    }

  /** A sitting whose score release the publisher has not announced: the test and its own date, never a guessed season. */
  private fun unannouncedObject(unannounced: UnannouncedScoreRelease): JsonObject =
    buildJsonObject {
      put("field", "score_release_date")
      put("test", unannounced.test.value)
      put("test_name", unannounced.test.displayName)
      put("test_date", unannounced.testDate.toString())
    }

  /**
   * What this student's own STATE does about the school-day SAT or ACT (RFC 208
   * section 6).
   *
   * EVERY KEY IS ALWAYS PRESENT, as an explicit `null` where there is nothing --
   * this file's rule, and it bites hardest here: three of the four arms carry
   * almost no data, and a section that shrank to two keys would read as a
   * programme the renderer half-emitted rather than as the honest answer it is.
   * [SchoolDayProgramme.why] is what says which arm this is.
   *
   * `held` IS `true` OR `null` AND NEVER `false`. False would be the claim that
   * the state runs nothing, which needs a state page saying so; an absent row,
   * a lapsed reading and an unknown state are all "we cannot say", exactly as
   * `usable_for_anchor` above is null for the two cases that are not verdicts.
   *
   * The grades are STRINGS and the school year is
   * [ed.unicoach.common.util.AcademicYear]'s own label, never a bare number:
   * [NUMBERS_BY_CONTRACT] is empty and stays empty, and `2026` alone is a year
   * the model would do its own arithmetic on.
   */
  private fun schoolDayObject(programme: SchoolDayProgramme): JsonObject =
    buildJsonObject {
      val row = (programme as? SchoolDayProgramme.Held)?.row
      val choice = row?.choice
      val chosenTest = (choice as? SchoolDayTestChoice.StateChose)?.test
      put("state", stateOf(programme)?.let(::JsonPrimitive) ?: JsonNull)
      // A plain BOOLEAN on every arm, never a null: "do we hold a current reading
      // for this state" has an answer in all four cases, and a null here would be
      // a third state the model has to guess the meaning of.
      put("held", row != null)
      // The code with no label of its own beside it, because the participation
      // WORDS ride in `why` (from `SchoolDayCopy`, the one home for them) rather
      // than as a second rendering that could disagree with the first.
      put("participation", row?.participation?.value?.let(::JsonPrimitive) ?: JsonNull)
      put("test_chooser", choice?.chooser?.value?.let(::JsonPrimitive) ?: JsonNull)
      // Both null unless the STATE chose, which is the pairing `SchoolDayTestChoice`
      // makes unrepresentable: a district-chosen row naming a test would say the
      // state chose after all.
      put("test", chosenTest?.value?.let(::JsonPrimitive) ?: JsonNull)
      put("test_name", chosenTest?.displayName?.let(::JsonPrimitive) ?: JsonNull)
      put("chooser_label", choice?.let { JsonPrimitive(SchoolDayCopy.chooserLabelOf(it.chooser)) } ?: JsonNull)
      put(
        "lowest_grade",
        row
          ?.grades
          ?.lowest
          ?.toString()
          ?.let(::JsonPrimitive) ?: JsonNull,
      )
      put(
        "highest_grade",
        row
          ?.grades
          ?.highest
          ?.toString()
          ?.let(::JsonPrimitive) ?: JsonNull,
      )
      // Null means the page does not say who pays -- the commonest case -- and
      // `why` says so in words. It never means the sitting is not paid for.
      put("state_funded", row?.stateFunded?.let(::JsonPrimitive) ?: JsonNull)
      // Null means we did not read it, and the coach says nothing either way.
      put("school_must_offer", row?.schoolMustOffer?.let(::JsonPrimitive) ?: JsonNull)
      put("school_year", row?.schoolYear?.label?.let(::JsonPrimitive) ?: JsonNull)
      put("school_year_basis", row?.schoolYearBasis?.value?.let(::JsonPrimitive) ?: JsonNull)
      put(
        "school_year_basis_label",
        row?.schoolYearBasis?.let { JsonPrimitive(SchoolDayCopy.schoolYearBasisLabelOf(it)) } ?: JsonNull,
      )
      put("source_url", row?.sourceUrl?.let(::JsonPrimitive) ?: JsonNull)
      put("archive_url", row?.archiveUrl?.let(::JsonPrimitive) ?: JsonNull)
      put("checked_on", row?.checkedOn?.toString()?.let(::JsonPrimitive) ?: JsonNull)
      // The lapsed arm keeps its own expiry, because `why` names that day: a
      // sentence carrying a date the payload does not is a date the model cannot
      // check.
      put("expires_on", expiresOnOf(programme)?.toString()?.let(::JsonPrimitive) ?: JsonNull)
      put("what_the_state_cannot_answer", SchoolDayCopy.WHAT_THE_STATE_CANNOT_ANSWER)
      put("why", whyOf(programme))
    }

  /** The state this section is about, where we know it -- the USPS code the row and the money profile agree on. */
  private fun stateOf(programme: SchoolDayProgramme): String? =
    when (programme) {
      is SchoolDayProgramme.Held -> programme.row.state
      is SchoolDayProgramme.Expired -> programme.state
      is SchoolDayProgramme.NotHeld -> programme.state
      is SchoolDayProgramme.StateUnknown -> null
    }

  /** The day this reading stops, or stopped, being believed -- present on the two arms that have one. */
  private fun expiresOnOf(programme: SchoolDayProgramme): LocalDate? =
    when (programme) {
      is SchoolDayProgramme.Held -> programme.row.expiresOn
      is SchoolDayProgramme.Expired -> programme.expiresOn
      is SchoolDayProgramme.NotHeld -> null
      is SchoolDayProgramme.StateUnknown -> null
    }

  companion object {
    private val logger = LoggerFactory.getLogger(AdmissionTestTimelineChatTool::class.java)

    const val TOOL_NAME = "admission_test_timeline"

    /**
     * EMPTY, and that is the contract: this tool emits no bare number anywhere.
     * Every date is an ISO string, every verdict is a boolean or a null, and
     * every count a reader might want is the length of an array it can see. The
     * RFC 143 guard reads this set, so a numeric key has to be admitted here
     * deliberately -- and a date arriving as a number would be the exact defect
     * the guard is for.
     */
    val NUMBERS_BY_CONTRACT: Set<String> = emptySet()

    /**
     * ONE SITTING ON THE WIRE, for every tool that puts one there.
     *
     * Beside [whyOf] and for its reason: a second renderer of this value is a
     * second set of keys the model has to be taught, and the copy that appeared
     * first (in `AutomaticAwardChatTool`) dropped `registration_deadline_time`
     * -- the key that stops one publisher's closing hour being read as the
     * other's, which the standing coach instruction "the tool names whose"
     * depends on. The test's code and its spoken name ride together, and so do
     * every deadline and the words that say what that deadline actually covers.
     *
     * `usable_for_anchor` is emitted as an explicit `null` for the two cases
     * that are not verdicts, with `why` saying which. It is NEVER omitted: an
     * absent key is a silence the model would fill -- and the same rule is why
     * the late-registration pair is always both keys, with the date null where
     * the publisher publishes none and the label standing anyway, because the
     * label is what that publisher WOULD cover and not a claim a date exists.
     */
    fun sittingJson(sitting: TestSitting): JsonObject =
      buildJsonObject {
        put("test", sitting.test.value)
        put("test_name", sitting.test.displayName)
        put("test_date", sitting.testDate.toString())
        put("registration_deadline", sitting.registrationDeadline.toString())
        put("registration_deadline_time", AdmissionTestCopy.deadlineTimeLabelOf(sitting.test))
        // The fact the answer actually turns on: a sitting whose registration has
        // closed is no longer a choice, whatever the scores would have done.
        put("registration_open", sitting.registrationOpen)
        put("late_or_changes_deadline", sitting.lateRegistrationDeadline?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("late_or_changes_deadline_label", AdmissionTestCopy.lateDeadlineLabelOf(sitting.test))
        put("fee_waiver_by", sitting.feeWaiverBy.toString())
        // Flagged at the same construct that emits it, so no renderer can put the
        // date on the wire without the statement that it is ours.
        put("fee_waiver_by_is_derived", true)
        put(
          "score_release_date",
          sitting.scoreRelease
            ?.date
            ?.toString()
            ?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("usable_for_anchor", sitting.usableForAnchor?.let(::JsonPrimitive) ?: JsonNull)
        put("why", whyOf(sitting.verdict))
      }

    /**
     * The sentence that justifies one sitting's verdict, built HERE from the
     * verdict itself -- exhaustive, with no `else`.
     *
     * One function of one value, which is what makes the safety property
     * checkable: a [SittingVerdict.RegistrationClosed] sentence names the
     * registration deadline and today and NOTHING ELSE, so the coach can never
     * read a registration fact as a guess about scores. The two used to be
     * parallel `when` chains in the service, and the last sentence arm read the
     * BOOLEAN the first chain produced.
     */
    fun whyOf(verdict: SittingVerdict): String =
      when (verdict) {
        is SittingVerdict.NoAnchor -> {
          "there is no resolved deadline on the student's list to work back from, so this sitting is " +
            "neither usable nor unusable"
        }

        is SittingVerdict.RegistrationClosed -> {
          "registration closed on ${UsDate.of(verdict.deadline)}, which is before ${UsDate.of(verdict.today)}"
        }

        is SittingVerdict.ScoreReleaseUnannounced -> {
          "${AdmissionTestCopy.publisherNameOf(verdict.test)} has not announced a score-release date for the " +
            "${UsDate.of(verdict.testDate)} sitting, so whether those scores would reach a deadline in time is not " +
            "ours to say"
        }

        is SittingVerdict.ScoresCompared -> {
          if (verdict.release.isAfter(verdict.anchor)) {
            "scores are released ${UsDate.of(verdict.release)}, after the ${UsDate.of(verdict.anchor)} deadline"
          } else {
            "scores are released ${UsDate.of(verdict.release)}, on or before the ${UsDate.of(verdict.anchor)} deadline"
          }
        }
      }

    /**
     * Why one round could not be worked back from -- exhaustive, with no `else`,
     * so a fifth rejection reason cannot reach a family as a silent omission.
     *
     * Each sentence states what the SCHOOL actually did, and none of them claims
     * the student has no deadline: "the school published January and no day" and
     * "the school does not run this round" are different facts, and the general
     * sequence used to assert neither of them while implying both.
     */
    fun whyOf(rejection: AnchorRejection): String =
      when (rejection.reason) {
        AnchorRejectionReason.ROUND_NOT_OFFERED -> {
          "${rejection.collegeName} states it does not offer ${rejection.round.label}, so there is no deadline " +
            "under it to work back from"
        }

        AnchorRejectionReason.NO_CLOSING_MONTH -> {
          "${rejection.collegeName} reported no closing date for ${rejection.round.label}, so there is nothing " +
            "to work back from"
        }

        AnchorRejectionReason.MONTH_WITHOUT_DAY -> {
          "${rejection.collegeName} published [${rejection.closingAsPublished}] for ${rejection.round.label} " +
            "and no day, and a month with no day cannot be worked back from -- no day is guessed for it"
        }

        AnchorRejectionReason.DAY_NOT_IN_RESOLVED_YEAR -> {
          "${rejection.collegeName} published [${rejection.closingAsPublished}] for ${rejection.round.label}, " +
            "and that day does not exist in ${rejection.resolvedYear}, the year of this student's application " +
            "cycle; confirm the date on the school's own page"
        }
      }

    /**
     * Why the school-day section says what it says -- built from the typed
     * [SchoolDayProgramme] in one `when` with no `else`, so a fifth arm cannot
     * ship without deciding what a family is told about it.
     *
     * IT NAMES NO SCHOOL AND SAYS "YOUR SCHOOL" NOWHERE. Every clause is a fact
     * about a STATE, read from that state's own page; whether one named high
     * school runs its test day is not in this corpus and cannot be inferred from
     * it. [SchoolDayCopy.schoolMustOfferClauseOf] is the closest any of these
     * clauses gets, and it is still a sentence about what the state requires of
     * every school.
     *
     * THE THREE NON-HELD ARMS STATE WHOSE GAP IT IS. "We hold no reading" is a
     * fact about our data; "the state runs nothing" is a claim about a state,
     * needs a page saying so, and is said nowhere -- it is the trust-losing
     * answer this section exists to prevent, pointing the other way.
     */
    fun whyOf(programme: SchoolDayProgramme): String =
      when (programme) {
        is SchoolDayProgramme.Held -> {
          val row = programme.row
          val clauses =
            listOfNotNull(
              SchoolDayCopy.participationClauseOf(row.participation),
              SchoolDayCopy.gradesClauseOf(row.grades),
              SchoolDayCopy.chooserLabelOf(row.choice.chooser),
              SchoolDayCopy.fundingClauseOf(row.stateFunded),
              // Absent for the states we did not read it for, which is most of
              // them: a null is UNKNOWN, and a sentence for it would be the
              // invention the column's own comment forbids.
              SchoolDayCopy.schoolMustOfferClauseOf(row.schoolMustOffer),
              SchoolDayCopy.schoolYearBasisLabelOf(row.schoolYearBasis),
            )
          "we hold a current reading of this state's own page, read on ${UsDate.of(row.checkedOn)}: " +
            clauses.joinToString("; ")
        }

        is SchoolDayProgramme.Expired -> {
          "our reading of this state's page lapsed on ${UsDate.of(programme.expiresOn)}, so we hold no current " +
            "record for " +
            "this state -- what it says now has to come from the state's own page, and this is a gap in our " +
            "data rather than a change we know about"
        }

        is SchoolDayProgramme.NotHeld -> {
          "we hold no reading of a school-day admission-test programme for this state, which is a gap in our " +
            "data and never a statement that the state runs none"
        }

        is SchoolDayProgramme.StateUnknown -> {
          "${residencyGapOf(programme.reason)}, so we cannot say what their state does; the state is not asked " +
            "for here, because the residency question belongs to the cost conversation and nothing in this " +
            "answer waits on it"
        }
      }

    /**
     * WHICH absence of a state this is -- three sentences, because a family that
     * declined the question is owed a different one from a family that was never
     * asked (D10). Exhaustive with no `else`.
     */
    private fun residencyGapOf(gap: ResidencyGap): String =
      when (gap) {
        ResidencyGap.NO_PROFILE -> "we hold no money profile for this student, so we do not know which state they live in"
        ResidencyGap.UNANSWERED -> "the residency question has not been answered, so we do not know which state they live in"
        ResidencyGap.DECLINED -> "this student chose not to say which state they live in"
      }

    /**
     * Whose year the resolved date carries, in words the coach can say aloud.
     *
     * Built here and not in the service, because it is a sentence: the service
     * hands over the school's own phrase and the student's graduation year, and
     * this is the one place that turns the pair into a claim about whose
     * arithmetic it is.
     */
    fun resolutionBasisOf(deadline: ResolvedDeadline): String =
      "the school publishes a month and a day and no year; this is the ${deadline.closingAsPublished} of the " +
        "application cycle for a student graduating in ${deadline.graduationYear}"

    /**
     * The refusal, one sentence per reason (D7).
     *
     * BOTH name BOTH publishers, because the one thing we still know is where the
     * current dates live, and naming only one would make that test the default --
     * which it never is (D13). They differ in the FIRST clause, which is the
     * point: "our calendar is out of date" is a true statement about a calendar
     * we held and no longer believe, and a false one about a table with nothing
     * in it. The second case used to say the first sentence.
     */
    fun refusalFor(reason: NoCalendarReason): String =
      when (reason) {
        NoCalendarReason.EXPIRED -> {
          "Our SAT and ACT calendar is out of date, so we cannot tell you any test date, registration deadline " +
            "or score-release date: $CHECK_THE_PUBLISHERS"
        }

        NoCalendarReason.EMPTY -> {
          "We do not hold an SAT or ACT calendar at all, so we cannot tell you any test date, registration " +
            "deadline or score-release date: $CHECK_THE_PUBLISHERS"
        }
      }

    /** Where a family goes instead, both publishers named, from the one place their names and pages live. */
    private val CHECK_THE_PUBLISHERS: String =
      "check ${AdmissionTestCopy.publisherNameOf(AdmissionTest.SAT)} at " +
        "${AdmissionTestCopy.registrationUrlOf(AdmissionTest.SAT)} and " +
        "${AdmissionTestCopy.publisherNameOf(AdmissionTest.ACT)} at " +
        "${AdmissionTestCopy.registrationUrlOf(AdmissionTest.ACT)} for the current dates."

    /**
     * The sequence for a student with no deadline to work back from, labelled
     * general in its own words so the coach cannot present it as advice about
     * their schools. It names no date, because a general sequence has none --
     * the real dates still come from the calendar, which is national.
     *
     * ITS LAST CLAUSE USED TO BE A CLAIM IT COULD NOT MAKE: "there is none on
     * this student's list to work back from" is false for a student whose school
     * published "January" with no day, or whose every deadline has already
     * closed. It now points at the two arrays that say what is actually true,
     * and asserts nothing itself.
     */
    val GENERAL_SEQUENCE: String =
      "This is the general sequence and not advice about any school: most students sit a first real test by the " +
        "spring of junior year, so there is room for one retake in the autumn of senior year before applications " +
        "close. Which sitting is right depends on a deadline, and none on this student's list resolves to one " +
        "right now -- read anchor_rejections and closed_deadlines for why before saying they have none."

    /**
     * The model-facing copy. Every sentence names a field this tool actually
     * emits, and nothing here instructs a claim no tool can source: the
     * superscore and scholarship sentences say we hold nothing, which is true,
     * rather than telling the coach to state a policy from a record that does not
     * exist (RFC 187's lesson, applied here rather than found by review).
     *
     * The fee-waiver lead is INTERPOLATED from
     * [TestTimelineService.FEE_WAIVER_LEAD] and never typed as a word: the number
     * used to be spelled "fourteen" here and `14` in the service, so moving the
     * constant would have left the coach reciting the old one.
     */
    val DESCRIPTION =
      "Work out when this student has to sit the SAT or the ACT, using the deadlines on their own college list. " +
        "It starts at the earliest application deadline on that list, works back through the day the publisher " +
        "releases scores, and lands on the day registration closes, which is the day they actually have to act. " +
        "Use it whenever they ask when to take a test, and whenever a deadline on their list makes that question " +
        "real. Never state a test date, a registration deadline or a score-release date from memory: every date a " +
        "family acts on comes from this tool. Both tests come back and you say both; neither is the default. " +
        "usable_for_anchor is computed here and never asserted - true means those scores arrive on or before the " +
        "anchor deadline, false means they do not or registration has already closed, and null means we cannot " +
        "say, either because there is no deadline to work back from or because the publisher has not announced a " +
        "score release yet. Every sitting carries a why sentence naming the dates that were compared, so show the " +
        "working rather than stating the conclusion. A sitting named in data_availability has no published score " +
        "release: say that the publisher has not announced one, and never guess whether those scores would arrive " +
        "in time. A school publishes a month and a day and no year, so closing_resolved carries OUR year, worked " +
        "out from the graduation date the student gave us - say the year is ours, read closing_as_published and " +
        "resolution_basis out with it, and tell them to confirm the date on that school's own page. fee_waiver_by " +
        "is derived, not published - registration_deadline minus ${TestTimelineService.FEE_WAIVER_LEAD.days} " +
        "days - so give it as guidance and as its own earlier deadline, and fee_waiver_by_is_derived is why. " +
        "registration_deadline_time is the publisher's own closing hour and the two publishers do not share one, " +
        "so never call a deadline today's without naming whose clock it is, and read " +
        "late_or_changes_deadline_label for what that later date actually covers; its date is null when that " +
        "publisher publishes no later deadline. When a refusal is present this tool returns no date at all: say " +
        "what refusal says, send them to the two publishers it names, and name no date of your own - " +
        "calendar.expired tells you which of the two happened. With no anchor, general_sequence is the general " +
        "picture - say that it is general, and read anchor_rejections and closed_deadlines before you tell them " +
        "they have no deadline, because a school that published a month with no day, or a deadline that has " +
        "already closed, is in one of those two arrays and not a school with nothing. You may still name real " +
        "dates from sittings, because the calendar is national. This tool holds no school's superscore rule and " +
        "no scholarship score rule: ${AutomaticAwardChatTool.TOOL_NAME} holds both for the schools we have read, " +
        "so use it for those, and for every other school say they would have to check that school's own page. " +
        "school_day_programme " +
        "is what this student's own STATE does about an SAT or ACT sat inside the school day, and you read it " +
        "before you suggest registering for a Saturday sitting. Say the national calendar first and the state's " +
        "in-school sitting second, because the in-school sitting is an exception to that calendar and not the way " +
        "into it. Its why sentence is built from the record we hold, so state what it says, give school_year and " +
        "checked_on with it, and say that a state can change this from year to year and that source_url is where " +
        "to confirm it. school_year_basis says WHOSE year school_year is - stated_by_source is the state's own " +
        "stated year and reading_year is the year we read the page in - so say which it is and never present " +
        "ours as theirs. state_funded true means the family does not pay for that sitting, and null means we do " +
        "not know who pays - never that it is free. This section is about the STATE and never about their " +
        "school: never say their school runs a test day, never name an in-school test date, and never read a " +
        "state programme as a school's plan. school_must_offer is the ONE exception and only when it is true - " +
        "the state requires every public high school to hold the sitting, so you may say the day will be held at " +
        "their school - and where it is null say nothing either way, because null means we did not read it and " +
        "never that the state does not require it. You may ask once, plainly, whether their school runs it and " +
        "when: nothing waits on the answer, \"I don't know\" is a complete answer, and you never ask again. " +
        "held is true only where we hold a current record and null everywhere else, and null is a gap in OUR " +
        "data and never the state running nothing - say we hold no record for that state and invent no " +
        "programme. Where state is null we do not know which state they live in: do not ask for it here, " +
        "because the residency question belongs to the cost conversation and no answer of yours waits on it. " +
        "lowest_grade and highest_grade are the grades the sitting happens in - read them against " +
        "year_of_school to say when it comes round or that it has passed, and never guess a grade the section " +
        "does not name. Where test_chooser is district or student the state chose no test and test is null, so " +
        "name both tests, say who chooses from chooser_label, and never present one of them as theirs. " +
        "what_the_state_cannot_answer is the question this record can never answer, and you say it rather than " +
        "answering it. Where archive_url is present we ALSO hold an archived copy of that state page: offer both " +
        "links, never present the archived copy as what the live page says today, and name no capture date for " +
        "it, because we do not hold one - checked_on is the day WE read the page and is a different date. " +
        "Read-only: this tool changes nothing."
  }
}
