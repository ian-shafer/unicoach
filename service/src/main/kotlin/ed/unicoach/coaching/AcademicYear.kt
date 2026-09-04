package ed.unicoach.coaching

import java.time.Clock
import java.time.LocalDate
import java.time.Month

/**
 * One academic year, named by its first calendar year, and the ONE home for its
 * spoken label: 2024 -> "2024-25", 2099 -> "2099-00".
 *
 * A TYPE rather than a formatter over a bare `Int`, because a calendar year and
 * an academic year are different facts that share a representation. RFC 149
 * D-E's whole point is that an ingest year
 * ([ed.unicoach.coaching.costs.CollegeCostProfile.ingestYear], a wall-clock
 * `colleges.updated_at` year) is NOT a vintage; while the label took a bare
 * `Int`, `label(profile.ingestYear)` was type-legal -- the one mistake the work
 * was done to prevent. Constructing this type is now the place that says "this
 * number names an academic year", so a wall-clock year cannot be spoken as one
 * by accident.
 *
 * Domain-agnostic on purpose. A Common Data Set cycle
 * ([ed.unicoach.coaching.admissions.CdsCitation]) and a College Scorecard
 * vintage ([ed.unicoach.coaching.costs.ScorecardVintage]) are different facts
 * from different sources that happen to share one way of being SAID, so neither
 * domain owns the other's wording -- and a change to one source's citation
 * cannot silently reword the other's years.
 *
 * The label exists so a bare year never reaches the model: a lone `2024` under a
 * bare key is a number with no measure attached, which is the whole complaint
 * the RFC 143 guard makes.
 */
@JvmInline
value class AcademicYear(
  /** The first calendar year of the academic year, e.g. 2022 for AY2022-23. */
  val firstCalendarYear: Int,
) : Comparable<AcademicYear> {
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

    /**
     * The federal award year running at [clock]'s instant: award years run
     * July 1 to June 30, so before July the running year started last
     * calendar year. The one home for the July 1 rule (RFC 159), so every
     * award-year consumer derives "current" the same way.
     */
    fun currentFederalAwardYear(clock: Clock): AcademicYear {
      val today = LocalDate.now(clock)
      val startYear = if (today.month >= Month.JULY) today.year else today.year - 1
      return AcademicYear(startYear)
    }
  }
}
