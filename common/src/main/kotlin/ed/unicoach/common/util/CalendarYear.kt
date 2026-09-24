package ed.unicoach.common.util

/**
 * One CALENDAR year -- the year a calendar names, not a year of school.
 *
 * A TYPE rather than a bare `Int` for the reason [AcademicYear] is one: the two
 * facts share a representation and nothing else. While a publisher's dollar
 * base was an `Int`, `AcademicYear(provenance.inflationBaseYear!!)` type-checked
 * -- stamping a figure's dollar base onto a cohort was legal Kotlin, which is
 * the one mistake [AcademicYear] exists to prevent. Constructing this type is
 * the place that says "this number names a calendar year".
 *
 * Carries no label, deliberately: a calendar year IS said as its own four
 * digits, so there is nothing to format. [AcademicYear.label] exists because
 * "2024-25" cannot be read off `2024`; this type has no such gap to close, and
 * a label here would only invite a reader to say a calendar year as a school
 * one.
 *
 * Lives in `:common` beside [AcademicYear], for the same reason (RFC 170 D14):
 * the publisher registries in `:college` name it and the sections in
 * `:service` serve it, and a value type copied into two modules is duplication.
 *
 * Not `Comparable`, for the reason it carries no label: nothing orders a dollar
 * base. The first caller that must order one adds the interface along with the
 * order it needs, and until then an unused capability is a promise with no test
 * behind it.
 *
 * RANGE-GUARDED, like [AcademicYear] and for the same reason: a publisher's
 * dollar base is transcribed out of a workbook cell or a CSV column, so a type
 * that only stops the year being MISNAMED still lets `CalendarYear(0)` be
 * spoken as "in 0 dollars". The bound is this type's own rather than the
 * `academic_year` SQL domain's, because no column stores one of these and a
 * publisher may legitimately state a dollar base older than that domain's first
 * year -- so the low end is set before any dollar base we could cite and above
 * every value that is a sentinel, a sign slip or a two-digit year.
 */
@JvmInline
value class CalendarYear(
  /** The year itself, e.g. 2024 for CY2024. */
  val year: Int,
) {
  init {
    require(year in FIRST_YEAR..LAST_YEAR) {
      "[$year] is not a calendar year a publisher states: the range is [$FIRST_YEAR..$LAST_YEAR]"
    }
  }

  companion object {
    /**
     * The years this type admits. The first is earlier than any dollar base a
     * federal publisher we read states, and the last is [AcademicYear.LAST_YEAR]
     * -- neither kind of year may name a year past the century that domain
     * admits.
     */
    const val FIRST_YEAR = 1900
    const val LAST_YEAR = AcademicYear.LAST_YEAR
  }
}
