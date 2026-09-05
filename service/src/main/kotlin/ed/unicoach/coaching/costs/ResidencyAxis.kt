package ed.unicoach.coaching.costs

/**
 * WHOSE RESIDENCY a College Scorecard cost figure is on (RFC 157 D-D).
 *
 * The third axis a blended figure is isolated on, and the only one that was
 * unmodelled. [CostField] already carried the arrangement axis (a blended
 * average across the ways of living) and the reporting-year axis ([FigureGroup],
 * then a `ScorecardVintage` constant). `COSTT4_A` and the `NPT4` family are isolated on a THIRD:
 * at a public institution the Scorecard builds both of them for students paying
 * the in-state (strictly in-state or in-district) tuition rate, and publishes no
 * out-of-state counterpart of either.
 *
 * That fact was true from the first ingest and was stated nowhere: not in the
 * schema comments, not in the enum, not in any basis sentence. So we printed an
 * in-state published price beside residency-correct out-of-state arrangement
 * totals, with nothing separating them. A WA family at UC San Diego read $38,701
 * where their own published price is near $77,102.
 *
 * It lives on [CostField] rather than in a renderer because the residency axis
 * is a property of the FIELD's own vocabulary, unlike the figure group, which is
 * derived from the field's canonical address: a figure added to the vocabulary
 * must SAY which residency it is on before it can compile, instead of borrowing
 * the residency of whatever was printed above it.
 *
 * NULL on [CostField] means the figure has no residency axis at all -- the six
 * published components, median debt, median earnings. A null is "residency does
 * not apply to this figure", never "we did not check".
 */
enum class ResidencyAxis {
  /**
   * Published ONLY on the in-state basis, with no out-of-state counterpart
   * anywhere in the source: `COSTT4_A` and the `NPT4*` family.
   *
   * Deliberately NOT [IN_STATE]. The tuition figure below is one of a PAIR, so
   * the family's residency selects between them and the unselected one is still
   * a real published figure. These two have no pair: at a public school whose
   * state the family does not live in, there is no out-of-state version of them
   * to show instead, which is exactly why RFC 157 D-A withholds them rather than
   * substituting anything.
   */
  IN_STATE_ONLY,

  /** One half of the published tuition PAIR: the figure for a family living in this school's state. */
  IN_STATE,

  /**
   * The THIRD published tier (RFC 166 §4): the figure for a student living in
   * the school's own district, which `price_figures` has carried as
   * `in_district` since RFC 158 and which no consumer surface has ever shown.
   *
   * Deliberately NOT [IN_STATE_ONLY], and the distinction is load-bearing:
   * [CostField.IN_STATE_ONLY_FIELDS] is DERIVED from this axis, so an
   * in-district figure filed under the wrong member would be silently withheld
   * from every family whose state does not match -- the exact opposite of what
   * adding the tier is for.
   *
   * It is also never the tuition line inside a total. A family's answered STATE
   * cannot select a DISTRICT price -- a state answer does not answer a district
   * -- and no new question is asked to close that gap (value before ask). The
   * figure is shown as a labelled tier and nothing more.
   */
  IN_DISTRICT,

  /** The other half of the pair: the figure for a family living anywhere else. */
  OUT_OF_STATE,
}
