package ed.unicoach.coaching.testing

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.PublishedGpa
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.usDate
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
 * The `college_automatic_awards` chat tool (RFC 239): what a named school on
 * this student's list publishes about its automatic merit grid, where the
 * student's own score lands in it, and the last sitting that still makes the
 * school's money.
 *
 * A thin adapter, the house shape [AdmissionTestTimelineChatTool] states: this
 * class parses, reads, and RENDERS. Every rule -- which sitting is usable, which
 * figure the school's rule reads, whether a combination may be computed at all
 * -- lives in [AutomaticAwardService], and every word the coach reads is
 * authored here. THIS CLASS DOES NO ARITHMETIC: it holds no clock, adds nothing
 * up, and compares no date.
 *
 * ONE OBJECT PER REQUESTED COLLEGE, and inside it one object per GRID. A school
 * can publish several -- Iowa State and Kansas State gate an in-state award on a
 * score and pay the out-of-state award on GPA alone -- so each grid names its own
 * `residency_basis` and carries its own `answer` discriminator, and no rule of one
 * school or one residency can be read onto another.
 *
 * CITE ONCE PER BLOCK (RFC 190). The publisher, the page and the day we read it
 * ride ONCE at the head of each grid object and never on a band. A citation per
 * band would be the same claim printed forty times, and a reader would take the
 * repetition for forty separate readings.
 *
 * IT EMITS DOLLAR FIGURES, which [AdmissionTestTimelineChatTool] never does. The
 * award amounts, the published score bands and the point gap are numbers BY
 * CONTRACT ([NUMBERS_BY_CONTRACT]) -- declared here, deliberately, so the RFC 143
 * guard and the RFC 203 concise-answer sweep know they are intended rather than a
 * source code that leaked.
 *
 * WE NEVER CALL OUR COMBINED FIGURE AN ACT-COMPUTED SUPERSCORE. ACT's own
 * superscore is computed and reported by ACT; ours is a combination WE made from
 * the sections the student told us, offered against a school that says in
 * writing it accepts one, and every sentence here says so.
 */
class AutomaticAwardChatTool(
  private val service: AutomaticAwardReader,
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

    val reading =
      service
        .getForStudent(studentId, collegeIds)
        .getOrElse { e ->
          // The subset is the only caller-supplied state in the call, so the log
          // is where a failing read has to be reproducible from: null means the
          // whole active list, which is a different call from a three-school one.
          //
          // ONE PROPERTY PER FACT, and that is why there are two. `college_ids`
          // always parses as ids, and "was a subset asked for at all" is its own
          // boolean: one property holding a list on some calls and a sentence on
          // others cannot be searched for the ids it exists to carry.
          logger.warn(
            "tool [{}] automatic-award read failed for student=[{}] college_ids=[{}] whole_active_list=[{}]",
            TOOL_NAME,
            studentId.value,
            collegeIds?.map { it.value }.orEmpty(),
            collegeIds == null,
            e,
          )
          // THE TOOL AND THE SCOPE, AND NOTHING ABOUT THE STUDENT. A transcript
          // cannot recover which tool spoke from "read failed", and the scope is
          // what the caller asked for; the identifiers stay in the log.
          return errorObject(
            "$TOOL_NAME could not read the automatic-award corpus for " +
              (collegeIds?.let { "the [${it.size}] requested colleges" } ?: "this student's whole active list"),
          )
        }

    return buildJsonObject {
      putJsonArray("colleges") {
        // The service answers one row per (college, residency), in the list's
        // own order; the grouping is the wire's, so a school with two grids is
        // one object with two grids rather than two schools with one each.
        reading.answers
          .groupBy { it.collegeId }
          .forEach { (collegeId, answers) -> add(collegeObject(collegeId, answers)) }
      }
      // ALWAYS PRESENT, empty included: an id that is not on the student's list
      // is reported rather than silently dropped, and an absent key would read
      // as a tool that never checked.
      putJsonArray("unknown_college_ids") {
        reading.unknownCollegeIds.forEach { add(JsonPrimitive(it.value.toString())) }
      }
      put("what_this_tool_cannot_say", WHAT_THIS_TOOL_CANNOT_SAY)
    }
  }

  /** One requested school: its id, and every grid this school publishes for it. */
  private fun collegeObject(
    collegeId: CollegeId,
    answers: List<StudentAwardAnswer>,
  ): JsonObject =
    buildJsonObject {
      put("college_id", collegeId.value.toString())
      putJsonArray("grids") { answers.forEach { add(gridObject(it)) } }
    }

  /**
   * ONE GRID, and WHICH OF THE FIVE ANSWERS IT IS -- an exhaustive `when` with no
   * `else`, so a sixth arm cannot reach a family as a silent omission.
   *
   * The head is the same on every arm ([putHead]), including the arm that has
   * least to say: a grid object missing `publisher` and `checked_on` reads as a
   * renderer that forgot them rather than as the honest "we have not read this
   * school" it is.
   */
  private fun gridObject(studentAnswer: StudentAwardAnswer): JsonObject =
    buildJsonObject {
      when (val answer = studentAnswer.answer) {
        is AutomaticAwardAnswer.NotCurated -> {
          putHead(NOT_CURATED, award = null, why = NOT_CURATED_WHY)
        }

        is AutomaticAwardAnswer.NonePublished -> {
          putHead(NONE_PUBLISHED, answer.award, nonePublishedWhy(answer.award))
        }

        is AutomaticAwardAnswer.NoScoreEffect -> {
          putHead(NO_SCORE_EFFECT, answer.award, NO_SCORE_EFFECT_WHY)
          putSubmissionRequirement(answer.award)
        }

        is AutomaticAwardAnswer.Expired -> {
          putHead(EXPIRED, answer.award, expiredWhy(answer.award, answer.expiredOn))
          put("expired_on", answer.expiredOn.toString())
        }

        is AutomaticAwardAnswer.ScoreBands -> {
          putHead(SCORE_BANDS, answer.award, scoreBandsWhy(answer.award))
          putSubmissionRequirement(answer.award)
          putCombinationRule(answer.award)
          putDates(answer)
          putJsonArray("bands") { answer.award.bands.forEach { add(bandObject(it)) } }
          // The STANDINGS COME OFF THE ENVELOPE AND NOT OFF THE ANSWER: the arm
          // above is what the school published, and only this door's caller
          // holds a student to place in it.
          putJsonArray("standings") { studentAnswer.standings.forEach { add(standingObject(it)) } }
        }
      }
    }

  /**
   * The head every grid object carries: WHICH answer this is, whose grid it is,
   * and the ONE citation for everything below it (RFC 190).
   *
   * Every key is present on every arm, as an explicit null where we hold
   * nothing. `checked_on` is the day a person read the page and is never the day
   * the page was published -- the two are different claims and only one of them
   * is ours to make.
   */
  private fun JsonObjectBuilder.putHead(
    answer: String,
    award: AutomaticAward?,
    why: String,
  ) {
    put("answer", answer)
    put("residency_basis", award?.residencyBasis?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("residency_basis_label", award?.let { JsonPrimitive(residencyLabelOf(it.residencyBasis)) } ?: JsonNull)
    // The AcademicYear label and never a bare year: 2027 alone is a number the
    // model would do its own arithmetic on, and this one is an ENTRY year.
    put("entry_year", award?.entryYear?.label?.let(::JsonPrimitive) ?: JsonNull)
    put("publisher", award?.publisher?.let(::JsonPrimitive) ?: JsonNull)
    put("source_url", award?.sourceUrl?.let(::JsonPrimitive) ?: JsonNull)
    put("archive_url", award?.archiveUrl?.let(::JsonPrimitive) ?: JsonNull)
    put("checked_on", award?.checkedOn?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("citation_note", if (award == null) JsonNull else JsonPrimitive(CITATION_NOTE))
    put("why", why)
  }

  /**
   * Whether a score must be SUBMITTED to qualify at all -- the fact that makes
   * test-optional-for-admission and test-optional-for-money different questions,
   * and it rides on the `gpa_only_grid` arm too, because a school whose table
   * ignores a score can still demand one.
   */
  private fun JsonObjectBuilder.putSubmissionRequirement(award: AutomaticAward) {
    put("score_requirement", award.scoreRequirement.value)
    put("score_requirement_as_published", award.scoreRequirementAsPublished?.let(::JsonPrimitive) ?: JsonNull)
    put("score_requirement_why", requirementWhyOf(award.scoreRequirement))
  }

  /** The school's own scholarship superscore rule, in its own words, and what that permits US to compute. */
  private fun JsonObjectBuilder.putCombinationRule(award: AutomaticAward) {
    put("superscore_rule", award.superscoreRule.value)
    put("superscore_as_published", award.superscoreAsPublished?.let(::JsonPrimitive) ?: JsonNull)
    put("combination_rule_why", combinationRuleWhyOf(award.superscoreRule))
  }

  /**
   * THE DATE HALF: the last sitting that still makes this school's money, WHICH
   * of the school's two bounds excluded the next one, the sittings whose
   * registration has already closed, and a cut-off published in words we will
   * not parse.
   *
   * The two bounds are named separately and never merged. "Registration for that
   * Saturday has closed", "those scores would reach the school after its
   * deadline" and "this school stops counting sittings in December" send a
   * family to three different decisions, and a payload that rendered them alike
   * would cost a retake or waste one.
   */
  private fun JsonObjectBuilder.putDates(answer: AutomaticAwardAnswer.ScoreBands) {
    val award = answer.award
    put("score_deadline", award.scoreDeadline?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("score_deadline_as_published", award.scoreDeadlineAsPublished?.let(::JsonPrimitive) ?: JsonNull)
    put("last_usable_sitting", answer.lastUsableSitting?.let(::sittingObject) ?: JsonNull)
    put("last_usable_sitting_why", lastUsableSittingWhyOf(answer))
    // IN THE ORDER [AwardDateBound] DECLARES ITS MEMBERS, so two runs of the same
    // read cannot differ by set order -- and both members are said, because both
    // bounds can exclude one Saturday. The order is the enum's and NOT the
    // spelling of the wire words: sorting the strings would make renaming a wire
    // word silently reorder this array and the sentence beside it.
    putJsonArray("bound_by") {
      AwardDateBound.entries
        .filter { it in answer.boundBy }
        .forEach { add(JsonPrimitive(it.wireValue)) }
    }
    put("bound_by_why", boundWhyOf(answer))
    putJsonArray("closed_sittings") { answer.closedSittings.forEach { add(sittingObject(it)) } }
    put("last_sitting_considered_as_published", answer.quotedCutOff?.let(::JsonPrimitive) ?: JsonNull)
    put("last_sitting_considered_why", answer.quotedCutOff?.let { JsonPrimitive(quotedCutOffWhy(it)) } ?: JsonNull)
  }

  /**
   * One sitting, as the ONE renderer of a sitting says it
   * ([AdmissionTestTimelineChatTool.sittingJson]).
   *
   * NOT A SECOND SERIALIZER OF THE SAME VALUE. This tool used to render its own
   * narrower copy, which dropped `registration_deadline_time` -- the key that
   * stops one publisher's closing hour being read as the other's -- so the
   * standing coach instruction "the tool names whose" could not be obeyed from
   * this payload. The `whyOf` seam was already shared with that tool; the
   * serializer had simply gone the other way.
   */
  private fun sittingObject(sitting: TestSitting): JsonObject = AdmissionTestTimelineChatTool.sittingJson(sitting)

  /**
   * One published cell, AND NO CITATION (RFC 190): the grid's one citation is at
   * the head of the object this array sits in.
   *
   * The GPA bounds are STRINGS, because they are the school's published bounds
   * to three decimals -- Louisiana Tech's 3.0-3.749 and 3.75-4.0 pay different
   * money at the same ACT -- and a bound that arrived as a bare number is a
   * figure the model would round and then compare.
   */
  private fun bandObject(band: AutomaticAwardBand): JsonObject =
    buildJsonObject {
      put("award_name", band.awardName)
      // FOUR EXPLICIT NULLS ON THE PUBLISHED NO-SCORE ROW, not four absent keys
      // (this file's rule): that row is the tier a test-optional applicant lands
      // in -- a real published cell -- and a model shown a key-shaped hole would
      // read it as a cell we failed to read rather than one the school printed.
      // ONE `when` over the axis and not four independent null-tests, because
      // there are two shapes here and the type now says which one this is.
      when (val axis = band.score) {
        is BandScoreAxis.NoScore -> {
          put("test", JsonNull)
          put("test_name", JsonNull)
          put("score_low", JsonNull)
          put("score_high", JsonNull)
        }

        is BandScoreAxis.Range -> {
          put("test", axis.test.value)
          put("test_name", axis.test.displayName)
          put("score_low", axis.low)
          put("score_high", axis.high)
        }
      }
      // THE SCHOOL'S OWN TYPOGRAPHY AND NOT THE COLUMN'S SCALE ([PublishedGpa]):
      // `NUMERIC(4,3)` returns a school's published 3.50 as `3.500`, and this
      // wire used to print that third digit while the cost report printed the
      // school's two -- one published bound, spoken two ways.
      put("gpa_low", band.gpaLow?.let { JsonPrimitive(PublishedGpa.of(it)) } ?: JsonNull)
      put("gpa_high", band.gpaHigh?.let { JsonPrimitive(PublishedGpa.of(it)) } ?: JsonNull)
      put("award_per_year_usd", band.awardPerYearUsd?.let(::JsonPrimitive) ?: JsonNull)
      // The school's own words where it publishes a KIND of award rather than an
      // amount. These are the TOP bands: dropping them would make the coach name
      // a school's second-best award as its best.
      put("award_as_published", band.awardAsPublished?.let(::JsonPrimitive) ?: JsonNull)
    }

  /** Where this student stands in one school's grid on ONE test -- the two figures, kept apart, and the gap to the next cell. */
  private fun standingObject(standing: StudentStanding): JsonObject =
    buildJsonObject {
      put("test", standing.test.value)
      put("test_name", standing.test.displayName)
      put("best_single_sitting", standing.bestSingleSitting?.let(::singleSittingObject) ?: JsonNull)
      putCombination(standing)
      put("figure_read", standing.figureRead?.let(::JsonPrimitive) ?: JsonNull)
      put("figure_read_why", figureReadWhyOf(standing))
      putJsonArray("bands_reached") { standing.bandsReached.forEach { add(bandObject(it)) } }
      put("next_band", standing.nextBand?.let(::nextBandObject) ?: JsonNull)
    }

  /**
   * OUR combination or the named reason there is none -- BOTH KEYS ALWAYS, and
   * never both filled.
   *
   * One exhaustive `when` over one value, so the tool's own contract ("where
   * combination_refusal is present there is NO combined number") is a property
   * of the type rather than of two independent null tests that could both fire.
   */
  private fun JsonObjectBuilder.putCombination(standing: StudentStanding) {
    when (val outcome = standing.combination) {
      is CombinationOutcome.Computed -> {
        put("combined", combinedObject(outcome.combined))
        put("combination_refusal", JsonNull)
      }

      is CombinationOutcome.Refused -> {
        put("combined", JsonNull)
        put("combination_refusal", refusalObject(outcome.refusal))
      }
    }
  }

  /** The best total the student reported for ONE date -- never a figure assembled from several. */
  private fun singleSittingObject(sitting: SittingScore): JsonObject =
    buildJsonObject {
      put("score", sitting.score)
      put("sitting", sitting.sitting.toIso())
      put("sitting_said", sitting.sitting.usDate())
      put("why", SINGLE_SITTING_WHY)
    }

  /**
   * OUR combination, labelled as ours in the same object that carries the
   * number.
   *
   * The label is not decoration: a number the school did not compute, sitting
   * beside a number it did, is exactly the pair a reader collapses. So the
   * figure never travels without the sentence that says whose arithmetic it is
   * and which stated rule of the school's it was offered against.
   */
  private fun combinedObject(combined: CombinedScore): JsonObject =
    buildJsonObject {
      put("score", combined.score)
      put("label", OUR_COMBINATION_LABEL)
      put("computed_by_us", true)
      putJsonArray("sittings_used") { combined.sittingsUsed.forEach { add(JsonPrimitive(it.toIso())) } }
      putJsonArray("sittings_used_said") { combined.sittingsUsed.forEach { add(JsonPrimitive(it.usDate())) } }
      put("act_subjects_averaged", combined.actSubjectsAveraged?.let(::JsonPrimitive) ?: JsonNull)
      put("why", combinedWhyOf(combined))
    }

  /** WHY there is no combination -- a named reason and never a silent absence. */
  private fun refusalObject(refusal: CombinationRefusal): JsonObject =
    buildJsonObject {
      put("reason", refusalCodeOf(refusal))
      put("why", refusalWhyOf(refusal))
    }

  /**
   * The cells at the lowest score above the one this student's figure reaches:
   * the slice's headline sentence, with the gap and the step.
   *
   * `bands` IS AN ARRAY AND WAS ONE OBJECT. A two-dimensional grid publishes one
   * cell per GPA row at a score and we hold no GPA of hers, so a single `band`
   * key was this payload naming a GPA she never gave us -- in the one sentence
   * that carries a dollar figure.
   *
   * The step is a LOW/HIGH PAIR for the same reason, and both are an explicit
   * `null` together where any cell on either side of the step publishes a KIND
   * of award rather than an amount: those cells are the school's TOP awards, and
   * a gap computed against them would be computed against nothing.
   */
  private fun nextBandObject(next: NextBand): JsonObject =
    buildJsonObject {
      putJsonArray("bands") { next.bands.forEach { add(bandObject(it)) } }
      put("points_away", next.pointsAway)
      put("dollar_step_per_year_usd_low", next.dollarStepPerYearUsd?.first?.let(::JsonPrimitive) ?: JsonNull)
      put("dollar_step_per_year_usd_high", next.dollarStepPerYearUsd?.last?.let(::JsonPrimitive) ?: JsonNull)
      put("why", nextBandWhyOf(next))
    }

  companion object {
    private val logger = LoggerFactory.getLogger(AutomaticAwardChatTool::class.java)

    const val TOOL_NAME = "college_automatic_awards"

    /** The five answers, as the model reads them. One per arm of [AutomaticAwardAnswer], and no two alike. */
    const val NOT_CURATED = "not_curated"
    const val NONE_PUBLISHED = "none_published"
    const val NO_SCORE_EFFECT = "no_score_effect"
    const val EXPIRED = "expired"
    const val SCORE_BANDS = "score_bands"

    /**
     * THE NUMBERS THIS TOOL MEANS TO EMIT, and the only ones the RFC 143 guard
     * will pass.
     *
     * `admission_test_timeline`'s set is EMPTY and every value on its wire is a
     * string; this tool cannot be that, because the answer IS money and a dollar
     * figure rendered as a string is a figure the coach would have to re-type.
     * So the set is enumerated rather than absent: the award amounts, the
     * published band edges, the student's own figures, the gap and the step.
     *
     * Everything NOT here is still a string -- every date is ISO, every year is
     * an [ed.unicoach.common.util.AcademicYear] label, and every GPA bound is the
     * school's own digits -- so a date or a year arriving as a number fails the
     * guard, which is the point of naming these nine.
     */
    val NUMBERS_BY_CONTRACT: Set<String> =
      setOf(
        "score_low",
        "score_high",
        "award_per_year_usd",
        "score",
        "figure_read",
        "points_away",
        "dollar_step_per_year_usd_low",
        "dollar_step_per_year_usd_high",
        "act_subjects_averaged",
      )

    /** The one sentence that says what a grid object's single citation covers (RFC 190). */
    const val CITATION_NOTE: String =
      "The publisher, the page and the day we read it are stated once for this whole grid: every band below was " +
        "read from that one page on that one day."

    /** We never read this school. The coach's standing sentence holds, and it never estimates. */
    const val NOT_CURATED_WHY: String =
      "We have not read this school's automatic-award page, so we cannot say what it publishes or whether a score " +
        "is worth money there -- and we never estimate one. This is a gap in OUR data and never a statement that " +
        "the school publishes nothing: the school's own financial-aid page is where that answer is."

    /** A published table in which a score changes nothing. A different TRUE answer, and "we do not know" would be false here. */
    const val NO_SCORE_EFFECT_WHY: String =
      "This school publishes an automatic award table and a test score changes NOTHING in it. That is what the " +
        "school's page says, not a gap in our data: the money here turns on the rest of its table, so a retake " +
        "cannot raise this award."

    /** The one thing this tool refuses to do, said on the payload rather than left to the prompt. */
    const val WHAT_THIS_TOOL_CANNOT_SAY: String =
      "This tool states what a school PUBLISHES about an automatic award and where a stored score lands in it. It " +
        "is not an offer, it is not a prediction, and it is never a school we have not read: a school absent from " +
        "colleges, or answering not_curated, has no automatic-award answer here at all."

    /** What a stored total IS: one date's number, as the student reported it. */
    const val SINGLE_SITTING_WHY: String =
      "This is the best total the student told us for ONE sitting date, as she reported it. We did not assemble it " +
        "from several sittings."

    /** The name our combined figure travels under. Never a publisher's own product name. */
    const val OUR_COMBINATION_LABEL: String = "a combined score from your best sections"

    /**
     * Whose grid this is, in words -- `not_applicable` is a real answer and not
     * an absence.
     *
     * THREE, AND THE OTHER TWO ARE REFUSED RATHER THAN WORDED. The table's
     * `college_automatic_awards_residency_basis_check` and the loader's
     * `AWARD_RESIDENCY_BASES` admit exactly these three, so a row on any other
     * basis is corruption -- and the one thing not to do with corruption is put
     * a sentence about a family's legal residency on the wire. Naming the two
     * arms is still not an `else`: a sixth member of the shared vocabulary
     * cannot compile until this function says what it is.
     */
    fun residencyLabelOf(basis: ResidencyBasis): String =
      when (basis) {
        ResidencyBasis.IN_STATE -> {
          "the award this school publishes for a student it treats as a state resident"
        }

        ResidencyBasis.OUT_OF_STATE -> {
          "the award this school publishes for a student it treats as a non-resident"
        }

        ResidencyBasis.NOT_APPLICABLE -> {
          "this school publishes one award grid and it does not vary by residency"
        }

        ResidencyBasis.IN_DISTRICT, ResidencyBasis.UNKNOWN -> {
          throw IllegalStateException(
            "[$TOOL_NAME] read an automatic award on residency_basis [${basis.value}], which " +
              "college_automatic_awards_residency_basis_check does not admit: this row is corrupt, and a grid " +
              "whose audience we cannot name is not one we describe to a family",
          )
        }
      }

    /** A verified absence with a citation -- and the date is the school's page, read by a person. */
    fun nonePublishedWhy(award: AutomaticAward): String =
      "We read ${award.publisher}'s own page on ${UsDate.of(award.checkedOn)} and this school publishes NO automatic " +
        "award at all. That is a verified absence with a page behind it, not a gap in our data -- say we checked, " +
        "and say when."

    /** Stale is not absent, and the two send a family to different places (D7/D20). */
    fun expiredWhy(
      award: AutomaticAward,
      expiredOn: LocalDate,
    ): String =
      "We read this school's page on ${UsDate.of(award.checkedOn)} and our reading stopped being current on " +
        "${UsDate.of(expiredOn)}. These pages are re-keyed for each entry year, so we will not state last cycle's " +
        "grid as this one's: we hold NO current grid for this school, which is our gap and not a change we know " +
        "about. Send them to ${award.sourceUrl}."

    /** A score is money here, and the citation for everything under it is one page read on one day. */
    fun scoreBandsWhy(award: AutomaticAward): String =
      "A test score is money at this school: ${award.publisher} publishes the bands below for " +
        "${award.entryYear.label} entry, read from its own page on ${UsDate.of(award.checkedOn)}."

    /** Whether a score must be submitted at all -- three states, and the third is the one the coach refuses on. */
    fun requirementWhyOf(requirement: ScoreRequirement): String =
      when (requirement) {
        ScoreRequirement.REQUIRED -> {
          "This school's page says a test score must be SUBMITTED to be considered for its automatic award, so " +
            "applying test-optional here costs the award whatever the rest of the application says."
        }

        ScoreRequirement.NOT_REQUIRED -> {
          "This school's page says a test score is not required to be considered for its automatic award."
        }

        ScoreRequirement.UNSTATED -> {
          "This school's page does not say whether a score must be submitted to qualify, so we do not know -- and " +
            "neither answer may be given on our authority. Ask the school's admissions office."
        }
      }

    /** What the school's stated rule permits US to compute against its own grid. */
    fun combinationRuleWhyOf(rule: ScholarshipSuperscoreRule): String =
      when (rule) {
        ScholarshipSuperscoreRule.SUPERSCORE_ACCEPTED -> {
          "This school's page says its scholarship grid accepts a combined score, so we may offer a figure built " +
            "from the student's best sections -- OURS, computed here, and never a score the school or the test's " +
            "publisher holds."
        }

        ScholarshipSuperscoreRule.SINGLE_SITTING_ONLY -> {
          "This school's page says its scholarship grid reads ONE sitting, so only a single sitting's total counts " +
            "here -- whatever any other school does, and whatever this school does for ADMISSION, which is a " +
            "different rule."
        }

        ScholarshipSuperscoreRule.UNSTATED -> {
          "This school's page does not state a scholarship rule about combining sittings, so we compute no " +
            "combination against its grid and quote no rule. Ask the school."
        }
      }

    /** The last sitting that clears BOTH of the school's bounds -- or the honest absence, which has two causes. */
    fun lastUsableSittingWhyOf(answer: AutomaticAwardAnswer.ScoreBands): String {
      val sitting = answer.lastUsableSitting
      if (sitting != null) {
        return "The ${UsDate.of(sitting.testDate)} ${sitting.test.displayName} is the last sitting we hold that " +
          "clears every date bound this school publishes. Registration for it closes " +
          "${UsDate.of(sitting.registrationDeadline)}, which is the day the student actually has to act."
      }
      return if (answer.award.scoreDeadline == null) {
        "This school publishes no date by which scores must be with it, so there is no last usable sitting to " +
          "work out and we name none. That is the school's silence, not a deadline of ours."
      } else {
        "No sitting we hold clears this school's date bounds, so there is no sitting left that can raise this " +
          "award. Say that plainly rather than suggesting a retake."
      }
    }

    /**
     * WHICH bound excluded the next sitting -- and the empty set is an answer of
     * its own, not a missing sentence.
     *
     * Both members can be present at once, because both can exclude the same
     * Saturday, and naming one of them would send a student to a school page
     * that repeats the other.
     */
    fun boundWhyOf(answer: AutomaticAwardAnswer.ScoreBands): String {
      if (answer.boundBy.isEmpty()) {
        return "Nothing later was excluded BY THIS SCHOOL: either it publishes no bound past the sittings we hold, " +
          "or our calendar simply runs out there. Do not read this as the school accepting a later sitting."
      }
      // The declaration's order, for `bound_by`'s reason: the array and this
      // sentence say the two bounds in ONE order, and it is not one a wire word
      // can move.
      return AwardDateBound.entries
        .filter { it in answer.boundBy }
        .joinToString(" And ") { bound -> boundClauseOf(bound, answer.award) }
    }

    /** One bound, in the school's own terms. Two clauses, and they are never interchangeable. */
    private fun boundClauseOf(
      bound: AwardDateBound,
      award: AutomaticAward,
    ): String =
      when (bound) {
        AwardDateBound.RECEIPT_DEADLINE -> {
          "The next sitting after the one above releases scores AFTER this school's deadline for having them" +
            (award.scoreDeadline?.let { " (${UsDate.of(it)})" } ?: "") +
            ", so those scores would not be with the school in time."
        }

        AwardDateBound.LAST_SITTING_CONSIDERED -> {
          "This school stops counting sittings after the cut-off it publishes" +
            (award.lastSittingConsidered?.let { " (\"${it.asPublished}\")" } ?: "") +
            ", so a later sitting cannot raise this award however early the report arrives -- which is a different " +
            "fact from the receipt deadline and is the one a retake plan gets wrong."
        }
      }

    /** A cut-off we will not parse: it excludes no sitting, and the school's own sentence is what the family reads. */
    fun quotedCutOffWhy(asPublished: String): String =
      "This school publishes its cut-off in words we do not turn into a date: \"$asPublished\". No sitting above is " +
        "excluded by it -- read the school's own sentence out and let the family check their test against it."

    /**
     * Which figure the SCHOOL's rule reads -- stated, because the two figures
     * can differ by a whole band.
     *
     * EXHAUSTIVE OVER [CombinationOutcome] WITH NO `else`, the shape
     * `putCombination` one screen up already has: a third arm of that sealed
     * type routed by a default would tell the coach this school reads a single
     * sitting, which is a claim about the school's money and not a fallback.
     */
    fun figureReadWhyOf(standing: StudentStanding): String {
      if (standing.figureRead == null) {
        return "We hold no ${standing.test.displayName} figure this school's grid can be read with: either she has " +
          "stored no total for it, or the combination was refused and combination_refusal says why. So she " +
          "lands in no cell here -- which is not the same as landing in the lowest one. The bands and the dates " +
          "above still stand, and no answer is gated on a score."
      }
      return when (standing.combination) {
        is CombinationOutcome.Computed -> {
          "This school's own rule reads the combined figure, so that is the number its grid is read with here -- " +
            "and it is ours, not the school's and not the test publisher's."
        }

        is CombinationOutcome.Refused -> {
          "This school's own rule reads a single sitting, so its grid is read with the best total the student " +
            "reported for one date."
        }
      }
    }

    /** Our combination, and the published rule it was computed under. Never presented as the publisher's own product. */
    fun combinedWhyOf(combined: CombinedScore): String {
      val head =
        "WE computed this figure from the best sections the student told us, against this school's own stated rule " +
          "that it accepts a combined score. It is not a score this school holds, and it is not a figure "
      return when (combined.test) {
        AdmissionTest.ACT -> {
          head + "ACT computes or reports for her. We averaged the ${combined.actSubjectsAveraged} subjects ACT's " +
            "own published rule names for her sittings and rounded half up, which is ACT's stated rounding."
        }

        AdmissionTest.SAT -> {
          head + "College Board computes or reports for her. On the SAT scale the two section scores add up to the " +
            "total, so this is that sum and nothing was estimated."
        }
      }
    }

    /** The reason code for a refusal -- one per arm, so the model can route on it without reading the sentence. */
    fun refusalCodeOf(refusal: CombinationRefusal): String =
      when (refusal) {
        is CombinationRefusal.RuleDoesNotPermit -> "rule_does_not_permit"
        is CombinationRefusal.NoSectionsHeld -> "no_sections_held"
        is CombinationRefusal.MixedActSectionHistory -> "mixed_act_section_history"
        is CombinationRefusal.IncompleteActSections -> "incomplete_act_sections"
        is CombinationRefusal.ActRuleNotCited -> "act_rule_not_cited"
      }

    /**
     * WHY no combination was computed -- five sentences, exhaustive with no
     * `else`, and two of them are about entirely different things: "this school
     * reads one sitting" is about the school's money, and "we cannot divide your
     * history" is about our arithmetic.
     */
    fun refusalWhyOf(refusal: CombinationRefusal): String =
      when (refusal) {
        is CombinationRefusal.RuleDoesNotPermit -> {
          "No combined figure is offered here, because this school's page does not say its scholarship grid " +
            "accepts one (it says: ${refusal.rule.value}). Offering one would be naming money this school has not " +
            "said it will pay."
        }

        is CombinationRefusal.NoSectionsHeld -> {
          "We hold no section scores for this test -- a sitting entered as a bare total contributes none -- so " +
            "there is nothing of the student's to combine. That is absent data of ours, not a refusal of the " +
            "school's, and she may add her section scores."
        }

        is CombinationRefusal.MixedActSectionHistory -> {
          "This student's ACT sittings do not all carry the same set of sections, and ACT publishes no divisor for " +
            "a history that spans its 2025 change. So there is NO combined number here at all: choosing a divisor " +
            "would be our interpretation wearing ACT's authority. Her single best sitting still stands."
        }

        is CombinationRefusal.IncompleteActSections -> {
          "One of this student's ACT sittings carries some sections and not a whole published set, so averaging it " +
            "would average a section we do not hold. No combined number is offered, and her single best sitting " +
            "still stands."
        }

        is CombinationRefusal.ActRuleNotCited -> {
          // THREE DIFFERENT FACTS AND THREE DIFFERENT SENTENCES. "We never
          // committed this citation", "the page has moved" and "the sweep never
          // reached an answer" are not one absence, and the family is told which
          // it is rather than the mildest of them.
          val why =
            when (refusal.status) {
              // NO ROW AT ALL: nobody has committed this citation yet.
              null -> "we do not yet hold the page it is on"

              SourceUrlStatus.NOT_FOUND -> "the page it is on no longer resolves for us"

              SourceUrlStatus.UNKNOWN -> "our last check of that page reached no usable answer"

              // The service builds this refusal only where the status is NOT
              // `found`, so this arm is unreachable from a read -- named rather
              // than left to an `else`, and worded as the state it would be.
              SourceUrlStatus.FOUND -> "that page resolved when we last looked and the combination was still withheld"
            }
          val looked = refusal.checkedOn?.let { " We last looked on ${UsDate.of(it)}." } ?: ""
          "ACT's composite arithmetic is ACT's published rule and not ours, and $why, so no combined ACT figure " +
            "is computed. The page this is waiting on is ${refusal.url}.$looked"
        }
      }

    /**
     * The gap, and what it is worth -- measured from the figure THIS school
     * reads, which is what makes it actionable.
     *
     * IT NAMES EVERY CELL AT THAT SCORE AND PRICES NONE OF THEM ALONE. Where the
     * school publishes several cells there, they differ by GPA and we hold no
     * GPA; where any cell on either side of the step is published as a KIND of
     * award, the school's own words are read out and NO dollar gap is stated,
     * because those are the top awards and a step computed against them is a
     * step computed against nothing.
     */
    fun nextBandWhyOf(next: NextBand): String {
      val names = next.bands.joinToString(" and ") { "\"${it.awardName}\"" }
      val cells = if (next.bands.size == 1) "band" else "${next.bands.size} bands"
      val step = stepClauseOf(next)
      val gpaNote =
        if (next.bands.size == 1) {
          ""
        } else {
          " This school publishes more than one cell at that score -- one per grade-point-average row -- and we hold " +
            "no GPA of hers, so read every one of them out and let the family find their own row."
        }
      return "${next.pointsAway} more points on the figure this school reads would reach its $names $cells, $step." +
        "$gpaNote The gap is measured from the figure the SCHOOL's own rule reads, so it is the real one -- and a " +
        "score is only worth this if it arrives inside the school's date bounds above."
    }

    /** What the step pays: one figure, a range across the GPA rows at that score, or the school's own words. */
    private fun stepClauseOf(next: NextBand): String {
      val step =
        next.dollarStepPerYearUsd
          ?: return "worth what this school publishes in words rather than as a figure -- " +
            quotedAwardsOf(next.bands) + " -- so state those words and state NO dollar gap"
      if (step.first == step.last) return "worth ${WholeDollars.spoken(step.first)} more per year"
      return "worth between ${WholeDollars.spoken(step.first)} and ${WholeDollars.spoken(step.last)} more per year, " +
        "depending which grade-point-average row of this school's grid she is in"
    }

    /**
     * The school's own words for what a cell pays, quoted -- a cell priced as a
     * figure names the figure instead.
     *
     * NOTHING HERE SUPPLIES A MISSING VALUE. A band states an amount or the
     * school's words (the model's own `award_has_value` require), so the two are
     * listed and never defaulted: a `?: 0` here would be the very reading -- an
     * unpriced top award treated as zero -- that this sentence exists to stop.
     */
    private fun quotedAwardsOf(bands: List<AutomaticAwardBand>): String =
      bands.joinToString("; ") { band ->
        val pays =
          listOfNotNull(
            band.awardAsPublished?.let { "\"$it\"" },
            band.awardPerYearUsd?.let { "${WholeDollars.spoken(it)} a year" },
          ).joinToString(", ")
        "\"${band.awardName}\": $pays"
      }

    /**
     * The model-facing copy. Every sentence names a field this tool actually
     * emits, and nothing here instructs a claim no field can source.
     *
     * It states the naming rule in its own words: our combined figure is OURS,
     * offered against a school's stated rule, and is never presented as a
     * publisher-computed and publisher-reported one.
     */
    val DESCRIPTION: String =
      "What a named school on this student's list PUBLISHES about its automatic merit award -- the grid where a " +
        "test score band is a price -- and where this student's own stored score lands in it. Use it whenever a " +
        "family asks whether a score is worth money at a school, whether a retake would pay, or what a school's " +
        "scholarship grid says. Never state a band, a dollar figure, a deadline or a superscore rule for a school " +
        "from memory: every one of them comes from here. One object per requested college, and a school may " +
        "publish SEVERAL grids -- one per residency_basis -- so read residency_basis and residency_basis_label out " +
        "loud and never apply one residency's grid to the other; we do not know which one a school would apply to " +
        "this student, because that is a legal status the school determines. Every grid carries an answer: " +
        "score_bands is a grid a score moves, no_score_effect is a published table a score changes NOTHING in, " +
        "none_published is a school we read that publishes no automatic award at all, expired is a reading of ours " +
        "that has gone stale, and not_curated is a school we have never read. Those last three are different " +
        "facts and you never say one of them for another. publisher, source_url and checked_on are stated ONCE per " +
        "grid and cover every band under it, so cite them once and never per band. bands are the school's own " +
        "published cells: award_per_year_usd is dollars per year, and where it is null the school published a KIND " +
        "of award and award_as_published is its own words for it -- say those words rather than calling it the " +
        "second-best band. standings is where this student's own figures land, one per test; a student with no " +
        "stored score gets the bands and the dates and no standing at all, and no answer of yours is ever gated on " +
        "a score. best_single_sitting is the best total she reported for ONE date. combined is a figure WE computed " +
        "from her best sections, and only where the school's own page says its scholarship grid accepts one: say " +
        "it is ours, say which sittings it used, and never present it as a superscore that ACT or College Board " +
        "computes and reports for her. Where combination_refusal is present there is NO combined number: read its " +
        "why out, and never supply a figure it withheld. figure_read is the number this school's rule actually " +
        "reads, and figure_read_why says which. next_band is the headline: points_away on the figure the school " +
        "reads, and what that step pays as dollar_step_per_year_usd_low and dollar_step_per_year_usd_high a year. " +
        "next_band.bands is EVERY cell the school publishes at that score -- several of them means several " +
        "grade-point-average rows and we hold no GPA, so read them all out and never pick one. Where both step " +
        "figures are null the school published a KIND of award there rather than an amount: say its own words and " +
        "state no dollar gap at all, because those are its top awards. A step is only worth anything if a sitting " +
        "is still usable, so say last_usable_sitting in the same breath. bound_by names WHICH of the school's two date bounds " +
        "excluded the next sitting: receipt_deadline means the scores would arrive too late, and " +
        "last_sitting_considered means the school stops counting sittings then whatever the report date -- they " +
        "are different and send a family to different decisions. closed_sittings have already closed for " +
        "registration and are not a choice any more. Where last_sitting_considered_as_published is present the " +
        "school published a cut-off we do not parse: quote it and let the family check their own test against it. " +
        "score_requirement says whether a score must be SUBMITTED to qualify at all, which is a different question " +
        "from whether the school is test-optional for admission; where it is unstated, say we do not know and send " +
        "them to the school. unknown_college_ids are ids that are not on this student's list, and they are " +
        "reported rather than answered. Read-only: this tool changes nothing."
  }
}

/** The stored code for one of the school's two date bounds -- the wire's word for it, in one place. */
internal val AwardDateBound.wireValue: String
  get() =
    when (this) {
      AwardDateBound.RECEIPT_DEADLINE -> "receipt_deadline"
      AwardDateBound.LAST_SITTING_CONSIDERED -> "last_sitting_considered"
    }
