package ed.unicoach.db.models

import java.time.Period

/**
 * The rules that govern HAND-TYPED reference data -- the corpora a human reads
 * off a publisher's or an agency's own page, types into a committed file, and
 * ships as a generated seed migration (RFC 197's `db/seed/test-calendar/`, RFC
 * 208's `db/seed/test-state-programmes/`).
 *
 * There is exactly one rule here so far, and it is a NUMBER rather than a
 * behaviour, because the behaviour differs per corpus while the number must
 * not: each corpus writes its own staleness guard over its own notion of the
 * rows that are still live, and every one of them asks the same question --
 * how long before a reading expires does the build start failing.
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
}
