package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.FigureAddress
import ed.unicoach.coaching.costs.canonical.figureAddress
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.PriceConcept

/**
 * The cost columns of the result (RFC 135): one entry per cost figure the
 * tool renders, each carrying its wire key. One vocabulary shared by
 * [CollegeCostChatTool]'s JSON keys and [CollegeCost.notReported], so the
 * domain traffics in the typed field while `data_availability` can never name
 * a field the tool does not render.
 *
 * The [FigureGroup] of the figure behind a member is NOT declared here. It is
 * the SINGLE classifier of which family of figures a member belongs to --
 * [CollegeCostChatTool] derives the academic-year labels it emits from it, and
 * [CostBreakdown] refuses to total fields that do not share one -- and a single
 * classifier cannot be written in two places. It is derived from the member's
 * canonical address instead
 * ([ed.unicoach.coaching.costs.canonical.figureGroup], RFC 166 §3), so a field says
 * WHERE it lives once and its group follows; the hand-typed third column this
 * enum used to carry could disagree with the address and ship a published
 * tuition price under the cohort year.
 *
 * The YEAR itself is not here either. It used to be a constant on
 * `ScorecardVintage`, which meant a store holding four academic years of prices
 * could only ever speak one of them; RFC 158 D15 is "store history, serve
 * latest", so the year now rides on the row that was served and can differ per
 * college.
 *
 * The group is NULL for a figure RFC 149 did not date. D-E establishes exactly
 * two families -- the published price and the blended averages -- and a figure
 * on neither cohort basis gets no year rather than a borrowed one. An undated
 * figure is therefore also unsummable: it cannot be shown to share a reporting
 * year with anything.
 *
 * Each member ALSO carries the [ResidencyAxis] of the figure behind it (RFC
 * 157 D-D), the third axis and the one that was unmodelled until a WA family
 * read a California family's published price at UC San Diego. It is NULL for the
 * figures that have no residency axis -- the six published components, median
 * debt, median earnings -- and a null means "residency does not apply to this
 * figure", never "we did not check". A figure added to this vocabulary now has
 * to say which residency it is on before it compiles, rather than borrowing the
 * residency of whatever the page printed above it.
 */
enum class CostField(
  val wireName: String,
  val residency: ResidencyAxis?,
) {
  // COSTT4_A: a BLENDED average across living arrangements, a year older than
  // the components, and -- at a public school -- built for students paying the
  // IN-STATE tuition rate with no out-of-state counterpart published anywhere
  // (RFC 157). So it keeps its own key and its own label, nothing compares it
  // to, or substitutes it for, an arrangement total (RFC 149 D-F), and nothing
  // shows it to a family the in-state basis does not describe (RFC 157 D-A).
  STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD(
    "sticker_cost_of_attendance_per_year_usd",
    ResidencyAxis.IN_STATE_ONLY,
  ),
  TUITION_AND_FEES_IN_STATE_PER_YEAR_USD(
    "tuition_and_fees_in_state_per_year_usd",
    ResidencyAxis.IN_STATE,
  ),
  TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD(
    "tuition_and_fees_out_of_state_per_year_usd",
    ResidencyAxis.OUT_OF_STATE,
  ),

  // The THIRD tuition tier (RFC 166 §4). `price_figures` has carried
  // `in_district` as a first-class residency basis since RFC 158 and no consumer
  // surface has ever shown it, while RFC 161 measured 269 institutions where the
  // Scorecard's "in-state" figure is really the in-district one.
  //
  // [ResidencyAxis.IN_DISTRICT] and deliberately NOT [ResidencyAxis.IN_STATE_ONLY]:
  // this is one of a published SET, not a figure with no counterpart, so the RFC
  // 157 withholding chain -- which is derived from the axis -- must not pick it
  // up. It is also never the tuition line inside a total: a family's answered
  // STATE cannot select a DISTRICT price, so it is shown as a labelled tier and
  // nothing more.
  TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD(
    "tuition_and_fees_in_district_per_year_usd",
    ResidencyAxis.IN_DISTRICT,
  ),

  // The fees split (RFC 166 §5). `PriceConcept.FEES_ONLY` (`CHG1AF`/`CHG2AF`/
  // `CHG3AF`) has been canonical-only; the consumer side has one combined
  // tuition-and-fees figure per tier and no way to say how much of it is fees.
  //
  // NOT components of any arrangement, and that is the whole care in this block:
  // `fees_only` is a part OF `tuition_and_fees`, not a part BESIDE it, so adding
  // these to `ARRANGEMENT_COMPONENTS` would double-count fees inside every
  // total. They are reported beside the combined figure and named as a split.
  FEES_ONLY_IN_DISTRICT_PER_YEAR_USD(
    "fees_only_in_district_per_year_usd",
    ResidencyAxis.IN_DISTRICT,
  ),
  FEES_ONLY_IN_STATE_PER_YEAR_USD(
    "fees_only_in_state_per_year_usd",
    ResidencyAxis.IN_STATE,
  ),
  FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD(
    "fees_only_out_of_state_per_year_usd",
    ResidencyAxis.OUT_OF_STATE,
  ),

  // The one entry that is NOT a bare dollar figure: it keys an OBJECT
  // (amount_usd + basis + optional band), and a container carries no unit --
  // the same rule that leaves `net_price_by_income_band` unsuffixed. The
  // scalar inside it says its own unit.
  //
  // IN_STATE_ONLY for the same reason as COSTT4_A above: `NPT4_PUB` and the
  // NPT41..45 band series are limited to undergraduates paying in-state tuition
  // (RFC 157). This is the more dangerous half of that defect -- the arrangement
  // totals beside it are at least residency-correct, and this one is not.
  NET_PRICE("net_price", ResidencyAxis.IN_STATE_ONLY),

  // UNDATED, and deliberately so (RFC 149 D-E). Neither figure is on either
  // cohort basis this RFC dates: the debt figure is a completers' cohort and the
  // earnings figure a ten-years-after-entry one. Labelling them AY2023-24
  // because COSTT4_A is would be exactly the false precision the vintage work
  // exists to remove, so they carry no vintage and the tool prints no academic
  // year beside them.
  //
  // They carry no residency either, and for a reason of the same kind: neither
  // is a price, so there is no tuition rate for one to have been built on.
  MEDIAN_DEBT_AT_COMPLETION_USD("median_debt_at_completion_usd", null),
  MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD("median_earnings_10y_after_entry_usd", null),

  // The SEVEN cost components (RFC 149; RFC 166 §7 added the seventh). The wire
  // names ARE the canonical concept names, so the JSON, `data_availability` and
  // the store speak one vocabulary.
  //
  // All seven carry NO residency: one books-and-supplies, one housing-and-food
  // and one other-expenses allowance per way of living, the same figure whichever
  // state the family lives in. That is why an out-of-state arrangement total is
  // obtainable at all -- out-of-state tuition and fees plus these residency-free
  // parts (RFC 157).
  HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD("housing_and_food_on_campus_per_year_usd", null),
  HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD(
    "housing_and_food_off_campus_per_year_usd",
    null,
  ),

  // The seventh, and the ONE figure in this vocabulary that is OURS (RFC 166 §7,
  // gate-2 D17). No source publishes a with-family food-and-housing figure --
  // IPEDS assumes zero and publishes no variable, and neither does the Scorecard
  // -- and that is not missing data about the school: it is a cost that
  // enrolling does not create.
  //
  // So the at-home total is complete and the food-and-housing line is `$0`, with
  // the assumption stated in words and attributed to unicoach. `CostLine`'s own
  // `init` admits [LineOrigin.ASSUMED_BY_UNICOACH] for exactly this field at
  // exactly amount `0` and refuses every other assumed line at construction, so
  // a fabricated zero remains unrepresentable everywhere else -- the rule RFC 149
  // wrote and this one field is the single, named exception to.
  HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD(
    "housing_and_food_with_family_per_year_usd",
    null,
  ),
  BOOKS_AND_SUPPLIES_PER_YEAR_USD("books_and_supplies_per_year_usd", null),
  OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD("other_expenses_on_campus_per_year_usd", null),
  OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD("other_expenses_off_campus_per_year_usd", null),
  OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD(
    "other_expenses_with_family_per_year_usd",
    null,
  ),
  ;

  companion object {
    /**
     * The published tuition figures: the ONLY fields that may fill an
     * [ArrangementCost] tuition slot (RFC 149 D-C).
     *
     * Named here, beside the fields themselves, because the slot's type is
     * [CostLine] and so admits EVERY member of [CostField]. A component in that slot
     * would be summed a second time under tuition's name and would pass every
     * other check the arrangement makes, because it shares the components'
     * vintage.
     */
    val TUITION_FIELDS: Set<CostField> =
      setOf(
        TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
        // In-district is admitted by the SLOT and never selected into it
        // ([applicableTuitionFor] cannot return it, because a family's answered
        // state does not answer a district). It is here so that the slot's rule
        // stays "a published tuition figure" rather than "the two tiers we
        // happened to have in 2026".
        TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD,
      )

    /**
     * The BLENDED figures that exist only on the in-state basis (RFC 157): the
     * published price and the net price. Exactly the two a family the in-state
     * basis does not describe must not be shown.
     *
     * DERIVED from [residency] rather than listed again, for the reason
     * [COMPONENTS] is derived from the arrangements: a third in-state-only
     * figure added to the vocabulary must be withheld from a non-matching family
     * by the same rule, and a hand-written list would simply never mention it.
     *
     * A computed property rather than a stored one: it reads [entries], and this
     * companion is initialised as part of [CostField]'s own class
     * initialisation, so evaluating it there could re-enter a half-built enum --
     * the hazard [COMPONENTS] documents and defers past.
     *
     * A LIST, in declaration order, and not a `Set`: the order is already the
     * vocabulary's own here, and returning a set threw it away only for the
     * caller to rebuild it with a second [listInDeclarationOrder] pass.
     */
    val IN_STATE_ONLY_FIELDS: List<CostField>
      get() = entries.filter { it.residency == ResidencyAxis.IN_STATE_ONLY }

    /**
     * The fees split: the fields that carry the fees PART of a combined tuition
     * figure (RFC 166 §5).
     *
     * DERIVED from the canonical address rather than hand-listed, exactly as the
     * tuition tiers are read off [CollegeCost.publishedTuitionTiers]: a fourth
     * fees tier is then emitted because of what it IS, not because a third call
     * site was copied into a fourth.
     *
     * Here beside [TUITION_FIELDS], because "which fields are the fees split" is
     * a fact about the vocabulary rather than about any one wire surface. A
     * computed property for the reason [IN_STATE_ONLY_FIELDS] is one: it reads
     * [entries] from this companion's own initialisation.
     */
    val FEES_ONLY_FIELDS: List<CostField>
      get() =
        entries.filter {
          (it.figureAddress as? FigureAddress.Price)?.address?.concept == PriceConcept.FEES_ONLY
        }

    /**
     * [fields] in enum DECLARATION order, so any list this vocabulary ships is a
     * fact about the vocabulary rather than about the set, or the concatenation,
     * it was collected in.
     *
     * One home for the idiom, because it was written at three call sites and the
     * next one would have been a fourth.
     */
    fun listInDeclarationOrder(fields: Collection<CostField>): List<CostField> = entries.filter { it in fields }

    /**
     * The published components, in enum declaration order.
     *
     * DERIVED from [LivingArrangement.components] rather than listed again:
     * membership of "the components" is the arrangements' own fact, and a second
     * hand-written list would not follow a seventh component added to an
     * arrangement -- it would simply never be named in `data_availability`.
     *
     * Lazy on purpose, and not decoration: the `ARRANGEMENT_COMPONENTS` map
     * that [LivingArrangement.components] reads through is built from
     * [CostField]'s own constants, so computing this during [CostField]'s class
     * initialisation could re-enter a half-initialised [CostField]. Deferring
     * to first access puts both initialisations safely behind us. (RFC 152
     * moved [LivingArrangement] to `:db`; that moved the cycle's other end into
     * `CostBreakdown.kt`, it did not remove it.)
     */
    val COMPONENTS: List<CostField> by lazy {
      val declared = LivingArrangement.entries.flatMap { it.components }.toSet()
      entries.filter { it in declared }
    }
  }
}

/**
 * The ROW vocabulary of a per-arrangement price table: which PART of a price a
 * cost component is (RFC 155).
 *
 * A way of living has at most one field in each role, so a role is one row and
 * every column in it is the same kind of money — which is why the report page
 * renders one "Housing and food" row rather than two half-empty ones side by
 * side, one per field.
 *
 * It lives beside [CostField], not in the renderer: the mapping below is an
 * exhaustive `when` with NO `else`, so a seventh component added to the
 * vocabulary FAILS TO COMPILE until it says which part of a price it is. A
 * hand-copied list in a renderer had the opposite property — a new component
 * silently rendered no row at all while still being counted in the total.
 */
enum class ComponentRole(
  val label: String,
) {
  HOUSING_AND_FOOD("Housing and food"),
  BOOKS_AND_SUPPLIES("Books and supplies"),
  OTHER_EXPENSES("Other expenses"),
  ;

  /** This arrangement's field in this role, or null when the role is not part of that way of living. */
  fun fieldOf(arrangement: LivingArrangement): CostField? = arrangement.components.firstOrNull { it.componentRole == this }
}

/**
 * Which part of a price this field is, or null when it is not a component at
 * all (a whole-school figure, a tuition figure, an outcome figure).
 *
 * Exhaustive with no `else` on purpose: this is the ONE site that owes a new
 * [CostField] a row, and it must refuse to compile rather than let one drop out
 * of every table that sums it.
 */
val CostField.componentRole: ComponentRole?
  get() =
    when (this) {
      CostField.HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD -> ComponentRole.HOUSING_AND_FOOD

      CostField.HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD -> ComponentRole.HOUSING_AND_FOOD

      CostField.HOUSING_AND_FOOD_WITH_FAMILY_PER_YEAR_USD -> ComponentRole.HOUSING_AND_FOOD

      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD -> ComponentRole.BOOKS_AND_SUPPLIES

      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD -> ComponentRole.OTHER_EXPENSES

      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD -> ComponentRole.OTHER_EXPENSES

      CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD -> ComponentRole.OTHER_EXPENSES

      // Not parts of a way of living: a blended whole-school average, the three
      // published tuition tiers, the computed price after aid, and the two
      // undated outcome figures. None of them is a row in an arrangement table.
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD -> null

      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD -> null

      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD -> null

      CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD -> null

      // A fees figure is a part OF tuition and fees, not a part beside it: it is
      // never a row in an arrangement table and never enters a total.
      CostField.FEES_ONLY_IN_DISTRICT_PER_YEAR_USD -> null

      CostField.FEES_ONLY_IN_STATE_PER_YEAR_USD -> null

      CostField.FEES_ONLY_OUT_OF_STATE_PER_YEAR_USD -> null

      CostField.NET_PRICE -> null

      CostField.MEDIAN_DEBT_AT_COMPLETION_USD -> null

      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD -> null
    }
