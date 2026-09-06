package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.CollegeFigures
import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.ServedFigures
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.coaching.costs.canonical.figureGroup
import ed.unicoach.coaching.costs.canonical.servedAt
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
 * [LivingArrangement.WITH_FAMILY] carries a housing-and-food component whose
 * amount is OURS (RFC 166 §7, gate-2 D17), and that is the one reversal in this
 * map's history. No source publishes a with-family food-and-housing figure --
 * IPEDS assumes zero and publishes no variable at all -- and this used to be
 * read as missing data, so the arrangement carried one fewer part and never
 * totalled. It is not missing data: eating at home is not free, but it is not a
 * new cost that ENROLLING creates, so the at-home total counts it as zero.
 *
 * The zero is carried as [LineOrigin.ASSUMED_BY_UNICOACH], stated in words on
 * both surfaces, and named on the wire as ours. No `price_figures` row is
 * invented for it. Every OTHER arrangement keeps both committed rules exactly:
 * no partial total, and no silent zero.
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
        CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD,
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

/**
 * This arrangement's components as they stand at ONE academic year; a component
 * bearing no value in that year is absent.
 *
 * ONE year for the whole list, never each component's own latest: an arrangement
 * is one school's one budget for one year, and a total assembled from three
 * different years would be a number no school ever published. Which year is
 * chosen is [CostBreakdown]'s decision, made once per college.
 */
fun LivingArrangement.reportedComponentsOf(served: ServedFigures): List<CostLine> {
  // A school that publishes NO PRICE ROW AT ALL has no served year
  // ([ServedFigures.servesNoPublishedPrice]), and an arrangement built out of
  // our own assumption alone would be a price nobody quoted. So it gets no
  // lines, stated once here rather than by each branch below, and every line
  // this function can build is dated -- which is why [CostLine] can require a
  // year rather than carry a null no caller may fill.
  val servedYear = served.academicYear?.label ?: return emptyList()
  return components.mapNotNull { field ->
    if (field.isAssumedByUnicoach) {
      // Ours, so it is available in every year the school publishes anything at all.
      assumedLineOf(field, servedYear)
    } else {
      served.amountOf(field)?.let {
        CostLine(field, it, servedYear, origin = LineOrigin.PUBLISHED)
      }
    }
  }
}

/**
 * True for a field whose amount is unicoach's assumption rather than any
 * publisher's figure (RFC 166 §7) -- DERIVED from the field's canonical
 * address, never a field name written out a second time.
 *
 * The address table already answers this question
 * ([FigureAddress.AssumedByUnicoach] is "no row anywhere, and never one"), and
 * this predicate is what four load-bearing sites consult: whether a component
 * line is built from the store or from us, both of [CostLine]'s `require`s, and
 * the year choice. Written as `== HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD` it
 * answered `false` for a SECOND unpublished field the compiler had just forced
 * an address for -- so that field's line would have been looked for in
 * `price_figures`, its `$0` unguarded, and our number rendered with no note
 * saying it is ours.
 */
val CostField.isAssumedByUnicoach: Boolean
  get() = figureAddress == FigureAddress.AssumedByUnicoach

/** The one assumed line this domain may build, at [year] so it dates with the budget it belongs to. */
private fun assumedLineOf(
  field: CostField,
  year: String,
): CostLine =
  CostLine(
    field = field,
    amountUsd = ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD,
    academicYear = year,
    origin = LineOrigin.ASSUMED_BY_UNICOACH,
  )

/**
 * The amount unicoach assumes for the one field no publisher fills (RFC 166 §7,
 * gate-2 D17): living at home creates no new food-and-housing cost, so the
 * at-home total counts it as zero.
 *
 * A named constant rather than a bare `0` at three sites, because it is the one
 * number in this domain that is ours and it should be greppable as such.
 *
 * Beside [LineOrigin] and [assumedLineOf], not in the store projection: a
 * reader auditing "which numbers are ours" reads the domain, and the canonical
 * package is a store of what publishers said -- which is exactly what this
 * number is not.
 */
const val ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD: Int = 0

/**
 * WHOSE number a cost line carries (RFC 166 §7, gate-2 D17).
 *
 * The type exists so the `$0` at-home food-and-housing line can never be read as
 * something a school published. Every other line in this domain is a figure a
 * publisher printed; exactly one is an assumption of ours, and it says so at the
 * type rather than in a comment beside the renderer.
 */
enum class LineOrigin(
  val value: String,
) {
  /** A figure the school published, read from a `price_figures` row. */
  PUBLISHED("published"),

  /** OURS: an amount we assume, named as ours everywhere it appears. */
  ASSUMED_BY_UNICOACH("assumed_by_unicoach"),
}

/**
 * One figure inside an arrangement: the shared [CostField] vocabulary, the
 * dollars, the academic year the figure describes, and whose number it is.
 *
 * [academicYear] is DATA on the line rather than a constant on the field's
 * group, because `price_figures` holds four academic years and the year served
 * differs per college (RFC 166 §3). It is NON-NULL: every figure this domain
 * prints carries the year it describes, and no site in the tree can produce an
 * undated line -- a price is read AT a year, and the assumed at-home line is
 * dated with the budget it belongs to. An undated line used to be refused at
 * runtime by [ArrangementCost] and folded into a 503; it is now unrepresentable.
 */
data class CostLine(
  val field: CostField,
  val amountUsd: Int,
  val academicYear: String,
  /**
   * WHOSE number this is. NO DEFAULT, deliberately: "the school published it"
   * is the most consequential claim on the line and the most damaging one to
   * make by omission -- a line silently labelled published is rendered with no
   * "our assumption" note, inside a total a parent reads as the school's price.
   * Every construction site says whose money it is.
   */
  val origin: LineOrigin,
) {
  /** The family of figures this line belongs to, read off the field -- never stated a second time. */
  val group: FigureGroup? get() = this.field.figureGroup

  init {
    // The `$0` is admitted for EXACTLY one field at EXACTLY one amount, and
    // refused everywhere else at construction (the [ArrangementCost] precedent).
    // Gate-2 D17 reversed the no-silent-zero rule for the at-home housing line
    // and for nothing else; without this check the reversal would be a
    // convention rather than a property, and a $0 could appear under any
    // arrangement the next caller felt like.
    require(
      origin == LineOrigin.PUBLISHED ||
        (field.isAssumedByUnicoach && amountUsd == ASSUMED_WITH_FAMILY_HOUSING_AND_FOOD_USD),
    ) {
      "unicoach assumes exactly one amount, the with-family food-and-housing zero: " +
        "field=[${field.wireName}] amount_usd=[$amountUsd] origin=[${origin.value}]"
    }

    // The converse: the assumed field has no publisher behind it, so a line for
    // it can never claim to be published.
    require(!field.isAssumedByUnicoach || origin == LineOrigin.ASSUMED_BY_UNICOACH) {
      "no source publishes a with-family food-and-housing figure, so a line for it is never published: " +
        "field=[${field.wireName}] amount_usd=[$amountUsd] origin=[${origin.value}]"
    }
  }
}

/**
 * An arrangement's lines did not share exactly ONE (figure group, academic
 * year) dating, so no budget could be built from them (RFC 149 D-F rule 3;
 * RFC 166 §3 made the year per college).
 *
 * Typed, and carrying the offending [lines] rather than a set of vintages,
 * because the diagnostic question is WHICH figure came from another reporting
 * year -- and this throw is caught into `Result.failure` by
 * [CollegeCostService.getForStudent], so its message is the whole of what an
 * operator ever sees.
 */
class MixedVintageArrangementException(
  /** WHOSE arrangement failed -- the read is batched over a whole list, so the message must name the school. */
  val collegeId: CollegeId,
  val arrangement: LivingArrangement,
  val lines: List<CostLine>,
) : IllegalArgumentException(
    "an arrangement may not sum figures of differing or unknown datings: " +
      "college_id=[${collegeId.value}] " +
      "arrangement=[${arrangement.value}] " +
      "dating_by_field=[${lines.joinToString(", ") { "${it.field.wireName}=${it.group}@${it.academicYear}" }}] " +
      "amounts_usd=[${lines.joinToString(", ") { "${it.field.wireName}=${it.amountUsd}" }}]",
  ) {
  /**
   * The datings that disagreed -- the (group, academic year) PAIRS, derived from
   * [lines] so the two can never be stated apart.
   *
   * A pair, not a group: two published-price figures from different academic
   * years are the mismatch this store made newly possible, and a set of groups
   * alone could not see it. The year is non-null, because no caller can build
   * an undated line at all.
   */
  val vintages: Set<Pair<FigureGroup?, String>> get() = lines.map { it.group to it.academicYear }.toSet()
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
  /**
   * The college this arrangement prices. Carried for the same reason
   * [CostBreakdown] carries it: this type's own invariant failures fold into
   * `Result.failure` on a read BATCHED over a student's whole list, so a
   * message with no identifier leaves the operator to reproduce the list to
   * find the school.
   */
  val collegeId: CollegeId,
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
      "an arrangement with no line is an absent arrangement, never an empty one: " +
        "college_id=[${collegeId.value}] arrangement=[${arrangement.value}]"
    }

    // The tuition SLOT holds a published tuition figure or nothing. The type is
    // CostLine, which admits every CostField; a component in this slot would
    // enter the total a second time under tuition's name and would pass every
    // other check here, because it shares the components' vintage.
    require(tuitionLine == null || tuitionLine.field in CostField.TUITION_FIELDS) {
      "the tuition line must be a published tuition figure (one of " +
        "[${CostField.TUITION_FIELDS.joinToString(", ") { it.wireName }}]), got [${tuitionLine?.field?.wireName}] " +
        "for college_id=[${collegeId.value}] arrangement=[${arrangement.value}]"
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
    // The check is over the (GROUP, ACADEMIC YEAR) pair, not the group alone
    // (RFC 166 §3). `price_figures` holds four academic years, so two figures can
    // now share the published-price group and still come from different years --
    // a mismatch the old closed enum made unrepresentable and this store makes
    // ordinary. The composer picks one year before it reads a line, so this
    // throw is unreachable from the read path BY CONSTRUCTION rather than by
    // luck; that matters, because a vintage mismatch folds into `Result.failure`
    // and surfaces as a 503 on the report.
    //
    // Checked BEFORE the component identity below on purpose: a stray figure is
    // most often a figure from another year, and that is the more useful thing
    // to be told about it.
    //
    // The YEAR half needs no null arm: [CostLine.academicYear] is non-null, so
    // an undated line is unrepresentable rather than merely refused here. The
    // GROUP half keeps its arm -- median debt and median earnings are real
    // fields with a null group, and summing one into a published-price total
    // would assert the very year RFC 149 D-E declined to give it.
    val dating = lines.map { it.group to it.academicYear }.toSet()
    if (dating.size != 1 || dating.single().first == null) {
      throw MixedVintageArrangementException(collegeId, arrangement, lines)
    }

    // The components are this arrangement's OWN, each exactly once and in
    // render order -- IDENTITY, never a count. A count alone accepts three
    // copies of the books allowance as a complete on-campus budget, or the
    // on-campus lines under WITH_FAMILY, and totals them: an at-home total
    // carrying a dorm charge. Both are published as facts about a family's
    // money, so they are refused at construction rather than filtered later.
    val fields = this.componentLines.map { it.field }
    require(fields == arrangement.components.filter { it in fields } && fields.size == fields.toSet().size) {
      "an arrangement carries only its own components, once each and in render order: " +
        "college_id=[${collegeId.value}] arrangement=[${arrangement.value}] " +
        "expected_any_of=[${arrangement.components.joinToString(", ") { it.wireName }}] " +
        "got=[${fields.joinToString(", ") { it.wireName }}]"
    }
  }

  override fun toString(): String =
    "ArrangementCost(collegeId=[${collegeId.value}], arrangement=[${arrangement.value}], tuitionLine=[$tuitionLine], " +
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
      served: ServedFigures,
      tuitionLine: CostLine?,
      offersOnCampusHousing: Boolean?,
    ): CostBreakdown? {
      val arrangements =
        LivingArrangement.entries
          .filterNot {
            it == LivingArrangement.ON_CAMPUS && isOnCampusSuppressed(served, offersOnCampusHousing)
          }.mapNotNull { arrangement -> arrangementOf(served, arrangement, tuitionLine) }
      return if (arrangements.isEmpty()) null else CostBreakdown(served.collegeId, arrangements)
    }

    /**
     * This college's figures bound to the ONE academic year its published price
     * is served at (RFC 166 §3 rule 2) -- the [ServedFigures] every read
     * downstream goes through, built HERE and nowhere else, because this is
     * where the year is decided.
     *
     * The served year is null -- [ServedFigures.servesNoPublishedPrice] -- only
     * for a college that publishes nothing at all.
     *
     * The latest year in which the applicable tuition figure AND every component
     * of SOME way of living bears a value; failing that, the latest year bearing
     * any published-price value at all. So a college whose newest year is
     * half-published is quoted a COMPLETE older budget rather than an incomplete
     * new one, and a college with no complete year anywhere shows its parts --
     * all from one year, each labelled -- and no total.
     *
     * One year for the college rather than one per arrangement, because the year
     * is STATED (rule 4) under a single wire key: two arrangements resolving to
     * two years would leave `published_price_academic_year` with no truthful
     * value to carry.
     *
     * The assumed at-home line is excluded from the candidate sets: its amount is
     * ours and does not depend on what the school published, so it must neither
     * make a year look complete nor veto one.
     */
    fun servedFiguresOf(
      figures: CollegeFigures,
      tuitionField: CostField?,
    ): ServedFigures =
      figures.servedAt(
        figures.publishedPriceYearOf(
          LivingArrangement.entries.map { arrangement ->
            (listOfNotNull(tuitionField) + arrangement.components.filterNot { it.isAssumedByUnicoach }).toSet()
          },
        ),
      )

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
      served: ServedFigures,
      offersOnCampusHousing: Boolean?,
    ): Boolean =
      offersOnCampusHousing == false &&
        LivingArrangement.ON_CAMPUS.exclusiveComponents.none { served.amountOf(it) != null }

    /**
     * True when IPEDS says this school has no residence halls and the Scorecard
     * publishes an on-campus figure for it anyway -- the contradiction D-B
     * resolves in favour of the published figure, and which [CollegeCostService]
     * logs so it stays visible rather than merely handled.
     */
    fun publishedOnCampusContradictsFlag(
      served: ServedFigures,
      offersOnCampusHousing: Boolean?,
    ): Boolean = offersOnCampusHousing == false && !isOnCampusSuppressed(served, offersOnCampusHousing)

    private fun arrangementOf(
      served: ServedFigures,
      arrangement: LivingArrangement,
      tuitionLine: CostLine?,
    ): ArrangementCost? {
      val reported = arrangement.reportedComponentsOf(served)
      // An arrangement whose ONLY line is our own assumption is not an
      // arrangement this school prices: the school has said nothing about living
      // at home, and a `$0` on its own is not a price list.
      if (reported.isEmpty() || reported.all { it.origin == LineOrigin.ASSUMED_BY_UNICOACH }) return null
      // The total is [ArrangementCost]'s own: it is computed from these lines,
      // so no assembly site can state one that disagrees with them.
      return ArrangementCost(
        collegeId = served.collegeId,
        arrangement = arrangement,
        tuitionLine = tuitionLine,
        componentLines = reported,
      )
    }
  }
}

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
 * never make that claim: three of the four causes here are gaps of OURS, and
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
  /**
   * True when the blank this reason explains is the TUITION line -- the two
   * gaps of ours that a residency answer or a control we can place would close.
   *
   * Read by [ChosenLivingPlan.NoTotalHere], which refuses a tuition reason
   * beside a tuition line that is present. Declared on the member rather than
   * listed at that call site, so a reason added later cannot be forgotten there.
   */
  val aboutTuition: Boolean,
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
    aboutTuition = true,
  ),

  /**
   * OURS: this school's control is outside the vocabulary we can place (RFC
   * 143), so we cannot say which of its published prices applies. Nothing about
   * the school's own price list is claimed.
   */
  TUITION_APPLICABILITY_UNKNOWN(
    "tuition_applicability_unknown",
    "no total, because we cannot tell which of this school's published prices applies",
    aboutTuition = true,
  ),

  /**
   * The SCHOOL's: it publishes only part of what that way of living costs, so
   * there is no total to give (RFC 149 D-C). The parts it does publish stay in
   * the breakdown, each labelled, and are never added up and called a total.
   */
  PART_NOT_PUBLISHED(
    "part_not_published",
    "no total, because a part of what that way of living costs is not published",
    aboutTuition = false,
  ),

  /**
   * OURS: a part of what that way of living costs is one WE do not hold for the
   * year this school's price is quoted at -- a `not_collected_by_us` row, or a
   * figure the school published only in another academic year (RFC 166 §3, §6).
   *
   * Separate from [PART_NOT_PUBLISHED] because that sentence is a claim about
   * the SCHOOL's price list, and this blank is not the school's. Routing a gap
   * of ours through it would tell a family the school does not publish a figure
   * the school may well publish -- the misattribution RFC 149 D-B forbids, and
   * the reason [ed.unicoach.coaching.costs.canonical.FigureStatusCopy.noTotalReasonOf]
   * exists at all.
   */
  PART_NOT_COLLECTED_BY_US(
    "part_not_collected_by_us",
    "no total, because we have not collected a part of what that way of living costs",
    aboutTuition = false,
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
     * WHY there is no total, as a code (RFC 152): three of the four causes are
     * gaps of OURS and one is the school's, and they are four different
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
      require(cost.tuitionLine == null || !reason.aboutTuition) {
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
