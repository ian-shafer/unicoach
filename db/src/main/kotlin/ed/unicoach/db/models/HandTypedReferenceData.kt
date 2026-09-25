package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.Period

/**
 * The rules that govern HAND-TYPED reference data -- the corpora a human reads
 * off a publisher's or an agency's own page and types into a committed file
 * under `db/seed/` (RFC 197's `db/seed/test-calendar/`, RFC 208's
 * `db/seed/test-state-programmes/`, RFC 239's `db/seed/automatic-awards/`).
 *
 * HOW SUCH A CORPUS REACHES THE DATABASE IS NOT ONE ANSWER, and this sentence
 * used to say it was. The first two ship as a GENERATED SEED MIGRATION, which
 * works because each is keyed by a state or by nothing, so a migration-time
 * insert has everything it needs. RFC 239's scholarship grids are keyed by
 * `colleges(id)`, and `colleges` is filled by the IMPORT RUN -- so a
 * migration-time seed would point at an empty table, and that corpus is loaded
 * by `bin/ingest-colleges` instead. What every one of them shares is what this
 * object is for: a committed file reviewable in a diff, a per-row `expires_on`,
 * and a build that fails before the reading goes stale.
 *
 * The rules here are NUMBERS rather than behaviours, because the behaviour
 * differs per corpus while the number must not: each corpus writes its own
 * staleness guard over its own notion of the rows that are still live, and every
 * one of them asks the same two questions -- how long before a reading expires
 * does the build start failing, and (for a corpus keyed to an admissions CYCLE)
 * which day of the year that expiry falls on.
 *
 * An `object` and not an interface: nothing implements this. It is the one home
 * for a constant that was about to be typed twice under two names.
 */
object HandTypedReferenceData {
  /**
   * How far ahead of a committed row's `expires_on` the build starts failing
   * (brief 0010 D20). Ninety days: by then a publisher has long since firmed up
   * the next cycle and a state agency has published the next school year's
   * programme, so the failure is actionable on the day it fires rather than a
   * month later.
   *
   * ONE named home, referenced by every hand-typed corpus's staleness guard, by
   * the row types' own KDoc and by the DDL header comments -- the number used to
   * be a bare `90L` in a test and the words "90 days" in three prose blocks, so
   * moving it would have left the prose asserting a lead time nothing enforced.
   * It was then `AdmissionTestAdministration.CALENDAR_REFRESH_LEAD`, which was
   * the same mistake one step later: RFC 208 added a SECOND hand-typed corpus
   * with the same ninety days, and a constant named for the calendar cannot be
   * the home of a rule about state programmes, so copying it would have put one
   * rule in two places under two names.
   *
   * A [Period] rather than a `Long`, so the unit is in the TYPE and no caller
   * has to know from the name whether it counts days or weeks.
   */
  val REFRESH_LEAD: Period = Period.ofDays(90)

  /**
   * The day a hand-typed CYCLE corpus stops being believed: July 31 of the entry
   * year it is keyed to (RFC 239 D-G).
   *
   * ONE HOME, beside [REFRESH_LEAD] for exactly [REFRESH_LEAD]'s reason. This
   * decides EVERY committed row's `expires_on` in `db/seed/automatic-awards/`,
   * and it was a bare `7, 31` typed at each site that stated it -- the guard that
   * asserts the corpus, and the re-read hypothesis that builds one -- so the two
   * could drift with nothing to notice. These pages are re-keyed in the spring
   * and summer, so a cycle's reading stops being the answer once the next cycle's
   * applicants are reading it.
   *
   * A [MonthDay], so the rule is the day of the YEAR it actually is and a caller
   * cannot hold one entry year's calendar date as though it were the rule.
   */
  val CYCLE_EXPIRY: MonthDay = MonthDay.of(Month.JULY, 31)

  /** [CYCLE_EXPIRY] on the calendar of one entry year. */
  fun cycleExpiryFor(entryYear: AcademicYear): LocalDate = CYCLE_EXPIRY.atYear(entryYear.firstCalendarYear)
}
