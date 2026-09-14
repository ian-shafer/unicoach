package ed.unicoach.db.models

import java.time.LocalDate
import java.time.Period
import java.util.UUID

/**
 * Surface id for an [AdmissionTestAdministration] row. Beside the model rather
 * than in its own file because `admission_test_administrations` is seeded
 * reference data with no id-keyed read path (the [CollegeDeadlineId] precedent).
 */
@JvmInline
value class AdmissionTestAdministrationId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * The day the publisher says scores reach the student, AND the page that was
 * read from -- one value, because neither half is data without the other.
 *
 * ONE NULLABLE VALUE and not two nullable columns' worth of Kotlin. The schema
 * already refuses a date with no citation and a citation with no date
 * (`admission_test_administrations_score_release_citation_check`); modelling
 * them as two independent nullables let the Kotlin side represent a state the
 * database rejects, so the only thing stopping an uncited date in memory was a
 * CHECK three layers away. Here it does not typecheck.
 *
 * Absent altogether -- `null` on the row -- is a real and current state: College
 * Board published score releases for fall 2026 only, so the three spring-2027
 * SAT sittings have none. It is never filled with a guess, and a reader must not
 * convert it into a verdict either way.
 */
data class ScoreRelease(
  val date: LocalDate,
  /** The publisher page [date] was read from. A fact with no citation is what this table exists to prevent. */
  val sourceUrl: String,
)

/**
 * One sitting of one admission test, from the `admission_test_administrations`
 * reference table (RFC 197): a test date, the registration deadlines that close
 * before it, and the day the publisher says scores arrive.
 *
 * Hand-typed from the publisher's own HTML table -- there is no machine-readable
 * feed for either calendar -- which is why every row carries its OWN provenance
 * and its OWN expiry instead of the table carrying one: [checkedOn] is the day a
 * human read the page named in [datesSourceUrl], and past [expiresOn] the coach
 * refuses to name a date at all.
 *
 * NO `FigureStatus` and no assurance tier. That vocabulary belongs to the
 * canonical MONEY tables, where a figure can be reported, suppressed by the
 * publisher, or absent, and a reader has to be told which. A test date is not a
 * money figure: it is either published -- in which case it is here, with the
 * page it was read from -- or it is not published, in which case the value is
 * NULL and the absence is reported as an absence. Borrowing the money
 * vocabulary would invite a reader to ask for the "status" of a date, which is
 * not a question this table can answer.
 *
 * The derived dates are deliberately absent too (CLAUDE.md): the fee-waiver lead
 * date and "the last sitting usable for this deadline" are computed at read time
 * and labelled as derived.
 */
data class AdmissionTestAdministration(
  override val id: AdmissionTestAdministrationId,
  /** Which test this sitting is of. The publisher's WORDS are coach-facing copy and live in `:service`. */
  val test: AdmissionTest,
  /** The academic year the publisher labels the calendar with, e.g. `2026-27`. Ours, and the schema checks its shape. */
  val cycle: String,
  val testDate: LocalDate,
  /** The day ordinary registration closes -- at an hour that is NOT the same for both publishers. */
  val registrationDeadline: LocalDate,
  /**
   * The publisher's later deadline, or null when it publishes none. What it
   * covers differs per publisher and is stated by that publisher's own copy,
   * never by this column's name.
   */
  val lateRegistrationDeadline: LocalDate?,
  /** The announced score release and its citation, or null when the publisher has announced none ([ScoreRelease]). */
  val scoreRelease: ScoreRelease?,
  /** The publisher page [testDate] and the deadlines were read from. */
  val datesSourceUrl: String,
  /** The day a human read these pages. */
  val checkedOn: LocalDate,
  /**
   * The day this row stops being believed. Past the earliest [expiresOn] of the
   * rows that are still LIVE the read refuses to name any date and points at the
   * publishers, and a committed row within [CALENDAR_REFRESH_LEAD] of it fails
   * the build (D7/D20).
   */
  val expiresOn: LocalDate,
) : Identifiable<AdmissionTestAdministrationId> {
  companion object {
    /**
     * How far ahead of a committed row's `expires_on` the build starts failing
     * (brief 0010 D20). Ninety days: by then both publishers have long since
     * firmed up the next cycle, so the failure is actionable on the day it fires
     * rather than a month later.
     *
     * ONE named home, referenced by the staleness guard, by the KDoc above and
     * by the DDL header comment -- the number used to be a bare `90L` in the
     * test and the words "90 days" in three prose blocks, so moving it would
     * have left the prose asserting a lead time nothing enforced. A [Period]
     * rather than a `Long`, so the unit is in the TYPE and no caller has to know
     * from the name whether it counts days or weeks.
     */
    val CALENDAR_REFRESH_LEAD: Period = Period.ofDays(90)
  }
}

/**
 * A row to write. Separate from [AdmissionTestAdministration] because the id is
 * the database's (`uuidv7()`), exactly as every other `New*` model in this
 * package works.
 */
data class NewAdmissionTestAdministration(
  val test: AdmissionTest,
  val cycle: String,
  val testDate: LocalDate,
  val registrationDeadline: LocalDate,
  val lateRegistrationDeadline: LocalDate?,
  val scoreRelease: ScoreRelease?,
  val datesSourceUrl: String,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)
