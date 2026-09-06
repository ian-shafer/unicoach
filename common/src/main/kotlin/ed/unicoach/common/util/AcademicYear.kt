package ed.unicoach.common.util

/**
 * One academic year, named by its first calendar year, and the ONE home for its
 * spoken label: 2024 -> "2024-25", 2099 -> "2099-00".
 *
 * A TYPE rather than a formatter over a bare `Int`, because a calendar year and
 * an academic year are different facts that share a representation. RFC 149
 * D-E's whole point is that an ingest year (a wall-clock `colleges.updated_at`
 * year, `CollegeCostProfile.ingestYear`) is NOT a vintage; while the label took
 * a bare `Int`, `label(profile.ingestYear)` was type-legal -- the one mistake
 * the work was done to prevent. Constructing this type is now the place that
 * says "this number names an academic year", so a wall-clock year cannot be
 * spoken as one by accident.
 *
 * Lives in `:common` rather than in one domain (RFC 170, D14). The money
 * layer's stored years are SMALLINT start years under the `academic_year`
 * SQL domain, so the row types in `:db` name this type too -- and a value type
 * copied into two modules is the duplication D13 refuses. A Common Data Set
 * cycle (`CdsCitation`), a College Scorecard vintage (`ScorecardVintage`) and a
 * stored `price_figures.academic_year` are different facts from different
 * sources that happen to share one way of being SAID, so neither domain owns
 * the other's wording -- and a change to one source's citation cannot silently
 * reword the other's years.
 *
 * The label exists so a bare year never reaches the model: a lone `2024` under a
 * bare key is a number with no measure attached, which is the whole complaint
 * the RFC 143 guard makes.
 *
 * Carries no source's CALENDAR rule. "Which award year is running now" is
 * Federal Student Aid's July-1 convention and belongs to the one feature that
 * asks the question (`FederalAidPolicyService`); a year type named by four
 * `:db` row types must not pull one publisher's fiscal calendar into every
 * module that only wants to say a year.
 */

@JvmInline
value class AcademicYear(
  /** The first calendar year of the academic year, e.g. 2022 for AY2022-23. */
  val firstCalendarYear: Int,
) : Comparable<AcademicYear> {
  init {
    // The same range the `academic_year` SQL domain states (RFC 170, D14),
    // held by the type as well: a loader builds one straight from a CSV cell,
    // and without this the bad year escapes the seed's located-defect contract
    // and surfaces as an anonymous insert failure a thousand rows later.
    require(firstCalendarYear in FIRST_YEAR..LAST_YEAR) {
      "[$firstCalendarYear] is not an academic year: the range is [$FIRST_YEAR..$LAST_YEAR]"
    }
  }

  /** Calendar order: an academic year is earlier exactly when its first calendar year is. */
  override fun compareTo(other: AcademicYear): Int = firstCalendarYear.compareTo(other.firstCalendarYear)

  /**
   * "2024-25" -- the spoken label, never a bare year.
   *
   * The NEXT calendar year is named by its last [TRAILING_DIGITS] digits,
   * zero padded. That width is stated ONCE and both used and padded from: a
   * modulus written beside a `%02d` format is the same bound written twice, and
   * changing one silently truncates against the other. The digits are taken
   * from the decimal text rather than by arithmetic, so no locale can reshape
   * them.
   */
  val label: String
    get() = "$firstCalendarYear-" + "${firstCalendarYear + 1}".padStart(TRAILING_DIGITS, '0').takeLast(TRAILING_DIGITS)

  companion object {
    /** The width of the trailing year in the label, and so also how much of it is said. */
    private const val TRAILING_DIGITS = 2

    /** The first and last years this type admits -- the `academic_year` SQL domain's own range. */
    const val FIRST_YEAR = 2000
    const val LAST_YEAR = 2100
  }
}
