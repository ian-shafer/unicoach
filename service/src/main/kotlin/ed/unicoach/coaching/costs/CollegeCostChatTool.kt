package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putIncomeBand
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
 * The `college_cost_profile` chat tool (RFC 135): the coach's read path into
 * what the student's listed schools actually cost their family. Read-only —
 * it writes nothing. Total by the [ed.unicoach.chat.ChatTool] contract:
 * malformed input returns a structured `{ "error": ... }` object the model
 * reads, never a throw.
 *
 * A thin adapter by design ([MoneyProfileChatTool]'s shape): [execute] only
 * orchestrates parse -> read -> render; the composition (basis selection,
 * tuition applicability, the precision-offer derivation) lives in
 * [CollegeCostService].
 */
class CollegeCostChatTool(
  private val service: CollegeCostService,
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
          logger.warn("tool [{}] cost read failed for student=[{}]", TOOL_NAME, studentId.value, e)
          return errorObject("college cost read failed")
        }

    return profileObject(profile)
  }

  /** The full structured result: one cost object per college, the money-profile echo, and the attribution. */
  private fun profileObject(profile: CollegeCostProfile): JsonObject =
    buildJsonObject {
      putJsonArray("colleges") { profile.colleges.forEach { add(collegeObject(profile, it)) } }
      put("count", profile.colleges.size)
      if (profile.unknownCollegeIds.isNotEmpty()) {
        putJsonArray("unknown_college_ids") {
          profile.unknownCollegeIds.forEach { add(JsonPrimitive(it.value.toString())) }
        }
      }
      put("money_profile", moneyProfileObject(profile.moneyProfile))
      // The comparison contract (RFC 151), present only when the answer carries
      // two or more colleges -- an absent key, never an empty object, so its
      // mere presence tells the model a side-by-side is being made.
      profile.comparisonBasis?.let { put(COMPARISON_BASIS_KEY, comparisonBasisObject(it)) }
      put("source", SOURCE_ATTRIBUTION)
    }

  /**
   * The five facts a side-by-side holds constant (RFC 151), each rendered as a
   * stable code AND the sentence the coach may say for it.
   *
   * A renderer and nothing else: every code and every statement is read off the
   * [ComparisonBasis] the service assembled, so this file cannot state a basis
   * the domain did not decide.
   */
  private fun comparisonBasisObject(basis: ComparisonBasis): JsonObject =
    buildJsonObject {
      putJsonObject("population") {
        put(BASIS_KEY, basis.population.code)
        put(STATEMENT_KEY, basis.population.statement)
      }
      put("residency", residencyBasisObject(basis.residency))
      put("living_arrangement", arrangementBasisObject(basis.livingArrangement))
      // Absent, never empty -- the same convention `comparable` and
      // `incomplete_by_college` follow below, and the one D-B gates the whole
      // object on. A call whose schools report only undated figures (median
      // debt, median earnings) dates nothing, and an empty array would read as
      // a year list nobody could name.
      if (basis.academicYears.isNotEmpty()) {
        putJsonArray("academic_years") { basis.academicYears.forEach { add(datedFiguresObject(it)) } }
      }
      putJsonObject("aid") {
        put(BASIS_KEY, basis.aid.code)
        put(STATEMENT_KEY, basis.aid.statement)
      }
    }

  /**
   * The residency held constant, and the one per-school element inside the
   * per-call object (RFC 151 D-A): the state rides only when it is answered,
   * exactly as the money-profile echo renders it.
   */
  private fun residencyBasisObject(residency: ResidencyBasis): JsonObject =
    buildJsonObject {
      // WHICH schools here charge by residency: the decision the statement
      // speaks, shipped as a code beside it (D-D), so no reader parses the
      // sentence to learn whether this table is all-public, mixed, or none.
      put(BASIS_KEY, residency.scope.value)
      put("status", residency.answer.status.value)
      // The state rides on the answered case and nowhere else, and EXHAUSTIVELY
      // so: a case added to [ComparedResidency] must fail to compile here rather
      // than silently ship without the state it carries.
      when (val answer = residency.answer) {
        is ComparedResidency.Answered -> put("residency_state", answer.state)
        ComparedResidency.Unanswered, ComparedResidency.Declined -> Unit
      }
      put(STATEMENT_KEY, residency.statement)
      putJsonArray("by_college") { residency.byCollege.forEach { add(collegeResidencyObject(it)) } }
    }

  /**
   * One school's residency line inside the per-call object: WHICH published
   * tuition figure this comparison holds constant there.
   *
   * [TUITION_BASIS_KEY] is its own key with its own five-code vocabulary, not
   * the per-college `tuition_applicable`: that one is the public-only
   * in-state/out-of-state/unknown fact, while this one also answers what kind of
   * school it is.
   */
  private fun collegeResidencyObject(college: CollegeResidencyBasis): JsonObject =
    buildJsonObject {
      put("college_id", college.collegeId.value.toString())
      put("name", college.name)
      put(TUITION_BASIS_KEY, college.tuition.code)
      // The raw control code that defeated the residency line, beside the
      // failure it caused: an operator reading this entry never has to open the
      // row to learn WHICH value we could not place.
      when (val tuition = college.tuition) {
        is ComparedTuition.PublishedPriceUnknown -> put(SOURCE_CONTROL_KEY, tuition.sourceControl.label)
        is ComparedTuition.Public, ComparedTuition.SinglePublishedPrice -> Unit
      }
      put(STATEMENT_KEY, college.statement)
    }

  /**
   * The living arrangement held constant (RFC 151 D-E): the arrangements every
   * school in the call is priced for, and the schools that lack one another
   * school has.
   *
   * Both lists are emitted only when they carry something -- the payload's
   * absent-never-empty convention -- and the statement says which case it is in
   * words, so an absent list is never read as a fact nobody stated.
   */
  private fun arrangementBasisObject(arrangement: ArrangementBasis): JsonObject =
    buildJsonObject {
      if (arrangement.comparable.isNotEmpty()) {
        putJsonArray("comparable") { arrangement.comparable.forEach { add(JsonPrimitive(it.wireName)) } }
      }
      if (arrangement.incompleteByCollege.isNotEmpty()) {
        putJsonArray("incomplete_by_college") {
          arrangement.incompleteByCollege.forEach { add(incompleteArrangementObject(it)) }
        }
      }
      put(STATEMENT_KEY, arrangement.statement)
    }

  /**
   * One school's gap in the arrangements the other schools are priced for: the
   * arrangements it lacks, and the one reason they are missing for (RFC 151
   * D-E). A school with two kinds of gap is two entries, so one entry is always
   * "these arrangements, for this reason".
   */
  private fun incompleteArrangementObject(entry: IncompleteArrangement): JsonObject =
    buildJsonObject {
      put("college_id", entry.collegeId.value.toString())
      put("name", entry.name)
      putJsonArray("missing") { entry.missing.forEach { add(JsonPrimitive(it.wireName)) } }
      put("reason", entry.reason.value)
    }

  /**
   * One academic year in the call and the figures it dates -- the same
   * `{academic_year, figures}` shape the per-college vintage labels carry, so
   * the model reads one convention rather than two.
   */
  private fun datedFiguresObject(dated: DatedFigures): JsonObject =
    buildJsonObject {
      put(BASIS_KEY, dated.basis)
      put(ACADEMIC_YEAR_KEY, dated.academicYear)
      putJsonArray(DATED_FIGURES_KEY) { dated.figures.forEach { add(JsonPrimitive(it.wireName)) } }
      put(STATEMENT_KEY, dated.statement)
    }

  private fun collegeObject(
    profile: CollegeCostProfile,
    cost: CollegeCost,
  ): JsonObject {
    // The vintage labels are derived from what this object ACTUALLY carries,
    // recorded as each figure is put. One decision per key, made once: a second
    // list restating the same emit conditions is how a figure comes to be
    // labelled with a year no key beside it describes, or to lose its label
    // entirely -- and nothing would fail for it.
    val emitted = mutableSetOf<CostField>()

    fun JsonObjectBuilder.putFigure(
      field: CostField,
      amountUsd: Int?,
    ) {
      amountUsd?.let {
        put(field.wireName, it)
        emitted += field
      }
    }

    return buildJsonObject {
      put("college_id", cost.collegeId.value.toString())
      put("name", cost.name)
      put("city", cost.city)
      put("state", cost.state)
      put("control", cost.control.label)
      put("list_status", cost.listStatus.value)
      putFigure(CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, cost.stickerCostOfAttendancePerYearUsd)
      putFigure(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD, cost.tuitionAndFeesInStatePerYearUsd)
      putFigure(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD, cost.tuitionAndFeesOutOfStatePerYearUsd)
      // Present only on the public case; the model makes the distinction
      // uncarryable by a private college, so it cannot be misread onto one.
      (cost.control as? CollegeControl.Public)?.let { put("tuition_applicable", it.tuitionApplicable.value) }
      // The one figure that is not a bare scalar: it keys an object, so it is
      // recorded beside its own emit rather than through putFigure.
      put(CostField.NET_PRICE.wireName, netPriceObject(cost.netPrice))
      if (cost.netPrice.amount != null) emitted += CostField.NET_PRICE
      // Emitted only when there is something to offer: an absent key, never an
      // empty array, so its mere presence stays meaningful to the model.
      val offers = profile.precisionOffersFor(cost)
      if (offers.isNotEmpty()) {
        putJsonArray(PRECISION_OFFER_KEY) { offers.forEach { add(precisionOfferObject(it)) } }
      }
      putFigure(CostField.MEDIAN_DEBT_AT_COMPLETION_USD, cost.medianDebtAtCompletionUsd)
      putFigure(CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD, cost.medianEarnings10yAfterEntryUsd)
      // The published price split by living arrangement (RFC 149). Absent, not
      // empty, when this school reports no component at all. Its lines are
      // recorded here, at the one place they are rendered.
      cost.breakdown?.let { putBreakdown(it, emitted) }
      putOffersOnCampusHousing(cost)
      // LAST of the figure-bearing keys, and it must stay there: it reads the
      // [emitted] set the puts above filled in, so a figure emitted BELOW this
      // line would ride with no academic year beside it and nothing -- no type,
      // no test -- would fail for it. The keys after it emit no CostField, which
      // is the only reason they may sit where they do.
      putVintageLabels(emitted)
      // Purely additive (RFC 148 D7): present only when the school reports it,
      // and carrying its OWN citation, because merit aid is not a Scorecard
      // fact and must never fold into the payload's Scorecard `source` string.
      cost.meritAid?.let { put(MeritAidWire.KEY, MeritAidWire.objectOf(it)) }
      putJsonArray("data_availability") {
        cost.notReported.forEach { add(JsonPrimitive(it.wireName)) }
      }
    }
  }

  /**
   * The per-arrangement split, RECORDING the fields it renders in [emitted] so
   * the vintage labels below follow exactly what this object carries.
   */
  private fun JsonObjectBuilder.putBreakdown(
    breakdown: CostBreakdown,
    emitted: MutableSet<CostField>,
  ) {
    put(BREAKDOWN_KEY, breakdownObject(breakdown))
    breakdown.arrangements.forEach { arrangement -> arrangement.lines.forEach { emitted += it.field } }
  }

  /**
   * The no-dorms answer (RFC 149 D-B), emitted whenever IPEDS ANSWERED -- true
   * as well as false.
   *
   * `false` is "this school has no residence halls"; `true` is the reported
   * fact that an absent `on_campus` arrangement is this school's silence about
   * its components rather than its having no dorms. A rendered arrangement is
   * NOT a surrogate for the flag: a school with dorms that publishes no
   * on-campus figure carries no arrangement, so the two are different facts and
   * collapsing them left the model unable to tell a known fact from a gap.
   *
   * Only an unknown flag is absent -- no IPEDS row is not evidence either way.
   * The key rides even when the school publishes on-campus figures in spite of
   * a `false` flag (D-B): both facts are true and the coach is owed both.
   *
   * Exhaustive on the nullable, so a future fourth state cannot fall through
   * this branch silently.
   */
  private fun JsonObjectBuilder.putOffersOnCampusHousing(cost: CollegeCost) {
    when (cost.offersOnCampusHousing) {
      true -> put(OFFERS_ON_CAMPUS_HOUSING_KEY, true)
      false -> put(OFFERS_ON_CAMPUS_HOUSING_KEY, false)
      null -> Unit
    }
  }

  /**
   * One object per vintage this college actually carries a figure of (RFC 149
   * D-E): the academic year, and the wire names that year dates.
   *
   * The year NAMES ITS FIGURES rather than sitting beside the college as a bare
   * label. Membership -- "the components are the published price, the sticker
   * and the net price are the blend" -- otherwise lived only in
   * `CostField.vintage` and in prose, so a reader that did not carry the
   * convention could attach either year to any figure, and the prompt's
   * instruction to quote the year beside a figure had no year beside any
   * figure.
   *
   * Both the key and its [DATED_FIGURES_KEY] list are read off the SAME
   * recorded [emitted] set, so the label can never date a figure this payload
   * did not render. A field whose vintage is null appears under no key and is
   * therefore said with no year at all.
   *
   * MUST be called AFTER every figure this object emits. [emitted] is complete
   * only because each `put*` above it recorded into it; a figure put after this
   * call is a figure said with no academic year, and neither the types nor a
   * test would notice.
   */
  private fun JsonObjectBuilder.putVintageLabels(emitted: Set<CostField>) {
    ScorecardVintage.entries.forEach { vintage ->
      val dated = emitted.filter { it.vintage == vintage }
      if (dated.isNotEmpty()) {
        putJsonObject(vintage.wireName) {
          put(ACADEMIC_YEAR_KEY, vintage.label)
          // Enum declaration order, not the order they happened to be emitted
          // in: the list is a fact about the payload, so it must not depend on
          // set iteration.
          putJsonArray(DATED_FIGURES_KEY) {
            CostField.entries.filter { it in dated }.forEach { add(JsonPrimitive(it.wireName)) }
          }
        }
      }
    }
  }

  /**
   * The `cost_by_living_arrangement` object: one entry per arrangement this
   * school can be priced for, keyed by the arrangement's wire name.
   *
   * The component keys inside are [CostField] wire names -- identical to the
   * column names -- so the JSON, `data_availability` and the schema all speak
   * one vocabulary. They look redundant nested inside an arrangement object on
   * purpose: the alternative is a second set of arrangement-local names that
   * nothing else shares, and a `data_availability` entry no key in the payload
   * matches.
   */
  private fun breakdownObject(breakdown: CostBreakdown): JsonObject =
    buildJsonObject {
      breakdown.arrangements.forEach { arrangement ->
        putJsonObject(arrangement.arrangement.wireName) {
          arrangement.lines.forEach { put(it.field.wireName, it.amountUsd) }
          // Absent whenever any part is missing (RFC 149 D-C): a partial sum is
          // not a total, and neither is a sum that guessed at the student's
          // residency.
          arrangement.totalPerYearUsd?.let { put(TOTAL_KEY, it) }
        }
      }
    }

  /** One invitation: the money-profile field it would fill, and the sentence the coach may say for it. */
  private fun precisionOfferObject(offer: PrecisionOffer): JsonObject =
    buildJsonObject {
      put("field", offer.field)
      put("offer", offerCopy(offer))
    }

  /**
   * The sentence the coach may say for one offer. Exhaustive on purpose: a new
   * [PrecisionOffer] member must fail to compile here — the one site that owes
   * it copy — rather than ship an invitation with no words in it.
   */
  private fun offerCopy(offer: PrecisionOffer): String =
    when (offer) {
      PrecisionOffer.RESIDENCY -> RESIDENCY_OFFER
      PrecisionOffer.INCOME_BAND -> INCOME_BAND_OFFER
    }

  /**
   * The `net_price` sub-object: `amount_usd` when reported, the basis label, and —
   * only on the band-specific case — the band's code and its spoken dollar
   * range (`IncomeBand.bracket`, RFC 142). The label rides beside the code so
   * the model never has to invent a phrase for the bucket it is naming aloud.
   */
  private fun netPriceObject(netPrice: NetPrice): JsonObject =
    buildJsonObject {
      netPrice.amount?.let { put("amount_usd", it) }
      put("basis", netPrice.basis)
      // Exhaustive on purpose: an overall average deliberately emits no
      // qualifier, and a future NetPrice case must fail to compile here rather
      // than ship an unlabeled basis. Do not collapse this to an `if`.
      when (netPrice) {
        is NetPrice.BandSpecific -> {
          putIncomeBand(netPrice.band)
        }

        is NetPrice.OverallAverage -> {}
      }
    }

  /**
   * The money-profile echo: both field statuses, values only when answered.
   * An answered band carries its spoken dollar range alongside its code
   * (`IncomeBand.bracket`, RFC 142); an unanswered or declined band carries
   * neither.
   */
  private fun moneyProfileObject(profile: MoneyProfileStatuses): JsonObject =
    buildJsonObject {
      put("income_band_status", profile.incomeBandStatus.value)
      profile.incomeBand?.let { putIncomeBand(it) }
      put("residency_status", profile.residencyStatus.value)
      profile.residencyState?.let { put("residency_state", it) }
    }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeCostChatTool::class.java)

    const val TOOL_NAME = "college_cost_profile"

    /**
     * The attribution the coach must quote when using these numbers.
     *
     * It names the source and nothing else (RFC 149 D-E). It used to append
     * "(data ingested 2026)", which was `colleges.updated_at` -- WHEN WE LOADED
     * THE FILE, not the year of the figures -- and the coach read it aloud as a
     * vintage. The real vintage now rides per college, beside the figures it
     * governs, as a [ScorecardVintage] academic-year label.
     */
    const val SOURCE_ATTRIBUTION = "U.S. Department of Education College Scorecard"

    /** The wire key carrying the per-arrangement price split (RFC 149). */
    const val BREAKDOWN_KEY = "cost_by_living_arrangement"

    /** The IPEDS housing answer's own key -- emitted whenever the flag is known, true or false (RFC 149 D-B). */
    const val OFFERS_ON_CAMPUS_HOUSING_KEY = "offers_on_campus_housing"

    /** The academic year one vintage names -- a label ("2022-23"), never a bare year. */
    const val ACADEMIC_YEAR_KEY = "academic_year"

    /** The wire names one vintage dates, so no reader infers membership from a naming convention. */
    const val DATED_FIGURES_KEY = "figures"

    /**
     * One arrangement's total. Unsuffixed by measure but not by unit: it is a
     * scalar, so it says its own unit, exactly like every other dollar key here.
     */
    const val TOTAL_KEY = "total_per_year_usd"

    /** The wire key carrying the upgrade invitations — one home for the emit site and the description. */
    const val PRECISION_OFFER_KEY = "precision_offer"

    /**
     * The wire key carrying the assumptions a side-by-side holds constant (RFC
     * 151) — present only when the answer carries two or more colleges.
     */
    const val COMPARISON_BASIS_KEY = "comparison_basis"

    /** The stable code one basis fact rides under; its spoken twin is [STATEMENT_KEY]. */
    const val BASIS_KEY = "basis"

    /**
     * WHICH published tuition figure the comparison holds constant at ONE school
     * (RFC 151), inside `comparison_basis.residency.by_college`.
     *
     * Its own key with its own five-code vocabulary — `in_state`,
     * `out_of_state`, `unknown`, `single_published_price`,
     * `published_price_unknown` — deliberately NOT the per-college
     * `tuition_applicable`, which is the public-only in/out/unknown fact. The
     * two answer different questions, so a reader never has to remember which
     * object it is looking at to know which vocabulary it is reading.
     */
    const val TUITION_BASIS_KEY = "tuition_basis"

    /**
     * The `colleges.control` value we could not place, beside the
     * `published_price_unknown` entry it produced — the one datum that says
     * WHICH value defeated this school's residency line.
     *
     * Rendered as [CollegeControl.Unrecognized]'s label ("unknown (control
     * [9])"), the same form the per-college `control` key carries: the code
     * stays readable, and no bare source code goes on the wire (RFC 143).
     */
    const val SOURCE_CONTROL_KEY = "source_control"

    /**
     * The sentence the coach may say for one basis fact — the paired-label
     * convention (`income_band` + `income_band_label`) applied to the comparison
     * contract: whenever a code goes on the wire, the words go with it, from the
     * same construct.
     */
    const val STATEMENT_KEY = "statement"

    /**
     * The residency invitation (RFC 145): present on a public college's result
     * exactly when residency is unanswered and the college publishes a tuition
     * figure the answer would select — and absent after a decline, so the
     * coach is never cued to reopen a closed topic
     * ([CollegeCostProfile.precisionOffersFor]). It says what the answer
     * unlocks: which of this school's published prices applies to this family.
     *
     * The promise is deliberately no wider than the data: the offer is admitted
     * when EITHER figure is published (residency still decides which one
     * applies), so the copy cannot promise a number — a family sorted onto the
     * side this school does not report gets the ordinary `data_availability`
     * answer, said plainly, instead of an invented one.
     */
    const val RESIDENCY_OFFER =
      "This is a public school, so its published tuition and fees depend on where the family lives. " +
        "If the student shares the state they live in (record it with ${MoneyProfileChatTool.TOOL_NAME}), " +
        "you can say which of this school's published prices applies to them - the in-state one or the " +
        "out-of-state one - and say plainly when this school does not report the one that applies."

    /**
     * The in-answer invitation (RFC 135): present on a college result exactly
     * when the income band is unanswered and that college reports band
     * pricing, so the coach can offer the upgrade right in the conversation —
     * and absent after a decline, so the coach is never cued to reopen a
     * closed topic ([CollegeCostProfile.precisionOffersFor]).
     */
    const val INCOME_BAND_OFFER =
      "This net price is the overall average. If the student shares their household income band " +
        "(record it with ${MoneyProfileChatTool.TOOL_NAME}), it becomes the family-specific price for their bracket."

    // The ethos contract rides the tool description (RFC 135): real numbers
    // with a named source, the basis always labeled, never re-raise a decline.
    // Not `const`: the example band range is rendered from IncomeBand.bracket,
    // the one home for that copy (RFC 142), so the description can never quote
    // a range the results themselves no longer carry.
    val DESCRIPTION =
      "Read the real cost facts for the colleges on the student's list: sticker cost, tuition, " +
        "the net price their family would actually pay, median debt and median earnings. " +
        "Data comes from the U.S. Department of Education College Scorecard - always attribute figures " +
        "to it, and when a field appears in data_availability the college does not report it: say so " +
        "plainly, never estimate. Each net_price is an object carrying amount_usd (whole US dollars per " +
        "academic year) and a basis: your_income_band means it is " +
        "specific to the student's answered household income band; overall_average means the band is not " +
        "on file and the figure is the all-family average - say which it is. When a net price is band-specific it " +
        "also carries income_band_label, the band's dollar range in plain words (e.g. \"${IncomeBand.OVER_110K.bracket}\") - say that " +
        "range when you name the band aloud, never the income_band code and never a data-source bucket name. " +
        "When a college's result carries " +
        "$PRECISION_OFFER_KEY, it is a list of upgrade invitations for that result, each naming the money-profile field " +
        "it would fill (${MoneyProfileChatTool.TOOL_NAME} records them) and carrying the sentence you may say. Raise them " +
        "in the order given: ${PrecisionOffer.RESIDENCY.field} sorts first because it is the cheaper question and the " +
        "bigger correction - it selects which of the school's published prices applies - while " +
        "${PrecisionOffer.INCOME_BAND.field} makes " +
        "the net price family-specific. money_profile.residency_status is the authority on whether to raise residency, " +
        "and money_profile.income_band_status the authority on whether to raise income: " +
        "declined means the student said no - never re-raise it yourself; answered means the field is already on file. " +
        "A college result may also carry ${MeritAidWire.KEY}, from that school's own Common Data Set and cited " +
        "separately from the Scorecard figures: ${MeritAidWire.SHARE_KEY} is a share of ALL full-time freshmen " +
        "at that school - never a share of the students with no financial need, which no school reports - and " +
        "${MeritAidWire.AVERAGE_KEY} is what last year's recipients averaged, not an offer to this student, so " +
        "never subtract it from any price here. Its absence means only that this school does not report it. " +
        "A college result may also carry $BREAKDOWN_KEY, the published price split into the parts a family can " +
        "actually influence, keyed by where the student would live: " +
        "${LivingArrangement.ON_CAMPUS.wireName}, ${LivingArrangement.OFF_CAMPUS.wireName}, " +
        "${LivingArrangement.WITH_FAMILY.wireName}. Each " +
        "arrangement carries the tuition and fees line that applies to this student and the school's published " +
        "allowances for that way of living, and $TOTAL_KEY only when every part of it is reported - when there is " +
        "no $TOTAL_KEY, say the parts and say a part is missing, never add up what is there and call it the total. " +
        "${LivingArrangement.WITH_FAMILY.wireName} carries no housing and food line because no school publishes " +
        "one for a student living at home; " +
        "that is missing data about the arrangement, never a housing cost of zero. " +
        "${CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName} is a separate figure - an average blended " +
        "across all three arrangements and from an earlier year - so never compare it with an arrangement total and " +
        "never present one as the other. Aid applies to the whole price and not to any one part of it, so never " +
        "subtract ${CostField.NET_PRICE.wireName} from tuition or from any of these components, or one from the " +
        "other. When a college carries \"$OFFERS_ON_CAMPUS_HOUSING_KEY\": false, IPEDS reports that school has no " +
        "residence halls: say so, and do not treat the absent ${LivingArrangement.ON_CAMPUS.wireName} arrangement as " +
        "unreported data. If that college nevertheless carries an ${LivingArrangement.ON_CAMPUS.wireName} " +
        "arrangement, the school published those figures itself and the two sources disagree: quote the published " +
        "figures and say the school reports no on-campus housing, never one fact without the other. " +
        "\"$OFFERS_ON_CAMPUS_HOUSING_KEY\": true means IPEDS reports the school does have on-campus housing, so a " +
        "missing ${LivingArrangement.ON_CAMPUS.wireName} arrangement there is unreported cost data rather than the " +
        "absence of dorms. When the key is absent altogether IPEDS does not say either way - never read a present or " +
        "missing ${LivingArrangement.ON_CAMPUS.wireName} arrangement as the answer. " +
        "${ScorecardVintage.PUBLISHED_PRICE.wireName} and ${ScorecardVintage.BLENDED_AVERAGE.wireName} each carry an " +
        "$ACADEMIC_YEAR_KEY (e.g. \"${ScorecardVintage.PUBLISHED_PRICE.label}\") and the $DATED_FIGURES_KEY it dates: " +
        "quote a number with the year of the key that lists it, never with the other one, and never add figures from " +
        "the two different years together. A figure named by neither key has no academic year - " +
        "${CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName} and " +
        "${CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName} describe cohorts rather than one price year - " +
        "so say those numbers without a year rather than borrowing one from another figure. " +
        "When the result carries two or more colleges it also carries $COMPARISON_BASIS_KEY, the assumptions a " +
        "side-by-side holds constant: who the figures describe, the residency (stated per school under " +
        "by_college), the way of living every school here is priced for, the academic years, and what aid means " +
        "in a net price. Each one carries a $BASIS_KEY code and the $STATEMENT_KEY you may say: say those " +
        "sentences as ordinary copy above the table, never as a footnote under it, and never build a column " +
        "from two different bases - one residency and one way of living per column, or say it as two tables. " +
        "Put the tuition and fees line above the estimated living costs and say which block is which. Rows are " +
        "schools; keep the table to three columns and short cells, or say it as a list. When a school does not " +
        "report a part, leave that cell blank and label it as not reported: never a zero, never a neighbour's " +
        "figure, and never a total summed from what happens to be there. " +
        "Read-only: this tool changes nothing."
  }
}
