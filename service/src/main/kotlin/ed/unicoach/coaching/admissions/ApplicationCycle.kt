package ed.unicoach.coaching.admissions

import java.time.Month
import java.time.Year

/**
 * WHICH YEAR A SCHOOL'S UNDATED DEADLINE BELONGS TO (RFC 197 section 5).
 *
 * `college_deadlines` stores a month and a day and no year, because that is how
 * the Common Data Set reports them, and nothing else in the tree attaches one.
 * Deadline arithmetic cannot exist without a year, so this is the one place a
 * year is supplied -- and it is supplied from the student's OWN stored
 * graduation year, never from today's date: a student graduating in year G
 * applies in the academic year ending in G.
 *
 * A NAMED HOME rather than a private helper of the one service that needed it
 * first. The rule is a fact about admissions and not about the admission-test
 * timeline, a second reader of it is foreseeable, and a rule this load-bearing
 * that lives inside a `companion object` is a rule nobody finds.
 *
 * Nothing here renders. The resolved year is OURS and every surface that shows
 * it has to say so beside it; this object supplies the year, and the sentence
 * that attributes it is the renderer's.
 */
object ApplicationCycle {
  /**
   * The year a deadline closing in [closingMonth] falls in, for a student
   * graduating in [graduationYear]: August through December resolve to the
   * autumn BEFORE graduation, January through July to the graduation year
   * itself.
   *
   * BOTH ARGUMENTS ARE TYPED, and that is the point of this signature. The rule
   * used to take `(closingMonth: Int, graduationYear: Int)`, so a call with the
   * two arguments swapped type-checked and returned `month - 1` -- a deadline
   * resolved to the year 10, silently, with no exception anywhere. [Month] and
   * [Year] cannot be exchanged, and neither can be confused with a day. The
   * unwrapping to `int` happens exactly once, at the `LocalDate.of` that needs
   * it.
   */
  fun yearOf(
    closingMonth: Month,
    graduationYear: Year,
  ): Year =
    if (closingMonth >= FIRST_MONTH_OF_THE_APPLICATION_AUTUMN) {
      graduationYear.minusYears(1)
    } else {
      graduationYear
    }

  /** August: the first month that belongs to the autumn the student applies IN, rather than the spring they hear back in. */
  private val FIRST_MONTH_OF_THE_APPLICATION_AUTUMN: Month = Month.AUGUST
}
