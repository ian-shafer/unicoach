package ed.unicoach.coaching.costs

import ed.unicoach.coaching.AcademicYear

/**
 * The ACADEMIC YEAR each College Scorecard figure in the cost answer describes
 * (RFC 149 D-E), for the snapshot this repo pins.
 *
 * It replaces "data ingested 2026", which was `colleges.updated_at` -- WHEN WE
 * LOADED THE FILE, not the year of the figures. That string was honest about
 * freshness and dishonest about vintage, and the coach reads it aloud as though
 * it were the latter. [CollegeCostProfile.ingestYear] survives as the freshness
 * fact it always was; it is simply no longer spoken as a vintage.
 *
 * There are exactly TWO vintages here, and the enum is CLOSED on purpose: RFC
 * 149 D-E dates these two families and no others, so a figure on neither basis
 * -- median debt at completion, median earnings ten years after entry -- carries
 * a null [CostField.vintage] and no spoken year. A borrowed year would be the
 * false precision this type exists to remove.
 *
 * The split between the two is the whole point: the
 * published-price figures and the blended averages come from different reporting
 * years, so adding one to the other is adding across vintages. [CostField]
 * carries its own vintage for that reason, and [CostBreakdown] refuses to total
 * fields that do not share one -- the rule is enforced by the types rather than
 * remembered by each call site.
 *
 * The label is rendered through [AcademicYear], the shared home for
 * "2022-23"-style academic-year copy, so a bare year can never reach the model
 * and the cost domain does not borrow the admissions domain's formatter.
 *
 * A snapshot bump starts with the two [firstCalendarYear] values here, but it
 * does NOT end there: every figure's vintage assignment on [CostField] has to be
 * re-checked (a new snapshot can move a family from one vintage to the other, or
 * date a figure this RFC left undated), and `db/schema/0062`'s column comments
 * name this enum as the owner of the year rather than restating it. Treat the
 * values here as the first edit of the bump, never the whole of it.
 */
enum class ScorecardVintage(
  /** The first calendar year of the academic year, e.g. 2022 for AY2022-23. */
  val firstCalendarYear: Int,
  /** The wire key this vintage rides under, beside the figures it governs. */
  val wireName: String,
) {
  /**
   * AY2022-23: the school's own published price list for one year -- tuition and
   * fees and the six cost components. These are the figures an arrangement total
   * may sum, because they are one school's one budget for one year.
   */
  PUBLISHED_PRICE(2022, "published_price_academic_year"),

  /**
   * AY2021-22: `COSTT4_A` and the `NPT4*` family -- averages BLENDED across
   * living arrangements and, for net price, across aid. A year older than the
   * components and a different kind of number, so neither may be compared with
   * a component sum nor added to one.
   */
  BLENDED_AVERAGE(2021, "blended_average_academic_year"),
  ;

  /** "2022-23" -- the spoken label, never a bare year. */
  val label: String get() = AcademicYear(firstCalendarYear).label
}
