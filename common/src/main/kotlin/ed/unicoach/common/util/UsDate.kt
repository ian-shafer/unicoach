package ed.unicoach.common.util

import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every date a human reads, in ONE shape: `Sep 12, 2026` (brief 0010 D21).
 *
 * It lives in `:common` for the same reason [phraseOf] beside it does: four
 * modules now say a date aloud -- the coach in `:service`, the admissions and
 * deadline copy in `:db`, the explainer pages in `:public-web`, the operator
 * tables in `:admin-web` -- and one copy per module is how the same date comes
 * to be written four ways, which is the defect this file exists to end.
 *
 * **It returns a DATE AND NOTHING ELSE.** No preposition, no caveat, no
 * provenance. "the day is not recorded" (OUR gap), "day not reported" (the
 * SOURCE's silence) and "this year is ours, worked out from the graduation
 * date" are CLAIMS about where a date came from, and they stay with the caller
 * that can justify them. A shared wording here would merge three different
 * statements into one, which would be a regression dressed as consistency.
 *
 * **Precision is carried by the TYPE, not by a nullable field**, so no caller
 * can render more than it holds:
 * - [monthDay] has NO year parameter, because it exists for a source that
 *   publishes a month and a day and no year (the Common Data Set), and a
 *   formatter that could accept a year is a formatter that will one day be
 *   handed a guessed one (RFC 148, kept by RFC 197).
 * - [of] over a [Year] has no month to leak, which is RFC 209's whole point
 *   about a year-precision stored date.
 *
 * `Locale.US` and not `Locale.ENGLISH`: the decision is "US-centric", and the
 * locale is the place that says so.
 */
object UsDate {
  /** `Sep 12, 2026` -- the full date. */
  fun of(date: LocalDate): String = FULL.format(date)

  /** `Sep 2026` -- the day is not known to the caller, and none is invented. */
  fun of(month: YearMonth): String = MONTH_AND_YEAR.format(month)

  /** `2026` -- only the year is known. There is no month here to render. */
  fun of(year: Year): String = year.value.toString()

  /**
   * `Nov 1`, or `Nov` when [day] is absent. NO YEAR, ever: this is the entry
   * point for a source that publishes a month and a day and no year, and it has
   * no parameter that could carry one.
   */
  fun monthDay(
    month: Month,
    day: Int?,
  ): String {
    val name = MONTH_ONLY.format(month)
    return if (day == null) name else "$name $day"
  }

  /**
   * `MMM d, yyyy`. The same pattern `admin-web` has rendered since RFC 118, so
   * this format is a precedent in this tree rather than an invention -- which is
   * why adopting it there changes no byte.
   */
  private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

  private val MONTH_AND_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

  private val MONTH_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)
}
