package ed.unicoach.coaching.testing

import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.Residency
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.coaching.putOffer
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.Share
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.models.AlternateEntry
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CommendedCutoffBasis
import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.FirstChoiceRule
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.NationalMeritCycle
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SemifinalistCutoffBasis
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

/**
 * THE SEVEN TIMING ANSWERS, as the model reads them: one per arm of
 * [CycleTiming], and no two alike.
 *
 * AN ENUM AND NOT A POOL OF `String` CONSTANTS, on the
 * [ed.unicoach.coaching.aid.StateGap] precedent one
 * corpus along: a package answer is type-legal wherever a bare token is, and
 * `putPackageHead(CYCLE_EXPIRED, ...)` compiled and would have emitted a package
 * answer this tool's own DESCRIPTION never declares for a package.
 */
enum class TimingAnswer(
  /** The code the wire carries. */
  val value: String,
) {
  WINDOW_AHEAD("window_ahead"),
  WINDOW_OPEN("window_open"),
  ALTERNATE_ENTRY_OPEN("alternate_entry_open"),

  /** The window has closed and the letter route is published without a date. NOT closed. */
  ALTERNATE_ENTRY_UNDATED("alternate_entry_undated"),
  CYCLE_CLOSED("cycle_closed"),
  CYCLE_EXPIRED("cycle_expired"),

  /** We hold no cycle row for her class. The same WORD as the package half's, and a different vocabulary. */
  NOT_CURATED("not_curated"),
}

/**
 * THE FIVE PER-SCHOOL ANSWERS, the second closed vocabulary on this wire.
 *
 * [NOT_CURATED] repeats the token [TimingAnswer.NOT_CURATED] carries
 * deliberately: it is the same fact about our data, said about a school instead
 * of about a cycle. It is a MEMBER OF EACH rather than one constant standing for
 * two facts, so a reader of either one knows which vocabulary it is in.
 */
enum class PackageAnswer(
  /** The code the wire carries. */
  val value: String,
) {
  AUTOMATIC("automatic"),
  COMPETITIVE("competitive"),
  NONE_PUBLISHED("none_published"),
  EXPIRED("expired"),
  NOT_CURATED("not_curated"),
}

/**
 * The PSAT/NMSQT and National Merit chat tool (RFC 243): what this test is, when
 * it is for THIS student, what a National Merit standing is worth at the schools
 * on her list, and the base rate every one of those figures has to be said with.
 *
 * IT IS A MONEY TOOL AND NOT AN ADMISSIONS ONE. No college ever sees a PSAT
 * score -- the corpus stores the administrator's own sentence saying so -- and
 * nothing on this wire is a score, a prediction or a qualifying cutoff.
 *
 * A thin adapter, the house shape [AdmissionTestTimelineChatTool] states: this
 * class parses, reads and RENDERS. Every rule -- which timing arm she is in,
 * which of the five answers a school gets, whether a price may be spoken at all
 * -- lives in [NationalMeritService], and every word the coach reads is authored
 * here. THIS CLASS DOES NO ARITHMETIC OVER DATES: it holds no clock and compares
 * no day.
 *
 * THE BASE RATE RIDES WITH THE MONEY, BY TYPE AND NOT BY CONVENTION. A dollar
 * figure and a publisher's price in words exist on this wire only inside a
 * [PublishedPackage.Priced], which cannot be built without a
 * [PublishedBaseRate], and the head's `base_rate` is the SAME value those
 * packages were built from ([NationalMeritReading.baseRate]), carried on the
 * reading rather than read a second time. So a payload naming six figures and no
 * published Finalist share is not a defect to test for; it is a value nothing
 * can construct -- and the head states the corpus's base rate even when no
 * package is priced, because the cycle object has figures of its own.
 *
 * NOTHING HERE RANKS, SORTS, TOTALS OR COMPARES TWO SCHOOLS' MONEY, and there is
 * no "best award" key for a reader to find. Half the paying schools in this
 * corpus publish no number at all, so there is no ordering to compute -- and RFC
 * 239's review found exactly that defect shipping as a $0 ranking of a school's
 * top award. Each package is served in the corpus's own key order and priced only
 * in the terms its own publisher used.
 *
 * CITE ONCE PER BLOCK (RFC 190). The cycle object carries its two publishers and
 * the day we read them once; each package object carries its publisher, its page
 * and its own reading day once. Nothing is cited per figure.
 *
 * IT EMITS DOLLAR FIGURES AND FUNNEL COUNTS, which [AdmissionTestTimelineChatTool]
 * never does: they are numbers BY CONTRACT ([NUMBERS_BY_CONTRACT]), declared here
 * so the RFC 143 guard knows they are intended rather than a source code that
 * leaked. Every date is an ISO string and every year is a string, so a year
 * arriving as a number fails that guard.
 */
class NationalMeritChatTool(
  service: NationalMeritService,
  moneyProfiles: MoneyProfileService,
  /**
   * The corpus read, as the same kind of SEAM `fafsa_deadlines` gives its own
   * service call: TWO of this tool's branches cannot be produced on demand from a
   * migrated database, and both of them decide what a family is told about money
   * -- a read that FAULTS, and a reading whose base rate is unavailable, which
   * Route A's migration seed makes unreachable by construction. A function-typed
   * parameter makes them testable and adds nothing else.
   */
  private val read: suspend (StudentId, List<CollegeId>?, Residency) -> Result<NationalMeritReading> =
    { studentId, collegeIds, residency -> service.getForStudent(studentId, collegeIds, residency) },
  /**
   * The profile read, as the SEAM [ed.unicoach.coaching.ResidencyScopedChatTool]
   * and `fafsa_deadlines` already use it: the one branch a Postgres-backed test
   * cannot ask for on demand is a read that FAULTS, and that branch decides
   * whether a database fault turns a working answer into an error or serves the
   * cycle and the packages with a labelled residency gap.
   */
  private val readProfile: suspend (StudentId) -> Result<GetMoneyProfileResult> = moneyProfiles::getForStudent,
) : StudentScopedChatTool() {
  override val name: String = TOOL_NAME

  override val definition: JsonObject =
    buildJsonObject {
      put("name", TOOL_NAME)
      put("description", DESCRIPTION)
      putJsonObject("input_schema") { putCollegeIdsSchema() }
    }

  /**
   * THE RESIDENCY IS NOT AN INPUT FIELD, and the college subset is the only
   * argument. The family's state comes from `money_profiles` and must not be
   * something the model can contradict, which is the standing rule for every
   * residency-scoped door here: Auburn publishes its package to Alabama
   * residents only, and a state the model supplied would decide whose award that
   * is.
   */
  override suspend fun execute(
    studentId: StudentId,
    input: JsonObject,
  ): JsonObject {
    val collegeIds =
      when (val parsed = readCollegeIds(input)) {
        is CollegeIdsInput.Ok -> parsed.collegeIds
        is CollegeIdsInput.Invalid -> return errorObject(parsed.reason)
      }

    val residency = Residency.forStudent(studentId, TOOL_NAME, readProfile)
    val reading =
      read(studentId, collegeIds, residency)
        .getOrElse { e -> return readFailure(studentId, collegeIds, e) }

    return readingObject(reading, residency)
  }

  /**
   * The read FAULTED: the tool and the scope to the model, the identifiers to
   * the log.
   *
   * THE TOOL AND THE SCOPE, AND NOTHING ABOUT THE STUDENT: a transcript cannot
   * recover which tool spoke from "read failed", and the identifiers stay in the
   * log.
   *
   * ONE PROPERTY PER FACT in that log: `college_ids` always logs as ids, and
   * "was a subset asked for at all" is its own boolean -- null means the whole
   * active list, which is a different call from a three-school one.
   */
  private fun readFailure(
    studentId: StudentId,
    collegeIds: List<CollegeId>?,
    cause: Throwable,
  ): JsonObject {
    logger.warn(
      "tool [{}] national-merit read failed for student=[{}] college_ids=[{}] whole_active_list=[{}]",
      TOOL_NAME,
      studentId.value,
      collegeIds?.map { it.value }.orEmpty(),
      collegeIds == null,
      cause,
    )
    return errorObject(
      "$TOOL_NAME could not read the National Merit corpus for " +
        (collegeIds?.let { "the [${it.size}] requested colleges" } ?: "this student's whole active list"),
    )
  }

  /**
   * THE WHOLE ANSWER: her own two facts, her cycle, the base rate every figure
   * below rides with, her schools, and what this tool will not say.
   *
   * `base_rate` and `unknown_college_ids` are present even when there is nothing
   * in them, an explicit null and an empty array included: an absent key reads
   * as a tool that never had one to state.
   */
  private fun readingObject(
    reading: NationalMeritReading,
    residency: Residency,
  ): JsonObject =
    buildJsonObject {
      // HER OWN YEAR OF SCHOOL AND HER OWN CLASS, as strings: both are derived
      // from the graduation date we stored at signup, and a bare 2028 is a
      // number the model would do arithmetic on.
      put("year_of_school", reading.yearOfSchool.label)
      put("graduation_year", reading.graduationYear.toString())
      put("cycle_answer", timingObject(reading))
      put("base_rate", reading.baseRate?.let(::baseRateObject) ?: JsonNull)
      put("base_rate_why", if (reading.baseRate == null) NO_BASE_RATE_WHY else BASE_RATE_WHY)
      putColleges(reading.schools)
      putUnknownCollegeIds(reading.unknownCollegeIds)
      put("nothing_is_ranked", NOTHING_IS_RANKED)
      put("what_this_tool_cannot_say", WHAT_THIS_TOOL_CANNOT_SAY)
      // The invitation where nobody has asked which state they live in, and the
      // named reason where a state could not be used -- never both, and never on
      // a refusal (`putOffer`'s own rule).
      putOffer(residency)
    }

  /**
   * One object per requested school, in the list's own order.
   *
   * The service answers one row per (college, entry year, residency, standing);
   * THE GROUPING IS THE WIRE'S, so a school with two rows is one object with two
   * packages rather than two schools with one each.
   */
  private fun JsonObjectBuilder.putColleges(schools: List<NationalMeritSchoolAnswer>) =
    putJsonArray("colleges") {
      schools
        .groupBy { it.collegeId }
        .forEach { (collegeId, answers) -> add(collegeObject(collegeId, answers)) }
    }

  /**
   * Requested ids that are not on this student's active list -- REPORTED rather
   * than silently dropped, and an empty array rather than an absent key, which
   * would read as a tool that never checked.
   */
  private fun JsonObjectBuilder.putUnknownCollegeIds(ids: List<CollegeId>) =
    putJsonArray("unknown_college_ids") { ids.forEach { add(JsonPrimitive(it.value.toString())) } }

  /**
   * WHERE THIS STUDENT STANDS IN HER CYCLE: which of the seven arms, why, and
   * the cycle row itself.
   *
   * An exhaustive `when` with no `else`, so an eighth arm cannot reach a family
   * as a silent omission. `not_curated` and `cycle_expired` carry an explicit
   * null cycle rather than an absent key, and name no date at all -- the second
   * because a window read off a page we stopped believing is exactly the date
   * nobody may act on.
   */
  private fun timingObject(reading: NationalMeritReading): JsonObject =
    buildJsonObject {
      when (val timing = reading.timing) {
        is CycleTiming.NotCurated -> {
          put("answer", TimingAnswer.NOT_CURATED.value)
          put("why", notCuratedWhy(timing.graduationYear))
          put("cycle", JsonNull)
        }

        is CycleTiming.CycleExpired -> {
          put("answer", TimingAnswer.CYCLE_EXPIRED.value)
          put("why", cycleExpiredWhy(timing.cycle, timing.expiredOn))
          // NO CELL OF A DEAD ROW REACHES THE WIRE, not even its window in
          // words: the citation is in the sentence, so a family is sent to the
          // publishers' own pages and to no date of ours.
          put("cycle", JsonNull)
        }

        is CycleTiming.WindowAhead -> {
          put("answer", TimingAnswer.WINDOW_AHEAD.value)
          put("why", windowAheadWhy(timing.cycle, reading.yearOfSchool))
          put("cycle", cycleObject(timing.cycle))
        }

        is CycleTiming.WindowOpen -> {
          put("answer", TimingAnswer.WINDOW_OPEN.value)
          put("why", windowOpenWhy(timing.cycle, timing.window))
          put("cycle", cycleObject(timing.cycle))
        }

        is CycleTiming.AlternateEntryOpen -> {
          put("answer", TimingAnswer.ALTERNATE_ENTRY_OPEN.value)
          put("why", alternateEntryOpenWhy(timing.alternateEntry, timing.deadline))
          put("cycle", cycleObject(timing.cycle))
        }

        is CycleTiming.AlternateEntryUndated -> {
          put("answer", TimingAnswer.ALTERNATE_ENTRY_UNDATED.value)
          put("why", alternateEntryUndatedWhy(timing.cycle, timing.alternateEntry))
          put("cycle", cycleObject(timing.cycle))
        }

        is CycleTiming.CycleClosed -> {
          put("answer", TimingAnswer.CYCLE_CLOSED.value)
          put("why", cycleClosedWhy(timing.cycle, reading.yearOfSchool))
          put("cycle", cycleObject(timing.cycle))
        }
      }
    }

  /**
   * The cycle row as its publishers state it: the window, the alternate-entry
   * route, what KIND of number the score is, HOW each bar is set, the sentence
   * saying no college sees the score, and NMSC's own award.
   *
   * THERE IS NO CUTOFF KEY, and that is the design. NMSC allocates Semifinalist
   * standing state-representationally, publishes no qualifying score in advance
   * and says outright that the score varies by state and by year, so what rides
   * here is how the bar is SET plus its own sentence saying it is not published.
   * `selection_index_low`/`high` are the scale the score is reported on and are
   * carried with [SELECTION_INDEX_WHY] saying so in the same object.
   */
  private fun cycleObject(cycle: NationalMeritCycle): JsonObject =
    buildJsonObject {
      put("competition_year", cycle.competitionYear.toString())
      put("qualifying_grade", cycle.qualifyingGrade)
      put("qualifying_grade_why", QUALIFYING_GRADE_WHY)
      putWindow(cycle.window)
      put("alternate_entry", cycle.alternateEntry?.let(::alternateEntryObject) ?: JsonNull)
      put("alternate_entry_why", if (cycle.alternateEntry == null) NO_ALTERNATE_ENTRY_WHY else ALTERNATE_ENTRY_WHY)
      put("selection_index_low", cycle.selectionIndex.low)
      put("selection_index_high", cycle.selectionIndex.high)
      put("selection_index_why", SELECTION_INDEX_WHY)
      put("semifinalist_bar_basis", cycle.semifinalistCutoff.value)
      put("semifinalist_bar_basis_why", semifinalistBasisWhyOf(cycle.semifinalistCutoff))
      put("commended_bar_basis", cycle.commendedCutoff.value)
      put("commended_bar_basis_why", commendedBasisWhyOf(cycle.commendedCutoff))
      put("qualifying_score_as_published", cycle.cutoffAsPublished)
      put("qualifying_score_why", QUALIFYING_SCORE_WHY)
      put("score_visibility_as_published", cycle.scoreVisibilityAsPublished)
      put("score_visibility_why", SCORE_VISIBILITY_WHY)
      put("nmsc_own_award_usd", cycle.nmscAward?.amountUsd?.let(::JsonPrimitive) ?: JsonNull)
      put("nmsc_own_award_as_published", cycle.nmscAward?.asPublished?.let(::JsonPrimitive) ?: JsonNull)
      put("nmsc_sponsor_cap_as_published", cycle.sponsorCapAsPublished?.let(::JsonPrimitive) ?: JsonNull)
      put("whose_money_why", WHOSE_MONEY_WHY)
      // TWO PUBLISHERS, CITED SEPARATELY, because no page holds all of this row:
      // NMSC sponsors the competition and publishes the entry rule, the letter
      // route and the funnel, while College Board administers the test and is the
      // only publisher of the window dates, the scale and the sentence saying a
      // college never receives the score. One citation would attribute four of
      // these cells to the wrong publisher.
      put("sponsor_publisher", cycle.sponsor.publisher)
      put("sponsor_source_url", cycle.sponsor.sourceUrl)
      put("sponsor_archive_url", cycle.sponsor.archiveUrl?.let(::JsonPrimitive) ?: JsonNull)
      put("administrator_publisher", cycle.administrator.publisher)
      put("administrator_source_url", cycle.administrator.sourceUrl)
      put("administrator_archive_url", cycle.administrator.archiveUrl?.let(::JsonPrimitive) ?: JsonNull)
      put("checked_on", cycle.checkedOn.toString())
      put("citation_note", CYCLE_CITATION_NOTE)
    }

  /**
   * The window: DATES ONLY WHERE THE PUBLISHER PRINTS THEM, and its own words
   * always.
   *
   * ONE `when` OVER THE SEALED WINDOW and not two null tests, because
   * [CycleWindow.Described] is the NORMAL shape two years out -- measured, not
   * assumed: no publisher has dated any cycle after 2028. A described window's
   * two date keys are explicit nulls, so a model reading it sees a window
   * nobody has dated rather than one we failed to read.
   */
  private fun JsonObjectBuilder.putWindow(window: CycleWindow) {
    when (window) {
      is CycleWindow.Dated -> {
        put("window_opens_on", window.opensOn.toString())
        put("window_closes_on", window.closesOn.toString())
        put("window_dated", true)
      }

      is CycleWindow.Described -> {
        put("window_opens_on", JsonNull)
        put("window_closes_on", JsonNull)
        put("window_dated", false)
      }
    }
    put("window_as_published", window.asPublished)
    put("window_why", windowWhyOf(window))
  }

  /** The letter route for a missed sitting, and its own deadline -- the reason "gone forever" is false. */
  private fun alternateEntryObject(entry: AlternateEntry): JsonObject =
    buildJsonObject {
      put("deadline", entry.deadline?.toString()?.let(::JsonPrimitive) ?: JsonNull)
      put("deadline_said", entry.deadline?.let { JsonPrimitive(UsDate.of(it)) } ?: JsonNull)
      put("contact", entry.contact?.let(::JsonPrimitive) ?: JsonNull)
      put("as_published", entry.asPublished)
    }

  /**
   * The base rate, and the competition year it BELONGS to -- named, in the same
   * object, because that is the whole contract.
   *
   * `finalist_share_of_entrants_percent` is DERIVED at read time from two of
   * these counts and is labelled as derived; it is not a stored column and
   * nothing anywhere stores one. It rides as the PERCENT its own key names,
   * from the one [Share] the sentence beside it is spoken from, so the figure
   * and the words cannot be on two scales.
   */
  private fun baseRateObject(baseRate: PublishedBaseRate): JsonObject =
    buildJsonObject {
      put("competition_year", baseRate.competitionYear.toString())
      put("said_as", saidAs(baseRate.competitionYear))
      putFunnel(baseRate.funnel)
      put("finalist_share_of_entrants_percent", baseRate.finalistShareOfEntrants?.let { JsonPrimitive(it.percent) } ?: JsonNull)
      put("finalist_share_said", baseRate.finalistShareOfEntrants?.let { JsonPrimitive(sharePercent(it)) } ?: JsonNull)
      put("finalist_share_derived_from", FINALIST_SHARE_DERIVED_FROM)
      put("funnel_as_published", baseRate.funnel.asPublished)
    }

  /** Every published stage, each an explicit null where its publisher rounds or omits it. */
  private fun JsonObjectBuilder.putFunnel(funnel: NationalMeritFunnel) {
    put("test_takers", funnel.testTakers?.let(::JsonPrimitive) ?: JsonNull)
    put("met_entry_requirements", funnel.metEntryRequirements?.let(::JsonPrimitive) ?: JsonNull)
    put("high_scorers_screened", funnel.highScorersScreened?.let(::JsonPrimitive) ?: JsonNull)
    put("commended", funnel.commended?.let(::JsonPrimitive) ?: JsonNull)
    put("semifinalists", funnel.semifinalists?.let(::JsonPrimitive) ?: JsonNull)
    put("finalists", funnel.finalists?.let(::JsonPrimitive) ?: JsonNull)
    put("winners", funnel.winners?.let(::JsonPrimitive) ?: JsonNull)
  }

  /** One requested school: its id, and every package this school publishes for it. */
  private fun collegeObject(
    collegeId: CollegeId,
    answers: List<NationalMeritSchoolAnswer>,
  ): JsonObject =
    buildJsonObject {
      put("college_id", collegeId.value.toString())
      putJsonArray("packages") { answers.forEach { add(packageObject(it)) } }
    }

  /**
   * ONE PACKAGE, and WHICH OF THE FIVE ANSWERS IT IS -- an exhaustive `when` with
   * no `else`, so a sixth arm cannot reach a family as a silent omission.
   *
   * The head is the same on every arm ([putPackageHead]), including the arm with
   * least to say: a package object missing `publisher` and `checked_on` reads as
   * a renderer that forgot them rather than as the honest "we have not read this
   * school" it is.
   *
   * MONEY EXISTS ON TWO ARMS ONLY, and on both of them it comes out of a
   * [PublishedPackage] that carries the base rate. `expired` states no price at
   * all -- not the figures and not the publisher's words, because the words ARE
   * the price and a stale page's price is not one to quote.
   */
  private fun packageObject(answer: NationalMeritSchoolAnswer): JsonObject =
    buildJsonObject {
      when (answer) {
        is NationalMeritSchoolAnswer.NotCurated -> {
          putPackageHead(PackageAnswer.NOT_CURATED, award = null, residencyScope = null, why = NOT_CURATED_SCHOOL_WHY)
          putNoStanding()
          putNoPrice(NOT_CURATED_PRICE_WHY)
          putNoConditions()
        }

        is NationalMeritSchoolAnswer.NonePublished -> {
          putPackageHead(PackageAnswer.NONE_PUBLISHED, answer.award, answer.residencyScope, nonePublishedWhy(answer.award))
          putNoStanding()
          putNoPrice(NONE_PUBLISHED_PRICE_WHY)
          // The sentence that PROVES the absence, non-null by type: it rides on
          // the arm's own claim, so there is nothing here to coalesce.
          put("absence_as_published", answer.claim.asPublished)
          putNoConditions()
        }

        is NationalMeritSchoolAnswer.Expired -> {
          putPackageHead(PackageAnswer.EXPIRED, answer.award, answer.residencyScope, expiredWhy(answer.award, answer.expiredOn))
          put("expired_on", answer.expiredOn.toString())
          putStanding(payingOf(answer.award)?.standing)
          putNoPrice(EXPIRED_PRICE_WHY)
          putNoConditions()
        }

        is NationalMeritSchoolAnswer.Automatic -> {
          putPackageHead(PackageAnswer.AUTOMATIC, answer.award, answer.residencyScope, automaticWhy(answer.award, answer.standing))
          putStanding(answer.standing)
          putPrice(answer.published)
          putConditions(answer.claim)
        }

        is NationalMeritSchoolAnswer.Competitive -> {
          putPackageHead(PackageAnswer.COMPETITIVE, answer.award, answer.residencyScope, competitiveWhy(answer.award, answer.standing))
          putStanding(answer.standing)
          putPrice(answer.published)
          putConditions(answer.claim)
        }
      }
    }

  /**
   * The head every package object carries: WHICH answer this is, whose award it
   * is, and the ONE citation for everything below it (RFC 190).
   *
   * Every key is present on every arm, as an explicit null where we hold
   * nothing. `checked_on` is the day a person read the page and is never the day
   * the page was published -- two different claims, and only one of them is ours
   * to make.
   */
  private fun JsonObjectBuilder.putPackageHead(
    answer: PackageAnswer,
    award: NationalMeritAward?,
    residencyScope: ResidencyScope?,
    why: String,
  ) {
    put("answer", answer.value)
    put("why", why)
    // The AcademicYear label and never a bare year: 2027 alone is a number the
    // model would do its own arithmetic on, and this one is an ENTRY year.
    put("entry_year", award?.entryYear?.label?.let(::JsonPrimitive) ?: JsonNull)
    put("residency_basis", award?.residencyBasis?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("residency_basis_label", award?.let { JsonPrimitive(residencyLabelOf(it)) } ?: JsonNull)
    put("residency_scope", residencyScope?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("residency_scope_why", residencyScope?.let { JsonPrimitive(residencyScopeWhyOf(it)) } ?: JsonNull)
    put("award_name", payingOf(award)?.awardName?.let(::JsonPrimitive) ?: JsonNull)
    // THE ABSENCE'S OWN WORDS, on every arm, because every key is on every arm:
    // the `none_published` branch overwrites this null with the sentence that
    // proves the absence, and nowhere else can have one -- a paying row carrying
    // absence words is a row the schema refuses.
    put("absence_as_published", JsonNull)
    put("publisher", award?.publisher?.let(::JsonPrimitive) ?: JsonNull)
    put("source_url", award?.sourceUrl?.let(::JsonPrimitive) ?: JsonNull)
    put("archive_url", award?.archiveUrl?.let(::JsonPrimitive) ?: JsonNull)
    put("checked_on", award?.checkedOn?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("citation_note", if (award == null) JsonNull else JsonPrimitive(PACKAGE_CITATION_NOTE))
    // `expired_on` is a key of the expired arm alone and is an explicit null
    // everywhere else, so a reader never has to ask whether an absent key means
    // "still believed" or "the renderer forgot".
    put("expired_on", JsonNull)
  }

  /** WHICH standing this school pays, where it pays one. */
  private fun JsonObjectBuilder.putStanding(standing: QualifyingStanding?) {
    put("qualifying_standing", standing?.value?.let(::JsonPrimitive) ?: JsonNull)
    put("qualifying_standing_label", standing?.let { JsonPrimitive(standingLabelOf(it)) } ?: JsonNull)
  }

  /** No standing at all: a school we never read, and a school we read that pays nothing, name none. */
  private fun JsonObjectBuilder.putNoStanding() = putStanding(null)

  /**
   * THE PRICE, AND THE BASE RATE, FROM ONE VALUE.
   *
   * The `when` is the contract: [PublishedPackage.Priced] is the only arm with
   * money on it and it carries the base rate it must be said with, so the four
   * money keys and `base_rate_competition_year` are written by the same branch or
   * none of them is written at all.
   */
  private fun JsonObjectBuilder.putPrice(published: PublishedPackage) {
    when (published) {
      is PublishedPackage.Priced -> {
        put("award_per_year_usd", published.perYearUsd?.let(::JsonPrimitive) ?: JsonNull)
        put("award_total_usd", published.totalUsd?.let(::JsonPrimitive) ?: JsonNull)
        put("award_years", published.years?.let(::JsonPrimitive) ?: JsonNull)
        put("award_as_published", published.asPublished)
        put("base_rate_competition_year", published.baseRate.competitionYear.toString())
        put("price_why", priceWhyOf(published))
      }

      is PublishedPackage.Withheld -> {
        putNoPrice(WITHHELD_PRICE_WHY)
      }
    }
  }

  /**
   * No price on this arm, and the named reason there is none.
   *
   * ALL FIVE KEYS, EXPLICITLY NULL, INCLUDING `base_rate_competition_year`: a
   * payload where a money key is absent rather than null is one a reader can
   * mistake for a renderer that dropped it, and this wire's one structural
   * promise is that money and the base-rate year appear together or not at all.
   */
  private fun JsonObjectBuilder.putNoPrice(why: String) {
    put("award_per_year_usd", JsonNull)
    put("award_total_usd", JsonNull)
    put("award_years", JsonNull)
    put("award_as_published", JsonNull)
    put("base_rate_competition_year", JsonNull)
    put("price_why", why)
  }

  /**
   * What the school asks BESIDE the standing: NMSC's first-choice designation
   * with its own deadline, and the school's own application deadline.
   *
   * FIRST CHOICE IS THE DIFFERENCE BETWEEN AN AWARD AND A COMMITMENT -- naming
   * one college first choice with NMSC is an act with consequences elsewhere --
   * so it is never folded into the price.
   */
  private fun JsonObjectBuilder.putConditions(claim: NationalMeritClaim.Paying) {
    put("first_choice", claim.firstChoice?.let(::firstChoiceObject) ?: JsonNull)
    put("first_choice_why", claim.firstChoice?.let { JsonPrimitive(firstChoiceWhyOf(it)) } ?: JsonPrimitive(NO_FIRST_CHOICE_WHY))
    put(
      "apply_deadline",
      claim.applyDeadline
        ?.on
        ?.toString()
        ?.let(::JsonPrimitive) ?: JsonNull,
    )
    put("apply_deadline_said", claim.applyDeadline?.let { JsonPrimitive(UsDate.of(it.on)) } ?: JsonNull)
    put("apply_deadline_as_published", claim.applyDeadline?.asPublished?.let(::JsonPrimitive) ?: JsonNull)
  }

  /**
   * The PAYING claim on one row, or null where the row is a verified absence
   * (and where there is no row at all).
   *
   * The two keys that read it -- `award_name` on every arm's head, and the
   * standing on the `expired` arm -- are owed by a row whichever claim it
   * states, and only one of the two claims has them. A `when` and not a cast, so
   * a third arm cannot compile until this says what it is.
   */
  private fun payingOf(award: NationalMeritAward?): NationalMeritClaim.Paying? =
    when (val claim = award?.claim) {
      is NationalMeritClaim.Paying -> claim
      is NationalMeritClaim.NonePublished, null -> null
    }

  /** No conditions to state: an absence, a stale reading and a school we never read all publish none. */
  private fun JsonObjectBuilder.putNoConditions() {
    put("first_choice", JsonNull)
    put("first_choice_why", NO_CONDITIONS_WHY)
    put("apply_deadline", JsonNull)
    put("apply_deadline_said", JsonNull)
    put("apply_deadline_as_published", JsonNull)
  }

  /** NMSC's first-choice mechanism at one school, in the school's own words. */
  private fun firstChoiceObject(rule: FirstChoiceRule): JsonObject =
    buildJsonObject {
      // THE DEADLINE IS A KEY OF THE REQUIRED ARM ALONE, and an explicit null on
      // the other: the arms carry what the schema admits, and `required` is read
      // off which arm this is rather than from a flag beside the date.
      //
      // AN EXHAUSTIVE `when` AND NOT A CAST, [payingOf]'s rule: a third rule arm
      // read by `is` would ship as `required: false`, telling a family a
      // designation that costs them every other school on their list is not
      // required.
      when (rule) {
        is FirstChoiceRule.Required -> {
          put("required", true)
          put("deadline", rule.deadline?.toString()?.let(::JsonPrimitive) ?: JsonNull)
          put("deadline_said", rule.deadline?.let { JsonPrimitive(UsDate.of(it)) } ?: JsonNull)
        }

        is FirstChoiceRule.NotRequired -> {
          put("required", false)
          put("deadline", JsonNull)
          put("deadline_said", JsonNull)
        }
      }
      put("as_published", rule.asPublished)
    }

  companion object {
    private val logger = LoggerFactory.getLogger(NationalMeritChatTool::class.java)

    const val TOOL_NAME = "psat_national_merit"

    /**
     * THE NUMBERS THIS TOOL MEANS TO EMIT, and the only ones the RFC 143 guard
     * will pass.
     *
     * The money, the funnel counts, the derived share, NMSC's own award, the
     * Selection Index scale and the qualifying grade. Everything else is a
     * string -- every date is ISO, every year is a string or an
     * [ed.unicoach.common.util.AcademicYear] label -- so a date or a year
     * arriving as a number fails the guard, which is the point of naming these.
     *
     * NO QUALIFYING SCORE IS IN THIS SET, because no key carries one: the
     * Selection Index bounds are the SCALE the score is reported on, published
     * with [SELECTION_INDEX_WHY] beside them, and nothing on this wire is a bar
     * a student has to clear.
     */
    val NUMBERS_BY_CONTRACT: Set<String> =
      setOf(
        "award_per_year_usd",
        "award_total_usd",
        "award_years",
        "nmsc_own_award_usd",
        "qualifying_grade",
        "selection_index_low",
        "selection_index_high",
        "test_takers",
        "met_entry_requirements",
        "high_scorers_screened",
        "commended",
        "semifinalists",
        "finalists",
        "winners",
        "finalist_share_of_entrants_percent",
      )

    /** The one sentence that says what a cycle object's citations cover (RFC 190). */
    const val CYCLE_CITATION_NOTE: String =
      "This cycle has TWO publishers and both are stated once for the whole object: the sponsor publishes the entry " +
        "rule, the letter route, the funnel and the sentence about the qualifying score, and the administrator " +
        "publishes the window dates, the score scale and the sentence about who sees the score. checked_on is the " +
        "day a person opened those pages."

    /** And the same rule one object along, for a school's package. */
    const val PACKAGE_CITATION_NOTE: String =
      "The publisher, the page and the day we read it are stated once for this whole package: every figure and " +
        "every quoted sentence below was read from that one page on that one day."

    /** What the base rate IS, and the one rule that governs it. */
    const val BASE_RATE_WHY: String =
      "This is the published funnel of ONE named competition year, and it is the counterweight to every figure in " +
        "this payload: finalist_share_said is how many of the students who meet the entry requirements reach " +
        "Finalist, read off these counts and never from memory. It is stated for the most recent competition that " +
        "published one, which is named in competition_year and in said_as, and it is NOT this student's own year " +
        "unless those years match."

    /** And what its absence is: our corpus, never a claim that no base rate exists. */
    const val NO_BASE_RATE_WHY: String =
      "No cycle we hold publishes a funnel at all, so we state no base rate -- and because we cannot state one, " +
        "no school's price is stated either (price_why says so on each package). It is the FUNNEL that is " +
        "missing and never this student's list: a student with no priced package, or no colleges at all, still " +
        "gets the base rate above. This is a gap in OUR data and never a statement about the competition."

    /** Which two stages the derived share is over -- stated because it is computed at read time and stored nowhere. */
    const val FINALIST_SHARE_DERIVED_FROM: String =
      "finalists divided by met_entry_requirements, as a PERCENTAGE, computed when this answer was built and " +
        "stored nowhere. It is over the students who MET THE ENTRY REQUIREMENTS and not over everyone who sat " +
        "the test, because the entry requirements are a screen a student has already passed by the time this " +
        "figure is worth stating."

    /** The one thing this payload is not, said on the wire rather than left to the prompt. */
    const val NOTHING_IS_RANKED: String =
      "Nothing here is ranked, sorted, totalled or compared. Half the schools in this corpus publish a package in " +
        "words and no figure at all -- \"the full cost of tuition and required fees, for four years\" -- so there " +
        "is no number to order them by, and there is no best-award key: the packages are in the corpus's own key " +
        "order, which carries no meaning."

    /** The one thing this tool refuses to do. */
    const val WHAT_THIS_TOOL_CANNOT_SAY: String =
      "This tool states what the competition's publishers and a school's own page PUBLISH. It holds no qualifying " +
        "score, no prediction and no estimate of this student's chances beyond the published base rate, and a " +
        "school absent from colleges, or answering not_curated, has no National Merit answer here at all."

    /** Stored, never spoken from memory: the one sitting that counts is a published rule of the programme. */
    const val QUALIFYING_GRADE_WHY: String =
      "Only this year of school's sitting counts for National Merit; a sitting in any earlier year does not enter " +
        "the competition at all. It is stored from the programme's own page, so it is never arithmetic of ours."

    /** What the Selection Index IS. It is not a bar, and nothing on this wire is one. */
    const val SELECTION_INDEX_WHY: String =
      "These two numbers are the SCALE the National Merit screen reads -- the lowest and highest Selection Index " +
        "that can be reported -- so a family reading a figure knows what it is out of. Neither of them is a " +
        "qualifying score, and we hold no qualifying score for any state or any year: qualifying_score_as_published " +
        "is the publisher's own sentence saying it is not published in advance, and that sentence is the answer to " +
        "\"what do I need\"."

    /** And the same refusal stated on the key that answers the question. */
    const val QUALIFYING_SCORE_WHY: String =
      "The publisher's own words on the qualifying score. It publishes none in advance, the Semifinalist bar is " +
        "allocated per STATE rather than nationally, and it varies from year to year -- so no number anywhere in " +
        "our data is this student's bar, and no figure from any other source is either."

    /** The sentence that makes this a money event and not an admissions one. */
    const val SCORE_VISIBILITY_WHY: String =
      "The administrator's own sentence on who receives a PSAT/NMSQT score. No college ever sees it, so this test " +
        "is not part of any admission decision anywhere."

    /** Whose money the big numbers are (brief 0010 correction 3). */
    const val WHOSE_MONEY_WHY: String =
      "The sponsor's OWN award is the small one named in nmsc_own_award_usd, and the awards it funds at a college " +
        "are capped as nmsc_sponsor_cap_as_published states. Every large figure in the colleges array below is the " +
        "UNIVERSITY's own package, published by that university, and is not the sponsor's money."

    /** The letter route, where a cycle has one. */
    const val ALTERNATE_ENTRY_WHY: String =
      "A student who misses the one qualifying sitting through illness or emergency can still enter by writing to " +
        "the sponsor, and that letter has its own deadline. This is why a missed October is not the end of the " +
        "competition for her."

    /** And where a cycle describes none, which is a different fact from a route with no date. */
    const val NO_ALTERNATE_ENTRY_WHY: String =
      "This cycle's page describes no alternate entry route, so we hold none. That is what our data says about " +
        "this cycle and not a statement that no route exists -- the sponsor's own page is where that answer is."

    /** The sentence the words-only cycle needs, and the one the dated cycle needs. */
    fun windowWhyOf(window: CycleWindow): String =
      when (window) {
        is CycleWindow.Dated -> {
          "The administrator has DATED this cycle's window, and these are the days it opens and closes -- both " +
            "inclusive. The test is given inside the school day, so the school decides which day within the " +
            "window it holds and the student does not register for it herself."
        }

        is CycleWindow.Described -> {
          "The administrator has NOT dated this cycle: window_as_published is its own description of when the " +
            "window falls, and we hold no day for it. This is the normal state of a cycle more than a year out, " +
            "and no date can be derived from the description."
        }
      }

    /**
     * HOW the Semifinalist bar is set -- never what it is.
     *
     * ONE SENTENCE PER BAR AND NOT ONE FUNCTION OVER BOTH VOCABULARIES: the two
     * columns are two closed vocabularies ([SemifinalistCutoffBasis]), and a
     * shared renderer could reach a bar with the other bar's explanation.
     */
    fun semifinalistBasisWhyOf(basis: SemifinalistCutoffBasis): String =
      when (basis) {
        SemifinalistCutoffBasis.STATE_REPRESENTATIONAL -> {
          "This standing is allocated so that each state's share of it matches its share of graduating seniors, so " +
            "the bar is HER STATE's and no national figure is hers."
        }
      }

    /** And HOW the Commended bar is set, over its own vocabulary. */
    fun commendedBasisWhyOf(basis: CommendedCutoffBasis): String =
      when (basis) {
        CommendedCutoffBasis.NATIONAL -> {
          "This standing is set by one national number across every state."
        }
      }

    /**
     * We hold her cycle and stopped believing our reading of it. It names no
     * date either, and it says WHOSE pages to open.
     */
    fun cycleExpiredWhy(
      cycle: NationalMeritCycle,
      expiredOn: java.time.LocalDate,
    ): String =
      "We read ${cycle.sponsor.publisher}'s and ${cycle.administrator.publisher}'s pages for the " +
        "${cycle.competitionYear} competition on ${UsDate.of(cycle.checkedOn)} and stopped believing that " +
        "reading on ${UsDate.of(expiredOn)}, so we state no window, no deadline and no figures for her cycle " +
        "and name none. Those two pages are where today's dates are. This is OUR reading going stale and never " +
        "a competition that stopped running."

    /** We hold no cycle for her class. It names no date. */
    fun notCuratedWhy(graduationYear: Int): String =
      "We hold no National Merit cycle row for the class of $graduationYear, so we have no window, no deadline and " +
        "no figures for her year and we name none. This is a gap in OUR data: the competition runs for her year, " +
        "and the sponsor's and administrator's own pages are where its dates are."

    /**
     * The window is ahead of her.
     *
     * IT NAMES WHY it is ahead -- her year of school or the calendar -- because
     * a student three years out and a junior in September are in the same arm
     * for two different reasons.
     */
    fun windowAheadWhy(
      cycle: NationalMeritCycle,
      yearOfSchool: HighSchoolYear,
    ): String {
      val whenClause =
        when (val window = cycle.window) {
          is CycleWindow.Dated -> "It opens on ${UsDate.of(window.opensOn)} and closes on ${UsDate.of(window.closesOn)}."
          is CycleWindow.Described -> "Its publisher has not dated it: \"${window.asPublished}\"."
        }
      return "Her one qualifying sitting for the ${cycle.competitionYear} competition is still ahead of her, and on " +
        "our record ${yearOfSchool.clause} $whenClause It is given inside the school day, so her school decides the " +
        "day and she does not register for it herself."
    }

    /** It is happening now, and the arm hands over the dated window rather than being cast back to it. */
    fun windowOpenWhy(
      cycle: NationalMeritCycle,
      window: CycleWindow.Dated,
    ): String =
      "The window for her ${cycle.competitionYear} competition is OPEN today. It closes on " +
        "${UsDate.of(window.closesOn)}. Her school decides which day inside the window it holds the sitting and " +
        "she does not register for it herself, so the school is who to ask how it runs it."

    /** The window has closed and the letter is still in time. */
    fun alternateEntryOpenWhy(
      entry: AlternateEntry,
      deadline: java.time.LocalDate,
    ): String {
      val contact = entry.contact?.let { " The letter goes to $it." } ?: ""
      return "The in-school window has closed, and the alternate entry route is still open: the sponsor must have " +
        "the letter by ${UsDate.of(deadline)}.$contact In the publisher's own words: \"${entry.asPublished}\". " +
        "Nothing in our data says whether she sat the test, so this route is what the answer is, not a guess about " +
        "which of the two happened."
    }

    /**
     * The window has closed and the letter route is published WITHOUT a date.
     *
     * The honest arm: the route EXISTS and we cannot name its day, so the
     * sentence sends the family to the sponsor's own page and refuses to call
     * the cycle shut. This is the recovery route brief 0010 correction 1 exists
     * to name -- "miss it and it is gone forever" is false, and for an undated
     * cycle a closed answer would quietly make it true again.
     */
    fun alternateEntryUndatedWhy(
      cycle: NationalMeritCycle,
      entry: AlternateEntry,
    ): String {
      val contact = entry.contact?.let { " The letter goes to $it." } ?: ""
      return "The in-school window for her ${cycle.competitionYear} competition has closed, and the alternate " +
        "entry route is still described -- in the publisher's own words: \"${entry.asPublished}\" -- with NO " +
        "deadline published for it.$contact So this door may be open and we cannot say until when: " +
        "${cycle.sponsor.publisher}'s own page is where that date is, and this is NOT a cycle to call closed. " +
        "Nothing in our data says whether she sat the test, so the route is what the answer is, not a guess " +
        "about which of the two happened."
    }

    /** It is over, and no route is offered that has closed. */
    fun cycleClosedWhy(
      cycle: NationalMeritCycle,
      yearOfSchool: HighSchoolYear,
    ): String =
      "The ${cycle.competitionYear} competition's entry routes have both closed -- its window, and the letter " +
        "route where it published one -- and on our record ${yearOfSchool.clause} So there is nothing here to act " +
        "on: the money below, where a school publishes any, is what a Finalist of a LATER cycle would be paid, and " +
        "this cycle cannot be entered."

    /** We never read this school. */
    const val NOT_CURATED_SCHOOL_WHY: String =
      "We have not read this school's National Merit page, so we cannot say what it pays a Finalist -- and we " +
        "never estimate one. This is a gap in OUR data and never a statement that the school pays nothing: the " +
        "school's own scholarship page is where that answer is."

    /** A verified absence with a citation, which is a different answer from silence. */
    fun nonePublishedWhy(award: NationalMeritAward): String =
      "We read ${award.publisher}'s own page on ${UsDate.of(award.checkedOn)} and this school publishes NO National " +
        "Merit award at all. That is a verified absence with the page and the day beside it, and it is a different " +
        "fact from a school we have not read."

    /** The school guarantees it. */
    fun automaticWhy(
      award: NationalMeritAward,
      standing: QualifyingStanding,
    ): String =
      "${award.publisher}'s own page publishes this package for a student holding ${standingLabelOf(standing)}: no " +
        "committee, no separate contest and no further competition. The standing itself is the hard part, which is " +
        "what base_rate above is for."

    /** The school CONSIDERS her, and has promised nothing. */
    fun competitiveWhy(
      award: NationalMeritAward,
      standing: QualifyingStanding,
    ): String =
      "${award.publisher}'s own page says a student holding ${standingLabelOf(standing)} is CONSIDERED for this " +
        "package. It is not promised to her and it does not follow from the standing: the figures here are what " +
        "the school publishes for the award, not money she would receive for qualifying."

    /** A stale reading: a page somebody has to open again, and no price spoken from it. */
    fun expiredWhy(
      award: NationalMeritAward,
      expiredOn: java.time.LocalDate,
    ): String =
      "We read ${award.publisher}'s page on ${UsDate.of(award.checkedOn)} and stopped believing that reading on " +
        "${UsDate.of(expiredOn)}, so this school's package is not stated here. The school may still publish one, " +
        "and its own page is where today's answer is. This is our reading going stale and never a school " +
        "withdrawing an award."

    /** Why there is no price on the three arms that have none, one sentence each. */
    const val NOT_CURATED_PRICE_WHY: String = "We have not read this school, so we hold no package and state no price for it."
    const val NONE_PUBLISHED_PRICE_WHY: String = "This school publishes no National Merit award, so there is no price to state."
    const val EXPIRED_PRICE_WHY: String =
      "Our reading of this page has gone stale, so no figure and no quoted sentence from it is stated -- the " +
        "publisher's own words are the price, so quoting them would be quoting a price we no longer believe."

    /** And why there is none when the base rate is missing: the price is withheld, deliberately. */
    const val WITHHELD_PRICE_WHY: String =
      "This school publishes a package and we are NOT stating it, because no cycle we hold publishes the base rate " +
        "it has to be said with. A six-figure package spoken without the published share of entrants who reach " +
        "Finalist is a lottery ticket, so the figures and the school's own words are both withheld until the base " +
        "rate is there. Nothing here means the school pays nothing."

    /** What a priced package IS: the publisher's own terms, and the year the base rate belongs to. */
    fun priceWhyOf(published: PublishedPackage.Priced): String {
      val figures =
        listOfNotNull(
          published.perYearUsd?.let { "${WholeDollars.spoken(it)} a year" },
          published.totalUsd?.let { total ->
            published.years?.let { "${WholeDollars.spoken(total)} over $it years" } ?: WholeDollars.spoken(total)
          },
        ).joinToString(", ")
      val priced =
        if (figures.isEmpty()) {
          "This school publishes its package in WORDS and no figure at all, and award_as_published is those words: " +
            "that is the school's own pricing, not a gap in ours, and there is no number here to compare with any " +
            "other school's"
        } else {
          "This school publishes $figures, and award_as_published is the sentence it published them in -- which may " +
            "carry a condition the figure alone does not"
        }
      return "$priced. Whatever is said about this money, the base rate of the " +
        "${published.baseRate.competitionYear} competition is stated with it: that is what " +
        "base_rate_competition_year names."
    }

    /**
     * Whose award this is, in words -- `not_applicable` is a real answer and not
     * an absence.
     *
     * IT TAKES THE ROW AND REFUSES WITH THE ROW, for
     * [CorruptAwardResidencyBasisException]'s reason: the refusal is the same
     * corruption the read already names, so it is the same typed, LOCATED
     * exception here rather than a second sentence about a row nobody can find.
     */
    fun residencyLabelOf(award: NationalMeritAward): String =
      when (award.residencyBasis) {
        ResidencyBasis.IN_STATE -> {
          "the package this school publishes for a student it treats as a state resident"
        }

        ResidencyBasis.OUT_OF_STATE -> {
          "the package this school publishes for a student it treats as a non-resident"
        }

        ResidencyBasis.NOT_APPLICABLE -> {
          "this school publishes one package and it does not vary by residency"
        }

        ResidencyBasis.IN_DISTRICT, ResidencyBasis.UNKNOWN -> {
          throw CorruptAwardResidencyBasisException(award)
        }
      }

    /**
     * What the residency comparison MEANS, and what it is not.
     *
     * It never says the student is or is not a resident. Residency for tuition
     * purposes is a legal status the school determines, so this is a comparison
     * of two facts we hold -- the school's own state and the state on file --
     * and the coach states which package applies from it.
     */
    fun residencyScopeWhyOf(scope: ResidencyScope): String =
      when (scope) {
        ResidencyScope.MATCHES_STATE_ON_FILE -> {
          "This package's audience agrees with the state this family told us they live in. Residency for tuition " +
            "purposes is still the school's own determination, so it is the package that applies on what we know " +
            "and never a decision already made."
        }

        ResidencyScope.DIFFERS_FROM_STATE_ON_FILE -> {
          "This package's audience does NOT agree with the state this family told us they live in, so it is not " +
            "the one that applies to her on what we know. It rides because it exists and is worth knowing about -- " +
            "a school that pays residents only is a real answer -- and never as money she would be paid."
        }

        ResidencyScope.NO_STATE_ON_FILE -> {
          "We hold no state of residency for this family, so there is no comparison to make: this package's " +
            "audience is residency_basis_label and whether it is hers is not something our data answers."
        }

        ResidencyScope.STATE_UNAVAILABLE -> {
          "This family's state of residency could not be used on this request, so there is no comparison to " +
            "make: whether this package's audience is hers is not something this answer settles. Do NOT say we " +
            "hold no state for them -- we do not know whether we do, and any note at the top of this payload " +
            "says why it was unavailable."
        }

        ResidencyScope.NOT_RESIDENCY_SCOPED -> {
          "This school publishes one package for everyone, so residency does not change it."
        }
      }

    /** WHICH standing a school pays, in words. */
    fun standingLabelOf(standing: QualifyingStanding): String =
      when (standing) {
        QualifyingStanding.FINALIST -> "Finalist standing, the last stage of the screen"
        QualifyingStanding.SEMIFINALIST -> "Semifinalist standing, which is allocated within her own state"
        QualifyingStanding.COMMENDED -> "Commended standing, which is set by one national number and is not Semifinalist"
      }

    /** The first-choice designation, and what it costs elsewhere. */
    fun firstChoiceWhyOf(rule: FirstChoiceRule): String =
      when (rule) {
        is FirstChoiceRule.Required -> {
          val dated = rule.deadline?.let { " The school dates it: ${UsDate.of(it)}." } ?: ""
          "This school requires being named FIRST CHOICE with the sponsor to be paid.$dated A student names one " +
            "college first choice and one only, so this is a commitment with consequences at every other school " +
            "on her list, in the school's own words: \"${rule.asPublished}\"."
        }

        is FirstChoiceRule.NotRequired -> {
          "This school's page says first choice is NOT required: \"${rule.asPublished}\". So this package costs " +
            "her nothing at the other schools on her list."
        }
      }

    /** Where the page says nothing either way, which is neither yes nor no. */
    const val NO_FIRST_CHOICE_WHY: String =
      "This school's page does not say whether being named first choice with the sponsor is required, so we hold " +
        "no answer -- neither that it is nor that it is not."

    /** And where there is no package for a condition to attach to. */
    const val NO_CONDITIONS_WHY: String = "There is no package here for a first-choice rule or a deadline to belong to."

    /** The competition's own name for the year, as the base rate must be SAID. */
    fun saidAs(competitionYear: Int): String = "in the $competitionYear competition"

    /**
     * The derived share as the sentence a coach reads -- our arithmetic over two
     * published counts, labelled as derived.
     *
     * THE NUMBER IS THE TYPE'S. [Share] owns `ratio * 100` and the rounding
     * rule, so a whole-percent base rate is spoken as "1%" exactly as every
     * other share in this codebase is, and this function composes words only.
     */
    fun sharePercent(share: Share): String = "about ${share.spokenPercent()} of entrants reach Finalist"

    /**
     * The model-facing legend: what each wire key means and what values it takes.
     *
     * A SCHEMA LEGEND AND NOT COPY (prompts/README.md, RFC 192). Every
     * behavioural rule about this corpus -- what the PSAT is not, that the base
     * rate is spoken with every figure, that no qualifying score is ever stated
     * -- lives in `prompts/coach-system-prompt.md`, which is its one home. This
     * string says what the keys are.
     */
    val DESCRIPTION: String =
      "The PSAT/NMSQT and National Merit answer for this student: her own place in the competition cycle, the " +
        "cycle's published facts, the published base rate, and what each school on her list publishes for a " +
        "National Merit standing. Call it for any question about the PSAT, the NMSQT, National Merit, a " +
        "Semifinalist or Finalist standing, or what a school pays one. year_of_school and graduation_year are " +
        "hers, derived from her stored graduation date. cycle_answer.answer is one of window_ahead (her qualifying " +
        "sitting is still ahead), window_open (today is inside the published window), alternate_entry_open (the " +
        "window has closed and the letter route has not), alternate_entry_undated (the window has closed and " +
        "the letter route is published with NO deadline, so it is not closed and we hold no day for it), " +
        "cycle_closed (both routes have closed, or she is past the year we hold it for), cycle_expired (we hold " +
        "her cycle row and our reading of it has gone stale, so no date is named) and not_curated (we hold no " +
        "cycle row for her class, and no date is named). " +
        "cycle_answer.cycle is null on both of those two and carries the cycle's own published cells otherwise: " +
        "window_opens_on and window_closes_on are ISO dates and are null when window_dated is false, " +
        "window_as_published is the publisher's own description, " +
        "alternate_entry is the letter route with its own deadline and contact, qualifying_grade is the one year of " +
        "school whose sitting counts, selection_index_low and selection_index_high are the SCALE a score is " +
        "reported on, semifinalist_bar_basis and commended_bar_basis are HOW each bar is set " +
        "(state_representational or national), qualifying_score_as_published is the publisher's own sentence about " +
        "the qualifying score, and score_visibility_as_published is the administrator's own sentence about who " +
        "receives a score. There is no qualifying-score key anywhere in this payload and no key holds a number a " +
        "student has to reach. base_rate is the published funnel of ONE competition year, named by " +
        "competition_year and said_as: test_takers, met_entry_requirements, high_scorers_screened, commended, " +
        "semifinalists, finalists and winners are its counts, any of which may be null where its publisher rounds " +
        "or omits it, and finalist_share_of_entrants_percent is finalists divided by met_entry_requirements as a " +
        "percentage, derived when this answer was built. It is null only when no cycle we hold publishes a " +
        "funnel, and base_rate_why says which of those it is. colleges holds one object per requested college " +
        "and packages one object per published row, because a school publishes one row per entry year, " +
        "residency basis and standing. Each " +
        "package's answer is one of automatic (the page guarantees it to anyone holding the standing), competitive " +
        "(the standing is CONSIDERED, and the figures are the award's and not money she would receive), " +
        "none_published (we read the page and it publishes no National Merit award, with absence_as_published as " +
        "the sentence that proves it), expired (our reading has gone stale, so no figure and no quoted price is " +
        "stated) and not_curated (we have never read this school). residency_basis is in_state, out_of_state or " +
        "not_applicable and residency_basis_label says whose package it is; residency_scope compares that basis " +
        "with the state on this family's money profile and is matches_state_on_file, " +
        "differs_from_state_on_file, no_state_on_file (we hold none), state_unavailable (we could not read or " +
        "could not use one, which is NOT the same as holding none) or not_residency_scoped. award_per_year_usd, award_total_usd " +
        "and award_years are whole dollars and years and any of them may be null: a school that publishes its " +
        "package in words prices it in award_as_published and in no figure at all. Those four keys and " +
        "base_rate_competition_year are non-null together or null together, and price_why says which case this is. " +
        "first_choice is the sponsor's first-choice designation with its own deadline, apply_deadline is the " +
        "school's own application deadline, and publisher, source_url, archive_url and checked_on are stated once " +
        "per package and cover every figure in it. unknown_college_ids holds requested ids that are not on her " +
        "active list. There is no ranking, no total and no best-award key in this payload."
  }
}
