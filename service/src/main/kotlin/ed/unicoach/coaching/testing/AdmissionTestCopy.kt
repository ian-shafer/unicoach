package ed.unicoach.coaching.testing

import ed.unicoach.db.models.AdmissionTest

/**
 * How a family hears the two admission-test publishers spoken about (RFC 197,
 * brief 0010 D13/D15).
 *
 * THE ONE HOME FOR THE PUBLISHERS' WORDS on this surface, on the
 * `FigureStatusCopy` / `MoneySourceCopy` / `AssuranceTierCopy` precedent.
 * [AdmissionTest] is external identity -- a code list whose members are the
 * store's own slugs -- and English a family reads is a rendering decision, so it
 * lives here in `:service` beside the surface that speaks it and never on a
 * `:db` enum. A storage enum carrying sentences puts model-facing copy under a
 * layer where no renderer owns it and no rendering test reaches it.
 *
 * Every `when` here is exhaustive with NO `else`: a third admission test must
 * fail to compile at this seam rather than reach a family under the wrong
 * publisher's deadline hour. That matters more here than almost anywhere,
 * because the two publishers' labels are the facts a coach would otherwise
 * guess: they do not share a deadline TIMEZONE, and they do not mean the same
 * thing by "the late deadline".
 *
 * Every label below was read from that publisher's own page on 2026-09-11 -- the
 * `checked_on` of the committed calendar,
 * `db/seed/test-calendar/PROVENANCE.json`.
 */
object AdmissionTestCopy {
  /**
   * The hour this publisher's registration deadlines expire at, IN ITS OWN
   * WORDS.
   *
   * College Board: "SAT Weekend deadlines expire at 11:59 p.m. ET, U.S."
   * (`https://satsuite.collegeboard.org/sat/dates-deadlines`). ACT: "Deadlines
   * occur at 11:59 pm Central Time"
   * (`https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html`).
   *
   * They are GENUINELY different zones, one hour apart, so "the deadline time"
   * is not one fact the repo can hold once. The coach states dates and never
   * hours (brief 0010 D15), and never says a deadline is "today" without naming
   * whose clock that is -- which is what this label is for.
   */
  fun deadlineTimeLabelOf(test: AdmissionTest): String =
    when (test) {
      AdmissionTest.SAT -> "11:59 p.m. Eastern Time"
      AdmissionTest.ACT -> "11:59 p.m. Central Time"
    }

  /**
   * What this publisher's SECOND, later deadline actually covers, in its own
   * words. College Board publishes ONE combined "Changes, Regular Cancellation,
   * and Late Registration" deadline; ACT publishes a "Late Deadline" that is
   * late registration and nothing else. Calling both "the late deadline" would
   * overstate the SAT column and understate what a student may still change.
   */
  fun lateDeadlineLabelOf(test: AdmissionTest): String =
    when (test) {
      AdmissionTest.SAT -> "changes, regular cancellation and late registration"
      AdmissionTest.ACT -> "late registration"
    }

  /** Who publishes this test's calendar, named so a refusal can point at them by name. */
  fun publisherNameOf(test: AdmissionTest): String =
    when (test) {
      AdmissionTest.SAT -> "College Board"
      AdmissionTest.ACT -> "ACT"
    }

  /** The publisher's own registration page -- where a family goes to act on a date we hold. */
  fun registrationUrlOf(test: AdmissionTest): String =
    when (test) {
      AdmissionTest.SAT -> "https://satsuite.collegeboard.org/sat/registration"
      AdmissionTest.ACT -> "https://www.act.org/content/act/en/products-and-services/the-act/registration.html"
    }
}
