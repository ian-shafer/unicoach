package ed.unicoach.db.models

import ed.unicoach.common.util.UsDate
import java.time.Month

/**
 * A cycle-relative CDS month/day (RFC 140). CDS reports application dates
 * without a year, so this is the raw reported pair and never a `LocalDate`.
 *
 * The month is always present; the [day] is the sparse best-effort part of the
 * corpus -- "applications close in March" with no day is real CDS reporting and
 * stays valid, storable data. A day WITHOUT a month is junk no render layer can
 * use, so it is unrepresentable here (and rejected by
 * `college_deadlines_day_requires_month_check`). "Not reported at all" is a
 * null [CdsMonthDay], not a pair of nulls.
 *
 * The pair must also be a REAL calendar date: `Feb 30` and `Sep 31` are exactly
 * what a mangled extraction produces, and a coach must never cite a date that
 * does not exist. Feb 29 IS accepted -- a CDS date carries no year, so it is
 * validated against a leap year.
 */
data class CdsMonthDay(
  val month: Int,
  val day: Int?,
) {
  init {
    require(isCalendarPair(month, day)) { "[$month]/[$day] is not a real calendar month/day" }
  }

  /**
   * This pair as a phrase, never a pair of bare numbers: "Nov 1", or
   * "Jan, day not reported" for the month-only case that is legal CDS reporting
   * and is NOT completed with a guessed day.
   *
   * It lives ON the pair rather than in one renderer because two surfaces now
   * say it -- `CollegeAdmissionsChatTool` and the admission-test timeline -- and
   * a service reaching into another feature's JSON adapter for a formatter is a
   * dependency pointing the wrong way.
   *
   * The SHAPE comes from [UsDate.monthDay] (RFC 227, brief 0010 D21); the WORDS
   * stay here. "day not reported" is a claim about the SOURCE's silence and is
   * this type's to make -- the seam renders a date and never a caveat. No year
   * is ever rendered, and [UsDate.monthDay] has no parameter that could carry
   * one: the pair carries none and none is invented (RFC 148).
   */
  val phrase: String
    get() {
      val monthName = UsDate.monthDay(Month.of(month), null)
      return day?.let { UsDate.monthDay(Month.of(month), it) } ?: "$monthName, day not reported"
    }

  companion object {
    /**
     * Whether [month] (1..12) and the optional [day] name a real calendar day.
     * The month length is taken in a LEAP year (`leapYear = true`): a CDS date
     * carries no year, so Feb 29 is a date the corpus can legitimately report.
     */
    fun isCalendarPair(
      month: Int,
      day: Int?,
    ): Boolean {
      if (month !in 1..12) return false
      return day == null || day in 1..Month.of(month).length(true)
    }
  }
}
