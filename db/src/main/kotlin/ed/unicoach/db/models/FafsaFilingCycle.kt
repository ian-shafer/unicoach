package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.Clock
import java.time.LocalDate
import java.time.Month

/**
 * The clock for `fafsa_filing_deadlines`: which FAFSA cycle a family starting
 * college next autumn is filling in RIGHT NOW (RFC 215, brief 0012 D11).
 *
 * The FAFSA for an award year opens in the OCTOBER BEFORE it, so from October
 * onwards the form on the table is next year's: in November 2026 a senior is
 * filing the 2027-28 FAFSA, and in February 2027 that same senior is still
 * filing it.
 *
 * IT IS NOT `FederalAidPolicyService.currentAwardYear()`, AND THE DIFFERENCE IS
 * NINE MONTHS. That function's rule is July 1, and it answers a different
 * question -- "which year's Pell table is in force", i.e. which year's money is
 * being PAID. Reusing it here would serve last cycle's deadlines from July to
 * the following June of every year: in November 2026 the aid being disbursed is
 * 2026-27 while the form families are filling in is the 2027-28 one, and a page
 * that printed 2026-27's closed deadlines to a senior would be wrong in the one
 * way this table exists to prevent. Two rules, two homes, neither derived from
 * the other.
 *
 * AND IT IS NOT "THE ONLY OPEN CYCLE" (Ian, at the RFC 215 gate). Two cycles are
 * legitimately open at once from October to June: in November 2026 a senior
 * files 2027-28 while a student already enrolled may still file 2026-27, and
 * both filings are real. This function names ONE cycle -- the one a family
 * starting next autumn is filing -- which is a question about the calendar and
 * NOT a claim that the other cycle is shut. `award_year` is the row's identity
 * precisely so the table can hold both, and no caller and no test here may
 * assume one.
 *
 * It lives in `:db` and not `:service` because the seed guard that enforces D11
 * is a `:db` test and cannot see `:service` -- [HandTypedReferenceData] is the
 * precedent for a rule-home in this package.
 */
object FafsaFilingCycle {
  /**
   * The first month of a cycle's filing window: the FAFSA for an award year
   * opens in the October before it. Stated once, so the boundary the tests
   * probe and the boundary the rule uses cannot drift apart.
   */
  val FILING_OPENS_IN: Month = Month.OCTOBER

  /**
   * The FIRST day a fact about [awardYear]'s cycle may fall on: 1 July of the
   * calendar year before it, the summer before the form opens.
   *
   * THE KOTLIN HOME OF A BOUND THE DATABASE ALSO STATES, and the pair is
   * deliberate rather than a copy nobody noticed. `fafsa_cycle_opens_on` in
   * `create-fafsa-filing-deadlines.sql` calls itself "one home for a bound two
   * CHECK constraints ask for", and it must stay in SQL because a CHECK
   * constraint can call nothing else. The generator that writes the seeds
   * carries a third statement of it in python, tied to the SQL by its harness.
   * What is NOT acceptable is a FOURTH copy hidden in one test class, which is
   * where this rule lived until RFC 223's review: `fafsa/03`'s service and page
   * need the same window to decide what to render, and a private helper in a
   * seed test is invisible to them.
   */
  fun windowOpens(awardYear: AcademicYear): LocalDate = LocalDate.of(awardYear.firstCalendarYear - 1, Month.JULY, 1)

  /**
   * The LAST day a fact about [awardYear]'s cycle may fall on: 31 December of
   * the calendar year after it.
   *
   * IT IS COARSE AND CANNOT BE TIGHT, which the DDL argues at length: two
   * cycles genuinely overlap in the calendar, because the 2026-27 corrections
   * deadline (11 September 2027) falls eleven months AFTER the 2027-28 form
   * opened. No pair of dates separates the two cycles, so a window that refused
   * every neighbouring cycle's date would refuse true rows.
   */
  fun windowCloses(awardYear: AcademicYear): LocalDate = LocalDate.of(awardYear.firstCalendarYear + 1, Month.DECEMBER, LAST_DAY_OF_DECEMBER)

  /**
   * The cycle being filed on [today]: October or later means NEXT year's award
   * year, otherwise this year's.
   *
   * [today] is a day and not an instant, so the ZONE is the caller's choice and
   * is made visible here rather than hidden in a `Clock`: at 21:00 Pacific on
   * 30 September a UTC clock has already turned October and answers the next
   * cycle. `fafsa/03` picks the zone its page and its coach answer in; this
   * function does not pick one for them.
   */
  fun currentFilingYear(today: LocalDate): AcademicYear = AcademicYear(if (today.month >= FILING_OPENS_IN) today.year + 1 else today.year)

  /** The cycle being filed at [clock]'s instant. */
  fun currentFilingYear(clock: Clock): AcademicYear = currentFilingYear(LocalDate.now(clock))

  /** 31 December, the far end of a cycle's window, named so the day is not a bare number. */
  private const val LAST_DAY_OF_DECEMBER = 31
}
