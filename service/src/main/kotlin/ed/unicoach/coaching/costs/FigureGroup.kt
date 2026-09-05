package ed.unicoach.coaching.costs

/**
 * WHICH family of figures a [CostField] belongs to (RFC 166 §3), and the wire
 * key its academic year rides under.
 *
 * This is what survives of `ScorecardVintage`, and it lives in the slot that
 * enum vacated -- in the money DOMAIN rather than in the store projection
 * beside it. The classifier is what stops a family being shown a "total" made
 * of a cohort average plus a dorm charge ([ArrangementCost] refuses one), so it
 * belongs where that rule is written; `costs.canonical` is the read side of one
 * store, and holds no user of this type at all.
 *
 * `ScorecardVintage` carried a GROUPING and a YEAR, and the year was a Kotlin
 * constant -- so a repo that stores four academic years of prices could only
 * ever speak one of them. RFC 158 D15 is "store history, serve latest", so the
 * year is now read off the row that was served and only the grouping survives,
 * because the wire keys keep their names.
 *
 * CLOSED, exactly as its predecessor was: a figure on neither basis -- median
 * debt, median earnings -- carries a null group and no spoken year, and a
 * borrowed year would be the false precision this type exists to remove. An
 * undated figure is therefore also unsummable.
 *
 * Which group a field is in is never stated here or on [CostField]: it is
 * DERIVED from the field's canonical address
 * ([ed.unicoach.coaching.costs.canonical.figureGroup]), so the classifier and the
 * cell it classifies cannot disagree.
 */
enum class FigureGroup(
  /** The wire key this group's academic year rides under, beside the figures it governs. */
  val wireName: String,
) {
  /**
   * The school's own published price list for one year -- tuition and fees, the
   * fees split, and the components of living cost. These are the figures an
   * arrangement total may sum, because they are one school's one budget for one
   * year. Stored in `price_figures`.
   */
  PUBLISHED_PRICE("published_price_academic_year"),

  /**
   * `COSTT4_A` and the `NPT4*` family -- averages BLENDED across living
   * arrangements and, for net price, across aid. A different kind of number from
   * a component sum, so neither may be compared with one nor added to one.
   * Stored in `cohort_money_stats`.
   */
  BLENDED_AVERAGE("blended_average_academic_year"),
}
