package ed.unicoach.coaching.costs

import ed.unicoach.db.models.LivingArrangement

/**
 * The cost columns of the result (RFC 135): one entry per cost figure the
 * tool renders, each carrying its wire key. One vocabulary shared by
 * [CollegeCostChatTool]'s JSON keys and [CollegeCost.notReported], so the
 * domain traffics in the typed field while `data_availability` can never name
 * a field the tool does not render.
 *
 * Each member also carries the [ScorecardVintage] of the figure behind it (RFC
 * 149 D-E/D-F). That is not decoration: it is the SINGLE classifier of which
 * academic year a figure describes -- [CollegeCostChatTool] derives the vintage
 * labels it emits from it, and [CostBreakdown] refuses to total fields that do
 * not share one, so "never sum across vintages" is a property of this enum
 * rather than a rule each site has to remember.
 *
 * The vintage is NULL for a figure RFC 149 did not date. D-E establishes exactly
 * two families -- the published price (AY2022-23) and the blended averages
 * (AY2021-22) -- and a figure on neither cohort basis gets no year rather than a
 * borrowed one. An undated figure is therefore also unsummable: it cannot be
 * shown to share a reporting year with anything.
 */
enum class CostField(
  val wireName: String,
  val vintage: ScorecardVintage?,
) {
  // COSTT4_A: a BLENDED average across living arrangements and a year older
  // than the components -- so it keeps its own key and its own label, and
  // nothing compares it to, or substitutes it for, an arrangement total
  // (RFC 149 D-F).
  STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD("sticker_cost_of_attendance_per_year_usd", ScorecardVintage.BLENDED_AVERAGE),
  TUITION_AND_FEES_IN_STATE_PER_YEAR_USD("tuition_and_fees_in_state_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD("tuition_and_fees_out_of_state_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),

  // The one entry that is NOT a bare dollar figure: it keys an OBJECT
  // (amount_usd + basis + optional band), and a container carries no unit --
  // the same rule that leaves `net_price_by_income_band` unsuffixed. The
  // scalar inside it says its own unit.
  NET_PRICE("net_price", ScorecardVintage.BLENDED_AVERAGE),

  // UNDATED, and deliberately so (RFC 149 D-E). Neither figure is on either
  // cohort basis this RFC dates: the debt figure is a completers' cohort and the
  // earnings figure a ten-years-after-entry one. Labelling them AY2021-22
  // because COSTT4_A is would be exactly the false precision the vintage work
  // exists to remove, so they carry no vintage and the tool prints no academic
  // year beside them.
  MEDIAN_DEBT_AT_COMPLETION_USD("median_debt_at_completion_usd", null),
  MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD("median_earnings_10y_after_entry_usd", null),

  // The six published cost components (RFC 149). The wire names ARE the column
  // names, so the JSON, `data_availability` and the schema speak one
  // vocabulary. Six, not seven: the Scorecard publishes no with-family housing
  // and food figure, which is why that arrangement renders no housing line
  // rather than a $0 one.
  HOUSING_AND_FOOD_ON_CAMPUS_PER_YEAR_USD("housing_and_food_on_campus_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  HOUSING_AND_FOOD_OFF_CAMPUS_PER_YEAR_USD("housing_and_food_off_campus_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  BOOKS_AND_SUPPLIES_PER_YEAR_USD("books_and_supplies_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD("other_expenses_on_campus_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD("other_expenses_off_campus_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD("other_expenses_with_family_per_year_usd", ScorecardVintage.PUBLISHED_PRICE),
  ;

  companion object {
    /**
     * The published tuition figures: the ONLY fields that may fill an
     * [ArrangementCost] tuition slot (RFC 149 D-C).
     *
     * Named here, beside the fields themselves, because the slot's type is
     * [CostLine] and so admits all twelve members. A component in that slot
     * would be summed a second time under tuition's name and would pass every
     * other check the arrangement makes, because it shares the components'
     * vintage.
     */
    val TUITION_FIELDS: Set<CostField> =
      setOf(
        TUITION_AND_FEES_IN_STATE_PER_YEAR_USD,
        TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD,
      )

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

      CostField.BOOKS_AND_SUPPLIES_PER_YEAR_USD -> ComponentRole.BOOKS_AND_SUPPLIES

      CostField.OTHER_EXPENSES_ON_CAMPUS_PER_YEAR_USD -> ComponentRole.OTHER_EXPENSES

      CostField.OTHER_EXPENSES_OFF_CAMPUS_PER_YEAR_USD -> ComponentRole.OTHER_EXPENSES

      CostField.OTHER_EXPENSES_WITH_FAMILY_PER_YEAR_USD -> ComponentRole.OTHER_EXPENSES

      // Not parts of a way of living: a blended whole-school average, the two
      // published tuition figures, the computed price after aid, and the two
      // undated outcome figures. None of them is a row in an arrangement table.
      CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD -> null

      CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD -> null

      CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD -> null

      CostField.NET_PRICE -> null

      CostField.MEDIAN_DEBT_AT_COMPLETION_USD -> null

      CostField.MEDIAN_EARNINGS_10Y_AFTER_ENTRY_USD -> null
    }
