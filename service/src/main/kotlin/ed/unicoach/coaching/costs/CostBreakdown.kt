package ed.unicoach.coaching.costs

import ed.unicoach.db.models.College
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.LivingArrangement

/**
 * The components the Scorecard publishes for each living arrangement, in render
 * order -- the housing-and-food figure that applies, the shared books
 * allowance, and the travel-and-personal allowance for that arrangement.
 *
 * [LivingArrangement] itself moved to `:db` in RFC 152 (D1), because the
 * arrangement is now a persisted fact -- the family's usual plan on
 * `money_profiles`, a per-school override on `college_list_entries` -- and
 * `:db` cannot depend on `:service`. The mapping stayed here: which [CostField]s
 * price an arrangement is the cost domain's business and no part of what the
 * column stores. One vocabulary, one enum; two homes for two different facts.
 *
 * [LivingArrangement.WITH_FAMILY] carries NO housing-and-food component, and
 * that is data, not an omission: the Scorecard publishes no `ROOMBOARD_FAM`. A
 * `$0` housing line there would be a fabricated fact, so the arrangement simply
 * has one fewer part.
 *
 * Lazy on purpose, and not decoration: [CostField.COMPONENTS] is derived from
 * THIS map, so building it during [CostField]'s class initialisation could
 * re-enter a half-initialised [CostField]. Deferring to first access puts both
 * initialisations safely behind us. (This is the same re-entrancy the RFC 149
 * `components` constructor argument was written lazily around; moving the
 * mapping out of the enum did not remove the cycle, only its direction.)
 */
private val ARRANGEMENT_COMPONENTS: Map<LivingArrangement, List<CostField>> by lazy {
  mapOf(
    LivingArrangement.ON_CAMPUS to
      listOf(
        CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
        CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD,
      ),
    LivingArrangement.OFF_CAMPUS to
      listOf(
        CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD,
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
        CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD,
      ),
    LivingArrangement.WITH_FAMILY to
      listOf(
        CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD,
        CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD,
      ),
  )
}

/**
 * The component fields this arrangement is made of, in render order.
 *
 * Total by construction: [ARRANGEMENT_COMPONENTS] is keyed by the enum, so a
 * fourth arrangement added to `:db` without its components fails here loudly
 * rather than pricing itself out of thin air.
 */
val LivingArrangement.components: List<CostField>
  get() =
    ARRANGEMENT_COMPONENTS[this]
      ?: error("no cost components are declared for living arrangement [${this.value}]")

/**
 * The components ONLY this arrangement is priced with -- read off [components]
 * rather than listed a second time, so a change to an arrangement's parts
 * cannot leave a stale copy behind.
 *
 * The shared books-and-supplies allowance is excluded by construction: it
 * belongs to every arrangement, so its silence is still silence at a school
 * that cannot be lived at this way.
 */
val LivingArrangement.exclusiveComponents: Set<CostField>
  get() =
    components.toSet() -
      LivingArrangement.entries
        .filter { it != this }
        .flatMap { it.components }
        .toSet()

/** This arrangement's components as the college reports them; an unreported one is absent. */
fun LivingArrangement.reportedComponentsOf(college: College): List<CostLine> =
  components.mapNotNull { field -> field.amountOn(college)?.let { CostLine(field, it) } }

/** One reported figure inside an arrangement: the shared [CostField] vocabulary, and the dollars. */
data class CostLine(
  val field: CostField,
  val amountUsd: Int,
)

/**
 * An arrangement's lines did not share exactly ONE dated Scorecard vintage, so
 * no budget could be built from them (RFC 149 D-F rule 3).
 *
 * Typed, and carrying the offending [lines] rather than a set of vintages,
 * because the diagnostic question is WHICH figure came from another reporting
 * year -- and this throw is caught into `Result.failure` by
 * [CollegeCostService.getForStudent], so its message is the whole of what an
 * operator ever sees.
 */
class MixedVintageArrangementException(
  val arrangement: LivingArrangement,
  val lines: List<CostLine>,
) : IllegalArgumentException(
    "an arrangement may not sum figures of differing or unknown Scorecard vintages: " +
      "arrangement=[${arrangement.value}] " +
      "vintages_by_field=[${lines.joinToString(", ") { "${it.field.wireName}=${it.field.vintage}" }}] " +
      "amounts_usd=[${lines.joinToString(", ") { "${it.field.wireName}=${it.amountUsd}" }}]",
  ) {
  /** The vintages that disagreed, derived from [lines] so the two can never be stated apart. */
  val vintages: Set<ScorecardVintage?> get() = lines.map { it.field.vintage }.toSet()
}

/**
 * One living arrangement's price picture (RFC 149 D-C): the tuition line that
 * applies to this student, the components the school reports for this
 * arrangement, and their total.
 *
 * [totalPerYearUsd] is OWNED by this type -- computed from the lines, never
 * passed in -- and is null unless EVERY part is present: the tuition line and
 * all of [LivingArrangement.components]. A partial sum is not a total, and a
 * total presented as one would understate the price by exactly the part nobody
 * mentioned. It is also null when residency is unanswered at a public college,
 * because a total that silently picked one residency would be a lie.
 *
 * NOT a `data class`, and constructible only from lists it copies: the total is
 * an invariant over the parts, so a `copy()` that swapped the arrangement under
 * validated lines, or a caller who kept a handle on a `MutableList` and changed
 * it after [init] ran, would leave a published total disagreeing with the parts
 * printed beside it.
 */
class ArrangementCost(
  val arrangement: LivingArrangement,
  /** The published tuition figure this student's residency selects, or null when none applies. */
  val tuitionLine: CostLine?,
  componentLines: List<CostLine>,
) {
  /**
   * The arrangement's own reported components, in render order; an unreported
   * one is absent.
   *
   * A SNAPSHOT of what was handed in: a `List` parameter may be a live
   * `MutableList`, and a caller keeping that handle could otherwise change the
   * parts after the checks below passed, leaving [totalPerYearUsd] to
   * sum a list nothing validated.
   */
  val componentLines: List<CostLine> = componentLines.toList()

  /** The tuition and components in render order: tuition first, then the arrangement's own parts. */
  val lines: List<CostLine> = listOfNotNull(tuitionLine) + this.componentLines

  /**
   * The total, COMPUTED from the lines rather than passed in: a caller cannot
   * hand this type a total that contradicts the parts beside it, because there
   * is no way to hand it one at all.
   *
   * Null unless every part is present -- the tuition line and all of
   * [LivingArrangement.components]. The test may be a COUNT rather than an
   * identity test only because [init] has already refused a list that is not
   * exactly this arrangement's components: a complete list is then the only one
   * that can reach the right size, and three copies of the books allowance can
   * never be summed as an on-campus budget.
   *
   * A computed accessor, NOT a property initialiser: Kotlin runs initialisers in
   * declaration order, so a stored `val` here would be summed BEFORE the checks
   * in [init] below ran -- the ordering the sentence above depends on would be
   * false. Read after construction, it is true.
   */
  val totalPerYearUsd: Int?
    get() =
      if (tuitionLine != null && componentLines.size == arrangement.components.size) lines.sumOf { it.amountUsd } else null

  init {
    // An arrangement with no line at all is an ABSENT arrangement, never an
    // empty one -- the same rule [CostBreakdown] applies to an empty list of
    // arrangements. Stated before the vintage check so the emptiness is named
    // as itself rather than reported as "no vintage".
    require(lines.isNotEmpty()) {
      "an arrangement with no line is an absent arrangement, never an empty one: arrangement=[${arrangement.value}]"
    }

    // The tuition SLOT holds a published tuition figure or nothing. The type is
    // CostLine, which admits all twelve fields; a component in this slot would
    // enter the total a second time under tuition's name and would pass every
    // other check here, because it shares the components' vintage.
    require(tuitionLine == null || tuitionLine.field in CostField.TUITION_FIELDS) {
      "the tuition line must be a published tuition figure (one of " +
        "[${CostField.TUITION_FIELDS.joinToString(", ") { it.wireName }}]), got [${tuitionLine?.field?.wireName}] " +
        "for arrangement=[${arrangement.value}]"
    }

    // RFC 149 D-F rule 3, enforced by construction rather than remembered: an
    // arrangement is one school's one budget for one year, so a figure from
    // another reporting year cannot be a line in it -- and therefore cannot
    // reach the total either. This is what keeps COSTT4_A and the NPT4 family
    // structurally unable to enter a breakdown.
    //
    // EXACTLY one, not at most one: "no vintage at all" is the empty-count hole
    // that would let an undated list through, and it is refused here rather
    // than tolerated as a wildcard. An undated figure (median debt, median
    // earnings) has no established reporting year, so it can never be shown to
    // share one with a dated line; summing it into a published-price total
    // would assert the very year D-E declined to give it.
    //
    // Checked BEFORE the component identity below on purpose: a stray figure is
    // most often a figure from another year, and that is the more useful thing
    // to be told about it.
    val vintages = lines.map { it.field.vintage }.toSet()
    if (vintages.size != 1 || null in vintages) throw MixedVintageArrangementException(arrangement, lines)

    // The components are this arrangement's OWN, each exactly once and in
    // render order -- IDENTITY, never a count. A count alone accepts three
    // copies of the books allowance as a complete on-campus budget, or the
    // on-campus lines under WITH_FAMILY, and totals them: an at-home total
    // carrying a dorm charge. Both are published as facts about a family's
    // money, so they are refused at construction rather than filtered later.
    val fields = this.componentLines.map { it.field }
    require(fields == arrangement.components.filter { it in fields } && fields.size == fields.toSet().size) {
      "an arrangement carries only its own components, once each and in render order: " +
        "arrangement=[${arrangement.value}] " +
        "expected_any_of=[${arrangement.components.joinToString(", ") { it.wireName }}] " +
        "got=[${fields.joinToString(", ") { it.wireName }}]"
    }
  }

  override fun toString(): String =
    "ArrangementCost(arrangement=[${arrangement.value}], tuitionLine=[$tuitionLine], " +
      "componentLines=[$componentLines], totalPerYearUsd=[$totalPerYearUsd])"
}

/**
 * The per-college cost breakdown keyed by living arrangement (RFC 149).
 *
 * Computed in [CollegeCostService], not in [CollegeCostChatTool]: the totals are
 * domain truth, and the service's own tests reach them without a JSON round
 * trip. The tool is a renderer.
 *
 * An arrangement with no reported component at all is ABSENT from
 * [arrangements] rather than present and empty -- a school that reports nothing
 * for a way of living has not said it costs nothing.
 */
class CostBreakdown private constructor(
  /**
   * The college this breakdown describes. Carried so the type's own invariant
   * failure can say WHOSE breakdown failed: a sentence with no identifier is
   * nothing an operator can grep for.
   */
  val collegeId: CollegeId,
  arrangements: List<ArrangementCost>,
) {
  /** A SNAPSHOT of the arrangements handed in, so no caller keeps a live handle on this breakdown's parts. */
  val arrangements: List<ArrangementCost> = arrangements.toList()

  init {
    require(this.arrangements.isNotEmpty()) {
      "a breakdown with no arrangement is an absent breakdown, never an empty one: college_id=[${collegeId.value}]"
    }
  }

  override fun toString(): String = "CostBreakdown(collegeId=[${collegeId.value}], arrangements=[$arrangements])"

  companion object {
    /**
     * Assembles the breakdown for one college.
     *
     * [tuitionLine] is the figure the student's own residency selects (null when
     * residency is unanswered at a public college, or when this school does not
     * publish the figure that applies) -- so it gates the totals without gating
     * the components, which are true whoever is reading them.
     *
     * [offersOnCampusHousing] drops the on-campus arrangement only when
     * [isOnCampusSuppressed] says so: a known `false` AND nothing published to
     * show. An unknown flag changes nothing -- absence of the IPEDS fact is not
     * evidence.
     */
    fun of(
      college: College,
      tuitionLine: CostLine?,
      offersOnCampusHousing: Boolean?,
    ): CostBreakdown? {
      val arrangements =
        LivingArrangement.entries
          .filterNot { it == LivingArrangement.ON_CAMPUS && isOnCampusSuppressed(college, offersOnCampusHousing) }
          .mapNotNull { arrangement -> arrangementOf(college, arrangement, tuitionLine) }
      return if (arrangements.isEmpty()) null else CostBreakdown(college.id, arrangements)
    }

    /**
     * Whether the IPEDS no-dorms flag suppresses the on-campus arrangement (RFC
     * 149 D-B): a known `false` AND no published on-campus figure to show.
     *
     * PUBLISHED FIGURES WIN OVER THE FLAG. The two sources can disagree -- a
     * school whose `IC.ROOM` says it offers no housing may still publish
     * `ROOMBOARD_ON` and `OTHEREXPENSE_ON`. Suppressing a number the school
     * published would be the worse failure of the two, and calling it "not
     * reported" would be false about a reported figure, so the arrangement is
     * rendered from what was published and the flag still rides beside it. The
     * disagreement is logged by [CollegeCostService], never silently resolved.
     *
     * Only the arrangement's OWN components are consulted
     * ([LivingArrangement.exclusiveComponents]): the shared books allowance says
     * nothing about whether this school can be lived at on campus.
     *
     * The one home for the rule, called by both the breakdown and the
     * `data_availability` suppression, so the payload can never render an
     * arrangement it also calls inapplicable.
     */
    fun isOnCampusSuppressed(
      college: College,
      offersOnCampusHousing: Boolean?,
    ): Boolean =
      offersOnCampusHousing == false &&
        LivingArrangement.ON_CAMPUS.exclusiveComponents.none { it.amountOn(college) != null }

    /**
     * True when IPEDS says this school has no residence halls and the Scorecard
     * publishes an on-campus figure for it anyway -- the contradiction D-B
     * resolves in favour of the published figure, and which [CollegeCostService]
     * logs so it stays visible rather than merely handled.
     */
    fun publishedOnCampusContradictsFlag(
      college: College,
      offersOnCampusHousing: Boolean?,
    ): Boolean = offersOnCampusHousing == false && !isOnCampusSuppressed(college, offersOnCampusHousing)

    private fun arrangementOf(
      college: College,
      arrangement: LivingArrangement,
      tuitionLine: CostLine?,
    ): ArrangementCost? {
      val reported = arrangement.reportedComponentsOf(college)
      if (reported.isEmpty()) return null
      // The total is [ArrangementCost]'s own: it is computed from these lines,
      // so no assembly site can state one that disagrees with them.
      return ArrangementCost(arrangement = arrangement, tuitionLine = tuitionLine, componentLines = reported)
    }
  }
}

/**
 * What one [CostField] reads off a `colleges` row -- the two answers a null
 * would otherwise collapse into one.
 *
 * "This college does not report it" and "this field is not a column at all" are
 * different facts with different consequences: the first belongs in
 * `data_availability`, the second is a question the row cannot answer. A bare
 * `Int?` said both at once, which is how [CostField.NET_PRICE] could read as a
 * silence the college never kept.
 */
internal sealed interface ReportedAmount {
  /** A dollar column. [amountUsd] null IS the college's silence, and means "not reported". */
  data class Column(
    val amountUsd: Int?,
  ) : ReportedAmount

  /**
   * NO COLUMN AT ALL: this field keys a computed object rather than a column, so
   * a `colleges` row has no answer to give and its silence is not evidence of
   * anything. [CostField.NET_PRICE] is the one member today -- the basis selects
   * the column, and the selection is
   * [ed.unicoach.db.models.IncomeBand.netPriceFor]'s job. Whoever asks must
   * supply the computed figure themselves.
   */
  data object NoColumn : ReportedAmount
}

/**
 * What this field reads off a `colleges` row: THE one answer to "does this
 * college report it", and the primitive every other site derives from
 * ([CostField.amountOn], `CollegeCostService.notReportedOf`,
 * `CollegeCostChatTool`'s emitted set).
 *
 * Exhaustive with no `else` on purpose: a [CostField] added to the vocabulary
 * must fail to compile here -- the one site that owes it a column, or an
 * explicit [ReportedAmount.NoColumn] -- rather than silently reading as "not
 * reported".
 */
internal fun CostField.reportedAmountOf(college: College): ReportedAmount =
  when (this) {
    CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD -> ReportedAmount.Column(college.costOfAttendancePerYearUsd)
    CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD -> ReportedAmount.Column(college.tuitionAndFeesInStatePerYearUsd)
    CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD -> ReportedAmount.Column(college.tuitionAndFeesOutOfStatePerYearUsd)
    CostField.NET_PRICE -> ReportedAmount.NoColumn
    CostField.MEDIAN_DEBT_AT_COMPLETION_USD -> ReportedAmount.Column(college.medianDebtAtCompletionUsd)
    CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD -> ReportedAmount.Column(college.medianEarnings10yAfterEntryUsd)
    CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD -> ReportedAmount.Column(college.housingAndFoodOnCampusPerYearUsd)
    CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD -> ReportedAmount.Column(college.housingAndFoodOffCampusPerYearUsd)
    CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD -> ReportedAmount.Column(college.booksAndSuppliesPerYearUsd)
    CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD -> ReportedAmount.Column(college.otherExpensesOnCampusPerYearUsd)
    CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD -> ReportedAmount.Column(college.otherExpensesOffCampusPerYearUsd)
    CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD -> ReportedAmount.Column(college.otherExpensesWithFamilyPerYearUsd)
  }

/**
 * The dollars behind this field on a `colleges` row, DERIVED from
 * [reportedAmountOf] rather than deciding anything itself -- so a breakdown line
 * and a `data_availability` entry can never disagree about what a college
 * reports.
 *
 * A field with no column ([CostField.NET_PRICE]) answers null here because there
 * is nothing to read, NOT because the college is silent; it is therefore never a
 * breakdown line. Sites that must distinguish the two read [reportedAmountOf]
 * instead.
 */
internal fun CostField.amountOn(college: College): Int? = (reportedAmountOf(college) as? ReportedAmount.Column)?.amountUsd

/**
 * How a resolved living plan reached this school (RFC 152 D2a) -- so the
 * renderer can tell the coach whether the family said this ABOUT THIS SCHOOL or
 * whether it is their usual plan being assumed here.
 *
 * The distinction is not decoration. `with_family` is never inferred by us: it
 * applies to a school only because the family said so. When it is only their
 * usual plan, the coach names the assumption in the same breath ("assuming
 * you'd commute to UCSD") and a correction is written as that school's own
 * override -- which is the [PER_COLLEGE] case on the next turn.
 */
enum class LivingPlanSource(
  val value: String,
) {
  /** The student said this about THIS school: `college_list_entries.living_plan`. */
  PER_COLLEGE("per_college"),

  /** The student's usual plan (`money_profiles.living_plan`), assumed here because this school has no plan of its own. */
  PROFILE_DEFAULT("profile_default"),
}

/**
 * WHICH of the resolved-plan cases a payload is in (RFC 152) -- the code beside
 * the sentence, so no reader recovers the case by noticing which sibling keys
 * are absent.
 *
 * Every other fact on this surface ships a code with its words ([ArrangementGap],
 * [LivingPlanSource], `ArrangementScope`); the resolved plan now does too. One
 * member per [ChosenLivingPlan] case, and the case owns its own code
 * ([ChosenLivingPlan.pricing]), so a fourth case cannot be added without one.
 */
enum class LivingPlanPricing(
  val value: String,
) {
  /** This school prices the resolved plan: the object carries a total. */
  PRICED("priced"),

  /** This school shows the resolved plan but no total is settled for it; the object carries a no-total reason. */
  NO_TOTAL_HERE("no_total_here"),

  /** This school is not priced for the resolved plan at all; the object carries the school's own gap reason. */
  NOT_PRICED_HERE("not_priced_here"),

  /** No plan resolved for this school: no override, and no usual plan on file. */
  NOT_CHOSEN("not_chosen"),
}

/**
 * WHY a resolved plan this school shows carries no total (RFC 152) -- a code and
 * the words a coach says it in, in one home ([ArrangementGap] is the pattern,
 * deliberately NOT the vocabulary).
 *
 * [ArrangementGap] states what the SCHOOL published, and this vocabulary must
 * never make that claim: two of the three causes here are gaps of OURS, and
 * folding them into the school's price list is the misattribution RFC 149 D-B
 * forbids. So they are separate vocabularies on purpose.
 *
 * The phrase reads on its own after a school's name ("UCSD: no total yet ..."),
 * because that is how the comparison names these schools; it is never the object
 * of "this school has", which would blame the school for a gap of ours.
 */
enum class NoTotalReason(
  val value: String,
  val phrase: String,
) {
  /**
   * OURS: the family has not told us which state the student is a resident of,
   * so at a public school no published tuition figure applies yet and nothing
   * can be totalled. The question that closes it is ours to ask, and the
   * residency precision offer already rides in the same payload.
   */
  AWAITING_RESIDENCY_ANSWER(
    "awaiting_residency_answer",
    "no total yet, because we have not been told which state the student is a resident of",
  ),

  /**
   * OURS: this school's control is outside the vocabulary we can place (RFC
   * 143), so we cannot say which of its published prices applies. Nothing about
   * the school's own price list is claimed.
   */
  TUITION_APPLICABILITY_UNKNOWN(
    "tuition_applicability_unknown",
    "no total, because we cannot tell which of this school's published prices applies",
  ),

  /**
   * The SCHOOL's: it publishes only part of what that way of living costs, so
   * there is no total to give (RFC 149 D-C). The parts it does publish stay in
   * the breakdown, each labelled, and are never added up and called a total.
   */
  PART_NOT_PUBLISHED(
    "part_not_published",
    "no total, because a part of what that way of living costs is not published",
  ),
}

/**
 * The plan RESOLUTION on its own (RFC 152 D2a): which way of living applies to
 * this school, and where that plan came from -- before anything is known about
 * whether this school prices it.
 *
 * Its own type rather than a `Pair`, so the two halves are named at every call
 * site: a plan paired with the wrong source is the difference between "you told
 * us this" and "we assumed this", which is the one thing the coach must never
 * get backwards.
 */
data class PlannedLivingPlan(
  val plan: LivingArrangement,
  val source: LivingPlanSource,
)

/**
 * The way of living this school's answer LEADS with, resolved once in
 * [CollegeCostService] and carried on [CollegeCost] rather than re-derived by
 * the renderer (RFC 152).
 *
 * It never filters (D2): [CostBreakdown] keeps emitting all three arrangements,
 * because they are true facts and a "what if we lived at home instead?" must
 * stay answerable from the same result. This type only says which one to lead
 * with -- or why there is none to lead with.
 */
sealed interface ChosenLivingPlan {
  /**
   * WHICH case this is, as a code (RFC 152) -- declared on the interface so a
   * case added later cannot ship without one, and read by the renderer rather
   * than re-decided there.
   */
  val pricing: LivingPlanPricing

  /**
   * A plan resolved, and this school PRICES it: the arrangement to lead with,
   * and where the plan came from.
   *
   * "Prices it" means the arrangement carries a total, not merely that a row
   * for it exists -- the same rule the living-plan precision offer keys off. An
   * arrangement this school SHOWS but cannot total is [NoTotalHere] with its
   * [NoTotalReason], and an arrangement it does not show at all is
   * [NotPricedHere] with its [ArrangementGap]; neither is a priced plan
   * carrying a silent hole where its number should be.
   */
  data class Priced(
    val cost: ArrangementCost,
    val source: LivingPlanSource,
  ) : ChosenLivingPlan {
    init {
      // The offending cost in full, and where the plan came from: the invariant
      // is about a MISSING total, so the lines that are present and the ones
      // that are not are the whole of the diagnosis, and the source says whose
      // data is wrong.
      require(cost.totalPerYearUsd != null) {
        "a priced plan must carry a total; a part-published arrangement is NoTotalHere: " +
          "source=[${source.value}] cost=[$cost]"
      }
    }

    override val pricing: LivingPlanPricing get() = LivingPlanPricing.PRICED

    val plan: LivingArrangement get() = cost.arrangement

    /** The price this school puts on the chosen way of living. Never null: the [init] above is why. */
    val totalPerYearUsd: Int get() = checkNotNull(cost.totalPerYearUsd)
  }

  /**
   * A plan resolved, but this school is not priced for it. The reason is said
   * plainly and a different arrangement is NEVER substituted -- the
   * [ArrangementGap] vocabulary is reused rather than cloned, so "no residence
   * halls" and "the school did not publish it" stay two different sentences.
   *
   * "Not priced for it" is the school publishing NO figure at all for the plan,
   * so the arrangement is absent from the breakdown entirely. A school that
   * SHOWS the way of living but settles no total for it (RFC 149 D-C) is
   * [NoTotalHere] instead, carrying a [NoTotalReason]: that blank may be a gap
   * of OURS, and [ArrangementGap]'s vocabulary may only ever claim what the
   * SCHOOL published.
   */
  data class NotPricedHere(
    val plan: LivingArrangement,
    val source: LivingPlanSource,
    val reason: ArrangementGap,
  ) : ChosenLivingPlan {
    override val pricing: LivingPlanPricing get() = LivingPlanPricing.NOT_PRICED_HERE
  }

  /**
   * A plan resolved and this school HAS that way of living, but no total for
   * it: some part of its price is not settled here (RFC 149 D-C's labelled
   * blank, or a tuition line still waiting on the family's state of residency).
   *
   * Its own case rather than a [Priced] with a hole where its number should be:
   * the coach must never be handed a plan to lead with and no price to lead
   * with it. And deliberately NOT [NotPricedHere], whose [ArrangementGap]
   * vocabulary makes a claim about what the SCHOOL published -- saying "this
   * school publishes no price for it" because WE do not know which tuition
   * applies is the misattribution RFC 149 D-B exists to forbid.
   *
   * The parts this school does publish stay in the breakdown, each labelled, so
   * the coach quotes them and never adds them up into a total.
   *
   * It carries its own [NoTotalReason], because one blank cell here has more
   * than one cause and they are not the same sentence: our residency question
   * still being open is a gap of OURS, while a part of the published price
   * being missing is the school's. One case with one fixed phrase said the
   * second sentence for both of them.
   */
  data class NoTotalHere(
    val cost: ArrangementCost,
    val source: LivingPlanSource,
    /**
     * WHY there is no total, as a code (RFC 152): two of the three causes are
     * gaps of OURS and one is the school's, and they are three different
     * sentences. Decided by [CollegeCostService], which holds the school's
     * control and the family's residency answer; the [init] below refuses a
     * reason that contradicts the lines beside it.
     */
    val reason: NoTotalReason,
  ) : ChosenLivingPlan {
    init {
      require(cost.totalPerYearUsd == null) {
        "an arrangement with a total is Priced, never a missing total: " +
          "arrangement=[${cost.arrangement.value}] source=[${source.value}] " +
          "total_per_year_usd=[${cost.totalPerYearUsd}] reason=[${reason.value}]"
      }
      // A tuition reason claims the tuition line is the blank; with a tuition
      // line present it would state a cause that is not there, and the coach
      // would ask a family for an answer that changes nothing.
      require(cost.tuitionLine == null || reason == NoTotalReason.PART_NOT_PUBLISHED) {
        "a tuition reason needs a missing tuition line: " +
          "arrangement=[${cost.arrangement.value}] reason=[${reason.value}] cost=[$cost]"
      }
    }

    override val pricing: LivingPlanPricing get() = LivingPlanPricing.NO_TOTAL_HERE

    val plan: LivingArrangement get() = cost.arrangement
  }

  /**
   * No plan resolved: no override on this school, and a usual plan that is
   * unanswered or declined. Today's behaviour exactly (RFC 152 D3) -- all three
   * arrangements, each named and labelled.
   */
  data object NotChosen : ChosenLivingPlan {
    override val pricing: LivingPlanPricing get() = LivingPlanPricing.NOT_CHOSEN
  }
}
