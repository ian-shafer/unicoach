package ed.unicoach.coaching.costs

import ed.unicoach.coaching.MoneyProfileChatTool
import ed.unicoach.coaching.StudentScopedChatTool
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.collegelist.CollegeListChatTool
import ed.unicoach.coaching.putCollegeIdsSchema
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.putIncomeBand
import ed.unicoach.db.models.putLivingPlan
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
      // The SIXTH fact (RFC 157 D-C), emitted directly after the residency fact
      // it completes: the residency line is about the tuition and fees column,
      // and this one says which residency the two blended figures are on and
      // which schools here they therefore do not describe.
      put(BLENDED_FIGURE_BASIS_KEY, blendedFigureBasisObject(basis.blendedFigures))
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
    // WHICH schools here charge by residency: the decision the statement speaks,
    // shipped as a code beside it (D-D), so no reader parses the sentence to
    // learn whether this table is all-public, mixed, or none.
    residencyAnsweredBasisObject(
      scope = residency.scope.value,
      answer = residency.answer,
      statement = residency.statement,
      byCollege = residency.byCollege,
      objectOfEntry = ::collegeResidencyObject,
    )

  /**
   * The shape EVERY residency-answered per-call fact rides in (RFC 151 D-D):
   * the scope code, the family's own answer, the sentence the coach may say, and
   * one entry per school.
   *
   * Written ONCE, so a key added here reaches the residency fact and the
   * blended-figure fact together rather than being remembered for one of them.
   */
  private fun <T> residencyAnsweredBasisObject(
    scope: String,
    answer: ComparedResidency,
    statement: String,
    byCollege: List<T>,
    objectOfEntry: (T) -> JsonObject,
  ): JsonObject =
    buildJsonObject {
      put(BASIS_KEY, scope)
      put("status", answer.status.value)
      // The state rides on the answered case and nowhere else, and EXHAUSTIVELY
      // so: a case added to [ComparedResidency] must fail to compile here rather
      // than silently ship without the state it carries.
      when (answer) {
        is ComparedResidency.Answered -> put("residency_state", answer.state)
        ComparedResidency.Unanswered, ComparedResidency.Declined -> Unit
      }
      put(STATEMENT_KEY, statement)
      putJsonArray("by_college") { byCollege.forEach { add(objectOfEntry(it)) } }
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
   * Which residency the published price and the price after a financial aid
   * offer are on, and which schools in this call they do not describe (RFC 157
   * D-C).
   *
   * The same shape as every other basis fact: a [BASIS_KEY] code, the family's
   * own answer echoed, the [STATEMENT_KEY] the coach may say, and one entry per
   * school -- so a reader never has to work out from an absent figure that a
   * figure was withheld.
   */
  private fun blendedFigureBasisObject(basis: BlendedFigureBasis): JsonObject =
    residencyAnsweredBasisObject(
      scope = basis.scope.value,
      answer = basis.answer,
      statement = basis.statement,
      byCollege = basis.byCollege,
      objectOfEntry = ::collegeBlendedFigureObject,
    )

  /**
   * One school's blended-figure line: the code, whether the two figures are this
   * family's, and the sentence. [putBlendedFiguresApply] owns the known-only
   * rule for [APPLIES_KEY] and says why.
   */
  private fun collegeBlendedFigureObject(college: CollegeBlendedFigureBasis): JsonObject =
    buildJsonObject {
      put("college_id", college.collegeId.value.toString())
      put("name", college.name)
      put(BASIS_KEY, college.code.value)
      putBlendedFiguresApply(college.applies)
      put(STATEMENT_KEY, college.statement)
    }

  /**
   * Whether the two blended figures are this family's at one school, on the wire
   * in ONE form wherever it is said (RFC 157 D-B).
   *
   * TWO keys, and both are load-bearing. [APPLIES_BASIS_KEY] carries the state
   * itself and is always written, so the payload is self-describing and no
   * reader infers an outcome from a key that is not there. [APPLIES_KEY] stays
   * the known-only boolean -- the `offers_on_campus_housing` convention (RFC 149
   * D-B) -- because an open residency question is not a `false`, and writing one
   * would tell the coach these figures are somebody else's when all we know is
   * that we have not asked.
   *
   * Exhaustive, so a fourth outcome must say what boolean it ships rather than
   * falling through to silence.
   */
  private fun JsonObjectBuilder.putBlendedFiguresApply(applies: BlendedFigureApplicability) {
    // The state ITSELF, always present: three outcomes cannot ride on a boolean
    // plus an absence, and an absent key has two causes (an open residency
    // question, an unrecognised control) that a reader cannot tell apart.
    put(APPLIES_BASIS_KEY, applies.value)
    when (applies) {
      BlendedFigureApplicability.APPLIES -> put(APPLIES_KEY, true)
      BlendedFigureApplicability.WITHHELD -> put(APPLIES_KEY, false)
      BlendedFigureApplicability.BASIS_STATED -> Unit
    }
  }

  /**
   * The living arrangement held constant (RFC 151 D-E, RFC 152 D5): what the
   * family answered, what each school resolved to, the arrangements every
   * school in the call is priced for, and the schools that lack one another
   * school has.
   *
   * The lists are emitted only when they carry something -- the payload's
   * absent-never-empty convention -- and the statement says which case it is in
   * words, beside the [BASIS_KEY] code that says it as a fact, so an absent
   * list is never read as a fact nobody stated.
   *
   * [CHOSEN_BY_COLLEGE_KEY] is emitted only when the schools are NOT all on one
   * plan or one of them is not priced for it: with one plan everywhere, the
   * statement and the per-college keys already say it, and a second per-school
   * list would invite the coach to narrate a difference there is none of.
   */
  private fun arrangementBasisObject(arrangement: ArrangementBasis): JsonObject =
    buildJsonObject {
      put(BASIS_KEY, arrangement.scope.value)
      putComparedLivingPlan(arrangement.answer)
      if (arrangement.planNamedPerCollege.isNotEmpty()) {
        putJsonArray(CHOSEN_BY_COLLEGE_KEY) {
          arrangement.planNamedPerCollege.forEach { add(collegeLivingPlanObject(it)) }
        }
      }
      if (arrangement.comparable.isNotEmpty()) {
        putJsonArray("comparable") { arrangement.comparable.forEach { add(JsonPrimitive(it.value)) } }
      }
      if (arrangement.incompleteByCollege.isNotEmpty()) {
        putJsonArray("incomplete_by_college") {
          arrangement.incompleteByCollege.forEach { add(incompleteArrangementObject(it)) }
        }
      }
      put(STATEMENT_KEY, arrangement.statement)
    }

  /**
   * One school's resolved plan inside the comparison: which way of living it is
   * quoted at, where that plan came from, and -- when the school is not priced
   * for it -- the reason, never a substitute.
   *
   * A school with no plan resolved carries its identity and the
   * [LivingPlanPricing.NOT_CHOSEN] code, so "nothing is on file for it" is
   * stated rather than left to be noticed in an absent key.
   */
  private fun collegeLivingPlanObject(row: CollegeLivingPlan): JsonObject =
    buildJsonObject {
      put("college_id", row.collegeId.value.toString())
      put("name", row.name)
      putChosenLivingPlan(row.chosen)
    }

  /**
   * The resolved plan itself: which way of living, in the student's words,
   * where the plan came from -- and the reason when this school is not priced
   * for it, never a substitute.
   *
   * ONE emitter for the two places the sub-object appears (the per-college
   * answer and the comparison's [CHOSEN_BY_COLLEGE_KEY]), so the coach can never
   * meet two spellings of one shape.
   *
   * Every case writes its own [PRICING_KEY] code (RFC 152): the three shapes
   * used to be told apart only by which sibling keys were absent, which made the
   * coach infer a fact from a silence -- the very thing a code exists to
   * prevent. A school with no plan resolved now says so as a code too, instead
   * of being an identity-only row.
   */
  private fun JsonObjectBuilder.putChosenLivingPlan(chosen: ChosenLivingPlan) {
    when (chosen) {
      is ChosenLivingPlan.Priced -> {
        put(ARRANGEMENT_KEY, chosen.plan.value)
        put(ARRANGEMENT_LABEL_KEY, chosen.plan.label)
        put(ARRANGEMENT_SOURCE_KEY, chosen.source.value)
        put(PRICING_KEY, chosen.pricing.value)
      }

      is ChosenLivingPlan.NoTotalHere -> {
        put(ARRANGEMENT_KEY, chosen.plan.value)
        put(ARRANGEMENT_LABEL_KEY, chosen.plan.label)
        put(ARRANGEMENT_SOURCE_KEY, chosen.source.value)
        put(PRICING_KEY, chosen.pricing.value)
        // OUR vocabulary, never the school's: two of this reason's three causes
        // are gaps of ours, so it rides under its own key and never under the
        // one that says what the school published.
        put(NO_TOTAL_REASON_KEY, chosen.reason.value)
      }

      is ChosenLivingPlan.NotPricedHere -> {
        put(ARRANGEMENT_KEY, chosen.plan.value)
        put(ARRANGEMENT_LABEL_KEY, chosen.plan.label)
        put(ARRANGEMENT_SOURCE_KEY, chosen.source.value)
        put(PRICING_KEY, chosen.pricing.value)
        // A reason code, never a substituted arrangement and never a
        // neighbour's figure. No total, because there is none to give.
        put(ARRANGEMENT_REASON_KEY, chosen.reason.value)
      }

      // No plan, so no arrangement to name -- but the code is written, so a row
      // with nothing on file states that rather than leaving it to be noticed.
      ChosenLivingPlan.NotChosen -> {
        put(PRICING_KEY, chosen.pricing.value)
      }
    }
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
      putJsonArray("missing") { entry.missing.forEach { add(JsonPrimitive(it.value)) } }
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
      // AFTER the breakdown and BEFORE putVintageLabels, and that ordering is
      // load-bearing (RFC 152). It re-keys a figure the breakdown already
      // emitted -- the resolved arrangement's total -- so it must sit above the
      // vintage labels for the rule "the keys after it emit no CostField" to
      // stay true; and it sits below the breakdown because it names one of the
      // arrangements that object rendered, so a reader meets the three before
      // being told which one to lead with.
      putChosenLivingArrangement(cost.chosen)
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
      putFieldsWithNoAmount(cost)
      putWithheldFigures(cost)
      // The blended-figure basis for THIS school, stated per college as well as
      // in COMPARISON_BASIS_KEY: that fact needs two colleges, and a one-college
      // result is exactly where the basis would otherwise go unsaid (RFC 157
      // D-B).
      putBlendedFiguresApply(cost.blendedFiguresApply)
    }
  }

  /**
   * The figures the coach has NO number for here, whichever of the two reasons
   * it is: the school did not report it, or it is not a figure for this family
   * (RFC 157 D-A).
   *
   * ONE list, because the instruction it drives is one instruction -- say so
   * plainly, never estimate -- and a field withheld from a list called
   * `data_availability` would be a silence with nothing said about it. WHICH
   * reason applies is [putWithheldFigures]' to say, with the words for it.
   *
   * The union is the DOMAIN's ([CollegeCost.fieldsWithNoAmount]), already in
   * enum declaration order: this writer spells out a set the cost answer names,
   * and never derives a third field category the page and the service cannot
   * share.
   *
   * ABSENT, never empty -- the payload's own convention, the one
   * [putWithheldFigures] follows and the one every other list here follows. An
   * empty array would say "we checked" in a shape a reader has to open to learn
   * it says nothing.
   */
  private fun JsonObjectBuilder.putFieldsWithNoAmount(cost: CollegeCost) {
    if (cost.fieldsWithNoAmount.isEmpty()) return
    putJsonArray(DATA_AVAILABILITY_KEY) { cost.fieldsWithNoAmount.forEach { add(JsonPrimitive(it.wireName)) } }
  }

  /**
   * The figures we hold and are not showing this family, each with its reason
   * (RFC 157 D-A).
   *
   * Absent, never empty. Its presence means exactly one thing: a
   * `data_availability` entry here is OURS rather than the school's silence.
   */
  private fun JsonObjectBuilder.putWithheldFigures(cost: CollegeCost) {
    if (cost.withheld.isEmpty()) return
    putJsonArray(WITHHELD_FIGURES_KEY) { cost.withheld.forEach { add(withheldFigureObject(it)) } }
  }

  /**
   * One withheld figure: which field, the reason code, and the sentence that
   * both names the reason and points at the figures that ARE this family's (RFC
   * 157 D-A).
   *
   * The statement is the [WithheldReason]'s own, never re-worded HERE, so the
   * coach explains the blank in the domain's words. The report page prints the
   * same reason in [WithheldReason.cellPhrase], the short form beside this one,
   * with its own pointer at the table the reader is looking at (RFC 157 D-A):
   * one vocabulary, two lengths, and neither file invents a third.
   */
  private fun withheldFigureObject(withheld: WithheldFigure): JsonObject =
    buildJsonObject {
      put("field", withheld.field.wireName)
      put(WITHHELD_REASON_KEY, withheld.reason.value)
      put(STATEMENT_KEY, withheld.reason.statement)
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
   * The way of living this answer LEADS with (RFC 152), when the family has
   * said where they plan to live.
   *
   * ABSENT, never null, when nothing is resolved: the plan is unanswered or
   * declined and this school carries no plan of its own, so the answer is
   * exactly what it was before this key existed -- all three arrangements, each
   * named and labelled (D3).
   *
   * It never narrows the breakdown beside it (D2): the other ways of living
   * stay in the payload as true facts, and a "what if we lived at home
   * instead?" stays answerable from the same result.
   *
   * The key emits no CostField into [emitted] because it emits no NEW figure:
   * the total it carries is the one the breakdown already rendered for the same
   * arrangement, already dated by the vintage labels below.
   */
  private fun JsonObjectBuilder.putChosenLivingArrangement(chosen: ChosenLivingPlan) {
    // Exhaustive with no `else`: a fourth case would otherwise fall silently
    // out of the payload, and this key is the whole of what the coach leads
    // with.
    when (chosen) {
      is ChosenLivingPlan.Priced -> {
        putJsonObject(CHOSEN_ARRANGEMENT_KEY) {
          putChosenLivingPlan(chosen)
          // Always present on this case: a plan is only PRICED when this school
          // prices it, which means it carries a total (RFC 152 D2a). An
          // arrangement this school shows but cannot total is emitted as
          // no_total_here with its reason instead, so this key never ships
          // without a number or a reason.
          put(TOTAL_KEY, chosen.totalPerYearUsd)
          put(STATEMENT_KEY, statementOf(chosen))
        }
      }

      is ChosenLivingPlan.NoTotalHere -> {
        putJsonObject(CHOSEN_ARRANGEMENT_KEY) {
          putChosenLivingPlan(chosen)
          // No total, because this school has none for it -- and the statement
          // says so, so the key never ships a plan with a silent blank where
          // its number should be.
          put(STATEMENT_KEY, statementOf(chosen))
        }
      }

      is ChosenLivingPlan.NotPricedHere -> {
        putJsonObject(CHOSEN_ARRANGEMENT_KEY) {
          putChosenLivingPlan(chosen)
          put(STATEMENT_KEY, statementOf(chosen))
        }
      }

      ChosenLivingPlan.NotChosen -> {}
    }
  }

  /**
   * The sentence for a resolved, priced plan. [LivingPlanSource] decides which
   * one: a per-college plan is something the family SAID about this school, and
   * their usual plan is an ASSUMPTION being made here -- which the coach must
   * name in the same breath, because `with_family` is never inferred by us
   * (RFC 152 D2a).
   */
  private fun statementOf(chosen: ChosenLivingPlan.Priced): String =
    when (chosen.source) {
      LivingPlanSource.PER_COLLEGE -> {
        "The student said they plan on ${chosen.plan.label} at this school, so lead with that way of " +
          "living and name it. The other ways of living are still here and still true if they ask."
      }

      LivingPlanSource.PROFILE_DEFAULT -> {
        "${chosen.plan.label.replaceFirstChar { it.uppercase() }} is the student's usual plan, assumed " +
          "for this school - say the assumption in the same breath as the number, and if they correct it, " +
          "record it as this school's own plan. The other ways of living are still here and still true."
      }
    }

  /**
   * The sentence for a resolved plan this school shows but cannot total.
   *
   * One sentence per [NoTotalReason], because the causes are not one fact: a gap
   * of OURS says so and says what would close it, and only the school's own
   * silence is stated as the school's. Saying "this school has no total for that
   * way of living" because WE have not asked which state the student lives in
   * would blame a price list for our own question (RFC 149 D-B).
   *
   * Every case ends the same way: quote the parts that are here, and never add
   * up what is there and call it the total.
   */
  private fun statementOf(chosen: ChosenLivingPlan.NoTotalHere): String =
    when (chosen.reason) {
      NoTotalReason.AWAITING_RESIDENCY_ANSWER -> {
        "The student plans on ${chosen.plan.label}, and this school's published price depends on which state " +
          "the student is a resident of - which we have not been told, so the gap is ours and not this " +
          "school's. Ask where the student is a resident and the total follows; meanwhile quote the parts " +
          "that are here and never add up what is there and call it the total."
      }

      NoTotalReason.TUITION_APPLICABILITY_UNKNOWN -> {
        "The student plans on ${chosen.plan.label}, but we cannot tell which of this school's published " +
          "prices applies to this student, so the missing total is ours and not this school's. Quote the " +
          "parts that are here and never add up what is there and call it the total."
      }

      NoTotalReason.PART_NOT_PUBLISHED -> {
        "The student plans on ${chosen.plan.label}, but this school does not publish every part of what that " +
          "way of living costs, so there is no total for it. Quote the parts of it that are here, say which " +
          "part is missing, and never add up what is there and call it the total."
      }
    }

  /**
   * The sentence for a resolved plan this school is not priced for. Two
   * reasons, two sentences (the [ArrangementGap] split RFC 151 landed): a
   * school with no residence halls has ANSWERED, and a school that published no
   * figure has stayed silent. Neither becomes a substituted arrangement.
   */
  private fun statementOf(chosen: ChosenLivingPlan.NotPricedHere): String {
    // The gap's own spoken phrase ([ArrangementGap.phrase]), never a second
    // wording of it: the comparison says the same silence the same way.
    return "The student plans on ${chosen.plan.label}, but this school has ${chosen.reason.phrase}. Say that " +
      "plainly, and never quote another way of living in its place or carry a figure across from another school."
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
            CostField.listInDeclarationOrder(dated).forEach { add(JsonPrimitive(it.wireName)) }
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
        putJsonObject(arrangement.arrangement.value) {
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
      PrecisionOffer.LIVING_PLAN -> LIVING_PLAN_OFFER
    }

  /**
   * The `net_price` sub-object: `amount_usd` when reported, the basis label, the
   * band's code and its spoken dollar range on a band-specific basis
   * (`IncomeBand.bracket`, RFC 142), and — when we hold the figure back — the
   * reason and the words for it. The label rides beside the code so the model
   * never has to invent a phrase for the bucket it is naming aloud.
   *
   * A withheld figure SAYS SO HERE, and not only in [WITHHELD_FIGURES_KEY]: an
   * object carrying a basis and no amount is otherwise indistinguishable from
   * the school's own silence, which is the very conflation the domain type
   * removed.
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

        // The basis the family's own answer selected rides above, so the label
        // that basis PROMISES rides with it -- a `your_income_band` object with
        // no band label would break an invariant the prompt states. Only the
        // amount is withheld, and the reason for that is said right here.
        is NetPrice.Withheld -> {
          putBasisLabel(netPrice.publishedBasis)
          put(WITHHELD_REASON_KEY, netPrice.reason.value)
          put(STATEMENT_KEY, netPrice.reason.statement)
        }
      }
    }

  /**
   * The qualifier one net-price BASIS owes, whether or not an amount came with
   * it: the band's code and spoken range on the band-specific basis, nothing on
   * the all-family average.
   *
   * Exhaustive, so a basis added to the vocabulary must say what label it ships.
   */
  private fun JsonObjectBuilder.putBasisLabel(basis: NetPriceBasis) {
    when (basis) {
      is NetPriceBasis.YourIncomeBand -> putIncomeBand(basis.band)
      NetPriceBasis.OverallAverage -> Unit
    }
  }

  /**
   * The money-profile echo: all three field statuses, values only when answered.
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
      putComparedLivingPlan(profile.living)
    }

  /**
   * The living-plan answer as the coach reads it: the status always, and the
   * plan itself only on the answered case.
   *
   * One emitter for the two objects that echo it (the money-profile block and
   * the comparison's arrangement basis), and exhaustive with no `else`, so the
   * plan is reachable only through [ComparedLivingPlan.Answered] -- which is the
   * whole reason that type is sealed.
   */
  private fun JsonObjectBuilder.putComparedLivingPlan(answer: ComparedLivingPlan) {
    put("living_plan_status", answer.status.value)
    when (answer) {
      is ComparedLivingPlan.Answered -> {
        putLivingPlan(answer.plan)
      }

      ComparedLivingPlan.Unanswered, ComparedLivingPlan.Declined -> {}
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(CollegeCostChatTool::class.java)

    const val TOOL_NAME = "college_cost_profile"

    /**
     * The attribution the coach must quote when using these numbers — unchanged
     * on the wire, and now a delegating alias for [CostSources], which is where
     * the fact actually lives. A parent-facing page cites the same source and
     * must not couple to this chat boundary to do it.
     *
     * It names the source and nothing else (RFC 149 D-E). It used to append
     * "(data ingested 2026)", which was `colleges.updated_at` -- WHEN WE LOADED
     * THE FILE, not the year of the figures -- and the coach read it aloud as a
     * vintage. The real vintage now rides per college, beside the figures it
     * governs, as a [ScorecardVintage] academic-year label.
     */
    const val SOURCE_ATTRIBUTION = CostSources.SCORECARD_ATTRIBUTION

    /** The wire key carrying the per-arrangement price split (RFC 149). */
    const val BREAKDOWN_KEY = "cost_by_living_arrangement"

    /**
     * The way of living this school's answer leads with (RFC 152) -- absent
     * when the family has said nothing about where they plan to live.
     */
    const val CHOSEN_ARRANGEMENT_KEY = "chosen_living_arrangement"

    /**
     * The resolved way of living itself, inside [CHOSEN_ARRANGEMENT_KEY] and
     * inside [CHOSEN_BY_COLLEGE_KEY]: the wire name of the arrangement.
     *
     * Constants rather than literals, like every other key the DESCRIPTION
     * quotes back to the model: a rename must move the payload and the prompt
     * together, or the coach is told to read a key nothing emits.
     */
    const val ARRANGEMENT_KEY = "arrangement"

    /** The resolved arrangement in the student's own words -- the paired-label convention. */
    const val ARRANGEMENT_LABEL_KEY = "label"

    /** Where the resolved plan came from ([LivingPlanSource]): the family said it here, or it is their usual plan. */
    const val ARRANGEMENT_SOURCE_KEY = "source"

    /** WHICH of the four resolved-plan cases this object is in ([LivingPlanPricing]) -- the code beside the statement. */
    const val PRICING_KEY = "pricing"

    /** Why this SCHOOL is not priced for the resolved plan ([ArrangementGap]) -- never a substituted arrangement. */
    const val ARRANGEMENT_REASON_KEY = "reason"

    /**
     * Why a plan this school DOES show carries no total ([NoTotalReason]) -- its
     * own key, because two of its three causes are gaps of ours and
     * [ARRANGEMENT_REASON_KEY]'s vocabulary states what the school published.
     * One key per vocabulary, so no reader has to know which case it is in to
     * know which words it is reading.
     */
    const val NO_TOTAL_REASON_KEY = "no_total_reason"

    /** The per-school plans inside the comparison basis: emitted only when the schools are not all on one plan. */
    const val CHOSEN_BY_COLLEGE_KEY = "chosen_by_college"

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
     * The SIXTH fact inside [COMPARISON_BASIS_KEY] (RFC 157 D-C): which
     * residency the published price and the price after a financial aid offer
     * are on, and which schools in this call they do not describe.
     *
     * Its own key beside `residency` rather than folded into it, because they
     * are two different facts about two different columns: `residency` says
     * which of a PAIR of published tuition figures this family is charged, and
     * this one says that the two blended figures have no pair at all.
     */
    const val BLENDED_FIGURE_BASIS_KEY = "blended_figure_basis"

    /**
     * Whether the two blended figures describe THIS family at one school --
     * written only when the answer is known. [putBlendedFiguresApply] owns that
     * rule and says why.
     */
    const val APPLIES_KEY = "applies_to_this_family"

    /**
     * The figures we hold and are not showing this family, each with its reason
     * (RFC 157 D-A) -- absent, never empty. Its presence is the ONE signal that
     * a [DATA_AVAILABILITY_KEY] entry is ours rather than the school's silence.
     */
    const val WITHHELD_FIGURES_KEY = "withheld_figures"

    /**
     * The fields this answer carries NO number for, whichever of the two reasons
     * it is -- absent, never empty, like every other list in this payload.
     */
    const val DATA_AVAILABILITY_KEY = "data_availability"

    /**
     * WHICH of the three blended-figure outcomes applies at one school
     * ([BlendedFigureApplicability]), ALWAYS written beside the known-only
     * [APPLIES_KEY] boolean.
     *
     * The boolean has two readable values and the fact has three, so its
     * absence would have to carry the third -- and absence has two causes here
     * (the residency question is open, or we did not recognise the control),
     * which a reader cannot tell apart. The code says which, in one field, with
     * nothing to remember.
     */
    const val APPLIES_BASIS_KEY = "applies_to_this_family_basis"

    /** Why one figure is withheld ([WithheldReason]) -- beside the field it is withheld for. */
    const val WITHHELD_REASON_KEY = "reason"

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

    /**
     * The living-plan invitation (RFC 152 D4): present exactly when the plan is
     * unanswered AND this school is priced for at least two ways of living, so
     * the question always has something to choose between -- and absent after a
     * decline, so the coach is never cued to reopen a closed topic
     * ([CollegeCostProfile.precisionOffersFor]).
     *
     * It promises no number, only a narrowing: three price pictures become one.
     */
    const val LIVING_PLAN_OFFER =
      "This school is priced for more than one way of living, and the student's USUAL plan is not on file. " +
        "If they say where they plan to live when they have the choice (record it with " +
        "${MoneyProfileChatTool.TOOL_NAME}), every school they have not decided separately about can lead " +
        "with that one price picture instead of three."

    // The ethos contract rides the tool description (RFC 135): real numbers
    // with a named source, the basis always labeled, never re-raise a decline.
    // Not `const`: the example band range is rendered from IncomeBand.bracket,
    // the one home for that copy (RFC 142), so the description can never quote
    // a range the results themselves no longer carry.
    val DESCRIPTION =
      "Read the real cost facts for the colleges on the student's list: sticker cost, tuition, " +
        "the net price their family would actually pay, median debt and median earnings. " +
        "Data comes from the U.S. Department of Education College Scorecard - always attribute figures " +
        "to it, and when a field appears in $DATA_AVAILABILITY_KEY there is no number for it in this result: " +
        "say so plainly, never estimate. That happens for one of two reasons, and they are not the same fact - " +
        "either the college does not report the field, or it is a figure that does not describe this family and " +
        "was withheld, which a $WITHHELD_FIGURES_KEY entry names and explains. Never say a school reported " +
        "nothing when the entry is ours. Every college carries $APPLIES_BASIS_KEY, which says which of three " +
        "states it is in: ${BlendedFigureApplicability.APPLIES.value} means the published price and the price " +
        "after a financial aid offer are this family's there, ${BlendedFigureApplicability.WITHHELD.value} " +
        "means they are withheld, and ${BlendedFigureApplicability.BASIS_STATED.value} means we cannot say " +
        "whose they are - either we have not asked where the family lives, or the data does not record what " +
        "kind of school it is - so both figures are shown on the in-state basis with that basis said. " +
        "$APPLIES_KEY rides beside it as a true/false only when the answer is known. " +
        "Each net_price is an object carrying amount_usd (whole US dollars per " +
        "academic year) and a basis: your_income_band means it is " +
        "specific to the student's answered household income band; overall_average means the band is not " +
        "on file and the figure is the all-family average - say which it is. When a net price is band-specific it " +
        "also carries income_band_label, the band's dollar range in plain words (e.g. \"${IncomeBand.OVER_110K.bracket}\") - say that " +
        "range when you name the band aloud, never the income_band code and never a data-source bucket name. " +
        "A net_price with no amount_usd but a $WITHHELD_REASON_KEY is one WE withheld, never the school's " +
        "silence: it keeps its basis and its band label, and the statement beside the reason is the one to " +
        "say. " +
        "When a college's result carries " +
        "$PRECISION_OFFER_KEY, it is a list of upgrade invitations for that result, each naming the money-profile field " +
        "it would fill (${MoneyProfileChatTool.TOOL_NAME} records them) and carrying the sentence you may say. Raise them " +
        "in the order given: ${PrecisionOffer.RESIDENCY.field} sorts first because it is the cheaper question and the " +
        "bigger correction - it selects which of the school's published prices applies - while " +
        "${PrecisionOffer.INCOME_BAND.field} makes " +
        "the net price family-specific and ${PrecisionOffer.LIVING_PLAN.field} narrows three price pictures to one. " +
        "money_profile.residency_status is the authority on whether to raise residency, " +
        "money_profile.income_band_status the authority on whether to raise income, and " +
        "money_profile.living_plan_status the authority on whether to raise the living plan: " +
        "declined means the student said no - never re-raise it yourself; answered means the field is already on file. " +
        "A college result may also carry ${MeritAidWire.KEY}, from that school's own Common Data Set and cited " +
        "separately from the Scorecard figures: ${MeritAidWire.SHARE_KEY} is a share of ALL full-time freshmen " +
        "at that school - never a share of the students with no financial need, which no school reports - and " +
        "${MeritAidWire.AVERAGE_KEY} is what last year's recipients averaged, not an offer to this student, so " +
        "never subtract it from any price here. Its absence means only that this school does not report it. " +
        "A college result may also carry $BREAKDOWN_KEY, the published price split into the parts a family can " +
        "actually influence, keyed by where the student would live: " +
        "${LivingArrangement.ON_CAMPUS.value}, ${LivingArrangement.OFF_CAMPUS.value}, " +
        "${LivingArrangement.WITH_FAMILY.value}. Each " +
        "arrangement carries the tuition and fees line that applies to this student and the school's published " +
        "allowances for that way of living, and $TOTAL_KEY only when every part of it is reported - when there is " +
        "no $TOTAL_KEY, say the parts and say a part is missing, never add up what is there and call it the total. " +
        "${LivingArrangement.WITH_FAMILY.value} carries no housing and food line because no school publishes " +
        "one for a student living at home; " +
        "that is missing data about the arrangement, never a housing cost of zero. " +
        "${CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD.wireName} is a separate figure - an average blended " +
        "across all three arrangements, from an earlier year, and at a public school built for students paying " +
        "in-state tuition, so never compare it with an arrangement total and " +
        "never present one as the other. ${CostField.NET_PRICE.wireName} is on that same in-state basis at a " +
        "public school. Never offer either of them to a family from another state as their price: at a public " +
        "school outside the family's state neither figure is in this result at all, and the arrangement totals in " +
        "$BREAKDOWN_KEY, built from that school's out-of-state tuition and fees, are the figures that apply to " +
        "them, so quote those instead. A private school publishes one price, so the question does not arise " +
        "there. When the state the family lives in is not on file, both figures ARE shown: say what basis they " +
        "are on and never gate an answer on the question. Aid applies to the whole price and not to any one part of it, so never " +
        "subtract ${CostField.NET_PRICE.wireName} from tuition or from any of these components, or one from the " +
        "other. When a college carries \"$OFFERS_ON_CAMPUS_HOUSING_KEY\": false, IPEDS reports that school has no " +
        "residence halls: say so, and do not treat the absent ${LivingArrangement.ON_CAMPUS.value} arrangement as " +
        "unreported data. If that college nevertheless carries an ${LivingArrangement.ON_CAMPUS.value} " +
        "arrangement, the school published those figures itself and the two sources disagree: quote the published " +
        "figures and say the school reports no on-campus housing, never one fact without the other. " +
        "\"$OFFERS_ON_CAMPUS_HOUSING_KEY\": true means IPEDS reports the school does have on-campus housing, so a " +
        "missing ${LivingArrangement.ON_CAMPUS.value} arrangement there is unreported cost data rather than the " +
        "absence of dorms. When the key is absent altogether IPEDS does not say either way - never read a present or " +
        "missing ${LivingArrangement.ON_CAMPUS.value} arrangement as the answer. " +
        "A college result may also carry $CHOSEN_ARRANGEMENT_KEY: the one way of living the family has said " +
        "they plan on at this school. Lead with it, name it in the student's own words from its " +
        "$ARRANGEMENT_LABEL_KEY. Its $ARRANGEMENT_SOURCE_KEY says where the plan came from: " +
        "${LivingPlanSource.PER_COLLEGE.value} means the student said it about THIS school; " +
        "${LivingPlanSource.PROFILE_DEFAULT.value} means it is their usual plan being assumed here, so name the " +
        "assumption in the same breath - never assume living at home for a school silently - and if they correct " +
        "you, record it as that school's own plan with ${CollegeListChatTool.TOOL_NAME}. Its $PRICING_KEY says " +
        "which case this school is in, and you read that code rather than guessing from which keys are there: " +
        "\"${LivingPlanPricing.PRICED.value}\" carries the $TOTAL_KEY to lead with; " +
        "\"${LivingPlanPricing.NOT_PRICED_HERE.value}\" carries an $ARRANGEMENT_REASON_KEY and no price, because " +
        "this school is not priced for that way of living at all: say the reason plainly, never quote a " +
        "different arrangement in its place, never carry a neighbour's figure across; " +
        "\"${LivingPlanPricing.NO_TOTAL_HERE.value}\" means this school shows that way of living but no total is " +
        "settled for it, and its $NO_TOTAL_REASON_KEY says whose gap that is - " +
        "\"${NoTotalReason.AWAITING_RESIDENCY_ANSWER.value}\" and " +
        "\"${NoTotalReason.TUITION_APPLICABILITY_UNKNOWN.value}\" are OUR gaps, so never say this school " +
        "published no price, while \"${NoTotalReason.PART_NOT_PUBLISHED.value}\" is a part this school does not " +
        "publish - in every one of those cases quote the parts of it in $BREAKDOWN_KEY, say which part is " +
        "missing, and never add up what is there and call it the total. Its $STATEMENT_KEY says the same case in " +
        "words, every time. The other ways " +
        "of living stay in $BREAKDOWN_KEY and stay true - answer a \"what if we lived at home instead\" from them. " +
        "When the key is absent the family has said nothing about where they will live: show every way of living " +
        "the school publishes, each named, exactly as before. " +
        "${ScorecardVintage.PUBLISHED_PRICE.wireName} and ${ScorecardVintage.BLENDED_AVERAGE.wireName} each carry an " +
        "$ACADEMIC_YEAR_KEY (e.g. \"${ScorecardVintage.PUBLISHED_PRICE.label}\") and the $DATED_FIGURES_KEY it dates: " +
        "quote a number with the year of the key that lists it, never with the other one, and never add figures from " +
        "the two different years together. A figure named by neither key has no academic year - " +
        "${CostField.MEDIAN_DEBT_AT_COMPLETION_USD.wireName} and " +
        "${CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD.wireName} describe cohorts rather than one price year - " +
        "so say those numbers without a year rather than borrowing one from another figure. " +
        "When the result carries two or more colleges it also carries $COMPARISON_BASIS_KEY, the assumptions a " +
        "side-by-side holds constant: who the figures describe, the residency (stated per school under " +
        "by_college), which residency the blended figures are on ($BLENDED_FIGURE_BASIS_KEY, also stated per " +
        "school, with $APPLIES_KEY saying whether those two figures are this family's there), the way of living " +
        "every school here is priced for, the academic years, and what aid means " +
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
