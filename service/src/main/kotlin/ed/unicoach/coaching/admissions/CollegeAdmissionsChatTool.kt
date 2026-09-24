package ed.unicoach.coaching.admissions

import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.db.models.CdsMonthDay
import ed.unicoach.db.models.StudentId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * The `college_admissions_profile` chat tool (RFC 148): the coach's read path
 * into what the student's listed schools published about themselves in their
 * own Common Data Set -- the non-need (merit) money they hand out, what the
 * admission office says it weighs, and when each round is due -- plus the two
 * facts that are NOT theirs to publish: the admission-test policy each of them
 * reported to the federal IPEDS survey (RFC 187), and the published 25th/75th
 * percentile score bands of the class each of them admitted (RFC 195).
 * Read-only --
 * it writes nothing. Total by the [ed.unicoach.chat.ChatTool] contract:
 * malformed input returns a structured `{ "error": ... }` object the model
 * reads, never a throw.
 *
 * A thin adapter by design ([ed.unicoach.coaching.costs.CollegeCostChatTool]'s
 * shape): [execute] only orchestrates parse -> read -> render; every
 * composition rule -- the share's denominator, the latest cycle, which sections
 * are unreported -- lives in [CollegeAdmissionsService].
 *
 * The rendering rules are the product, not decoration. A rating reaches the
 * wire as the words a coach says, a round carries its spoken name beside its
 * code, a date is a phrase and never a `{month, day}` pair of bare numbers, and
 * a school's `false` round flag is emitted as the statement it is. Each section
 * carries its OWN citation -- the three CDS sections because each is a
 * different school's own document from its own cycle, and the test policy
 * because a policy claim without the year it was collected in is a claim about
 * a year nobody named.
 */
class CollegeAdmissionsChatTool(
  private val service: CollegeAdmissionsService,
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

    val profile =
      service
        .getForStudent(studentId, collegeIds)
        .getOrElse { e ->
          // The subset the call asked for is the only caller-supplied state in
          // it, and the wire error is deliberately opaque, so the log is where
          // the failing call has to be reproducible from: null means the whole
          // active list, which is a different call from a three-school subset.
          logger.warn(
            "tool [{}] admissions read failed for student=[{}] college_ids=[{}]",
            TOOL_NAME,
            studentId.value,
            collegeIds?.map { it.value } ?: "whole active list",
            e,
          )
          return errorObject("college admissions read failed")
        }

    return profileObject(profile)
  }

  /** The full structured result: one admissions object per college. Attribution rides per section, not here. */
  private fun profileObject(profile: CollegeAdmissionsProfile): JsonObject =
    buildJsonObject {
      putJsonArray("colleges") { profile.colleges.forEach { add(collegeObject(it)) } }
      put("count", profile.colleges.size)
      if (profile.unknownCollegeIds.isNotEmpty()) {
        putJsonArray("unknown_college_ids") {
          profile.unknownCollegeIds.forEach { add(JsonPrimitive(it.value.toString())) }
        }
      }
    }

  /**
   * One college. The five sections are emitted in the order the coach should
   * reach for them (RFC 145): the money first, then what the school weighs --
   * the C7 grid, then whether it wanted a test score at all, then what those
   * scores looked like -- then when it is all due. A section this school does
   * not report is ABSENT and is named in `data_availability`, never emitted as
   * an empty object.
   */
  private fun collegeObject(admissions: CollegeAdmissions): JsonObject =
    buildJsonObject {
      put("college_id", admissions.collegeId.value.toString())
      put("name", admissions.name)
      put("city", admissions.city)
      put("state", admissions.state)
      put("list_status", admissions.listStatus.value)
      admissions.meritAid?.let { put(AdmissionsField.MERIT_AID.wireName, MeritAidWire.objectOf(it)) }
      admissions.factors?.let { put(AdmissionsField.ADMISSION_FACTORS.wireName, factorsObject(it)) }
      admissions.testPolicy?.let { put(AdmissionsField.TEST_POLICY.wireName, testPolicyObject(it)) }
      admissions.scoreBands?.let { put(AdmissionsField.SCORE_BANDS.wireName, ScoreBandWire.objectOf(it)) }
      admissions.deadlines?.let { put(AdmissionsField.DEADLINES.wireName, deadlinesObject(it)) }
      putJsonArray("data_availability") {
        admissions.notReported.forEach { add(JsonPrimitive(it.wireName)) }
      }
    }

  /**
   * The C7 grid as rows of spoken words. A factor the school did not report is
   * not here at all -- the service dropped it -- because "not reported" and the
   * school's own "not considered" are different facts and the second one is
   * still rendered, in its own words.
   */
  private fun factorsObject(grid: AdmissionFactorGrid): JsonObject =
    buildJsonObject {
      putJsonArray("factors") { grid.weights.forEach { add(factorObject(it)) } }
      putJsonObject("source") { putCitation(grid.source) }
    }

  /**
   * What this school told the federal admissions survey about test scores (RFC
   * 187). Four string values, and the third of them is not optional: the
   * "a school can change this, confirm it on their page" sentence rides with
   * EVERY rendered policy (D17), because a dated policy quoted without it is
   * exactly the fact a family would act on a year too late.
   *
   * The slug rides beside the sentence because it is the same plain word
   * `search_colleges` accepts for this axis. The raw `1`/`3`/`5` never reaches
   * here -- the service only builds the section from a mapped member -- and
   * neither does the string `ADMCON7`, which is a survey's internal variable
   * name and means nothing to a student.
   */
  private fun testPolicyObject(section: SchoolTestPolicy): JsonObject =
    buildJsonObject {
      put("policy", section.policy.slug)
      put("statement", section.statement)
      put("confirm_with_school", TestPolicyCopy.CONFIRM_WITH_SCHOOL)
      putJsonObject("source") { putCitation(section.source) }
    }

  /** One reported C7 row: the code, the words a coach says for it, and the weight the school gives it -- from one construct. */
  private fun factorObject(weight: FactorWeight): JsonObject =
    buildJsonObject {
      put("factor", weight.factor.value)
      put("factor_label", weight.factor.label)
      put("importance", weight.rating.label)
    }

  /**
   * The application calendar. Every round carries its code and its spoken name
   * from one construct, plus a statement of the offered flag -- a `false` is
   * the school saying it does not run that round, which is a fact worth saying
   * and not a silence. Dates are phrases; a `{month, day}` pair of bare numbers
   * is never emitted.
   */
  private fun deadlinesObject(schedule: DeadlineSchedule): JsonObject =
    buildJsonObject {
      putJsonArray("rounds") { schedule.rounds.forEach { add(roundObject(it)) } }
      putJsonObject("source") { putCitation(schedule.source) }
    }

  /**
   * One application round: its code, its spoken name, and the school's own
   * offered flag stated in words. The dates ride on the offered case only.
   */
  private fun roundObject(round: DeadlineRound): JsonObject =
    buildJsonObject {
      put("round", round.round.value)
      put("round_label", round.round.label)
      put("offered", round.offered)
      put("offered_label", offeredLabel(round))
      putRoundDates(round)
    }

  /**
   * Exhaustive on purpose, and the reason dates are typed onto the offered
   * case: a date under a round the school states it does not run is a
   * contradiction the model would have to resolve, so it is unrepresentable
   * rather than filtered out here.
   */
  private fun JsonObjectBuilder.putRoundDates(round: DeadlineRound) {
    when (round) {
      is DeadlineRound.Offered -> {
        round.closing?.let { put("application_deadline", datePhrase(it)) }
        round.notification?.let { put("decision_notification", datePhrase(it)) }
      }

      is DeadlineRound.NotOffered -> {}
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeAdmissionsChatTool::class.java)

    const val TOOL_NAME = "college_admissions_profile"

    /**
     * The keys this tool emits whose value is a NUMBER by contract -- the
     * merit measures, the two percentile scores, the D16 admitted average and
     * the result count. Everything else it renders is a string by construction
     * (spoken ratings, spoken round names, date phrases, the cycle label),
     * which is the point of the rendering rules rather than a coincidence: the
     * RFC 143 guard reads this list, so a new numeric key has to be admitted
     * here deliberately.
     *
     * THE THREE SCORE KEYS ARE ADMITTED ON PURPOSE (RFC 195 D-C), and they are
     * the first figures here that are not money. The guard exists to catch a
     * SOURCE CODE arriving as a bare number -- a `1` that means New England, a
     * quintile index, a `month` with no `day` -- and its test is "a number
     * whose key does not name a measure". These keys name their measure
     * exactly: a 25th-percentile score, a 75th-percentile score, an admitted
     * average. They are admitted rather than avoided because the alternative
     * is a model comparing a student's figure against English prose, which is
     * the arithmetic error this section exists to prevent; every one of them
     * arrives beside a sentence that says what it is, and
     * [ScoreBandCopy.NOT_A_CUT_OFF] says what it is not.
     */
    val NUMBERS_BY_CONTRACT: Set<String> = MeritAidWire.NUMERIC_KEYS + ScoreBandWire.NUMERIC_KEYS + setOf("count")

    /**
     * A CDS date as a phrase, never a pair of bare numbers. A month with no day
     * is legal CDS reporting -- "applications close in January" -- and says so
     * plainly rather than being completed with a guessed day. The cycle carries
     * no year, so no year is ever rendered into a date.
     */
    fun datePhrase(date: CdsMonthDay): String = date.phrase

    /** The offered flag and its words, emitted together so the boolean is never alone in the model's context. */
    fun offeredLabel(round: DeadlineRound): String =
      if (round.offered) {
        "this school offers ${round.round.label}"
      } else {
        "this school does not offer ${round.round.label}"
      }

    // The ethos contract rides the tool description (RFC 148): each school's
    // own document, the denominator said in full, and the two silences named.
    //
    // TWO lines below name IPEDS and carry `money-attribution-exempt` markers
    // (RFC 177): the ban was on naming the publisher of a canonical MONEY row
    // in a live string, and a school's admission-test policy is not a money
    // figure. The markers are VESTIGIAL as of RFC 231 -- the source-text sweep
    // that read them is deleted, and D7's surviving half is a compile-time one
    // (`MoneySourceCopy`'s `when`s with no `else`) that no comment can arm or
    // disarm. They are left in place and recorded as a follow-up, so nothing
    // here depends on where the string literals are split.
    val DESCRIPTION =
      "Read what the colleges on the student's list published about themselves in their own Common Data Set: " +
        "the non-need (merit) aid they give, what the admission office says it weighs, and their application " +
        "rounds and deadlines, plus what each school " +
        "told the federal IPEDS " + // money-attribution-exempt: a test policy is not a money figure
        "admissions survey about admission test " +
        "scores. Always attribute the figures to the school's own Common Data Set - each section " +
        "carries its own source with the exact words to cite - and never estimate: when a section is named in " +
        "data_availability, that school does not report it, so say so plainly. " +
        "${MeritAidWire.SHARE_KEY} is a share of ALL full-time freshmen at that school, not of the students " +
        "who pay full price and not of any smaller group - say it the way ${MeritAidWire.SHARE_LABEL_KEY} says it, " +
        "and never as a " +
        "share of students with no financial need, which no school reports. " +
        "${MeritAidWire.AVERAGE_KEY} is what last year's recipients averaged; it is not an offer to this " +
        "student, so never subtract it from a published price. A round with offered false is the school stating " +
        "it does not run that round - tell the student that, it is a real answer. Dates come with no year " +
        "because the schools publish none; read them as given. " +
        "${AdmissionsField.TEST_POLICY.wireName} is what THAT school reported " +
        "to the IPEDS " + // money-attribution-exempt: the same test policy, not a money figure
        "admissions survey in " +
        "the year its source cites, and nothing else: say the year, say the confirm_with_school sentence with " +
        "it, and never carry one school's policy over to another school on the list. When " +
        "${AdmissionsField.TEST_POLICY.wireName} is named in data_availability, ${TestPolicyCopy.NOT_REPORTED} " +
        "${AdmissionsField.SCORE_BANDS.wireName} is what THAT school's admitted class actually scored: each band " +
        "is the middle half of admitted students, so say the cohort the source cites with the numbers, always say " +
        "the ${ScoreBandWire.NOT_A_CUT_OFF_KEY} sentence with them, and never turn a band into a chance of " +
        "admission or into a score the student has to reach. Never carry one school's band to another school on " +
        "the list. When a school publishes no SAT band you USUALLY get ${ScoreBandWire.ADMITTED_AVERAGE_KEY} " +
        "instead - usually and not always, because we hold no average for some of those schools - and it is an " +
        "average and never a threshold: say it the way ${ScoreBandWire.ADMITTED_AVERAGE_LABEL_KEY} says it, " +
        "never subtract from it, never compare a student to it, and never present it as a range. If " +
        "${ScoreBandWire.ADMITTED_AVERAGE_KEY} is absent we hold no average for that school, so say nothing " +
        "about one. You will never get an average and a band for the same test. When " +
        "${AdmissionsField.SCORE_BANDS.wireName} is named in data_availability, ${ScoreBandCopy.NOT_REPORTED} " +
        "Read-only: this tool changes nothing."
  }
}
