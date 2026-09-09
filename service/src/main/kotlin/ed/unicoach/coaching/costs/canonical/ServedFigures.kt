package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneySource

/**
 * One college's canonical figures AT the ONE academic year this answer serves
 * them at (RFC 166 §3) -- the pair `(figures, academicYear)` bound into the
 * single value it always was.
 *
 * The year is chosen ONCE per college, by [CollegeFigures.publishedPriceYearOf] through
 * [ed.unicoach.coaching.costs.CostBreakdown.publishedPriceYearOf], and every
 * published-price read this answer makes goes through this object. Before it
 * existed the two halves travelled as two parameters through ~21 signatures, so
 * "one college, one served year" was held by argument discipline rather than by
 * a type: any `String?` type-checked, and a wrong one did not throw. It
 * DEGRADED -- [CollegeFigures.priceAt] answers null for a year the college does
 * not carry -- so a school's real price list was rendered as "this school does
 * not report it", a false claim about a named school produced by an argument
 * mistake that compiled.
 *
 * Two `require`s make that mistake unconstructable:
 *
 * - a non-null year must be a year this college actually publishes a price row
 *   in, so no read can be served at a year that was never this school's;
 * - a null year is legal ONLY for a college that publishes no price row at all
 *   ([servesNoPublishedPrice]). "No served year" is therefore a NAMED state
 *   about the school, never the silent shape a dropped or mistyped year takes.
 */
class ServedFigures(
  val figures: CollegeFigures,
  /**
   * The academic year this college's published price is served at, or null when
   * it publishes no price row at all ([servesNoPublishedPrice]).
   */
  val academicYear: AcademicYear?,
) {
  init {
    require(academicYear == null || academicYear in figures.publishedPriceYears) {
      "a college is served at a year it publishes: college_id=[${figures.collegeId.value}] " +
        "served_year=[$academicYear] published_price_years=[${figures.publishedPriceYears.joinToString(", ")}]"
    }
    require(academicYear != null || figures.publishedPriceYears.isEmpty()) {
      "a college that publishes a price is served at one of its years, never at none: " +
        "college_id=[${figures.collegeId.value}] " +
        "published_price_years=[${figures.publishedPriceYears.joinToString(", ")}]"
    }
  }

  /** The college these figures belong to -- carried so a failure can name whose answer it was. */
  val collegeId = figures.collegeId

  /**
   * True when this school publishes NO price row at all, which is the one and
   * only reason [academicYear] may be null.
   *
   * A named state rather than a bare null: "we hold no price list for this
   * school" is a fact about the school, and it used to be indistinguishable
   * from a year that went missing on its way down a call stack.
   */
  val servesNoPublishedPrice: Boolean get() = academicYear == null

  /** This field's figure at the served year -- [CollegeFigures.figureOf], with the year already bound. */
  fun figureOf(field: CostField): DatedFigure? = figures.figureOf(field, academicYear)

  /**
   * The dollars behind this field at the served year -- the one door a caller
   * reads a published price through.
   *
   * A field with no row, and a field whose row bears no value, both answer
   * null; a site that must distinguish them asks [figureOf] for the reading
   * itself, and a site that must know the school published the figure in
   * ANOTHER year asks [yearGapOf].
   */
  fun amountOf(field: CostField): Int? = figureOf(field)?.amountUsd

  /**
   * The canonical status behind this field, and the publisher that answered for
   * it -- [CollegeFigures.statusOf], with the year already bound.
   */
  fun statusOf(
    field: CostField,
    band: IncomeBand?,
  ): FigureProvenance? = figures.statusOf(field, academicYear, band)

  /**
   * The distinct publishers behind the figures this college serves at this band
   * -- [CollegeFigures.servedSourcesOf], with the year already bound (RFC 177 D5).
   */
  fun servedSourcesOf(band: IncomeBand?): List<MoneySource> = figures.servedSourcesOf(academicYear, band)

  /** This address's price row at the served year -- [CollegeFigures.priceAt], with the year already bound. */
  fun priceAt(address: PriceAddress): DatedFigure? = figures.priceAt(address, academicYear)

  /** The figure this school published in ANOTHER year -- [CollegeFigures.yearGapOf], with the year already bound. */
  fun yearGapOf(field: CostField): DatedFigure? = figures.yearGapOf(field, academicYear)

  /**
   * The cohort statistic behind one field, at this band. Undated by the served
   * year on purpose ([CollegeFigures.cohortOf] owns the rule): a blended
   * average carries its own vintage and must never acquire the published-price
   * year.
   */
  fun cohortOf(
    field: CostField,
    band: IncomeBand?,
  ): DatedStat? = figures.cohortOf(field, band)

  /**
   * The vintage of the blended averages this college serves at this band --
   * [CollegeFigures.blendedAverageVintage]. A cohort label, so it does NOT take
   * the served year; it is here so a caller holding this object needs no second
   * handle on the figures.
   */
  fun blendedAverageVintage(band: IncomeBand?): AcademicYear? = figures.blendedAverageVintage(band)

  /** The cohort statistic at one canonical address and band -- the address-shaped twin of [cohortOf]. */
  fun cohortOf(
    address: CohortAddress,
    band: IncomeBand?,
  ): DatedStat? = figures.cohortOf(address, band)

  override fun toString(): String = "ServedFigures(academic_year=[$academicYear], figures=[$figures])"
}

/** This college's figures served at [academicYear] -- the one way the pair is made. */
fun CollegeFigures.servedAt(academicYear: AcademicYear?): ServedFigures = ServedFigures(this, academicYear)
