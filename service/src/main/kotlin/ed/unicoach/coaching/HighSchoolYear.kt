package ed.unicoach.coaching

import ed.unicoach.db.models.PartialDate
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.YearMonth

/**
 * The year of school a student is in today, derived from today's date and the
 * expected high-school graduation date we stored at signup (RFC 186). Never
 * stored: a stored grade is wrong on 1 July every year.
 *
 * It lives in the coaching module rather than beside [PartialDate] because
 * "the US high-school year turns over on 1 July" is a coaching convention, not
 * a property of partial dates -- the same argument
 * [ed.unicoach.coaching.aid.FederalAidPolicyService] makes for keeping Federal
 * Student Aid's award-year rule out of the shared `AcademicYear`. Two features
 * sharing a month boundary by coincidence is not a shared abstraction.
 *
 * The derivation reads the graduation YEAR only, so a date recorded with no
 * month still yields a year of school and the coach never has a month to
 * invent.
 */
enum class HighSchoolYear(
  /**
   * The year of school as a NOUN -- "grade 11 (junior year)" -- for a caller
   * that names it inside its own sentence.
   *
   * It is not itself a sentence, and two constants do not complete the one the
   * coach block says: neither "not yet in high school" nor "past their expected
   * high-school graduation" follows "their year of school today is". That
   * sentence is [clause], which every constant answers correctly.
   */
  val label: String,
  /**
   * The US grade this constant IS, or null for the two states that are not a
   * grade at all. It is what [of] resolves against, so the ladder is declared
   * once here instead of being re-listed as integer arms in the derivation.
   */
  val grade: Int? = null,
) {
  FRESHMAN("grade 9 (freshman year)", grade = 9),
  SOPHOMORE("grade 10 (sophomore year)", grade = 10),
  JUNIOR("grade 11 (junior year)", grade = 11),
  SENIOR("grade 12 (senior year)", grade = 12),

  /** Younger than grade 9 on the date we hold -- named, never "grade 8". */
  BEFORE_HIGH_SCHOOL("not yet in high school") {
    override val clause: String = "they are not yet in high school."
  },

  /**
   * The stored graduation date is behind the academic year now running, so on
   * our record this person is no longer a high-school student. This is what
   * makes the scope refusal (brief 0010 D3) answerable from data rather than
   * from the model's guess.
   */
  PAST_GRADUATION("past their expected high-school graduation") {
    override val clause: String = "that date has passed, so on our record they are no longer a high-school student."
  },
  ;

  /**
   * What the coach is told after the graduation date, as a sentence tail.
   *
   * It lives on the constant rather than at the call site because the two
   * out-of-range constants do not fit the sentence the four grades do: a caller
   * holding a `when` or an `if` over them is a caller a later constant can be
   * added behind, and "their year of school today is not yet in high school" is
   * what that costs.
   */
  open val clause: String get() = "their year of school today is $label."

  companion object {
    /**
     * The grade a US student graduates from: the fixed point the derivation
     * counts back from, read off [SENIOR] rather than retyped -- a companion
     * constant cannot be used in a constant's own constructor call (the
     * companion is not initialized yet), and two literal 12s that must agree is
     * exactly what this names.
     */
    val GRADUATION_GRADE: Int = requireNotNull(SENIOR.grade)

    /** The ladder, read off the constants themselves so it is declared in exactly one place. */
    private val BY_GRADE: Map<Int, HighSchoolYear> = entries.mapNotNull { year -> year.grade?.let { it to year } }.toMap()

    /**
     * The academic year running on [today], named by the calendar year it ENDS
     * in: the school year turns over on 1 July, so July through December belong
     * to the year ending next calendar year.
     *
     * A [Year] and not a bare `Int`, so it cannot be subtracted from a grade by
     * accident: the one place the two meet is [of], which unwraps both sides
     * into a named count of years.
     */
    internal fun academicYearEnding(today: LocalDate): Year =
      if (today.month >= Month.JULY) Year.of(today.year + 1) else Year.of(today.year)

    /**
     * Whether the recorded date is strictly behind [today], at the precision it
     * was recorded. A date recorded for TODAY has not passed -- graduating this
     * morning still makes you a senior this afternoon -- and a year-only date
     * says nothing either way, which is the honest answer for a record that
     * holds no month.
     */
    private fun hasPassed(
      today: LocalDate,
      graduation: PartialDate,
    ): Boolean =
      when (graduation) {
        is PartialDate.FullDate -> graduation.date.isBefore(today)
        is PartialDate.YearAndMonth -> YearMonth.of(graduation.year.value, graduation.month).atEndOfMonth().isBefore(today)
        is PartialDate.YearOnly -> false
      }

    /**
     * Grade 12 is the year the student graduates in, so each year the
     * graduation year runs ahead of the academic year now ending costs one
     * grade. Out of range is a named state, never grade 8 or grade 13.
     */
    fun of(
      today: LocalDate,
      graduation: PartialDate,
    ): HighSchoolYear {
      // A date we hold to the day (or to the month) and that is already behind
      // us settles the question on its own, before the year rule gets to it:
      // otherwise a student who finished on 5 June is called a senior until the
      // following 1 July, in the same sentence that says the date has arrived.
      // A year-only date cannot answer this and defers to the year rule, which
      // is what keeps a month-less date answerable at all.
      if (hasPassed(today, graduation)) return PAST_GRADUATION
      val yearsAhead = graduation.year.value - academicYearEnding(today).value
      val grade = GRADUATION_GRADE - yearsAhead
      return BY_GRADE[grade] ?: if (grade > GRADUATION_GRADE) PAST_GRADUATION else BEFORE_HIGH_SCHOOL
    }
  }
}
